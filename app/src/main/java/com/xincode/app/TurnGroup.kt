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

fun List<ChatState.MessageUi>.groupByTurn(): List<TurnGroup> {
    val groups = mutableListOf<TurnGroup>()
    val byTurn = this.groupBy { it.turnId }

    // turnId=0: 每条消息单独平铺, sortKey = 自身 id
    byTurn[0]?.forEach { msg ->
        groups.add(TurnGroup(
            key = "flat_${msg.id}", turnId = 0, isFlat = true,
            userMessage = if (msg.role == "user") msg else null,
            assistantMessage = if (msg.role == "assistant") msg else null,
            toolMessages = if (msg.role == "tool") listOf(msg) else emptyList(),
            sortKey = msg.id
        ))
    }

    // turnId>0: 聚合, sortKey = group 内最早消息的 id
    byTurn.filterKeys { it != 0L }.forEach { (turnId, msgs) ->
        val minId = msgs.minOfOrNull { it.id } ?: 0L
        groups.add(TurnGroup(
            key = "turn_$turnId", turnId = turnId, isFlat = false,
            assistantMessage = msgs.firstOrNull { it.role == "assistant" },
            toolMessages = msgs.filter { it.role == "tool" }.sortedBy { it.id },
            sortKey = minId
        ))
    }

    // 按 sortKey 排，不按 turnId 排
    return groups.sortedBy { it.sortKey }
}