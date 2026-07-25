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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_TODO = "todo"
        const val STATUS_DOING = "doing"
        const val STATUS_DONE = "done"
    }
}

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
}
