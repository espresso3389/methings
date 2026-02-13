package jp.espresso3389.methings.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PermissionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: PermissionEntity)

    @Query("SELECT * FROM permissions WHERE status = 'pending' ORDER BY createdAt")
    fun listPending(): List<PermissionEntity>

    @Query("SELECT * FROM permissions WHERE id = :id LIMIT 1")
    fun getById(id: String): PermissionEntity?

    @Query(
        "SELECT * FROM permissions " +
            "WHERE status = 'approved' AND identity = :identity AND tool = :tool AND capability = :capability " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    fun findLatestApproved(identity: String, tool: String, capability: String): PermissionEntity?

    @Query(
        "SELECT * FROM permissions " +
            "WHERE status = 'pending' AND identity = :identity AND tool = :tool AND capability = :capability " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    fun findLatestPending(identity: String, tool: String, capability: String): PermissionEntity?

    @Query("SELECT * FROM permissions WHERE status = 'approved' ORDER BY createdAt DESC")
    fun listApproved(): List<PermissionEntity>

    @Query("DELETE FROM permissions")
    fun deleteAll()

    @Update
    fun update(entity: PermissionEntity)
}
