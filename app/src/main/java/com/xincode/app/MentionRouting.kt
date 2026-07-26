package com.xincode.app

/**
 * 群聊 `@名字` 路由。
 *
 * 看着简单,但有几条不这么写就会出事:
 *
 *  1. **必须排除发送者自己**。两个智能体一旦在回复里带上对方的 @,就会你来我往地
 *     无限对话下去,烧到没额度为止。这是这套机制最危险的一处。
 *  2. **引用块里的 @ 要屏蔽**。回复里引用了别人的原话,不该把被引用者再叫起来一次。
 *  3. **边界规则必须适配中文**。这里踩过一次坑:原先要求 @ 前面是空白或括号,
 *     结果「他问你呢@前端设计师」判成不命中 —— 中文打字根本不会在 @ 前敲空格,
 *     于是用户 @ 了没人应,群聊看着就像卡死了。现在只挡 ASCII 词字符
 *     (那是为了不把 `foo@example.com` 当成 @ 某人),中文一律放行。
 *  4. **长名字优先**。成员名互为前缀时(「设计」和「前端设计师」同时在房间里),
 *     先匹配长的,命中后把那段抹掉,短的就不会从同一处再误命中。
 *  5. `@all` / `@所有人` 叫醒全部成员(除了发送者)。
 */
object MentionRouting {

    const val ALL = "all"

    /** `@所有人` 的各种写法。中文用户不会去打 @all,只认这一个等于没有这功能。 */
    private val ALL_ALIASES = listOf("all", "所有人", "全体", "大家", "everyone")

    /** 引用块。屏蔽其中的 @ —— 用等长空格替换,保持后面所有下标不变。 */
    private val QUOTE_BLOCK = Regex("""<quote(?:\s[^>]*)?>[\s\S]*?</quote>""", RegexOption.IGNORE_CASE)

    private fun maskQuotes(content: String): String =
        QUOTE_BLOCK.replace(content) { m -> m.value.replace(Regex("[^\n]"), " ") }

    /**
     * ASCII 词字符。只有这些字符会破坏 @ 的边界。
     *
     * 【绝不能用 Char.isLetterOrDigit()】—— 汉字在它眼里也是字母,一用就把
     * 「呢@某人」这种最常见的中文写法判死。这正是之前那个 bug 的成因。
     */
    private fun isAsciiWord(c: Char?): Boolean =
        c != null && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9')

    /** @ 之前:不能紧跟 ASCII 词字符或邮箱里的连接符,否则 foo@example.com 会被当成 @ 某人。 */
    private fun okBefore(c: Char?): Boolean =
        c == null || !(isAsciiWord(c) || c == '.' || c == '_' || c == '-' || c == '@')

    /** 名字之后:不能紧跟 ASCII 词字符或下划线,否则 @user 会误命中 @user123。中文一律放行。 */
    private fun okAfter(c: Char?): Boolean =
        c == null || !(isAsciiWord(c) || c == '_')

    /** 在 [text] 里找 `@name`,返回起始下标;找不到返回 -1。 */
    private fun indexOfMention(text: String, name: String): Int {
        if (name.isBlank()) return -1
        val lower = text.lowercase()
        val target = "@" + name.lowercase()
        var i = 0
        while (i <= lower.length - target.length) {
            val at = lower.indexOf(target, i)
            if (at < 0) return -1
            val end = at + target.length
            if (okBefore(text.getOrNull(at - 1)) && okAfter(text.getOrNull(end))) return at
            i = at + 1
        }
        return -1
    }

    /** 文本里是否 @ 了这个名字(大小写不敏感,带边界检测)。 */
    fun isMentioned(content: String, name: String): Boolean {
        if (content.isBlank() || name.isBlank()) return false
        return indexOfMention(maskQuotes(content), name) >= 0
    }

    /** 是否 @ 了全体。 */
    fun isAllMentioned(content: String): Boolean {
        if (content.isBlank()) return false
        val masked = maskQuotes(content)
        return ALL_ALIASES.any { indexOfMention(masked, it) >= 0 }
    }

    /**
     * 解析出这条消息该叫醒谁。
     *
     * @param names      房间里所有成员的显示名
     * @param content    消息正文
     * @param senderName 发送者显示名(用户发的传空串)
     * @return 应当回复的成员名,顺序与 [names] 一致。没 @ 任何人时返回空 ——
     *         群里不该有人抢答。
     */
    fun resolveTargets(names: List<String>, content: String, senderName: String): List<String> {
        // 永远把发送者自己排除掉,这是防无限互相触发的关键一步
        val candidates = names.filter { !it.equals(senderName, ignoreCase = true) }
        if (candidates.isEmpty()) return emptyList()
        if (isAllMentioned(content)) return candidates

        // 长名字先匹配,命中后把那一段抹成空格,短名字就不会从同一处再命中一次。
        // 否则房间里同时有「设计」和「前端设计师」时,一句 @前端设计师 会叫醒两个人。
        val buffer = StringBuilder(maskQuotes(content))
        val hit = mutableSetOf<String>()
        for (name in candidates.sortedByDescending { it.length }) {
            val at = indexOfMention(buffer.toString(), name)
            if (at >= 0) {
                hit += name
                val end = minOf(at + name.length + 1, buffer.length)
                for (i in at until end) buffer.setCharAt(i, ' ')
            }
        }
        return candidates.filter { it in hit }
    }
}
