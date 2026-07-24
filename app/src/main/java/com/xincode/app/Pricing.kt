package com.xincode.app

import com.xincode.app.TokenStats

/**
 * 缓存感知的成本估算(人民币 ¥),供状态条显示「本会话已花多少钱」。
 *
 * 价格表按模型名前缀匹配,单位 **¥/百万 token**,三档:命中缓存输入价 / 未命中输入价 / 输出价。
 * DeepSeek 为官方精确价(V4-Flash 命中 0.02 vs 未命中 1 —— 差 50 倍,这就是缓存优化的意义);
 * 其余供应商为按官方美元价 × 约 7.2 汇率的近似值,仅供参考。
 *
 * 成本 = (命中*命中价 + 未命中*未命中价 + 输出*输出价) / 1e6。
 * 未命中 token 数取 cacheMiss;若未上报则用 prompt - cacheHit 兜底。
 */
object Pricing {
    /** (cacheHitPer1M, inputPer1M, outputPer1M) in RMB. */
    data class Rate(val cacheHit: Double, val input: Double, val output: Double)

    // 前缀匹配(小写);越靠前越优先。DeepSeek 精确,其余近似(¥)。
    private val TABLE: List<Pair<String, Rate>> = listOf(
        // —— DeepSeek(官方 RMB 精确价)——
        "deepseek-v4-pro" to Rate(0.025, 3.0, 6.0),
        "deepseek-v4-flash" to Rate(0.02, 1.0, 2.0),
        "deepseek-reasoner" to Rate(0.02, 1.0, 2.0),   // → v4-flash 思考态
        "deepseek-chat" to Rate(0.02, 1.0, 2.0),       // → v4-flash 非思考态
        "v4-pro" to Rate(0.025, 3.0, 6.0),
        "v4-flash" to Rate(0.02, 1.0, 2.0),
        "deepseek" to Rate(0.02, 1.0, 2.0),
        // —— 其余供应商(¥ 近似,美元官方价 ×7.2)——
        "gpt-4o-mini" to Rate(0.54, 1.08, 4.32),
        "gpt-4o" to Rate(9.0, 18.0, 72.0),
        "gpt-4.1" to Rate(0.72, 14.4, 57.6),
        "o1" to Rate(54.0, 108.0, 432.0),
        "o3-mini" to Rate(4.0, 7.9, 31.7),
        "claude-3-opus" to Rate(7.9, 108.0, 540.0),
        "claude-3-5-sonnet" to Rate(2.16, 21.6, 108.0),
        "claude-sonnet" to Rate(2.16, 21.6, 108.0),
        "claude-3-5-haiku" to Rate(0.58, 5.76, 28.8),
        "claude-haiku" to Rate(0.58, 5.76, 28.8),
        "claude" to Rate(2.16, 21.6, 108.0),
        "grok-2-mini" to Rate(0.72, 1.44, 7.2),
        "grok" to Rate(14.4, 14.4, 72.0),
        "llama-3.3-70b" to Rate(4.2, 4.2, 5.7),
        "mixtral" to Rate(1.7, 1.7, 1.7),
        "qwen-max" to Rate(1.2, 2.4, 9.6),
        "qwen-plus" to Rate(0.4, 0.8, 2.0),
        "qwen" to Rate(0.15, 0.3, 0.6),
        "glm-4-flash" to Rate(0.0, 0.1, 0.1),
        "glm" to Rate(0.5, 1.0, 5.0),
        "moonshot" to Rate(2.0, 12.0, 12.0),
        "kimi" to Rate(2.0, 12.0, 12.0),
        "ernie" to Rate(0.4, 0.8, 2.0)
    )

    private fun rateFor(model: String): Rate? {
        val m = model.trim().lowercase()
        if (m.isEmpty()) return null
        return TABLE.firstOrNull { m.contains(it.first) }?.second
    }

    /** 返回本会话累计成本(¥);未知模型返回 null(不显示金额)。 */
    fun costRmb(stats: TokenStats, model: String): Double? {
        val r = rateFor(model) ?: return null
        val hit = stats.cacheHit.toDouble()
        val miss = if (stats.cacheMiss > 0) stats.cacheMiss.toDouble()
        else (stats.prompt - stats.cacheHit).coerceAtLeast(0).toDouble()
        val out = stats.completion.toDouble()
        return (hit * r.cacheHit + miss * r.input + out * r.output) / 1_000_000.0
    }

    /** 格式化为「¥0.0000」样式(小额高精度)。 */
    fun formatRmb(cost: Double): String = when {
        cost <= 0.0 -> "¥0"
        cost < 0.01 -> "¥%.4f".format(cost)
        cost < 1.0 -> "¥%.3f".format(cost)
        else -> "¥%.2f".format(cost)
    }
}
