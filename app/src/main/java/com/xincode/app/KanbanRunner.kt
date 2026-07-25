package com.xincode.app

import android.util.Log
import com.xincode.core.AgentCore
import com.xincode.core.AgentState
import com.xincode.data.AppDatabase
import com.xincode.data.KanbanRunEntity
import com.xincode.data.KanbanTaskEntity
import com.xincode.data.KanbanTaskEntity.Companion.STATUS_BLOCKED
import com.xincode.data.KanbanTaskEntity.Companion.STATUS_READY
import com.xincode.data.KanbanTaskEntity.Companion.STATUS_REVIEW
import com.xincode.data.KanbanTaskEntity.Companion.STATUS_RUNNING
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 看板执行器:把 ready 的任务交给智能体去跑。
 *
 * 这一步让看板从「待办清单」变成「工作队列」—— 任务不只是记着,是真能被执行的。
 *
 * ## 状态流转
 * ```
 *   ready ──开跑──> running ──成功──> review(等你验收)──> done
 *                      └────失败────> blocked(需要人介入)
 * ```
 * 跑完停在 review 而不是直接 done:智能体说自己做完了不等于真做完了,得你看一眼。
 *
 * ## 几个必须防住的点
 * - **单并发**。手机上同时跑多个 agent 会抢内存、抢网络,而且用户根本看不过来。
 *   一次只跑一个,其余排队。
 * - **进程被杀的悬挂**。跑到一半被系统回收,任务会永远停在 running。启动时统一收回
 *   ready(见 KanbanTaskDao.reclaimStuckRunning)。
 * - **超时**。任务卡住不能一直占着队列,到点就判失败进 blocked。
 */
class KanbanRunner(
    private val database: AppDatabase,
    /** 造一个隔离的 AgentCore(全工具、独立 sessionId、不碰主对话)。 */
    private val coreFactory: () -> AgentCore
) {
    companion object {
        private const val TAG = "XincodeKanban"

        /** 单个任务的最长执行时间。超时判失败,不能让它无限占着队列。 */
        private const val TASK_TIMEOUT_MS = 20 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 队列是否正在消费。手机上只允许单并发。 */
    private val busy = AtomicBoolean(false)

    @Volatile
    private var currentJob: Job? = null

    @Volatile
    var currentTaskId: Long = 0
        private set

    /** 状态变化回调,供 UI 显示「正在跑第几个」。 */
    var onProgress: ((taskId: Long, text: String) -> Unit)? = null

    val isRunning: Boolean get() = busy.get()

    /**
     * 启动队列消费。已在跑就直接返回(不重入)。
     * 会一直取 ready 任务直到取不到为止。
     */
    fun start() {
        if (!busy.compareAndSet(false, true)) return
        currentJob = scope.launch {
            try {
                while (true) {
                    val task = database.kanbanTaskDao().nextReady() ?: break
                    runOne(task)
                }
            } catch (e: Exception) {
                Log.w(TAG, "queue stopped: ${e.message}")
            } finally {
                busy.set(false)
                currentTaskId = 0
                onProgress?.invoke(0, "")
            }
        }
    }

    /** 停止队列。正在跑的那个会被取消并退回 ready。 */
    fun stop() {
        currentJob?.cancel()
        scope.launch {
            val id = currentTaskId
            if (id > 0) {
                runCatching { database.kanbanTaskDao().setStatus(id, STATUS_READY) }
            }
        }
        busy.set(false)
        currentTaskId = 0
    }

    /** 只跑指定的一个任务(用户在卡片上点「开跑」)。 */
    fun runTask(taskId: Long) {
        if (!busy.compareAndSet(false, true)) return
        currentJob = scope.launch {
            try {
                val task = database.kanbanTaskDao().getById(taskId) ?: return@launch
                runOne(task)
            } finally {
                busy.set(false)
                currentTaskId = 0
                onProgress?.invoke(0, "")
            }
        }
    }

    private suspend fun runOne(task: KanbanTaskEntity) {
        val taskDao = database.kanbanTaskDao()
        val runDao = database.kanbanRunDao()
        currentTaskId = task.id

        taskDao.update(task.copy(
            status = STATUS_RUNNING,
            startedAt = System.currentTimeMillis(),
            runCount = task.runCount + 1,
            updatedAt = System.currentTimeMillis()
        ))
        val runId = runDao.insert(KanbanRunEntity(taskId = task.id, assignee = task.assignee))
        onProgress?.invoke(task.id, "正在执行:${task.title}")
        Log.i(TAG, "task ${task.id} start: ${task.title}")

        var outcome = "failed"
        var summary = ""
        var error = ""

        try {
            val core = coreFactory()
            // 指派给了子智能体就把它的设定拼进去。子智能体被删掉时按名字找不到,
            // 自动回落到主智能体 —— 删一个子智能体不该让所有指派给它的任务全废掉。
            val assigneePrompt = if (task.assignee.isNotBlank()) {
                val sub = runCatching { database.subAgentDao().getAll() }.getOrNull()
                    ?.firstOrNull { it.name == task.assignee }
                sub?.systemPrompt?.takeIf { it.isNotBlank() }
                    ?.let { "\n\n你现在以「${task.assignee}」的身份执行这个任务:\n$it" }
                    ?: ""
            } else ""

            val prompt = buildString {
                append("请完成下面这个任务。完成后用一段话总结你做了什么、结果如何。\n\n")
                append("任务:${task.title}\n")
                if (task.note.isNotBlank()) append("补充说明:${task.note}\n")
                append(assigneePrompt)
            }

            // 超时:卡住的任务不能一直占着队列
            val finished = withTimeoutOrNull(TASK_TIMEOUT_MS) {
                core.run(prompt, scope, thinkingEnabled = false, thinkingLevel = 1).join()
                // 跑完后从状态里判断成败
                core.state.first { it is AgentState.Idle || it is AgentState.Error }
            }

            when {
                finished == null -> {
                    error = "执行超时(超过 ${TASK_TIMEOUT_MS / 60000} 分钟)"
                }
                finished is AgentState.Error -> {
                    error = finished.message
                }
                else -> {
                    outcome = "success"
                    summary = "已完成"
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            outcome = "cancelled"
            error = "已取消"
            throw e
        } catch (t: Throwable) {
            error = "${t::class.java.simpleName}: ${t.message?.take(160)}"
        } finally {
            val now = System.currentTimeMillis()
            runCatching {
                runDao.update(KanbanRunEntity(
                    id = runId, taskId = task.id, assignee = task.assignee,
                    startedAt = task.startedAt.takeIf { it > 0 } ?: now,
                    endedAt = now, outcome = outcome, summary = summary, error = error
                ))
                val latest = taskDao.getById(task.id)
                if (latest != null) {
                    taskDao.update(latest.copy(
                        // 成功后停在 review 等人验收,不直接 done ——
                        // 智能体说自己做完了不等于真做完了。
                        status = if (outcome == "success") STATUS_REVIEW else STATUS_BLOCKED,
                        completedAt = now,
                        result = if (outcome == "success") summary else error,
                        updatedAt = now
                    ))
                }
            }
            Log.i(TAG, "task ${task.id} end: $outcome ${error.take(80)}")
        }
    }
}
