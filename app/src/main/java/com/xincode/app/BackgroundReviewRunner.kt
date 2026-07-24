package com.xincode.app

import android.util.Log
import com.xincode.core.AgentCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hermes-① 自进化学习闭环的执行器。
 *
 * 收到 [AgentCore.onBackgroundReview] 回调后,用 [reviewCoreFactory] 造一个**隔离**的复盘分身
 * (受限工具集:save_memory + skill_manage + recall/invoke,isReviewFork=true 不再递归),
 * 喂给它一段复盘提示 + 对话尾部,让它**自主判断**该不该沉淀记忆 / 改进或新建技能。
 * 全程在后台、回答已交付之后运行,失败静默(学习闭环不该影响主流程)。
 */
class BackgroundReviewRunner(
    private val reviewCoreFactory: () -> AgentCore
) {
    companion object {
        private const val TAG = "BgReview"
        private const val REVIEW_TIMEOUT_MS = 90_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onReview(reviewMemory: Boolean, reviewSkill: Boolean, conversationTail: String) {
        scope.launch {
            try {
                val prompt = buildPrompt(reviewMemory, reviewSkill, conversationTail)
                val core = reviewCoreFactory()
                core.isReviewFork = true
                core.clearHistory()
                withTimeoutOrNull(REVIEW_TIMEOUT_MS) {
                    kotlinx.coroutines.coroutineScope {
                        // 复盘分身只调工具、不需要展示输出;把 tokenFlow 排空避免背压。
                        val drain = launch { core.tokenFlow.collect { } }
                        val job = core.run(prompt, scope = this, thinkingEnabled = false, thinkingLevel = 0)
                        job.join()
                        drain.cancel()
                    }
                }
                Log.i(TAG, "background review done (memory=$reviewMemory skill=$reviewSkill)")
            } catch (e: Exception) {
                Log.w(TAG, "background review error: ${e.message}")
            }
        }
    }

    private fun buildPrompt(reviewMemory: Boolean, reviewSkill: Boolean, tail: String): String = buildString {
        appendLine("你是一个【后台复盘】分身,正在用户回答之后独立复盘刚才的对话。只做下面要求的事,做完用一句话总结即可。")
        appendLine()
        if (reviewMemory) {
            appendLine("【记忆复盘】用户是否透露了值得长期记住的画像/偏好/个人信息?是否表达了对你行为方式的期望?")
            appendLine("- 若有耐久画像 → save_memory(target=user)。")
            appendLine("- 若是当前进行中的事/近况 → save_memory(target=situation)。")
            appendLine("- 若是可检索的知识点 → save_memory(target=note)。")
            appendLine("- 什么都不值得记就直接说“无需沉淀”。不要记一次性、环境相关或否定性的失败结论。")
            appendLine()
        }
        if (reviewSkill) {
            appendLine("【技能复盘】这次任务里有没有可复用的做法值得固化?")
            appendLine("- 优先 skill_manage(action=patch) 改进这次实际用到的、已存在的技能(先 view 再 patch)。")
            appendLine("- 只有当没有任何技能覆盖该【类】任务时,才 skill_manage(action=create) 新建。")
            appendLine("- 不要用 PR 号/报错串命名;不要固化环境相关的失败。多数会话至少有一处技能值得更新。")
            appendLine()
        }
        appendLine("以下是刚才对话的尾部,供你判断:")
        appendLine("----")
        appendLine(tail.take(6000))
        appendLine("----")
    }
}
