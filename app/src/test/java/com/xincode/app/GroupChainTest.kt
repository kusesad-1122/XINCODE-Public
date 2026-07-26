package com.xincode.app

import kotlinx.coroutines.CancellationException
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
        ) { spoke += it; "好的" }

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
        ) { spoke += it; "收到" }

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
        ) { spoke += it; "@工程师 你来说" }   // 秘书点名工程师,但开关关着

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
        ) { name ->
            spoke += name
            // 秘书点工程师,工程师点设计师,设计师收尾不再点人
            when (name) {
                "秘书" -> "@工程师 你先讲"
                "工程师" -> "@设计师 你补充"
                else -> "讲完了"
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
        ) { name ->
            spoke += name
            if (name == "甲") "@乙 该你" else "@甲 该你"   // 死循环剧本
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
        ) { count++; "@all 继续" }

        // 光靠跳数闸的话这里会是 6+36+216… 总量闸必须兜住
        assertTrue("产生了 $n 条,总量闸没起作用", n <= 12)
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
        ) { spoke += it; "@工程师 接着" }

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
        ) { name -> spoke += name; "@秘书 我觉得还是你来" }   // 秘书 @ 自己

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
        ) { spoke += it; "" }

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
        ) { name ->
            spoke += name
            // 让它自然收尾:跑够 40 轮后不再点人
            if (spoke.size >= 40) "聊完了"
            else if (name == "甲") "@乙 该你" else "@甲 该你"
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
        ) { name ->
            count++
            // 永不收尾的死循环剧本 —— 兜底不生效的话这个测试会跑不完
            if (name == "甲") "@乙 该你" else "@甲 该你"
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
                seedContent = "@all 说话",
                seedSender = "",
                allowChain = true,
                maxHops = 3
            ) { name ->
                spoke += name
                // 模拟用户在第一个人说完后点了停止
                if (spoke.size == 1) throw CancellationException("用户点了停止")
                "好"
            }
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue("取消应当原样上抛,而不是被吞掉", thrown is CancellationException)
        assertEquals(1, spoke.size)
    }
}
