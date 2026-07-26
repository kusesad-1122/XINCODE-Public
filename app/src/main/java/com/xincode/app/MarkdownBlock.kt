package com.xincode.app

/**
 * 从 Markdown 正文解析出的一个块。
 *
 * 之前这里只认围栏代码块,别的一律当纯文本原样打印 —— 于是模型输出的
 * `**重点**`、`# 标题`、`- 列表` 全都带着符号显示给用户看。这不是「样式差一点」,
 * 是内容里混进了本该被吃掉的标记,读起来很脏。
 *
 * 现在按行做块级切分,行内样式(粗体/斜体/行内代码/链接)在渲染时另做一遍。
 */
sealed class MarkdownBlock {
    /** 普通段落。内容里可能还有行内标记,交给 [parseInline] 处理。 */
    data class TextSpan(val content: String) : MarkdownBlock()

    /** 围栏代码块。里面的东西一律原样,不做任何行内解析。 */
    data class CodeBlock(val language: String?, val content: String) : MarkdownBlock()

    /** 标题。[level] 1..6。 */
    data class Heading(val level: Int, val content: String) : MarkdownBlock()

    /**
     * 列表项。[ordered] 区分有序/无序,[marker] 是有序时显示的序号,
     * [indent] 是缩进级数(每两个空格算一级)。
     */
    data class ListItem(
        val ordered: Boolean,
        val marker: String,
        val content: String,
        val indent: Int
    ) : MarkdownBlock()

    /** 引用块 `> …`。 */
    data class Quote(val content: String) : MarkdownBlock()

    /** 分隔线 `---` / `***` / `___`。 */
    object Divider : MarkdownBlock()

    /** 表格。[header] 是表头,[rows] 是数据行。 */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock()
}

/** 行内样式的一段。 */
sealed class InlineSpan {
    data class Plain(val text: String) : InlineSpan()
    data class Bold(val text: String) : InlineSpan()
    data class Italic(val text: String) : InlineSpan()
    data class BoldItalic(val text: String) : InlineSpan()
    data class Code(val text: String) : InlineSpan()
    data class Strike(val text: String) : InlineSpan()
    data class Link(val text: String, val url: String) : InlineSpan()
}

private val FENCE = Regex("^\\s*```([\\w+-]*)\\s*$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val UNORDERED = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val QUOTE = Regex("^\\s*>\\s?(.*)$")
private val DIVIDER = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
private val TABLE_SEP = Regex("^\\s*\\|?[\\s:|-]+\\|[\\s:|-]*$")

/**
 * 把 Markdown 正文切成块。
 *
 * 按行扫而不是用一个大正则:围栏代码块内部必须原样保留,行首出现 `#` 或 `-`
 * 都不能当成标题或列表 —— 代码里这两个字符太常见了(注释、减号),用全局正则
 * 一定会误伤。所以状态机里「是否在代码块内」是第一优先级的判断。
 *
 * 未闭合的围栏按代码块处理到文末:流式输出时这是常态(代码还没吐完),
 * 当成普通文本会让内容在收尾前一直跳来跳去。
 */
fun parseMarkdownBlocks(input: String): List<MarkdownBlock> {
    if (input.isEmpty()) return listOf(MarkdownBlock.TextSpan(""))

    val out = mutableListOf<MarkdownBlock>()
    val lines = input.lines()
    val para = StringBuilder()

    fun flushParagraph() {
        if (para.isNotEmpty()) {
            out += MarkdownBlock.TextSpan(para.toString().trimEnd('\n'))
            para.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = FENCE.find(line)

        if (fence != null) {
            flushParagraph()
            val lang = fence.groupValues[1].takeIf { it.isNotEmpty() }
            val body = StringBuilder()
            i++
            while (i < lines.size && FENCE.find(lines[i]) == null) {
                body.append(lines[i]).append('\n')
                i++
            }
            i++   // 跳过收尾的 ```(没有就是走到了文末,同样退出)
            out += MarkdownBlock.CodeBlock(lang, body.toString().trimEnd('\n'))
            continue
        }

        val heading = HEADING.find(line)
        if (heading != null) {
            flushParagraph()
            out += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            i++
            continue
        }

        if (DIVIDER.matches(line)) {
            flushParagraph()
            out += MarkdownBlock.Divider
            i++
            continue
        }

        // 表格:当前行像表格行,且下一行是 |---|---| 分隔行才算
        if (line.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1])) {
            flushParagraph()
            val header = splitTableRow(line)
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                rows += splitTableRow(lines[i])
                i++
            }
            out += MarkdownBlock.Table(header, rows)
            continue
        }

        val ordered = ORDERED.find(line)
        if (ordered != null) {
            flushParagraph()
            out += MarkdownBlock.ListItem(
                ordered = true,
                marker = ordered.groupValues[2] + ".",
                content = ordered.groupValues[3],
                indent = ordered.groupValues[1].length / 2
            )
            i++
            continue
        }

        val unordered = UNORDERED.find(line)
        if (unordered != null) {
            flushParagraph()
            out += MarkdownBlock.ListItem(
                ordered = false,
                marker = "•",
                content = unordered.groupValues[2],
                indent = unordered.groupValues[1].length / 2
            )
            i++
            continue
        }

        val quote = QUOTE.find(line)
        if (quote != null) {
            flushParagraph()
            // 连续的引用行合成一块,不然每行都画一条竖线,看着像断掉的
            val body = StringBuilder(quote.groupValues[1])
            i++
            while (i < lines.size) {
                val q = QUOTE.find(lines[i]) ?: break
                body.append('\n').append(q.groupValues[1])
                i++
            }
            out += MarkdownBlock.Quote(body.toString())
            continue
        }

        if (line.isBlank()) {
            flushParagraph()
        } else {
            if (para.isNotEmpty()) para.append('\n')
            para.append(line)
        }
        i++
    }
    flushParagraph()

    if (out.isEmpty()) out += MarkdownBlock.TextSpan(input)
    return out
}

