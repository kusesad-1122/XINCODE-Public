package com.xincode.app

import android.util.Log
import com.xincode.core.AgentCore
import com.xincode.core.Tool
import com.xincode.core.ToolRegistry
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import com.xincode.data.SubAgentEntity
import com.xincode.provider.OpenAiClient
import com.xincode.security.SecurityGate
import com.xincode.security.ToolConfirmResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 子智能体调度(主脑 → 专职子智能体)。
 *
 * `dispatch_agents` 接收一组 {agent, task} 分配:把一个大任务【同时】拆给不同类型的子智能体并行处理,
 * 各自跑在**隔离** AgentCore 上,用**各自专属的技能与工具**;全部完成后把各自结论汇总回主脑。
 *
 * 关键:每个子智能体只被注入【它这一类型的专属技能】,让它据任务从自己这套里选(而非盲目从全量技能挑);
 * 工具也只给它类型白名单里的那些。
 */
class SubAgentTool(
    private val mainRegistry: ToolRegistry,
    private val openAiClient: OpenAiClient,
    private val database: AppDatabase,
    private val securityGate: SecurityGate?,
    private val scene: SubAgentSceneState? = null   // 「指挥室」像素动画状态(可空)
) : Tool {

    companion object {
        private const val TAG = "SubAgentTool"
        private const val MAX_CONCURRENT = 4
        // 网络研究常常需要多轮抓取,3 分钟太短会"搜到超时";放宽到 10 分钟。
        private const val TASK_TIMEOUT_MS = 10 * 60 * 1000L
        private val READONLY_DEFAULT = listOf("file_read", "list_dir", "grep", "glob")
        // 无论子智能体类型如何,都【始终】赋予联网工具——否则研究/探索类会退化成只翻本地文件、直到超时。
        private val ALWAYS_GRANTED = listOf("web_search", "web_fetch", "invoke_skill")
        // 子智能体后台自主运行时自动放行的【安全】工具(只读/联网/技能)。其余需确认的一律拒绝。
        private val SAFE_AUTO_ALLOW = (ALWAYS_GRANTED + READONLY_DEFAULT).toSet()
    }

    override val name = "dispatch_agents"
    override val description =
        "把一个大任务【同时】拆给多个专职子智能体并行处理,各用各的专属技能/工具,最后汇总结论回你(主脑)。" +
        "先用 list_sub_agents 或看下方清单了解有哪些子智能体类型。传 assignments=[{agent:\"类型名\", task:\"这个子智能体要做的事\"}...]。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("assignments", JSONObject().apply {
                put("type", "array")
                put("description", "分配数组,每项 {agent:子智能体类型名, task:要它做的事}")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("agent", JSONObject().put("type", "string"))
                        put("task", JSONObject().put("type", "string"))
                    })
                    put("required", JSONArray(listOf("agent", "task")))
                })
            })
        })
        put("required", JSONArray(listOf("assignments")))
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        // 兼容:executeJson 未命中时从压平的字符串解析。
        val raw = params["assignments"] ?: return ToolResult.Error("缺少 assignments")
        return runAssignments(try { JSONArray(raw) } catch (e: Exception) { return ToolResult.Error("assignments 不是合法 JSON 数组") })
    }

    override suspend fun executeJson(args: JSONObject): ToolResult {
        val arr = coerceAssignments(args) ?: return ToolResult.Error(
            "dispatch_agents 需要 assignments 数组，形如 " +
                "assignments=[{\"agent\":\"子智能体类型名\",\"task\":\"要它做的事\"}]。" +
                "你这次传的是: ${args.toString().take(200)}"
        )
        return runAssignments(arr)
    }

    /**
     * 把模型给的各种写法都收敛成 assignments 数组。
     *
     * 【为什么要这么宽容】原来只认 `optJSONArray("assignments")`，别的形状一律回一句
     * 「缺少 assignments 数组」——这句话对模型没有信息量（它以为自己传了），于是原样重试，
     * 连错三次被防空转刹车掐掉，整轮分派作废。而实际上这几种写法的意图都毫无歧义：
     *  - 只派一个人时写成对象 `{agent, task}` 而不是只有一项的数组
     *  - 键名写成 tasks / agents（描述里两种词都出现过，模型记混很正常）
     *  - 整个数组被序列化成字符串 `"[{...}]"`（部分供应商的函数调用会这样吐）
     */
    private fun coerceAssignments(args: JSONObject): JSONArray? {
        for (key in listOf("assignments", "tasks", "agents", "assignment")) {
            args.optJSONArray(key)?.let { return it }
            args.optJSONObject(key)?.let { return JSONArray().put(it) }
            val s = args.optString(key, "").trim()
            if (s.startsWith("[")) runCatching { JSONArray(s) }.getOrNull()?.let { return it }
            if (s.startsWith("{")) runCatching { JSONObject(s) }.getOrNull()?.let { return JSONArray().put(it) }
        }
        // 顶层就直接是 {agent, task}(一个人的时候模型很爱这么写)
        if (args.optString("agent").isNotBlank() && args.optString("task").isNotBlank()) {
            return JSONArray().put(args)
        }
        return null
    }

    private suspend fun runAssignments(arr: JSONArray): ToolResult {
        if (arr.length() == 0) return ToolResult.Error("assignments 为空")
        val items = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { it.optString("agent") to it.optString("task") }
        }.filter { it.first.isNotBlank() && it.second.isNotBlank() }
        if (items.isEmpty()) return ToolResult.Error("assignments 无有效项(需 agent + task)")

        scene?.begin(items) // 「指挥室」动画:登记这场分配

        val sem = java.util.concurrent.Semaphore(MAX_CONCURRENT)
        val out = JSONArray()
        coroutineScope {
            val jobs = items.mapIndexed { idx, (agentName, task) ->
                async(Dispatchers.IO) {
                    sem.acquire()
                    try { runOne(idx, agentName, task) }
                    finally { sem.release() }
                }
            }
            jobs.forEach { out.put(it.await()) }
        }
        scene?.finishAll()
        return ToolResult.Success(out.toString(2))
    }

    private suspend fun runOne(index: Int, agentName: String, task: String): JSONObject {
        val def = database.subAgentDao().getByName(agentName)
        if (def == null) {
            scene?.setStatus(index, SubAgentSceneState.Status.FAILED)
            return result(agentName, false, "未找到子智能体类型「$agentName」(用 list_sub_agents 查看)")
        }
        scene?.setStatus(index, SubAgentSceneState.Status.RUNNING)
        val r = try {
            withTimeoutOrNull(TASK_TIMEOUT_MS) {
                val core = buildCore(def)
                core.clearHistory()
                val buf = StringBuilder()
                coroutineScope {
                    // 边跑边把输出尾部推给「指挥室」,让用户点进子智能体能看到它当前在做什么。
                    val collector = launch {
                        core.tokenFlow.collect {
                            buf.append(it)
                            scene?.setActivity(index, buf.toString().takeLast(600))
                        }
                    }
                    // 也把工具调用动作透出为实时动作。
                    core.onToolBlock = { action ->
                        if (action is com.xincode.core.ToolBlockAction.PushCall) {
                            scene?.setActivity(index, "调用工具: ${action.toolName} ${action.arguments.take(80)}")
                        }
                    }
                    val job = core.run(buildTaskPrompt(def, task), this)
                    job.join()
                    collector.cancel()
                }
                val text = buf.toString().ifBlank { "(无输出)" }.take(3000)
                scene?.setResult(index, text)
                // L3:用整任务累计 token(而非仅最后一次调用),避免多轮子智能体被低估。
                AgentStats.addSubAgentRun(core.cumulativePromptTokens, core.cumulativeCompletionTokens)
                result(agentName, true, text)
            } ?: run {
                scene?.setResult(index, "超时(${TASK_TIMEOUT_MS / 1000}s)")
                result(agentName, false, "超时(${TASK_TIMEOUT_MS / 1000}s)")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // L4:用户 Stop / 上层取消要向上传播,不能吞成"伪失败"文案。
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sub-agent $agentName failed: ${e.message}")
            scene?.setResult(index, "错误: ${e.message?.take(200)}")
            result(agentName, false, "错误: ${e.message?.take(200)}")
        }
        scene?.setStatus(index, if (r.optBoolean("success")) SubAgentSceneState.Status.DONE else SubAgentSceneState.Status.FAILED)
        return r
    }

    /** 用子智能体类型的专属工具集 + 角色系统提示,构造隔离 AgentCore。 */
    private fun buildCore(def: SubAgentEntity): AgentCore {
        val reg = ToolRegistry()
        val toolNames = def.toolNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { READONLY_DEFAULT }
        for (n in toolNames) mainRegistry.get(n)?.let { reg.register(it) }
        // 始终赋予联网 + 技能工具(去重),让子智能体能真正上网搜/抓,而不是只翻本地文件到超时。
        for (n in ALWAYS_GRANTED) if (reg.get(n) == null) mainRegistry.get(n)?.let { reg.register(it) }
        return AgentCore(
            openAiClient = openAiClient,
            toolRegistry = reg,
            securityGate = securityGate,
            cursorDao = null,
            sessionId = -20L,
            systemPrompt = buildSystemPrompt(def)
        ).also {
            it.isReviewFork = true // 不递归触发后台复盘
            // B4 修复:子智能体后台自主运行、没有 UI 确认。默认 ASK 模式下若无 confirmHandler,
            // NeedConfirm 会被自动拒绝,导致 web_search/web_fetch/invoke_skill 全被拒(联网功能形同虚设)。
            // 这里给它一个自动放行【安全只读/联网/技能】工具的处理器;其余(如 shell 写操作)仍拒绝。
            it.setConfirmHandler { cmd, _ ->
                if (cmd.toolName in SAFE_AUTO_ALLOW) ToolConfirmResult.ALLOW_ONCE else ToolConfirmResult.DENY
            }
        }
    }

    private fun buildSystemPrompt(def: SubAgentEntity): String = buildString {
        append(def.systemPrompt.ifBlank { "你是一个专职子智能体:${def.name}。${def.description}" })
        val skills = def.skillNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (skills.isNotEmpty()) {
            append("\n\n你的【专属技能】(据当前任务从这几个里挑合适的,用 invoke_skill(name) 拉取其指令再照做;")
            append("不要盲目全调,也不要用清单外的):\n")
            for (s in skills) append("- ").append(s).append("\n")
        }
        append("\n完成后用简洁中文直接汇报你的结论/产出,不复述工具细节。")
    }

    private fun buildTaskPrompt(def: SubAgentEntity, task: String): String =
        "你被主脑指派了一个子任务。\n子任务:$task\n\n请用你的专属技能与工具完成,完成后简洁汇报结论。"

    private fun result(agent: String, ok: Boolean, text: String) = JSONObject().apply {
        put("agent", agent); put("success", ok); put("result", text)
    }
}
