package com.xincode.data

import androidx.room.*

@Dao
interface ProviderConfigDao {

    @Query("SELECT * FROM provider_configs ORDER BY createdAt DESC")
    suspend fun getAll(): List<ProviderConfigEntity>

    @Insert
    suspend fun insert(config: ProviderConfigEntity): Long

    @Update
    suspend fun update(config: ProviderConfigEntity)

    @Delete
    suspend fun delete(config: ProviderConfigEntity)

    @Query("UPDATE provider_configs SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE provider_configs SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: Long)

    @Query("SELECT * FROM provider_configs WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ProviderConfigEntity?

    /** 功能模型配置按 id 取指定配置(取不到时由调用方回落到 getActive)。 */
    @Query("SELECT * FROM provider_configs WHERE id = :id")
    suspend fun getById(id: Long): ProviderConfigEntity?
}