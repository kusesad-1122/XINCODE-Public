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
    /** 所属项目 id(0=全局/无项目)。用于记忆按项目隔离。 */
    val projectId: Long = 0L,
    /** Comma-separated tags. */
    val tags: String = "",
    /** Originating assistant message ID. */
    val sourceMessageId: Long = 0L,
    /** Vector embedding (FloatArray serialized as BLOB). Null if not yet computed. */
    val embedding: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)