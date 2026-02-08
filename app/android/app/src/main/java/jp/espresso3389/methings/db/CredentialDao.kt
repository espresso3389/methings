package jp.espresso3389.methings.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CredentialDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: CredentialEntity)

    @Query("SELECT * FROM credentials WHERE name = :name LIMIT 1")
    fun getByName(name: String): CredentialEntity?

    @Query("DELETE FROM credentials WHERE name = :name")
    fun deleteByName(name: String)

    @Query("SELECT * FROM credentials ORDER BY name")
    fun listAll(): List<CredentialEntity>
}
