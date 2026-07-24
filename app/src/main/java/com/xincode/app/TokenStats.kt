package com.xincode.app

/**
 * Aggregated token accounting for the current session, computed from the
 * message rows' `promptTokens / cacheHitTokens / cacheMissTokens / completionTokens`
 * columns already persisted by [AgentChatState.send]. Displayed as a compact
 * status strip above the input field so the user can see cost pile up in real time.
 */
data class TokenStats(
    val prompt: Long,
    val cacheHit: Long,
    val cacheMiss: Long,
    val completion: Long
) {
    val total: Long get() = prompt + completion

    /** 0.0..1.0 — fraction of prompt tokens served from the KV cache. */
    val cacheHitRatio: Float
        get() {
            val denom = cacheHit + cacheMiss
            return if (denom <= 0) 0f else cacheHit.toFloat() / denom.toFloat()
        }

    val hasData: Boolean get() = total > 0

    companion object {
        val EMPTY = TokenStats(0, 0, 0, 0)
    }
}

/**
 * 当前上下文占用,用于输入框旁的「上下文圆环」。
 * @param usedTokens 当前上下文实际占用(最近一次请求的 prompt_tokens,或历史字符估算)
 * @param windowTokens 有效上下文窗口(0=未声明,此时圆环显示为未知/不填充)
 */
data class ContextUsage(
    val usedTokens: Long,
    val windowTokens: Long
) {
    /** 0.0..1.0 占用比;窗口未知时返回 0。 */
    val ratio: Float
        get() = if (windowTokens <= 0) 0f else (usedTokens.toFloat() / windowTokens.toFloat()).coerceIn(0f, 1f)

    val known: Boolean get() = windowTokens > 0

    companion object {
        val EMPTY = ContextUsage(0, 0)
    }
}
