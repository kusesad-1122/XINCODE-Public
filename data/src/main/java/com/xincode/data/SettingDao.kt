package com.xincode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingDao {

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(entity: SettingEntity)

    suspend fun put(key: String, value: String) {
        set(SettingEntity(key, value))
    }

    /** 按前缀取一批(多 Profile 的克隆/导出用)。 */
    @Query("SELECT * FROM settings WHERE `key` LIKE :prefix || '%'")
    suspend fun getByPrefix(prefix: String): List<SettingEntity>

    /** 按前缀删一批(删除 Profile 时清掉它名下所有键)。 */
    @Query("DELETE FROM settings WHERE `key` LIKE :prefix || '%'")
    suspend fun deleteByPrefix(prefix: String)
}