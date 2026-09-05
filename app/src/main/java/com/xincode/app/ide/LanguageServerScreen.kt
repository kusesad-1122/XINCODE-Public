package com.xincode.app.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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

@Composable
fun LanguageServerScreen(
    database: AppDatabase,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit = {}
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installedMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var diagnosePath by remember { mutableStateOf("/sdcard/test.java") }
    var diagnoseOut by remember { mutableStateOf<List<String>>(emptyList()) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val en = LspManager.getEnabled(database)
            val map = mutableMapOf<String, Boolean>()
            LspManager.servers.forEach { s -> map[s.id] = LspManager.isInstalled(s) }
            withContext(Dispatchers.Main) { enabled = en; installedMap = map }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(title = "语言服务器", subtitle = "Java / Kotlin / XML · 诊断与补全", onBack = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
            XinHeaderAction(label = "刷新", onClick = { reload() })
            XinHeaderAction(label = "终端", onClick = onOpenTerminal)
        }

        if (!LinuxEnvironment.isReady()) {
            Box(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(16.dp)) {
                Text("Linux 环境未就绪，语言服务器运行在 Ubuntu chroot 内。请先部署环境。", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            items(LspManager.servers) { srv ->
                val installed = installedMap[srv.id] ?: false
                val isEnabled = enabled.contains(srv.id)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(srv.language, fontSize = 13.sp, fontFamily = Mono, color = xc.ink, modifier = Modifier.weight(1f))
                            Text(if(installed) "已安装" else "未安装", fontSize = 10.sp, fontFamily = Mono, color = if(installed) xc.green else xc.faint, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if(installed) xc.green.copy(0.15f) else xc.bg).padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        Text(srv.name, fontSize = 12.sp, fontFamily = Mono, color = xc.ink)
                        Text(srv.description, fontSize = 10.sp, fontFamily = Mono, color = xc.sub)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!installed) {
                                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    if (busy==null) scope.launch {
                                        busy=srv.id
                                        withContext(Dispatchers.IO) { LspManager.install(srv) { LinuxEnvironment.outputSink?.invoke(it) } }
                                        busy=null
                                        reload()
                                    }
                                }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                    Text(if(busy==srv.id) "安装中..." else "安装", fontSize = 11.sp, fontFamily = Mono, color = Color.White)
                                }
                            } else {
                                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if(isEnabled) xc.green else xc.bg).border(1.dp, if(isEnabled) xc.green else xc.border, RoundedCornerShape(8.dp)).clickable {
                                    scope.launch(Dispatchers.IO) {
                                        LspManager.setEnabled(database, srv.id, !isEnabled)
                                        val en2 = LspManager.getEnabled(database)
                                        withContext(Dispatchers.Main) { enabled = en2 }
                                    }
                                }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                    Text(if(isEnabled) "已启用" else "启用", fontSize = 11.sp, fontFamily = Mono, color = if(isEnabled) Color.White else xc.sub)
                                }
                                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.bg).border(1.dp, xc.border, RoundedCornerShape(8.dp)).clickable {
                                    scope.launch(Dispatchers.IO) {
                                        LinuxEnvironment.runInEnvStreaming(srv.runCmd + " --help 2>&1 | head -n 30") { LinuxEnvironment.outputSink?.invoke(it) }
                                    }
                                }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                    Text("测试运行", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1117)).padding(12.dp)) {
                    Column {
                        Text("快速诊断（javac/kotlinc/xmllint）", fontSize = 11.sp, fontFamily = Mono, color = xc.green)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.TextField(value = diagnosePath, onValueChange = { diagnosePath=it }, modifier = Modifier.weight(1f).border(1.dp, Color(0xFF20232E), RoundedCornerShape(8.dp)), placeholder = { Text("/sdcard/.../Main.java", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF6B7089)) }, colors = androidx.compose.material3.TextFieldDefaults.colors(focusedContainerColor = Color(0xFF161923), unfocusedContainerColor = Color(0xFF161923), focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = Color(0xFFD7DAE0), unfocusedTextColor = Color(0xFFD7DAE0)), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 11.sp), singleLine = true)
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green).clickable {
                                scope.launch(Dispatchers.IO) {
                                    val ext = diagnosePath.substringAfterLast('.', "").lowercase()
                                    val lang = when(ext){ "java"->"java"; "kt"->"kotlin"; "xml"->"xml"; else->"java" }
                                    val out = LspManager.diagnose(diagnosePath, lang)
                                    withContext(Dispatchers.Main) { diagnoseOut = out }
                                }
                            }.padding(horizontal = 12.dp, vertical = 8.dp)) { Text("诊断", fontSize = 11.sp, fontFamily = Mono, color = Color.White) }
                        }
                        if (diagnoseOut.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            diagnoseOut.take(20).forEach { line ->
                                Text(line, fontSize = 10.sp, fontFamily = Mono, color = if(line.contains("error", true)) Color(0xFFE0685C) else Color(0xFFD7DAE0))
                            }
                        }
                        Text("说明：完整 LSP 需在编辑器中通过 stdin/stdout 连接，本页提供安装、启用与命令行诊断能力，编辑器可据此实现补全/跳转。", fontSize = 9.sp, fontFamily = Mono, color = Color(0xFF6B7089), lineHeight = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}
