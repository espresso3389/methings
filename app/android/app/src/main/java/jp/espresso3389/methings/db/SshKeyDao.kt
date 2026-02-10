package jp.espresso3389.methings.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SshKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: SshKeyEntity)

    @Query("SELECT * FROM ssh_keys WHERE fingerprint = :fingerprint LIMIT 1")
    fun getByFingerprint(fingerprint: String): SshKeyEntity?

    @Query("SELECT * FROM ssh_keys ORDER BY createdAt DESC")
    fun listAll(): List<SshKeyEntity>

    @Query("DELETE FROM ssh_keys WHERE fingerprint = :fingerprint")
    fun deleteByFingerprint(fingerprint: String)
}
