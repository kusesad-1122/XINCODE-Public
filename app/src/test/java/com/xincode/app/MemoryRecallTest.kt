package com.xincode.app

import com.xincode.data.MemoryEntity
import com.xincode.provider.EmbeddingService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryRecallTest {

    private val DAY = 24L * 60 * 60 * 1000

    @Before
    fun resetEmbeddingFailures() {
        // EmbeddingClientHolder.failures 是进程级共享计数,测试间复位避免相互干扰。
        EmbeddingClientHolder.failures.set(0)
    }

    @Test
    fun trivialPromptGateSkipsAcksAndCommands() {
        assertTrue(MemoryRecall.isTrivialPrompt(null))
        assertTrue(MemoryRecall.isTrivialPrompt(""))
        assertTrue(MemoryRecall.isTrivialPrompt("   "))
        assertTrue(MemoryRecall.isTrivialPrompt("/skills"))
        assertTrue(MemoryRecall.isTrivialPrompt("好的"))
        assertTrue(MemoryRecall.isTrivialPrompt("嗯"))
        assertTrue(MemoryRecall.isTrivialPrompt("hi!"))
        assertTrue(MemoryRecall.isTrivialPrompt("got it."))
        assertTrue(MemoryRecall.isTrivialPrompt("继续"))
        assertTrue(MemoryRecall.isTrivialPrompt("没问题！"))
    }

    @Test
    fun trivialPromptGateAllowsRealQuestions() {
        assertFalse(MemoryRecall.isTrivialPrompt("帮我安装 Node.js"))
        assertFalse(MemoryRecall.isTrivialPrompt("上次我们讨论的方案是什么"))
        assertFalse(MemoryRecall.isTrivialPrompt("k8s"))
        assertFalse(MemoryRecall.isTrivialPrompt("note"))
        assertFalse(MemoryRecall.isTrivialPrompt("继续优化这个模块"))
    }

    @Test
    fun embeddingRankingOrdersByCosineAndAppendsUnembeddedLast() {
        val a = mem(1, "甲方案", floatArrayOf(0.9f, 0.1f, 0f))
        val b = mem(2, "乙方案", floatArrayOf(0f, 1f, 0f))
        val c = mem(3, "无向量", null)
        val query = floatArrayOf(1f, 0f, 0f)

        val ranked = MemoryRecall.rankMemoriesByEmbedding(query, listOf(b, c, a))
        assertEquals(listOf(1L, 2L, 3L), ranked.map { it.id })

        // 查询向量为空时保持原顺序
        assertEquals(listOf(2L, 3L, 1L), MemoryRecall.rankMemoriesByEmbedding(null, listOf(b, c, a)).map { it.id })
    }

    @Test
    fun recallBlockFormatsHitsAndSkipsEmpty() {
        assertEquals("", MemoryRecall.buildRecallBlock(emptyList()))
        assertEquals(
            "",
            MemoryRecall.buildRecallBlock(listOf(mem(1, "空", null, content = "")))
        )
        val block = MemoryRecall.buildRecallBlock(
            listOf(
                mem(1, "用户偏好", null, content = "用户喜欢 Kotlin"),
                mem(2, "项目约束", null, content = "禁止 root")
            ),
            limit = 2
        )
        assertTrue(block.contains("用户偏好"))
        assertTrue(block.contains("Kotlin"))
        assertTrue(block.contains("项目约束"))
    }

    // ---------------------------------------------------------------- M3-1 前半:打分加成

    @Test
    fun sameSimilarity_recallCountAndRecencyReorder() {
        val now = 1_000_000_000_000L
        val vec = floatArrayOf(1f, 0f, 0f)
        val plain = mem(1, "plain", vec, recallCount = 0, lastRecalledAt = now - 100 * DAY)
        val hot = mem(2, "hot", vec, recallCount = 8, lastRecalledAt = now)
        val ranked = MemoryRecall.rankMemoriesByEmbedding(vec, listOf(plain, hot), now)
        // 向量相似度相同(都是 1.0),命中多+更近的排前面
        assertEquals(listOf(2L, 1L), ranked.map { it.id })
    }

    @Test
    fun recencyAloneReordersEqualSimilarity() {
        val now = 1_000_000_000_000L
        val vec = floatArrayOf(1f, 0f, 0f)
        val old = mem(1, "old", vec, recallCount = 0, lastRecalledAt = now - 100 * DAY)
        val recent = mem(2, "recent", vec, recallCount = 0, lastRecalledAt = now)
        val ranked = MemoryRecall.rankMemoriesByEmbedding(vec, listOf(old, recent), now)
        assertEquals(listOf(2L, 1L), ranked.map { it.id })
    }

    @Test
    fun similarityDominatesBonus() {
        val now = 1_000_000_000_000L
        val query = floatArrayOf(1f, 0f, 0f)
        // 高相似度、零加成
        val strong = mem(1, "strong", floatArrayOf(1f, 0f, 0f), recallCount = 0, lastRecalledAt = 0)
        // 低相似度、被召回 100 次且刚召回(加成封顶 0.15 + 新近度 0.10)
        val weakHot = mem(2, "weakHot", floatArrayOf(0f, 1f, 0f), recallCount = 100, lastRecalledAt = now)
        val ranked = MemoryRecall.rankMemoriesByEmbedding(query, listOf(weakHot, strong), now)
        // 相似度明显更高者仍排第一,加成不会反客为主
        assertEquals(listOf(1L, 2L), ranked.map { it.id })
    }

    @Test
    fun decayWeightMultipliesButDoesNotHideStrongSimilarity() {
        val query = floatArrayOf(1f, 0f, 0f)
        // 高相似度但已降权(0.4)
        val strongOld = mem(1, "strongOld", floatArrayOf(1f, 0f, 0f), decayWeight = 0.4f)
        // 无相似度的新记忆
        val noSim = mem(2, "noSim", floatArrayOf(0f, 1f, 0f))
        val ranked = MemoryRecall.rankMemoriesByEmbedding(query, listOf(noSim, strongOld))
        // 即便被降权,相似度最高的仍排第一(权重只是乘数,不为 0)
        assertEquals(listOf(1L, 2L), ranked.map { it.id })
    }

    // ---------------------------------------------------------------- M3-3:query 向量缓存

    @Test
    fun sameQueryComputesEmbeddingOnlyOnce() = runBlocking {
        val cache = QueryEmbeddingCache(capacity = 4)
        cache.getOrCompute("  Hello  World ") { floatArrayOf(0.1f) }
        cache.getOrCompute("hello world") { floatArrayOf(0.1f) } // 规范化后同 key → 命中
        assertEquals(1, cache.computeCallCount())
        assertEquals(1, cache.hitCount())
        assertEquals(2, cache.requestCount())
    }

    @Test
    fun differentQueriesComputeEachOnce() = runBlocking {
        val cache = QueryEmbeddingCache(capacity = 4)
        cache.getOrCompute("alpha") { floatArrayOf(0.1f) }
        cache.getOrCompute("beta") { floatArrayOf(0.2f) }
        assertEquals(2, cache.computeCallCount())
        assertEquals(0, cache.hitCount())
    }

    @Test
    fun capacityEvictionRecomputesOldest() = runBlocking {
        val cache = QueryEmbeddingCache(capacity = 2)
        cache.getOrCompute("a") { floatArrayOf(1f) }
        cache.getOrCompute("b") { floatArrayOf(2f) }
        cache.getOrCompute("c") { floatArrayOf(3f) } // 容量 2 → 淘汰最旧 "a"
        assertEquals(2, cache.size())
        cache.getOrCompute("a") { floatArrayOf(1f) } // "a" 已被淘汰,需重算
        assertEquals(4, cache.computeCallCount())
        assertEquals(2, cache.size())
    }

    @Test
    fun failedEmbeddingIsNotCached() = runBlocking {
        val cache = QueryEmbeddingCache(capacity = 4)
        cache.getOrCompute("x") { null } // 失败(null)不缓存
        cache.getOrCompute("x") { floatArrayOf(0.9f) } // 下次重试成功
        assertEquals(2, cache.computeCallCount())
    }

    // ---------------------------------------------------------------- M3-2:embedding 覆盖全来源

    @Test
    fun embedWithGeneratesBytesWhenComputeSucceeds() = runBlocking {
        val bytes = EmbeddingClientHolder.embedWith(
            compute = { floatArrayOf(0.2f, 0.3f) },
            text = "用户喜欢 go 语言"
        )
        assertNotNull(bytes)
        assertTrue(bytes!!.contentEquals(EmbeddingService.floatArrayToBytes(floatArrayOf(0.2f, 0.3f))))
        assertEquals(0, EmbeddingClientHolder.failures.get())
    }

    @Test
    fun embedWithDegradesToNullAndCountsOnFailure() = runBlocking {
        val bytes = EmbeddingClientHolder.embedWith(
            compute = { throw RuntimeException("boom") },
            text = "x"
        )
        assertNull(bytes) // 降级为无向量,不抛
        assertEquals(1, EmbeddingClientHolder.failures.get()) // 失败计数,便于排障
    }

    // ---------------------------------------------------------------- helpers

    private fun mem(
        id: Long,
        title: String,
        embedding: FloatArray?,
        content: String = "内容 $title",
        recallCount: Int = 0,
        lastRecalledAt: Long = 0,
        archived: Int = 0,
        decayWeight: Float = 1.0f
    ) = MemoryEntity(
        id = id,
        title = title,
        content = content,
        embedding = embedding?.let { EmbeddingService.floatArrayToBytes(it) },
        recallCount = recallCount,
        lastRecalledAt = lastRecalledAt,
        archived = archived,
        decayWeight = decayWeight
    )
}
