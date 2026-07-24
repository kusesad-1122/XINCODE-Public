package com.xincode.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Minimal interface for ChatScreen to work with either ChatState or AgentChatState.
 */
interface ChatStateLike {
    val messages: SnapshotStateList<ChatState.MessageUi>
    val input: MutableState<String>
    val isStreaming: MutableState<Boolean>
    val statusLine: MutableState<String>

    suspend fun loadHistory()
    fun send()
    fun stop()

    /** Format all messages as exportable plain text. */
    fun formatForExport(): String {
        val sb = StringBuilder()
        sb.appendLine("XINCODE 对话导出")
        sb.appendLine(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
        sb.appendLine()
        for (msg in messages) {
            val role = when (msg.role) {
                "user" -> "you"
                "assistant" -> "xincode"
                "tool" -> "tool"
                else -> msg.role
            }
            if (msg.content.isNotBlank()) {
                sb.appendLine("$role: ${msg.content}")
                sb.appendLine()
            }
        }
        return sb.toString().trimEnd()
    }
}