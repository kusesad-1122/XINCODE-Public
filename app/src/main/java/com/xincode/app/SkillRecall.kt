package com.xincode.app

import com.xincode.data.SkillEntity

/**
 * 场景技能自动引用（学习借鉴 Codex Skills 的渐进披露）。
 *
 * Codex 做法：系统提示只带技能目录（名+简介），运行时按场景命中后再展开全文。
 * 这里同构：每回合用用户输入匹配一次 active 技能，命中则把全文（截断）作为
 * 本回合附加上下文（走 recallBlock 同款“不写回冻结前缀”通道，保前缀缓存），
 * 未命中则零成本——模型照常靠 invoke_skill 精确名调用。
 *
 * 匹配规则（纯本地计算，无模型调用）：
 * - 名含查询或查询含名：+5（用户直呼其名最优先）；
 * - 简介词元命中：每词 +1（封顶 6），中英文分词（英文词+CJK 二元）；
 * - 总分 ≥3 且简介至少中 1 个词才算命中（中文两字一词，3 交集≈5 共享字，实测够准；
 *   短句靠简介 0 交集拦，外加查询 <4 字直接跳过）；
 * - 并列按 useCount 取常用者。
 */
object SkillRecall {

    /**
     * 单条技能建索引时的描述上限。对齐 Claude Code 渐进披露的 250 字预算：
     * 清单只给"这技能是干什么的"，步骤正文按需展开。
     */
    const val SKILL_DESC_MAX = 250

    /**
     * 本回合附加块；空字符串 = 无命中（调用方直接跳过）。
     *
     * **只注入索引级信息**（技能名 + 摘要 ≤[SKILL_DESC_MAX] 字），不注正文 —— 这才是渐进披露。
     * 正文由模型按需 `invoke_skill("<name>")` 展开（该工具返回技能全文）。
     *
     * 为什么改：之前这里注的是 `content.take(1500)` 全文，与"渐进披露"名不符实 ——
     * 命中一次就吃掉 1500 字上下文预算，而多数回合其实只是"知道有这么个技能"就够了。
     * 现在是「摘要常驻、正文按需」，命中成本从 ~1500 字降到 ~250 字。
     */
    fun blockForQuery(skills: List<SkillEntity>, query: String): String {
        val hit = suggest(skills, query) ?: return ""
        val desc = hit.description.trim().replace(Regex("""\s+"""), " ").take(SKILL_DESC_MAX)
        if (desc.isBlank() && hit.name.isBlank()) return ""
        return buildString {
            append("[场景技能命中:").append(hit.name).append("]")
            if (desc.isNotBlank()) append(" ").append(desc)
            append("\n(以上只是索引,不含步骤。需要照着做时请先 invoke_skill(\"")
            append(hit.name)
            append("\") 取完整正文,不要凭摘要猜步骤。)")
        }
    }

    fun suggest(skills: List<SkillEntity>, query: String): SkillEntity? {
        val q = query.trim()
        if (q.length < 4) return null
        val qTokens = tokens(q)
        if (qTokens.isEmpty()) return null
        var best: SkillEntity? = null
        var bestScore = 0
        for (s in skills) {
            if (s.state != "active" || s.content.isBlank()) continue
            var score = 0
            val name = s.name.trim()
            // 名字命中最强，但单字名不算（含一个单字哪儿都是命中，纯噪音）。
            if (name.length >= 2 && (q.contains(name, ignoreCase = true) || name.contains(q, ignoreCase = true))) {
                score += 5
            }
            val descHits = tokens(s.description).intersect(qTokens).size.coerceAtMost(6)
            if (descHits == 0 && score == 0) continue
            score += descHits
            // 纯名字分不够：简介至少中 1 词（短名误 containing 如“a”不算，length<4 已拦）。
            if (descHits == 0 && score < 5) continue
            if (score < 3) continue
            if (score > bestScore || (score == bestScore && s.useCount > (best?.useCount ?: -1))) {
                best = s
                bestScore = score
            }
        }
        return best
    }

    /** 英文词（≥2字母）+ CJK 二元，供简介匹配。 */
    fun tokens(s: String): Set<String> {
        val lower = s.lowercase()
        val out = mutableSetOf<String>()
        Regex("[a-z0-9]+").findAll(lower).forEach {
            if (it.value.length >= 2) out.add(it.value)
        }
        val cjk = lower.filter { it in '一'..'鿿' }
        for (i in 0 until cjk.length - 1) out.add(cjk.substring(i, i + 2))
        return out
    }
}
