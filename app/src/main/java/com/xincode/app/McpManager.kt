package com.xincode.app

import android.util.Log
import com.xincode.core.ToolRegistry
import com.xincode.data.AppDatabase
import com.xincode.data.McpServerEntity
import com.xincode.provider.McpClient
import com.xincode.provider.McpServerInfo
import com.xincode.security.KeystoreProvider
import com.xincode.tools.McpToolAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Manages MCP server connections and synchronizes their tools to the ToolRegistry.
 *
 * Lifecycle:
 * - connectServer(): initialize handshake → discover tools → register adapters → update Room
 * - disconnectServer(): disconnect client → unregister adapters → update Room
 * - syncFromRoom(): on startup, restore previously connected servers
 *
 * 安全:auth_header 在 Room 里以 Keystore 加密存放(1.13.6 起);旧版本的明文存量
 * 解不开时按原文回退使用,不影响重连。
 */
class McpManager(
    private val database: AppDatabase,
    private val toolRegistry: ToolRegistry,
    private val okHttpClient: OkHttpClient,
    private val keystore: KeystoreProvider
) {
    companion object {
        private const val TAG = "McpManager"
    }

    /** 鉴权头加密入库;空串原样返回。 */
    private fun encryptHeader(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            android.util.Base64.encodeToString(keystore.encrypt(raw), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            raw
        }
    }

    /** 鉴权头解密使用;解不开视为旧版明文存量,原样返回。 */
    private fun decryptHeader(stored: String): String {
        if (stored.isBlank()) return ""
        return try {
            keystore.decrypt(android.util.Base64.decode(stored, android.util.Base64.NO_WRAP))
        } catch (_: Exception) {
            stored
        }
    }

    /** Active connections: key(url 或 stdio:name)→ 传输(HTTP/stdio) */
    private val clients = mutableMapOf<String, com.xincode.provider.McpTransport>()

    /** Active tool adapters registered in ToolRegistry: server URL → tool adapter names */
    private val registeredTools = mutableMapOf<String, List<String>>()

    /** Connect to an MCP server, discover tools, and register them. */
    suspend fun connectServer(
        name: String,
        url: String,
        authHeader: String = ""
    ): McpConnectResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Connecting to MCP server: $name ($url)")

            // Create and initialize client
            val client = McpClient(okHttpClient, url, authHeader)
            val serverInfo = client.initialize()

            // Discover tools
            val tools = client.listTools()
            Log.i(TAG, "Discovered ${tools.size} tools from $name")

            // Register adapters
            val adapterNames = mutableListOf<String>()
            for (toolInfo in tools) {
                val adapter = McpToolAdapter(client, toolInfo, name) // gap-21 传入 server 名做命名空间
                toolRegistry.register(adapter)
                adapterNames.add(adapter.name)
            }

            // Store connection(重连同一地址时先关掉旧传输,避免泄漏旧客户端/子进程)
            clients[url]?.let { runCatching { it.close() } }
            clients[url] = client
            registeredTools[url] = adapterNames

            // Update Room(authHeader 加密存放)
            val existing = database.mcpServerDao().getByUrl(url)
            val entity = McpServerEntity(
                id = existing?.id ?: 0,
                name = name,
                url = url,
                authHeader = encryptHeader(authHeader),
                connected = true,
                toolNames = tools.joinToString(",") { it.name },
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            database.mcpServerDao().upsert(entity)

            McpConnectResult.Success(
                serverName = serverInfo.name,
                toolCount = tools.size,
                toolNames = tools.map { it.name }
            )
        } catch (e: Exception) {
            Log.e(TAG, "MCP connect failed: ${e.message}", e)
            McpConnectResult.Error(e.message ?: "连接失败")
        }
    }

    /**
     * gap-22:连接【本地 stdio】MCP 服务器——ProcessBuilder 拉起 command/args/env(可选 root),
     * 管道走 JSON-RPC。设备需具备可执行运行时(Termux 的 node/npx/uvx 或 root 可执行二进制)。
     * key 用 "stdio:$name"。
     */
    suspend fun connectStdioServer(
        name: String,
        command: String,
        args: List<String>,
        env: Map<String, String>,
        runAsRoot: Boolean = false
    ): McpConnectResult = withContext(Dispatchers.IO) {
        val key = "stdio:$name"
        try {
            Log.i(TAG, "Connecting to stdio MCP server: $name ($command)")
            val client = com.xincode.provider.McpStdioClient(command, args, env, runAsRoot)
            val serverInfo = client.initialize()
            val tools = client.listTools()
            val adapterNames = mutableListOf<String>()
            for (toolInfo in tools) {
                val adapter = McpToolAdapter(client, toolInfo, name)
                toolRegistry.register(adapter)
                adapterNames.add(adapter.name)
            }
            clients[key]?.let { runCatching { it.close() } }
            clients[key] = client
            registeredTools[key] = adapterNames

            val existing = database.mcpServerDao().getAll().firstOrNull { it.transport == "stdio" && it.name == name }
            val entity = McpServerEntity(
                id = existing?.id ?: 0,
                name = name,
                url = existing?.url ?: "",
                connected = true,
                toolNames = tools.joinToString(",") { it.name },
                transport = "stdio",
                command = command,
                argsJson = org.json.JSONArray(args).toString(),
                envJson = org.json.JSONObject().apply { env.forEach { (k, v) -> put(k, v) } }.toString(),
                runAsRoot = runAsRoot,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            database.mcpServerDao().upsert(entity)
            McpConnectResult.Success(serverInfo.name, tools.size, tools.map { it.name })
        } catch (e: Exception) {
            Log.e(TAG, "stdio MCP connect failed: ${e.message}", e)
            clients[key]?.close(); clients.remove(key)
            McpConnectResult.Error(e.message ?: "本地 MCP 连接失败(检查 command 是否可执行,如 Termux 的 node/npx)")
        }
    }

    /** Disconnect from an MCP server and unregister its tools. */
    suspend fun disconnectServer(url: String): Unit = withContext(Dispatchers.IO) {
        Log.i(TAG, "Disconnecting from MCP server: $url")

        // Unregister adapters
        val adapterNames = registeredTools[url] ?: emptyList()
        for (name in adapterNames) {
            toolRegistry.unregister(name)
        }
        registeredTools.remove(url)

        // Disconnect client
        clients[url]?.close()
        clients.remove(url)

        // Update Room
        val entity = database.mcpServerDao().getByUrl(url)
        if (entity != null) {
            database.mcpServerDao().update(entity.copy(connected = false, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Restore connections from previously connected servers in Room. */
    suspend fun syncFromRoom(): List<McpConnectResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<McpConnectResult>()
        val servers = database.mcpServerDao().getAll()

        for (server in servers) {
            if (!server.connected) continue
            val result = if (server.transport == "stdio") {
                // gap-22:恢复本地 stdio MCP 服务器。
                val args = parseJsonArray(server.argsJson)
                val env = parseJsonObject(server.envJson)
                connectStdioServer(server.name, server.command, args, env, server.runAsRoot)
            } else {
                connectServer(server.name, server.url, decryptHeader(server.authHeader))
            }
            // 启动重连失败:把 connected 落回 false,让插件页/MCP 页如实显示未连接,
            // 否则会出现「卡片显示已安装、AI 却说没有这个工具」的矛盾状态。
            if (result is McpConnectResult.Error) {
                runCatching {
                    database.mcpServerDao().update(server.copy(connected = false, updatedAt = System.currentTimeMillis()))
                }
            }
            results.add(result)
        }

        results
    }

    private fun parseJsonArray(s: String): List<String> = try {
        val a = org.json.JSONArray(s.ifBlank { "[]" }); (0 until a.length()).map { a.optString(it) }
    } catch (_: Exception) { emptyList() }

    private fun parseJsonObject(s: String): Map<String, String> = try {
        val o = org.json.JSONObject(s.ifBlank { "{}" }); o.keys().asSequence().associateWith { o.optString(it) }
    } catch (_: Exception) { emptyMap() }

    /** Get all stored MCP server entities. */
    suspend fun getAllServers(): List<McpServerEntity> {
        return database.mcpServerDao().getAll()
    }

    /** Delete an MCP server entry from Room (disconnects first if connected). */
    suspend fun deleteServer(url: String) {
        if (clients.containsKey(url)) {
            disconnectServer(url)
        }
        val entity = database.mcpServerDao().getByUrl(url)
        if (entity != null) {
            database.mcpServerDao().deleteById(entity.id)
        }
    }
}

sealed class McpConnectResult {
    data class Success(
        val serverName: String,
        val toolCount: Int,
        val toolNames: List<String>
    ) : McpConnectResult()

    data class Error(val message: String) : McpConnectResult()
}