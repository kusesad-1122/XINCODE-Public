package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import com.xincode.data.AppDatabase
import com.xincode.data.GlobalSettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

/**
 * 「上下文压缩」设置页:自定义上下文窗口长度(带 256k / 1M 快捷键)、自动压缩阈值(接近填满即总结,
 * 类似 Claude 的 auto-compact),以及自定义总结规则(指导智能体怎么压、总结哪些内容)。
 */
@Composable
fun ContextCompressionScreen(database: AppDatabase, onBack: () -> Unit) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var windowText by remember { mutableStateOf("") }      // 空/0 = 跟随供应商
    var thresholdText by remember { mutableStateOf("") }   // 空/0 = 用默认 85
    var summaryRule by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val s = withContext(Dispatchers.IO) { database.globalSettingsDao().get() }
        windowText = (s?.contextWindowOverride ?: 0).takeIf { it > 0 }?.toString() ?: ""
        thresholdText = (s?.autoCompactThresholdOverride ?: 0).takeIf { it > 0 }?.toString() ?: ""
        summaryRule = s?.customSummaryRule ?: ""
        loaded = true
    }

    fun save() {
        scope.launch {
            val cur = withContext(Dispatchers.IO) { database.globalSettingsDao().get() } ?: GlobalSettingsEntity()
            val win = windowText.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            val th = thresholdText.trim().toIntOrNull()?.coerceIn(0, 100) ?: 0
            withContext(Dispatchers.IO) {
                database.globalSettingsDao().upsert(
                    cur.copy(
                        contextWindowOverride = win,
                        autoCompactThresholdOverride = th,
                        customSummaryRule = summaryRule.trim()
                    )
                )
            }
            saved = true
        }
    }

    Column(
        Modifier.fillMaxSize().background(xc.bg).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
            modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
        Spacer(Modifier.height(16.dp))
        Text("上下文压缩", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = xc.ink, fontFamily = Mono)
        Text("上下文接近填满时自动把历史总结成摘要(类似 Claude 的 /compact),既省 token 又不丢关键信息。",
            fontSize = 11.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))

        if (!loaded) {
            Text("加载中…", fontSize = 12.sp, fontFamily = Mono, color = xc.faint)
            return@Column
        }

        // ── 上下文长度 ──
        SectionLabel("上下文长度", xc)
        Text("模型的上下文窗口(tokens)。留空/0 = 跟随当前供应商配置。用于圆环占比与自动压缩判定。",
            fontSize = 10.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(bottom = 8.dp))
        FieldBox {
            TextField(
                value = windowText,
                onValueChange = { windowText = it.filter { c -> c.isDigit() }; saved = false },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("如 200000(留空=跟随供应商)", fontSize = 12.sp, fontFamily = Mono, color = xc.faint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = transparentFieldColors(xc),
                textStyle = TextStyle(fontSize = 13.sp, fontFamily = Mono)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickChip("256K", xc) { windowText = "256000"; saved = false }
            QuickChip("1M", xc) { windowText = "1000000"; saved = false }
            QuickChip("跟随供应商", xc) { windowText = ""; saved = false }
        }

        Spacer(Modifier.height(20.dp))
        // ── 自动压缩阈值 ──
        SectionLabel("自动压缩阈值(%)", xc)
        Text("上下文占用达到该百分比时自动开始总结压缩。留空/0 = 用默认 85%。想「接近填满(如 99%)才压」就填 99。",
            fontSize = 10.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(bottom = 8.dp))
        FieldBox {
            TextField(
                value = thresholdText,
                onValueChange = { thresholdText = it.filter { c -> c.isDigit() }.take(3); saved = false },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("如 99(留空=默认 85)", fontSize = 12.sp, fontFamily = Mono, color = xc.faint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = transparentFieldColors(xc),
                textStyle = TextStyle(fontSize = 13.sp, fontFamily = Mono)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickChip("99%", xc) { thresholdText = "99"; saved = false }
            QuickChip("85%", xc) { thresholdText = "85"; saved = false }
            QuickChip("默认", xc) { thresholdText = ""; saved = false }
        }

        Spacer(Modifier.height(20.dp))
        // ── 自定义总结规则 ──
        SectionLabel("自定义总结规则", xc)
        Text("压缩时追加给总结模型的额外要求:总结哪些内容、保留什么、忽略什么。留空 = 用默认规则。",
            fontSize = 10.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(bottom = 8.dp))
        FieldBox {
            TextField(
                value = summaryRule,
                onValueChange = { summaryRule = it; saved = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                placeholder = { Text("例:重点保留代码改动与文件路径、待办清单与用户偏好;忽略寒暄。", fontSize = 12.sp, fontFamily = Mono, color = xc.faint) },
                colors = transparentFieldColors(xc),
                textStyle = TextStyle(fontSize = 13.sp, fontFamily = Mono, lineHeight = 18.sp)
            )
        }

        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.fillMaxWidth().height(50.dp).background(if (saved) xc.green.copy(alpha = 0.5f) else xc.green, RoundedCornerShape(25.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { save() },
            contentAlignment = Alignment.Center
        ) {
            Text(if (saved) "已保存 ✓" else "保存", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        Text("提示:也可在对话输入框输入 /compact 立即手动压缩一次。",
            fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String, xc: XinColors) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink,
        modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun FieldBox(content: @Composable () -> Unit) {
    val xc = LocalXinColors.current
    Box(
        Modifier.fillMaxWidth().border(1.dp, xc.border, RoundedCornerShape(12.dp))
            .background(xc.bgElevated, RoundedCornerShape(12.dp)).padding(horizontal = 6.dp)
    ) { content() }
}

@Composable
private fun QuickChip(label: String, xc: XinColors, onClick: () -> Unit) {
    Box(
        Modifier.border(1.dp, xc.green.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .background(xc.green.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) { Text(label, fontSize = 12.sp, fontFamily = Mono, color = xc.green) }
}

@Composable
private fun transparentFieldColors(xc: XinColors) = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink
)
