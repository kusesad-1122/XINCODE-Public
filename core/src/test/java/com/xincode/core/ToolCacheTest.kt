package com.xincode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolCache 单元测试：正常命中 / 边界淘汰 / 异常输入 / **缓存键碰撞**。
 *
 * 重点：缓存键只取参数串的 32 位 hashCode，而 Java 字符串 hashCode 极易碰撞
 * （经典例子："Aa" 与 "BB" 同哈希）。一旦碰撞，file_read 会返回**另一个文件**的缓存内容 ——
 * 这是正确性问题，不是性能问题。
 *
 * 跑法：`./gradlew :core:testDebugUnitTest --tests '*ToolCacheTest'`
 */
class ToolCacheTest {

    private fun ok(s: String) = ToolResult.Success(s)

    @Test
    fun putThenGet_returnsSameResult() {
        val c = ToolCache()
        c.put("file_read", mapOf("path" to "/a.txt"), ok("content-a"))
        val hit = c.get("file_read", mapOf("path" to "/a.txt"))
        assertNotNull("同参数应命中", hit)
        assertEquals("content-a", (hit as ToolResult.Success).output)
    }

    @Test
    fun paramOrderDoesNotMatter() {
        val c = ToolCache()
        c.put("file_read", mapOf("path" to "/a.txt", "limit" to "10"), ok("x"))
        assertNotNull("参数顺序不同应视为同一次调用", c.get("file_read", mapOf("limit" to "10", "path" to "/a.txt")))
    }

    @Test
    fun nonCacheableTools_areNeverCached() {
        val c = ToolCache()
        for (tool in listOf("file_write", "su_exec", "invoke_skill", "shell_exec_unknown")) {
            c.put(tool, mapOf("p" to "1"), ok("v"))
            assertNull("$tool 带副作用/未声明 TTL，不应缓存", c.get(tool, mapOf("p" to "1")))
        }
        assertEquals(0, c.size)
    }

    @Test
    fun errorResults_areNotCached() {
        val c = ToolCache()
        c.put("file_read", mapOf("path" to "/e.txt"), ToolResult.Error("boom"))
        assertNull("失败结果不应缓存（否则会一直拿到旧错误）", c.get("file_read", mapOf("path" to "/e.txt")))
    }

    @Test
    fun differentParams_areDifferentEntries() {
        val c = ToolCache()
        c.put("file_read", mapOf("path" to "/a.txt"), ok("A"))
        c.put("file_read", mapOf("path" to "/b.txt"), ok("B"))
        assertEquals("A", (c.get("file_read", mapOf("path" to "/a.txt")) as ToolResult.Success).output)
        assertEquals("B", (c.get("file_read", mapOf("path" to "/b.txt")) as ToolResult.Success).output)
    }

    @Test
    fun hashCollidingPaths_mustNotShareEntry() {
        // "Aa" 与 "BB" 的 String.hashCode 相同（65*31+97 == 66*31+66 == 2112），
        // 因此 "path=Aa" 与 "path=BB" 拼串后哈希也相同。
        val c = ToolCache()
        c.put("file_read", mapOf("path" to "Aa"), ok("FILE-Aa"))
        val other = c.get("file_read", mapOf("path" to "BB"))
        assertNull(
            "哈希碰撞不得串数据：读 Aa 的缓存被当成了 BB 的内容 -> " +
                (other as? ToolResult.Success)?.output,
            other
        )
    }

    @Test
    fun lru_evictsEldestBeyondCapacity() {
        val c = ToolCache(maxEntries = 2)
        c.put("file_read", mapOf("path" to "1"), ok("1"))
        c.put("file_read", mapOf("path" to "2"), ok("2"))
        c.put("file_read", mapOf("path" to "3"), ok("3"))
        assertEquals(2, c.size)
        assertNull("最久未使用的条目应被淘汰", c.get("file_read", mapOf("path" to "1")))
        assertNotNull(c.get("file_read", mapOf("path" to "3")))
    }

    @Test
    fun lru_updatingExistingKey_doesNotEvictOthers() {
        val c = ToolCache(maxEntries = 2)
        c.put("file_read", mapOf("path" to "1"), ok("1"))
        c.put("file_read", mapOf("path" to "2"), ok("2"))
        c.put("file_read", mapOf("path" to "2"), ok("2b"))   // 更新已有键
        assertEquals(2, c.size)
        assertNotNull("更新已有键不应把别的条目挤掉", c.get("file_read", mapOf("path" to "1")))
        assertEquals("2b", (c.get("file_read", mapOf("path" to "2")) as ToolResult.Success).output)
    }

    @Test
    fun boundary_zeroCapacity_doesNotThrow() {
        val c = ToolCache(maxEntries = 0)
        try {
            c.put("file_read", mapOf("path" to "1"), ok("1"))
        } catch (t: Throwable) {
            throw AssertionError("maxEntries=0 时 put 不应抛异常，实际: $t")
        }
        assertTrue(c.size >= 0)
    }

    @Test
    fun invalidate_removesOnlyThatTool() {
        val c = ToolCache()
        c.put("file_read", mapOf("path" to "x"), ok("r"))
        c.put("list_dir", mapOf("path" to "x"), ok("d"))
        c.invalidate("file_read")
        assertNull(c.get("file_read", mapOf("path" to "x")))
        assertNotNull("不应误伤其它工具", c.get("list_dir", mapOf("path" to "x")))
    }

    @Test
    fun exception_emptyParams_areStable() {
        val c = ToolCache()
        c.put("web_search", emptyMap(), ok("empty"))
        assertNotNull("空参数也应能缓存与命中", c.get("web_search", emptyMap()))
    }
}
