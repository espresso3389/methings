package jp.espresso3389.methings.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PermissionEntity::class,
        SshKeyEntity::class,
        CredentialEntity::class,
        DeviceGrantEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun permissionDao(): PermissionDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun credentialDao(): CredentialDao
    abstract fun deviceGrantDao(): DeviceGrantDao
}
