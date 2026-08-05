package com.xincode.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight()
        ) {
            Canvas(Modifier.fillMaxHeight().width(48.dp)) {
                val x = size.width / 2f
                drawLine(
                    color = xc.border,
                    start = androidx.compose.ui.geometry.Offset(x, 42.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            ProviderAvatar(
                supplierId = supplierId,
                size = 42.dp,
                contentDescription = "$assistantName 供应商图标",
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 4.dp)
        ) {
            Text(
                assistantName,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontFamily = XinUiFont,
                color = xc.green
            )

            if (hasReasoning || toolCount > 0) {
                Spacer(Modifier.height(18.dp))
                Text(
                    formatThinkingLabel(derivedThinkingDurationMs(group)),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = XinUiFont,
                    color = xc.green
                )
            }

            assistant?.let { message ->
                if (hasReasoning) {
                    ReasoningFoldable(
                        msg = message,
                        isCurrentStreaming = isStreaming && message.reasoning.isNotBlank()
                    )
                }
                if (message.content.isNotBlank()) {
                    MarkdownContent(
                        message.content,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
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
                }
            }

            assistant?.takeIf { !isStreaming }?.let { message ->
                MessageActionsRow(message.content, onRegenerate)
            }
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
