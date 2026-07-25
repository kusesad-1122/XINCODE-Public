package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R

private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, androidx.compose.ui.text.font.FontWeight.Normal))

@Composable
fun AgentTurnBlock(group: TurnGroup, isStreaming: Boolean = false) {
    val toolCount = group.toolMessages.size
    val hasReasoning = group.assistantMessage?.reasoning?.isNotEmpty() == true
    val xc = LocalXinColors.current
    val Sub = xc.sub
    val Green = xc.green

    // ===== 空轮:无 reasoning 无 tool → 直接展平 =====
    if (toolCount == 0 && !hasReasoning) {
        group.assistantMessage?.let { asst ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                if (asst.content.isNotEmpty()) {
                    Text("xincode", fontSize = 10.sp, fontFamily = JetBrainsMono, color = Green)
                    MarkdownContent(asst.content)
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Sub.copy(alpha = 0.3f)))
            }
        }
        return
    }

    // ===== 原正常聚合渲染逻辑 =====
    // 用户要求:工具调用【默认收起】,想看时再点开(不再默认展开)。
    var expanded by remember(group.turnId) { mutableStateOf(false) }  // 默认收起

    // 一「步」= 先说的那段话 + 为此做的操作。按真实顺序自上而下呈现:
    //   xincode  先确认现有素材与入口。
    //     ▸ 读取 app/SettingsScreen.kt
    //     ▸ 执行 gradlew compileDebugKotlin
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {

        // 1. 这一步的说明文字(先说)
        group.assistantMessage?.let { asst ->
            ReasoningFoldable(
                msg = asst,
                isCurrentStreaming = isStreaming && asst.reasoning.isNotEmpty()
            )
            if (asst.content.isNotEmpty()) {
                Text(
                    "xincode",
                    fontSize = 10.sp,
                    fontFamily = JetBrainsMono,
                    color = Green
                )
                MarkdownContent(asst.content, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
            }
        }

        // 2. 这一步做的操作(后做)——每个工具一行,点行内箭头看详情。
        //    工具多时可整体折叠,避免一步里刷屏。
        if (toolCount > 0) {
            if (toolCount > 3) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { expanded = !expanded }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (expanded) "▾" else "▸",
                        fontSize = 11.sp,
                        color = Sub,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(
                        if (expanded) "收起 $toolCount 步操作" else "展开 $toolCount 步操作",
                        fontSize = 10.sp,
                        color = Sub,
                        fontFamily = JetBrainsMono
                    )
                }
            }
            // ≤3 个工具直接铺开(读起来就是时间线);多于 3 个才需要点开。
            if (toolCount <= 3 || expanded) {
                Column(Modifier.padding(start = 8.dp)) {
                    group.toolMessages.forEach { tool ->
                        tool.contentBlock?.let { block ->
                            when (block) {
                                is MessageContent.ToolCall -> ToolCallRow(block, modifier = Modifier)
                                is MessageContent.FileRead -> CodeBlock(block, Modifier.padding(vertical = 1.dp))
                                is MessageContent.FileEdit -> DiffBlock(block, Modifier.padding(vertical = 1.dp))
                                else -> {}
                            }
                        }
                    }
                }
            }
        }

        // 步与步之间的细线分隔
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(0.5.dp).background(Sub.copy(alpha = 0.3f)))
    }
}

/** Reasoning folding section extracted from MessageBubble, shared with AgentTurnBlock. */
@Composable
fun ReasoningFoldable(msg: ChatState.MessageUi, isCurrentStreaming: Boolean = false) {
    var expanded by remember(msg.id) { mutableStateOf(isCurrentStreaming) }
    val Faint = LocalXinColors.current.faint

    if (msg.reasoning.isEmpty()) return

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { expanded = !expanded }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (expanded) "▾ 思考过程" else "▸ 思考过程",
            fontSize = 11.sp,
            fontFamily = JetBrainsMono,
            color = Faint,
            modifier = Modifier.padding(end = 4.dp)
        )
    }
    if (expanded) {
        Text(
            msg.reasoning,
            fontSize = 11.sp,
            fontFamily = JetBrainsMono,
            color = Faint,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 4.dp)
        )
    }
}