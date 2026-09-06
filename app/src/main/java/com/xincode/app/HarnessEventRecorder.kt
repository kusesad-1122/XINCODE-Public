package com.xincode.app

import com.xincode.data.ExecutionStatus
import com.xincode.data.HarnessEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 步骤G:Rollout 记录器 —— 把运行中各缝隙的事件写进 harness_events。
 *
 * 归因规则:Turn 起止由各会话 turnHooks 报到(trackStart/trackFinish,精确);
 * 工具派发是全局的,按 ToolSessionContext.sessionId 查 openTurns 表归因,
 * 查不到记 threadId=0/turnId=0(归属不明也记,总比丢了强)。
 * 全部后台发射 + runCatching:记录永远不能掀翻主循环。
 */
class HarnessEventRecorder(
    private val scope: CoroutineScope,
    private val events: HarnessEvents
) {
    /** sessionId -> (threadId, turnId):当前占着执行位的 Turn。 */
    private val openTurns = ConcurrentHashMap<Long, Pair<Long, Long>>()

    fun trackStart(sessionId: Long, threadId: Long, turnId: Long, input: String) {
        openTurns[sessionId] = threadId to turnId
        record(threadId, turnId, HarnessEvents.USER_INPUT, JSONObject().put("input", input.take(500)).toString())
        record(threadId, turnId, HarnessEvents.TURN_STARTED, JSONObject().put("thread_id", threadId).toString())
    }

    fun trackFinish(sessionId: Long, ok: Boolean, summary: String) {
        val (threadId, turnId) = openTurns.remove(sessionId) ?: (0L to 0L)
        record(
            threadId, turnId, HarnessEvents.TURN_FINISHED,
            JSONObject().put("ok", ok).put("summary", summary.take(500)).toString(),
            // 移植3:Turn 收尾带 Codex 式终态(完成/失败;取消走 reclaim 路径,见 DAO)。
            status = if (ok) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED
        )
    }

    fun recordToolCall(sessionId: Long, toolName: String, args: String, callId: String = "") {
        val (threadId, turnId) = openTurns[sessionId] ?: (0L to 0L)
        record(
            threadId, turnId, HarnessEvents.TOOL_CALL,
            JSONObject().put("tool", toolName).put("args", args.take(500)).toString(),
            // 移植3:Begin 配 Running,End 配终态,靠 callId 配对(与 Codex trace 同构)。
            callId = callId,
            status = ExecutionStatus.RUNNING
        )
    }

    fun recordToolResult(sessionId: Long, toolName: String, ok: Boolean, digest: String, callId: String = "") {
        val (threadId, turnId) = openTurns[sessionId] ?: (0L to 0L)
        record(
            threadId, turnId, HarnessEvents.TOOL_RESULT,
            JSONObject().put("tool", toolName).put("ok", ok).put("digest", digest.take(500)).toString(),
            callId = callId,
            status = if (ok) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED
        )
    }

    fun recordApprovalRequest(sessionId: Long, requestId: String, toolName: String) {
        val (threadId, turnId) = openTurns[sessionId] ?: (0L to 0L)
        record(
            threadId, turnId, HarnessEvents.APPROVAL_REQUEST,
            JSONObject().put("id", requestId).put("tool", toolName).toString(),
            status = ExecutionStatus.RUNNING
        )
    }

    fun recordApprovalResult(sessionId: Long, requestId: String, approved: Boolean) {
        val (threadId, turnId) = openTurns[sessionId] ?: (0L to 0L)
        record(
            threadId, turnId, HarnessEvents.APPROVAL_RESULT,
            JSONObject().put("id", requestId).put("approved", approved).toString(),
            // 批复即终态(同意/拒绝都结束这次等待;拒绝后模型重判是新的 Begin)。
            status = ExecutionStatus.COMPLETED
        )
    }

    private fun record(
        threadId: Long,
        turnId: Long,
        type: String,
        payload: String,
        callId: String = "",
        status: com.xincode.data.ExecutionStatus? = null
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching { events.record(threadId, turnId, type, payload, callId, status) }
        }
    }
}
