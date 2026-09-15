package com.xincode.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3-1 后半:记忆生命周期(老化/归档)的纯逻辑测试。
 *
 * data 模块的 JVM 单测没有 Robolectric,起不了真 Room 库,所以 [MemoryDecay.sweep] 用 lambda 回调
 * 接收落库动作,这里用内存 list 验证「归档 / 降权 / 复活」的分界;DAO 落库由生产侧传入
 * `dao.updateDecay` 的 lambda 完成。
 */
class MemoryDecayTest {

    private val NOW = 1_000_000_000_000L
    private val DAY = MemoryDecay.DAY_MS

    private fun mem(
        id: Long,
        lastRecalledAt: Long,
        createdAt: Long = NOW,
        archived: Int = 0,
        decayWeight: Float = 1.0f
    ) = MemoryEntity(
        id = id,
        title = "t$id",
        content = "c$id",
        lastRecalledAt = lastRecalledAt,
        createdAt = createdAt,
        archived = archived,
        decayWeight = decayWeight
    )

    @Test
    fun neverRecalledStaysActive() {
        // 存量数据安全阀:从未被召回(lastRecalledAt=0)的老记忆不归档、不降权。
        val r = MemoryDecay.classify(0, NOW)
        assertFalse(r.archived)
        assertEquals(1.0f, r.weight, 1e-6f)
    }

    @Test
    fun freshWithinDecayWindowIsActiveFullWeight() {
        val r = MemoryDecay.classify(NOW - 10 * DAY, NOW)
        assertFalse(r.archived)
        assertEquals(1.0f, r.weight, 1e-6f)
    }

    @Test
    fun betweenDecayAndArchiveIsDecayedNotArchived() {
        val age = (MemoryDecay.DECAY_AFTER_DAYS + MemoryDecay.ARCHIVE_AFTER_DAYS) / 2
        val r = MemoryDecay.classify(NOW - age * DAY, NOW)
        assertFalse(r.archived)
        assertTrue("应已降权(weight<1)", r.weight < 1.0f)
        assertTrue("降权不应低于下限", r.weight >= MemoryDecay.DECAY_WEIGHT_FLOOR)
    }

    @Test
    fun beyondArchiveIsArchived() {
        val r = MemoryDecay.classify(NOW - (MemoryDecay.ARCHIVE_AFTER_DAYS + 50) * DAY, NOW)
        assertTrue(r.archived)
        assertEquals(MemoryDecay.DECAY_WEIGHT_FLOOR, r.weight, 1e-6f)
    }

    @Test
    fun decayWeightMonotonicWithAge() {
        val w1 = MemoryDecay.classify(NOW - (MemoryDecay.DECAY_AFTER_DAYS + 10) * DAY, NOW).weight
        val w2 = MemoryDecay.classify(NOW - (MemoryDecay.ARCHIVE_AFTER_DAYS - 10) * DAY, NOW).weight
        assertTrue("越老权重应越低", w2 < w1)
    }

    @Test
    fun boundaryAtDecayStartIsFullWeight() {
        // 恰好 DECAY_AFTER_DAYS 当天:还在窗口内,不降权。
        val r = MemoryDecay.classify(NOW - MemoryDecay.DECAY_AFTER_DAYS * DAY, NOW)
        assertFalse(r.archived)
        assertEquals(1.0f, r.weight, 1e-6f)
    }

    @Test
    fun boundaryOneDayPastArchiveIsArchived() {
        val r = MemoryDecay.classify(NOW - (MemoryDecay.ARCHIVE_AFTER_DAYS + 1) * DAY, NOW)
        assertTrue(r.archived)
    }

    @Test
    fun sweepArchivesDecaysAndReactivates() {
        val memories = listOf(
            mem(1, NOW - (MemoryDecay.ARCHIVE_AFTER_DAYS + 10) * DAY), // 该归档
            mem(2, NOW - (MemoryDecay.DECAY_AFTER_DAYS + 20) * DAY),  // 该降权
            mem(3, NOW - 5 * DAY),                                    // 活跃,不变
            mem(4, NOW - 5 * DAY, archived = 1, decayWeight = MemoryDecay.DECAY_WEIGHT_FLOOR) // 已归档但近期被召回 → 复活
        )
        val updates = mutableListOf<Triple<Long, Int, Float>>()
        val summary = runBlocking {
            MemoryDecay.sweep(memories, NOW) { id, a, w -> updates.add(Triple(id, a, w)) }
        }

        assertEquals(4, summary.scanned)
        assertEquals(1, summary.archived)
        assertEquals(1, summary.decayed)
        assertEquals(1, summary.reactivated)

        assertTrue(updates.any { it.first == 1L && it.second == 1 })
        assertTrue(updates.any { it.first == 2L && it.second == 0 && it.third < 1.0f })
        assertTrue(updates.any { it.first == 4L && it.second == 0 && it.third == 1.0f })
        // 活跃的那条(mem 3)不应有任何写回
        assertFalse(updates.any { it.first == 3L })
    }

    @Test
    fun sweepSkipsUnchanged() {
        val memories = listOf(
            mem(1, NOW - 5 * DAY),                 // 活跃
            mem(2, 0, NOW - 400 * DAY)            // 从未召回:保持活跃,不变
        )
        val updates = mutableListOf<Triple<Long, Int, Float>>()
        val summary = runBlocking {
            MemoryDecay.sweep(memories, NOW) { id, a, w -> updates.add(Triple(id, a, w)) }
        }
        assertEquals(0, updates.size)
        assertEquals(0, summary.archived)
        assertEquals(0, summary.decayed)
    }
}
