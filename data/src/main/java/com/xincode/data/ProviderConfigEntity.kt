package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import org.json.JSONArray

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
    val apiPathType: String = "openai",       // "openai" → /v1/chat/completions, "anthropic" → /v1/messages, "custom" → no append
    // gap-08:自定义请求头(JSON 对象字符串),verbatim 注入 inference 请求(BYOK/网关头/归因)。
    val extraHeadersJson: String = "",
    // gap-10:上下文窗口(tokens,0=未声明走默认)与自动压缩阈值百分比。
    val contextWindow: Int = 0,
    val autoCompactThresholdPercent: Int = 85
)

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String = JSONArray(value).toString()

    @TypeConverter
    fun toStringList(value: String): List<String> = try {
        val arr = JSONArray(value)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }
}