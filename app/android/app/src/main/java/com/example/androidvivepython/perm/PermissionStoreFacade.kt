package jp.espresso3389.kugutz.perm

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PermissionStoreFacade(context: Context) {
    private val dbStore = PermissionStore(context)
    private val fallback = InMemoryPermissionStore()
    private val dbAvailable = AtomicBoolean(true)

    fun create(tool: String, detail: String, scope: String): PermissionStore.PermissionRequest {
        return try {
            if (dbAvailable.get()) {
                dbStore.create(tool, detail, scope)
            } else {
                fallback.create(tool, detail, scope)
            }
        } catch (ex: Throwable) {
            Log.e(TAG, "Permission DB unavailable, falling back", ex)
            dbAvailable.set(false)
            fallback.create(tool, detail, scope)
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

    private class InMemoryPermissionStore {
        private val counter = AtomicLong(System.currentTimeMillis())
        private val items = ConcurrentHashMap<String, PermissionStore.PermissionRequest>()

        fun create(tool: String, detail: String, scope: String): PermissionStore.PermissionRequest {
            val req = PermissionStore.PermissionRequest(
                id = "p_${counter.incrementAndGet()}",
                tool = tool,
                detail = detail,
                scope = scope,
                status = "pending",
                createdAt = System.currentTimeMillis()
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
    }

    companion object {
        private const val TAG = "PermissionStoreFacade"
    }
}
