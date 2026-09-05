package com.xincode.app

import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    // 修「结论排在工具卡上面」:消息 id 即真实发生顺序。id 小于正文的工具发生在
    // 说话之前(模型不说话直接动手),渲染到正文上方;其余保持在正文下方。
    val toolsSplit = if (assistant != null) {
        group.toolMessages.partition { it.id < assistant.id }
    } else {
        group.toolMessages to emptyList()
    }
    val toolsBefore = toolsSplit.first
    val toolsAfter = toolsSplit.second

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

            // Claude 式思考行:默认只留一行「思考了 X 秒」,点开才看摘要。
            if (hasReasoning) {
                Spacer(Modifier.height(8.dp))
                ThinkingFoldable(
                    label = formatThinkingLabel(derivedThinkingDurationMs(group)),
                    reasoning = assistant?.reasoning.orEmpty()
                )
            }

            // 工具折叠开关:一步以上默认收起,点了才展开,不再撑爆时间线。
            if (toolCount > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { toolsExpanded = !toolsExpanded }
                        .padding(top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "执行了 $toolCount 步",
                        fontSize = 12.sp,
                        fontFamily = XinUiFont,
                        color = xc.green
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (toolsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (toolsExpanded) "收起步骤" else "展开步骤",
                        tint = xc.green,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // —— 发生在说明文字之前的工具,排在文字上方(保持真实时序) ——
            if (toolsExpanded) {
                ToolStepRows(toolsBefore)
            } else {
                ImagePreviewsFromTools(toolsBefore)
            }

            assistant?.let { message ->
                if (message.content.isNotBlank()) {
                    val visibleText = stripGeneratedImageMarkers(message.content)
                    Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                        GeneratedImagePreview(message.content)
                        if (visibleText.isNotBlank()) MarkdownContent(visibleText)
                    }
                }
            }

            // —— 说话之后执行的工具,保持在正文下方 ——
            if (toolsExpanded) {
                ToolStepRows(toolsAfter)
            } else {
                ImagePreviewsFromTools(toolsAfter)
            }

            assistant?.takeIf { !isStreaming }?.let { message ->
                MessageActionsRow(message.content, onRegenerate)
            }
    }
}

/** 渲染一组工具消息(命令卡/文件查看/差异块)。 */
@Composable
private fun ToolStepRows(messages: List<ChatState.MessageUi>) {
    Column {
        messages.forEach { tool ->
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

/** 折叠状态下生图步骤仍显示成品,避免用户必须展开工具卡才能看到图片。 */
@Composable
private fun ImagePreviewsFromTools(messages: List<ChatState.MessageUi>) {
    messages.forEach { tool ->
        val block = tool.contentBlock as? MessageContent.ToolCall
        if (block?.toolName == "generate_image" && block.stdout.isNotBlank()) {
            GeneratedImagePreview(block.stdout)
        }
    }
}

/** Claude 式思考折叠行:裸行 + 时钟图标 + 思考首句摘要 + 细箭头,默认收起,无底框。 */
@Composable
private fun ThinkingFoldable(label: String, reasoning: String) {
    val xc = LocalXinColors.current
    var expanded by remember(reasoning) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { expanded = !expanded }
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Schedule,
            contentDescription = null,
            tint = xc.faint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            thinkingSummary(reasoning, label),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontFamily = XinUiFont,
            color = xc.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
            contentDescription = if (expanded) "收起思考" else "展开思考",
            tint = xc.faint,
            modifier = Modifier.size(16.dp)
        )
    }
    if (expanded && reasoning.isNotBlank()) {
        Text(
            reasoning,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = XinUiFont,
            color = xc.sub,
            modifier = Modifier.padding(start = 21.dp, top = 2.dp, bottom = 4.dp)
        )
    }
}

/** 思考摘要:取思考内容的第一行压成一句;没有内容时回退到「思考了 X 秒」。 */
private fun thinkingSummary(reasoning: String, fallback: String): String {
    val flat = reasoning.replace('\n', ' ').trim()
    return when {
        flat.isEmpty() -> fallback
        flat.length <= 34 -> flat
        else -> flat.take(34) + "…"
    }
}

/** Reasoning detail stays available without letting hidden chain-of-thought dominate the conversation. */
@Composable
fun ReasoningFoldable(msg: ChatState.MessageUi, isCurrentStreaming: Boolean = false) {
    if (msg.reasoning.isBlank()) return
    val xc = LocalXinColors.current
    var expanded by remember(msg.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { expanded = !expanded }
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Schedule,
            contentDescription = null,
            tint = xc.faint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (isCurrentStreaming) "思考中…" else thinkingSummary(msg.reasoning, "思考摘要"),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontFamily = XinUiFont,
            color = xc.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
            contentDescription = if (expanded) "收起思考摘要" else "查看思考摘要",
            tint = xc.faint,
            modifier = Modifier.size(16.dp)
        )
    }
    if (expanded) {
        Text(
            msg.reasoning,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontFamily = XinUiFont,
            color = xc.sub,
            modifier = Modifier.padding(start = 21.dp, bottom = 4.dp)
        )
    }
}
