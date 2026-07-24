package com.xincode.data

import androidx.room.*

@Dao
interface StateCursorDao {
    @Query("SELECT * FROM state_cursor LIMIT 1")
    suspend fun getAny(): StateCursorEntity?

    @Query("SELECT * FROM state_cursor WHERE sessionId = :sessionId")
    suspend fun getBySessionId(sessionId: Long): StateCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: StateCursorEntity)

    @Query("DELETE FROM state_cursor WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: Long)

    @Query("DELETE FROM state_cursor")
    suspend fun deleteAll()
}