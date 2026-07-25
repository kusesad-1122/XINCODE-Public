package com.xincode.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 一次模型调用的用量记录。
 *
 * 为什么不复用 messages 里的 usage:那边只存最后一次调用的快照,一个回合里模型可能被调
 * 十几次(每次工具回环都是一次),按消息统计会严重少算。这张表按【每次调用】追加,不覆盖。
 *
 * 缓存读写单独记,是因为算成本时它们的单价跟普通输入差一个数量级
 * (缓存命中通常只要十分之一),混在 inputTokens 里成本就没法估准。
 */
@Entity(tableName = "usage_records")
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ts: Long = System.currentTimeMillis(),
    val sessionId: Long = 0,
    val model: String = "",
    val provider: String = "",
    /** 谁产生的:chat / compact / review / subagent / judge / cron … 与 FunctionModels 的 key 对齐。 */
    val source: String = "chat",
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val reasoningTokens: Long = 0
)

/** 按天聚合的结果(给趋势图用)。 */
data class DailyUsage(
    val day: String,        // yyyy-MM-dd
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val calls: Long
)

/** 按模型聚合的结果(给占比图用)。 */
data class ModelUsage(
    val model: String,
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val calls: Long
)

@Dao
interface UsageRecordDao {

    @Insert
    suspend fun insert(record: UsageRecordEntity): Long

    /**
     * 按天聚合。
     * 用 SQLite 的 date(ts/1000, 'unixepoch', 'localtime') —— 必须带 localtime,
     * 否则跨时区时「今天」会错位一整天(UTC 早上 8 点前算前一天)。
     */
    @Query("""
        SELECT date(ts/1000, 'unixepoch', 'localtime') AS day,
               SUM(inputTokens) AS input,
               SUM(outputTokens) AS output,
               SUM(cacheReadTokens) AS cacheRead,
               COUNT(*) AS calls
        FROM usage_records
        WHERE ts >= :since
        GROUP BY day
        ORDER BY day ASC
    """)
    suspend fun dailySince(since: Long): List<DailyUsage>

    @Query("""
        SELECT model,
               SUM(inputTokens) AS input,
               SUM(outputTokens) AS output,
               SUM(cacheReadTokens) AS cacheRead,
               COUNT(*) AS calls
        FROM usage_records
        WHERE ts >= :since AND model != ''
        GROUP BY model
        ORDER BY (SUM(inputTokens) + SUM(outputTokens)) DESC
    """)
    suspend fun byModelSince(since: Long): List<ModelUsage>

    @Query("SELECT COUNT(*) FROM usage_records WHERE ts >= :since")
    suspend fun callCountSince(since: Long): Long

    @Query("SELECT SUM(inputTokens) FROM usage_records WHERE ts >= :since")
    suspend fun totalInputSince(since: Long): Long?

    @Query("SELECT SUM(outputTokens) FROM usage_records WHERE ts >= :since")
    suspend fun totalOutputSince(since: Long): Long?

    @Query("SELECT SUM(cacheReadTokens) FROM usage_records WHERE ts >= :since")
    suspend fun totalCacheReadSince(since: Long): Long?

    /** 定期清理:只保留最近 N 天,免得这张表无限长。 */
    @Query("DELETE FROM usage_records WHERE ts < :before")
    suspend fun deleteBefore(before: Long)
}
