package com.xincode.app

import android.util.Log
import com.xincode.core.AgentCore
import com.xincode.core.Tool
import com.xincode.core.ToolRegistry
import com.xincode.core.ToolResult
import com.xincode.provider.OpenAiClient
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wolfpack orchestrator: runs multiple sub-tasks in parallel.
 *
 * Each sub-task creates a lightweight AgentCore instance with its own empty tool registry.
 * Semaphore limits concurrency to [MAX_CONCURRENT]. Per-task timeout: [TASK_TIMEOUT_MS].
 */
class WolfpackOrchestrator(
    private val openAiClient: OpenAiClient
) : Tool {

    companion object {
        private const val TAG = "Wolfpack"
        private const val TASK_TIMEOUT_MS = 2 * 60 * 1000L
    }

    /** 并行度(同时最多跑几个),仅用来限制内存峰值——**不限制**子任务总数。可由 PowerController 调。 */
    var concurrency: Int = 5

    override val name = "wolfpack_run"

    override val description = """Run multiple independent sub-tasks in parallel and return combined results.
Input: JSON array of task objects, each with a "task" string.
Output: JSON array of results, each with "task", "success", and "result".
Example: [{"task":"count files in /sdcard"}, {"task":"list directories in /sdcard"}]
你可以按需要传入【任意多个】子任务(数量由你自己判断,没有上限);它们会以最多 $concurrency 个同时运行、
其余排队的方式全部执行完(并行度仅为控制手机内存峰值,不限制总数)。Per-task timeout: ${TASK_TIMEOUT_MS / 1000}s."""

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("tasks", JSONObject().apply {
                put("type", "array")
                put("description", "Array of task objects, each with a 'task' string field")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("task", JSONObject().apply {
                            put("type", "string")
                            put("description", "Sub-task description (natural language)")
                        })
                    })
                    put("required", JSONArray().apply { put("task") })
                })
            })
        })
        put("required", JSONArray().apply { put("tasks") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val tasksJson = params["tasks"] ?: return ToolResult.Error("Missing 'tasks' parameter")

        val taskList: List<String> = try {
            val arr = JSONArray(tasksJson)
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).getString("task")
            }
        } catch (e: Exception) {
            return ToolResult.Error("Invalid tasks JSON: ${e.message}")
        }

        if (taskList.isEmpty()) return ToolResult.Error("Empty task list")
        // 不再限制子任务总数(用户要求"无上限");并行度由 semaphore 控制,其余排队执行。

        Log.i(TAG, "Executing ${taskList.size} tasks (max $concurrency concurrent)")

        // Execute in parallel with semaphore
        val results = mutableListOf<JSONObject>()
        val semaphore = java.util.concurrent.Semaphore(concurrency)

        coroutineScope {
            val jobs = taskList.mapIndexed { idx, taskDesc ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        Log.d(TAG, "Task #${idx + 1} starting: ${taskDesc.take(60)}")
                        executeOneTask(taskDesc, idx)
                    } finally {
                        semaphore.release()
                    }
                }
            }
            jobs.forEach { results.add(it.await()) }
        }

        val resultArray = JSONArray()
        results.forEach { resultArray.put(it) }

        val successCount = results.count { it.optBoolean("success", false) }
        Log.i(TAG, "Done: $successCount/${taskList.size} succeeded")

        return ToolResult.Success(resultArray.toString(2))
    }

    /**
     * gap-17:子 agent 的只读工具集(file_read/list_dir/grep/glob)。
     * 让子任务能真正探查文件系统,而非空手;只读工具无副作用,安全并发。
     */
    private fun buildSubAgentRegistry(): ToolRegistry = ToolRegistry().apply {
        register(com.xincode.tools.FileReadTool())
        register(com.xincode.tools.ListDirTool())
        register(com.xincode.tools.GrepTool())
        register(com.xincode.tools.GlobTool())
    }

    private suspend fun executeOneTask(taskDesc: String, index: Int): JSONObject =
        withTimeoutOrNull(TASK_TIMEOUT_MS) {
            try {
                // gap-17:子 agent 拥有只读工具集(独立上下文、独立 sessionId)。
                val agentCore = AgentCore(
                    openAiClient = openAiClient,
                    toolRegistry = buildSubAgentRegistry(),
                    securityGate = null,   // 只读工具无需闸门;不放行任何写/执行工具
                    cursorDao = null,
                    sessionId = 0L,
                    systemPrompt = "你是一个专注的子 agent。用提供的只读工具(file_read/list_dir/grep/glob)完成任务," +
                        "拿到结果后用简洁中文直接汇报结论。不要复述工具细节。"
                )

                // gap-17:并发采集 tokenFlow(在 run 之前启动收集,避免事后 2s 抓取的竞态/丢字)。
                val buf = StringBuilder()
                coroutineScope {
                    val collector = launch { agentCore.tokenFlow.collect { buf.append(it) } }
                    val job = agentCore.run(taskDesc, this)
                    job.join()
                    collector.cancel()
                }

                val output = buf.toString().ifBlank { "(no output captured)" }
                Log.d(TAG, "Task #${index + 1} done: ${output.take(100)}")

                JSONObject().apply {
                    put("task", taskDesc.take(100))
                    put("success", true)
                    put("result", output.take(2000))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Task #${index + 1} failed: ${e.message}")
                JSONObject().apply {
                    put("task", taskDesc.take(100))
                    put("success", false)
                    put("result", "Error: ${e.message?.take(200)}")
                }
            }
        } ?: JSONObject().apply {
            Log.w(TAG, "Task #${index + 1} timeout")
            put("task", taskDesc.take(100))
            put("success", false)
            put("result", "Timeout after ${TASK_TIMEOUT_MS / 1000}s")
        }
}