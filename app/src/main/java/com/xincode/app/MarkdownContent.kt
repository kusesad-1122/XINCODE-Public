package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/** 标题字号:采用 Claude 典雅排版比例，一级标题大气舒展，逐级优雅缩进。 */
private val HEADING_SIZES = listOf(21, 18, 16, 15, 14, 13)

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
                        InlineText(block.content, 14.5.sp, xc.ink)
                    }

                is MarkdownBlock.CodeBlock -> InlineCodeBlock(block.language, block.content)

                is MarkdownBlock.Heading -> {
                    val size = HEADING_SIZES[(block.level - 1).coerceIn(0, HEADING_SIZES.lastIndex)]
                    Text(
                        buildInline(block.content, xc),
                        fontSize = size.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = xc.ink,
                        fontFamily = XinSerifFont,
                        lineHeight = (size + 8).sp,
                        letterSpacing = (-0.3).sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }

                is MarkdownBlock.ListItem -> Row(
                    Modifier.padding(start = (block.indent * 14).dp, top = 2.dp, bottom = 2.dp)
                ) {
                    Text(
                        block.marker,
                        fontSize = 14.sp,
                        color = xc.green,
                        fontFamily = if (block.ordered) XinCodeFont else XinUiFont,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 22.sp,
                        // 固定宽度让多行列表项的续行对齐到文字而不是缩回符号下面
                        modifier = Modifier.width(if (block.ordered) 24.dp else 16.dp)
                    )
                    InlineText(block.content, 14.5.sp, xc.ink)
                }

                is MarkdownBlock.Quote -> Row(
                    Modifier.padding(vertical = 6.dp).height(IntrinsicSize.Min)
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(xc.green.copy(alpha = 0.6f)))
                    Spacer(Modifier.width(10.dp))
                    InlineText(block.content, 14.sp, xc.sub)
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

/** 一段带行内样式的文字。链接可点。正文全面切换为人文无衬线，字距行高对齐 Claude Desktop。 */
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
        fontFamily = XinUiFont,
        lineHeight = 22.sp
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
        Row(Modifier.background(xc.bgElevated).border(0.5.dp, xc.border)) {
            table.header.forEach { cell ->
                Text(
                    buildInline(cell, xc),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = xc.ink,
                    fontFamily = XinUiFont,
                    lineHeight = 18.sp,
                    modifier = Modifier.width(colWidth).padding(8.dp)
                )
            }
        }
        table.rows.forEach { row ->
            Row(Modifier.border(0.5.dp, xc.border)) {
                // 数据行可能比表头短,补空单元格,不然列会错位
                for (idx in table.header.indices) {
                    Text(
                        buildInline(row.getOrElse(idx) { "" }, xc),
                        fontSize = 12.sp,
                        color = xc.ink,
                        fontFamily = XinUiFont,
                        lineHeight = 18.sp,
                        modifier = Modifier.width(colWidth).padding(8.dp)
                    )
                }
            }
        }
    }
}
