package jp.espresso3389.methings.device

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide coordination for waiting on Android's OS-level USB permission prompt.
 *
 * The OS delivers results via a broadcast PendingIntent. We need a stable place to store latches
 * so requesters (LocalHttpServer) can synchronously wait for allow/deny.
 */
object UsbPermissionWaiter {
    data class Snapshot(
        val deviceName: String,
        val requestedAtMs: Long,
        val responded: Boolean,
        val granted: Boolean?,
        val completedAtMs: Long?,
        val timedOut: Boolean,
    )

    private data class Waiter(
        val latch: CountDownLatch,
        val granted: AtomicBoolean,
        val requestedAtMs: Long,
        val responded: AtomicBoolean,
        val completedAtMs: AtomicLong,
        val timedOut: AtomicBoolean,
    )

    private val waiters = ConcurrentHashMap<String, Waiter>()
    private val last = ConcurrentHashMap<String, Snapshot>()

    fun begin(deviceName: String): Boolean {
        val key = deviceName.trim()
        if (key.isBlank()) return false
        // If a request is already in-flight, do not overwrite it. This prevents a burst of
        // retries from spamming the OS prompt or losing timing information.
        val now = System.currentTimeMillis()
        val created = waiters.putIfAbsent(
            key,
            Waiter(
                latch = CountDownLatch(1),
                granted = AtomicBoolean(false),
                requestedAtMs = now,
                responded = AtomicBoolean(false),
                completedAtMs = AtomicLong(0L),
                timedOut = AtomicBoolean(false),
            )
        ) == null
        if (created) {
            last[key] = Snapshot(
                deviceName = key,
                requestedAtMs = now,
                responded = false,
                granted = null,
                completedAtMs = null,
                timedOut = false,
            )
            trimLast()
        }
        return created
    }

    fun complete(deviceName: String, granted: Boolean) {
        val key = deviceName.trim()
        val w = waiters[key] ?: return
        val now = System.currentTimeMillis()
        w.granted.set(granted)
        w.responded.set(true)
        w.completedAtMs.set(now)
        w.timedOut.set(false)
        w.latch.countDown()
        last[key] = Snapshot(
            deviceName = key,
            requestedAtMs = w.requestedAtMs,
            responded = true,
            granted = granted,
            completedAtMs = now,
            timedOut = false,
        )
        trimLast()
    }

    fun await(deviceName: String, timeoutMs: Long): Boolean {
        val key = deviceName.trim()
        val w = waiters[key] ?: return false
        try {
            if (timeoutMs <= 0L) {
                w.latch.await()
            } else {
                val ok = w.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                if (!ok && !w.responded.get()) {
                    w.timedOut.set(true)
                    last[key] = Snapshot(
                        deviceName = key,
                        requestedAtMs = w.requestedAtMs,
                        responded = false,
                        granted = null,
                        completedAtMs = null,
                        timedOut = true,
                    )
                    trimLast()
                }
            }
        } catch (_: InterruptedException) {
            // ignore
        }
        return w.granted.get()
    }

    fun clear(deviceName: String) {
        val key = deviceName.trim()
        if (key.isBlank()) return
        waiters.remove(key)
    }

    fun snapshot(deviceName: String): Snapshot? {
        val key = deviceName.trim()
        if (key.isBlank()) return null
        val w = waiters[key]
        if (w != null) {
            val completed = w.completedAtMs.get().takeIf { it > 0L }
            val granted: Boolean? = if (w.responded.get()) w.granted.get() else null
            return Snapshot(
                deviceName = key,
                requestedAtMs = w.requestedAtMs,
                responded = w.responded.get(),
                granted = granted,
                completedAtMs = completed,
                timedOut = w.timedOut.get(),
            )
        }
        return last[key]
    }

    fun pendingSnapshots(): List<Snapshot> {
        val out = ArrayList<Snapshot>()
        for ((k, w) in waiters.entries) {
            // Only consider truly pending (no broadcast yet).
            if (w.responded.get()) continue
            out.add(
                Snapshot(
                    deviceName = k,
                    requestedAtMs = w.requestedAtMs,
                    responded = false,
                    granted = null,
                    completedAtMs = null,
                    timedOut = w.timedOut.get(),
                )
            )
        }
        // Stable ordering: oldest first.
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
            .map { it.deviceName }
            .toHashSet()
        for (id in last.keys) {
            if (!keep.contains(id)) last.remove(id)
        }
    }
}
