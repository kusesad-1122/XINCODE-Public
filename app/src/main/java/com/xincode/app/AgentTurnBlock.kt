package com.xincode.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AgentTurnBlock(
    group: TurnGroup,
    supplierId: String,
    assistantName: String,
    isStreaming: Boolean = false,
    onRegenerate: (() -> Unit)? = null
) {
    val xc = LocalXinColors.current
    val assistant = group.assistantMessage
    val toolCount = group.toolMessages.size
    val hasReasoning = assistant?.reasoning?.isNotBlank() == true
    var toolsExpanded by remember(group.key) { mutableStateOf(toolCount <= 1) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "✦",
                    fontSize = 13.sp,
                    color = xc.green,
                    modifier = Modifier.padding(end = 5.dp)
                )
                Text(
                    assistantName,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontFamily = XinSerifFont,
                    fontWeight = FontWeight.SemiBold,
                    color = xc.ink
                )
            }

            if (hasReasoning || toolCount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(xc.bgElevated)
                        .border(0.5.dp, xc.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatThinkingLabel(derivedThinkingDurationMs(group)),
                        fontSize = 11.sp,
                        fontFamily = XinUiFont,
                        color = xc.sub
                    )
                }
            }

            assistant?.let { message ->
                if (hasReasoning) {
                    ReasoningFoldable(
                        msg = message,
                        isCurrentStreaming = isStreaming && message.reasoning.isNotBlank()
                    )
                }
                if (message.content.isNotBlank()) {
                    val visibleText = stripGeneratedImageMarkers(message.content)
                    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        GeneratedImagePreview(message.content)
                        if (visibleText.isNotBlank()) MarkdownContent(visibleText)
                    }
                }
            }

            if (toolCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { toolsExpanded = !toolsExpanded }
                        .padding(top = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "再显示 $toolCount 步",
                        fontSize = 14.sp,
                        fontFamily = XinUiFont,
                        color = xc.green
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (toolsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (toolsExpanded) "收起步骤" else "展开步骤",
                        tint = xc.green,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (toolsExpanded) {
                    Column {
                        group.toolMessages.forEach { tool ->
                            tool.contentBlock?.let { block ->
                                when (block) {
                                    is MessageContent.ToolCall -> ToolCallRow(block)
                                    is MessageContent.FileRead -> CodeBlock(block, Modifier.padding(vertical = 4.dp))
                                    is MessageContent.FileEdit -> DiffBlock(block, Modifier.padding(vertical = 4.dp))
                                    else -> Unit
                                }
                            }
                        }
                    }
                } else {
                    // 生图步骤折叠时仍显示成品，避免用户必须展开工具卡才能看到图片。
                    group.toolMessages.forEach { tool ->
                        val block = tool.contentBlock as? MessageContent.ToolCall
                        if (block?.toolName == "generate_image" && block.stdout.isNotBlank()) {
                            GeneratedImagePreview(block.stdout)
                        }
                    }
                }
            }

            assistant?.takeIf { !isStreaming }?.let { message ->
                MessageActionsRow(message.content, onRegenerate)
            }
    }
}

/** Reasoning detail stays available without letting hidden chain-of-thought dominate the conversation. */
@Composable
fun ReasoningFoldable(msg: ChatState.MessageUi, isCurrentStreaming: Boolean = false) {
    if (msg.reasoning.isBlank()) return
    val xc = LocalXinColors.current
    var expanded by remember(msg.id) { mutableStateOf(isCurrentStreaming) }

    Text(
        text = if (expanded) "收起思考摘要" else "查看思考摘要",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFamily = XinUiFont,
        color = xc.sub,
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { expanded = !expanded }
            .padding(top = 6.dp, bottom = 2.dp)
    )
    if (expanded) {
        Text(
            msg.reasoning,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = XinUiFont,
            color = xc.sub,
            modifier = Modifier.padding(start = 10.dp, bottom = 4.dp)
        )
    }
}
