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
 * 把消息整理成「步骤」时间线(合并 reasonix 的 buildStepGroups 思路)。
 *
 * 为什么不是一轮聚合成一块:那样呈现为「文字全堆一起 + 工具全堆一起」,看不出实际执行顺序。
 * 现在按消息 id(真实发生顺序)扫描,切分规则:
 *   - user            → 单独成组
 *   - assistant       → 【切段】:开启新的一步,该段文字是这一步的说明
 *   - tool            → 归入当前这一步(即紧跟在其上方那段文字之下)
 *
 * 于是每一组 = 「说了一段话 + 为此做的那些操作」,呈现成
 * 「说一段 → 做几步 → 再说一段」的时间线,并且整组可折叠。
 */
fun List<ChatState.MessageUi>.groupByTurn(): List<TurnGroup> {
    val groups = mutableListOf<TurnGroup>()

    var pendingAssistant: ChatState.MessageUi? = null
    var pendingTools = mutableListOf<ChatState.MessageUi>()
    var pendingSort = Long.MAX_VALUE

    fun flush() {
        if (pendingAssistant == null && pendingTools.isEmpty()) return
        val anchor = pendingAssistant
        groups.add(
            TurnGroup(
                key = anchor?.let { "step_${it.id}" } ?: "step_tools_${pendingTools.first().id}",
                turnId = anchor?.turnId ?: pendingTools.first().turnId,
                assistantMessage = anchor,
                toolMessages = pendingTools.toList(),
                // 只有「一段文字 + 无工具」才走扁平快速渲染;带工具的一步交给 AgentTurnBlock 统一成块。
                isFlat = pendingTools.isEmpty(),
                sortKey = pendingSort
            )
        )
        pendingAssistant = null
        pendingTools = mutableListOf()
        pendingSort = Long.MAX_VALUE
    }

    for (msg in this) {
        when (msg.role) {
            "user" -> {
                flush()
                groups.add(
                    TurnGroup(
                        key = "flat_${msg.id}", turnId = 0, isFlat = true,
                        userMessage = msg, sortKey = msg.id
                    )
                )
            }
            "assistant" -> {
                // 新的一段文字 = 新的一步的开始
                flush()
                pendingAssistant = msg
                pendingSort = msg.id
            }
            "tool" -> {
                pendingTools.add(msg)
                if (pendingSort == Long.MAX_VALUE) pendingSort = msg.id
            }
        }
    }
    flush()

    return groups.sortedBy { it.sortKey }
}
