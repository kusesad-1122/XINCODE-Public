package com.xincode.tools

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.provider.McpTransport
import com.xincode.provider.McpToolInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Adapter that wraps an MCP server tool as a XINCODE [Tool].
 * Registered in the ToolRegistry so the model can call it like any native tool.
 *
 * Tool name is prefixed with "mcp:{toolName}" to avoid collision with native tools.
 */
class McpToolAdapter(
    private val mcpClient: McpTransport,          // gap-22 传输抽象:HTTP 或 stdio 均可
    private val mcpTool: McpToolInfo,
    private val serverName: String = "server"   // gap-21 二级命名空间
) : Tool {

    // gap-21:限定名 mcp__{server}__{tool},避免跨 server 同名工具在 ToolRegistry 互相覆盖。
    override val name: String = "mcp__${sanitize(serverName)}__${sanitize(mcpTool.name)}"

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

    override val description: String = buildString {
        append("[MCP] ${mcpTool.description}")
        val props = mcpTool.inputSchema.optJSONObject("properties")
        if (props != null && props.length() > 0) {
            append("\nParameters: ")
            val keys = props.keys()
            val paramList = mutableListOf<String>()
            while (keys.hasNext()) {
                val key = keys.next()
                val prop = props.getJSONObject(key)
                val type = prop.optString("type", "string")
                val desc = prop.optString("description", "")
                paramList.add("$key ($type): $desc")
            }
            append(paramList.joinToString("; "))
        }
    }

    override val parametersSchema: JSONObject = mcpTool.inputSchema

    // gap-21:走结构化入口,按 inputSchema 保留 number/boolean/object/array 类型,不再全降级为 String。
    override suspend fun executeJson(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        callWith(args)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val argsJson = JSONObject()
        for ((k, v) in params) argsJson.put(k, v)
        callWith(argsJson)
    }

    private suspend fun callWith(argsJson: JSONObject): ToolResult {
        return try {
            val rawResult = mcpClient.callTool(mcpTool.name, argsJson)
            val truncated = rawResult.let {
                if (it.length > 4000) it.take(2000) +
                    "\n[...truncated ${it.length - 4000} chars...]\n" +
                    it.takeLast(2000)
                else it
            }
            ToolResult.Success(truncated)
        } catch (e: Exception) {
            ToolResult.Error("MCP tool '${mcpTool.name}' failed: ${e.message}")
        }
    }
}