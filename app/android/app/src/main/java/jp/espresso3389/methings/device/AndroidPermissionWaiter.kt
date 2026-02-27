package jp.espresso3389.methings.device

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Process-wide coordination for waiting on Android runtime permission prompts.
 *
 * The HTTP server thread blocks on [await] while MainActivity shows the system dialog
 * and calls [complete] with per-permission results.
 */
object AndroidPermissionWaiter {
    data class Snapshot(
        val requestId: String,
        val permissions: List<String>,
        val requestedAtMs: Long,
        val responded: Boolean,
        val results: Map<String, Boolean>?,
        val completedAtMs: Long?,
        val timedOut: Boolean,
    )

    private data class Waiter(
        val latch: CountDownLatch,
        val permissions: List<String>,
        val requestedAtMs: Long,
        @Volatile var responded: Boolean = false,
        @Volatile var results: Map<String, Boolean>? = null,
        @Volatile var completedAtMs: Long = 0L,
        @Volatile var timedOut: Boolean = false,
    )

    private val waiters = ConcurrentHashMap<String, Waiter>()
    private val last = ConcurrentHashMap<String, Snapshot>()

    fun begin(requestId: String, permissions: List<String>): Boolean {
        if (requestId.isBlank() || permissions.isEmpty()) return false
        val now = System.currentTimeMillis()
        val created = waiters.putIfAbsent(
            requestId,
            Waiter(latch = CountDownLatch(1), permissions = permissions, requestedAtMs = now)
        ) == null
        if (created) {
            last[requestId] = Snapshot(
                requestId = requestId,
                permissions = permissions,
                requestedAtMs = now,
                responded = false,
                results = null,
                completedAtMs = null,
                timedOut = false,
            )
        }
        return created
    }

    fun complete(requestId: String, results: Map<String, Boolean>) {
        val w = waiters[requestId] ?: return
        val now = System.currentTimeMillis()
        w.responded = true
        w.results = results
        w.completedAtMs = now
        w.timedOut = false
        w.latch.countDown()
        last[requestId] = Snapshot(
            requestId = requestId,
            permissions = w.permissions,
            requestedAtMs = w.requestedAtMs,
            responded = true,
            results = results,
            completedAtMs = now,
            timedOut = false,
        )
        trimLast()
    }

    fun await(requestId: String, timeoutMs: Long): Map<String, Boolean>? {
        val w = waiters[requestId] ?: return null
        try {
            if (timeoutMs <= 0L) {
                w.latch.await()
            } else {
                val ok = w.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                if (!ok && !w.responded) {
                    w.timedOut = true
                    last[requestId] = Snapshot(
                        requestId = requestId,
                        permissions = w.permissions,
                        requestedAtMs = w.requestedAtMs,
                        responded = false,
                        results = null,
                        completedAtMs = null,
                        timedOut = true,
                    )
                    trimLast()
                }
            }
        } catch (_: InterruptedException) {
            // ignore
        }
        return w.results
    }

    fun clear(requestId: String) {
        waiters.remove(requestId)
    }

    fun pendingSnapshots(): List<Snapshot> {
        val out = ArrayList<Snapshot>()
        for ((id, w) in waiters.entries) {
            if (w.responded) continue
            out.add(
                Snapshot(
                    requestId = id,
                    permissions = w.permissions,
                    requestedAtMs = w.requestedAtMs,
                    responded = false,
                    results = null,
                    completedAtMs = null,
                    timedOut = w.timedOut,
                )
            )
        }
        out.sortBy { it.requestedAtMs }
        return out
    }

    fun recentSnapshots(limit: Int = 8): List<Snapshot> {
        if (limit <= 0) return emptyList()
        return last.values
            .sortedByDescending { it.completedAtMs ?: it.requestedAtMs }
            .take(limit)
    }

    private fun trimLast(maxEntries: Int = 64) {
        if (last.size <= maxEntries) return
        val keep = last.values
            .sortedByDescending { it.completedAtMs ?: it.requestedAtMs }
            .take(maxEntries)
            .map { it.requestId }
            .toHashSet()
        for (id in last.keys) {
            if (!keep.contains(id)) last.remove(id)
        }
    }
}
