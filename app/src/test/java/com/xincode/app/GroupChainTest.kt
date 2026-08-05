package com.xincode.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊连锁的防失控测试。
 *
 * 这些闸门平时看不见 —— 只有在两个成员开始互相 @ 的时候才起作用,而那时候用户看到的
 * 只是「消息一直冒出来」。真出问题就是烧光额度,所以每道闸都必须有测试钉住。
 *
 * 全部脱离网络:speak 回调直接给假回复,测的是调度本身。
 */
class GroupChainTest {

    private val members = listOf("秘书", "工程师", "设计师")

    @Test
    fun noMentionMeansNobodySpeaks() = runTest {
        val spoke = mutableListOf<String>()
        val n = GroupRoomEngine.driveChain(
            memberNames = members,
            seedContent = "你们聊一下 xincode 这个产品",   // 截图里那句,没有 @
            seedSender = "",
            allowChain = true,
            maxHops = 3
        ) { name, _ -> spoke += name; GroupReply("好的", 0L) }

        assertEquals(0, n)
        assertTrue("没 @ 任何人却有人开口了: $spoke", spoke.isEmpty())
    }

    @Test
    fun mentionAllWakesEveryoneButSender() = runTest {
        val spoke = mutableListOf<String>()
        GroupRoomEngine.driveChain(
            memberNames = members,
            seedContent = "@all 都说说",
            seedSender = "",
            allowChain = false,   // 只看第一跳
            maxHops = 3
        ) { name, _ -> spoke += name; GroupReply("收到", 0L) }

        assertEquals(members.toSet(), spoke.toSet())
    }

    @Test
    fun chainStopsAtFirstHopWhenMemberMentionsDisabled() = runTest {
        val spoke = mutableListOf<String>()
        GroupRoomEngine.driveChain(
            memberNames = members,
            seedContent = "@秘书 组织一下",
            seedSender = "",
            allowChain = false,
            maxHops = 5
        ) { name, _ -> spoke += name; GroupReply("@工程师 你来说", 0L) }   // 秘书点名工程师,但开关关着

        assertEquals(listOf("秘书"), spoke)
    }

    @Test
    fun chainPropagatesWhenMemberMentionsEnabled() = runTest {
        val spoke = mutableListOf<String>()
        GroupRoomEngine.driveChain(
            memberNames = members,
            seedContent = "@秘书 组织一下",
            seedSender = "",
            allowChain = true,
            maxHops = 3
        ) { name, _ ->
            spoke += name
            // 秘书点工程师,工程师点设计师,设计师收尾不再点人
            when (name) {
                "秘书" -> GroupReply("@工程师 你先讲", 0L)
                "工程师" -> GroupReply("@设计师 你补充", 0L)
                else -> GroupReply("讲完了", 0L)
            }
        }

        assertEquals(listOf("秘书", "工程师", "设计师"), spoke)
    }

    @Test
    fun pingPongBetweenTwoMembersCannotLoopForever() = runTest {
        val spoke = mutableListOf<String>()
        val n = GroupRoomEngine.driveChain(
            memberNames = listOf("甲", "乙"),
            seedContent = "@甲 开始",
            seedSender = "",
            allowChain = true,
            maxHops = 8            // 故意给很深
        ) { name, _ ->
            spoke += name
            if (name == "甲") GroupReply("@乙 该你", 0L) else GroupReply("@甲 该你", 0L)   // 死循环剧本
        }

        // 单人次数闸把它按在 3 次以内,两人合计最多 6 条
        assertTrue("产生了 $n 条,闸门没拦住: $spoke", n <= 6)
        assertTrue(spoke.count { it == "甲" } <= 3)
        assertTrue(spoke.count { it == "乙" } <= 3)
    }

    @Test
    fun mentionAllEveryHopCannotExplode() = runTest {
        var count = 0
        val n = GroupRoomEngine.driveChain(
            memberNames = listOf("A", "B", "C", "D", "E", "F"),
            seedContent = "@all 开始",
            seedSender = "",
            allowChain = true,
            maxHops = 8
        ) { _, _ -> count++; GroupReply("@all 继续", 0L) }

        // 真正的硬顶来自单人次数闸:6 人 × 每人最多 3 次 = 18 条。
        // 总量闸 12 是「整批之间」检查的软上限:一条消息 @ 的所有成员要么全回、
        // 要么不回,最后一批可能略超 12,但永远到不了 18 的硬顶。
        assertTrue("产生了 $n 条,闸门没拦住", n <= 18)
        assertEquals(n, count)
    }

    @Test
    fun hopLimitOneWalksExactlyOneHop() = runTest {
        val spoke = mutableListOf<String>()
        GroupRoomEngine.driveChain(
            memberNames = members,
            seedContent = "@秘书 开始",
            seedSender = "",
            allowChain = true,
            maxHops = 1
        ) { name, _ -> spoke += name; GroupReply("@工程师 接着", 0L) }

        assertEquals(listOf("秘书"), spoke)
    }

    @Test
    fun speakerIsNeverWokenByOwnMention() = runTest {
        val spoke = mutableListOf<String>()
        GroupRoomEngine.driveChain(
            memberNames = members,
            seedContent = "@秘书 开始",
            seedSender = "",
            allowChain = true,
            maxHops = 5
        ) { name, _ -> spoke += name; GroupReply("@秘书 我觉得还是你来", 0L) }   // 秘书 @ 自己

        assertEquals(listOf("秘书"), spoke)
    }

