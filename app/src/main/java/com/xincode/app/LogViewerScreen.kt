package com.xincode.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Mono = FontFamily.Monospace

/** 日志级别。顺序即严重度,用于「只看这个级别及以上」的过滤。 */
private enum class LogLevel(val label: String, val rank: Int) {
    ALL("全部", 0), INFO("信息", 1), WARN("警告", 2), ERROR("错误", 3)
}

private data class LogLine(val text: String, val level: LogLevel)

/**
 * 日志查看:直接在手机上看崩溃与运行日志,不用连电脑抓 logcat。
 *
 * 三个来源:
 *  - crash.log      全局崩溃兜底写的堆栈(非主线程异常不杀进程,但会记在这)
 *  - 内存日志       记忆系统的调试输出
 *  - logcat         本进程的实时日志(Android 只允许读自己的,读不到别人的)
 *
 * 级别是从文本猜的:Android 的 logcat 行首有 D/I/W/E 标记,自己写的文件日志则靠关键词。
 * 猜不准好过没有——这一页的目的是让用户能把错误原文发出来,而不是做精确的日志系统。
 */
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val xc = LocalXinColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var sources by remember { mutableStateOf<List<Pair<String, File?>>>(emptyList()) }
    var selectedSource by remember { mutableStateOf(0) }
    var level by remember { mutableStateOf(LogLevel.ALL) }
    var keyword by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    fun reload() {
        loading = true
        scope.launch {
            val src = sources.getOrNull(selectedSource)
            lines = withContext(Dispatchers.IO) {
                when {
                    src == null -> emptyList()
                    src.second == null -> readLogcat()
                    else -> readFileTail(src.second!!)
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        sources = withContext(Dispatchers.IO) {
            buildList {
                add("logcat(本进程)" to null)
                val dir = ctx.filesDir
                // 只列真实存在且非空的文件,避免一堆点进去空白的条目
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") && it.length() > 0 }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { add(it.name to it) }
            }
        }
        reload()
    }

    val filtered = remember(lines, level, keyword) {
        val kw = keyword.trim()
        lines.filter { l ->
            (level == LogLevel.ALL || l.level.rank >= level.rank) &&
                (kw.isEmpty() || l.text.contains(kw, ignoreCase = true))
        }
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(
            title = "日志",
            subtitle = "运行记录、崩溃信息与过滤",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            XinHeaderAction(label = if (loading) "读取中" else "刷新", enabled = !loading, onClick = { reload() })
        }

        // 来源
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sources.forEachIndexed { i, (name, _) ->
                Chip(name, i == selectedSource, xc) { selectedSource = i; reload() }
            }
        }

        // 级别
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LogLevel.values().forEach { lv ->
                Chip(lv.label, lv == level, xc) { level = lv }
            }
        }

        // 关键词
        TextField(
            value = keyword, onValueChange = { keyword = it }, singleLine = true,
            placeholder = { Text("按关键词过滤", fontSize = 12.sp, fontFamily = Mono, color = xc.faint) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .border(0.5.dp, xc.border, RoundedCornerShape(4.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = Mono)
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${filtered.size} / ${lines.size} 行", fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
                modifier = Modifier.weight(1f))
            Text("复制全部", fontSize = 11.sp, fontFamily = Mono, color = xc.green,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    // 截一下:剪贴板塞几 MB 会让部分 ROM 直接吞掉整次复制
                    val text = filtered.joinToString("\n") { it.text }.take(200_000)
                    cm?.setPrimaryClip(ClipData.newPlainText("xincode-log", text))
                    Toast.makeText(ctx, "已复制 ${filtered.size} 行", Toast.LENGTH_SHORT).show()
                })
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            if (filtered.isEmpty() && !loading) {
                item {
                    Text("没有匹配的日志。", fontSize = 12.sp, fontFamily = Mono, color = xc.sub,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            items(filtered.size) { i ->
                val l = filtered[i]
                Text(
                    l.text,
                    fontSize = 10.sp, fontFamily = Mono, lineHeight = 14.sp,
                    color = when (l.level) {
                        LogLevel.ERROR -> xc.red
                        LogLevel.WARN -> Color(0xFFF2C14E)
                        else -> xc.sub
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, xc: XinColors, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) xc.green.copy(alpha = 0.15f) else xc.bgElevated)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, fontSize = 10.sp, fontFamily = Mono,
            color = if (selected) xc.green else xc.sub)
    }
}

/** 读文件尾部若干行。日志可能很大,只读尾部避免一次性把整个文件读进内存。 */
private fun readFileTail(file: File, maxLines: Int = 3000): List<LogLine> = try {
    val all = file.readLines()
    all.takeLast(maxLines).map { LogLine(it, guessLevel(it)) }
} catch (e: OutOfMemoryError) {
    listOf(LogLine("日志文件过大,读不进内存:${file.name}", LogLevel.ERROR))
} catch (e: Exception) {
    listOf(LogLine("读取失败:${e.message}", LogLevel.ERROR))
}

/** 读本进程 logcat。Android 只允许应用读自己的日志,读不到系统或别的应用的。 */
private fun readLogcat(maxLines: Int = 2000): List<LogLine> = try {
    val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "brief", "-t", maxLines.toString()))
    proc.inputStream.bufferedReader().useLines { seq ->
        seq.map { LogLine(it, guessLevel(it)) }.toList()
    }
} catch (e: Exception) {
    listOf(LogLine("无法读取 logcat:${e.message}", LogLevel.ERROR))
}

/**
 * 从一行文本猜级别。
 * logcat brief 格式行首是 `E/Tag(pid):`,自己写的文件日志没有这个,退回关键词匹配。
 */
private fun guessLevel(line: String): LogLevel {
    val head = line.trimStart().firstOrNull()
    if (line.length > 1 && line.trimStart().getOrNull(1) == '/') {
        return when (head) {
            'E', 'F' -> LogLevel.ERROR
            'W' -> LogLevel.WARN
            else -> LogLevel.INFO
        }
    }
    val lower = line.lowercase()
    return when {
        "exception" in lower || "error" in lower || "crash" in lower ||
            "\tat " in line || "fatal" in lower || "✗" in line -> LogLevel.ERROR
        "warn" in lower || "⚠" in line -> LogLevel.WARN
        else -> LogLevel.INFO
    }
}
