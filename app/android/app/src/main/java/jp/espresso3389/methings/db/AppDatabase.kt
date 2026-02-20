package jp.espresso3389.methings.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PermissionEntity::class,
        CredentialEntity::class,
        DeviceGrantEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun permissionDao(): PermissionDao
    abstract fun credentialDao(): CredentialDao
    abstract fun deviceGrantDao(): DeviceGrantDao
}
