package com.xincode.app

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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Menu
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
import com.xincode.data.SessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen Claude-style Chats manager:
 * - Editorial "Chats" heading
 * - Search bar
 * - Filter & Multi-select batch actions (Archive / Delete)
 * - Pinned indicators & clean bullet-point list
 * - Bottom-right floating "+ New chat" pill
 */
@Composable
fun ChatsScreen(
    sessions: List<SessionEntity>,
    currentSessionId: Long,
    onBack: () -> Unit,
    onSelectSession: (Long) -> Unit,
    onNewChat: () -> Unit,
    onDeleteSession: (Long) -> Unit,
    onTogglePin: (Long, Boolean) -> Unit
) {
    val xc = LocalXinColors.current
    var query by remember { mutableStateOf("") }
    var filterOnlyPinned by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }

    val dateFormat = remember { SimpleDateFormat("yyyy年M月d日", Locale.CHINA) }

    val filtered = remember(sessions, query, filterOnlyPinned) {
        sessions
            .filter { !it.isGoal }
            .filter { if (filterOnlyPinned) it.isStarred else true }
            .filter {
                if (query.isBlank()) true
                else it.title.contains(query, ignoreCase = true)
            }
            .sortedWith(compareByDescending<SessionEntity> { it.isStarred }
                .thenByDescending { it.updatedAt.takeIf { t -> t > 0 } ?: it.createdAt })
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(xc.bg)
    ) {
        // ── Top Navigation Bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = xc.ink)
            }
            Spacer(Modifier.weight(1f))

            if (selectMode) {
                // Batch delete action
                IconButton(
                    onClick = {
                        selectedIds.forEach { id -> onDeleteSession(id) }
                        selectedIds.clear()
                        selectMode = false
                    },
                    enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "批量删除",
                        tint = if (selectedIds.isNotEmpty()) xc.red else xc.faint
                    )
                }
                IconButton(
                    onClick = {
                        selectMode = false
                        selectedIds.clear()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "退出多选", tint = xc.ink)
                }
            } else {
                // Pin filter toggle button
                IconButton(
                    onClick = { filterOnlyPinned = !filterOnlyPinned },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = "置顶筛选",
                        tint = if (filterOnlyPinned) xc.green else xc.sub
                    )
                }
                // Multi-select toggle button
                IconButton(
                    onClick = { selectMode = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Outlined.FilterList, contentDescription = "多选管理", tint = xc.sub)
                }
            }
        }

        // ── Claude Editorial Heading ──
        Text(
            text = "Chats",
            fontFamily = XinSerifFont,
            fontWeight = FontWeight.Medium,
            fontSize = 36.sp,
            lineHeight = 42.sp,
            letterSpacing = (-0.5).sp,
            color = xc.ink,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
        )

        // ── Search Field ──
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
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = xc.sub)
            },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清除", tint = xc.sub)
                    }
                }
            },
            placeholder = {
                Text("Search Chats", fontFamily = XinUiFont, fontSize = 15.sp, color = xc.faint)
            },
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

        // ── Chat Items List ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                items(filtered, key = { it.id }) { session ->
                    val isChecked = selectedIds.contains(session.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (selectMode) {
                                    if (isChecked) selectedIds.remove(session.id)
                                    else selectedIds.add(session.id)
                                } else {
                                    onSelectSession(session.id)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectMode) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { check ->
                                    if (check) selectedIds.add(session.id)
                                    else selectedIds.remove(session.id)
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = xc.green,
                                    checkmarkColor = Color.White
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                        }

                        // Bullet point
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (session.id == currentSessionId) xc.green else xc.faint)
                        )
                        Spacer(Modifier.width(14.dp))

                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = session.title.ifBlank { "新对话" },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = XinUiFont,
                                    color = xc.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (session.isStarred) {
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
                                text = dateFormat.format(Date(session.updatedAt.takeIf { it > 0 } ?: session.createdAt)),
                                fontSize = 12.sp,
                                fontFamily = XinUiFont,
                                color = xc.faint,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
            }

            // Floating "+ New chat" pill button at bottom-right
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
                        onClick = onNewChat
                    )
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = xc.bg, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "New chat",
                    color = xc.bg,
                    fontFamily = XinUiFont,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
        }
    }
}
