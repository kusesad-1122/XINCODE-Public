package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted agent state for resume after kill.
 * Only one active cursor per session. Cleared when conversation completes.
 *
 * [messagesSnapshot] is a JSON array of the full conversation history at checkpoint time,
 * sufficient to resume the agent loop without data loss.
 */
@Entity(tableName = "state_cursor")
data class StateCursorEntity(
    @PrimaryKey
    val sessionId: Long,
    /** Current loop iteration. */
    val iteration: Int = 0,
    /** Agent state at checkpoint (e.g. "Idle", "CallingTool"). */
    val state: String = "Idle",
    /** JSON array of all messages in the conversation history. */
    val messagesJson: String = "[]",
    /** If tool was executed but result not yet fed back: the ToolCall JSON. */
    val pendingToolCallJson: String? = null,
    /** If tool was executed but result not yet fed back: the ToolResult JSON. */
    val pendingToolResultJson: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)