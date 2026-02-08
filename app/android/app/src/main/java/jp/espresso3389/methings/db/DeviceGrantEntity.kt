package jp.espresso3389.methings.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_grants")
data class DeviceGrantEntity(
    @PrimaryKey val key: String, // "$identity::$capability"
    val identity: String,
    val capability: String,
    val scope: String,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long
)

