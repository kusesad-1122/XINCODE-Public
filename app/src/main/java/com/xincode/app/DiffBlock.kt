package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
private val GreenBg = Color(0x1A6E8050)   // 10% green background for ADD
private val RedBg = Color(0x1AA8514A)      // 10% red background for DELETE
private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/**
 * Diff viewer block for file_edit output.
 * Shows +/- lines with color-coded backgrounds.
 */
@Composable
fun DiffBlock(fileEdit: MessageContent.FileEdit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().border(0.5.dp, Border)
    ) {
        // Title
        Text(
            "─ ${fileEdit.path} (diff) ─",
            fontSize = 10.sp,
            fontFamily = JetBrainsMono,
            color = Sub,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Border))

        // Diff lines
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            fileEdit.diffLines.forEach { diffLine ->
                val prefix = when (diffLine.type) {
                    DiffType.ADD -> "+"
                    DiffType.DELETE -> "-"
                    DiffType.CONTEXT -> " "
                }
                val textColor = when (diffLine.type) {
                    DiffType.ADD -> Green
                    DiffType.DELETE -> Red
                    DiffType.CONTEXT -> Sub
                }
                val bgColor = when (diffLine.type) {
                    DiffType.ADD -> GreenBg
                    DiffType.DELETE -> RedBg
                    DiffType.CONTEXT -> Color.Transparent
                }
                Row(
                    Modifier.fillMaxWidth().background(bgColor)
                ) {
                    Text(
                        prefix,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMono,
                        color = textColor,
                        modifier = Modifier.width(12.dp)
                    )
                    Text(
                        diffLine.content,
                        fontSize = 11.sp,
                        fontFamily = JetBrainsMono,
                        color = textColor
                    )
                }
            }
        }
    }
}