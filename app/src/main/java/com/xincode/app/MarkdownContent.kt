package com.xincode.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.xincode.app.R

private val Ink = Color(0xFF1A1A17)
private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

@Composable
fun MarkdownContent(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) { parseMarkdownBlocks(content) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.TextSpan -> Text(
                    block.content,
                    fontSize = 13.sp,
                    color = Ink,
                    fontFamily = JetBrainsMono,
                    lineHeight = 20.sp
                )
                is MarkdownBlock.CodeBlock -> InlineCodeBlock(block.language, block.content)
            }
        }
    }
}