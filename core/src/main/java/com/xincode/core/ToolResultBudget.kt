package com.xincode.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * §1 工具并发分批(纯函数,无 Android 依赖,可单测)。
 *
 * 桌面端 [StreamingToolExecutor] 的等价逻辑:连续的安全工具聚成一批并发执行,
 * 遇到非安全工具先等当前批结束再让它独占一批(串行)。单批不超过 [maxParallel]。
 * 结果顺序由调用方按原始 index 保证,本函数只决定「怎么分批」。
 */
object ToolBatchPlanner {

    /**
     * @param calls    顺序即为原始调用顺序
     * @param isSafe  判定某调用是否并发安全
     * @param maxParallel 单批安全工具并发上限
     * @return 分批列表(每批内的相对顺序与 [calls] 一致)
     */
    fun <T> planToolBatches(calls: List<T>, isSafe: (T) -> Boolean, maxParallel: Int): List<List<T>> {
        if (calls.isEmpty()) return emptyList()
        val batches = ArrayList<List<T>>()
        var cur = ArrayList<T>()
        for (c in calls) {
            if (isSafe(c)) {
                if (cur.size >= maxParallel) {
                    batches.add(cur)
                    cur = ArrayList()
                }
                cur.add(c)
            } else {
                // 非安全工具:先把当前批刷出去,再让它独占一批(串行)。
                if (cur.isNotEmpty()) {
                    batches.add(cur)
                    cur = ArrayList()
                }
                batches.add(listOf(c))
            }
        }
        if (cur.isNotEmpty()) batches.add(cur)
        return batches
    }
}

/**
 * §2 结果三层截断(纯函数,无 Android 依赖,可单测)。
 *
 *  - L1 单工具硬上限:超过 [perToolCapChars] → 头 [RESULT_HEAD_RATIO]% + 尾 [RESULT_TAIL_RATIO]% 截断。
 *  - L2 单轮聚合预算:整轮累计字符超过 [perMessageCapChars] 的后续结果降级为摘要。
 *  - L3 落盘:非豁免工具结果超过 [offloadThresholdChars] 且目录可用 → 写盘,
 *            模型只收「摘要 + 文件路径」([EXEMPT_OFFLOAD_TOOL] 豁免,避免读文件还要二次读)。
 *
 * 与旧 [compactToolResults] 的关系:三层预算是「入 prompt 前的硬预算」,先执行;
 * compactToolResults 是「发 API 前的最后一层压缩」,后执行。两者不打架。
 */
object ToolResultBudget {

    /** 单个工具结果(用于预算计算)。 */
    data class Entry(val callId: String, val toolName: String, val content: String)

    /**
     * 对一轮内所有【已执行】工具结果按原始顺序做三层预算。
     * @return 顺序与 [entries] 一致的最终 content 列表。
     */
    fun applyTurnBudget(
        entries: List<Entry>,
        perToolCapChars: Int = AgentCoreContract.MAX_RESULT_CHARS_PER_TOOL,
        perMessageCapChars: Int = AgentCoreContract.MAX_RESULT_CHARS_PER_MESSAGE,
        offloadThresholdChars: Int = AgentCoreContract.OFFLOAD_THRESHOLD_CHARS,
        summaryMaxChars: Int = AgentCoreContract.TOOL_SUMMARY_MAX_CHARS,
        offloadDir: File? = null,
        isExemptFromOffload: (String) -> Boolean = { it in AgentCoreContract.OFFLOAD_EXEMPT_TOOLS }
    ): List<String> {
        var running = 0L
        val out = ArrayList<String>(entries.size)
        for (e in entries) {
            var text = e.content
            // L2 聚合预算:加上本条会超整轮预算 → 降级为摘要(只占很少预算)。
            if (running + text.length > perMessageCapChars) {
                text = summarize(text, summaryMaxChars)
            }
            // L3 落盘(非豁免 + 超阈值 + 目录可用)。file_read 等豁免工具跳过,直接走 L1。
            if (!isExemptFromOffload(e.toolName) && text.length > offloadThresholdChars && offloadDir != null) {
                val path = offloadResult(e.callId, text, offloadDir)
                text = "[结果过大已落盘] ${summarize(text, summaryMaxChars)} " +
                    "完整内容见文件: $path (可用 file_read 读取)"
            } else if (text.length > perToolCapChars) {
                // L1 单工具截断(落盘豁免后仍可能超单工具上限,如超长 file_read)。
                text = truncateToHeadTail(text)
            }
            running += text.length
            out.add(text)
        }
        return out
    }

    /** L1:头 [RESULT_HEAD_RATIO]% + 尾 [RESULT_TAIL_RATIO]% 保留,中间用标记替代。 */
    fun truncateToHeadTail(
        text: String,
        headRatio: Double = AgentCoreContract.RESULT_HEAD_RATIO,
        tailRatio: Double = AgentCoreContract.RESULT_TAIL_RATIO,
        marker: String = AgentCoreContract.RESULT_TRUNCATE_MARKER
    ): String {
        val n = text.length
        val headLen = (n * headRatio).toInt()
        val tailLen = (n * tailRatio).toInt()
        if (headLen + tailLen >= n) return text
        val removed = n - headLen - tailLen
        return text.take(headLen) + String.format(marker, removed) + text.takeLast(tailLen)
    }

    /** L2 降级摘要。 */
    fun summarize(text: String, maxChars: Int = AgentCoreContract.TOOL_SUMMARY_MAX_CHARS): String {
        return if (text.length <= maxChars) text
        else text.take(maxChars) + "…(结果过大已降级为摘要)"
    }

    /** L3:把完整结果写入 [dir]/<safeCallId>.txt,返回绝对路径。调用方负责 7 天清理。 */
    fun offloadResult(callId: String, content: String, dir: File): String {
        dir.mkdirs()
        val safeId = callId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val file = File(dir, "$safeId.txt")
        file.writeText(content)
        return file.absolutePath
    }

    /** 清理超过 [retentionDays] 天的落盘文件(纯 java.io,无新依赖)。 */
    fun cleanupStaleOffloads(dir: File?, retentionDays: Long = AgentCoreContract.OFFLOAD_RETENTION_DAYS) {
        if (dir == null || !dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays)
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") && f.lastModified() < cutoff }
            ?.forEach { runCatching { it.delete() } }
    }

    // ───────────── §3 单轮预算的纯判定(可单测,runLoop 直接复用) ─────────────

    /** 单轮 tool_calls 数量是否超过上限(超出部分不执行)。 */
    fun overPerTurnLimit(count: Int): Boolean = count > AgentCoreContract.MAX_TOOL_CALLS_PER_TURN

    /**
     * 是否应注入软 token 预算提醒:上一轮 prompt_tokens ≥ contextWindow × [ratio]。
     * 达到即提醒(不硬停);contextWindow<=0(未声明)或 prompt_tokens<=0 不触发。
     */
    fun shouldInjectBudgetReminder(
        promptTokens: Int,
        contextWindow: Int,
        ratio: Double = AgentCoreContract.TURN_TOKEN_BUDGET_RATIO
    ): Boolean = contextWindow > 0 && promptTokens > 0 && promptTokens >= contextWindow * ratio
}
