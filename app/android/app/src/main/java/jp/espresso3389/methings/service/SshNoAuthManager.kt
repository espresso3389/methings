package jp.espresso3389.methings.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import jp.espresso3389.methings.R
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SshNoAuthManager(private val context: Context) {

    @Volatile private var active = false
    @Volatile private var expiresAt: Long = 0L

    private val pendingRequests = ConcurrentHashMap<String, CountDownLatch>()
    private val responses = ConcurrentHashMap<String, Boolean>()
    private val recentApprovals = ConcurrentHashMap<String, Long>()
    private val seenPromptIds = ConcurrentHashMap<String, Long>()
    private val watcher = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var watcherTask: ScheduledFuture<*>? = null

    fun start(ttlSeconds: Int = DEFAULT_TTL): NoAuthState {
        val ttl = ttlSeconds.coerceIn(MIN_TTL, MAX_TTL)
        expiresAt = System.currentTimeMillis() + ttl * 1000L
        active = true
        instance = this
        startPromptWatcher()
        return NoAuthState(active = true, expiresAt = expiresAt)
    }

    fun stop() {
        active = false
        expiresAt = 0L
        watcherTask?.cancel(true)
        watcherTask = null
        // Release any blocked requests
        for ((id, latch) in pendingRequests) {
            responses[id] = false
            latch.countDown()
        }
        pendingRequests.clear()
        responses.clear()
        recentApprovals.clear()
        seenPromptIds.clear()
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
        writePromptResponse(context, requestId, allow)
        pendingRequests[requestId]?.countDown()
        cancelNotification(requestId)
    }

    private fun startPromptWatcher() {
        if (watcherTask?.isCancelled == false && watcherTask?.isDone == false) return
        watcherTask = watcher.scheduleWithFixedDelay(
            { scanPromptDir() },
            0L,
            300L,
            TimeUnit.MILLISECONDS
        )
    }

    private fun scanPromptDir() {
        if (!isActive()) return
        val dir = promptDir(context)
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.isFile || !f.name.endsWith(".req")) continue
            val firstLine = try {
                f.bufferedReader().use { it.readLine()?.trim().orEmpty() }
            } catch (_: Exception) {
                ""
            }
            if (firstLine.isBlank()) continue
            val parts = firstLine.split('\t')
            if (parts.isEmpty()) continue
            val reqId = parts[0].trim()
            if (!reqId.matches(ID_RE)) continue
            if (seenPromptIds.putIfAbsent(reqId, now) != null) continue
            val user = parts.getOrNull(1)?.trim().orEmpty()
            val addr = parts.getOrNull(2)?.trim().orEmpty()
            val who = if (addr.isBlank()) user else "$user ($addr)"
            showApprovalNotification(reqId, who)
        }
        // Prune old seen IDs to bound memory.
        val it = seenPromptIds.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > SEEN_PROMPT_TTL_MS) it.remove()
        }
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
        private const val SEEN_PROMPT_TTL_MS = 2 * 60_000L
        private const val TAG = "SshNoAuthManager"
        private val ID_RE = Regex("^[A-Za-z0-9_:-]+$")

        private fun promptDir(context: Context): File {
            return File(context.filesDir, "protected/ssh/noauth_prompts")
        }

        fun writePromptResponse(context: Context, requestId: String, allow: Boolean) {
            if (!requestId.matches(ID_RE)) return
            try {
                val dir = promptDir(context)
                dir.mkdirs()
                val resp = File(dir, "$requestId.resp")
                resp.writeText(if (allow) "allow\n" else "deny\n")
                resp.setReadable(true, true)
                resp.setWritable(true, true)
            } catch (ex: Exception) {
                Log.w(TAG, "Failed writing noauth response for $requestId", ex)
            }
        }
    }
}
