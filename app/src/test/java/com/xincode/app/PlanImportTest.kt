package com.xincode.app

import com.xincode.data.KanbanTaskDao
import com.xincode.data.KanbanTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3-5 「一键固化到看板」回归。
 *
 * 重点：
 *  1. **新任务一律落 todo，绝不落 ready** —— 固化是"记下来待办"，不是"授权智能体立刻动手"。
 *     这条如果写错，用户点一下按钮就等于自动执行，是个严重后果。
 *  2. **幂等** —— 重复点不该产生重复任务。
 *  3. 排位接在现有任务之后，不抢占用户已有排序。
 */
class PlanImportTest {

    /** KanbanTaskDao 是接口，这里给一个内存假实现（无 mockito 依赖）。 */
    private class FakeKanbanDao(private val seeded: List<KanbanTaskEntity> = emptyList()) : KanbanTaskDao {
        val rows = seeded.toMutableList()
        private var nextId = (seeded.maxOfOrNull { it.id } ?: 0L) + 1

        override fun observeAll(): Flow<List<KanbanTaskEntity>> = flowOf(rows.toList())
        override suspend fun getAll(): List<KanbanTaskEntity> = rows.toList()
        override suspend fun insert(task: KanbanTaskEntity): Long {
            val id = nextId++
            rows.add(task.copy(id = id))
            return id
        }
        override suspend fun update(task: KanbanTaskEntity) {
            val i = rows.indexOfFirst { it.id == task.id }
            if (i >= 0) rows[i] = task
        }
        override suspend fun delete(task: KanbanTaskEntity) { rows.removeAll { it.id == task.id } }
        override suspend fun setStatus(id: Long, status: String, ts: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(status = status, updatedAt = ts)
        }
        override suspend fun clearDone() { rows.removeAll { it.status == KanbanTaskEntity.STATUS_DONE } }
        override suspend fun maxPosition(status: String): Int =
            rows.filter { it.status == status }.maxOfOrNull { it.position } ?: -1
        override suspend fun getById(id: Long): KanbanTaskEntity? = rows.firstOrNull { it.id == id }
        override suspend fun nextReady(assignee: String): KanbanTaskEntity? =
            rows.firstOrNull { it.status == KanbanTaskEntity.STATUS_READY }
        override suspend fun runningCount(): Int = rows.count { it.status == KanbanTaskEntity.STATUS_RUNNING }
        override suspend fun reclaimStuckRunning(ts: Long) = Unit
    }

    private fun plan(vararg steps: String, title: String = "重构登录模块"): PlanState =
        PlanState().apply { setPlan(title, steps.toList()) }

    @Test
    fun createsOneTaskPerStep() = runBlocking {
        val dao = FakeKanbanDao()
        val state = plan("读现有实现", "改调用方", "补测试")
        val created = PlanImport.importToKanban(dao, state, sessionId = 7L)

        assertEquals(3, created)
        assertEquals(3, dao.rows.size)
        assertEquals(listOf("读现有实现", "改调用方", "补测试"), dao.rows.map { it.title })
    }

    @Test
    fun neverBecomesReady_notAuthorizedToAutoRun() = runBlocking {
        val dao = FakeKanbanDao()
        PlanImport.importToKanban(dao, plan("a", "b"), sessionId = 1L)
        assertTrue(
            "固化必须是 todo，落 ready 会让 KanbanRunner 自动接手执行",
            dao.rows.all { it.status == KanbanTaskEntity.STATUS_TODO }
        )
        assertTrue(dao.rows.none { it.status == KanbanTaskEntity.STATUS_READY })
    }

    @Test
    fun noteCarriesSourceAndPlanTitle() = runBlocking {
        val dao = FakeKanbanDao()
        PlanImport.importToKanban(dao, plan("第一步", title = "重构登录模块"), sessionId = 1L)
        val note = dao.rows.first().note
        assertTrue("note 要能看出任务从哪来: $note", note.contains(PlanImport.SOURCE_TAG))
        assertTrue("note 要带计划标题: $note", note.contains("重构登录模块"))
        assertTrue("note 要带步号: $note", note.contains("第 1 步"))
    }

