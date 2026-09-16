package com.xincode.core

import org.json.JSONObject

/**
 * M1-4:历史窗口裁剪(纯逻辑,不依赖 Android / 不发请求,可直接单测)。
 *
 * 为什么单独抽出来:[AgentCore] 持有 `OpenAiClient`(final 类且构造需要 database/keystore),
 * 单测里造不出实例。而"裁得对不对"这件事完全是纯逻辑 —— 抽成对象后可以像
 * [ToolBatchPlanner] / [ToolResultBudget] 一样被机械校验。
 *
 * ## 为什么只在 user 边界切
 *
 * OpenAI 兼容接口要求每个 `assistant.tool_calls` 必须紧跟其配对的全部 `tool` 结果,
 * 否则**整段历史非法 → HTTP 400**。一轮里 tool 结果总在同一个 user 回合内闭合,
 * 所以以 user 为切点能够保证:任何被保留的后缀都是自洽的。
 * 从中间切(例如刚好落在 assistant 与 tool 之间)就会拆散配对。
 *
 * ## 为什么找不到 user 边界时宁可不裁
 *
 * 此时"裁"必然产生非法历史。宁可这轮多花点 token,也不能发一个注定 400 的请求。
 */
object HistoryWindow {

    /**
     * 就地裁剪 [msgs](第 0 条固定是 system,永不动)。
     *
     * @return 被裁掉的条数(0 = 没裁 / 无法安全裁)。
     */
    fun trim(
        msgs: MutableList<JSONObject>,
        maxMessages: Int = AgentCoreContract.HISTORY_WINDOW_MAX_MESSAGES,
        maxChars: Int = AgentCoreContract.HISTORY_WINDOW_MAX_CHARS
    ): Int {
        // 至少要留下 system + 一条,否则没得裁
        if (msgs.size <= 2) return 0
        if (maxMessages <= 0 || maxChars <= 0) return 0

        // 从尾部往前累计,直到任一预算耗尽(至少留 1 条,否则会把整段都切掉)
        var acc = 0
        var kept = 0
        var keepFrom = msgs.size
        for (i in msgs.size - 1 downTo 1) {
            val len = msgs[i].optString("content").length
            if (kept >= 1 && (kept + 1 > maxMessages || acc + len > maxChars)) break
            acc += len
            kept += 1
            keepFrom = i
        }
        if (kept >= msgs.size - 1) return 0 // 全都在预算内,不动

        // 从 keepFrom 起找第一个 user 作为合法切点
        var cut = -1
        for (i in keepFrom until msgs.size) {
            if (msgs[i].optString("role") == "user") {
                cut = i
                break
            }
        }
        if (cut <= 1) return 0 // 没有可用边界 → 安全兜底,不裁

        val removed = cut - 1
        repeat(removed) { msgs.removeAt(1) } // 从下标 1 删,永远不动 system
        return removed
    }
}
