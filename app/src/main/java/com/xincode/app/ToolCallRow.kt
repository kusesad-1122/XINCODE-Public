package com.xincode.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R

private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

private fun toolIcon(toolName: String): ImageVector = when (toolName) {
    "shell_exec", "su_exec" -> Icons.Outlined.Build
    "file_read" -> Icons.Outlined.Description
    "file_edit" -> Icons.Outlined.Edit
    "web_search" -> Icons.Outlined.Search
    "web_fetch" -> Icons.Outlined.Public
    "agent_plan" -> Icons.Outlined.CheckCircle
    "web_search_batch" -> Icons.Outlined.Search
    else -> Icons.Outlined.Build
}

@Composable
private fun statusColor(status: ToolStatus): Color {
    val xc = LocalXinColors.current
    return when (status) {
        ToolStatus.RUNNING -> xc.green
        ToolStatus.SUCCESS -> xc.green
        ToolStatus.FAILED -> xc.red
        ToolStatus.DENIED -> xc.faint
        else -> xc.green
    }
}

private fun statusLabel(status: ToolStatus, durationMs: Long?, exitCode: Int?): String = when (status) {
    ToolStatus.RUNNING -> "running\u2026"
    ToolStatus.SUCCESS -> {
        val ms = durationMs ?: 0L
        if (ms < 1000) "${ms}ms" else "${"%.1f".format(ms / 1000.0)}s"
    }
    ToolStatus.FAILED -> "exit ${exitCode ?: -1}"
    ToolStatus.DENIED -> "denied"
    else -> ""
}

/**
 * Claude-style collapsible tool call row.
 *
 * Default (folded): single 32dp row with icon, toolName, paramsSummary, arrow, dot, status.
 * Expanded: same row + detail panel with command, stdout, stderr, copy buttons.
 */
