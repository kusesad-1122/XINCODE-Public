package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.tools.CodeGraphNative
import com.xincode.tools.WorkspaceContext
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = FontFamily.Monospace

/**
 * 代码索引页:建索引、看状态、重建。
 *
 * 建索引是个耗时操作(一个中等工程几十秒),所以要能看进度、能中途停。
 * 不做成「打开应用自动建」—— 用户可能根本不在代码目录里工作,凭空跑几十秒
 * 满负载扫描既费电又莫名其妙。
 */
@Composable
fun CodeIndexScreen(database: AppDatabase, onBack: () -> Unit) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf(0) }
    var symbols by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<CodeIndexer.Progress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val root = WorkspaceContext.workspaceRoot

    suspend fun refreshStats() = withContext(Dispatchers.IO) {
        val dao = database.codeIndexDao()
        files = runCatching { dao.fileCount(root) }.getOrDefault(0)
        symbols = runCatching { dao.symbolCount(root) }.getOrDefault(0)
    }
    LaunchedEffect(root) { refreshStats() }

    fun build(force: Boolean) {
        if (running) return
        running = true
        progress = null
        job = scope.launch {
            try {
                CodeIndexer.index(database, root, force) { p -> progress = p }
            } finally {
                running = false
                job = null
                refreshStats()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(
            title = "代码索引",
            subtitle = "本地符号、定义和调用关系",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {

            Text(
                "把工作区的代码结构抽出来存进本地索引。之后 AI 想知道「某个函数在哪定义」" +
                    "「改它会影响谁」时,直接查索引而不用把文件读进对话 —— 手机上下文本来就紧张," +
                    "这个差别很明显。",
                fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 15.sp
            )

            Spacer(Modifier.height(14.dp))

            if (!CodeGraphNative.available) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(xc.bgElevated).padding(12.dp)) {
                    Text(
                        "✗ 索引内核在这台设备上加载失败。\n" +
                            "设备 ABI：${Build.SUPPORTED_ABIS.joinToString()}\n" +
                            "加载原因：${CodeGraphNative.failureReason.take(180)}\n" +
                            "AI 会自动退回 grep；这不是 Root 权限问题。",
                        fontSize = 11.sp, fontFamily = Mono, color = xc.red, lineHeight = 16.sp
                    )
                }
                return@Column
            }

            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(xc.bgElevated).padding(12.dp)) {
                Column {
                    Text("工作区", fontSize = 10.sp, fontFamily = Mono, color = xc.sub)
                    Text(root, fontSize = 11.sp, fontFamily = Mono, color = xc.green, lineHeight = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (files == 0) "还没有索引" else "已索引 $files 个文件 · $symbols 个符号",
                        fontSize = 12.sp, fontFamily = Mono, color = xc.ink
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            progress?.let { p ->
                Text(
                    "扫描 ${p.scanned} · 已索引 ${p.indexed} · 跳过 ${p.skipped} · 符号 ${p.symbols}",
                    fontSize = 11.sp, fontFamily = Mono, color = xc.sub
                )
                Spacer(Modifier.height(8.dp))
            }

            if (running) {
                Text("■ 停止", fontSize = 13.sp, fontFamily = Mono, color = xc.red,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        job?.cancel()
                    }.padding(vertical = 8.dp))
            } else {
                Text(
                    if (files == 0) "▸ 建立索引" else "▸ 更新索引(只处理改过的文件)",
                    fontSize = 13.sp, fontFamily = Mono, color = xc.green,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        build(force = false)
                    }.padding(vertical = 8.dp)
                )
                if (files > 0) {
                    Text("↻ 全部重建", fontSize = 12.sp, fontFamily = Mono, color = xc.sub,
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            build(force = true)
                        }.padding(vertical = 8.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "支持的语言:" + CodeGraphNative.languages().joinToString("、"),
                fontSize = 9.sp, fontFamily = Mono, color = xc.faint, lineHeight = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "索引跳过 .git / node_modules / build 等目录和超过 1MB 的文件 —— " +
                    "那些多半是生成物,解析代价高但对理解项目没帮助。",
                fontSize = 9.sp, fontFamily = Mono, color = xc.faint, lineHeight = 13.sp
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
