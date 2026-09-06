package com.xincode.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 步骤H:Thread/Turn/Rollout/Fork 全链演练(方案“Demo 走一遍即回归一遍”的可执行版)。
 *
 * 在 JVM 单测里用内存假 DAO 跑真实 [HarnessThreads] + [HarnessEvents]:
 * 开 Thread → 两轮 Turn(父子血缘) → 记事件 → 继承上下文 → 重建 → Fork → 导出 → 回收悬挂。
 * 跑法(有 JDK 的机器): `./gradlew :data:testDebugUnitTest`。
 */
class HarnessDrillTest {

    private class FakeThreadDao : HarnessThreadDao {
        val threads = mutableMapOf<Long, HarnessThreadEntity>()
        val turns = mutableMapOf<Long, HarnessTurnEntity>()
        private var threadSeq = 0L
        private var turnSeq = 0L

        override suspend fun insertThread(thread: HarnessThreadEntity): Long {
            val id = ++threadSeq
            threads[id] = thread.copy(id = id)
            return id
        }

        override suspend fun updateThread(thread: HarnessThreadEntity) {
            threads[thread.id] = thread
        }

        override suspend fun getThread(id: Long) = threads[id]

        override suspend fun activeThreadOfSession(sessionId: Long) =
            threads.values.filter { it.sessionId == sessionId && it.status == "active" }
                .maxByOrNull { it.updatedAt }

        override suspend fun setThreadStatus(id: Long, status: String, ts: Long) {
            threads[id]?.let { threads[id] = it.copy(status = status, updatedAt = ts) }
        }

        override suspend fun insertTurn(turn: HarnessTurnEntity): Long {
            val id = ++turnSeq
            turns[id] = turn.copy(id = id)
            return id
        }

        override suspend fun updateTurn(turn: HarnessTurnEntity) {
            turns[turn.id] = turn
        }

        override suspend fun getTurn(id: Long) = turns[id]

        override suspend fun turnsOf(threadId: Long) =
            turns.values.filter { it.threadId == threadId }.sortedBy { it.id }

        override suspend fun openTurnOf(threadId: Long) =
            turns.values.filter {
                it.threadId == threadId && it.status in HarnessTurnEntity.OPEN_STATUSES
            }.maxByOrNull { it.id }

        override suspend fun setTurnStatus(id: Long, status: String, ts: Long) {
            turns[id]?.let { turns[id] = it.copy(status = status, updatedAt = ts) }
        }

        override suspend fun finishTurn(id: Long, status: String, summary: String, evidence: String, ts: Long) {
            turns[id]?.let { turns[id] = it.copy(status = status, summary = summary, evidence = evidence, updatedAt = ts) }
        }

        override suspend fun reclaimStuckTurns(ts: Long): Int {
            val stuck = turns.values.filter { it.status in HarnessTurnEntity.OPEN_STATUSES }
            stuck.forEach { turns[it.id] = it.copy(status = HarnessTurnEntity.STATUS_CANCELLED, updatedAt = ts) }
            return stuck.size
        }
    }

    private class FakeEventDao : HarnessEventDao {
        val events = mutableListOf<HarnessEventEntity>()
        private var seq = 0L

        override suspend fun insert(event: HarnessEventEntity): Long {
            val id = ++seq
            events.add(event.copy(id = id))
            return id
        }

        override suspend fun eventsOf(threadId: Long) =
            events.filter { it.threadId == threadId }.sortedBy { it.id }

        override suspend fun countOf(threadId: Long) =
            events.count { it.threadId == threadId }
    }

    private fun toolResultPayload(tool: String, ok: Boolean, digest: String) =
        JSONObject().put("tool", tool).put("ok", ok).put("digest", digest).toString()

