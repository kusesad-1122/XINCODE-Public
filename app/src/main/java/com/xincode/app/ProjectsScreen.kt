package com.xincode.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.ProjectEntity
import com.xincode.data.SessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Claude-style Projects screen:
 * - Project index with search and creation
 * - Project detail page with memory, knowledge, custom instructions, and sessions
 * - Edit details modal bottom sheet ("What are you working on?", "What are you trying to achieve?")
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    projects: List<ProjectEntity>,
    projectSessions: Map<Long, List<SessionEntity>>,
    onBack: () -> Unit,
    onCreateProject: (String) -> Unit,
    onCreateNewInProject: (Long) -> Unit,
    onSelectSession: (Long) -> Unit,
    onRenameProject: (Long, String) -> Unit,
    onDeleteProject: (Long) -> Unit
) {
    val xc = LocalXinColors.current
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var menuProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var selectedProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var showEditDetails by remember { mutableStateOf(false) }
    var editWorkingOn by remember { mutableStateOf("") }
    var editAchieve by remember { mutableStateOf("") }

    // Keep selected project updated if projects list updates
    val currentProject = remember(projects, selectedProject) {
        selectedProject?.let { sel -> projects.firstOrNull { it.id == sel.id } }
    }

    val dateFormat = remember { SimpleDateFormat("yyyy年M月d日", Locale.CHINA) }

    if (currentProject != null) {
        // ── PROJECT DETAIL VIEW (Screenshots 23:11:16 & 23:11:19) ──
        var detailMenuExpanded by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize().background(xc.bg)) {
            Column(Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedProject = null }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回项目列表", tint = xc.ink)
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { detailMenuExpanded = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作", tint = xc.ink)
                        }
                        DropdownMenu(
                            expanded = detailMenuExpanded,
                            onDismissRequest = { detailMenuExpanded = false },
                            modifier = Modifier.background(xc.bgElevated)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Pin", fontFamily = XinUiFont) },
                                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                                onClick = { detailMenuExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Edit details", fontFamily = XinUiFont) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    detailMenuExpanded = false
                                    editWorkingOn = currentProject.name
                                    editAchieve = currentProject.workspaceRoot
                                    showEditDetails = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", fontFamily = XinUiFont, color = xc.red) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = xc.red) },
                                onClick = {
                                    detailMenuExpanded = false
                                    onDeleteProject(currentProject.id)
                                    selectedProject = null
                                }
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    // Heading: Project Name
                    item(key = "title") {
                        Text(
                            text = currentProject.name,
                            fontFamily = XinSerifFont,
                            fontWeight = FontWeight.Medium,
                            fontSize = 32.sp,
                            color = xc.ink
                        )
                        Spacer(Modifier.height(4.dp))
                        // Subtitle
                        Text(
                            text = currentProject.workspaceRoot.ifBlank { "一个自研项目" },
                            fontFamily = XinUiFont,
                            fontSize = 15.sp,
                            color = xc.sub
                        )
                        Spacer(Modifier.height(10.dp))
                        // Private badge
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(xc.bgElevated)
                                .border(0.8.dp, xc.border, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, tint = xc.sub, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Private", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.sub)
                        }
                        Spacer(Modifier.height(20.dp))
                    }

                    // Card 1: Memory card
                    item(key = "memory_card") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(xc.bgElevated)
                                .border(0.8.dp, xc.border, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                "Project memory will appear after a few chats.",
                                fontFamily = XinUiFont,
                                fontSize = 13.sp,
                                color = xc.sub
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                    }

                    // Card 2: Knowledge & Instructions (two columns)
                    item(key = "knowledge_instructions") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Left: Project knowledge
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(xc.bgElevated)
                                    .border(0.8.dp, xc.border, RoundedCornerShape(16.dp))
                                    .padding(16.dp)
                            ) {
                                Text("Project knowledge", fontFamily = XinUiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = xc.ink)
                                Spacer(Modifier.height(14.dp))
                                Text("Add knowledge", fontFamily = XinUiFont, fontSize = 13.sp, color = xc.green)
                            }

                            // Right: Custom instructions
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(xc.bgElevated)
                                    .border(0.8.dp, xc.border, RoundedCornerShape(16.dp))
                                    .padding(16.dp)
                            ) {
                                Text("Custom instructions", fontFamily = XinUiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = xc.ink)
                                Spacer(Modifier.height(14.dp))
                                Text("Add instructions", fontFamily = XinUiFont, fontSize = 13.sp, color = xc.green)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }

                    // Sessions in this project
                    val sessions = projectSessions[currentProject.id].orEmpty()
                    items(sessions, key = { it.id }) { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectSession(session.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(xc.faint)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    session.title.ifBlank { "新对话" },
                                    fontSize = 15.sp,
                                    fontFamily = XinUiFont,
                                    fontWeight = FontWeight.Medium,
                                    color = xc.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    dateFormat.format(Date(session.updatedAt.takeIf { t -> t > 0 } ?: session.createdAt)),
                                    fontSize = 12.sp,
                                    fontFamily = XinUiFont,
                                    color = xc.faint
                                )
                            }
                        }
                    }
                }
            }

            // Bottom-right "+ New chat" pill in this project
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .height(46.dp)
                    .clip(CircleShape)
                    .background(xc.ink)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { onCreateNewInProject(currentProject.id) }
                    )
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = xc.bg, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("New chat", color = xc.bg, fontFamily = XinUiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
        }

        // ── EDIT DETAILS MODAL BOTTOM SHEET (Screenshot 23:11:19) ──
        if (showEditDetails) {
            ModalBottomSheet(
                onDismissRequest = { showEditDetails = false },
                containerColor = xc.bgElevated,
                dragHandle = {
                    Box(Modifier.padding(top = 10.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(xc.border))
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showEditDetails = false }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = xc.ink)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Edit details", fontFamily = XinSerifFont, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = xc.ink)
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(36.dp))
                    }

                    Spacer(Modifier.height(18.dp))
                    Text("What are you working on?", fontFamily = XinUiFont, fontSize = 14.sp, color = xc.sub)
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = editWorkingOn,
                        onValueChange = { editWorkingOn = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(xc.bg)
                            .border(0.8.dp, xc.border, RoundedCornerShape(14.dp)),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = xc.green,
                            focusedTextColor = xc.ink,
                            unfocusedTextColor = xc.ink
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = XinUiFont, fontSize = 15.sp)
                    )

                    Spacer(Modifier.height(18.dp))
                    Text("What are you trying to achieve?", fontFamily = XinUiFont, fontSize = 14.sp, color = xc.sub)
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = editAchieve,
                        onValueChange = { editAchieve = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 200.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(xc.bg)
                            .border(0.8.dp, xc.border, RoundedCornerShape(14.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = xc.green,
                            focusedTextColor = xc.ink,
                            unfocusedTextColor = xc.ink
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = XinUiFont, fontSize = 15.sp, lineHeight = 22.sp)
                    )

                    Spacer(Modifier.height(26.dp))
                    // Black full-width pill Save button
                    Button(
                        onClick = {
                            if (editWorkingOn.isNotBlank()) {
                                onRenameProject(currentProject.id, editWorkingOn.trim())
                            }
                            showEditDetails = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = xc.ink, contentColor = xc.bg)
                    ) {
                        Text("Save", fontFamily = XinUiFont, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
        }

    } else {
        // ── PROJECT INDEX VIEW ──
        val filtered = remember(projects, query) {
            val q = query.trim()
            if (q.isBlank()) projects else projects.filter { it.name.contains(q, ignoreCase = true) }
        }

        Box(Modifier.fillMaxSize().background(xc.bg)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = xc.ink)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showCreate = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建项目", tint = xc.ink)
                    }
                }

                Text(
                    text = "Projects",
                    fontFamily = XinSerifFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 36.sp,
                    color = xc.ink,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )

                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(xc.bgElevated)
                        .border(0.8.dp, xc.border, RoundedCornerShape(16.dp)),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = "搜索项目", tint = xc.sub) },
                    placeholder = { Text("Search projects", fontFamily = XinUiFont, color = xc.faint, fontSize = 15.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = xc.green,
                        focusedTextColor = xc.ink,
                        unfocusedTextColor = xc.ink
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = XinUiFont, fontSize = 15.sp)
                )

                if (filtered.isEmpty()) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = xc.faint, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (query.isBlank()) "No projects yet" else "No matching projects",
                            fontFamily = XinSerifFont,
                            fontSize = 20.sp,
                            color = xc.ink
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (query.isBlank()) "Create a project to keep related chats and work together." else "Try a different project name.",
                            fontFamily = XinUiFont,
                            fontSize = 13.sp,
                            color = xc.sub
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.id }) { project ->
                            val count = projectSessions[project.id].orEmpty().size
                            ProjectIndexRow(
                                project = project,
                                sessionCount = count,
                                onOpen = { selectedProject = project },
                                onMenu = { menuProject = project }
                            )
                        }
                    }
                }
            }

            // Bottom-right "+ New project" pill
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .height(46.dp)
                    .clip(CircleShape)
                    .background(xc.ink)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { showCreate = true }
                    )
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = xc.bg, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("New project", color = xc.bg, fontFamily = XinUiFont, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
        }

        if (showCreate) {
            AlertDialog(
                onDismissRequest = { showCreate = false; createName = "" },
                title = { Text("New project", fontFamily = XinSerifFont, color = xc.ink) },
                text = {
                    TextField(
                        value = createName,
                        onValueChange = { createName = it },
                        singleLine = true,
                        placeholder = { Text("Project name", fontFamily = XinUiFont, color = xc.faint) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = xc.bgElevated,
                            unfocusedContainerColor = xc.bgElevated,
                            focusedIndicatorColor = xc.green,
                            unfocusedIndicatorColor = xc.border,
                            focusedTextColor = xc.ink,
                            unfocusedTextColor = xc.ink,
                            cursorColor = xc.green
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = createName.isNotBlank(),
                        onClick = {
                            onCreateProject(createName.trim())
                            showCreate = false
                            createName = ""
                        }
                    ) { Text("Create", color = xc.green, fontFamily = XinUiFont) }
                },
                dismissButton = {
                    TextButton(onClick = { showCreate = false; createName = "" }) {
                        Text("Cancel", color = xc.sub, fontFamily = XinUiFont)
                    }
                },
                containerColor = xc.bgElevated
            )
        }

        menuProject?.let { project ->
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menuProject = null },
                modifier = Modifier.background(xc.bgElevated)
            ) {
                DropdownMenuItem(
                    text = { Text("Open details", fontFamily = XinUiFont) },
                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    onClick = {
                        selectedProject = project
                        menuProject = null
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete", fontFamily = XinUiFont, color = xc.red) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = xc.red) },
                    onClick = {
                        onDeleteProject(project.id)
                        menuProject = null
                    }
                )
            }
        }
    }
}

@Composable
private fun ProjectIndexRow(
    project: ProjectEntity,
    sessionCount: Int,
    onOpen: () -> Unit,
    onMenu: () -> Unit
) {
    val xc = LocalXinColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(xc.bgElevated)
            .border(BorderStroke(0.8.dp, xc.border), RoundedCornerShape(16.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = xc.ink, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, fontFamily = XinSerifFont, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = xc.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text("$sessionCount chats", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.sub)
        }
        IconButton(onClick = onMenu, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "项目操作", tint = xc.sub)
        }
    }
}
