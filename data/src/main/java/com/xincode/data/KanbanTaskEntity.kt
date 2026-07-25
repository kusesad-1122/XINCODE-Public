package com.xincode.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 看板任务。
 *
 * 与 `agent_plan` 的分工:agent_plan 是【一个回合内】的临时清单,回合结束就该消失
 * (PlanState 刻意不落库,留着旧计划只会让 UI 混乱)。看板是【跨会话长期】的待办,
 * 由用户手建,或者把 AI 的计划一键固化过来。
 */
@Entity(tableName = "kanban_tasks")
data class KanbanTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val note: String = "",
    /** todo / doing / done。用字符串而非枚举序号,以后加列不会让老数据错位。 */
    val status: String = STATUS_TODO,
    /** 同列内的排序位,越小越靠前。 */
    val position: Int = 0,
    /** 关联的会话;0 = 不关联。用于「从这个对话生成的任务」。 */
    val sessionId: Long = 0,

    // ---- 派活相关 ----
    /**
     * 指派给谁执行。空 = 主智能体;否则是子智能体的名字。
     * 用名字而非 id:子智能体被删掉时任务不该变成指向空气,回落到主智能体即可。
     */
    val assignee: String = "",
    /** 越小越优先。同状态内先按 priority 再按 position 排。 */
    val priority: Int = 0,
    /** 最近一次执行的起止时间;0 = 没跑过。 */
    val startedAt: Long = 0,
    val completedAt: Long = 0,
    /** 最近一次执行的产出摘要(成功)或失败原因(失败)。 */
    val result: String = "",
    /** 累计执行次数。反复失败重跑时能一眼看出来。 */
    val runCount: Int = 0,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // 手工态
        const val STATUS_TODO = "todo"        // 待办:还没准备好交给智能体
        const val STATUS_DOING = "doing"      // 进行中:你自己在做
        const val STATUS_DONE = "done"

        // 派活态 —— 看板从「待办清单」变成「工作队列」的关键
        const val STATUS_READY = "ready"      // 就绪:可以交给智能体跑了
        const val STATUS_RUNNING = "running"  // 智能体正在跑
        const val STATUS_BLOCKED = "blocked"  // 跑失败/卡住,需要人介入
        const val STATUS_REVIEW = "review"    // 跑完了,等你验收

        /** 智能体正在占用的状态。用于防重入与恢复时的悬挂检测。 */
        val ACTIVE_STATUSES = setOf(STATUS_RUNNING)
    }
}

/**
 * 一次执行的记录。任务本身只留最近一次结果,历次过程记在这里。
 * 反复失败时能看出「是一直失败还是偶发」,这是只存最新结果看不出来的。
 */
@Entity(tableName = "kanban_runs")
data class KanbanRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long,
    val assignee: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = 0,
    /** success / failed / cancelled */
    val outcome: String = "",
    val summary: String = "",
    val error: String = ""
)

@Dao
interface KanbanTaskDao {

    @Query("SELECT * FROM kanban_tasks ORDER BY position ASC, createdAt ASC")
    fun observeAll(): Flow<List<KanbanTaskEntity>>

    @Query("SELECT * FROM kanban_tasks ORDER BY position ASC, createdAt ASC")
    suspend fun getAll(): List<KanbanTaskEntity>

    @Insert
    suspend fun insert(task: KanbanTaskEntity): Long

    @Update
    suspend fun update(task: KanbanTaskEntity)

    @Delete
    suspend fun delete(task: KanbanTaskEntity)

    @Query("UPDATE kanban_tasks SET status = :status, updatedAt = :ts WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, ts: Long = System.currentTimeMillis())

    @Query("DELETE FROM kanban_tasks WHERE status = 'done'")
    suspend fun clearDone()

    @Query("SELECT COALESCE(MAX(position), 0) FROM kanban_tasks WHERE status = :status")
    suspend fun maxPosition(status: String): Int

    @Query("SELECT * FROM kanban_tasks WHERE id = :id")
    suspend fun getById(id: Long): KanbanTaskEntity?

    /**
     * 取下一个待跑任务:ready 状态、按优先级与创建时间排。
     * 指定 assignee 时只取该执行者的。
     */
    @Query("""
        SELECT * FROM kanban_tasks
        WHERE status = 'ready' AND (:assignee = '' OR assignee = :assignee)
        ORDER BY priority ASC, position ASC, createdAt ASC LIMIT 1
    """)
    suspend fun nextReady(assignee: String = ""): KanbanTaskEntity?

    @Query("SELECT COUNT(*) FROM kanban_tasks WHERE status = 'running'")
    suspend fun runningCount(): Int

    /**
     * 把悬挂的 running 任务收回 ready。
     * 进程被杀时正在跑的任务会永远停在 running,重启后没人管它 —— 启动时调一次。
     */
    @Query("UPDATE kanban_tasks SET status = 'ready', updatedAt = :ts WHERE status = 'running'")
    suspend fun reclaimStuckRunning(ts: Long = System.currentTimeMillis())
}

@Dao
interface KanbanRunDao {

    @Insert
    suspend fun insert(run: KanbanRunEntity): Long

    @Update
    suspend fun update(run: KanbanRunEntity)

    @Query("SELECT * FROM kanban_runs WHERE taskId = :taskId ORDER BY startedAt DESC")
    suspend fun getByTask(taskId: Long): List<KanbanRunEntity>

    @Query("DELETE FROM kanban_runs WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)
}
