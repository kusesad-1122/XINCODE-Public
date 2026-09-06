package com.xincode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface HarnessThreadDao {

    // ---- Thread ----
    @Insert
    suspend fun insertThread(thread: HarnessThreadEntity): Long

    @Update
    suspend fun updateThread(thread: HarnessThreadEntity)

    @Query("SELECT * FROM harness_threads WHERE id = :id")
    suspend fun getThread(id: Long): HarnessThreadEntity?

    /** 该会话最新一条 active Thread;没有返回 null(调用方再新建)。 */
    @Query("""
        SELECT * FROM harness_threads
        WHERE sessionId = :sessionId AND status = 'active'
        ORDER BY updatedAt DESC LIMIT 1
    """)
    suspend fun activeThreadOfSession(sessionId: Long): HarnessThreadEntity?

    @Query("UPDATE harness_threads SET status = :status, updatedAt = :ts WHERE id = :id")
    suspend fun setThreadStatus(id: Long, status: String, ts: Long = System.currentTimeMillis())

    // ---- Turn ----
    @Insert
    suspend fun insertTurn(turn: HarnessTurnEntity): Long

    @Update
    suspend fun updateTurn(turn: HarnessTurnEntity)

    @Query("SELECT * FROM harness_turns WHERE id = :id")
    suspend fun getTurn(id: Long): HarnessTurnEntity?

    /** 整条血缘按执行序(恢复重建/G 步 Fork 复制前缀都用它)。 */
    @Query("SELECT * FROM harness_turns WHERE threadId = :threadId ORDER BY id ASC")
    suspend fun turnsOf(threadId: Long): List<HarnessTurnEntity>

    /** 该 Thread 当前占着执行位的 Turn(正常同时最多一条)。 */
    @Query("""
        SELECT * FROM harness_turns WHERE threadId = :threadId
        AND status IN ('running', 'waiting_tool', 'waiting_approval')
        ORDER BY id DESC LIMIT 1
    """)
    suspend fun openTurnOf(threadId: Long): HarnessTurnEntity?

    @Query("UPDATE harness_turns SET status = :status, updatedAt = :ts WHERE id = :id")
    suspend fun setTurnStatus(id: Long, status: String, ts: Long = System.currentTimeMillis())

    /** 正常收尾:状态+结论摘要+证据一次写回。 */
    @Query("""
        UPDATE harness_turns SET status = :status, summary = :summary,
        evidence = :evidence, updatedAt = :ts WHERE id = :id
    """)
    suspend fun finishTurn(
        id: Long,
        status: String,
        summary: String,
        evidence: String,
        ts: Long = System.currentTimeMillis()
    )

    /**
     * 进程被杀/重启后把悬挂的 open Turn 收回 cancelled。
     * 调一次(Application 启动),与 kanban reclaimStuckRunning 同 pattern。
     */
    @Query("""
        UPDATE harness_turns SET status = 'cancelled', updatedAt = :ts
        WHERE status IN ('running', 'waiting_tool', 'waiting_approval')
    """)
    suspend fun reclaimStuckTurns(ts: Long = System.currentTimeMillis()): Int
}
