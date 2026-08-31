package com.xincode.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "messages", indices = [Index(value = ["sessionId"])])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: Long = 1,
    val reasoning: String? = null,
    val turnId: Long = 0,
    val promptTokens: Long? = null,
    val cacheHitTokens: Long? = null,
    val cacheMissTokens: Long? = null,
    val completionTokens: Long? = null
)