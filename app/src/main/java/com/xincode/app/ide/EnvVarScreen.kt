package com.xincode.app.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.LocalXinColors
import com.xincode.app.XinPageHeader
import com.xincode.app.XinHeaderAction
import com.xincode.app.XinUiFont
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

@Composable
fun EnvVarScreen(
    database: AppDatabase,
    onBack: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    var vars by remember { mutableStateOf<List<EnvVar>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var valueInput by remember { mutableStateOf("") }
    var scopeInput by remember { mutableStateOf("all") }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val loaded = EnvVarManager.load(database)
            withContext(Dispatchers.Main) { vars = loaded }
            com.xincode.app.LinuxEnvironment.customEnvVars = loaded
        }
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(vars) { com.xincode.app.LinuxEnvironment.customEnvVars = vars }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(
            title = "环境变量",
            subtitle = "自定义构建与终端变量 · 注入 Ubuntu chroot",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            XinHeaderAction(label = "添加", onClick = {
                keyInput = ""; valueInput=""; scopeInput="all"; error=null; showAdd=true; editingIdx=null
            })
        }

        if (vars.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(16.dp)) {
                Text("暂无自定义变量。\n添加后会自动注入到：\n• 终端（TerminalScreen / env_exec）\n• 构建（Gradle / SDKManager）\n示例：MY_API_URL / GRADLE_OPTS", fontSize = 11.sp, fontFamily = Mono, color = xc.sub, lineHeight = 15.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                itemsIndexed(vars, key = { idx, v -> "$idx-${v.key}" }) { idx, v ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.key, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
                            Text(v.value, fontSize = 11.sp, fontFamily = Mono, color = xc.sub, maxLines = 2)
                            Text(when(v.scope){ "terminal"->"仅终端"; "build"->"仅构建"; else->"全部生效" }, fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                        }
                        Text("编辑", fontSize = 11.sp, fontFamily = Mono, color = xc.green, modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            keyInput = v.key; valueInput = v.value; scopeInput = v.scope; error=null; editingIdx=idx; showAdd=true
                        })
                        Spacer(Modifier.width(12.dp))
                        Text("删除", fontSize = 11.sp, fontFamily = Mono, color = xc.red, modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            scope.launch(Dispatchers.IO) {
                                // 以当前快照为准，避免并发删除时 idx 越界
                                val snap = vars
                                if (idx < 0 || idx >= snap.size) return@launch
                                val newList = snap.toMutableList().apply { removeAt(idx) }
                                EnvVarManager.save(database, newList)
                                withContext(Dispatchers.Main) { vars = newList }
                            }
                        })
                    }
                }
            }
        }

        if (vars.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F1117)).padding(10.dp)) {
                Text("导出预览: ${EnvVarManager.toExportCommands(vars).take(200)}", fontSize = 10.sp, fontFamily = Mono, color = xc.green)
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(if (editingIdx==null) "添加变量" else "编辑变量", fontFamily = Mono, color = xc.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(value = keyInput, onValueChange = { keyInput = it.uppercase().filter { c -> c.isLetterOrDigit() || c=='_' } }, label = { Text("变量名 (A-Z_)", fontFamily = Mono, fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 13.sp))
                    TextField(value = valueInput, onValueChange = { valueInput = it }, label = { Text("变量值", fontFamily = Mono, fontSize = 11.sp) }, singleLine = false, modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 13.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "全部", "terminal" to "仅终端", "build" to "仅构建").forEach { (v, label) ->
                            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (scopeInput==v) xc.green.copy(0.15f) else xc.bgElevated).border(1.dp, if(scopeInput==v) xc.green else xc.border, RoundedCornerShape(16.dp)).clickable { scopeInput=v }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(label, fontSize = 10.sp, fontFamily = Mono, color = if(scopeInput==v) xc.green else xc.sub)
                            }
                        }
                    }
                    if (error != null) Text(error!!, fontSize = 11.sp, fontFamily = Mono, color = xc.red)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val k = keyInput.trim().uppercase()
                    val v = valueInput
                    val err = EnvVarManager.validateKey(k) ?: if (v.isBlank()) "值不能为空" else null
                    if (err != null) { error = err; return@TextButton }
                    val newVar = EnvVar(k, v, scopeInput)
                    scope.launch(Dispatchers.IO) {
                        val cur = vars.toMutableList()
                        // 编辑时：检查改名后是否与其它条目重键（排除自身）
                        if (editingIdx != null) {
                            val dup = cur.indexOfFirst { it.key == k && cur.indexOf(it) != editingIdx }
                            if (dup >= 0) {
                                withContext(Dispatchers.Main) { error = "已存在同名变量 $k" }
                                return@launch
                            }
                            cur[editingIdx!!] = newVar
                        } else {
                            val existingIdx = cur.indexOfFirst { it.key == k }
                            if (existingIdx >= 0) {
                                cur[existingIdx] = newVar
                            } else {
                                cur.add(newVar)
                            }
                        }
                        EnvVarManager.save(database, cur)
                        withContext(Dispatchers.Main) { vars = cur; showAdd=false }
                    }
                }) { Text("保存", fontFamily = Mono, color = xc.green) }
            },
            dismissButton = { TextButton(onClick = { showAdd=false }) { Text("取消", fontFamily = Mono, color = xc.sub) } },
            containerColor = xc.bg
        )
    }
}
