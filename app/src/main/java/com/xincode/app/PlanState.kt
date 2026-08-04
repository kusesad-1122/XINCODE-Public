package com.xincode.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import java.util.concurrent.ConcurrentHashMap

/**
 * A single step in the model's live task plan. Immutable — mutations replace the whole
 * item in the [SnapshotStateList] so Compose animates the change.
 */
data class PlanStep(
    val id: Int,
    val text: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING
)

enum class PlanStepStatus { PENDING, IN_PROGRESS, DONE, FAILED }

/**
 * Application-scoped live task-plan surface for the running agent.
 *
 * Backed by [SnapshotStateList] so any Composable observing [steps] recomposes on
 * every mutation. The `agent_plan` tool writes here; the UI card in ChatScreen reads.
 *
 * We deliberately do NOT persist plan state to Room — a plan is a per-turn scratchpad,
 * and stale plans from old turns would just confuse the UI.
 */
class PlanState {
    val steps: SnapshotStateList<PlanStep> = mutableStateListOf()

    /** Timestamp of last mutation — used to drive the pulse animation on the summary bar. */
    var lastUpdatedMs by mutableStateOf(0L)
        private set

    /** Title shown at the top of the card. Set by the tool's first call. */
    var title by mutableStateOf("")
        private set

    /** True while the plan card should be visible on screen. Cleared by `agent_plan reset`. */
    var visible by mutableStateOf(false)
        private set

    /**
     * Replace the entire plan. Called when the agent commits to a fresh plan (op="set").
     * @param items pairs of (step text, initial status)
     */
    fun setPlan(newTitle: String, items: List<String>) {
        title = newTitle.ifBlank { "任务计划" }
        steps.clear()
        items.forEachIndexed { i, t ->
            steps.add(PlanStep(id = i + 1, text = t, status = PlanStepStatus.PENDING))
        }
        visible = true
        lastUpdatedMs = System.currentTimeMillis()
    }

    /** Update status of a single step by 1-indexed id. No-op if id is out of range. */
    fun updateStep(id: Int, status: PlanStepStatus) {
        val idx = steps.indexOfFirst { it.id == id }
        if (idx < 0) return
        steps[idx] = steps[idx].copy(status = status)
        lastUpdatedMs = System.currentTimeMillis()
    }

    /** Mark the next PENDING step as IN_PROGRESS. Returns the id, or -1 if none. */
    fun advance(): Int {
        val idx = steps.indexOfFirst { it.status == PlanStepStatus.PENDING }
        if (idx < 0) return -1
        val step = steps[idx]
        steps[idx] = step.copy(status = PlanStepStatus.IN_PROGRESS)
        lastUpdatedMs = System.currentTimeMillis()
        return step.id
    }

    /** Hide the card and clear all steps. */
    fun reset() {
        steps.clear()
        title = ""
        visible = false
        lastUpdatedMs = System.currentTimeMillis()
    }

    /**
     * 当前「进行中」那一步的 id；没有进行中的就退回第一个还没做完的；都做完了返回 -1。
     *
     * 给 `agent_plan` 的 op=done/fail 缺 id 时兜底用 —— 模型刚 advance 完就说「这步做完了」，
     * 不带步骤号是最自然的写法，而那时目标毫无歧义。
     */
    fun currentStepId(): Int =
        steps.firstOrNull { it.status == PlanStepStatus.IN_PROGRESS }?.id
            ?: steps.firstOrNull { it.status == PlanStepStatus.PENDING }?.id
            ?: -1

    /** Number of DONE steps (for the progress ratio). */
    fun doneCount(): Int = steps.count { it.status == PlanStepStatus.DONE }

    /** Total number of steps. */
    fun totalCount(): Int = steps.size
}

/** Keeps the live task card owned by the conversation that created it. */
class SessionPlanStore {
    private val states = ConcurrentHashMap<Long, PlanState>()

    fun forSession(sessionId: Long): PlanState =
        states.getOrPut(sessionId) { PlanState() }

    fun remove(sessionId: Long) {
        states.remove(sessionId)?.reset()
    }
}
