package com.xincode.app

import android.util.Log
import com.xincode.core.Tool
import com.xincode.core.ToolRegistry
import com.xincode.core.ToolResult
import com.xincode.provider.ToolCall
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Hermes-④ `execute_code` —— 零上下文成本的工具-RPC 管线。
 *
 * 模型写一段 JavaScript,脚本里像调普通函数一样调工具(web_search/file_read/grep/…),
 * **只有脚本 print() 的输出**回到模型上下文——把 N 次工具调用压成 1 个模型轮次。
 *
 * 与 Hermes 的差异:XINCODE 同进程,无需 socket——桥接对象 [Bridge] 直接把工具名回落到
 * 唯一派发点 [ToolRegistry.execute](与模型正常工具调用同一入口)。
 *
 * 安全:
 *  - 白名单 [SANDBOX_TOOLS] 只含**只读/安全**工具(不含 file_write/shell_exec/su_exec),
 *    因为脚本内的工具调用绕过了 AgentCore 的安全闸门。
 *  - 工具调用数上限 [MAX_TOOL_CALLS]、指令数看门狗(防死循环)、stdout 上限 [MAX_STDOUT]。
 *  - 用 Rhino 解释模式(optimizationLevel=-1),不生成 JVM 字节码,兼容 Android。
 */
class CodeExecTool(private val toolRegistry: ToolRegistry) : Tool {

    companion object {
        private const val TAG = "CodeExecTool"
        private const val MAX_TOOL_CALLS = 50
        private const val MAX_STDOUT = 50_000
        private const val INSTRUCTION_BUDGET = 20_000_000
        // 只读/安全工具白名单(与 Hermes SANDBOX_ALLOWED_TOOLS 精神一致)。
        private val SANDBOX_TOOLS = setOf(
            "web_search", "web_fetch", "file_read", "list_dir", "grep", "glob", "recall_memory",
            "current_time"
        )

        /**
         * 脚本专用调度器(闪退修复,关键)。
         *
         * 原先跑在 [Dispatchers.Default]:该池大小 = CPU 核数(手机常见 4~8)。而 [Bridge.call] 用
         * runBlocking **阻塞住当前线程**去等工具完成——web_fetch/web_search 是网络请求(可达 15~30s)。
         * 脚本连调几次、或多个会话并发跑脚本,就会把 Default 池全部占满并互相等待 → 整个 App 的
         * CPU 协程停摆 → ANR → 被系统杀掉(用户看到的"闪退")。
         *
         * 改用独立线程池后,阻塞只发生在这些自有线程上,不再拖垮 Default 池。
         * 线程数设上限,避免脚本并发无限开线程;Rhino Context 绑定线程,单脚本始终在同一线程内执行。
         */
        private val scriptExecutor: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newFixedThreadPool(3) { r ->
                Thread(r, "xincode-codeexec").apply { isDaemon = true }
            }
        private val scriptDispatcher = scriptExecutor.asCoroutineDispatcher()
    }

