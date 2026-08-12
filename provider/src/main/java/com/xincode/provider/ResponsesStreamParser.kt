package com.xincode.provider

import org.json.JSONObject
import java.util.TreeMap

/** Aggregates one Responses API SSE stream into the same result shape as chat completions. */
class ResponsesStreamParser(
    private val onText: (String) -> Unit = {},
    private val onReasoning: (String) -> Unit = {}
) {
    private enum class Terminal { NONE, COMPLETED, INCOMPLETE }

    private data class ToolAccumulator(
        var id: String = "",
        var callId: String = "",
        var name: String = "",
        var arguments: String = ""
    )

    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val tools = TreeMap<Int, ToolAccumulator>()
    private var usage: JSONObject? = null
    private var terminal = Terminal.NONE
    private var errorMessage: String? = null

    fun accept(rawLine: String) {
        val line = rawLine.trimStart()
        if (!line.startsWith("data:")) return
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return
        if (payload == "[DONE]") {
            if (terminal == Terminal.NONE) terminal = Terminal.COMPLETED
            return
        }

        val event = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return
        }
        when (event.safeString("type")) {
            "response.output_text.delta" -> {
                val delta = event.safeString("delta")
                if (delta.isNotEmpty()) {
                    content.append(delta)
                    onText(delta)
                }
            }
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                val delta = event.safeString("delta")
                if (delta.isNotEmpty()) {
                    reasoning.append(delta)
                    onReasoning(delta)
                }
            }
            "response.output_item.added" -> {
                val item = event.optJSONObject("item") ?: return
                if (item.safeString("type") == "function_call") {
                    val index = event.optInt("output_index", tools.size)
                    val acc = tools.getOrPut(index) { ToolAccumulator() }
                    updateTool(acc, item)
                }
            }
            "response.function_call_arguments.delta" -> {
                val index = event.optInt("output_index", -1)
                if (index >= 0) {
                    val acc = tools.getOrPut(index) { ToolAccumulator() }
                    acc.arguments += event.safeString("delta")
                }
            }
            "response.function_call_arguments.done" -> {
                val index = event.optInt("output_index", -1)
                if (index >= 0) {
                    val acc = tools.getOrPut(index) { ToolAccumulator() }
                    if (event.has("arguments") && !event.isNull("arguments")) {
                        acc.arguments = event.safeString("arguments")
                    }
                }
            }
            "response.output_item.done" -> {
                val item = event.optJSONObject("item") ?: return
                if (item.safeString("type") == "function_call") {
                    val index = event.optInt("output_index", tools.size)
                    val acc = tools.getOrPut(index) { ToolAccumulator() }
                    updateTool(acc, item)
                }
            }
            "response.completed" -> {
                terminal = Terminal.COMPLETED
                updateUsage(event.optJSONObject("response")?.optJSONObject("usage"))
            }
            "response.incomplete" -> {
                terminal = Terminal.INCOMPLETE
                updateUsage(event.optJSONObject("response")?.optJSONObject("usage"))
            }
            "response.failed", "error" -> {
                val responseError = event.optJSONObject("response")?.optJSONObject("error")
                val eventError = event.optJSONObject("error")
                errorMessage = responseError?.safeString("message")?.ifBlank { null }
                    ?: eventError?.safeString("message")?.ifBlank { null }
                    ?: event.safeString("message").ifBlank { "Responses stream error" }
            }
        }
    }

    fun result(): ResponsesResult = ResponsesResult(
        content = content.toString(),
        reasoning = reasoning.toString(),
        toolCalls = tools.values.mapNotNull { acc ->
            val id = acc.callId.ifBlank { acc.id }
            if (id.isBlank() || acc.name.isBlank()) null
            else ToolCall(id, acc.name, acc.arguments.ifBlank { "{}" })
        },
        usage = usage,
        truncated = terminal == Terminal.NONE || terminal == Terminal.INCOMPLETE,
        errorMessage = errorMessage
    )

    private fun updateTool(acc: ToolAccumulator, item: JSONObject) {
        item.safeString("id").ifBlank { "" }.takeIf { it.isNotBlank() }?.let { acc.id = it }
        item.safeString("call_id").ifBlank { "" }.takeIf { it.isNotBlank() }?.let { acc.callId = it }
        item.safeString("name").ifBlank { "" }.takeIf { it.isNotBlank() }?.let { acc.name = it }
        if (item.has("arguments") && !item.isNull("arguments")) {
            acc.arguments = item.safeString("arguments")
        }
    }

    private fun updateUsage(candidate: JSONObject?) {
        if (candidate != null) usage = ResponsesProtocol.normalizeUsage(candidate)
    }

    private fun JSONObject.safeString(key: String): String =
        if (isNull(key)) "" else optString(key, "")
}
