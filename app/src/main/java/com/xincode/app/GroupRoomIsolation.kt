package com.xincode.app

/** Pure rules shared by group-room persistence, runtime binding and regression tests. */
internal object GroupRoomIsolation {

    private val INTERNAL_QUOTE_TAG = Regex(
        """</?quote(?:\s+[^>]*)?>""",
        RegexOption.IGNORE_CASE
    )
    private val CONTROL_CHARACTER = Regex(
        "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"
    )

    /** Use a negative room id so it can never collide with a real positive project id. */
    fun memoryScopeId(roomId: Long): Long =
        if (roomId > 0L) -roomId else Long.MIN_VALUE

    /**
     * New rooms get an id-bearing directory. Existing rooms with an explicitly stored path
     * remain untouched, so an upgrade never silently moves user files.
     */
    fun defaultWorkspacePath(root: String, roomName: String, roomId: Long): String {
        val base = root.trim().trimEnd('/').ifBlank { "/" }
        val safeName = roomName
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace("..", "_")
            .trim()
            .ifBlank { "room" }
        val suffix = if (roomId > 0L) "-$roomId" else "-new"
        return if (base == "/") "/rooms/$safeName$suffix" else "$base/rooms/$safeName$suffix"
    }

    /** Remove internal protocol wrappers without touching normal Markdown or emoji. */
    fun cleanReplyText(text: String): String = text
        .replace(INTERNAL_QUOTE_TAG, "")
        .replace(CONTROL_CHARACTER, "")
        .trim()
}
