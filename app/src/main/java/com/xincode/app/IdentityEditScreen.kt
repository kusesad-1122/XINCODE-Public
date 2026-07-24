package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import com.xincode.data.IdentityEntity

private val Bg = Color(0xFFF9F9F6)
private val Ink = Color(0xFF1A1A17)
private val Sub = Color(0xFF86857B)
private val Faint = Color(0xFFB7B6AB)
private val Green = Color(0xFF6E8050)
private val Red = Color(0xFFA8514A)
private val Border = Color(0xFFE6E4DC)
private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/**
 * Pure form for creating/editing an identity card. Not a chat container (P1) —
 * this is what distinguishes an identity from a project.
 */
@Composable
fun IdentityEditScreen(
    identity: IdentityEntity?,
    onBack: () -> Unit,
    onSave: (name: String, systemPrompt: String, temperature: Float) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(identity?.name ?: "") }
    var prompt by remember { mutableStateOf(identity?.systemPrompt ?: "") }
    var temperature by remember { mutableStateOf(identity?.temperature ?: 1.0f) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("← 返回", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub,
                modifier = Modifier
                    .weight(1f)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Text(
                if (identity == null) "创建" else "保存",
                fontSize = 13.sp, fontFamily = JetBrainsMono,
                color = if (name.isNotBlank()) Green else Faint,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, enabled = name.isNotBlank()) {
                        onSave(name.trim(), prompt, temperature)
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(if (identity == null) "新建身份卡" else "编辑身份卡", fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink)
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(0.5.dp).background(Border))
        Spacer(Modifier.height(12.dp))

        Text("名称", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
        Spacer(Modifier.height(4.dp))
        TextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            placeholder = { Text("例如: 代码评审", fontSize = 13.sp, fontFamily = JetBrainsMono, color = Faint) },
            modifier = Modifier.fillMaxWidth().border(0.5.dp, Border, RoundedCornerShape(4.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = JetBrainsMono)
        )

        Spacer(Modifier.height(20.dp))
        Text("角色设定", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
        Spacer(Modifier.height(4.dp))
        TextField(
            value = prompt, onValueChange = { prompt = it },
            placeholder = { Text("描述这张身份卡的角色、性格、能力倾向…", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).border(0.5.dp, Border, RoundedCornerShape(4.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = JetBrainsMono)
        )

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("温度参数", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
            Text(String.format("%.1f", temperature), fontSize = 11.sp, fontFamily = JetBrainsMono, color = Ink)
        }
        Slider(
            value = temperature,
            onValueChange = { temperature = (it * 10).toInt() / 10f },
            valueRange = 0f..2f,
            steps = 19,
            colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green, inactiveTrackColor = Border)
        )

        if (onDelete != null) {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Border))
            Spacer(Modifier.height(12.dp))
            Text(
                "删除身份卡", fontSize = 13.sp, fontFamily = JetBrainsMono, color = Red,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showDeleteConfirm = true }
                    .padding(vertical = 4.dp)
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除身份卡", fontFamily = JetBrainsMono, color = Ink) },
            text = { Text("「${identity?.name}」将被删除。已用过这张身份卡的对话会失去关联,但对话内容不会丢失。", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete?.invoke() }) { Text("删除", fontFamily = JetBrainsMono, color = Red) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消", fontFamily = JetBrainsMono, color = Sub) } },
            containerColor = Bg
        )
    }
}
