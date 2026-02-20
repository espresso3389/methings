package jp.espresso3389.methings.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import jp.espresso3389.methings.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SshNoAuthManager(private val context: Context) {

    @Volatile private var active = false
    @Volatile private var expiresAt: Long = 0L

    private val pendingRequests = ConcurrentHashMap<String, CountDownLatch>()
    private val responses = ConcurrentHashMap<String, Boolean>()
    private val recentApprovals = ConcurrentHashMap<String, Long>()

    fun start(ttlSeconds: Int = DEFAULT_TTL): NoAuthState {
        val ttl = ttlSeconds.coerceIn(MIN_TTL, MAX_TTL)
        expiresAt = System.currentTimeMillis() + ttl * 1000L
        active = true
        instance = this
        return NoAuthState(active = true, expiresAt = expiresAt)
    }

    fun stop() {
        active = false
        expiresAt = 0L
        // Release any blocked requests
        for ((id, latch) in pendingRequests) {
            responses[id] = false
            latch.countDown()
        }
        pendingRequests.clear()
        responses.clear()
        recentApprovals.clear()
        if (instance === this) instance = null
    }

    fun status(): NoAuthState {
        if (!active) return NoAuthState(active = false, expiresAt = null)
        if (expiresAt <= System.currentTimeMillis()) {
            return NoAuthState(active = false, expiresAt = expiresAt, expired = true)
        }
        return NoAuthState(active = true, expiresAt = expiresAt)
    }

    fun isActive(): Boolean {
        if (!active) return false
        if (expiresAt <= System.currentTimeMillis()) {
            active = false
            return false
        }
        return true
    }

    /**
     * Called from the /sshd/auth/keys handler when notification mode is active.
     * Blocks the calling thread until the user responds or timeout.
     * Returns true if the user approved.
     */
    fun waitForApproval(requestId: String, user: String, timeoutMs: Long = 30_000L): Boolean {
        // Dedup: if the same fingerprint was recently approved, skip notification
        val now = System.currentTimeMillis()
        val recentTs = recentApprovals[requestId]
        if (recentTs != null && (now - recentTs) < RECENT_APPROVAL_WINDOW_MS) {
            return true
        }

        val latch = CountDownLatch(1)
        pendingRequests[requestId] = latch
        responses.remove(requestId)

        showApprovalNotification(requestId, user)

        try {
            val answered = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!answered) {
                cancelNotification(requestId)
                return false
            }
        } finally {
            pendingRequests.remove(requestId)
        }

        val allowed = responses.remove(requestId) ?: false
        if (allowed) {
            recentApprovals[requestId] = System.currentTimeMillis()
        }
        return allowed
    }

    /**
     * Called from SshNoAuthReceiver when user taps Allow/Deny.
     */
    fun respond(requestId: String, allow: Boolean) {
        responses[requestId] = allow
        pendingRequests[requestId]?.countDown()
        cancelNotification(requestId)
    }

    private fun showApprovalNotification(requestId: String, user: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SSH Login Requests",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

        val allowIntent = Intent(context, SshNoAuthReceiver::class.java).apply {
            action = SshNoAuthReceiver.ACTION_RESPOND
            putExtra(SshNoAuthReceiver.EXTRA_ID, requestId)
            putExtra(SshNoAuthReceiver.EXTRA_ALLOW, true)
        }
        val denyIntent = Intent(context, SshNoAuthReceiver::class.java).apply {
            action = SshNoAuthReceiver.ACTION_RESPOND
            putExtra(SshNoAuthReceiver.EXTRA_ID, requestId)
            putExtra(SshNoAuthReceiver.EXTRA_ALLOW, false)
        }
        val allowPi = PendingIntent.getBroadcast(
            context,
            requestId.hashCode(),
            allowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyPi = PendingIntent.getBroadcast(
            context,
            -requestId.hashCode(),
            denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val who = user.ifBlank { "unknown" }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("SSH login request")
            .setContentText("User: $who")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_input_add, "Allow", allowPi)
            .addAction(android.R.drawable.ic_delete, "Deny", denyPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(requestId.hashCode(), notif)
    }

    private fun cancelNotification(requestId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(requestId.hashCode())
    }

    data class NoAuthState(
        val active: Boolean,
        val expiresAt: Long?,
        val expired: Boolean = false
    )

    companion object {
        @Volatile var instance: SshNoAuthManager? = null
        private const val CHANNEL_ID = "ssh_noauth_prompt"
        private const val DEFAULT_TTL = 30
        private const val MIN_TTL = 5
        private const val MAX_TTL = 600
        private const val RECENT_APPROVAL_WINDOW_MS = 5_000L
    }
}
