package jp.espresso3389.methings.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import jp.espresso3389.methings.R
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SshNoAuthRequest(
    val id: String,
    val user: String,
    val addr: String,
    val createdAt: Long
)

class SshNoAuthPromptManager(private val context: Context) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val pending = ConcurrentHashMap<String, SshNoAuthRequest>()
    private val promptDir = File(context.filesDir, "protected/ssh/noauth_prompts")
    private val prefs = context.getSharedPreferences(SshdManager.PREFS, Context.MODE_PRIVATE)

    fun start() {
        executor.scheduleAtFixedRate({ pollOnce() }, 1, 2, TimeUnit.SECONDS)
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun pollOnce() {
        if (!prefs.getBoolean(SshdManager.KEY_NOAUTH, false)) {
            for (id in pending.keys) {
                cancelNotification(id)
            }
            pending.clear()
            return
        }
        val requests = fetchRequests()
        val activeIds = requests.map { it.id }.toSet()
        for (req in requests) {
            if (pending.putIfAbsent(req.id, req) == null) {
                showNotification(req)
            }
        }
        for (id in pending.keys) {
            if (!activeIds.contains(id)) {
                pending.remove(id)
                cancelNotification(id)
            }
        }
    }

    private fun fetchRequests(): List<SshNoAuthRequest> {
        if (!promptDir.exists()) {
            return emptyList()
        }
        val out = mutableListOf<SshNoAuthRequest>()
        val files = promptDir.listFiles { f -> f.isFile && f.name.endsWith(".req") } ?: return out
        for (file in files) {
            val line = try {
                file.readLines().firstOrNull() ?: continue
            } catch (_: Exception) {
                continue
            }
            val parts = line.split("\t")
            if (parts.isEmpty()) continue
            val id = parts.getOrNull(0)?.trim().orEmpty()
            if (id.isBlank()) continue
            val user = parts.getOrNull(1)?.trim().orEmpty()
            val addr = parts.getOrNull(2)?.trim().orEmpty()
            val createdAt = parts.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L
            out.add(SshNoAuthRequest(id, user, addr, createdAt))
        }
        return out
    }

    private fun showNotification(req: SshNoAuthRequest) {
        val channelId = "ssh_noauth_prompt"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SSH Login Requests",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val allowIntent = Intent(context, SshNoAuthReceiver::class.java).apply {
            action = SshNoAuthReceiver.ACTION_RESPOND
            putExtra(SshNoAuthReceiver.EXTRA_ID, req.id)
            putExtra(SshNoAuthReceiver.EXTRA_ALLOW, true)
        }
        val denyIntent = Intent(context, SshNoAuthReceiver::class.java).apply {
            action = SshNoAuthReceiver.ACTION_RESPOND
            putExtra(SshNoAuthReceiver.EXTRA_ID, req.id)
            putExtra(SshNoAuthReceiver.EXTRA_ALLOW, false)
        }
        val allowPending = PendingIntent.getBroadcast(
            context,
            req.id.hashCode(),
            allowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyPending = PendingIntent.getBroadcast(
            context,
            -req.id.hashCode(),
            denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "SSH login request"
        val who = if (req.user.isNotBlank()) req.user else "unknown"
        val host = if (req.addr.isNotBlank()) req.addr else "unknown"
        val message = "$who from $host"
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_input_add, "Allow", allowPending)
            .addAction(android.R.drawable.ic_delete, "Deny", denyPending)
            .build()
        manager.notify(req.id.hashCode(), notification)
    }

    private fun cancelNotification(id: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(id.hashCode())
    }
}
