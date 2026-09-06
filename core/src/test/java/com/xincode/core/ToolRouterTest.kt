package com.xincode.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 步骤H:ToolRouter 全仓唯一下发前“交给谁”的裁决必须稳定。
 * 纯逻辑测试,不碰 Android(Tool.parametersSchema 只是持有,从不调用)。
 * 跑法:`./gradlew :core:testDebugUnitTest`。
 */
class ToolRouterTest {

    private class FakeTool(
        override val name: String,
        private val available: Boolean = true
    ) : Tool {
        override val description = "fake"
        override val parametersSchema: JSONObject = JSONObject()
        override fun isAvailable() = available
        override suspend fun execute(params: Map<String, String>) = ToolResult.Success("ok")
    }

    @Test
    fun directHit_returnsSameName() {
        val registry = ToolRegistry()
        registry.register(FakeTool("web_search"))
        val route = ToolRouter.resolve(registry, "web_search")
        assertTrue(route is ToolRouter.Route.Found)
        assertEquals("web_search", (route as ToolRouter.Route.Found).canonicalName)
    }

    @Test
    fun misspelledName_correctedBeforeGate() {
        // 模型最高频写反:search_web → web_search。纠偏必须发生在闸门之前,
        // 否则判权限用的名字和真正执行的对不上。
        val registry = ToolRegistry()
        registry.register(FakeTool("web_search"))
        val route = ToolRouter.resolve(registry, "search_web")
        assertTrue(route is ToolRouter.Route.Found)
        assertEquals("web_search", (route as ToolRouter.Route.Found).canonicalName)
    }

    @Test
    fun unknownName_staysUnknown() {
        val registry = ToolRegistry()
        registry.register(FakeTool("web_search"))
        val route = ToolRouter.resolve(registry, "definitely_not_a_tool_xyz")
        assertTrue(route is ToolRouter.Route.Unknown)
        assertEquals("definitely_not_a_tool_xyz", (route as ToolRouter.Route.Unknown).requestedName)
    }
}
