package com.xincode.app.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.LinuxEnvironment
import com.xincode.app.LocalXinColors
import com.xincode.app.XinHeaderAction
import com.xincode.app.XinPageHeader
import com.xincode.app.XinUiFont
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

data class GitStatus(val branch: String = "", val staged: List<String> = emptyList(), val unstaged: List<String> = emptyList(), val untracked: List<String> = emptyList())

object GitOps {
    suspend fun status(projectPath: String): GitStatus = withContext(Dispatchers.IO) {
        if (projectPath.isBlank() || !LinuxEnvironment.isReady()) return@withContext GitStatus()
        val r = LinuxEnvironment.runInEnv("cd ${q(projectPath)} && git status --porcelain=v1 -b 2>&1")
        // 非 git 仓库时 git 会输出 fatal: not a git repository，视为未检测到
        if (r.stdout.contains("fatal:") || r.stdout.contains("not a git repository")) return@withContext GitStatus()
        val lines = r.stdout.lines()
        var branch = ""
        val staged = mutableListOf<String>(); val unstaged = mutableListOf<String>(); val untracked = mutableListOf<String>()
        lines.forEach { line ->
            if (line.startsWith("## ")) {
                branch = line.removePrefix("## ").substringBefore("...").substringBefore(" ")
            } else if (line.startsWith("?? ")) {
                // git 对含空格/特殊字符路径会加引号包裹，保留原始显示但去引号
                var p = line.removePrefix("?? ").trim()
                if (p.startsWith("\"") && p.endsWith("\"") && p.length >= 2) p = p.substring(1, p.length - 1)
                untracked.add(p)
            } else if (line.length >= 3) {
                val x = line[0]; val y = line[1]; var f = line.substring(3).trim()
                // 处理重命名 "R  old -> new" 取新路径，引号包裹时去引号
                if (f.contains(" -> ")) f = f.substringAfter(" -> ").trim()
                if (f.startsWith("\"") && f.endsWith("\"") && f.length >= 2) f = f.substring(1, f.length - 1)
                if (x != ' ' && x != '?') staged.add(f)
                if (y != ' ' ) unstaged.add(f)
            }
        }
        GitStatus(branch, staged, unstaged, untracked)
    }

    suspend fun log(projectPath: String, n: Int = 20): List<String> = withContext(Dispatchers.IO) {
        if (projectPath.isBlank() || !LinuxEnvironment.isReady()) return@withContext emptyList()
        val r = LinuxEnvironment.runInEnv("cd ${q(projectPath)} && git log --oneline -n $n 2>&1 | head -n $n")
        r.stdout.lines().filter { it.isNotBlank() }
    }

    suspend fun diff(projectPath: String): String = withContext(Dispatchers.IO) {
        if (projectPath.isBlank() || !LinuxEnvironment.isReady()) return@withContext ""
        LinuxEnvironment.runInEnv("cd ${q(projectPath)} && git diff --stat 2>&1 | head -n 80").stdout
    }

    suspend fun branchList(projectPath: String): List<String> = withContext(Dispatchers.IO) {
        if (projectPath.isBlank() || !LinuxEnvironment.isReady()) return@withContext emptyList()
        val r = LinuxEnvironment.runInEnv("cd ${q(projectPath)} && git branch --all 2>&1 | head -n 40")
        r.stdout.lines().filter { it.isNotBlank() }
    }

    suspend fun commit(projectPath: String, message: String, onLog:(String)->Unit={}): Boolean = withContext(Dispatchers.IO) {
        val r = LinuxEnvironment.runInEnvStreaming("cd ${q(projectPath)} && git add -A && git commit -m ${q(message)} 2>&1", onLog)
        r.exitCode==0
    }

