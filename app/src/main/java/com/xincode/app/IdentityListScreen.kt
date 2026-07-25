package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import com.xincode.data.IdentityEntity

private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Red: Color @Composable get() = LocalXinColors.current.red
private val Border: Color @Composable get() = LocalXinColors.current.border
private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/**
 * Identity card management list — P1 pure list, no chat container.
 */
@Composable
fun IdentityListScreen(
    identities: List<IdentityEntity>,
    activeId: Long,
    onBack: () -> Unit,
    onCreateNew: () -> Unit,
    onEdit: (Long) -> Unit,
    onSetActive: (Long) -> Unit,
    onToggleStar: (Long, Boolean) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    sessionCountForIdentity: (Long) -> Int = { 0 }
) {
    var renameTarget by remember { mutableStateOf<IdentityEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<IdentityEntity?>(null) }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("← 返回", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub,
                modifier = Modifier
                    .weight(1f)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Text("+", fontSize = 16.sp, fontFamily = JetBrainsMono, color = Green,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onCreateNew() }
                    .padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("身份卡", fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink)
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(0.5.dp).background(Border))

        LazyColumn(Modifier.weight(1f)) {
            items(identities, key = { it.id }) { identity ->
                IdentityRow(
                    identity = identity,
                    isActive = identity.id == activeId,
                    onSelect = { onEdit(identity.id) },
                    onSetActive = { onSetActive(identity.id) },
                    onToggleStar = { onToggleStar(identity.id, !identity.isStarred) },
                    onRename = { renameTarget = identity; renameText = identity.name },
                    onDelete = { deleteTarget = identity }
                )
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Border))
            }
        }
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名身份卡", fontFamily = JetBrainsMono, color = Ink) },
            text = {
                TextField(value = renameText, onValueChange = { renameText = it }, singleLine = true,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = JetBrainsMono))
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget?.let { onRename(it.id, renameText) }
                    renameTarget = null
                }) { Text("保存", fontFamily = JetBrainsMono, color = Green) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消", fontFamily = JetBrainsMono, color = Sub) } },
            containerColor = Bg
        )
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        val affected = sessionCountForIdentity(target.id)
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除身份卡", fontFamily = JetBrainsMono, color = Ink) },
            text = {
                Text(
                    if (affected > 0)
                        "「${target.name}」将被删除。已用过这张身份卡的 $affected 个对话会失去关联,但对话内容不会丢失。"
                    else
                        "「${target.name}」将被删除。",
                    fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(target.id); deleteTarget = null }) { Text("删除", fontFamily = JetBrainsMono, color = Red) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消", fontFamily = JetBrainsMono, color = Sub) } },
            containerColor = Bg
        )
    }
}

@Composable
private fun IdentityRow(
    identity: IdentityEntity,
    isActive: Boolean,
    onSelect: () -> Unit,
    onSetActive: () -> Unit,
    onToggleStar: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSelect() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (isActive) "▓" else "▢",
            fontSize = 14.sp, fontFamily = JetBrainsMono, color = if (isActive) Green else Faint,
            modifier = Modifier
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSetActive() }
                .padding(end = 10.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(identity.name, fontSize = 13.sp, fontFamily = JetBrainsMono, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                identity.systemPrompt.take(30) + if (identity.systemPrompt.length > 30) "…" else "",
                fontSize = 11.sp, fontFamily = JetBrainsMono, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (identity.isStarred) {
            Text("★", fontSize = 12.sp, color = Green, modifier = Modifier.padding(end = 8.dp))
        }
        Box {
            Text("⋯", fontSize = 14.sp, color = Sub,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showMenu = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp))
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, offset = DpOffset(0.dp, 0.dp)) {
                DropdownMenuItem(text = { Text(if (identity.isStarred) "取消收藏" else "收藏", fontSize = 12.sp) }, onClick = { showMenu = false; onToggleStar() })
                DropdownMenuItem(text = { Text("重命名", fontSize = 12.sp) }, onClick = { showMenu = false; onRename() })
                DropdownMenuItem(text = { Text("删除", fontSize = 12.sp, color = Red) }, onClick = { showMenu = false; onDelete() })
            }
        }
    }
}
