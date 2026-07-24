package com.xincode.data

import androidx.room.*

@Dao
interface CronJobDao {
    @Query("SELECT * FROM cron_jobs ORDER BY updatedAt DESC")
    suspend fun getAll(): List<CronJobEntity>

    @Query("SELECT * FROM cron_jobs WHERE id = :id")
    suspend fun getById(id: Long): CronJobEntity?

    @Query("SELECT * FROM cron_jobs WHERE enabled = 1 AND nextRunAt > 0 AND nextRunAt <= :now")
    suspend fun getDue(now: Long): List<CronJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: CronJobEntity): Long

    @Update
    suspend fun update(job: CronJobEntity)

    @Query("DELETE FROM cron_jobs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
