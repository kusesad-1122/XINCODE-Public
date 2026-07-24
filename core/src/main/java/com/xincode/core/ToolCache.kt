package com.xincode.core

/**
 * LRU cache for tool execution results.
 * Key = "$toolName:$argHash" where argHash is a deterministic hash of sorted params.
 * Value = cached ToolResult + timestamp.
 */
class ToolCache(private val maxEntries: Int = 100) {

    data class Entry(
        val result: ToolResult,
        val cachedAt: Long = System.currentTimeMillis()
    )

    // toolName → TTL in milliseconds
    private val ttlMap = mapOf(
        "file_read" to 5_000L,      // 5s — files can change
        "list_dir" to 10_000L,      // 10s — directory listings
        "web_search" to 300_000L,   // 5min — search results stable
        "web_fetch" to 600_000L,    // 10min — web pages stable
        "shell_exec" to 3_000L,     // 3s — read-only commands (ls, cat, etc.)
        // su_exec, file_write, invoke_skill: no cache (side-effects)
    )

    private val cache = LinkedHashMap<String, Entry>(
        32, 0.75f, true  // initialCapacity, loadFactor, accessOrder (LRU)
    )

    /** Deterministic hash of sorted params for cache key. */
    private fun argHash(params: Map<String, String>): String {
        val sorted = params.entries.sortedBy { it.key }
        return sorted.joinToString("|") { "${it.key}=${it.value}" }.hashCode().toUInt().toString(16)
    }

    /** Build cache key from tool name and arguments. */
    private fun key(toolName: String, params: Map<String, String>): String {
        return "$toolName:${argHash(params)}"
    }

    /** Check if this tool/args should be cached based on tool name. */
    private fun isCacheable(toolName: String): Boolean {
        return toolName in ttlMap
    }

    /** Get TTL for a tool (default: no cache = 0). */
    private fun ttlMs(toolName: String): Long {
        return ttlMap[toolName] ?: 0L
    }

    /** Try to get a cached result. Returns null if miss or expired. */
    @Synchronized
    fun get(toolName: String, params: Map<String, String>): ToolResult? {
        if (!isCacheable(toolName)) return null
        val k = key(toolName, params)
        val entry = cache[k] ?: return null
        val age = System.currentTimeMillis() - entry.cachedAt
        if (age > ttlMs(toolName)) {
            cache.remove(k)
            return null
        }
        return entry.result
    }

    /** Store a result in cache. */
    @Synchronized
    fun put(toolName: String, params: Map<String, String>, result: ToolResult) {
        if (!isCacheable(toolName)) return
        if (result is ToolResult.Error) return  // don't cache errors

        val k = key(toolName, params)
        if (cache.size >= maxEntries) {
            // Remove eldest (LinkedHashMap with accessOrder=true)
            val iter = cache.entries.iterator()
            iter.next()
            iter.remove()
        }
        cache[k] = Entry(result)
    }

    /** Invalidate all cached entries for a tool (e.g., after file_write). */
    @Synchronized
    fun invalidate(toolName: String) {
        val prefix = "$toolName:"
        cache.keys.removeAll { it.startsWith(prefix) }
    }

    /** Clear entire cache. */
    @Synchronized
    fun clear() {
        cache.clear()
    }

    /** Current cache size. */
    val size: Int get() = cache.size
}