package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val supplierId: String,
    val baseUrl: String,
    val apiKeyEnc: String,
    val model: String,                        // active model ID (must ∈ enabledModelIds)
    val enabledModelIds: List<String> = emptyList(),  // user-selected models for quick switch
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val apiPathType: String = "openai",       // "openai" → /v1/chat/completions, "responses" → /v1/responses, "anthropic" → /v1/messages, "custom" → no append
    // gap-08:自定义请求头(JSON 对象字符串),verbatim 注入 inference 请求(BYOK/网关头/归因)。
    val extraHeadersJson: String = "",
    // gap-10:上下文窗口(tokens,0=未声明走默认)与自动压缩阈值百分比。
    val contextWindow: Int = 0,
    val autoCompactThresholdPercent: Int = 85,

    // ---- 能力声明 ----
    // 记录「这套配置指向的模型会什么」。两层用途:
    //  1. 功能模型配置页据此校验 —— 把「图像识别」指给一个没勾识图的配置时给出警告;
    //  2. supportsToolCall 直接影响请求体:关掉就不发 tools 字段。
    // 默认值按「保守但不挡路」取:多模态默认关(绝大多数模型不支持),ToolCall 默认开
    // (现有用户都靠原生工具调用在用,默认关会让所有人的 agent 突然罢工)。
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsVideo: Boolean = false,
    val supportsToolCall: Boolean = true,
    /** JSON map: model id -> independent context/output/thinking profile. */
    val modelSettingsJson: String = "{}"
)

/** Per-model limits and reasoning controls shown in the provider configuration UI. */
data class ModelProfile(
    val contextWindow: Int = 0,
    val maxOutputTokens: Int = 0,
    val thinkingEffort: String = "auto",
    val supportsImageInput: Boolean = false
)

object ModelProfileCodec {
    fun decode(raw: String): Map<String, ModelProfile> {
        return try {
            val root = JSONObject(raw.ifBlank { "{}" })
            val result = linkedMapOf<String, ModelProfile>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = root.optJSONObject(key) ?: continue
                result[key] = ModelProfile(
                    contextWindow = item.optInt("contextWindow", 0).coerceAtLeast(0),
                    maxOutputTokens = item.optInt("maxOutputTokens", 0).coerceAtLeast(0),
                    thinkingEffort = item.optString("thinkingEffort", "auto").ifBlank { "auto" },
                    supportsImageInput = item.optBoolean("supportsImageInput", false)
                )
            }
            result
        } catch (_: Exception) { emptyMap() }
    }

    fun encode(profiles: Map<String, ModelProfile>): String = JSONObject().apply {
        profiles.forEach { (model, profile) ->
            put(model, JSONObject().apply {
                put("contextWindow", profile.contextWindow)
                put("maxOutputTokens", profile.maxOutputTokens)
                put("thinkingEffort", profile.thinkingEffort)
                put("supportsImageInput", profile.supportsImageInput)
            })
        }
    }.toString()
}

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = JSONArray(value).toString()

    @TypeConverter
    fun toStringList(value: String): List<String> = try {
        val arr = JSONArray(value)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }
}