@Composable
fun ToolCallRow(toolCall: MessageContent.ToolCall, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    // Arrow rotation animation (0° → 180° on expand, 150ms)
    val rotationProp by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(150, easing = LinearEasing),
        label = "arrowRotate"
    )

    // RUNNING blink animation (alpha 0.4 ↔ 1.0, period 1s)
    val blinkTransition = rememberInfiniteTransition(label = "dotBlink")
    val dotAlpha by blinkTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotBlinkAlpha"
    )

    val isRunning = toolCall.status == ToolStatus.RUNNING
    val stColor = statusColor(toolCall.status)
    val xc = LocalXinColors.current
    val Ink = xc.ink
    val Sub = xc.sub
    val Faint = xc.faint
    val Green = xc.green
    val Red = xc.red
    val Border = xc.border
    val Bg = xc.bg

    Column(
        modifier = modifier
            .padding(vertical = 2.dp)
            .animateContentSize(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
    ) {
        // ---- folded row (32dp) ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clickable(indication = null, interactionSource = interactionSource) {
                    expanded = !expanded
                }
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Tool icon(缩小)
            Icon(
                imageVector = toolIcon(toolCall.toolName),
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = stColor
            )
            Spacer(Modifier.width(6.dp))

            // 2+3. 折叠态显示「人话」标签(动词 + 参数摘要),如「读取 app/Settings.kt」「搜索网页:天气」,
            // 而不是裸工具名 + 一坨 JSON。展开后仍能看到原始命令与完整输出,排查不受影响。
            Text(
                // 用 fullParams(原始 JSON)解析,paramsSummary 已被加工过、不再是合法 JSON。
                ToolLabels.labelOf(toolCall.toolName, toolCall.fullParams.ifBlank { toolCall.paramsSummary }),
                fontSize = 9.sp,
                fontFamily = JetBrainsMono,
                color = if (toolCall.status == ToolStatus.FAILED || toolCall.status == ToolStatus.DENIED) Red else Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // 4. Arrow(缩小)
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(14.dp).rotate(rotationProp),
                tint = Sub
            )
            Spacer(Modifier.width(6.dp))

            // 5. Status dot(缩小)
            Canvas(Modifier.size(7.dp)) {
                drawCircle(
                    color = stColor,
                    alpha = if (isRunning) dotAlpha else 1f
                )
            }
            Spacer(Modifier.width(4.dp))

            // 6. Status text(字体调小)
            Text(
                statusLabel(toolCall.status, toolCall.durationMs, toolCall.exitCode),
                fontSize = 9.sp,
                fontFamily = JetBrainsMono,
                color = Sub
            )
        }

        // ---- expanded detail panel ----
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 12.dp)
                    .padding(top = 2.dp, bottom = 4.dp)
                    .border(1.dp, Color(0x1A1A1A17), shape = RoundedCornerShape(8.dp))
            ) {
                // Section 1: Command + copy
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("❯ ", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Green)
                    Text(toolCall.paramsSummary, fontSize = 11.sp, fontFamily = JetBrainsMono, color = Ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制命令",
                        modifier = Modifier.size(16.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            val clip = ClipData.newPlainText("label", toolCall.paramsSummary)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(clip)
                            Toast.makeText(context, "已复制命令", Toast.LENGTH_SHORT).show()
                        },
                        tint = Sub
                    )
                }

                // Section 2: stdout (if present)
                if (toolCall.stdout.isNotBlank()) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x1A1A1A17)))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.weight(1f)) {
                            toolCall.stdout.lines().forEach { line ->
                                Text(line, fontSize = 10.sp, fontFamily = JetBrainsMono, color = Sub,
                                    lineHeight = 14.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "复制输出",
                            modifier = Modifier.size(16.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                val clip = ClipData.newPlainText("label", toolCall.stdout)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(clip)
                                Toast.makeText(context, "已复制输出", Toast.LENGTH_SHORT).show()
                            },
                            tint = Sub
                        )
                    }
                }

                // Section 3: stderr (if present)
                if (toolCall.stderr.isNotBlank()) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x1A1A1A17)))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.weight(1f)) {
                            toolCall.stderr.lines().forEach { line ->
                                Text(line, fontSize = 10.sp, fontFamily = JetBrainsMono, color = Red,
                                    lineHeight = 14.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "复制错误",
                            modifier = Modifier.size(16.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                val clip = ClipData.newPlainText("label", toolCall.stderr)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(clip)
                                Toast.makeText(context, "已复制错误输出", Toast.LENGTH_SHORT).show()
                            },
                            tint = Sub
                        )
                    }
                }

                // Section 4: status tail (FAILED / DENIED only)
                if (toolCall.status == ToolStatus.FAILED || toolCall.status == ToolStatus.DENIED) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x1A1A1A17)))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (toolCall.status == ToolStatus.FAILED) {
                            Text("●", fontSize = 10.sp, fontFamily = JetBrainsMono, color = Red)
                            Spacer(Modifier.width(4.dp))
                            val ms = toolCall.durationMs ?: 0L
                            val dur = if (ms < 1000) "${ms}ms" else "${"%.1f".format(ms / 1000.0)}s"
                            Text(
                                "exit ${toolCall.exitCode ?: -1} · ${dur} · failed",
                                fontSize = 11.sp, fontFamily = JetBrainsMono, color = Red
                            )
                        } else {
                            Text("●", fontSize = 10.sp, fontFamily = JetBrainsMono, color = Faint)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "denied by security gate",
                                fontSize = 11.sp, fontFamily = JetBrainsMono, color = Faint
                            )
                        }
                    }
                }

                // Copy All button
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制全部",
                        modifier = Modifier.size(14.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            val all = buildString {
                                append("❯ ${toolCall.paramsSummary}")
                                if (toolCall.stdout.isNotBlank()) append("\n---\n${toolCall.stdout}")
                                if (toolCall.stderr.isNotBlank()) append("\n---\n${toolCall.stderr}")
                            }
                            val clip = ClipData.newPlainText("label", all)
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(clip)
                            Toast.makeText(context, "已复制全部", Toast.LENGTH_SHORT).show()
                        },
                        tint = Sub
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("All", fontSize = 9.sp, fontFamily = JetBrainsMono, color = Sub)
                }
            }
        }
    }
}