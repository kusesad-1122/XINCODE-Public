package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFFF9F9F6)
private val Ink = Color(0xFF1A1A17)
private val Sub = Color(0xFF86857B)
private val Faint = Color(0xFFB7B6AB)
private val Green = Color(0xFF6E8050)
private val Border = Color(0xFFE6E4DC)
private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/**
 * Hermes-⑤ 精编记忆查看/编辑页:USER.md(耐久画像)+ MEMORY.md(近况)。
 * 这两段由「后台复盘分身」自主维护并冻结进系统提示;此页让用户能亲眼看、手动改/清。
 */
@Composable
fun CuratedMemoryScreen(
    database: AppDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var userText by remember { mutableStateOf("") }
    var memoryText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        userText = withContext(Dispatchers.IO) { CuratedMemory.read(database, "user") }
        memoryText = withContext(Dispatchers.IO) { CuratedMemory.read(database, "memory") }
        loaded = true
    }

    Column(
        Modifier.fillMaxSize().background(Bg).padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text("← 返回", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub,
            modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
        Spacer(Modifier.height(16.dp))
        Text("精编记忆", fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink)
        Text("由后台复盘分身自动维护,冻结进系统提示。可手动编辑。", fontSize = 9.sp, fontFamily = JetBrainsMono, color = Faint)
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(0.5.dp).background(Border))

        if (!loaded) {
            Text("加载中…", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub)
            return@Column
        }

        EditBlock("USER.md — 耐久用户画像", "你是谁 / 偏好 / 对 agent 的期望", userText,
            "${userText.length}/${CuratedMemory.USER_CAP}") { userText = it }
        Spacer(Modifier.height(16.dp))
        EditBlock("MEMORY.md — 当前近况", "正在做的事 / 临时上下文", memoryText,
            "${memoryText.length}/${CuratedMemory.MEMORY_CAP}") { memoryText = it }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("保存", fontSize = 13.sp, fontFamily = JetBrainsMono, color = Green,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            database.settingDao().put(CuratedMemory.keyFor("user"), userText.trim())
                            database.settingDao().put(CuratedMemory.keyFor("memory"), memoryText.trim())
                        }
                        savedHint = "已保存 ✓"
                    }
                })
            Text(savedHint, fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
        }
    }
}

@Composable
private fun EditBlock(title: String, hint: String, value: String, counter: String, onChange: (String) -> Unit) {
    Text(title, fontSize = 12.sp, fontFamily = JetBrainsMono, color = Ink)
    Text(hint, fontSize = 9.sp, fontFamily = JetBrainsMono, color = Faint)
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().border(0.5.dp, Border).background(Color.White).padding(8.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontSize = 12.sp, fontFamily = JetBrainsMono, color = Ink),
            cursorBrush = SolidColor(Green),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
        )
    }
    Text(counter, fontSize = 9.sp, fontFamily = JetBrainsMono, color = Faint, modifier = Modifier.padding(top = 2.dp))
}
