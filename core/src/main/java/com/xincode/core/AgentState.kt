package com.xincode.core

/**
 * 8-state machine for the Agent loop. Exposed via StateFlow for UI observation.
 *
 * Transitions:
 *   Idle → Thinking (user sends message)
 *   Thinking → Responding (model returns final text, no tool_calls)
 *   Thinking → CallingTool (model requests tool execution)
 *   CallingTool → WaitingConfirm (security gate requires user approval — feat-010)
 *   CallingTool → Executing (tool runs)
 *   Executing → Thinking (result fed back, next iteration)
 *   Any → Error (exception / max iterations / timeout)
 *   Any → Interrupted (user cancels)
 *   Responding → Idle (done)
 *   Error → Idle (acknowledged)
 *   Interrupted → Idle (acknowledged)
 */
sealed class AgentState {

    /** Ready for user input. */
    object Idle : AgentState()

    /** Waiting for model response (streaming in progress). */
    data class Thinking(val iteration: Int) : AgentState()

    /** Model requested a tool call; about to execute. */
    data class CallingTool(
        val iteration: Int,
        val toolName: String,
        val toolArgs: String
    ) : AgentState()

    /** Tool execution requires user confirmation (security gate — feat-010). */
    data class WaitingConfirm(
        val iteration: Int,
        val toolName: String,
        val preview: String
    ) : AgentState()

    /** Tool is currently executing. */
    data class Executing(
        val iteration: Int,
        val toolName: String
    ) : AgentState()

    /** Model returned final response (no more tool calls). */
    data class Responding(val iteration: Int) : AgentState()

    /** Terminal error state. */
    data class Error(
        val message: String,
        val iteration: Int = 0
    ) : AgentState()

    /** User interrupted the loop. */
    object Interrupted : AgentState()

    // ---- helpers ----

    val isTerminal: Boolean get() = this is Idle || this is Error || this is Interrupted

    val isBusy: Boolean get() = this !is Idle && this !is Error && this !is Interrupted
}