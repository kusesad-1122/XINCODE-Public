package com.xincode.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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

/** Claude-style project index: large editorial title, quiet search, and one clear create action. */
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
    var renameProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var renameName by remember { mutableStateOf("") }

    val filtered = remember(projects, query) {
        val q = query.trim()
        if (q.isBlank()) projects else projects.filter { it.name.contains(q, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = xc.ink)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showCreate = true }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = "新建项目", tint = xc.ink)
            }
        }

        Text(
            text = "Projects",
            fontFamily = XinSerifFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 38.sp,
            lineHeight = 44.sp,
            color = xc.ink,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp)
        )

        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .border(1.dp, xc.border, RoundedCornerShape(18.dp)),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = "搜索项目", tint = xc.sub) },
            placeholder = { Text("Search projects", fontFamily = XinUiFont, color = xc.faint, fontSize = 16.sp) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = xc.bgElevated,
                unfocusedContainerColor = xc.bgElevated,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = xc.green,
                focusedTextColor = xc.ink,
                unfocusedTextColor = xc.ink
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = XinUiFont, fontSize = 16.sp)
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = xc.faint, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (query.isBlank()) "No projects yet" else "No matching projects",
                        fontFamily = XinSerifFont,
                        fontSize = 22.sp,
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { project ->
                        val count = projectSessions[project.id].orEmpty().size
                        ProjectIndexRow(
                            project = project,
                            sessionCount = count,
                            onOpen = {
                                projectSessions[project.id].orEmpty().firstOrNull()?.let { onSelectSession(it.id) }
                                    ?: onCreateNewInProject(project.id)
                            },
                            onMenu = { menuProject = project }
                        )
                    }
                }
            }

            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
                shape = RoundedCornerShape(28.dp),
                containerColor = xc.ink,
                contentColor = xc.bg,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("New project", fontFamily = XinUiFont, fontWeight = FontWeight.Medium) }
            )
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
                text = { Text("Rename", fontFamily = XinUiFont) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    renameProject = project
                    renameName = project.name
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

    renameProject?.let { project ->
        AlertDialog(
            onDismissRequest = { renameProject = null },
            title = { Text("Rename project", fontFamily = XinSerifFont, color = xc.ink) },
            text = {
                TextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    singleLine = true,
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
                TextButton(enabled = renameName.isNotBlank(), onClick = {
                    onRenameProject(project.id, renameName.trim())
                    renameProject = null
                }) { Text("Save", color = xc.green, fontFamily = XinUiFont) }
            },
            dismissButton = {
                TextButton(onClick = { renameProject = null }) { Text("Cancel", color = xc.sub, fontFamily = XinUiFont) }
            },
            containerColor = xc.bgElevated
        )
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
            .clip(RoundedCornerShape(18.dp))
            .background(xc.bgElevated)
            .border(BorderStroke(1.dp, xc.border))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = xc.green, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, fontFamily = XinSerifFont, fontSize = 18.sp, color = xc.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text("$sessionCount chats", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.sub)
        }
        IconButton(onClick = onMenu, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "项目操作", tint = xc.sub)
        }
    }
}
