package com.xincode.core

/** 轮数不设实际上限(仅由总超时兜底),避免"已达最大轮数"提前打断长任务。
 *  必须是【顶层】常量:枚举项的初始化早于伴生对象,不能在枚举构造参数里引用伴生对象成员。 */
const val UNLIMITED_ITERS = 100000

/**
 * Agent power mode — controls performance vs battery life tradeoffs.
 * Driven by [BatteryMonitor] based on current battery state.
 */
enum class PowerMode(
    val label: String,
    val wolfpackConcurrency: Int = 3,
    val maxIterations: Int = UNLIMITED_ITERS,
    val totalTimeoutMs: Long = 60 * 60 * 1000L,
    val thinkingLevel: Int = 2
) {
    /** Full performance — charging or battery > 80%. */
    HIGH_PERF(
        label = "高性能",
        wolfpackConcurrency = 5,
        maxIterations = UNLIMITED_ITERS,
        totalTimeoutMs = 60 * 60 * 1000L,
        thinkingLevel = 4
    ),
    /** Normal operation — battery between 20% and 80%. */
    NORMAL(
        label = "正常",
        wolfpackConcurrency = 3,
        maxIterations = UNLIMITED_ITERS,
        totalTimeoutMs = 60 * 60 * 1000L,
        thinkingLevel = 2
    ),
    /** Power saving — battery < 20% and not charging. */
    POWER_SAVE(
        label = "省电",
        wolfpackConcurrency = 1,
        maxIterations = UNLIMITED_ITERS,   // 用户要求不限轮数;省电只降并发/思考深度
        totalTimeoutMs = 30 * 60 * 1000L,
        thinkingLevel = 0
    );
}
