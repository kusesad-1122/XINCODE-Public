package com.xincode.app

/**
 * Sealed class for structured message content types.
 * When MessageUi.contentBlock is non-null, it is rendered instead of raw content text.
 */
sealed class MessageContent {

    /** Plain text message (existing fallback). */
    data class Text(val content: String) : MessageContent()

    /** Tool execution block — Claude-style collapsible row. */
    data class ToolCall(
        val toolName: String,               // "shell_exec" / "file_read" / "web_search" / ...
        val paramsSummary: String,          // one-line param summary, e.g. "ls /sdcard"
        val fullParams: String = "",        // raw JSON arguments
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int? = null,
        val durationMs: Long? = null,
        val status: ToolStatus = ToolStatus.PENDING
    ) : MessageContent()

    /** File read block — rendered as a code viewer. */
    data class FileRead(
        val path: String,
        val content: String,
        val startLine: Int = 1,
        val totalLines: Int = 0
    ) : MessageContent()

    /** File edit diff block — rendered as a side-by-side diff. */
    data class FileEdit(
        val path: String,
        val diffLines: List<DiffLine>
    ) : MessageContent()
}

enum class ToolStatus { PENDING, RUNNING, SUCCESS, FAILED, DENIED }

data class DiffLine(
    val type: DiffType,
    val content: String
)

enum class DiffType { CONTEXT, ADD, DELETE }