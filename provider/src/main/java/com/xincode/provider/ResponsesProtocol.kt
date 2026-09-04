package com.xincode.provider

import org.json.JSONArray
import org.json.JSONObject

/** Normalized result shared by non-streaming and streaming Responses calls. */
data class ResponsesResult(
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: JSONObject? = null,
    val truncated: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Pure JSON protocol helpers for OpenAI's Responses API.
 *
 * Keeping this code independent from Android/OkHttp makes the protocol mapping
 * testable on the JVM and keeps API keys outside of the request-building layer.
 */
object ResponsesProtocol {
    private val VERSION_SEGMENT = Regex("/v\\d+[a-zA-Z0-9]*$")

    fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/responses")) return base
        return base + if (VERSION_SEGMENT.containsMatchIn(base)) "/responses" else "/v1/responses"
    }

    fun buildRequest(
        model: String,
        messages: List<JSONObject>,
        tools: JSONArray = JSONArray(),
        temperature: Float? = null,
        maxOutputTokens: Int? = null,
        topP: Float? = null,
        responseFormat: JSONObject? = null,
        stream: Boolean = false,
        thinkingEnabled: Boolean = false,
        thinkingLevel: Int = 2
    ): JSONObject {
        val (instructions, input) = buildInput(messages)
        return JSONObject().apply {
            put("model", model)
            if (instructions.isNotBlank()) put("instructions", instructions)
            put("input", input)
            if (tools.length() > 0) put("tools", convertTools(tools))

            if (thinkingEnabled) {
                put("reasoning", JSONObject().apply {
                    put("effort", when (thinkingLevel) {
                        0 -> "low"
                        1 -> "medium"
                        else -> "high"
                    })
                    put("summary", "auto")
                })
            } else {
                temperature?.let { put("temperature", it) }
                topP?.let { put("top_p", it) }
            }

            if (maxOutputTokens != null && maxOutputTokens > 0) {
                put("max_output_tokens", maxOutputTokens)
            }
            responseFormatToText(responseFormat)?.let { put("text", it) }
            put("stream", stream)
        }
    }

    fun extractResponse(response: JSONObject): ResponsesResult {
        val output = response.optJSONArray("output")
        val content = response.safeString("output_text").ifBlank {
            extractOutputText(output)
        }
        val toolCalls = extractToolCalls(output)
        val status = response.safeString("status")
        val errorMessage = response.optJSONObject("error")?.safeString("message")
            ?.ifBlank { null }
            ?: if (status == "failed") "Responses response failed" else null
        return ResponsesResult(
            content = content,
            toolCalls = toolCalls,
            usage = normalizeUsage(response.optJSONObject("usage")),
            truncated = status == "incomplete",
            errorMessage = errorMessage
        )
    }

    /** Convert Responses usage names to the usage shape already consumed by XINCODE. */
    fun normalizeUsage(usage: JSONObject?): JSONObject? {
        if (usage == null) return null
        val normalized = JSONObject()
        val keys = usage.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            normalized.put(key, usage.opt(key))
        }

        val inputTokens = usage.optLong("input_tokens", Long.MIN_VALUE)
        val outputTokens = usage.optLong("output_tokens", Long.MIN_VALUE)
        if (inputTokens != Long.MIN_VALUE && !normalized.has("prompt_tokens")) {
            normalized.put("prompt_tokens", inputTokens)
        }
        if (outputTokens != Long.MIN_VALUE && !normalized.has("completion_tokens")) {
            normalized.put("completion_tokens", outputTokens)
        }
        if (!normalized.has("total_tokens") &&
            inputTokens != Long.MIN_VALUE && outputTokens != Long.MIN_VALUE
        ) {
            normalized.put("total_tokens", inputTokens + outputTokens)
        }
        return normalized
    }

    private fun buildInput(messages: List<JSONObject>): Pair<String, JSONArray> {
        val instructions = StringBuilder()
        val input = JSONArray()
        messages.forEach { message ->
            when (message.safeString("role")) {
                "system" -> {
                    val text = message.safeString("content")
                    if (text.isNotBlank()) {
                        if (instructions.isNotEmpty()) instructions.append("\n\n")
                        instructions.append(text)
                    }
                }
                "user" -> input.put(JSONObject().apply {
                    put("role", "user")
                    putValue("content", message.opt("content"))
                })
                "assistant" -> {
                    val content = message.opt("content")
                    if (content != null && content !== JSONObject.NULL && content.toString().isNotBlank()) {
                        input.put(JSONObject().apply {
                            put("role", "assistant")
                            putValue("content", content)
                        })
                    }
                    message.optJSONArray("tool_calls")?.let { calls ->
                        for (index in 0 until calls.length()) {
                            val call = calls.optJSONObject(index) ?: continue
                            val function = call.optJSONObject("function") ?: continue
                            input.put(JSONObject().apply {
                                put("type", "function_call")
                                put("call_id", call.safeString("id").ifBlank { call.safeString("call_id") })
                                put("name", function.safeString("name"))
                                put("arguments", function.safeString("arguments").ifBlank { "{}" })
                            })
                        }
                    }
                }
                "tool" -> input.put(JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", message.safeString("tool_call_id"))
                    putValue("output", message.opt("content"))
                })
            }
        }
        return instructions.toString() to input
    }

    private fun convertTools(tools: JSONArray): JSONArray {
        val converted = JSONArray()
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            val function = tool.optJSONObject("function") ?: continue
            converted.put(JSONObject().apply {
                put("type", "function")
                put("name", function.safeString("name"))
                function.safeString("description").ifBlank { null }?.let { put("description", it) }
                function.optJSONObject("parameters")?.let { put("parameters", it) }
                if (function.has("strict") && !function.isNull("strict")) {
                    put("strict", function.optBoolean("strict"))
                }
            })
        }
        return converted
    }

    private fun responseFormatToText(responseFormat: JSONObject?): JSONObject? {
        if (responseFormat == null) return null
        val format = JSONObject()
        val nestedSchema = responseFormat.optJSONObject("json_schema")
        if (nestedSchema != null) {
            format.put("type", responseFormat.safeString("type").ifBlank { "json_schema" })
            format.put("name", nestedSchema.safeString("name").ifBlank { "response" })
            nestedSchema.optJSONObject("schema")?.let { format.put("schema", it) }
            if (nestedSchema.has("strict") && !nestedSchema.isNull("strict")) {
                format.put("strict", nestedSchema.optBoolean("strict"))
            }
        } else {
            val source = responseFormat.optJSONObject("format") ?: responseFormat
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                format.put(key, source.opt(key))
            }
        }
        return JSONObject().put("format", format)
    }

    private fun extractOutputText(output: JSONArray?): String {
        if (output == null) return ""
        val text = StringBuilder()
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            when (item.safeString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: continue
                    for (contentIndex in 0 until content.length()) {
                        val part = content.optJSONObject(contentIndex) ?: continue
                        if (part.safeString("type") in setOf("output_text", "text")) {
                            text.append(part.safeString("text"))
                        }
                    }
                }
                "output_text" -> text.append(item.safeString("text"))
            }
        }
        return text.toString()
    }

    private fun extractToolCalls(output: JSONArray?): List<ToolCall> {
        if (output == null) return emptyList()
        val calls = mutableListOf<ToolCall>()
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            if (item.safeString("type") != "function_call") continue
            val id = item.safeString("call_id").ifBlank { item.safeString("id") }
            val name = item.safeString("name")
            if (id.isNotBlank() && name.isNotBlank()) {
                val extra = item.optJSONObject("extra_content") ?: item.optJSONObject("extraContent")
                val google = extra?.optJSONObject("google") ?: item.optJSONObject("google")
                val signature = item.safeString("thought_signature").ifBlank { google?.safeString("thought_signature").orEmpty() }
                calls += ToolCall(id, name, item.safeString("arguments").ifBlank { "{}" }, signature)
            }
        }
        return calls
    }

    private fun JSONObject.putValue(key: String, value: Any?) {
        if (value != null && value !== JSONObject.NULL) put(key, value)
        else put(key, "")
    }
}

private fun JSONObject.safeString(key: String): String =
    if (isNull(key)) "" else optString(key, "")