    @Test
    fun appendsAfterExistingTasks_doesNotStealPositions() = runBlocking {
        val dao = FakeKanbanDao(
            listOf(
                KanbanTaskEntity(id = 1, title = "已有任务A", status = KanbanTaskEntity.STATUS_TODO, position = 0),
                KanbanTaskEntity(id = 2, title = "已有任务B", status = KanbanTaskEntity.STATUS_TODO, position = 5)
            )
        )
        PlanImport.importToKanban(dao, plan("新一", "新二"), sessionId = 1L)
        val imported = dao.rows.filter { it.note.contains(PlanImport.SOURCE_TAG) }
        assertEquals(2, imported.size)
        assertTrue("新任务要排在已有之后，实际 ${imported.map { it.position }}", imported.all { it.position > 5 })
    }

    @Test
    fun idempotent_repeatedImportDoesNotDuplicate() = runBlocking {
        val dao = FakeKanbanDao()
        val state = plan("a", "b", "c")
        assertEquals(3, PlanImport.importToKanban(dao, state, sessionId = 1L))
        assertEquals("重复固化不该再插", 0, PlanImport.importToKanban(dao, state, sessionId = 1L))
        assertEquals(3, dao.rows.size)
    }

    @Test
    fun skipExistingFalse_allowsDuplicateWhenExplicitlyAsked() = runBlocking {
        val dao = FakeKanbanDao()
        val state = plan("a")
        PlanImport.importToKanban(dao, state, sessionId = 1L)
        assertEquals(1, PlanImport.importToKanban(dao, state, sessionId = 1L, skipExisting = false))
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun blankStepsAreSkipped() = runBlocking {
        val dao = FakeKanbanDao()
        val created = PlanImport.importToKanban(dao, plan("真步骤", "   ", ""), sessionId = 1L)
        assertEquals(1, created)
        assertEquals(listOf("真步骤"), dao.rows.map { it.title })
    }

    @Test
    fun emptyPlanCreatesNothing() = runBlocking {
        val dao = FakeKanbanDao()
        assertEquals(0, PlanImport.importToKanban(dao, PlanState(), sessionId = 1L))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun sessionIdIsRecorded() = runBlocking {
        val dao = FakeKanbanDao()
        PlanImport.importToKanban(dao, plan("x"), sessionId = 42L)
        assertEquals(42L, dao.rows.first().sessionId)
    }

    @Test
    fun noteUsesPlanTitle_evenWhenSetWithBlank() = runBlocking {
        val dao = FakeKanbanDao()
        // setPlan 自己会把空标题兜成「任务计划」，所以 state.title 一定非空；
        // PlanImport 直接沿用 state.title，不该出现空标题的 note。
        val state = PlanState().apply { setPlan("   ", listOf("x")) }
        PlanImport.importToKanban(dao, state, sessionId = 1L)
        val captured = dao.rows.first().note.substringAfter("「").substringBefore("」")
        assertTrue("note 里的标题不能是空的: ${dao.rows.first().note}", captured.isNotBlank())
        assertEquals(state.title, captured)
    }

    @Test
    fun receiptWordingMatchesOutcome() {
        assertTrue(PlanImport.receipt(0, 0).contains("没有步骤"))
        assertTrue(PlanImport.receipt(0, 3).contains("都已经在看板里"))
        assertTrue(PlanImport.receipt(2, 3).contains("已固化 2 步"))
        assertTrue(PlanImport.receipt(3, 3).contains("全部固化"))
    }
}

/** SessionPlanStore 的有界性回归（M3-5 附带修掉的无界增长）。 */
class SessionPlanStoreBoundTest {

    @Test
    fun keepsAtMostMaxSessions() {
        val store = SessionPlanStore(maxSessions = 3)
        for (i in 1..10) store.forSession(i.toLong())
        assertTrue("实际保留 ${store.size()}", store.size() <= 3)
    }

    @Test
    fun recentlyTouchedSessionSurvivesEviction() {
        val store = SessionPlanStore(maxSessions = 2)
        val first = store.forSession(1L)
        first.setPlan("计划1", listOf("a"))
        // 反复 touch 会话 1，再灌入大量其它会话
        for (i in 2..20) {
            store.forSession(i.toLong())
            store.forSession(1L)
        }
        assertTrue("被反复访问的会话不该被淘汰", store.size() <= 2)
        assertEquals("1", store.forSession(1L).title.substringAfter("计划").take(1))
    }

    @Test
    fun removeClearsAndResets() {
        val store = SessionPlanStore()
        val s = store.forSession(1L)
        s.setPlan("计划", listOf("a"))
        store.remove(1L)
        assertTrue(store.forSession(1L).steps.isEmpty())
    }
}
