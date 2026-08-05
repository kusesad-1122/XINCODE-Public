package com.xincode.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupWorkTurnResultTest {

    @Test
    fun returnsFinalAssistantSegmentFromCurrentTurn() {
        val messages = listOf(
            message("assistant", "old workbench reply"),
            message("user", "start product planning"),
            message("assistant", "I will read the existing scope first"),
            message("tool", "file_read"),
            message("assistant", "Report to group: @architect choose storage; @engineer estimate delivery")
        )

        assertEquals(
            "Report to group: @architect choose storage; @engineer estimate delivery",
            finalAssistantContentSince(messages, startIndex = 1)
        )
    }

    @Test
    fun neverFallsBackToAnEarlierTurn() {
        val messages = listOf(
            message("assistant", "old reply"),
            message("user", "new turn"),
            message("tool", "shell")
        )

        assertEquals("", finalAssistantContentSince(messages, startIndex = 1))
    }

    private fun message(role: String, content: String) = ChatState.MessageUi(
        id = 0L,
        role = role,
        content = content,
        timestamp = 0L
    )
}
