package jp.espresso3389.kugutz.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AgentService : Service() {
    private lateinit var runtimeManager: PythonRuntimeManager

    override fun onCreate() {
        super.onCreate()
        runtimeManager = PythonRuntimeManager(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        runtimeManager.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESTART_PYTHON -> {
                android.util.Log.i("AgentService", "Restart action received")
                runtimeManager.restartSoft()
            }
            ACTION_STOP_PYTHON -> {
                android.util.Log.i("AgentService", "Stop action received")
                runtimeManager.requestShutdown()
            }
            else -> {}
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        runtimeManager.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "agent_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Agent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Agent Service")
            .setContentText("Running local Python service")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_RESTART_PYTHON = "jp.espresso3389.kugutz.action.RESTART_PYTHON"
        const val ACTION_STOP_PYTHON = "jp.espresso3389.kugutz.action.STOP_PYTHON"
    }
}