    override val name = "execute_code"
    override val description =
        "执行一段 JavaScript 脚本来编排多步工具调用。脚本里可直接调用这些函数(参数为对象,返回值为工具输出字符串):" +
        SANDBOX_TOOLS.joinToString(", ") + "。用 print(x) 输出——【只有 print 的内容】会回到你这里,中间工具结果不占上下文。" +
        "适合:批量检索/读多个文件后汇总。不支持写文件/执行 shell(安全)。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("code", JSONObject().apply {
                put("type", "string")
                // 语言写在描述里而不是给一个 language 参数:给了参数,模型就会以为可以填 python。
                put("description", "要执行的 JavaScript(ES5)源码——【只支持 JavaScript,不支持 Python】。用 print(...) 输出结果。")
            })
        })
        put("required", JSONArray(listOf("code")))
    }

    /** JS ↔ Kotlin 桥:call() 回落到工具派发,print() 收集输出。 */
    inner class Bridge {
        val out = StringBuilder()
        var toolCalls = 0
        fun print(v: Any?) {
            if (out.length < MAX_STDOUT) out.append(v?.toString() ?: "null").append("\n")
        }
        fun call(toolName: String, argsJson: String): String {
            if (toolName !in SANDBOX_TOOLS) return "ERROR: 工具 '$toolName' 不在脚本沙箱白名单内"
            if (++toolCalls > MAX_TOOL_CALLS) throw RuntimeException("超出脚本工具调用上限 ($MAX_TOOL_CALLS)")
            return try {
                runBlocking {
                    when (val r = toolRegistry.execute(ToolCall(id = "codeexec_$toolCalls", name = toolName, arguments = argsJson))) {
                        is ToolResult.Success -> r.output
                        is ToolResult.Error -> "ERROR: ${r.message}"
                    }
                }
            } catch (t: Throwable) {
                // 捕到 Throwable 而非 Exception:工具内部可能抛 OutOfMemoryError(如 Jsoup 解析超大页面)
                // 等 Error,漏掉会直接杀死进程(闪退)。这里转成脚本可见的错误字符串,让脚本继续/收尾。
                "ERROR: ${t::class.java.simpleName}: ${t.message}"
            }
        }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(scriptDispatcher) {
        // 模型常把 code 写成 script/source,漏掉时先从别名里捞一把再报错。
        val code = params["code"].takeUnless { it.isNullOrBlank() }
            ?: params["script"].takeUnless { it.isNullOrBlank() }
            ?: params["source"].takeUnless { it.isNullOrBlank() }
            ?: ""
        if (code.isBlank()) return@withContext ToolResult.Error("execute_code: code 不能为空(传 JavaScript 源码字符串)")

        // 实测模型会传 {"language":"python"} 然后写一段 Python —— 这里必须【明确拒绝】。
        // 让 Rhino 去解析 Python 只会吐一句语法错误,模型看不出是语言选错了,会原样重试到被刹车。
        val lang = (params["language"] ?: params["lang"]).orEmpty().trim().lowercase()
        if (lang.isNotEmpty() && lang !in setOf("javascript", "js", "ecmascript", "node")) {
            return@withContext ToolResult.Error(
                "execute_code 只能执行 JavaScript(ES5),不支持 $lang。" +
                    "要跑 Python 请改用 shell_exec/env_exec(需要环境里已装 python)。"
            )
        }

        val bridge = Bridge()
        val factory = object : ContextFactory() {
            override fun makeContext(): Context {
                val cx = super.makeContext()
                cx.optimizationLevel = -1 // 解释模式:Android 不能生成字节码
                cx.instructionObserverThreshold = 100_000
                return cx
            }
            private var instructions = 0
            override fun observeInstructionCount(cx: Context?, count: Int) {
                instructions += count
                if (instructions > INSTRUCTION_BUDGET) throw RuntimeException("脚本指令数超限(疑似死循环)")
            }
        }

        try {
            val cx = factory.enterContext()
            try {
                // initSafeStandardObjects 而不是 initStandardObjects:后者会装上 Packages/java/
                // JavaAdapter 这套「从 JS 直接反射 Java」的入口——脚本能借它绕开下面的白名单,
                // 拿到任意类。Safe 版只留 ES 标准内置对象(JSON/Math/Date/RegExp 都还在)。
                val scope = cx.initSafeStandardObjects()
                installBridge(scope, bridge)
                cx.evaluateString(scope, buildPreamble() + "\n" + code, "userscript", 1, null)
            } finally {
                // exit() 自身在异常路径下可能抛 IllegalStateException,吞掉以免掩盖真正的错误。
                try { Context.exit() } catch (_: Throwable) {}
            }
            val stdout = bridge.out.toString().let {
                if (it.length >= MAX_STDOUT) it.take(MAX_STDOUT) + "\n...(输出截断)" else it
            }
            ToolResult.Success(stdout.ifBlank { "(脚本无 print 输出)" } + "\n[工具调用 ${bridge.toolCalls} 次]")
        } catch (t: Throwable) {
            // 捕 Throwable 而非 Exception:Rhino 解释模式执行深递归/复杂脚本会抛 StackOverflowError,
            // 大结果拼接会抛 OutOfMemoryError——都是 Error,不接住就是整个 App 闪退。
            Log.w(TAG, "execute_code failed: ${t::class.java.simpleName}: ${t.message}")
            val partial = bridge.out.toString().take(2000)
            ToolResult.Error(
                "execute_code 运行出错(${t::class.java.simpleName}): ${t.message}" +
                    if (partial.isNotBlank()) "\n已产出:\n$partial" else ""
            )
        }
    }

    /**
     * 把 [Bridge] 的两个方法作为**原生 Rhino 函数**装进作用域。
     *
     * 【为什么不用 Context.javaToJS(bridge, scope)】那条路会走 `JavaMembers` 去反射 Bridge 类,
     * 而 Rhino 1.7.14 的 `JavaMembers` 静态初始化块里调了 `javax.lang.model.SourceVersion`
     * (它拿来判断 JDK 9+ 的严格反射)。Android 运行时【根本没有 javax.lang.model 这个包】,
     * 于是 <clinit> 直接抛 NoClassDefFoundError —— 注意这是 Error 不是 Exception,而且它发生在
     * 脚本跑起来之前。结果就是 execute_code 在真机上【从来没成功过一次】,每次都是
     * 「execute_code 运行出错(NoClassDefFoundError): Failed resolution of:
     * Ljavax/lang/model/SourceVersion;」,而在 JVM 单测里反而是好的,所以一直没被发现。
     *
     * 换成 BaseFunction 后全程走 Rhino 自己的对象模型,一次反射都不做,也就碰不到那个类。
     */
    private fun installBridge(scope: Scriptable, bridge: Bridge) {
        val printFn = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
                bridge.print(args.firstOrNull()?.let { Context.toString(it) })
                return Undefined.instance
            }
        }
        ScriptableObject.putProperty(scope, "__print", printFn)
        ScriptableObject.putProperty(scope, "__call", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any {
                val toolName = args.getOrNull(0)?.let { Context.toString(it) }.orEmpty()
                val argsJson = args.getOrNull(1)?.let { Context.toString(it) } ?: "{}"
                return bridge.call(toolName, argsJson)
            }
        })
    }

    /** 为每个白名单工具生成 JS 包装函数 + print。 */
    private fun buildPreamble(): String = buildString {
        append("var print = function(x){ __print(x); };\n")
        append("var console = { log: print };\n")
        for (t in SANDBOX_TOOLS) {
            append("function ").append(t).append("(args){ return __call('").append(t)
            append("', JSON.stringify(args || {})); }\n")
        }
    }
}
