package com.xincode.app

import android.util.Log
import com.xincode.core.AgentCore
import com.xincode.data.AppDatabase
import com.xincode.provider.JudgeService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Goal-driven agent loop with independent judge evaluation.
 *
 * Each iteration:
 *   1. Run AgentCore with goal instruction
 *   2. Judge evaluates if goal achieved (separate model call)
 *   3. If achieved → done; if not → inject judge feedback → next iteration
 *   4. Respect max iterations + total timeout
 */
class GoalRunner(
    // gap-18:用工厂构造【独立】AgentCore,避免复用主实例、clearHistory 清空主对话。
    private val agentCoreFactory: () -> AgentCore,
    private val database: AppDatabase,
    private val judgeApiKey: String,
    private val judgeBaseUrl: String,
    private val judgeModel: String
) {
    companion object {
        private const val TAG = "GoalRunner"
        private const val DEFAULT_MAX_ROUNDS = 5
        private const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L
    }

    /** Goal execution state. */
    sealed class GoalState {
        object Idle : GoalState()
        data class Running(val goal: String, val round: Int, val maxRounds: Int, val status: String) : GoalState()
        data class Achieved(val goal: String, val round: Int, val verdict: String) : GoalState()
        data class Failed(val goal: String, val round: Int, val reason: String) : GoalState()
        data class Error(val goal: String, val message: String) : GoalState()

        val isTerminal get() = this is Achieved || this is Failed || this is Error
        val isRunning get() = this is Running
    }

    private val _state = MutableStateFlow<GoalState>(GoalState.Idle)
    val state: StateFlow<GoalState> = _state.asStateFlow()

    /** Timeline of judge evaluations. */
    data class JudgeEvent(val round: Int, val achieved: Boolean, val confidence: Float, val verdict: String, val explanation: String)

    private val _judgeLog = MutableStateFlow<List<JudgeEvent>>(emptyList())
    val judgeLog: StateFlow<List<JudgeEvent>> = _judgeLog.asStateFlow()

    private var currentJob: Job? = null

    /**
     * Start a goal-driven execution.
     * @param goal the target to achieve
     * @param scope coroutine scope
     * @param maxRounds maximum iterations before giving up
     * @param timeoutMs total time limit
     * @param thinkingEnabled pass to AgentCore
     * @param thinkingLevel pass to AgentCore
     */
    fun run(
        goal: String,
        scope: CoroutineScope,
        maxRounds: Int = DEFAULT_MAX_ROUNDS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        thinkingEnabled: Boolean = false,
        thinkingLevel: Int = 2
    ): Job {
        if (_state.value.isRunning) {
            Log.w(TAG, "GoalRunner busy, ignoring")
            return Job()
        }

        _judgeLog.value = emptyList()
        _state.value = GoalState.Running(goal, 0, maxRounds, "初始化...")

        currentJob = scope.launch {
            try {
                withTimeout(timeoutMs) {
                    runGoalLoop(this, goal, maxRounds, thinkingEnabled, thinkingLevel)
                }
            } catch (e: TimeoutCancellationException) {
                _state.value = GoalState.Failed(goal, 0, "总超时 ($timeoutMs)")
            } catch (e: CancellationException) {
                _state.value = GoalState.Failed(goal, 0, "用户中断")
            } catch (e: Exception) {
                Log.e(TAG, "Goal loop exception: ${e.message}", e)
                _state.value = GoalState.Error(goal, e.message ?: "未知错误")
            } finally {
                currentJob = null
            }
        }
        return currentJob!!
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        val current = _state.value
        if (current is GoalState.Running) {
            _state.value = GoalState.Failed(current.goal, current.round, "用户中断")
        }
    }

    private suspend fun runGoalLoop(
        scope: CoroutineScope,
        goal: String,
        maxRounds: Int,
        thinkingEnabled: Boolean,
        thinkingLevel: Int
    ) {
        // gap-18:每次 goal 运行使用独立 AgentCore 实例,迭代历史留在该实例内,不触碰主对话。
        val core = agentCoreFactory()

        // Create judge with current config
        val judge = JudgeService(
            baseUrl = judgeBaseUrl,
            apiKey = judgeApiKey,
            model = judgeModel
        )

        // Compose the goal prompt — tells Agent to work toward the goal
        val goalPrompt = buildString {
            appendLine("你正在执行一个目标驱动任务。以下是你的目标：")
            appendLine()
            appendLine("目标: $goal")
            appendLine()
            appendLine("请使用你所有可用工具来达成这个目标。完成后明确说明你做了什么以及结果。")
        }

        var round = 0
        var lastOutput = ""

        while (round < maxRounds) {
            round++
            _state.value = GoalState.Running(goal, round, maxRounds, "第${round}轮: 执行中...")

            Log.i(TAG, "Goal round $round/$maxRounds")

            // Build per-round prompt (first round = goal, later rounds = goal + judge feedback)
            val prompt = if (round == 1) {
                goalPrompt
            } else {
                val lastJudge = _judgeLog.value.lastOrNull()
                buildString {
                    appendLine(goalPrompt)
                    appendLine()
                    appendLine("裁判评估（第${round - 1}轮）：")
                    appendLine("结论: ${lastJudge?.verdict}")
                    appendLine("说明: ${lastJudge?.explanation}")
                    appendLine()
                    appendLine("请根据裁判的反馈调整策略，再次尝试达成目标。")
                }
            }

            // gap-18:在【独立】core 上运行本轮,并发采集其 tokenFlow 作为输出(不读全局 DB、不清主对话)。
            core.clearHistory()
            val buf = StringBuilder()
            kotlinx.coroutines.coroutineScope {
                val collector = launch { core.tokenFlow.collect { buf.append(it) } }
                val agentJob = core.run(prompt, scope = this, thinkingEnabled = thinkingEnabled, thinkingLevel = thinkingLevel)
                agentJob.join()
                collector.cancel()
            }
            lastOutput = buf.toString().ifBlank { "(无输出)" }

            Log.i(TAG, "Agent output (round $round): ${lastOutput.take(200)}")

            _state.value = GoalState.Running(goal, round, maxRounds, "第${round}轮: 裁判评估中...")

            // Judge evaluation
            val judgeResult = judge.judgePanel(goal, lastOutput, round, voters = 3) // gap-25 多裁判面板

            if (judgeResult == null) {
                Log.w(TAG, "Judge API failed, continuing")
                _judgeLog.value = _judgeLog.value + JudgeEvent(round, false, 0f, "API_ERROR", "裁判API调用失败")
                // Continue to next round rather than stopping
                continue
            }

            _judgeLog.value = _judgeLog.value + JudgeEvent(
                round = round,
                achieved = judgeResult.achieved,
                confidence = judgeResult.confidence,
                verdict = judgeResult.verdict,
                explanation = judgeResult.explanation
            )

            Log.i(TAG, "Judge: achieved=${judgeResult.achieved} confidence=${judgeResult.confidence} verdict=${judgeResult.verdict}")

            if (judgeResult.achieved) {
                _state.value = GoalState.Achieved(goal, round, judgeResult.explanation)
                return
            }

            // Not achieved — loop continues
        }

        // Exhausted max rounds
        val lastJudge = _judgeLog.value.lastOrNull()
        _state.value = GoalState.Failed(
            goal, round,
            "已达最大轮数 ($maxRounds)。裁判最终评估: ${lastJudge?.explanation ?: "无"}"
        )
    }

    /**
     * Capture the agent's last assistant message content from the database.
     * tokenFlow is exhausted after agentJob.join(), so we read from Room instead.
     */
    private suspend fun captureLastOutput(): String {
        return withContext(Dispatchers.IO) {
            try {
                val messages = database.messageDao().getAll()
                // Find the last assistant message
                val lastAssistant = messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
                lastAssistant?.content ?: "(无输出)"
            } catch (e: Exception) {
                Log.w(TAG, "captureLastOutput failed: ${e.message}")
                "(读取失败)"
            }
        }
    }
}