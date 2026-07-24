package com.xincode.data

import androidx.room.*

@Dao
interface HookDao {
    @Query("SELECT * FROM hooks WHERE enabled = 1 AND event = :event")
    suspend fun getEnabledByEvent(event: String): List<HookEntity>

    @Query("SELECT * FROM hooks ORDER BY createdAt DESC")
    suspend fun getAll(): List<HookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(hook: HookEntity): Long

    @Query("DELETE FROM hooks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
