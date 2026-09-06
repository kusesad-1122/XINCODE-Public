package com.xincode.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 步骤G:Rollout 读写口子 —— 记录 / 重建 / Fork / 导出。
 *
 * 概念对齐 OpenAI Codex (Apache-2.0, codex-rs/rollout + rollout-trace):
 * 事件流 + 读取→解析→重建 + 前缀 Fork + 原始载荷保留;实现为原创 Room 版。
 * - [record]: fire-and-forget 式记录,调用方用后台 scope 发射,不许阻塞主循环。
 * - [rebuild]: 读取 Rollout → 解析 → 重建运行态:哪些动作已完成、最后拿到什么结果、
 *   现在在等什么。进程被杀/重启后的断点续跑全靠它(配合 HarnessThreadDao.reclaimStuckTurns)。
 * - [forkThread]: 从源 Thread 的某 Turn 处复制前缀(turns 摘要+事件)另起新 Thread,
 *   换方案续跑。协作模式“换方案重试”即 Fork,不再另起一摊。
 * - [exportRollout]: 导出整条流水 JSON(调试/报障/审计),落盘位置由调用方定。
 */
class HarnessEvents(
    private val eventDao: HarnessEventDao,
    private val threadDao: HarnessThreadDao
) {
    // ---- type 词表(与 HarnessEventEntity.type 约定一致) ----
    companion object {
        const val USER_INPUT = "user_input"
        const val TURN_STARTED = "turn_started"
        const val TOOL_CALL = "tool_call"
        const val TOOL_RESULT = "tool_result"
        const val APPROVAL_REQUEST = "approval_request"
        const val APPROVAL_RESULT = "approval_result"
        const val TURN_FINISHED = "turn_finished"
    }

    suspend fun record(
        threadId: Long,
        turnId: Long,
        type: String,
        payload: String,
        callId: String = "",
        status: ExecutionStatus? = null
    ) {
        eventDao.insert(
            HarnessEventEntity(
                threadId = threadId,
                turnId = turnId,
                type = type,
                payload = payload.take(2000),
                callId = callId.take(128),
                status = status?.wire.orEmpty(),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /** 重建结果:给恢复/续跑/UI 状态页用的只读快照。 */
    data class RebuildResult(
        /** 已完成动作(工具名,按发生序,可重复)。 */
        val completedTools: List<String>,
        /** 失败/拒批动作(工具名:原因摘要)。 */
        val failedTools: List<String>,
        /** 最后一条结论摘要(取自 turns)。 */
        val lastSummary: String,
        /** 当前卡在哪:open Turn 的状态,无则空(可直接继续)。 */
        val openStatus: String,
        /** 未裁决的审批请求 payload(有则先处理它)。 */
        val pendingApprovals: List<String>
    )

    suspend fun rebuild(threadId: Long): RebuildResult {
        val events = eventDao.eventsOf(threadId)
        val completed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val approvals = mutableMapOf<String, String>() // requestId -> payload
        for (e in events) {
            // 脏 payload(历史版本/手写)不能掀翻重建:解析失败就当纯文本跳过。
            val json = runCatching { JSONObject(e.payload) }.getOrNull()
            when (e.type) {
                TOOL_RESULT -> {
                    val name = json?.optString("tool").orEmpty().ifBlank { "(未知工具)" }
                    // 移植3:优先读 status 列(与 Codex ExecutionStatus 同词汇);
                    // 老行 status 为空才回退读 payload 的 ok 字段。
                    val completed = when (ExecutionStatus.ofWire(e.status)) {
                        ExecutionStatus.COMPLETED -> true
                        ExecutionStatus.FAILED, ExecutionStatus.CANCELLED, ExecutionStatus.ABORTED -> false
                        ExecutionStatus.RUNNING, null ->
                            json != null && e.payload.contains("\"ok\":true")
                    }
                    if (completed) completed.add(name)
                    else failed.add("$name:${json?.optString("digest").orEmpty().take(120)}")
                }
                APPROVAL_REQUEST -> {
                    val id = json?.optString("id").orEmpty()
                    if (id.isNotBlank()) approvals[id] = e.payload
                }
                APPROVAL_RESULT -> approvals.remove(json?.optString("id").orEmpty())
            }
        }
        val turns = threadDao.turnsOf(threadId)
        val lastSummary = turns.lastOrNull { it.summary.isNotBlank() }?.summary.orEmpty()
        val open = turns.lastOrNull {
            it.status == HarnessTurnEntity.STATUS_RUNNING ||
                it.status == HarnessTurnEntity.STATUS_WAITING_TOOL ||
                it.status == HarnessTurnEntity.STATUS_WAITING_APPROVAL
        }?.status.orEmpty()
        return RebuildResult(completed, failed, lastSummary, open, approvals.values.toList())
    }

    /**
     * Fork:把源 Thread 在 [fromTurnId](含)之前的 turns 前缀连同事件复制到新 Thread。
     * parent 链在新 Thread 内重映射(保持血缘不断);事件 turnId 同步重映射。
     * @return 新 Thread id。
     */
    suspend fun forkThread(srcThreadId: Long, fromTurnId: Long, goalSuffix: String = "(分支)"): Long {
        val src = threadDao.getThread(srcThreadId) ?: return 0L
        val now = System.currentTimeMillis()
        val newThreadId = threadDao.insertThread(
            HarnessThreadEntity(
                sessionId = src.sessionId,
                goal = (src.goal + goalSuffix).take(200),
                createdAt = now,
                updatedAt = now
            )
        )
        val prefix = threadDao.turnsOf(srcThreadId).filter { it.id <= fromTurnId }
        val idMap = mutableMapOf<Long, Long>() // oldTurnId -> newTurnId
        for (t in prefix) {
            val newParent = if (t.parentTurnId == 0L) 0L else (idMap[t.parentTurnId] ?: 0L)
            val newId = threadDao.insertTurn(
                t.copy(id = 0, threadId = newThreadId, parentTurnId = newParent, createdAt = now, updatedAt = now)
            )
            idMap[t.id] = newId
        }
        val events = eventDao.eventsOf(srcThreadId).filter { e ->
            e.turnId == 0L || (idMap.containsKey(e.turnId))
        }
        for (e in events) {
            eventDao.insert(
                e.copy(id = 0, threadId = newThreadId, turnId = idMap[e.turnId] ?: 0L, createdAt = now)
            )
        }
        return newThreadId
    }

    /**
     * 导出整条流水(含 turns),调用方决定落盘/分享。
     * 键名向 Codex Rollout JSONL 对齐(thread_id/turn_id/call_id/type/status/ts),
     * payload 原样保留(排障用),便于两边工具互读。
     */
    suspend fun exportRollout(threadId: Long): String {
        val turns = threadDao.turnsOf(threadId)
        val events = eventDao.eventsOf(threadId)
        val turnsJson = JSONArray()
        for (t in turns) {
            turnsJson.put(
                JSONObject()
                    .put("id", t.id)
                    .put("parent", t.parentTurnId)
                    .put("status", t.status)
                    .put("input", t.input)
                    .put("summary", t.summary)
                    .put("evidence", t.evidence)
            )
        }
        val eventsJson = JSONArray()
        for (e in events) {
            eventsJson.put(
                JSONObject()
                    .put("id", e.id)
                    .put("thread_id", e.threadId)
                    .put("turn_id", e.turnId)
                    .put("call_id", e.callId)
                    .put("type", e.type)
                    .put("status", e.status)
                    .put("payload", e.payload)
                    .put("ts", e.createdAt)
            )
        }
        return JSONObject().put("thread_id", threadId).put("turns", turnsJson).put("events", eventsJson).toString()
    }
}
