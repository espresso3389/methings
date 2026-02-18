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

    private data class Waiter(
        val latch: CountDownLatch,
        val permissions: List<String>,
        @Volatile var results: Map<String, Boolean>? = null,
    )

    private val waiters = ConcurrentHashMap<String, Waiter>()

    fun begin(requestId: String, permissions: List<String>): Boolean {
        if (requestId.isBlank() || permissions.isEmpty()) return false
        return waiters.putIfAbsent(
            requestId,
            Waiter(latch = CountDownLatch(1), permissions = permissions)
        ) == null
    }

    fun complete(requestId: String, results: Map<String, Boolean>) {
        val w = waiters[requestId] ?: return
        w.results = results
        w.latch.countDown()
    }

    fun await(requestId: String, timeoutMs: Long): Map<String, Boolean>? {
        val w = waiters[requestId] ?: return null
        try {
            if (timeoutMs <= 0L) {
                w.latch.await()
            } else {
                w.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            // ignore
        }
        return w.results
    }

    fun clear(requestId: String) {
        waiters.remove(requestId)
    }
}
