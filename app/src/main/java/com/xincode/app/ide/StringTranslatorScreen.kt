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
import com.xincode.app.LocalXinColors
import com.xincode.app.XinHeaderAction
import com.xincode.app.XinPageHeader
import com.xincode.app.XinUiFont
import com.xincode.app.ide.designer.ResourceParser
import com.xincode.app.ide.designer.StringRes
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Mono = XinUiFont

private fun escapeXml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun containsExactStringName(xml: String, name: String): Boolean {
    // 精确匹配 name="xxx" 避免子串误判（app 命中 app_name）
    return xml.contains(Regex("""name\s*=\s*""" + Regex.escape(name) + """"""""))
}

@Composable
fun StringTranslatorScreen(
    database: AppDatabase,
    workspaceRoot: String,
    onBack: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    var projectRoot by remember { mutableStateOf(workspaceRoot.ifBlank { "/sdcard" }) }
    var strings by remember { mutableStateOf<List<StringRes>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var targetLang by remember { mutableStateOf("en") }
    var translating by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val res = ResourceParser.parseProjectResources(projectRoot)
            withContext(Dispatchers.Main) { strings = res.strings }
        }
    }

    LaunchedEffect(projectRoot) { reload() }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(title = "字符串翻译器", subtitle = "strings.xml 多语言 · AI 辅助翻译", onBack = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
            XinHeaderAction(label = "刷新", onClick = { reload() })
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("项目: $projectRoot", fontSize = 10.sp, fontFamily = Mono, color = xc.sub)
                Text("共 ${strings.size} 条 · 目标语言: $targetLang", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
            }
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green).clickable { showAdd=true }.padding(horizontal = 10.dp, vertical = 6.dp)) { Text("添加", fontSize = 11.sp, fontFamily = Mono, color = Color.White) }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextField(value = query, onValueChange = { query=it }, placeholder = { Text("搜索 key/value", fontSize = 11.sp, fontFamily = Mono, color = xc.faint) }, modifier = Modifier.weight(1f).border(1.dp, xc.border, RoundedCornerShape(8.dp)), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 11.sp), singleLine = true)
            listOf("en","zh-rCN","ja","ko","fr","de").forEach { lang ->
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if(targetLang==lang) xc.green.copy(0.15f) else xc.bgElevated).border(1.dp, if(targetLang==lang) xc.green else xc.border, RoundedCornerShape(8.dp)).clickable { targetLang=lang }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(lang, fontSize = 10.sp, fontFamily = Mono, color = if(targetLang==lang) xc.green else xc.sub)
                }
            }
        }

        val filtered = if (query.isBlank()) strings else strings.filter { it.name.contains(query,true) || it.value.contains(query,true) }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(filtered.take(80)) { s ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Column {
                        Text("@string/${s.name}", fontSize = 11.sp, fontFamily = Mono, color = xc.ink)
                        Text(s.value, fontSize = 12.sp, fontFamily = Mono, color = xc.sub)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.bg).border(1.dp, xc.border, RoundedCornerShape(8.dp)).clickable {
                                scope.launch(Dispatchers.IO) {
                                    translating = s.name
                                    // 尝试通过 AI 翻译：调用 Linux 环境或简单占位（实际由 Agent 执行）
                                    // 这里做模拟：写入 values-<lang>/strings.xml
                                    try {
                                        val dir = File(projectRoot, "app/src/main/res/values-$targetLang")
                                        dir.mkdirs()
                                        val f = File(dir, "strings.xml")
                                        val existing = if (f.exists()) f.readText() else "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>"
                                        val escaped = escapeXml("${s.value} [$targetLang]")
                                        val entry = "    <string name=\"${s.name}\">$escaped</string>\n"
                                        val newContent = if (containsExactStringName(existing, s.name)) existing else existing.replace("</resources>", "$entry</resources>")
                                        f.writeText(newContent)
                                    } catch (_: Exception) {}
                                    withContext(Dispatchers.Main) { translating = null }
                                }
                            }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(if(translating==s.name) "翻译中..." else "→ $targetLang", fontSize = 10.sp, fontFamily = Mono, color = xc.green)
                            }
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.bg).border(1.dp, xc.border, RoundedCornerShape(8.dp)).clickable {
                                // 复制
                                scope.launch(Dispatchers.Main) { query = s.name }
                            }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text("编辑", fontSize = 10.sp, fontFamily = Mono, color = xc.sub)
                            }
                        }
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { Text("无匹配字符串", fontSize = 11.sp, fontFamily = Mono, color = xc.faint, modifier = Modifier.padding(16.dp)) }
            }
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1117)).padding(12.dp)) {
                    Text("说明：真实翻译由 AI 代理执行（调用翻译副模型或联网翻译），本页提供资源解析、预览与一键写入 values-<lang>/strings.xml。", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF6B7089), lineHeight = 13.sp)
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(onDismissRequest = { showAdd=false }, title = { Text("添加字符串", fontFamily = Mono) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = newKey, onValueChange = { newKey=it }, label = { Text("name 如 app_name", fontSize = 11.sp, fontFamily = Mono) }, modifier = Modifier.fillMaxWidth())
                TextField(value = newValue, onValueChange = { newValue=it }, label = { Text("value", fontSize = 11.sp, fontFamily = Mono) }, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            TextButton(onClick = {
                if (newKey.isBlank() || newValue.isBlank()) return@TextButton
                scope.launch(Dispatchers.IO) {
                    try {
                        val dir = File(projectRoot, "app/src/main/res/values")
                        dir.mkdirs()
                        val f = File(dir, "strings.xml")
                        val existing = if (f.exists()) f.readText() else "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>"
                        val k = newKey.trim()
                        if (containsExactStringName(existing, k)) {
                            withContext(Dispatchers.Main) { showAdd=false }
                            return@launch
                        }
                        val entry = "    <string name=\"$k\">${escapeXml(newValue.trim())}</string>\n"
                        f.writeText(existing.replace("</resources>", "$entry</resources>"))
                    } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { showAdd=false; newKey=""; newValue=""; reload() }
                }
            }) { Text("保存", fontFamily = Mono, color = xc.green) }
        }, dismissButton = { TextButton(onClick = { showAdd=false }) { Text("取消", fontFamily = Mono) } }, containerColor = xc.bg)
    }
}