    @Test
    fun fullChain_threadTurnsEventsRebuildForkExport() = runBlocking {
        val threadDao = FakeThreadDao()
        val eventDao = FakeEventDao()
        val threads = HarnessThreads(threadDao)
        val events = HarnessEvents(eventDao, threadDao)

        // 1. 开 Thread + 首轮 Turn(修登录 Bug:T1 查根因)
        val tid = threads.activeOrStart(7L, "修复登录 Bug")
        val t1 = threads.startTurn(tid, "查一下登录闪退的根因")
        events.record(tid, t1, HarnessEvents.USER_INPUT, "查根因")
        events.record(tid, t1, HarnessEvents.TURN_STARTED, "{}")
        events.record(tid, t1, HarnessEvents.TOOL_CALL, """{"tool":"file_read"}""")
        events.record(tid, t1, HarnessEvents.TOOL_RESULT, toolResultPayload("file_read", true, "LoginActivity.kt:88 空指针"))
        threads.finishTurn(t1, true, "根因:LoginActivity 88 行空指针", "LoginActivity.kt:88")

        // 2. 第二轮继承第一轮(parent 血缘 + 摘要可达)
        val t2 = threads.startTurn(tid, "按证据修复")
        assertEquals(t1, threadDao.getTurn(t2)!!.parentTurnId)
        val inherited = threads.buildInheritedContext(tid)
        assertTrue("次轮必须继承首轮结论,实际:\n$inherited", inherited.contains("空指针"))

        // 3. 重建:已完成动作/无悬挂/无未决审批
        threads.finishTurn(t2, true, "已修复并回归", "LoginActivity.kt:88")
        val rebuilt = events.rebuild(tid)
        assertTrue(rebuilt.completedTools.contains("file_read"))
        assertEquals("", rebuilt.openStatus)
        assertTrue(rebuilt.pendingApprovals.isEmpty())
        assertTrue(rebuilt.lastSummary.contains("已修复"))

        // 4. 未裁决审批在重建里可见
        events.record(tid, t2, HarnessEvents.APPROVAL_REQUEST, """{"id":"a1","tool":"su_exec"}""")
        assertEquals(1, events.rebuild(tid).pendingApprovals.size)
        events.record(tid, t2, HarnessEvents.APPROVAL_RESULT, """{"id":"a1","approved":true}""")
        assertTrue(events.rebuild(tid).pendingApprovals.isEmpty())

        // 5. Fork:从 T1 处分支,前缀(turns+事件)完整复制,parent 重映射不断档
        val forked = events.forkThread(tid, t1, "(换方案)")
        assertNotEquals(0L, forked)
        assertNotEquals(tid, forked)
        val forkTurns = threadDao.turnsOf(forked)
        assertEquals(1, forkTurns.size)
        assertEquals(0L, forkTurns[0].parentTurnId)
        assertTrue(forkTurns[0].summary.contains("空指针"))
        // Fork 只带 fromTurnId 之前缀:T2 的两条审批事件必须留在原 Thread,不许串过去。
        val prefixEventCount = eventDao.eventsOf(tid).count { it.turnId == t1 || it.turnId == 0L }
        assertEquals(prefixEventCount, eventDao.countOf(forked))
        assertTrue(eventDao.eventsOf(forked).none { it.payload.contains("su_exec") })

        // 6. 导出含 turns + events(键名向 Codex Rollout 对齐)
        val exported = JSONObject(events.exportRollout(tid))
        assertEquals(tid, exported.getLong("thread_id"))
        assertTrue(exported.getJSONArray("turns").length() == 2)
        val outEvents = exported.getJSONArray("events")
        assertTrue(outEvents.length() > 0)
        assertEquals(tid, outEvents.getJSONObject(0).getLong("thread_id"))
        assertTrue(outEvents.getJSONObject(0).has("call_id"))

        // 7. 悬挂回收:open Turn 收回 cancelled
        val stuck = threadDao.insertTurn(
            HarnessTurnEntity(threadId = tid, status = HarnessTurnEntity.STATUS_WAITING_TOOL)
        )
        assertEquals(1, threadDao.reclaimStuckTurns())
        assertEquals(HarnessTurnEntity.STATUS_CANCELLED, threadDao.getTurn(stuck)!!.status)
    }

    @Test
    fun inheritedContext_truncatesOldestFirstButKeepsNewest() = runBlocking {
        val threadDao = FakeThreadDao()
        val threads = HarnessThreads(threadDao)
        val tid = threads.startThread(1L, "长任务")
        repeat(5) { i ->
            val t = threads.startTurn(tid, "第${i + 1}轮")
            threads.finishTurn(t, true, "结论${i + 1}-" + "x".repeat(50), "")
        }
        // 极小上限:最老被丢,但最新一轮必须保留(继承不断档)。
        val ctx = threads.buildInheritedContext(tid, maxTurns = 5, maxChars = 60)
        assertTrue(ctx.contains("结论5"))
    }

    /**
     * 移植3:status 列优先于 payload(与 Codex ExecutionStatus 同词汇);
     * 老行 status 为空才回退读 ok;callId 随事件落盘,导出可读。
     */
    @Test
    fun statusColumn_winsOverPayload_withCallIdPairing() = runBlocking {
        val threadDao = FakeThreadDao()
        val eventDao = FakeEventDao()
        val threads = HarnessThreads(threadDao)
        val events = HarnessEvents(eventDao, threadDao)
        val tid = threads.startThread(1L, "对齐")
        val t = threads.startTurn(tid, "跑一个工具")

        // Begin 配 Running + callId。
        events.record(tid, t, HarnessEvents.TOOL_CALL, """{"tool":"shell_exec"}""", callId = "call_1", status = ExecutionStatus.RUNNING)
        // payload 写 ok:true,但 status 列 FAILED → 以列为准(列是裁决,载荷只是原文)。
        events.record(
            tid, t, HarnessEvents.TOOL_RESULT, """{"tool":"shell_exec","ok":true,"digest":"x"}""",
            callId = "call_1", status = ExecutionStatus.FAILED
        )
        // 老行:无 status,回退读 ok。
        events.record(tid, t, HarnessEvents.TOOL_RESULT, toolResultPayload("file_read", true, "ok"))
        // 新行:status COMPLETED,payload 里压根没 ok 字段。
        events.record(
            tid, t, HarnessEvents.TOOL_RESULT, """{"tool":"grep","digest":"3 hits"}""",
            callId = "call_2", status = ExecutionStatus.COMPLETED
        )
        threads.finishTurn(t, true, "完", "")

        val rebuilt = events.rebuild(tid)
        assertTrue(rebuilt.completedTools.containsAll(listOf("file_read", "grep")))
        assertEquals(listOf("shell_exec:x"), rebuilt.failedTools)

        val out = JSONObject(events.exportRollout(tid)).getJSONArray("events")
        val begins = (0 until out.length()).map { out.getJSONObject(it) }
            .filter { it.getString("type") == HarnessEvents.TOOL_CALL }
        assertEquals("call_1", begins[0].getString("call_id"))
        assertEquals("running", begins[0].getString("status"))
    }
}
