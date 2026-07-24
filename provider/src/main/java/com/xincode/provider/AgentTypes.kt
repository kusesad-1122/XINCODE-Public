package com.xincode.provider

/**
 * Types shared between provider (OpenAiClient) and core (AgentCore).
 * Both modules depend on these definitions without circular dependency.
 */

/** A tool call extracted from the model's streaming response. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String  // raw JSON string (model-generated)
)

/** Complete result of one agent streaming call. */
data class AgentStreamResult(
    val content: String,                 // accumulated text (may be empty if only tool_calls)
    val toolCalls: List<ToolCall>,        // tool calls requested by model (empty = final response)
    val usage: org.json.JSONObject? = null  // usage metrics from final SSE chunk (DeepSeek cache stats)
)