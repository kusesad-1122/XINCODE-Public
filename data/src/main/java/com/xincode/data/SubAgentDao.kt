package com.xincode.data

import androidx.room.*

@Dao
interface SubAgentDao {
    @Query("SELECT * FROM sub_agents ORDER BY builtin DESC, updatedAt DESC")
    suspend fun getAll(): List<SubAgentEntity>

    @Query("SELECT * FROM sub_agents WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SubAgentEntity?

    @Query("SELECT * FROM sub_agents WHERE id = :id")
    suspend fun getById(id: Long): SubAgentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: SubAgentEntity): Long

    @Query("DELETE FROM sub_agents WHERE id = :id")
    suspend fun deleteById(id: Long)
}
