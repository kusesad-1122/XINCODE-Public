package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A skill is a reusable prompt instruction + optional script reference.
 * Stored as markdown in [content]. The model reads skill content when invoked.
 */
@Entity(tableName = "skills", indices = [androidx.room.Index(value = ["name"], unique = true)])
data class SkillEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Skill name — unique identifier users reference in chat. */
    val name: String,
    /** Short description shown in skill list. */
    val description: String = "",
    /** Full markdown content (the actual instructions the model follows). */
    val content: String = "",
    /**
     * Hermes-① 出处/保护档:"user"(用户建,可改)、"bundled"(内置/导入,只读)、
     * "agent"(后台复盘分身自建,可改)。后台复盘只能改 user/agent、不能动 bundled。
     */
    val source: String = "user",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)