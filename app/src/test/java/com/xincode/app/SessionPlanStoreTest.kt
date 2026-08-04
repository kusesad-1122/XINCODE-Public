package com.xincode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanStoreTest {

    @Test
    fun plansAreIsolatedBySession() {
        val store = SessionPlanStore()
        val normalChat = store.forSession(11L)
        val teamWorkbench = store.forSession(22L)

        teamWorkbench.setPlan("设计任务", listOf("审计", "改稿"))

        assertNotSame(normalChat, teamWorkbench)
        assertFalse(normalChat.visible)
        assertTrue(teamWorkbench.visible)
        assertEquals("设计任务", teamWorkbench.title)
    }

    @Test
    fun newConversationStartsWithoutPreviousPlan() {
        val store = SessionPlanStore()
        store.forSession(1L).setPlan("旧任务", listOf("一步"))

        val newConversation = store.forSession(2L)

        assertFalse(newConversation.visible)
        assertTrue(newConversation.steps.isEmpty())
    }

    @Test
    fun removingSessionDropsItsPlan() {
        val store = SessionPlanStore()
        val old = store.forSession(7L)
        old.setPlan("待删除", listOf("一步"))

        store.remove(7L)
        val replacement = store.forSession(7L)

        assertNotSame(old, replacement)
        assertFalse(replacement.visible)
    }
}
