package com.xincode.data

/**
 * 执行状态词汇 —— 与 OpenAI Codex 对齐。
 *
 * Ported from https://github.com/openai/codex (Apache-2.0):
 * codex-rs/rollout-trace/src/model/session.rs (ExecutionStatus)。
 * wire 值逐字一致(snake_case),两边 Rollout 工具可互读状态。
 */
enum class ExecutionStatus(val wire: String) {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ABORTED("aborted");

    companion object {
        fun ofWire(raw: String): ExecutionStatus? = values().firstOrNull { it.wire == raw }
    }
}
