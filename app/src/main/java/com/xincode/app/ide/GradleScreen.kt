package com.xincode.app.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.*
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

@Composable
fun GradleScreen(
    database: AppDatabase,
    workspaceRoot: String,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit = {}
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    var projectPath by remember { mutableStateOf(workspaceRoot) }
    var info by remember { mutableStateOf<GradleProjectInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var jdk by remember { mutableStateOf("17") }
    var customTask by remember { mutableStateOf("") }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }

    fun reload() {
        if (projectPath.isBlank()) return
        scope.launch(Dispatchers.IO) {
            loading = true
            val i = GradleManager.detectProject(projectPath)
            withContext(Dispatchers.Main) { info = i; loading=false }
            GradleManager.setLastProject(database, projectPath)
        }
    }

    LaunchedEffect(Unit) {
        val last = GradleManager.getLastProject(database)
        if (last.isNotBlank()) projectPath = last else if (workspaceRoot.isNotBlank()) projectPath = workspaceRoot
        reload()
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(title = "Gradle 支持", subtitle = "Wrapper/任务/依赖·与JDK 11/17联动", onBack = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
            XinHeaderAction(label = "终端", onClick = onOpenTerminal)
            XinHeaderAction(label = "刷新", onClick = { reload() })
        }

        // 项目路径卡
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Column {
                Text("项目目录", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(projectPath.ifBlank { "未选择" }, fontSize = 11.sp, fontFamily = Mono, color = xc.ink, modifier = Modifier.weight(1f).padding(end = 8.dp))
                    Text("更改", fontSize = 11.sp, fontFamily = Mono, color = xc.green, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green.copy(0.15f)).clickable { pathInput=projectPath; showPathDialog=true }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("JDK:", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                    listOf("11","17").forEach { v ->
                        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if(jdk==v) xc.green.copy(0.15f) else xc.bg).border(1.dp, if(jdk==v) xc.green else xc.border, RoundedCornerShape(16.dp)).clickable { jdk=v }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Text("JDK $v", fontSize = 10.sp, fontFamily = Mono, color = if(jdk==v) xc.green else xc.sub)
                        }
                    }
                    Text("环境变量已自动注入", fontSize = 9.sp, fontFamily = Mono, color = xc.faint)
                }
                if (info != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Wrapper: ${if(info!!.hasWrapper) "有 (${info!!.wrapperVersion.ifBlank { "未知版本" }})" else "无"} · Gradle: ${info!!.gradleVersion.ifBlank{"未安装"}} · ${if(info!!.hasKotlinDsl) "Kotlin DSL" else "Groovy"}",
                        fontSize = 10.sp, fontFamily = Mono, color = xc.sub
                    )
                }
            }
        }

        // 自定义任务输入
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(value = customTask, onValueChange = { customTask=it }, placeholder = { Text("自定义任务 如 clean assembleDebug --info", fontSize = 11.sp, fontFamily = Mono, color = xc.faint) }, modifier = Modifier.weight(1f).border(1.dp, xc.border, RoundedCornerShape(8.dp)), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 11.sp, color = xc.ink), singleLine = true)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green).clickable {
                val t = customTask.trim(); if (t.isBlank()) return@clickable
                scope.launch(Dispatchers.IO) { GradleManager.runTask(projectPath, t, jdkVersion = jdk) { LinuxEnvironment.outputSink?.invoke(it) } }
            }.padding(horizontal = 14.dp, vertical = 10.dp)) { Text("执行", fontSize = 12.sp, fontFamily = Mono, color = Color.White) }
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(16.dp)) { Text("检测中...", fontSize = 11.sp, fontFamily = Mono, color = xc.sub) }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(GradleManager.commonTasks) { task ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        scope.launch(Dispatchers.IO) { GradleManager.runTask(projectPath, task.name, jdkVersion = jdk) { LinuxEnvironment.outputSink?.invoke(it) } }
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(task.name, fontSize = 12.sp, fontFamily = Mono, color = xc.ink)
                            Text(task.description + " [${task.group}]", fontSize = 10.sp, fontFamily = Mono, color = xc.sub)
                        }
                        Text("▶", fontSize = 14.sp, fontFamily = Mono, color = xc.green)
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1117)).padding(12.dp)) {
                        Text("提示: 首次在项目中执行会下载 Gradle Wrapper 与依赖，输出实时显示在终端页。自定义环境变量（MY_*）已自动注入。", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF7BE0A4), lineHeight = 13.sp)
                    }
                }
            }
        }
    }

    if (showPathDialog) {
        AlertDialog(onDismissRequest = { showPathDialog=false }, title = { Text("项目路径", fontFamily = Mono) }, text = {
            Column {
                Text("输入 Android 项目根目录（包含 gradlew 或 build.gradle）", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                Spacer(Modifier.height(8.dp))
                TextField(value = pathInput, onValueChange = { pathInput=it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("/sdcard/projects/MyApp", fontSize = 11.sp, fontFamily = Mono) }, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 12.sp))
            }
        }, confirmButton = { TextButton(onClick = { projectPath = pathInput.trim(); showPathDialog=false; reload() }) { Text("确定", fontFamily = Mono, color = xc.green) } }, dismissButton = { TextButton(onClick = { showPathDialog=false }) { Text("取消", fontFamily = Mono, color = xc.sub) } }, containerColor = xc.bg)
    }
}
