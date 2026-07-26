package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R

private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/** 标题字号:一级最大,往下递减,到四级以后就不再缩了(再小和正文没区别)。 */
private val HEADING_SIZES = listOf(19, 17, 15, 14, 13, 13)

/**
 * 渲染 Markdown。
 *
 * 之前这里只把围栏代码块挑出来,剩下的整段丢给 Text —— 于是 `**重点**`、`# 标题`、
 * `- 列表` 全都带着符号显示。模型输出几乎必然带 Markdown,那些符号是噪音,
 * 该被吃掉换成样式。
 */
@Composable
fun MarkdownContent(content: String, modifier: Modifier = Modifier) {
    val xc = LocalXinColors.current
    val blocks = remember(content) { parseMarkdownBlocks(content) }

    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.TextSpan ->
                    if (block.content.isNotBlank()) {
                        InlineText(block.content, 13.sp, xc.ink)
                    }

                is MarkdownBlock.CodeBlock -> InlineCodeBlock(block.language, block.content)

                is MarkdownBlock.Heading -> {
                    val size = HEADING_SIZES[(block.level - 1).coerceIn(0, HEADING_SIZES.lastIndex)]
                    Text(
                        buildInline(block.content, xc),
                        fontSize = size.sp,
                        fontWeight = FontWeight.Bold,
                        color = xc.ink,
                        fontFamily = JetBrainsMono,
                        lineHeight = (size + 7).sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 3.dp)
                    )
                }

                is MarkdownBlock.ListItem -> Row(
                    Modifier.padding(start = (block.indent * 14).dp, top = 1.dp, bottom = 1.dp)
                ) {
                    Text(
                        block.marker,
                        fontSize = 13.sp,
                        color = xc.sub,
                        fontFamily = JetBrainsMono,
                        lineHeight = 20.sp,
                        // 固定宽度让多行列表项的续行对齐到文字而不是缩回符号下面
                        modifier = Modifier.width(if (block.ordered) 22.dp else 14.dp)
                    )
                    InlineText(block.content, 13.sp, xc.ink)
                }

                is MarkdownBlock.Quote -> Row(
                    Modifier.padding(vertical = 3.dp).height(IntrinsicSize.Min)
                ) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(xc.border))
                    Spacer(Modifier.width(8.dp))
                    InlineText(block.content, 13.sp, xc.sub)
                }

                is MarkdownBlock.Divider -> Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .padding(vertical = 0.dp)
                        .background(xc.divider)
                        .padding(vertical = 6.dp)
                )

                is MarkdownBlock.Table -> MarkdownTable(block, xc)
            }
        }
    }
}

/** 一段带行内样式的文字。链接可点。 */
@Composable
private fun InlineText(
    text: String,
    size: androidx.compose.ui.unit.TextUnit,
    color: Color
) {
    val xc = LocalXinColors.current
    val annotated = remember(text, xc) { buildInline(text, xc) }
    Text(
        annotated,
        fontSize = size,
        color = color,
        fontFamily = JetBrainsMono,
        lineHeight = 20.sp
    )
}

/** 把行内标记转成 AnnotatedString。 */
private fun buildInline(text: String, xc: XinColors): AnnotatedString =
    buildAnnotatedString {
        for (span in parseInline(text)) {
            when (span) {
                is InlineSpan.Plain -> append(span.text)
                is InlineSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                is InlineSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                is InlineSpan.BoldItalic -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                ) { append(span.text) }
                is InlineSpan.Strike -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough, color = xc.sub)
                ) { append(span.text) }
                is InlineSpan.Code -> withStyle(
                    SpanStyle(
                        fontFamily = JetBrainsMono,
                        background = xc.bgElevated,
                        color = xc.green
                    )
                ) { append(" ${span.text} ") }
                is InlineSpan.Link -> withLink(
                    // 用 LinkAnnotation 而不是自己接 onClick:点击热区、无障碍、长按
                    // 全都由 Text 负责,不必手动按 offset 查注解。
                    LinkAnnotation.Url(
                        span.url,
                        TextLinkStyles(
                            SpanStyle(color = xc.green, textDecoration = TextDecoration.Underline)
                        )
                    )
                ) { append(span.text) }
            }
        }
    }

/**
 * 表格。横向可滚 —— 手机屏幕窄,列一多必然放不下,
 * 不给滚动就只能靠压缩字号硬塞,结果是谁都看不清。
 */
@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, xc: XinColors) {
    val scroll = rememberScrollState()
    val colWidth = 110.dp

    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .horizontalScroll(scroll)
    ) {
        Row(Modifier.background(xc.bgElevated)) {
            table.header.forEach { cell ->
                Text(
                    buildInline(cell, xc),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = xc.ink,
                    fontFamily = JetBrainsMono,
                    lineHeight = 16.sp,
                    modifier = Modifier.width(colWidth).padding(6.dp)
                )
            }
        }
        table.rows.forEach { row ->
            Row {
                // 数据行可能比表头短,补空单元格,不然列会错位
                for (idx in table.header.indices) {
                    Text(
                        buildInline(row.getOrElse(idx) { "" }, xc),
                        fontSize = 11.sp,
                        color = xc.ink,
                        fontFamily = JetBrainsMono,
                        lineHeight = 16.sp,
                        modifier = Modifier.width(colWidth).padding(6.dp)
                    )
                }
            }
        }
    }
}
