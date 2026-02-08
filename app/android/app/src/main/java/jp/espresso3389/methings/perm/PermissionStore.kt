package jp.espresso3389.methings.perm

import android.content.Context
import jp.espresso3389.methings.db.PlainDbProvider
import jp.espresso3389.methings.db.PermissionEntity
import java.util.concurrent.atomic.AtomicLong

class PermissionStore(context: Context) {
    private val dao = PlainDbProvider.get(context).permissionDao()
    private val counter = AtomicLong(System.currentTimeMillis())

    fun create(tool: String, detail: String, scope: String, identity: String = "", capability: String = ""): PermissionRequest {
        val req = PermissionRequest(
            id = "p_${counter.incrementAndGet()}",
            tool = tool,
            detail = detail,
            scope = scope,
            status = "pending",
            createdAt = System.currentTimeMillis(),
            identity = identity,
            capability = capability
        )
        dao.upsert(req.toEntity())
        return req
    }

    fun listPending(): List<PermissionRequest> {
        return dao.listPending().map { it.toModel() }
    }

    fun updateStatus(id: String, status: String): PermissionRequest? {
        val existing = dao.getById(id) ?: return null
        val updated = existing.copy(status = status)
        dao.upsert(updated)
        return updated.toModel()
    }

    fun markUsed(id: String): PermissionRequest? {
        return updateStatus(id, "used")
    }

    fun get(id: String): PermissionRequest? {
        return dao.getById(id)?.toModel()
    }

    fun findLatestApproved(identity: String, tool: String, capability: String): PermissionRequest? {
        val ident = identity.trim()
        if (ident.isBlank()) return null
        val t = tool.trim()
        if (t.isBlank()) return null
        val cap = capability.trim()
        return dao.findLatestApproved(ident, t, cap)?.toModel()
    }

    fun findLatestPending(identity: String, tool: String, capability: String): PermissionRequest? {
        val ident = identity.trim()
        if (ident.isBlank()) return null
        val t = tool.trim()
        if (t.isBlank()) return null
        val cap = capability.trim()
        return dao.findLatestPending(ident, t, cap)?.toModel()
    }

    fun clearAll() {
        dao.deleteAll()
    }

    data class PermissionRequest(
        val id: String,
        val tool: String,
        val detail: String,
        val scope: String,
        val status: String,
        val createdAt: Long,
        val identity: String,
        val capability: String
    )

    private fun PermissionRequest.toEntity(): PermissionEntity {
        return PermissionEntity(
            id = id,
            tool = tool,
            detail = detail,
            scope = scope,
            status = status,
            createdAt = createdAt,
            identity = identity,
            capability = capability
        )
    }

    private fun PermissionEntity.toModel(): PermissionRequest {
        return PermissionRequest(
            id = id,
            tool = tool,
            detail = detail,
            scope = scope,
            status = status,
            createdAt = createdAt,
            identity = identity,
            capability = capability
        )
    }
}
