package com.xincode.core

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Identifies the AgentCore session that is currently executing a tool.
 *
 * ToolRegistry is shared by several AgentCore instances, so a tool must not infer
 * ownership from whichever conversation happens to be visible in the UI. The
 * coroutine context keeps the id correct across dispatcher switches and nested
 * tool calls such as execute_code.
 */
object ToolSessionContext {
    private val current = ThreadLocal<Long?>()

    val sessionId: Long?
        get() = current.get()

    internal fun swap(sessionId: Long?): Long? {
        val previous = current.get()
        if (sessionId == null) current.remove() else current.set(sessionId)
        return previous
    }
}

internal class ToolSessionElement(
    private val sessionId: Long
) : ThreadContextElement<Long?> {

    companion object Key : CoroutineContext.Key<ToolSessionElement>

    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Long? =
        ToolSessionContext.swap(sessionId)

    override fun restoreThreadContext(context: CoroutineContext, oldState: Long?) {
        ToolSessionContext.swap(oldState)
    }
}
