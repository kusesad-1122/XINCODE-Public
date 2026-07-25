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
    val topP: Float? = null,

    // ---- 身份设定扩展(参照 Operit 角色卡,取对编程 agent 真正有用的部分)----
    /** 一句话描述,只在身份列表里显示,不进提示词。 */
    val description: String = "",
    /** 开场白。新建会话时作为第一条 AI 消息落库,让身份一上来就进入状态。 */
    val openingStatement: String = "",
    /** 备注。给自己看的,绝不拼进提示词——Operit 那边同名字段也是这个约定。 */
    val marks: String = "",
    /**
     * 工具白名单(逗号分隔的工具名);空 = 不限制。
     * 用途:让某个身份只用得上它该用的工具,比如「文档撰写」身份不该能执行 shell。
     */
    val allowedTools: String = "",
    /**
     * 绑定的供应商配置 id;0 = 跟随当前活跃配置。
     * 与「功能模型配置」同一套思路:这个身份固定用某个模型。
     */
    val providerConfigId: Long = 0,
    /** 绑定的模型 ID;空 = 用该配置自己的默认模型。 */
    val modelOverride: String = ""
)
