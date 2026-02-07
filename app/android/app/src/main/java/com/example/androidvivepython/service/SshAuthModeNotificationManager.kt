package jp.espresso3389.kugutz.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class SshAuthModeNotificationManager(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun showPinActive(expiresAt: Long?) {
        ensureChannel()
        val stopPi = PendingIntent.getService(
            context,
            1001,
            Intent(context, AgentService::class.java).apply { action = AgentService.ACTION_SSH_PIN_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val remaining = formatRemaining(expiresAt)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle("SSHD: PIN auth active")
            .setContentText(if (remaining.isNotBlank()) "Expires in $remaining" else "Active")
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .build()
        manager.notify(NOTIFICATION_ID, notif)
    }

    fun showNotificationActive(expiresAt: Long?) {
        ensureChannel()
        val stopPi = PendingIntent.getService(
            context,
            1002,
            Intent(context, AgentService::class.java).apply { action = AgentService.ACTION_SSH_NOAUTH_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val remaining = formatRemaining(expiresAt)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle("SSHD: Notification auth active")
            .setContentText(if (remaining.isNotBlank()) "Expires in $remaining" else "Active")
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .build()
        manager.notify(NOTIFICATION_ID, notif)
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SSH Auth Mode",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun formatRemaining(expiresAt: Long?): String {
        if (expiresAt == null) return ""
        val sec = ((expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    companion object {
        private const val CHANNEL_ID = "ssh_auth_mode"
        private const val NOTIFICATION_ID = 7127
    }
}

