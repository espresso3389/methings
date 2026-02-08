package jp.espresso3389.methings.device

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide coordination for waiting on Android's OS-level USB permission prompt.
 *
 * The OS delivers results via a broadcast PendingIntent. We need a stable place to store latches
 * so requesters (LocalHttpServer) can synchronously wait for allow/deny.
 */
object UsbPermissionWaiter {
    private data class Waiter(
        val latch: CountDownLatch,
        val granted: AtomicBoolean,
    )

    private val waiters = ConcurrentHashMap<String, Waiter>()

    fun begin(deviceName: String): Boolean {
        val key = deviceName.trim()
        if (key.isBlank()) return false
        waiters[key] = Waiter(CountDownLatch(1), AtomicBoolean(false))
        return true
    }

    fun complete(deviceName: String, granted: Boolean) {
        val key = deviceName.trim()
        val w = waiters[key] ?: return
        w.granted.set(granted)
        w.latch.countDown()
    }

    fun await(deviceName: String, timeoutMs: Long): Boolean {
        val key = deviceName.trim()
        val w = waiters[key] ?: return false
        try {
            if (timeoutMs <= 0L) {
                w.latch.await()
            } else {
                w.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
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
}

