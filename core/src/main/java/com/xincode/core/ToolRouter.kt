package com.xincode.core

/**
 * 步骤D:工具调度器(方案 docs/CODEX-HARNESS优化方案.md,对应视频 ToolRouter)。
 *
 * 只回答一个问题:“这项工作应该交给谁”。
 * 纯函数、无副作用:不做权限判定、不执行、不碰状态。权限归闸门(D→E)、执行归
 * [ToolOrchestrator]。名字纠偏逻辑与原来 AgentCore 内联的两行逐字等价。
 */
object ToolRouter {

    sealed interface Route {
        /** 找到负责的工具(含纠偏后的真名,下游全程只见真名)。 */
        data class Found(val tool: Tool, val canonicalName: String) : Route
        /** 注册表里没有(也不像任何已注册名):交给 registry.execute 报“未知工具”。 */
        data class Unknown(val requestedName: String) : Route
    }

    fun resolve(registry: ToolRegistry, requestedName: String): Route {
        val direct = registry.get(requestedName)
        if (direct != null) return Route.Found(direct, requestedName)
        val canonical = registry.canonicalName(requestedName)
        val tool = registry.get(canonical)
        return if (tool != null) Route.Found(tool, canonical) else Route.Unknown(requestedName)
    }
}
