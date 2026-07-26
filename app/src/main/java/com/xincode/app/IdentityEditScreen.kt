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
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Red: Color @Composable get() = LocalXinColors.current.red
private val Border: Color @Composable get() = LocalXinColors.current.border
private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/**
 * Pure form for creating/editing an identity card. Not a chat container (P1) —
 * this is what distinguishes an identity from a project.
 */
@Composable
fun IdentityEditScreen(
    identity: IdentityEntity?,
    onBack: () -> Unit,
    onSave: (IdentityEditResult) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(identity?.name ?: "") }
    var prompt by remember { mutableStateOf(identity?.systemPrompt ?: "") }
    var temperature by remember { mutableStateOf(identity?.temperature ?: 1.0f) }
    var description by remember { mutableStateOf(identity?.description ?: "") }
    var opening by remember { mutableStateOf(identity?.openingStatement ?: "") }
    var marks by remember { mutableStateOf(identity?.marks ?: "") }
    var allowedTools by remember { mutableStateOf(identity?.allowedTools ?: "") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var expanding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
                        onSave(IdentityEditResult(
                            name = name.trim(), systemPrompt = prompt, temperature = temperature,
                            description = description.trim(), openingStatement = opening.trim(),
                            marks = marks.trim(), allowedTools = allowedTools.trim()
                        ))
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
        Text("描述(只在列表里显示,不进提示词)", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
        Spacer(Modifier.height(4.dp))
        IdField(description, { description = it }, "一句话说明这张卡是干嘛的", singleLine = true)

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("角色设定", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub,
                modifier = Modifier.weight(1f))
            // 扩展提示词。身份卡写得好不好差别巨大 —— 只写「架构师」三个字,模型给的是
            // 泛泛而谈;写清楚「盯什么、不管什么、什么时候闭嘴」产出完全不同。
            // 但没人愿意每次手打三百字,所以这里给一份合格初稿让你改。
            Text(
                if (expanding) "扩展中…" else "✦ 扩展提示词",
                fontSize = 11.sp, fontFamily = JetBrainsMono,
                color = if (expanding || (name.isBlank() && prompt.isBlank())) Faint else Green,
                modifier = Modifier.clickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() }
                ) {
                    // 已经写了设定就在设定基础上扩,只写了名字就从名字扩
                    val draft = prompt.ifBlank { name }
                    if (!expanding && draft.isNotBlank()) {
                        expanding = true
                        scope.launch {
                            val a = context.applicationContext as XincodeApplication
                            val r = PromptExpander.expand(
                                a.database, a.keystore, PromptExpander.Kind.IDENTITY, draft
                            )
                            r.onSuccess { prompt = it }
                            r.onFailure {
                                Toast.makeText(context, "扩展失败:${it.message}", Toast.LENGTH_SHORT).show()
                            }
                            expanding = false
                        }
                    }
                }
            )
        }
        Text(
            "只写个名字(比如「架构师」)也能点扩展,它会补出角色边界、关注点和输出要求。",
            fontSize = 9.sp, fontFamily = JetBrainsMono, color = Faint, lineHeight = 13.sp
        )
        Spacer(Modifier.height(4.dp))
        TextField(
            value = prompt, onValueChange = { prompt = it },
            placeholder = { Text("描述角色、性格、能力倾向…或只写个名字后点上面的扩展", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint) },
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

        Spacer(Modifier.height(20.dp))
        Text("开场白", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
        Text("用这张卡新建会话时,自动作为 AI 的第一句话。留空则不加。",
            fontSize = 10.sp, fontFamily = JetBrainsMono, color = Faint, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
        IdField(opening, { opening = it }, "例如:我是代码评审助手,把要看的文件发我。", minHeight = 80.dp)

        Spacer(Modifier.height(20.dp))
        Text("允许的工具(逗号分隔,留空=不限制)", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
        Text("填了之后这张卡只能用列出的工具。比如「文档撰写」不该能执行 shell,就只留 file_read,file_write。",
            fontSize = 10.sp, fontFamily = JetBrainsMono, color = Faint, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
        IdField(allowedTools, { allowedTools = it }, "file_read,file_write,web_search", singleLine = true)

        Spacer(Modifier.height(20.dp))
        Text("备注(不会进提示词)", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
        Spacer(Modifier.height(4.dp))
        IdField(marks, { marks = it }, "给自己看的记录", minHeight = 60.dp)

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

/** 身份编辑结果。字段一多就别再堆参数了,免得调用处对不上号。 */
data class IdentityEditResult(
    val name: String,
    val systemPrompt: String,
    val temperature: Float,
    val description: String = "",
    val openingStatement: String = "",
    val marks: String = "",
    val allowedTools: String = ""
)

@Composable
private fun IdField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = 0.dp
) {
    TextField(
        value = value, onValueChange = onValueChange, singleLine = singleLine,
        placeholder = { Text(placeholder, fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint) },
        modifier = Modifier.fillMaxWidth()
            .let { if (minHeight > 0.dp) it.heightIn(min = minHeight) else it }
            .border(0.5.dp, Border, RoundedCornerShape(4.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = JetBrainsMono)
    )
}
