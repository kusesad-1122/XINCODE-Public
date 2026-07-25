package com.xincode.core

import com.xincode.provider.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registry of available tools. The AgentCore queries this to build the tools[] array
 * in requests, and dispatches tool_calls by name.
 *
 * Adding a new tool = register(Tool) + its schema is auto-generated — no loop changes.
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    // Hermes-③ check_fn TTL 缓存:避免每次 buildToolsJson 都真跑 isAvailable()(可能读设置/探网)。
    private val availabilityCache = mutableMapOf<String, Pair<Long, Boolean>>()
    private val availabilityTtlMs = 30_000L

    /**
     * 协作模式白名单:非空时,`buildToolsJson` 只暴露名字在此集合里的工具,其余「动手」工具对模型
     * 【完全不可见】——从而【硬性】强制主脑把活派给子智能体(弱模型不理会系统提示的软约束)。
     * 只设在【主脑】注册表上;子智能体各自新建注册表(此集合默认空),故不受影响,仍握有全部工具。
     */
    @Volatile
    var collabAllowlist: Set<String> = emptySet()

    /**
     * 身份卡工具白名单:非空时只暴露列出的工具。
     *
     * 与 [collabAllowlist] 是【两道独立的闸门,同时生效】(取交集),不是互相覆盖:
     * 协作模式限制的是「主脑只能派活」,身份卡限制的是「这个角色本来就不该碰某些工具」。
     * 两个都开时,只有同时满足两边的工具才可见——这是符合直觉的收紧,反过来会让任一限制失效。
     */
    @Volatile
    var identityAllowlist: Set<String> = emptySet()

    private fun isAvailableCached(tool: Tool): Boolean {
        val now = System.currentTimeMillis()
        val hit = availabilityCache[tool.name]
        if (hit != null && now - hit.first < availabilityTtlMs) return hit.second
        // 捕 Throwable:isAvailable 内部可能读 DB/探网,抖动或 Error 都不该把整个请求(乃至进程)带崩。
        val v = try { tool.isAvailable() } catch (_: Throwable) { hit?.second ?: true } // 抖动时沿用上次好值
        availabilityCache[tool.name] = now to v
        return v
    }

    /** 清空可用性缓存 —— 开关类工具(如联网搜索)切换后立即生效,不等 30s TTL。 */
    fun invalidateAvailability() {
        availabilityCache.clear()
    }

    /** Register a tool. Overwrites if [tool.name] already exists. */
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    /** Unregister a tool by name. No-op if not found. */
    fun unregister(name: String) {
        tools.remove(name)
    }

    /** Look up a tool by name. Returns null if not registered. */
    fun get(name: String): Tool? = tools[name]

    /** All registered tools (for introspection / testing). */
    fun all(): List<Tool> = tools.values.toList()

    /** Whether any tools are registered. */
    fun isEmpty(): Boolean = tools.isEmpty()

    /**
     * Build the OpenAI-compatible `tools` JSON array for a chat completion request.
     * Each entry: { "type": "function", "function": { "name": "...", "description": "...", "parameters": {...} } }
     */
    fun buildToolsJson(): JSONArray {
        val arr = JSONArray()
        // DeepSeek 缓存优化:工具**按名字典序**排序,保证 tools 数组逐字节稳定,
        // 不受注册/HashMap 迭代顺序影响——前缀稳定才能命中 DeepSeek 自动前缀缓存。
        for (tool in tools.values.sortedBy { it.name }) {
            // 协作模式:白名单非空时,只放行编排类工具,其余「动手」工具对主脑隐藏(强制派活)。
            if (collabAllowlist.isNotEmpty() && tool.name !in collabAllowlist) continue
            // 身份卡白名单:与上面那道是「都要满足」的关系,不是二选一。
            if (identityAllowlist.isNotEmpty() && tool.name !in identityAllowlist) continue
            // Hermes-③ 服务门控:前置条件不满足的工具零 schema 成本、模型不可见。
            if (!isAvailableCached(tool)) continue
            val entry = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parametersSchema)
                })
            }
            arr.put(entry)
        }
        return arr
    }

    /**
     * Execute a [ToolCall] by dispatching to the registered tool.
     * @return [ToolResult.Error] if the tool name is not registered, or the tool's result.
     */
    suspend fun execute(call: ToolCall): ToolResult {
        val tool = tools[call.name]
            ?: return ToolResult.Error("未知工具: ${call.name}（可用: ${tools.keys.joinToString()}）")

        // gap-05:把原始 arguments 解析为 JSONObject 后经 executeJson 透传(保留 array/object 类型)。
        // 默认 executeJson 会压平成 Map<String,String> 委托 execute,老工具行为不变。
        val argsJson = parseArgumentsJson(call.arguments)
        return try {
            tool.executeJson(argsJson)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c  // 协程取消必须原样上抛,否则「停止」按钮会失灵
        } catch (t: Throwable) {
            // 这里是所有工具的唯一派发点,捕 Throwable 而非 Exception 是刻意的:
            // OutOfMemoryError(抓取/读取超大内容)、StackOverflowError(脚本深递归)等属于 Error,
            // 漏掉就会直接杀死进程(用户看到的"闪退")。转成模型可见的错误,让它自我纠正。
            ToolResult.Error("${tool.name} 执行异常(${t::class.java.simpleName}): ${t.message}")
        }
    }

    /** Parse model-generated JSON arguments string into a JSONObject (empty on failure). */
    private fun parseArgumentsJson(raw: String): JSONObject {
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /** Parse model-generated JSON arguments string into key-value map. */
    private fun parseArguments(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) {
                map[key] = json.optString(key, "")
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
}