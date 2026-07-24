package com.xincode.core

/**
 * gap-24 生命周期 hooks 分发接口(对标 grok 的 hooks 子系统)。
 *
 * AgentCore 在关键生命周期点调用 [dispatch];具体实现(在 :app)据事件从 Room 取出用户配置的
 * hook 命令并执行(经 shell)。core 不直接依赖 shell,通过本接口解耦。
 *
 * 约定事件:
 * - "session_start"       会话开始(一次 run 的首轮前)
 * - "user_prompt_submit"  收到用户输入
 * - "pre_tool"            工具执行前(context: tool, args)
 * - "post_tool"           工具执行后(context: tool, status, output_head)
 * - "session_end"         一次 run 结束
 */
interface HookDispatcher {
    suspend fun dispatch(event: String, context: Map<String, String>)
}