    @Test
    fun blankReplyNeitherCountsNorPropagates() = runTest {
        val spoke = mutableListOf<String>()
        val n = GroupRoomEngine.driveChain(
            memberNames = members,
            seedContent = "@all 说话",
            seedSender = "",
            allowChain = true,
            maxHops = 3
        ) { name, _ -> spoke += name; GroupReply("", 0L) }

        assertEquals(0, n)
        assertEquals(members.size, spoke.size)   // 都被叫到了,只是都没说出东西
    }

    @Test
    fun unlimitedIgnoresHopAndTurnLimits() = runTest {
        val spoke = mutableListOf<String>()
        val n = GroupRoomEngine.driveChain(
            memberNames = listOf("甲", "乙"),
            seedContent = "@甲 开始",
            seedSender = "",
            allowChain = true,
            maxHops = 0            // 0 = 无上限
        ) { name, _ ->
            spoke += name
            // 让它自然收尾:跑够 40 轮后不再点人
            if (spoke.size >= 40) GroupReply("聊完了", 0L)
            else if (name == "甲") GroupReply("@乙 该你", 0L) else GroupReply("@甲 该你", 0L)
        }

        // 有上限时单人最多 3 次、总共最多 12 条;无上限必须能远远越过这条线
        assertEquals(40, n)
        assertTrue("单人次数闸在无上限下不该生效", spoke.count { it == "甲" } > 3)
    }

    @Test
    fun unlimitedStillHasRunawayCeiling() = runTest {
        var count = 0
        val n = GroupRoomEngine.driveChain(
            memberNames = listOf("甲", "乙"),
            seedContent = "@甲 开始",
            seedSender = "",
            allowChain = true,
            maxHops = 0
        ) { name, _ ->
            count++
            // 永不收尾的死循环剧本 —— 兜底不生效的话这个测试会跑不完
            if (name == "甲") GroupReply("@乙 该你", 0L) else GroupReply("@甲 该你", 0L)
        }

        assertEquals("跑飞兜底必须封顶,否则真实场景会一直烧额度", 500, n)
        assertEquals(n, count)
    }

    @Test
    fun cancellationBreaksChainBetweenTurns() = runTest {
        val spoke = mutableListOf<String>()
        var thrown: Throwable? = null
        try {
            GroupRoomEngine.driveChain(
                memberNames = members,
                seedContent = "@秘书 说话",
                seedSender = "",
                allowChain = true,
                maxHops = 3
            ) { name, _ ->
                spoke += name
                // 模拟用户在第一轮说到一半时点了停止(单目标,不会有并行竞态)
                if (spoke.size == 1) throw CancellationException("用户点了停止")
                GroupReply("好", 0L)
            }
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue("取消应当原样上抛,而不是被吞掉", thrown is CancellationException)
        assertEquals(1, spoke.size)
    }

    @Test
    fun mentionedMembersReplyConcurrently() = runTest {
        val started = mutableSetOf<String>()
        var sawOtherStarted = false
        GroupRoomEngine.driveChain(
            memberNames = listOf("甲", "乙"),
            seedContent = "@甲 @乙 一起回答",
            seedSender = "",
            allowChain = false,
            maxHops = 3
        ) { name, _ ->
            started += name
            // 甲挂起等待的这 50ms 里,并行调度应该已经让乙开始跑了
            delay(50)
            if (name == "甲") sawOtherStarted = "乙" in started
            GroupReply("好的", 0L)
        }

        assertTrue(
            "同一批被 @ 的成员应当并行发言,而不是一个等一个: started=$started",
            sawOtherStarted
        )
        assertEquals(setOf("甲", "乙"), started)
    }

    @Test
    fun repliesCarrySeedQuote() = runTest {
        val seen = mutableMapOf<String, GroupQuote?>()
        GroupRoomEngine.driveChain(
            memberNames = listOf("甲", "乙"),
            seedContent = "@甲 @乙 讨论方案",
            seedSender = "",
            allowChain = false,
            maxHops = 3,
            seedQuote = GroupQuote(42L, "", "@甲 @乙 讨论方案")
        ) { name, replyTo ->
            seen[name] = replyTo
            GroupReply("收到", 0L)
        }

        assertEquals(GroupQuote(42L, "", "@甲 @乙 讨论方案"), seen["甲"])
        assertEquals(GroupQuote(42L, "", "@甲 @乙 讨论方案"), seen["乙"])
    }

    @Test
    fun nextHopQuotePointsAtReplyingMessage() = runTest {
        val seen = mutableListOf<GroupQuote?>()
        GroupRoomEngine.driveChain(
            memberNames = listOf("甲", "乙"),
            seedContent = "@甲 开始",
            seedSender = "",
            allowChain = true,
            maxHops = 3
        ) { name, replyTo ->
            seen += replyTo
            if (name == "甲") GroupReply("@乙 接着说", 7L)
            else GroupReply("好的", 8L)
        }

        assertEquals(GroupQuote(7L, "甲", "@乙 接着说"), seen.last())
    }
}
