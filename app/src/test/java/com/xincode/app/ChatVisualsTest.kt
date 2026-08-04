package com.xincode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatVisualsTest {

    @Test
    fun providerIcon_usesOfficialBrandAndSafeFallback() {
        assertEquals(R.drawable.provider_deepseek, providerIconRes("deepseek"))
        assertEquals(R.drawable.provider_qwen, providerIconRes("dashscope"))
        assertEquals(R.drawable.provider_openrouter, providerIconRes(" OPENROUTER "))
        assertEquals(R.mipmap.ic_launcher_round, providerIconRes("custom"))
    }

    @Test
    fun assistantSubtitle_includesAssistantModelAndProvider() {
        assertEquals(
            "默认助手 / deepseek-v4-flash (DeepSeek)",
            assistantSubtitle("默认助手", "deepseek-v4-flash", "DeepSeek")
        )
    }

    @Test
    fun assistantSubtitle_usesSafeFallbacksForMissingConfiguration() {
        assertEquals(
            "默认助手 / 未选择模型",
            assistantSubtitle("", "", "")
        )
    }

    @Test
    fun derivedThinkingDuration_usesFirstToolAfterAssistant() {
        val assistant = ChatState.MessageUi(1, "assistant", "", 1_000L)
        val firstTool = ChatState.MessageUi(2, "tool", "", 1_320L)
        val secondTool = ChatState.MessageUi(3, "tool", "", 2_400L)
        val group = TurnGroup(
            key = "turn",
            turnId = 1,
            assistantMessage = assistant,
            toolMessages = listOf(secondTool, firstTool)
        )

        assertEquals(320L, derivedThinkingDurationMs(group))
        assertEquals("思考了 0.3 秒", formatThinkingLabel(derivedThinkingDurationMs(group)))
    }

    @Test
    fun derivedThinkingDuration_rejectsMissingOrOutOfOrderToolTime() {
        val assistant = ChatState.MessageUi(1, "assistant", "", 2_000L)
        val staleTool = ChatState.MessageUi(2, "tool", "", 1_000L)

        assertNull(
            derivedThinkingDurationMs(
                TurnGroup("turn", 1, assistantMessage = assistant, toolMessages = listOf(staleTool))
            )
        )
        assertEquals("思考过程", formatThinkingLabel(null))
    }
}