    suspend fun push(projectPath: String, onLog:(String)->Unit={}): Boolean = withContext(Dispatchers.IO) {
        LinuxEnvironment.runInEnvStreaming("cd ${q(projectPath)} && git push 2>&1", onLog).exitCode==0
    }
    suspend fun pull(projectPath: String, onLog:(String)->Unit={}): Boolean = withContext(Dispatchers.IO) {
        LinuxEnvironment.runInEnvStreaming("cd ${q(projectPath)} && git pull --rebase 2>&1", onLog).exitCode==0
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

@Composable
fun GitIntegrationScreen(
    database: AppDatabase,
    workspaceRoot: String,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit = {}
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    var projectPath by remember { mutableStateOf(workspaceRoot.ifBlank { "/sdcard" }) }
    var status by remember { mutableStateOf(GitStatus()) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var diffText by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var commitMsg by remember { mutableStateOf("") }
    var showCommit by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val s = GitOps.status(projectPath)
            val l = GitOps.log(projectPath)
            val d = GitOps.diff(projectPath)
            val b = GitOps.branchList(projectPath)
            withContext(Dispatchers.Main) { status = s; logLines = l; diffText = d; branches = b }
        }
    }

    LaunchedEffect(projectPath) { reload() }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(title = "Git 集成", subtitle = "状态/提交/分支/日志 · 终端 git", onBack = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
            XinHeaderAction(label = "刷新", onClick = { reload() })
            XinHeaderAction(label = "终端", onClick = onOpenTerminal)
        }

        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(10.dp)).padding(10.dp)) {
            Column {
                Text("仓库: $projectPath", fontSize = 10.sp, fontFamily = Mono, color = xc.sub)
                Text("分支: ${status.branch.ifBlank { "未检测到" }}  已暂存:${status.staged.size}  未暂存:${status.unstaged.size}  未跟踪:${status.untracked.size}", fontSize = 10.sp, fontFamily = Mono, color = xc.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green).clickable { showCommit=true }.padding(horizontal = 10.dp, vertical = 6.dp)) { Text("提交", fontSize = 11.sp, fontFamily = Mono, color = Color.White) }
                    androidx.compose.foundation.layout.Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.bg).border(1.dp, xc.border, RoundedCornerShape(8.dp)).clickable { scope.launch(Dispatchers.IO){ busy=true; GitOps.pull(projectPath){ LinuxEnvironment.outputSink?.invoke(it)}; busy=false; reload() }}.padding(horizontal = 10.dp, vertical = 6.dp)) { Text(if(busy) "执行中" else "拉取", fontSize = 11.sp, fontFamily = Mono, color = xc.sub) }
                    androidx.compose.foundation.layout.Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.bg).border(1.dp, xc.border, RoundedCornerShape(8.dp)).clickable { scope.launch(Dispatchers.IO){ busy=true; GitOps.push(projectPath){ LinuxEnvironment.outputSink?.invoke(it)}; busy=false; reload() }}.padding(horizontal = 10.dp, vertical = 6.dp)) { Text("推送", fontSize = 11.sp, fontFamily = Mono, color = xc.sub) }
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            if (status.staged.isNotEmpty() || status.unstaged.isNotEmpty() || status.untracked.isNotEmpty()) {
                item {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Column {
                            Text("变更", fontSize = 11.sp, fontFamily = Mono, color = xc.ink)
                            if (status.staged.isNotEmpty()) { Text("已暂存:", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF7BE0A4)); status.staged.take(10).forEach { Text("  $it", fontSize = 10.sp, fontFamily = Mono, color = xc.sub) } }
                            if (status.unstaged.isNotEmpty()) { Text("未暂存:", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFFF59E0B)); status.unstaged.take(10).forEach { Text("  $it", fontSize = 10.sp, fontFamily = Mono, color = xc.sub) } }
                            if (status.untracked.isNotEmpty()) { Text("未跟踪:", fontSize = 10.sp, fontFamily = Mono, color = xc.faint); status.untracked.take(10).forEach { Text("  $it", fontSize = 10.sp, fontFamily = Mono, color = xc.faint) } }
                            if (diffText.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(diffText.take(800), fontSize = 9.sp, fontFamily = Mono, color = xc.faint, lineHeight = 11.sp)
                            }
                        }
                    }
                }
            }
            item {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1117)).padding(12.dp)) {
                    Column {
                        Text("提交日志", fontSize = 11.sp, fontFamily = Mono, color = Color(0xFF7BE0A4))
                        logLines.take(20).forEach { Text(it, fontSize = 10.sp, fontFamily = Mono, color = Color(0xFFD7DAE0)) }
                        if (logLines.isEmpty()) Text("无提交或非 git 仓库", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF6B7089))
                    }
                }
            }
            item {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Column {
                        Text("分支", fontSize = 11.sp, fontFamily = Mono, color = xc.ink)
                        branches.take(15).forEach { Text(it.trim(), fontSize = 10.sp, fontFamily = Mono, color = if(it.contains("*")) xc.green else xc.sub) }
                        if (branches.isEmpty()) Text("无分支信息", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                        Text("提示：完整分支切换/合并请在终端执行 git checkout / merge", fontSize = 9.sp, fontFamily = Mono, color = xc.faint, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }

    if (showCommit) {
        AlertDialog(onDismissRequest = { showCommit=false }, title = { Text("提交", fontFamily = Mono) }, text = {
            TextField(value = commitMsg, onValueChange = { commitMsg=it }, label = { Text("提交信息", fontSize = 11.sp, fontFamily = Mono) }, modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono))
        }, confirmButton = {
            TextButton(onClick = {
                val msg = commitMsg.trim(); if (msg.isBlank()) return@TextButton
                scope.launch(Dispatchers.IO) {
                    busy=true
                    GitOps.commit(projectPath, msg) { LinuxEnvironment.outputSink?.invoke(it) }
                    busy=false
                    withContext(Dispatchers.Main) { showCommit=false; commitMsg=""; reload() }
                }
            }) { Text("提交", fontFamily = Mono, color = xc.green) }
        }, dismissButton = { TextButton(onClick = { showCommit=false }) { Text("取消", fontFamily = Mono) } }, containerColor = xc.bg)
    }
}
