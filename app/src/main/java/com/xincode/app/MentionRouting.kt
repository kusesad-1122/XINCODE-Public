package com.xincode.app

/**
 * 群聊 `@名字` 路由。
 *
 * 看着简单,但有几条不这么写就会出事:
 *
 *  1. **必须排除发送者自己**。两个智能体一旦在回复里带上对方的 @,就会你来我往地
 *     无限对话下去,烧到没额度为止。这是这套机制最危险的一处。
 *  2. **引用块里的 @ 要屏蔽**。回复里引用了别人的原话,不该把被引用者再叫起来一次。
 *  3. **要做边界检测**。`@张三` 不该匹配到 `@张三丰`;反过来 `@张三,` 后面跟着标点
 *     也得算命中。
 *  4. `@all` 是保留字,叫醒全部成员(除了发送者)。
 */
object MentionRouting {

    const val ALL = "all"

    /** 引用块。屏蔽其中的 @ —— 用等长空格替换,保持后面所有下标不变。 */
    private val QUOTE_BLOCK = Regex("""<quote(?:\s[^>]*)?>[\s\S]*?</quote>""", RegexOption.IGNORE_CASE)

    /** @ 之前允许出现的字符(除空白外)。 */
    private val BEFORE_OK = setOf('(', '[', '{', '<', '「', '【', '(')

    /** @名字 之后允许出现的字符(除空白外)。 */
    private val AFTER_OK = setOf(
        '.', ',', '!', '?', ';', ':', ')', ']', '}', '>',
        '，', '。', '！', '？', '；', '：', ')', '】', '」', '、'
    )

    private fun maskQuotes(content: String): String =
        QUOTE_BLOCK.replace(content) { m -> m.value.replace(Regex("[^\n]"), " ") }

    private fun isBoundaryBefore(c: Char?): Boolean =
        c == null || c.isWhitespace() || c in BEFORE_OK

    private fun isBoundaryAfter(c: Char?): Boolean =
        c == null || c.isWhitespace() || c in AFTER_OK

    /** 文本里是否 @ 了这个名字(大小写不敏感,带边界检测)。 */
    fun isMentioned(content: String, name: String): Boolean {
        if (content.isBlank() || name.isBlank()) return false
        val masked = maskQuotes(content)
        val lower = masked.lowercase()
        val target = "@" + name.lowercase()
        var from = 0
        while (from <= lower.length - target.length) {
            val at = lower.indexOf(target, from)
            if (at < 0) return false
            val end = at + target.length
            if (isBoundaryBefore(masked.getOrNull(at - 1)) && isBoundaryAfter(masked.getOrNull(end))) {
                return true
            }
            from = at + 1
        }
        return false
    }

    fun isAllMentioned(content: String): Boolean = isMentioned(content, ALL)

    /**
     * 解析出这条消息该叫醒谁。
     *
     * @param names      房间里所有成员的显示名
     * @param content    消息正文
     * @param senderName 发送者显示名(用户发的传空串)
     * @return 应当回复的成员名。没 @ 任何人时返回空——群里不该有人抢答。
     */
    fun resolveTargets(names: List<String>, content: String, senderName: String): List<String> {
        // 永远把发送者自己排除掉,这是防无限互相触发的关键一步
        val candidates = names.filter { !it.equals(senderName, ignoreCase = true) }
        if (isAllMentioned(content)) return candidates
        return candidates.filter { isMentioned(content, it) }
    }
}
