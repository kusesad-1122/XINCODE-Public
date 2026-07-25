package com.xincode.app

data class TurnGroup(
    val key: String,    // 唯一标识符: "flat_{msgId}" or "turn_{turnId}"
    val turnId: Long,
    val userMessage: ChatState.MessageUi? = null,
    val assistantMessage: ChatState.MessageUi? = null,
    val toolMessages: List<ChatState.MessageUi> = emptyList(),
    val isFlat: Boolean = false,
    val sortKey: Long = 0L          // group 内最小 message id，用于时序排序
)

/**
 * 把消息列表整理成渲染单元。
 *
 * 交错时间线:一轮(turnId>0)内的消息【不再整体聚合成一个折叠块】——那样会变成
 * 「文字全堆一起 + 工具全堆一起」,看不出实际执行顺序。现在按消息 id(即真实发生顺序)
 * 逐条平铺:每段 assistant 文字单独成块,其后紧跟当次的工具调用,形成
 * 「说一段 → 做一步 → 再说一段」的时间线(与流式分段逻辑配套)。
 *
 * 每个工具消息独立成组,便于单独折叠/展开;assistant 段保留 reasoning 供思考折叠使用。
 */
fun List<ChatState.MessageUi>.groupByTurn(): List<TurnGroup> {
    val groups = mutableListOf<TurnGroup>()

    for (msg in this) {
        when (msg.role) {
            "user" -> groups.add(
                TurnGroup(
                    key = "flat_${msg.id}", turnId = 0, isFlat = true,
                    userMessage = msg, sortKey = msg.id
                )
            )
            "assistant" -> groups.add(
                TurnGroup(
                    // turnId 带上,便于折叠状态按段独立记忆(remember(key))
                    key = "asst_${msg.id}", turnId = msg.turnId, isFlat = true,
                    assistantMessage = msg, sortKey = msg.id
                )
            )
            "tool" -> groups.add(
                TurnGroup(
                    key = "tool_${msg.id}", turnId = msg.turnId, isFlat = true,
                    toolMessages = listOf(msg), sortKey = msg.id
                )
            )
        }
    }

    // 按消息 id 排序 = 按真实发生顺序,文字与工具自然交错
    return groups.sortedBy { it.sortKey }
}
