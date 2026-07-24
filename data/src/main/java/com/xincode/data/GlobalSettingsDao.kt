package com.xincode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GlobalSettingsDao {
    @Query("SELECT * FROM global_settings WHERE id = 1")
    suspend fun get(): GlobalSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: GlobalSettingsEntity)
}