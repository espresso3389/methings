package jp.espresso3389.kugutz.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

    fun start() {
        executor.scheduleAtFixedRate({ pollOnce() }, 1, 2, TimeUnit.SECONDS)
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun pollOnce() {
        val requests = fetchRequests() ?: return
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

    private fun fetchRequests(): List<SshNoAuthRequest>? {
        val url = URL("http://127.0.0.1:8765/ssh/noauth/requests")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 1000
        conn.readTimeout = 1000
        return try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val arr = json.optJSONArray("requests") ?: JSONArray()
            val out = mutableListOf<SshNoAuthRequest>()
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val id = row.optString("id")
                if (id.isBlank()) continue
                val user = row.optString("user")
                val addr = row.optString("addr")
                val createdAt = row.optLong("created_at", 0L)
                out.add(SshNoAuthRequest(id, user, addr, createdAt))
            }
            out
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
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
            .setSmallIcon(android.R.drawable.stat_sys_warning)
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
