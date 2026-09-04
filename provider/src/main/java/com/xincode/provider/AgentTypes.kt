package com.xincode.provider

/**
 * Types shared between provider (OpenAiClient) and core (AgentCore).
 * Both modules depend on these definitions without circular dependency.
 */

/** A tool call extracted from the model's streaming response. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,  // raw JSON string (model-generated)
    /** Gemini thought signature; must survive the assistant tool-call round trip. */
    val thoughtSignature: String = ""
)

/** Complete result of one agent streaming call. */
data class AgentStreamResult(
    val content: String,                 // accumulated text (may be empty if only tool_calls)
    val toolCalls: List<ToolCall>,        // tool calls requested by model (empty = final response)
    val usage: org.json.JSONObject? = null,  // usage metrics from final SSE chunk (DeepSeek cache stats)
    /**
     * 流在收到结束标记之前就断了(移动网络切换、网关超时、服务端掐流)。
     *
     * 为什么必须显式标记:SSE 读取循环遇到连接中断时会「正常」退出,若不区分就会把半截回复
     * 当成完整回复交给上层。而半截回复往往不含 tool_calls → agent 循环判定为最终回答 → 无声
     * 停工(用户看到的「做一点就罢工」)。标记后由 AgentCore 决定续跑还是报错。
     */
    val truncated: Boolean = false
)