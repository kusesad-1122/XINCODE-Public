package com.xincode.data

import android.util.Log

/**
 * Extracts structured memories from assistant message content.
 *
 * Rules:
 * - Skip messages < 50 chars (too short to be meaningful).
 * - Skip greetings / acknowledgments / pure code blocks.
 * - Title = first sentence (up to first 。！？.!? or 80 chars).
 * - Content = full message.
 * - Tags = extracted technical keywords (English identifiers, proper nouns).
 * - Returns null when extraction is not applicable.
 */
object MemoryExtractor {
    private const val TAG = "MemoryExtractor"
    private const val MIN_LENGTH = 50

    /** File-based log for debuggability on ROMs that suppress logcat. */
    private val logDir = java.io.File("/data/data/com.xincode.app/files")
    private val logFile by lazy { java.io.File(logDir, "xincode_memory.log") }

    private fun fileLog(msg: String) {
        try {
            if (!logDir.exists()) logDir.mkdirs()
            logFile.appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg\n")
        } catch (e: Exception) {
            android.util.Log.wtf(TAG, "fileLog failed: ${e.message}")
        }
    }

    /** Regex for skip-classification: greetings, confirmations, code-only. */
    private val skipPatterns = listOf(
        Regex("""^[\s]*(你好|您好|哈[喽啰]|hi\b|hello\b|hey\b)""", RegexOption.IGNORE_CASE),
        Regex("""^[\s]*(好的|收到|明白|了解|没问题|可以|当然|没错|正确|是的|对)["']?\s*$"""),
        Regex("""^[\s]*```[\s\S]*```[\s]*$""")  // pure code block
    )

    /** Result of memory extraction. Null fields mean extraction skipped. */
    data class Extracted(
        val title: String,
        val content: String,
        val tags: String,
        val sourceMessageId: Long
    )

    /**
     * Attempt to extract a memory from an assistant message.
     * @return [Extracted] or null if the message is not suitable for memory storage.
     */
    fun extract(assistantContent: String, messageId: Long): Extracted? {
        val trimmed = assistantContent.trim()
        fileLog("extract called msgId=$messageId len=${trimmed.length}")
        if (trimmed.length < MIN_LENGTH) {
            fileLog("SKIP: too short (${trimmed.length} < $MIN_LENGTH)")
            Log.d(TAG, "Skip: too short (${trimmed.length} chars)")
            return null
        }

        // Skip greetings / acknowledgments / code-only
        for (pat in skipPatterns) {
            if (pat.matches(trimmed)) {
                fileLog("SKIP: matches skip pattern ${pat.pattern.take(40)}")
                Log.d(TAG, "Skip: matches skip pattern")
                return null
            }
        }

        // Title: first sentence
        val title = extractTitle(trimmed)
        if (title.isBlank()) {
            fileLog("SKIP: empty title")
            Log.d(TAG, "Skip: empty title")
            return null
        }

        // Tags: extract technical keywords
        val tags = extractTags(trimmed)

        fileLog("EXTRACTED: title='${title.take(60)}' tags=$tags")
        Log.i(TAG, "Extracted: title='${title.take(60)}' tags=$tags")

        return Extracted(
            title = title,
            content = trimmed,
            tags = tags,
            sourceMessageId = messageId
        )
    }

    /** 一眼能看出是「用户的持久偏好/个人资料」的短陈述句标记(用户消息,常常 <50 字,不能被 MIN_LENGTH 滤掉)。 */
    private val userFactMarkers = listOf(
        "我喜欢", "我爱", "我不喜欢", "我讨厌", "我偏好", "我习惯", "我常用", "我经常用", "我倾向", "我用的是",
        "我叫", "我的名字", "我姓", "我是做", "我是一名", "我是个",
        "我在做", "我正在做", "我在开发", "我在写", "我在学", "我的项目", "我的工作", "我的职业", "我的专业",
        "我住在", "我来自", "我的邮箱", "我的微信", "我的电话",
        "记住", "请记住", "帮我记住", "以后记住", "你要记住"
    )

    /**
     * 从【用户消息】里抓取值得长期记住的个人偏好/资料(如"我喜欢 go 语言")。
     * 与 [extract](只处理助手长回复)互补——用户表述往往很短,会被 MIN_LENGTH 滤掉,却正是最该记住的。
     * 只在命中标记且【不是疑问句】时返回(避免把"我喜欢什么语言"这类问题当成事实存下)。
     */
    fun extractUserFact(userText: String, messageId: Long): Extracted? {
        val t = userText.trim()
        if (t.length < 2 || t.length > 200) return null
        // 疑问句排除:在“问”而不是在“陈述”。
        if (t.contains("吗") || t.contains("?") || t.contains("？") || t.contains("什么") ||
            t.contains("怎么") || t.contains("如何") || t.contains("是不是") || t.contains("能不能")) return null
        if (userFactMarkers.none { t.contains(it) }) return null
        return Extracted(
            title = "用户偏好/资料:" + t.take(60),
            content = t,
            tags = "用户偏好",
            sourceMessageId = messageId
        )
    }

    /** Extract first sentence as title, capped at 80 chars. */
    private fun extractTitle(text: String): String {
        // Find first sentence-ending punctuation
        val match = Regex("""^(.+?)[。！？.!?\n]""").find(text)
        val first = match?.groupValues?.get(1)?.trim() ?: text.take(80).trim()
        return first.take(80)
    }

    /** Extract technical keywords: English identifiers, CamelCase, snake_case, dot.paths. */
    private fun extractTags(text: String): String {
        val keywords = mutableSetOf<String>()

        // Match English identifiers: camelCase, snake_case, dot.paths, kebab-case
        val techPattern = Regex("""\b([a-zA-Z][a-zA-Z0-9]*(?:[._-][a-zA-Z0-9]+)+)\b""")
        techPattern.findAll(text).forEach { mr ->
            keywords.add(mr.value.lowercase())
        }

        // Match standalone acronyms (2-4 uppercase letters)
        val acronymPattern = Regex("""\b([A-Z]{2,4})\b""")
        acronymPattern.findAll(text).forEach { mr ->
            keywords.add(mr.value)
        }

        // Limit tags
        return keywords.take(8).joinToString(",")
    }
}