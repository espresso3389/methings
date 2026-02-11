package jp.espresso3389.methings.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.content.pm.ServiceInfo
import java.util.ArrayDeque
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import jp.espresso3389.methings.AppForegroundState
import jp.espresso3389.methings.R
import jp.espresso3389.methings.ui.MainActivity
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
    private var brainWorkNotificationShown = false
    private var lastBrainWorkCheckAtMs: Long = 0L

    // Permission prompt notifications:
    // - Show exactly one notification at a time.
    // - Queue subsequent prompts and show them one-by-one once the current is resolved or dismissed.
    data class PermissionPrompt(val id: String, val tool: String, val detail: String)
    private val permissionPromptQueue: ArrayDeque<PermissionPrompt> = ArrayDeque()
    private var activePermissionPrompt: PermissionPrompt? = null
    private val permissionNotifLock = Any()
    private val permissionPromptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_ID) ?: return
            val tool = intent.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_TOOL) ?: "unknown"
            val detail = intent.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_DETAIL) ?: ""
            enqueuePermissionPrompt(PermissionPrompt(id, tool, detail))
        }
    }

    private val permissionResolvedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_ID) ?: return
            val status = intent.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_STATUS) ?: ""
            onPermissionResolved(id, status)
        }
    }

    private val permissionNotificationDismissedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_ID) ?: return
            onPermissionNotificationDismissed(id)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val extractor = AssetExtractor(this)
        extractor.extractUiAssetsIfMissing()
        extractor.extractUserDefaultsIfMissing()
        extractor.extractNodeAssetsIfMissing()
        extractor.extractDropbearIfMissing()
        // Ensure Python runtime is installed for SSH/pip subprocesses (build isolation) even if the
        // main Python worker hasn't been started yet.
        PythonRuntimeInstaller(this).ensureInstalled()
        jp.espresso3389.methings.db.PlainDbProvider.get(this)
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
        startForegroundCompat(buildNotification())
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

            // Watchdog: restart SSHD if it was killed externally (e.g. Phantom Process Killer)
            sshd.ensureRunning()

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
            startForegroundCompat(fg)

            tickBrainWorkNotification()
        } catch (_: Exception) {
        }
    }

    private fun tickBrainWorkNotification() {
        val now = System.currentTimeMillis()
        if (now - lastBrainWorkCheckAtMs < 2500) return
        lastBrainWorkCheckAtMs = now

        val nm = getSystemService(NotificationManager::class.java)
        val id = 82010
        if (AppForegroundState.isForeground) {
            if (brainWorkNotificationShown) {
                nm.cancel(id)
                brainWorkNotificationShown = false
            }
            return
        }

        // Query the python worker brain status (best-effort).
        val raw = try {
            val url = java.net.URL("http://127.0.0.1:8776/brain/status")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1200
                readTimeout = 1800
            }
            val txt = runCatching { conn.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            conn.disconnect()
            txt
        } catch (_: Exception) {
            ""
        }
        val st = runCatching { org.json.JSONObject(raw) }.getOrNull()
        val busy = st?.optBoolean("busy", false) ?: false
        val q = st?.optInt("queue_size", 0) ?: 0
        val shouldShow = busy || q > 0
        if (!shouldShow) {
            if (brainWorkNotificationShown) {
                nm.cancel(id)
                brainWorkNotificationShown = false
            }
            return
        }

        val channelId = "methings_agent_work"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Agent Activity", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPi = PendingIntent.getActivity(
            this,
            82010,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val interruptIntent = Intent(this, BrainInterruptReceiver::class.java).apply {
            action = BrainInterruptReceiver.ACTION_INTERRUPT
        }
        val interruptPi = PendingIntent.getBroadcast(
            this,
            82011,
            interruptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("methings")
            .setContentText("Agent is working")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .addAction(NotificationCompat.Action(0, "Interrupt", interruptPi))
            .build()
        nm.notify(id, notif)
        brainWorkNotificationShown = true
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Declare only types we are currently eligible for.
            //
            // On Android 14+ a foreground service started with type MICROPHONE/CAMERA/etc can throw
            // SecurityException if the corresponding runtime permission isn't granted yet.
            //
            // We keep the manifest declaration broad (so features can work), but keep the runtime
            // startForeground(...) types conservative and permission-aware.
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // Connected-device is only safe to declare when the relevant runtime permission is granted.
            // This keeps us compatible with Android 12+ Bluetooth runtime permission model.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
            }
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
            registerReceiver(
                permissionResolvedReceiver,
                IntentFilter(LocalHttpServer.ACTION_PERMISSION_RESOLVED),
                Context.RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                permissionNotificationDismissedReceiver,
                IntentFilter(ACTION_PERMISSION_NOTIFICATION_DISMISSED),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(permissionPromptReceiver, IntentFilter(LocalHttpServer.ACTION_PERMISSION_PROMPT))
            registerReceiver(permissionResolvedReceiver, IntentFilter(LocalHttpServer.ACTION_PERMISSION_RESOLVED))
            registerReceiver(permissionNotificationDismissedReceiver, IntentFilter(ACTION_PERMISSION_NOTIFICATION_DISMISSED))
        }
    }

    private fun unregisterPermissionPromptReceiver() {
        if (!permissionReceiverRegistered) return
        permissionReceiverRegistered = false
        try {
            unregisterReceiver(permissionPromptReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(permissionResolvedReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(permissionNotificationDismissedReceiver)
        } catch (_: Exception) {
        }
    }

    // Tools that require native biometric/PIN consent (keep individual notification flow).
    private val biometricTools = setOf("credentials", "ssh_keys", "ssh_pin")

    private fun enqueuePermissionPrompt(prompt: PermissionPrompt) {
        synchronized(permissionNotifLock) {
            val id = prompt.id.trim()
            if (id.isBlank()) return

            if (biometricTools.contains(prompt.tool)) {
                // Biometric permissions: use the individual notification + PermissionBroker flow.
                showBiometricPermissionNotification(prompt)
                return
            }

            // Non-biometric: track and show a single summary notification.
            // Avoid duplicates.
            if (activePermissionPrompt?.id == id) return
            if (permissionPromptQueue.any { it.id == id }) return
            permissionPromptQueue.addLast(prompt)
            if (activePermissionPrompt == null) {
                activePermissionPrompt = prompt
                permissionPromptQueue.removeFirst()
            }
            showSummaryPermissionNotification()
        }
    }

    private fun onPermissionResolved(id: String, status: String) {
        synchronized(permissionNotifLock) {
            // Drop from queue.
            if (permissionPromptQueue.isNotEmpty()) {
                val it = permissionPromptQueue.iterator()
                while (it.hasNext()) {
                    if (it.next().id == id) { it.remove(); break }
                }
            }
            if (activePermissionPrompt?.id == id) {
                activePermissionPrompt = null
                if (permissionPromptQueue.isNotEmpty()) {
                    activePermissionPrompt = permissionPromptQueue.removeFirst()
                }
            }
            if (activePermissionPrompt != null || permissionPromptQueue.isNotEmpty()) {
                showSummaryPermissionNotification()
            } else {
                cancelPermissionNotification()
            }
        }
    }

    private fun onPermissionNotificationDismissed(id: String) {
        // Summary notification dismissed: do nothing (permissions stay pending,
        // user will see the in-chat permission card when they open the app).
    }

    private fun cancelPermissionNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(PERMISSION_NOTIFICATION_ID)
    }

    /**
     * Show a single summary notification for all non-biometric pending permissions.
     * Tapping it just opens the app (no PermissionBroker dialog).
     */
    private fun showSummaryPermissionNotification() {
        // Skip notification when the app is in the foreground — user sees in-chat permission cards.
        if (AppForegroundState.isForeground) return
        val channelId = "permission_prompts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Permission Prompts", NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val total = 1 + permissionPromptQueue.size  // active + queued
        val text = if (total == 1) "1 permission waiting for review" else "$total permissions waiting for review"

        // Tap: just open the app (no permission extras → no PermissionBroker).
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, PERMISSION_NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Permission required")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java).notify(PERMISSION_NOTIFICATION_ID, notif)
    }

    /**
     * Show an individual notification for biometric-required permissions.
     * Tapping opens PermissionBroker as before.
     */
    private fun showBiometricPermissionNotification(prompt: PermissionPrompt) {
        val channelId = "permission_prompts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Permission Prompts", NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(LocalHttpServer.EXTRA_PERMISSION_ID, prompt.id)
            putExtra(LocalHttpServer.EXTRA_PERMISSION_TOOL, prompt.tool)
            putExtra(LocalHttpServer.EXTRA_PERMISSION_DETAIL, prompt.detail)
            putExtra(LocalHttpServer.EXTRA_PERMISSION_BIOMETRIC, true)
        }
        val openPi = PendingIntent.getActivity(
            this, prompt.id.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (prompt.detail.isBlank()) prompt.tool else "${prompt.tool}: ${prompt.detail}"
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Authentication required")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Use a separate notification ID for biometric prompts so they don't collide with summary.
        getSystemService(NotificationManager::class.java)
            .notify(prompt.id.hashCode(), notif)
    }

    private fun buildNotification(): Notification {
        return buildForegroundNotification("", null)
    }

    private fun buildForegroundNotification(activeMode: String, expiresAt: Long?): Notification {
        val channelId = "agent_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "methings Agent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("methings Agent Service")
            .setContentText(foregroundText(activeMode, expiresAt))
            .setSmallIcon(R.mipmap.ic_launcher)
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
        private const val PERMISSION_NOTIFICATION_ID = 200201
        private const val ACTION_PERMISSION_NOTIFICATION_DISMISSED =
            "jp.espresso3389.methings.action.PERMISSION_NOTIFICATION_DISMISSED"
        const val ACTION_START_PYTHON = "jp.espresso3389.methings.action.START_PYTHON"
        const val ACTION_RESTART_PYTHON = "jp.espresso3389.methings.action.RESTART_PYTHON"
        const val ACTION_STOP_PYTHON = "jp.espresso3389.methings.action.STOP_PYTHON"
        const val ACTION_SSH_PIN_STOP = "jp.espresso3389.methings.action.SSH_PIN_STOP"
        const val ACTION_SSH_NOAUTH_STOP = "jp.espresso3389.methings.action.SSH_NOAUTH_STOP"
    }
}
