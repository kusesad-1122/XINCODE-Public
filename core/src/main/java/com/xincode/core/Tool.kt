package com.xincode.core

import org.json.JSONObject

/**
 * Represents a tool the Agent can call. Each tool = a Kotlin implementation + JSON Schema
 * (for the model to understand when/how to call it).
 */
interface Tool {
    /** Unique name the model uses to request this tool. */
    val name: String

    /** Human-readable description for the model. */
    val description: String

    /**
     * JSON Schema for the tool's parameters, as a [JSONObject].
     * Example: { "type": "object", "properties": { "cmd": { "type": "string" } }, "required": ["cmd"] }
     */
    val parametersSchema: JSONObject

    /**
     * Hermes-③ 服务门控(check_fn):是否把本工具的 schema 发给模型。
     *
     * 默认恒 true(核心工具无条件暴露)。需要前置条件(API key / OAuth / 设备能力)的工具 override:
     * 返回 false 时 [ToolRegistry.buildToolsJson] 不发其 schema——未配置就零 prompt token 成本,
     * 模型也看不到从而不会幻觉调用。返回值经 TTL 缓存,不会每次 API 调用都真探测。
     */
    fun isAvailable(): Boolean = true

    /**
     * [isAvailable] 为 false 时给模型看的原因。
     *
     * 光把 schema 藏起来是不够的:模型完全可能从历史记录里、或者靠猜名字调到一个被关掉的工具。
     * 那时回一句「未知工具」是错的 —— 工具存在,只是前置条件没满足,而模型会当成拼错了名字
     * 接着换着花样猜(实测:联网搜索关着时,它试了 search_web、又试 invoke_skill("联网搜索"),
     * 一路猜到被防空转刹车掐掉)。说清楚是「关着」,它才会转而告诉用户去开。
     */
    fun unavailableReason(): String = "当前不可用(前置条件未满足)"

    /**
     * Execute the tool with parsed [params] (key→value from model's JSON arguments).
     * @return [ToolResult.Success] with the output string, or [ToolResult.Error] with a message
     *         that will be fed back to the model for self-correction.
     */
    suspend fun execute(params: Map<String, String>): ToolResult

    /**
     * gap-05 参数类型保真:结构化入参入口。
     *
     * 模型给出的 arguments 是完整 JSON,可能含 array/object/number/boolean。默认实现把顶层
     * 键压平成 `Map<String,String>`(字符串原样、其它类型转 JSON 文本)再委托 [execute],
     * 对只吃字符串参数的老工具保持 100% 向后兼容。
     *
     * 需要结构化参数的工具(如 multi_edit 的 edits:JSONArray、MCP typed params)override 本方法,
     * 直接从 [args] 读 optJSONArray/optJSONObject/optInt/optBoolean,不丢类型。
     */
    suspend fun executeJson(args: JSONObject): ToolResult {
        val map = HashMap<String, String>(args.length())
        val keys = args.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = if (args.isNull(k)) "" else {
                when (val v = args.get(k)) {
                    is String -> v
                    else -> v.toString() // JSONArray/JSONObject/Number/Boolean → 其 JSON 文本
                }
            }
        }
        return execute(map)
    }
}

/** Result of a tool execution, designed to be fed back to the model. */
sealed class ToolResult {
    /** Tool succeeded. [output] is stdout (truncated). */
    data class Success(val output: String) : ToolResult()

    /**
     * Tool failed. [message] for model self-correction, [exitCode] if applicable,
     * [stderr] feed-back for model to understand what went wrong.
     */
    data class Error(
        val message: String,
        val exitCode: Int? = null,
        val stderr: String? = null
    ) : ToolResult()
}