package com.xincode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun thinkingLevelLabel_usesChineseLabelsAndSafeFallback() {
        assertEquals("低", thinkingLevelLabel(0))
        assertEquals("极致", thinkingLevelLabel(4))
        assertEquals("高", thinkingLevelLabel(99))
    }

    @Test
    fun voiceFeedback_exposesStartupPartialAndErrorStates() {
        val starting = voiceUiFeedback(VoiceInputHelper.State.STARTING)
        assertTrue(starting.active)
        assertEquals("正在启动语音识别…", starting.message)

        val listening = voiceUiFeedback(VoiceInputHelper.State.LISTENING, partialText = "帮我写代码")
        assertTrue(listening.active)
        assertEquals("帮我写代码", listening.message)

        val error = voiceUiFeedback(VoiceInputHelper.State.ERROR, errorMessage = "设备不支持语音识别")
        assertTrue(error.isError)
        assertEquals("设备不支持语音识别", error.message)
    }
}
