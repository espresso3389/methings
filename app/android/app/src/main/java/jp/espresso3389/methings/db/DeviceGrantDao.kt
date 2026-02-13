package jp.espresso3389.methings.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeviceGrantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: DeviceGrantEntity)

    @Query("SELECT * FROM device_grants WHERE key = :key LIMIT 1")
    fun getByKey(key: String): DeviceGrantEntity?

    @Query("DELETE FROM device_grants WHERE key = :key")
    fun deleteByKey(key: String)

    @Query("DELETE FROM device_grants")
    fun deleteAll()
}
