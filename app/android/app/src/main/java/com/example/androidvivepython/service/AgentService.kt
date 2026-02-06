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
    private var localServer: LocalHttpServer? = null
    private var vaultServer: KeystoreVaultServer? = null
    private var sshdManager: SshdManager? = null
    private var noAuthPromptManager: SshNoAuthPromptManager? = null

    override fun onCreate() {
        super.onCreate()
        val extractor = AssetExtractor(this)
        extractor.extractUiAssetsIfMissing()
        extractor.extractUserDefaultsIfMissing()
        extractor.extractDropbearIfMissing()
        // Ensure Python runtime is installed for SSH/pip subprocesses (build isolation) even if the
        // main Python worker hasn't been started yet.
        PythonRuntimeInstaller(this).ensureInstalled()
        jp.espresso3389.kugutz.db.PlainDbProvider.get(this)
        // Make sure we always have a CA bundle file in app-private storage before SSH sessions start.
        // The periodic updater will refresh it when the network is available.
        CaBundleManager(this).ensureSeededFromPyenv(java.io.File(filesDir, "pyenv"))
        runtimeManager = PythonRuntimeManager(this)
        sshdManager = SshdManager(this).also { it.startIfEnabled() }
        noAuthPromptManager = SshNoAuthPromptManager(this).also { it.start() }
        localServer = LocalHttpServer(this, runtimeManager).also {
            it.startServer()
        }
        vaultServer = KeystoreVaultServer(this).apply { start() }
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PYTHON -> {
                android.util.Log.i("AgentService", "Start action received")
                runtimeManager.startWorker()
            }
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
        vaultServer?.stop()
        vaultServer = null
        noAuthPromptManager?.stop()
        noAuthPromptManager = null
        sshdManager?.stop()
        sshdManager = null
        localServer?.stopServer()
        localServer = null
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
        const val ACTION_START_PYTHON = "jp.espresso3389.kugutz.action.START_PYTHON"
        const val ACTION_RESTART_PYTHON = "jp.espresso3389.kugutz.action.RESTART_PYTHON"
        const val ACTION_STOP_PYTHON = "jp.espresso3389.kugutz.action.STOP_PYTHON"
    }
}
