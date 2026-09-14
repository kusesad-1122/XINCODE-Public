package com.xincode.app

import android.util.Log
import com.xincode.core.AgentCore
import com.xincode.tools.WorkspaceContext
import com.xincode.tools.WorkspaceThreadElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hermes-① 自进化学习闭环的执行器。
 *
 * 收到 [AgentCore.onBackgroundReview] 回调后,用 [reviewCoreFactory] 造一个**隔离**的复盘分身
 * (受限工具集:save_memory + skill_manage + recall/invoke + 只读环境探针 file_read/list_dir/glob/grep,
 * isReviewFork=true 不再递归),喂给它一段复盘提示 + 对话尾部,让它按
 * **propose–probe–commit** 三段策展:先提炼候选记忆,再用只读探针现场核实,
 * 核实通过才写盘——记忆落盘那一刻就是「可执行的」,而不是「看起来对的」。
 * 全程在后台、回答已交付之后运行,失败静默(学习闭环不该影响主流程)。
 */
class BackgroundReviewRunner(
    private val reviewCoreFactory: () -> AgentCore
) {
    companion object {
        private const val TAG = "BgReview"
        private const val REVIEW_TIMEOUT_MS = 90_000L
        private const val SKILL_IMPROVE_THRESHOLD = 5
        private const val SKILL_IMPROVE_COOLDOWN_MS = 6L * 60 * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val skillImprovementCooldown = HashMap<String, Long>()

    fun onReview(
        reviewMemory: Boolean,
        reviewSkill: Boolean,
        conversationTail: String,
        workspaceRoot: String = WorkspaceContext.workspaceRoot,
        projectId: Long = WorkspaceContext.projectId
    ) {
        scope.launch(WorkspaceThreadElement { workspaceRoot to projectId }) {
            runReview(buildPrompt(reviewMemory, reviewSkill, conversationTail))
        }
    }

    /**
     * 技能「用后自改进」:agent/user 技能每被命中 5 次(5/10/15…)触发一次后台复查,
     * 让复盘分身基于当前技能内容判断是否需要 patch。bundled 由调用方过滤,这里不重复判断。
     */
    fun onSkillImprovement(
        skillName: String,
        skillContent: String,
        useCount: Int,
        workspaceRoot: String = WorkspaceContext.workspaceRoot,
        projectId: Long = WorkspaceContext.projectId
    ) {
        if (useCount < SKILL_IMPROVE_THRESHOLD || useCount % SKILL_IMPROVE_THRESHOLD != 0) return
        val now = System.currentTimeMillis()
        val last = skillImprovementCooldown[skillName] ?: 0L
        if (now - last < SKILL_IMPROVE_COOLDOWN_MS) return
        skillImprovementCooldown[skillName] = now
        scope.launch(WorkspaceThreadElement { workspaceRoot to projectId }) {
            runReview(buildImprovePrompt(skillName, skillContent))
        }
    }

    private suspend fun runReview(prompt: String) {
        try {
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
            Log.i(TAG, "background review done")
        } catch (e: Exception) {
            Log.w(TAG, "background review error: ${e.message}")
        }
    }

    private fun buildPrompt(reviewMemory: Boolean, reviewSkill: Boolean, tail: String): String = buildString {
        appendLine("你是一个【后台复盘】分身,正在用户回答之后独立复盘刚才的对话。只做下面要求的事,做完用一句话总结即可。")
        appendLine()
        if (reviewMemory) {
            appendLine("【记忆复盘】按 propose–probe–commit 三段执行:")
            appendLine()
            appendLine("1) Propose(提炼):从对话尾部提炼候选记忆——耐久画像、当前近况、可复用的可执行经验。")
            appendLine("2) Probe(核实,写前必做):你手上有 file_read/glob/grep/list_dir 一组只读探针。")
            appendLine("   - 带着假设去探,不为探而探、不为未来任务探。")
            appendLine("   - 候选记忆若涉及文件路径/符号名/命令/schema,必须先现场核实它【现在】仍然成立:")
            appendLine("     路径存在吗?符号还在吗?有没有更短路径能得到同样结果?前置条件都满足吗?")
            appendLine("   - 区分偶然答案与可复用关系;怀疑环境已漂移时重新查询当前环境,并顺手把已知过期的")
            appendLine("     旧记忆用 save_memory(action=replace/remove) 订正、收窄或删除,不要留给下游误导。")
            appendLine("   - 核实不成立:改为记录当前真实状态,或直接不记。")
            appendLine("   - 与记忆/事实无关的主观偏好(用户画像类)不需要探针核实。")
            appendLine("3) Commit(落盘):只写【可执行】的记忆,不写【看起来对的】记忆:")
            appendLine("   - 好的写法是「适用范围 + 可执行过程」:从哪里查、怎么关联、过滤条件是什么、当前状态是什么。")
            appendLine("     例:『gradle 依赖改动在 app/build.gradle.kts;改后跑 ./gradlew :app:tasks 验证。』")
            appendLine("   - 坏的写法是答案锚定的警告:只说『X 是错的/不对』却不给当前正确做法——这种一律不落盘。")
            appendLine("   - 绝不记任务答案本身;沉淀的是过程、关系、约定和带范围的警告。")
            appendLine("   - 落盘目标:耐久画像 → save_memory(target=user);当前近况 → save_memory(target=situation);")
            appendLine("     可检索的可执行经验 → save_memory(target=note)。")
            appendLine("   - 什么都不值得记就直接说“无需沉淀”。不要记一次性、环境相关或否定性的失败结论。")
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

    private fun buildImprovePrompt(skillName: String, skillContent: String): String = buildString {
        appendLine("你是一个【技能自改进】分身。下面是一个已存在、且刚被使用过的技能,请判断它是否需要改进。")
        appendLine()
        appendLine("规则:")
        appendLine("- 只能改进这个技能本身(skill_manage: 先 action=view 读当前内容,再 action=patch 做外科式增量改进)。")
        appendLine("- 不要改名、不要整体重写、不要删除技能。")
        appendLine("- 不要凭空添加源里不存在的命令/API/路径;只补实际踩过的坑、订正错误步骤。")
        appendLine("- 如果内容已经足够好,直接说「无需改进」,不要为了改而改。")
        appendLine("- 完成后用一句话总结改了什么(或为什么没改)。")
        appendLine()
        appendLine("技能名: $skillName")
        appendLine()
        appendLine("当前技能内容:")
        appendLine("----")
        appendLine(skillContent.take(6000))
        appendLine("----")
    }
}
