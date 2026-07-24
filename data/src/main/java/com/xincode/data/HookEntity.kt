package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * gap-24 生命周期 hook 配置。
 * - event: 触发点(session_start/user_prompt_submit/pre_tool/post_tool/session_end)。
 * - matcher: 可选,pre_tool/post_tool 时按工具名前缀过滤(空=所有工具)。
 * - command: 触发时执行的 shell 命令(可用占位:$TOOL $ARGS $STATUS)。
 */
@Entity(tableName = "hooks")
data class HookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val event: String,
    val matcher: String = "",
    val command: String,
    val runAsRoot: Boolean = false,
    val enabled: Boolean = true,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
