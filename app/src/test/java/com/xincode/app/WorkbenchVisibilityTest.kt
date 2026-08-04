package com.xincode.app

import com.xincode.data.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkbenchVisibilityTest {

    @Test
    fun hidesInternalPromptAndRawToolProtocol() {
        val messages = listOf(
            MessageEntity(role = "user", content = "内部系统性提示词", sessionId = 9),
            MessageEntity(role = "tool", content = "{\"__tool_call__\":true}", sessionId = 9),
            MessageEntity(role = "assistant", content = "{\"__tool_call__\":true,\"tool_name\":\"shell_exec\"}", sessionId = 9),
            MessageEntity(role = "assistant", content = "已完成权限检查。", sessionId = 9),
            MessageEntity(role = "assistant", content = "", sessionId = 9)
        )

        val visible = workbenchVisibleMessages(messages)

        assertEquals(listOf("已完成权限检查。"), visible.map { it.content })
    }
}
