package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 子智能体【类型】定义。主脑(主对话)把任务拆给这些专职子智能体并行处理,各管各的技能与工具。
 *
 * - [systemPrompt]:该子智能体的角色设定(它是谁、擅长什么)。
 * - [skillNames]:它的**专属技能**(逗号分隔)。派活时只把这些技能注入它的系统提示,
 *   让它据任务从【自己这套】里选,而非盲目从全量技能里挑。
 * - [toolNames]:它能用的工具白名单(逗号分隔;空=只读安全工具集)。
 */
@Entity(tableName = "sub_agents")
data class SubAgentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val skillNames: String = "",
    val toolNames: String = "",
    val builtin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
