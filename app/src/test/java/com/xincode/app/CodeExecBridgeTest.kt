package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolRegistry
import com.xincode.core.ToolResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `execute_code` 的脚本桥。
 *
 * 【这组测试挡的是什么】桥原本用 `Context.javaToJS(bridge, scope)` 把 Kotlin 对象反射进 JS。
 * 那条路会触发 Rhino 1.7.14 的 `JavaMembers` 静态初始化,而它引用了 `javax.lang.model.SourceVersion`
 * —— Android 运行时【没有这个包】,于是真机上每次调用都抛 NoClassDefFoundError,
 * execute_code 从来没成功过一次。偏偏在 JVM 单测里 javax.lang.model 是存在的,所以这个 bug
 * 用单测【测不出来】,只能靠改成不做任何 Java 反射的原生 Rhino 函数来根除。
 *
 * 所以下面测的不是「Android 上不崩」(单测证明不了),而是换掉反射之后 print / 工具回调 /
 * JSON 这些能力一个都没丢 —— 别为了绕开崩溃把功能改瘸了。
 */
class CodeExecBridgeTest {

    private class EchoTool(override val name: String) : Tool {
        override val description = ""
        override val parametersSchema: JSONObject = JSONObject()
        override suspend fun execute(params: Map<String, String>): ToolResult =
            ToolResult.Success("echo:${params["query"].orEmpty()}")
    }

    private fun toolOf(vararg names: String) = CodeExecTool(
        ToolRegistry().apply { names.forEach { register(EchoTool(it)) } }
    )

    private fun run(args: Map<String, String>): ToolResult =
        runBlocking { toolOf("web_search").execute(args) }

    @Test
    fun printReachesOutput() {
        val r = run(mapOf("code" to "print('hello'); print(1 + 2);"))
        assertTrue(r is ToolResult.Success)
        val out = (r as ToolResult.Success).output
        assertTrue(out, out.contains("hello"))
        assertTrue(out, out.contains("3"))
    }

    @Test
    fun scriptCanCallWhitelistedTool() {
        val r = run(mapOf("code" to "print(web_search({query: 'kotlin'}));"))
        assertTrue(r is ToolResult.Success)
        val out = (r as ToolResult.Success).output
        assertTrue(out, out.contains("echo:kotlin"))
        assertTrue(out, out.contains("[工具调用 1 次]"))
    }

    @Test
    fun jsonBuiltinStillAvailableAfterSafeInit() {
        // initSafeStandardObjects 砍掉的是 Packages/java 这套反射入口,ES 标准内置对象必须还在——
        // 工具包装函数本身就靠 JSON.stringify 传参,JSON 没了整个桥就废了。
        val r = run(mapOf("code" to "print(JSON.stringify({a: 1})); print(Math.max(2, 5));"))
        assertTrue(r is ToolResult.Success)
        val out = (r as ToolResult.Success).output
        assertTrue(out, out.contains("{\"a\":1}"))
        assertTrue(out, out.contains("5"))
    }

    @Test
    fun javaReflectionEntryPointsAreGone() {
        // 脚本不该还能从 JS 里摸到任意 Java 类。
        val r = run(mapOf("code" to "print(typeof Packages); print(typeof java);"))
        assertTrue(r is ToolResult.Success)
        val out = (r as ToolResult.Success).output
        assertTrue(out, !out.contains("object"))
    }

    @Test
    fun nonJavaScriptLanguageIsRejectedClearly() {
        // 实测模型会传 language=python 再写一段 Python。必须明说是语言选错了,
        // 否则它只看到一句语法错误,会原样重试到被防空转刹车掐掉。
        val r = run(mapOf("language" to "python", "code" to "print('hi')"))
        assertTrue(r is ToolResult.Error)
        val msg = (r as ToolResult.Error).message
        assertTrue(msg, msg.contains("只能执行 JavaScript"))
    }

    @Test
    fun javaScriptLanguageHintIsAccepted() {
        val r = run(mapOf("language" to "javascript", "code" to "print('ok')"))
        assertTrue(r is ToolResult.Success)
    }

    @Test
    fun codeAliasesAreAccepted() {
        // 模型常把参数名写成 script/source。
        val r = run(mapOf("script" to "print('aliased')"))
        assertTrue(r is ToolResult.Success)
        assertTrue((r as ToolResult.Success).output.contains("aliased"))
    }

    @Test
    fun nonWhitelistedToolIsRefusedInsideScript() {
        val tool = CodeExecTool(ToolRegistry().apply { register(EchoTool("shell_exec")) })
        val r = runBlocking { tool.execute(mapOf("code" to "print(typeof shell_exec);")) }
        assertTrue(r is ToolResult.Success)
        // 白名单外的工具连包装函数都不会生成
        assertTrue((r as ToolResult.Success).output.contains("undefined"))
    }
}
