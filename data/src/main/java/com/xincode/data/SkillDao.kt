package com.xincode.data

import androidx.room.*

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY updatedAt DESC")
    suspend fun getAll(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id")
    suspend fun getById(id: Long): SkillEntity?

    @Query("SELECT * FROM skills WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SkillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillEntity): Long

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteById(id: Long)
}