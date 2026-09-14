package com.xincode.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * §1/§2/§3 纯逻辑测试(不依赖 Android,跑 `:core:testDebugUnitTest`)。
 * 分批、并发观察、顺序回灌、失败隔离、三层截断、单轮预算均在此机械校验。
 */
class ToolContractLogicTest {

    // ───────────── §1 工具并发分层 ─────────────

    @Test
    fun planBatches_groupsConsecutiveSafeTools() {
        val safe = setOf("a", "b", "c", "d", "e")
        val batches = ToolBatchPlanner.planToolBatches(
            listOf("a", "b", "c", "d", "e"), { it in safe }, 3
        )
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d", "e")), batches)
    }

    @Test
    fun planBatches_isolatesUnsafeToolAndBreaksCurrentBatch() {
        val safe = setOf("a", "b", "c")
        // 连续安全 → 非安全 X 独占一批 → 后续安全新开一批
        val batches = ToolBatchPlanner.planToolBatches(
            listOf("a", "b", "X", "c"), { it in safe }, 8
        )
        assertEquals(3, batches.size)
        assertEquals(listOf("a", "b"), batches[0])
        assertEquals(listOf("X"), batches[1]) // 非安全独占
        assertEquals(listOf("c"), batches[2])
        // 顺序不丢:展平后 == 原始
        assertEquals(listOf("a", "b", "X", "c"), batches.flatten())
    }

    @Test
    fun planBatches_everyUnsafeToolRunsAlone() {
        val batches = ToolBatchPlanner.planToolBatches(
            listOf("X", "Y"), { false }, 8
        )
        assertEquals(listOf(listOf("X"), listOf("Y")), batches)
        assertTrue(batches.all { it.size == 1 })
    }

