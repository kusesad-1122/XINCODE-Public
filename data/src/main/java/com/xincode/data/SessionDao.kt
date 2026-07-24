package com.xincode.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    // 普通会话列表【排除】Goal 任务(Goal 单列在侧栏「Goal 模式」区)。
    @Query("SELECT * FROM sessions WHERE isGoal = 0 ORDER BY updatedAt DESC")
    fun getAllFlow(): Flow<List<SessionEntity>>

    // ---- Goal/Work 模式 ----
    @Query("SELECT * FROM sessions WHERE isGoal = 1 ORDER BY updatedAt DESC")
    fun observeGoals(): Flow<List<SessionEntity>>

    @Query("UPDATE sessions SET goalStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun setGoalStatus(id: Long, status: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity): Long

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE sessions SET updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET updatedAt = :now, systemPromptOverride = :prompt WHERE id = :id")
    suspend fun updateSystemPromptOverride(id: Long, prompt: String?, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun countSessions(): Int

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    // ---- 5.0: Project & Star support ----

    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeByProject(projectId: Long?): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE isStarred = 1 AND isGoal = 0 ORDER BY updatedAt DESC")
    fun observeStarred(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE projectId IS NULL AND isGoal = 0 ORDER BY updatedAt DESC")
    fun observeUngrouped(): Flow<List<SessionEntity>>

    @Query("UPDATE sessions SET projectId = :projectId WHERE id = :sessionId")
    suspend fun moveToProject(sessionId: Long, projectId: Long?)

    @Query("UPDATE sessions SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE sessions SET projectId = NULL WHERE projectId = :projectId")
    suspend fun ungroupAllInProject(projectId: Long)
}