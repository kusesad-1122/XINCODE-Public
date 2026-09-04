package com.xincode.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import kotlinx.coroutines.launch
import org.json.JSONArray

private val Mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

// 终端固定深色配色(专属终端观感)
private val TBg = Color(0xFF0F1117)
private val TInk = Color(0xFFD7DAE0)
private val TGreen = Color(0xFF7BE0A4)
private val TSub = Color(0xFF6B7089)
private val TCard = Color(0xFF161923)

private const val PREFS_TERMINAL = "xincode_terminal_prefs"
private const val KEY_SAVED_COMMANDS = "saved_commands"

/**
 * 终端页:
 * - 顶部快捷指令标签栏(内置高频命令 + 用户自定义保存命令，点击一键填入或运行)
 * - 放大、整合且触控友好的执行/终止大按钮
 * - 多行可展开输入区与清屏操作
 */
@Composable
fun TerminalScreen(terminal: TerminalState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var inputExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val defaultShortcuts = listOf("ls -la", "pwd", "git status", "top -n 1", "df -h", "uname -a")
    val savedCommands = remember { mutableStateListOf<String>() }
    var deleteCandidate by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val sp = context.getSharedPreferences(PREFS_TERMINAL, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_SAVED_COMMANDS, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            savedCommands.clear()
            for (i in 0 until arr.length()) savedCommands.add(arr.getString(i))
        } catch (_: Exception) {}
    }

    fun persistSavedCommands() {
        val sp = context.getSharedPreferences(PREFS_TERMINAL, Context.MODE_PRIVATE)
        val arr = JSONArray(savedCommands.toList())
        sp.edit().putString(KEY_SAVED_COMMANDS, arr.toString()).apply()
    }

    fun saveCurrentToShortcuts() {
        val c = input.trim()
        if (c.isBlank()) {
            Toast.makeText(context, "请先在下方输入要收藏的命令", Toast.LENGTH_SHORT).show()
            return
        }
        if (!savedCommands.contains(c) && !defaultShortcuts.contains(c)) {
            savedCommands.add(0, c)
            persistSavedCommands()
            Toast.makeText(context, "已收藏到快捷指令栏", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "该指令已在快捷列表中", Toast.LENGTH_SHORT).show()
        }
    }

    fun submitCommand(raw: String) {
        val command = raw.trim()
        if (command.isNotBlank() && !terminal.running) {
            scope.launch { terminal.run(command) }
        }
    }

    // 新行到达时自动滚到底部
    LaunchedEffect(terminal.lines.size) {
        if (terminal.lines.isNotEmpty()) listState.animateScrollToItem(terminal.lines.size - 1)
    }

    Column(Modifier.fillMaxSize().background(TBg)) {
        // ── 顶栏 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = TSub)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (LinuxEnvironment.isReady()) "终端 · Ubuntu" else "终端 · root shell",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Mono,
                color = TInk
            )
            Spacer(Modifier.weight(1f))
            Text(
                "清屏",
                fontSize = 13.sp,
                fontFamily = Mono,
                color = TSub,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { terminal.clear() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }

        // ── 快捷指令横滑标签栏 ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF13161F))
                .padding(vertical = 6.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 收藏当前输入按键
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF222736))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        saveCurrentToShortcuts()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.BookmarkAdd, contentDescription = "收藏当前指令", tint = TGreen, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("收藏当前", fontSize = 11.sp, fontFamily = Mono, color = TGreen)
            }

            // 用户自定义保存的快捷指令
            savedCommands.forEach { cmd ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF252B3C))
                        .border(0.8.dp, Color(0xFF384059), RoundedCornerShape(12.dp))
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            input = cmd
                            submitCommand(cmd)
                        }
                        .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(cmd, fontSize = 11.sp, fontFamily = Mono, color = TInk)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "删除收藏",
                        tint = TSub,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                deleteCandidate = cmd
                            }
                    )
                }
            }

            // 内置常用指令
            defaultShortcuts.forEach { cmd ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(TCard)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            input = cmd
                            submitCommand(cmd)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(cmd, fontSize = 11.sp, fontFamily = Mono, color = TSub)
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF20232E)))

        // ── 滚动输出区 ──
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            items(terminal.lines) { line ->
                val color = when {
                    line.startsWith("$ ") -> TGreen
                    line.startsWith("[exit 0]") -> TSub
                    line.startsWith("[exit ") || line.startsWith("[错误]") || line.startsWith("[异常]") -> Color(0xFFE0685C)
                    else -> TInk
                }
                Text(line, fontSize = 12.sp, fontFamily = Mono, color = color, lineHeight = 16.sp)
            }
        }

        // ── 整合放大的底部输入与控制区 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF13161F))
                .border(1.dp, Color(0xFF20232E))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text("❯", fontSize = 16.sp, fontFamily = Mono, color = TGreen, modifier = Modifier.padding(bottom = 12.dp))
                    Spacer(Modifier.width(8.dp))

                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = if (inputExpanded) 120.dp else 52.dp, max = 180.dp),
                        enabled = !terminal.running,
                        singleLine = false,
                        maxLines = if (inputExpanded) 8 else 2,
                        placeholder = { Text(if (terminal.running) "命令执行中…" else "输入命令，点击右侧运行", fontSize = 13.sp, fontFamily = Mono, color = TSub) },
                        textStyle = TextStyle(fontSize = 13.sp, fontFamily = Mono, lineHeight = 18.sp),
                        keyboardActions = KeyboardActions(onDone = { val c = input; input = ""; submitCommand(c) }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = TCard,
                            unfocusedContainerColor = TCard,
                            cursorColor = TGreen,
                            focusedTextColor = TInk,
                            unfocusedTextColor = TInk,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.width(6.dp))

                    // 展开/收起按钮
                    IconButton(
                        onClick = { inputExpanded = !inputExpanded },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            if (inputExpanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                            contentDescription = "切换多行",
                            tint = TSub,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    // 大号实心执行/终止按钮
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .widthIn(min = 72.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (terminal.running) Color(0xFFD34F44) else TGreen)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                if (terminal.running) {
                                    scope.launch { terminal.stop() }
                                } else {
                                    val c = input
                                    input = ""
                                    submitCommand(c)
                                }
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (terminal.running) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                tint = if (terminal.running) Color.White else Color(0xFF0F1117),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (terminal.running) "终止" else "运行",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = Mono,
                                color = if (terminal.running) Color.White else Color(0xFF0F1117)
                            )
                        }
                    }
                }
            }
        }
    }

    if (deleteCandidate != null) {
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除快捷指令", fontFamily = Mono, color = TInk) },
            text = { Text("确认从快捷栏移除指令「${deleteCandidate}」？", fontFamily = Mono, fontSize = 13.sp, color = TSub) },
            confirmButton = {
                TextButton(onClick = {
                    savedCommands.remove(deleteCandidate)
                    persistSavedCommands()
                    deleteCandidate = null
                }) { Text("删除", color = Color(0xFFE0685C), fontFamily = Mono) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("取消", color = TSub, fontFamily = Mono) }
            },
            containerColor = TCard
        )
    }
}
