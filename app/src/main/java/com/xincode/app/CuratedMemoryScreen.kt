package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val BgElevated: Color @Composable get() = LocalXinColors.current.bgElevated
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Border: Color @Composable get() = LocalXinColors.current.border
private val JetBrainsMono = XinUiFont

/**
 * Hermes-⑤ 精编记忆查看/编辑页:USER.md(耐久画像)+ MEMORY.md(近况)。
 * 这两段由「后台复盘分身」自主维护并冻结进系统提示;此页让用户能亲眼看、手动改/清。
 * 卡片式排版:标题+字数一行,编辑区圆角内嵌,保存为主题绿药丸按钮。
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
        XinPageHeader(
            title = t("精编记忆"),
            subtitle = t("自动维护的长期信息，也可以手动编辑"),
            onBack = onBack
        )
        Spacer(Modifier.height(8.dp))

        if (!loaded) {
            Text(t("加载中…"), fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub)
            return@Column
        }

        EditBlock(
            title = "USER.md · " + t("耐久用户画像"),
            hint = t("你是谁 / 偏好 / 对 agent 的期望"),
            value = userText,
            counter = "${userText.length}/${CuratedMemory.USER_CAP}"
        ) { userText = it }
        Spacer(Modifier.height(14.dp))
        EditBlock(
            title = "MEMORY.md · " + t("当前近况"),
            hint = t("正在做的事 / 临时上下文"),
            value = memoryText,
            counter = "${memoryText.length}/${CuratedMemory.MEMORY_CAP}"
        ) { memoryText = it }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Green.copy(alpha = 0.14f))
                    .border(0.5.dp, Green.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                database.settingDao().put(CuratedMemory.keyFor("user"), userText.trim())
                                database.settingDao().put(CuratedMemory.keyFor("memory"), memoryText.trim())
                            }
                            savedHint = t("已保存 ✓")
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(t("保存"), fontSize = 12.sp, fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, color = Green)
            }
            if (savedHint.isNotBlank()) {
                Text(savedHint, fontSize = 11.sp, fontFamily = JetBrainsMono, color = Green)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun EditBlock(title: String, hint: String, value: String, counter: String, onChange: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BgElevated)
            .border(1.dp, Border, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontFamily = JetBrainsMono, color = Ink)
                Text(hint, fontSize = 10.sp, fontFamily = JetBrainsMono, color = Faint, modifier = Modifier.padding(top = 2.dp))
            }
            Text(counter, fontSize = 10.sp, fontFamily = JetBrainsMono, color = Faint)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(0.5.dp, Border, RoundedCornerShape(12.dp))
                .background(Bg)
                .padding(10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = JetBrainsMono, color = Ink),
                cursorBrush = SolidColor(Green),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
            )
        }
    }
}
