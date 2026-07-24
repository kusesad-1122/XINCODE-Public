package com.xincode.data

import androidx.room.*

@Dao
interface AuditLogDao {
    @Insert
    suspend fun insert(entry: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<AuditLogEntity>

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC")
    suspend fun getAll(): List<AuditLogEntity>

    @Query("DELETE FROM audit_log")
    suspend fun deleteAll()
}