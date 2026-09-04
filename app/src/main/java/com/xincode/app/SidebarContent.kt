package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.IdentityEntity
import com.xincode.data.ProjectEntity
import com.xincode.data.GroupRoomEntity
import com.xincode.data.SessionEntity
import androidx.compose.foundation.ExperimentalFoundationApi

/** A single matched message returned by the sidebar search. */
data class SearchHit(
    val messageId: Long,
    val sessionId: Long,
    val sessionTitle: String,
    val snippet: String
)

// Palette is now provided by [LocalXinColors]. Reads are done at Composable-scope
// so switching light↔dark ripples through immediately.

/**
 * Claude-style sidebar with project grouping, starring, and context menus.
 */
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
    onClose: () -> Unit,
    onSearchMessages: suspend (String) -> List<SearchHit> = { emptyList() },
    // ---- Goal/Work 模式 ----
    goalSessions: List<SessionEntity> = emptyList(),
    goalLiveStatus: (Long) -> String = { "" },
    onCreateGoal: () -> Unit = {},
    onSelectGoal: (Long) -> Unit = {},
    // ---- 群聊房间 ----
    // 放侧边栏而不是设置页:它是一种【对话】,和主对话、Goal 任务是同一层的东西,
    // 埋进设置里等于告诉用户「这是个配置项」,那是定位错了。
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
    var workspaceProjectTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var showCreateProject by remember { mutableStateOf(false) }
    var createProjectText by remember { mutableStateOf("") }
    var moveTarget by remember { mutableStateOf<Long?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    val xc = LocalXinColors.current
    val Bg = xc.bg
    val Ink = xc.ink
    val Sub = xc.sub
    val Accent = xc.green
    val Divider = xc.divider

    Column(
        Modifier
            .fillMaxHeight()
            .widthIn(max = 360.dp)
            .fillMaxWidth(0.94f)
            .background(Bg)
    ) {
        // ── Brand and primary navigation ──
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp)) {
            Text(
                "XINCODE",
                fontFamily = XinSerifFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                color = Ink,
                letterSpacing = 0.sp
            )
            Spacer(Modifier.height(3.dp))
            Text("个人 Agent 工作台", fontFamily = XinUiFont, fontSize = 12.sp, color = Sub)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            SidebarMainRow("Chats", Icons.Outlined.AddComment, selected = true) { onClose() }
            SidebarMainRow("Projects", Icons.Outlined.FolderOpen) { onNavigateToProjects(); onClose() }
            SidebarMainRow("Artifacts", Icons.Outlined.Code, enabled = false) {}
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "工作区",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = XinUiFont,
            color = Sub,
            modifier = Modifier.padding(start = 22.dp, bottom = 6.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            SidebarFeatureRow("GOAL 模式", "自主执行目标并跟踪任务", Icons.Outlined.Build) {
                onNavigateToGoal(); onClose()
            }
            SidebarFeatureRow("群聊房间", "多个智能体协作讨论与执行", Icons.Outlined.FolderOpen) {
                onOpenGroupRooms(); onClose()
            }
            SidebarFeatureRow("IDE", "代码、构建与设计工具", Icons.Outlined.Code) {
                onNavigateToIde(); onClose()
            }
            SidebarFeatureRow("MCP", "连接外部工具与服务", Icons.Outlined.Extension) {
                onNavigateToMcp(); onClose()
            }
            SidebarFeatureRow("Skills", "管理可复用的 Agent 技能", Icons.Outlined.Lightbulb) {
                onNavigateToSkills(); onClose()
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Search box ──
        LaunchedEffect(searchQuery) {
            if (searchQuery.length >= 2) {
                kotlinx.coroutines.delay(250) // debounce
                searchHits = onSearchMessages(searchQuery)
            } else {
                searchHits = emptyList()
            }
        }
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(xc.bgElevated)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Search, null, Modifier.size(14.dp), tint = xc.sub)
            Spacer(Modifier.width(6.dp))
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = { Text("搜索对话…", fontSize = 12.sp, color = xc.faint, fontFamily = FontFamily.Serif) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Ink,
                    focusedTextColor = Ink,
                    unfocusedTextColor = Ink
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                modifier = Modifier.weight(1f).height(34.dp)
            )
            if (searchQuery.isNotBlank()) {
                Icon(Icons.Outlined.Close, "清除", Modifier.size(14.dp).clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { searchQuery = "" }, tint = xc.sub)
            }
        }
        Spacer(Modifier.height(8.dp))

        // ── LazyColumn ──
        LazyColumn(Modifier.weight(1f)) {
            if (searchQuery.length >= 2) {
                item(key = "search_header") {
                    SectionHeaderRow("SEARCH · ${searchHits.size}")
                }
                items(searchHits, key = { "hit_${it.messageId}" }) { hit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onSelectSession(hit.sessionId); onClose() }
                            .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Text(
                            hit.sessionTitle.ifBlank { "对话 ${hit.sessionId}" },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            hit.snippet,
                            fontSize = 11.sp,
                            color = Sub,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 15.sp
                        )
                    }
                }
            } else {
            // STARRED
            if (starredSessions.isNotEmpty()) {
                item(key = "header_starred") {
                    SectionHeaderRow("STARRED")
                }
                items(starredSessions, key = { "star_${it.id}" }) { session ->
                    ConversationRow(
                        session = session,
                        isActive = session.id == currentSessionId,
                        onSelect = { onSelectSession(session.id); onClose() },
                        onRename = { renameTarget = session; renameText = session.title },
                        onDelete = { deleteTarget = session },
                        onToggleStar = { onSetSessionStarred(session.id, !session.isStarred) },
                        onMoveToProject = { moveTarget = session.id }
                    )
                }
            }

            // Goal 预览：入口标题统一位于顶部。
            items(goalSessions, key = { "goal_${it.id}" }) { session ->
                GoalRow(
                    session = session,
                    isActive = session.id == currentSessionId,
                    liveStatus = goalLiveStatus(session.id),
                    onSelect = { onSelectGoal(session.id); onClose() },
                    onDelete = { deleteTarget = session }
                )
            }

            // 群聊预览：入口标题统一位于顶部。
            items(groupRooms, key = { "room_${it.id}" }) { room ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            onOpenGroupRoom(room.id); onClose()
                        }
                        .padding(start = 20.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(room.name, fontSize = 12.sp, color = Ink, fontFamily = FontFamily.Serif,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (room.fullAccess) {
                        Text("完全访问", fontSize = 8.sp, color = xc.red, fontFamily = FontFamily.Serif)
                    }
                }
            }

            // RECENTS (ungrouped)
            if (ungroupedSessions.isNotEmpty()) {
                item(key = "header_recents") {
                    SectionHeaderRow("RECENTS")
                }
                items(ungroupedSessions, key = { "ungrp_${it.id}" }) { session ->
                    ConversationRow(
                        session = session,
                        isActive = session.id == currentSessionId,
                        onSelect = { onSelectSession(session.id); onClose() },
                        onRename = { renameTarget = session; renameText = session.title },
                        onDelete = { deleteTarget = session },
                        onToggleStar = { onSetSessionStarred(session.id, !session.isStarred) },
                        onMoveToProject = { moveTarget = session.id }
                    )
                }
            }

            // PROJECTS
            item(key = "header_projects") {
                SectionHeaderRow("PROJECTS", onAddProject = { showCreateProject = true })
            }
            items(projects, key = { "proj_${it.id}" }) { project ->
                val sessions = projectSessionsMap[project.id] ?: emptyList()
                ProjectHeaderRow(
                    project = project,
                    isExpanded = project.isExpanded,
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    onToggle = { onToggleProject(project.id) },
                    onSelectSession = { id -> onSelectSession(id); onClose() },
                    onRename = { renameProjectTarget = project; renameProjectText = project.name },
                    onDelete = { deleteProjectTarget = project },
                    onSetWorkspace = { workspaceProjectTarget = project },
                    onCreateConversation = { onCreateNewInProject?.invoke(project.id) },
                    onConvRename = { session -> renameTarget = session; renameText = session.title },
                    onConvDelete = { session -> deleteTarget = session },
                    onConvToggleStar = { id, starred -> onSetSessionStarred(id, starred) },
                    onConvMoveToProject = { id -> moveTarget = id }
                )
            }

            // IDENTITY
            item(key = "identity_section") {
                IdentitySwitcherRow(
                    identities = identities,
                    activeIdentityId = activeIdentityId,
                    onSetActiveIdentity = { onSetActiveIdentity(it) },
                    onCreateIdentity = { onCreateIdentity(); onClose() },
                    onManageIdentities = { onNavigateToIdentityList(); onClose() }
                )
            }
            } // end else (non-search branch)
        }

        // ── New chat and settings ──
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(min = 48.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Ink)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    onCreateNew(); onClose()
                }
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = Bg)
            Spacer(Modifier.width(10.dp))
            Text("New chat", fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = XinUiFont, color = Bg)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(0.5.dp).background(Divider))
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    onNavigateToSettings(); onClose()
                }.padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Settings, null, Modifier.size(16.dp), tint = Ink)
            Spacer(Modifier.width(12.dp))
            Text("设置", fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(24.dp))
    }

    // ── Dialogs ──
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名", color = Ink) },
            text = { TextField(value = renameText, onValueChange = { renameText = it }, singleLine = true,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)) },
            confirmButton = { TextButton(onClick = { renameTarget?.let { onRenameSession(it.id, renameText) }; renameTarget = null }) { Text("保存", color = Accent) } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消", color = Sub) } },
            containerColor = Bg
        )
    }
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话", color = Ink) },
            text = { Text("删除「${deleteTarget?.title}」及其所有消息？\n对话会移到未分组。", fontSize = 12.sp, color = Sub) },
            confirmButton = { TextButton(onClick = { deleteTarget?.let { onDeleteSession(it.id) }; deleteTarget = null }) { Text("删除", color = Color(0xFFA8514A)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消", color = Sub) } },
            containerColor = Bg
        )
    }
    if (renameProjectTarget != null) {
        AlertDialog(
            onDismissRequest = { renameProjectTarget = null },
            title = { Text("重命名项目", color = Ink) },
            text = { TextField(value = renameProjectText, onValueChange = { renameProjectText = it }, singleLine = true,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)) },
            confirmButton = { TextButton(onClick = { renameProjectTarget?.let { onRenameProject(it.id, renameProjectText) }; renameProjectTarget = null }) { Text("保存", color = Accent) } },
            dismissButton = { TextButton(onClick = { renameProjectTarget = null }) { Text("取消", color = Sub) } },
            containerColor = Bg
        )
    }
    if (deleteProjectTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteProjectTarget = null },
            title = { Text("删除项目", color = Ink) },
            text = { Text("删除项目「${deleteProjectTarget?.name}」？项目内对话会移到未分组，不会丢失。", fontSize = 12.sp, color = Sub) },
            confirmButton = { TextButton(onClick = { deleteProjectTarget?.let { onDeleteProject(it.id) }; deleteProjectTarget = null }) { Text("删除", color = Color(0xFFA8514A)) } },
            dismissButton = { TextButton(onClick = { deleteProjectTarget = null }) { Text("取消", color = Sub) } },
            containerColor = Bg
        )
    }
    if (showCreateProject) {
        AlertDialog(
            onDismissRequest = { showCreateProject = false; createProjectText = "" },
            title = { Text("新建项目", color = Ink) },
            text = { TextField(value = createProjectText, onValueChange = { createProjectText = it }, singleLine = true,
                placeholder = { Text("项目名称", fontSize = 13.sp, color = Sub) },
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)) },
            confirmButton = {
                TextButton(onClick = {
                    if (createProjectText.isNotBlank()) { onCreateProject(createProjectText.trim()); showCreateProject = false; createProjectText = "" }
                }, enabled = createProjectText.isNotBlank()) { Text("创建", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { showCreateProject = false; createProjectText = "" }) { Text("取消", color = Sub) } },
            containerColor = Bg
        )
    }
    if (moveTarget != null) {
        MoveToProjectDialog(
            projects = projects,
            onSelect = { pid -> moveTarget?.let { onMoveSessionToProject(it, pid) }; moveTarget = null },
            onDismiss = { moveTarget = null }
        )
    }
    // 项目独立工作目录:文件夹选择器。选定后写入该项目 workspaceRoot(空=回退全局/默认)。
    workspaceProjectTarget?.let { proj ->
        DirectoryPickerDialog(
            initialPath = proj.workspaceRoot,
            onConfirm = { path -> onSetProjectWorkspace(proj.id, path); workspaceProjectTarget = null },
            onDismiss = { workspaceProjectTarget = null }
        )
    }
}

