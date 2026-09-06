package com.xincode.app

import com.xincode.data.SkillEntity
import org.junit.Assert.assertEquals
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
    fun block_containsContentAndHint() {
        val skills = listOf(skill("root终端", "执行 root shell 命令", content = "先 su，再执行"))
        val block = SkillRecall.blockForQuery(skills, "用root终端重启手机")
        assertTrue(block.contains("root终端") && block.contains("先 su") && block.contains("invoke_skill"))
    }
}
