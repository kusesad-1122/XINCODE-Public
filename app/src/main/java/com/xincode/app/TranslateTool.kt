package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import com.xincode.security.KeystoreProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型委托·翻译:把文本交给单独配置的翻译副模型,返回目标语言译文。服务门控。
 */
class TranslateTool(
    private val database: AppDatabase,
    private val keystore: KeystoreProvider
) : Tool {
    override val name = "translate_text"
    override val description =
        "把一段文本交给翻译副模型翻译。传 text=原文,target=目标语言(如 en / zh / ja / 英文)。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply { put("type", "string"); put("description", "要翻译的原文") })
            put("target", JSONObject().apply { put("type", "string"); put("description", "目标语言,如 en/zh/ja 或 中文/英文") })
        })
        put("required", JSONArray(listOf("text", "target")))
    }

    override fun isAvailable(): Boolean = kotlinx.coroutines.runBlocking {
        try { AuxModels.isConfigured(database, "translate") } catch (_: Exception) { false }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val text = params["text"]?.trim().orEmpty()
        val target = params["target"]?.trim()?.ifBlank { "英文" } ?: "英文"
        if (text.isEmpty()) return ToolResult.Error("缺少 text")
        return AuxModels.chat(database, keystore, "translate",
            systemPrompt = "你是专业翻译。只输出译文本身,不加解释、不加引号。",
            userText = "把下面内容翻译成【$target】:\n\n$text"
        ).fold(
            onSuccess = { ToolResult.Success(it) },
            onFailure = { ToolResult.Error("translate_text 失败: ${it.message}") }
        )
    }
}
