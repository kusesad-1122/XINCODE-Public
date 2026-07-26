package com.xincode.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * @ 路由测试。
 *
 * 这里出过一个很贵的 bug:边界规则要求 @ 前面必须是空白或括号,于是中文里最常见的
 * 「他问你呢@某人」判成不命中 —— 用户 @ 了没人应,群聊看着就像卡死了,而日志里
 * 什么错都没有。所以中文写法必须逐个钉死。
 */
class MentionRoutingTest {

    private val members = listOf("秘书助理", "产品经理", "架构师", "工程师", "前端设计师", "测试工程师")

    // ---- 中文场景:之前全是漏的 ----

    @Test
    fun mentionRightAfterChineseChar() {
        // 用户真实发过的那句,之前判不命中
        assertTrue(MentionRouting.isMentioned("他问你呢@前端设计师", "前端设计师"))
    }

    @Test
    fun mentionFollowedByChineseChar() {
        // 名字后面直接跟中文,也是中文里的常态
        assertTrue(MentionRouting.isMentioned("@前端设计师可以开始出交互稿了", "前端设计师"))
    }

    @Test
    fun mentionSurroundedByChinese() {
        assertTrue(MentionRouting.isMentioned("那么请@架构师确认一下这个方案", "架构师"))
    }

    @Test
    fun mentionAfterChinesePunctuation() {
        assertTrue(MentionRouting.isMentioned("好的,@工程师你来估个工期", "工程师"))
        assertTrue(MentionRouting.isMentioned("先这样。@测试工程师补充", "测试工程师"))
    }

    // ---- 仍要挡住的 ----

    @Test
    fun emailIsNotAMention() {
        // foo@工程师.com 这种不该把人叫起来
        assertFalse(MentionRouting.isMentioned("联系 foo@工程师", "工程师"))
        assertFalse(MentionRouting.isMentioned("发到 admin@example.com", "example"))
    }

    @Test
    fun asciiNameNotMatchedInsideLongerWord() {
        assertFalse(MentionRouting.isMentioned("@bot123 在吗", "bot"))
        assertTrue(MentionRouting.isMentioned("@bot 在吗", "bot"))
    }

    @Test
    fun quotedMentionIsIgnored() {
        val text = "<quote>之前 @工程师 说过</quote>我同意"
        assertFalse("引用块里的 @ 不该把人叫起来", MentionRouting.isMentioned(text, "工程师"))
    }

    // ---- 长名字优先 ----

    @Test
    fun longerNameWinsOverPrefix() {
        val names = listOf("设计", "前端设计师")
        val targets = MentionRouting.resolveTargets(names, "@前端设计师 你来", "")
        assertEquals(listOf("前端设计师"), targets)
    }

    @Test
    fun engineerVsTestEngineerAreDistinct() {
        // 「工程师」是「测试工程师」的后缀,不能互相误命中
        assertEquals(
            listOf("测试工程师"),
            MentionRouting.resolveTargets(members, "@测试工程师 你说", "")
        )
        assertEquals(
            listOf("工程师"),
            MentionRouting.resolveTargets(members, "@工程师 你说", "")
        )
    }

    // ---- @所有人 ----

    @Test
    fun chineseAllAliasesWork() {
        for (alias in listOf("@所有人", "@全体", "@大家", "@all")) {
            assertTrue("$alias 应当叫醒全体", MentionRouting.isAllMentioned("$alias 都说说"))
        }
    }

    @Test
    fun allExcludesSender() {
        val targets = MentionRouting.resolveTargets(members, "@所有人 说说", "架构师")
        assertFalse("发送者不该被自己的 @所有人 叫醒", targets.contains("架构师"))
        assertEquals(members.size - 1, targets.size)
    }

    // ---- 基本约束 ----

    @Test
    fun senderIsAlwaysExcluded() {
        val targets = MentionRouting.resolveTargets(members, "@工程师 我觉得还是你来", "工程师")
        assertTrue("发送者 @ 自己不该触发", targets.isEmpty())
    }

    @Test
    fun noMentionMeansNoTargets() {
        assertTrue(MentionRouting.resolveTargets(members, "你们聊一下这个产品", "").isEmpty())
    }

    @Test
    fun multipleMentionsAllResolve() {
        val targets = MentionRouting.resolveTargets(
            members, "@工程师 @架构师 @前端设计师 @产品经理", ""
        )
        assertEquals(setOf("工程师", "架构师", "前端设计师", "产品经理"), targets.toSet())
    }

    @Test
    fun caseInsensitiveForAscii() {
        assertTrue(MentionRouting.isMentioned("@Alice 在吗", "alice"))
        assertTrue(MentionRouting.isMentioned("@alice 在吗", "Alice"))
    }
}
