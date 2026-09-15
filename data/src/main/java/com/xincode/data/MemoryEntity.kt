package com.xincode.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent memory extracted from conversations.
 *
 * Dedup key: [title]. Same title → upsert (OnConflictStrategy.REPLACE).
 * [tags] is comma-separated for simple keyword indexing.
 * [sourceMessageId] links back to the originating assistant message.
 */
@Entity(tableName = "memories", indices = [Index(value = ["title", "projectId"], unique = true)])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Memory title. Used as dedup key. */
    val title: String,
    /** Full memory content. */
    val content: String,
    /**
     * 记忆来源:note=可检索知识条目,user=用户偏好/资料,situation=近况,
     * assistant=助手消息自动沉淀,agent=后台复盘分身写入。
     */
    val source: String = "note",
    /** 所属项目 id(0=全局/无项目)。用于记忆按项目隔离。 */
    val projectId: Long = 0L,
    /** Comma-separated tags. */
    val tags: String = "",
    /** Originating assistant message ID. */
    val sourceMessageId: Long = 0L,
    /** Vector embedding (FloatArray serialized as BLOB). Null if not yet computed. */
    val embedding: ByteArray? = null,
    /** 被按需召回命中过几次(衡量记忆价值,后续可据此做衰减/清理)。 */
    val recallCount: Int = 0,
    val lastRecalledAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * 生命周期标记:1=已归档(休眠)。归档的记忆【不参与自动召回】(不注入系统提示、不在
     * `MemoryRecall.recallForQuery` 候选里),但【仍可被检索】(get_memory_by_title 精确取、
     * recall_memory 的 FTS 仍命中),用户显式查到时会自动复活。0=活跃。
     *
     * 由 `MemoryDecay.sweep` 周期性写入:一条记忆「被召回过、之后长期(>ARCHIVE_AFTER_DAYS)不再被召回」
     * 才归档;从未被召回过的记忆(存量数据也多半如此)不纳入老化,避免一次性误清大量老记忆。
     */
    val archived: Int = 0,
    /**
     * 活跃度权重[DECAY_WEIGHT_FLOOR, 1.0]:`MemoryDecay.sweep` 按「距上次召回的天数」线性降权,
     * 召回打分(MemoryRecall.rankMemoriesByEmbedding)会把综合得分乘它。1.0=未衰减。
     * 注意这是「加成项」的乘数,不是替代:相似度明显更高的记忆即使权重更低仍排前面。
     */
    val decayWeight: Float = 1.0f
)
