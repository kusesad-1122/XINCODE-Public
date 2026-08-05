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

    /** invoke_skill 每次命中:累计次数、刷新最近使用、自动复活(active)。 */
    @Query("UPDATE skills SET useCount = useCount + 1, lastUsedAt = :ts, state = 'active', updatedAt = :ts WHERE id = :id")
    suspend fun incrementUsage(id: Long, ts: Long)

    @Query("UPDATE skills SET state = :state, updatedAt = :ts WHERE id = :id")
    suspend fun setState(id: Long, state: String, ts: Long)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteById(id: Long)
}
