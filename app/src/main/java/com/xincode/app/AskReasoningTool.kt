package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import com.xincode.security.KeystoreProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型委托·深度推理:把一个难的子问题转交给单独配置的**更强推理模型**(如 deepseek-reasoner / o1),
 * 拿回它的推理结论。适合主模型算不动的数学/逻辑/复杂规划子问题。服务门控。
 */
class AskReasoningTool(
    private val database: AppDatabase,
    private val keystore: KeystoreProvider
) : Tool {
    override val name = "ask_reasoning"
    override val description =
        "把一个较难的子问题交给更强的推理副模型思考,返回其结论。适合复杂数学/逻辑/规划等你没把握的子问题。" +
        "传 question=要问的问题(可带必要上下文)。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("question", JSONObject().apply { put("type", "string"); put("description", "要副模型深度思考的问题(含必要上下文)") })
        })
        put("required", JSONArray(listOf("question")))
    }

    override fun isAvailable(): Boolean = kotlinx.coroutines.runBlocking {
        try { AuxModels.isConfigured(database, "reason") } catch (_: Exception) { false }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val q = params["question"]?.trim().orEmpty()
        if (q.isEmpty()) return ToolResult.Error("缺少 question")
        return AuxModels.chat(database, keystore, "reason",
            systemPrompt = "你是严谨的推理助手。一步步想清楚,给出最终结论,并简述关键推理。",
            userText = q
        ).fold(
            onSuccess = { ToolResult.Success(it) },
            onFailure = { ToolResult.Error("ask_reasoning 失败: ${it.message}") }
        )
    }
}
