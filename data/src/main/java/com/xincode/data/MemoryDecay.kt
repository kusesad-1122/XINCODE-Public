package com.xincode.data

import android.util.Log

/**
 * 记忆生命周期:确定性老化 / 归档规则(M3-1 后半)。
 *
 * 设计目标:记忆「只增不清」的旧问题 —— `recallCount/lastRecalledAt` 已埋但从不消费。
 * 这里把「命中次数 + 新近度」反向用成**老化**信号:一条记忆**被召回过、之后长期不再被召回**,
 * 说明它已经不活跃,应当降权,乃至归档;而**从未被召回过**的记忆(存量数据也多半如此)不纳入老化,
 * 因为我们没有证据证明它「曾经有用但已弃用」——直接归档会误伤大量仍有价值的老记忆。
 *
 * 全部逻辑都是**纯函数 + 阈值常量**,便于单测;唯一有副作用的是 [sweep](写回 DB)。
 * **绝不删除记忆**:归档只是把 `archived` 置 1,记忆仍可被检索(get_memory_by_title / recall_memory 的
 * FTS),用户显式查到时会自动复活(见 GetMemoryByTitleTool)。删除必须由用户显式操作。
 */
object MemoryDecay {
    private const val TAG = "MemoryDecay"

    /** 毫秒/天,用于把「距上次召回的天数」算出来。 */
    const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * 降权起点:一条记忆**超过这么多天**没被召回,开始线性降权(权重从 1.0 往下走)。
     * 选 30 天:个人助手的记忆,一个月没被用到基本可以认为是「冷却中」,但还不该消失。
     */
    const val DECAY_AFTER_DAYS = 30L

    /**
     * 归档起点:一条记忆**超过这么多天**没被召回,标记为归档(退出自动召回,但仍可检索)。
     * 选 180 天(半年):只有明显「长期吃灰」的记忆才该休眠;比这更短会在日常使用里误伤
     * 那些「偶尔才相关」的记忆(比如半年才问一次的某条配置)。
     */
    const val ARCHIVE_AFTER_DAYS = 180L

    /**
     * 降权下限(归档边界处的权重)。0.4 而非 0:即使一条记忆已很老,只要它的向量相似度
     * 明显最高,仍该排到前面(相似度主导),只是别让它与新鲜记忆同权。
     */
    const val DECAY_WEIGHT_FLOOR = 0.4f

    data class DecayResult(
        /** true=归档(退出自动召回,仍可检索)。 */
        val archived: Boolean,
        /** 活跃度权重,召回打分时作为乘数。范围 [DECAY_WEIGHT_FLOOR, 1.0]。 */
        val weight: Float
    )

    /**
     * 纯函数:给定一条记忆「上次被召回的时刻」,算出它当下的老化状态。
     *
     * @param lastRecalledAt [MemoryEntity.lastRecalledAt](0=从未被召回)
     * @param now            当前时间戳(ms),注入以便单测固定
     *
     * 注意:只认 `lastRecalledAt`,**不**回退到 createdAt。因为「从未被召回过」的记忆(存量数据也多半如此)
     * 没有证据表明它「曾经有用但已弃用」,直接按创建时间算年龄会一次性全归档 —— 那是缺陷不是特性。
     */
    fun classify(lastRecalledAt: Long, now: Long): DecayResult {
        // 从未被召回过 → 视为「活跃」,不归档、不降权(存量数据安全阀)。
        if (lastRecalledAt <= 0L) return DecayResult(false, 1.0f)

        val ageDays = (now - lastRecalledAt) / DAY_MS
        return when {
            ageDays > ARCHIVE_AFTER_DAYS ->
                DecayResult(true, DECAY_WEIGHT_FLOOR)
            ageDays > DECAY_AFTER_DAYS -> {
                // (DECAY_AFTER_DAYS, ARCHIVE_AFTER_DAYS] 区间内线性降权 1.0 → DECAY_WEIGHT_FLOOR
                val span = (ARCHIVE_AFTER_DAYS - DECAY_AFTER_DAYS).toFloat()
                val t = ((ageDays - DECAY_AFTER_DAYS).toFloat() / span).coerceIn(0f, 1f)
                DecayResult(false, lerp(1.0f, DECAY_WEIGHT_FLOOR, t))
            }
            else -> DecayResult(false, 1.0f)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    data class SweepSummary(
        /** 本次新归档的条数。 */
        val archived: Int,
        /** 本次被降权(未归档)的条数。 */
        val decayed: Int,
        /** 本次从归档状态复活(被重新激活)的条数。 */
        val reactivated: Int,
        /** 扫描过的总条数。 */
        val scanned: Int
    )

    /**
     * 周期性清理入口。读出全部记忆,逐条分类,把变化写回 `onUpdate`。
     *
     * 用 `onUpdate` 回调而不是具体 DAO,是为了**可单测**(data 模块的 JVM 单测没有 Robolectric,
     * 起不了真 Room 库);生产侧传入一个调用 [MemoryDao.updateDecay] 的 lambda 即可。
     *
     * @param memories 当前全部记忆(生产侧用 [MemoryDao.getAll])
     * @param now      当前时间戳(ms)
     * @param onUpdate 落库回调:`(id, archived, weight) -> Unit`
     */
    suspend fun sweep(
        memories: List<MemoryEntity>,
        now: Long,
        onUpdate: suspend (id: Long, archived: Int, weight: Float) -> Unit
    ): SweepSummary {
        var archived = 0
        var decayed = 0
        var reactivated = 0
        for (m in memories) {
            val r = classify(m.lastRecalledAt, now)
            val wasArchived = m.archived != 0
            val weightChanged = kotlin.math.abs(m.decayWeight - r.weight) > 1e-4f
            if (r.archived == wasArchived && !weightChanged) continue
            onUpdate(m.id, if (r.archived) 1 else 0, r.weight)
            when {
                r.archived && !wasArchived -> archived++
                !r.archived && wasArchived -> reactivated++
                else -> decayed++
            }
        }
        if (archived > 0 || decayed > 0 || reactivated > 0) {
            Log.i(TAG, "sweep: scanned=${memories.size} archived=$archived decayed=$decayed reactivated=$reactivated")
        }
        return SweepSummary(archived, decayed, reactivated, memories.size)
    }
}
