package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import kotlinx.coroutines.launch

private val Mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

// 终端固定深色配色(不随主题;专属终端观感)
private val TBg = Color(0xFF0F1117)
private val TInk = Color(0xFFD7DAE0)
private val TGreen = Color(0xFF7BE0A4)
private val TSub = Color(0xFF6B7089)

/**
 * 可视终端页:实时显示部署/工具安装/AI(env_exec)与用户手输命令的输出。
 * 环境就绪时命令在内置 Ubuntu(chroot)执行,否则在 root shell。
 */
@Composable
fun TerminalScreen(terminal: TerminalState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 新行到达时自动滚到底部。
    LaunchedEffect(terminal.lines.size) {
        if (terminal.lines.isNotEmpty()) listState.animateScrollToItem(terminal.lines.size - 1)
    }

    Column(Modifier.fillMaxSize().background(TBg)) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = TSub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
            Text(if (LinuxEnvironment.isReady()) "终端 · Ubuntu" else "终端 · root shell",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = TInk)
            Spacer(Modifier.weight(1f))
            Text("清屏", fontSize = 12.sp, fontFamily = Mono, color = TSub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { terminal.clear() })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF20232E)))

        // 滚动输出区
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
                Text(line, fontSize = 11.sp, fontFamily = Mono, color = color, lineHeight = 15.sp)
            }
        }

        // 输入行
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("❯", fontSize = 14.sp, fontFamily = Mono, color = TGreen)
            Spacer(Modifier.width(8.dp))
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = !terminal.running,
                singleLine = true,
                placeholder = { Text(if (terminal.running) "执行中…" else "输入命令,回车执行", fontSize = 12.sp, fontFamily = Mono, color = TSub) },
                textStyle = TextStyle(fontSize = 12.sp, fontFamily = Mono),
                keyboardActions = KeyboardActions(onDone = {
                    val c = input; input = ""
                    if (c.isNotBlank()) scope.launch { terminal.run(c) }
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF161923),
                    unfocusedContainerColor = Color(0xFF161923),
                    cursorColor = TGreen, focusedTextColor = TInk, unfocusedTextColor = TInk,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (terminal.running) TGreen.copy(alpha = 0.4f) else TGreen)
                    .clickable(enabled = !terminal.running, indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        val c = input; input = ""
                        if (c.isNotBlank()) scope.launch { terminal.run(c) }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) { Text("运行", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = Color(0xFF0F1117)) }
        }
    }
}
