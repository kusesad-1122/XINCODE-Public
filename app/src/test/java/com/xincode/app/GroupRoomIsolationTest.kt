package com.xincode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRoomIsolationTest {

    @Test
    fun roomMemoryUsesNegativeScopeAndNeverFallsBackToGlobal() {
        assertEquals(-42L, GroupRoomIsolation.memoryScopeId(42L))
        assertNotEquals(0L, GroupRoomIsolation.memoryScopeId(42L))
        assertTrue(GroupRoomIsolation.memoryScopeId(0L) < 0L)
        assertTrue(GroupRoomIsolation.memoryScopeId(-7L) < 0L)
    }

    @Test
    fun sameNameRoomsGetDifferentNewWorkspacePaths() {
        val first = GroupRoomIsolation.defaultWorkspacePath("/work", "产品团队", 11L)
        val second = GroupRoomIsolation.defaultWorkspacePath("/work", "产品团队", 12L)

        assertNotEquals(first, second)
        assertTrue(first.startsWith("/work/rooms/"))
        assertTrue(first.endsWith("-11"))
        assertTrue(second.endsWith("-12"))
    }

    @Test
    fun internalQuoteTagsAndControlCharactersDoNotReachVisibleReply() {
        val raw = "前文<quote sender=\"架构师\">引用内容</quote>\n正文\u0001\u001f"

        assertEquals("前文引用内容\n正文", GroupRoomIsolation.cleanReplyText(raw))
    }
}
