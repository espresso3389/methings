package jp.espresso3389.kugutz.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import jp.espresso3389.kugutz.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentService : LifecycleService() {
    private lateinit var runtimeManager: PythonRuntimeManager
    private var localServer: LocalHttpServer? = null
    private var vaultServer: KeystoreVaultServer? = null
    private var sshdManager: SshdManager? = null
    private var sshPinManager: SshPinManager? = null
    private var sshNoAuthModeManager: SshNoAuthModeManager? = null
    private var noAuthPromptManager: SshNoAuthPromptManager? = null
    private val sshAuthExecutor = Executors.newSingleThreadScheduledExecutor()
    private var lastActiveAuthMode: String = ""
    private var permissionReceiverRegistered = false
    private val permissionPromptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_ID) ?: return
            val tool = intent.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_TOOL) ?: "unknown"
            val detail = intent.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_DETAIL) ?: ""
            showPermissionNotification(id, tool, detail)
        }
    }

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
        sshPinManager = SshPinManager(this)
        sshNoAuthModeManager = SshNoAuthModeManager(this)
        noAuthPromptManager = SshNoAuthPromptManager(this).also { it.start() }
        startSshAuthMonitor()
        localServer = LocalHttpServer(
            this,
            this,
            runtimeManager,
            sshdManager!!,
            sshPinManager!!,
            sshNoAuthModeManager!!
        ).also {
            it.startServer()
        }
        vaultServer = KeystoreVaultServer(this).apply { start() }
        registerPermissionPromptReceiver()
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
            ACTION_SSH_PIN_STOP -> {
                android.util.Log.i("AgentService", "SSH PIN stop requested")
                sshPinManager?.stopPin()
                sshdManager?.exitPinMode()
            }
            ACTION_SSH_NOAUTH_STOP -> {
                android.util.Log.i("AgentService", "SSH notification auth stop requested")
                sshNoAuthModeManager?.stop()
                sshdManager?.exitNotificationMode()
            }
            else -> {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterPermissionPromptReceiver()
        vaultServer?.stop()
        vaultServer = null
        sshAuthExecutor.shutdownNow()
        noAuthPromptManager?.stop()
        noAuthPromptManager = null
        sshNoAuthModeManager = null
        sshPinManager = null
        sshdManager?.stop()
        sshdManager = null
        localServer?.stopServer()
        localServer = null
        runtimeManager.stop()
        super.onDestroy()
    }

    private fun startSshAuthMonitor() {
        sshAuthExecutor.scheduleAtFixedRate({ tickSshAuth() }, 0, 1, TimeUnit.SECONDS)
    }

    private fun tickSshAuth() {
        try {
            val sshd = sshdManager ?: return
            val pinMgr = sshPinManager ?: return
            val noAuthMgr = sshNoAuthModeManager ?: return

            val pin = pinMgr.status()
            if (pin.expired) {
                android.util.Log.i("AgentService", "PIN auth expired; exiting PIN mode")
                pinMgr.stopPin()
                sshd.exitPinMode()
            }

            val noauth = noAuthMgr.status()
            if (noauth.expired) {
                android.util.Log.i("AgentService", "Notification auth expired; exiting notification mode")
                noAuthMgr.stop()
                sshd.exitNotificationMode()
            }

            val authMode = sshd.getAuthMode()
            val activeMode = when {
                authMode == SshdManager.AUTH_MODE_PIN && pin.active -> SshdManager.AUTH_MODE_PIN
                authMode == SshdManager.AUTH_MODE_NOTIFICATION && noauth.active -> SshdManager.AUTH_MODE_NOTIFICATION
                else -> ""
            }

            if (activeMode != lastActiveAuthMode) {
                // Fire a heads-up style alert when enabling a temporary auth mode.
                if (activeMode.isNotBlank()) {
                    showSshAuthAlert(activeMode, when (activeMode) {
                        SshdManager.AUTH_MODE_PIN -> pin.expiresAt
                        SshdManager.AUTH_MODE_NOTIFICATION -> noauth.expiresAt
                        else -> null
                    })
                }
                lastActiveAuthMode = activeMode
            }

            // Keep the (always visible) foreground service notification as the primary status indicator.
            val fg = buildForegroundNotification(
                activeMode,
                when (activeMode) {
                    SshdManager.AUTH_MODE_PIN -> pin.expiresAt
                    SshdManager.AUTH_MODE_NOTIFICATION -> noauth.expiresAt
                    else -> null
                }
            )
            startForeground(NOTIFICATION_ID, fg)
        } catch (_: Exception) {
        }
    }

    private fun registerPermissionPromptReceiver() {
        if (permissionReceiverRegistered) return
        permissionReceiverRegistered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                permissionPromptReceiver,
                IntentFilter(LocalHttpServer.ACTION_PERMISSION_PROMPT),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(permissionPromptReceiver, IntentFilter(LocalHttpServer.ACTION_PERMISSION_PROMPT))
        }
    }

    private fun unregisterPermissionPromptReceiver() {
        if (!permissionReceiverRegistered) return
        permissionReceiverRegistered = false
        try {
            unregisterReceiver(permissionPromptReceiver)
        } catch (_: Exception) {
        }
    }

    private fun showPermissionNotification(id: String, tool: String, detail: String) {
        val channelId = "permission_prompts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Permission Prompts",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(LocalHttpServer.EXTRA_PERMISSION_ID, id)
            putExtra(LocalHttpServer.EXTRA_PERMISSION_TOOL, tool)
            putExtra(LocalHttpServer.EXTRA_PERMISSION_DETAIL, detail)
        }
        val openPi = PendingIntent.getActivity(
            this,
            id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (detail.isBlank()) tool else "$tool: $detail"
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Permission required")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        val notifId = 200000 + ((id.hashCode() and 0x7FFFFFFF) % 99999)
        nm.notify(notifId, notif)
    }

    private fun buildNotification(): Notification {
        return buildForegroundNotification("", null)
    }

    private fun buildForegroundNotification(activeMode: String, expiresAt: Long?): Notification {
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

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Agent Service")
            .setContentText(foregroundText(activeMode, expiresAt))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        when (activeMode) {
            SshdManager.AUTH_MODE_PIN -> builder.addAction(
                android.R.drawable.ic_delete,
                "Stop PIN",
                PendingIntent.getService(
                    this,
                    1001,
                    Intent(this, AgentService::class.java).apply { action = ACTION_SSH_PIN_STOP },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            SshdManager.AUTH_MODE_NOTIFICATION -> builder.addAction(
                android.R.drawable.ic_delete,
                "Stop",
                PendingIntent.getService(
                    this,
                    1002,
                    Intent(this, AgentService::class.java).apply { action = ACTION_SSH_NOAUTH_STOP },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            else -> {}
        }

        return builder.build()
    }

    private fun showSshAuthAlert(activeMode: String, expiresAt: Long?) {
        val channelId = "ssh_auth_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SSH Auth Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val stopAction = when (activeMode) {
            SshdManager.AUTH_MODE_PIN -> NotificationCompat.Action(
                android.R.drawable.ic_delete,
                "Stop PIN",
                PendingIntent.getService(
                    this,
                    2001,
                    Intent(this, AgentService::class.java).apply { action = ACTION_SSH_PIN_STOP },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            SshdManager.AUTH_MODE_NOTIFICATION -> NotificationCompat.Action(
                android.R.drawable.ic_delete,
                "Stop",
                PendingIntent.getService(
                    this,
                    2002,
                    Intent(this, AgentService::class.java).apply { action = ACTION_SSH_NOAUTH_STOP },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            else -> null
        }

        val title = when (activeMode) {
            SshdManager.AUTH_MODE_PIN -> "SSHD: PIN auth enabled"
            SshdManager.AUTH_MODE_NOTIFICATION -> "SSHD: Notification auth enabled"
            else -> "SSHD auth enabled"
        }
        val text = foregroundText(activeMode, expiresAt)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this,
            2003,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPi)
            .setTimeoutAfter(30000)
            .also { b -> if (stopAction != null) b.addAction(stopAction) }
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(82001, notif)
    }

    private fun foregroundText(activeMode: String, expiresAt: Long?): String {
        if (activeMode.isBlank()) return "Running local Python service"
        val remaining = formatRemaining(expiresAt)
        return when (activeMode) {
            SshdManager.AUTH_MODE_PIN -> "SSHD: PIN auth active" + if (remaining.isNotBlank()) " (expires in $remaining)" else ""
            SshdManager.AUTH_MODE_NOTIFICATION -> "SSHD: Notification auth active" + if (remaining.isNotBlank()) " (expires in $remaining)" else ""
            else -> "SSHD: auth active"
        }
    }

    private fun formatRemaining(expiresAt: Long?): String {
        if (expiresAt == null) return ""
        val sec = ((expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_PYTHON = "jp.espresso3389.kugutz.action.START_PYTHON"
        const val ACTION_RESTART_PYTHON = "jp.espresso3389.kugutz.action.RESTART_PYTHON"
        const val ACTION_STOP_PYTHON = "jp.espresso3389.kugutz.action.STOP_PYTHON"
        const val ACTION_SSH_PIN_STOP = "jp.espresso3389.kugutz.action.SSH_PIN_STOP"
        const val ACTION_SSH_NOAUTH_STOP = "jp.espresso3389.kugutz.action.SSH_NOAUTH_STOP"
    }
}
