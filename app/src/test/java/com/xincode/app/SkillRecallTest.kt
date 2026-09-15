package com.xincode.app

import com.xincode.data.SkillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SkillRecall 场景匹配回归。跑法:`./gradlew :app:testDebugUnitTest`。 */
class SkillRecallTest {

    private fun skill(
        name: String,
        description: String,
        content: String = "用法正文",
        useCount: Int = 0
    ) = SkillEntity(
        name = name, description = description, content = content,
        state = "active", useCount = useCount
    )

    @Test
    fun nameHit_wins() {
        val skills = listOf(
            skill("联网搜索", "用搜索引擎查资料"),
            skill("root终端", "执行 root shell 命令")
        )
        assertEquals("root终端", SkillRecall.suggest(skills, "帮我用root终端看一下电池")!!.name)
    }

    @Test
    fun descriptionOverlap_matchesScenario() {
        val skills = listOf(
            skill("联网搜索", "用搜索引擎查最新资料新闻"),
            skill("root终端", "执行 root shell 命令")
        )
        // “搜索最新手机资料”与简介有“搜索/资料”两词交集 → 命中，不用点名。
        assertEquals("联网搜索", SkillRecall.suggest(skills, "搜索一下最新手机资料")!!.name)
    }

    @Test
    fun shortOrIrrelevant_noMatch() {
        val skills = listOf(skill("联网搜索", "用搜索引擎查资料"))
        assertNull(SkillRecall.suggest(skills, "你好"))
        assertNull(SkillRecall.suggest(skills, "今天天气怎么样出门带伞吗"))
        assertNull(SkillRecall.suggest(skills, "hi"))
    }

    @Test
    fun inactiveOrEmptyContent_skipped() {
        val skills = listOf(
            skill("联网搜索", "用搜索引擎查资料", content = "").copy(state = "archived"),
            skill("root终端", "执行命令", content = "")
        )
        assertNull(SkillRecall.suggest(skills, "用root终端执行命令查资料"))
    }

    @Test
    fun tieBrokenByUseCount() {
        val skills = listOf(
            skill("甲技能", "查询资料新闻", useCount = 1),
            skill("乙技能", "查询资料新闻", useCount = 9)
        )
        assertEquals("乙技能", SkillRecall.suggest(skills, "帮我查询资料新闻内容")!!.name)
    }

    @Test
    fun block_isIndexOnlyAndHintsInvokeSkill() {
        val skills = listOf(skill("root终端", "执行 root shell 命令", content = "先 su，再执行"))
        val block = SkillRecall.blockForQuery(skills, "用root终端重启手机")
        // 渐进披露：注入的是索引（名 + 摘要），**不含步骤正文**
        assertTrue("必须带技能名", block.contains("root终端"))
        assertTrue("必须带摘要", block.contains("执行 root shell 命令"))
        assertTrue("必须指引模型去取正文", block.contains("invoke_skill"))
        assertFalse("正文不得进上下文（那是渐进披露的意义）", block.contains("先 su"))
        // 命中成本应远小于旧的 1500 字全文
        assertTrue("索引块应很短，实际 ${block.length} 字", block.length < 300)
    }

    @Test
    fun block_descriptionIsClippedToBudget() {
        val long = "很长的技能描述".repeat(200) // 远超 250 字预算
        val skills = listOf(skill("大技能", long, content = "步骤"))
        val block = SkillRecall.blockForQuery(skills, "帮我用大技能干活")
        assertTrue(block.contains("大技能"))
        assertTrue(
            "描述必须被裁到 SKILL_DESC_MAX 以内（实际 ${block.length}）",
            block.length < SkillRecall.SKILL_DESC_MAX + 200
        )
    }
}
