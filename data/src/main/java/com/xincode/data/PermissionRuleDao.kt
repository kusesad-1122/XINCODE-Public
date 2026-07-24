package com.xincode.data

import androidx.room.*

@Dao
interface PermissionRuleDao {
    @Query("SELECT * FROM permission_rules ORDER BY createdAt DESC")
    suspend fun getAll(): List<PermissionRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: PermissionRuleEntity): Long

    @Query("DELETE FROM permission_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM permission_rules")
    suspend fun clear()
}