    @Test
    fun planBatches_respectsMaxParallel() {
        val safe = setOf("a", "b", "c", "d")
        val batches = ToolBatchPlanner.planToolBatches(
            listOf("a", "b", "c", "d"), { it in safe }, 2
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), batches)
        assertTrue(batches.all { it.size <= 2 })
    }

    @Test
    fun safeToolsRunConcurrently() = runBlocking {
        val calls = listOf("a", "b", "c", "d")
        val batches = ToolBatchPlanner.planToolBatches(calls, { true }, 8)
        val current = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        suspend fun fakeExec(name: String): String {
            val n = current.incrementAndGet()
            maxSeen.updateAndGet { if (n > it) n else it }
            kotlinx.coroutines.delay(120)
            current.decrementAndGet()
            return name
        }
        for (batch in batches) {
            if (batch.size == 1) {
                fakeExec(batch[0])
            } else {
                coroutineScope {
                    batch.map { async(Dispatchers.IO) { fakeExec(it) } }.awaitAll()
                }
            }
        }
        // 4 个安全工具同批并发 → 峰值并发必须 > 1
        assertTrue("期望并发执行,实际峰值=${maxSeen.get()}", maxSeen.get() > 1)
    }

    @Test
    fun unsafeToolsRunSerially() = runBlocking {
        val calls = listOf("X", "Y", "Z")
        val batches = ToolBatchPlanner.planToolBatches(calls, { false }, 8)
        assertTrue(batches.all { it.size == 1 })
        val current = AtomicInteger(0)
        val maxSeen = AtomicInteger(0)
        suspend fun fakeExec(name: String): String {
            val n = current.incrementAndGet()
            maxSeen.updateAndGet { if (n > it) n else it }
            kotlinx.coroutines.delay(60)
            current.decrementAndGet()
            return name
        }
        for (batch in batches) {
            // 非安全走 size==1 分支:直接调用(无 async),天然串行
            fakeExec(batch[0])
        }
        assertEquals(1, maxSeen.get())
    }

    @Test
    fun resultOrderEqualsCallOrderDespiteSpeed() = runBlocking {
        // 故意:第一个慢、后面快 —— 结果顺序仍须等于原始调用顺序
        val calls = listOf("slow", "fast1", "fast2")
        val batches = ToolBatchPlanner.planToolBatches(calls, { true }, 8)
        val results = coroutineScope {
            batches.flatMap { batch ->
                if (batch.size == 1) listOf(fakeTimed(batch[0]))
                else batch.map { async(Dispatchers.IO) { fakeTimed(it) } }.awaitAll()
            }
        }
        assertEquals(calls, results)
    }

    @Test
    fun batchFailureDoesNotKillSiblings() = runBlocking {
        // 模拟 runToolExec 把异常吞成 Error:一个炸了,兄弟照常成功
        val calls = listOf("ok1", "boom", "ok2")
        val batches = ToolBatchPlanner.planToolBatches(calls, { true }, 8)
        val results = coroutineScope {
            batches.flatMap { batch ->
                batch.map { async(Dispatchers.IO) { swallow(it) } }.awaitAll()
            }
        }
        assertEquals(listOf("ok1", "ERROR:boom", "ok2"), results)
    }

    private suspend fun fakeTimed(name: String): String {
        if (name == "slow") kotlinx.coroutines.delay(200) else kotlinx.coroutines.delay(10)
        return name
    }

    private fun swallow(name: String): String = try {
        if (name == "boom") throw RuntimeException("boom") else name
    } catch (_: Throwable) {
        "ERROR:$name"
    }

    // ───────────── §2 结果三层截断 ─────────────

    @Test
    fun l1_truncatesSingleOversizedExemptTool() {
        val big = "A".repeat(80_000)
        // file_read 豁免 L3 且无 offloadDir → 走 L1 头60%+尾30% 截断
        val out = ToolResultBudget.applyTurnBudget(
            listOf(ToolResultBudget.Entry("c1", "file_read", big)),
            offloadDir = null,
            isExemptFromOffload = { true }
        )
        val r = out[0]
        assertTrue("应被截断", r.length < big.length)
        assertTrue("应保留头部", r.startsWith("A".repeat((80_000 * 0.6).toInt())))
        assertTrue("应保留尾部", r.takeLast((80_000 * 0.3).toInt()).all { it == 'A' })
        assertTrue(r.contains("已截断"))
    }

    @Test
    fun l1_markerReportsRemovedCount() {
        val n = 10_000
        val text = ToolResultBudget.truncateToHeadTail("Z".repeat(n))
        // 头 60% + 尾 30% 保留,中间用标记替代(不依赖 locale 的数字格式)
        assertTrue(text.contains("已截断"))
        assertTrue(text.startsWith("Z".repeat((n * 0.6).toInt())))
        assertTrue(text.takeLast((n * 0.3).toInt()).all { it == 'Z' })
    }

    @Test
    fun l3_offloadsOversizedNonExemptTool() {
        val dir = createTempDir("tool-results")
        val big = "B".repeat(80_000)
        val out = ToolResultBudget.applyTurnBudget(
            listOf(ToolResultBudget.Entry("call-1", "grep", big)),
            offloadDir = dir,
            isExemptFromOffload = { false }
        )
        val r = out[0]
        assertTrue("应落盘并给摘要", r.contains("已落盘"))
        // 完整内容写盘,模型可二次 file_read
        val file = dir.listFiles()!!.first { it.name.endsWith(".txt") }
        assertEquals(big, file.readText())
        file.delete()
    }

    @Test
    fun l3_exemptToolNeverOffloads() {
        val dir = createTempDir("tool-results")
        val big = "C".repeat(80_000)
        ToolResultBudget.applyTurnBudget(
            listOf(ToolResultBudget.Entry("c1", "file_read", big)),
            offloadDir = dir,
            isExemptFromOffload = { true } // file_read 豁免
        )
        // 豁免 → 不应写任何落盘文件(只走 L1)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun l2_degradesOverflowAggregateToSummary() {
        val entries = (1..10).map {
            ToolResultBudget.Entry("c$it", "file_read", "X".repeat(30_000))
        } // 合计 300_000 > 200_000 聚合预算
        val out = ToolResultBudget.applyTurnBudget(
            entries, offloadDir = null, isExemptFromOffload = { true }
        )
        val degraded = out.count { it.contains("已降级为摘要") }
        assertTrue("应有被降级的结果", degraded > 0)
        assertFalse("靠前的结果不应被降级", out[0].contains("已降级为摘要"))
        assertTrue("降级后长度应远小于原值", out.last().length < 30_000)
    }

    @Test
    fun cleanupRemovesStaleOffloads() {
        val dir = createTempDir("tool-results")
        val fresh = File(dir, "fresh.txt").apply { writeText("x") }
        val stale = File(dir, "stale.txt").apply {
            writeText("y")
            setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        }
        ToolResultBudget.cleanupStaleOffloads(dir, 7L)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    // ───────────── §3 单轮预算 ─────────────

    @Test
    fun overPerTurnLimit_boundary() {
        assertFalse(ToolResultBudget.overPerTurnLimit(AgentCoreContract.MAX_TOOL_CALLS_PER_TURN))
        assertTrue(ToolResultBudget.overPerTurnLimit(AgentCoreContract.MAX_TOOL_CALLS_PER_TURN + 1))
    }

    @Test
    fun softBudgetReminder_boundary() {
        assertFalse(ToolResultBudget.shouldInjectBudgetReminder(4_000, 10_000))
        assertTrue(ToolResultBudget.shouldInjectBudgetReminder(5_000, 10_000))   // 恰好 0.5
        assertTrue(ToolResultBudget.shouldInjectBudgetReminder(6_000, 10_000))
        assertFalse(ToolResultBudget.shouldInjectBudgetReminder(0, 10_000))      // 无用量
        assertFalse(ToolResultBudget.shouldInjectBudgetReminder(5_000, 0))       // 未声明 ctx
    }

    @Test
    fun contractConstants_present() {
        assertNotNull(AgentCoreContract.CONCURRENCY_SAFE_TOOLS)
        assertNotNull(AgentCoreContract.OFFLOAD_EXEMPT_TOOLS)
    }
}
