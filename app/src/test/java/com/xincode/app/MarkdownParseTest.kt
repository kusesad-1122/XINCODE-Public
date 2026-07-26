package com.xincode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 解析测试。
 *
 * 这块出问题的表现是「符号泄漏到界面上」—— 用户看到 `**重点**` 而不是**重点**。
 * 它不会崩、不会报错,只是一直脏着,所以只能靠测试盯。
 *
 * 重点覆盖两类容易写错的:
 *  - **代码块内不能解析**。代码里 `#`、`-`、`*` 满地都是,当成标题/列表就毁了。
 *  - **未闭合标记不能吃掉后文**。流式输出中途必然出现半个 `**`。
 */
class MarkdownParseTest {

    // ---- 块级 ----

    @Test
    fun parsesHeadings() {
        val blocks = parseMarkdownBlocks("# 一级\n### 三级")
        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Heading(1, "一级"), blocks[0])
        assertEquals(MarkdownBlock.Heading(3, "三级"), blocks[1])
    }

    @Test
    fun hashWithoutSpaceIsNotHeading() {
        // "#hashtag" 不是标题,别把话题标签吃掉
        val blocks = parseMarkdownBlocks("#hashtag 这不是标题")
        assertTrue(blocks[0] is MarkdownBlock.TextSpan)
    }

    @Test
    fun parsesUnorderedAndOrderedList() {
        val blocks = parseMarkdownBlocks("- 甲\n- 乙\n1. 第一\n2. 第二")
        assertEquals(4, blocks.size)
        assertEquals(false, (blocks[0] as MarkdownBlock.ListItem).ordered)
        assertEquals("甲", (blocks[0] as MarkdownBlock.ListItem).content)
        assertEquals(true, (blocks[2] as MarkdownBlock.ListItem).ordered)
        assertEquals("1.", (blocks[2] as MarkdownBlock.ListItem).marker)
    }

    @Test
    fun tracksListIndent() {
        val blocks = parseMarkdownBlocks("- 顶层\n  - 二层\n    - 三层")
        assertEquals(0, (blocks[0] as MarkdownBlock.ListItem).indent)
        assertEquals(1, (blocks[1] as MarkdownBlock.ListItem).indent)
        assertEquals(2, (blocks[2] as MarkdownBlock.ListItem).indent)
    }

    @Test
    fun mergesConsecutiveQuoteLines() {
        val blocks = parseMarkdownBlocks("> 第一行\n> 第二行\n\n正文")
        val quote = blocks[0] as MarkdownBlock.Quote
        assertEquals("第一行\n第二行", quote.content)
    }

    @Test
    fun parsesDivider() {
        for (s in listOf("---", "***", "___", "- - -")) {
            assertTrue("$s 应当是分隔线", parseMarkdownBlocks(s)[0] is MarkdownBlock.Divider)
        }
    }

    @Test
    fun parsesTable() {
        val md = """
            | 工具 | 说明 |
            |------|------|
            | read_file | 读取文件 |
            | grep | 搜索 |
        """.trimIndent()
        val table = parseMarkdownBlocks(md)[0] as MarkdownBlock.Table
        assertEquals(listOf("工具", "说明"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("read_file", "读取文件"), table.rows[0])
    }

    @Test
    fun pipeWithoutSeparatorRowIsNotTable() {
        // 正文里出现竖线很常见(比如说 a|b),没有 |---| 分隔行就不该当表格
        val blocks = parseMarkdownBlocks("这里有个 a|b 竖线")
        assertTrue(blocks[0] is MarkdownBlock.TextSpan)
    }

    // ---- 代码块隔离:最容易出错的地方 ----

    @Test
    fun codeBlockContentIsNeverParsed() {
        val md = """
            正文
            ```kotlin
            # 这是注释不是标题
            - list.remove(x)
            val a = b * c * d
            ```
            结尾
        """.trimIndent()
        val blocks = parseMarkdownBlocks(md)

        val code = blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertTrue("代码里的 # 必须原样保留", code.content.contains("# 这是注释不是标题"))
        assertTrue("代码里的 - 必须原样保留", code.content.contains("- list.remove(x)"))
        // 代码块里的行绝不能变成 Heading / ListItem
        assertTrue(blocks.none { it is MarkdownBlock.Heading })
        assertTrue(blocks.none { it is MarkdownBlock.ListItem })
    }

    @Test
    fun unclosedFenceStillBecomesCodeBlock() {
        // 流式输出到一半的常态:代码还没吐完
        val blocks = parseMarkdownBlocks("说明\n```python\nprint(1)")
        val code = blocks.filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("print(1)", code.content)
    }

    // ---- 行内 ----

    @Test
    fun parsesBoldItalicCodeStrike() {
        assertEquals(listOf(InlineSpan.Bold("重点")), parseInline("**重点**"))
        assertEquals(listOf(InlineSpan.Italic("斜")), parseInline("*斜*"))
        assertEquals(listOf(InlineSpan.BoldItalic("都要")), parseInline("***都要***"))
        assertEquals(listOf(InlineSpan.Code("file_read")), parseInline("`file_read`"))
        assertEquals(listOf(InlineSpan.Strike("废弃")), parseInline("~~废弃~~"))
    }

    @Test
    fun boldInsideSentenceKeepsSurroundingText() {
        val spans = parseInline("核心是**提升效率**,目标用户是开发者")
        assertEquals(InlineSpan.Plain("核心是"), spans[0])
        assertEquals(InlineSpan.Bold("提升效率"), spans[1])
        assertEquals(InlineSpan.Plain(",目标用户是开发者"), spans[2])
    }

    @Test
    fun unclosedMarkerStaysPlain() {
        // 流式输出中途只吐了半个 **,不能把后面全染成粗体
        assertEquals(listOf(InlineSpan.Plain("未闭合 **粗体")), parseInline("未闭合 **粗体"))
        assertEquals(listOf(InlineSpan.Plain("半个 `代码")), parseInline("半个 `代码"))
    }

    @Test
    fun inlineCodeSuppressesMarkersInside() {
        // `a*b*c` 里的星号是代码的一部分,不是斜体
        val spans = parseInline("看 `a*b*c` 这段")
        assertEquals(InlineSpan.Code("a*b*c"), spans[1])
    }

    @Test
    fun underscoreInsideWordIsNotItalic() {
        // snake_case_name 不该被拆成斜体
        assertEquals(listOf(InlineSpan.Plain("snake_case_name")), parseInline("snake_case_name"))
    }

    @Test
    fun parsesLink() {
        val spans = parseInline("见 [文档](https://example.com) 说明")
        assertEquals(InlineSpan.Link("文档", "https://example.com"), spans[1])
    }

    @Test
    fun backslashEscapesMarker() {
        assertEquals(listOf(InlineSpan.Plain("*不是斜体*")), parseInline("\\*不是斜体\\*"))
    }

    @Test
    fun emptyInputDoesNotCrash() {
        assertEquals(listOf(MarkdownBlock.TextSpan("")), parseMarkdownBlocks(""))
        assertEquals(emptyList<InlineSpan>(), parseInline(""))
    }

    /** 用户截图里产品经理那条真实输出,整段过一遍。 */
    @Test
    fun realWorldMessageRendersWithoutLeakingSymbols() {
        val md = """
            关于 xincode,它的核心是**提升开发者的代码编写效率与质量**,目标用户主要是**中高级软件工程师**。

            核心价值点已明确写入产品需求文档(PRD)v1.0,主要覆盖:
            1. 智能代码补全(基于上下文)
            2. 代码片段生成
            3. 内联式代码解释与优化建议
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        assertEquals(3, blocks.count { it is MarkdownBlock.ListItem })

        // 段落里的 ** 必须被解析掉,不能出现在任何 Plain 片段里
        val paragraphs = blocks.filterIsInstance<MarkdownBlock.TextSpan>()
        val plains = paragraphs.flatMap { parseInline(it.content) }
            .filterIsInstance<InlineSpan.Plain>()
        assertTrue(
            "星号泄漏到了正文: ${plains.map { it.text }}",
            plains.none { it.text.contains("**") }
        )
        assertTrue(
            "粗体没被识别出来",
            paragraphs.flatMap { parseInline(it.content) }.any {
                it is InlineSpan.Bold && it.text.contains("提升开发者")
            }
        )
    }
}
