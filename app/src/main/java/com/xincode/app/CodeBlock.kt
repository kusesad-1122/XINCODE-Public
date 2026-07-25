package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R

private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Red: Color @Composable get() = LocalXinColors.current.red
private val Border: Color @Composable get() = LocalXinColors.current.divider
private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))
private const val MAX_FOLD_LINES = 20

/**
 * Code viewer block for file_read output.
 * Shows path title, line numbers, and content in a bordered card.
 */
@Composable
fun CodeBlock(fileRead: MessageContent.FileRead, modifier: Modifier = Modifier) {
    val lines = fileRead.content.lines()
    val totalLines = lines.size
    var expanded by remember { mutableStateOf(false) }
    val displayLines = if (expanded || totalLines <= MAX_FOLD_LINES) lines else lines.take(MAX_FOLD_LINES)
    val lineNumWidth = if (totalLines >= 1000) 40.dp else if (totalLines >= 100) 32.dp else 24.dp

    Column(
        modifier.fillMaxWidth().border(0.5.dp, Border)
    ) {
        // Title bar: path
        Text(
            "─ ${fileRead.path} ─",
            fontSize = 10.sp,
            fontFamily = JetBrainsMono,
            color = Sub,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Border))

        // Content with line numbers
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            displayLines.forEachIndexed { idx, line ->
                Row {
                    Text(
                        "${fileRead.startLine + idx}",
                        fontSize = 10.sp,
                        fontFamily = JetBrainsMono,
                        color = Faint,
                        modifier = Modifier.width(lineNumWidth)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        line,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMono,
                        color = Ink
                    )
                }
            }
        }

        // Expand toggle
        if (totalLines > MAX_FOLD_LINES) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Border))
            Text(
                if (expanded) "△ 收起" else "▽ 展开全部 ($totalLines 行)",
                fontSize = 10.sp,
                fontFamily = JetBrainsMono,
                color = Faint,
                modifier = Modifier
                    .padding(8.dp)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        expanded = !expanded
                    }
            )
        }
    }
}