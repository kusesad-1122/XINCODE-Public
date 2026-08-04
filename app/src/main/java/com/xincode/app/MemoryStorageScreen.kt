package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import com.xincode.app.R
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Red: Color @Composable get() = LocalXinColors.current.red
private val Border: Color @Composable get() = LocalXinColors.current.border
private val JetBrainsMono = XinUiFont

@Composable
fun MemoryStorageScreen(
    database: AppDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var msgCount by remember { mutableStateOf(-1) }
    var sessionCount by remember { mutableStateOf(-1) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }

    // Load counts on first composition
    LaunchedEffect(Unit) {
        msgCount = withContext(Dispatchers.IO) { database.messageDao().count() }
        sessionCount = withContext(Dispatchers.IO) { database.sessionDao().countSessions() }
    }

    Column(
        Modifier.fillMaxSize().background(Bg).padding(16.dp)
    ) {
        XinPageHeader(
            title = "记忆与存储",
            subtitle = "本地数据库、会话和消息数据",
            onBack = onBack
        )
        Spacer(Modifier.height(12.dp))

        // Info rows
        InfoRow("本地记忆数据", "Phase 2 完整实现")
        Spacer(Modifier.height(8.dp))
        InfoRow("存储路径", "/data/data/com.xincode.app/databases/xincode.db")
        Spacer(Modifier.height(8.dp))
        InfoRow("当前消息总数", if (msgCount >= 0) "$msgCount 条" else "加载中…")
        Spacer(Modifier.height(8.dp))
        InfoRow("当前会话数量", if (sessionCount >= 0) "$sessionCount 个" else "加载中…")

        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Border))
        Spacer(Modifier.height(12.dp))

        // Clear button
        Text(
            if (clearing) "清空中…" else "清空所有数据",
            fontSize = 13.sp,
            fontFamily = JetBrainsMono,
            color = Red,
            modifier = Modifier
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    if (!clearing) showClearConfirm = true
                }
                .padding(vertical = 8.dp)
        )
        Text("此操作不可逆，所有消息和记忆将被删除", fontSize = 9.sp, fontFamily = JetBrainsMono, color = Faint)
    }

    // Clear confirmation dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空所有数据", fontFamily = JetBrainsMono, color = Ink) },
            text = { Text("确认清空所有消息、会话和记忆？\n此操作不可恢复。", fontFamily = JetBrainsMono, fontSize = 12.sp, color = Sub) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    clearing = true
                    scope.launch(Dispatchers.IO) {
                        database.messageDao().deleteAll()
                        database.sessionDao().deleteAllSessions()
                        database.memoryDao().deleteAll()
                        // Reload counts
                        msgCount = 0
                        sessionCount = 0
                        clearing = false
                    }
                }) { Text("确认清空", fontFamily = JetBrainsMono, color = Red) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消", fontFamily = JetBrainsMono, color = Sub) }
            },
            containerColor = Bg
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, fontFamily = JetBrainsMono, color = Ink)
        Text(value, fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub)
    }
}
