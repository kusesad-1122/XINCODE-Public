package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolRegistry
import com.xincode.core.ToolResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具名纠偏。
 *
 * 这里守的是一个真实事故:模型把 `web_search` 写成 `search_web`,派发时直接落进「未知工具」,
 * 界面上只显示一行 `exit -1`。同一个错误连出三次就会触发防空转刹车,整轮任务作废。
 * 纠偏做得太松同样危险——把名字纠到别的工具上,用户看到的就是「它干了我没让它干的事」,
 * 所以下面【每一条不该命中的用例都和该命中的用例一样重要】。
 */
class ToolNameResolveTest {

    private class FakeTool(override val name: String) : Tool {
        override val description = ""
        override val parametersSchema: JSONObject = JSONObject()
        override suspend fun execute(params: Map<String, String>): ToolResult = ToolResult.Success("")
    }

    private fun registryOf(vararg names: String) = ToolRegistry().apply {
        names.forEach { register(FakeTool(it)) }
    }

    @Test
    fun exactNamePassesThrough() {
        val r = registryOf("web_search", "file_read")
        assertEquals("web_search", r.canonicalName("web_search"))
    }

    @Test
    fun reversedWordOrderResolves() {
        // 实测最高频的一个:模型记成了 search_web
        val r = registryOf("web_search", "web_fetch", "file_read", "file_write")
        assertEquals("web_search", r.canonicalName("search_web"))
        assertEquals("file_read", r.canonicalName("read_file"))
        assertEquals("file_write", r.canonicalName("write_file"))
    }

    @Test
    fun separatorAndCaseVariantsResolve() {
        val r = registryOf("web_search", "file_read")
        assertEquals("web_search", r.canonicalName("webSearch"))
        assertEquals("web_search", r.canonicalName("web-search"))
        assertEquals("web_search", r.canonicalName("WEB_SEARCH"))
        assertEquals("web_search", r.canonicalName("web.search"))
    }

    @Test
    fun unrelatedNameIsLeftAlone() {
        // 认不出来就原样返回,让它照常走「未知工具」报错——不能瞎猜一个去执行。
        val r = registryOf("web_search", "file_read")
        assertEquals("launch_missiles", r.canonicalName("launch_missiles"))
        assertEquals("", r.canonicalName(""))
    }

    @Test
    fun ambiguousNameIsLeftAlone() {
        // 词集合同时命中两个工具时必须放弃:随便挑一个执行比报错危险得多。
        val r = registryOf("a_b", "b_a")
        assertEquals("a_b_a", r.canonicalName("a_b_a"))
    }

    @Test
    fun partialOverlapDoesNotResolve() {
        // 只对上一半的词不算命中(file_read vs file_write 只共用 file)。
        val r = registryOf("file_read", "file_write")
        assertEquals("file_delete", r.canonicalName("file_delete"))
    }
}
