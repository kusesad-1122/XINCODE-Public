package com.xincode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PromptExpanderTest {

    @Test
    fun extractsOnlyTaggedTransformation() {
        val result = PromptExpander.extractExpandedPrompt(
            "<expanded_prompt>请检查边界条件，并运行可失败的测试。</expanded_prompt>"
        )
        assertEquals("请检查边界条件，并运行可失败的测试。", result)
    }

    @Test
    fun rejectsNormalChatAnswer() {
        assertThrows(IllegalStateException::class.java) {
            PromptExpander.extractExpandedPrompt("好的，我来帮你处理这个任务。")
        }
    }

    @Test
    fun rejectsProseOutsideEnvelope() {
        assertThrows(IllegalStateException::class.java) {
            PromptExpander.extractExpandedPrompt(
                "好的，已经为你整理：<expanded_prompt>请检查边界条件。</expanded_prompt>"
            )
        }
    }

    @Test
    fun rejectsConversationalTextEvenInsideEnvelope() {
        assertThrows(IllegalStateException::class.java) {
            PromptExpander.extractExpandedPrompt(
                "<expanded_prompt>当然可以，我来帮你安装 Node.js。</expanded_prompt>"
            )
        }
    }
}
