package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a connected MCP (Model Context Protocol) server.
 * Stores the URL, optional auth header, and connection state.
 */
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    /** Optional auth header value, format: "Bearer xxx" or "ApiKey xxx". Empty = no auth. */
    val authHeader: String = "",
    /** Whether the server is currently connected (tools discovered). */
    val connected: Boolean = false,
    /** Comma-separated list of tool names discovered from this server. */
    val toolNames: String = "",
    // gap-22 传输方式:"http"(默认,用 url)| "stdio"(本地进程,用 command/args/env)。
    val transport: String = "http",
    val command: String = "",
    val argsJson: String = "",   // JSON 数组字符串,如 ["-y","@modelcontextprotocol/server-filesystem","/sdcard"]
    val envJson: String = "",    // JSON 对象字符串
    val runAsRoot: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
