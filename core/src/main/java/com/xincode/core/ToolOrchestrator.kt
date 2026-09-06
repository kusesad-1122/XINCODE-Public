package com.xincode.core

import com.xincode.provider.ToolCall
import kotlinx.coroutines.withContext

/**
 * 步骤D:工具执行管理器(方案 docs/CODEX-HARNESS优化方案.md,对应视频 ToolOxTreater 的执行 half)。
 *
 * 拥有“执行的边界”,固定顺序(与原来 AgentCore 内联代码逐行一致):
 * pre_tool 钩 → 会话归属元素 + registry.execute → 计时 → post_tool 钩。
 *
 * - 不含任何按工具名分支:新增工具零权限代码(自检项),意图/名单/开关全在
 *   SecurityGate 与 PermissionRule,沙箱分级在 E 步按执行域扩展。
 * - 审批(policy)仍在 AgentCore 的闸门段,E 步才搬入,本步只收敛执行边界。
 */
class ToolOrchestrator(
    private val registry: ToolRegistry,
    private val fireHook: suspend (event: String, ctx: Map<String, String>) -> Unit
) {

    data class Execution(val result: ToolResult, val durationMs: Long)

    suspend fun runBounded(call: ToolCall, sessionId: Long): Execution {
        // gap-24 pre_tool hook(原文搬入)
        fireHook("pre_tool", mapOf("tool" to call.name, "args" to call.arguments))
        val startTime = System.currentTimeMillis()
        val result = withContext(ToolSessionElement(sessionId)) {
            registry.execute(call)
        }
        val durationMs = System.currentTimeMillis() - startTime
        // gap-24 post_tool hook(原文搬入)
        fireHook(
            "post_tool", mapOf(
                "tool" to call.name,
                "status" to (if (result is ToolResult.Success) "SUCCESS" else "FAIL"),
                "output_head" to (if (result is ToolResult.Success) result.output.take(200) else "")
            )
        )
        return Execution(result, durationMs)
    }
}
