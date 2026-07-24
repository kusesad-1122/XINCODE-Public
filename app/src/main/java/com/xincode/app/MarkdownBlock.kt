package com.xincode.app

/** A single block parsed from Markdown content. */
sealed class MarkdownBlock {
    data class TextSpan(val content: String) : MarkdownBlock()
    data class CodeBlock(val language: String?, val content: String) : MarkdownBlock()
}

/**
 * Parse content into alternating TextSpan and CodeBlock segments.
 * Only handles ```...``` fenced code blocks. Unclosed fences remain as TextSpan.
 */
fun parseMarkdownBlocks(input: String): List<MarkdownBlock> {
    if (input.isEmpty()) return listOf(MarkdownBlock.TextSpan(""))
    val result = mutableListOf<MarkdownBlock>()
    val regex = Regex("```([\\w+-]*)?\\n([\\s\\S]*?)\\n```")
    var lastEnd = 0
    for (match in regex.findAll(input)) {
        if (match.range.first > lastEnd) {
            result.add(MarkdownBlock.TextSpan(input.substring(lastEnd, match.range.first)))
        }
        val lang = match.groupValues[1].takeIf { it.isNotEmpty() }
        val code = match.groupValues[2]
        result.add(MarkdownBlock.CodeBlock(lang, code))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < input.length) {
        result.add(MarkdownBlock.TextSpan(input.substring(lastEnd)))
    }
    if (result.isEmpty()) result.add(MarkdownBlock.TextSpan(input))
    return result
}