package com.xincode.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolSessionContextTest {

    @Test
    fun sessionIdSurvivesDispatcherSwitchAndRestores() = runBlocking {
        assertNull(ToolSessionContext.sessionId)

        withContext(ToolSessionElement(42L)) {
            assertEquals(42L, ToolSessionContext.sessionId)
            withContext(Dispatchers.Default) {
                assertEquals(42L, ToolSessionContext.sessionId)
            }
        }

        assertNull(ToolSessionContext.sessionId)
    }
}
