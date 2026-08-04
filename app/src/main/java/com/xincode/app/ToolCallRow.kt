package com.xincode.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun toolIcon(toolName: String): ImageVector = when (toolName) {
    "shell_exec", "su_exec" -> Icons.Outlined.Terminal
    "file_read" -> Icons.Outlined.Description
    "file_edit" -> Icons.Outlined.Edit
    "web_search", "web_search_batch" -> Icons.Outlined.Search
    "web_fetch" -> Icons.Outlined.Public
    "agent_plan" -> Icons.Outlined.CheckCircle
    else -> Icons.Outlined.Terminal
}

private fun toolTitle(toolCall: MessageContent.ToolCall): String {
    val prefix = when (toolCall.toolName) {
        "shell_exec", "su_exec" -> "Shell"
        "file_read" -> "Read"
        "file_edit" -> "Edit"
        "web_search", "web_search_batch" -> "Search"
        "web_fetch" -> "Fetch"
        else -> ToolLabels.labelOf(toolCall.toolName, toolCall.fullParams.ifBlank { toolCall.paramsSummary })
    }
    return if (prefix == ToolLabels.labelOf(toolCall.toolName, toolCall.fullParams.ifBlank { toolCall.paramsSummary })) {
        prefix
    } else {
        "$prefix: ${toolCall.paramsSummary}"
    }
}

private fun statusLabel(status: ToolStatus, exitCode: Int?): String = when (status) {
    ToolStatus.RUNNING -> "running…"
    ToolStatus.SUCCESS -> "exit ${exitCode ?: 0}"
    ToolStatus.FAILED -> "exit ${exitCode ?: -1}"
    ToolStatus.DENIED -> "denied"
    else -> ""
}

@Composable
fun ToolCallRow(toolCall: MessageContent.ToolCall, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val xc = LocalXinColors.current
    var expanded by remember(toolCall.toolName, toolCall.paramsSummary) {
        mutableStateOf(toolCall.status == ToolStatus.SUCCESS)
    }
    val attention = toolCall.status == ToolStatus.RUNNING || toolCall.status == ToolStatus.FAILED
    val statusColor = when {
        attention -> xc.red
        toolCall.status == ToolStatus.DENIED -> xc.faint
        else -> xc.green
    }
    val title = toolTitle(toolCall)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .border(2.dp, statusColor, RoundedCornerShape(8.dp))
                    .padding(3.dp)
                    .background(statusColor, RoundedCornerShape(5.dp))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = XinCodeFont,
                fontWeight = FontWeight.Medium,
                color = xc.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "收起命令" else "展开命令",
                tint = xc.sub,
                modifier = Modifier.size(18.dp)
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, xc.border, RoundedCornerShape(18.dp))
                    .background(xc.bgElevated, RoundedCornerShape(18.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color(0xFF292A28), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = toolIcon(toolCall.toolName),
                            contentDescription = null,
                            tint = Color(0xFFB3CE6F),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        title,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = XinCodeFont,
                        fontWeight = FontWeight.SemiBold,
                        color = xc.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制命令",
                        tint = xc.sub,
                        modifier = Modifier
                            .size(32.dp)
                            .clickable {
                                copyToClipboard(context, "xincode-command", toolCall.paramsSummary)
                                Toast.makeText(context, "已复制命令", Toast.LENGTH_SHORT).show()
                            }
                            .padding(7.dp)
                    )
                }

                Text(
                    statusLabel(toolCall.status, toolCall.exitCode),
                    fontSize = 12.sp,
                    fontFamily = XinCodeFont,
                    color = statusColor,
                    modifier = Modifier.padding(start = 44.dp, top = 6.dp, bottom = 8.dp)
                )

                if (toolCall.stdout.isNotBlank() || toolCall.stderr.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, xc.border.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        if (toolCall.stdout.isNotBlank()) {
                            Text(
                                toolCall.stdout.trimEnd(),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = XinCodeFont,
                                color = xc.ink
                            )
                        }
                        if (toolCall.stderr.isNotBlank()) {
                            Text(
                                toolCall.stderr.trimEnd(),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = XinCodeFont,
                                color = xc.red,
                                modifier = Modifier.padding(top = if (toolCall.stdout.isBlank()) 0.dp else 8.dp)
                            )
                        }
                    }
                }

                if (toolCall.stdout.isNotBlank() || toolCall.stderr.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            "复制结果",
                            fontSize = 11.sp,
                            fontFamily = XinUiFont,
                            color = xc.sub,
                            modifier = Modifier
                                .clickable {
                                    copyToClipboard(
                                        context,
                                        "xincode-output",
                                        listOf(toolCall.stdout, toolCall.stderr).filter { it.isNotBlank() }.joinToString("\n")
                                    )
                                    Toast.makeText(context, "已复制结果", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, content: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText(label, content))
}
