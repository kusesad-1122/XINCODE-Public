package com.xincode.provider

import org.json.JSONObject

/**
 * gap-22 MCP 传输抽象:HTTP/SSE(McpClient)与本地 stdio 进程(McpStdioClient)共用同一接口,
 * 上层 McpToolAdapter / McpManager 与传输方式解耦。
 */
interface McpTransport {
    suspend fun initialize(): McpServerInfo
    suspend fun listTools(): List<McpToolInfo>
    suspend fun callTool(toolName: String, arguments: JSONObject): String
    fun close() {}
}
