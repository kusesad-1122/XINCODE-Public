package com.xincode.app

import com.xincode.data.GroupRoomEntity
import com.xincode.tools.WorkspaceContext
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceMigrationTest {

    @Test
    fun legacyTeamPathMovesUnderCurrentAppDefault() {
        val original = WorkspaceContext.defaultRoot
        try {
            WorkspaceContext.configureDefaultRoot("/data/user/0/com.xincode.app/files/workspace")
            val room = GroupRoomEntity(
                name = "产品团队",
                workspacePath = "/storage/emulated/0/XINCODE/rooms/产品团队"
            )

            assertEquals(
                "/data/user/0/com.xincode.app/files/workspace/rooms/产品团队",
                GroupRoomEngine.workspaceOf(room)
            )
        } finally {
            WorkspaceContext.configureDefaultRoot(original)
        }
    }
}
