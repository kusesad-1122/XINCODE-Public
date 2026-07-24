package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A conversation session. Multiple sessions allow parallel conversations.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val systemPromptOverride: String? = null,
    val currentModelId: String? = null,
    val currentEffortLevel: String? = null,
    val thinkingEnabled: Boolean? = null,
    /** P2: SHA-256(systemPrompt + toolsJson) — for cache stability drift detection. */
    val prefixHash: String? = null,
    /** 5.0: Project grouping. null = 未分组. */
    val projectId: Long? = null,
    /** 5.0: Starred for quick access. */
    val isStarred: Boolean = false,
    /** Phase 2.0 子项目 B: 创建时锁定的身份卡 id. null = 无身份卡(兼容旧数据/身份卡已删除). 发出首条消息后不再改变. */
    val identityId: Long? = null,
    /** Goal/Work 模式:该会话是一个「目标任务」(自主循环+独立裁判验收,可后台跑)。 */
    val isGoal: Boolean = false,
    /** Goal 状态:""=未开始 / "running"=执行中 / "achieved"=已达成 / "failed"=未达成。 */
    val goalStatus: String = ""
)