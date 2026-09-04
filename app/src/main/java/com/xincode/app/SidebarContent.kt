package com.xincode.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.GroupRoomEntity
import com.xincode.data.IdentityEntity
import com.xincode.data.ProjectEntity
import com.xincode.data.SessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A single matched message returned by search. */
data class SearchHit(
    val messageId: Long,
    val sessionId: Long,
    val sessionTitle: String,
    val snippet: String
)

/**
 * Exact Claude-style mobile sidebar layout:
 * - Clean editorial "XINCODE" header
 * - Primary items: Chats / Projects / Artifacts
 * - Groupings: Projects / Pinned / Recents with bullet points and friendly date
 * - Bottom bar: Left "设置" with user badge + Right "+ New chat" pill capsule
 * - No redundant bottom search bar
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarContent(
    currentSessionId: Long,
    starredSessions: List<SessionEntity>,
    ungroupedSessions: List<SessionEntity>,
    projects: List<ProjectEntity>,
    projectSessionsMap: Map<Long, List<SessionEntity>>,
    onCreateNew: () -> Unit,
    onCreateNewInProject: ((Long) -> Unit)? = null,
    onSelectSession: (Long) -> Unit,
    onRenameSession: (Long, String) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onCreateProject: (String) -> Unit,
    onRenameProject: (Long, String) -> Unit,
    onDeleteProject: (Long) -> Unit,
    onSetProjectWorkspace: (Long, String) -> Unit = { _, _ -> },
    onToggleProject: (Long) -> Unit,
    onMoveSessionToProject: (Long, Long?) -> Unit,
    onSetSessionStarred: (Long, Boolean) -> Unit,
    identities: List<IdentityEntity>,
    activeIdentityId: Long,
    onSetActiveIdentity: (Long) -> Unit,
    onCreateIdentity: () -> Unit,
    onNavigateToIdentityList: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToIde: () -> Unit = {},
    onNavigateToGoal: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNavigateToProjects: () -> Unit = {},
    onNavigateToChats: () -> Unit = {},
    onClose: () -> Unit,
    onSearchMessages: suspend (String) -> List<SearchHit> = { emptyList() },
    goalSessions: List<SessionEntity> = emptyList(),
    goalLiveStatus: (Long) -> String = { "" },
    onCreateGoal: () -> Unit = {},
    onSelectGoal: (Long) -> Unit = {},
    groupRooms: List<GroupRoomEntity> = emptyList(),
    onOpenGroupRooms: () -> Unit = {},
    onOpenGroupRoom: (Long) -> Unit = {}
) {
    var renameTarget by remember { mutableStateOf<SessionEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameProjectTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var renameProjectText by remember { mutableStateOf("") }
    var deleteProjectTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var moveTarget by remember { mutableStateOf<Long?>(null) }

    val xc = LocalXinColors.current
    val Bg = xc.bg
    val Ink = xc.ink
    val Sub = xc.sub
    val Divider = xc.divider

    // Format timestamp like 2026年8月3日
    val dateFormat = remember { SimpleDateFormat("yyyy年M月d日", Locale.CHINA) }

    Column(
        Modifier
            .fillMaxHeight()
            .widthIn(max = 330.dp)
            .fillMaxWidth(0.86f)
            .background(Bg)
    ) {
        // ── Claude Editorial Header ──
        Text(
            "XINCODE",
            fontFamily = XinSerifFont,
            fontWeight = FontWeight.Medium,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            color = Ink,
            letterSpacing = (-0.3).sp,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 18.dp)
        )

        // ── Primary Navigation Items (Chats / Projects / Artifacts) ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
        ) {
            ClaudeSidebarNavRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                label = "Chats",
                selected = false,
                onClick = { onNavigateToChats(); onClose() }
            )
            ClaudeSidebarNavRow(
                icon = Icons.Outlined.Folder,
                label = "Projects",
                selected = false,
                onClick = { onNavigateToProjects(); onClose() }
            )
            ClaudeSidebarNavRow(
                icon = Icons.Outlined.Code,
                label = "Artifacts",
                selected = false,
                enabled = true,
                onClick = { onNavigateToIde(); onClose() }
            )
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Divider))
        Spacer(Modifier.height(8.dp))

        // ── Scrollable Sessions & Projects Stream ──
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // Section: Projects
            if (projects.isNotEmpty()) {
                item(key = "hdr_projects") {
                    ClaudeSidebarSectionTitle("Projects")
                }
                items(projects, key = { "proj_${it.id}" }) { proj ->
                    ClaudeSidebarProjectItem(
                        title = proj.name,
                        onClick = { onNavigateToProjects(); onClose() }
                    )
                }
                item(key = "div_projects") {
                    Spacer(Modifier.height(10.dp))
                }
            }

            // Section: Pinned
            if (starredSessions.isNotEmpty()) {
                item(key = "hdr_pinned") {
                    ClaudeSidebarSectionTitle("Pinned")
                }
                items(starredSessions, key = { "pinned_${it.id}" }) { session ->
                    ClaudeSidebarRecentItem(
                        session = session,
                        isActive = session.id == currentSessionId,
                        dateStr = dateFormat.format(Date(session.updatedAt.takeIf { it > 0 } ?: session.createdAt)),
                        isPinned = true,
                        onSelect = { onSelectSession(session.id); onClose() },
                        onRename = { renameTarget = session; renameText = session.title },
                        onDelete = { deleteTarget = session },
                        onTogglePin = { onSetSessionStarred(session.id, false) },
                        onMove = { moveTarget = session.id }
                    )
                }
                item(key = "div_pinned") {
                    Spacer(Modifier.height(10.dp))
                }
            }

            // Section: Recents
            val recentList = ungroupedSessions.filter { !it.isStarred && !it.isGoal }
            if (recentList.isNotEmpty()) {
                item(key = "hdr_recents") {
                    ClaudeSidebarSectionTitle("Recents")
                }
                items(recentList, key = { "recent_${it.id}" }) { session ->
                    ClaudeSidebarRecentItem(
                        session = session,
                        isActive = session.id == currentSessionId,
                        dateStr = dateFormat.format(Date(session.updatedAt.takeIf { it > 0 } ?: session.createdAt)),
                        isPinned = false,
                        onSelect = { onSelectSession(session.id); onClose() },
                        onRename = { renameTarget = session; renameText = session.title },
                        onDelete = { deleteTarget = session },
                        onTogglePin = { onSetSessionStarred(session.id, true) },
                        onMove = { moveTarget = session.id }
                    )
                }
            }

            // Other features (Goals / Rooms) kept cleanly accessible in list
            if (groupRooms.isNotEmpty() || goalSessions.isNotEmpty()) {
                item(key = "hdr_more_workspace") {
                    Spacer(Modifier.height(12.dp))
                    ClaudeSidebarSectionTitle("Workspace")
                }
                items(groupRooms, key = { "room_${it.id}" }) { room ->
                    ClaudeSidebarProjectItem(
                        title = "群聊 · ${room.name}",
                        onClick = { onOpenGroupRoom(room.id); onClose() }
                    )
                }
            }
        }

        // ── Claude Bottom Bar: Settings on left + Small oval "+ New chat" pill on right ──
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Divider))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Settings row with user badge
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        onNavigateToSettings(); onClose()
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(xc.green),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "苦",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = XinUiFont
                    )
                }
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "设置",
                    tint = Ink,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Right: Compact "+ New chat" oval pill capsule
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(Ink)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        onCreateNew(); onClose()
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    tint = Bg,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "New chat",
                    color = Bg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = XinUiFont
                )
            }
        }
    }

    // ── Dialogs ──
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名", color = Ink, fontFamily = XinSerifFont) },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = Ink,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontFamily = XinUiFont)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget?.let { onRenameSession(it.id, renameText) }
                    renameTarget = null
                }) { Text("保存", color = xc.green) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消", color = Sub) }
            },
            containerColor = Bg
        )
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话", color = Ink, fontFamily = XinSerifFont) },
            text = {
                Text(
                    "删除「${deleteTarget?.title}」及其所有记录？",
                    fontSize = 13.sp,
                    color = Sub,
                    fontFamily = XinUiFont
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget?.let { onDeleteSession(it.id) }
                    deleteTarget = null
                }) { Text("删除", color = xc.red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消", color = Sub) }
            },
            containerColor = Bg
        )
    }
}

// ── Claude Sidebar Item Helpers ──

@Composable
private fun ClaudeSidebarNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val xc = LocalXinColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) xc.activeBg else Color.Transparent)
            .clickable(enabled = enabled, indication = null, interactionSource = remember { MutableInteractionSource() }) {
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) xc.ink else xc.faint,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontFamily = XinUiFont,
            color = if (enabled) xc.ink else xc.faint
        )
    }
}

@Composable
private fun ClaudeSidebarSectionTitle(text: String) {
    val xc = LocalXinColors.current
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = XinUiFont,
        color = xc.sub,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun ClaudeSidebarProjectItem(
    title: String,
    onClick: () -> Unit
) {
    val xc = LocalXinColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = xc.sub,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            fontSize = 14.sp,
            fontFamily = XinUiFont,
            color = xc.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClaudeSidebarRecentItem(
    session: SessionEntity,
    isActive: Boolean,
    dateStr: String,
    isPinned: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onMove: () -> Unit
) {
    val xc = LocalXinColors.current
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) xc.bgElevated else Color.Transparent)
            .combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onSelect,
                onLongClick = { showMenu = true }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Claude dot bullet
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isActive) xc.green else xc.faint)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title.ifBlank { "新对话" },
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                    fontFamily = XinUiFont,
                    color = xc.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isPinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "置顶",
                        tint = xc.sub,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            Text(
                dateStr,
                fontSize = 11.sp,
                fontFamily = XinUiFont,
                color = xc.faint,
                modifier = Modifier.padding(top = 2.dp)
            )

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (isPinned) "取消置顶" else "置顶会话", fontFamily = XinUiFont) },
                    onClick = { showMenu = false; onTogglePin() }
                )
                DropdownMenuItem(
                    text = { Text("重命名", fontFamily = XinUiFont) },
                    onClick = { showMenu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("删除", color = xc.red, fontFamily = XinUiFont) },
                    onClick = { showMenu = false; onDelete() }
                )
            }
        }
    }
}
