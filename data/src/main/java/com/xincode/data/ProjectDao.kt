package com.xincode.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("UPDATE projects SET isExpanded = :expanded WHERE id = :id")
    suspend fun setExpanded(id: Long, expanded: Boolean)

    @Query("UPDATE projects SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /** 设置项目级工作区根目录(空=回退全局/默认)。 */
    @Query("UPDATE projects SET workspaceRoot = :root WHERE id = :id")
    suspend fun setWorkspaceRoot(id: Long, root: String)
}