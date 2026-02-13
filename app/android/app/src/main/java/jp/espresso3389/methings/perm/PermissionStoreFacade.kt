package jp.espresso3389.methings.perm

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PermissionStoreFacade(context: Context) {
    private val dbStore = PermissionStore(context)
    private val fallback = InMemoryPermissionStore()
    private val dbAvailable = AtomicBoolean(true)

    fun findReusableApproved(
        tool: String,
        scope: String,
        identity: String,
        capability: String = ""
    ): PermissionStore.PermissionRequest? {
        val ident = identity.trim()
        if (ident.isBlank()) return null
        val now = System.currentTimeMillis()
        fun isStillValid(req: PermissionStore.PermissionRequest): Boolean {
            if (req.status != "approved") return false
            val existingRank = scopeRank(req.scope)
            val requestedRank = scopeRank(scope)
            if (existingRank < requestedRank) return false
            val ttlMs = ttlMsForScope(req.scope)
            return ttlMs <= 0L || (now - req.createdAt) <= ttlMs
        }

        return try {
            val latest = if (dbAvailable.get()) {
                dbStore.findLatestApproved(ident, tool, capability)
            } else {
                fallback.findLatestApproved(ident, tool, capability)
            }
            if (latest != null && isStillValid(latest)) latest else null
        } catch (ex: Throwable) {
            Log.e(TAG, "Permission DB unavailable, falling back", ex)
            dbAvailable.set(false)
            val latest = fallback.findLatestApproved(ident, tool, capability)
            if (latest != null && isStillValid(latest)) latest else null
        }
    }

    fun findRecentPending(
        tool: String,
        identity: String,
        capability: String = "",
        maxAgeMs: Long = 2L * 60L * 1000L
    ): PermissionStore.PermissionRequest? {
        val ident = identity.trim()
        if (ident.isBlank()) return null
        val now = System.currentTimeMillis()
        fun isStillPending(req: PermissionStore.PermissionRequest): Boolean {
            if (req.status != "pending") return false
            return (now - req.createdAt) <= maxAgeMs
        }

        return try {
            val latest = if (dbAvailable.get()) {
                dbStore.findLatestPending(ident, tool, capability)
            } else {
                fallback.findLatestPending(ident, tool, capability)
            }
            if (latest != null && isStillPending(latest)) latest else null
        } catch (ex: Throwable) {
            Log.e(TAG, "Permission DB unavailable, falling back", ex)
            dbAvailable.set(false)
            val latest = fallback.findLatestPending(ident, tool, capability)
            if (latest != null && isStillPending(latest)) latest else null
        }
    }

    fun create(
        tool: String,
        detail: String,
        scope: String,
        identity: String = "",
        capability: String = ""
    ): PermissionStore.PermissionRequest {
        return try {
            if (dbAvailable.get()) {
                dbStore.create(tool, detail, scope, identity = identity, capability = capability)
            } else {
                fallback.create(tool, detail, scope, identity = identity, capability = capability)
            }
        } catch (ex: Throwable) {
            Log.e(TAG, "Permission DB unavailable, falling back", ex)
            dbAvailable.set(false)
            fallback.create(tool, detail, scope, identity = identity, capability = capability)
        }
    }

    fun listPending(): List<PermissionStore.PermissionRequest> {
        return try {
            if (dbAvailable.get()) {
                dbStore.listPending()
            } else {
                fallback.listPending()
            }
        } catch (ex: Throwable) {
            Log.e(TAG, "Permission DB unavailable, falling back", ex)
            dbAvailable.set(false)
            fallback.listPending()
        }
    }

    fun listApproved(): List<PermissionStore.PermissionRequest> {
        val now = System.currentTimeMillis()
        fun isStillValid(req: PermissionStore.PermissionRequest): Boolean {
            val ttlMs = ttlMsForScope(req.scope)
            return ttlMs <= 0L || (now - req.createdAt) <= ttlMs
        }
        return try {
            val all = if (dbAvailable.get()) {
                dbStore.listApproved()
            } else {
                fallback.listApproved()
            }
            all.filter { isStillValid(it) }
        } catch (ex: Throwable) {
            Log.e(TAG, "Permission DB unavailable, falling back", ex)
            dbAvailable.set(false)
            fallback.listApproved().filter { isStillValid(it) }
        }
    }

    fun updateStatus(id: String, status: String): PermissionStore.PermissionRequest? {
        return try {
            if (dbAvailable.get()) {
                dbStore.updateStatus(id, status)
            } else {
                fallback.updateStatus(id, status)
            }
        } catch (ex: Throwable) {
            Log.e(TAG, "Permission DB unavailable, falling back", ex)
            dbAvailable.set(false)
            fallback.updateStatus(id, status)
        }
    }

    fun markUsed(id: String): PermissionStore.PermissionRequest? {
        return updateStatus(id, "used")
    }

    fun get(id: String): PermissionStore.PermissionRequest? {
        return try {
            if (dbAvailable.get()) {
                dbStore.get(id)
            } else {
                fallback.get(id)
            }
        } catch (ex: Throwable) {
            Log.e(TAG, "Permission DB unavailable, falling back", ex)
            dbAvailable.set(false)
            fallback.get(id)
        }
    }

    fun clearAll() {
        try {
            if (dbAvailable.get()) {
                dbStore.clearAll()
            } else {
                fallback.clearAll()
            }
        } catch (ex: Throwable) {
            Log.e(TAG, "Permission DB unavailable, falling back", ex)
            dbAvailable.set(false)
            fallback.clearAll()
        }
    }

    private class InMemoryPermissionStore {
        private val counter = AtomicLong(System.currentTimeMillis())
        private val items = ConcurrentHashMap<String, PermissionStore.PermissionRequest>()

        fun create(
            tool: String,
            detail: String,
            scope: String,
            identity: String,
            capability: String
        ): PermissionStore.PermissionRequest {
            val req = PermissionStore.PermissionRequest(
                id = "p_${counter.incrementAndGet()}",
                tool = tool,
                detail = detail,
                scope = scope,
                status = "pending",
                createdAt = System.currentTimeMillis(),
                identity = identity,
                capability = capability
            )
            items[req.id] = req
            return req
        }

        fun listPending(): List<PermissionStore.PermissionRequest> {
            return items.values.filter { it.status == "pending" }.sortedBy { it.createdAt }
        }

        fun updateStatus(id: String, status: String): PermissionStore.PermissionRequest? {
            val existing = items[id] ?: return null
            val updated = existing.copy(status = status)
            items[id] = updated
            return updated
        }

        fun get(id: String): PermissionStore.PermissionRequest? {
            return items[id]
        }

        fun clearAll() {
            items.clear()
        }

        fun listApproved(): List<PermissionStore.PermissionRequest> {
            return items.values.filter { it.status == "approved" }.sortedByDescending { it.createdAt }
        }

        fun findLatestApproved(
            identity: String,
            tool: String,
            capability: String
        ): PermissionStore.PermissionRequest? {
            return items.values
                .asSequence()
                .filter { it.status == "approved" }
                .filter { it.identity == identity && it.tool == tool && it.capability == capability }
                .maxByOrNull { it.createdAt }
        }

        fun findLatestPending(
            identity: String,
            tool: String,
            capability: String
        ): PermissionStore.PermissionRequest? {
            return items.values
                .asSequence()
                .filter { it.status == "pending" }
                .filter { it.identity == identity && it.tool == tool && it.capability == capability }
                .maxByOrNull { it.createdAt }
        }
    }

    companion object {
        private const val TAG = "PermissionStoreFacade"

        private fun scopeRank(scope: String): Int {
            return when (scope.trim()) {
                "once" -> 0
                "program" -> 1
                "session" -> 2
                "persistent" -> 3
                else -> 1
            }
        }

        private fun ttlMsForScope(scope: String): Long {
            return when (scope.trim()) {
                "persistent" -> 0L
                "session" -> 60L * 60L * 1000L
                "program" -> 10L * 60L * 1000L
                // "once" should not be auto-reused; treat as expired immediately.
                "once" -> -1L
                else -> 10L * 60L * 1000L
            }
        }
    }
}
