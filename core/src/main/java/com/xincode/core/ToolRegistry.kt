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

    /**
     * 把模型写歪的工具名纠正回真实注册名;认不出来就原样返回。
     *
     * 【为什么必须有】模型经常把词序或分隔符记反 —— 实测最高频的是 `search_web`(真名
     * `web_search`)、`read_file`(真名 `file_read`)。以前这些一律走「未知工具」分支,
     * 界面上只显示一行 `exit -1`,用户看到的是「一堆工具失败」,而模型拿到的错误里
     * 塞着几十个工具名,弱模型往往还是挑不对,于是连错三次被防空转刹车掐掉 ——
     * 一次本可以完成的任务就这么整轮报废。
     *
     * 【为什么在这里纠正而不是在 execute 里】权限闸门 `SecurityGate.classify(name, ...)`
     * 也是按名字分类的。只在派发处纠正的话,`search_web` 会被当成不认识的名字去归类,
     * 权限判定用的名字和真正执行的工具对不上 —— 那比不纠正更危险。所以 AgentCore 在
     * 进闸门【之前】就调用本方法,全链路自始至终只见真名。
     *
     * 匹配只做两级,而且【必须唯一命中】才算数,宁可返回原名报错也不猜:
     *  1. 去掉所有非字母数字后相等(`webSearch` / `web-search` → `web_search`)
     *  2. 按分隔符切词后集合相等(`search_web` → `web_search`)
     */
    fun canonicalName(name: String): String {
        if (tools.containsKey(name)) return name
        val flat = flatten(name)
        if (flat.isEmpty()) return name
        tools.keys.filter { flatten(it) == flat }.singleOrNull()?.let { return it }
        val tokens = tokenize(name)
        if (tokens.isEmpty()) return name
        tools.keys.filter { tokenize(it) == tokens }.singleOrNull()?.let { return it }
        return name
    }

    /** 小写并去掉所有非字母数字字符。 */
    private fun flatten(s: String): String =
        s.filter { it.isLetterOrDigit() }.lowercase()

    /** 按 `_`/`-`/`.`/空格 以及 camelCase 边界切词,小写后取集合(词序无关)。 */
    private fun tokenize(s: String): Set<String> {
        val out = StringBuilder()
        for ((i, c) in s.withIndex()) {
            when {
                c == '_' || c == '-' || c == '.' || c == ' ' -> out.append(' ')
                c.isUpperCase() && i > 0 && s[i - 1].isLowerCase() -> out.append(' ').append(c.lowercaseChar())
                else -> out.append(c.lowercaseChar())
            }
        }
        return out.toString().split(' ').filter { it.isNotBlank() }.toSet()
    }

    /** 未知工具时挑几个最像的名字放在错误最前面 —— 比甩一整串工具名更容易让模型改对。 */
    private fun suggest(name: String): List<String> {
        val tokens = tokenize(name)
        if (tokens.isEmpty()) return emptyList()
        return tools.keys
            .map { it to tokenize(it).count { t -> t in tokens } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
    }

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
        // 名字纠偏兜底。正常路径上 AgentCore 已经在进权限闸门前把名字换成真名了,
        // 这里再走一次是给绕过 AgentCore 的调用方(execute_code 的脚本桥、子智能体)留的。
        val tool = tools[call.name] ?: tools[canonicalName(call.name)]
            ?: return ToolResult.Error(
                buildString {
                    append("未知工具: ${call.name}")
                    val near = suggest(call.name)
                    if (near.isNotEmpty()) append("。你要找的可能是: ${near.joinToString(" / ")}")
                    append("（全部可用: ${tools.keys.sorted().joinToString()}）")
                }
            )

        // 前置条件没满足的工具【不执行】。
        //
        // buildToolsJson 只是不发它的 schema —— 那挡不住模型从历史记录里照抄一个调用,也挡不住
        // execute_code 的脚本桥直接点名调用。少了这道拦截,用户明明把联网搜索关了,一次「照着上面
        // 那条重试」就能把搜索真的发出去。这里既是给模型的纠错信息,也是对用户那个开关的兑现。
        if (!isAvailableCached(tool)) {
            return ToolResult.Error("${tool.name} ${tool.unavailableReason()}")
        }

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