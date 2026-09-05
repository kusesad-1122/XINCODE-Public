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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
private val BgElevated: Color @Composable get() = LocalXinColors.current.bgElevated
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Red: Color @Composable get() = LocalXinColors.current.red
private val Border: Color @Composable get() = LocalXinColors.current.border
private val JetBrainsMono = XinUiFont

/**
 * Identity card management list — 卡片式列表:头像字 + 名称/预览 + 使用中徽章,
 * 与全 app 的 18dp 圆角卡片风格统一。
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
        XinPageHeader(title = t("身份卡"), subtitle = t("为不同场景配置独立助手"), onBack = onBack) {
            XinHeaderAction(label = t("新建"), onClick = onCreateNew)
        }
        Spacer(Modifier.height(8.dp))

        if (identities.isEmpty()) {
            Text(
                t("还没有身份卡,点右上角「新建」创建第一个"),
                fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint,
                modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp)
            )
        }

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
            }
        }
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(t("重命名身份卡"), fontFamily = JetBrainsMono, color = Ink) },
            text = {
                TextField(value = renameText, onValueChange = { renameText = it }, singleLine = true,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = JetBrainsMono))
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget?.let { onRename(it.id, renameText) }
                    renameTarget = null
                }) { Text(t("保存"), fontFamily = JetBrainsMono, color = Green) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(t("取消"), fontFamily = JetBrainsMono, color = Sub) } },
            containerColor = Bg
        )
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        val affected = sessionCountForIdentity(target.id)
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t("删除身份卡"), fontFamily = JetBrainsMono, color = Ink) },
            text = {
                Text(
                    if (affected > 0)
                        tx("「%s」将被删除。已用过这张身份卡的 %s 个对话会失去关联,但对话内容不会丢失。", target.name, affected)
                    else
                        tx("「%s」将被删除。", target.name),
                    fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(target.id); deleteTarget = null }) { Text(t("删除"), fontFamily = JetBrainsMono, color = Red) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(t("取消"), fontFamily = JetBrainsMono, color = Sub) } },
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
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isActive) Green.copy(alpha = 0.08f) else BgElevated)
            .border(1.dp, if (isActive) Green.copy(alpha = 0.4f) else Border, RoundedCornerShape(18.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSelect() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像字:点按=设为使用中
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isActive) Green else LocalXinColors.current.activeBg)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSetActive() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                identity.name.firstOrNull()?.toString() ?: "?",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (isActive) Color.White else Sub
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    identity.name, fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t("● 使用中"),
                        fontSize = 10.sp, fontFamily = JetBrainsMono, color = Green
                    )
                }
                if (identity.isStarred) {
                    Spacer(Modifier.width(6.dp))
                    Text("★", fontSize = 11.sp, color = Green)
                }
            }
            val preview = identity.systemPrompt.take(40) + if (identity.systemPrompt.length > 40) "…" else ""
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Box {
            Text(
                "⋯", fontSize = 15.sp, color = Sub,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showMenu = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, offset = DpOffset(0.dp, 0.dp)) {
                DropdownMenuItem(text = { Text(if (identity.isStarred) t("取消收藏") else t("收藏"), fontSize = 12.sp) }, onClick = { showMenu = false; onToggleStar() })
                DropdownMenuItem(text = { Text(t("重命名"), fontSize = 12.sp) }, onClick = { showMenu = false; onRename() })
                DropdownMenuItem(text = { Text(t("删除"), fontSize = 12.sp, color = Red) }, onClick = { showMenu = false; onDelete() })
            }
        }
    }
}
