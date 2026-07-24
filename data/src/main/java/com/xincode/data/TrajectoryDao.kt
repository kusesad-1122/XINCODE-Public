package com.xincode.data

import androidx.room.*

@Dao
interface TrajectoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrajectoryEntity)

    @Query("SELECT * FROM trajectories WHERE sessionId = :sessionId")
    suspend fun getBySessionId(sessionId: Long): TrajectoryEntity?

    @Query("SELECT * FROM trajectories ORDER BY updatedAt DESC")
    suspend fun getAll(): List<TrajectoryEntity>

    @Delete
    suspend fun delete(entity: TrajectoryEntity)
}