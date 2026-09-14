package com.xincode.core

import com.xincode.provider.ToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2-2 工具结果缓存接线测试。
 *
 * 背景:[ToolCache] 原本是一个实现完整但**零调用**的类(逐工具 TTL / LRU / 错误不缓存都写好了,
 * 全仓却只有它自己的定义)。本测试锁死接线后的行为,防止它再次退化为死代码。
 */
class ToolCacheWiringTest {

    /** 计数工具:同一 [name] 下每次真执行都会让 calls +1,用它判定"是否真的又跑了一次"。 */
    private class CountingTool(
        override val name: String,
        override val concurrencySafe: Boolean = true,
        private val fail: Boolean = false,
        private val echoArg: Boolean = true
    ) : Tool {
        val calls = AtomicInteger(0)
        override val description: String = "counting tool for cache wiring test"
        override val parametersSchema: JSONObject = JSONObject().put("type", "object")

        override suspend fun execute(params: Map<String, String>): ToolResult {
            val n = calls.incrementAndGet()
            if (fail) return ToolResult.Error("boom #$n")
            return ToolResult.Success(if (echoArg) "call#$n path=${params["path"] ?: ""}" else "call#$n")
        }
    }

    private fun call(name: String, args: String = """{"path":"a.txt"}""") =
        ToolCall(id = "c1", name = name, arguments = args)

    @Test
    fun readOnlyTool_secondIdenticalCall_hitsCache() = runBlocking {
        val reg = ToolRegistry()
        val t = CountingTool("file_read")
        reg.register(t)

        val first = reg.execute(call("file_read")) as ToolResult.Success
        val second = reg.execute(call("file_read")) as ToolResult.Success

        assertEquals("只读工具相同参数只应真跑一次", 1, t.calls.get())
        assertEquals("缓存命中必须返回同一结果", first.output, second.output)
        assertEquals("应记录一次命中", 1L, reg.cacheHitCount)
    }

    @Test
    fun readOnlyTool_differentArgs_missesCache() = runBlocking {
        val reg = ToolRegistry()
        val t = CountingTool("file_read")
        reg.register(t)

        reg.execute(call("file_read", """{"path":"a.txt"}"""))
        reg.execute(call("file_read", """{"path":"b.txt"}"""))

        assertEquals("参数不同必须各自真跑", 2, t.calls.get())
        assertEquals(0L, reg.cacheHitCount)
    }

    @Test
    fun argOrderDoesNotAffectCacheKey() = runBlocking {
        val reg = ToolRegistry()
        val t = CountingTool("file_read")
        reg.register(t)

        reg.execute(call("file_read", """{"path":"a.txt","start_line":1}"""))
        reg.execute(call("file_read", """{"start_line":1,"path":"a.txt"}"""))

        assertEquals("参数顺序不同但语义相同,应命中同一缓存键", 1, t.calls.get())
    }

    @Test
    fun nonWhitelistedTool_isNotCached() = runBlocking {
        val reg = ToolRegistry()
        // shell_exec 在 ToolCache 的 TTL 表里,但不在契约的只读白名单里 —— 以白名单为准,不缓存
        val t = CountingTool("shell_exec", concurrencySafe = false)
        reg.register(t)

        reg.execute(call("shell_exec", """{"command":"ls"}"""))
        reg.execute(call("shell_exec", """{"command":"ls"}"""))

        assertEquals("非只读白名单工具不得缓存", 2, t.calls.get())
    }

    @Test
    fun errorResult_isNotCached() = runBlocking {
        val reg = ToolRegistry()
        val t = CountingTool("file_read", fail = true)
        reg.register(t)

        val a = reg.execute(call("file_read"))
        val b = reg.execute(call("file_read"))

        assertTrue(a is ToolResult.Error)
        assertTrue(b is ToolResult.Error)
        assertEquals("错误结果不得进缓存,必须重试", 2, t.calls.get())
    }

    @Test
    fun mutatingToolSuccess_invalidatesReadCaches() = runBlocking {
        val reg = ToolRegistry()
        val reader = CountingTool("file_read")
        val writer = CountingTool("file_write", concurrencySafe = false)
        reg.register(reader)
        reg.register(writer)

        val before = (reg.execute(call("file_read")) as ToolResult.Success).output
        val cachedAgain = (reg.execute(call("file_read")) as ToolResult.Success).output
        assertEquals("先确认缓存已生效", before, cachedAgain)
        assertEquals(1, reader.calls.get())

        reg.execute(call("file_write", """{"path":"a.txt"}"""))

        val after = (reg.execute(call("file_read")) as ToolResult.Success).output
        assertNotEquals("写操作后只读缓存必须失效,不能返回写之前的内容", before, after)
        assertEquals("失效后必须重新真跑", 2, reader.calls.get())
    }

    @Test
    fun mutatingToolFailure_doesNotInvalidate() = runBlocking {
        val reg = ToolRegistry()
        val reader = CountingTool("file_read")
        val writer = CountingTool("file_write", concurrencySafe = false, fail = true)
        reg.register(reader)
        reg.register(writer)

        reg.execute(call("file_read"))
        reg.execute(call("file_write", """{"path":"a.txt"}"""))  // 失败
        reg.execute(call("file_read"))

        assertEquals("写失败不该让缓存失效(数据其实没变)", 1, reader.calls.get())
    }

    @Test
    fun cacheHit_doesNotFireDispatchHook() = runBlocking {
        val reg = ToolRegistry()
        val t = CountingTool("file_read")
        reg.register(t)
        val dispatched = AtomicInteger(0)
        reg.onDispatch = { dispatched.incrementAndGet() }

        reg.execute(call("file_read"))
        reg.execute(call("file_read"))

        assertEquals("缓存命中不是一次真实派发,不得污染观测轨迹", 1, dispatched.get())
    }

    @Test
    fun cacheCanBeDisabled() = runBlocking {
        val reg = ToolRegistry()
        val t = CountingTool("file_read")
        reg.register(t)
        reg.cacheEnabled = false

        reg.execute(call("file_read"))
        reg.execute(call("file_read"))

        assertEquals("关掉开关后必须每次都真跑", 2, t.calls.get())
        assertEquals(0L, reg.cacheHitCount)
    }

    @Test
    fun listDirIsAlsoCached_butGlobIsNotInTtlTable() = runBlocking {
        val reg = ToolRegistry()
        val lister = CountingTool("list_dir")
        val globber = CountingTool("glob")
        reg.register(lister)
        reg.register(globber)

        reg.execute(call("list_dir", """{"path":"."}"""))
        reg.execute(call("list_dir", """{"path":"."}"""))
        assertEquals("list_dir 在白名单且 TTL 表内 → 应缓存", 1, lister.calls.get())

        reg.execute(call("glob", """{"pattern":"*.kt"}"""))
        reg.execute(call("glob", """{"pattern":"*.kt"}"""))
        assertEquals("glob 在白名单但不在 TTL 表内 → 按 ToolCache 自身策略不缓存", 2, globber.calls.get())
    }
}
