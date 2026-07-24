package com.xincode.data

import androidx.room.*

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY createdAt ASC")
    suspend fun getAll(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun getById(id: Long): McpServerEntity?

    @Query("SELECT * FROM mcp_servers WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): McpServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: McpServerEntity): Long

    @Update
    suspend fun update(server: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM mcp_servers WHERE connected = 1")
    suspend fun getConnected(): List<McpServerEntity>
}
