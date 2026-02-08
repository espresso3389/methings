package jp.espresso3389.methings.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val name: String,
    val value: String,
    val updatedAt: Long
)
