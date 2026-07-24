package com.xincode.app

import com.xincode.data.ProjectEntity
import com.xincode.data.SessionEntity

/**
 * Sealed hierarchy for the sidebar LazyColumn.
 */
sealed class SidebarItem {
    /** Section label (STARRED / RECENTS / PROJECTS / TOOLS). */
    data class SectionHeader(val label: String) : SidebarItem()

    /** A single conversation entry. */
    data class Conversation(
        val session: SessionEntity,
        val isActive: Boolean
    ) : SidebarItem()

    /** Project header (clickable to expand/collapse). */
    data class ProjectHeader(
        val project: ProjectEntity,
        val sessions: List<SessionEntity>,
        val isExpanded: Boolean
    ) : SidebarItem()

    /** Vertical spacer. */
    data class Spacer(val heightDp: Int) : SidebarItem()
}