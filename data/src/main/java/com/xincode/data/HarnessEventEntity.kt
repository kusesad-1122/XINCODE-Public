package com.xincode.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 步骤G:Rollout 事件 —— Agent 运行流水(视频 Event/Rollout/ThreadStore)。
 *
 * 只追加不改写不删除(无 Delete/Update 方法是刻意的):
 * 模型上下文可以压缩,但系统到底经历过什么永留此处。恢复 = 读取→解析→重建,
 * 不是读内存快照。type 固定词表,见 [HarnessEvents].
 *
 * 对齐 Codex Rollout(移植3):每条事件带 thread/turn/call 三 id,
 * Begin/End 配 [ExecutionStatus];原始 payload 原样保留(类型化字段供重建,
 * raw 供端到端排障 —— 与 Codex "reducers consume typed events, raw preserved" 同构)。
 */
@Entity(
    tableName = "harness_events",
    indices = [Index("threadId"), Index("turnId")]
)
data class HarnessEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 归属 Thread;0 = 归属不明(仍要记下来,总比丢了强)。 */
    val threadId: Long = 0,
    /** 归属 Turn;0 = Turn 外事件(如恢复/归档)。 */
    val turnId: Long = 0,
    /** user_input / turn_started / tool_call / tool_result / approval_request / approval_result / turn_finished */
    val type: String = "",
    /** 短载荷(JSON 片段或摘要,超长由记录方截断)。 */
    val payload: String = "",
    /**
     * 移植3:工具调用 id(模型的 tool_call_id;非工具事件为空)。
     * 与 Codex RawTraceEvent 的 tool_call_id 同义,Begin/End 配对全靠它。
     */
    val callId: String = "",
    /**
     * 移植3:执行状态([ExecutionStatus.wire];写事件时已知多少填多少,
     * 老行留空,重建时回退读 payload)。
     */
    val status: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface HarnessEventDao {

    @Insert
    suspend fun insert(event: HarnessEventEntity): Long

    /** 整条流水按发生序(重建/Fork/导出全用它)。 */
    @Query("SELECT * FROM harness_events WHERE threadId = :threadId ORDER BY id ASC")
    suspend fun eventsOf(threadId: Long): List<HarnessEventEntity>

    @Query("SELECT COUNT(*) FROM harness_events WHERE threadId = :threadId")
    suspend fun countOf(threadId: Long): Int
}
