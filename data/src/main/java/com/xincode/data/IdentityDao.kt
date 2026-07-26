package com.xincode.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {
    @Insert
    suspend fun insert(identity: IdentityEntity): Long

    @Update
    suspend fun update(identity: IdentityEntity)

    @Delete
    suspend fun delete(identity: IdentityEntity)

    @Query("SELECT * FROM identities ORDER BY isStarred DESC, createdAt DESC")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identities WHERE id = :id")
    suspend fun getById(id: Long): IdentityEntity?

    @Query("SELECT * FROM identities WHERE id = :id")
    fun observeById(id: Long): Flow<IdentityEntity?>

    @Query("UPDATE identities SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE identities SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("SELECT COUNT(*) FROM identities")
    suspend fun count(): Int

    /** 预制团队按名字查重复用 —— 重复安装不该攒出一堆同名卡。 */
    @Query("SELECT * FROM identities")
    suspend fun getAll(): List<IdentityEntity>

    /** 删除身份卡后,所有指向它的 session 改为 null(对话保留,只失去身份卡关联). */
    @Query("UPDATE sessions SET identityId = NULL WHERE identityId = :identityId")
    suspend fun nullifyIdentityInSessions(identityId: Long)
}
