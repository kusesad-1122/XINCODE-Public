package com.xincode.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `agent_plan` 的 op=done/fail 缺 id 时该落到哪一步。
 *
 * 背景:原来缺 id 直接报「op=done 需要 id 参数」。模型刚 advance 完这一步,说「做完了」时
 * 不会再重复一遍步骤号,于是原样重试 → 连错三次被防空转刹车掐掉 → 计划卡永远停在半路。
 * 而缺 id 时目标本来就毫无歧义,就是当前进行中的那一步。
 */
class PlanStepFallbackTest {

    @Test
    fun fallsBackToInProgressStep() {
        val s = PlanState()
        s.setPlan("t", listOf("一", "二", "三"))
        assertEquals(1, s.advance())
        assertEquals(1, s.currentStepId())
        s.updateStep(1, PlanStepStatus.DONE)
        assertEquals(2, s.advance())
        assertEquals(2, s.currentStepId())
    }

    @Test
    fun fallsBackToFirstPendingWhenNothingInProgress() {
        // 模型跳过了 advance 直接说「第一步做完了」:落到第一个没做的,而不是报错。
        val s = PlanState()
        s.setPlan("t", listOf("一", "二"))
        assertEquals(1, s.currentStepId())
    }

    @Test
    fun returnsMinusOneWhenNoPlanOrAllDone() {
        val s = PlanState()
        assertEquals(-1, s.currentStepId())   // 压根没发布计划
        s.setPlan("t", listOf("一"))
        s.updateStep(1, PlanStepStatus.DONE)
        assertEquals(-1, s.currentStepId())   // 全做完了 —— 这时报错才是有信息量的
    }

    @Test
    fun failedStepIsNotPickedUpAgain() {
        // 已标记失败的步骤不该被下一次缺 id 的 done 又捡回来改成完成。
        val s = PlanState()
        s.setPlan("t", listOf("一", "二"))
        s.updateStep(1, PlanStepStatus.FAILED)
        assertEquals(2, s.currentStepId())
    }
}
