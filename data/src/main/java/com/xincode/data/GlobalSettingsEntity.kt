package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "global_settings")
data class GlobalSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val globalSystemPrompt: String = "",
    /** gap-19 结构化输出:chat 形态 response_format 的 JSON 文本(空=不启用)。 */
    val structuredOutputSchema: String = "",
    /** 上下文窗口覆盖(tokens,0=沿用供应商配置)。用户可在设置里自定义,或用 256k/1M 快捷键。 */
    val contextWindowOverride: Int = 0,
    /** 自动压缩阈值百分比覆盖(0=沿用供应商默认 85)。用户想"接近填满(如 99%)才压缩"就设 99。 */
    val autoCompactThresholdOverride: Int = 0,
    /** 自定义总结规则:压缩上下文时追加给总结模型的额外要求(空=用默认规则)。 */
    val customSummaryRule: String = ""
)