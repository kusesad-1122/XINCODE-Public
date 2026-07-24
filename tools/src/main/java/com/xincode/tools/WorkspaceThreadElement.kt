package com.xincode.tools

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 把「本会话的工作区根 + 项目 id」绑定到一个协程作用域:该作用域内(含 withContext 切线程后)
 * 执行工具时,[WorkspaceContext] 的读取会拿到本会话的值,而不是被别的会话切换所污染。
 *
 * 每个会话的 AgentChatState 用自己的 scope 携带一个本元素(provider 读该会话当前的 root/pid),
 * 从而实现 per-session 工作区隔离(B2 修复),且无覆盖时自动回退全局值(非破坏性)。
 */
class WorkspaceThreadElement(
    private val provider: () -> Pair<String, Long>
) : ThreadContextElement<Pair<String?, Long?>> {

    companion object Key : CoroutineContext.Key<WorkspaceThreadElement>

    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Pair<String?, Long?> {
        val prev = WorkspaceContext.peekThread()
        val (root, pid) = provider()
        WorkspaceContext.pushThread(root, pid)
        return prev
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Pair<String?, Long?>) {
        WorkspaceContext.pushThread(oldState.first, oldState.second)
    }
}
