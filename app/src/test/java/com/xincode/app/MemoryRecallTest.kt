package com.xincode.app

import com.xincode.data.MemoryEntity
import com.xincode.provider.EmbeddingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallTest {

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

    private fun mem(
        id: Long,
        title: String,
        embedding: FloatArray?,
        content: String = "内容 $title"
    ) = MemoryEntity(
        id = id,
        title = title,
        content = content,
        embedding = embedding?.let { EmbeddingService.floatArrayToBytes(it) }
    )
}
