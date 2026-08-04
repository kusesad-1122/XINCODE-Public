package com.xincode.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

/**
 * 多 Profile:一套独立的配置环境。
 *
 * 用途是「工作用一套、自己折腾用一套」——各自的功能模型分配、委托模型、搜索配置互不影响,
 * 切换即生效,不用来回改。
 */
@Composable
fun ProfilesScreen(database: AppDatabase, onBack: () -> Unit) {
    val xc = LocalXinColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var profiles by remember { mutableStateOf<List<Profiles.Profile>>(emptyList()) }
    var currentId by remember { mutableStateOf(Profiles.DEFAULT_ID) }
    var showNew by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Profiles.Profile?>(null) }
    var trashed by remember { mutableStateOf<List<Profiles.Trashed>>(emptyList()) }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                profiles = Profiles.list(database)
                currentId = Profiles.currentId(database)
                trashed = Profiles.listTrashed(database)
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
            Text("配置环境", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
            Spacer(Modifier.weight(1f))
            Text("+ 新建", fontSize = 12.sp, fontFamily = Mono, color = xc.green,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    newName = ""; showNew = true
                })
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                "每套环境有各自的功能模型分配、委托模型、搜索与协作配置,切换即生效。",
                fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 15.sp
            )
            Text(
                "供应商配置本身、对话历史、身份卡、技能与记忆是所有环境共用的;界面主题这类偏好也共用。",
                fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 12.dp)
            )

            profiles.forEach { p ->
                val active = p.id == currentId
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) xc.activeBg else xc.bgElevated)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            fontFamily = Mono, color = xc.ink, modifier = Modifier.weight(1f))
                        if (active) {
                            Box(Modifier.clip(RoundedCornerShape(10.dp))
                                .background(xc.green.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)) {
                                Text("当前", fontSize = 10.sp, fontFamily = Mono, color = xc.green)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (!active) {
                            Act("切换到这套", xc.green) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { Profiles.setCurrent(database, p.id) }
                                    reload()
                                    Toast.makeText(ctx, "已切换到「${p.name}」", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        Act("克隆", xc.sub) {
                            scope.launch {
                                withContext(Dispatchers.IO) { Profiles.clone(database, p.id, "${p.name} 副本") }
                                reload()
                            }
                        }
                        Act("导出", xc.sub) {
                            scope.launch {
                                val json = withContext(Dispatchers.IO) { Profiles.export(database, p.id) }
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("xincode-profile", json))
                                Toast.makeText(ctx, "配置已复制到剪贴板(不含 API Key)", Toast.LENGTH_LONG).show()
                            }
                        }
                        if (p.id != Profiles.DEFAULT_ID) {
                            Act("改名", xc.sub) { newName = p.name; renaming = p }
                            Act("删除", xc.red) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { Profiles.delete(database, p.id) }
                                    reload()
                                    Toast.makeText(ctx, "已删除,可在下方「最近删除」恢复", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            }

            // 回收站:删除是先备份再删的,这里给回恢复入口 —— 备份了不给入口等于没备份。
            if (trashed.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("最近删除", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                Spacer(Modifier.height(4.dp))
                trashed.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp)).background(xc.bgElevated).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t.name, fontSize = 12.sp, fontFamily = Mono, color = xc.faint,
                            modifier = Modifier.weight(1f))
                        Act("恢复", xc.green) {
                            scope.launch {
                                withContext(Dispatchers.IO) { Profiles.restore(database, t.id) }
                                reload()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("彻底清空回收站", fontSize = 10.sp, fontFamily = Mono, color = xc.red,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        scope.launch {
                            withContext(Dispatchers.IO) { Profiles.emptyTrash(database) }
                            reload()
                        }
                    })
            }

            Spacer(Modifier.height(8.dp))
            Text("从剪贴板导入配置", fontSize = 12.sp, fontFamily = Mono, color = xc.green,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    importText = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    showImport = true
                })
            Text(
                "导出的配置【不含 API Key】—— 那份 JSON 可能会被分享出去,里面绝不能带凭证。" +
                    "导入后自己补填 Key 即可。",
                fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 14.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(48.dp))
        }
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("新建配置环境", fontFamily = Mono, color = xc.ink, fontSize = 14.sp) },
            text = { PField(newName, { newName = it }, "例如:工作", xc) },
            confirmButton = {
                TextButton(onClick = {
                    val n = newName.trim()
                    if (n.isNotBlank()) scope.launch {
                        withContext(Dispatchers.IO) { Profiles.create(database, n) }
                        reload()
                    }
                    showNew = false
                }) { Text("创建", fontFamily = Mono, color = xc.green) }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("取消", fontFamily = Mono, color = xc.sub) } },
            containerColor = xc.bg
        )
    }

    renaming?.let { p ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("改名", fontFamily = Mono, color = xc.ink, fontSize = 14.sp) },
            text = { PField(newName, { newName = it }, p.name, xc) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { Profiles.rename(database, p.id, newName) }
                        reload()
                    }
                    renaming = null
                }) { Text("保存", fontFamily = Mono, color = xc.green) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消", fontFamily = Mono, color = xc.sub) } },
            containerColor = xc.bg
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入配置", fontFamily = Mono, color = xc.ink, fontSize = 14.sp) },
            text = {
                Column {
                    Text("将创建一个新的配置环境,不会覆盖现有的。",
                        fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                    Spacer(Modifier.height(8.dp))
                    Text(importText.take(300).ifBlank { "(剪贴板为空)" },
                        fontSize = 9.sp, fontFamily = Mono, color = xc.faint, lineHeight = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Profiles.import(database, importText) }
                        reload()
                        Toast.makeText(ctx,
                            if (ok != null) "已导入" else "格式不对,不是 XINCODE 导出的配置",
                            Toast.LENGTH_SHORT).show()
                    }
                    showImport = false
                }) { Text("导入", fontFamily = Mono, color = xc.green) }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消", fontFamily = Mono, color = xc.sub) } },
            containerColor = xc.bg
        )
    }
}

@Composable
private fun Act(label: String, color: Color, onClick: () -> Unit) {
    Text(label, fontSize = 11.sp, fontFamily = Mono, color = color,
        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() })
}

@Composable
private fun PField(value: String, onChange: (String) -> Unit, hint: String, xc: XinColors) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, fontSize = 12.sp, fontFamily = Mono, color = xc.faint) },
        modifier = Modifier.fillMaxWidth().border(0.5.dp, xc.border, RoundedCornerShape(4.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = Mono)
    )
}
