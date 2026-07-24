package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An identity card: a system prompt template defining the AI's role, personality, and temperature.
 * Sessions lock onto one identity at creation time.
 */
@Entity(tableName = "identities")
data class IdentityEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val systemPrompt: String,
    val temperature: Float = 1.0f,
    val isStarred: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // gap-09:采样上限与核采样(null=不发,走服务端默认)。
    val maxTokens: Int? = null,
    val topP: Float? = null
)
