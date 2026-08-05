package com.xincode.app

import com.xincode.data.GroupMessageEntity
import com.xincode.data.GroupRoomSummaryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChatSupportTest {

    private fun msg(
        id: Long,
        ts: Long,
        sender: String = "",
        content: String = "c$id",
        runId: String = "",
        phase: Int = GroupMessagePhase.USER,
        kind: String = "message",
        streaming: Boolean = false,
        interrupted: Boolean = false
    ) = GroupMessageEntity(
        id = id, roomId = 1, sender = sender, content = content,
        runId = runId, phase = phase, kind = kind,
        streaming = streaming, interrupted = interrupted, ts = ts
    )

    @Test
    fun canonicalOrderingKeepsRunPartsTogether() {
        val messages = listOf(
            msg(1, 100, content = "用户消息"),
            msg(5, 201, sender = "乙", content = "乙的最终回复", runId = "r2", phase = GroupMessagePhase.ASSISTANT),
            msg(2, 200, sender = "甲", content = "🔧 shell", runId = "r1", phase = GroupMessagePhase.TOOL_CALL, kind = "toolcall"),
            msg(4, 200, sender = "甲", content = "甲:补充说明", runId = "r1", phase = GroupMessagePhase.ASSISTANT),
            msg(3, 200, sender = "甲", content = "exit 0", runId = "r1", phase = GroupMessagePhase.TOOL_RESULT, kind = "toolresult")
        )

        val ordered = sortGroupMessagesCanonical(messages)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ordered.map { it.id })
    }

    @Test
    fun sseLineParsingExtractsContentReasoningAndUsage() {
        val content = parseGroupSseLine(
            """data: {"choices":[{"delta":{"content":"你好"},"index":0,"finish_reason":null}]}"""
        )
        assertEquals("你好", content?.content)

        val reasoning = parseGroupSseLine(
            """data: {"choices":[{"delta":{"reasoning_content":"先查一下"},"index":0}]}"""
        )
        assertEquals("先查一下", reasoning?.reasoning)

        val usage = parseGroupSseLine(
            """data: {"usage":{"prompt_tokens":10,"completion_tokens":3}}"""
        )
        assertEquals(10, usage?.usage?.optInt("prompt_tokens"))

        assertNull(parseGroupSseLine("data: [DONE]"))
        assertNull(parseGroupSseLine("ping"))
    }

    @Test
    fun summarySlicingUsesCursorAndFallsBackToTimestamp() {
        val messages = listOf(
            msg(1, 100, content = "第一句"),
            msg(2, 200, sender = "甲", content = "第二句"),
            msg(3, 300, content = "第三句")
        )
        val summary = GroupRoomSummaryEntity(
            roomId = 1, summary = "旧总结",
            summaryThroughMessageId = 2, summaryThroughMessageTimestamp = 200
        )
        assertEquals(listOf(3L), groupMessagesAfterSummary(messages, summary).map { it.id })

        // 游标消息被删(例如旧的一次性压缩)时,按时间戳回退,不猜。
        val orphan = summary.copy(summaryThroughMessageId = 999)
        assertEquals(listOf(3L), groupMessagesAfterSummary(messages, orphan).map { it.id })
    }

    @Test
    fun summaryPromptBundlesPreviousAndIncrementalMessages() {
        val prompt = buildGroupSummaryPrompt(
            "旧总结",
            listOf(
                msg(1, 100, content = "用户要求 X"),
                msg(2, 200, sender = "甲", content = "甲报告完成")
            )
        )
        assertTrue(prompt.contains("旧总结"))
        assertTrue(prompt.contains("用户要求 X"))
        assertTrue(prompt.contains("甲报告完成"))
        assertTrue(prompt.contains("<summary_data>"))
    }

    @Test
    fun cleanSummaryMessagesSkipsToolAndStreamingRows() {
        val messages = listOf(
            msg(1, 100, content = "正常消息"),
            msg(2, 200, sender = "甲", content = "🔧 shell", kind = "toolcall"),
            msg(3, 300, content = "流式中", streaming = true),
            msg(4, 400, content = "已中断", interrupted = true),
            msg(5, 500, content = "工作区变更", kind = "diff")
        )
        assertEquals(listOf(1L), cleanGroupMessagesForSummary(messages).map { it.id })
    }

    @Test
    fun toolEventParsingReadsWorkSessionJson() {
        val json = """{"__tool_call__":true,"tool_name":"shell_exec","params_summary":"uname -a","stdout":"Linux","stderr":"","exit_code":0,"status":"SUCCESS"}"""
        val ev = parseGroupToolEvent(json)
        assertEquals("shell_exec", ev?.toolName)
        assertEquals(0, ev?.exitCode)
        assertEquals("SUCCESS", ev?.status)
        assertNull(parseGroupToolEvent("不是工具消息"))
    }
}
