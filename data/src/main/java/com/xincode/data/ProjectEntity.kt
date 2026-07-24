package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A project groups conversations together.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isExpanded: Boolean = true,
    /** 项目级工作区根目录(空=用全局设置/默认)。项目内会话的文件工具以此为基准。 */
    val workspaceRoot: String = ""
)
