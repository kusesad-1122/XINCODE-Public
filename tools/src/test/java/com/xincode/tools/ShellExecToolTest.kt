package com.xincode.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellExecToolTest {

    @Test
    fun appendBoundedKeepsRecentOutput() {
        val sb = StringBuilder()
        appendBounded(sb, "aaaa", 8)
        appendBounded(sb, "bbbb", 8)
        // 只保留最近 8 字符(可能从半行开始),最新内容必须完整保留
        assertTrue(sb.toString().endsWith("bbbb\n"))
        assertTrue(sb.length <= 8)
    }

    @Test
    fun appendBoundedUnderCapKeepsEverything() {
        val sb = StringBuilder()
        appendBounded(sb, "hello", 100)
        appendBounded(sb, "world", 100)
        assertEquals("hello\nworld", sb.toString().trim())
    }
}
