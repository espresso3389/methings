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
import android.media.RingtoneManager
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentService : LifecycleService() {
    private lateinit var runtimeManager: TermuxWorkerManager
    private lateinit var termuxManager: TermuxManager
    private var localServer: LocalHttpServer? = null
    private var vaultServer: KeystoreVaultServer? = null
    private val tickExecutor = Executors.newSingleThreadScheduledExecutor()
    private var permissionReceiverRegistered = false
    private var brainWorkNotificationShown = false
    private var lastBrainWorkCheckAtMs: Long = 0L
    private var wasBrainBusy = false

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
        extractor.extractServerAssets()
        jp.espresso3389.methings.db.PlainDbProvider.get(this)
        CaBundleManager(this).ensureSeeded()
        termuxManager = TermuxManager(this)
        runtimeManager = TermuxWorkerManager(this)
        localServer = LocalHttpServer(
            this,
            this,
            runtimeManager,
            termuxManager
        ).also {
            it.startServer()
        }
        vaultServer = KeystoreVaultServer(this).apply { start() }
        registerPermissionPromptReceiver()
        startForegroundCompat(buildNotification())
        tickExecutor.scheduleAtFixedRate({ tickBrainWorkNotification() }, 3, 6, TimeUnit.SECONDS)
        // Register FCM token with notify gateway for push notifications.
        FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
            Thread {
                try {
                    val deviceId = jp.espresso3389.methings.perm.InstallIdentity(applicationContext).get()
                    NotifyGatewayClient.registerDevice(applicationContext, deviceId, fcmToken)
                } catch (e: Exception) {
                    android.util.Log.e("AgentService", "FCM registration failed", e)
                }
            }.start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WORKER -> {
                android.util.Log.i("AgentService", "Start worker action received")
                runtimeManager.startWorker()
            }
            ACTION_RESTART_WORKER -> {
                android.util.Log.i("AgentService", "Restart worker action received")
                runtimeManager.restartSoft()
            }
            ACTION_STOP_WORKER -> {
                android.util.Log.i("AgentService", "Stop worker action received")
                runtimeManager.requestShutdown()
            }
            ACTION_STOP_SERVICE -> {
                android.util.Log.i("AgentService", "Stop service action received")
                stopSelf()
            }
            else -> {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterPermissionPromptReceiver()
        vaultServer?.stop()
        vaultServer = null
        tickExecutor.shutdownNow()
        localServer?.stopServer()
        localServer = null
        runtimeManager.stop()
        super.onDestroy()
    }

    private fun tickBrainWorkNotification() {
        try {
            val now = System.currentTimeMillis()
            if (now - lastBrainWorkCheckAtMs < 6000) return
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

            // Query the worker brain status (best-effort).
            val raw = try {
                val url = java.net.URL("http://127.0.0.1:${TermuxManager.WORKER_PORT}/brain/status")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 300
                    readTimeout = 400
                }
                val txt = runCatching { conn.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
                conn.disconnect()
                txt
            } catch (_: java.net.SocketTimeoutException) {
                "timeout"
            } catch (_: Exception) {
                ""
            }
            val timedOut = raw == "timeout"
            val st = if (timedOut) null else runCatching { org.json.JSONObject(raw) }.getOrNull()
            val busy = timedOut || (st?.optBoolean("busy", false) ?: false)
            val q = st?.optInt("queue_size", 0) ?: 0
            val shouldShow = busy || q > 0
            if (shouldShow) {
                wasBrainBusy = true
            } else {
                if (wasBrainBusy) {
                    wasBrainBusy = false
                    onBrainTaskCompleted()
                }
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
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("methings")
                .setContentText("Agent is working")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .addAction(NotificationCompat.Action(0, "Interrupt", interruptPi))
                .build()
            nm.notify(id, notif)
            brainWorkNotificationShown = true
        } catch (_: Exception) {
        }
    }

    private fun onBrainTaskCompleted() {
        if (AppForegroundState.isForeground) return
        val prefs = getSharedPreferences("task_completion_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notify_android", true)) {
            showTaskCompleteNotification()
        }
        if (prefs.getBoolean("notify_sound", false)) {
            playTaskCompleteSound()
        }
        val webhookUrl = prefs.getString("notify_webhook_url", "") ?: ""
        if (webhookUrl.isNotBlank()) {
            fireWebhook(webhookUrl)
        }
    }

    private fun showTaskCompleteNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                TASK_COMPLETE_CHANNEL_ID,
                "Task Completion",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(ch)
        }
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPi = PendingIntent.getActivity(
            this,
            TASK_COMPLETE_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, TASK_COMPLETE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("methings")
            .setContentText("Agent task completed")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .build()
        nm.notify(TASK_COMPLETE_NOTIFICATION_ID, notif)
    }

    private fun playTaskCompleteSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(applicationContext, uri)?.play()
        } catch (_: Exception) {
        }
    }

    private fun fireWebhook(url: String) {
        Thread {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                }
                val body = org.json.JSONObject().apply {
                    put("event", "task_completed")
                    put("timestamp", System.currentTimeMillis())
                    put("source", "methings")
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
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

    // Tools that require native biometric/PIN consent.
    private val biometricTools = setOf("credentials")

    private fun enqueuePermissionPrompt(prompt: PermissionPrompt) {
        synchronized(permissionNotifLock) {
            val id = prompt.id.trim()
            if (id.isBlank()) return

            if (biometricTools.contains(prompt.tool)) {
                showBiometricPermissionNotification(prompt)
                return
            }

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

    private fun showSummaryPermissionNotification() {
        if (AppForegroundState.isForeground) return
        val channelId = "permission_prompts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Permission Prompts", NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val total = 1 + permissionPromptQueue.size
        val text = if (total == 1) "1 permission waiting for review" else "$total permissions waiting for review"

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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java).notify(PERMISSION_NOTIFICATION_ID, notif)
    }

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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(prompt.id.hashCode(), notif)
    }

    private fun buildNotification(): Notification {
        val channelId = "agent_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "me.things Agent Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("me.things Agent Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                PendingIntent.getService(
                    this,
                    1003,
                    Intent(this, AgentService::class.java).apply { action = ACTION_STOP_SERVICE },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val PERMISSION_NOTIFICATION_ID = 200201
        private const val TASK_COMPLETE_NOTIFICATION_ID = 82020
        private const val TASK_COMPLETE_CHANNEL_ID = "task_completion"
        private const val ACTION_PERMISSION_NOTIFICATION_DISMISSED =
            "jp.espresso3389.methings.action.PERMISSION_NOTIFICATION_DISMISSED"
        const val ACTION_START_WORKER = "jp.espresso3389.methings.action.START_WORKER"
        const val ACTION_RESTART_WORKER = "jp.espresso3389.methings.action.RESTART_WORKER"
        const val ACTION_STOP_WORKER = "jp.espresso3389.methings.action.STOP_WORKER"
        const val ACTION_STOP_SERVICE = "jp.espresso3389.methings.action.STOP_SERVICE"
    }
}