// ── Sub-components ──

/** 侧栏 Goal 任务行:标题 + 状态点(运行中/已达成/未达成)+ 实时状态小字。长按删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GoalRow(session: SessionEntity, isActive: Boolean, liveStatus: String, onSelect: () -> Unit, onDelete: () -> Unit) {
    val xc = LocalXinColors.current
    val (dot, statusText) = when {
        liveStatus.isNotBlank() && session.goalStatus == "running" -> xc.yellow to liveStatus
        session.goalStatus == "running" -> xc.yellow to (liveStatus.ifBlank { "执行中…" })
        session.goalStatus == "achieved" -> xc.green to "✓ 已达成"
        session.goalStatus == "failed" -> xc.red to "✗ 未达成"
        else -> xc.faint to "未开始"
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (isActive) xc.bgElevated else Color.Transparent)
            .combinedClickable(
                indication = null, interactionSource = remember { MutableInteractionSource() },
                onClick = onSelect, onLongClick = onDelete
            )
            .padding(start = 20.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(session.title.ifBlank { "目标任务" }, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = xc.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(statusText, fontSize = 10.sp, color = xc.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionHeaderRow(label: String, onAddProject: (() -> Unit)? = null) {
    val Sub = LocalXinColors.current.sub
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Sub, letterSpacing = 0.08.sp, modifier = Modifier.weight(1f))
        if (onAddProject != null) {
            IconButton(onClick = onAddProject, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Outlined.Add, "新建项目", Modifier.size(14.dp), tint = Sub)
            }
        }
    }
}

@Composable
private fun IdentitySwitcherRow(
    identities: List<IdentityEntity>,
    activeIdentityId: Long,
    onSetActiveIdentity: (Long) -> Unit,
    onCreateIdentity: () -> Unit,
    onManageIdentities: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val activeName = identities.firstOrNull { it.id == activeIdentityId }?.name ?: "无"
    val xc = LocalXinColors.current
    val Ink = xc.ink
    val Sub = xc.sub
    val Accent = xc.green

    Column {
        SectionHeaderRow("IDENTITY")
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { menuExpanded = true }
                    .padding(start = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("当前身份:", fontSize = 12.sp, color = Sub, modifier = Modifier.padding(end = 4.dp))
                Text(activeName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "+",
                fontSize = 14.sp,
                color = Sub,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onCreateIdentity() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            identities.forEach { identity ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (identity.id == activeIdentityId) "▓ " else "▢ ", color = Accent, fontSize = 12.sp)
                            Text(identity.name, fontSize = 13.sp, color = Ink)
                        }
                    },
                    onClick = { onSetActiveIdentity(identity.id); menuExpanded = false }
                )
            }
            DropdownMenuItem(
                text = { Text("管理身份卡…", fontSize = 12.sp, color = Sub) },
                onClick = { menuExpanded = false; onManageIdentities() }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    session: SessionEntity, isActive: Boolean,
    onSelect: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
    onToggleStar: () -> Unit, onMoveToProject: () -> Unit,
    startPadding: Int = 0
) {
    var showMenu by remember { mutableStateOf(false) }
    val xc = LocalXinColors.current
    val Ink = xc.ink
    val Sub = xc.sub
    val Accent = xc.green
    val ActiveBg = xc.activeBg
    val ActiveBar = xc.activeBar
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Active left accent bar
        Box(
            Modifier
                .width(3.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isActive) ActiveBar else Color.Transparent)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .padding(end = 8.dp)
                .then(
                    if (isActive)
                        Modifier.clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)).background(ActiveBg)
                    else
                        Modifier
                )
                .combinedClickable(onClick = onSelect, onLongClick = { showMenu = true })
                .padding(start = (12 + startPadding).dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                session.title,
                fontSize = 13.sp,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                color = if (isActive) Ink else Color(0xFF3A3A35),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (session.isStarred) {
                Icon(Icons.Outlined.Star, null, Modifier.size(11.dp), tint = Accent)
                Spacer(Modifier.width(4.dp))
            }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                    Text("⋯", fontSize = 12.sp, color = Sub)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, offset = DpOffset(0.dp, 0.dp)) {
                    DropdownMenuItem(
                        text = { Text(if (session.isStarred) "取消收藏" else "收藏", fontSize = 12.sp) },
                        onClick = { showMenu = false; onToggleStar() },
                        leadingIcon = { Icon(if (session.isStarred) Icons.Outlined.Star else Icons.Outlined.StarBorder, null, Modifier.size(14.dp), tint = Ink) }
                    )
                    DropdownMenuItem(
                        text = { Text("重命名", fontSize = 12.sp) },
                        onClick = { showMenu = false; onRename() },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null, Modifier.size(14.dp), tint = Ink) }
                    )
                    DropdownMenuItem(
                        text = { Text("移到项目", fontSize = 12.sp) },
                        onClick = { showMenu = false; onMoveToProject() },
                        leadingIcon = { Icon(Icons.Outlined.Folder, null, Modifier.size(14.dp), tint = Ink) }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", fontSize = 12.sp, color = Color(0xFFA8514A)) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, Modifier.size(14.dp), tint = Color(0xFFA8514A)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectHeaderRow(
    project: ProjectEntity, isExpanded: Boolean, sessions: List<SessionEntity>,
    currentSessionId: Long, onToggle: () -> Unit, onSelectSession: (Long) -> Unit,
    onRename: () -> Unit, onDelete: () -> Unit,
    onSetWorkspace: () -> Unit,
    onCreateConversation: () -> Unit,
    onConvRename: (SessionEntity) -> Unit, onConvDelete: (SessionEntity) -> Unit,
    onConvToggleStar: (Long, Boolean) -> Unit, onConvMoveToProject: (Long) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val xc = LocalXinColors.current
    val Ink = xc.ink
    val Sub = xc.sub
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggle() }
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (isExpanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, null, Modifier.size(12.dp), tint = Ink)
            Spacer(Modifier.width(8.dp))
            Text(project.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // "+" button for creating conversation in this project
            Text(
                "+",
                fontSize = 14.sp,
                color = Sub,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onCreateConversation() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(20.dp)) { Text("⋯", fontSize = 10.sp, color = Sub) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, offset = DpOffset(0.dp, 0.dp)) {
                    DropdownMenuItem(text = { Text("重命名", fontSize = 12.sp) }, onClick = { showMenu = false; onRename() },
                        leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null, Modifier.size(14.dp), tint = Ink) })
                    DropdownMenuItem(text = { Text("工作目录", fontSize = 12.sp) }, onClick = { showMenu = false; onSetWorkspace() },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(14.dp), tint = Ink) })
                    DropdownMenuItem(text = { Text("删除", fontSize = 12.sp, color = Color(0xFFA8514A)) }, onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, Modifier.size(14.dp), tint = Color(0xFFA8514A)) })
                }
            }
        }
        if (isExpanded) {
            sessions.forEach { session ->
                ConversationRow(
                    session = session, isActive = session.id == currentSessionId,
                    onSelect = { onSelectSession(session.id) },
                    onRename = { onConvRename(session) },
                    onDelete = { onConvDelete(session) },
                    onToggleStar = { onConvToggleStar(session.id, !session.isStarred) },
                    onMoveToProject = { onConvMoveToProject(session.id) },
                    startPadding = 12
                )
            }
        }
    }
}

@Composable
private fun SidebarMainRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val xc = LocalXinColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) xc.activeBg else Color.Transparent)
            .clickable(enabled = enabled, indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = if (enabled) xc.ink else xc.faint)
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, fontFamily = XinUiFont, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (enabled) xc.ink else xc.faint)
    }
}

@Composable
private fun SidebarFeatureRow(
    label: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val xc = LocalXinColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label, Modifier.size(18.dp), tint = xc.sub)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = xc.ink,
                fontFamily = XinSerifFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(desc, fontSize = 11.sp, color = xc.sub, fontFamily = XinUiFont,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        }
        Text("›", fontSize = 18.sp, color = xc.faint, fontFamily = FontFamily.Serif,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun MoveToProjectDialog(projects: List<ProjectEntity>, onSelect: (Long?) -> Unit, onDismiss: () -> Unit) {
    val xc = LocalXinColors.current
    val Ink = xc.ink
    val Sub = xc.sub
    val Bg = xc.bg
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移到项目", color = Ink) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("未分组", fontSize = 13.sp, color = Ink)
                }
                projects.forEach { project ->
                    Row(Modifier.fillMaxWidth().clickable { onSelect(project.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(project.name, fontSize = 13.sp, color = Ink)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Sub) } },
        containerColor = Bg
    )
}

