package jp.espresso3389.methings.perm

import android.content.Context
import jp.espresso3389.methings.db.DeviceGrantEntity
import jp.espresso3389.methings.db.PlainDbProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DeviceGrantStore(context: Context) {
    private val dao = PlainDbProvider.get(context).deviceGrantDao()

    fun isGranted(identity: String, capability: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = key(identity, capability)
        val row = dao.getByKey(key) ?: return false
        if (row.status != "granted") return false
        return row.expiresAt <= 0L || row.expiresAt > nowMs
    }

    fun upsertGrant(
        identity: String,
        capability: String,
        scope: String,
        expiresAt: Long
    ) {
        val now = System.currentTimeMillis()
        dao.upsert(
            DeviceGrantEntity(
                key = key(identity, capability),
                identity = identity,
                capability = capability,
                scope = scope,
                status = "granted",
                createdAt = now,
                expiresAt = expiresAt
            )
        )
    }

    fun revoke(identity: String, capability: String) {
        dao.deleteByKey(key(identity, capability))
    }

    companion object {
        fun key(identity: String, capability: String): String {
            return "${identity.trim()}::${capability.trim()}"
        }
    }
}

class DeviceGrantStoreFacade(context: Context) {
    private val dbStore = DeviceGrantStore(context)
    private val dbAvailable = AtomicBoolean(true)
    private val fallback = InMemoryDeviceGrantStore()

    fun isGranted(identity: String, capability: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        return try {
            if (dbAvailable.get()) dbStore.isGranted(identity, capability, nowMs) else fallback.isGranted(identity, capability, nowMs)
        } catch (_: Throwable) {
            dbAvailable.set(false)
            fallback.isGranted(identity, capability, nowMs)
        }
    }

    fun upsertGrant(identity: String, capability: String, scope: String, expiresAt: Long) {
        try {
            if (dbAvailable.get()) {
                dbStore.upsertGrant(identity, capability, scope, expiresAt)
            } else {
                fallback.upsertGrant(identity, capability, scope, expiresAt)
            }
        } catch (_: Throwable) {
            dbAvailable.set(false)
            fallback.upsertGrant(identity, capability, scope, expiresAt)
        }
    }

    private class InMemoryDeviceGrantStore {
        private data class Row(val status: String, val expiresAt: Long)
        private val items = ConcurrentHashMap<String, Row>()

        fun isGranted(identity: String, capability: String, nowMs: Long): Boolean {
            val key = DeviceGrantStore.key(identity, capability)
            val row = items[key] ?: return false
            if (row.status != "granted") return false
            return row.expiresAt <= 0L || row.expiresAt > nowMs
        }

        fun upsertGrant(identity: String, capability: String, scope: String, expiresAt: Long) {
            val key = DeviceGrantStore.key(identity, capability)
            items[key] = Row("granted", expiresAt)
        }
    }
}