private fun splitTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

/**
 * 解析行内标记。
 *
 * 手写而不是套正则全局替换,因为要处理**嵌套与优先级**:行内代码里的 `*`
 * 不能当成斜体(`` `a*b*c` `` 里那两个星号是代码的一部分),所以代码段必须
 * 先被整段吃掉。同理链接文字里的标记也不再往下解析。
 *
 * 没有闭合标记时按普通文本输出 —— 流式输出中途经常只吐出半个 `**`,
 * 那时候不该把后面所有内容都染成粗体。
 */
fun parseInline(input: String): List<InlineSpan> {
    if (input.isEmpty()) return emptyList()
    val out = mutableListOf<InlineSpan>()
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            out += InlineSpan.Plain(plain.toString())
            plain.clear()
        }
    }

    var i = 0
    while (i < input.length) {
        val c = input[i]

        // 反斜杠转义:\* 就是一个普通星号
        if (c == '\\' && i + 1 < input.length) {
            plain.append(input[i + 1])
            i += 2
            continue
        }

        // 行内代码优先级最高:里面的任何标记都不再解析
        if (c == '`') {
            val end = input.indexOf('`', i + 1)
            if (end > i + 1) {
                flushPlain()
                out += InlineSpan.Code(input.substring(i + 1, end))
                i = end + 1
                continue
            }
        }

        // 链接 [文字](地址)
        if (c == '[') {
            val close = input.indexOf(']', i + 1)
            if (close > i && close + 1 < input.length && input[close + 1] == '(') {
                val urlEnd = input.indexOf(')', close + 2)
                if (urlEnd > close) {
                    flushPlain()
                    out += InlineSpan.Link(
                        text = input.substring(i + 1, close),
                        url = input.substring(close + 2, urlEnd)
                    )
                    i = urlEnd + 1
                    continue
                }
            }
        }

        if (c == '~' && input.startsWith("~~", i)) {
            val end = input.indexOf("~~", i + 2)
            if (end > i + 1) {
                flushPlain()
                out += InlineSpan.Strike(input.substring(i + 2, end))
                i = end + 2
                continue
            }
        }

        if (c == '*' || c == '_') {
            // 先试三个(粗斜体),再两个(粗体),最后一个(斜体)——
            // 顺序反了的话 ***x*** 会被当成「斜体的 **x**」。
            val triple = "$c$c$c"
            if (input.startsWith(triple, i)) {
                val end = input.indexOf(triple, i + 3)
                if (end > i + 2) {
                    flushPlain()
                    out += InlineSpan.BoldItalic(input.substring(i + 3, end))
                    i = end + 3
                    continue
                }
            }
            val double = "$c$c"
            if (input.startsWith(double, i)) {
                val end = input.indexOf(double, i + 2)
                if (end > i + 1) {
                    flushPlain()
                    out += InlineSpan.Bold(input.substring(i + 2, end))
                    i = end + 2
                    continue
                }
            }
            // 单个 `_` 只在词边界才算斜体:snake_case_name 里的下划线不是标记。
            // 星号没这个问题,但统一走同一条判断更好懂。
            val isWordChar = { ch: Char? -> ch != null && (ch.isLetterOrDigit() || ch == '_') }
            val okBefore = c == '*' || !isWordChar(input.getOrNull(i - 1))
            if (okBefore) {
                val end = input.indexOf(c, i + 1)
                if (end > i + 1) {
                    val okAfter = c == '*' || !isWordChar(input.getOrNull(end + 1))
                    if (okAfter) {
                        flushPlain()
                        out += InlineSpan.Italic(input.substring(i + 1, end))
                        i = end + 1
                        continue
                    }
                }
            }
        }

        plain.append(c)
        i++
    }
    flushPlain()
    return out
}
