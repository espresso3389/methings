package jp.espresso3389.methings.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbConstants
import android.os.Build
import android.os.PowerManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import jp.espresso3389.methings.perm.PermissionStoreFacade
import jp.espresso3389.methings.BuildConfig
import jp.espresso3389.methings.perm.CredentialStore
import jp.espresso3389.methings.perm.InstallIdentity
import jp.espresso3389.methings.perm.PermissionPrefs
import jp.espresso3389.methings.device.BleManager
import jp.espresso3389.methings.device.AudioPlaybackManager
import jp.espresso3389.methings.device.AudioRecordManager
import jp.espresso3389.methings.device.CameraXManager
import jp.espresso3389.methings.device.MediaStreamManager
import jp.espresso3389.methings.device.ScreenRecordManager
import jp.espresso3389.methings.device.VideoRecordManager
import jp.espresso3389.methings.device.DeviceLocationManager
import jp.espresso3389.methings.device.DeviceNetworkManager
import jp.espresso3389.methings.device.SensorsStreamManager
import jp.espresso3389.methings.device.SttManager
import jp.espresso3389.methings.device.TtsManager
import jp.espresso3389.methings.vision.VisionFrameStore
import jp.espresso3389.methings.vision.VisionImageIo
import jp.espresso3389.methings.vision.TfliteModelManager
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import jp.espresso3389.methings.device.AndroidPermissionWaiter
import jp.espresso3389.methings.device.UsbPermissionWaiter
import jp.espresso3389.methings.service.agent.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONArray
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import jp.espresso3389.methings.service.mcu.esp.EspFlashException
import jp.espresso3389.methings.service.mcu.esp.EspFlashStageException
import jp.espresso3389.methings.service.mcu.esp.EspSerialSession
import jp.espresso3389.methings.service.mcu.esp.EspSyncException

class LocalHttpServer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val runtimeManager: TermuxWorkerManager,
    private val termuxManager: TermuxManager,
    private val sshdManager: SshdManager,
    private val sshPinManager: SshPinManager,
    private val sshNoAuthManager: SshNoAuthManager
) : NanoWSD(HOST, PORT) {
    private val uiRoot = File(context.filesDir, "user/www")
    private val permissionStore = PermissionStoreFacade(context)
    private val permissionPrefs = PermissionPrefs(context)
    private val installIdentity = InstallIdentity(context)
    private val credentialStore = CredentialStore(context)
    private val deviceGrantStore = jp.espresso3389.methings.perm.DeviceGrantStoreFacade(context)
    private val sshKeyStore = jp.espresso3389.methings.perm.SshKeyStore(context)
    private val sshKeyPolicy = jp.espresso3389.methings.perm.SshKeyPolicy(context)
    private val agentTasks = java.util.concurrent.ConcurrentHashMap<String, AgentTask>()
    private val lastPermissionPromptAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val usbConnections = ConcurrentHashMap<String, UsbDeviceConnection>()
    private val usbDevicesByHandle = ConcurrentHashMap<String, UsbDevice>()
    private val usbStreams = ConcurrentHashMap<String, UsbStreamState>()
    private val serialSessions = ConcurrentHashMap<String, SerialSessionState>()

    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private val powerManager: PowerManager by lazy {
        context.getSystemService(PowerManager::class.java)
    }

    private val USB_PERMISSION_ACTION = "jp.espresso3389.methings.USB_PERMISSION"
    private val visionFrames = VisionFrameStore()
    private val tflite = TfliteModelManager(context)
    private val camera = CameraXManager(context, lifecycleOwner)
    private val ble = BleManager(context)
    private val mediaAudio = AudioPlaybackManager(context)
    private val tts = TtsManager(context)
    private val stt = SttManager(context)
    private val location = DeviceLocationManager(context)
    private val network = DeviceNetworkManager(context)
    private val sensors = SensorsStreamManager(context)
    private val audioRecord = AudioRecordManager(context)
    private val videoRecord = VideoRecordManager(context, lifecycleOwner)
    private val screenRecord = ScreenRecordManager(context)
    private val mediaStream = MediaStreamManager(context)
    private val appUpdateManager = AppUpdateManager(context)
    private val workJobManager = WorkJobManager(context)
    private val meSyncTransfers = ConcurrentHashMap<String, MeSyncTransfer>()
    private val meSyncV3Tickets = ConcurrentHashMap<String, MeSyncV3Ticket>()

    // --- Shared JsRuntime ---
    private val agentJsRuntime by lazy {
        JsRuntime(
            userDir = File(context.filesDir, "user"),
            sysDir = File(context.filesDir, "system"),
            port = PORT,
            deviceApiCallback = { action, payloadJson ->
                val payload = try { JSONObject(payloadJson) } catch (_: Exception) { JSONObject() }
                agentDeviceBridge.execute(action, payload, "js_runtime").toString()
            },
        )
    }

    // --- Scheduler ---
    private val schedulerStore by lazy { SchedulerStore(context) }
    private val schedulerDeviceBridge by lazy {
        DeviceToolBridge(identity = { "scheduler" })
    }
    private val schedulerJsRuntime by lazy {
        JsRuntime(
            userDir = File(context.filesDir, "user"),
            sysDir = File(context.filesDir, "system"),
            port = PORT,
            deviceApiCallback = { action, payloadJson ->
                val payload = try { JSONObject(payloadJson) } catch (_: Exception) { JSONObject() }
                schedulerDeviceBridge.execute(action, payload, "scheduler").toString()
            },
        )
    }
    private val schedulerEngine by lazy {
        SchedulerEngine(
            store = schedulerStore,
            userDir = File(context.filesDir, "user"),
            executeRunJs = { code, timeoutMs ->
                schedulerJsRuntime.executeBlocking(code, timeoutMs)
            },
            executeShellExec = { cmd, args, cwd -> shellExecViaTermux(cmd, args, cwd) },
        )
    }

    // --- Native Agent Runtime ---
    private val agentStorage by lazy { AgentStorage(context) }
    private val agentJournalStore by lazy { JournalStore(File(context.filesDir, "user/journal")) }
    private val agentConfigManager by lazy { AgentConfigManager(context) }
    private val agentLlmClient by lazy { LlmClient() }
    private val agentDeviceBridge by lazy {
        DeviceToolBridge(identity = { agentRuntime?.let { "default" } ?: "default" })
    }
    private val nativeShellExecutor by lazy {
        NativeShellExecutor(defaultCwd = File(context.filesDir, "user"))
    }
    private val agentToolExecutor by lazy {
        ToolExecutor(
            userDir = File(context.filesDir, "user"),
            sysDir = File(context.filesDir, "system"),
            journalStore = agentJournalStore,
            deviceBridge = agentDeviceBridge,
            shellExec = { cmd, args, cwd -> shellExecViaTermux(cmd, args, cwd) },
            sessionIdProvider = { "default" },
            jsRuntime = agentJsRuntime,
            nativeShell = nativeShellExecutor,
        ).also { executor ->
            // Apply File Transfer image settings
            val ftPrefs = fileTransferPrefs
            executor.imageResizeEnabled = ftPrefs.getBoolean("image_resize_enabled", true)
            executor.imageMaxDimPx = ftPrefs.getInt("image_resize_max_dim_px", 512).coerceIn(64, 4096)
            executor.imageJpegQuality = ftPrefs.getInt("image_resize_jpeg_quality", 70).coerceIn(30, 95)
        }
    }
    @Volatile private var agentRuntime: AgentRuntime? = null
    private fun getOrCreateAgentRuntime(): AgentRuntime {
        agentRuntime?.let { return it }
        synchronized(this) {
            agentRuntime?.let { return it }
            val runtime = AgentRuntime(
                userDir = File(context.filesDir, "user"),
                sysDir = File(context.filesDir, "system"),
                storage = agentStorage,
                journalStore = agentJournalStore,
                toolExecutor = agentToolExecutor,
                llmClient = agentLlmClient,
                configManager = agentConfigManager,
                emitLog = { name, payload -> broadcastBrainEvent(name, payload) },
            )
            runtime.onEvent = { name, payload -> broadcastBrainEvent(name, payload) }
            agentRuntime = runtime
            // One-time: migrate chat history from legacy DB
            try { agentStorage.migrateLegacyDbIfNeeded() } catch (_: Exception) {}
            return runtime
        }
    }
    private fun shellExecViaTermux(cmd: String, args: String, cwd: String): JSONObject {
        // Delegate to Termux via the existing /shell/exec endpoint (loopback)
        return try {
            val body = JSONObject().put("cmd", cmd).put("args", args).put("cwd", cwd)
            val url = java.net.URL("http://127.0.0.1:$PORT/shell/exec")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 2000
            conn.readTimeout = 300_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            conn.disconnect()
            try { JSONObject(responseBody) } catch (_: Exception) { JSONObject().put("status", "error").put("raw", responseBody) }
        } catch (ex: Exception) {
            JSONObject().put("status", "error").put("error", "shell_unavailable").put("detail", ex.message ?: "")
        }
    }
    // SSE event broadcasting for brain events
    private val brainSseClients = CopyOnWriteArrayList<java.io.PipedOutputStream>()
    private fun broadcastBrainEvent(name: String, payload: JSONObject) {
        val data = payload.toString()
        val sseMsg = "event: $name\ndata: $data\n\n"
        val bytes = sseMsg.toByteArray(Charsets.UTF_8)
        for (client in brainSseClients) {
            try {
                client.write(bytes)
                client.flush()
            } catch (_: Exception) {
                brainSseClients.remove(client)
            }
        }
    }

    private val meSyncImportProgressLock = Any()
    @Volatile private var meSyncImportProgress = MeSyncImportProgress()
    private val meMePrefs = context.getSharedPreferences(ME_ME_PREFS, Context.MODE_PRIVATE)
    private val meMeConnectIntents = ConcurrentHashMap<String, MeMeConnectIntent>()
    private val meMeConnections = ConcurrentHashMap<String, MeMeConnection>()
    private val meMeInboundMessages = ConcurrentHashMap<String, MutableList<JSONObject>>()
    private data class MeMeBleDataAckWaiter(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var ack: JSONObject? = null
    )
    private val meMeBleDataAckWaiters = ConcurrentHashMap<String, MeMeBleDataAckWaiter>()
    private val meMeRelayEvents = mutableListOf<JSONObject>()
    private val meMeRelayEventsLock = Any()
    private val meMePeerPresence = ConcurrentHashMap<String, MeMePeerPresence>()
    private data class RemotePermission(
        val permissionId: String,
        val tool: String,
        val detail: String,
        val sourceDeviceId: String,
        val sourceDeviceName: String,
        val receivedAt: Long,
        val status: String = "pending"
    )
    private val meMeRemotePermissions = ConcurrentHashMap<String, RemotePermission>()
    private val meMeReconnectAttemptAt = ConcurrentHashMap<String, Long>()
    @Volatile private var meMeLastScanAtMs: Long = 0L
    @Volatile private var meMeRelayLastRegisterAtMs: Long = 0L
    @Volatile private var meMeRelayLastNotifyAtMs: Long = 0L
    @Volatile private var meMeRelayLastGatewayPullAtMs: Long = 0L
    private val meMeDiscovery = MeMeDiscoveryManager(
        context = context,
        servicePort = ME_ME_LAN_PORT,
        logger = { msg, ex ->
            if (ex != null) Log.w(TAG, msg, ex) else Log.w(TAG, msg)
        },
        onBlePayload = { payload, sourceAddress ->
            handleMeMeBlePayload(payload, sourceAddress)
        }
    )
    private val meSyncNearbyTransport = MeSyncNearbyTransport(
        context = context,
        serviceId = NEARBY_ME_SYNC_SERVICE_ID,
        openOutgoingStream = { ticketId, transferId -> openMeSyncTransferStreamForNearby(ticketId, transferId) },
        logger = { msg, ex ->
            if (ex != null) Log.w(TAG, msg, ex) else Log.w(TAG, msg)
        }
    )
    private val meSyncLanDownloadServer = MeSyncLanDownloadServer()
    @Volatile private var meSyncLanServerStarted = false
    private val meMeLanServer = MeMeLanServer()
    @Volatile private var meMeLanServerStarted = false
    private val meMeP2pManager = MeMeP2pManager(
        context, installIdentity.get(),
        onDataChannelMessage = { peerId, data -> handleMeMeP2pPayload(peerId, data) },
        onConnectionStateChanged = { peerId, state -> handleMeMeP2pStateChange(peerId, state) },
        logger = { msg, ex -> if (ex != null) Log.w(TAG, msg, ex) else Log.d(TAG, msg) }
    )

    @Volatile private var bootstrapPhase: String = "none"
    @Volatile private var bootstrapMessage: String = ""
    @Volatile private var showTermuxSetupFlag = false
    private val bootstrapPrefs by lazy { context.getSharedPreferences("termux_bootstrap", Context.MODE_PRIVATE) }

    @Volatile private var keepScreenOnWakeLock: PowerManager.WakeLock? = null
    @Volatile private var keepScreenOnExpiresAtMs: Long = 0L
    private val screenScheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var screenReleaseFuture: ScheduledFuture<*>? = null
    private val housekeepingScheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var housekeepingFuture: ScheduledFuture<*>? = null
    private val meMeExecutor = Executors.newSingleThreadExecutor()
    private val meMeDiscoveryScheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var meMeDiscoveryFuture: ScheduledFuture<*>? = null
    private val meMeConnectionCheckScheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var meMeConnectionCheckFuture: ScheduledFuture<*>? = null

    fun startServer(): Boolean {
        return try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Local HTTP server started on $HOST:$PORT")
            meSyncLanServerStarted = try {
                meSyncLanDownloadServer.start(SOCKET_READ_TIMEOUT, false)
                Log.i(TAG, "me.sync LAN download server started on 0.0.0.0:$ME_SYNC_LAN_PORT")
                true
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to start me.sync LAN download server", ex)
                false
            }
            meMeLanServerStarted = try {
                meMeLanServer.start(SOCKET_READ_TIMEOUT, false)
                Log.i(TAG, "me.me LAN server started on 0.0.0.0:$ME_ME_LAN_PORT")
                true
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to start me.me LAN server", ex)
                false
            }
            scheduleHousekeeping()
            try { schedulerEngine.start() } catch (e: Exception) { Log.w(TAG, "Failed to start scheduler", e) }
            meMeExecutor.execute {
                runCatching { meMeDiscovery.applyConfig(currentMeMeDiscoveryConfig()) }
                runCatching {
                    val p2pCfg = currentMeMeP2pConfig()
                    if (p2pCfg.enabled) meMeP2pManager.initialize(buildP2pManagerConfig(p2pCfg))
                }
                // Refresh provisioned device list from gateway on startup
                runCatching { handleProvisionRefresh() }
            }
            scheduleMeMeDiscoveryLoop()
            scheduleMeMeConnectionCheckLoop()
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to start local HTTP server", ex)
            false
        }
    }

    fun stopServer() {
        try {
            stop()
        } catch (_: Exception) {
            // ignore
        }
        try {
            meSyncLanDownloadServer.stop()
        } catch (_: Exception) {
        }
        meSyncLanServerStarted = false
        try {
            meMeLanServer.stop()
        } catch (_: Exception) {
        }
        meMeLanServerStarted = false
        try {
            setKeepScreenOn(false, timeoutS = 0)
        } catch (_: Exception) {
        }
        try {
            screenScheduler.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            housekeepingFuture?.cancel(true)
            housekeepingFuture = null
        } catch (_: Exception) {
        }
        try {
            schedulerEngine.stop()
        } catch (_: Exception) {
        }
        try {
            agentJsRuntime.close()
        } catch (_: Exception) {
        }
        try {
            schedulerJsRuntime.close()
        } catch (_: Exception) {
        }
        try {
            meMeDiscoveryFuture?.cancel(true)
            meMeDiscoveryFuture = null
        } catch (_: Exception) {
        }
        try {
            meMeConnectionCheckFuture?.cancel(true)
            meMeConnectionCheckFuture = null
        } catch (_: Exception) {
        }
        try {
            meSyncNearbyTransport.shutdown()
        } catch (_: Exception) {
        }
        try {
            meMeDiscoveryScheduler.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            meMeConnectionCheckScheduler.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            meMeP2pManager.shutdown()
        } catch (_: Exception) {
        }
        try {
            meMeExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        try {
            meMeDiscovery.shutdown()
        } catch (_: Exception) {
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        // NanoHTTPD keeps connections alive; if we return early on a POST without consuming the body,
        // leftover bytes can corrupt the next request line (e.g. "{}POST ...").
        // Always read the POST body once up-front and reuse it across handlers.
        val contentType = (session.headers["content-type"] ?: "").lowercase()
        val isMultipart = contentType.contains("multipart/form-data")
        val postBody: String? = if (session.method == Method.POST && !isMultipart) readBody(session) else null
        val seg = uri.indexOf('/', 1).let { if (it < 0) uri else uri.substring(0, it) }
        return when (seg) {
            "/health" -> routeHealth(session, uri, postBody)
            "/debug" -> routeDebug(session, uri, postBody)
            "/python", "/termux" -> routeTermux(session, uri, postBody)
            "/service" -> routeService(session, uri, postBody)
            "/app" -> routeApp(session, uri, postBody)
            "/work" -> routeWork(session, uri, postBody)
            "/me" -> routeMe(session, uri, postBody)
            "/agent" -> routeAgent(session, uri, postBody)
            "/ui" -> routeUi(session, uri, postBody)
            "/permissions" -> routePermissions(session, uri, postBody)
            "/vault" -> routeVault(session, uri, postBody)
            "/builtins" -> routeBuiltins(session, uri, postBody)
            "/brain" -> routeBrain(session, uri, postBody)
            "/shell" -> routeShell(session, uri, postBody)
            "/web" -> routeWeb(session, uri, postBody)
            "/pip" -> routePip(session, uri, postBody)
            "/auth" -> routeAuth(session, uri, postBody)
            "/cloud" -> routeCloud(session, uri, postBody)
            "/file_transfer" -> routeFileTransfer(session, uri, postBody)
            "/notifications" -> routeNotifications(session, uri, postBody)
            "/screen" -> routeScreen(session, uri, postBody)
            "/usb" -> routeUsb(session, uri, postBody)
            "/serial" -> routeSerial(session, uri, postBody)
            "/mcu" -> routeMcu(session, uri, postBody)
            "/uvc" -> routeUvc(session, uri, postBody)
            "/vision" -> routeVision(session, uri, postBody)
            "/camera" -> routeCamera(session, uri, postBody)
            "/ble" -> routeBle(session, uri, postBody)
            "/tts" -> routeTts(session, uri, postBody)
            "/media" -> routeMedia(session, uri, postBody)
            "/audio" -> routeAudio(session, uri, postBody)
            "/video" -> routeVideo(session, uri, postBody)
            "/scheduler" -> routeScheduler(session, uri, postBody)
            "/stt" -> routeStt(session, uri, postBody)
            "/location" -> routeLocation(session, uri, postBody)
            "/network" -> routeNetwork(session, uri, postBody)
            "/wifi" -> routeWifi(session, uri, postBody)
            "/mobile" -> routeMobile(session, uri, postBody)
            "/sensors", "/sensor" -> routeSensors(session, uri, postBody)
            "/user" -> routeUser(session, uri, postBody)
            "/webview" -> routeWebview(session, uri, postBody)
            "/intent" -> routeIntent(session, uri, postBody)
            "/sys" -> routeSys(session, uri, postBody)
            "/android" -> routeAndroid(session, uri, postBody)
            "/sshd" -> routeSshd(session, uri, postBody)
            "/" -> routeUi(session, uri, postBody)
            "" -> routeUi(session, uri, postBody)
            else -> notFound()
        }
    }

    private fun routeAuth(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/auth/identity" && session.method == Method.GET -> {
                val identities = loadVerifiedOwnerIdentities()
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("identities", org.json.JSONArray(identities))
                )
            }
            uri == "/auth/signout" && session.method == Method.POST -> {
                credentialStore.delete("me_me_owner:google")
                credentialStore.delete("me_me_owner:google:id_token")
                jsonResponse(JSONObject().put("status", "ok"))
            }
            else -> notFound()
        }
    }

    private fun routeHealth(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/health" -> jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("service", "local")
                    .put("termux", runtimeManager.getStatus())
            )
            else -> notFound()
        }
    }

    private fun routeDebug(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/debug/logs/export" && session.method == Method.GET -> {
                handleDebugLogsExport(session, null)
            }
            uri == "/debug/logs/export" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleDebugLogsExport(session, payload)
            }
            uri == "/debug/logs/list" && session.method == Method.GET -> {
                handleDebugLogsList(session)
            }
            uri == "/debug/logs/delete_all" && session.method == Method.POST -> {
                handleDebugLogsDeleteAll(session)
            }
            uri == "/debug/logs/stream" && session.method == Method.GET -> {
                handleDebugLogsStream(session, null)
            }
            uri == "/debug/logs/stream" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleDebugLogsStream(session, payload)
            }
            else -> notFound()
        }
    }


    private fun routeService(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/service/stop" && session.method == Method.POST -> {
                val intent = Intent(context, AgentService::class.java).apply {
                    action = AgentService.ACTION_STOP_SERVICE
                }
                context.startService(intent)
                jsonResponse(JSONObject().put("status", "stopping"))
            }
            uri == "/service/prefs" && session.method == Method.GET -> {
                val prefs = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                jsonResponse(JSONObject().put("start_on_boot", prefs.getBoolean("start_on_boot", true)))
            }
            uri == "/service/prefs" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val prefs = context.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                if (payload.has("start_on_boot")) {
                    editor.putBoolean("start_on_boot", payload.getBoolean("start_on_boot"))
                }
                editor.apply()
                jsonResponse(JSONObject().put("ok", true))
            }
            else -> notFound()
        }
    }

    private fun routeApp(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/app/update/check" -> {
                handleAppUpdateCheck()
            }
            uri == "/app/update/install" && session.method == Method.POST -> {
                handleAppUpdateInstall()
            }
            uri == "/app/update/install_permission" && session.method == Method.GET -> {
                handleAppUpdateInstallPermissionStatus()
            }
            uri == "/app/update/install_permission/open_settings" && session.method == Method.POST -> {
                handleAppUpdateInstallPermissionOpenSettings()
            }
            uri == "/app/info" -> {
                handleAppInfo()
            }
            else -> notFound()
        }
    }

    private fun routeWork(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/work/jobs/app_update_check" && session.method == Method.GET -> {
                handleWorkAppUpdateCheckStatus()
            }
            uri == "/work/jobs/app_update_check/schedule" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.work",
                    capability = "workmanager",
                    detail = "Schedule app update background check"
                )
                if (!ok.first) return ok.second!!
                handleWorkAppUpdateCheckSchedule(payload)
            }
            uri == "/work/jobs/app_update_check/run_once" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.work",
                    capability = "workmanager",
                    detail = "Run app update background check once"
                )
                if (!ok.first) return ok.second!!
                handleWorkAppUpdateCheckRunOnce(payload)
            }
            uri == "/work/jobs/app_update_check/cancel" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.work",
                    capability = "workmanager",
                    detail = "Cancel app update background checks"
                )
                if (!ok.first) return ok.second!!
                handleWorkAppUpdateCheckCancel()
            }
            else -> notFound()
        }
    }

    private fun routeScheduler(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/scheduler/status" && session.method == Method.GET -> {
                jsonResponse(schedulerEngine.status())
            }
            uri == "/scheduler/schedules" && session.method == Method.GET -> {
                val list = schedulerStore.listSchedules()
                jsonResponse(JSONObject().put("schedules", org.json.JSONArray().apply {
                    list.forEach { put(it.toJson()) }
                }))
            }
            uri == "/scheduler/create" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session, payload, tool = "device.scheduler",
                    capability = "scheduler", detail = "Create scheduled code execution"
                )
                if (!ok.first) return ok.second!!
                // Pre-check device API permissions for the scheduler identity
                val createRuntime = payload.optString("runtime", "run_js")
                if (createRuntime == "run_js") {
                    val createCode = payload.optString("code", "")
                    val needed = CapabilityMap.capabilitiesForCode(createCode)
                    for ((tool, cap, label) in needed) {
                        val schedPayload = JSONObject().put("identity", "scheduler")
                        val capOk = ensureDevicePermission(
                            session, schedPayload,
                            tool = tool, capability = cap,
                            detail = "Scheduled task '${payload.optString("name", "")}' needs $label"
                        )
                        if (!capOk.first) return capOk.second!!
                    }
                }
                try {
                    val row = schedulerStore.createSchedule(
                        name = payload.optString("name", ""),
                        launchType = payload.optString("launch_type", "one_time"),
                        schedulePattern = payload.optString("schedule_pattern", ""),
                        runtime = payload.optString("runtime", "run_js"),
                        code = payload.optString("code", ""),
                        args = payload.optString("args", ""),
                        cwd = payload.optString("cwd", ""),
                        timeoutMs = payload.optLong("timeout_ms", 60_000),
                        enabled = payload.optBoolean("enabled", true),
                        meta = payload.optString("meta", "{}"),
                    )
                    // If daemon or one_time and enabled, trigger immediately
                    if (row.enabled && (row.launchType == "daemon" || row.launchType == "one_time")) {
                        schedulerEngine.triggerNow(row.id)
                    }
                    jsonResponse(row.toJson())
                } catch (e: IllegalStateException) {
                    jsonError(Response.Status.BAD_REQUEST, e.message ?: "create_failed")
                } catch (e: Exception) {
                    jsonError(Response.Status.INTERNAL_ERROR, "create_failed",
                        JSONObject().put("detail", e.message ?: ""))
                }
            }
            uri == "/scheduler/get" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val id = payload.optString("id", "")
                val row = schedulerStore.getSchedule(id)
                    ?: return jsonError(Response.Status.NOT_FOUND, "not_found")
                jsonResponse(row.toJson().put("running", schedulerEngine.isRunning(id)))
            }
            uri == "/scheduler/update" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session, payload, tool = "device.scheduler",
                    capability = "scheduler", detail = "Update scheduled code execution"
                )
                if (!ok.first) return ok.second!!
                // Pre-check device API permissions when code changes
                if (payload.has("code") && payload.optString("runtime", "run_js") == "run_js") {
                    val updateCode = payload.optString("code", "")
                    val needed = CapabilityMap.capabilitiesForCode(updateCode)
                    for ((tool, cap, label) in needed) {
                        val schedPayload = JSONObject().put("identity", "scheduler")
                        val capOk = ensureDevicePermission(
                            session, schedPayload,
                            tool = tool, capability = cap,
                            detail = "Scheduled task update needs $label"
                        )
                        if (!capOk.first) return capOk.second!!
                    }
                }
                val id = payload.optString("id", "")
                val row = schedulerStore.updateSchedule(id, payload)
                    ?: return jsonError(Response.Status.NOT_FOUND, "not_found")
                jsonResponse(row.toJson())
            }
            uri == "/scheduler/delete" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session, payload, tool = "device.scheduler",
                    capability = "scheduler", detail = "Delete scheduled code execution"
                )
                if (!ok.first) return ok.second!!
                val id = payload.optString("id", "")
                val deleted = schedulerStore.deleteSchedule(id)
                jsonResponse(JSONObject().put("status", if (deleted) "ok" else "not_found"))
            }
            uri == "/scheduler/trigger" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session, payload, tool = "device.scheduler",
                    capability = "scheduler", detail = "Trigger scheduled code execution"
                )
                if (!ok.first) return ok.second!!
                val id = payload.optString("id", "")
                jsonResponse(schedulerEngine.triggerNow(id))
            }
            uri == "/scheduler/capability_map" && session.method == Method.GET -> {
                jsonResponse(CapabilityMap.toJson())
            }
            uri == "/scheduler/log" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val id = payload.optString("id", "")
                val limit = payload.optInt("limit", 20)
                val logs = schedulerStore.listExecutionLog(id, limit)
                jsonResponse(JSONObject().put("logs", org.json.JSONArray().apply {
                    logs.forEach { put(it.toJson()) }
                }))
            }
            else -> notFound()
        }
    }

    private fun routeMe(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/me/sync/status" && session.method == Method.GET -> {
                handleMeSyncStatus()
            }
            uri == "/me/sync/progress" && session.method == Method.GET -> {
                handleMeSyncProgress()
            }
            uri == "/me/me/status" && session.method == Method.GET -> {
                handleMeMeStatus()
            }
            uri == "/me/me/routes" && session.method == Method.GET -> {
                handleMeMeRoutes()
            }
            uri == "/me/me/config" && session.method == Method.GET -> {
                handleMeMeConfigGet()
            }
            uri == "/me/me/config" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.config",
                    detail = "Update me.me connection settings"
                )
                if (!ok.first) return ok.second!!
                handleMeMeConfigSet(payload)
            }
            uri == "/me/me/scan" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.scan",
                    detail = "Scan nearby me.things devices"
                )
                if (!ok.first) return ok.second!!
                handleMeMeScan(payload)
            }
            uri == "/me/me/connect" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.connect",
                    detail = "Request connection to nearby me.things device"
                )
                if (!ok.first) return ok.second!!
                handleMeMeConnect(payload)
            }
            uri == "/me/me/accept" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.accept",
                    detail = "Accept me.things connection request"
                )
                if (!ok.first) return ok.second!!
                handleMeMeAccept(payload)
            }
            uri == "/me/me/request/reject" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.accept",
                    detail = "Reject me.things connection request"
                )
                if (!ok.first) return ok.second!!
                handleMeMeRequestReject(payload)
            }
            uri == "/me/me/policy/set" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.config",
                    detail = "Update me.things peer policy"
                )
                if (!ok.first) return ok.second!!
                handleMeMePeerPolicySet(payload)
            }
            uri == "/me/me/disconnect" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.disconnect",
                    detail = "Disconnect me.things device connection"
                )
                if (!ok.first) return ok.second!!
                handleMeMeDisconnect(payload)
            }
            uri == "/me/me/message/send" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.message",
                    detail = "Send message to connected me.things peer"
                )
                if (!ok.first) return ok.second!!
                handleMeMeMessageSend(payload)
            }
            uri == "/me/me/messages/pull" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.message",
                    detail = "Read messages from connected me.things peer"
                )
                if (!ok.first) return ok.second!!
                handleMeMeMessagesPull(payload)
            }
            uri == "/me/me/relay/status" && session.method == Method.GET -> {
                handleMeMeRelayStatus()
            }
            uri == "/me/me/relay/config" && session.method == Method.GET -> {
                handleMeMeRelayConfigGet()
            }
            uri == "/me/me/relay/config" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.relay",
                    detail = "Update me.me relay server configuration"
                )
                if (!ok.first) return ok.second!!
                handleMeMeRelayConfigSet(payload)
            }
            uri == "/me/me/relay/register" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.relay",
                    detail = "Register this device push token to me.me relay server"
                )
                if (!ok.first) return ok.second!!
                handleMeMeRelayRegister(payload)
            }
            uri == "/me/me/relay/ingest" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleMeMeRelayIngest(payload)
            }
            uri == "/me/me/p2p/status" && session.method == Method.GET -> {
                handleMeMeP2pStatus()
            }
            uri == "/me/me/p2p/config" && session.method == Method.GET -> {
                handleMeMeP2pConfigGet()
            }
            uri == "/me/me/p2p/config" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.p2p.config",
                    detail = "Update me.me P2P WebRTC configuration"
                )
                if (!ok.first) return ok.second!!
                handleMeMeP2pConfigSet(payload)
            }
            uri == "/me/me/p2p/connect" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.p2p.connect",
                    detail = "Establish WebRTC P2P connection to a peer"
                )
                if (!ok.first) return ok.second!!
                handleMeMeP2pConnect(payload)
            }
            uri == "/me/me/p2p/disconnect" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.p2p.disconnect",
                    detail = "Disconnect WebRTC P2P connection to a peer"
                )
                if (!ok.first) return ok.second!!
                handleMeMeP2pDisconnect(payload)
            }
            uri == "/me/me/provision/start" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.provision",
                    detail = "Start device provisioning (sign in with Google/GitHub)"
                )
                if (!ok.first) return ok.second!!
                handleProvisionStart(payload)
            }
            uri == "/me/me/provision/claim" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleProvisionClaim(payload)
            }
            uri == "/me/me/provision/status" && session.method == Method.GET -> {
                handleProvisionStatus()
            }
            uri == "/me/me/provision/refresh" && session.method == Method.POST -> {
                handleProvisionRefresh()
            }
            uri == "/me/me/provision/signout" && session.method == Method.POST -> {
                handleProvisionSignout()
            }
            uri == "/me/me/permissions/remote" && session.method == Method.GET -> {
                handleMeMeRemotePermissionsList()
            }
            uri.startsWith("/me/me/permissions/remote/") && uri.endsWith("/resolve") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleMeMeRemotePermissionResolve(uri, payload)
            }
            uri == "/me/me/data/ingest" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleMeMeDataIngest(payload, sourceIp = "local")
            }
            uri == "/me/sync/local_state" && session.method == Method.GET -> {
                handleMeSyncLocalState()
            }
            uri == "/me/sync/prepare_export" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleMeSyncPrepareExport(payload)
            }
            uri == "/me/sync/v3/ticket/create" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleMeSyncV3TicketCreate(payload)
            }
            uri == "/me/sync/v3/ticket/status" && session.method == Method.GET -> {
                handleMeSyncV3TicketStatus(session)
            }
            uri == "/me/sync/v3/ticket/cancel" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleMeSyncV3TicketCancel(payload)
            }
            uri == "/me/sync/v3/import/apply" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleMeSyncV3ImportApply(payload)
            }
            uri == "/me/sync/download" && session.method == Method.GET -> {
                handleMeSyncDownload(session)
            }
            uri == "/me/sync/import" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleMeSyncImport(payload)
            }
            uri == "/me/sync/wipe_all" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_sync",
                    capability = "me_sync.wipe_all",
                    detail = "Wipe local app data for me.sync"
                )
                if (!ok.first) return ok.second!!
                handleMeSyncWipeAll(payload)
            }
            else -> notFound()
        }
    }

    private fun routeAgent(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/agent/run" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val name = payload.optString("name", "task")
                val task = createTask(name, payload)
                runtimeManager.startWorker()
                jsonResponse(
                    JSONObject()
                        .put("id", task.id)
                        .put("status", task.status)
                )
            }
            uri == "/agent/tasks" -> {
                val arr = org.json.JSONArray()
                agentTasks.values.sortedBy { it.createdAt }.forEach { task ->
                    arr.put(task.toJson())
                }
                jsonResponse(JSONObject().put("items", arr))
            }
            uri.startsWith("/agent/tasks/") -> {
                val id = uri.removePrefix("/agent/tasks/").trim()
                val task = agentTasks[id] ?: return notFound()
                jsonResponse(task.toJson())
            }
            else -> notFound()
        }
    }

    private fun routeUi(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/ui/version" -> {
                val versionFile = File(uiRoot, ".version")
                val version = if (versionFile.exists()) versionFile.readText().trim() else ""
                textResponse(version)
            }
            uri == "/ui/reload" && session.method == Method.POST -> {
                // Hot-reload WebView UI after updating files/user/www on disk.
                context.sendBroadcast(
                    android.content.Intent(ACTION_UI_RELOAD).apply {
                        setPackage(context.packageName)
                        putExtra(EXTRA_UI_RELOAD_TOAST, "UI reloaded")
                    }
                )
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/ui/reset" && session.method == Method.POST -> {
                // Re-extract the factory UI from APK assets, replacing any agent edits.
                val extractor = jp.espresso3389.methings.service.AssetExtractor(context)
                val result = extractor.resetUiAssets()
                if (result != null) {
                    context.sendBroadcast(
                        android.content.Intent(ACTION_UI_RELOAD).apply {
                            setPackage(context.packageName)
                            putExtra(EXTRA_UI_RELOAD_TOAST, "UI reset to factory default")
                        }
                    )
                    jsonResponse(JSONObject().put("status", "ok"))
                } else {
                    jsonError(Response.Status.INTERNAL_ERROR, "reset_failed")
                }
            }
            uri == "/ui/settings/sections" && session.method == Method.GET -> {
                handleUiSettingsSections()
            }
            (uri == "/ui/settings/navigate" || uri == "/ui/settings/navigate/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val sectionRaw = payload.optString("section_id", "")
                val settingKeyRaw = payload.optString("setting_key", "")
                var sectionId = normalizeSettingsSectionId(sectionRaw)
                if (sectionId.isBlank()) {
                    val settingKey = normalizeSettingsSectionId(settingKeyRaw)
                    if (settingKey.isNotBlank()) {
                        sectionId = extractSettingsKeyToSectionMap()[settingKey] ?: ""
                    }
                }
                if (sectionId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "section_id_or_setting_key_required")
                if (!SETTINGS_SECTION_IDS.contains(sectionId)) {
                    return jsonError(Response.Status.BAD_REQUEST, "unknown_section_id")
                }
                val intent = Intent(ACTION_UI_SETTINGS_NAVIGATE).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_SETTINGS_SECTION_ID, sectionId)
                }
                context.sendBroadcast(intent)
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("section_id", sectionId)
                )
            }
            (uri == "/ui/me/sync/export/show" || uri == "/ui/me/sync/export/show/") && session.method == Method.POST -> {
                context.sendBroadcast(Intent(ACTION_UI_ME_SYNC_EXPORT_SHOW).apply {
                    setPackage(context.packageName)
                })
                jsonResponse(JSONObject().put("status", "ok"))
            }
            (uri == "/ui/viewer/open" || uri == "/ui/viewer/open/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val path = payload.optString("path", "").trim()
                if (path.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
                // Strip #page=N fragment for file validation; preserve full path for JS.
                val filePath = path.replace(Regex("#.*$"), "")
                val viewPath = if (filePath.startsWith("\$sys/")) {
                    val file = systemPath(filePath.removePrefix("\$sys/"))
                        ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_system_dir")
                    if (!file.exists()) return jsonError(Response.Status.NOT_FOUND, "not_found")
                    path
                } else {
                    val ref = parseFsPathRef(filePath) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
                    if (ref.fs == "user") {
                        val file = ref.userFile ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
                        if (!file.exists()) return jsonError(Response.Status.NOT_FOUND, "not_found")
                    } else if (ref.fs == "termux") {
                        val stat = statFsPath(ref)
                        if (!stat.first) return jsonError(Response.Status.NOT_FOUND, "not_found")
                    } else {
                        return jsonError(Response.Status.BAD_REQUEST, "unsupported_filesystem")
                    }
                    val frag = path.substringAfter('#', "")
                    if (frag.isNotBlank()) "${ref.displayPath}#$frag" else ref.displayPath
                }
                val intent = Intent(ACTION_UI_VIEWER_COMMAND).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_VIEWER_COMMAND, "open")
                    putExtra(EXTRA_VIEWER_PATH, viewPath)
                }
                context.sendBroadcast(intent)
                jsonResponse(JSONObject().put("status", "ok"))
            }
            (uri == "/ui/viewer/close" || uri == "/ui/viewer/close/") && session.method == Method.POST -> {
                val intent = Intent(ACTION_UI_VIEWER_COMMAND).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_VIEWER_COMMAND, "close")
                }
                context.sendBroadcast(intent)
                jsonResponse(JSONObject().put("status", "ok"))
            }
            (uri == "/ui/viewer/immersive" || uri == "/ui/viewer/immersive/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val enabled = payload.optBoolean("enabled", true)
                val intent = Intent(ACTION_UI_VIEWER_COMMAND).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_VIEWER_COMMAND, "immersive")
                    putExtra(EXTRA_VIEWER_ENABLED, enabled)
                }
                context.sendBroadcast(intent)
                jsonResponse(JSONObject().put("status", "ok"))
            }
            (uri == "/ui/viewer/slideshow" || uri == "/ui/viewer/slideshow/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val enabled = payload.optBoolean("enabled", true)
                val intent = Intent(ACTION_UI_VIEWER_COMMAND).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_VIEWER_COMMAND, "slideshow")
                    putExtra(EXTRA_VIEWER_ENABLED, enabled)
                }
                context.sendBroadcast(intent)
                jsonResponse(JSONObject().put("status", "ok"))
            }
            (uri == "/ui/viewer/goto" || uri == "/ui/viewer/goto/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val page = payload.optInt("page", 0)
                val intent = Intent(ACTION_UI_VIEWER_COMMAND).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_VIEWER_COMMAND, "goto")
                    putExtra(EXTRA_VIEWER_PAGE, page)
                }
                context.sendBroadcast(intent)
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/" || uri == "/ui" || uri == "/ui/" -> serveUiFile("index.html")
            uri.startsWith("/ui/") -> {
                val raw = uri.removePrefix("/ui/")
                val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
                serveUiFile(decoded)
            }
            else -> notFound()
        }
    }

    private fun routePermissions(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/permissions/request" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val tool = payload.optString("tool", "unknown")
                val detail = payload.optString("detail", "")
                val requestedScope = payload.optString("scope", "once")
                val headerIdentity =
                    ((session.headers["x-methings-identity"] ?: session.headers["x-methings-identity"]) ?: "").trim()
                val identity = payload.optString("identity", "").trim().ifBlank { headerIdentity }.ifBlank { installIdentity.get() }
                val capabilityFromTool = if (tool.startsWith("device.")) tool.removePrefix("device.").trim() else ""
                val capabilityFromPayload = payload.optString("capability", "").trim()
                val capability = capabilityFromPayload.ifBlank {
                    if (capabilityFromTool.isNotBlank()) {
                        capabilityFromTool
                    } else if (tool.trim().lowercase() == "device_api") {
                        // Back-compat: older clients used a generic "device_api" tool. Without a capability,
                        // "remember approvals" becomes ineffective and the UI keeps prompting.
                        val req = jp.espresso3389.methings.perm.DevicePermissionPolicy.requiredFor(tool, detail)
                        when ((req?.userFacingLabel ?: "").trim().lowercase()) {
                            "camera" -> "camera"
                            "microphone" -> "mic"
                            "location" -> "location"
                            "bluetooth" -> "bluetooth"
                            "usb device access" -> "usb"
                            "text-to-speech" -> "tts"
                            else -> "device"
                        }
                    } else {
                        ""
                    }
                }
                val remember = permissionPrefs.rememberApprovals()
                val scope = when {
                    remember && requestedScope.trim() == "once" -> "persistent"
                    else -> requestedScope
                }
                val dangerousSkip = permissionPrefs.dangerouslySkipPermissions()

                if (!dangerousSkip && identity.isNotBlank() && scope != "once") {
                    val pending = permissionStore.findRecentPending(
                        tool = tool,
                        identity = identity,
                        capability = capability
                    )
                    if (pending != null) {
                        val forceBio = false
                        sendPermissionPrompt(pending.id, tool, pending.detail, forceBio)
                        return jsonResponse(
                            JSONObject()
                                .put("id", pending.id)
                                .put("status", pending.status)
                                .put("tool", pending.tool)
                                .put("detail", pending.detail)
                                .put("scope", pending.scope)
                                .put("identity", pending.identity)
                                .put("capability", pending.capability)
                        )
                    }

                    val reusable = permissionStore.findReusableApproved(
                        tool = tool,
                        scope = scope,
                        identity = identity,
                        capability = capability
                    )
                    if (reusable != null) {
                        return jsonResponse(
                            JSONObject()
                                .put("id", reusable.id)
                                .put("status", reusable.status)
                                .put("tool", reusable.tool)
                                .put("detail", reusable.detail)
                                .put("scope", reusable.scope)
                                .put("identity", reusable.identity)
                                .put("capability", reusable.capability)
                        )
                    }
                }

                if (dangerousSkip) {
                    val approved = autoApprovePermission(
                        tool = tool,
                        detail = detail,
                        scope = scope,
                        identity = identity,
                        capability = capability
                    )
                    return jsonResponse(
                        JSONObject()
                            .put("id", approved.id)
                            .put("status", approved.status)
                            .put("tool", approved.tool)
                            .put("detail", approved.detail)
                            .put("scope", approved.scope)
                            .put("identity", approved.identity)
                            .put("capability", approved.capability)
                            .put("auto_approved", true)
                    )
                }

                val req = permissionStore.create(
                    tool = tool,
                    detail = detail,
                    scope = scope,
                    identity = identity,
                    capability = capability
                )
                // Permission UX:
                // - Always prompt: the client can't complete without user action, and agent flows
                //   otherwise look like "silent" failures.
                // - Some tools additionally require biometric or Android runtime permissions.
                val forceBio = false
                sendPermissionPrompt(req.id, tool, detail, forceBio)
                jsonResponse(
                    JSONObject()
                        .put("id", req.id)
                        .put("status", req.status)
                        .put("tool", req.tool)
                        .put("detail", req.detail)
                        .put("scope", req.scope)
                        .put("identity", req.identity)
                        .put("capability", req.capability)
                )
            }
            uri == "/permissions/prefs" && session.method == Method.GET -> {
                jsonResponse(
                    JSONObject()
                        .put("remember_approvals", permissionPrefs.rememberApprovals())
                        .put("dangerously_skip_permissions", permissionPrefs.dangerouslySkipPermissions())
                        .put("identity", installIdentity.get())
                )
            }
            uri == "/permissions/prefs" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val remember = payload.optBoolean("remember_approvals", true)
                val dangerousSkip = payload.optBoolean(
                    "dangerously_skip_permissions",
                    permissionPrefs.dangerouslySkipPermissions()
                )
                permissionPrefs.setRememberApprovals(remember)
                permissionPrefs.setDangerouslySkipPermissions(dangerousSkip)
                jsonResponse(
                    JSONObject()
                        .put("remember_approvals", permissionPrefs.rememberApprovals())
                        .put("dangerously_skip_permissions", permissionPrefs.dangerouslySkipPermissions())
                )
            }
            uri == "/permissions/clear" && session.method == Method.POST -> {
                permissionStore.clearAll()
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/permissions/pending" -> {
                val pending = permissionStore.listPending()
                val arr = org.json.JSONArray()
                pending.forEach { req ->
                    arr.put(
                        JSONObject()
                            .put("id", req.id)
                            .put("tool", req.tool)
                            .put("detail", req.detail)
                            .put("scope", req.scope)
                            .put("status", req.status)
                            .put("created_at", req.createdAt)
                            .put("identity", req.identity)
                            .put("capability", req.capability)
                    )
                }
                jsonResponse(JSONObject().put("items", arr))
            }
            uri == "/permissions/grants" -> {
                val grants = permissionStore.listApproved()
                val arr = org.json.JSONArray()
                grants.forEach { req ->
                    arr.put(
                        JSONObject()
                            .put("id", req.id)
                            .put("tool", req.tool)
                            .put("detail", req.detail)
                            .put("scope", req.scope)
                            .put("status", req.status)
                            .put("created_at", req.createdAt)
                            .put("identity", req.identity)
                            .put("capability", req.capability)
                    )
                }
                jsonResponse(JSONObject().put("items", arr))
            }
            uri.startsWith("/permissions/") && session.method == Method.GET -> {
                val id = uri.removePrefix("/permissions/").trim()
                if (id.isBlank()) {
                    return notFound()
                }
                val req = permissionStore.get(id) ?: return notFound()
                jsonResponse(
                    JSONObject()
                        .put("id", req.id)
                        .put("tool", req.tool)
                        .put("detail", req.detail)
                        .put("scope", req.scope)
                        .put("status", req.status)
                        .put("created_at", req.createdAt)
                        .put("identity", req.identity)
                        .put("capability", req.capability)
                )
            }
            uri.startsWith("/permissions/") && session.method == Method.POST -> {
                val parts = uri.removePrefix("/permissions/").split("/")
                if (parts.size == 2) {
                    val id = parts[0]
                    val action = parts[1]
                    val status = when (action) {
                        "approve" -> "approved"
                        "deny" -> "denied"
                        else -> "unknown"
                    }
                    val updated = permissionStore.updateStatus(id, status)
                    if (updated == null) {
                        notFound()
                    } else {
                        if (status == "approved") {
                            maybeGrantDeviceCapability(updated)
                        }
                        // Notify the foreground service so it can advance queued permission notifications.
                        sendPermissionResolved(updated.id, updated.status)
                        // Dismiss forwarded permission on connected siblings.
                        Thread {
                            dismissRemotePermission(updated.id, updated.status)
                        }.apply { isDaemon = true }.start()
                        // Also notify the agent runtime so it can resume automatically.
                        // Run this off-thread so /permissions/{id}/approve responds immediately even if
                        // the worker is slow or temporarily blocked.
                        Thread {
                            notifyBrainPermissionResolved(updated)
                        }.apply { isDaemon = true }.start()
                        jsonResponse(
                            JSONObject()
                                .put("id", updated.id)
                                .put("status", updated.status)
                        )
                    }
                } else {
                    notFound()
                }
            }
            else -> notFound()
        }
    }

    private fun routeVault(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/vault/credentials" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val name = payload.optString("name", "")
                val value = payload.optString("value", "")
                val permissionId = payload.optString("permission_id", "")
                if (permissionId.isBlank()) {
                    return jsonError(Response.Status.BAD_REQUEST, "permission_id_required")
                }
                if (!isPermissionApproved(permissionId, consume = false)) {
                    return jsonError(Response.Status.FORBIDDEN, "permission_required")
                }
                val nameTrimmed = name.trim()
                if (nameTrimmed.isBlank()) {
                    return jsonError(Response.Status.BAD_REQUEST, "name_required")
                }
                if (nameTrimmed.length > 128) {
                    return jsonError(Response.Status.BAD_REQUEST, "name_too_long")
                }
                if (value.length > 8192) {
                    return jsonError(Response.Status.BAD_REQUEST, "value_too_long")
                }
                credentialStore.set(nameTrimmed, value)
                jsonResponse(JSONObject().put("status", "ok").put("name", nameTrimmed))
            }
            uri == "/vault/credentials/get" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val name = payload.optString("name", "")
                val permissionId = payload.optString("permission_id", "")
                if (permissionId.isBlank()) {
                    return jsonError(Response.Status.BAD_REQUEST, "permission_id_required")
                }
                if (!isPermissionApproved(permissionId, consume = false)) {
                    return jsonError(Response.Status.FORBIDDEN, "permission_required")
                }
                val nameTrimmed = name.trim()
                if (nameTrimmed.isBlank()) {
                    return jsonError(Response.Status.BAD_REQUEST, "name_required")
                }
                if (nameTrimmed.length > 128) {
                    return jsonError(Response.Status.BAD_REQUEST, "name_too_long")
                }
                val row = credentialStore.get(nameTrimmed)
                if (row == null) {
                    return jsonError(Response.Status.NOT_FOUND, "not_found")
                }
                jsonResponse(
                    JSONObject()
                        .put("name", row.name)
                        .put("value", row.value)
                        .put("updated_at", row.updatedAt)
                )
            }
            uri == "/vault/credentials/has" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val name = payload.optString("name", "")
                val permissionId = payload.optString("permission_id", "")
                if (permissionId.isBlank()) {
                    return jsonError(Response.Status.BAD_REQUEST, "permission_id_required")
                }
                if (!isPermissionApproved(permissionId, consume = false)) {
                    return jsonError(Response.Status.FORBIDDEN, "permission_required")
                }
                val nameTrimmed = name.trim()
                if (nameTrimmed.isBlank()) {
                    return jsonError(Response.Status.BAD_REQUEST, "name_required")
                }
                if (nameTrimmed.length > 128) {
                    return jsonError(Response.Status.BAD_REQUEST, "name_too_long")
                }
                val row = credentialStore.get(nameTrimmed)
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("name", nameTrimmed)
                        .put("present", row != null && row.value.trim().isNotEmpty())
                        .put("updated_at", row?.updatedAt ?: 0)
                )
            }
            else -> notFound()
        }
    }

    private fun routeBuiltins(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/builtins/tts" && session.method == Method.POST -> {
                return jsonError(Response.Status.NOT_IMPLEMENTED, "not_implemented", JSONObject().put("feature", "tts"))
            }
            uri == "/builtins/stt" && session.method == Method.POST -> {
                return jsonError(Response.Status.NOT_IMPLEMENTED, "not_implemented", JSONObject().put("feature", "stt"))
            }
            else -> notFound()
        }
    }

    private fun routeBrain(session: IHTTPSession, uri: String, postBody: String?): Response {
        val runtime = getOrCreateAgentRuntime()
        return when {
            // --- SSE event stream (native) ---
            uri == "/brain/events" && session.method == Method.GET -> {
                serveBrainSse()
            }
            // --- GET endpoints (native) ---
            uri == "/brain/status" && session.method == Method.GET -> {
                jsonResponse(runtime.status())
            }
            uri == "/brain/messages" && session.method == Method.GET -> {
                val params = session.parms ?: emptyMap()
                val sessionId = (params["session_id"] ?: "default").trim()
                val limit = (params["limit"] ?: "200").toIntOrNull() ?: 200
                val msgs = runtime.listMessagesForSession(sessionId, limit)
                val arr = JSONArray()
                for (m in msgs) {
                    val obj = JSONObject(m)
                    // Parse meta from string to object for the UI
                    val metaStr = obj.optString("meta", "")
                    if (metaStr.isNotEmpty()) {
                        try { obj.put("meta", JSONObject(metaStr)) } catch (_: Exception) {}
                    }
                    // Alias created_at → ts for JS poll dedup
                    if (obj.has("created_at") && !obj.has("ts")) {
                        obj.put("ts", obj.optLong("created_at", 0))
                    }
                    arr.put(obj)
                }
                jsonResponse(JSONObject().put("messages", arr))
            }
            uri == "/brain/sessions" && session.method == Method.GET -> {
                val params = session.parms ?: emptyMap()
                val limit = (params["limit"] ?: "50").toIntOrNull() ?: 50
                val sessions = runtime.listSessions(limit)
                val arr = JSONArray()
                for (s in sessions) {
                    arr.put(JSONObject(s))
                }
                jsonResponse(JSONObject().put("sessions", arr))
            }
            uri == "/brain/journal/config" && session.method == Method.GET -> {
                jsonResponse(agentJournalStore.config())
            }
            uri == "/brain/journal/current" && session.method == Method.GET -> {
                val params = session.parms ?: emptyMap()
                val sessionId = (params["session_id"] ?: "default").trim()
                jsonResponse(agentJournalStore.getCurrent(sessionId))
            }
            uri == "/brain/journal/list" && session.method == Method.GET -> {
                val params = session.parms ?: emptyMap()
                val sessionId = (params["session_id"] ?: "default").trim()
                val limit = (params["limit"] ?: "50").toIntOrNull() ?: 50
                jsonResponse(agentJournalStore.listEntries(sessionId, limit))
            }
            // --- POST endpoints (native) ---
            uri == "/brain/start" && session.method == Method.POST -> {
                jsonResponse(runtime.start())
            }
            uri == "/brain/stop" && session.method == Method.POST -> {
                jsonResponse(runtime.stop())
            }
            uri == "/brain/interrupt" && session.method == Method.POST -> {
                val body = runCatching { JSONObject(postBody ?: "{}") }.getOrDefault(JSONObject())
                jsonResponse(runtime.interrupt(
                    itemId = body.optString("item_id", ""),
                    sessionId = body.optString("session_id", ""),
                    clearQueue = body.optBoolean("clear_queue", false),
                ))
            }
            uri == "/brain/inbox/chat" && session.method == Method.POST -> {
                val body = runCatching { JSONObject(postBody ?: "{}") }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                val text = body.optString("text", "").trim()
                if (text.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "missing_text")
                val meta = body.optJSONObject("meta") ?: JSONObject()
                jsonResponse(runtime.enqueueChat(text, meta))
            }
            uri == "/brain/inbox/event" && session.method == Method.POST -> {
                val body = runCatching { JSONObject(postBody ?: "{}") }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                jsonResponse(runtime.enqueueEvent(
                    name = body.optString("name", ""),
                    payload = body.optJSONObject("payload") ?: JSONObject(),
                    priority = body.optString("priority", "normal"),
                    interruptPolicy = body.optString("interrupt_policy", "turn_end"),
                    coalesceKey = body.optString("coalesce_key", ""),
                    coalesceWindowMs = body.optLong("coalesce_window_ms", 0),
                ))
            }
            uri == "/brain/session/delete" && session.method == Method.POST -> {
                val body = runCatching { JSONObject(postBody ?: "{}") }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                val sessionId = body.optString("session_id", "").trim()
                if (sessionId.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "missing_session_id")
                val deleted = agentStorage.deleteChatSession(sessionId)
                jsonResponse(JSONObject().put("status", "ok").put("deleted", deleted))
            }
            uri == "/brain/session/rename" && session.method == Method.POST -> {
                val body = runCatching { JSONObject(postBody ?: "{}") }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                val oldId = body.optString("old_id", "").trim()
                val newId = body.optString("new_id", "").trim()
                if (oldId.isEmpty() || newId.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "missing_ids")
                val renamed = agentStorage.renameChatSession(oldId, newId)
                agentJournalStore.renameSession(oldId, newId)
                jsonResponse(JSONObject().put("status", "ok").put("renamed", renamed))
            }
            uri == "/brain/journal/current" && session.method == Method.POST -> {
                val body = runCatching { JSONObject(postBody ?: "{}") }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                val sessionId = body.optString("session_id", "default").trim()
                val text = body.optString("text", "")
                jsonResponse(agentJournalStore.setCurrent(sessionId, text))
            }
            uri == "/brain/journal/append" && session.method == Method.POST -> {
                val body = runCatching { JSONObject(postBody ?: "{}") }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                val sessionId = body.optString("session_id", "default").trim()
                jsonResponse(agentJournalStore.append(
                    sessionId,
                    kind = body.optString("kind", "note"),
                    title = body.optString("title", ""),
                    text = body.optString("text", ""),
                    meta = body.optJSONObject("meta"),
                ))
            }
            // --- Config routes (unchanged) ---
            uri == "/brain/config" && session.method == Method.GET -> handleBrainConfigGet()
            uri == "/brain/config" && session.method == Method.POST -> {
                val body = postBody ?: ""
                handleBrainConfigSet(body)
            }
            uri == "/brain/agent/bootstrap" && session.method == Method.POST -> {
                handleBrainAgentBootstrap()
            }
            uri == "/brain/chat" && session.method == Method.POST -> {
                jsonError(Response.Status.GONE, "chat_mode_removed")
            }
            uri == "/brain/memory" && session.method == Method.GET -> {
                jsonResponse(JSONObject().put("content", readMemory()))
            }
            uri == "/brain/memory" && session.method == Method.POST -> {
                val body = postBody ?: ""
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                writeMemory(payload.optString("content", ""))
                jsonResponse(JSONObject().put("status", "ok"))
            }
            else -> notFound()
        }
    }

    private fun serveBrainSse(): Response {
        val pipedOut = java.io.PipedOutputStream()
        val pipedIn = java.io.PipedInputStream(pipedOut, 8192)
        brainSseClients.add(pipedOut)
        // Send initial status event
        try {
            val runtime = agentRuntime
            val initData = runtime?.status() ?: JSONObject().put("running", false)
            pipedOut.write("event: brain_status\ndata: ${initData}\n\n".toByteArray(Charsets.UTF_8))
            pipedOut.flush()
        } catch (_: Exception) {}
        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        return response
    }

    private fun routeShell(session: IHTTPSession, uri: String, postBody: String?): Response {
        if (session.method != Method.POST && session.method != Method.GET) {
            return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
        }
        return when {
            (uri == "/shell/exec" || uri == "/shell/exec/") && session.method == Method.POST -> {
                val body = postBody ?: ""
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                return handleShellExec(payload)
            }
            // Session endpoints: /shell/session/start, /shell/session/<id>/<action>, /shell/session/list
            uri.startsWith("/shell/session/") -> {
                ensureWorkerRunning()
                val subPath = uri.removePrefix("/shell")  // → /session/...
                if (session.method == Method.GET) {
                    return proxyWorkerRequest(subPath, "GET") ?: jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
                }
                val body = postBody ?: "{}"
                val timeoutMs = if (subPath.contains("/exec")) 305_000 else 10_000
                return proxyWorkerRequest(subPath, "POST", body, readTimeoutMs = timeoutMs) ?: jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
            }
            // File system endpoints: /shell/fs/<action>
            uri.startsWith("/shell/fs/") && session.method == Method.POST -> {
                ensureWorkerRunning()
                val subPath = uri.removePrefix("/shell")  // → /fs/...
                val body = postBody ?: "{}"
                return proxyWorkerRequest(subPath, "POST", body) ?: jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
            }
            else -> notFound()
        }
    }

    private fun ensureWorkerRunning() {
        if (runtimeManager.getStatus() != "ok") {
            runtimeManager.startWorker()
            waitForTermuxHealth(5000)
        }
    }

    private fun handleShellExec(payload: JSONObject): Response {
        // Support both legacy cmd+args format and new command format
        val hasCommand = payload.has("command") && payload.optString("command", "").isNotEmpty()

        if (hasCommand) {
            // New format: try worker first, fall back to native shell
            ensureWorkerRunning()
            val timeoutMs = payload.optLong("timeout_ms", 60_000).coerceIn(1_000, 300_000)
            val readTimeout = (timeoutMs + 5_000).toInt()
            val proxied = proxyWorkerRequest("/exec", "POST", payload.toString(), readTimeoutMs = readTimeout)
            if (proxied != null) return proxied

            // Worker unavailable — use native shell fallback
            val command = payload.optString("command", "")
            val cwd = payload.optString("cwd", "")
            val env = payload.optJSONObject("env")
            val result = nativeShellExecutor.exec(command, cwd, timeoutMs, env)
            return jsonResponse(result)
        }

        // Legacy format: cmd + args
        val cmd = payload.optString("cmd")
        val args = payload.optString("args", "")
        val cwd = payload.optString("cwd", "")
        if (cmd != "python" && cmd != "pip" && cmd != "curl") {
            return jsonError(Response.Status.FORBIDDEN, "command_not_allowed")
        }

        ensureWorkerRunning()
        val proxied = proxyShellExecToWorker(cmd, args, cwd)
        if (proxied != null) {
            return proxied
        }
        return jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
    }

    private fun routeWeb(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/web/search" || uri == "/web/search/") -> {
                if (session.method != Method.POST) {
                    return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
                }
                val body = postBody ?: ""
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                return handleWebSearch(session, payload)
            }
            else -> notFound()
        }
    }

    private fun routePip(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/pip/download" || uri == "/pip/download/") -> {
                if (session.method != Method.POST) {
                    return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
                }
                val body = postBody ?: ""
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                return handlePipDownload(session, payload)
            }
            (uri == "/pip/install" || uri == "/pip/install/") -> {
                if (session.method != Method.POST) {
                    return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
                }
                val body = postBody ?: ""
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                return handlePipInstall(session, payload)
            }
            (uri == "/pip/status" || uri == "/pip/status/") -> {
                return jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "")
                )
            }
            else -> notFound()
        }
    }

    private fun routeCloud(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/cloud/request" || uri == "/cloud/request/") -> {
                if (session.method != Method.POST) {
                    return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
                }
                val body = (postBody ?: "").ifBlank { "{}" }
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                return handleCloudRequest(session, payload)
            }
            else -> notFound()
        }
    }

    private fun routeFileTransfer(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/file_transfer/prefs" && session.method == Method.GET -> {
                val autoMb = fileTransferPrefs.getFloat("auto_upload_no_confirm_mb", 1.0f).toDouble()
                val minKbps = fileTransferPrefs.getFloat("min_transfer_kbps", 0.0f).toDouble()
                val imgResizeEnabled = fileTransferPrefs.getBoolean("image_resize_enabled", true)
                val imgMaxDim = fileTransferPrefs.getInt("image_resize_max_dim_px", 512)
                val imgJpegQ = fileTransferPrefs.getInt("image_resize_jpeg_quality", 70)
                return jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("auto_upload_no_confirm_mb", autoMb)
                        // Alias for agents/configuration: same meaning as auto_upload_no_confirm_mb.
                        .put("allow_auto_upload_payload_size_less_than_mb", autoMb)
                        .put("min_transfer_kbps", minKbps)
                        .put("image_resize_enabled", imgResizeEnabled)
                        .put("image_resize_max_dim_px", imgMaxDim)
                        .put("image_resize_jpeg_quality", imgJpegQ)
                )
            }
            uri == "/file_transfer/prefs" && session.method == Method.POST -> {
                val body = (postBody ?: "").ifBlank { "{}" }
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                val v = when {
                    payload.has("auto_upload_no_confirm_mb") ->
                        payload.optDouble("auto_upload_no_confirm_mb", fileTransferPrefs.getFloat("auto_upload_no_confirm_mb", 1.0f).toDouble())
                    payload.has("allow_auto_upload_payload_size_less_than_mb") ->
                        payload.optDouble("allow_auto_upload_payload_size_less_than_mb", fileTransferPrefs.getFloat("auto_upload_no_confirm_mb", 1.0f).toDouble())
                    else ->
                        fileTransferPrefs.getFloat("auto_upload_no_confirm_mb", 1.0f).toDouble()
                }
                val clamped = v.coerceIn(0.0, 25.0)
                val mk = payload.optDouble("min_transfer_kbps", fileTransferPrefs.getFloat("min_transfer_kbps", 0.0f).toDouble())
                val mkClamped = mk.coerceIn(0.0, 50_000.0)
                val imgEnabled = if (payload.has("image_resize_enabled")) payload.optBoolean("image_resize_enabled", true) else fileTransferPrefs.getBoolean("image_resize_enabled", true)
                val imgMaxDimRaw = if (payload.has("image_resize_max_dim_px")) payload.optInt("image_resize_max_dim_px", 512) else fileTransferPrefs.getInt("image_resize_max_dim_px", 512)
                val imgJpegQRaw = if (payload.has("image_resize_jpeg_quality")) payload.optInt("image_resize_jpeg_quality", 70) else fileTransferPrefs.getInt("image_resize_jpeg_quality", 70)
                val imgMaxDim = imgMaxDimRaw.coerceIn(64, 4096)
                val imgJpegQ = imgJpegQRaw.coerceIn(30, 95)
                fileTransferPrefs.edit()
                    .putFloat("auto_upload_no_confirm_mb", clamped.toFloat())
                    .putFloat("min_transfer_kbps", mkClamped.toFloat())
                    .putBoolean("image_resize_enabled", imgEnabled)
                    .putInt("image_resize_max_dim_px", imgMaxDim)
                    .putInt("image_resize_jpeg_quality", imgJpegQ)
                    .apply()
                // Propagate to agent tool executor
                agentToolExecutor.imageResizeEnabled = imgEnabled
                agentToolExecutor.imageMaxDimPx = imgMaxDim
                agentToolExecutor.imageJpegQuality = imgJpegQ
                return jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("auto_upload_no_confirm_mb", clamped)
                        .put("allow_auto_upload_payload_size_less_than_mb", clamped)
                        .put("min_transfer_kbps", mkClamped)
                        .put("image_resize_enabled", imgEnabled)
                        .put("image_resize_max_dim_px", imgMaxDim)
                        .put("image_resize_jpeg_quality", imgJpegQ)
                )
            }
            else -> notFound()
        }
    }

    private fun routeNotifications(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/notifications/prefs" && session.method == Method.GET -> {
                val android = taskCompletionPrefs.getBoolean("notify_android", true)
                val sound = taskCompletionPrefs.getBoolean("notify_sound", false)
                val webhook = taskCompletionPrefs.getString("notify_webhook_url", "") ?: ""
                return jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("notify_android", android)
                        .put("notify_sound", sound)
                        .put("notify_webhook_url", webhook)
                )
            }
            uri == "/notifications/prefs" && session.method == Method.POST -> {
                val body = (postBody ?: "").ifBlank { "{}" }
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                val android = if (payload.has("notify_android")) payload.optBoolean("notify_android", true) else taskCompletionPrefs.getBoolean("notify_android", true)
                val sound = if (payload.has("notify_sound")) payload.optBoolean("notify_sound", false) else taskCompletionPrefs.getBoolean("notify_sound", false)
                val webhook = if (payload.has("notify_webhook_url")) payload.optString("notify_webhook_url", "") else (taskCompletionPrefs.getString("notify_webhook_url", "") ?: "")
                taskCompletionPrefs.edit()
                    .putBoolean("notify_android", android)
                    .putBoolean("notify_sound", sound)
                    .putString("notify_webhook_url", webhook)
                    .apply()
                return jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("notify_android", android)
                        .put("notify_sound", sound)
                        .put("notify_webhook_url", webhook)
                )
            }
            else -> notFound()
        }
    }

    private fun routeScreen(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/screen/status" || uri == "/screen/status/") && session.method == Method.GET -> {
                return handleScreenStatus()
            }
            (uri == "/screen/keep_on" || uri == "/screen/keep_on/") && session.method == Method.POST -> {
                val body = (postBody ?: "").ifBlank { "{}" }
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                return handleScreenKeepOn(session, payload)
            }
            (uri == "/screen/record/status" || uri == "/screen/record/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.screen", capability = "screen_recording", detail = "Screen recording status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(screenRecord.status()))
            }
            (uri == "/screen/record/start" || uri == "/screen/record/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.screen", capability = "screen_recording", detail = "Start screen recording")
                if (!ok.first) return ok.second!!
                val path = payload.optString("path", "").trim().ifBlank { null }
                val maxDur = if (payload.has("max_duration_s")) payload.optInt("max_duration_s") else null
                val resolution = payload.optString("resolution", "").trim().ifBlank { null }
                val bitrate = if (payload.has("bitrate")) payload.optInt("bitrate") else null
                return jsonResponse(JSONObject(screenRecord.startRecording(path, maxDur, resolution, bitrate)))
            }
            (uri == "/screen/record/stop" || uri == "/screen/record/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.screen", capability = "screen_recording", detail = "Stop screen recording")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(screenRecord.stopRecording()))
            }
            (uri == "/screen/record/config" || uri == "/screen/record/config/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.screen", capability = "screen_recording", detail = "Get screen recording config")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(screenRecord.getConfig()))
            }
            (uri == "/screen/record/config" || uri == "/screen/record/config/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.screen", capability = "screen_recording", detail = "Set screen recording config")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(screenRecord.setConfig(payload)))
            }
            else -> notFound()
        }
    }

    private fun routeUsb(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/usb/list" || uri == "/usb/list/") && session.method == Method.GET -> {
                return handleUsbList(session)
            }
            (uri == "/usb/status" || uri == "/usb/status/") && session.method == Method.GET -> {
                return handleUsbStatus(session)
            }
            (uri == "/usb/open" || uri == "/usb/open/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUsbOpen(session, payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "USB open handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "usb_open_handler_failed")
                }
            }
            (uri == "/usb/close" || uri == "/usb/close/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUsbClose(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "USB close handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "usb_close_handler_failed")
                }
            }
            (uri == "/usb/control_transfer" || uri == "/usb/control_transfer/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUsbControlTransfer(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "USB control_transfer handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "usb_control_transfer_handler_failed")
                }
            }
            (uri == "/usb/raw_descriptors" || uri == "/usb/raw_descriptors/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUsbRawDescriptors(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "USB raw_descriptors handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "usb_raw_descriptors_handler_failed")
                }
            }
            (uri == "/usb/claim_interface" || uri == "/usb/claim_interface/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUsbClaimInterface(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "USB claim_interface handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "usb_claim_interface_handler_failed")
                }
            }
            (uri == "/usb/release_interface" || uri == "/usb/release_interface/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUsbReleaseInterface(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "USB release_interface handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "usb_release_interface_handler_failed")
                }
            }
            (uri == "/usb/bulk_transfer" || uri == "/usb/bulk_transfer/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUsbBulkTransfer(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "USB bulk_transfer handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "usb_bulk_transfer_handler_failed")
                }
            }
            (uri == "/usb/iso_transfer" || uri == "/usb/iso_transfer/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUsbIsoTransfer(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "USB iso_transfer handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "usb_iso_transfer_handler_failed")
                }
            }
            (uri == "/usb/stream/start" || uri == "/usb/stream/start/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUsbStreamStart(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "USB stream/start handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "usb_stream_start_handler_failed")
                }
            }
            (uri == "/usb/stream/stop" || uri == "/usb/stream/stop/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUsbStreamStop(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "USB stream/stop handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "usb_stream_stop_handler_failed")
                }
            }
            (uri == "/usb/stream/status" || uri == "/usb/stream/status/") && session.method == Method.GET -> {
                return handleUsbStreamStatus()
            }
            else -> notFound()
        }
    }

    private fun routeMcu(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/mcu/models" || uri == "/mcu/models/") && session.method == Method.GET -> {
                return handleMcuModels()
            }
            (uri == "/mcu/probe" || uri == "/mcu/probe/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleMcuProbe(session, payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "MCU probe handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "mcu_probe_handler_failed")
                }
            }
            (uri == "/mcu/flash" || uri == "/mcu/flash/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleMcuFlash(session, payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "MCU flash handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "mcu_flash_handler_failed")
                }
            }
            (uri == "/mcu/flash/plan" || uri == "/mcu/flash/plan/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleMcuFlashPlan(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "MCU flash plan handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "mcu_flash_plan_handler_failed")
                }
            }
            (uri == "/mcu/diag/serial" || uri == "/mcu/diag/serial/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleMcuDiagSerial(session, payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "MCU diag serial handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "mcu_diag_serial_handler_failed")
                }
            }
            (uri == "/mcu/serial_lines" || uri == "/mcu/serial_lines/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleMcuSerialLines(session, payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "MCU serial lines handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "mcu_serial_lines_handler_failed")
                }
            }
            (uri == "/mcu/reset" || uri == "/mcu/reset/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleMcuReset(session, payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "MCU reset handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "mcu_reset_handler_failed")
                }
            }
            (uri == "/mcu/serial_monitor" || uri == "/mcu/serial_monitor/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleMcuSerialMonitor(session, payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "MCU serial monitor handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "mcu_serial_monitor_handler_failed")
                }
            }
            else -> notFound()
        }
    }

    private fun routeSerial(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/serial/status" || uri == "/serial/status/") && session.method == Method.GET -> {
                return handleSerialStatus(session)
            }
            (uri == "/serial/open" || uri == "/serial/open/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleSerialOpen(session, payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "Serial open handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "serial_open_handler_failed")
                }
            }
            (uri == "/serial/list_ports" || uri == "/serial/list_ports/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleSerialListPorts(session, payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "Serial list_ports handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "serial_list_ports_handler_failed")
                }
            }
            (uri == "/serial/close" || uri == "/serial/close/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleSerialClose(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "Serial close handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "serial_close_handler_failed")
                }
            }
            (uri == "/serial/read" || uri == "/serial/read/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleSerialRead(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "Serial read handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "serial_read_handler_failed")
                }
            }
            (uri == "/serial/write" || uri == "/serial/write/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleSerialWrite(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "Serial write handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "serial_write_handler_failed")
                }
            }
            (uri == "/serial/lines" || uri == "/serial/lines/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleSerialLines(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "Serial lines handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "serial_lines_handler_failed")
                }
            }
            else -> notFound()
        }
    }

    private fun routeUvc(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/uvc/mjpeg/capture" || uri == "/uvc/mjpeg/capture/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUvcMjpegCapture(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "UVC mjpeg/capture handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "uvc_capture_handler_failed")
                }
            }
            (uri == "/uvc/diagnose" || uri == "/uvc/diagnose/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleUvcDiagnose(session, payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "UVC diagnose handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "uvc_diagnose_handler_failed")
                }
            }
            else -> notFound()
        }
    }

    private fun routeVision(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/vision/model/load" || uri == "/vision/model/load/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleVisionModelLoad(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "vision model/load handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "vision_model_load_failed")
                }
            }
            (uri == "/vision/model/unload" || uri == "/vision/model/unload/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleVisionModelUnload(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "vision model/unload handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "vision_model_unload_failed")
                }
            }
            (uri == "/vision/frame/put" || uri == "/vision/frame/put/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleVisionFramePut(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "vision frame/put handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "vision_frame_put_failed")
                }
            }
            (uri == "/vision/frame/get" || uri == "/vision/frame/get/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleVisionFrameGet(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "vision frame/get handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "vision_frame_get_failed")
                }
            }
            (uri == "/vision/frame/delete" || uri == "/vision/frame/delete/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleVisionFrameDelete(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "vision frame/delete handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "vision_frame_delete_failed")
                }
            }
            (uri == "/vision/frame/save" || uri == "/vision/frame/save/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleVisionFrameSave(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "vision frame/save handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "vision_frame_save_failed")
                }
            }
            (uri == "/vision/image/load" || uri == "/vision/image/load/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleVisionImageLoad(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "vision image/load handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "vision_image_load_failed")
                }
            }
            (uri == "/vision/run" || uri == "/vision/run/") && session.method == Method.POST -> {
                return try {
                    val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                    handleVisionRun(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "vision run handler failed", ex)
                    jsonError(Response.Status.INTERNAL_ERROR, "vision_run_failed")
                }
            }
            else -> notFound()
        }
    }

    private fun routeCamera(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/camera/list" || uri == "/camera/list/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.camera", capability = "camera", detail = "List cameras")
                if (!ok.first) return ok.second!!
                val out = JSONObject(camera.listCameras())
                if (out.has("cameras")) {
                    out.put("cameras", org.json.JSONArray(out.getString("cameras")))
                }
                return jsonResponse(out)
            }
            (uri == "/camera/status" || uri == "/camera/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.camera", capability = "camera", detail = "Camera status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(camera.status()))
            }
            (uri == "/camera/preview/start" || uri == "/camera/preview/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.camera", capability = "camera", detail = "Start camera preview stream")
                if (!ok.first) return ok.second!!
                val lens = payload.optString("lens", "back")
                val w = payload.optInt("width", 640)
                val h = payload.optInt("height", 480)
                val fps = payload.optInt("fps", 5)
                val q = payload.optInt("jpeg_quality", 70)
                return jsonResponse(JSONObject(camera.startPreview(lens, w, h, fps, q)))
            }
            (uri == "/camera/preview/stop" || uri == "/camera/preview/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.camera", capability = "camera", detail = "Stop camera preview stream")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(camera.stopPreview()))
            }
            (uri == "/camera/capture" || uri == "/camera/capture/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.camera", capability = "camera", detail = "Capture still image")
                if (!ok.first) return ok.second!!
                val outPath = payload.optString("path", "captures/capture_${System.currentTimeMillis()}.jpg")
                val lens = payload.optString("lens", "back")
                val outRef = parseFsPathRef(outPath.trim()) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
                val tempCaptureRef = if (outRef.fs == "user") {
                    outRef
                } else {
                    parseFsPathRef("user://captures/.tmp_capture_${System.currentTimeMillis()}.jpg")
                        ?: return jsonError(Response.Status.INTERNAL_ERROR, "temp_path_resolve_failed")
                }
                val file = tempCaptureRef.userFile ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
                val q = payload.optInt("jpeg_quality", 95).coerceIn(40, 100)
                val exp = if (payload.has("exposure_compensation")) payload.optInt("exposure_compensation") else null
                val out = JSONObject(camera.captureStill(file, lens, jpegQuality = q, exposureCompensation = exp))
                if (out.optString("status", "") == "ok" && outRef.fs == "termux") {
                    val bytes = try {
                        file.readBytes()
                    } catch (ex: Exception) {
                        return jsonError(Response.Status.INTERNAL_ERROR, "capture_temp_read_failed", JSONObject().put("detail", ex.message ?: ""))
                    }
                    try {
                        writeFsPathBytes(outRef, bytes)
                    } catch (ex: IllegalArgumentException) {
                        return when (ex.message ?: "") {
                            "path_outside_termux_home" -> jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                            "termux_unavailable" -> jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
                            else -> jsonError(Response.Status.INTERNAL_ERROR, "termux_fs_write_failed")
                        }
                    } finally {
                        runCatching { file.delete() }
                    }
                }
                // Absolute path is useful for logs/debugging, but tools should prefer rel_path under user root.
                out.put("rel_path", outRef.displayPath)
                return jsonResponse(out)
            }
            else -> notFound()
        }
    }

    private fun routeBle(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/ble/status" || uri == "/ble/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.ble", capability = "ble", detail = "Bluetooth status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(ble.status()))
            }
            (uri == "/ble/scan/start" || uri == "/ble/scan/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.ble", capability = "ble", detail = "Start BLE scan")
                if (!ok.first) return ok.second!!
                val lowLatency = payload.optBoolean("low_latency", true)
                val resp = JSONObject(ble.scanStart(lowLatency)).put("ws_path", "/ws/ble/events")
                return jsonResponse(resp)
            }
            (uri == "/ble/scan/stop" || uri == "/ble/scan/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.ble", capability = "ble", detail = "Stop BLE scan")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(ble.scanStop()))
            }
            (uri == "/ble/connect" || uri == "/ble/connect/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.ble", capability = "ble", detail = "Connect BLE device")
                if (!ok.first) return ok.second!!
                val address = payload.optString("address", "")
                val auto = payload.optBoolean("auto_connect", false)
                val resp = JSONObject(ble.connect(address, auto)).put("ws_path", "/ws/ble/events")
                return jsonResponse(resp)
            }
            (uri == "/ble/disconnect" || uri == "/ble/disconnect/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.ble", capability = "ble", detail = "Disconnect BLE device")
                if (!ok.first) return ok.second!!
                val address = payload.optString("address", "")
                return jsonResponse(JSONObject(ble.disconnect(address)))
            }
            (uri == "/ble/gatt/services" || uri == "/ble/gatt/services/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.ble", capability = "ble", detail = "List BLE GATT services")
                if (!ok.first) return ok.second!!
                val address = payload.optString("address", "")
                val m = ble.services(address)
                // servicesJson is encoded as string to keep kotlin->json conversion simple.
                val out = JSONObject(m)
                if (out.has("services")) {
                    out.put("services", org.json.JSONArray(out.getString("services")))
                }
                return jsonResponse(out)
            }
            (uri == "/ble/gatt/read" || uri == "/ble/gatt/read/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.ble", capability = "ble", detail = "Read BLE characteristic")
                if (!ok.first) return ok.second!!
                return jsonResponse(
                    JSONObject(
                        ble.read(
                            payload.optString("address", ""),
                            payload.optString("service_uuid", ""),
                            payload.optString("char_uuid", "")
                        )
                    )
                )
            }
            (uri == "/ble/gatt/write" || uri == "/ble/gatt/write/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.ble", capability = "ble", detail = "Write BLE characteristic")
                if (!ok.first) return ok.second!!
                val b64 = payload.optString("value_b64", "")
                val bytes = runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_value_b64")
                val withResp = payload.optBoolean("with_response", true)
                return jsonResponse(
                    JSONObject(
                        ble.write(
                            payload.optString("address", ""),
                            payload.optString("service_uuid", ""),
                            payload.optString("char_uuid", ""),
                            bytes,
                            withResp
                        )
                    )
                )
            }
            (uri == "/ble/gatt/notify/start" || uri == "/ble/gatt/notify/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.ble", capability = "ble", detail = "Subscribe BLE notifications")
                if (!ok.first) return ok.second!!
                return jsonResponse(
                    JSONObject(
                        ble.notifyStart(
                            payload.optString("address", ""),
                            payload.optString("service_uuid", ""),
                            payload.optString("char_uuid", "")
                        )
                    )
                )
            }
            (uri == "/ble/gatt/notify/stop" || uri == "/ble/gatt/notify/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.ble", capability = "ble", detail = "Unsubscribe BLE notifications")
                if (!ok.first) return ok.second!!
                return jsonResponse(
                    JSONObject(
                        ble.notifyStop(
                            payload.optString("address", ""),
                            payload.optString("service_uuid", ""),
                            payload.optString("char_uuid", "")
                        )
                    )
                )
            }
            else -> notFound()
        }
    }

    private fun routeTts(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/tts/init" || uri == "/tts/init/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.tts", capability = "tts", detail = "Initialize TTS")
                if (!ok.first) return ok.second!!
                val engine = payload.optString("engine", "").trim().ifBlank { null }
                return jsonResponse(JSONObject(tts.init(engine)))
            }
            (uri == "/tts/voices" || uri == "/tts/voices/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.tts", capability = "tts", detail = "List TTS voices")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(tts.listVoices()))
            }
            (uri == "/tts/speak" || uri == "/tts/speak/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.tts", capability = "tts", detail = "Speak text")
                if (!ok.first) return ok.second!!
                val text = payload.optString("text", "")
                val voice = payload.optString("voice", "").trim().ifBlank { null }
                val locale = payload.optString("locale", "").trim().ifBlank { null }
                val rate = if (payload.has("rate")) payload.optDouble("rate", 1.0).toFloat() else null
                val pitch = if (payload.has("pitch")) payload.optDouble("pitch", 1.0).toFloat() else null
                return jsonResponse(JSONObject(tts.speak(text, voice, locale, rate, pitch)))
            }
            (uri == "/tts/stop" || uri == "/tts/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.tts", capability = "tts", detail = "Stop TTS")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(tts.stop()))
            }
            else -> notFound()
        }
    }

    private fun routeMedia(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/media/audio/status" || uri == "/media/audio/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.media", capability = "media", detail = "Audio playback status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(mediaAudio.status()))
            }
            (uri == "/media/audio/play" || uri == "/media/audio/play/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.media", capability = "media", detail = "Play audio")
                if (!ok.first) return ok.second!!
                val path = payload.optString("path", "").trim().ifBlank { null }
                val audioB64 = payload.optString("audio_b64", "").trim().ifBlank { null }
                val ext = payload.optString("ext", "").trim().ifBlank { null }
                return jsonResponse(JSONObject(mediaAudio.play(path, audioB64, ext)))
            }
            (uri == "/media/audio/stop" || uri == "/media/audio/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.media", capability = "media", detail = "Stop audio playback")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(mediaAudio.stop()))
            }
            // ── Media Stream (file decode) ───────────────────────────────
            (uri == "/media/stream/status" || uri == "/media/stream/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.media", capability = "media_stream", detail = "Media stream status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(mediaStream.status()))
            }
            (uri == "/media/stream/audio/start" || uri == "/media/stream/audio/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.media", capability = "media_stream", detail = "Decode audio file to PCM stream")
                if (!ok.first) return ok.second!!
                val src = payload.optString("source_file", "").trim().ifBlank { null }
                val sr = if (payload.has("sample_rate")) payload.optInt("sample_rate") else null
                val ch = if (payload.has("channels")) payload.optInt("channels") else null
                return jsonResponse(JSONObject(mediaStream.startAudioDecode(src, sr, ch)))
            }
            (uri == "/media/stream/video/start" || uri == "/media/stream/video/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.media", capability = "media_stream", detail = "Decode video file to frame stream")
                if (!ok.first) return ok.second!!
                val src = payload.optString("source_file", "").trim().ifBlank { null }
                val format = payload.optString("format", "").trim().ifBlank { null }
                val fps = if (payload.has("fps")) payload.optInt("fps") else null
                val jq = if (payload.has("jpeg_quality")) payload.optInt("jpeg_quality") else null
                return jsonResponse(JSONObject(mediaStream.startVideoDecode(src, format, fps, jq)))
            }
            (uri == "/media/stream/stop" || uri == "/media/stream/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.media", capability = "media_stream", detail = "Stop media decode stream")
                if (!ok.first) return ok.second!!
                val streamId = payload.optString("stream_id", "").trim().ifBlank { null }
                return jsonResponse(JSONObject(mediaStream.stopDecode(streamId)))
            }
            else -> notFound()
        }
    }

    private fun routeAudio(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/audio/record/status" || uri == "/audio/record/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.mic", capability = "recording", detail = "Audio recording status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(audioRecord.status()))
            }
            (uri == "/audio/record/start" || uri == "/audio/record/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.mic", capability = "recording", detail = "Start audio recording")
                if (!ok.first) return ok.second!!
                val path = payload.optString("path", "").trim().ifBlank { null }
                val maxDur = if (payload.has("max_duration_s")) payload.optInt("max_duration_s") else null
                val sr = if (payload.has("sample_rate")) payload.optInt("sample_rate") else null
                val ch = if (payload.has("channels")) payload.optInt("channels") else null
                val br = if (payload.has("bitrate")) payload.optInt("bitrate") else null
                return jsonResponse(JSONObject(audioRecord.startRecording(path, maxDur, sr, ch, br)))
            }
            (uri == "/audio/record/stop" || uri == "/audio/record/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.mic", capability = "recording", detail = "Stop audio recording")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(audioRecord.stopRecording()))
            }
            (uri == "/audio/record/config" || uri == "/audio/record/config/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.mic", capability = "recording", detail = "Get audio recording config")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(audioRecord.getConfig()))
            }
            (uri == "/audio/record/config" || uri == "/audio/record/config/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.mic", capability = "recording", detail = "Set audio recording config")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(audioRecord.setConfig(payload)))
            }

            // ── Audio PCM Streaming ──────────────────────────────────────
            (uri == "/audio/stream/start" || uri == "/audio/stream/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.mic", capability = "recording", detail = "Start live PCM audio stream")
                if (!ok.first) return ok.second!!
                val sr = if (payload.has("sample_rate")) payload.optInt("sample_rate") else null
                val ch = if (payload.has("channels")) payload.optInt("channels") else null
                return jsonResponse(JSONObject(audioRecord.startStream(sr, ch)))
            }
            (uri == "/audio/stream/stop" || uri == "/audio/stream/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.mic", capability = "recording", detail = "Stop live PCM audio stream")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(audioRecord.stopStream()))
            }
            else -> notFound()
        }
    }

    private fun routeVideo(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/video/record/status" || uri == "/video/record/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.camera", capability = "recording", detail = "Video recording status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(videoRecord.status()))
            }
            (uri == "/video/record/start" || uri == "/video/record/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.camera", capability = "recording", detail = "Start video recording")
                if (!ok.first) return ok.second!!
                val path = payload.optString("path", "").trim().ifBlank { null }
                val lens = payload.optString("lens", "").trim().ifBlank { null }
                val maxDur = if (payload.has("max_duration_s")) payload.optInt("max_duration_s") else null
                val resolution = payload.optString("resolution", "").trim().ifBlank { null }
                return jsonResponse(JSONObject(videoRecord.startRecording(path, lens, maxDur, resolution)))
            }
            (uri == "/video/record/stop" || uri == "/video/record/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.camera", capability = "recording", detail = "Stop video recording")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(videoRecord.stopRecording()))
            }
            (uri == "/video/record/config" || uri == "/video/record/config/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.camera", capability = "recording", detail = "Get video recording config")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(videoRecord.getConfig()))
            }
            (uri == "/video/record/config" || uri == "/video/record/config/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.camera", capability = "recording", detail = "Set video recording config")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(videoRecord.setConfig(payload)))
            }

            // ── Video Frame Streaming ────────────────────────────────────
            (uri == "/video/stream/start" || uri == "/video/stream/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.camera", capability = "recording", detail = "Start live video frame stream")
                if (!ok.first) return ok.second!!
                val lens = payload.optString("lens", "").trim().ifBlank { null }
                val width = if (payload.has("width")) payload.optInt("width") else null
                val height = if (payload.has("height")) payload.optInt("height") else null
                val fps = if (payload.has("fps")) payload.optInt("fps") else null
                val format = payload.optString("format", "").trim().ifBlank { null }
                val jq = if (payload.has("jpeg_quality")) payload.optInt("jpeg_quality") else null
                return jsonResponse(JSONObject(videoRecord.startStream(lens, width, height, fps, format, jq)))
            }
            (uri == "/video/stream/stop" || uri == "/video/stream/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.camera", capability = "recording", detail = "Stop live video frame stream")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(videoRecord.stopStream()))
            }
            else -> notFound()
        }
    }

    private fun routeTermux(session: IHTTPSession, uri: String, postBody: String?): Response {
        // Accept both /termux/* (canonical) and /python/* (legacy alias)
        val path = if (uri.startsWith("/python")) uri.replaceFirst("/python", "/termux") else uri
        return when {
            path == "/termux/write" && session.method == Method.POST -> {
                handleFileWrite(postBody, forcedPath = null, expectedFs = "termux")
            }
            path.startsWith("/termux/write/") && session.method == Method.POST -> {
                val p = decodePathSuffix(path, "/termux/write/") ?: return jsonError(Response.Status.BAD_REQUEST, "path_required")
                val normalized = normalizeTermuxRoutePath(p) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                handleFileWrite(postBody, forcedPath = normalized, expectedFs = "termux")
            }
            path == "/termux/file" && session.method == Method.GET -> {
                val raw = firstParam(session, "path").trim()
                if (raw.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
                val normalized = normalizeTermuxRoutePath(raw) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                serveFileByPath(normalized)
            }
            path.startsWith("/termux/file/info/") && session.method == Method.GET -> {
                val p = decodePathSuffix(path, "/termux/file/info/") ?: return jsonError(Response.Status.BAD_REQUEST, "path_required")
                val normalized = normalizeTermuxRoutePath(p) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                handleFileInfoByPath(normalized)
            }
            path == "/termux/file/info" && session.method == Method.GET -> {
                val raw = firstParam(session, "path").trim()
                if (raw.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
                val normalized = normalizeTermuxRoutePath(raw) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                handleFileInfoByPath(normalized)
            }
            path.startsWith("/termux/file/") && session.method == Method.GET -> {
                val p = decodePathSuffix(path, "/termux/file/") ?: return jsonError(Response.Status.BAD_REQUEST, "path_required")
                val normalized = normalizeTermuxRoutePath(p) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                serveFileByPath(normalized)
            }
            path == "/termux/list" && session.method == Method.GET -> {
                val raw = firstParam(session, "path").trim().ifBlank { "~" }
                val normalized = normalizeTermuxRoutePath(raw) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                handleListByPath(normalized)
            }
            path.startsWith("/termux/list/") && session.method == Method.GET -> {
                val p = decodePathSuffix(path, "/termux/list/") ?: return jsonError(Response.Status.BAD_REQUEST, "path_required")
                val normalized = normalizeTermuxRoutePath(p) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                handleListByPath(normalized)
            }
            path == "/termux/worker/start" || path == "/termux/start" -> {
                runtimeManager.startWorker()
                jsonResponse(JSONObject().put("status", "starting"))
            }
            path == "/termux/worker/stop" || path == "/termux/stop" -> {
                runtimeManager.requestShutdown()
                jsonResponse(JSONObject().put("status", "stopping"))
            }
            path == "/termux/worker/restart" || path == "/termux/restart" -> {
                runtimeManager.restartSoft()
                jsonResponse(JSONObject().put("status", "starting"))
            }
            path == "/termux/bootstrap/notify" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val phase = payload.optString("phase", "")
                if (phase in listOf("running", "done")) {
                    bootstrapPhase = phase
                }
                val msg = payload.optString("message", "")
                if (msg.isNotBlank()) bootstrapMessage = msg
                // When bootstrap finishes, persist and auto-start the worker
                if (phase == "done") {
                    context.getSharedPreferences("termux_bootstrap", Context.MODE_PRIVATE)
                        .edit().putBoolean("done", true).apply()
                    runtimeManager.startWorker()
                }
                jsonResponse(JSONObject().put("status", "ok").put("phase", bootstrapPhase))
            }
            path == "/termux/status" -> {
                val installed = termuxManager.isTermuxInstalled()
                // Compute effective bootstrap phase:
                // - If Termux is not installed, any persisted "done" is stale → reset.
                // - If actively bootstrapping (in-memory "running"), use that.
                // - Otherwise, fall back to the persisted flag.
                val effectivePhase = if (!installed) {
                    if (bootstrapPrefs.getBoolean("done", false)) {
                        bootstrapPrefs.edit().remove("done").apply()
                    }
                    bootstrapPhase = "none"
                    "none"
                } else if (bootstrapPhase == "running") {
                    "running"
                } else if (bootstrapPrefs.getBoolean("done", false)) {
                    "done"
                } else {
                    bootstrapPhase
                }
                jsonResponse(
                    JSONObject()
                        .put("installed", installed)
                        .put("ready", termuxManager.isTermuxReady())
                        .put("run_command_permitted", termuxManager.hasRunCommandPermission())
                        .put("sshd_running", termuxManager.isSshdRunning())
                        .put("sshd_port", TermuxManager.TERMUX_SSHD_PORT)
                        .put("worker_status", runtimeManager.getStatus())
                        .put("releases_url", TermuxManager.TERMUX_RELEASES_URL)
                        .put("bootstrap_command", "curl -so ~/b.sh http://127.0.0.1:$PORT/termux/bootstrap.sh && bash ~/b.sh")
                        .put("bootstrap_phase", effectivePhase)
                        .put("bootstrap_message", bootstrapMessage)
                        .put("can_request_installs", termuxManager.canInstallPackages())
                        .put("show_termux_setup", showTermuxSetupFlag.also { if (it) showTermuxSetupFlag = false })
                )
            }
            path == "/termux/setup/show" && session.method == Method.POST -> {
                showTermuxSetupFlag = true
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/termux/bootstrap.sh" && session.method == Method.GET -> {
                val script = termuxManager.getBootstrapScript()
                newFixedLengthResponse(Response.Status.OK, "text/plain", script)
            }
            uri == "/termux/server.tar.gz" && session.method == Method.GET -> {
                try {
                    val serverDir = File(context.filesDir, "server")
                    if (!serverDir.exists()) return jsonError(Response.Status.NOT_FOUND, "server_dir_missing")
                    val tmpFile = File(context.cacheDir, "server.tar.gz")
                    val proc = ProcessBuilder("tar", "czf", tmpFile.absolutePath, "-C", serverDir.absolutePath, ".")
                        .redirectErrorStream(true).start()
                    proc.waitFor(30, TimeUnit.SECONDS)
                    if (!tmpFile.exists()) return jsonError(Response.Status.INTERNAL_ERROR, "tar_failed")
                    val inputStream = java.io.FileInputStream(tmpFile)
                    newFixedLengthResponse(Response.Status.OK, "application/gzip", inputStream, tmpFile.length())
                } catch (ex: Exception) {
                    jsonError(Response.Status.INTERNAL_ERROR, ex.message ?: "tar_failed")
                }
            }
            uri == "/termux/launch" && session.method == Method.POST -> {
                termuxManager.launchTermux()
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/termux/run" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val command = payload.optString("command", "").trim()
                if (command.isBlank()) return badRequest("command_required")
                val background = payload.optBoolean("background", true)
                termuxManager.runCommand(command, background)
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/termux/update" && session.method == Method.POST -> {
                termuxManager.stopWorker()
                Thread.sleep(1000)
                termuxManager.updateServerCode()
                Thread.sleep(2000)
                termuxManager.startWorker()
                runtimeManager.startWorker()
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/termux/sshd/start" && session.method == Method.POST -> {
                termuxManager.startSshd()
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/termux/sshd/stop" && session.method == Method.POST -> {
                termuxManager.stopSshd()
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/termux/install/check" && session.method == Method.GET -> {
                try {
                    jsonResponse(termuxManager.checkTermuxRelease())
                } catch (ex: Throwable) {
                    Log.w(TAG, "Termux install check failed", ex)
                    jsonError(
                        Response.Status.INTERNAL_ERROR,
                        "termux_install_check_failed",
                        JSONObject().put("detail", "${ex.javaClass.simpleName}:${ex.message ?: ""}")
                    )
                }
            }
            uri == "/termux/install" && session.method == Method.POST -> {
                try {
                    jsonResponse(termuxManager.downloadAndInstallTermux())
                } catch (ex: Throwable) {
                    Log.w(TAG, "Termux install failed", ex)
                    jsonError(
                        Response.Status.INTERNAL_ERROR,
                        "termux_install_failed",
                        JSONObject().put("detail", "${ex.javaClass.simpleName}:${ex.message ?: ""}")
                    )
                }
            }
            else -> notFound()
        }
    }


    private fun routeStt(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/stt/status" || uri == "/stt/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.mic", capability = "stt", detail = "Speech recognizer status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(stt.status()))
            }
            (uri == "/stt/record" || uri == "/stt/record/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.mic", capability = "stt", detail = "Start one-shot speech recognition")
                if (!ok.first) return ok.second!!
                val locale = payload.optString("locale", "").trim().ifBlank { null }
                val partial = payload.optBoolean("partial", true)
                val maxResults = payload.optInt("max_results", 5)
                val resp = JSONObject(stt.start(locale, partial, maxResults)).put("ws_path", "/ws/stt/events")
                return jsonResponse(resp)
            }
            else -> notFound()
        }
    }

    private fun routeLocation(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/location/status" || uri == "/location/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.gps", capability = "location", detail = "Location status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(location.status()))
            }
            (uri == "/location/get" || uri == "/location/get/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.gps", capability = "location", detail = "Get current location")
                if (!ok.first) return ok.second!!
                val high = payload.optBoolean("high_accuracy", true)
                val timeoutMs = payload.optLong("timeout_ms", 12_000L).coerceIn(250L, 120_000L)
                return jsonResponse(JSONObject(location.getCurrent(highAccuracy = high, timeoutMs = timeoutMs)))
            }
            else -> notFound()
        }
    }

    private fun routeNetwork(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/network/status" || uri == "/network/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.network", capability = "network", detail = "Network connectivity status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(network.status()))
            }
            else -> notFound()
        }
    }

    private fun routeWifi(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/wifi/status" || uri == "/wifi/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.network", capability = "network", detail = "Wi-Fi status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(network.wifiStatus()))
            }
            else -> notFound()
        }
    }

    private fun routeMobile(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/mobile/status" || uri == "/mobile/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.network", capability = "network", detail = "Mobile signal status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(network.mobileStatus()))
            }
            else -> notFound()
        }
    }

    private fun routeSensors(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/sensors/list" || uri == "/sensors/list/" || uri == "/sensor/list" || uri == "/sensor/list/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.sensors", capability = "sensors", detail = "List available sensors")
                if (!ok.first) return ok.second!!
                val out = JSONObject(sensors.listSensors())
                if (out.has("items")) {
                    out.put("items", org.json.JSONArray(out.getString("items")))
                }
                return jsonResponse(out)
            }
            (uri == "/sensors/ws/contract" || uri == "/sensors/ws/contract/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.sensors", capability = "sensors", detail = "Sensors websocket contract")
                if (!ok.first) return ok.second!!
                return jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("ws_path", "/ws/sensors")
                        .put("query", JSONObject()
                            .put("sensors", "comma-separated sensor keys, e.g. a,g,m")
                            .put("rate_hz", "1..1000 (default 200)")
                            .put("latency", "realtime|normal|ui (default realtime)")
                            .put("timestamp", "mono|unix (default mono)")
                            .put("backpressure", "drop_old|drop_new (default drop_old)")
                            .put("max_queue", "64..50000 (default 4096)"))
                        .put("sample_event", JSONObject()
                            .put("type", "sample")
                            .put("stream_id", "s1234abcd")
                            .put("sensor", "a")
                            .put("t", 12345.678)
                            .put("seq", 1001)
                            .put("v", org.json.JSONArray().put(0.01).put(9.8).put(0.12)))
                )
            }
            (uri == "/sensors/stream/status" || uri == "/sensors/stream/status/") && session.method == Method.GET -> {
                return jsonError(Response.Status.GONE, "deprecated_use_ws_sensors")
            }
            (uri == "/sensors/stream/start" || uri == "/sensors/stream/start/" || uri == "/sensor/stream/start" || uri == "/sensor/stream/start/") && session.method == Method.POST -> {
                return jsonError(Response.Status.GONE, "deprecated_use_ws_sensors")
            }
            (uri == "/sensors/stream/stop" || uri == "/sensors/stream/stop/" || uri == "/sensor/stream/stop" || uri == "/sensor/stream/stop/") && session.method == Method.POST -> {
                return jsonError(Response.Status.GONE, "deprecated_use_ws_sensors")
            }
            (uri == "/sensors/stream/latest" || uri == "/sensors/stream/latest/" || uri == "/sensor/stream/latest" || uri == "/sensor/stream/latest/") && session.method == Method.POST -> {
                return jsonError(Response.Status.GONE, "deprecated_use_ws_sensors")
            }
            (uri == "/sensors/stream/batch" || uri == "/sensors/stream/batch/" || uri == "/sensor/stream/batch" || uri == "/sensor/stream/batch/") && session.method == Method.POST -> {
                return jsonError(Response.Status.GONE, "deprecated_use_ws_sensors")
            }
            (uri == "/sensor/stream/status" || uri == "/sensor/stream/status/") && session.method == Method.POST -> {
                return jsonError(Response.Status.GONE, "deprecated_use_ws_sensors")
            }
            else -> notFound()
        }
    }

    private fun routeUser(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/user/write" && session.method == Method.POST -> {
                handleFileWrite(postBody, forcedPath = null, expectedFs = "user")
            }
            uri.startsWith("/user/write/") && session.method == Method.POST -> {
                val p = decodePathSuffix(uri, "/user/write/") ?: return jsonError(Response.Status.BAD_REQUEST, "path_required")
                handleFileWrite(postBody, forcedPath = "user://$p", expectedFs = "user")
            }
            uri == "/user/list" && session.method == Method.GET -> {
                handleUserList(session)
            }
            uri.startsWith("/user/list/") && session.method == Method.GET -> {
                val p = decodePathSuffix(uri, "/user/list/") ?: return jsonError(Response.Status.BAD_REQUEST, "path_required")
                handleListByPath("user://$p")
            }
            uri == "/user/file" && session.method == Method.GET -> {
                serveUserFile(session)
            }
            uri.startsWith("/user/file/info/") && session.method == Method.GET -> {
                val p = decodePathSuffix(uri, "/user/file/info/") ?: return jsonError(Response.Status.BAD_REQUEST, "path_required")
                handleFileInfoByPath("user://$p")
            }
            uri.startsWith("/user/file/") && session.method == Method.GET -> {
                val p = decodePathSuffix(uri, "/user/file/") ?: return jsonError(Response.Status.BAD_REQUEST, "path_required")
                serveFileByPath("user://$p")
            }
            uri.startsWith("/user/www/") && session.method == Method.GET -> {
                serveUserWww(session)
            }
            uri == "/user/upload" && session.method == Method.POST -> {
                handleUserUpload(session)
            }
            uri == "/user/file/info" && session.method == Method.GET -> {
                handleUserFileInfo(session)
            }
            else -> notFound()
        }
    }

    private fun routeWebview(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            (uri == "/webview/open" || uri == "/webview/open/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val url = payload.optString("url", "").trim()
                if (url.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "url_required")
                val timeoutS = payload.optLong("timeout_s", 30).coerceIn(1, 120)
                val result = jp.espresso3389.methings.device.WebViewBrowserManager.open(context, url, timeoutS)
                jsonResponse(result)
            }
            (uri == "/webview/close" || uri == "/webview/close/") && session.method == Method.POST -> {
                val result = jp.espresso3389.methings.device.WebViewBrowserManager.close(context)
                jsonResponse(result)
            }
            (uri == "/webview/status" || uri == "/webview/status/") && session.method == Method.GET -> {
                val result = jp.espresso3389.methings.device.WebViewBrowserManager.status()
                jsonResponse(result)
            }
            (uri == "/webview/screenshot" || uri == "/webview/screenshot/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val outPath = payload.optString("path", "browser/screenshot_${System.currentTimeMillis()}.jpg")
                val outRef = parseFsPathRef(outPath.trim()) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
                val tempOutRef = if (outRef.fs == "user") {
                    outRef
                } else {
                    parseFsPathRef("user://browser/.tmp_screenshot_${System.currentTimeMillis()}.jpg")
                        ?: return jsonError(Response.Status.INTERNAL_ERROR, "temp_path_resolve_failed")
                }
                val file = tempOutRef.userFile ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
                val quality = payload.optInt("quality", 80).coerceIn(10, 100)
                val timeoutS = payload.optLong("timeout_s", 10).coerceIn(1, 60)
                val result = jp.espresso3389.methings.device.WebViewBrowserManager.screenshot(file, quality, timeoutS)
                if (result.optString("status") == "ok") {
                    if (outRef.fs == "termux") {
                        val bytes = try {
                            file.readBytes()
                        } catch (ex: Exception) {
                            return jsonError(Response.Status.INTERNAL_ERROR, "screenshot_temp_read_failed", JSONObject().put("detail", ex.message ?: ""))
                        }
                        try {
                            writeFsPathBytes(outRef, bytes)
                        } catch (ex: IllegalArgumentException) {
                            return when (ex.message ?: "") {
                                "path_outside_termux_home" -> jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                                "termux_unavailable" -> jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
                                else -> jsonError(Response.Status.INTERNAL_ERROR, "termux_fs_write_failed")
                            }
                        } finally {
                            runCatching { file.delete() }
                        }
                    }
                    result.put("rel_path", outRef.displayPath)
                }
                jsonResponse(result)
            }
            (uri == "/webview/js" || uri == "/webview/js/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val script = payload.optString("script", "").trim()
                if (script.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "script_required")
                val timeoutS = payload.optLong("timeout_s", 10).coerceIn(1, 60)
                val result = jp.espresso3389.methings.device.WebViewBrowserManager.evaluateJs(script, timeoutS)
                jsonResponse(result)
            }
            (uri == "/webview/tap" || uri == "/webview/tap/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                if (!payload.has("x") || !payload.has("y")) return jsonError(Response.Status.BAD_REQUEST, "x_and_y_required")
                val x = payload.optDouble("x", 0.0).toFloat()
                val y = payload.optDouble("y", 0.0).toFloat()
                val result = jp.espresso3389.methings.device.WebViewBrowserManager.tap(x, y)
                jsonResponse(result)
            }
            (uri == "/webview/scroll" || uri == "/webview/scroll/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val dx = payload.optInt("dx", 0)
                val dy = payload.optInt("dy", 0)
                val result = jp.espresso3389.methings.device.WebViewBrowserManager.scroll(dx, dy)
                jsonResponse(result)
            }
            (uri == "/webview/back" || uri == "/webview/back/") && session.method == Method.POST -> {
                val result = jp.espresso3389.methings.device.WebViewBrowserManager.goBack()
                jsonResponse(result)
            }
            (uri == "/webview/forward" || uri == "/webview/forward/") && session.method == Method.POST -> {
                val result = jp.espresso3389.methings.device.WebViewBrowserManager.goForward()
                jsonResponse(result)
            }
            (uri == "/webview/split" || uri == "/webview/split/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val visible = payload.optBoolean("visible", true)
                val fullscreen = payload.optBoolean("fullscreen", false)
                val position = payload.optString("position", "").ifBlank { null }
                val mgr = jp.espresso3389.methings.device.WebViewBrowserManager
                if (visible) {
                    val intent = Intent(mgr.ACTION_BROWSER_SHOW).apply {
                        setPackage(context.packageName)
                        putExtra(mgr.EXTRA_FULLSCREEN, fullscreen)
                        if (position != null) putExtra(mgr.EXTRA_POSITION, position)
                    }
                    context.sendBroadcast(intent)
                } else {
                    val intent = Intent(mgr.ACTION_BROWSER_CLOSE).apply {
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(intent)
                }
                val result = JSONObject().put("status", "ok").put("visible", visible).put("fullscreen", fullscreen)
                if (position != null) result.put("position", position)
                jsonResponse(result)
            }
            (uri == "/webview/console" || uri == "/webview/console/") && session.method == Method.GET -> {
                val params = session.parms ?: emptyMap()
                val since = params["since"]?.toLongOrNull() ?: 0L
                val source = params["source"]?.ifBlank { null }
                val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val entries = jp.espresso3389.methings.device.WebViewConsoleBuffer.getEntries(since, source, limit)
                val result = JSONObject().put("status", "ok").put("entries", entries)
                jsonResponse(result)
            }
            (uri == "/webview/console/clear" || uri == "/webview/console/clear/") && session.method == Method.POST -> {
                jp.espresso3389.methings.device.WebViewConsoleBuffer.clear()
                jsonResponse(JSONObject().put("status", "ok"))
            }
            else -> notFound()
        }
    }

    private fun routeIntent(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/intent/send" && session.method == Method.POST -> {
                val payload = runCatching { JSONObject(postBody ?: "{}") }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                val action = payload.optString("action", "").trim()
                if (action.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "action_required")
                val ok = ensureDevicePermission(session, payload, tool = "device.intent", capability = "intent", detail = action)
                if (!ok.first) return ok.second!!
                handleIntentSend(payload)
            }
            uri == "/intent/share_app" && session.method == Method.POST -> {
                val payload = runCatching { JSONObject(postBody ?: "{}") }.getOrNull() ?: JSONObject()
                val ok = ensureDevicePermission(session, payload, tool = "device.intent", capability = "intent", detail = "Share this app (APK)")
                if (!ok.first) return ok.second!!
                handleIntentShareApp()
            }
            else -> notFound()
        }
    }

    private fun routeSys(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/sys/list" && session.method == Method.GET -> {
                handleSysList(session)
            }
            uri == "/sys/file" && session.method == Method.GET -> {
                serveSysFile(session)
            }
            else -> notFound()
        }
    }


    private fun firstParam(session: IHTTPSession, name: String): String {
        return session.parameters[name]?.firstOrNull()?.trim() ?: ""
    }

    private fun normalizeSettingsSectionId(raw: String): String {
        return raw.trim().lowercase(Locale.US).replace('-', '_').replace(Regex("\\s+"), "_")
    }

    private fun extractSettingsKeyToSectionMap(): Map<String, String> {
        val htmlFile = File(uiRoot, "index.html")
        if (!htmlFile.exists()) return emptyMap()
        val html = runCatching { htmlFile.readText() }.getOrElse { return emptyMap() }
        val sectionRegex = Regex(
            """<section\s+class="card"\s+id="settings-section-([a-zA-Z0-9_]+)"[^>]*>""",
            RegexOption.IGNORE_CASE
        )
        val keyRegex = Regex("""data-setting-key="([^"]+)"""", RegexOption.IGNORE_CASE)
        val matches = sectionRegex.findAll(html).toList()
        if (matches.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (i in matches.indices) {
            val cur = matches[i]
            val sectionId = normalizeSettingsSectionId(cur.groupValues.getOrNull(1) ?: "")
            if (sectionId.isBlank()) continue
            val start = cur.range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else html.length
            if (start < 0 || end <= start || end > html.length) continue
            val chunk = html.substring(start, end)
            keyRegex.findAll(chunk).forEach { km ->
                val raw = km.groupValues.getOrNull(1) ?: ""
                raw.split(",").forEach tokenLoop@{ token ->
                    val key = normalizeSettingsSectionId(token)
                    if (key.isBlank()) return@tokenLoop
                    if (!out.containsKey(key)) out[key] = sectionId
                }
            }
        }
        return out
    }

    private fun handleUiSettingsSections(): Response {
        val arr = org.json.JSONArray()
        val keyMap = extractSettingsKeyToSectionMap()
        SETTINGS_SECTIONS.forEach { s ->
            val keys = org.json.JSONArray()
            keyMap.forEach { (k, sid) ->
                if (sid == s.first) keys.put(k)
            }
            arr.put(
                JSONObject()
                    .put("id", s.first)
                    .put("label", s.second)
                    .put("setting_keys", keys)
            )
        }
        val keyMapJson = JSONObject()
        keyMap.forEach { (k, v) -> keyMapJson.put(k, v) }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("sections", arr)
                .put("setting_key_map", keyMapJson)
        )
    }

    private fun handleUserList(session: IHTTPSession): Response {
        val raw = firstParam(session, "path").trim()
        val path = if (raw.isBlank()) "user://." else raw
        return handleListByPath(path)
    }

    private fun serveUserFile(session: IHTTPSession): Response {
        val raw = firstParam(session, "path").trim()
        if (raw.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
        return serveFileByPath(raw)
    }

    private fun handleUserFileInfo(session: IHTTPSession): Response {
        val rawPath = firstParam(session, "path").replace(Regex("#.*$"), "").trim()
        if (rawPath.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
        return handleFileInfoByPath(rawPath)
    }

    private fun handleListByPath(path: String): Response {
        return listFsPath(path)
    }

    private fun serveFileByPath(raw: String): Response {
        val ref = parseFsPathRef(raw) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
        val displayPath = ref.displayPath
        val name = when {
            ref.fs == "user" -> ref.userFile?.name ?: raw.substringAfterLast('/')
            ref.fs == "termux" -> ref.termuxPath?.substringAfterLast('/') ?: raw.substringAfterLast('/')
            else -> raw.substringAfterLast('/')
        }

        val relLower = displayPath.lowercase()
        val nameLower = name.lowercase()
        val isAudioRecordingWebm =
            (nameLower.endsWith(".webm") && (nameLower.startsWith("audio_recording") || relLower.contains("uploads/recordings/")))
        val mime = if (isAudioRecordingWebm) {
            "audio/webm"
        } else {
            URLConnection.guessContentTypeFromName(name) ?: mimeTypeFor(name)
        }
        if (ref.fs == "user") {
            val file = ref.userFile ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
            if (!file.exists() || !file.isFile) return jsonError(Response.Status.NOT_FOUND, "not_found")
            val stream: InputStream = FileInputStream(file)
            val response = newChunkedResponse(Response.Status.OK, mime, stream)
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("X-Content-Type-Options", "nosniff")
            return response
        }
        val bytes = try {
            readFsPathBytes(ref).first
        } catch (ex: IllegalArgumentException) {
            return when (ex.message ?: "") {
                "path_outside_termux_home" -> jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                "termux_unavailable" -> jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
                "not_found" -> jsonError(Response.Status.NOT_FOUND, "not_found")
                "file_too_large" -> jsonError(Response.Status.BAD_REQUEST, "file_too_large")
                else -> jsonError(Response.Status.INTERNAL_ERROR, "file_read_failed")
            }
        }
        val response = newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("X-Content-Type-Options", "nosniff")
        return response
    }

    private fun handleFileInfoByPath(rawPath: String): Response {
        val ref = parseFsPathRef(rawPath) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
        val displayPath = ref.displayPath
        val fileName = when (ref.fs) {
            "user" -> ref.userFile?.name ?: rawPath.substringAfterLast('/')
            "termux" -> ref.termuxPath?.substringAfterLast('/') ?: rawPath.substringAfterLast('/')
            else -> rawPath.substringAfterLast('/')
        }

        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = URLConnection.guessContentTypeFromName(fileName) ?: mimeTypeFor(fileName)
        val kind = when {
            ext in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg") -> "image"
            ext in listOf("mp4", "mkv", "mov", "m4v", "3gp", "webm") -> "video"
            ext in listOf("mp3", "wav", "ogg", "m4a", "aac", "flac") -> "audio"
            ext in listOf("txt", "md", "json", "log", "py", "js", "ts", "html", "css", "sh",
                "yaml", "yml", "toml", "xml", "csv", "cfg", "ini", "conf",
                "kt", "java", "c", "cpp", "h", "rs", "go", "rb", "pl") -> "text"
            else -> "bin"
        }

        val json = JSONObject()
            .put("name", fileName)
            .put("size", 0L)
            .put("mtime_ms", 0L)
            .put("mime", mime)
            .put("kind", kind)
            .put("ext", ext)
            .put("fs", ref.fs)
            .put("path", displayPath)

        if (ref.fs == "user") {
            val file = ref.userFile ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
            if (!file.exists() || !file.isFile) return jsonError(Response.Status.NOT_FOUND, "not_found")
            json.put("size", file.length())
            json.put("mtime_ms", file.lastModified())

            if (kind == "image" && ext != "svg") {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        json.put("width", bounds.outWidth)
                        json.put("height", bounds.outHeight)
                    }
                } catch (_: Exception) {}
            }

            if (ext == "md") {
                try {
                    val head = file.inputStream().use { inp ->
                        val buf = ByteArray(1024)
                        val n = inp.read(buf)
                        if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
                    }
                    val fmMatch = Regex("^---\\s*\\n([\\s\\S]*?)\\n---").find(head)
                    val isMarp = fmMatch != null && Regex("^marp\\s*:\\s*true\\s*$", RegexOption.MULTILINE).containsMatchIn(fmMatch.groupValues[1])
                    json.put("is_marp", isMarp)
                    if (isMarp) {
                        val fullText = file.readText(Charsets.UTF_8)
                        val stripped = fullText.replace(Regex("^---\\s*\\n[\\s\\S]*?\\n---\\n?"), "")
                        val slideCount = stripped.split("\n---\n").size
                        json.put("slide_count", slideCount)
                    }
                } catch (_: Exception) {
                    json.put("is_marp", false)
                }
            }
        } else {
            val stat = statFsPath(ref)
            if (!stat.first) return jsonError(Response.Status.NOT_FOUND, "not_found")
            json.put("size", stat.second)
        }

        return jsonResponse(json)
    }

    private fun handleFileWrite(postBody: String?, forcedPath: String?, expectedFs: String): Response {
        val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
        val inputPath = forcedPath ?: payload.optString("path", "").trim()
        if (inputPath.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
        val normalizedPath = when {
            forcedPath != null -> forcedPath
            expectedFs == "termux" -> normalizeTermuxRoutePath(inputPath) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
            else -> {
                if (inputPath.startsWith("user://", ignoreCase = true)) inputPath
                else if (inputPath.startsWith("/") || inputPath.startsWith("termux://", ignoreCase = true)) {
                    return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
                } else "user://${inputPath.trimStart('/')}"
            }
        }
        val ref = parseFsPathRef(normalizedPath) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
        if (ref.fs != expectedFs) {
            return jsonError(
                Response.Status.BAD_REQUEST,
                if (expectedFs == "user") "path_outside_user_dir" else "path_outside_termux_home"
            )
        }

        val hasDataB64 = payload.optString("data_b64", "").trim().isNotBlank()
        val hasContent = payload.has("content")
        val hasBody = payload.has("body")
        if (!hasDataB64 && !hasContent && !hasBody) {
            return jsonError(Response.Status.BAD_REQUEST, "content_required")
        }

        val bytes = try {
            if (hasDataB64) {
                Base64.decode(payload.optString("data_b64", "").trim(), Base64.DEFAULT)
            } else {
                val encoding = payload.optString("encoding", "utf-8").trim().lowercase(Locale.US)
                val value = if (hasContent) payload.opt("content") else payload.opt("body")
                val text = when (value) {
                    null, JSONObject.NULL -> ""
                    is JSONObject, is JSONArray -> value.toString()
                    else -> value.toString()
                }
                if (encoding == "base64") Base64.decode(text, Base64.DEFAULT) else text.toByteArray(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid_content")
        }

        return try {
            val saved = writeFsPathBytes(ref, bytes)
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("path", saved)
                    .put("bytes_written", bytes.size)
            )
        } catch (ex: IllegalArgumentException) {
            when (ex.message ?: "") {
                "path_outside_termux_home" -> jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                "path_outside_user_dir" -> jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
                "termux_unavailable" -> jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
                else -> jsonError(Response.Status.INTERNAL_ERROR, "file_write_failed")
            }
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "file_write_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun serveUserWww(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        val raw = uri.removePrefix("/user/www/").trimStart('/')
        if (raw.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
        val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        val safePath = decoded.replace("\\", "/")
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
        if (safePath.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
        val file0 = userPath(safePath) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
        val file = if (file0.isDirectory) File(file0, "index.html") else file0
        if (!file.exists() || !file.isFile) return jsonError(Response.Status.NOT_FOUND, "not_found")
        val mime = URLConnection.guessContentTypeFromName(file.name) ?: mimeTypeFor(file.name)
        val stream: InputStream = FileInputStream(file)
        val response = newChunkedResponse(Response.Status.OK, mime, stream)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("X-Content-Type-Options", "nosniff")
        return response
    }

    private fun handleUserUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        val parms = session.parameters
        try {
            session.parseBody(files)
        } catch (ex: Exception) {
            Log.w(TAG, "user upload parse failed", ex)
            return jsonError(Response.Status.BAD_REQUEST, "upload_parse_failed", JSONObject().put("detail", ex.message ?: ""))
        }

        val tmp = files["file"] ?: files.entries.firstOrNull()?.value
        if (tmp.isNullOrBlank()) return jsonError(Response.Status.BAD_REQUEST, "file_required")

        // NanoHTTPD sets the uploaded filename as a parameter with the same field name.
        val originalName = (parms["file"]?.firstOrNull() ?: "").trim()
        val name = (parms["name"]?.firstOrNull() ?: originalName).trim().ifBlank {
            "upload_" + System.currentTimeMillis().toString()
        }
        val dirRaw = (parms["dir"]?.firstOrNull() ?: parms["path"]?.firstOrNull() ?: "").trim()
        val finalPath = if (dirRaw.isBlank()) {
            "user://uploads/$name"
        } else if (dirRaw.startsWith("user://", ignoreCase = true) ||
            dirRaw.startsWith("termux://", ignoreCase = true) ||
            dirRaw.startsWith(TERMUX_HOME_PREFIX)
        ) {
            dirRaw.trimEnd('/') + "/" + name
        } else {
            "user://" + dirRaw.trimStart('/').trimEnd('/') + "/" + name
        }
        val outRef = parseFsPathRef(finalPath) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
        if (outRef.fs == "user") {
            outRef.userFile?.parentFile?.mkdirs()
        }

        return try {
            val bytes = File(tmp).readBytes()
            writeFsPathBytes(outRef, bytes)
            // Upload is an explicit user action (file picker + send). Treat it as consent to let the
            // agent/UI read uploaded user files without re-prompting for device.files.
            try {
                val headerIdentity =
                    ((session.headers["x-methings-identity"] ?: session.headers["x-methings-identity"]) ?: "").trim()
                val identity = (parms["identity"]?.firstOrNull() ?: "").trim()
                    .ifBlank { headerIdentity }
                    .ifBlank { installIdentity.get() }
                if (identity.isNotBlank()) {
                    val scope = if (permissionPrefs.rememberApprovals()) "persistent" else "session"
                    val existing = permissionStore.findReusableApproved(
                        tool = "device.files",
                        scope = scope,
                        identity = identity,
                        capability = "files"
                    )
                    if (existing == null) {
                        val req = permissionStore.create(
                            tool = "device.files",
                            detail = "Read uploaded user files",
                            scope = scope,
                            identity = identity,
                            capability = "files"
                        )
                        val approved = permissionStore.updateStatus(req.id, "approved")
                        if (approved != null) {
                            maybeGrantDeviceCapability(approved)
                        }
                    }
                }
            } catch (_: Exception) {
                // Best-effort only; upload must succeed even if permission state can't be updated.
            }
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("path", outRef.displayPath)
                    .put("size", bytes.size)
            )
        } catch (ex: IllegalArgumentException) {
            when (ex.message ?: "") {
                "path_outside_termux_home" -> jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                "termux_unavailable" -> jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
                else -> jsonError(Response.Status.INTERNAL_ERROR, "upload_failed", JSONObject().put("detail", ex.message ?: ""))
            }
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "upload_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleUsbList(session: IHTTPSession): Response {
        // Enumeration is sensitive (device presence and IDs); gate it the same way as other USB actions.
        val perm = ensureDevicePermission(
            session,
            JSONObject(),
            tool = "device.usb",
            capability = "usb",
            detail = "USB list"
        )
        if (!perm.first) return perm.second!!

        val list = usbManager.deviceList
        val arr = org.json.JSONArray()
        list.values.forEach { dev ->
            arr.put(usbDeviceToJson(dev))
        }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("count", arr.length())
                .put("devices", arr)
        )
    }

    private fun handleUsbStatus(session: IHTTPSession): Response {
        val perm = ensureDevicePermission(
            session,
            JSONObject(),
            tool = "device.usb",
            capability = "usb",
            detail = "USB status"
        )
        if (!perm.first) return perm.second!!

        val list = usbManager.deviceList.values.toList()
        val devices = org.json.JSONArray()
        for (dev in list) {
            val o = usbDeviceToJson(dev)
            val has = runCatching { usbManager.hasPermission(dev) }.getOrDefault(false)
            o.put("has_permission", has)
            val snap = UsbPermissionWaiter.snapshot(dev.deviceName)
            if (snap != null) {
                o.put(
                    "permission_request",
                    JSONObject()
                        .put("requested_at_ms", snap.requestedAtMs)
                        .put("age_ms", (System.currentTimeMillis() - snap.requestedAtMs).coerceAtLeast(0L))
                        .put("responded", snap.responded)
                        .put("granted", if (snap.granted == null) JSONObject.NULL else snap.granted)
                        .put("completed_at_ms", if (snap.completedAtMs == null) JSONObject.NULL else snap.completedAtMs)
                        .put("timed_out", snap.timedOut)
                )
            }
            devices.put(o)
        }

        val pending = org.json.JSONArray()
        for (snap in UsbPermissionWaiter.pendingSnapshots()) {
            pending.put(
                JSONObject()
                    .put("name", snap.deviceName)
                    .put("requested_at_ms", snap.requestedAtMs)
                    .put("age_ms", (System.currentTimeMillis() - snap.requestedAtMs).coerceAtLeast(0L))
                    .put("timed_out", snap.timedOut)
            )
        }

        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("now_ms", System.currentTimeMillis())
                .put("count", devices.length())
                .put("devices", devices)
                .put("pending_permission_requests", pending)
        )
    }

    private fun handleMcuModels(): Response {
        val models = org.json.JSONArray().put(
            JSONObject()
                .put("id", "esp32")
                .put("family", "esp32")
                .put("protocol", "espressif-rom-serial")
                .put("status", "supported")
                .put("notes", "Initial model support. Flash pipeline will be added incrementally.")
        )
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("models", models)
        )
    }

    private fun handleMcuProbe(session: IHTTPSession, payload: JSONObject): Response {
        val model = payload.optString("model", "").trim().lowercase(Locale.US)
        if (model.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "model_required")
        if (model != "esp32") {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "unsupported_model",
                JSONObject()
                    .put("model", model)
                    .put("supported_models", org.json.JSONArray().put("esp32"))
            )
        }

        val name = payload.optString("name", "").trim()
        val vid = payload.optInt("vendor_id", -1)
        val pid = payload.optInt("product_id", -1)
        val timeoutMs = payload.optLong("permission_timeout_ms", 0L).coerceIn(0L, 120_000L)
        val dev = findUsbDevice(name, vid, pid)
            ?: return jsonError(Response.Status.NOT_FOUND, "usb_device_not_found")

        val perm = ensureDevicePermission(
            session,
            payload,
            tool = "device.usb",
            capability = "usb",
            detail = "MCU probe: model=$model vid=${dev.vendorId} pid=${dev.productId} name=${dev.deviceName}"
        )
        if (!perm.first) return perm.second!!

        if (!ensureUsbPermission(dev, timeoutMs)) {
            return jsonError(
                Response.Status.FORBIDDEN,
                "usb_permission_required",
                JSONObject()
                    .put("name", dev.deviceName)
                    .put("vendor_id", dev.vendorId)
                    .put("product_id", dev.productId)
            )
        }

        val serial = findSerialBulkPort(dev)
        val bridge = guessUsbSerialBridge(dev)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("model", model)
                .put("device", usbDeviceToJson(dev))
                .put("bridge_hint", bridge ?: JSONObject.NULL)
                .put("serial_port", serial ?: JSONObject.NULL)
                .put("ready_for_serial_protocol", serial != null)
        )
    }

    private fun findSerialBulkPort(dev: UsbDevice): JSONObject? {
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
            }
            if (inEp == null || outEp == null) continue

            val score = when (intf.interfaceClass) {
                UsbConstants.USB_CLASS_CDC_DATA -> 100
                UsbConstants.USB_CLASS_VENDOR_SPEC -> 90
                UsbConstants.USB_CLASS_COMM -> 60
                else -> 30
            } + intf.endpointCount

            if (score > bestScore) {
                bestScore = score
                best = JSONObject()
                    .put("interface_id", intf.id)
                    .put("interface_class", intf.interfaceClass)
                    .put("interface_subclass", intf.interfaceSubclass)
                    .put("interface_protocol", intf.interfaceProtocol)
                    .put("in_endpoint_address", inEp.address)
                    .put("out_endpoint_address", outEp.address)
            }
        }
        return best
    }

    private fun guessUsbSerialBridge(dev: UsbDevice): String? {
        return when (dev.vendorId) {
            0x10C4 -> "cp210x"
            0x1A86 -> "ch34x"
            0x0403 -> "ftdi"
            0x303A -> "esp-usb-serial-jtag"
            else -> null
        }
    }

    private data class EspSerialSelection(
        val interfaceObj: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
    )

    private data class McuFlashSegment(
        val relPath: String,
        val offset: Int,
        val bytes: ByteArray,
    )

    private fun handleMcuFlash(session: IHTTPSession, payload: JSONObject): Response {
        val model = payload.optString("model", "").trim().lowercase(Locale.US)
        if (model.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "model_required")
        if (model != "esp32") {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "unsupported_model",
                JSONObject()
                    .put("model", model)
                    .put("supported_models", org.json.JSONArray().put("esp32"))
            )
        }

        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val dev = usbDevicesByHandle[handle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")

        val perm = ensureDevicePermission(
            session,
            payload,
            tool = "device.usb",
            capability = "usb",
            detail = "MCU flash: model=$model handle=$handle"
        )
        if (!perm.first) return perm.second!!

        val imagePath = payload.optString("image_path", "").trim()

        val timeoutMs = payload.optInt("timeout_ms", 2000).coerceIn(100, 60_000)
        val reboot = payload.optBoolean("reboot", true)
        val autoEnterBootloader = payload.optBoolean("auto_enter_bootloader", true)
        val debug = payload.optBoolean("debug", false)

        val selection = pickEspSerialSelection(dev, payload)
            ?: return jsonError(Response.Status.BAD_REQUEST, "serial_port_not_found")

        val segments = try {
            buildMcuFlashSegments(payload, fallbackPath = imagePath)
        } catch (ex: IllegalArgumentException) {
            return jsonError(Response.Status.BAD_REQUEST, ex.message ?: "invalid_segments")
        } catch (ex: Exception) {
            return jsonError(Response.Status.INTERNAL_ERROR, "segment_prepare_failed", JSONObject().put("detail", ex.message ?: ""))
        }
        if (segments.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "segments_required")

        val t0 = System.currentTimeMillis()
        return try {
            val bridge = guessUsbSerialBridge(dev)
            val sessionCtx = EspSerialSession(
                usbManager = usbManager,
                dev = dev,
                conn = conn,
                inEp = selection.inEndpoint,
                outEp = selection.outEndpoint,
                timeoutMs = timeoutMs,
                bridgeHint = bridge,
                interfaceId = selection.interfaceObj.id,
            )
            try {
                if (!sessionCtx.usesUsbSerial()) {
                    val force = payload.optBoolean("force_claim", true)
                    if (!conn.claimInterface(selection.interfaceObj, force)) {
                        return jsonError(Response.Status.INTERNAL_ERROR, "claim_interface_failed")
                    }
                    runCatching { conn.setInterface(selection.interfaceObj) }
                }
                sessionCtx.configureSerial()
                sessionCtx.flushInput()
                var syncDebug: JSONArray? = null
                val flashDebug: JSONArray? = if (debug) JSONArray() else null
                var postSyncProbe: JSONObject? = null
                var spiAttachDebug: JSONObject? = null
                if (autoEnterBootloader) {
                    syncDebug = sessionCtx.syncWithAutoResetProfiles(bridge, selection.interfaceObj.id, debug = debug)
                } else {
                    val syncStart = System.currentTimeMillis()
                    try {
                        sessionCtx.sync()
                        if (debug) {
                            syncDebug = JSONArray().put(
                                JSONObject()
                                    .put("profile", "direct_sync")
                                    .put("ok", true)
                                    .put("elapsed_ms", (System.currentTimeMillis() - syncStart).coerceAtLeast(0L))
                            )
                        }
                    } catch (ex: Exception) {
                        if (debug) {
                            throw EspSyncException(
                                ex.message ?: "esp_response_timeout",
                                JSONArray().put(
                                    JSONObject()
                                        .put("profile", "direct_sync")
                                        .put("ok", false)
                                        .put("error", ex.message ?: "unknown_error")
                                        .put("elapsed_ms", (System.currentTimeMillis() - syncStart).coerceAtLeast(0L))
                                )
                            )
                        }
                        throw ex
                    }
                }
                if (debug) {
                    val probeStart = System.currentTimeMillis()
                    postSyncProbe = try {
                        val magic = sessionCtx.readChipDetectMagic()
                        JSONObject()
                            .put("ok", true)
                            .put("register", "0x40001000")
                            .put("value", String.format(Locale.US, "0x%08x", magic))
                            .put("elapsed_ms", (System.currentTimeMillis() - probeStart).coerceAtLeast(0L))
                    } catch (ex: Exception) {
                        JSONObject()
                            .put("ok", false)
                            .put("register", "0x40001000")
                            .put("error", ex.message ?: "unknown_error")
                            .put("elapsed_ms", (System.currentTimeMillis() - probeStart).coerceAtLeast(0L))
                    }
                }
                val spiAttachStart = System.currentTimeMillis()
                try {
                    val attach = sessionCtx.attachEsp32SpiFlash()
                    if (debug) {
                        spiAttachDebug = attach
                            .put("ok", true)
                            .put("elapsed_ms", (System.currentTimeMillis() - spiAttachStart).coerceAtLeast(0L))
                    }
                } catch (ex: Exception) {
                    if (debug) {
                        spiAttachDebug = JSONObject()
                            .put("ok", false)
                            .put("error", ex.message ?: "unknown_error")
                            .put("elapsed_ms", (System.currentTimeMillis() - spiAttachStart).coerceAtLeast(0L))
                        throw EspFlashException(
                            ex.message ?: "spi_attach_failed",
                            JSONObject()
                                .put("detail", ex.message ?: "spi_attach_failed")
                                .put("failed_segment_index", 0)
                                .put("failed_segment_path", segments.firstOrNull()?.relPath ?: JSONObject.NULL)
                                .put("failed_stage", "spi_attach")
                                .put("sync_debug", syncDebug ?: JSONArray())
                                .put("post_sync_probe", if (postSyncProbe == null) JSONObject.NULL else postSyncProbe)
                                .put("spi_attach_debug", spiAttachDebug)
                                .put("flash_debug", flashDebug ?: JSONArray())
                        )
                    }
                    throw ex
                }
                var totalBlocks = 0
                val written = org.json.JSONArray()
                for ((idx, seg) in segments.withIndex()) {
                    val isLast = idx == segments.lastIndex
                    val segStart = System.currentTimeMillis()
                    val r = try {
                        sessionCtx.flashImage(seg.bytes, seg.offset, reboot = reboot && isLast)
                    } catch (ex: EspFlashStageException) {
                        if (debug) {
                            flashDebug?.put(
                                JSONObject()
                                    .put("segment_index", idx)
                                    .put("path", seg.relPath)
                                    .put("offset", seg.offset)
                                    .put("ok", false)
                                    .put("stage", ex.stage)
                                    .put("block_index", if (ex.blockIndex == null) JSONObject.NULL else ex.blockIndex)
                                    .put("blocks_written", ex.blocksWritten)
                                    .put("elapsed_ms", (System.currentTimeMillis() - segStart).coerceAtLeast(0L))
                                    .put("error", ex.message ?: "unknown_error")
                            )
                            throw EspFlashException(
                                ex.message ?: ex.stage,
                                JSONObject()
                                    .put("detail", ex.message ?: ex.stage)
                                    .put("failed_segment_index", idx)
                                    .put("failed_segment_path", seg.relPath)
                                    .put("failed_stage", ex.stage)
                                    .put("sync_debug", syncDebug ?: JSONArray())
                                    .put("post_sync_probe", if (postSyncProbe == null) JSONObject.NULL else postSyncProbe)
                                    .put("spi_attach_debug", if (spiAttachDebug == null) JSONObject.NULL else spiAttachDebug)
                                    .put("flash_debug", flashDebug ?: JSONArray())
                            )
                        }
                        throw ex
                    }
                    totalBlocks += r.blocksWritten
                    written.put(
                        JSONObject()
                            .put("path", seg.relPath)
                            .put("offset", seg.offset)
                            .put("size", seg.bytes.size)
                            .put("md5", md5Hex(seg.bytes))
                            .put("blocks_written", r.blocksWritten)
                            .put("block_size", r.blockSize)
                    )
                    if (debug) {
                        flashDebug?.put(
                            JSONObject()
                                .put("segment_index", idx)
                                .put("path", seg.relPath)
                                .put("offset", seg.offset)
                                .put("ok", true)
                                .put("stage", "flash_data_done")
                                .put("blocks_written", r.blocksWritten)
                                .put("elapsed_ms", (System.currentTimeMillis() - segStart).coerceAtLeast(0L))
                        )
                    }
                }

                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(0L)
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("model", model)
                        .put("handle", handle)
                        .put("segment_count", segments.size)
                        .put("segments", written)
                        .put("interface_id", selection.interfaceObj.id)
                        .put("in_endpoint_address", selection.inEndpoint.address)
                        .put("out_endpoint_address", selection.outEndpoint.address)
                        .put("blocks_written_total", totalBlocks)
                        .put("elapsed_ms", elapsed)
                        .put("transport", if (sessionCtx.usesUsbSerial()) "usb-serial-for-android" else "usb-bulk")
                        .put("sync_debug", if (debug) (syncDebug ?: JSONArray()) else JSONObject.NULL)
                        .put("post_sync_probe", if (debug) (postSyncProbe ?: JSONObject.NULL) else JSONObject.NULL)
                        .put("spi_attach_debug", if (debug) (spiAttachDebug ?: JSONObject.NULL) else JSONObject.NULL)
                        .put("flash_debug", if (debug) (flashDebug ?: JSONArray()) else JSONObject.NULL)
                )
            } finally {
                sessionCtx.close()
            }
        } catch (ex: EspSyncException) {
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "mcu_flash_failed",
                JSONObject()
                    .put("detail", ex.message ?: "")
                    .put("sync_debug", ex.attempts)
            )
        } catch (ex: EspFlashException) {
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "mcu_flash_failed",
                ex.detailPayload
            )
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "mcu_flash_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleMcuFlashPlan(payload: JSONObject): Response {
        val planPath = payload.optString("plan_path", "").trim()
        if (planPath.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "plan_path_required")
        val planRef = parseFsPathRef(planPath) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
        val planBytes = try {
            readFsPathBytes(planRef).first
        } catch (ex: IllegalArgumentException) {
            return when (ex.message ?: "") {
                "path_outside_termux_home" -> jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                "termux_unavailable" -> jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
                "file_too_large" -> jsonError(Response.Status.BAD_REQUEST, "plan_too_large")
                else -> jsonError(Response.Status.NOT_FOUND, "plan_not_found")
            }
        }

        val planJson = try {
            JSONObject(planBytes.toString(Charsets.UTF_8))
        } catch (ex: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid_plan_json", JSONObject().put("detail", ex.message ?: ""))
        }

        val flashFiles = planJson.optJSONObject("flash_files")
            ?: return jsonError(Response.Status.BAD_REQUEST, "plan_flash_files_required")

        val chipFromPlan = planJson.optJSONObject("extra_esptool_args")
            ?.optString("chip", "")
            ?.trim()
            ?.lowercase(Locale.US)
            ?: ""
        val model = payload.optString("model", chipFromPlan.ifBlank { "esp32" }).trim().lowercase(Locale.US)
        if (model != "esp32") {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "unsupported_model",
                JSONObject()
                    .put("model", model)
                    .put("supported_models", org.json.JSONArray().put("esp32"))
            )
        }

        val userRoot = File(context.filesDir, "user").canonicalFile
        val baseDir = when (planRef.fs) {
            "user" -> planRef.userFile?.parentFile ?: userRoot
            "termux" -> File(planRef.termuxPath ?: TERMUX_HOME_PREFIX).parentFile ?: File(TERMUX_HOME_PREFIX)
            else -> userRoot
        }
        val sortedOffsets = flashFiles.keys().asSequence().toList().sortedBy {
            parseOffsetToInt(it)
        }
        if (sortedOffsets.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "plan_flash_files_empty")

        val segments = org.json.JSONArray()
        val missing = org.json.JSONArray()
        for (offKey in sortedOffsets) {
            val offset = parseOffsetToInt(offKey)
                ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_offset", JSONObject().put("offset", offKey))
            val raw = flashFiles.opt(offKey)
            val path = when (raw) {
                is String -> raw.trim()
                is JSONObject -> raw.optString("file", raw.optString("path", "")).trim()
                else -> ""
            }
            if (path.isBlank()) continue
            val abs = File(baseDir, path).canonicalFile.path.replace('\\', '/')
            val segmentPath = when (planRef.fs) {
                "user" -> {
                    val rel = toUserRelativePath(File(abs))
                        ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir", JSONObject().put("path", path))
                    "user://$rel"
                }
                "termux" -> {
                    if (!isAllowedTermuxPath(abs)) {
                        return jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home", JSONObject().put("path", path))
                    }
                    "termux://~${abs.removePrefix(TERMUX_HOME_PREFIX)}"
                }
                else -> path
            }
            val ref = parseFsPathRef(segmentPath)
                ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path", JSONObject().put("path", segmentPath))
            val stat = statFsPath(ref)
            val exists = stat.first
            if (!exists) missing.put(segmentPath)
            val size = if (exists) stat.second else 0L
            segments.put(
                JSONObject()
                    .put("path", segmentPath)
                    .put("offset", offset)
                    .put("exists", exists)
                    .put("size", size)
            )
        }

        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("model", model)
                .put("plan_path", planRef.displayPath)
                .put("segment_count", segments.length())
                .put("segments", segments)
                .put("missing_files", missing)
        )
    }

    private fun handleMcuDiagSerial(session: IHTTPSession, payload: JSONObject): Response {
        val model = payload.optString("model", "").trim().lowercase(Locale.US)
        if (model.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "model_required")
        if (model != "esp32") {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "unsupported_model",
                JSONObject()
                    .put("model", model)
                    .put("supported_models", org.json.JSONArray().put("esp32"))
            )
        }

        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val dev = usbDevicesByHandle[handle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")

        val perm = ensureDevicePermission(
            session,
            payload,
            tool = "device.usb",
            capability = "usb",
            detail = "MCU serial diagnostics: model=$model handle=$handle"
        )
        if (!perm.first) return perm.second!!

        val selection = pickEspSerialSelection(dev, payload)
            ?: return jsonError(Response.Status.BAD_REQUEST, "serial_port_not_found")
        val timeoutMs = payload.optInt("timeout_ms", 2000).coerceIn(100, 60_000)
        val sniffBeforeMs = payload.optInt("sniff_before_ms", 600).coerceIn(100, 10_000)
        val sniffAfterMs = payload.optInt("sniff_after_ms", 1400).coerceIn(100, 20_000)
        val autoEnterBootloader = payload.optBoolean("auto_enter_bootloader", true)

        return try {
            val bridge = guessUsbSerialBridge(dev)
            val sessionCtx = EspSerialSession(
                usbManager = usbManager,
                dev = dev,
                conn = conn,
                inEp = selection.inEndpoint,
                outEp = selection.outEndpoint,
                timeoutMs = timeoutMs,
                bridgeHint = bridge,
                interfaceId = selection.interfaceObj.id,
            )
            try {
                if (!sessionCtx.usesUsbSerial()) {
                    val force = payload.optBoolean("force_claim", true)
                    if (!conn.claimInterface(selection.interfaceObj, force)) {
                        return jsonError(Response.Status.INTERNAL_ERROR, "claim_interface_failed")
                    }
                    runCatching { conn.setInterface(selection.interfaceObj) }
                }
                sessionCtx.configureSerial()
                sessionCtx.flushInput()
                val baseline = sessionCtx.sniffRaw(sniffBeforeMs)
                if (autoEnterBootloader) {
                    sessionCtx.enterBootloaderIfSupported(bridge, selection.interfaceObj.id)
                    sessionCtx.settleAfterBootloaderReset()
                }
                sessionCtx.sendSyncProbe()
                val replyRaw = sessionCtx.sniffRaw(sniffAfterMs)
                val frames = sessionCtx.collectSlipFrames(400, 8)

                val maxDump = 1024
                val baselineSlice = baseline.copyOfRange(0, minOf(maxDump, baseline.size))
                val replySlice = replyRaw.copyOfRange(0, minOf(maxDump, replyRaw.size))
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("model", model)
                        .put("handle", handle)
                        .put("bridge_hint", bridge ?: JSONObject.NULL)
                        .put("interface_id", selection.interfaceObj.id)
                        .put("in_endpoint_address", selection.inEndpoint.address)
                        .put("out_endpoint_address", selection.outEndpoint.address)
                        .put("baseline_len", baseline.size)
                        .put("reply_len", replyRaw.size)
                        .put("baseline_b64", android.util.Base64.encodeToString(baselineSlice, android.util.Base64.NO_WRAP))
                        .put("reply_b64", android.util.Base64.encodeToString(replySlice, android.util.Base64.NO_WRAP))
                        .put("baseline_ascii", bytesToAsciiPreview(baselineSlice))
                        .put("reply_ascii", bytesToAsciiPreview(replySlice))
                        .put("slip_frames", frames)
                        .put("transport", if (sessionCtx.usesUsbSerial()) "usb-serial-for-android" else "usb-bulk")
                )
            } finally {
                sessionCtx.close()
            }
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "mcu_diag_serial_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleMcuSerialLines(session: IHTTPSession, payload: JSONObject): Response {
        val model = payload.optString("model", "").trim().lowercase(Locale.US)
        if (model.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "model_required")
        if (model != "esp32") {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "unsupported_model",
                JSONObject()
                    .put("model", model)
                    .put("supported_models", org.json.JSONArray().put("esp32"))
            )
        }

        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val dev = usbDevicesByHandle[handle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")

        val perm = ensureDevicePermission(
            session,
            payload,
            tool = "device.usb",
            capability = "usb",
            detail = "MCU serial line control: model=$model handle=$handle"
        )
        if (!perm.first) return perm.second!!

        val selection = pickEspSerialSelection(dev, payload)
            ?: return jsonError(Response.Status.BAD_REQUEST, "serial_port_not_found")
        val timeoutMs = payload.optInt("timeout_ms", 2000).coerceIn(100, 60_000)
        val sequence = payload.optString("sequence", "").trim().lowercase(Locale.US)
        val dtr = if (payload.has("dtr")) payload.optBoolean("dtr") else null
        val rts = if (payload.has("rts")) payload.optBoolean("rts") else null
        val sleepAfterMs = payload.optInt("sleep_after_ms", 0).coerceIn(0, 5000)
        val script = payload.optJSONArray("script")

        return try {
            val bridge = guessUsbSerialBridge(dev)
            val sessionCtx = EspSerialSession(
                usbManager = usbManager,
                dev = dev,
                conn = conn,
                inEp = selection.inEndpoint,
                outEp = selection.outEndpoint,
                timeoutMs = timeoutMs,
                bridgeHint = bridge,
                interfaceId = selection.interfaceObj.id,
            )
            try {
                if (!sessionCtx.usesUsbSerial()) {
                    val force = payload.optBoolean("force_claim", true)
                    if (!conn.claimInterface(selection.interfaceObj, force)) {
                        return jsonError(Response.Status.INTERNAL_ERROR, "claim_interface_failed")
                    }
                    runCatching { conn.setInterface(selection.interfaceObj) }
                }
                sessionCtx.configureSerial()
                var executedSteps = 0
                if (script != null && script.length() > 0) {
                    if (script.length() > 16) return jsonError(Response.Status.BAD_REQUEST, "script_too_long")
                    for (i in 0 until script.length()) {
                        val step = script.optJSONObject(i) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_script_step")
                        val stepDtr = if (step.has("dtr")) step.optBoolean("dtr") else null
                        val stepRts = if (step.has("rts")) step.optBoolean("rts") else null
                        val stepSleep = step.optInt("sleep_ms", 0).coerceIn(0, 5000)
                        if (stepDtr == null && stepRts == null) {
                            return jsonError(Response.Status.BAD_REQUEST, "script_step_state_required")
                        }
                        sessionCtx.setModemLines(stepDtr, stepRts)
                        if (stepSleep > 0) Thread.sleep(stepSleep.toLong())
                        executedSteps += 1
                    }
                } else {
                    when (sequence) {
                        "", "none" -> {
                            if (dtr == null && rts == null) {
                                return jsonError(Response.Status.BAD_REQUEST, "sequence_or_line_state_required")
                            }
                            sessionCtx.setModemLines(dtr, rts)
                        }
                        "enter_bootloader", "download", "bootloader" -> {
                            sessionCtx.enterBootloaderIfSupported(bridge, selection.interfaceObj.id)
                            sessionCtx.settleAfterBootloaderReset()
                        }
                        "enter_bootloader_inverted", "download_inverted", "bootloader_inverted" -> {
                            // Inverted mapping variant for boards wired opposite to common DTR/RTS usage.
                            sessionCtx.setModemLines(dtr = true, rts = false)
                            Thread.sleep(100)
                            sessionCtx.setModemLines(dtr = false, rts = true)
                            Thread.sleep(50)
                            sessionCtx.setModemLines(dtr = false, rts = false)
                            Thread.sleep(40)
                            sessionCtx.settleAfterBootloaderReset()
                        }
                        "run", "normal" -> {
                            sessionCtx.setModemLines(dtr = false, rts = false)
                            Thread.sleep(40)
                        }
                        else -> {
                            return jsonError(
                                Response.Status.BAD_REQUEST,
                                "invalid_sequence",
                                JSONObject().put(
                                    "allowed",
                                    org.json.JSONArray()
                                        .put("enter_bootloader")
                                        .put("enter_bootloader_inverted")
                                        .put("run")
                                        .put("none")
                                )
                            )
                        }
                    }
                }
                if (sleepAfterMs > 0) Thread.sleep(sleepAfterMs.toLong())
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("model", model)
                        .put("handle", handle)
                        .put("bridge_hint", bridge ?: JSONObject.NULL)
                        .put("interface_id", selection.interfaceObj.id)
                        .put("sequence", if (sequence.isBlank()) "none" else sequence)
                        .put("dtr", if (dtr == null) JSONObject.NULL else dtr)
                        .put("rts", if (rts == null) JSONObject.NULL else rts)
                        .put("executed_script_steps", executedSteps)
                        .put("transport", if (sessionCtx.usesUsbSerial()) "usb-serial-for-android" else "usb-bulk")
                )
            } finally {
                sessionCtx.close()
            }
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "mcu_serial_lines_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleMcuReset(session: IHTTPSession, payload: JSONObject): Response {
        val model = payload.optString("model", "").trim().lowercase(Locale.US)
        if (model.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "model_required")
        if (model != "esp32") {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "unsupported_model",
                JSONObject()
                    .put("model", model)
                    .put("supported_models", org.json.JSONArray().put("esp32"))
            )
        }

        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val dev = usbDevicesByHandle[handle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")

        val perm = ensureDevicePermission(
            session,
            payload,
            tool = "device.usb",
            capability = "usb",
            detail = "MCU reset control: model=$model handle=$handle"
        )
        if (!perm.first) return perm.second!!

        val selection = pickEspSerialSelection(dev, payload)
            ?: return jsonError(Response.Status.BAD_REQUEST, "serial_port_not_found")
        val timeoutMs = payload.optInt("timeout_ms", 2000).coerceIn(100, 60_000)
        val mode = payload.optString("mode", "reboot").trim().lowercase(Locale.US)
        val sleepAfterMs = payload.optInt("sleep_after_ms", 120).coerceIn(0, 5000)

        return try {
            val bridge = guessUsbSerialBridge(dev)
            val sessionCtx = EspSerialSession(
                usbManager = usbManager,
                dev = dev,
                conn = conn,
                inEp = selection.inEndpoint,
                outEp = selection.outEndpoint,
                timeoutMs = timeoutMs,
                bridgeHint = bridge,
                interfaceId = selection.interfaceObj.id,
            )
            try {
                if (!sessionCtx.usesUsbSerial()) {
                    val force = payload.optBoolean("force_claim", true)
                    if (!conn.claimInterface(selection.interfaceObj, force)) {
                        return jsonError(Response.Status.INTERNAL_ERROR, "claim_interface_failed")
                    }
                    runCatching { conn.setInterface(selection.interfaceObj) }
                }
                sessionCtx.configureSerial()
                when (mode) {
                    "reboot", "reset" -> sessionCtx.rebootToRunIfSupported(bridge, selection.interfaceObj.id)
                    "bootloader" -> {
                        sessionCtx.enterBootloaderIfSupported(bridge, selection.interfaceObj.id)
                        sessionCtx.settleAfterBootloaderReset()
                    }
                    "bootloader_inverted" -> {
                        sessionCtx.enterBootloaderInvertedIfSupported(bridge, selection.interfaceObj.id)
                        sessionCtx.settleAfterBootloaderReset()
                    }
                    "run", "normal" -> {
                        sessionCtx.setModemLines(dtr = false, rts = false)
                        Thread.sleep(40)
                    }
                    else -> {
                        return jsonError(
                            Response.Status.BAD_REQUEST,
                            "invalid_mode",
                            JSONObject().put(
                                "allowed",
                                org.json.JSONArray()
                                    .put("reboot")
                                    .put("bootloader")
                                    .put("bootloader_inverted")
                                    .put("run")
                            )
                        )
                    }
                }
                if (sleepAfterMs > 0) Thread.sleep(sleepAfterMs.toLong())
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("model", model)
                        .put("handle", handle)
                        .put("mode", mode)
                        .put("bridge_hint", bridge ?: JSONObject.NULL)
                        .put("interface_id", selection.interfaceObj.id)
                        .put("transport", if (sessionCtx.usesUsbSerial()) "usb-serial-for-android" else "usb-bulk")
                )
            } finally {
                sessionCtx.close()
            }
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "mcu_reset_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleMcuSerialMonitor(session: IHTTPSession, payload: JSONObject): Response {
        val model = payload.optString("model", "").trim().lowercase(Locale.US)
        if (model.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "model_required")
        if (model != "esp32") {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "unsupported_model",
                JSONObject()
                    .put("model", model)
                    .put("supported_models", org.json.JSONArray().put("esp32"))
            )
        }

        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val dev = usbDevicesByHandle[handle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")

        val perm = ensureDevicePermission(
            session,
            payload,
            tool = "device.usb",
            capability = "usb",
            detail = "MCU passive serial monitor: model=$model handle=$handle"
        )
        if (!perm.first) return perm.second!!

        val selection = pickEspSerialSelection(dev, payload)
            ?: return jsonError(Response.Status.BAD_REQUEST, "serial_port_not_found")
        val timeoutMs = payload.optInt("timeout_ms", 2000).coerceIn(100, 60_000)
        val durationMs = payload.optInt("duration_ms", 2000).coerceIn(100, 60_000)
        val configureSerial = payload.optBoolean("configure_serial", true)
        val flushInput = payload.optBoolean("flush_input", false)
        val maxDumpBytes = payload.optInt("max_dump_bytes", 8192).coerceIn(256, 262_144)

        return try {
            val bridge = guessUsbSerialBridge(dev)
            val sessionCtx = EspSerialSession(
                usbManager = usbManager,
                dev = dev,
                conn = conn,
                inEp = selection.inEndpoint,
                outEp = selection.outEndpoint,
                timeoutMs = timeoutMs,
                bridgeHint = bridge,
                interfaceId = selection.interfaceObj.id,
            )
            try {
                if (!sessionCtx.usesUsbSerial()) {
                    val force = payload.optBoolean("force_claim", true)
                    if (!conn.claimInterface(selection.interfaceObj, force)) {
                        return jsonError(Response.Status.INTERNAL_ERROR, "claim_interface_failed")
                    }
                    runCatching { conn.setInterface(selection.interfaceObj) }
                }
                if (configureSerial) sessionCtx.configureSerial()
                if (flushInput) sessionCtx.flushInput()
                val raw = sessionCtx.sniffRaw(durationMs)
                val slice = raw.copyOfRange(0, minOf(maxDumpBytes, raw.size))
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("model", model)
                        .put("handle", handle)
                        .put("bridge_hint", bridge ?: JSONObject.NULL)
                        .put("interface_id", selection.interfaceObj.id)
                        .put("in_endpoint_address", selection.inEndpoint.address)
                        .put("out_endpoint_address", selection.outEndpoint.address)
                        .put("duration_ms", durationMs)
                        .put("raw_len", raw.size)
                        .put("truncated", raw.size > slice.size)
                        .put("raw_b64", android.util.Base64.encodeToString(slice, android.util.Base64.NO_WRAP))
                        .put("raw_ascii", bytesToAsciiPreview(slice))
                        .put("transport", if (sessionCtx.usesUsbSerial()) "usb-serial-for-android" else "usb-bulk")
                )
            } finally {
                sessionCtx.close()
            }
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "mcu_serial_monitor_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun bytesToAsciiPreview(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v in 32..126 || v == 10 || v == 13 || v == 9) sb.append(v.toChar()) else sb.append('.')
        }
        return sb.toString()
    }

    private fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun buildMcuFlashSegments(payload: JSONObject, fallbackPath: String): List<McuFlashSegment> {
        val out = ArrayList<McuFlashSegment>()
        val segments = payload.optJSONArray("segments")
        if (segments != null && segments.length() > 0) {
            for (i in 0 until segments.length()) {
                val item = segments.optJSONObject(i)
                    ?: throw IllegalArgumentException("invalid_segments")
                val path = item.optString("path", "").trim()
                if (path.isBlank()) throw IllegalArgumentException("segment_path_required")
                val offset = item.optLong("offset", -1L)
                if (offset < 0 || offset > 0xFFFFFFFFL) throw IllegalArgumentException("segment_offset_invalid")
                val ref = parseFsPathRef(path) ?: throw IllegalArgumentException("invalid_path")
                val bytes = try {
                    readFsPathBytes(ref).first
                } catch (ex: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        when (ex.message ?: "") {
                            "path_outside_termux_home" -> "path_outside_termux_home"
                            "termux_unavailable" -> "termux_unavailable"
                            "file_too_large" -> "segment_too_large"
                            else -> "segment_not_found"
                        }
                    )
                }
                if (bytes.isEmpty()) throw IllegalArgumentException("segment_empty")
                out.add(McuFlashSegment(relPath = ref.displayPath, offset = offset.toInt(), bytes = bytes))
            }
            return out
        }

        if (fallbackPath.isBlank()) throw IllegalArgumentException("image_path_required")
        val offset = payload.optLong("offset", 0x10000).coerceIn(0L, 0xFFFFFFFFL).toInt()
        val ref = parseFsPathRef(fallbackPath) ?: throw IllegalArgumentException("invalid_path")
        val bytes = try {
            readFsPathBytes(ref).first
        } catch (ex: IllegalArgumentException) {
            throw IllegalArgumentException(
                when (ex.message ?: "") {
                    "path_outside_termux_home" -> "path_outside_termux_home"
                    "termux_unavailable" -> "termux_unavailable"
                    "file_too_large" -> "image_too_large"
                    else -> "image_not_found"
                }
            )
        }
        if (bytes.isEmpty()) throw IllegalArgumentException("image_empty")
        out.add(McuFlashSegment(relPath = ref.displayPath, offset = offset, bytes = bytes))
        return out
    }

    private fun parseOffsetToInt(text: String): Int? {
        val t = text.trim()
        if (t.isBlank()) return null
        return try {
            val v = if (t.startsWith("0x", ignoreCase = true)) {
                java.lang.Long.parseLong(t.substring(2), 16)
            } else {
                java.lang.Long.parseLong(t, 10)
            }
            if (v < 0L || v > 0xFFFFFFFFL) null else v.toInt()
        } catch (_: Exception) {
            null
        }
    }

    private fun pickEspSerialSelection(dev: UsbDevice, payload: JSONObject): EspSerialSelection? {
        val interfaceId = if (payload.has("interface_id")) payload.optInt("interface_id", -1) else -1
        val inAddr = if (payload.has("in_endpoint_address")) payload.optInt("in_endpoint_address", -1) else -1
        val outAddr = if (payload.has("out_endpoint_address")) payload.optInt("out_endpoint_address", -1) else -1

        if (interfaceId >= 0 && inAddr >= 0 && outAddr >= 0) {
            val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == interfaceId }
                ?: return null
            val inEp = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                .firstOrNull { it.address == inAddr && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
                ?: return null
            val outEp = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                .firstOrNull { it.address == outAddr && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
                ?: return null
            return EspSerialSelection(intf, inEp, outEp)
        }

        val detected = findSerialBulkPort(dev) ?: return null
        val autoIntfId = detected.optInt("interface_id", -1)
        val autoIn = detected.optInt("in_endpoint_address", -1)
        val autoOut = detected.optInt("out_endpoint_address", -1)
        if (autoIntfId < 0 || autoIn < 0 || autoOut < 0) return null
        val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == autoIntfId }
            ?: return null
        val inEp = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull { it.address == autoIn }
            ?: return null
        val outEp = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull { it.address == autoOut }
            ?: return null
        return EspSerialSelection(intf, inEp, outEp)
    }

    private fun setKeepScreenOn(enabled: Boolean, timeoutS: Long) {
        if (!enabled) {
            keepScreenOnExpiresAtMs = 0L
            try {
                screenReleaseFuture?.cancel(false)
            } catch (_: Exception) {
            }
            screenReleaseFuture = null
            val wl = keepScreenOnWakeLock
            keepScreenOnWakeLock = null
            try {
                if (wl != null && wl.isHeld) wl.release()
            } catch (_: Exception) {
            }
            return
        }

        val now = System.currentTimeMillis()
        keepScreenOnExpiresAtMs = if (timeoutS > 0) now + timeoutS * 1000L else 0L

        // Release any prior timer.
        try {
            screenReleaseFuture?.cancel(false)
        } catch (_: Exception) {
        }
        screenReleaseFuture = null

        var wl = keepScreenOnWakeLock
        if (wl == null) {
            @Suppress("DEPRECATION")
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            wl = powerManager.newWakeLock(flags, "methings:keep_screen_on")
            wl.setReferenceCounted(false)
            keepScreenOnWakeLock = wl
        }

        val wln = wl
        if (wln != null) {
            try {
                if (!wln.isHeld) wln.acquire()
            } catch (_: Exception) {
            }
        }

        if (timeoutS > 0) {
            screenReleaseFuture = screenScheduler.schedule(
                { setKeepScreenOn(false, timeoutS = 0) },
                timeoutS,
                TimeUnit.SECONDS
            )
        }
    }

    private fun handleScreenStatus(): Response {
        val wl = keepScreenOnWakeLock
        val held = try { wl?.isHeld == true } catch (_: Exception) { false }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("keep_screen_on", held)
                .put("expires_at", keepScreenOnExpiresAtMs)
        )
    }

    private fun handleScreenKeepOn(session: IHTTPSession, payload: JSONObject): Response {
        val perm = ensureDevicePermission(
            session,
            payload,
            tool = "device.screen",
            capability = "screen",
            detail = "Keep screen on"
        )
        if (!perm.first) return perm.second!!

        val enabled = payload.optBoolean("enabled", true)
        val timeoutS = payload.optLong("timeout_s", 0L).coerceIn(0L, 24 * 60 * 60L)
        setKeepScreenOn(enabled, timeoutS = timeoutS)
        return handleScreenStatus()
    }

    private fun closeSerialSessionInternal(st: SerialSessionState) {
        runCatching { st.port.close() }
        runCatching { st.connection.close() }
    }

    private fun closeSerialSessionsForUsbHandle(usbHandle: String): Int {
        val targets = serialSessions.values.filter { it.usbHandle == usbHandle }
        for (st in targets) {
            serialSessions.remove(st.id)
            closeSerialSessionInternal(st)
        }
        return targets.size
    }

    private fun serialSessionToJson(st: SerialSessionState): JSONObject {
        return JSONObject()
            .put("serial_handle", st.id)
            .put("usb_handle", st.usbHandle)
            .put("device_name", st.deviceName)
            .put("port_index", st.portIndex)
            .put("baud_rate", st.baudRate)
            .put("data_bits", st.dataBits)
            .put("stop_bits", st.stopBits)
            .put("parity", st.parity)
            .put("opened_at_ms", st.openedAtMs)
            .put("driver", "usb-serial-for-android")
    }

    private fun handleSerialStatus(session: IHTTPSession): Response {
        val perm = ensureDevicePermission(
            session,
            JSONObject(),
            tool = "device.usb",
            capability = "usb",
            detail = "USB serial status"
        )
        if (!perm.first) return perm.second!!

        val items = JSONArray()
        serialSessions.values.sortedBy { it.id }.forEach { items.put(serialSessionToJson(it)) }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("count", items.length())
                .put("items", items)
        )
    }

    private fun handleSerialOpen(session: IHTTPSession, payload: JSONObject): Response {
        val perm = ensureDevicePermission(
            session,
            payload,
            tool = "device.usb",
            capability = "usb",
            detail = "Open USB serial session"
        )
        if (!perm.first) return perm.second!!

        val usbHandle = payload.optString("handle", "").trim()
        if (usbHandle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val dev = usbDevicesByHandle[usbHandle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")
        if (!usbConnections.containsKey(usbHandle)) return jsonError(Response.Status.NOT_FOUND, "handle_not_found")

        if (!runCatching { usbManager.hasPermission(dev) }.getOrDefault(false)) {
            return jsonError(Response.Status.FORBIDDEN, "usb_permission_required")
        }

        val portIndex = payload.optInt("port_index", 0)
        if (portIndex < 0) return jsonError(Response.Status.BAD_REQUEST, "invalid_port_index")
        val baudRate = payload.optInt("baud_rate", 115200).coerceIn(300, 3_500_000)
        val dataBits = payload.optInt("data_bits", UsbSerialPort.DATABITS_8)
        val stopBits = payload.optInt("stop_bits", UsbSerialPort.STOPBITS_1)
        val parity = payload.optInt("parity", UsbSerialPort.PARITY_NONE)
        val dtr = if (payload.has("dtr")) payload.optBoolean("dtr") else null
        val rts = if (payload.has("rts")) payload.optBoolean("rts") else null

        val serialConn = usbManager.openDevice(dev) ?: return jsonError(Response.Status.INTERNAL_ERROR, "serial_open_failed")
        return try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(dev)
            if (driver == null) {
                runCatching { serialConn.close() }
                return jsonError(Response.Status.BAD_REQUEST, "serial_driver_not_found")
            }
            val ports = driver.ports
            if (portIndex >= ports.size) {
                runCatching { serialConn.close() }
                return jsonError(
                    Response.Status.BAD_REQUEST,
                    "serial_port_not_found",
                    JSONObject().put("available_ports", ports.size)
                )
            }
            val port = ports[portIndex]
            port.open(serialConn)
            port.setParameters(baudRate, dataBits, stopBits, parity)
            if (dtr != null) runCatching { port.setDTR(dtr) }
            if (rts != null) runCatching { port.setRTS(rts) }

            val serialHandle = UUID.randomUUID().toString()
            val st = SerialSessionState(
                id = serialHandle,
                usbHandle = usbHandle,
                deviceName = dev.deviceName,
                portIndex = portIndex,
                baudRate = baudRate,
                dataBits = dataBits,
                stopBits = stopBits,
                parity = parity,
                connection = serialConn,
                port = port,
                openedAtMs = System.currentTimeMillis(),
            )
            serialSessions[serialHandle] = st
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("session", serialSessionToJson(st))
            )
        } catch (ex: Exception) {
            runCatching { serialConn.close() }
            jsonError(Response.Status.INTERNAL_ERROR, "serial_open_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleSerialListPorts(session: IHTTPSession, payload: JSONObject): Response {
        val perm = ensureDevicePermission(
            session,
            payload,
            tool = "device.usb",
            capability = "usb",
            detail = "List USB serial ports"
        )
        if (!perm.first) return perm.second!!

        val usbHandle = payload.optString("handle", "").trim()
        if (usbHandle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val dev = usbDevicesByHandle[usbHandle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")
        if (!usbConnections.containsKey(usbHandle)) return jsonError(Response.Status.NOT_FOUND, "handle_not_found")

        if (!runCatching { usbManager.hasPermission(dev) }.getOrDefault(false)) {
            return jsonError(Response.Status.FORBIDDEN, "usb_permission_required")
        }

        val driver = UsbSerialProber.getDefaultProber().probeDevice(dev)
            ?: return jsonError(Response.Status.BAD_REQUEST, "serial_driver_not_found")

        val ports = JSONArray()
        driver.ports.forEachIndexed { idx, port ->
            ports.put(
                JSONObject()
                    .put("port_index", idx)
                    .put("port_number", runCatching { port.portNumber }.getOrNull() ?: idx)
                    .put("driver_class", port.javaClass.simpleName)
            )
        }

        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("handle", usbHandle)
                .put("device_name", dev.deviceName)
                .put("bridge_hint", guessUsbSerialBridge(dev) ?: JSONObject.NULL)
                .put("driver", driver.javaClass.simpleName)
                .put("port_count", ports.length())
                .put("ports", ports)
        )
    }

    private fun handleSerialClose(payload: JSONObject): Response {
        val serialHandle = payload.optString("serial_handle", "").trim()
        if (serialHandle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "serial_handle_required")
        val st = serialSessions.remove(serialHandle)
        if (st != null) closeSerialSessionInternal(st)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("closed", st != null)
        )
    }

    private fun handleSerialRead(payload: JSONObject): Response {
        val serialHandle = payload.optString("serial_handle", "").trim()
        if (serialHandle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "serial_handle_required")
        val st = serialSessions[serialHandle] ?: return jsonError(Response.Status.NOT_FOUND, "serial_handle_not_found")
        val maxBytes = payload.optInt("max_bytes", 4096).coerceIn(1, 1024 * 1024)
        val timeoutMs = payload.optInt("timeout_ms", 200).coerceIn(0, 60_000)

        val buf = ByteArray(maxBytes)
        return try {
            val n = synchronized(st.lock) {
                st.port.read(buf, timeoutMs)
            }
            val out = if (n > 0) buf.copyOfRange(0, n.coerceIn(0, buf.size)) else ByteArray(0)
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("serial_handle", serialHandle)
                    .put("bytes_read", out.size)
                    .put("data_b64", Base64.encodeToString(out, Base64.NO_WRAP))
            )
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "serial_read_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleSerialWrite(payload: JSONObject): Response {
        val serialHandle = payload.optString("serial_handle", "").trim()
        if (serialHandle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "serial_handle_required")
        val st = serialSessions[serialHandle] ?: return jsonError(Response.Status.NOT_FOUND, "serial_handle_not_found")
        val timeoutMs = payload.optInt("timeout_ms", 2000).coerceIn(0, 60_000)
        val dataB64 = payload.optString("data_b64", "").trim()
        if (dataB64.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "data_b64_required")
        val bytes = try {
            Base64.decode(dataB64, Base64.DEFAULT)
        } catch (_: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid_data_b64")
        }
        if (bytes.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "data_empty")

        return try {
            val n = synchronized(st.lock) {
                st.port.write(bytes, timeoutMs)
            }
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("serial_handle", serialHandle)
                    .put("bytes_written", n)
            )
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "serial_write_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleSerialLines(payload: JSONObject): Response {
        val serialHandle = payload.optString("serial_handle", "").trim()
        if (serialHandle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "serial_handle_required")
        val st = serialSessions[serialHandle] ?: return jsonError(Response.Status.NOT_FOUND, "serial_handle_not_found")
        val dtr = if (payload.has("dtr")) payload.optBoolean("dtr") else null
        val rts = if (payload.has("rts")) payload.optBoolean("rts") else null
        if (dtr == null && rts == null) return jsonError(Response.Status.BAD_REQUEST, "line_state_required")

        return try {
            synchronized(st.lock) {
                if (dtr != null) st.port.setDTR(dtr)
                if (rts != null) st.port.setRTS(rts)
            }
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("serial_handle", serialHandle)
                    .put("dtr", if (dtr == null) JSONObject.NULL else dtr)
                    .put("rts", if (rts == null) JSONObject.NULL else rts)
            )
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "serial_lines_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleUsbOpen(session: IHTTPSession, payload: JSONObject): Response {
        val name = payload.optString("name", "").trim()
        val vid = payload.optInt("vendor_id", -1)
        val pid = payload.optInt("product_id", -1)
        val timeoutMs = payload.optLong("permission_timeout_ms", 0L)

        val dev = findUsbDevice(name, vid, pid)
            ?: return jsonError(Response.Status.NOT_FOUND, "usb_device_not_found")

        val perm = ensureDevicePermission(
            session,
            payload,
            tool = "device.usb",
            capability = "usb",
            detail = "USB access: vid=${dev.vendorId} pid=${dev.productId} name=${dev.deviceName}"
        )
        if (!perm.first) return perm.second!!

        if (!ensureUsbPermission(dev, timeoutMs)) {
            return jsonError(
                Response.Status.FORBIDDEN,
                "usb_permission_required",
                JSONObject()
                    .put("name", dev.deviceName)
                    .put("vendor_id", dev.vendorId)
                    .put("product_id", dev.productId)
                    .put(
                        "hint",
                        "Android USB permission is required. The system 'Allow access to USB device' dialog must be accepted. " +
                            "If no dialog appears, bring the app to foreground and retry (Android may auto-deny requests from background). " +
                            "If it still auto-denies with no dialog, Android may have saved a default 'deny' for this USB device: " +
                            "open the app settings and clear defaults, then replug the device and retry."
                    )
            )
        }

        val conn = usbManager.openDevice(dev)
            ?: return jsonError(Response.Status.INTERNAL_ERROR, "usb_open_failed")

        val handle = java.util.UUID.randomUUID().toString()
        usbConnections[handle] = conn
        usbDevicesByHandle[handle] = dev
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("handle", handle)
                .put("device", usbDeviceToJson(dev))
        )
    }

    private fun handleUsbClose(payload: JSONObject): Response {
        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val serialClosed = closeSerialSessionsForUsbHandle(handle)
        val conn = usbConnections.remove(handle)
        usbDevicesByHandle.remove(handle)
        try {
            conn?.close()
        } catch (_: Exception) {
        }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("closed", conn != null)
                .put("serial_sessions_closed", serialClosed)
        )
    }

    private fun handleUsbControlTransfer(payload: JSONObject): Response {
        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")

        val requestType = payload.optInt("request_type", -1)
        val request = payload.optInt("request", -1)
        val value = payload.optInt("value", 0)
        val index = payload.optInt("index", 0)
        val timeout = payload.optInt("timeout_ms", 2000).coerceIn(0, 60000)
        if (requestType < 0 || request < 0) return jsonError(Response.Status.BAD_REQUEST, "request_type_and_request_required")

        val directionIn = (requestType and 0x80) != 0
        val b64 = payload.optString("data_b64", "")
        val length = payload.optInt("length", if (directionIn) 256 else 0).coerceIn(0, 16384)

        val buf: ByteArray? = if (directionIn) {
            ByteArray(length)
        } else {
            if (b64.isNotBlank()) android.util.Base64.decode(b64, android.util.Base64.DEFAULT) else ByteArray(0)
        }

        val transferred = conn.controlTransfer(
            requestType,
            request,
            value,
            index,
            buf,
            if (directionIn) length else (buf?.size ?: 0),
            timeout
        )

        if (transferred < 0) {
            return jsonError(Response.Status.INTERNAL_ERROR, "control_transfer_failed")
        }

        val out = JSONObject()
            .put("status", "ok")
            .put("transferred", transferred)

        if (directionIn && buf != null) {
            val slice = buf.copyOfRange(0, transferred.coerceIn(0, buf.size))
            out.put("data_b64", android.util.Base64.encodeToString(slice, android.util.Base64.NO_WRAP))
        }
        return jsonResponse(out)
    }

    private fun handleUsbRawDescriptors(payload: JSONObject): Response {
        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val raw = conn.rawDescriptors
            ?: return jsonError(Response.Status.INTERNAL_ERROR, "raw_descriptors_unavailable")
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("data_b64", android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP))
                .put("length", raw.size)
        )
    }

    private fun handleUsbClaimInterface(payload: JSONObject): Response {
        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val dev = usbDevicesByHandle[handle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")
        val id = payload.optInt("interface_id", -1)
        if (id < 0) return jsonError(Response.Status.BAD_REQUEST, "interface_id_required")
        val force = payload.optBoolean("force", true)
        val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == id }
            ?: return jsonError(Response.Status.NOT_FOUND, "interface_not_found")
        val ok = conn.claimInterface(intf, force)
        return jsonResponse(JSONObject().put("status", "ok").put("claimed", ok).put("interface_id", id))
    }

    private fun handleUsbReleaseInterface(payload: JSONObject): Response {
        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val dev = usbDevicesByHandle[handle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")
        val id = payload.optInt("interface_id", -1)
        if (id < 0) return jsonError(Response.Status.BAD_REQUEST, "interface_id_required")
        val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == id }
            ?: return jsonError(Response.Status.NOT_FOUND, "interface_not_found")
        runCatching { conn.releaseInterface(intf) }
        return jsonResponse(JSONObject().put("status", "ok").put("interface_id", id))
    }

    private fun handleUsbBulkTransfer(payload: JSONObject): Response {
        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val dev = usbDevicesByHandle[handle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")
        val epAddr = payload.optInt("endpoint_address", -1)
        if (epAddr < 0) return jsonError(Response.Status.BAD_REQUEST, "endpoint_address_required")
        val timeout = payload.optInt("timeout_ms", 2000).coerceIn(0, 60000)

        // Find the endpoint by address across all interfaces.
        var foundEp: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.address == epAddr) {
                    foundEp = ep
                    break
                }
            }
            if (foundEp != null) break
        }
        val ep = foundEp ?: return jsonError(Response.Status.NOT_FOUND, "endpoint_not_found")

        val directionIn = (epAddr and 0x80) != 0
        if (directionIn) {
            val length = payload.optInt("length", 512).coerceIn(0, 1024 * 1024)
            val buf = ByteArray(length)
            val n = conn.bulkTransfer(ep, buf, buf.size, timeout)
            if (n < 0) return jsonError(Response.Status.INTERNAL_ERROR, "bulk_transfer_failed")
            val slice = buf.copyOfRange(0, n.coerceIn(0, buf.size))
            return jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("transferred", n)
                    .put("data_b64", android.util.Base64.encodeToString(slice, android.util.Base64.NO_WRAP))
            )
        } else {
            val b64 = payload.optString("data_b64", "")
            val data = if (b64.isNotBlank()) android.util.Base64.decode(b64, android.util.Base64.DEFAULT) else ByteArray(0)
            val n = conn.bulkTransfer(ep, data, data.size, timeout)
            if (n < 0) return jsonError(Response.Status.INTERNAL_ERROR, "bulk_transfer_failed")
            return jsonResponse(JSONObject().put("status", "ok").put("transferred", n))
        }
    }

    private fun handleUsbIsoTransfer(payload: JSONObject): Response {
        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val dev = usbDevicesByHandle[handle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")

        val epAddr = payload.optInt("endpoint_address", -1)
        if (epAddr < 0) return jsonError(Response.Status.BAD_REQUEST, "endpoint_address_required")

        val interfaceId = payload.optInt("interface_id", -1)
        val altSetting = if (payload.has("alt_setting")) payload.optInt("alt_setting", -1) else null
        val packetSize = payload.optInt("packet_size", 1024).coerceIn(1, 1024 * 1024)
        val numPackets = payload.optInt("num_packets", 32).coerceIn(1, 1024)
        val timeout = payload.optInt("timeout_ms", 800).coerceIn(1, 60000)

        // Choose an interface/alt setting to match the endpoint.
        val candidates = (0 until dev.interfaceCount).map { dev.getInterface(it) }
        val chosen = candidates.firstOrNull { intf ->
            if (interfaceId >= 0 && intf.id != interfaceId) return@firstOrNull false
            if (altSetting != null && altSetting >= 0 && intf.alternateSetting != altSetting) return@firstOrNull false
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.address == epAddr) return@firstOrNull true
            }
            false
        } ?: return jsonError(Response.Status.NOT_FOUND, "interface_or_endpoint_not_found")

        // Require that the endpoint is ISO.
        val isoEp = (0 until chosen.endpointCount).map { chosen.getEndpoint(it) }.firstOrNull { it.address == epAddr }
            ?: return jsonError(Response.Status.NOT_FOUND, "endpoint_not_found")
        if (isoEp.type != UsbConstants.USB_ENDPOINT_XFER_ISOC) {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "endpoint_not_isochronous",
                JSONObject()
                    .put("endpoint_type", isoEp.type)
                    .put("expected", UsbConstants.USB_ENDPOINT_XFER_ISOC)
            )
        }

        // Claim + switch alternate setting.
        val force = payload.optBoolean("force", true)
        val claimed = conn.claimInterface(chosen, force)
        if (!claimed) {
            return jsonError(Response.Status.INTERNAL_ERROR, "claim_interface_failed")
        }
        runCatching { conn.setInterface(chosen) }

        UsbIsoBridge.ensureLoaded()
        val fd = conn.fileDescriptor
        if (fd < 0) return jsonError(Response.Status.INTERNAL_ERROR, "file_descriptor_unavailable")

        val blob: ByteArray = try {
            UsbIsoBridge.isochIn(fd, epAddr, packetSize, numPackets, timeout)
                ?: return jsonError(Response.Status.INTERNAL_ERROR, "iso_transfer_failed")
        } catch (ex: Exception) {
            Log.e(TAG, "isochIn failed", ex)
            return jsonError(Response.Status.INTERNAL_ERROR, "iso_transfer_exception")
        }

        // Parse KISO blob.
        if (blob.size < 12) return jsonError(Response.Status.INTERNAL_ERROR, "iso_blob_too_small")
        fun u32le(off: Int): Long {
            return (blob[off].toLong() and 0xFF) or
                ((blob[off + 1].toLong() and 0xFF) shl 8) or
                ((blob[off + 2].toLong() and 0xFF) shl 16) or
                ((blob[off + 3].toLong() and 0xFF) shl 24)
        }
        fun i32le(off: Int): Int {
            return (u32le(off).toInt())
        }

        val magic = u32le(0).toInt()
        if (magic != 0x4F53494B) return jsonError(Response.Status.INTERNAL_ERROR, "iso_bad_magic")
        val nPk = u32le(4).toInt().coerceIn(0, 1024)
        val payloadLen = u32le(8).toInt().coerceIn(0, 32 * 1024 * 1024)
        val metaLen = 12 + nPk * 8
        if (blob.size < metaLen) return jsonError(Response.Status.INTERNAL_ERROR, "iso_blob_meta_truncated")
        val expectedTotal = metaLen + payloadLen
        if (blob.size < expectedTotal) return jsonError(Response.Status.INTERNAL_ERROR, "iso_blob_payload_truncated")

        val packets = org.json.JSONArray()
        var metaOff = 12
        for (i in 0 until nPk) {
            val st = i32le(metaOff)
            val al = i32le(metaOff + 4)
            packets.put(JSONObject().put("status", st).put("actual_length", al))
            metaOff += 8
        }
        val payloadBytes = blob.copyOfRange(metaLen, metaLen + payloadLen)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("handle", handle)
                .put("interface_id", chosen.id)
                .put("alt_setting", chosen.alternateSetting)
                .put("endpoint_address", epAddr)
                .put("packet_size", packetSize)
                .put("num_packets", nPk)
                .put("payload_length", payloadLen)
                .put("packets", packets)
                .put("data_b64", android.util.Base64.encodeToString(payloadBytes, android.util.Base64.NO_WRAP))
        )
    }

    private data class UsbStreamClient(
        val socket: Socket,
        val out: java.io.BufferedOutputStream,
    )

    private data class SerialSessionState(
        val id: String,
        val usbHandle: String,
        val deviceName: String,
        val portIndex: Int,
        val baudRate: Int,
        val dataBits: Int,
        val stopBits: Int,
        val parity: Int,
        val connection: UsbDeviceConnection,
        val port: UsbSerialPort,
        val openedAtMs: Long,
        val lock: Any = Any(),
    )

    private data class UsbStreamState(
        val id: String,
        val mode: String,
        val handle: String,
        val endpointAddress: Int,
        val interfaceId: Int,
        val altSetting: Int,
        val tcpPort: Int,
        val serverSocket: ServerSocket,
        val stop: java.util.concurrent.atomic.AtomicBoolean,
        val clients: CopyOnWriteArrayList<UsbStreamClient>,
        val wsClients: CopyOnWriteArrayList<NanoWSD.WebSocket>,
        val acceptThread: Thread,
        val ioThread: Thread,
    )

    private fun writeFrameHeader(out: java.io.OutputStream, type: Int, length: Int) {
        out.write(type and 0xFF)
        // u32 little-endian length
        out.write(length and 0xFF)
        out.write((length ushr 8) and 0xFF)
        out.write((length ushr 16) and 0xFF)
        out.write((length ushr 24) and 0xFF)
    }

    private fun broadcastUsbStream(state: UsbStreamState, type: Int, payload: ByteArray) {
        // TCP clients: [u8 type][u32le length][payload]
        val dead = ArrayList<UsbStreamClient>()
        for (c in state.clients) {
            try {
                writeFrameHeader(c.out, type, payload.size)
                c.out.write(payload)
                c.out.flush()
            } catch (_: Exception) {
                dead.add(c)
            }
        }
        for (c in dead) {
            state.clients.remove(c)
            runCatching { c.socket.close() }
        }

        // WS clients: one binary message: [u8 type] + payload
        val wsDead = ArrayList<NanoWSD.WebSocket>()
        val msg = ByteArray(1 + payload.size)
        msg[0] = (type and 0xFF).toByte()
        java.lang.System.arraycopy(payload, 0, msg, 1, payload.size)
        for (ws in state.wsClients) {
            try {
                if (ws.isOpen) {
                    ws.send(msg)
                } else {
                    wsDead.add(ws)
                }
            } catch (_: Exception) {
                wsDead.add(ws)
            }
        }
        for (ws in wsDead) {
            state.wsClients.remove(ws)
        }
    }

    private fun handleUsbStreamStart(payload: JSONObject): Response {
        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val dev = usbDevicesByHandle[handle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")

        val mode = payload.optString("mode", "bulk_in").trim().ifBlank { "bulk_in" }
        val epAddr = payload.optInt("endpoint_address", -1)
        if (epAddr < 0) return jsonError(Response.Status.BAD_REQUEST, "endpoint_address_required")

        // Find interface/endpoint.
        var chosenIntf: android.hardware.usb.UsbInterface? = null
        var chosenEp: android.hardware.usb.UsbEndpoint? = null
        loop@ for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.address == epAddr) {
                    chosenIntf = intf
                    chosenEp = ep
                    break@loop
                }
            }
        }
        val intf = chosenIntf ?: return jsonError(Response.Status.NOT_FOUND, "interface_not_found_for_endpoint")
        val ep = chosenEp ?: return jsonError(Response.Status.NOT_FOUND, "endpoint_not_found")

        // Basic validation.
        val isIn = (epAddr and 0x80) != 0
        if (!isIn) return jsonError(Response.Status.BAD_REQUEST, "endpoint_must_be_in")
        if (mode == "bulk_in" && ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) {
            return jsonError(Response.Status.BAD_REQUEST, "endpoint_not_bulk")
        }
        if (mode == "iso_in" && ep.type != UsbConstants.USB_ENDPOINT_XFER_ISOC) {
            return jsonError(Response.Status.BAD_REQUEST, "endpoint_not_isochronous")
        }

        // Claim + set interface (lets caller pick an alternate setting by passing interface_id+alt_setting).
        // Note: UsbInterface in Android includes alternateSetting; the endpoint match above already selects it.
        val force = payload.optBoolean("force", true)
        val claimed = conn.claimInterface(intf, force)
        if (!claimed) return jsonError(Response.Status.INTERNAL_ERROR, "claim_interface_failed")
        runCatching { conn.setInterface(intf) }

        val id = java.util.UUID.randomUUID().toString()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val clients = CopyOnWriteArrayList<UsbStreamClient>()
        val wsClients = CopyOnWriteArrayList<NanoWSD.WebSocket>()

        val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket.soTimeout = 600
        val port = serverSocket.localPort

        val acceptThread = Thread {
            while (!stop.get()) {
                try {
                    val s = serverSocket.accept()
                    s.tcpNoDelay = true
                    s.soTimeout = 0
                    val out = java.io.BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                    clients.add(UsbStreamClient(s, out))
                } catch (_: java.net.SocketTimeoutException) {
                    // loop
                } catch (_: Exception) {
                    if (!stop.get()) {
                        // Something went wrong; stop accepting.
                        stop.set(true)
                    }
                }
            }
        }.also { it.name = "usb-stream-accept-$id" }

        val ioThread = Thread {
            val timeout = payload.optInt("timeout_ms", 200).coerceIn(1, 60000)
            val chunkSize = payload.optInt("chunk_size", 16 * 1024).coerceIn(1, 1024 * 1024)
            val intervalMs = payload.optInt("interval_ms", 0).coerceIn(0, 2000)

            val fd = conn.fileDescriptor
            val isoPacketSize = payload.optInt("packet_size", 1024).coerceIn(1, 1024 * 1024)
            val isoNumPackets = payload.optInt("num_packets", 32).coerceIn(1, 1024)

            UsbIsoBridge.ensureLoaded()

            val buf = ByteArray(chunkSize)
            while (!stop.get()) {
                try {
                    if (mode == "bulk_in") {
                        val n = conn.bulkTransfer(ep, buf, buf.size, timeout)
                        if (n > 0) {
                            broadcastUsbStream(
                                usbStreams[id] ?: break,
                                1,
                                buf.copyOfRange(0, n.coerceIn(0, buf.size))
                            )
                        }
                    } else if (mode == "iso_in") {
                        if (fd < 0) break
                        val blob = UsbIsoBridge.isochIn(fd, epAddr, isoPacketSize, isoNumPackets, timeout) ?: break
                        // Send the raw KISO blob; clients can parse status/length fields if they need them.
                        broadcastUsbStream(usbStreams[id] ?: break, 2, blob)
                    } else {
                        break
                    }
                    if (intervalMs > 0) {
                        Thread.sleep(intervalMs.toLong())
                    }
                } catch (_: Exception) {
                    // If transfers start failing, stop the stream.
                    stop.set(true)
                }
            }
        }.also { it.name = "usb-stream-io-$id" }

        val state = UsbStreamState(
            id = id,
            mode = mode,
            handle = handle,
            endpointAddress = epAddr,
            interfaceId = intf.id,
            altSetting = intf.alternateSetting,
            tcpPort = port,
            serverSocket = serverSocket,
            stop = stop,
            clients = clients,
            wsClients = wsClients,
            acceptThread = acceptThread,
            ioThread = ioThread
        )
        usbStreams[id] = state

        acceptThread.start()
        ioThread.start()

        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("stream_id", id)
                .put("mode", mode)
                .put("handle", handle)
                .put("endpoint_address", epAddr)
                .put("interface_id", intf.id)
                .put("alt_setting", intf.alternateSetting)
                .put("tcp_host", "127.0.0.1")
                .put("tcp_port", port)
                .put("ws_path", "/ws/usb/stream/$id")
        )
    }

    private fun handleUsbStreamStop(payload: JSONObject): Response {
        val id = payload.optString("stream_id", "").trim()
        if (id.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "stream_id_required")
        val st = usbStreams.remove(id) ?: return jsonError(Response.Status.NOT_FOUND, "stream_not_found")
        st.stop.set(true)
        runCatching { st.serverSocket.close() }
        runCatching { st.acceptThread.join(800) }
        runCatching { st.ioThread.join(800) }
        for (c in st.clients) {
            runCatching { c.socket.close() }
        }
        st.clients.clear()
        st.wsClients.clear()
        return jsonResponse(JSONObject().put("status", "ok").put("stopped", true))
    }

    private fun handleUsbStreamStatus(): Response {
        val arr = org.json.JSONArray()
        usbStreams.values.sortedBy { it.id }.forEach { st ->
            arr.put(
                JSONObject()
                    .put("stream_id", st.id)
                    .put("mode", st.mode)
                    .put("handle", st.handle)
                    .put("endpoint_address", st.endpointAddress)
                    .put("interface_id", st.interfaceId)
                    .put("alt_setting", st.altSetting)
                    .put("tcp_host", "127.0.0.1")
                    .put("tcp_port", st.tcpPort)
                    .put("ws_path", "/ws/usb/stream/${st.id}")
                    .put("clients_tcp", st.clients.size)
                    .put("clients_ws", st.wsClients.size)
            )
        }
        return jsonResponse(JSONObject().put("status", "ok").put("items", arr))
    }

    private data class UvcMjpegFrame(
        val vsInterface: Int,
        val formatIndex: Int,
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        val defaultInterval100ns: Long,
        val intervals100ns: List<Long>,
    )

    private fun parseUvcMjpegFrames(raw: ByteArray): List<UvcMjpegFrame> {
        // Parse a minimal subset of UVC VideoStreaming descriptors out of raw config descriptors.
        // We only need MJPEG formats/frames to construct a VS_PROBE_CONTROL for streaming.
        val out = ArrayList<UvcMjpegFrame>()
        var curVs: Int? = null
        var curFormatIndex = 0
        var i = 0
        while (i + 2 < raw.size) {
            val dlen = raw[i].toInt() and 0xFF
            if (dlen <= 0) break
            if (i + dlen > raw.size) break
            val dtype = raw[i + 1].toInt() and 0xFF
            if (dtype == 0x04 && dlen >= 9) {
                val ifNum = raw[i + 2].toInt() and 0xFF
                val ifClass = raw[i + 5].toInt() and 0xFF
                val ifSub = raw[i + 6].toInt() and 0xFF
                curVs = if (ifClass == 0x0E && ifSub == 0x02) ifNum else null
                curFormatIndex = 0
            } else if (dtype == 0x24 && curVs != null && dlen >= 3) {
                val subtype = raw[i + 2].toInt() and 0xFF
                if (subtype == 0x06 && dlen >= 11) {
                    // VS_FORMAT_MJPEG: bFormatIndex at +3.
                    curFormatIndex = raw[i + 3].toInt() and 0xFF
                } else if (subtype == 0x07 && dlen >= 26 && curFormatIndex != 0) {
                    // VS_FRAME_MJPEG
                    val frameIndex = raw[i + 3].toInt() and 0xFF
                    val w = (raw[i + 5].toInt() and 0xFF) or ((raw[i + 6].toInt() and 0xFF) shl 8)
                    val h = (raw[i + 7].toInt() and 0xFF) or ((raw[i + 8].toInt() and 0xFF) shl 8)
                    fun u32(off: Int): Long {
                        return (raw[off].toLong() and 0xFF) or
                            ((raw[off + 1].toLong() and 0xFF) shl 8) or
                            ((raw[off + 2].toLong() and 0xFF) shl 16) or
                            ((raw[off + 3].toLong() and 0xFF) shl 24)
                    }
                    val defaultInterval = u32(i + 21)
                    val intervalType = raw[i + 25].toInt() and 0xFF
                    val intervals = ArrayList<Long>()
                    if (intervalType == 0) {
                        // Continuous: dwMin/dwMax/dwStep exist if descriptor is long enough.
                        if (dlen >= 38) {
                            val minInt = u32(i + 26)
                            val maxInt = u32(i + 30)
                            val step = u32(i + 34).coerceAtLeast(1)
                            var v = minInt
                            var guard = 0
                            while (v <= maxInt && guard++ < 64) {
                                intervals.add(v)
                                v += step
                            }
                        }
                    } else {
                        // Discrete list: intervalType entries of 4 bytes.
                        var off = i + 26
                        var n = intervalType
                        while (n > 0 && off + 4 <= i + dlen) {
                            intervals.add(u32(off))
                            off += 4
                            n--
                        }
                    }
                    out.add(
                        UvcMjpegFrame(
                            vsInterface = curVs,
                            formatIndex = curFormatIndex,
                            frameIndex = frameIndex,
                            width = w,
                            height = h,
                            defaultInterval100ns = defaultInterval,
                            intervals100ns = intervals,
                        )
                    )
                }
            }
            i += dlen
        }
        return out
    }

    private fun pickBestUvcFrame(frames: List<UvcMjpegFrame>, width: Int, height: Int): UvcMjpegFrame? {
        if (frames.isEmpty()) return null
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val targetArea = w.toLong() * h.toLong()
        fun score(f: UvcMjpegFrame): Long {
            val a = f.width.toLong() * f.height.toLong()
            return kotlin.math.abs(a - targetArea) + (kotlin.math.abs(f.width - w) + kotlin.math.abs(f.height - h)).toLong() * 2000L
        }
        return frames.minByOrNull { score(it) }
    }

    private fun pickBestInterval(frame: UvcMjpegFrame, fps: Int): Long {
        val f = fps.coerceIn(1, 120)
        val desired = 10_000_000L / f.toLong()
        val list = frame.intervals100ns
        if (list.isEmpty()) return frame.defaultInterval100ns.takeIf { it > 0 } ?: desired
        return list.minByOrNull { kotlin.math.abs(it - desired) } ?: desired
    }

    private fun uvcGetLen(conn: UsbDeviceConnection, vsInterface: Int, controlSelector: Int): Int? {
        val buf = ByteArray(2)
        val rc = conn.controlTransfer(
            0xA1, // IN | Class | Interface
            0x85, // GET_LEN
            (controlSelector and 0xFF) shl 8,
            vsInterface and 0xFF,
            buf,
            buf.size,
            300
        )
        if (rc < 2) return null
        val len = (buf[0].toInt() and 0xFF) or ((buf[1].toInt() and 0xFF) shl 8)
        return len.takeIf { it in 8..64 }
    }

    private fun putU16le(b: ByteArray, off: Int, v: Int) {
        if (off + 2 > b.size) return
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putU32le(b: ByteArray, off: Int, v: Long) {
        if (off + 4 > b.size) return
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private data class UvcXuUnit(val unitId: Int, val guidHex: String, val bmControls: ByteArray)

    private fun parseUvcExtensionUnitsFromRaw(raw: ByteArray): List<UvcXuUnit> {
        val out = mutableListOf<UvcXuUnit>()
        var i = 0
        while (i + 2 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len <= 0 || i + len > raw.size) break
            val dtype = raw[i + 1].toInt() and 0xFF
            val subtype = if (len >= 3) (raw[i + 2].toInt() and 0xFF) else -1
            // CS_INTERFACE (0x24), VC_EXTENSION_UNIT (0x06)
            if (dtype == 0x24 && subtype == 0x06 && len >= 24) {
                val unitId = raw[i + 3].toInt() and 0xFF
                val guidBytes = raw.copyOfRange(i + 4, i + 20)
                val guidHex = guidBytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                val numPins = raw[i + 21].toInt() and 0xFF
                val ctrlSizeIndex = i + 22 + numPins
                val ctrlSize = if (ctrlSizeIndex < i + len) (raw[ctrlSizeIndex].toInt() and 0xFF) else 0
                val ctrlStart = ctrlSizeIndex + 1
                val ctrlEnd = (ctrlStart + ctrlSize).coerceAtMost(i + len)
                val bm = if (ctrlStart < ctrlEnd) raw.copyOfRange(ctrlStart, ctrlEnd) else ByteArray(0)
                out.add(UvcXuUnit(unitId = unitId, guidHex = guidHex, bmControls = bm))
            }
            i += len
        }
        return out
    }

    private fun findUvcVideoControlInterface(dev: UsbDevice): Int? {
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.interfaceClass == 0x0E && intf.interfaceSubclass == 0x01) {
                return intf.id
            }
        }
        return null
    }

    private fun findUvcCameraTerminalIds(raw: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i + 2 < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len <= 0 || i + len > raw.size) break
            val dtype = raw[i + 1].toInt() and 0xFF
            val subtype = if (len >= 3) (raw[i + 2].toInt() and 0xFF) else -1
            // CS_INTERFACE (0x24), VC_INPUT_TERMINAL (0x02)
            if (dtype == 0x24 && subtype == 0x02 && len >= 8) {
                val terminalId = raw[i + 3].toInt() and 0xFF
                val wTerminalType = (raw[i + 4].toInt() and 0xFF) or ((raw[i + 5].toInt() and 0xFF) shl 8)
                if (wTerminalType == 0x0201) {
                    out.add(terminalId)
                }
            }
            i += len
        }
        return out.distinct()
    }

    private fun handleUvcDiagnose(session: IHTTPSession, payload: JSONObject): Response {
        val steps = org.json.JSONArray()
        fun step(name: String, ok: Boolean, detail: JSONObject? = null) {
            val o = JSONObject().put("name", name).put("ok", ok)
            if (detail != null) o.put("detail", detail)
            steps.put(o)
        }

        val perm = ensureDevicePermission(
            session = session,
            payload = payload,
            tool = "device.usb",
            capability = "usb",
            detail = "UVC diagnose (step-by-step USB/UVC check)"
        )
        if (!perm.first) return perm.second!!

        val vid = payload.optInt("vendor_id", -1)
        val pid = payload.optInt("product_id", -1)
        val deviceName = payload.optString("device_name", "").trim().ifBlank { payload.optString("name", "").trim() }
        val timeoutMs = payload.optLong("timeout_ms", 60000L).coerceIn(3000L, 120000L)
        val doPtz = payload.optBoolean("ptz_get_cur", true)
        val ptzSelector = payload.optInt("ptz_selector", 0x0D).coerceIn(0, 255)

        val all = usbManager.deviceList.values.toList()
        step(
            "usb.list",
            true,
            JSONObject()
                .put("count", all.size)
                .put("devices", org.json.JSONArray().apply { all.forEach { put(usbDeviceToJson(it)) } })
        )

        val dev = all.firstOrNull { d ->
            when {
                deviceName.isNotBlank() -> d.deviceName == deviceName
                vid >= 0 && pid >= 0 -> d.vendorId == vid && d.productId == pid
                else -> false
            }
        }
        if (dev == null) {
            step("usb.pick_device", false, JSONObject().put("error", "device_not_found"))
            return jsonError(Response.Status.NOT_FOUND, "device_not_found", JSONObject().put("steps", steps))
        }
        step("usb.pick_device", true, usbDeviceToJson(dev))

        if (!ensureUsbPermission(dev, timeoutMs)) {
            step("usb.os_permission", false, JSONObject().put("error", "usb_permission_required"))
            return jsonError(
                Response.Status.FORBIDDEN,
                "usb_permission_required",
                JSONObject()
                    .put("steps", steps)
                    .put(
                        "hint",
                        "Android USB permission is required. Accept the system 'Allow access to USB device' dialog. " +
                            "If no dialog appears, bring me.things to foreground and retry. " +
                            "If it still auto-denies with no dialog, clear app defaults in Android settings, then replug and retry."
                    )
            )
        }
        step("usb.os_permission", true, JSONObject().put("granted", true))

        val conn = usbManager.openDevice(dev)
        if (conn == null) {
            step("usb.open", false, JSONObject().put("error", "usb_open_failed"))
            return jsonError(Response.Status.INTERNAL_ERROR, "usb_open_failed", JSONObject().put("steps", steps))
        }
        step("usb.open", true)

        try {
            val raw = conn.rawDescriptors
            if (raw == null || raw.isEmpty()) {
                step("usb.raw_descriptors", false, JSONObject().put("error", "raw_descriptors_unavailable"))
                return jsonError(Response.Status.INTERNAL_ERROR, "raw_descriptors_unavailable", JSONObject().put("steps", steps))
            }
            step("usb.raw_descriptors", true, JSONObject().put("length", raw.size))

            val vcByIntf = findUvcVideoControlInterface(dev)
            val ctIds = findUvcCameraTerminalIds(raw)
            val xuUnits = parseUvcExtensionUnitsFromRaw(raw)

            step(
                "uvc.parse",
                true,
                JSONObject()
                    .put("vc_interface", vcByIntf ?: JSONObject.NULL)
                    .put("camera_terminal_ids", org.json.JSONArray().apply { ctIds.forEach { put(it) } })
                    .put(
                        "xu_units",
                        org.json.JSONArray().apply {
                            xuUnits.forEach { u ->
                                val bmHex = u.bmControls.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                                put(JSONObject().put("unit_id", u.unitId).put("guid", u.guidHex).put("bm_controls_hex", bmHex))
                            }
                        }
                    )
            )

            val vc = vcByIntf ?: 0
            val vcIntf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == vc }
            if (vcIntf != null) {
                val claimed = conn.claimInterface(vcIntf, true)
                step("usb.claim_vc_interface", claimed, JSONObject().put("interface_id", vc))
            } else {
                step("usb.claim_vc_interface", false, JSONObject().put("error", "vc_interface_not_found").put("interface_id", vc))
            }

            val out = JSONObject()
                .put("status", "ok")
                .put("device", usbDeviceToJson(dev))
                .put("vc_interface", vcByIntf ?: JSONObject.NULL)
                .put("camera_terminal_ids", org.json.JSONArray().apply { ctIds.forEach { put(it) } })
                .put("steps", steps)

            if (doPtz) {
                val entity = (ctIds.firstOrNull() ?: 1).coerceIn(0, 255)
                val selector = (ptzSelector and 0xFF)
                val wValue = (selector shl 8) and 0xFF00
                val buf = ByteArray(8)
                fun probe(label: String, wIndex: Int): JSONObject {
                    val rc = conn.controlTransfer(
                        0xA1, // IN | Class | Interface
                        0x81, // GET_CUR
                        wValue,
                        wIndex,
                        buf,
                        buf.size,
                        350
                    )
                    val o = JSONObject().put("label", label).put("wIndex", wIndex).put("rc", rc)
                    if (rc >= 8) {
                        val pan = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                        val tilt = java.nio.ByteBuffer.wrap(buf, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                        o.put("pan_abs", pan).put("tilt_abs", tilt)
                        o.put("data_hex", buf.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
                    }
                    return o
                }

                val probes = org.json.JSONArray()
                // Common guesses:
                probes.put(probe("entity<<8|vc", ((entity and 0xFF) shl 8) or (vc and 0xFF)))
                probes.put(probe("vc<<8|entity", ((vc and 0xFF) shl 8) or (entity and 0xFF)))
                // Known-good for Insta360 Link based on linux capture.
                probes.put(probe("fixed_0x0100", 0x0100))
                out.put("ptz_get_cur", probes)
            }

            return jsonResponse(out)
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun handleUvcMjpegCapture(payload: JSONObject): Response {
        val handle = payload.optString("handle", "").trim()
        if (handle.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "handle_required")
        val conn = usbConnections[handle] ?: return jsonError(Response.Status.NOT_FOUND, "handle_not_found")
        val dev = usbDevicesByHandle[handle] ?: return jsonError(Response.Status.NOT_FOUND, "device_not_found")

        val widthReq = payload.optInt("width", 1280).coerceIn(1, 8192)
        val heightReq = payload.optInt("height", 720).coerceIn(1, 8192)
        val fpsReq = payload.optInt("fps", 30).coerceIn(1, 120)
        val timeoutMs = payload.optLong("timeout_ms", 12000L).coerceIn(1500L, 60000L)
        val maxFrameBytes = payload.optInt("max_frame_bytes", 6 * 1024 * 1024).coerceIn(64 * 1024, 40 * 1024 * 1024)

        // Output path under user root.
        val userRoot = File(context.filesDir, "user").also { it.mkdirs() }
        File(userRoot, "captures").also { it.mkdirs() }
        val relPath = payload.optString("path", "").trim().ifBlank {
            "captures/uvc_${System.currentTimeMillis()}.jpg"
        }
        val outFile = resolveUserPath(userRoot, relPath) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
        outFile.parentFile?.mkdirs()

        // Parse MJPEG formats/frames and pick a reasonable configuration.
        val raw = conn.rawDescriptors ?: return jsonError(Response.Status.INTERNAL_ERROR, "raw_descriptors_unavailable")
        val frames = parseUvcMjpegFrames(raw)
        val bestFrame = pickBestUvcFrame(frames, widthReq, heightReq)
            ?: return jsonError(Response.Status.INTERNAL_ERROR, "uvc_mjpeg_frames_not_found")

        val vsInterface = bestFrame.vsInterface
        val interval = pickBestInterval(bestFrame, fpsReq)

        // Choose a streaming IN endpoint on the VS interface.
        // Many UVC webcams expose ISO IN, but some (including Insta360 Link) expose only BULK IN.
        data class EpPick(val intf: UsbInterface, val ep: UsbEndpoint)
        var isoPick: EpPick? = null
        var bulkPick: EpPick? = null
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.id != vsInterface) continue
            if (intf.interfaceClass != 0x0E || intf.interfaceSubclass != 0x02) continue
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                val isIn = (ep.address and 0x80) != 0
                if (!isIn) continue
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                    val cur = isoPick
                    if (cur == null || ep.maxPacketSize > cur.ep.maxPacketSize) {
                        isoPick = EpPick(intf, ep)
                    }
                } else if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    val cur = bulkPick
                    if (cur == null || ep.maxPacketSize > cur.ep.maxPacketSize) {
                        bulkPick = EpPick(intf, ep)
                    }
                }
            }
        }
        val chosen = isoPick ?: bulkPick ?: return jsonError(Response.Status.INTERNAL_ERROR, "uvc_stream_endpoint_not_found")
        val transferMode = if (chosen.ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) "iso" else "bulk"

        // Claim + select alternate setting.
        val claimed = conn.claimInterface(chosen.intf, true)
        if (!claimed) return jsonError(Response.Status.INTERNAL_ERROR, "claim_interface_failed")
        runCatching { conn.setInterface(chosen.intf) }

        // Perform a minimal UVC Probe/Commit for MJPEG.
        val probeLen = uvcGetLen(conn, vsInterface, 0x01) ?: 34
        val probe = ByteArray(probeLen)
        // bmHint: set dwFrameInterval
        putU16le(probe, 0, 0x0001)
        // bFormatIndex / bFrameIndex
        if (probe.size >= 4) {
            probe[2] = (bestFrame.formatIndex and 0xFF).toByte()
            probe[3] = (bestFrame.frameIndex and 0xFF).toByte()
        }
        // dwFrameInterval
        putU32le(probe, 4, interval)

        fun ctrlOut(controlSelector: Int, data: ByteArray): Int {
            return conn.controlTransfer(
                0x21, // OUT | Class | Interface
                0x01, // SET_CUR
                (controlSelector and 0xFF) shl 8,
                vsInterface and 0xFF,
                data,
                data.size,
                600
            )
        }
        fun ctrlIn(controlSelector: Int, len: Int): Pair<Int, ByteArray> {
            val buf = ByteArray(len.coerceIn(1, 64))
            val rc = conn.controlTransfer(
                0xA1, // IN | Class | Interface
                0x81, // GET_CUR
                (controlSelector and 0xFF) shl 8,
                vsInterface and 0xFF,
                buf,
                buf.size,
                600
            )
            val out = if (rc > 0) buf.copyOfRange(0, rc.coerceIn(0, buf.size)) else ByteArray(0)
            return Pair(rc, out)
        }

        if (ctrlOut(0x01, probe) < 0) return jsonError(Response.Status.INTERNAL_ERROR, "uvc_probe_set_failed")
        val (probeRc, probeCur) = ctrlIn(0x01, probe.size)
        val commitData = if (probeRc > 0) {
            // Some cameras update fields in GET_CUR (e.g. max payload size). Use that for COMMIT.
            val b = ByteArray(probe.size)
            java.lang.System.arraycopy(probeCur, 0, b, 0, kotlin.math.min(probeCur.size, b.size))
            b
        } else {
            probe
        }
        if (ctrlOut(0x02, commitData) < 0) return jsonError(Response.Status.INTERNAL_ERROR, "uvc_commit_set_failed")
        val (_, commitCur) = ctrlIn(0x02, commitData.size)

        fun u32leFrom(arr: ByteArray, off: Int): Long? {
            if (off < 0 || off + 4 > arr.size) return null
            return (arr[off].toLong() and 0xFF) or
                ((arr[off + 1].toLong() and 0xFF) shl 8) or
                ((arr[off + 2].toLong() and 0xFF) shl 16) or
                ((arr[off + 3].toLong() and 0xFF) shl 24)
        }
        // UVC VS Probe/Commit control (UVC 1.1+): dwMaxPayloadTransferSize at offset 22.
        // Use COMMIT if available; fallback to PROBE.
        val maxPayloadFromCommit = u32leFrom(commitCur, 22) ?: u32leFrom(probeCur, 22)
        val negotiatedMaxPayloadTransferSize = (maxPayloadFromCommit ?: 0L).coerceAtLeast(0L)

        val epAddr = chosen.ep.address
        val deadline = System.currentTimeMillis() + timeoutMs
        val frame = java.io.ByteArrayOutputStream(1024 * 256)
        var started = false
        var lastFid = -1

        fun findSoi(bytes: ByteArray, off: Int, len: Int): Int {
            val end = (off + len - 1).coerceAtMost(bytes.size - 1)
            var j = off
            while (j + 1 <= end) {
                if ((bytes[j].toInt() and 0xFF) == 0xFF && (bytes[j + 1].toInt() and 0xFF) == 0xD8) return j
                j++
            }
            return -1
        }

        fun findEoi(bytes: ByteArray): Int {
            // JPEG EOI marker.
            for (i in bytes.size - 2 downTo 0) {
                if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xFF) == 0xD9) return i
            }
            return -1
        }

        fun tryFinalizeAndWrite(allowAppendEoi: Boolean): Response? {
            if (!(started && frame.size() >= 4)) return null
            val bytes = frame.toByteArray()
            val soi = findSoi(bytes, 0, bytes.size)
            if (soi < 0) return null
            val eoi = findEoi(bytes)
            val hasEoi = eoi >= 0 && eoi + 2 <= bytes.size
            val sliced = if (soi > 0) bytes.copyOfRange(soi, bytes.size) else bytes
            val final = if (hasEoi) {
                // Cut exactly at EOI.
                val cut = (eoi + 2 - soi).coerceIn(2, sliced.size)
                sliced.copyOfRange(0, cut)
            } else if (allowAppendEoi) {
                // Some MJPEG sources omit EOI; appending makes many viewers accept it.
                val out = ByteArray(sliced.size + 2)
                java.lang.System.arraycopy(sliced, 0, out, 0, sliced.size)
                out[out.size - 2] = 0xFF.toByte()
                out[out.size - 1] = 0xD9.toByte()
                out
            } else {
                return null
            }
            return try {
                java.io.FileOutputStream(outFile).use { it.write(final) }
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("rel_path", relPath)
                        .put("bytes", final.size)
                        .put("transfer_mode", transferMode)
                        .put("jpeg_has_eoi", hasEoi)
                        .put("jpeg_eoi_appended", (!hasEoi && allowAppendEoi))
                        .put("vs_interface", vsInterface)
                        .put("format_index", bestFrame.formatIndex)
                        .put("frame_index", bestFrame.frameIndex)
                        .put("width", bestFrame.width)
                        .put("height", bestFrame.height)
                        .put("interval_100ns", interval)
                        .put("endpoint_address", epAddr)
                        .put("endpoint_type", chosen.ep.type)
                        .put("interface_id", chosen.intf.id)
                        .put("alt_setting", chosen.intf.alternateSetting)
                )
            } catch (ex: Exception) {
                jsonError(Response.Status.INTERNAL_ERROR, "write_failed", JSONObject().put("detail", ex.message ?: ""))
            }
        }

        fun processUvcPayload(buf: ByteArray, off: Int, len: Int): Response? {
            if (len < 4) return null
            val hlen = buf[off].toInt() and 0xFF
            if (hlen < 2 || hlen > len) return null
            val info = buf[off + 1].toInt() and 0xFF
            val fid = info and 0x01
            val eof = (info and 0x02) != 0
            val err = (info and 0x40) != 0
            val payloadOff = off + hlen
            val payloadCount = len - hlen
            if (err || payloadCount <= 0) return null

            if (lastFid >= 0 && fid != lastFid && frame.size() > 0 && !eof) {
                frame.reset()
                started = false
            }
            lastFid = fid

            if (!started) {
                val soi = findSoi(buf, payloadOff, payloadCount)
                if (soi >= 0) {
                    started = true
                    frame.write(buf, soi, payloadOff + payloadCount - soi)
                }
            } else {
                frame.write(buf, payloadOff, payloadCount)
            }

            if (frame.size() > maxFrameBytes) {
                frame.reset()
                started = false
            }

            if (eof) {
                // Prefer writing a complete JPEG (EOI present). If missing EOI, append it as a fallback.
                val r = tryFinalizeAndWrite(false) ?: tryFinalizeAndWrite(true)
                if (r != null) return r
                // If we still can't finalize, keep collecting until timeout or a new frame boundary.
            }
            return null
        }

        if (transferMode == "iso") {
            // Isochronous path: parse KISO blob and feed individual packets as UVC payloads.
            UsbIsoBridge.ensureLoaded()
            val fd = conn.fileDescriptor
            if (fd < 0) return jsonError(Response.Status.INTERNAL_ERROR, "file_descriptor_unavailable")
            val packetSize = chosen.ep.maxPacketSize.coerceAtLeast(256)
            val numPackets = payload.optInt("num_packets", 48).coerceIn(8, 512)
            val isoTimeout = payload.optInt("iso_timeout_ms", 260).coerceIn(20, 6000)

            while (System.currentTimeMillis() < deadline) {
                val blob: ByteArray = try {
                    UsbIsoBridge.isochIn(fd, epAddr, packetSize, numPackets, isoTimeout) ?: break
                } catch (_: Exception) {
                    break
                }
                if (blob.size < 12) continue

                fun u32le(off: Int): Int {
                    return (blob[off].toInt() and 0xFF) or
                        ((blob[off + 1].toInt() and 0xFF) shl 8) or
                        ((blob[off + 2].toInt() and 0xFF) shl 16) or
                        ((blob[off + 3].toInt() and 0xFF) shl 24)
                }
                val magic = u32le(0)
                if (magic != 0x4F53494B) continue // "KISO"
                val nPk = u32le(4).coerceIn(0, 1024)
                val payloadLen = u32le(8).coerceIn(0, 64 * 1024 * 1024)
                val metaLen = 12 + nPk * 8
                if (blob.size < metaLen) continue
                if (blob.size < metaLen + payloadLen) continue

                var metaOff = 12
                var dataOff = metaLen
                for (pi in 0 until nPk) {
                    val st = u32le(metaOff)
                    val al = u32le(metaOff + 4).coerceIn(0, payloadLen)
                    metaOff += 8
                    if (al <= 0) continue
                    if (dataOff + al > metaLen + payloadLen) break
                    if (st == 0) {
                        val r = processUvcPayload(blob, dataOff, al)
                        if (r != null) return r
                    }
                    dataOff += al
                }
            }
        } else {
            // Bulk path: read from the bulk IN endpoint and feed each bulkTransfer chunk as a UVC payload.
            val bulkTimeout = payload.optInt("bulk_timeout_ms", 240).coerceIn(20, 6000)
            // For UVC bulk streaming, a single bulkTransfer() may return multiple UVC payload transfers
            // concatenated. Use negotiated dwMaxPayloadTransferSize when available to split.
            val negotiated = negotiatedMaxPayloadTransferSize.toInt().coerceIn(0, 1024 * 1024)
            val payloadSize = payload.optInt("bulk_payload_bytes", if (negotiated > 0) negotiated else chosen.ep.maxPacketSize).coerceIn(256, 1024 * 1024)
            val readSize = payload.optInt("bulk_read_size", (payloadSize * 4).coerceIn(1024, 256 * 1024)).coerceIn(1024, 1024 * 1024)
            val buf = ByteArray(readSize)
            val q: java.util.ArrayDeque<ByteArray> = java.util.ArrayDeque()
            var qBytes = 0
            while (System.currentTimeMillis() < deadline) {
                val n = try {
                    conn.bulkTransfer(chosen.ep, buf, buf.size, bulkTimeout)
                } catch (_: Exception) {
                    -1
                }
                if (n <= 0) continue
                val chunk = ByteArray(n.coerceIn(0, buf.size))
                java.lang.System.arraycopy(buf, 0, chunk, 0, chunk.size)
                q.addLast(chunk)
                qBytes += chunk.size

                while (qBytes >= payloadSize) {
	                    val payloadBuf = ByteArray(payloadSize)
	                    var off = 0
	                    while (off < payloadSize && q.isNotEmpty()) {
	                        val head = q.peekFirst() ?: break
	                        val take = kotlin.math.min(payloadSize - off, head.size)
	                        java.lang.System.arraycopy(head, 0, payloadBuf, off, take)
	                        off += take
	                        if (take == head.size) {
	                            q.removeFirst()
	                        } else {
	                            val rest = ByteArray(head.size - take)
	                            java.lang.System.arraycopy(head, take, rest, 0, rest.size)
	                            q.removeFirst()
	                            q.addFirst(rest)
	                        }
	                    }
                    qBytes -= payloadSize
                    val r = processUvcPayload(payloadBuf, 0, payloadBuf.size)
                    if (r != null) return r
                }
            }
        }

        return jsonError(Response.Status.INTERNAL_ERROR, "uvc_capture_timeout")
    }

    override fun openWebSocket(handshake: IHTTPSession): NanoWSD.WebSocket {
        val uri = handshake.uri ?: "/"
        val prefix = "/ws/usb/stream/"
        if (uri.startsWith(prefix)) {
            val streamId = uri.removePrefix(prefix).trim()
            return object : NanoWSD.WebSocket(handshake) {
                override fun onOpen() {
                    val st = usbStreams[streamId]
                    if (st == null) {
                        runCatching { close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "stream_not_found", false) }
                        return
                    }
                    st.wsClients.add(this)
                }

                override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                    usbStreams[streamId]?.wsClients?.remove(this)
                }

                override fun onMessage(message: NanoWSD.WebSocketFrame?) {
                    // Ignore; this is a server->client stream.
                }

                override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

                override fun onException(exception: java.io.IOException?) {
                    usbStreams[streamId]?.wsClients?.remove(this)
                }
            }
        }

        if (uri == "/ws/sensors") {
            val params = handshake.parameters
            val sensorsCsv = (params["sensors"]?.firstOrNull() ?: "").trim()
            val names = sensorsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val rateHz = (params["rate_hz"]?.firstOrNull() ?: "200").toIntOrNull()?.coerceIn(1, 1000) ?: 200
            val latency = (params["latency"]?.firstOrNull() ?: "realtime").trim()
            val timestamp = (params["timestamp"]?.firstOrNull() ?: "mono").trim()
            val backpressure = (params["backpressure"]?.firstOrNull() ?: "drop_old").trim()
            val maxQueue = (params["max_queue"]?.firstOrNull() ?: "4096").toIntOrNull()?.coerceIn(64, 50_000) ?: 4096
            val permissionId = (params["permission_id"]?.firstOrNull() ?: "").trim()
            val identityQ = (params["identity"]?.firstOrNull() ?: "").trim()

            return object : NanoWSD.WebSocket(handshake) {
                private var streamId: String? = null

                override fun onOpen() {
                    if (names.isEmpty()) {
                        runCatching {
                            send(JSONObject().put("type", "error").put("code", "sensors_required").toString())
                            close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "sensors_required", false)
                        }
                        return
                    }

                    val permission = ensureDevicePermissionForWs(
                        session = handshake,
                        permissionId = permissionId,
                        identityFromQuery = identityQ,
                        tool = "device.sensors",
                        capability = "sensors",
                        detail = "Open sensors websocket stream"
                    )
                    if (permission != null) {
                        runCatching {
                            send(JSONObject().put("type", "permission_required").put("request", permission).toString())
                            close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "permission_required", false)
                        }
                        return
                    }

                    val started = sensors.start(
                        SensorsStreamManager.StreamStart(
                            sensors = names,
                            rateHz = rateHz,
                            latency = latency,
                            timestamp = timestamp,
                            bufferMax = maxQueue,
                            backpressureMode = backpressure,
                        )
                    )
                    if (started["status"] != "ok") {
                        runCatching {
                            send(JSONObject().put("type", "error").put("code", "start_failed").put("detail", JSONObject(started)).toString())
                            close(NanoWSD.WebSocketFrame.CloseCode.InternalServerError, "start_failed", false)
                        }
                        return
                    }
                    val id = (started["stream_id"] as? String ?: "").trim()
                    if (id.isBlank()) {
                        runCatching { close(NanoWSD.WebSocketFrame.CloseCode.InternalServerError, "stream_id_missing", false) }
                        return
                    }
                    streamId = id
                    val added = sensors.addWsClient(id, this)
                    if (!added) {
                        sensors.stop(id)
                        runCatching { close(NanoWSD.WebSocketFrame.CloseCode.InternalServerError, "attach_failed", false) }
                        return
                    }
                    runCatching { send(sensors.hello(id).toString()) }
                }

                override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                    val id = streamId
                    if (id != null) {
                        sensors.removeWsClient(id, this)
                        sensors.stop(id)
                        streamId = null
                    }
                }

                override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
                override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
                override fun onException(exception: java.io.IOException?) {
                    val msg = exception?.message?.lowercase(Locale.US) ?: ""
                    if (exception is SocketTimeoutException ||
                        (exception is java.io.InterruptedIOException && msg.contains("timed out"))
                    ) {
                        return
                    }
                    val id = streamId
                    if (id != null) {
                        sensors.removeWsClient(id, this)
                        sensors.stop(id)
                        streamId = null
                    }
                }
            }
        }

        if (uri == "/ws/ble/events") {
            return object : NanoWSD.WebSocket(handshake) {
                override fun onOpen() {
                    ble.addWsClient(this)
                }
                override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                    ble.removeWsClient(this)
                }
                override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
                override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
                override fun onException(exception: java.io.IOException?) {
                    ble.removeWsClient(this)
                }
            }
        }

        if (uri == "/ws/camera/preview") {
            return object : NanoWSD.WebSocket(handshake) {
                override fun onOpen() {
                    camera.addWsClient(this)
                }
                override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                    camera.removeWsClient(this)
                }
                override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
                override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
                override fun onException(exception: java.io.IOException?) {
                    camera.removeWsClient(this)
                }
            }
        }

        if (uri == "/ws/stt/events") {
            return object : NanoWSD.WebSocket(handshake) {
                override fun onOpen() {
                    stt.addWsClient(this)
                }
                override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                    stt.removeWsClient(this)
                }
                override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
                override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
                override fun onException(exception: java.io.IOException?) {
                    stt.removeWsClient(this)
                }
            }
        }

        if (uri == "/ws/audio/pcm") {
            return object : NanoWSD.WebSocket(handshake) {
                override fun onOpen() {
                    audioRecord.addWsClient(this)
                }
                override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                    audioRecord.removeWsClient(this)
                }
                override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
                override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
                override fun onException(exception: java.io.IOException?) {
                    audioRecord.removeWsClient(this)
                }
            }
        }

        if (uri == "/ws/video/frames") {
            return object : NanoWSD.WebSocket(handshake) {
                override fun onOpen() {
                    videoRecord.addWsClient(this)
                }
                override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                    videoRecord.removeWsClient(this)
                }
                override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
                override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
                override fun onException(exception: java.io.IOException?) {
                    videoRecord.removeWsClient(this)
                }
            }
        }

        val mediaStreamPrefix = "/ws/media/stream/"
        if (uri.startsWith(mediaStreamPrefix)) {
            val streamId = uri.removePrefix(mediaStreamPrefix).trim()
            return object : NanoWSD.WebSocket(handshake) {
                override fun onOpen() {
                    if (!mediaStream.addWsClient(streamId, this)) {
                        runCatching { close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "stream_not_found", false) }
                    }
                }
                override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                    mediaStream.removeWsClient(streamId, this)
                }
                override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
                override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
                override fun onException(exception: java.io.IOException?) {
                    mediaStream.removeWsClient(streamId, this)
                }
            }
        }

        return object : NanoWSD.WebSocket(handshake) {
            override fun onOpen() {
                runCatching { close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "unknown_ws_path", false) }
            }
            override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {}
            override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
            override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
            override fun onException(exception: java.io.IOException?) {}
        }
    }

    private fun ensureVisionPermission(payload: JSONObject): Boolean {
        val pid = payload.optString("permission_id", "")
        return isPermissionApproved(pid, consume = true)
    }

    private fun userPath(relative: String): File? {
        val root = File(context.filesDir, "user")
        val rel = relative.trim().trimStart('/')
        if (rel.isBlank()) return null
        return try {
            val out = File(root, rel).canonicalFile
            if (out.path.startsWith(root.canonicalPath + File.separator)) out else null
        } catch (_: Exception) {
            null
        }
    }

    private data class FsPathRef(
        val fs: String,
        val sourcePath: String,
        val displayPath: String,
        val userFile: File? = null,
        val userRelPath: String? = null,
        val termuxPath: String? = null,
    )

    private fun parseFsPathRef(rawPath: String): FsPathRef? {
        val raw = rawPath.trim()
        if (raw.isBlank()) return null

        if (raw.startsWith("user://", ignoreCase = true)) {
            val rel = raw.substringAfter("://").trim().trimStart('/')
            if (rel.isBlank() || rel == ".") {
                val root = File(context.filesDir, "user").canonicalFile
                return FsPathRef(
                    fs = "user",
                    sourcePath = raw,
                    displayPath = "user://.",
                    userFile = root,
                    userRelPath = ""
                )
            }
            val file = userPath(rel) ?: return null
            return FsPathRef(
                fs = "user",
                sourcePath = raw,
                displayPath = "user://$rel",
                userFile = file,
                userRelPath = rel
            )
        }

        if (raw.startsWith("termux://", ignoreCase = true)) {
            val rest0 = raw.substringAfter("://").trim()
            val abs = when {
                rest0 == "~" -> TERMUX_HOME_PREFIX
                rest0.startsWith("~/") -> TERMUX_HOME_PREFIX + "/" + rest0.removePrefix("~/")
                rest0.startsWith("/") -> rest0
                else -> TERMUX_HOME_PREFIX + "/" + rest0.trimStart('/')
            }
            val normalizedAbs = abs.replace('\\', '/')
            if (!isAllowedTermuxPath(normalizedAbs)) return null
            val display = "termux://${normalizedAbs.removePrefix(TERMUX_HOME_PREFIX).let { if (it.isBlank()) "~" else "~$it" }}"
            return FsPathRef(
                fs = "termux",
                sourcePath = raw,
                displayPath = display,
                termuxPath = normalizedAbs
            )
        }

        if (raw.startsWith(TERMUX_HOME_PREFIX + "/") || raw == TERMUX_HOME_PREFIX) {
            val normalizedAbs = raw.replace('\\', '/')
            if (!isAllowedTermuxPath(normalizedAbs)) return null
            val display = "termux://${normalizedAbs.removePrefix(TERMUX_HOME_PREFIX).let { if (it.isBlank()) "~" else "~$it" }}"
            return FsPathRef(
                fs = "termux",
                sourcePath = raw,
                displayPath = display,
                termuxPath = normalizedAbs
            )
        }

        if (raw.startsWith("/")) return null
        val rel = raw.trimStart('/')
        if (rel.isBlank() || rel == ".") {
            val root = File(context.filesDir, "user").canonicalFile
            return FsPathRef(
                fs = "user",
                sourcePath = raw,
                displayPath = "user://.",
                userFile = root,
                userRelPath = ""
            )
        }
        val file = userPath(rel) ?: return null
        return FsPathRef(
            fs = "user",
            sourcePath = raw,
            displayPath = "user://$rel",
            userFile = file,
            userRelPath = rel
        )
    }

    private fun decodePathSuffix(uri: String, prefix: String): String? {
        if (!uri.startsWith(prefix)) return null
        val raw = uri.substring(prefix.length)
        if (raw.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrNull() ?: return null
        val clean = decoded.trim().replace("\\", "/")
        return clean.ifBlank { null }
    }

    private fun normalizeTermuxRoutePath(path: String): String? {
        val p = path.trim()
        if (p.isBlank()) return null
        if (p.startsWith("termux://", ignoreCase = true)) {
            val ref = parseFsPathRef(p) ?: return null
            return ref.displayPath
        }
        if (p.startsWith(TERMUX_HOME_PREFIX) || p == "~" || p.startsWith("~/") || p.startsWith("/")) {
            val asUri = "termux://$p"
            val ref = parseFsPathRef(asUri) ?: return null
            return ref.displayPath
        }
        val ref = parseFsPathRef("termux://~/$p") ?: return null
        return ref.displayPath
    }

    private fun isAllowedTermuxPath(absPath: String): Boolean {
        if (absPath.isBlank()) return false
        val p = absPath.replace('\\', '/')
        if (p == TERMUX_HOME_PREFIX) return true
        return p.startsWith(TERMUX_HOME_PREFIX + "/")
    }

    private fun readFsPathBytes(ref: FsPathRef, maxBytes: Int = 64 * 1024 * 1024): Pair<ByteArray, String> {
        return when (ref.fs) {
            "user" -> {
                val file = ref.userFile ?: throw IllegalArgumentException("path_outside_user_dir")
                if (!file.exists() || !file.isFile) throw IllegalArgumentException("not_found")
                val bytes = file.readBytes()
                Pair(bytes, ref.displayPath)
            }
            "termux" -> {
                val absPath = ref.termuxPath ?: throw IllegalArgumentException("path_outside_termux_home")
                val payload = JSONObject()
                    .put("path", absPath)
                    .put("offset", 0)
                    .put("max_bytes", maxBytes.coerceIn(1, 128 * 1024 * 1024))
                val rsp = workerJsonRequest("/fs/read", "POST", payload, readTimeoutMs = 30_000)
                    ?: throw IllegalArgumentException("termux_unavailable")
                val body = rsp.second
                if (rsp.first !in 200..299) {
                    val err = body.optString("error", "").trim()
                    throw IllegalArgumentException(
                        when (err) {
                            "not_found" -> "not_found"
                            "path_outside_home" -> "path_outside_termux_home"
                            else -> "termux_fs_read_failed"
                        }
                    )
                }
                val encoding = body.optString("encoding", "utf-8").trim().lowercase(Locale.US)
                val content = body.optString("content", "")
                val truncated = body.optBoolean("truncated", false)
                val bytes = when (encoding) {
                    "base64" -> runCatching { Base64.decode(content, Base64.DEFAULT) }.getOrNull()
                        ?: throw IllegalArgumentException("termux_fs_read_failed")
                    else -> content.toByteArray(Charsets.UTF_8)
                }
                if (truncated) throw IllegalArgumentException("file_too_large")
                Pair(bytes, ref.displayPath)
            }
            else -> throw IllegalArgumentException("unsupported_filesystem")
        }
    }

    private fun writeFsPathBytes(ref: FsPathRef, bytes: ByteArray): String {
        return when (ref.fs) {
            "user" -> {
                val file = ref.userFile ?: throw IllegalArgumentException("path_outside_user_dir")
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                ref.displayPath
            }
            "termux" -> {
                val absPath = ref.termuxPath ?: throw IllegalArgumentException("path_outside_termux_home")
                val payload = JSONObject()
                    .put("path", absPath)
                    .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("encoding", "base64")
                val rsp = workerJsonRequest("/fs/write", "POST", payload, readTimeoutMs = 30_000)
                    ?: throw IllegalArgumentException("termux_unavailable")
                if (rsp.first !in 200..299) {
                    val err = rsp.second.optString("error", "").trim()
                    throw IllegalArgumentException(
                        when (err) {
                            "path_outside_home" -> "path_outside_termux_home"
                            else -> "termux_fs_write_failed"
                        }
                    )
                }
                ref.displayPath
            }
            else -> throw IllegalArgumentException("unsupported_filesystem")
        }
    }

    private fun materializeFsPathToLocalFile(ref: FsPathRef, prefix: String): Pair<File, Boolean> {
        return when (ref.fs) {
            "user" -> {
                val file = ref.userFile ?: throw IllegalArgumentException("path_outside_user_dir")
                if (!file.exists() || !file.isFile) throw IllegalArgumentException("not_found")
                Pair(file, false)
            }
            "termux" -> {
                val bytes = readFsPathBytes(ref).first
                val ext = ref.termuxPath?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() } ?: "bin"
                val tmp = File.createTempFile(prefix, ".$ext", context.cacheDir)
                tmp.writeBytes(bytes)
                Pair(tmp, true)
            }
            else -> throw IllegalArgumentException("unsupported_filesystem")
        }
    }

    private fun statFsPath(ref: FsPathRef): Pair<Boolean, Long> {
        return when (ref.fs) {
            "user" -> {
                val f = ref.userFile
                if (f != null && f.exists() && f.isFile) Pair(true, f.length()) else Pair(false, 0L)
            }
            "termux" -> {
                val absPath = ref.termuxPath ?: return Pair(false, 0L)
                val payload = JSONObject().put("path", absPath).put("offset", 0).put("max_bytes", 1)
                val rsp = workerJsonRequest("/fs/read", "POST", payload, readTimeoutMs = 5000) ?: return Pair(false, 0L)
                if (rsp.first !in 200..299) return Pair(false, 0L)
                Pair(true, rsp.second.optLong("size", 0L))
            }
            else -> Pair(false, 0L)
        }
    }

    private fun workerJsonRequest(
        path: String,
        method: String = "POST",
        body: JSONObject? = null,
        readTimeoutMs: Int = 5000,
    ): Pair<Int, JSONObject>? {
        return try {
            val url = java.net.URL("http://127.0.0.1:8776$path")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 2000
                readTimeout = readTimeoutMs
                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (method == "POST") {
                conn.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = runCatching { JSONObject(txt) }.getOrElse { JSONObject().put("raw", txt) }
            Pair(code, json)
        } catch (_: Exception) {
            null
        }
    }

    private fun listFsPath(path: String): Response {
        val ref = parseFsPathRef(path) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
        return when (ref.fs) {
            "user" -> {
                val root = File(context.filesDir, "user").canonicalFile
                val dir = ref.userFile ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
                if (!dir.exists()) return jsonError(Response.Status.NOT_FOUND, "not_found")
                if (!dir.isDirectory) return jsonError(Response.Status.BAD_REQUEST, "not_a_directory")
                val arr = org.json.JSONArray()
                val kids = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
                for (f in kids) {
                    arr.put(
                        JSONObject()
                            .put("name", f.name)
                            .put("is_dir", f.isDirectory)
                            .put("size", if (f.isFile) f.length() else 0L)
                            .put("mtime_ms", f.lastModified())
                    )
                }
                val outRel = if (dir == root) "" else dir.relativeTo(root).path.replace("\\", "/")
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("fs", "user")
                        .put("path", outRel)
                        .put("path_uri", if (outRel.isBlank()) "user://." else "user://$outRel")
                        .put("items", arr)
                )
            }
            "termux" -> {
                val termuxPath = ref.termuxPath ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                val payload = JSONObject().put("path", termuxPath)
                val rsp = workerJsonRequest("/fs/list", "POST", payload, readTimeoutMs = 10_000)
                    ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
                val code = rsp.first
                val body = rsp.second
                if (code !in 200..299) {
                    val err = body.optString("error", "")
                    return when (err) {
                        "not_a_directory" -> jsonError(Response.Status.BAD_REQUEST, "not_a_directory")
                        "path_outside_home" -> jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                        else -> jsonError(Response.Status.NOT_FOUND, "not_found")
                    }
                }
                val entries = body.optJSONArray("entries") ?: JSONArray()
                val out = JSONArray()
                for (i in 0 until entries.length()) {
                    val item = entries.optJSONObject(i) ?: continue
                    out.put(
                        JSONObject()
                            .put("name", item.optString("name", ""))
                            .put("is_dir", item.optBoolean("is_dir", false))
                            .put("size", item.optLong("size", 0L))
                            .put("mtime_ms", (item.optDouble("mtime", 0.0) * 1000.0).toLong())
                    )
                }
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("fs", "termux")
                        .put("path", termuxPath)
                        .put("path_uri", ref.displayPath)
                        .put("items", out)
                )
            }
            else -> jsonError(Response.Status.BAD_REQUEST, "unsupported_filesystem")
        }
    }

    private fun toUserRelativePath(abs: File): String? {
        return try {
            val root = File(context.filesDir, "user").canonicalFile
            val canon = abs.canonicalFile
            if (canon == root) return null
            if (!canon.path.startsWith(root.path + File.separator)) return null
            canon.relativeTo(root).path.replace("\\", "/")
        } catch (_: Exception) {
            null
        }
    }

    private fun systemPath(relative: String): File? {
        val root = File(context.filesDir, "system")
        val rel = relative.trim().trimStart('/')
        if (rel.isBlank()) return null
        return try {
            val out = File(root, rel).canonicalFile
            if (out.path.startsWith(root.canonicalPath + File.separator) || out == root.canonicalFile) out else null
        } catch (_: Exception) {
            null
        }
    }

    private fun handleSysList(session: IHTTPSession): Response {
        val rel = firstParam(session, "path").trim().trimStart('/')
        val root = File(context.filesDir, "system").canonicalFile
        val dir = if (rel.isBlank()) root else systemPath(rel) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_system_dir")
        if (!dir.exists()) return jsonError(Response.Status.NOT_FOUND, "not_found")
        if (!dir.isDirectory) return jsonError(Response.Status.BAD_REQUEST, "not_a_directory")

        val arr = org.json.JSONArray()
        val kids = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        for (f in kids) {
            val item = JSONObject()
                .put("name", f.name)
                .put("is_dir", f.isDirectory)
                .put("size", if (f.isFile) f.length() else 0L)
                .put("mtime_ms", f.lastModified())
            arr.put(item)
        }
        val outRel = if (dir == root) "" else dir.relativeTo(root).path.replace("\\", "/")
        return jsonResponse(JSONObject().put("status", "ok").put("path", outRel).put("items", arr))
    }

    private fun serveSysFile(session: IHTTPSession): Response {
        val rel = firstParam(session, "path")
        if (rel.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
        val file = systemPath(rel) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_system_dir")
        if (!file.exists() || !file.isFile) return jsonError(Response.Status.NOT_FOUND, "not_found")

        val mime = URLConnection.guessContentTypeFromName(file.name) ?: mimeTypeFor(file.name)
        val stream: InputStream = FileInputStream(file)
        val response = newChunkedResponse(Response.Status.OK, mime, stream)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("X-Content-Type-Options", "nosniff")
        return response
    }

    private fun handleVisionModelLoad(payload: JSONObject): Response {
        if (!ensureVisionPermission(payload)) return forbidden("permission_required")
        val name = payload.optString("name", "").trim()
        val path = payload.optString("path", "").trim()
        if (name.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "name_required")
        if (path.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
        val ref = parseFsPathRef(path) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
        val mat = try {
            materializeFsPathToLocalFile(ref, "vision_model_")
        } catch (ex: IllegalArgumentException) {
            return when (ex.message ?: "") {
                "path_outside_termux_home" -> jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                "termux_unavailable" -> jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
                "not_found" -> jsonError(Response.Status.NOT_FOUND, "not_found")
                else -> jsonError(Response.Status.INTERNAL_ERROR, "file_read_failed")
            }
        }
        val file = mat.first
        val isTemp = mat.second
        val delegate = payload.optString("delegate", "none")
        val threads = payload.optInt("num_threads", 2)
        return try {
            val info = tflite.load(name, file, delegate, threads)
            jsonResponse(JSONObject(info))
        } catch (ex: Exception) {
            jsonError(Response.Status.BAD_REQUEST, "model_load_failed", JSONObject().put("detail", ex.message ?: ""))
        } finally {
            if (isTemp) runCatching { file.delete() }
        }
    }

    private fun handleVisionModelUnload(payload: JSONObject): Response {
        if (!ensureVisionPermission(payload)) return forbidden("permission_required")
        val name = payload.optString("name", "").trim()
        if (name.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "name_required")
        val ok = tflite.unload(name)
        return jsonResponse(JSONObject().put("status", "ok").put("unloaded", ok))
    }

    private fun handleVisionFramePut(payload: JSONObject): Response {
        if (!ensureVisionPermission(payload)) return forbidden("permission_required")
        val w = payload.optInt("width", 0)
        val h = payload.optInt("height", 0)
        val b64 = payload.optString("rgba_b64", "")
        if (w <= 0 || h <= 0) return jsonError(Response.Status.BAD_REQUEST, "invalid_size")
        if (b64.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "rgba_b64_required")
        return try {
            val frame = VisionImageIo.decodeRgbaB64(w, h, b64)
            val id = visionFrames.put(frame)
            jsonResponse(JSONObject().put("status", "ok").put("frame_id", id).put("stats", JSONObject(visionFrames.stats())))
        } catch (ex: Exception) {
            jsonError(Response.Status.BAD_REQUEST, "frame_put_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleVisionFrameGet(payload: JSONObject): Response {
        if (!ensureVisionPermission(payload)) return forbidden("permission_required")
        val id = payload.optString("frame_id", "").trim()
        if (id.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "frame_id_required")
        val frame = visionFrames.get(id) ?: return jsonError(Response.Status.NOT_FOUND, "frame_not_found")
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("frame_id", id)
                .put("width", frame.width)
                .put("height", frame.height)
                .put("rgba_b64", VisionImageIo.encodeRgbaB64(frame))
        )
    }

    private fun handleVisionFrameDelete(payload: JSONObject): Response {
        if (!ensureVisionPermission(payload)) return forbidden("permission_required")
        val id = payload.optString("frame_id", "").trim()
        if (id.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "frame_id_required")
        val ok = visionFrames.delete(id)
        return jsonResponse(JSONObject().put("status", "ok").put("deleted", ok).put("stats", JSONObject(visionFrames.stats())))
    }

    private fun handleVisionImageLoad(payload: JSONObject): Response {
        if (!ensureVisionPermission(payload)) return forbidden("permission_required")
        val path = payload.optString("path", "").trim()
        if (path.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
        val ref = parseFsPathRef(path) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
        val mat = try {
            materializeFsPathToLocalFile(ref, "vision_image_")
        } catch (ex: IllegalArgumentException) {
            return when (ex.message ?: "") {
                "path_outside_termux_home" -> jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                "termux_unavailable" -> jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
                "not_found" -> jsonError(Response.Status.NOT_FOUND, "not_found")
                else -> jsonError(Response.Status.INTERNAL_ERROR, "file_read_failed")
            }
        }
        val file = mat.first
        val isTemp = mat.second
        return try {
            val frame = VisionImageIo.decodeFileToRgba(file)
            val id = visionFrames.put(frame)
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("frame_id", id)
                    .put("width", frame.width)
                    .put("height", frame.height)
                    .put("stats", JSONObject(visionFrames.stats()))
            )
        } catch (ex: Exception) {
            jsonError(Response.Status.BAD_REQUEST, "image_load_failed", JSONObject().put("detail", ex.message ?: ""))
        } finally {
            if (isTemp) runCatching { file.delete() }
        }
    }

    private fun handleVisionFrameSave(payload: JSONObject): Response {
        if (!ensureVisionPermission(payload)) return forbidden("permission_required")
        val id = payload.optString("frame_id", "").trim()
        val outPath = payload.optString("path", "").trim()
        val format = payload.optString("format", "jpg")
        val jpegQuality = payload.optInt("jpeg_quality", 90)
        if (id.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "frame_id_required")
        if (outPath.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
        val frame = visionFrames.get(id) ?: return jsonError(Response.Status.NOT_FOUND, "frame_not_found")
        val outRef = parseFsPathRef(outPath) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_path")
        return try {
            if (outRef.fs == "user") {
                val outFile = outRef.userFile ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
                VisionImageIo.encodeRgbaToFile(frame, format, outFile, jpegQuality)
            } else {
                val tmp = File.createTempFile("vision_out_", ".${format.lowercase(Locale.US)}", context.cacheDir)
                try {
                    VisionImageIo.encodeRgbaToFile(frame, format, tmp, jpegQuality)
                    writeFsPathBytes(outRef, tmp.readBytes())
                } finally {
                    runCatching { tmp.delete() }
                }
            }
            jsonResponse(JSONObject().put("status", "ok").put("saved", true).put("path", outRef.displayPath))
        } catch (ex: IllegalArgumentException) {
            when (ex.message ?: "") {
                "path_outside_termux_home" -> jsonError(Response.Status.BAD_REQUEST, "path_outside_termux_home")
                "termux_unavailable" -> jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
                else -> jsonError(Response.Status.BAD_REQUEST, "frame_save_failed", JSONObject().put("detail", ex.message ?: ""))
            }
        } catch (ex: Exception) {
            jsonError(Response.Status.BAD_REQUEST, "frame_save_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleVisionRun(payload: JSONObject): Response {
        if (!ensureVisionPermission(payload)) return forbidden("permission_required")
        val model = payload.optString("model", "").trim()
        if (model.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "model_required")

        val frameId = payload.optString("frame_id", "").trim()
        val frame = if (frameId.isNotBlank()) {
            visionFrames.get(frameId)
        } else {
            val w = payload.optInt("width", 0)
            val h = payload.optInt("height", 0)
            val b64 = payload.optString("rgba_b64", "")
            if (w <= 0 || h <= 0 || b64.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "frame_id_or_rgba_required")
            try {
                VisionImageIo.decodeRgbaB64(w, h, b64)
            } catch (ex: Exception) {
                return jsonError(Response.Status.BAD_REQUEST, "invalid_rgba", JSONObject().put("detail", ex.message ?: ""))
            }
        } ?: return jsonError(Response.Status.NOT_FOUND, "frame_not_found")

        val normalize = payload.optBoolean("normalize", true)
        val meanArr = payload.optJSONArray("mean")
        val stdArr = payload.optJSONArray("std")
        fun floatArr3(a: org.json.JSONArray?, fallback: FloatArray): FloatArray {
            if (a == null || a.length() < 3) return fallback
            return floatArrayOf(
                (a.optDouble(0, fallback[0].toDouble())).toFloat(),
                (a.optDouble(1, fallback[1].toDouble())).toFloat(),
                (a.optDouble(2, fallback[2].toDouble())).toFloat(),
            )
        }
        val mean = floatArr3(meanArr, floatArrayOf(0f, 0f, 0f))
        val std = floatArr3(stdArr, floatArrayOf(1f, 1f, 1f))

        return try {
            val result = tflite.runRgba(model, frame.rgba, frame.width, frame.height, normalize, mean, std)
            jsonResponse(JSONObject(result))
        } catch (ex: Exception) {
            jsonError(Response.Status.BAD_REQUEST, "vision_run_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun findUsbDevice(name: String, vendorId: Int, productId: Int): UsbDevice? {
        val list = usbManager.deviceList.values
        if (name.isNotBlank()) {
            return list.firstOrNull { it.deviceName == name }
        }
        if (vendorId >= 0 && productId >= 0) {
            return list.firstOrNull { it.vendorId == vendorId && it.productId == productId }
        }
        return null
    }

    private fun ensureUsbPermission(device: UsbDevice, timeoutMs: Long): Boolean {
        if (usbManager.hasPermission(device)) return true

        val name = device.deviceName
        UsbPermissionWaiter.begin(name)
        try {
            // Initiate the OS permission request from an Activity context.
            val intent = Intent(context, jp.espresso3389.methings.ui.UsbPermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(jp.espresso3389.methings.ui.UsbPermissionActivity.EXTRA_DEVICE_NAME, name)
            }
            Log.i(TAG, "Requesting USB permission (activity): name=$name vid=${device.vendorId} pid=${device.productId}")
            context.startActivity(intent)
        } catch (ex: Exception) {
            Log.w(TAG, "USB permission activity launch failed", ex)
        }

        val granted = UsbPermissionWaiter.await(name, timeoutMs)
        UsbPermissionWaiter.clear(name)

        val has = runCatching { usbManager.hasPermission(device) }.getOrDefault(false)
        Log.i(TAG, "USB permission result: name=$name granted=$granted hasPermission=$has")
        return granted && has
    }

    private fun usbDeviceToJson(dev: UsbDevice): JSONObject {
        val o = JSONObject()
            .put("name", dev.deviceName)
            .put("vendor_id", dev.vendorId)
            .put("product_id", dev.productId)
            .put("device_class", dev.deviceClass)
            .put("device_subclass", dev.deviceSubclass)
            .put("device_protocol", dev.deviceProtocol)
            .put("interface_count", dev.interfaceCount)

        val ifArr = org.json.JSONArray()
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            val eps = org.json.JSONArray()
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                eps.put(
                    JSONObject()
                        .put("address", ep.address)
                        .put("attributes", ep.attributes)
                        .put("direction", ep.direction)
                        .put("max_packet_size", ep.maxPacketSize)
                        .put("number", ep.endpointNumber)
                        .put("interval", ep.interval)
                        .put("type", ep.type)
                )
            }
            ifArr.put(
                JSONObject()
                    .put("id", intf.id)
                    .put("interface_class", intf.interfaceClass)
                    .put("interface_subclass", intf.interfaceSubclass)
                    .put("interface_protocol", intf.interfaceProtocol)
                    .put("endpoint_count", intf.endpointCount)
                    .put("endpoints", eps)
            )
        }
        o.put("interfaces", ifArr)

        // Strings may throw without permission; keep best-effort.
        runCatching { o.put("manufacturer_name", dev.manufacturerName ?: "") }
        runCatching { o.put("product_name", dev.productName ?: "") }
        runCatching { o.put("serial_number", dev.serialNumber ?: "") }
        return o
    }

    private fun serveUiFile(path: String): Response {
        val safePath = path.replace("\\", "/")
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
        val file = File(uiRoot, safePath)
        if (!file.exists() || !file.isFile) {
            return notFound()
        }
        val mime = mimeTypeFor(file.name)
        val stream: InputStream = FileInputStream(file)
        val response = newChunkedResponse(Response.Status.OK, mime, stream)
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        return response
    }

    private fun jsonResponse(payload: JSONObject): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", payload.toString())
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    private fun jsonError(status: Response.Status, code: String, extra: JSONObject? = null): Response {
        val payload = (extra ?: JSONObject()).put("error", code)
        val response = newFixedLengthResponse(status, "application/json", payload.toString())
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    private fun handleAppUpdateCheck(): Response {
        if (BuildConfig.DEBUG) {
            return jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("update_enabled", false)
                    .put("current_version", "DEBUG VERSION")
                    .put("latest_tag", "")
                    .put("latest_version", "")
                    .put("has_update", false)
                    .put("published_at", "")
                    .put("release_url", "")
                    .put("apk_name", "")
                    .put("apk_size", 0L)
                    .put("message", "Auto update is disabled for debug builds.")
            )
        }
        return try {
            jsonResponse(appUpdateManager.checkLatestRelease())
        } catch (ex: Throwable) {
            Log.w(TAG, "App update check failed", ex)
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "update_check_failed",
                JSONObject().put("detail", "${ex.javaClass.simpleName}:${ex.message ?: ""}")
            )
        }
    }

    private fun handleAppUpdateInstall(): Response {
        if (BuildConfig.DEBUG) {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "debug_build_update_disabled",
                JSONObject().put("message", "Auto update/install is disabled for debug builds.")
            )
        }
        return try {
            jsonResponse(appUpdateManager.downloadAndStartInstall())
        } catch (ex: Throwable) {
            Log.w(TAG, "App update install failed", ex)
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "update_install_failed",
                JSONObject().put("detail", "${ex.javaClass.simpleName}:${ex.message ?: ""}")
            )
        }
    }

    private fun handleAppUpdateInstallPermissionStatus(): Response {
        val granted = appUpdateManager.canInstallPackages()
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("granted", granted)
                .put("update_enabled", !BuildConfig.DEBUG)
                .put(
                    "message",
                    if (BuildConfig.DEBUG) {
                        "Auto update/install is disabled for debug builds."
                    } else if (granted) {
                        "Install permission granted."
                    } else {
                        "Allow installs from this app to enable in-app APK updates."
                    }
                )
        )
    }

    private fun handleAppUpdateInstallPermissionOpenSettings(): Response {
        // No debug guard here — opening the "install unknown apps" settings page
        // is needed for Termux installation regardless of build type.
        return try {
            appUpdateManager.openInstallPermissionSettings()
            jsonResponse(JSONObject().put("status", "ok"))
        } catch (ex: Throwable) {
            Log.w(TAG, "Open install-permission settings failed", ex)
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "open_install_permission_settings_failed",
                JSONObject().put("detail", "${ex.javaClass.simpleName}:${ex.message ?: ""}")
            )
        }
    }

    private fun handleWorkAppUpdateCheckStatus(): Response {
        return try {
            jsonResponse(workJobManager.appUpdateCheckStatus())
        } catch (ex: Throwable) {
            Log.w(TAG, "Work app_update_check status failed", ex)
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "work_status_failed",
                JSONObject().put("detail", "${ex.javaClass.simpleName}:${ex.message ?: ""}")
            )
        }
    }

    private fun handleWorkAppUpdateCheckSchedule(payload: JSONObject): Response {
        return try {
            val intervalMin = payload.optLong("interval_minutes", 360L).coerceAtLeast(15L)
            val requireCharging = payload.optBoolean("require_charging", false)
            val requireUnmetered = payload.optBoolean("require_unmetered", false)
            val replace = payload.optBoolean("replace", true)
            jsonResponse(
                workJobManager.scheduleAppUpdateCheck(
                    intervalMinutes = intervalMin,
                    requireCharging = requireCharging,
                    requireUnmetered = requireUnmetered,
                    replace = replace
                )
            )
        } catch (ex: Throwable) {
            Log.w(TAG, "Work app_update_check schedule failed", ex)
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "work_schedule_failed",
                JSONObject().put("detail", "${ex.javaClass.simpleName}:${ex.message ?: ""}")
            )
        }
    }

    private fun handleWorkAppUpdateCheckRunOnce(payload: JSONObject): Response {
        return try {
            val requireCharging = payload.optBoolean("require_charging", false)
            val requireUnmetered = payload.optBoolean("require_unmetered", false)
            jsonResponse(
                workJobManager.runAppUpdateCheckOnce(
                    requireCharging = requireCharging,
                    requireUnmetered = requireUnmetered
                )
            )
        } catch (ex: Throwable) {
            Log.w(TAG, "Work app_update_check run_once failed", ex)
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "work_run_once_failed",
                JSONObject().put("detail", "${ex.javaClass.simpleName}:${ex.message ?: ""}")
            )
        }
    }

    private fun handleWorkAppUpdateCheckCancel(): Response {
        return try {
            jsonResponse(workJobManager.cancelAppUpdateCheck())
        } catch (ex: Throwable) {
            Log.w(TAG, "Work app_update_check cancel failed", ex)
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "work_cancel_failed",
                JSONObject().put("detail", "${ex.javaClass.simpleName}:${ex.message ?: ""}")
            )
        }
    }

    private fun handleAppInfo(): Response {
        return try {
            val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val versionName = if (BuildConfig.DEBUG) "DEBUG VERSION" else (pkg.versionName ?: "")
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }
            val gitSha = BuildConfig.GIT_SHA
            val commitUrl = if (gitSha.isNotBlank() && gitSha != "unknown") {
                BuildConfig.REPO_URL.trimEnd('/') + "/commit/" + gitSha
            } else {
                ""
            }
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("version_name", versionName)
                    .put("is_debug", BuildConfig.DEBUG)
                    .put("version_code", versionCode)
                    .put("git_sha", gitSha)
                    .put("commit_url", commitUrl)
                    .put("repo_url", BuildConfig.REPO_URL)
            )
        } catch (ex: Throwable) {
            Log.w(TAG, "App info query failed", ex)
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "app_info_failed",
                JSONObject().put("detail", "${ex.javaClass.simpleName}:${ex.message ?: ""}")
            )
        }
    }

    private fun ensurePipPermission(
        session: IHTTPSession,
        payload: JSONObject,
        capability: String,
        detail: String
    ): Pair<Boolean, Response?> {
        val headerIdentity =
            ((session.headers["x-methings-identity"] ?: session.headers["x-methings-identity"]) ?: "").trim()
        val identity = payload.optString("identity", "").trim().ifBlank { headerIdentity }.ifBlank { installIdentity.get() }
        var permissionId = payload.optString("permission_id", "").trim()

        val scope = if (permissionPrefs.rememberApprovals()) "persistent" else "session"

        if (!isPermissionApproved(permissionId, consume = true) && identity.isNotBlank()) {
            val reusable = permissionStore.findReusableApproved(
                tool = "pip",
                scope = scope,
                identity = identity,
                capability = capability
            )
            if (reusable != null) {
                permissionId = reusable.id
            }
        }

        if (!isPermissionApproved(permissionId, consume = true)) {
            if (permissionPrefs.dangerouslySkipPermissions()) {
                autoApprovePermission(
                    tool = "pip",
                    detail = detail,
                    scope = scope,
                    identity = identity,
                    capability = capability
                )
                return Pair(true, null)
            }
            val existing = if (identity.isNotBlank()) permissionStore.findRecentPending(
                tool = "pip", identity = identity, capability = capability
            ) else null
            val req = existing ?: permissionStore.create(
                tool = "pip",
                detail = detail.take(240),
                scope = scope,
                identity = identity,
                capability = capability
            )
            sendPermissionPrompt(req.id, req.tool, req.detail, false)
            val requestJson = JSONObject()
                .put("id", req.id)
                .put("status", req.status)
                .put("tool", req.tool)
                .put("detail", req.detail)
                .put("scope", req.scope)
                .put("created_at", req.createdAt)
                .put("identity", req.identity)
                .put("capability", req.capability)
            val out = JSONObject()
                .put("status", "permission_required")
                .put("request", requestJson)
            return Pair(false, newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", out.toString()))
        }

        return Pair(true, null)
    }

    private fun ensureDevicePermission(
        session: IHTTPSession,
        payload: JSONObject,
        tool: String,
        capability: String,
        detail: String
    ): Pair<Boolean, Response?> {
        val headerIdentity =
            ((session.headers["x-methings-identity"] ?: session.headers["x-methings-identity"]) ?: "").trim()
        val identity = payload.optString("identity", "").trim().ifBlank { headerIdentity }.ifBlank { installIdentity.get() }
        var permissionId = payload.optString("permission_id", "").trim()

        val scope = if (permissionPrefs.rememberApprovals()) "persistent" else "session"

        if (!isPermissionApproved(permissionId, consume = true) && identity.isNotBlank()) {
            val reusable = permissionStore.findReusableApproved(
                tool = tool,
                scope = scope,
                identity = identity,
                capability = capability
            )
            if (reusable != null) {
                permissionId = reusable.id
            }
        }

        // me.me is used as the core device-to-device control plane. Requiring a manual
        // permission approval here breaks nearby flows (connect/accept/message) and causes
        // stale "accept not done" style failures in agent automation. Keep consent explicit
        // at feature/config level, but auto-approve me.me tool calls at runtime.
        if (tool == "device.me_me" && !isPermissionApproved(permissionId, consume = true)) {
            val approved = autoApprovePermission(
                tool = tool,
                detail = detail,
                scope = scope,
                identity = identity,
                capability = capability
            )
            payload.put("permission_id", approved.id)
            return Pair(true, null)
        }

        if (!isPermissionApproved(permissionId, consume = true)) {
            if (permissionPrefs.dangerouslySkipPermissions()) {
                autoApprovePermission(
                    tool = tool,
                    detail = detail,
                    scope = scope,
                    identity = identity,
                    capability = capability
                )
                return Pair(true, null)
            }
            // Reuse an existing pending request for the same (identity, tool, capability) to avoid duplicates.
            val existing = if (identity.isNotBlank()) permissionStore.findRecentPending(
                tool = tool, identity = identity, capability = capability
            ) else null
            val req = existing ?: permissionStore.create(
                tool = tool,
                detail = detail.take(240),
                scope = scope,
                identity = identity,
                capability = capability
            )
            sendPermissionPrompt(req.id, req.tool, req.detail, false)
            val requestJson = JSONObject()
                .put("id", req.id)
                .put("status", req.status)
                .put("tool", req.tool)
                .put("detail", req.detail)
                .put("scope", req.scope)
                .put("created_at", req.createdAt)
                .put("identity", req.identity)
                .put("capability", req.capability)
            val out = JSONObject()
                .put("status", "permission_required")
                .put("request", requestJson)
            return Pair(false, newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", out.toString()))
        }

        return Pair(true, null)
    }

    private fun ensureDevicePermissionForWs(
        session: IHTTPSession,
        permissionId: String,
        identityFromQuery: String,
        tool: String,
        capability: String,
        detail: String
    ): JSONObject? {
        val headerIdentity =
            ((session.headers["x-methings-identity"] ?: session.headers["x-methings-identity"]) ?: "").trim()
        val identity = identityFromQuery.ifBlank { headerIdentity }.ifBlank { installIdentity.get() }
        var pid = permissionId.trim()
        val scope = if (permissionPrefs.rememberApprovals()) "persistent" else "session"

        if (!isPermissionApproved(pid, consume = true) && identity.isNotBlank()) {
            val reusable = permissionStore.findReusableApproved(
                tool = tool,
                scope = scope,
                identity = identity,
                capability = capability
            )
            if (reusable != null) {
                pid = reusable.id
            }
        }
        if (isPermissionApproved(pid, consume = true)) return null

        if (permissionPrefs.dangerouslySkipPermissions()) {
            autoApprovePermission(
                tool = tool,
                detail = detail,
                scope = scope,
                identity = identity,
                capability = capability
            )
            return null
        }

        // Reuse an existing pending request for the same (identity, tool, capability) to avoid duplicates.
        val existing = if (identity.isNotBlank()) permissionStore.findRecentPending(
            tool = tool, identity = identity, capability = capability
        ) else null
        val req = existing ?: permissionStore.create(
            tool = tool,
            detail = detail.take(240),
            scope = scope,
            identity = identity,
            capability = capability
        )
        sendPermissionPrompt(req.id, req.tool, req.detail, false)
        return JSONObject()
            .put("id", req.id)
            .put("status", req.status)
            .put("tool", req.tool)
            .put("detail", req.detail)
            .put("scope", req.scope)
            .put("created_at", req.createdAt)
            .put("identity", req.identity)
            .put("capability", req.capability)
    }

    private fun handlePipDownload(session: IHTTPSession, payload: JSONObject): Response {

        val pkgsJson = payload.optJSONArray("packages")
        val pkgs = mutableListOf<String>()
        if (pkgsJson != null) {
            for (i in 0 until pkgsJson.length()) {
                val p = pkgsJson.optString(i, "").trim()
                if (p.isNotBlank()) pkgs.add(p)
            }
        } else {
            val spec = payload.optString("spec", "").trim()
            if (spec.isNotBlank()) pkgs.addAll(spec.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() })
        }
        if (pkgs.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "packages_required")
        }

        val withDeps = payload.optBoolean("with_deps", true)
        val onlyBinary = payload.optBoolean("only_binary", true)
        val indexUrl = payload.optString("index_url", "").trim()
        val extraIndexUrls = payload.optJSONArray("extra_index_urls")
        val trustedHosts = payload.optJSONArray("trusted_hosts")

        val detail = "pip download: " + pkgs.joinToString(" ").take(180)
        val perm = ensurePipPermission(session, payload, capability = "pip.download", detail = detail)
        if (!perm.first) return perm.second!!

        val args = mutableListOf(
            "download",
            "--disable-pip-version-check",
            "--no-input",
            "--dest",
            File(context.filesDir, "pip_downloads").also { it.mkdirs() }.absolutePath
        )
        if (!withDeps) {
            args.add("--no-deps")
        }
        if (onlyBinary) {
            args.add("--only-binary=:all:")
            args.add("--prefer-binary")
        }
        if (indexUrl.isNotBlank()) {
            args.add("--index-url")
            args.add(indexUrl)
        }
        if (extraIndexUrls != null) {
            for (i in 0 until extraIndexUrls.length()) {
                val u = extraIndexUrls.optString(i, "").trim()
                if (u.isNotBlank()) {
                    args.add("--extra-index-url")
                    args.add(u)
                }
            }
        }
        if (trustedHosts != null) {
            for (i in 0 until trustedHosts.length()) {
                val h = trustedHosts.optString(i, "").trim()
                if (h.isNotBlank()) {
                    args.add("--trusted-host")
                    args.add(h)
                }
            }
        }
        args.addAll(pkgs)

        if (runtimeManager.getStatus() != "ok") {
            runtimeManager.startWorker()
            waitForTermuxHealth(5000)
        }
        val proxied = proxyShellExecToWorker("pip", args.joinToString(" "), "")
        if (proxied != null) return proxied
        return jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
    }

    private fun handlePipInstall(session: IHTTPSession, payload: JSONObject): Response {

        val pkgsJson = payload.optJSONArray("packages")
        val pkgs = mutableListOf<String>()
        if (pkgsJson != null) {
            for (i in 0 until pkgsJson.length()) {
                val p = pkgsJson.optString(i, "").trim()
                if (p.isNotBlank()) pkgs.add(p)
            }
        } else {
            val spec = payload.optString("spec", "").trim()
            if (spec.isNotBlank()) pkgs.addAll(spec.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() })
        }
        if (pkgs.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "packages_required")
        }

        val allowNetwork = payload.optBoolean("allow_network", false)
        val onlyBinary = payload.optBoolean("only_binary", true)
        val upgrade = payload.optBoolean("upgrade", true)
        val noDeps = payload.optBoolean("no_deps", false)
        val indexUrl = payload.optString("index_url", "").trim()
        val extraIndexUrls = payload.optJSONArray("extra_index_urls")
        val trustedHosts = payload.optJSONArray("trusted_hosts")

        val mode = if (allowNetwork) "network" else "offline"
        val detail = "pip install ($mode): " + pkgs.joinToString(" ").take(180)
        val permCap = if (allowNetwork) "pip.install.network" else "pip.install.offline"
        val perm = ensurePipPermission(session, payload, capability = permCap, detail = detail)
        if (!perm.first) return perm.second!!

        val args = mutableListOf(
            "install",
            "--disable-pip-version-check",
            "--no-input"
        )
        if (!allowNetwork) {
            args.add("--no-index")
        }
        if (onlyBinary) {
            args.add("--only-binary=:all:")
            args.add("--prefer-binary")
        }
        if (upgrade) {
            args.add("--upgrade")
        }
        if (noDeps) {
            args.add("--no-deps")
        }
        if (allowNetwork && indexUrl.isNotBlank()) {
            args.add("--index-url")
            args.add(indexUrl)
        }
        if (allowNetwork && extraIndexUrls != null) {
            for (i in 0 until extraIndexUrls.length()) {
                val u = extraIndexUrls.optString(i, "").trim()
                if (u.isNotBlank()) {
                    args.add("--extra-index-url")
                    args.add(u)
                }
            }
        }
        if (allowNetwork && trustedHosts != null) {
            for (i in 0 until trustedHosts.length()) {
                val h = trustedHosts.optString(i, "").trim()
                if (h.isNotBlank()) {
                    args.add("--trusted-host")
                    args.add(h)
                }
            }
        }
        args.addAll(pkgs)

        if (runtimeManager.getStatus() != "ok") {
            runtimeManager.startWorker()
            waitForTermuxHealth(5000)
        }
        val proxied = proxyShellExecToWorker("pip", args.joinToString(" "), "")
        if (proxied != null) return proxied
        return jsonError(Response.Status.SERVICE_UNAVAILABLE, "termux_unavailable")
    }

    private fun handleWebSearch(session: IHTTPSession, payload: JSONObject): Response {
        val q = payload.optString("q", payload.optString("query", "")).trim()
        if (q.isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "query_required")
        }
        val maxResults = payload.optInt("max_results", payload.optInt("limit", 5)).coerceIn(1, 10)
        val headerIdentity =
            ((session.headers["x-methings-identity"] ?: session.headers["x-methings-identity"]) ?: "").trim()
        val identity = payload.optString("identity", "").trim().ifBlank { headerIdentity }.ifBlank { installIdentity.get() }
        var permissionId = payload.optString("permission_id", "")

        if (!isPermissionApproved(permissionId, consume = true) && identity.isNotBlank()) {
            val reusable = permissionStore.findReusableApproved(
                tool = "network",
                scope = "session",
                identity = identity,
                capability = "web.search"
            )
            if (reusable != null) {
                permissionId = reusable.id
            }
        }

        if (!isPermissionApproved(permissionId, consume = true)) {
            val existing = if (identity.isNotBlank()) permissionStore.findRecentPending(
                tool = "network", identity = identity, capability = "web.search"
            ) else null
            val req = existing ?: permissionStore.create(
                tool = "network",
                detail = "Search: " + q.take(200),
                // Searching is typically iterative; don't re-prompt for every query.
                scope = "session",
                identity = identity,
                capability = "web.search"
            )
            sendPermissionPrompt(req.id, req.tool, req.detail, false)
            val requestJson = JSONObject()
                .put("id", req.id)
                .put("status", req.status)
                .put("tool", req.tool)
                .put("detail", req.detail)
                .put("scope", req.scope)
                .put("created_at", req.createdAt)
                .put("identity", req.identity)
                .put("capability", req.capability)
            val out = JSONObject()
                .put("status", "permission_required")
                .put("request", requestJson)
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", out.toString())
        }

        // Provider selection:
        // - auto (default): use Brave Search API only if the user configured an API key, else fall back to DuckDuckGo
        //   Instant Answer API (not a real web search API).
        // - brave: always use Brave Search API (requires API key)
        // - mojeek: optional alternative (requires API key)
        // - duckduckgo/ddg: force Instant Answer API
        val provider = payload.optString("provider", "auto").trim().ifBlank { "auto" }.lowercase()
        val braveKey =
            (credentialStore.get("brave_search_api_key")?.value ?: credentialStore.get("brave_api_key")?.value ?: "").trim()
        val mojeekKey = (credentialStore.get("mojeek_api_key")?.value ?: "").trim()
        val isBraveConfigured = braveKey.isNotBlank()

        val useBrave = when (provider) {
            "auto" -> isBraveConfigured
            "brave" -> true
            else -> false
        }
        val useMojeek = when (provider) {
            "mojeek" -> true
            else -> false
        }

        if (useBrave) {
            if (braveKey.isBlank()) {
                return jsonError(
                    Response.Status.BAD_REQUEST,
                    "missing_brave_search_api_key",
                    JSONObject()
                        .put(
                            "hint",
                            "Store a 'brave_search_api_key' credential in vault to enable Brave Search API."
                        )
                )
            }
            return try {
                val isJapanese = Regex("[\\p{InHiragana}\\p{InKatakana}\\p{InCJK_Unified_Ideographs}]").containsMatchIn(q)
                val country = if (isJapanese) "JP" else "US"
                val searchLang = if (isJapanese) "ja" else "en"
                val uiLang = if (isJapanese) "ja-jp" else "en-us"
                val url = java.net.URL(
                    "https://api.search.brave.com/res/v1/web/search?" +
                        "q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                        "&count=" + maxResults +
                        "&offset=0" +
                        "&country=" + country +
                        "&search_lang=" + searchLang +
                        "&ui_lang=" + uiLang +
                        "&safesearch=moderate"
                )
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Subscription-Token", braveKey)
                    setRequestProperty(
                        "Accept-Language",
                        if (isJapanese) "ja-JP,ja;q=0.9,en-US;q=0.7,en;q=0.5" else "en-US,en;q=0.9"
                    )
                }
                val stream =
                    if (conn.responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val body = stream.bufferedReader().use { it.readText() }
                if (conn.responseCode !in 200..299) {
                    // In auto mode, Brave can intermittently fail (rate-limit/temporary errors).
                    // Fall back to DuckDuckGo instead of returning upstream_error immediately.
                    if (provider == "auto") {
                        val retryPayload = JSONObject(payload.toString()).put("provider", "duckduckgo")
                        if (permissionId.isNotBlank()) {
                            retryPayload.put("permission_id", permissionId)
                        }
                        return handleWebSearch(session, retryPayload)
                    }
                    if (conn.responseCode == 429) {
                        return jsonError(
                            Response.Status.TOO_MANY_REQUESTS,
                            "upstream_rate_limited",
                            JSONObject().put("status", conn.responseCode).put("detail", body.take(400))
                        )
                    }
                    return jsonError(
                        Response.Status.SERVICE_UNAVAILABLE,
                        "upstream_error",
                        JSONObject().put("status", conn.responseCode).put("detail", body.take(400))
                    )
                }
                val parsed = JSONObject(body.ifBlank { "{}" })
                val web = parsed.optJSONObject("web") ?: JSONObject()
                val arr = web.optJSONArray("results") ?: org.json.JSONArray()
                val results = org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    if (results.length() >= maxResults) break
                    val r = arr.optJSONObject(i) ?: continue
                    val u = r.optString("url", "").trim()
                    val title = r.optString("title", "").trim()
                    val desc = r.optString("description", "").trim()
                    if (u.isBlank() || title.isBlank()) continue
                    results.put(
                        JSONObject()
                            .put("title", title)
                            .put("url", u)
                            .put("snippet", desc.ifBlank { title })
                    )
                }
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("provider", "brave_search_api")
                        .put("query", q)
                        .put("abstract", JSONObject().put("heading", "").put("text", "").put("url", ""))
                        .put("results", results)
                )
            } catch (ex: java.net.SocketTimeoutException) {
                jsonError(Response.Status.SERVICE_UNAVAILABLE, "upstream_timeout")
            } catch (ex: Exception) {
                jsonError(Response.Status.INTERNAL_ERROR, "search_failed", JSONObject().put("detail", ex.message ?: ""))
            }
        }

        if (useMojeek) {
            val apiKey = mojeekKey
            if (apiKey.isBlank()) {
                return jsonError(
                    Response.Status.BAD_REQUEST,
                    "missing_mojeek_api_key",
                    JSONObject().put("hint", "Store a 'mojeek_api_key' credential in vault to enable Mojeek web search.")
                )
            }

            return try {
                val isJapanese = Regex("[\\p{InHiragana}\\p{InKatakana}\\p{InCJK_Unified_Ideographs}]").containsMatchIn(q)
                val lb = if (isJapanese) "JA" else "EN"
                val rb = if (isJapanese) "JP" else "US"
                val url = java.net.URL(
                    "https://api.mojeek.com/search?" +
                        "q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                        "&api_key=" + java.net.URLEncoder.encode(apiKey, "UTF-8") +
                        "&fmt=json" +
                        "&t=" + maxResults +
                        // Apply gentle language/location boosting; users can override by providing provider-specific
                        // parameters later if we expose them.
                        "&lb=" + lb + "&lbb=100" +
                        "&rb=" + rb + "&rbb=10"
                )
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty(
                        "Accept-Language",
                        if (isJapanese) "ja-JP,ja;q=0.9,en-US;q=0.7,en;q=0.5" else "en-US,en;q=0.9"
                    )
                }
                val stream =
                    if (conn.responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val body = stream.bufferedReader().use { it.readText() }
                if (conn.responseCode !in 200..299) {
                    if (conn.responseCode == 429) {
                        return jsonError(
                            Response.Status.TOO_MANY_REQUESTS,
                            "upstream_rate_limited",
                            JSONObject().put("status", conn.responseCode).put("detail", body.take(400))
                        )
                    }
                    return jsonError(
                        Response.Status.SERVICE_UNAVAILABLE,
                        "upstream_error",
                        JSONObject().put("status", conn.responseCode).put("detail", body.take(400))
                    )
                }

                val parsed = JSONObject(body.ifBlank { "{}" })
                val resp = parsed.optJSONObject("response") ?: JSONObject()
                val status = resp.optString("status", "")
                if (status != "OK") {
                    return jsonError(
                        Response.Status.SERVICE_UNAVAILABLE,
                        "upstream_error",
                        JSONObject().put("detail", status.ifBlank { "unknown_error" })
                    )
                }

                val results = org.json.JSONArray()
                val arr = resp.optJSONArray("results") ?: org.json.JSONArray()
                for (i in 0 until arr.length()) {
                    if (results.length() >= maxResults) break
                    val r = arr.optJSONObject(i) ?: continue
                    val u = r.optString("url", "").trim()
                    val title = r.optString("title", "").trim()
                    val desc = r.optString("desc", "").trim()
                    if (u.isBlank() || title.isBlank()) continue
                    results.put(
                        JSONObject()
                            .put("title", title)
                            .put("url", u)
                            .put("snippet", desc.ifBlank { title })
                    )
                }

                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("provider", "mojeek_search_api")
                        .put("query", q)
                        .put(
                            "abstract",
                            JSONObject().put("heading", "").put("text", "").put("url", "")
                        )
                        .put("results", results)
                )
            } catch (ex: java.net.SocketTimeoutException) {
                jsonError(Response.Status.SERVICE_UNAVAILABLE, "upstream_timeout")
            } catch (ex: Exception) {
                jsonError(Response.Status.INTERNAL_ERROR, "search_failed", JSONObject().put("detail", ex.message ?: ""))
            }
        }

        // DuckDuckGo Instant Answer API (best-effort; not a full web search API).
        // https://api.duckduckgo.com/?q=...&format=json&no_html=1&skip_disambig=1
        return try {
            val url = java.net.URL(
                "https://api.duckduckgo.com/?" +
                    "q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                    "&format=json&no_html=1&skip_disambig=1&t=methings"
            )
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.7,en;q=0.5")
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val body = stream.bufferedReader().use { it.readText() }
            if (conn.responseCode !in 200..299) {
                if (conn.responseCode == 429) {
                    return jsonError(
                        Response.Status.TOO_MANY_REQUESTS,
                        "upstream_rate_limited",
                        JSONObject().put("status", conn.responseCode).put("detail", body.take(400))
                    )
                }
                return jsonError(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "upstream_error",
                    JSONObject().put("status", conn.responseCode).put("detail", body.take(400))
                )
            }
            val parsed = JSONObject(body.ifBlank { "{}" })

            val results = org.json.JSONArray()
            fun addResult(text: String, firstUrl: String) {
                if (results.length() >= maxResults) return
                val t = text.trim()
                val u = firstUrl.trim()
                if (t.isBlank() || u.isBlank()) return
                results.put(JSONObject().put("title", t).put("url", u).put("snippet", t))
            }

            val directResults = parsed.optJSONArray("Results")
            if (directResults != null) {
                for (i in 0 until directResults.length()) {
                    val r = directResults.optJSONObject(i) ?: continue
                    addResult(r.optString("Text", ""), r.optString("FirstURL", ""))
                }
            }

            fun flattenRelated(arr: org.json.JSONArray?) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    if (results.length() >= maxResults) return
                    val o = arr.opt(i)
                    if (o is JSONObject) {
                        val topics = o.optJSONArray("Topics")
                        if (topics != null) {
                            flattenRelated(topics)
                            continue
                        }
                        addResult(o.optString("Text", ""), o.optString("FirstURL", ""))
                    }
                }
            }
            flattenRelated(parsed.optJSONArray("RelatedTopics"))

            val abstractText = parsed.optString("AbstractText", "").trim()
            val abstractUrl = parsed.optString("AbstractURL", "").trim()
            val heading = parsed.optString("Heading", "").trim()
            val abstractObj = JSONObject()
                .put("heading", heading)
                .put("text", abstractText)
                .put("url", abstractUrl)

            // DuckDuckGo IA frequently returns empty results (HTTP 202) for many queries,
            // especially non-English. As a fallback, use the autocomplete endpoint to produce
            // clickable "search result" links for suggestions.
            if (results.length() == 0 && abstractText.isBlank() && heading.isBlank()) {
                val suggestions = org.json.JSONArray()
                try {
                    val sugUrl = java.net.URL(
                        "https://duckduckgo.com/ac/?" +
                            "q=" + java.net.URLEncoder.encode(q, "UTF-8") +
                            "&type=list"
                    )
                    val sugConn = (sugUrl.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 12000
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.7,en;q=0.5")
                    }
                    val sugStream =
                        if (sugConn.responseCode in 200..299) sugConn.inputStream else (sugConn.errorStream ?: sugConn.inputStream)
                    val sugBody = sugStream.bufferedReader().use { it.readText() }
                    if (sugConn.responseCode in 200..299) {
                        val arr = org.json.JSONArray(sugBody.ifBlank { "[]" })
                        // Format: ["query", ["s1","s2",...]]
                        val list = arr.optJSONArray(1)
                        if (list != null) {
                            for (i in 0 until list.length()) {
                                if (suggestions.length() >= maxResults) break
                                val s = (list.optString(i, "") ?: "").trim()
                                if (s.isBlank()) continue
                                val u = "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(s, "UTF-8")
                                suggestions.put(JSONObject().put("title", s).put("url", u).put("snippet", s))
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                if (suggestions.length() > 0) {
                    return jsonResponse(
                        JSONObject()
                            .put("status", "ok")
                            .put("provider", "duckduckgo_autocomplete_fallback")
                            .put("query", q)
                            .put("abstract", abstractObj)
                            .put("results", suggestions)
                    )
                }
            }

            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("provider", "duckduckgo_instant_answer")
                    .put("query", q)
                    .put("abstract", abstractObj)
                    .put("results", results)
            )
        } catch (ex: java.net.SocketTimeoutException) {
            jsonError(Response.Status.SERVICE_UNAVAILABLE, "upstream_timeout")
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "search_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private data class CloudExpansion(
        val usedVaultKeys: MutableSet<String> = linkedSetOf(),
        val usedConfigKeys: MutableSet<String> = linkedSetOf(),
        val usedFiles: MutableSet<String> = linkedSetOf(),
        var uploadBytes: Long = 0L,
    )

    private fun expandTemplateString(s: String, exp: CloudExpansion): String {
        // Minimal deterministic template expansion.
        //
        // ${vault:key} -> credentialStore (secret)
        // ${config:brain.api_key|brain.base_url|brain.model|brain.vendor} -> brain SharedPreferences
        // ${file:path[:base64|text]} -> read from user:// or termux:// path
        // ICU regex on Android treats a bare '}' as syntax error; escape both braces.
        val re = Regex("\\$\\{([^}]+)\\}")
        return re.replace(s) { m ->
            val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val parts = raw.split(":", limit = 3).map { it.trim() }
            if (parts.isEmpty()) return@replace m.value
            val kind = parts[0]
            if (kind == "vault" && parts.size >= 2) {
                val key = parts[1]
                exp.usedVaultKeys.add(key)
                return@replace (credentialStore.get(key)?.value ?: "").trim()
            }
            if (kind == "config" && parts.size >= 2) {
                val key = parts[1]
                exp.usedConfigKeys.add(key)
                return@replace when (key) {
                    "brain.api_key" -> (brainPrefs.getString("api_key", "") ?: "")
                    "brain.base_url" -> (brainPrefs.getString("base_url", "") ?: "")
                    "brain.model" -> (brainPrefs.getString("model", "") ?: "")
                    "brain.vendor" -> (brainPrefs.getString("vendor", "") ?: "")
                    else -> ""
                }
            }
            if (kind == "file" && parts.size >= 2) {
                val path = parts[1].trim()
                val mode = if (parts.size >= 3) parts[2].trim().lowercase() else "base64"
                val ref = parseFsPathRef(path) ?: return@replace ""
                exp.usedFiles.add(ref.displayPath)
                return@replace try {
                    val bytes0 = readFsPathBytes(ref).first
                    val bytes: ByteArray = when (mode) {
                        "text" -> bytes0
                        "base64_raw" -> bytes0
                        else -> {
                            // Default: base64. For common image types, downscale/compress to reduce upload size.
                            val fileName = when (ref.fs) {
                                "user" -> ref.userFile?.name ?: ""
                                "termux" -> ref.termuxPath?.substringAfterLast('/') ?: ""
                                else -> ""
                            }
                            val ext = fileName.substringAfterLast('.', "").lowercase()
                            val isImg = ext in setOf("jpg", "jpeg", "png", "webp")
                            val enabled = fileTransferPrefs.getBoolean("image_resize_enabled", true)
                            if (mode == "base64" && enabled && isImg && ref.fs == "user") {
                                downscaleImageToJpeg(
                                    ref.userFile!!,
                                    maxDimPx = fileTransferPrefs.getInt("image_resize_max_dim_px", 512).coerceIn(64, 4096),
                                    jpegQuality = fileTransferPrefs.getInt("image_resize_jpeg_quality", 70).coerceIn(30, 95)
                                ) ?: bytes0
                            } else {
                                bytes0
                            }
                        }
                    }
                    exp.uploadBytes += bytes.size.toLong()
                    when (mode) {
                        "text" -> bytes.toString(Charsets.UTF_8)
                        "base64_raw" -> Base64.encodeToString(bytes, Base64.NO_WRAP)
                        else -> Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                } catch (_: Exception) {
                    ""
                }
            }
            m.value
        }
    }

    private fun downscaleImageToJpeg(src: File, maxDimPx: Int, jpegQuality: Int): ByteArray? {
        // Best-effort: decode + downscale + JPEG encode. Returns null on failure.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) return null

        val targetMax = maxDimPx.coerceIn(64, 4096)
        var sample = 1
        // Use power-of-two sampling first to keep memory low.
        while ((w0 / sample) > targetMax * 2 || (h0 / sample) > targetMax * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeFile(src.absolutePath, opts) ?: return null
        val w1 = bmp.width
        val h1 = bmp.height
        if (w1 <= 0 || h1 <= 0) {
            bmp.recycle()
            return null
        }
        val scale = minOf(1.0, targetMax.toDouble() / maxOf(w1, h1).toDouble())
        val outBmp = if (scale < 1.0) {
            val tw = maxOf(1, (w1 * scale).toInt())
            val th = maxOf(1, (h1 * scale).toInt())
            val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
            if (scaled != bmp) bmp.recycle()
            scaled
        } else {
            bmp
        }
        return try {
            val baos = ByteArrayOutputStream()
            outBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(30, 95), baos)
            baos.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            outBmp.recycle()
        }
    }

    private fun expandJsonValue(v: Any?, exp: CloudExpansion): Any? {
        return when (v) {
            null, JSONObject.NULL -> JSONObject.NULL
            is String -> expandTemplateString(v, exp)
            is JSONObject -> {
                val out = JSONObject()
                val it = v.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    out.put(k, expandJsonValue(v.opt(k), exp))
                }
                out
            }
            is org.json.JSONArray -> {
                val arr = org.json.JSONArray()
                for (i in 0 until v.length()) {
                    arr.put(expandJsonValue(v.opt(i), exp))
                }
                arr
            }
            else -> v
        }
    }

    private fun isBlockedCloudHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isBlank()) return true
        if (h == "localhost") return true
        if (h == "127.0.0.1" || h == "::1") return true
        return false
    }

    private fun isPrivateAddress(addr: InetAddress): Boolean {
        return addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
    }

    private fun ensureCloudPermission(
        session: IHTTPSession,
        payload: JSONObject,
        tool: String,
        capability: String,
        detail: String
    ): Pair<Boolean, Response?> {
        val headerIdentity =
            ((session.headers["x-methings-identity"] ?: session.headers["x-methings-identity"]) ?: "").trim()
        val identity = payload.optString("identity", "").trim().ifBlank { headerIdentity }.ifBlank { installIdentity.get() }
        var permissionId = payload.optString("permission_id", "").trim()

        // Cloud calls are "ask once per session" regardless of remember-approvals UI.
        val scope = "session"
        val consume = scope == "once"

        if (!isPermissionApproved(permissionId, consume = consume) && identity.isNotBlank()) {
            val reusable = permissionStore.findReusableApproved(
                tool = tool,
                scope = scope,
                identity = identity,
                capability = capability
            )
            if (reusable != null) {
                permissionId = reusable.id
            }
        }

        if (!isPermissionApproved(permissionId, consume = consume)) {
            if (permissionPrefs.dangerouslySkipPermissions()) {
                autoApprovePermission(
                    tool = tool,
                    detail = detail,
                    scope = scope,
                    identity = identity,
                    capability = capability
                )
                return Pair(true, null)
            }
            val existing = if (identity.isNotBlank()) permissionStore.findRecentPending(
                tool = tool, identity = identity, capability = capability
            ) else null
            val req = existing ?: permissionStore.create(
                tool = tool,
                detail = detail.take(240),
                scope = scope,
                identity = identity,
                capability = capability
            )
            sendPermissionPrompt(req.id, req.tool, req.detail, false)
            val requestJson = JSONObject()
                .put("id", req.id)
                .put("status", req.status)
                .put("tool", req.tool)
                .put("detail", req.detail)
                .put("scope", req.scope)
                .put("created_at", req.createdAt)
                .put("identity", req.identity)
                .put("capability", req.capability)
            val out = JSONObject()
                .put("status", "permission_required")
                .put("request", requestJson)
            return Pair(false, newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", out.toString()))
        }

        return Pair(true, null)
    }

    private fun handleCloudRequest(session: IHTTPSession, payload: JSONObject): Response {
        val method = payload.optString("method", "POST").trim().uppercase().ifBlank { "POST" }
        val rawUrl = payload.optString("url", "").trim()
        if (rawUrl.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "url_required")

        val exp = CloudExpansion()
        val urlExpanded = expandTemplateString(rawUrl, exp)
        val uri = runCatching { URI(urlExpanded) }.getOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_url")
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme != "https" && !(scheme == "http" && payload.optBoolean("allow_insecure_http", false))) {
            return jsonError(Response.Status.BAD_REQUEST, "scheme_not_allowed")
        }
        val host = (uri.host ?: "").trim()
        if (isBlockedCloudHost(host)) return jsonError(Response.Status.BAD_REQUEST, "host_not_allowed")
        try {
            val resolved = InetAddress.getAllByName(host)
            if (resolved.any { isPrivateAddress(it) }) {
                return jsonError(Response.Status.BAD_REQUEST, "host_private_not_allowed")
            }
        } catch (_: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "host_resolve_failed")
        }

        val headersIn = payload.optJSONObject("headers") ?: JSONObject()
        val headersOut = JSONObject()
        val headerKeys = headersIn.keys()
        while (headerKeys.hasNext()) {
            val k = headerKeys.next()
            val v = headersIn.optString(k, "")
            headersOut.put(k, expandTemplateString(v, exp))
        }

        // Request body supports:
        // - json: any JSON value (object/array/string/number/bool/null)
        // - body: string (raw body) OR JSON object/array (treated like json)
        val jsonBodyFromJson = payload.opt("json")
        val rawBodyAny = payload.opt("body")
        val jsonBody = when {
            jsonBodyFromJson != null && jsonBodyFromJson != JSONObject.NULL -> jsonBodyFromJson
            rawBodyAny is JSONObject || rawBodyAny is org.json.JSONArray -> rawBodyAny
            else -> null
        }
        val bodyStr = if (rawBodyAny is String) rawBodyAny else ""
        val bodyB64 = payload.optString("body_base64", "")

        var outBytes: ByteArray? = null
        var contentType: String? = null
        if (jsonBody != null && jsonBody != JSONObject.NULL) {
            val expanded = expandJsonValue(jsonBody, exp)
            val txt = when (expanded) {
                is JSONObject -> expanded.toString()
                is org.json.JSONArray -> expanded.toString()
                is String -> expanded
                else -> (JSONObject.wrap(expanded) ?: JSONObject.NULL).toString()
            }
            outBytes = txt.toByteArray(Charsets.UTF_8)
            exp.uploadBytes += outBytes.size.toLong()
            contentType = "application/json; charset=utf-8"
        } else if (bodyB64.isNotBlank()) {
            val b64Expanded = expandTemplateString(bodyB64, exp)
            outBytes = runCatching { Base64.decode(b64Expanded, Base64.DEFAULT) }.getOrNull()
                ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_body_base64")
            exp.uploadBytes += outBytes.size.toLong()
            contentType = payload.optString("content_type", "").trim().ifBlank { "application/octet-stream" }
        } else if (bodyStr.isNotBlank()) {
            val expanded = expandTemplateString(bodyStr, exp)
            outBytes = expanded.toByteArray(Charsets.UTF_8)
            exp.uploadBytes += outBytes.size.toLong()
            contentType = payload.optString("content_type", "").trim().ifBlank { "text/plain; charset=utf-8" }
        }

        val missingVault = exp.usedVaultKeys.filter { (credentialStore.get(it)?.value ?: "").trim().isBlank() }
        if (missingVault.isNotEmpty()) {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "missing_vault_key",
                JSONObject().put("keys", org.json.JSONArray(missingVault))
            )
        }
        if (exp.usedConfigKeys.contains("brain.api_key") && (brainPrefs.getString("api_key", "") ?: "").isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "missing_brain_api_key")
        }

        val autoMb = fileTransferPrefs.getFloat("auto_upload_no_confirm_mb", 1.0f).toDouble().coerceIn(0.0, 25.0)
        val threshold = (autoMb * 1024.0 * 1024.0).toLong().coerceIn(0L, 50L * 1024L * 1024L)
        if (exp.uploadBytes > threshold && !payload.optBoolean("confirm_large", false)) {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "confirm_large_required",
                JSONObject()
                    .put("host", host)
                    .put("upload_bytes", exp.uploadBytes)
                    .put("threshold_bytes", threshold)
            )
        }

        val tool = if (exp.usedFiles.isNotEmpty() || exp.uploadBytes > 0) "cloud.media_upload" else "cloud.http"
        val cap = tool + ":" + host
        val detail = (tool + " -> " + host + " " + method + " " + (uri.path ?: "/") + " bytes=" + exp.uploadBytes).take(220)
        val perm = ensureCloudPermission(session, payload, tool = tool, capability = cap, detail = detail)
        if (!perm.first) return perm.second!!

        // Timeout semantics:
        // - connectTimeout/readTimeout: stall detection only (if no bytes are transferred for this long).
        // - We intentionally avoid a hard "overall request deadline" so large transfers can complete
        //   as long as they keep making steady progress.
        val timeoutS = payload.optDouble("timeout_s", 45.0).coerceIn(3.0, 120.0)
        val minBytesPerSFromPrefs = fileTransferPrefs.getFloat("min_transfer_kbps", 0.0f).toDouble() * 1024.0
        val minBytesPerSFromReq = if (payload.has("min_bytes_per_s")) payload.optDouble("min_bytes_per_s", 0.0) else null
        val minBytesPerS = (minBytesPerSFromReq ?: minBytesPerSFromPrefs).coerceIn(0.0, 50.0 * 1024.0 * 1024.0)
        val minRateGraceS = payload.optDouble("min_rate_grace_s", 3.0).coerceIn(0.0, 30.0)
        val maxResp = payload.optInt("max_response_bytes", 1024 * 1024).coerceIn(16 * 1024, 5 * 1024 * 1024)

        return try {
            val startedAt = System.currentTimeMillis()
            var bytesWritten = 0L
            var bytesRead = 0L

            val urlObj = java.net.URL(urlExpanded)
            val conn = (urlObj.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = (timeoutS * 1000).toInt()
                readTimeout = (timeoutS * 1000).toInt()
                instanceFollowRedirects = false
                useCaches = false
                doInput = true
            }

            val hk = headersOut.keys()
            while (hk.hasNext()) {
                val k = hk.next()
                conn.setRequestProperty(k, headersOut.optString(k, ""))
            }

            if (outBytes != null && (method == "POST" || method == "PUT" || method == "PATCH")) {
                conn.doOutput = true
                if (!contentType.isNullOrBlank() && conn.getRequestProperty("Content-Type").isNullOrBlank()) {
                    conn.setRequestProperty("Content-Type", contentType)
                }
                // Chunked write enables progress accounting for large uploads.
                conn.outputStream.use { os ->
                    val bufSize = 64 * 1024
                    var off = 0
                    val n = outBytes.size
                    while (off < n) {
                        val len = minOf(bufSize, n - off)
                        os.write(outBytes, off, len)
                        off += len
                        bytesWritten += len.toLong()

                        if (minBytesPerS > 0.0) {
                            val elapsedS = (System.currentTimeMillis() - startedAt).toDouble() / 1000.0
                            if (elapsedS >= minRateGraceS && elapsedS > 0.0) {
                                val rate = bytesWritten.toDouble() / elapsedS
                                if (rate < minBytesPerS) {
                                    throw java.net.SocketTimeoutException("upload_slow bytes_written=$bytesWritten rate_bps=$rate min_bps=$minBytesPerS")
                                }
                            }
                        }
                    }
                    os.flush()
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val out = java.io.ByteArrayOutputStream(minOf(maxResp, 256 * 1024))
            var truncated = false
            stream?.use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = inp.read(buf)
                    if (r <= 0) break
                    bytesRead += r.toLong()
                    if (out.size() + r > maxResp) {
                        val keep = maxResp - out.size()
                        if (keep > 0) {
                            out.write(buf, 0, keep)
                        }
                        truncated = true
                        break
                    }
                    out.write(buf, 0, r)

                    if (minBytesPerS > 0.0) {
                        val elapsedS = (System.currentTimeMillis() - startedAt).toDouble() / 1000.0
                        if (elapsedS >= minRateGraceS && elapsedS > 0.0) {
                            val rate = bytesRead.toDouble() / elapsedS
                            if (rate < minBytesPerS) {
                                throw java.net.SocketTimeoutException("download_slow bytes_read=$bytesRead rate_bps=$rate min_bps=$minBytesPerS")
                            }
                        }
                    }
                }
            }
            val slice = out.toByteArray()
            val ct = (conn.contentType ?: "").trim()
            val isJson = ct.lowercase().contains("application/json")

            val bodyOut = JSONObject()
                .put("status", "ok")
                .put("http_status", code)
                .put("content_type", ct)
                .put("truncated", truncated)
                .put("bytes", bytesRead)
                .put("upload_bytes", bytesWritten)
                .put("elapsed_ms", System.currentTimeMillis() - startedAt)
                .put("host", host)
                .put("used_files", org.json.JSONArray(exp.usedFiles.toList()))
            if (isJson) {
                val txt = slice.toString(Charsets.UTF_8)
                val parsedObj = runCatching { JSONObject(txt) }.getOrNull()
                val parsedArr = if (parsedObj == null) runCatching { org.json.JSONArray(txt) }.getOrNull() else null
                if (parsedObj != null) {
                    bodyOut.put("json", parsedObj)
                } else if (parsedArr != null) {
                    bodyOut.put("json", parsedArr)
                } else {
                    bodyOut.put("text", txt.take(20000))
                }
            } else {
                bodyOut.put("text", slice.toString(Charsets.UTF_8).take(20000))
            }
            jsonResponse(bodyOut)
        } catch (ex: java.net.SocketTimeoutException) {
            jsonError(Response.Status.SERVICE_UNAVAILABLE, "upstream_timeout")
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "cloud_request_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun proxyShellExecToWorker(cmd: String, args: String, cwd: String): Response? {
        val payload = JSONObject()
            .put("cmd", cmd)
            .put("args", args)
            .put("cwd", cwd)
        return proxyWorkerRequest("/shell/exec", "POST", payload.toString())
    }

    private fun proxyWorkerRequest(
        path: String,
        method: String,
        body: String? = null,
        query: String? = null,
        readTimeoutMs: Int = 5000,
    ): Response? {
        return try {
            val fullPath = if (!query.isNullOrBlank()) "$path?$query" else path
            val url = java.net.URL("http://127.0.0.1:8776$fullPath")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 2000
            conn.readTimeout = readTimeoutMs
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val out = body ?: "{}"
                conn.outputStream.use { it.write(out.toByteArray(Charsets.UTF_8)) }
            }
            // Some servers may not populate errorStream; fall back to inputStream so callers
            // still receive a meaningful error body.
            val stream = if (conn.responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            newFixedLengthResponse(
                Response.Status.lookup(conn.responseCode) ?: Response.Status.OK,
                "application/json",
                responseBody
            )
        } catch (_: Exception) {
            null
        }
    }

    // --- Brain config (SharedPreferences) ---
    private val brainPrefs by lazy {
        context.getSharedPreferences("brain_config", Context.MODE_PRIVATE)
    }

    // --- File transfer prefs (cloud uploads + me.me transfers) ---
    private val fileTransferPrefs by lazy {
        context.getSharedPreferences("file_transfer_prefs", Context.MODE_PRIVATE)
    }

    // --- Task completion notification prefs ---
    private val taskCompletionPrefs by lazy {
        context.getSharedPreferences("task_completion_prefs", Context.MODE_PRIVATE)
    }

    private fun readMemory(): String {
        val file = File(File(context.filesDir, "user"), "MEMORY.md")
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }

    private fun writeMemory(content: String) {
        val userDir = File(context.filesDir, "user")
        userDir.mkdirs()
        File(userDir, "MEMORY.md").writeText(content, Charsets.UTF_8)
    }

    private fun buildSystemPrompt(): String {
        val memory = readMemory().trim()
        return BRAIN_SYSTEM_PROMPT + if (memory.isEmpty()) "(empty)" else memory
    }

    private fun handleBrainConfigGet(): Response {
        val vendor = brainPrefs.getString("vendor", "") ?: ""
        val baseUrl = brainPrefs.getString("base_url", "") ?: ""
        val model = brainPrefs.getString("model", "") ?: ""
        val hasKey = !brainPrefs.getString("api_key", "").isNullOrEmpty()
        return jsonResponse(
            JSONObject()
                .put("vendor", vendor)
                .put("base_url", baseUrl)
                .put("model", model)
                .put("has_api_key", hasKey)
        )
    }

    private fun sanitizeVendor(vendor: String): String {
        val v = vendor.trim().lowercase(Locale.US)
        if (v.isBlank()) return "custom"
        return v.replace(Regex("[^a-z0-9_\\-]"), "_")
    }

    private fun shortHashHex(s: String): String {
        return try {
            val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            dig.take(6).joinToString("") { b -> "%02x".format(b) } // 12 hex chars
        } catch (_: Exception) {
            val h = s.hashCode().toUInt().toString(16)
            h.padStart(12, '0').take(12)
        }
    }

    private fun brainKeySlotFor(vendor: String, baseUrl: String): String {
        val v = sanitizeVendor(vendor)
        val b = baseUrl.trim().trimEnd('/').lowercase(Locale.US)
        val hx = shortHashHex(v + "|" + b)
        return "api_key_for_${v}_${hx}"
    }

    private fun handleBrainConfigSet(body: String): Response {
        val payload = runCatching { JSONObject(body) }.getOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
        val beforeVendor = (brainPrefs.getString("vendor", "") ?: "").trim()
        val beforeBase = (brainPrefs.getString("base_url", "") ?: "").trim().trimEnd('/')

        val afterVendor = if (payload.has("vendor")) payload.optString("vendor", "").trim() else beforeVendor
        val afterBase = if (payload.has("base_url")) payload.optString("base_url", "").trim().trimEnd('/') else beforeBase

        val editor = brainPrefs.edit()
        if (payload.has("vendor")) editor.putString("vendor", afterVendor)
        if (payload.has("base_url")) editor.putString("base_url", afterBase)
        if (payload.has("model")) editor.putString("model", payload.optString("model", "").trim())

        val vendorChanged = !beforeVendor.equals(afterVendor, ignoreCase = true)
        val baseChanged = !beforeBase.equals(afterBase, ignoreCase = true)
        val apiKeyProvided = payload.has("api_key")

        if (apiKeyProvided) {
            val key = payload.optString("api_key", "").trim()
            editor.putString("api_key", key)
            // Store per-provider key (vendor + base_url) so switching presets restores it.
            val slot = brainKeySlotFor(afterVendor, afterBase)
            editor.putString(slot, key)
        } else if (vendorChanged || baseChanged) {
            // If switching provider without specifying a key, restore any previously saved key for that provider.
            val slot = brainKeySlotFor(afterVendor, afterBase)
            val restored = (brainPrefs.getString(slot, "") ?: "").trim()
            if (restored.isNotBlank()) {
                editor.putString("api_key", restored)
            }
        }

        editor.apply()
        return handleBrainConfigGet()
    }

    private fun handleBrainAgentBootstrap(): Response {
        val vendor = brainPrefs.getString("vendor", "")?.trim().orEmpty()
        val baseUrl = brainPrefs.getString("base_url", "")?.trim()?.trimEnd('/').orEmpty()
        val model = brainPrefs.getString("model", "")?.trim().orEmpty()
        val apiKey = brainPrefs.getString("api_key", "")?.trim().orEmpty()

        if (baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "brain_not_configured")
        }
        // NOTE: Anthropic is now supported natively — no vendor rejection.

        val providerUrl = agentConfigManager.resolveProviderUrl(vendor, baseUrl)
        val runtime = getOrCreateAgentRuntime()
        val result = runtime.start()

        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("provider_url", providerUrl)
                .put("model", model)
                .put("native", true)
        )
    }

    private fun handleBrainChat(body: String): Response {
        val payload = runCatching { JSONObject(body) }.getOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
        val messages = payload.optJSONArray("messages")
        if (messages == null || messages.length() == 0) {
            return jsonError(Response.Status.BAD_REQUEST, "missing_messages")
        }

        val vendor = brainPrefs.getString("vendor", "") ?: ""
        val baseUrl = brainPrefs.getString("base_url", "") ?: ""
        val model = brainPrefs.getString("model", "") ?: ""
        val apiKey = brainPrefs.getString("api_key", "") ?: ""
        if (baseUrl.isEmpty() || model.isEmpty() || apiKey.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "brain_not_configured")
        }

        val isAnthropic = vendor == "anthropic"

        val url: String
        val reqBody: JSONObject
        if (isAnthropic) {
            url = baseUrl.trimEnd('/') + "/v1/messages"
            // Extract system message if present
            val chatMessages = org.json.JSONArray()
            var systemText = ""
            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                if (msg.optString("role") == "system") {
                    systemText = msg.optString("content", "")
                } else {
                    chatMessages.put(msg)
                }
            }
            val fullSystem = if (systemText.isNotEmpty()) {
                buildSystemPrompt() + "\n\n---\n\n" + systemText
            } else {
                buildSystemPrompt()
            }
            reqBody = JSONObject()
                .put("model", model)
                .put("max_tokens", 8192)
                .put("system", fullSystem)
                .put("messages", chatMessages)
                .put("stream", true)
        } else {
            // OpenAI Responses API
            url = baseUrl.trimEnd('/') + "/responses"
            reqBody = JSONObject()
                .put("model", model)
                .put("instructions", buildSystemPrompt())
                .put("input", messages)
                .put("stream", true)
        }
        val reqBytes = reqBody.toString().toByteArray(Charsets.UTF_8)

        val pipeIn = java.io.PipedInputStream(8192)
        val pipeOut = java.io.PipedOutputStream(pipeIn)

        Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (isAnthropic) {
                    conn.setRequestProperty("x-api-key", apiKey)
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                }
                conn.outputStream.use { it.write(reqBytes) }

                if (conn.responseCode !in 200..299) {
                    val errorBody = (conn.errorStream ?: conn.inputStream)
                        .bufferedReader().use { it.readText().take(500) }
                    val errorEvent = "data: " + JSONObject()
                        .put("error", "upstream_error")
                        .put("status", conn.responseCode)
                        .put("detail", errorBody)
                        .toString() + "\n\n"
                    pipeOut.write(errorEvent.toByteArray(Charsets.UTF_8))
                    pipeOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                    pipeOut.flush()
                    pipeOut.close()
                    return@Thread
                }

                conn.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    var currentEventType = ""
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        // Track SSE event type (used by Responses API)
                        if (l.startsWith("event: ") || l.startsWith("event:")) {
                            currentEventType = l.removePrefix("event:").trim()
                            continue
                        }
                        if (!l.startsWith("data: ") && !l.startsWith("data:")) continue
                        val dataStr = l.removePrefix("data:").trim()
                        if (dataStr == "[DONE]") {
                            pipeOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                            pipeOut.flush()
                            break
                        }
                        try {
                            val chunk = JSONObject(dataStr)
                            val content: String? = if (isAnthropic) {
                                // Anthropic: {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}
                                val type = chunk.optString("type")
                                if (type == "content_block_delta") {
                                    chunk.optJSONObject("delta")?.optString("text", "")
                                } else if (type == "message_stop") {
                                    pipeOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                                    pipeOut.flush()
                                    break
                                } else null
                            } else {
                                // OpenAI Responses API:
                                // event: response.output_text.delta
                                // data: {"type":"response.output_text.delta","delta":"..."}
                                // event: response.completed
                                // data: {"type":"response.completed",...}
                                val type = chunk.optString("type", currentEventType)
                                if (type == "response.output_text.delta") {
                                    chunk.optString("delta", "")
                                } else if (type == "response.completed" || type == "response.done") {
                                    pipeOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                                    pipeOut.flush()
                                    break
                                } else null
                            }
                            if (!content.isNullOrEmpty()) {
                                val sseEvent = "data: " + JSONObject()
                                    .put("content", content)
                                    .toString() + "\n\n"
                                pipeOut.write(sseEvent.toByteArray(Charsets.UTF_8))
                                pipeOut.flush()
                            }
                        } catch (_: Exception) {
                            continue
                        }
                        currentEventType = ""
                    }
                }
                // Ensure DONE is sent if stream ended without explicit marker
                try {
                    pipeOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                    pipeOut.flush()
                } catch (_: Exception) {}
            } catch (ex: java.net.SocketTimeoutException) {
                try {
                    val ev = "data: " + JSONObject().put("error", "upstream_timeout").toString() + "\n\n"
                    pipeOut.write(ev.toByteArray(Charsets.UTF_8))
                    pipeOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                    pipeOut.flush()
                } catch (_: Exception) {}
            } catch (ex: java.net.ConnectException) {
                try {
                    val ev = "data: " + JSONObject().put("error", "upstream_unreachable").toString() + "\n\n"
                    pipeOut.write(ev.toByteArray(Charsets.UTF_8))
                    pipeOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                    pipeOut.flush()
                } catch (_: Exception) {}
            } catch (ex: Exception) {
                Log.w(TAG, "Brain chat failed", ex)
                try {
                    val ev = "data: " + JSONObject().put("error", "internal_error")
                        .put("detail", ex.message ?: "").toString() + "\n\n"
                    pipeOut.write(ev.toByteArray(Charsets.UTF_8))
                    pipeOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                    pipeOut.flush()
                } catch (_: Exception) {}
            } finally {
                try { pipeOut.close() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()

        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        return response
    }

    private fun proxyGetToWorker(path: String): Response? {
        return try {
            val url = java.net.URL("http://127.0.0.1:8776$path")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            newFixedLengthResponse(
                Response.Status.lookup(conn.responseCode) ?: Response.Status.OK,
                "application/json",
                body
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun proxyPostToWorker(path: String, body: String): Response? {
        return try {
            val url = java.net.URL("http://127.0.0.1:8776$path")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseBody = stream.bufferedReader().use { it.readText() }
            newFixedLengthResponse(
                Response.Status.lookup(conn.responseCode) ?: Response.Status.OK,
                "application/json",
                responseBody
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun proxyStreamToWorker(path: String, body: String): Response? {
        return try {
            val url = java.net.URL("http://127.0.0.1:8776$path")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 0
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                val errorStream = conn.errorStream ?: conn.inputStream
                val errorBody = errorStream.bufferedReader().use { it.readText() }
                return newFixedLengthResponse(
                    Response.Status.lookup(conn.responseCode) ?: Response.Status.INTERNAL_ERROR,
                    "application/json",
                    errorBody
                )
            }
            val inputStream = conn.inputStream
            val response = newChunkedResponse(Response.Status.OK, "text/event-stream", inputStream)
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "keep-alive")
            response
        } catch (ex: Exception) {
            Log.w(TAG, "Brain chat proxy failed", ex)
            null
        }
    }

    private fun proxyGetStreamFromWorker(path: String, query: String?): Response? {
        return try {
            val fullPath = if (!query.isNullOrBlank()) "$path?$query" else path
            val url = java.net.URL("http://127.0.0.1:8776$fullPath")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 0 // infinite for SSE
            if (conn.responseCode !in 200..299) {
                val errorStream = conn.errorStream ?: conn.inputStream
                val errorBody = errorStream.bufferedReader().use { it.readText() }
                return newFixedLengthResponse(
                    Response.Status.lookup(conn.responseCode) ?: Response.Status.INTERNAL_ERROR,
                    "application/json",
                    errorBody
                )
            }
            val inputStream = conn.inputStream
            val response = newChunkedResponse(Response.Status.OK, "text/event-stream", inputStream)
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "keep-alive")
            response
        } catch (ex: Exception) {
            Log.w(TAG, "Brain SSE proxy failed", ex)
            null
        }
    }

    private fun waitForTermuxHealth(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = java.net.URL("http://127.0.0.1:8776/health")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 500
                conn.readTimeout = 500
                conn.requestMethod = "GET"
                if (conn.responseCode in 200..299) {
                    return
                }
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun resolveUserPath(root: File, path: String): File? {
        if (path.isBlank()) {
            return root
        }
        val candidate = if (path.startsWith("/")) File(path) else File(root, path)
        return try {
            val canonicalRoot = root.canonicalFile
            val canonical = candidate.canonicalFile
            if (canonical.path.startsWith(canonicalRoot.path)) canonical else null
        } catch (_: Exception) {
            null
        }
    }

    private data class DebugLogOptions(
        val scope: String,
        val role: String,
        val mode: String,
        val lines: Int,
        val pid: Int,
        val tags: List<String>,
        val keywords: List<String>,
        val streamSeconds: Int
    )

    private fun handleDebugLogsExport(session: IHTTPSession, payload: JSONObject?): Response {
        return try {
            val opts = parseDebugLogOptions(session, payload)

            val userRoot = File(context.filesDir, "user").also { it.mkdirs() }
            val logsDir = File(userRoot, "logs").also { it.mkdirs() }
            val safeScope = opts.scope.replace(Regex("[^a-z0-9_-]"), "_").trim('_').ifBlank { "scope" }
            val safeRole = opts.role.replace(Regex("[^a-z0-9_-]"), "_").trim('_').ifBlank { "device" }
            val ts = System.currentTimeMillis()
            val outFile = File(logsDir, "diagnostics_${safeScope}_${safeRole}_${ts}.log")

            val report = StringBuilder()
            report.appendLine("me.things diagnostics")
            report.appendLine("generated_at_ms=$ts")
            report.appendLine("scope=${opts.scope}")
            report.appendLine("role=${opts.role}")
            report.appendLine("mode=${opts.mode}")
            report.appendLine("pid=${opts.pid}")
            report.appendLine("app=${context.packageName}")
            report.appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            if (opts.tags.isNotEmpty()) report.appendLine("tags=${opts.tags.joinToString(",")}")
            if (opts.keywords.isNotEmpty()) report.appendLine("keywords=${opts.keywords.joinToString(",")}")
            report.appendLine()
            when (opts.mode) {
                "pid" -> {
                    report.appendLine("=== pid_logcat ===")
                    report.append(filterLogLines(captureLogcat(buildLogcatDumpArgs(opts, usePid = true, useTags = false)), opts.keywords))
                }
                "tags" -> {
                    report.appendLine("=== tags_logcat ===")
                    report.append(filterLogLines(captureLogcat(buildLogcatDumpArgs(opts, usePid = false, useTags = true)), opts.keywords))
                }
                "all" -> {
                    report.appendLine("=== all_logcat ===")
                    report.append(filterLogLines(captureLogcat(buildLogcatDumpArgs(opts, usePid = false, useTags = false)), opts.keywords))
                }
                else -> {
                    report.appendLine("=== pid_logcat ===")
                    report.append(filterLogLines(captureLogcat(buildLogcatDumpArgs(opts, usePid = true, useTags = false)), opts.keywords))
                    if (opts.tags.isNotEmpty()) {
                        report.appendLine()
                        report.appendLine()
                        report.appendLine("=== tags_logcat ===")
                        report.append(filterLogLines(captureLogcat(buildLogcatDumpArgs(opts, usePid = false, useTags = true)), opts.keywords))
                    }
                }
            }

            outFile.writeText(report.toString(), Charsets.UTF_8)
            val relPath = "logs/${outFile.name}"
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("rel_path", relPath)
                    .put("abs_path", outFile.absolutePath)
                    .put("size_bytes", outFile.length())
                    .put("options", JSONObject()
                        .put("scope", opts.scope)
                        .put("role", opts.role)
                        .put("mode", opts.mode)
                        .put("lines", opts.lines)
                        .put("pid", opts.pid)
                        .put("tags", JSONArray(opts.tags))
                        .put("keywords", JSONArray(opts.keywords)))
            )
        } catch (ex: Exception) {
            Log.w(TAG, "debug logs export failed", ex)
            jsonError(
                Response.Status.INTERNAL_ERROR,
                "debug_logs_export_failed",
                JSONObject().put("detail", ex.message ?: "")
            )
        }
    }

    private fun handleDebugLogsStream(session: IHTTPSession, payload: JSONObject?): Response {
        return try {
            val opts = parseDebugLogOptions(session, payload)
            val usePid = opts.mode == "pid" || opts.mode == "pid_and_tags"
            val useTags = opts.mode == "tags" || opts.mode == "pid_and_tags"
            val cmd = buildLogcatStreamArgs(opts, usePid = usePid, useTags = useTags)
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val inPipe = java.io.PipedInputStream(128 * 1024)
            val outPipe = java.io.PipedOutputStream(inPipe)
            Thread({
                val deadline = System.currentTimeMillis() + (opts.streamSeconds.toLong() * 1000L)
                val reader = proc.inputStream.bufferedReader()
                val writer = outPipe.bufferedWriter()
                try {
                    while (System.currentTimeMillis() < deadline) {
                        if (!reader.ready()) {
                            Thread.sleep(120)
                            continue
                        }
                        val line = reader.readLine() ?: break
                        if (!matchesKeywords(line, opts.keywords)) continue
                        writer.write(line)
                        writer.newLine()
                        writer.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { writer.flush() }
                    runCatching { writer.close() }
                    runCatching { reader.close() }
                    runCatching { proc.destroy() }
                }
            }, "debug-logcat-stream").start()
            val res = newChunkedResponse(Response.Status.OK, "text/plain; charset=utf-8", inPipe)
            res.addHeader("Cache-Control", "no-store")
            res
        } catch (ex: Exception) {
            Log.w(TAG, "debug logs stream failed", ex)
            jsonError(Response.Status.INTERNAL_ERROR, "debug_logs_stream_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleDebugLogsList(session: IHTTPSession): Response {
        return try {
            val limit = firstParam(session, "limit").toIntOrNull()?.coerceIn(1, 100) ?: 20
            val logsDir = File(File(context.filesDir, "user"), "logs")
            val arr = JSONArray()
            if (logsDir.exists() && logsDir.isDirectory) {
                val files = (logsDir.listFiles() ?: emptyArray())
                    .filter { it.isFile && it.name.lowercase(Locale.US).endsWith(".log") }
                    .sortedByDescending { it.lastModified() }
                    .take(limit)
                files.forEach { f ->
                    arr.put(
                        JSONObject()
                            .put("name", f.name)
                            .put("rel_path", "logs/${f.name}")
                            .put("size_bytes", f.length())
                            .put("modified_at", f.lastModified())
                    )
                }
            }
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("items", arr)
            )
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "debug_logs_list_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleDebugLogsDeleteAll(session: IHTTPSession): Response {
        return try {
            val logsDir = File(File(context.filesDir, "user"), "logs")
            var removed = 0
            if (logsDir.exists() && logsDir.isDirectory) {
                (logsDir.listFiles() ?: emptyArray())
                    .filter { it.isFile && it.name.lowercase(Locale.US).endsWith(".log") }
                    .forEach { f ->
                        if (runCatching { f.delete() }.getOrDefault(false)) removed += 1
                    }
            }
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("removed", removed)
            )
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "debug_logs_delete_all_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun parseDebugLogOptions(session: IHTTPSession, payload: JSONObject?): DebugLogOptions {
        fun pickString(name: String, fallback: String = ""): String {
            val fromPayload = payload?.optString(name, "")?.trim().orEmpty()
            if (fromPayload.isNotBlank()) return fromPayload
            val fromQuery = firstParam(session, name)
            if (fromQuery.isNotBlank()) return fromQuery
            return fallback
        }
        fun pickInt(name: String, fallback: Int, min: Int, max: Int): Int {
            val p = payload?.optInt(name, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (p != Int.MIN_VALUE) return p.coerceIn(min, max)
            val q = firstParam(session, name).toIntOrNull()
            if (q != null) return q.coerceIn(min, max)
            return fallback
        }
        val mode = pickString("mode", "pid_and_tags")
            .lowercase(Locale.US)
            .let { if (it in setOf("pid", "tags", "all", "pid_and_tags")) it else "pid_and_tags" }
        val tags = pickString("tags")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(12)
        val keywords = pickString("keywords")
            .split(',')
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .take(10)
        val pid = pickInt("pid", android.os.Process.myPid(), 1, Int.MAX_VALUE)
        return DebugLogOptions(
            scope = pickString("scope", "generic").lowercase(Locale.US),
            role = pickString("role", "device").lowercase(Locale.US),
            mode = mode,
            lines = pickInt("lines", 3000, 200, 10000),
            pid = pid,
            tags = tags,
            keywords = keywords,
            streamSeconds = pickInt("stream_seconds", 15, 2, 120)
        )
    }

    private fun buildLogcatDumpArgs(opts: DebugLogOptions, usePid: Boolean, useTags: Boolean): List<String> {
        val out = mutableListOf("logcat", "-d", "-v", "time", "-t", opts.lines.toString())
        if (usePid) {
            out.add("--pid")
            out.add(opts.pid.toString())
        }
        if (useTags && opts.tags.isNotEmpty()) {
            out.add("-s")
            out.addAll(opts.tags)
        }
        return out
    }

    private fun buildLogcatStreamArgs(opts: DebugLogOptions, usePid: Boolean, useTags: Boolean): List<String> {
        val out = mutableListOf("logcat", "-v", "time")
        if (usePid) {
            out.add("--pid")
            out.add(opts.pid.toString())
        }
        if (useTags && opts.tags.isNotEmpty()) {
            out.add("-s")
            out.addAll(opts.tags)
        }
        return out
    }

    private fun captureLogcat(args: List<String>): String {
        return try {
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            runCatching { proc.waitFor(1500, TimeUnit.MILLISECONDS) }
            runCatching { proc.destroy() }
            out
        } catch (ex: Exception) {
            "logcat_capture_failed: ${ex.message ?: "unknown"}\n"
        }
    }

    private fun filterLogLines(text: String, keywords: List<String>): String {
        if (keywords.isEmpty()) return text
        val out = StringBuilder()
        text.lineSequence().forEach { line ->
            if (matchesKeywords(line, keywords)) out.appendLine(line)
        }
        return out.toString()
    }

    private fun matchesKeywords(line: String, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return true
        val low = line.lowercase(Locale.US)
        return keywords.any { low.contains(it) }
    }

    private fun textResponse(text: String): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "text/plain", text)
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    // ── Intent launch helpers ────────────────────────────────────────

    private fun handleIntentSend(payload: JSONObject): Response {
        val action = payload.optString("action", "").trim()
        val type = payload.optString("type", "").trim().ifBlank { null }
        val dataStr = payload.optString("data", "").trim().ifBlank { null }
        val streamRel = payload.optString("stream", "").trim().ifBlank { null }
        val extras: JSONObject? = payload.optJSONObject("extras")
        val categories: JSONArray? = payload.optJSONArray("categories")
        val pkg = payload.optString("package", "").trim().ifBlank { null }
        val useChooser = payload.optBoolean("chooser", true)
        val chooserTitle = payload.optString("chooser_title", "").trim().ifBlank { null }

        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (type != null && dataStr != null) {
                setDataAndType(Uri.parse(dataStr), type)
            } else if (dataStr != null) {
                data = Uri.parse(dataStr)
            } else if (type != null) {
                setType(type)
            }

            if (streamRel != null) {
                val ref = parseFsPathRef(streamRel)
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_stream_path")
                if (ref.fs != "user") return jsonError(Response.Status.BAD_REQUEST, "intent_stream_must_be_user_path")
                val file = ref.userFile ?: return jsonError(Response.Status.BAD_REQUEST, "stream_path_outside_user_dir")
                if (!file.exists() || !file.isFile) {
                    return jsonError(Response.Status.BAD_REQUEST, "stream_file_not_found")
                }
                val authority = context.packageName + ".fileprovider"
                val contentUri = FileProvider.getUriForFile(context, authority, file)
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            extras?.keys()?.forEach { key ->
                putExtra(key, extras.optString(key, ""))
            }

            categories?.let { cats ->
                for (i in 0 until cats.length()) {
                    addCategory(cats.optString(i))
                }
            }

            if (pkg != null) setPackage(pkg)
        }

        val launch = if (useChooser) {
            Intent.createChooser(intent, chooserTitle ?: "Share").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            intent
        }

        Handler(Looper.getMainLooper()).post { context.startActivity(launch) }
        return jsonResponse(JSONObject().put("status", "ok").put("action", action))
    }

    private fun handleIntentShareApp(): Response {
        val src = File(context.applicationInfo.sourceDir)
        val shareDir = File(context.cacheDir, "share").also { it.mkdirs() }
        val dest = File(shareDir, "methings.apk")
        src.inputStream().use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }

        val authority = context.packageName + ".fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, dest)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Share methings").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Handler(Looper.getMainLooper()).post { context.startActivity(chooser) }
        return jsonResponse(JSONObject().put("status", "ok"))
    }

    // --- SSHD routes ---

    private data class ParsedSshPublicKey(
        val type: String,
        val b64: String,
        val comment: String?
    ) {
        fun canonicalNoComment(): String = "$type $b64"
    }

    private fun routeSshd(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            uri == "/sshd/status" && session.method == Method.GET -> {
                val status = sshdManager.status()
                jsonResponse(
                    JSONObject()
                        .put("enabled", status.enabled)
                        .put("running", status.running)
                        .put("port", status.port)
                        .put("auth_mode", status.authMode)
                        .put("host", status.host)
                )
            }
            uri == "/sshd/config" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val enabled = payload.optBoolean("enabled", sshdManager.isEnabled())
                val port = if (payload.has("port")) payload.optInt("port", sshdManager.getPort()) else null
                val authMode = payload.optString("auth_mode", "").ifBlank { null }
                val status = sshdManager.updateConfig(enabled, port, authMode)
                if (authMode != null) syncAuthorizedKeys()
                jsonResponse(
                    JSONObject()
                        .put("enabled", status.enabled)
                        .put("running", status.running)
                        .put("port", status.port)
                        .put("auth_mode", status.authMode)
                )
            }
            uri == "/sshd/keys" && session.method == Method.GET -> {
                sshKeyStore.pruneExpired()
                val arr = org.json.JSONArray()
                sshKeyStore.listAll().forEach { key ->
                    arr.put(
                        JSONObject()
                            .put("fingerprint", key.fingerprint)
                            .put("key", key.key)
                            .put("label", key.label ?: "")
                            .put("expires_at", key.expiresAt ?: JSONObject.NULL)
                            .put("created_at", key.createdAt)
                    )
                }
                jsonResponse(JSONObject().put("items", arr))
            }
            uri == "/sshd/keys/add" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val key = payload.optString("key", "")
                val label = payload.optString("label", "")
                val expiresAt = if (payload.has("expires_at")) payload.optLong("expires_at", 0L) else null
                val perm = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.ssh",
                    capability = "ssh",
                    detail = "Add SSH authorized key"
                )
                if (!perm.first) return perm.second!!
                if (key.isBlank()) {
                    return badRequest("key_required")
                }
                val parsed = parseSshPublicKey(key) ?: return badRequest("invalid_public_key")
                val finalLabel = sanitizeSshKeyLabel(label).takeIf { it != null }
                    ?: sanitizeSshKeyLabel(parsed.comment ?: "")
                val entity = sshKeyStore.upsert(parsed.canonicalNoComment(), finalLabel, expiresAt)
                syncAuthorizedKeys()
                jsonResponse(
                    JSONObject()
                        .put("fingerprint", entity.fingerprint)
                        .put("status", "ok")
                )
            }
            uri == "/sshd/keys/delete" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                var fingerprint = payload.optString("fingerprint", "").trim()
                val keyRaw = payload.optString("key", "")
                val perm = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.ssh",
                    capability = "ssh",
                    detail = "Remove SSH authorized key"
                )
                if (!perm.first) return perm.second!!
                if (fingerprint.isBlank() && keyRaw.isNotBlank()) {
                    val parsed = parseSshPublicKey(keyRaw)
                    if (parsed != null) {
                        val keyEntity = sshKeyStore.findByPublicKey(parsed.canonicalNoComment())
                        if (keyEntity != null) {
                            fingerprint = keyEntity.fingerprint
                        }
                    }
                }
                if (fingerprint.isBlank()) {
                    return badRequest("fingerprint_or_key_required")
                }
                sshKeyStore.delete(fingerprint)
                syncAuthorizedKeys()
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/sshd/keys/policy" && session.method == Method.GET -> {
                jsonResponse(
                    JSONObject()
                        .put("require_biometric", sshKeyPolicy.isBiometricRequired())
                )
            }
            uri == "/sshd/keys/policy" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val requireBio = payload.optBoolean("require_biometric", sshKeyPolicy.isBiometricRequired())
                sshKeyPolicy.setBiometricRequired(requireBio)
                jsonResponse(JSONObject().put("require_biometric", sshKeyPolicy.isBiometricRequired()))
            }
            uri == "/sshd/pin/status" && session.method == Method.GET -> {
                val state = sshPinManager.status()
                if (state.expired) {
                    sshPinManager.stopPin()
                    sshdManager.exitPinMode()
                    syncAuthorizedKeys()
                } else if (!state.active && sshdManager.getAuthMode() == SshdManager.AUTH_MODE_PIN) {
                    sshdManager.exitPinMode()
                    syncAuthorizedKeys()
                }
                jsonResponse(
                    JSONObject()
                        .put("active", state.active)
                        .put("pin", state.pin ?: "")
                        .put("expires_at", state.expiresAt ?: JSONObject.NULL)
                )
            }
            uri == "/sshd/pin/start" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val seconds = payload.optInt("seconds", 10)
                val perm = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.ssh",
                    capability = "ssh",
                    detail = "Enable SSH PIN auth"
                )
                if (!perm.first) return perm.second!!
                val state = sshPinManager.startPin(seconds)
                sshdManager.enterPinMode()
                syncAuthorizedKeys()
                jsonResponse(
                    JSONObject()
                        .put("active", state.active)
                        .put("pin", state.pin ?: "")
                        .put("expires_at", state.expiresAt ?: JSONObject.NULL)
                )
            }
            uri == "/sshd/pin/stop" && session.method == Method.POST -> {
                sshPinManager.stopPin()
                sshdManager.exitPinMode()
                syncAuthorizedKeys()
                jsonResponse(JSONObject().put("active", false))
            }
            uri == "/sshd/pin/verify" && session.method == Method.GET -> {
                // Called by the methings-pin-check script running inside Termux SSH session
                val pin = session.parms["pin"] ?: ""
                val state = sshPinManager.status()
                if (state.expired || (!state.active && sshdManager.getAuthMode() == SshdManager.AUTH_MODE_PIN)) {
                    sshPinManager.stopPin()
                    sshdManager.exitPinMode()
                    syncAuthorizedKeys()
                    return jsonResponse(JSONObject().put("valid", false).put("reason", "expired"))
                }
                val valid = sshPinManager.verifyPin(pin)
                jsonResponse(JSONObject().put("valid", valid))
            }
            uri == "/sshd/noauth/status" && session.method == Method.GET -> {
                val state = sshNoAuthManager.status()
                if (state.expired) {
                    sshNoAuthManager.stop()
                    sshdManager.exitNotificationMode()
                    syncAuthorizedKeys()
                } else if (!state.active && sshdManager.getAuthMode() == SshdManager.AUTH_MODE_NOTIFICATION) {
                    sshdManager.exitNotificationMode()
                    syncAuthorizedKeys()
                }
                jsonResponse(
                    JSONObject()
                        .put("active", state.active)
                        .put("expires_at", state.expiresAt ?: JSONObject.NULL)
                )
            }
            uri == "/sshd/noauth/start" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val seconds = payload.optInt("seconds", 30)
                val perm = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.ssh",
                    capability = "ssh",
                    detail = "Enable SSH notification auth"
                )
                if (!perm.first) return perm.second!!
                val state = sshNoAuthManager.start(seconds)
                sshdManager.enterNotificationMode()
                syncAuthorizedKeys()
                jsonResponse(
                    JSONObject()
                        .put("active", state.active)
                        .put("expires_at", state.expiresAt ?: JSONObject.NULL)
                )
            }
            uri == "/sshd/noauth/stop" && session.method == Method.POST -> {
                sshNoAuthManager.stop()
                sshdManager.exitNotificationMode()
                syncAuthorizedKeys()
                jsonResponse(JSONObject().put("active", false))
            }
            uri == "/sshd/noauth/wait" && session.method == Method.GET -> {
                // Called by methings-notif-check ForceCommand script after key auth.
                // Blocks until the user taps Allow/Deny on the notification.
                val state = sshNoAuthManager.status()
                if (state.expired || (!state.active && sshdManager.getAuthMode() == SshdManager.AUTH_MODE_NOTIFICATION)) {
                    sshNoAuthManager.stop()
                    sshdManager.exitNotificationMode()
                    syncAuthorizedKeys()
                    return jsonResponse(JSONObject().put("approved", false).put("reason", "expired"))
                }
                if (!state.active) {
                    return jsonResponse(JSONObject().put("approved", false).put("reason", "inactive"))
                }
                val requestId = session.parms["id"]
                    ?: System.currentTimeMillis().toString()
                val user = session.parms["user"] ?: ""
                val approved = sshNoAuthManager.waitForApproval(requestId, user, 30_000L)
                jsonResponse(JSONObject().put("approved", approved))
            }
            uri == "/sshd/auth/keys" && session.method == Method.GET -> {
                // THE BRIDGE endpoint — called by AuthorizedKeysCommand script in Termux.
                // Returns authorized_keys-format lines based on current auth mode.
                handleAuthKeysQuery(session)
            }
            else -> notFound()
        }
    }

    /**
     * Push current DB keys to Termux's authorized_keys file.
     * Called after key add/delete and auth mode changes.
     */
    fun syncAuthorizedKeys() {
        sshKeyStore.pruneExpired()
        val keys = sshKeyStore.listActive().map { it.key }
        sshdManager.writeAuthorizedKeys(keys)
    }

    /**
     * AuthorizedKeysCommand bridge (legacy — no longer called by sshd after
     * switch to AuthorizedKeysFile, but kept for debugging).
     */
    private fun handleAuthKeysQuery(session: IHTTPSession): Response {
        val user = session.parms["user"] ?: ""
        val fp = session.parms["fp"] ?: ""
        val keyType = session.parms["type"] ?: ""
        val keyB64 = session.parms["key"] ?: ""
        val offeredKey = if (keyType.isNotBlank() && keyB64.isNotBlank()) "$keyType $keyB64" else ""

        val authMode = sshdManager.getAuthMode()

        // Helper: return DB keys (used by public_key mode and as fallback)
        fun respondWithDbKeys(): Response {
            sshKeyStore.pruneExpired()
            val now = System.currentTimeMillis()
            val active = sshKeyStore.listActive(now)
            val lines = active.mapNotNull { row ->
                val key = row.key.trim()
                if (key.isBlank()) return@mapNotNull null
                key
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                lines.joinToString("\n") + if (lines.isNotEmpty()) "\n" else ""
            )
        }

        return when (authMode) {
            SshdManager.AUTH_MODE_PUBLIC_KEY -> respondWithDbKeys()
            SshdManager.AUTH_MODE_PIN -> {
                // When PIN is not active, fall back to public_key behavior
                if (!sshPinManager.status().active) {
                    return respondWithDbKeys()
                }
                // Accept any offered key but wrap with command= to force PIN check
                if (offeredKey.isBlank()) {
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
                }
                val line = "command=\"/data/data/com.termux/files/usr/bin/methings-pin-check\" $offeredKey"
                newFixedLengthResponse(Response.Status.OK, "text/plain", line + "\n")
            }
            SshdManager.AUTH_MODE_NOTIFICATION -> {
                // When notification mode is not active, fall back to public_key behavior
                if (!sshNoAuthManager.isActive()) {
                    return respondWithDbKeys()
                }
                // Block until user approves via notification
                if (offeredKey.isBlank()) {
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "")
                }
                val requestId = fp.ifBlank { offeredKey.hashCode().toString() }
                val approved = sshNoAuthManager.waitForApproval(requestId, user, 30_000L)
                if (approved) {
                    newFixedLengthResponse(Response.Status.OK, "text/plain", offeredKey + "\n")
                } else {
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "")
                }
            }
            else -> newFixedLengthResponse(Response.Status.OK, "text/plain", "")
        }
    }

    private fun sanitizeSshKeyLabel(raw: String): String? {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isBlank()) return null
        return s.take(120)
    }

    private fun parseSshPublicKey(raw: String): ParsedSshPublicKey? {
        val line = raw.trim()
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
        if (line.isBlank()) return null
        val parts = line.split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) return null
        val type = parts[0].trim()
        val b64 = parts[1].trim()
        val comment = parts.getOrNull(2)?.trim()?.ifBlank { null }

        val t = type.lowercase(Locale.US)
        val allowed = setOf(
            "ssh-ed25519",
            "ssh-rsa",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521",
            "sk-ssh-ed25519@openssh.com",
            "sk-ecdsa-sha2-nistp256@openssh.com"
        )
        if (!allowed.contains(t)) return null

        try {
            val decoded = Base64.decode(b64, Base64.DEFAULT)
            if (decoded.isEmpty()) return null
        } catch (_: Exception) {
            return null
        }
        return ParsedSshPublicKey(type = type, b64 = b64, comment = comment)
    }

    // --- End SSHD routes ---

    private fun notFound(): Response {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
    }

    private fun badRequest(reason: String): Response {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", reason)
    }

    private fun forbidden(reason: String): Response {
        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", reason)
    }

    private fun readBody(session: IHTTPSession): String {
        // NanoHTTPD's parseBody() path can mis-decode non-ASCII JSON bodies depending on the
        // request Content-Type. For our JSON APIs, read raw bytes and decode using the declared
        // charset (default UTF-8).
        return try {
            val headers = session.headers ?: emptyMap()
            val ct = (headers["content-type"] ?: headers["Content-Type"] ?: "").trim()
            val charset = Regex("(?i)charset=([^;]+)")
                .find(ct)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.trim('"')
                ?.ifBlank { null }
                ?: "UTF-8"
            val len = (headers["content-length"] ?: headers["Content-Length"] ?: "").trim().toIntOrNull()
            val te = (headers["transfer-encoding"] ?: headers["Transfer-Encoding"] ?: "").trim().lowercase(Locale.US)
            // If Content-Length is absent and request is not chunked, treat as empty body.
            // Waiting for EOF here can hang on keep-alive clients that send no body.
            if ((len == null || len < 0) && !te.contains("chunked")) {
                return ""
            }
            val bytes = if (len != null && len >= 0) readExactly(session.inputStream, len) else session.inputStream.readBytes()
            String(bytes, java.nio.charset.Charset.forName(charset))
        } catch (_: Exception) {
            // Fallback: best-effort parseBody (also consumes request body)
            try {
                val map = HashMap<String, String>()
                session.parseBody(map)
                map["postData"] ?: ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun readExactly(input: java.io.InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(out, off, length - off)
            if (n <= 0) break
            off += n
        }
        return if (off == length) out else out.copyOf(off)
    }

    private fun isPermissionApproved(permissionId: String, consume: Boolean): Boolean {
        if (permissionId.isBlank()) return false
        val req = permissionStore.get(permissionId) ?: return false
        if (req.status != "approved") return false
        if (consume && req.scope == "once") {
            permissionStore.markUsed(permissionId)
        }
        return true
    }

    private fun runHousekeepingOnce() {
        runCatching {
            val pruned = sshKeyStore.pruneExpired()
            if (pruned > 0) {
                Log.i(TAG, "Housekeeping pruned expired SSH keys: $pruned")
            }
            cleanupExpiredMeSyncTransfers()
            cleanupExpiredMeSyncV3Tickets()
        }.onFailure {
            Log.w(TAG, "Housekeeping failed", it)
        }
    }

    private fun scheduleHousekeeping() {
        if (housekeepingFuture != null) return
        runHousekeepingOnce()
        housekeepingFuture = housekeepingScheduler.scheduleWithFixedDelay(
            { runHousekeepingOnce() },
            HOUSEKEEPING_INITIAL_DELAY_SEC,
            HOUSEKEEPING_INTERVAL_SEC,
            TimeUnit.SECONDS
        )
    }

    private fun scheduleMeMeDiscoveryLoop() {
        meMeDiscoveryFuture?.cancel(true)
        val cfg = currentMeMeConfig()
        val intervalSec = cfg.discoveryIntervalSec.coerceIn(10, 3600).toLong()
        meMeDiscoveryFuture = meMeDiscoveryScheduler.scheduleWithFixedDelay(
            { runMeMeDiscoveryTick() },
            ME_ME_DISCOVERY_INITIAL_DELAY_SEC,
            intervalSec,
            TimeUnit.SECONDS
        )
    }

    private fun scheduleMeMeConnectionCheckLoop() {
        meMeConnectionCheckFuture?.cancel(true)
        val cfg = currentMeMeConfig()
        val intervalSec = cfg.connectionCheckIntervalSec.coerceIn(5, 3600).toLong()
        meMeConnectionCheckFuture = meMeConnectionCheckScheduler.scheduleWithFixedDelay(
            { runMeMeConnectionCheckTick() },
            ME_ME_CONNECTION_CHECK_INITIAL_DELAY_SEC,
            intervalSec,
            TimeUnit.SECONDS
        )
    }

    private fun runMeMeDiscoveryTick() {
        runCatching {
            val cfg = currentMeMeConfig()
            if (!cfg.allowDiscovery) return
            if (!cfg.connectionMethods.contains("wifi") && !cfg.connectionMethods.contains("ble")) return
            val summary = meMeDiscovery.scan(currentMeMeDiscoveryConfig(cfg), ME_ME_DISCOVERY_SCAN_TIMEOUT_MS)
            handleMeMePresenceUpdates(summary.discovered, source = "me_me.scan")
            if (cfg.autoReconnect) {
                runMeMeAutoConnectFromDiscovered(summary.discovered, cfg)
            }
        }.onFailure {
            Log.w(TAG, "me.me discovery tick failed", it)
        }
    }

    private fun runMeMeConnectionCheckTick() {
        runCatching {
            if (meMeConnections.isNotEmpty()) {
                val cfg = currentMeMeConfig()
                val now = System.currentTimeMillis()
                val snapshot = meMeConnections.values.toList()
                snapshot.forEach { conn ->
                    val route = resolveMeMePeerRoute(conn.peerDeviceId)
                    var reachable = false
                    var preferredMethod = conn.method
                    if (route != null) {
                        val host = route.host.trim()
                        if (host.isNotBlank()) {
                            val lanOk = probeMeMeLanHealth(host, route.port)
                            if (lanOk) {
                                reachable = true
                                preferredMethod = "wifi"
                            } else if (route.hasBle) {
                                reachable = true
                                preferredMethod = "ble"
                            }
                        } else if (route.hasBle) {
                            reachable = true
                            preferredMethod = "ble"
                        }
                    }
                    if (!reachable) {
                        if (conn.state == "connected") {
                            updateMeMeConnectionState(conn.peerDeviceId, "disconnected", preferredMethod)
                        }
                        return@forEach
                    }
                    if (conn.state == "connected") {
                        if (preferredMethod != conn.method) {
                            updateMeMeConnectionState(conn.peerDeviceId, "connected", preferredMethod)
                        }
                        return@forEach
                    }
                    if (!cfg.autoReconnect) return@forEach
                    val lastAttempt = meMeReconnectAttemptAt[conn.peerDeviceId] ?: 0L
                    val minGapMs = cfg.reconnectIntervalSec.coerceIn(3, 3600) * 1000L
                    if (now - lastAttempt < minGapMs) return@forEach
                    meMeReconnectAttemptAt[conn.peerDeviceId] = now
                    updateMeMeConnectionState(conn.peerDeviceId, "connected", preferredMethod)
                }
            }
            maybePullMeMeRelayEventsFromGateway(force = false)
        }.onFailure {
            Log.w(TAG, "me.me connection check tick failed", it)
        }
    }

    private fun probeMeMeLanHealth(host: String, port: Int): Boolean {
        return runCatching {
            val conn = (URI("http://$host:$port/health").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("Connection", "close")
            }
            val code = conn.responseCode
            code in 200..299
        }.getOrDefault(false)
    }

    private fun updateMeMeConnectionState(peerDeviceId: String, newState: String, method: String? = null) {
        val prev = meMeConnections[peerDeviceId] ?: return
        val normalizedState = newState.trim().ifBlank { prev.state }
        val normalizedMethod = method?.trim()?.ifBlank { prev.method } ?: prev.method
        if (prev.state == normalizedState && prev.method == normalizedMethod) return
        meMeConnections[peerDeviceId] = prev.copy(
            state = normalizedState,
            method = normalizedMethod
        )
    }

    private fun handleMeMePresenceUpdates(discovered: List<JSONObject>, source: String) {
        val now = System.currentTimeMillis()
        val cfg = currentMeMeConfig()
        val lostGraceMs = maxOf(
            ME_ME_DEVICE_LOST_MIN_MS,
            cfg.discoveryIntervalSec.coerceIn(10, 3600).toLong() * 3_000L
        )
        val seenIds = HashSet<String>()
        val newlyDiscovered = ArrayList<JSONObject>()
        discovered.forEach { peer ->
            val did = peer.optString("device_id", "").trim()
            if (did.isBlank()) return@forEach
            seenIds += did
            val peerName = peer.optString("device_name", "").trim()
            if (peerName.isNotBlank()) {
                maybeUpdateMeMeConnectionNameFromDiscovery(did, peerName)
            }
            val peerLastSeenAt = peer.optLong("last_seen_at", now).let { if (it > 0L) it else now }
            val prev = meMePeerPresence[did]
            if (prev == null) {
                meMePeerPresence[did] = MeMePeerPresence(
                    deviceName = peerName,
                    lastSeenAt = peerLastSeenAt,
                    online = true
                )
                newlyDiscovered += JSONObject()
                    .put("device_id", did)
                    .put("device_name", peerName)
                return@forEach
            }
            prev.deviceName = peerName.ifBlank { prev.deviceName }
            prev.lastSeenAt = maxOf(prev.lastSeenAt, peerLastSeenAt)
            if (!prev.online) {
                prev.online = true
                newlyDiscovered += JSONObject()
                    .put("device_id", did)
                    .put("device_name", prev.deviceName)
            }
        }

        val newlyLost = ArrayList<JSONObject>()
        val staleOffline = ArrayList<String>()
        for ((did, state) in meMePeerPresence.entries) {
            // Keep recent positives online. Emit "lost" only when absent for a while.
            if (seenIds.contains(did)) continue
            val silentForMs = now - state.lastSeenAt
            if (state.online && silentForMs >= lostGraceMs) {
                state.online = false
                newlyLost += JSONObject()
                    .put("device_id", did)
                    .put("device_name", state.deviceName)
            }
            // Prune long-idle offline peers to avoid unbounded map growth.
            if (!state.online && silentForMs >= ME_ME_DEVICE_PRESENCE_TTL_MS) {
                staleOffline += did
            }
        }
        staleOffline.forEach { did -> meMePeerPresence.remove(did) }

        // Send a single batched brain event instead of one per device.
        if (newlyDiscovered.isNotEmpty() || newlyLost.isNotEmpty()) {
            val devices = org.json.JSONArray()
            newlyDiscovered.forEach { devices.put(JSONObject(it.toString()).put("event", "discovered")) }
            newlyLost.forEach { devices.put(JSONObject(it.toString()).put("event", "lost")) }
            val names = newlyDiscovered.map { it.optString("device_name", it.optString("device_id", "?")) }
            val lostNames = newlyLost.map { it.optString("device_name", it.optString("device_id", "?")) }
            val parts = ArrayList<String>()
            if (names.isNotEmpty()) parts += "discovered: ${names.joinToString(", ")}"
            if (lostNames.isNotEmpty()) parts += "lost: ${lostNames.joinToString(", ")}"
            notifyBrainEvent(
                name = "me.me.device.presence",
                payload = JSONObject()
                    .put("session_id", "default")
                    .put("source", source)
                    .put("ui_visible", false)
                    .put("devices", devices)
                    .put("discovered_count", newlyDiscovered.size)
                    .put("lost_count", newlyLost.size)
                    .put("summary", "Nearby devices: ${parts.joinToString("; ")}"),
                priority = "low",
                interruptPolicy = "never",
                coalesceKey = "me_me_device_presence",
                coalesceWindowMs = 60_000L
            )
        }
    }

    private fun maybeUpdateMeMeConnectionNameFromDiscovery(deviceId: String, discoveredName: String) {
        val did = deviceId.trim()
        val name = discoveredName.trim()
        if (did.isBlank() || name.isBlank()) return
        val current = meMeConnections[did] ?: return
        val currentName = current.peerDeviceName.trim()
        if (name == did && currentName.isNotBlank() && currentName != did) {
            // Keep richer handshake-provided names instead of replacing them
            // with discovery fallback identifiers.
            return
        }
        if (current.peerDeviceName.trim() == name) return
        meMeConnections[did] = current.copy(peerDeviceName = name)
    }

    private fun runMeMeAutoConnectFromDiscovered(discovered: List<JSONObject>, cfg: MeMeConfig) {
        if (discovered.isEmpty()) return
        if (meMeConnections.size >= cfg.maxConnections) return
        val now = System.currentTimeMillis()
        val minGapMs = cfg.reconnectIntervalSec.coerceIn(3, 3600) * 1000L
        var attempts = 0
        discovered.forEach { peer ->
            if (attempts >= ME_ME_AUTO_CONNECT_MAX_ATTEMPTS_PER_TICK) return@forEach
            val did = peer.optString("device_id", "").trim()
            if (did.isBlank() || did == cfg.deviceId) return@forEach
            if (meMeConnections.containsKey(did)) return@forEach
            if (cfg.blockedDevices.contains(did)) return@forEach
            if (cfg.allowedDevices.isNotEmpty() && !cfg.allowedDevices.contains(did) && !isProvisionedSibling(did)) return@forEach
            if (meMeConnections.size >= cfg.maxConnections) return@forEach
            val hasPending = meMeConnectIntents.values.any { !it.accepted && it.targetDeviceId == did && it.expiresAt > now }
            if (hasPending) return@forEach
            val lastAttempt = meMeReconnectAttemptAt[did] ?: 0L
            if ((now - lastAttempt) < minGapMs) return@forEach
            meMeReconnectAttemptAt[did] = now
            attempts += 1
            val payload = JSONObject()
                .put("target_device_id", did)
                .put("method", "auto")
                .put("auto_scan", false)
                .put("auto_delivery_retries", 1)
                .put("auto_delivery_retry_delay_ms", ME_ME_AUTO_CONNECT_RETRY_DELAY_MS)
                .put("connect_wait_budget_ms", ME_ME_AUTO_CONNECT_WAIT_BUDGET_MS)
                .put("auto_delivery_settle_wait_ms", ME_ME_AUTO_CONNECT_SETTLE_WAIT_MS)
                .put("suppress_pending_on_delivery_failure", true)
            runCatching { handleMeMeConnect(payload) }
                .onFailure { Log.w(TAG, "me.me auto-connect attempt failed for $did", it) }
        }
    }

    private fun handleMeSyncStatus(): Response {
        cleanupExpiredMeSyncTransfers()
        val arr = org.json.JSONArray()
        val now = System.currentTimeMillis()
        meSyncTransfers.values.sortedByDescending { it.createdAt }.forEach { tr ->
            arr.put(
                JSONObject()
                    .put("id", tr.id)
                    .put("created_at", tr.createdAt)
                    .put("expires_at", tr.expiresAt)
                    .put("expired", tr.expiresAt in 1..now)
                    .put("download_count", tr.downloadCount)
                    .put("transmitting", tr.transmitting)
                    .put("bytes_sent", tr.bytesSent)
                    .put("transfer_started_at", if (tr.transferStartedAt > 0L) tr.transferStartedAt else JSONObject.NULL)
                    .put("transfer_completed_at", if (tr.transferCompletedAt > 0L) tr.transferCompletedAt else JSONObject.NULL)
                    .put("size", if (tr.file != null) tr.file.length() else JSONObject.NULL)
                    .put("mode", if (tr.includeIdentity) "migration" else "export")
                    .put("me_sync_uri", tr.meSyncUri)
            )
        }
        return jsonResponse(JSONObject().put("status", "ok").put("items", arr))
    }

    private fun handleMeSyncProgress(): Response {
        val now = System.currentTimeMillis()
        val progress = synchronized(meSyncImportProgressLock) { meSyncImportProgress }
        val sticky = progress.state == "running" || (progress.state != "idle" && (now - progress.updatedAt) <= ME_SYNC_PROGRESS_STICKY_MS)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("active", sticky)
                .put("import", progress.toJson(now, sticky))
        )
    }

    private fun updateMeSyncImportProgress(
        state: String,
        phase: String,
        message: String,
        source: String = "",
        bytesDownloaded: Long? = null,
        bytesCopied: Long? = null,
        totalBytes: Long? = null,
        detail: String? = null
    ) {
        synchronized(meSyncImportProgressLock) {
            val now = System.currentTimeMillis()
            val prev = meSyncImportProgress
            val startedAt = when {
                state == "running" && prev.startedAt <= 0L -> now
                state == "running" && prev.state != "running" -> now
                else -> prev.startedAt
            }
            val copyStartedAt = when {
                state == "running" && (bytesCopied ?: 0L) > 0L && (prev.copyStartedAt <= 0L || prev.phase != phase) -> now
                state == "running" && prev.copyStartedAt > 0L -> prev.copyStartedAt
                else -> 0L
            }
            meSyncImportProgress = MeSyncImportProgress(
                state = state,
                phase = phase,
                message = message,
                source = source.ifBlank { prev.source },
                startedAt = startedAt,
                updatedAt = now,
                bytesDownloaded = bytesDownloaded ?: prev.bytesDownloaded,
                bytesCopied = bytesCopied ?: prev.bytesCopied,
                totalBytes = totalBytes ?: prev.totalBytes,
                copyStartedAt = copyStartedAt,
                detail = detail ?: prev.detail
            )
        }
    }

    private fun handleMeMeStatus(): Response {
        cleanupExpiredMeMeState()
        val cfg = currentMeMeConfig()
        val relayCfg = currentMeMeRelayConfig()
        val relayQueueCount = synchronized(meMeRelayEventsLock) { meMeRelayEvents.size }
        val self = JSONObject()
            .put("device_id", cfg.deviceId)
            .put("device_name", cfg.deviceName)
            .put("device_description", cfg.deviceDescription)
            .put("device_icon", cfg.deviceIcon)
            .put("allow_discovery", cfg.allowDiscovery)
            .put("connection_methods", org.json.JSONArray(cfg.connectionMethods))
        val runtime = meMeDiscovery.statusJson(currentMeMeDiscoveryConfig(cfg))
        val connectionList = meMeConnections.values
            .sortedByDescending { it.connectedAt }
        val connections = org.json.JSONArray(connectionList.map { it.toJson() })
        val connNameByDeviceId = connectionList
            .mapNotNull { conn ->
                val did = conn.peerDeviceId.trim()
                val name = conn.peerDeviceName.trim()
                if (did.isBlank() || name.isBlank()) null else did to name
            }
            .toMap()
        val discoveredRaw = runtime.optJSONArray("discovered") ?: org.json.JSONArray()
        val discovered = org.json.JSONArray()
        for (i in 0 until discoveredRaw.length()) {
            val src = discoveredRaw.optJSONObject(i)
            if (src == null) {
                discovered.put(discoveredRaw.opt(i))
                continue
            }
            val row = JSONObject(src.toString())
            val did = row.optString("device_id", "").trim()
            if (did == cfg.deviceId) continue
            val dname = row.optString("device_name", "").trim()
            if (did.isNotBlank() && dname.isBlank()) {
                val merged = connNameByDeviceId[did].orEmpty().trim()
                if (merged.isNotBlank()) row.put("device_name", merged)
            }
            discovered.put(row)
        }
        val pending = org.json.JSONArray(
            meMeConnectIntents.values
                .filter { !it.accepted }
                .filter { it.sourceDeviceId != cfg.deviceId }
                .filter { req -> meMeConnections.values.none { conn -> conn.sessionId == req.sessionId } }
                .sortedByDescending { it.createdAt }
                .map { it.toJson(includeToken = false) }
        )
        // Build unified devices list
        val unifiedMap = LinkedHashMap<String, JSONObject>()
        // 1. Seed from provisioned siblings (excluding self)
        val provSiblings = getProvisionedSiblingDevicesJson(cfg.deviceId)
        for (i in 0 until provSiblings.length()) {
            val d = provSiblings.optJSONObject(i) ?: continue
            val did = d.optString("device_id", "").trim()
            if (did.isBlank()) continue
            unifiedMap[did] = JSONObject()
                .put("device_id", did)
                .put("device_name", d.optString("device_name", did))
                .put("state", "offline")
                .put("auth", "provisioned_sibling")
                .put("connection_method", JSONObject.NULL)
                .put("last_seen_at", JSONObject.NULL)
                .put("connected_at", JSONObject.NULL)
                .put("online", false)
        }
        // 2. Merge discovered devices (excluding self)
        for (i in 0 until discovered.length()) {
            val d = discovered.optJSONObject(i) ?: continue
            val did = d.optString("device_id", "").trim()
            if (did.isBlank() || did == cfg.deviceId) continue
            val existing = unifiedMap[did]
            val prevAuth = existing?.optString("auth", "unapproved") ?: "unapproved"
            unifiedMap[did] = JSONObject()
                .put("device_id", did)
                .put("device_name", d.optString("device_name", "").trim().ifBlank { existing?.optString("device_name", did) ?: did })
                .put("state", "discovered")
                .put("auth", prevAuth)
                .put("connection_method", JSONObject.NULL)
                .put("last_seen_at", d.opt("last_seen_at") ?: JSONObject.NULL)
                .put("connected_at", JSONObject.NULL)
                .put("online", true)
        }
        // 3. Merge active connections (excluding self)
        for (conn in connectionList) {
            val did = conn.peerDeviceId.trim()
            if (did.isBlank() || did == cfg.deviceId) continue
            val existing = unifiedMap[did]
            val prevAuth = existing?.optString("auth", "unapproved") ?: "unapproved"
            unifiedMap[did] = JSONObject()
                .put("device_id", did)
                .put("device_name", conn.peerDeviceName.trim().ifBlank { existing?.optString("device_name", did) ?: did })
                .put("state", "connected")
                .put("auth", prevAuth)
                .put("connection_method", conn.method.ifBlank { JSONObject.NULL })
                .put("last_seen_at", JSONObject.NULL)
                .put("connected_at", conn.connectedAt)
                .put("online", true)
        }
        // 4. Merge pending requests — ONLY incoming (target == self), skip outgoing (source == self)
        for (i in 0 until pending.length()) {
            val p = pending.optJSONObject(i) ?: continue
            val did = p.optString("source_device_id", "").trim()
            if (did.isBlank() || did == cfg.deviceId) continue
            val existing = unifiedMap[did]
            if (existing != null && existing.optString("state", "") == "connected") continue
            val prevAuth = existing?.optString("auth", "unapproved") ?: "unapproved"
            unifiedMap[did] = (existing ?: JSONObject())
                .put("device_id", did)
                .put("device_name", p.optString("source_device_name", "").trim().ifBlank { existing?.optString("device_name", did) ?: did })
                .put("state", "pending")
                .put("auth", prevAuth)
                .put("connection_method", JSONObject.NULL)
                .put("last_seen_at", JSONObject.NULL)
                .put("connected_at", JSONObject.NULL)
                .put("online", false)
                .put("request_id", p.optString("id", ""))
        }
        // 5. Apply allowed_devices / blocked_devices overrides
        for ((did, dev) in unifiedMap) {
            if (cfg.blockedDevices.contains(did)) {
                dev.put("auth", "blocked")
            } else if (cfg.allowedDevices.contains(did)) {
                dev.put("auth", "authorized")
            }
            dev.put("online", dev.optString("state", "") in listOf("connected", "discovered"))
        }
        // Sort: connected > discovered > pending > offline
        val stateOrder = mapOf("connected" to 0, "discovered" to 1, "pending" to 2, "provisioned" to 3, "offline" to 4)
        val unifiedDevices = org.json.JSONArray(
            unifiedMap.values.sortedBy { stateOrder[it.optString("state", "offline")] ?: 4 }
        )
        val p2pCfg = currentMeMeP2pConfig()
        val userSubject = (meMePrefs.getString("provision_user_subject", "") ?: "").trim()
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("self", self)
                .put("connected_count", connections.length())
                .put("discovered_count", discovered.length())
                .put("pending_request_count", pending.length())
                .put("pending_requests", pending)
                .put("connections", connections)
                .put("discovered", discovered)
                .put("devices", unifiedDevices)
                .put("advertising", runtime.optJSONObject("advertising") ?: JSONObject())
                .put("relay", relayCfg.toJson(includeSecrets = false, fcmToken = NotifyGatewayClient.loadFcmToken(context)))
                .put("relay_event_queue_count", relayQueueCount)
                .put("p2p", meMeP2pManager.statusJson())
                .put("p2p_peers", meMeP2pManager.peerStatesJson())
                .put("last_scan_at", runtime.opt("last_scan_at"))
                .put("provisioned_devices", getProvisionedSiblingDevicesJson(cfg.deviceId))
                .put("provision", JSONObject()
                    .put("provisioned", userSubject.isNotBlank() && p2pCfg.signalingToken.isNotBlank())
                    .put("user_subject", userSubject.ifBlank { JSONObject.NULL }))
        )
    }

    private fun handleMeMeRoutes(): Response {
        cleanupExpiredMeMeState()
        val cfg = currentMeMeConfig()
        val runtime = meMeDiscovery.statusJson(currentMeMeDiscoveryConfig(cfg))
        val discovered = runtime.optJSONArray("discovered")
        val selfId = cfg.deviceId
        val peerIds = LinkedHashSet<String>()
        meMeConnections.keys.forEach { peerIds += it }
        for (i in 0 until (discovered?.length() ?: 0)) {
            val obj = discovered?.optJSONObject(i) ?: continue
            val did = obj.optString("device_id", "").trim()
            if (did.isNotBlank() && did != selfId) peerIds += did
        }
        val routes = JSONArray()
        peerIds.forEach { did ->
            routes.put(evaluateMeMeRoute(peerDeviceId = did, transportHint = "auto", probeLan = true))
        }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("count", routes.length())
                .put("routes", routes)
        )
    }

    private fun handleMeMeConfigGet(): Response {
        val cfg = currentMeMeConfig()
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("config", cfg.toJson())
        )
    }

    private fun handleMeMeConfigSet(payload: JSONObject): Response {
        val prev = currentMeMeConfig()
        val next = MeMeConfig(
            deviceId = prev.deviceId,
            deviceName = payload.optString("device_name", prev.deviceName).trim().ifBlank { Build.MODEL?.trim().orEmpty() }.ifBlank { "Android device" },
            deviceDescription = payload.optString("device_description", prev.deviceDescription).trim(),
            deviceIcon = payload.optString("device_icon", prev.deviceIcon).trim(),
            allowDiscovery = if (payload.has("allow_discovery")) payload.optBoolean("allow_discovery", prev.allowDiscovery) else prev.allowDiscovery,
            connectionTimeoutSec = payload.optInt("connection_timeout", prev.connectionTimeoutSec).coerceIn(5, 300),
            maxConnections = payload.optInt("max_connections", prev.maxConnections).coerceIn(1, 32),
            connectionMethods = sanitizeConnectionMethods(readStringList(payload, "connection_methods", prev.connectionMethods)),
            autoReconnect = if (payload.has("auto_reconnect")) payload.optBoolean("auto_reconnect", prev.autoReconnect) else prev.autoReconnect,
            reconnectIntervalSec = payload.optInt("reconnect_interval", prev.reconnectIntervalSec).coerceIn(3, 3600),
            discoveryIntervalSec = payload.optInt("discovery_interval", prev.discoveryIntervalSec).coerceIn(10, 3600),
            connectionCheckIntervalSec = payload.optInt("connection_check_interval", prev.connectionCheckIntervalSec).coerceIn(5, 3600),
            blePreferredMaxBytes = payload.optInt("ble_preferred_max_bytes", prev.blePreferredMaxBytes).coerceIn(ME_ME_BLE_PREFERRED_MAX_BYTES_MIN, ME_ME_BLE_MAX_MESSAGE_BYTES),
            autoApproveOwnDevices = if (payload.has("auto_approve_own_devices")) payload.optBoolean("auto_approve_own_devices", prev.autoApproveOwnDevices) else prev.autoApproveOwnDevices,
            ownerIdentities = loadVerifiedOwnerIdentities(),
            allowedDevices = readStringList(payload, "allowed_devices", prev.allowedDevices),
            blockedDevices = readStringList(payload, "blocked_devices", prev.blockedDevices),
            notifyOnConnection = if (payload.has("notify_on_connection")) payload.optBoolean("notify_on_connection", prev.notifyOnConnection) else prev.notifyOnConnection,
            notifyOnDisconnection = if (payload.has("notify_on_disconnection")) payload.optBoolean("notify_on_disconnection", prev.notifyOnDisconnection) else prev.notifyOnDisconnection
        )
        saveMeMeConfig(next)
        meMeExecutor.execute {
            runCatching { meMeDiscovery.applyConfig(currentMeMeDiscoveryConfig(next)) }
        }
        scheduleMeMeDiscoveryLoop()
        scheduleMeMeConnectionCheckLoop()
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("config", next.toJson())
        )
    }

    private fun handleMeMeScan(payload: JSONObject): Response {
        meMeLastScanAtMs = System.currentTimeMillis()
        val timeoutMs = payload.optLong("timeout_ms", 3000L).coerceIn(500L, 30_000L)
        val cfg = currentMeMeConfig()
        val summary = meMeDiscovery.scan(currentMeMeDiscoveryConfig(cfg), timeoutMs)
        handleMeMePresenceUpdates(summary.discovered, source = "me_me.scan")
        // Defense-in-depth: filter self even though the discovery layer should already exclude it.
        val selfId = cfg.deviceId
        val discovered = summary.discovered.filter { it.optString("device_id", "") != selfId }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("started_at", summary.startedAt)
                .put("timeout_ms", summary.timeoutMs)
                .put("discovered", org.json.JSONArray(discovered))
                .put("warnings", org.json.JSONArray(summary.warnings))
        )
    }

    private fun handleMeMeConnect(payload: JSONObject): Response {
        cleanupExpiredMeMeState()
        val cfg = currentMeMeConfig()
        val targetDeviceId = payload.optString("target_device_id", "").trim()
            .ifBlank { payload.optString("peer_device_id", "").trim() }
            .ifBlank { payload.optString("device_id", "").trim() }
        if (targetDeviceId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "target_device_id_required")
        if (targetDeviceId == cfg.deviceId) return jsonError(Response.Status.BAD_REQUEST, "cannot_connect_self")
        if (cfg.blockedDevices.contains(targetDeviceId)) {
            return jsonError(Response.Status.FORBIDDEN, "target_blocked")
        }
        if (cfg.allowedDevices.isNotEmpty() && !cfg.allowedDevices.contains(targetDeviceId) && !isProvisionedSibling(targetDeviceId)) {
            return jsonError(Response.Status.FORBIDDEN, "target_not_allowed")
        }
        val existing = meMeConnections[targetDeviceId]
        if (existing != null) {
            if (existing.state == "connected") {
                return jsonResponse(JSONObject().put("status", "ok").put("connection", existing.toJson()).put("already_connected", true))
            }
            // Stale disconnected entry should not block reconnect attempts.
            meMeConnections.remove(targetDeviceId)
            meMeReconnectAttemptAt.remove(targetDeviceId)
        }
        if (meMeConnections.size >= cfg.maxConnections) {
            return jsonError(Response.Status.FORBIDDEN, "max_connections_reached")
        }
        var discovered = meMeDiscovery.statusJson(currentMeMeDiscoveryConfig(cfg)).optJSONArray("discovered")
        var peer = findDiscoveredPeer(discovered, targetDeviceId)
        if (peer == null && !payload.optBoolean("allow_unknown", false) && payload.optBoolean("auto_scan", true)) {
            val scanAttempts = payload.optInt("scan_attempts", 2).coerceIn(1, 4)
            val scanTimeoutMs = payload.optLong("scan_timeout_ms", 2500L).coerceIn(500L, 10_000L)
            repeat(scanAttempts) { idx ->
                val summary = meMeDiscovery.scan(currentMeMeDiscoveryConfig(cfg), scanTimeoutMs)
                handleMeMePresenceUpdates(summary.discovered, source = "me_me.connect")
                discovered = org.json.JSONArray(summary.discovered)
                peer = findDiscoveredPeer(discovered, targetDeviceId)
                if (peer != null) return@repeat
                if (idx < (scanAttempts - 1)) {
                    Thread.sleep(250L)
                }
            }
        }
        if (peer == null && !payload.optBoolean("allow_unknown", false)) {
            return jsonError(Response.Status.NOT_FOUND, "target_not_discovered")
        }
        val reqId = "mmr_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
        val createdAt = System.currentTimeMillis()
        val expiresAt = createdAt + cfg.connectionTimeoutSec * 1000L
        val sessionId = "mms_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
        val identity = currentMeMeIdentityKeyPair()
            ?: return jsonError(Response.Status.INTERNAL_ERROR, "identity_key_unavailable")
        val sourceEphemeral = generateMeMeEphemeralKeyPairB64()
            ?: return jsonError(Response.Status.INTERNAL_ERROR, "ephemeral_key_unavailable")
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonceB64 = Base64.encodeToString(nonce, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val acceptToken = encodeMeMeAcceptToken(
            requestId = reqId,
            sourceDeviceId = cfg.deviceId,
            targetDeviceId = targetDeviceId,
            sessionId = sessionId,
            sourceSigAlgorithm = identity.algorithm,
            sourceKexAlgorithm = sourceEphemeral.algorithm,
            sourceSigPublicKeyB64 = identity.publicKeyB64,
            sourceEphemeralPublicKeyB64 = sourceEphemeral.publicKeyB64,
            nonceB64 = nonceB64,
            expiresAt = expiresAt,
            sourceSigPrivateKeyB64 = identity.privateKeyB64
        ) ?: return jsonError(Response.Status.INTERNAL_ERROR, "accept_token_sign_failed")
        val req = MeMeConnectIntent(
            id = reqId,
            sourceDeviceId = cfg.deviceId,
            sourceDeviceName = cfg.deviceName,
            sourceDeviceDescription = cfg.deviceDescription,
            sourceDeviceIcon = cfg.deviceIcon,
            sourceBleAddress = "",
            targetDeviceId = targetDeviceId,
            targetDeviceName = peer?.optString("device_name", "")?.trim().orEmpty(),
            createdAt = createdAt,
            expiresAt = expiresAt,
            accepted = false,
            acceptToken = acceptToken,
            methodHint = payload.optString("method", "").trim().ifBlank { "auto" },
            sessionId = sessionId,
            sessionKeyB64 = "",
            sourceOwnerIdentities = cfg.ownerIdentities,
            sourceSigAlgorithm = identity.algorithm,
            sourceKexAlgorithm = sourceEphemeral.algorithm,
            sourceSigPublicKeyB64 = identity.publicKeyB64,
            sourceEphemeralPublicKeyB64 = sourceEphemeral.publicKeyB64,
            sourceEphemeralPrivateKeyB64 = sourceEphemeral.privateKeyB64
        )
        meMeConnectIntents[req.id] = req
        val connectWaitBudgetMs = payload.optLong("connect_wait_budget_ms", 15_000L).coerceIn(2_000L, 60_000L)
        val connectWaitStartedAt = System.currentTimeMillis()
        val autoDelivery = runAutoDeliverMeMeConnectOffer(req, payload)
        var autoDelivered = autoDelivery.optBoolean("delivered", false)
        var deliveredAtLeastOnce = autoDelivered
        val settleWaitMs = payload.optLong("auto_delivery_settle_wait_ms", 1600L).coerceIn(300L, 8_000L)
        val firstSettleWaitMs = (connectWaitBudgetMs - (System.currentTimeMillis() - connectWaitStartedAt)).coerceAtLeast(0L)
        var connected = if (autoDelivered && firstSettleWaitMs > 0L) {
            waitForMeMeConnection(req.targetDeviceId, minOf(settleWaitMs, firstSettleWaitMs))
        } else {
            null
        }
        val retryCount = payload.optInt("auto_delivery_retries", 2).coerceIn(0, 5)
        val retryDelayMs = payload.optLong("auto_delivery_retry_delay_ms", 700L).coerceIn(100L, 5_000L)
        var retriesUsed = 0
        var lastAutoDelivery = autoDelivery
        while (connected == null && retriesUsed < retryCount) {
            val elapsed = System.currentTimeMillis() - connectWaitStartedAt
            if (elapsed >= connectWaitBudgetMs) break
            if (autoDelivered) {
                val sleepMs = minOf(retryDelayMs, connectWaitBudgetMs - elapsed)
                if (sleepMs > 0L) Thread.sleep(sleepMs)
            }
            lastAutoDelivery = runAutoDeliverMeMeConnectOffer(req, payload)
            autoDelivered = lastAutoDelivery.optBoolean("delivered", false)
            if (autoDelivered) deliveredAtLeastOnce = true
            retriesUsed += 1
            if (autoDelivered) {
                val remainingMs = (connectWaitBudgetMs - (System.currentTimeMillis() - connectWaitStartedAt)).coerceAtLeast(0L)
                if (remainingMs <= 0L) break
                connected = waitForMeMeConnection(req.targetDeviceId, minOf(settleWaitMs, remainingMs))
            }
        }
        if (connected != null) {
            return jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("connection", connected.toJson())
                    .put("auto_connected", true)
                    .put("request", req.toJson(includeToken = true))
                    .put("auto_delivery", JSONObject(lastAutoDelivery.toString()).put("retry_count", retriesUsed))
            )
        }
        val suppressPendingOnDeliveryFailure = payload.optBoolean("suppress_pending_on_delivery_failure", false)
        if (!deliveredAtLeastOnce && suppressPendingOnDeliveryFailure) {
            meMeConnectIntents.remove(req.id)
            return jsonResponse(
                JSONObject()
                    .put("status", "failed")
                    .put("request_id", req.id)
                    .put("auto_delivery", JSONObject(lastAutoDelivery.toString()).put("retry_count", retriesUsed))
                    .put("error", "auto_delivery_failed")
            )
        }
        return jsonResponse(
            JSONObject()
                .put("status", "pending")
                .put("request", req.toJson(includeToken = true))
                .put("auto_delivery", JSONObject(lastAutoDelivery.toString()).put("retry_count", retriesUsed))
                .put(
                    "note",
                    if (deliveredAtLeastOnce) {
                        "Connection offer auto-delivered. Waiting for peer confirmation."
                    } else {
                        "Automatic offer delivery failed; share accept_token with target and call /me/me/accept on target."
                    }
                )
        )
    }

    private fun waitForMeMeConnection(peerDeviceId: String, timeoutMs: Long): MeMeConnection? {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(100L, 15_000L)
        while (System.currentTimeMillis() < deadline) {
            val conn = meMeConnections[peerDeviceId]
            if (conn != null && conn.state == "connected") {
                return conn
            }
            Thread.sleep(120L)
        }
        return null
    }

    private fun runAutoDeliverMeMeConnectOffer(req: MeMeConnectIntent, payload: JSONObject): JSONObject {
        val targetHostOverride = payload.optString("target_host", "").trim()
        val targetPortOverride = payload.optInt("target_port", 0).takeIf { it in 1..65535 }
        val transportHint = payload.optString("transport", req.methodHint).trim()
        val timeoutMs = payload.optLong("auto_delivery_timeout_ms", 20_000L).coerceIn(1500L, 30_000L)
        val offerPayload = JSONObject()
            .put("accept_token", req.acceptToken)
            .put("request_id", req.id)
            .put("source_device_id", req.sourceDeviceId)
            .put("source_device_name", req.sourceDeviceName)
            .put("source_device_description", req.sourceDeviceDescription)
            .put("source_device_icon", req.sourceDeviceIcon)
            .put("source_owner_identities", org.json.JSONArray(req.sourceOwnerIdentities))
            .put("method", req.methodHint)
        val delivery = deliverMeMeBootstrapPayload(
            peerDeviceId = req.targetDeviceId,
            transportHint = transportHint,
            timeoutMs = timeoutMs,
            lanPath = "/me/me/connect/offer",
            lanPayload = offerPayload,
            bleKind = "connect_offer",
            lanHostOverride = targetHostOverride,
            lanPortOverride = targetPortOverride
        )
        return JSONObject()
            .put("attempted", true)
            .put("delivered", delivery.optBoolean("ok", false))
            .put("transport", delivery.optString("transport", "unknown"))
            .put("target_device_id", req.targetDeviceId)
            .put("result", delivery)
    }

    private fun handleMeMeConnectOffer(payload: JSONObject, sourceIp: String): Response {
        val effective = JSONObject(payload.toString())
        if (sourceIp.isNotBlank() && sourceIp != "0:0:0:0:0:0:0:1" && sourceIp != "::1") {
            val hostMissing = effective.optString("source_host", "").trim().isBlank()
            if (sourceIp.startsWith("ble:")) {
                val bleAddr = sourceIp.removePrefix("ble:").trim()
                if (bleAddr.isNotBlank() && effective.optString("source_ble_address", "").trim().isBlank()) {
                    effective.put("source_ble_address", bleAddr)
                }
            } else if (hostMissing) {
                effective.put("source_host", sourceIp)
            }
        }
        if (!effective.has("source_port")) {
            effective.put("source_port", ME_ME_LAN_PORT)
        }
        effective.put("auto_path", true)
        return handleMeMeAccept(effective)
    }

    private fun handleMeMeAccept(payload: JSONObject): Response {
        cleanupExpiredMeMeState()
        val cfg = currentMeMeConfig()
        val token = payload.optString("accept_token", "").trim()
        val requestId = payload.optString("request_id", "").trim()
        val isAutoPath = payload.optBoolean("auto_path", false)
        val forceAccept = payload.optBoolean("force_accept", false)
        val req = when {
            token.isNotBlank() -> {
                val parsed = decodeMeMeAcceptToken(token) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_accept_token")
                if (parsed.expiresAt in 1..System.currentTimeMillis()) return jsonError(Response.Status.GONE, "request_expired")
                if (parsed.targetDeviceId != cfg.deviceId) return jsonError(Response.Status.FORBIDDEN, "token_target_mismatch")
                if (!verifyMeMeOfferSignature(parsed)) return jsonError(Response.Status.FORBIDDEN, "token_signature_invalid")
                val sourceName = payload.optString("source_device_name", "").trim()
                val sourceDescription = payload.optString("source_device_description", "").trim()
                val sourceIcon = payload.optString("source_device_icon", "").trim()
                val sourceBleAddress = payload.optString("source_ble_address", "").trim()
                meMeConnectIntents[parsed.requestId] ?: MeMeConnectIntent(
                    id = parsed.requestId,
                    sourceDeviceId = parsed.sourceDeviceId,
                    sourceDeviceName = sourceName.ifBlank { resolveMeMePeerDisplayName(parsed.sourceDeviceId) },
                    sourceDeviceDescription = sourceDescription,
                    sourceDeviceIcon = sourceIcon,
                    sourceBleAddress = sourceBleAddress,
                    targetDeviceId = parsed.targetDeviceId,
                    targetDeviceName = cfg.deviceName,
                    createdAt = System.currentTimeMillis(),
                    expiresAt = parsed.expiresAt,
                    accepted = false,
                    acceptToken = token,
                    methodHint = payload.optString("method", "").trim().ifBlank { "auto" },
                    sessionId = parsed.sessionId,
                    sessionKeyB64 = "",
                    sourceOwnerIdentities = readIdentityList(payload, "source_owner_identities", emptyList()),
                    sourceSigAlgorithm = parsed.sourceSigAlgorithm,
                    sourceKexAlgorithm = parsed.sourceKexAlgorithm,
                    sourceSigPublicKeyB64 = parsed.sourceSigPublicKeyB64,
                    sourceEphemeralPublicKeyB64 = parsed.sourceEphemeralPublicKeyB64,
                    sourceEphemeralPrivateKeyB64 = ""
                )
            }
            requestId.isNotBlank() -> meMeConnectIntents[requestId]
            else -> null
        } ?: return jsonError(Response.Status.NOT_FOUND, "request_not_found")

        val sourceNameFromPayload = payload.optString("source_device_name", "").trim()
        val sourceDescriptionFromPayload = payload.optString("source_device_description", "").trim()
        val sourceIconFromPayload = payload.optString("source_device_icon", "").trim()
        val sourceBleAddressFromPayload = payload.optString("source_ble_address", "").trim()
        val reqWithMeta = req.copy(
            sourceDeviceName = sourceNameFromPayload.ifBlank {
                req.sourceDeviceName.ifBlank { resolveMeMePeerDisplayName(req.sourceDeviceId) }
            },
            sourceDeviceDescription = sourceDescriptionFromPayload.ifBlank { req.sourceDeviceDescription },
            sourceDeviceIcon = sourceIconFromPayload.ifBlank { req.sourceDeviceIcon },
            sourceBleAddress = sourceBleAddressFromPayload.ifBlank { req.sourceBleAddress }
        )
        if (reqWithMeta.sourceBleAddress.isNotBlank()) {
            meMeDiscovery.rememberPeerBleAddress(
                deviceId = reqWithMeta.sourceDeviceId,
                address = reqWithMeta.sourceBleAddress,
                deviceName = reqWithMeta.sourceDeviceName,
                deviceDescription = reqWithMeta.sourceDeviceDescription,
                deviceIcon = reqWithMeta.sourceDeviceIcon
            )
        }
        if (reqWithMeta != req) {
            meMeConnectIntents[reqWithMeta.id] = reqWithMeta
        }

        if (cfg.blockedDevices.contains(reqWithMeta.sourceDeviceId)) return jsonError(Response.Status.FORBIDDEN, "source_blocked")
        if (cfg.allowedDevices.isNotEmpty() && !cfg.allowedDevices.contains(reqWithMeta.sourceDeviceId) && !isProvisionedSibling(reqWithMeta.sourceDeviceId)) {
            return jsonError(Response.Status.FORBIDDEN, "source_not_allowed")
        }
        if (meMeConnections.size >= cfg.maxConnections && !meMeConnections.containsKey(reqWithMeta.sourceDeviceId)) {
            return jsonError(Response.Status.FORBIDDEN, "max_connections_reached")
        }
        val canAutoApprove = isProvisionedSibling(reqWithMeta.sourceDeviceId) ||
            (cfg.autoApproveOwnDevices &&
                hasOwnerIdentityMatch(cfg.ownerIdentities, reqWithMeta.sourceOwnerIdentities)) ||
            cfg.allowedDevices.contains(reqWithMeta.sourceDeviceId)
        val manualAction = forceAccept || (requestId.isNotBlank() && !isAutoPath)
        if (!manualAction && !canAutoApprove) {
            meMeConnectIntents[reqWithMeta.id] = reqWithMeta.copy(accepted = false)
            return jsonResponse(
                JSONObject()
                    .put("status", "pending")
                    .put("request", reqWithMeta.toJson(includeToken = false))
                    .put("reason", "approval_required")
            )
        }
        val parsedToken = decodeMeMeAcceptToken(reqWithMeta.acceptToken) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_accept_token")
        if (!verifyMeMeOfferSignature(parsedToken)) return jsonError(Response.Status.FORBIDDEN, "token_signature_invalid")
        val responderIdentity = currentMeMeIdentityKeyPair() ?: return jsonError(Response.Status.INTERNAL_ERROR, "identity_key_unavailable")
        val responderEphemeral = generateMeMeEphemeralKeyPairB64(parsedToken.sourceKexAlgorithm)
            ?: return jsonError(Response.Status.INTERNAL_ERROR, "ephemeral_key_unavailable")
        val sessionKeyB64 = deriveMeMeSessionKeyB64(
            kexAlgorithm = parsedToken.sourceKexAlgorithm,
            ownEphemeralPrivateKeyB64 = responderEphemeral.privateKeyB64,
            peerEphemeralPublicKeyB64 = parsedToken.sourceEphemeralPublicKeyB64,
            contextParts = listOf(
                parsedToken.requestId,
                parsedToken.sessionId,
                parsedToken.sourceDeviceId,
                parsedToken.targetDeviceId,
                parsedToken.nonceB64
            )
        ) ?: return jsonError(Response.Status.FORBIDDEN, "session_key_derive_failed")
        val connId = "mmc_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
        val conn = MeMeConnection(
            id = connId,
            peerDeviceId = reqWithMeta.sourceDeviceId,
            peerDeviceName = reqWithMeta.sourceDeviceName,
            method = reqWithMeta.methodHint,
            connectedAt = System.currentTimeMillis(),
            state = "connected",
            role = "acceptor",
            sessionId = reqWithMeta.sessionId,
            sessionKeyB64 = sessionKeyB64
        )
        meMeConnections[conn.peerDeviceId] = conn
        meMeReconnectAttemptAt.remove(conn.peerDeviceId)
        meMeConnectIntents[reqWithMeta.id] = reqWithMeta.copy(
            accepted = true,
            sessionKeyB64 = sessionKeyB64
        )
        clearResolvedMeMeConnectIntents(sessionId = reqWithMeta.sessionId, requestId = reqWithMeta.id)
        val autoConfirm = runAutoConfirmMeMeAccept(
            req = reqWithMeta,
            payload = payload,
            acceptedByName = cfg.deviceName,
            parsedToken = parsedToken,
            responderIdentity = responderIdentity,
            responderEphemeral = responderEphemeral
        )
        maybeAutoConnectP2p(conn.peerDeviceId)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("ack_token", reqWithMeta.acceptToken)
                .put("auto_confirm", autoConfirm)
                .put("connection", conn.toJson())
        )
    }

    private fun handleMeMeConnectConfirm(payload: JSONObject): Response {
        cleanupExpiredMeMeState()
        val cfg = currentMeMeConfig()
        val token = payload.optString("accept_token", "").trim()
        if (token.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "accept_token_required")
        val parsed = decodeMeMeAcceptToken(token) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_accept_token")
        if (parsed.expiresAt in 1..System.currentTimeMillis()) return jsonError(Response.Status.GONE, "request_expired")
        if (parsed.sourceDeviceId != cfg.deviceId) return jsonError(Response.Status.FORBIDDEN, "token_source_mismatch")
        if (!verifyMeMeOfferSignature(parsed)) return jsonError(Response.Status.FORBIDDEN, "token_signature_invalid")
        val responderSigAlgorithm = normalizeMeMeSigAlgorithm(payload.optString("responder_sig_algorithm", "").trim())
            ?: parsed.sourceSigAlgorithm
        val responderKexAlgorithm = normalizeMeMeKexAlgorithm(payload.optString("responder_kex_algorithm", "").trim())
            ?: parsed.sourceKexAlgorithm
        val responderSigPublicKeyB64 = payload.optString("responder_sig_public_key", "").trim()
        val responderEphemeralPublicKeyB64 = payload.optString("responder_ephemeral_public_key", "").trim()
        val responderSignatureB64 = payload.optString("responder_signature", "").trim()
        if (responderSigPublicKeyB64.isBlank() || responderEphemeralPublicKeyB64.isBlank() || responderSignatureB64.isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "confirm_signature_required")
        }
        if (!verifyMeMeConfirmSignature(
                responderSigAlgorithm = responderSigAlgorithm,
                responderSigPublicKeyB64 = responderSigPublicKeyB64,
                responderSignatureB64 = responderSignatureB64,
                token = parsed,
                responderEphemeralPublicKeyB64 = responderEphemeralPublicKeyB64
            )
        ) {
            return jsonError(Response.Status.FORBIDDEN, "confirm_signature_invalid")
        }
        val req = meMeConnectIntents[parsed.requestId] ?: return jsonError(Response.Status.NOT_FOUND, "request_not_found")
        if (req.sourceEphemeralPrivateKeyB64.isBlank()) return jsonError(Response.Status.FORBIDDEN, "request_ephemeral_key_missing")
        if (responderKexAlgorithm != parsed.sourceKexAlgorithm) {
            return jsonError(Response.Status.FORBIDDEN, "confirm_kex_algorithm_mismatch")
        }
        val sessionKeyB64 = deriveMeMeSessionKeyB64(
            kexAlgorithm = parsed.sourceKexAlgorithm,
            ownEphemeralPrivateKeyB64 = req.sourceEphemeralPrivateKeyB64,
            peerEphemeralPublicKeyB64 = responderEphemeralPublicKeyB64,
            contextParts = listOf(
                parsed.requestId,
                parsed.sessionId,
                parsed.sourceDeviceId,
                parsed.targetDeviceId,
                parsed.nonceB64
            )
        ) ?: return jsonError(Response.Status.FORBIDDEN, "session_key_derive_failed")

        val conn = MeMeConnection(
            id = "mmc_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}",
            peerDeviceId = parsed.targetDeviceId,
            peerDeviceName = payload.optString("peer_device_name", "").trim(),
            method = payload.optString("method", "auto").trim().ifBlank { "auto" },
            connectedAt = System.currentTimeMillis(),
            state = "connected",
            role = "initiator",
            sessionId = parsed.sessionId,
            sessionKeyB64 = sessionKeyB64
        )
        meMeConnections[conn.peerDeviceId] = conn
        meMeReconnectAttemptAt.remove(conn.peerDeviceId)
        markMeMeConnectIntentAccepted(parsed.requestId)
        clearResolvedMeMeConnectIntents(sessionId = parsed.sessionId, requestId = parsed.requestId)
        maybeAutoConnectP2p(conn.peerDeviceId)
        return jsonResponse(JSONObject().put("status", "ok").put("connection", conn.toJson()))
    }

    private fun runAutoConfirmMeMeAccept(
        req: MeMeConnectIntent,
        payload: JSONObject,
        acceptedByName: String,
        parsedToken: MeMeAcceptToken,
        responderIdentity: MeMeIdentityKeyPair,
        responderEphemeral: MeMeIdentityKeyPair
    ): JSONObject {
        val sourceHostOverride = payload.optString("source_host", "").trim()
        val sourcePortOverride = payload.optInt("source_port", 0).takeIf { it in 1..65535 }
        val sourceBleAddress = payload.optString("source_ble_address", "").trim().ifBlank { req.sourceBleAddress }
        val route = resolveMeMePeerRoute(req.sourceDeviceId)
        val host = sourceHostOverride.ifBlank { route?.host.orEmpty() }
        val port = sourcePortOverride ?: route?.port ?: ME_ME_LAN_PORT
        val responderSigB64 = signMeMeConfirm(
            sourceSigPrivateKeyB64 = responderIdentity.privateKeyB64,
            sourceSigAlgorithm = responderIdentity.algorithm,
            token = parsedToken,
            responderEphemeralPublicKeyB64 = responderEphemeral.publicKeyB64
        ) ?: return JSONObject()
            .put("attempted", false)
            .put("confirmed", false)
            .put("transport", "none")
            .put("target_device_id", req.sourceDeviceId)
            .put("result", JSONObject().put("ok", false).put("error", "confirm_sign_failed"))
        val confirmPayload = JSONObject()
            .put("accept_token", req.acceptToken)
            .put("peer_device_name", acceptedByName)
            .put("method", req.methodHint)
            .put("responder_sig_algorithm", responderIdentity.algorithm)
            .put("responder_kex_algorithm", responderEphemeral.algorithm)
            .put("responder_sig_public_key", responderIdentity.publicKeyB64)
            .put("responder_ephemeral_public_key", responderEphemeral.publicKeyB64)
            .put("responder_signature", responderSigB64)
        val timeoutMs = payload.optLong("auto_confirm_timeout_ms", 12_000L).coerceIn(1500L, 20_000L)
        val retries = payload.optInt("auto_confirm_retries", 2).coerceIn(0, 5)
        val retryDelayMs = payload.optLong("auto_confirm_retry_delay_ms", 700L).coerceIn(100L, 5_000L)
        fun deliverOnce(): JSONObject {
            return if (host.isNotBlank()) {
                postMeMeLanJson(host, port, "/me/me/connect/confirm", confirmPayload).put("transport", "lan")
            } else if (sourceBleAddress.isNotBlank()) {
                meMeDiscovery.rememberPeerBleAddress(
                    deviceId = req.sourceDeviceId,
                    address = sourceBleAddress,
                    deviceName = req.sourceDeviceName,
                    deviceDescription = req.sourceDeviceDescription,
                    deviceIcon = req.sourceDeviceIcon
                )
                val wire = JSONObject().put("kind", "connect_confirm").put("payload", confirmPayload)
                val bytes = wire.toString().toByteArray(StandardCharsets.UTF_8)
                // Prefer same-session BLE notify first so connect->accept->confirm can finish
                // in a single BLE session without opening a second GATT connection.
                val notifyOut = meMeDiscovery.sendBleNotificationToAddress(sourceBleAddress, bytes, timeoutMs)
                    .put("transport", "ble_notify")
                if (notifyOut.optBoolean("ok", false)) return notifyOut
                val direct = meMeDiscovery.sendBlePayloadToAddress(sourceBleAddress, bytes, timeoutMs)
                    .put("transport", "ble")
                if (direct.optBoolean("ok", false)) return direct
                // Source BLE address may rotate or become stale. Fall back to peer-id route
                // if discoverable.
                deliverMeMePayload(
                    peerDeviceId = req.sourceDeviceId,
                    transportHint = "",
                    timeoutMs = timeoutMs,
                    lanPath = "/me/me/connect/confirm",
                    lanPayload = confirmPayload,
                    bleKind = "connect_confirm",
                    allowRelayFallback = true
                )
            } else {
                deliverMeMePayload(
                    peerDeviceId = req.sourceDeviceId,
                    transportHint = "",
                    timeoutMs = timeoutMs,
                    lanPath = "/me/me/connect/confirm",
                    lanPayload = confirmPayload,
                    bleKind = "connect_confirm",
                    allowRelayFallback = true
                )
            }
        }
        var attempts = 0
        var delivery = JSONObject().put("ok", false).put("error", "auto_confirm_not_attempted")
        while (attempts <= retries) {
            if (attempts > 0) Thread.sleep(retryDelayMs)
            delivery = deliverOnce()
            attempts += 1
            if (delivery.optBoolean("ok", false)) break
        }
        return JSONObject()
            .put("attempted", true)
            .put("transport", delivery.optString("transport", if (host.isNotBlank()) "lan" else "ble"))
            .put("target_device_id", req.sourceDeviceId)
            .put("confirmed", delivery.optBoolean("ok", false))
            .put("attempts", attempts)
            .put("result", delivery)
    }

    private fun handleMeMeDisconnect(payload: JSONObject): Response {
        cleanupExpiredMeMeState()
        val peerDeviceId = payload.optString("peer_device_id", "").trim()
        val connectionId = payload.optString("connection_id", "").trim()
        val removed = when {
            peerDeviceId.isNotBlank() -> meMeConnections.remove(peerDeviceId)
            connectionId.isNotBlank() -> {
                val hit = meMeConnections.values.firstOrNull { it.id == connectionId }
                if (hit != null) meMeConnections.remove(hit.peerDeviceId) else null
            }
            else -> null
        } ?: return jsonError(Response.Status.NOT_FOUND, "connection_not_found")
        meMeInboundMessages.remove(removed.peerDeviceId)
        meMeReconnectAttemptAt.remove(removed.peerDeviceId)
        runCatching { meMeP2pManager.disconnectPeer(removed.peerDeviceId) }
        return jsonResponse(JSONObject().put("status", "ok").put("disconnected", true).put("connection", removed.toJson()))
    }

    private fun handleMeMeRequestReject(payload: JSONObject): Response {
        cleanupExpiredMeMeState()
        val requestId = payload.optString("request_id", "").trim()
        if (requestId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "request_id_required")
        val req = meMeConnectIntents.remove(requestId) ?: return jsonError(Response.Status.NOT_FOUND, "request_not_found")
        if (payload.optBoolean("block_source", false)) {
            val cfg = currentMeMeConfig()
            if (!cfg.blockedDevices.contains(req.sourceDeviceId)) {
                val next = cfg.copy(blockedDevices = (cfg.blockedDevices + req.sourceDeviceId).distinct())
                saveMeMeConfig(next)
            }
        }
        return jsonResponse(JSONObject().put("status", "ok").put("rejected", true).put("request_id", requestId))
    }

    private fun handleMeMePeerPolicySet(payload: JSONObject): Response {
        val peerDeviceId = payload.optString("peer_device_id", "").trim()
        if (peerDeviceId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "peer_device_id_required")
        val policy = payload.optString("policy", "").trim().lowercase(Locale.US)
        if (policy !in setOf("allow", "block", "clear")) {
            return jsonError(Response.Status.BAD_REQUEST, "policy_invalid")
        }
        val cfg = currentMeMeConfig()
        val next = when (policy) {
            "allow" -> cfg.copy(
                allowedDevices = (cfg.allowedDevices + peerDeviceId).distinct(),
                blockedDevices = cfg.blockedDevices.filter { it != peerDeviceId }
            )
            "block" -> cfg.copy(
                blockedDevices = (cfg.blockedDevices + peerDeviceId).distinct(),
                allowedDevices = cfg.allowedDevices.filter { it != peerDeviceId }
            )
            else -> cfg.copy(
                allowedDevices = cfg.allowedDevices.filter { it != peerDeviceId },
                blockedDevices = cfg.blockedDevices.filter { it != peerDeviceId }
            )
        }
        saveMeMeConfig(next)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("policy", policy)
                .put("peer_device_id", peerDeviceId)
                .put("config", next.toJson())
        )
    }

    private fun handleMeMeMessageSend(payload: JSONObject): Response {
        cleanupExpiredMeMeState()
        val peerDeviceId = payload.optString("peer_device_id", "").trim()
        if (peerDeviceId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "peer_device_id_required")
        val messageId = payload.optString("message_id", "").trim()
            .ifBlank { "mmm_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}" }
        val nestedMessageAny = if (payload.has("message")) payload.opt("message") else null
        val nestedMessage = nestedMessageAny as? JSONObject
        val type = payload.optString("type", "").trim()
            .ifBlank { nestedMessage?.optString("type", "")?.trim().orEmpty() }
            .ifBlank { "message" }
        val payloadValue: Any? = when {
            payload.has("payload") -> payload.opt("payload")
            nestedMessage?.has("payload") == true -> nestedMessage.opt("payload")
            nestedMessageAny is String && nestedMessageAny.trim().isNotBlank() ->
                JSONObject().put("text", nestedMessageAny.trim())
            nestedMessage != null -> buildMeMePayloadFromObject(
                source = nestedMessage,
                excludedKeys = setOf("type", "payload")
            )
            else -> buildLegacyMeMePayload(payload)
        }
        if (isMeMePayloadEmpty(payloadValue)) {
            return jsonError(Response.Status.BAD_REQUEST, "payload_required")
        }
        // Auto-embed file content when rel_path is present but data_b64 is not
        val resolvedPayload = embedMeMeFileContent(payloadValue)
        val send = sendMeMeEncryptedMessage(
            peerDeviceId = peerDeviceId,
            type = type,
            payloadValue = resolvedPayload,
            transportHint = payload.optString("transport", ""),
            timeoutMs = payload.optLong("timeout_ms", 12_000L),
            messageId = messageId
        )
        val delivery = formatMeMeDelivery(peerDeviceId = peerDeviceId, type = type, send = send)
        logMeMeTrace(
            event = "message.send",
            messageId = messageId,
            fields = JSONObject()
                .put("endpoint", "/me/me/message/send")
                .put("peer_device_id", peerDeviceId)
                .put("requested_transport", payload.optString("transport", "").trim().ifBlank { "auto" })
                .put("resolved_transport", delivery.optString("transport", "unknown"))
                .put("payload_shape", describeMeMePayloadShape(resolvedPayload))
                .put("ok", delivery.optBoolean("ok", false))
                .put(
                    "error",
                    delivery.optJSONObject("result")?.optString("error", "")?.trim()?.takeIf { it.isNotBlank() }
                        ?: JSONObject.NULL
                )
        )
        if (!delivery.optBoolean("ok", false)) return jsonError(Response.Status.SERVICE_UNAVAILABLE, "peer_delivery_failed", delivery)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("message_id", messageId)
                .put("delivered", delivery.optBoolean("delivered", false))
                .put("transport", delivery.optString("transport", "unknown"))
                .put("peer_device_id", peerDeviceId)
                .put("result", delivery.optJSONObject("result") ?: JSONObject())
                .put("delivery", delivery)
        )
    }

    private fun isMeMePayloadEmpty(value: Any?): Boolean {
        return when (value) {
            null, JSONObject.NULL -> true
            is String -> value.trim().isBlank()
            is JSONObject -> value.length() <= 0
            is JSONArray -> value.length() <= 0
            else -> false
        }
    }

    private fun describeMeMePayloadShape(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is String -> "string"
            is JSONObject -> "object"
            is JSONArray -> "array"
            is Number -> "number"
            is Boolean -> "boolean"
            else -> value::class.java.simpleName.lowercase(Locale.US)
        }
    }

    private fun buildMeMePayloadFromObject(
        source: JSONObject,
        excludedKeys: Set<String> = emptySet()
    ): JSONObject? {
        val out = JSONObject()
        val text = source.optString("text", "").trim()
            .ifBlank { source.optString("message", "").trim() }
        if (text.isNotBlank()) out.put("text", text)
        val relPath = source.optString("rel_path", "").trim()
        if (relPath.isNotBlank()) out.put("rel_path", relPath)
        val fileName = source.optString("file_name", "").trim()
        if (fileName.isNotBlank()) out.put("file_name", fileName)
        val mimeType = source.optString("mime_type", "").trim()
        if (mimeType.isNotBlank()) out.put("mime_type", mimeType)
        if (source.has("command")) {
            val command = source.opt("command")
            if (command != null && command != JSONObject.NULL) out.put("command", command)
        }
        if (source.has("data")) {
            val data = source.opt("data")
            if (data != null && data != JSONObject.NULL) out.put("data", data)
        }
        val skip = hashSetOf(
            "peer_device_id",
            "target_device_id",
            "device_id",
            "message_id",
            "transport",
            "timeout_ms",
            "permission_id",
            "type",
            "payload",
            "message"
        )
        skip.addAll(excludedKeys)
        val it = source.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k in skip) continue
            if (out.has(k)) continue
            val v = source.opt(k)
            if (v == null || v == JSONObject.NULL) continue
            out.put(k, v)
        }
        if (out.length() <= 0) return null
        return out
    }

    private fun buildLegacyMeMePayload(payload: JSONObject): Any? {
        return buildMeMePayloadFromObject(payload)
    }

    /**
     * If the payload contains `rel_path` but no `data_b64`, resolve the file
     * from user:// or termux:// path, read its bytes, and embed as `data_b64` + `mime_type`.
     * Images are compressed to fit within BLE transport limits.
     * This ensures the receiver gets actual file content instead of a sender-local path.
     */
    private fun embedMeMeFileContent(payloadValue: Any?): Any? {
        if (payloadValue !is JSONObject) return payloadValue
        val relPath = payloadValue.optString("rel_path", "").trim()
        if (relPath.isBlank()) return payloadValue
        // Already has embedded data — nothing to do
        val dataKeys = listOf("data_b64", "file_b64", "attachment_b64", "content_b64", "bytes_b64")
        if (dataKeys.any { payloadValue.optString(it, "").trim().isNotBlank() }) return payloadValue
        val ref = parseFsPathRef(relPath)
        if (ref == null) {
            Log.w(TAG, "embedMeMeFileContent: cannot read rel_path=$relPath")
            return payloadValue
        }
        val rawBytes = try {
            readFsPathBytes(ref).first
        } catch (_: Exception) {
            Log.w(TAG, "embedMeMeFileContent: cannot read rel_path=$relPath")
            return payloadValue
        }
        if (rawBytes.isEmpty()) {
            Log.w(TAG, "embedMeMeFileContent: file is empty rel_path=$relPath")
            return payloadValue
        }
        val fileName = when (ref.fs) {
            "user" -> ref.userFile?.name ?: relPath.substringAfterLast('/')
            "termux" -> ref.termuxPath?.substringAfterLast('/') ?: relPath.substringAfterLast('/')
            else -> relPath.substringAfterLast('/')
        }
        val mime = payloadValue.optString("mime_type", "").trim()
            .ifBlank { URLConnection.guessContentTypeFromName(fileName) ?: mimeTypeFor(fileName) }
        // For images, apply file transfer resize settings (same as cloud uploads)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val isImg = mime.startsWith("image/") && ext in setOf("jpg", "jpeg", "png", "webp")
        val imgResizeEnabled = fileTransferPrefs.getBoolean("image_resize_enabled", true)
        val bytes = if (isImg && imgResizeEnabled && ref.fs == "user" && ref.userFile != null) {
            val maxDimPx = fileTransferPrefs.getInt("image_resize_max_dim_px", 512).coerceIn(64, 4096)
            val jpegQuality = fileTransferPrefs.getInt("image_resize_jpeg_quality", 70).coerceIn(30, 95)
            downscaleImageToJpeg(ref.userFile, maxDimPx, jpegQuality) ?: rawBytes
        } else {
            rawBytes
        }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val out = JSONObject(payloadValue.toString())
        out.put("data_b64", b64)
        if (out.optString("mime_type", "").trim().isBlank()) {
            out.put("mime_type", mime)
        }
        if (out.optString("file_name", "").trim().isBlank()) {
            out.put("file_name", fileName)
        }
        // Remove rel_path since it's sender-local and meaningless to the receiver
        out.remove("rel_path")
        Log.i(TAG, "embedMeMeFileContent: embedded ${bytes.size} bytes (raw ${rawBytes.size}) from $relPath as data_b64")
        return out
    }

    private fun formatMeMeDelivery(
        peerDeviceId: String,
        type: String,
        send: JSONObject
    ): JSONObject {
        val ok = send.optBoolean("ok", false)
        return JSONObject()
            .put("ok", ok)
            .put("delivered", ok)
            .put("peer_device_id", peerDeviceId)
            .put("type", type)
            .put("transport", send.optString("transport", "unknown"))
            .put("result", send)
    }

    private fun sendMeMeEncryptedMessage(
        peerDeviceId: String,
        type: String,
        payloadValue: Any?,
        transportHint: String = "",
        timeoutMs: Long = 12_000L,
        messageId: String = ""
    ): JSONObject {
        val selfCfg = currentMeMeConfig()
        val conn = meMeConnections[peerDeviceId] ?: return JSONObject().put("ok", false).put("error", "connection_not_found")
        if (conn.state != "connected") return JSONObject().put("ok", false).put("error", "connection_not_ready")
        val plaintext = JSONObject()
            .put("type", type.ifBlank { "message" })
            .put("payload", payloadValue ?: JSONObject.NULL)
            .put("sent_at", System.currentTimeMillis())
            .put("from_device_id", selfCfg.deviceId)
            .put("from_device_name", selfCfg.deviceName)
        val encrypted = encryptMeMePayload(conn.sessionKeyB64, plaintext)
            ?: return JSONObject().put("ok", false).put("error", "encrypt_failed")
        val req = JSONObject()
            .put("session_id", conn.sessionId)
            .put("from_device_id", selfCfg.deviceId)
            .put("to_device_id", peerDeviceId)
            .put("delivery_id", "mda_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}")
            .put("iv_b64", encrypted.ivB64)
            .put("ciphertext_b64", encrypted.ciphertextB64)
        return deliverMeMePayload(
            peerDeviceId = peerDeviceId,
            transportHint = transportHint,
            timeoutMs = timeoutMs,
            lanPath = "/me/me/data/ingest",
            lanPayload = req,
            bleKind = "data_ingest",
            messageId = messageId,
            allowRelayFallback = true
        )
    }

    private fun deliverMeMePayload(
        peerDeviceId: String,
        transportHint: String,
        timeoutMs: Long,
        lanPath: String,
        lanPayload: JSONObject,
        bleKind: String,
        messageId: String = "",
        allowRelayFallback: Boolean = false
    ): JSONObject {
        val conn = meMeConnections[peerDeviceId] ?: return JSONObject().put("ok", false).put("error", "connection_not_found")
        val cfg = currentMeMeConfig()
        val route = resolveMeMePeerRoute(peerDeviceId)
        val normalizedHint = transportHint.trim().lowercase(Locale.US)
        val forceP2p = normalizedHint == "p2p" || normalizedHint == "webrtc"
        val forceRelay = normalizedHint == "relay"
        val wire = JSONObject().put("kind", bleKind).put("payload", lanPayload)
        val bleBytes = wire.toString().toByteArray(StandardCharsets.UTF_8)
        val blePreferredMaxBytes = cfg.blePreferredMaxBytes.coerceIn(ME_ME_BLE_PREFERRED_MAX_BYTES_MIN, ME_ME_BLE_MAX_MESSAGE_BYTES)
        val bleWithinPreferred = bleBytes.size <= blePreferredMaxBytes
        val bleWithinHardLimit = bleBytes.size <= ME_ME_BLE_MAX_MESSAGE_BYTES
        val shouldUseBle = when (normalizedHint) {
            "ble" -> bleWithinHardLimit
            "lan", "wifi" -> false
            "relay" -> false
            "p2p", "webrtc" -> false
            else -> (route?.hasBle == true && bleWithinPreferred) ||
                (conn.method.trim().lowercase(Locale.US) == "ble" && route?.host.isNullOrBlank() && bleWithinHardLimit)
        }
        val decisionReason = when (normalizedHint) {
            "ble" -> if (bleWithinHardLimit) "transport_forced_ble" else "transport_forced_ble_payload_too_large"
            "lan", "wifi" -> "transport_forced_lan"
            "relay" -> "transport_forced_relay"
            "p2p", "webrtc" -> "transport_forced_p2p"
            else -> if (route?.hasBle == true && bleWithinPreferred) {
                "ble_preferred_size_window"
            } else if (conn.method.trim().lowercase(Locale.US) == "ble" && route?.host.isNullOrBlank() && bleWithinHardLimit) {
                "conn_method_ble_host_unavailable"
            } else if (route?.hasBle == true && !bleWithinPreferred) {
                "prefer_lan_ble_size_over_preferred"
            } else {
                "prefer_lan"
            }
        }
        if (forceP2p) {
            val p2pOut = deliverMeMeP2pPayload(peerDeviceId, lanPayload, bleKind)
            logMeMeTrace(
                event = "message.delivery",
                messageId = messageId,
                fields = JSONObject()
                    .put("peer_device_id", peerDeviceId)
                    .put("resolved_transport", "p2p")
                    .put("ok", p2pOut.optBoolean("ok", false))
                    .put("error", p2pOut.optString("error", "").trim().takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            )
            return p2pOut
        }
        if (forceRelay) {
            val relayOut = deliverMeMeRelayPayload(peerDeviceId, lanPayload)
            logMeMeTrace(
                event = "message.delivery",
                messageId = messageId,
                fields = JSONObject()
                    .put("peer_device_id", peerDeviceId)
                    .put("resolved_transport", "relay")
                    .put("ok", relayOut.optBoolean("ok", false))
                    .put("error", relayOut.optString("error", "").trim().takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            )
            return relayOut
        }
        logMeMeTrace(
            event = "message.route_decision",
            messageId = messageId,
            fields = JSONObject()
                .put("peer_device_id", peerDeviceId)
                .put("requested_transport", normalizedHint.ifBlank { "auto" })
                .put("resolved_transport", if (shouldUseBle) "ble" else "lan")
                .put("decision_reason", decisionReason)
                .put("connection_state", conn.state)
                .put("connection_method", conn.method)
                .put("route_host", route?.host ?: "")
                .put("route_port", route?.port ?: ME_ME_LAN_PORT)
                .put("route_has_ble", route?.hasBle ?: false)
                .put("ble_payload_bytes", bleBytes.size)
                .put("ble_preferred_max_bytes", blePreferredMaxBytes)
                .put("lan_path", lanPath)
        )
        if (shouldUseBle) {
            val deliveryId = if (bleKind == "data_ingest") lanPayload.optString("delivery_id", "").trim() else ""
            val ackWaiter = if (deliveryId.isNotBlank()) MeMeBleDataAckWaiter() else null
            if (ackWaiter != null) {
                meMeBleDataAckWaiters[deliveryId] = ackWaiter
            }
            if (!bleWithinHardLimit) {
                if (deliveryId.isNotBlank()) meMeBleDataAckWaiters.remove(deliveryId)
                logMeMeTrace(
                    event = "message.delivery",
                    messageId = messageId,
                    fields = JSONObject()
                        .put("peer_device_id", peerDeviceId)
                        .put("resolved_transport", "ble")
                        .put("ok", false)
                        .put("error", "ble_payload_too_large")
                        .put("bytes", bleBytes.size)
                        .put("max_bytes", ME_ME_BLE_MAX_MESSAGE_BYTES)
                )
                return JSONObject()
                    .put("ok", false)
                    .put("transport", "ble")
                    .put("error", "ble_payload_too_large")
                    .put("bytes", bleBytes.size)
                    .put("max_bytes", ME_ME_BLE_MAX_MESSAGE_BYTES)
            }
            val out = meMeDiscovery.sendBlePayload(peerDeviceId, bleBytes, timeoutMs).put("transport", "ble")
            if (ackWaiter != null) {
                try {
                    if (out.optBoolean("ok", false)) {
                        val ackWaitMs = timeoutMs.coerceIn(1500L, 12_000L)
                        val ackArrived = ackWaiter.latch.await(ackWaitMs, TimeUnit.MILLISECONDS)
                        val ack = ackWaiter.ack
                        if (!ackArrived || ack == null) {
                            out.put("ok", false)
                                .put("error", "ble_data_ack_timeout")
                                .put("ack_wait_ms", ackWaitMs)
                        } else if (!ack.optBoolean("ok", false)) {
                            out.put("ok", false)
                                .put("error", ack.optString("error", "").trim().ifBlank { "ble_data_ack_failed" })
                                .put("ack", ack)
                        } else {
                            out.put("ack", ack)
                        }
                    }
                } finally {
                    if (deliveryId.isNotBlank()) meMeBleDataAckWaiters.remove(deliveryId)
                }
            }
            if (!out.optBoolean("ok", false) && normalizedHint.isBlank()) {
                val host = route?.host.orEmpty()
                if (host.isNotBlank()) {
                    val port = route?.port ?: ME_ME_LAN_PORT
                    val lanOut = postMeMeLanJson(host, port, lanPath, lanPayload).put("transport", "lan")
                    if (lanOut.optBoolean("ok", false)) {
                        logMeMeTrace(
                            event = "message.delivery.fallback",
                            messageId = messageId,
                            fields = JSONObject()
                                .put("peer_device_id", peerDeviceId)
                                .put("from_transport", "ble")
                                .put("to_transport", "lan")
                                .put("reason", out.optString("error", "ble_failed"))
                        )
                        return lanOut
                    }
                }
            }
            if (!out.optBoolean("ok", false) && normalizedHint.isBlank() && currentMeMeP2pConfig().enabled) {
                val p2pOut = deliverMeMeP2pPayload(peerDeviceId, lanPayload, bleKind)
                if (p2pOut.optBoolean("ok", false)) {
                    logMeMeTrace(
                        event = "message.delivery.fallback",
                        messageId = messageId,
                        fields = JSONObject()
                            .put("peer_device_id", peerDeviceId)
                            .put("from_transport", "ble")
                            .put("to_transport", "p2p")
                            .put("reason", out.optString("error", "ble_failed"))
                    )
                    return p2pOut
                }
            }
            if (!out.optBoolean("ok", false) && normalizedHint.isBlank() && allowRelayFallback) {
                val relayOut = deliverMeMeRelayPayload(peerDeviceId, lanPayload)
                if (relayOut.optBoolean("ok", false)) {
                    logMeMeTrace(
                        event = "message.delivery.fallback",
                        messageId = messageId,
                        fields = JSONObject()
                            .put("peer_device_id", peerDeviceId)
                            .put("from_transport", "ble")
                            .put("to_transport", "relay")
                            .put("reason", out.optString("error", "ble_failed"))
                    )
                    return relayOut
                }
            }
            logMeMeTrace(
                event = "message.delivery",
                messageId = messageId,
                fields = JSONObject()
                    .put("peer_device_id", peerDeviceId)
                    .put("resolved_transport", "ble")
                    .put("ok", out.optBoolean("ok", false))
                    .put("error", out.optString("error", "").trim().takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            )
            return out
        }
        val host = route?.host.orEmpty()
        val port = route?.port ?: ME_ME_LAN_PORT
        if (host.isBlank()) {
            if (normalizedHint.isBlank() && currentMeMeP2pConfig().enabled) {
                val p2pOut = deliverMeMeP2pPayload(peerDeviceId, lanPayload, bleKind)
                if (p2pOut.optBoolean("ok", false)) {
                    logMeMeTrace(
                        event = "message.delivery.fallback",
                        messageId = messageId,
                        fields = JSONObject()
                            .put("peer_device_id", peerDeviceId)
                            .put("from_transport", "lan")
                            .put("to_transport", "p2p")
                            .put("reason", "peer_host_unavailable")
                    )
                    return p2pOut
                }
            }
            if (normalizedHint.isBlank() && allowRelayFallback) {
                val relayOut = deliverMeMeRelayPayload(peerDeviceId, lanPayload)
                if (relayOut.optBoolean("ok", false)) {
                    logMeMeTrace(
                        event = "message.delivery.fallback",
                        messageId = messageId,
                        fields = JSONObject()
                            .put("peer_device_id", peerDeviceId)
                            .put("from_transport", "lan")
                            .put("to_transport", "relay")
                            .put("reason", "peer_host_unavailable")
                    )
                    return relayOut
                }
            }
            logMeMeTrace(
                event = "message.delivery",
                messageId = messageId,
                fields = JSONObject()
                    .put("peer_device_id", peerDeviceId)
                    .put("resolved_transport", "lan")
                    .put("ok", false)
                    .put("error", "peer_host_unavailable")
            )
            return JSONObject().put("ok", false).put("transport", "lan").put("error", "peer_host_unavailable")
        }
        var out = postMeMeLanJson(host, port, lanPath, lanPayload).put("transport", "lan")
        if (!out.optBoolean("ok", false) && normalizedHint.isBlank()) {
            if (route?.hasBle == true && bleWithinPreferred) {
                if (bleWithinHardLimit) {
                    val bleOut = meMeDiscovery.sendBlePayload(peerDeviceId, bleBytes, timeoutMs).put("transport", "ble")
                    if (bleOut.optBoolean("ok", false)) {
                        logMeMeTrace(
                            event = "message.delivery.fallback",
                            messageId = messageId,
                            fields = JSONObject()
                                .put("peer_device_id", peerDeviceId)
                                .put("from_transport", "lan")
                                .put("to_transport", "ble")
                                .put("reason", out.optString("error", "lan_failed"))
                        )
                        out = bleOut
                    }
                }
            }
            if (!out.optBoolean("ok", false) && currentMeMeP2pConfig().enabled) {
                val p2pOut = deliverMeMeP2pPayload(peerDeviceId, lanPayload, bleKind)
                if (p2pOut.optBoolean("ok", false)) {
                    logMeMeTrace(
                        event = "message.delivery.fallback",
                        messageId = messageId,
                        fields = JSONObject()
                            .put("peer_device_id", peerDeviceId)
                            .put("from_transport", "lan")
                            .put("to_transport", "p2p")
                            .put("reason", out.optString("error", "lan_failed"))
                    )
                    out = p2pOut
                }
            }
            if (!out.optBoolean("ok", false) && allowRelayFallback) {
                val relayOut = deliverMeMeRelayPayload(peerDeviceId, lanPayload)
                if (relayOut.optBoolean("ok", false)) {
                    logMeMeTrace(
                        event = "message.delivery.fallback",
                        messageId = messageId,
                        fields = JSONObject()
                            .put("peer_device_id", peerDeviceId)
                            .put("from_transport", "lan")
                            .put("to_transport", "relay")
                            .put("reason", out.optString("error", "lan_failed"))
                    )
                    out = relayOut
                }
            }
        }
        logMeMeTrace(
            event = "message.delivery",
            messageId = messageId,
            fields = JSONObject()
                .put("peer_device_id", peerDeviceId)
                .put("resolved_transport", "lan")
                .put("ok", out.optBoolean("ok", false))
                .put("error", out.optString("error", "").trim().takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("http_status", out.optInt("http_status", 0))
        )
        return out
    }

    private fun evaluateMeMeRoute(
        peerDeviceId: String,
        transportHint: String = "auto",
        probeLan: Boolean = true
    ): JSONObject {
        val route = resolveMeMePeerRoute(peerDeviceId)
        val conn = meMeConnections[peerDeviceId]
        val lanHost = route?.host.orEmpty()
        val lanPort = route?.port ?: ME_ME_LAN_PORT
        val lanVisible = lanHost.isNotBlank()
        val lanReachable = lanVisible && (!probeLan || probeMeMeLanHealth(lanHost, lanPort))
        val bleReachable = route?.hasBle == true
        val p2pCfg = currentMeMeP2pConfig()
        val p2pReachable = p2pCfg.enabled && (meMeP2pManager.isConnected(peerDeviceId) || p2pCfg.signalingUrl.isNotBlank())
        val relayCfg = currentMeMeRelayConfig()
        val relayReachable = relayCfg.enabled && relayCfg.gatewayBaseUrl.isNotBlank() && relayCfg.gatewayAdminSecret.isNotBlank()
        val normalizedHint = transportHint.trim().lowercase(Locale.US)
        val selected = when (normalizedHint) {
            "lan", "wifi" -> if (lanReachable) "lan" else "unavailable"
            "ble" -> if (bleReachable) "ble" else "unavailable"
            "p2p", "webrtc" -> if (p2pReachable) "p2p" else "unavailable"
            "relay" -> if (relayReachable) "relay" else "unavailable"
            else -> when {
                lanReachable -> "lan"
                bleReachable -> "ble"
                p2pReachable -> "p2p"
                relayReachable -> "relay"
                else -> "unavailable"
            }
        }
        val reason = when {
            selected == "lan" -> "lan_reachable"
            selected == "ble" -> "lan_unreachable_ble_visible"
            selected == "p2p" -> "direct_unavailable_p2p_connected"
            selected == "relay" -> "direct_unavailable_relay_enabled"
            normalizedHint in setOf("lan", "wifi") -> "lan_forced_unavailable"
            normalizedHint == "ble" -> "ble_forced_unavailable"
            normalizedHint in setOf("p2p", "webrtc") -> "p2p_forced_unavailable"
            normalizedHint == "relay" -> "relay_forced_unavailable"
            else -> "no_route_available"
        }
        return JSONObject()
            .put("peer_device_id", peerDeviceId)
            .put("connection_state", conn?.state ?: "none")
            .put("connection_method", conn?.method ?: "")
            .put("lan_host", lanHost)
            .put("lan_port", lanPort)
            .put("lan_visible", lanVisible)
            .put("lan_reachable", lanReachable)
            .put("ble_reachable", bleReachable)
            .put("p2p_reachable", p2pReachable)
            .put("relay_reachable", relayReachable)
            .put("nearby", lanVisible || bleReachable)
            .put("same_lan", lanReachable)
            .put("selected_transport", selected)
            .put("selection_reason", reason)
            .put("checked_at", System.currentTimeMillis())
    }

    private fun deliverMeMeRelayPayload(peerDeviceId: String, encryptedPayload: JSONObject): JSONObject {
        val cfg = currentMeMeRelayConfig()
        if (!cfg.enabled) return JSONObject().put("ok", false).put("transport", "relay").put("error", "relay_disabled")
        val adminSecret = cfg.gatewayAdminSecret.trim()
        if (adminSecret.isBlank()) return JSONObject().put("ok", false).put("transport", "relay").put("error", "gateway_admin_secret_missing")
        val issue = postMeMeRelayJson(
            baseUrl = cfg.gatewayBaseUrl,
            path = "/route_token/issue",
            payload = JSONObject()
                .put("device_id", peerDeviceId)
                .put("source", "me_me_data")
                .put("ttl_sec", cfg.routeTokenTtlSec.coerceIn(30, 86_400)),
            headers = mapOf("X-Admin-Secret" to adminSecret)
        )
        if (!issue.optBoolean("ok", false)) {
            return JSONObject()
                .put("ok", false)
                .put("transport", "relay")
                .put("error", "relay_route_token_issue_failed")
                .put("result", issue)
        }
        val routeToken = issue.optJSONObject("body")?.optString("route_token", "")?.trim().orEmpty()
        if (routeToken.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("transport", "relay")
                .put("error", "relay_route_token_missing")
        }
        // Use provider=me_me so the gateway stores the raw encrypted payload
        // without wrapping it in normalized/provider structure. This allows the
        // receiving device's relay ingest handler to find the encrypted fields
        // (session_id, iv_b64, ciphertext_b64) at the top level.
        val webhook = postMeMeRelayJson(
            baseUrl = cfg.gatewayBaseUrl,
            path = "/webhook/$routeToken?provider=me_me",
            payload = encryptedPayload
        )
        if (!webhook.optBoolean("ok", false)) {
            return JSONObject()
                .put("ok", false)
                .put("transport", "relay")
                .put("error", "relay_notify_failed")
                .put("result", webhook)
        }
        meMeRelayLastNotifyAtMs = System.currentTimeMillis()
        return JSONObject()
            .put("ok", true)
            .put("transport", "relay")
            .put("issue_result", issue)
            .put("webhook_result", webhook)
    }

    private fun deliverMeMeBootstrapPayload(
        peerDeviceId: String,
        transportHint: String,
        timeoutMs: Long,
        lanPath: String,
        lanPayload: JSONObject,
        bleKind: String,
        lanHostOverride: String = "",
        lanPortOverride: Int? = null
    ): JSONObject {
        val route = resolveMeMePeerRoute(peerDeviceId)
        val normalizedHint = transportHint.trim().lowercase(Locale.US)
        val shouldUseBle = when (normalizedHint) {
            "ble" -> true
            "lan", "wifi" -> false
            else -> lanHostOverride.isBlank() && (route?.host.isNullOrBlank() || (route?.hasBle ?: false))
        }
        if (shouldUseBle) {
            val wire = JSONObject().put("kind", bleKind).put("payload", lanPayload)
            val bytes = wire.toString().toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > ME_ME_BLE_MAX_MESSAGE_BYTES) {
                return JSONObject()
                    .put("ok", false)
                    .put("transport", "ble")
                    .put("error", "ble_payload_too_large")
                    .put("bytes", bytes.size)
                    .put("max_bytes", ME_ME_BLE_MAX_MESSAGE_BYTES)
            }
            return meMeDiscovery.sendBlePayload(peerDeviceId, bytes, timeoutMs).put("transport", "ble")
        }
        val host = lanHostOverride.ifBlank { route?.host.orEmpty() }
        val port = lanPortOverride ?: route?.port ?: ME_ME_LAN_PORT
        if (host.isBlank()) return JSONObject().put("ok", false).put("transport", "lan").put("error", "peer_host_unavailable")
        return postMeMeLanJson(host, port, lanPath, lanPayload).put("transport", "lan")
    }

    private data class MeMePeerRoute(
        val host: String,
        val port: Int,
        val hasBle: Boolean
    )

    private fun resolveMeMePeerRoute(peerDeviceId: String): MeMePeerRoute? {
        val discovered = meMeDiscovery.statusJson(currentMeMeDiscoveryConfig()).optJSONArray("discovered")
        val peer = findDiscoveredPeer(discovered, peerDeviceId) ?: return null
        val wifi = peer.optJSONObject("wifi")
        val host = wifi?.optString("host", "")?.trim().orEmpty()
        val port = wifi?.optInt("port", ME_ME_LAN_PORT) ?: ME_ME_LAN_PORT
        val hasBle = peer.optJSONObject("ble") != null
        return MeMePeerRoute(host = host, port = port, hasBle = hasBle)
    }

    private fun handleMeMeMessagesPull(payload: JSONObject): Response {
        cleanupExpiredMeMeState()
        val peerDeviceId = payload.optString("peer_device_id", "").trim()
        if (peerDeviceId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "peer_device_id_required")
        val limit = payload.optInt("limit", 50).coerceIn(1, 200)
        val consume = payload.optBoolean("consume", true)
        val list = meMeInboundMessages[peerDeviceId] ?: mutableListOf()
        val items = synchronized(list) {
            val take = list.takeLast(limit)
            if (consume && take.isNotEmpty()) {
                repeat(take.size) { list.removeAt(0) }
            }
            take
        }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("peer_device_id", peerDeviceId)
                .put("items", JSONArray(items))
                .put("count", items.size)
        )
    }

    private fun handleMeMeRelayStatus(): Response {
        val cfg = currentMeMeRelayConfig()
        val relayEvents = synchronized(meMeRelayEventsLock) { meMeRelayEvents.size }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("relay", cfg.toJson(includeSecrets = false, fcmToken = NotifyGatewayClient.loadFcmToken(context)))
                .put("event_queue_count", relayEvents)
                .put("last_register_at", if (meMeRelayLastRegisterAtMs > 0L) meMeRelayLastRegisterAtMs else JSONObject.NULL)
                .put("last_notify_at", if (meMeRelayLastNotifyAtMs > 0L) meMeRelayLastNotifyAtMs else JSONObject.NULL)
                .put("last_gateway_pull_at", if (meMeRelayLastGatewayPullAtMs > 0L) meMeRelayLastGatewayPullAtMs else JSONObject.NULL)
        )
    }

    private fun handleMeMeRelayConfigGet(): Response {
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("config", currentMeMeRelayConfig().toJson(includeSecrets = false, fcmToken = NotifyGatewayClient.loadFcmToken(context)))
        )
    }

    private fun handleMeMeRelayConfigSet(payload: JSONObject): Response {
        val prev = currentMeMeRelayConfig()
        val next = MeMeRelayConfig(
            enabled = if (payload.has("enabled")) payload.optBoolean("enabled", prev.enabled) else prev.enabled,
            gatewayBaseUrl = normalizeMeMeRelayBaseUrl(payload.optString("gateway_base_url", prev.gatewayBaseUrl)),
            provider = payload.optString("provider", prev.provider).trim().ifBlank { prev.provider },
            routeTokenTtlSec = payload.optInt("route_token_ttl_sec", prev.routeTokenTtlSec).coerceIn(30, 86_400),
            gatewayAdminSecret = prev.gatewayAdminSecret
        )
        val adminSecretOverride = if (payload.has("gateway_admin_secret")) payload.optString("gateway_admin_secret", "").trim() else null
        val clearAdminSecret = payload.optBoolean("clear_gateway_admin_secret", false)
        saveMeMeRelayConfig(next, adminSecretOverride = adminSecretOverride, clearAdminSecret = clearAdminSecret)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("config", currentMeMeRelayConfig().toJson(includeSecrets = false, fcmToken = NotifyGatewayClient.loadFcmToken(context)))
        )
    }

    private fun handleMeMeRelayRegister(payload: JSONObject): Response {
        val cfg = currentMeMeRelayConfig()
        val pushToken = NotifyGatewayClient.loadFcmToken(context)
        if (pushToken.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "fcm_token_not_available")
        val body = JSONObject()
            .put("device_id", currentMeMeConfig().deviceId)
            .put("fcm_token", pushToken)
            .put("platform", "android")
        val result = postMeMeRelayJson(
            baseUrl = cfg.gatewayBaseUrl,
            path = "/devices/register",
            payload = body
        )
        if (!result.optBoolean("ok", false)) {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "relay_register_failed", result)
        }
        meMeRelayLastRegisterAtMs = System.currentTimeMillis()
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("registered", true)
                .put("relay_result", result)
                .put("config", currentMeMeRelayConfig().toJson(includeSecrets = false, fcmToken = pushToken))
        )
    }

    private fun handleMeMeRelayIngest(payload: JSONObject): Response {
        val source = payload.optString("source", "relay").trim().ifBlank { "relay" }
        val alert = buildRelayAlert(payload, source = source)
        val event = JSONObject()
            .put("received_at", System.currentTimeMillis())
            .put("source", source)
            .put("event_id", payload.optString("event_id", "").trim())
            .put("provider", alert.optString("provider", source))
            .put("kind", alert.optString("kind", "generic.event"))
            .put("summary", alert.optString("summary", "Relay event received from $source"))
            .put("payload", payload)
        synchronized(meMeRelayEventsLock) {
            meMeRelayEvents.add(event)
            while (meMeRelayEvents.size > 500) meMeRelayEvents.removeAt(0)
        }

        var meMeIngested = false
        val directEncrypted = payload.optJSONObject("payload") ?: payload
        if (
            directEncrypted.has("session_id") &&
            directEncrypted.has("from_device_id") &&
            directEncrypted.has("iv_b64") &&
            directEncrypted.has("ciphertext_b64")
        ) {
            val resp = handleMeMeDataIngest(directEncrypted, sourceIp = "relay")
            meMeIngested = resp.status == Response.Status.OK
        }
        notifyBrainEvent(
            name = "me.me.received",
            payload = JSONObject()
                .put("session_id", "default")
                .put("source", "me_me.receive")
                .put("ui_visible", false)
                .put("event_id", payload.optString("event_id", "").trim())
                .put("received_origin", "external")
                .put("received_transport", "gateway")
                .put("received_source", source)
                .put("received_provider", alert.optString("provider", source))
                .put("received_kind", alert.optString("kind", "generic.event"))
                .put("summary", alert.optString("summary", "Received event from $source")),
            priority = alert.optString("priority", "normal"),
            interruptPolicy = "turn_end",
            coalesceKey = alert.optString("coalesce_key", "me_me_received_external"),
            coalesceWindowMs = 5_000L
        )
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("stored", true)
                .put("me_me_data_ingested", meMeIngested)
        )
    }

    private fun buildRelayAlert(ingestPayload: JSONObject, source: String): JSONObject {
        val topSource = source.trim().ifBlank { "relay" }
        val eventPayload = ingestPayload.optJSONObject("payload")
        val provider = eventPayload?.optString("provider", topSource)?.trim().orEmpty().ifBlank { topSource }
        val normalized = eventPayload?.optJSONObject("normalized")
        val kind = normalized?.optString("kind", "")?.trim().orEmpty().ifBlank { "generic.event" }
        val eventId = ingestPayload.optString("event_id", "").trim()
        if (kind == "discord.message") {
            val author = normalized?.optJSONObject("author")
            val authorName = author?.optString("global_name", "")?.trim().orEmpty()
                .ifBlank { author?.optString("username", "")?.trim().orEmpty() }
                .ifBlank { author?.optString("id", "")?.trim().orEmpty() }
                .ifBlank { "unknown" }
            val authorId = author?.optString("id", "")?.trim().orEmpty()
            val channelId = normalized?.optString("channel_id", "")?.trim().orEmpty()
            val content = normalized?.optString("content", "")?.trim().orEmpty()
            val summary = if (content.isBlank()) {
                "Discord message from $authorName"
            } else {
                "Discord: $authorName - ${truncateRelaySummary(content, 100)}"
            }
            val coalesce = "me_me_received_discord_${channelId.ifBlank { "na" }}_${authorId.ifBlank { "na" }}"
            return JSONObject()
                .put("provider", provider)
                .put("kind", kind)
                .put("summary", summary)
                .put("priority", "normal")
                .put("coalesce_key", coalesce)
                .put("event_id", eventId)
        }
        if (kind == "slack.event") {
            val userId = normalized?.optString("user_id", "")?.trim().orEmpty().ifBlank { "unknown" }
            val channelId = normalized?.optString("channel_id", "")?.trim().orEmpty()
            val eventType = normalized?.optString("event_type", "")?.trim().orEmpty().ifBlank { "event" }
            val text = normalized?.optString("text", "")?.trim().orEmpty()
            val summary = if (text.isBlank()) {
                "Slack $eventType from $userId"
            } else {
                "Slack $eventType: ${truncateRelaySummary(text, 100)}"
            }
            val coalesce = "me_me_received_slack_${channelId.ifBlank { "na" }}_${eventType}"
            return JSONObject()
                .put("provider", provider)
                .put("kind", kind)
                .put("summary", summary)
                .put("priority", "normal")
                .put("coalesce_key", coalesce)
                .put("event_id", eventId)
        }
        val raw = normalized?.optJSONObject("raw")
        val rawEvent = raw?.optString("event", "")?.trim().orEmpty()
        val rawFromDevice = raw?.optString("from_device_id", "")?.trim().orEmpty()
        val summary = when {
            rawEvent.isNotBlank() && rawFromDevice.isNotBlank() -> "Received $rawEvent from $rawFromDevice"
            rawEvent.isNotBlank() -> "Received $rawEvent"
            else -> "Received event from $topSource"
        }
        return JSONObject()
            .put("provider", provider)
            .put("kind", kind)
            .put("summary", summary)
            .put("priority", "normal")
            .put("coalesce_key", "me_me_received_external")
            .put("event_id", eventId)
    }

    private fun truncateRelaySummary(text: String, maxChars: Int): String {
        val compact = text.replace("\\s+".toRegex(), " ").trim()
        if (compact.length <= maxChars) return compact
        return compact.substring(0, maxChars.coerceAtLeast(1)) + "..."
    }

    private fun logMeMeTrace(event: String, messageId: String, fields: JSONObject) {
        val payload = JSONObject()
            .put("component", "me.me")
            .put("event", event)
            .put("message_id", messageId.trim().takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("ts_ms", System.currentTimeMillis())
            .put("fields", fields)
        Log.i(TAG, "me.me.trace ${payload}")
    }

    private fun maybePullMeMeRelayEventsFromGateway(
        force: Boolean,
        limit: Int = 50,
        consume: Boolean = true
    ): JSONObject {
        val cfg = currentMeMeRelayConfig()
        if (!cfg.enabled) return JSONObject().put("status", "skipped").put("reason", "relay_disabled")
        val adminSecret = cfg.gatewayAdminSecret.trim()
        if (adminSecret.isBlank()) return JSONObject().put("status", "skipped").put("reason", "gateway_admin_secret_missing")
        val now = System.currentTimeMillis()
        if (!force && (now - meMeRelayLastGatewayPullAtMs) < ME_ME_RELAY_PULL_MIN_INTERVAL_MS) {
            return JSONObject().put("status", "skipped").put("reason", "throttled")
        }
        val body = JSONObject()
            .put("device_id", currentMeMeConfig().deviceId)
            .put("limit", limit.coerceIn(1, 200))
            .put("consume", consume)
        val result = postMeMeRelayJson(
            baseUrl = cfg.gatewayBaseUrl,
            path = "/events/pull",
            payload = body,
            headers = mapOf("X-Admin-Secret" to adminSecret)
        )
        if (!result.optBoolean("ok", false)) {
            return JSONObject()
                .put("status", "error")
                .put("reason", "gateway_pull_failed")
                .put("result", result)
        }
        val items = result.optJSONObject("body")?.optJSONArray("items") ?: JSONArray()
        var ingested = 0
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val ingestPayload = JSONObject()
                .put("source", item.optString("provider", "gateway_pull").trim().ifBlank { "gateway_pull" })
                .put("event_id", item.optString("event_id", "").trim())
            val payloadObj = item.optJSONObject("payload")
            if (payloadObj != null) {
                ingestPayload.put("payload", payloadObj)
            }
            val resp = handleMeMeRelayIngest(ingestPayload)
            if (resp.status == Response.Status.OK) ingested += 1
        }
        meMeRelayLastGatewayPullAtMs = now
        return JSONObject()
            .put("status", "ok")
            .put("pulled", items.length())
            .put("ingested", ingested)
            .put("consume", consume)
    }

    private fun handleMeMeDataIngest(payload: JSONObject, sourceIp: String): Response {
        val sessionId = payload.optString("session_id", "").trim()
        val fromDeviceId = payload.optString("from_device_id", "").trim()
        val deliveryId = payload.optString("delivery_id", "").trim()
        if (sessionId.isBlank() || fromDeviceId.isBlank()) {
            maybeSendMeMeDataAck(sourceIp, deliveryId, sessionId, fromDeviceId, ok = false, error = "invalid_ingest_payload")
            return jsonError(Response.Status.BAD_REQUEST, "invalid_ingest_payload")
        }
        val conn = meMeConnections[fromDeviceId]
        if (conn == null) {
            maybeSendMeMeDataAck(sourceIp, deliveryId, sessionId, fromDeviceId, ok = false, error = "connection_not_found")
            return jsonError(Response.Status.NOT_FOUND, "connection_not_found")
        }
        if (conn.sessionId != sessionId) {
            maybeSendMeMeDataAck(sourceIp, deliveryId, sessionId, fromDeviceId, ok = false, error = "session_mismatch")
            return jsonError(Response.Status.FORBIDDEN, "session_mismatch")
        }
        val iv = payload.optString("iv_b64", "").trim()
        val ct = payload.optString("ciphertext_b64", "").trim()
        val plain = decryptMeMePayload(conn.sessionKeyB64, iv, ct)
            ?: run {
                maybeSendMeMeDataAck(sourceIp, deliveryId, sessionId, fromDeviceId, ok = false, error = "decrypt_failed")
                return jsonError(Response.Status.BAD_REQUEST, "decrypt_failed")
            }
        val fromDeviceNameHint = plain.optString("from_device_name", "").trim()
        val fromDeviceName = resolveMeMePeerDisplayName(fromDeviceId, fromDeviceNameHint)
        val summary = buildMeMeIncomingSummary(fromDeviceName, plain)
        val messagePriority = resolveMeMeMessagePriority(plain)
        val messageType = plain.optString("type", "message").trim().ifBlank { "message" }

        // Intercept system-level me.me messages (not stored in inbox, not sent to brain).
        when (messageType) {
            "permission_forward" -> {
                handleRemotePermissionForward(fromDeviceId, fromDeviceName, plain)
                maybeSendMeMeDataAck(sourceIp, deliveryId, sessionId, fromDeviceId, ok = true, error = "")
                return jsonResponse(JSONObject().put("status", "ok").put("stored", false))
            }
            "permission_resolve" -> {
                handleRemotePermissionResolve(fromDeviceId, plain)
                maybeSendMeMeDataAck(sourceIp, deliveryId, sessionId, fromDeviceId, ok = true, error = "")
                return jsonResponse(JSONObject().put("status", "ok").put("stored", false))
            }
            "permission_dismiss" -> {
                handleRemotePermissionDismiss(plain)
                maybeSendMeMeDataAck(sourceIp, deliveryId, sessionId, fromDeviceId, ok = true, error = "")
                return jsonResponse(JSONObject().put("status", "ok").put("stored", false))
            }
        }

        // Auto-save received file attachments to disk so the agent has a local rel_path
        saveMeMeReceivedFileIfPresent(plain, fromDeviceId)
        // Extract rel_path if the file was saved (available after saveMeMeReceivedFileIfPresent)
        val savedRelPath = (plain.optJSONObject("payload") ?: plain).optString("rel_path", "").trim()
        val messagePreview = buildMeMeMessagePreview(plain)
        val runAgent = shouldRunAgentForMeMeMessage(plain, messageType, messagePriority)
        val agentPrompt = if (runAgent) {
            buildMeMeAgentFollowupPrompt(
                fromDeviceId = fromDeviceId,
                fromDeviceName = fromDeviceName,
                messageType = messageType,
                messagePriority = messagePriority,
                messagePreview = messagePreview,
                savedRelPath = savedRelPath
            )
        } else {
            ""
        }
        val bucket = meMeInboundMessages.computeIfAbsent(fromDeviceId) { mutableListOf() }
        synchronized(bucket) {
            bucket.add(
                JSONObject()
                    .put("received_at", System.currentTimeMillis())
                    .put("source_ip", sourceIp)
                    .put("session_id", sessionId)
                    .put("from_device_name", fromDeviceName)
                    .put("message", plain)
            )
            while (bucket.size > 200) bucket.removeAt(0)
        }
        notifyBrainEvent(
            name = "me.me.received",
            payload = JSONObject()
                .put("session_id", "default")
                .put("source", "me_me.receive")
                .put("received_origin", "peer")
                .put("received_transport", if (sourceIp.startsWith("p2p:")) "p2p" else if (sourceIp.startsWith("ble:")) "ble" else if (sourceIp == "relay") "gateway" else "lan")
                .put("received_source", sourceIp)
                .put("received_provider", if (sourceIp == "relay") "generic" else "direct")
                .put("received_kind", messageType)
                .put("from_device_id", fromDeviceId)
                .put("from_device_name", fromDeviceName)
                .put("message_type", messageType)
                .put("message_priority", messagePriority)
                .put("message_preview", messagePreview)
                .put("summary", summary)
                .put("ui_visible", false)
                .put("run_agent", runAgent)
                .put("prompt", agentPrompt),
            priority = messagePriority,
            interruptPolicy = if (messagePriority == "low") "never" else "turn_end",
            coalesceKey = if (messagePriority == "low") "me_me_message_from_$fromDeviceId" else null,
            coalesceWindowMs = if (messagePriority == "low") 2_500L else null
        )
        maybeSendMeMeDataAck(sourceIp, deliveryId, sessionId, fromDeviceId, ok = true, error = "")
        return jsonResponse(JSONObject().put("status", "ok").put("stored", true))
    }

    private fun handleMeMeDataAck(payload: JSONObject, sourceIp: String): Response {
        val deliveryId = payload.optString("delivery_id", "").trim()
        if (deliveryId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "delivery_id_required")
        meMeBleDataAckWaiters[deliveryId]?.let { waiter ->
            val ack = JSONObject(payload.toString())
                .put("source_ip", sourceIp)
            waiter.ack = ack
            waiter.latch.countDown()
        }
        return jsonResponse(JSONObject().put("status", "ok").put("stored", true))
    }

    private fun maybeSendMeMeDataAck(
        sourceIp: String,
        deliveryId: String,
        sessionId: String,
        fromDeviceId: String,
        ok: Boolean,
        error: String
    ) {
        val did = deliveryId.trim()
        if (did.isBlank()) return
        val sourceAddress = if (sourceIp.startsWith("ble:")) sourceIp.removePrefix("ble:").trim() else ""
        val selfCfg = currentMeMeConfig()
        val ackPayload = JSONObject()
            .put("delivery_id", did)
            .put("session_id", sessionId)
            .put("from_device_id", selfCfg.deviceId)
            .put("to_device_id", fromDeviceId)
            .put("ok", ok)
            .put("error", if (ok) JSONObject.NULL else error.trim().ifBlank { "ingest_failed" })
            .put("ack_at", System.currentTimeMillis())
        var ackTransport = ""
        var ackOut = JSONObject().put("ok", false).put("error", "ack_not_attempted")
        if (sourceAddress.isNotBlank()) {
            val wire = JSONObject().put("kind", "data_ack").put("payload", ackPayload)
            val bytes = wire.toString().toByteArray(StandardCharsets.UTF_8)
            if (bytes.size <= ME_ME_BLE_MAX_MESSAGE_BYTES) {
                ackOut = meMeDiscovery.sendBleNotificationToAddress(sourceAddress, bytes, 2500L)
                ackTransport = "ble_notify"
            } else {
                ackOut = JSONObject().put("ok", false).put("error", "ble_payload_too_large")
                ackTransport = "ble_notify"
            }
        }
        if (!ackOut.optBoolean("ok", false)) {
            val route = resolveMeMePeerRoute(fromDeviceId)
            val host = route?.host.orEmpty().trim()
            if (host.isNotBlank()) {
                val port = route?.port ?: ME_ME_LAN_PORT
                val lanOut = postMeMeLanJson(host, port, "/me/me/data/ack", ackPayload)
                    .put("transport", "lan")
                if (lanOut.optBoolean("ok", false)) {
                    ackOut = lanOut
                    ackTransport = "lan"
                } else if (ackTransport.isBlank()) {
                    ackOut = lanOut
                    ackTransport = "lan"
                }
            }
        }
        logMeMeTrace(
            event = "message.ingest_ack",
            messageId = did,
            fields = JSONObject()
                .put("delivery_id", did)
                .put("ok", ackOut.optBoolean("ok", false))
                .put("ack_status", if (ok) "stored" else "failed")
                .put("ack_transport", ackTransport.ifBlank { "none" })
                .put("ack_error", ackOut.optString("error", "").trim().takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("ingest_error", if (ok) JSONObject.NULL else error.trim().ifBlank { "ingest_failed" })
        )
    }

    private fun resolveMeMePeerDisplayName(deviceId: String, hintName: String = ""): String {
        val hinted = hintName.trim()
        if (hinted.isNotBlank()) return hinted
        val connName = meMeConnections[deviceId]?.peerDeviceName?.trim().orEmpty()
        if (connName.isNotBlank()) return connName
        val presenceName = meMePeerPresence[deviceId]?.deviceName?.trim().orEmpty()
        if (presenceName.isNotBlank()) return presenceName
        return deviceId
    }

    private fun buildMeMeIncomingSummary(fromDeviceName: String, message: JSONObject): String {
        val type = message.optString("type", "message").trim().ifBlank { "message" }.lowercase(Locale.US)
        val payload = message.opt("payload")
        val fileName = if (hasMeMeAttachmentPayload(payload)) extractMeMeFileName(payload) else ""
        if (fileName.isNotBlank()) {
            return "File received from $fromDeviceName: $fileName"
        }
        val text = when (payload) {
            is JSONObject -> payload.optString("text", "").ifBlank { payload.optString("message", "") }.trim()
            is String -> payload.trim()
            else -> ""
        }
        if (text.isNotBlank()) {
            return "Message received from $fromDeviceName: ${truncateMeMeSummary(text, 80)}"
        }
        return when (type) {
            "image", "photo" -> "Image received from $fromDeviceName"
            "video" -> "Video received from $fromDeviceName"
            "audio", "voice" -> "Audio received from $fromDeviceName"
            else -> "Message received from $fromDeviceName"
        }
    }

    private fun hasMeMeAttachmentPayload(payload: Any?): Boolean {
        fun objHasAttachment(obj: JSONObject): Boolean {
            val directKeys = listOf("attachment_id", "attachment_b64", "file_b64", "bytes_b64", "content_b64", "data_b64")
            if (directKeys.any { k -> obj.optString(k, "").trim().isNotBlank() }) return true
            val attachment = obj.optJSONObject("attachment")
            if (attachment != null && objHasAttachment(attachment)) return true
            val files = obj.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val fileObj = files.optJSONObject(i)
                    if (fileObj != null && objHasAttachment(fileObj)) return true
                }
            }
            return false
        }
        return when (payload) {
            is JSONObject -> objHasAttachment(payload)
            is JSONArray -> {
                for (i in 0 until payload.length()) {
                    val fileObj = payload.optJSONObject(i)
                    if (fileObj != null && objHasAttachment(fileObj)) return true
                }
                false
            }
            else -> false
        }
    }

    private fun extractMeMeFileName(payload: Any?): String {
        fun fromObj(obj: JSONObject): String {
            val direct = listOf("file_name", "filename", "name")
            for (k in direct) {
                val v = obj.optString(k, "").trim()
                if (v.isNotBlank()) return basenameForMeMeSummary(v)
            }
            val files = obj.optJSONArray("files")
            if (files != null && files.length() > 0) {
                val firstObj = files.optJSONObject(0)
                if (firstObj != null) {
                    val nested = fromObj(firstObj)
                    if (nested.isNotBlank()) return nested
                }
                val firstStr = files.optString(0, "").trim()
                if (firstStr.isNotBlank()) return basenameForMeMeSummary(firstStr)
            }
            return ""
        }
        return when (payload) {
            is JSONObject -> fromObj(payload)
            is JSONArray -> {
                if (payload.length() <= 0) return ""
                val o = payload.optJSONObject(0)
                if (o != null) fromObj(o) else basenameForMeMeSummary(payload.optString(0, "").trim())
            }
            is String -> {
                val s = payload.trim()
                if (s.startsWith("rel_path:", ignoreCase = true)) {
                    basenameForMeMeSummary(s.substringAfter(':', "").trim())
                } else {
                    ""
                }
            }
            else -> ""
        }
    }

    private fun basenameForMeMeSummary(rawPath: String): String {
        val trimmed = rawPath.trim().ifBlank { return "" }
        val noQuery = trimmed.substringBefore('?').substringBefore('#')
        val normalized = noQuery.replace('\\', '/')
        return normalized.substringAfterLast('/', normalized).ifBlank { trimmed }
    }

    private fun truncateMeMeSummary(text: String, maxChars: Int): String {
        val compact = text.replace("\\s+".toRegex(), " ").trim()
        if (compact.length <= maxChars) return compact
        return compact.substring(0, maxChars.coerceAtLeast(1)) + "..."
    }

    private fun resolveMeMeMessagePriority(message: JSONObject): String {
        fun normalize(raw: String): String {
            return when (raw.trim().lowercase(Locale.US)) {
                "urgent", "critical", "immediate", "p0" -> "urgent"
                "high", "important", "p1" -> "high"
                "low", "minor", "background", "p3" -> "low"
                else -> "normal"
            }
        }
        val explicitTop = message.optString("priority", "").trim()
        if (explicitTop.isNotBlank()) return normalize(explicitTop)
        val payload = message.opt("payload")
        if (payload is JSONObject) {
            val explicitPayload = payload.optString("priority", "").trim()
            if (explicitPayload.isNotBlank()) return normalize(explicitPayload)
            if (payload.optBoolean("urgent", false)) return "urgent"
            if (payload.optBoolean("important", false)) return "high"
        }
        return when (message.optString("type", "").trim().lowercase(Locale.US)) {
            "command", "task", "alert" -> "high"
            "ping", "presence", "heartbeat" -> "low"
            else -> "normal"
        }
    }

    private fun buildMeMeMessagePreview(message: JSONObject): String {
        val payload = message.opt("payload")
        val text = when (payload) {
            is JSONObject -> payload.optString("text", "").ifBlank { payload.optString("message", "") }.trim()
            is String -> payload.trim()
            else -> ""
        }
        if (text.isNotBlank()) return truncateMeMeSummary(text, 120)
        val file = extractMeMeFileName(payload)
        if (file.isNotBlank()) return "file:$file"
        val type = message.optString("type", "message").trim().ifBlank { "message" }
        return "type:$type"
    }

    /**
     * If the decrypted message payload contains `data_b64` (file attachment),
     * save it under `me_me_received/<from_device_id>/` and inject `rel_path`
     * into the payload so the agent can reference the local file.
     */
    private fun saveMeMeReceivedFileIfPresent(plain: JSONObject, fromDeviceId: String) {
        try {
            val innerPayload = plain.optJSONObject("payload") ?: plain
            val dataKeys = listOf("data_b64", "file_b64", "attachment_b64", "content_b64", "bytes_b64")
            val dataKey = dataKeys.firstOrNull { innerPayload.optString(it, "").trim().isNotBlank() }
                ?: return
            val b64 = innerPayload.optString(dataKey, "").trim()
            if (b64.isBlank()) return
            val bytes = try {
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                return
            }
            if (bytes.isEmpty()) return
            val fileName = innerPayload.optString("file_name", "").trim().ifBlank {
                val mime = innerPayload.optString("mime_type", "").trim()
                val ext = when {
                    mime.startsWith("image/jpeg") || mime.startsWith("image/jpg") -> ".jpg"
                    mime.startsWith("image/png") -> ".png"
                    mime.startsWith("image/webp") -> ".webp"
                    mime.startsWith("audio/") -> ".audio"
                    mime.startsWith("video/") -> ".mp4"
                    else -> ".bin"
                }
                "received_${System.currentTimeMillis()}$ext"
            }
            // Sanitize filename
            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_").take(128)
            val safeDeviceId = fromDeviceId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val dir = File(File(context.filesDir, "user/me_me_received"), safeDeviceId)
            dir.mkdirs()
            val outFile = File(dir, safeName)
            outFile.writeBytes(bytes)
            val relPath = "me_me_received/$safeDeviceId/$safeName"
            // Inject rel_path into the inner payload so the agent sees a local file path
            innerPayload.put("rel_path", relPath)
            // Remove raw data to keep the stored message small
            innerPayload.remove(dataKey)
            innerPayload.put("saved_to_disk", true)
            innerPayload.put("file_size", bytes.size)
            Log.i(TAG, "saveMeMeReceivedFile: saved ${bytes.size} bytes to $relPath")
        } catch (ex: Exception) {
            Log.w(TAG, "saveMeMeReceivedFile: failed to save attachment: ${ex.message}")
        }
    }

    private fun shouldRunAgentForMeMeMessage(
        message: JSONObject,
        messageType: String,
        messagePriority: String
    ): Boolean {
        if (messagePriority == "low") return false
        val normalizedType = messageType.trim().lowercase(Locale.US)
        if (normalizedType in setOf("agent_request", "request", "task", "command", "agent_task", "file", "response")) {
            return true
        }
        val payload = message.opt("payload")
        if (payload is JSONObject) {
            if (payload.optBoolean("run_agent", false)) return true
            if (payload.optBoolean("requires_action", false)) return true
            val direct = payload.optString("agent_prompt", "").trim()
            if (direct.isNotBlank()) return true
        }
        return normalizedType == "message" && messagePriority in setOf("high", "urgent")
    }

    private fun buildMeMeAgentFollowupPrompt(
        fromDeviceId: String,
        fromDeviceName: String,
        messageType: String,
        messagePriority: String,
        messagePreview: String,
        savedRelPath: String = ""
    ): String {
        val safeName = fromDeviceName.trim().ifBlank { fromDeviceId }
        val safePreview = messagePreview.trim().ifBlank { "none" }
        val normalizedType = messageType.trim().lowercase(Locale.US)
        return when (normalizedType) {
            "file" -> {
                val fileInfo = if (savedRelPath.isNotBlank()) {
                    "The file has been saved to '$savedRelPath'. " +
                        "Present it to the user with rel_path: $savedRelPath"
                } else {
                    "Use device_api action me.me.messages.pull with peer_device_id='$fromDeviceId' to fetch the file. " +
                        "Present it to the user."
                }
                "A file was received from '$safeName' ($fromDeviceId) via me.me. " +
                    "Preview='$safePreview'. $fileInfo"
            }
            "response" -> {
                val pullHint = if (savedRelPath.isNotBlank()) {
                    "An attached file was saved to '$savedRelPath'. "
                } else {
                    ""
                }
                "A response was received from '$safeName' ($fromDeviceId) via me.me. " +
                    "Preview='$safePreview'. ${pullHint}" +
                    "Use device_api action me.me.messages.pull with peer_device_id='$fromDeviceId' to fetch the full message. " +
                    "Present the response to the user."
            }
            else -> (
                "A me.me message requiring action was received from '$safeName' ($fromDeviceId). " +
                    "Type=$messageType, priority=$messagePriority, preview='$safePreview'. " +
                    "Use device_api action me.me.messages.pull with peer_device_id='$fromDeviceId' to fetch the full message. " +
                    "Perform the requested action if possible, then ALWAYS send the result back via " +
                    "device_api action me.me.message.send with peer_device_id='$fromDeviceId' and type='response'. " +
                    "You MUST send a reply even if just to confirm completion. " +
                    "To send a file back, use device_api action me.me.message.send with " +
                    "peer_device_id='$fromDeviceId', type='response', and rel_path pointing to the file under user home."
                )
        }
    }

    private fun findDiscoveredPeer(discovered: JSONArray?, deviceId: String): JSONObject? {
        if (discovered == null) return null
        for (i in 0 until discovered.length()) {
            val obj = discovered.optJSONObject(i) ?: continue
            if (obj.optString("device_id", "").trim() == deviceId) return obj
        }
        return null
    }

    private fun cleanupExpiredMeMeState() {
        val now = System.currentTimeMillis()
        val activeSessionIds = meMeConnections.values.map { it.sessionId }.toSet()
        val it = meMeConnectIntents.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val req = e.value
            if (req.expiresAt in 1..now) {
                it.remove()
                continue
            }
            if (req.accepted || activeSessionIds.contains(req.sessionId)) {
                it.remove()
            }
        }
    }

    private fun markMeMeConnectIntentAccepted(requestId: String) {
        val reqId = requestId.trim()
        if (reqId.isBlank()) return
        val current = meMeConnectIntents[reqId] ?: return
        if (!current.accepted) {
            meMeConnectIntents[reqId] = current.copy(accepted = true)
        }
    }

    private fun clearResolvedMeMeConnectIntents(sessionId: String, requestId: String = "") {
        val sid = sessionId.trim()
        val rid = requestId.trim()
        val it = meMeConnectIntents.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val req = e.value
            if (rid.isNotBlank() && req.id == rid) {
                it.remove()
                continue
            }
            if (sid.isNotBlank() && req.sessionId == sid) {
                it.remove()
            }
        }
    }

    private fun encodeMeMeAcceptToken(
        requestId: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        sessionId: String,
        sourceSigAlgorithm: String,
        sourceKexAlgorithm: String,
        sourceSigPublicKeyB64: String,
        sourceEphemeralPublicKeyB64: String,
        nonceB64: String,
        expiresAt: Long,
        sourceSigPrivateKeyB64: String
    ): String? {
        val sig = signMeMeOffer(
            sourceSigPrivateKeyB64 = sourceSigPrivateKeyB64,
            requestId = requestId,
            sourceDeviceId = sourceDeviceId,
            targetDeviceId = targetDeviceId,
            sessionId = sessionId,
            sourceSigAlgorithm = sourceSigAlgorithm,
            sourceKexAlgorithm = sourceKexAlgorithm,
            sourceSigPublicKeyB64 = sourceSigPublicKeyB64,
            sourceEphemeralPublicKeyB64 = sourceEphemeralPublicKeyB64,
            nonceB64 = nonceB64,
            expiresAt = expiresAt
        ) ?: return null
        val payload = JSONObject()
            .put("v", 2)
            .put("request_id", requestId)
            .put("source_device_id", sourceDeviceId)
            .put("target_device_id", targetDeviceId)
            .put("session_id", sessionId)
            .put("source_sig_algorithm", sourceSigAlgorithm)
            .put("source_kex_algorithm", sourceKexAlgorithm)
            .put("source_sig_public_key", sourceSigPublicKeyB64)
            .put("source_ephemeral_public_key", sourceEphemeralPublicKeyB64)
            .put("nonce", nonceB64)
            .put("expires_at", expiresAt)
            .put("sig", sig)
        val b64 = Base64.encodeToString(
            payload.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "me.things:me.me.conn:$b64"
    }

    private fun decodeMeMeAcceptToken(token: String): MeMeAcceptToken? {
        val prefix = "me.things:me.me.conn:"
        if (!token.startsWith(prefix)) return null
        val raw = token.removePrefix(prefix).trim()
        if (raw.isBlank()) return null
        return runCatching {
            val decoded = Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val obj = JSONObject(String(decoded, StandardCharsets.UTF_8))
            MeMeAcceptToken(
                requestId = obj.optString("request_id", "").trim(),
                sourceDeviceId = obj.optString("source_device_id", "").trim(),
                targetDeviceId = obj.optString("target_device_id", "").trim(),
                sessionId = obj.optString("session_id", "").trim(),
                sourceSigAlgorithm = normalizeMeMeSigAlgorithm(obj.optString("source_sig_algorithm", "").trim()) ?: "ed25519",
                sourceKexAlgorithm = normalizeMeMeKexAlgorithm(obj.optString("source_kex_algorithm", "").trim()) ?: "x25519",
                sourceSigPublicKeyB64 = obj.optString("source_sig_public_key", "").trim(),
                sourceEphemeralPublicKeyB64 = obj.optString("source_ephemeral_public_key", "").trim(),
                nonceB64 = obj.optString("nonce", "").trim(),
                signatureB64 = obj.optString("sig", "").trim(),
                expiresAt = obj.optLong("expires_at", 0L)
            )
        }.getOrNull()?.takeIf {
            it.requestId.isNotBlank() && it.sourceDeviceId.isNotBlank() &&
                it.targetDeviceId.isNotBlank() && it.sessionId.isNotBlank() &&
                it.sourceSigPublicKeyB64.isNotBlank() && it.sourceEphemeralPublicKeyB64.isNotBlank() &&
                it.nonceB64.isNotBlank() && it.signatureB64.isNotBlank()
        }
    }

    private fun currentMeMeIdentityKeyPair(): MeMeIdentityKeyPair? {
        val alg = normalizeMeMeSigAlgorithm(meMePrefs.getString("identity_sig_algorithm", "")?.trim().orEmpty())
        val priv = meMePrefs.getString("identity_sig_private_pkcs8", "")?.trim().orEmpty()
        val pub = meMePrefs.getString("identity_sig_public_x509", "")?.trim().orEmpty()
        if (alg != null && priv.isNotBlank() && pub.isNotBlank()) {
            return MeMeIdentityKeyPair(algorithm = alg, privateKeyB64 = priv, publicKeyB64 = pub)
        }
        // Migrate from old ed25519-only prefs if present.
        val legacyPriv = meMePrefs.getString("identity_ed25519_private_pkcs8", "")?.trim().orEmpty()
        val legacyPub = meMePrefs.getString("identity_ed25519_public_x509", "")?.trim().orEmpty()
        if (legacyPriv.isNotBlank() && legacyPub.isNotBlank()) {
            val legacy = MeMeIdentityKeyPair(algorithm = "ed25519", privateKeyB64 = legacyPriv, publicKeyB64 = legacyPub)
            meMePrefs.edit()
                .putString("identity_sig_algorithm", legacy.algorithm)
                .putString("identity_sig_private_pkcs8", legacy.privateKeyB64)
                .putString("identity_sig_public_x509", legacy.publicKeyB64)
                .apply()
            return legacy
        }
        val generated = generateMeMeSignatureKeyPairB64() ?: return null
        meMePrefs.edit()
            .putString("identity_sig_algorithm", generated.algorithm)
            .putString("identity_sig_private_pkcs8", generated.privateKeyB64)
            .putString("identity_sig_public_x509", generated.publicKeyB64)
            .apply()
        return generated
    }

    private fun normalizeMeMeSigAlgorithm(raw: String): String? {
        return when (raw.trim().lowercase(Locale.US)) {
            "ed25519", "eddsa" -> "ed25519"
            "ecdsa_p256", "ecdsa-p256", "p256", "ec_p256" -> "ecdsa_p256"
            else -> null
        }
    }

    private fun normalizeMeMeKexAlgorithm(raw: String): String? {
        return when (raw.trim().lowercase(Locale.US)) {
            "x25519", "xdh" -> "x25519"
            "ecdh_p256", "ecdh-p256", "p256", "ec_p256" -> "ecdh_p256"
            else -> null
        }
    }

    private fun generateMeMeSignatureKeyPairB64(): MeMeIdentityKeyPair? {
        generateEd25519KeyPairB64()?.let { return it }
        generateEcP256KeyPairB64(usage = "sign")?.let { return it.copy(algorithm = "ecdsa_p256") }
        return null
    }

    private fun generateMeMeEphemeralKeyPairB64(preferredAlgorithm: String = ""): MeMeIdentityKeyPair? {
        when (normalizeMeMeKexAlgorithm(preferredAlgorithm)) {
            "x25519" -> {
                generateX25519KeyPairB64()?.let { return it }
                return null
            }
            "ecdh_p256" -> {
                generateEcP256KeyPairB64(usage = "kex")?.let { return it.copy(algorithm = "ecdh_p256") }
                return null
            }
        }
        generateX25519KeyPairB64()?.let { return it }
        generateEcP256KeyPairB64(usage = "kex")?.let { return it.copy(algorithm = "ecdh_p256") }
        return null
    }

    private fun generateEd25519KeyPairB64(): MeMeIdentityKeyPair? {
        val kp = runCatching { KeyPairGenerator.getInstance("Ed25519").generateKeyPair() }
            .onFailure { Log.w(TAG, "me.me Ed25519 keygen failed via Ed25519(default)", it) }
            .getOrNull()
        if (kp != null) {
            return MeMeIdentityKeyPair(
                algorithm = "ed25519",
                privateKeyB64 = Base64.encodeToString(kp.private.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                publicKeyB64 = Base64.encodeToString(kp.public.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            )
        }
        Log.e(TAG, "me.me Ed25519 key generation unavailable on this device")
        return null
    }

    private fun generateX25519KeyPairB64(): MeMeIdentityKeyPair? {
        val attempts = ArrayList<Pair<String, () -> KeyPairGenerator>>()
        attempts += "X25519(default)" to { KeyPairGenerator.getInstance("X25519") }
        attempts += "XDH+X25519(default)" to {
            KeyPairGenerator.getInstance("XDH").apply { initialize(ECGenParameterSpec("X25519")) }
        }
        for ((label, factory) in attempts) {
            val kp = runCatching { factory().generateKeyPair() }
                .onFailure { Log.w(TAG, "me.me X25519 keygen failed via $label", it) }
                .getOrNull() ?: continue
            return MeMeIdentityKeyPair(
                algorithm = "x25519",
                privateKeyB64 = Base64.encodeToString(kp.private.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                publicKeyB64 = Base64.encodeToString(kp.public.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            )
        }
        Log.e(TAG, "me.me X25519 key generation unavailable on this device")
        return null
    }

    private fun generateEcP256KeyPairB64(usage: String): MeMeIdentityKeyPair? {
        val attempts = ArrayList<Pair<String, () -> KeyPairGenerator>>()
        attempts += "EC(secp256r1,default)" to {
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        }
        attempts += "EC(prime256v1,default)" to {
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("prime256v1")) }
        }
        for ((label, factory) in attempts) {
            val kp = runCatching { factory().generateKeyPair() }
                .onFailure { Log.w(TAG, "me.me P-256 keygen failed via $label", it) }
                .getOrNull() ?: continue
            return MeMeIdentityKeyPair(
                algorithm = if (usage == "kex") "ecdh_p256" else "ecdsa_p256",
                privateKeyB64 = Base64.encodeToString(kp.private.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                publicKeyB64 = Base64.encodeToString(kp.public.encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            )
        }
        Log.e(TAG, "me.me P-256 key generation unavailable for usage=$usage")
        return null
    }

    private fun loadEd25519PrivateKey(privateKeyB64: String): PrivateKey? {
        return runCatching {
            val bytes = Base64.decode(privateKeyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }.getOrNull()
    }

    private fun loadEd25519PublicKey(publicKeyB64: String): PublicKey? {
        return runCatching {
            val bytes = Base64.decode(publicKeyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(bytes))
        }.getOrNull()
    }

    private fun loadX25519PrivateKey(privateKeyB64: String): PrivateKey? {
        return runCatching {
            val bytes = Base64.decode(privateKeyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            KeyFactory.getInstance("X25519").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }.getOrNull()
    }

    private fun loadX25519PublicKey(publicKeyB64: String): PublicKey? {
        return runCatching {
            val bytes = Base64.decode(publicKeyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(bytes))
        }.getOrNull()
    }

    private fun loadEcPrivateKey(privateKeyB64: String): PrivateKey? {
        return runCatching {
            val bytes = Base64.decode(privateKeyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }.getOrNull()
    }

    private fun loadEcPublicKey(publicKeyB64: String): PublicKey? {
        return runCatching {
            val bytes = Base64.decode(publicKeyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }.getOrNull()
    }

    private fun loadMeMeSigPrivateKey(sigAlgorithm: String, privateKeyB64: String): PrivateKey? {
        return when (normalizeMeMeSigAlgorithm(sigAlgorithm)) {
            "ed25519" -> loadEd25519PrivateKey(privateKeyB64)
            "ecdsa_p256" -> loadEcPrivateKey(privateKeyB64)
            else -> null
        }
    }

    private fun loadMeMeSigPublicKey(sigAlgorithm: String, publicKeyB64: String): PublicKey? {
        return when (normalizeMeMeSigAlgorithm(sigAlgorithm)) {
            "ed25519" -> loadEd25519PublicKey(publicKeyB64)
            "ecdsa_p256" -> loadEcPublicKey(publicKeyB64)
            else -> null
        }
    }

    private fun loadMeMeKexPrivateKey(kexAlgorithm: String, privateKeyB64: String): PrivateKey? {
        return when (normalizeMeMeKexAlgorithm(kexAlgorithm)) {
            "x25519" -> loadX25519PrivateKey(privateKeyB64)
            "ecdh_p256" -> loadEcPrivateKey(privateKeyB64)
            else -> null
        }
    }

    private fun loadMeMeKexPublicKey(kexAlgorithm: String, publicKeyB64: String): PublicKey? {
        return when (normalizeMeMeKexAlgorithm(kexAlgorithm)) {
            "x25519" -> loadX25519PublicKey(publicKeyB64)
            "ecdh_p256" -> loadEcPublicKey(publicKeyB64)
            else -> null
        }
    }

    private fun buildMeMeOfferSignatureMessage(
        requestId: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        sessionId: String,
        sourceSigAlgorithm: String,
        sourceKexAlgorithm: String,
        sourceSigPublicKeyB64: String,
        sourceEphemeralPublicKeyB64: String,
        nonceB64: String,
        expiresAt: Long
    ): ByteArray {
        val text = listOf(
            "v2",
            requestId,
            sourceDeviceId,
            targetDeviceId,
            sessionId,
            sourceSigAlgorithm,
            sourceKexAlgorithm,
            sourceSigPublicKeyB64,
            sourceEphemeralPublicKeyB64,
            nonceB64,
            expiresAt.toString()
        ).joinToString("\n")
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun signMeMeOffer(
        sourceSigPrivateKeyB64: String,
        sourceSigAlgorithm: String,
        sourceKexAlgorithm: String,
        requestId: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        sessionId: String,
        sourceSigPublicKeyB64: String,
        sourceEphemeralPublicKeyB64: String,
        nonceB64: String,
        expiresAt: Long
    ): String? {
        val sigAlgorithm = normalizeMeMeSigAlgorithm(sourceSigAlgorithm) ?: return null
        val kexAlgorithm = normalizeMeMeKexAlgorithm(sourceKexAlgorithm) ?: return null
        val key = loadMeMeSigPrivateKey(sigAlgorithm, sourceSigPrivateKeyB64) ?: return null
        val msg = buildMeMeOfferSignatureMessage(
            requestId = requestId,
            sourceDeviceId = sourceDeviceId,
            targetDeviceId = targetDeviceId,
            sessionId = sessionId,
            sourceSigAlgorithm = sigAlgorithm,
            sourceKexAlgorithm = kexAlgorithm,
            sourceSigPublicKeyB64 = sourceSigPublicKeyB64,
            sourceEphemeralPublicKeyB64 = sourceEphemeralPublicKeyB64,
            nonceB64 = nonceB64,
            expiresAt = expiresAt
        )
        return runCatching {
            val sig = Signature.getInstance(
                if (sigAlgorithm == "ed25519") "Ed25519" else "SHA256withECDSA"
            )
            sig.initSign(key)
            sig.update(msg)
            Base64.encodeToString(sig.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull()
    }

    private fun verifyMeMeOfferSignature(token: MeMeAcceptToken): Boolean {
        val sigAlgorithm = normalizeMeMeSigAlgorithm(token.sourceSigAlgorithm) ?: return false
        val kexAlgorithm = normalizeMeMeKexAlgorithm(token.sourceKexAlgorithm) ?: return false
        val key = loadMeMeSigPublicKey(sigAlgorithm, token.sourceSigPublicKeyB64) ?: return false
        val sigBytes = runCatching {
            Base64.decode(token.signatureB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return false
        val msg = buildMeMeOfferSignatureMessage(
            requestId = token.requestId,
            sourceDeviceId = token.sourceDeviceId,
            targetDeviceId = token.targetDeviceId,
            sessionId = token.sessionId,
            sourceSigAlgorithm = sigAlgorithm,
            sourceKexAlgorithm = kexAlgorithm,
            sourceSigPublicKeyB64 = token.sourceSigPublicKeyB64,
            sourceEphemeralPublicKeyB64 = token.sourceEphemeralPublicKeyB64,
            nonceB64 = token.nonceB64,
            expiresAt = token.expiresAt
        )
        return runCatching {
            val sig = Signature.getInstance(
                if (sigAlgorithm == "ed25519") "Ed25519" else "SHA256withECDSA"
            )
            sig.initVerify(key)
            sig.update(msg)
            sig.verify(sigBytes)
        }.getOrDefault(false)
    }

    private fun buildMeMeConfirmSignatureMessage(
        token: MeMeAcceptToken,
        responderEphemeralPublicKeyB64: String
    ): ByteArray {
        val offerDigest = MessageDigest.getInstance("SHA-256").digest(
            buildMeMeOfferSignatureMessage(
                requestId = token.requestId,
                sourceDeviceId = token.sourceDeviceId,
                targetDeviceId = token.targetDeviceId,
                sessionId = token.sessionId,
                sourceSigAlgorithm = token.sourceSigAlgorithm,
                sourceKexAlgorithm = token.sourceKexAlgorithm,
                sourceSigPublicKeyB64 = token.sourceSigPublicKeyB64,
                sourceEphemeralPublicKeyB64 = token.sourceEphemeralPublicKeyB64,
                nonceB64 = token.nonceB64,
                expiresAt = token.expiresAt
            )
        )
        val offerDigestB64 = Base64.encodeToString(offerDigest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val text = listOf(
            "v2-confirm",
            token.requestId,
            token.sessionId,
            offerDigestB64,
            responderEphemeralPublicKeyB64
        ).joinToString("\n")
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun signMeMeConfirm(
        sourceSigPrivateKeyB64: String,
        sourceSigAlgorithm: String,
        token: MeMeAcceptToken,
        responderEphemeralPublicKeyB64: String
    ): String? {
        val sigAlgorithm = normalizeMeMeSigAlgorithm(sourceSigAlgorithm) ?: return null
        val key = loadMeMeSigPrivateKey(sigAlgorithm, sourceSigPrivateKeyB64) ?: return null
        val msg = buildMeMeConfirmSignatureMessage(token, responderEphemeralPublicKeyB64)
        return runCatching {
            val sig = Signature.getInstance(
                if (sigAlgorithm == "ed25519") "Ed25519" else "SHA256withECDSA"
            )
            sig.initSign(key)
            sig.update(msg)
            Base64.encodeToString(sig.sign(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull()
    }

    private fun verifyMeMeConfirmSignature(
        responderSigAlgorithm: String,
        responderSigPublicKeyB64: String,
        responderSignatureB64: String,
        token: MeMeAcceptToken,
        responderEphemeralPublicKeyB64: String
    ): Boolean {
        val sigAlgorithm = normalizeMeMeSigAlgorithm(responderSigAlgorithm) ?: return false
        val key = loadMeMeSigPublicKey(sigAlgorithm, responderSigPublicKeyB64) ?: return false
        val sigBytes = runCatching {
            Base64.decode(responderSignatureB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return false
        val msg = buildMeMeConfirmSignatureMessage(token, responderEphemeralPublicKeyB64)
        return runCatching {
            val sig = Signature.getInstance(
                if (sigAlgorithm == "ed25519") "Ed25519" else "SHA256withECDSA"
            )
            sig.initVerify(key)
            sig.update(msg)
            sig.verify(sigBytes)
        }.getOrDefault(false)
    }

    private fun deriveMeMeSessionKeyB64(
        kexAlgorithm: String,
        ownEphemeralPrivateKeyB64: String,
        peerEphemeralPublicKeyB64: String,
        contextParts: List<String>
    ): String? {
        val kex = normalizeMeMeKexAlgorithm(kexAlgorithm) ?: return null
        val ownPrivate = loadMeMeKexPrivateKey(kex, ownEphemeralPrivateKeyB64) ?: return null
        val peerPublic = loadMeMeKexPublicKey(kex, peerEphemeralPublicKeyB64) ?: return null
        return runCatching {
            val ka = KeyAgreement.getInstance(if (kex == "x25519") "X25519" else "ECDH")
            ka.init(ownPrivate)
            ka.doPhase(peerPublic, true)
            val shared = ka.generateSecret()
            val salt = MessageDigest.getInstance("SHA-256")
                .digest(contextParts.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
            val info = "me.me.session.v2".toByteArray(StandardCharsets.UTF_8)
            val key = hkdfSha256(shared, salt, info, 32)
            Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull()
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = run {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            mac.doFinal(ikm)
        }
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter: Byte = 1
        while (out.size() < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter)
            t = mac.doFinal()
            out.write(t)
            counter = (counter + 1).toByte()
        }
        val bytes = out.toByteArray()
        return if (bytes.size == length) bytes else bytes.copyOf(length)
    }

    private data class EncryptedMessage(val ivB64: String, val ciphertextB64: String)

    private fun encryptMeMePayload(sessionKeyB64: String, payload: JSONObject): EncryptedMessage? {
        return runCatching {
            val key = Base64.decode(sessionKeyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(payload.toString().toByteArray(StandardCharsets.UTF_8))
            EncryptedMessage(
                ivB64 = Base64.encodeToString(iv, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                ciphertextB64 = Base64.encodeToString(ct, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            )
        }.getOrNull()
    }

    private fun decryptMeMePayload(sessionKeyB64: String, ivB64: String, ciphertextB64: String): JSONObject? {
        return runCatching {
            val key = Base64.decode(sessionKeyB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val iv = Base64.decode(ivB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val ct = Base64.decode(ciphertextB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ct)
            JSONObject(String(plain, StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun postMeMeLanJson(host: String, port: Int, path: String, payload: JSONObject): JSONObject {
        return try {
            val url = URI("http://$host:$port$path").toURL()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Connection", "close")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            } ?: ""
            val parsed = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            JSONObject()
                .put("ok", code in 200..299)
                .put("http_status", code)
                .put("body", parsed)
        } catch (ex: Exception) {
            JSONObject().put("ok", false).put("error", ex.message ?: "request_failed")
        }
    }

    private fun postMeMeRelayJson(
        baseUrl: String,
        path: String,
        payload: JSONObject,
        headers: Map<String, String> = emptyMap()
    ): JSONObject {
        return try {
            val normalizedBase = normalizeMeMeRelayBaseUrl(baseUrl)
            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            val url = URI("$normalizedBase$normalizedPath").toURL()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Connection", "close")
                headers.forEach { (k, v) -> if (v.isNotBlank()) setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            } ?: ""
            val parsed = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            JSONObject()
                .put("ok", code in 200..299)
                .put("http_status", code)
                .put("body", parsed)
        } catch (ex: Exception) {
            JSONObject().put("ok", false).put("error", ex.message ?: "request_failed")
        }
    }

    private fun handleMeMeBlePayload(raw: ByteArray, sourceAddress: String) {
        val obj = runCatching {
            JSONObject(String(raw, StandardCharsets.UTF_8))
        }.getOrElse {
            Log.w(TAG, "Failed to parse me.me BLE payload from $sourceAddress", it)
            return
        }
        val kind = obj.optString("kind", "").trim().lowercase(Locale.US)
        when (kind) {
            "connect_offer" -> {
                val payload = obj.optJSONObject("payload") ?: obj
                handleMeMeConnectOffer(payload, sourceIp = "ble:$sourceAddress")
            }
            "connect_confirm" -> {
                val payload = obj.optJSONObject("payload") ?: obj
                handleMeMeConnectConfirm(payload)
            }
            "data_ack" -> {
                val payload = obj.optJSONObject("payload") ?: obj
                handleMeMeDataAck(payload, sourceIp = "ble:$sourceAddress")
            }
            "data_ingest", "" -> {
                val payload = obj.optJSONObject("payload") ?: obj
                handleMeMeDataIngest(payload, sourceIp = "ble:$sourceAddress")
            }
            else -> {
                Log.w(TAG, "Unknown me.me BLE payload kind: $kind")
            }
        }
    }

    // -- me.me P2P (WebRTC DataChannel) --

    private fun handleMeMeP2pPayload(peerDeviceId: String, data: String) {
        val obj = runCatching {
            JSONObject(data)
        }.getOrElse {
            Log.w(TAG, "Failed to parse me.me P2P payload from $peerDeviceId", it)
            return
        }
        val kind = obj.optString("kind", "").trim().lowercase(Locale.US)
        when (kind) {
            "connect_offer" -> {
                val payload = obj.optJSONObject("payload") ?: obj
                handleMeMeConnectOffer(payload, sourceIp = "p2p:$peerDeviceId")
            }
            "connect_confirm" -> {
                val payload = obj.optJSONObject("payload") ?: obj
                handleMeMeConnectConfirm(payload)
            }
            "data_ack" -> {
                val payload = obj.optJSONObject("payload") ?: obj
                handleMeMeDataAck(payload, sourceIp = "p2p:$peerDeviceId")
            }
            "data_ingest", "" -> {
                val payload = obj.optJSONObject("payload") ?: obj
                handleMeMeDataIngest(payload, sourceIp = "p2p:$peerDeviceId")
            }
            else -> {
                Log.w(TAG, "Unknown me.me P2P payload kind: $kind")
            }
        }
    }

    private fun handleMeMeP2pStateChange(peerDeviceId: String, state: String) {
        Log.d(TAG, "me.me P2P state change: $peerDeviceId -> $state")
    }

    private fun ensureMeMeP2pConnected(peerDeviceId: String, timeoutMs: Long = 10_000L): Boolean {
        if (meMeP2pManager.isConnected(peerDeviceId)) return true
        val cfg = currentMeMeP2pConfig()
        if (!cfg.enabled) return false
        Log.d(TAG, "P2P: on-demand connect to $peerDeviceId (timeout=${timeoutMs}ms)")
        return meMeP2pManager.connectAndWait(peerDeviceId, timeoutMs)
    }

    private fun deliverMeMeP2pPayload(peerDeviceId: String, payload: JSONObject, kind: String = "data_ingest"): JSONObject {
        if (!ensureMeMeP2pConnected(peerDeviceId)) {
            return JSONObject().put("ok", false).put("transport", "p2p").put("error", "p2p_not_connected")
        }
        val wire = JSONObject().put("kind", kind).put("payload", payload)
        val sent = meMeP2pManager.sendJson(peerDeviceId, wire)
        return if (sent) {
            JSONObject().put("ok", true).put("transport", "p2p")
        } else {
            JSONObject().put("ok", false).put("transport", "p2p").put("error", "p2p_send_failed")
        }
    }

    private fun maybeAutoConnectP2p(peerDeviceId: String) {
        val p2pCfg = currentMeMeP2pConfig()
        if (p2pCfg.enabled && p2pCfg.autoConnect) {
            meMeExecutor.execute {
                runCatching { meMeP2pManager.connectToPeer(peerDeviceId) }
            }
        }
    }

    private data class MeMeP2pConfig(
        val enabled: Boolean,
        val signalingUrl: String,
        val iceServersJson: String,
        val autoConnect: Boolean,
        val signalingToken: String
    ) {
        fun toJson(includeSecrets: Boolean): JSONObject {
            val out = JSONObject()
                .put("enabled", enabled)
                .put("signaling_url", signalingUrl)
                .put("ice_servers", iceServersJson)
                .put("auto_connect", autoConnect)
                .put("signaling_token_configured", signalingToken.isNotBlank())
            if (includeSecrets) {
                out.put("signaling_token", signalingToken)
            }
            return out
        }
    }

    private fun currentMeMeP2pConfig(): MeMeP2pConfig {
        val token = runCatching {
            credentialStore.get(ME_ME_P2P_SIGNALING_TOKEN_CREDENTIAL)?.value.orEmpty()
        }.getOrDefault("")
        return MeMeP2pConfig(
            enabled = meMePrefs.getBoolean("p2p_enabled", false),
            signalingUrl = (meMePrefs.getString("p2p_signaling_url", DEFAULT_ME_ME_SIGNALING_URL) ?: DEFAULT_ME_ME_SIGNALING_URL).trim().ifBlank { DEFAULT_ME_ME_SIGNALING_URL },
            iceServersJson = (meMePrefs.getString("p2p_ice_servers", "[]") ?: "[]").trim().ifBlank { "[]" },
            autoConnect = meMePrefs.getBoolean("p2p_auto_connect", false),
            signalingToken = token
        )
    }

    private fun saveMeMeP2pConfig(
        cfg: MeMeP2pConfig,
        tokenOverride: String?,
        clearToken: Boolean
    ) {
        meMePrefs.edit()
            .putBoolean("p2p_enabled", cfg.enabled)
            .putString("p2p_signaling_url", cfg.signalingUrl.trim().ifBlank { DEFAULT_ME_ME_SIGNALING_URL })
            .putString("p2p_ice_servers", cfg.iceServersJson.trim().ifBlank { "[]" })
            .putBoolean("p2p_auto_connect", cfg.autoConnect)
            .apply()
        if (clearToken) {
            runCatching { credentialStore.delete(ME_ME_P2P_SIGNALING_TOKEN_CREDENTIAL) }
        } else if (tokenOverride != null) {
            val token = tokenOverride.trim()
            if (token.isBlank()) {
                runCatching { credentialStore.delete(ME_ME_P2P_SIGNALING_TOKEN_CREDENTIAL) }
            } else {
                runCatching { credentialStore.set(ME_ME_P2P_SIGNALING_TOKEN_CREDENTIAL, token) }
            }
        }
    }

    private fun buildP2pManagerConfig(cfg: MeMeP2pConfig): MeMeP2pManager.P2pConfig {
        return MeMeP2pManager.P2pConfig(
            enabled = cfg.enabled,
            signalingUrl = cfg.signalingUrl,
            iceServersJson = cfg.iceServersJson,
            autoConnect = cfg.autoConnect,
            signalingToken = cfg.signalingToken
        )
    }

    private fun handleMeMeP2pStatus(): Response {
        val cfg = currentMeMeP2pConfig()
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("p2p", meMeP2pManager.statusJson())
                .put("config", cfg.toJson(includeSecrets = false))
                .put("peers", meMeP2pManager.peerStatesJson())
        )
    }

    private fun handleMeMeP2pConfigGet(): Response {
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("config", currentMeMeP2pConfig().toJson(includeSecrets = false))
        )
    }

    private fun handleMeMeP2pConfigSet(payload: JSONObject): Response {
        val prev = currentMeMeP2pConfig()
        val next = MeMeP2pConfig(
            enabled = if (payload.has("enabled")) payload.optBoolean("enabled", prev.enabled) else prev.enabled,
            signalingUrl = payload.optString("signaling_url", prev.signalingUrl).trim().ifBlank { prev.signalingUrl },
            iceServersJson = payload.optString("ice_servers", prev.iceServersJson).trim().ifBlank { prev.iceServersJson },
            autoConnect = if (payload.has("auto_connect")) payload.optBoolean("auto_connect", prev.autoConnect) else prev.autoConnect,
            signalingToken = prev.signalingToken
        )
        val tokenOverride = if (payload.has("signaling_token")) payload.optString("signaling_token", "").trim() else null
        val clearToken = payload.optBoolean("clear_signaling_token", false)
        saveMeMeP2pConfig(next, tokenOverride = tokenOverride, clearToken = clearToken)
        val saved = currentMeMeP2pConfig()
        meMeExecutor.execute {
            runCatching { meMeP2pManager.updateConfig(buildP2pManagerConfig(saved)) }
        }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("config", saved.toJson(includeSecrets = false))
        )
    }

    private fun handleMeMeP2pConnect(payload: JSONObject): Response {
        val peerDeviceId = payload.optString("peer_device_id", "").trim()
        if (peerDeviceId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "peer_device_id_required")
        val cfg = currentMeMeP2pConfig()
        if (!cfg.enabled) return jsonError(Response.Status.CONFLICT, "p2p_not_enabled")
        meMeP2pManager.connectToPeer(peerDeviceId)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("peer_device_id", peerDeviceId)
                .put("message", "P2P connection initiated")
        )
    }

    private fun handleMeMeP2pDisconnect(payload: JSONObject): Response {
        val peerDeviceId = payload.optString("peer_device_id", "").trim()
        if (peerDeviceId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "peer_device_id_required")
        meMeP2pManager.disconnectPeer(peerDeviceId)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("peer_device_id", peerDeviceId)
                .put("disconnected", true)
        )
    }

    // ---------------------------------------------------------------------------
    // Device provisioning
    // ---------------------------------------------------------------------------

    private fun handleProvisionStart(payload: JSONObject): Response {
        val provider = payload.optString("provider", "").trim().lowercase(Locale.US)
        val cfg = currentMeMeConfig()
        val relayCfg = currentMeMeRelayConfig()
        val baseUrl = normalizeMeMeRelayBaseUrl(relayCfg.gatewayBaseUrl)
        val deviceName = cfg.deviceName
        var url = "$baseUrl/provision/start?device_id=${java.net.URLEncoder.encode(cfg.deviceId, "UTF-8")}" +
            "&device_name=${java.net.URLEncoder.encode(deviceName, "UTF-8")}"
        if (provider.isNotBlank()) {
            url += "&provider=${java.net.URLEncoder.encode(provider, "UTF-8")}"
        }
        val resp = JSONObject()
            .put("status", "ok")
            .put("url", url)
        if (provider.isNotBlank()) resp.put("provider", provider)
        return jsonResponse(resp)
    }

    private fun handleProvisionClaim(payload: JSONObject): Response {
        val provisionToken = payload.optString("provision_token", "").trim()
        if (provisionToken.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "provision_token_required")

        val cfg = currentMeMeConfig()
        val relayCfg = currentMeMeRelayConfig()
        val baseUrl = normalizeMeMeRelayBaseUrl(relayCfg.gatewayBaseUrl)

        val claimResult = postMeMeRelayJson(
            baseUrl = baseUrl,
            path = "/provision/claim",
            payload = JSONObject()
                .put("device_id", cfg.deviceId)
                .put("provision_token", provisionToken)
        )

        val claimOk = claimResult.optBoolean("ok", false)
        val body = claimResult.optJSONObject("body") ?: JSONObject()
        if (!claimOk || body.optString("status", "") != "ok") {
            val error = body.optString("error", "claim_failed")
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, error, claimResult)
        }

        val userSubject = body.optString("user_subject", "").trim()
        val signalingToken = body.optString("signaling_token", "").trim()
        val devicesArray = body.optJSONArray("devices") ?: org.json.JSONArray()

        // Store user subject in credential store
        if (userSubject.isNotBlank()) {
            val parts = userSubject.split(":", limit = 2)
            if (parts.size == 2) {
                val issuer = parts[0].trim().lowercase(Locale.US)
                val sub = parts[1].trim()
                runCatching { credentialStore.set("me_me_owner:$issuer", sub) }
            }
        }

        // Store signaling token and enable P2P
        if (signalingToken.isNotBlank()) {
            val prevP2p = currentMeMeP2pConfig()
            saveMeMeP2pConfig(
                prevP2p.copy(enabled = true, autoConnect = true),
                tokenOverride = signalingToken,
                clearToken = false
            )
            meMeExecutor.execute {
                runCatching { meMeP2pManager.updateConfig(buildP2pManagerConfig(currentMeMeP2pConfig())) }
            }
        }

        // Store sibling device list in SharedPrefs
        val deviceList = mutableListOf<JSONObject>()
        for (i in 0 until devicesArray.length()) {
            val d = devicesArray.optJSONObject(i) ?: continue
            deviceList.add(d)
        }
        meMePrefs.edit()
            .putString("provision_user_subject", userSubject)
            .putString("provision_devices", devicesArray.toString())
            .putLong("provision_claimed_at", System.currentTimeMillis())
            .apply()

        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("user_subject", userSubject)
                .put("signaling_token_configured", signalingToken.isNotBlank())
                .put("devices", devicesArray)
                .put("p2p_enabled", currentMeMeP2pConfig().enabled)
        )
    }

    private fun handleProvisionStatus(): Response {
        val cfg = currentMeMeConfig()
        val p2pCfg = currentMeMeP2pConfig()
        val userSubject = (meMePrefs.getString("provision_user_subject", "") ?: "").trim()
        val devicesJson = (meMePrefs.getString("provision_devices", "[]") ?: "[]").trim()
        val devices = runCatching { org.json.JSONArray(devicesJson) }.getOrDefault(org.json.JSONArray())
        val claimedAt = meMePrefs.getLong("provision_claimed_at", 0L)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("provisioned", userSubject.isNotBlank() && p2pCfg.signalingToken.isNotBlank())
                .put("user_subject", userSubject.ifBlank { JSONObject.NULL })
                .put("device_id", cfg.deviceId)
                .put("device_name", cfg.deviceName)
                .put("devices", devices)
                .put("claimed_at", if (claimedAt > 0L) claimedAt else JSONObject.NULL)
                .put("p2p_enabled", p2pCfg.enabled)
                .put("signaling_token_configured", p2pCfg.signalingToken.isNotBlank())
        )
    }

    private fun handleProvisionRefresh(): Response {
        val cfg = currentMeMeConfig()
        val relayCfg = currentMeMeRelayConfig()
        val baseUrl = normalizeMeMeRelayBaseUrl(relayCfg.gatewayBaseUrl)
        val pullSecret = NotifyGatewayClient.loadPullSecretPublic(context)

        val url = "$baseUrl/provision/devices?device_id=${java.net.URLEncoder.encode(cfg.deviceId, "UTF-8")}" +
            "&pull_secret=${java.net.URLEncoder.encode(pullSecret, "UTF-8")}"
        val result = try {
            val conn = (java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 10000
                setRequestProperty("Connection", "close")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use {
                it.readBytes().toString(java.nio.charset.StandardCharsets.UTF_8)
            } ?: ""
            val parsed = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            JSONObject().put("ok", code in 200..299).put("http_status", code).put("body", parsed)
        } catch (ex: Exception) {
            JSONObject().put("ok", false).put("error", ex.message ?: "request_failed")
        }

        val resultOk = result.optBoolean("ok", false)
        val body = result.optJSONObject("body") ?: JSONObject()
        if (!resultOk || body.optString("status", "") != "ok") {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "refresh_failed", result)
        }

        val userSubject = body.optString("user_subject", "").trim()
        val devicesArray = body.optJSONArray("devices") ?: org.json.JSONArray()
        meMePrefs.edit()
            .putString("provision_user_subject", userSubject)
            .putString("provision_devices", devicesArray.toString())
            .apply()

        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("user_subject", userSubject)
                .put("devices", devicesArray)
        )
    }

    private fun handleProvisionSignout(): Response {
        val cfg = currentMeMeConfig()
        val relayCfg = currentMeMeRelayConfig()
        val baseUrl = normalizeMeMeRelayBaseUrl(relayCfg.gatewayBaseUrl)
        val pullSecret = NotifyGatewayClient.loadPullSecretPublic(context)

        // Tell gateway to remove this device from the account
        try {
            val url = java.net.URI("$baseUrl/provision/signout").toURL()
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 10000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Connection", "close")
                doOutput = true
            }
            val body = JSONObject()
                .put("device_id", cfg.deviceId)
                .put("pull_secret", pullSecret)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "Gateway signout returned code=$code")
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Gateway signout failed", ex)
        }

        // Clear local provisioning state
        meMePrefs.edit()
            .remove("provision_user_subject")
            .remove("provision_devices")
            .remove("provision_claimed_at")
            .apply()

        // Clear signaling token (disables P2P)
        val p2pCfg = currentMeMeP2pConfig()
        if (p2pCfg.signalingToken.isNotBlank()) {
            saveMeMeP2pConfig(p2pCfg, tokenOverride = null, clearToken = true)
            meMeP2pManager.shutdown()
        }

        return jsonResponse(JSONObject().put("status", "ok"))
    }

    private fun getProvisionedSiblingDeviceIds(): Set<String> {
        val devicesJson = (meMePrefs.getString("provision_devices", "[]") ?: "[]").trim()
        val arr = runCatching { org.json.JSONArray(devicesJson) }.getOrDefault(org.json.JSONArray())
        val ids = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val id = d.optString("device_id", "").trim()
            if (id.isNotBlank()) ids.add(id)
        }
        return ids
    }

    private fun isProvisionedSibling(deviceId: String): Boolean {
        if (deviceId.isBlank()) return false
        return getProvisionedSiblingDeviceIds().contains(deviceId)
    }

    private fun getProvisionedSiblingDevicesJson(selfDeviceId: String): org.json.JSONArray {
        val devicesJson = (meMePrefs.getString("provision_devices", "[]") ?: "[]").trim()
        val arr = runCatching { org.json.JSONArray(devicesJson) }.getOrDefault(org.json.JSONArray())
        val result = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val id = d.optString("device_id", "").trim()
            if (id.isNotBlank() && id != selfDeviceId) result.put(d)
        }
        return result
    }

    private fun currentMeMeDiscoveryConfig(cfg: MeMeConfig = currentMeMeConfig()): MeMeDiscoveryManager.Config {
        return MeMeDiscoveryManager.Config(
            deviceId = cfg.deviceId,
            deviceName = cfg.deviceName,
            deviceDescription = cfg.deviceDescription,
            deviceIcon = cfg.deviceIcon,
            allowDiscovery = cfg.allowDiscovery,
            connectionMethods = cfg.connectionMethods
        )
    }

    private fun currentMeMeRelayConfig(): MeMeRelayConfig {
        val adminSecret = runCatching {
            credentialStore.get(ME_ME_RELAY_GATEWAY_ADMIN_SECRET_CREDENTIAL)?.value.orEmpty()
        }.getOrDefault("")
        return MeMeRelayConfig(
            enabled = meMePrefs.getBoolean("relay_enabled", false),
            gatewayBaseUrl = normalizeMeMeRelayBaseUrl(meMePrefs.getString("relay_gateway_base_url", DEFAULT_ME_ME_RELAY_BASE_URL) ?: DEFAULT_ME_ME_RELAY_BASE_URL),
            provider = (meMePrefs.getString("relay_provider", "me_me") ?: "me_me").trim().ifBlank { "me_me" },
            routeTokenTtlSec = meMePrefs.getInt("relay_route_token_ttl_sec", 300).coerceIn(30, 86_400),
            gatewayAdminSecret = adminSecret
        )
    }

    private fun saveMeMeRelayConfig(
        cfg: MeMeRelayConfig,
        adminSecretOverride: String?,
        clearAdminSecret: Boolean
    ) {
        meMePrefs.edit()
            .putBoolean("relay_enabled", cfg.enabled)
            .putString("relay_gateway_base_url", normalizeMeMeRelayBaseUrl(cfg.gatewayBaseUrl))
            .putString("relay_provider", cfg.provider.trim().ifBlank { "me_me" })
            .putInt("relay_route_token_ttl_sec", cfg.routeTokenTtlSec.coerceIn(30, 86_400))
            .apply()
        if (clearAdminSecret) {
            runCatching { credentialStore.delete(ME_ME_RELAY_GATEWAY_ADMIN_SECRET_CREDENTIAL) }
        } else if (adminSecretOverride != null) {
            val secret = adminSecretOverride.trim()
            if (secret.isBlank()) {
                runCatching { credentialStore.delete(ME_ME_RELAY_GATEWAY_ADMIN_SECRET_CREDENTIAL) }
            } else {
                runCatching { credentialStore.set(ME_ME_RELAY_GATEWAY_ADMIN_SECRET_CREDENTIAL, secret) }
            }
        }
    }

    private fun normalizeMeMeRelayBaseUrl(raw: String): String {
        val trimmed = raw.trim().ifBlank { DEFAULT_ME_ME_RELAY_BASE_URL }.trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private fun currentMeMeConfig(): MeMeConfig {
        val deviceId = installIdentity.get()
        if ((meMePrefs.getString("device_id", "") ?: "").trim() != deviceId) {
            meMePrefs.edit().putString("device_id", deviceId).apply()
        }
        val methodsRaw = meMePrefs.getStringSet("connection_methods", setOf("wifi", "ble"))?.toList() ?: listOf("wifi", "ble")
        return MeMeConfig(
            deviceId = deviceId,
            deviceName = (meMePrefs.getString("device_name", Build.MODEL?.trim().orEmpty()) ?: "").trim().ifBlank { "Android device" },
            deviceDescription = (meMePrefs.getString("device_description", "") ?: "").trim(),
            deviceIcon = (meMePrefs.getString("device_icon", "") ?: "").trim(),
            allowDiscovery = meMePrefs.getBoolean("allow_discovery", false),
            connectionTimeoutSec = meMePrefs.getInt("connection_timeout", 30).coerceIn(5, 300),
            maxConnections = meMePrefs.getInt("max_connections", 5).coerceIn(1, 32),
            connectionMethods = sanitizeConnectionMethods(methodsRaw),
            autoReconnect = meMePrefs.getBoolean("auto_reconnect", true),
            reconnectIntervalSec = meMePrefs.getInt("reconnect_interval", 10).coerceIn(3, 3600),
            discoveryIntervalSec = meMePrefs.getInt("discovery_interval", 60).coerceIn(10, 3600),
            connectionCheckIntervalSec = meMePrefs.getInt("connection_check_interval", 30).coerceIn(5, 3600),
            blePreferredMaxBytes = meMePrefs.getInt("ble_preferred_max_bytes", ME_ME_BLE_PREFERRED_MAX_BYTES_DEFAULT)
                .coerceIn(ME_ME_BLE_PREFERRED_MAX_BYTES_MIN, ME_ME_BLE_MAX_MESSAGE_BYTES),
            autoApproveOwnDevices = meMePrefs.getBoolean("auto_approve_own_devices", false),
            ownerIdentities = loadVerifiedOwnerIdentities(),
            allowedDevices = meMePrefs.getStringSet("allowed_devices", emptySet())?.toList()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            blockedDevices = meMePrefs.getStringSet("blocked_devices", emptySet())?.toList()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            notifyOnConnection = meMePrefs.getBoolean("notify_on_connection", true),
            notifyOnDisconnection = meMePrefs.getBoolean("notify_on_disconnection", true)
        )
    }

    private fun saveMeMeConfig(cfg: MeMeConfig) {
        val unifiedDeviceId = installIdentity.get()
        meMePrefs.edit()
            .putString("device_id", unifiedDeviceId)
            .putString("device_name", cfg.deviceName)
            .putString("device_description", cfg.deviceDescription)
            .putString("device_icon", cfg.deviceIcon)
            .putBoolean("allow_discovery", cfg.allowDiscovery)
            .putInt("connection_timeout", cfg.connectionTimeoutSec)
            .putInt("max_connections", cfg.maxConnections)
            .putStringSet("connection_methods", cfg.connectionMethods.toSet())
            .putBoolean("auto_reconnect", cfg.autoReconnect)
            .putInt("reconnect_interval", cfg.reconnectIntervalSec)
            .putInt("discovery_interval", cfg.discoveryIntervalSec)
            .putInt("connection_check_interval", cfg.connectionCheckIntervalSec)
            .putInt("ble_preferred_max_bytes", cfg.blePreferredMaxBytes)
            .putBoolean("auto_approve_own_devices", cfg.autoApproveOwnDevices)
            .putStringSet("allowed_devices", cfg.allowedDevices.toSet())
            .putStringSet("blocked_devices", cfg.blockedDevices.toSet())
            .putBoolean("notify_on_connection", cfg.notifyOnConnection)
            .putBoolean("notify_on_disconnection", cfg.notifyOnDisconnection)
            .apply()
    }

    private fun normalizeOwnerIdentity(raw: String): String {
        val v = raw.trim().lowercase(Locale.US)
        val idx = v.indexOf(':')
        if (idx <= 0 || idx >= v.length - 1) return ""
        val issuer = v.substring(0, idx).trim()
        val sub = v.substring(idx + 1).trim()
        if (issuer !in setOf("google", "github")) return ""
        if (sub.isBlank()) return ""
        return "$issuer:$sub"
    }

    private fun loadVerifiedOwnerIdentities(): List<String> {
        val google = credentialStore.get("me_me_owner:google")?.value?.trim()?.lowercase(Locale.US).orEmpty()
        if (google.isBlank()) return emptyList()
        val normalized = normalizeOwnerIdentity("google:$google")
        return if (normalized.isNotBlank()) listOf(normalized) else emptyList()
    }

    private fun readIdentityList(payload: JSONObject, key: String, fallback: List<String>): List<String> {
        val raw = readStringList(payload, key, fallback)
        return raw.map { normalizeOwnerIdentity(it) }.filter { it.isNotBlank() }.distinct()
    }

    private fun hasOwnerIdentityMatch(local: List<String>, remote: List<String>): Boolean {
        if (local.isEmpty() || remote.isEmpty()) return false
        val l = local.map { normalizeOwnerIdentity(it) }.filter { it.isNotBlank() }.toSet()
        if (l.isEmpty()) return false
        val r = remote.map { normalizeOwnerIdentity(it) }.filter { it.isNotBlank() }.toSet()
        if (r.isEmpty()) return false
        return l.any { r.contains(it) }
    }

    private fun readStringList(payload: JSONObject, key: String, fallback: List<String>): List<String> {
        val arr = payload.optJSONArray(key)
        if (arr != null) {
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotBlank()) out.add(v)
            }
            return out.distinct()
        }
        if (payload.has(key)) {
            val raw = payload.optString(key, "")
            return raw.split(',', '\n', ' ')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }
        return fallback
    }

    private fun sanitizeConnectionMethods(raw: List<String>): List<String> {
        val allowed = setOf("wifi", "ble", "other")
        val out = raw.map { it.trim().lowercase(Locale.US) }
            .filter { it in allowed }
            .distinct()
        return if (out.isEmpty()) listOf("wifi", "ble") else out
    }

    private fun handleMeSyncLocalState(): Response {
        val userRoot = File(context.filesDir, "user")
        val userFileCount = if (userRoot.exists() && userRoot.isDirectory) {
            runCatching { userRoot.walkTopDown().count { it.isFile } }.getOrElse { 0 }
        } else 0
        val protectedDb = File(File(context.filesDir, "protected"), "app.db")
        val credentials = runCatching { credentialStore.list().size }.getOrElse { 0 }
        val hasAny = userFileCount > 0 || protectedDb.exists() || credentials > 0
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("has_existing_data", hasAny)
                .put("user_file_count", userFileCount)
                .put("has_protected_db", protectedDb.exists())
                .put("credential_count", credentials)
        )
    }

    private fun handleMeSyncPrepareExport(payload: JSONObject): Response {
        return try {
            cleanupExpiredMeSyncTransfers()
            val modeRaw = payload.optString("mode", "").trim().lowercase(Locale.US)
            val migrationMode = payload.optBoolean("migration", false) || modeRaw == "migration"
            val includeUser = payload.optBoolean("include_user", true)
            val includeProtectedDb = payload.optBoolean("include_protected_db", true)
            val includeIdentity = payload.optBoolean("include_identity", migrationMode)
            val forceRefresh = payload.optBoolean("force_refresh", false)
            val active = if (forceRefresh) {
                null
            } else {
                findReusableMeSyncTransfer(includeUser, includeProtectedDb, includeIdentity)
                    ?.takeIf { hasMinimumTransferTtlRemaining(it, ME_SYNC_V3_MIN_TRANSFER_REMAINING_MS) }
            }
            val transfer = active ?: buildMeSyncExport(
                includeUser = includeUser,
                includeProtectedDb = includeProtectedDb,
                includeIdentity = includeIdentity,
                ttlMs = ME_SYNC_V3_TRANSFER_TTL_MS
            ).also {
                meSyncTransfers[it.id] = it
            }
            jsonResponse(buildMeSyncPrepareResponse(transfer, reused = active != null))
        } catch (ex: Exception) {
            Log.e(TAG, "me.sync export failed", ex)
            jsonError(Response.Status.INTERNAL_ERROR, "me_sync_export_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleMeSyncV3TicketCreate(payload: JSONObject): Response {
        return try {
            cleanupExpiredMeSyncTransfers()
            cleanupExpiredMeSyncV3Tickets()
            cleanupExpiredMeMeState()
            val modeRaw = payload.optString("mode", "").trim().lowercase(Locale.US)
            val migrationMode = payload.optBoolean("migration", false) || modeRaw == "migration"
            val includeUser = payload.optBoolean("include_user", true)
            val includeProtectedDb = payload.optBoolean("include_protected_db", true)
            val includeIdentity = payload.optBoolean("include_identity", migrationMode)
            val forceRefresh = payload.optBoolean("force_refresh", false)
            val peerDeviceId = payload.optString("peer_device_id", "").trim()
            val transportHint = payload.optString("transport", "").trim()
            val sourceName = payload.optString("source_name", "").trim()
                .ifBlank { Build.MODEL?.trim().orEmpty() }
                .ifBlank { "Android device" }
            val active = if (forceRefresh) {
                null
            } else {
                findReusableMeSyncTransfer(includeUser, includeProtectedDb, includeIdentity)
                    ?.takeIf { hasMinimumTransferTtlRemaining(it, ME_SYNC_V3_TICKET_TTL_MS) }
            }
            val transfer = active ?: buildMeSyncExport(
                includeUser = includeUser,
                includeProtectedDb = includeProtectedDb,
                includeIdentity = includeIdentity,
                ttlMs = ME_SYNC_V3_TRANSFER_TTL_MS
            ).also {
                meSyncTransfers[it.id] = it
            }
            val ticketId = "ms3_" + System.currentTimeMillis() + "_" + Random.nextInt(1000, 9999)
            val expiresAt = System.currentTimeMillis() + ME_SYNC_V3_TICKET_TTL_MS
            val sessionNonce = randomToken(20)
            val pairCode = String.format(Locale.US, "%06d", Random.nextInt(0, 1_000_000))
            val fallbackUrl = if (transfer.downloadUrlLan.isNotBlank()) transfer.downloadUrlLan else transfer.downloadUrlLocal
            val ticketPayload = JSONObject()
                .put("v", 3)
                .put("tid", ticketId)
                .put("t", transfer.id)
                .put("n", sessionNonce)
            val hostIp = ((network.wifiStatus()["ip_address"] as? String) ?: "").trim()
            if (hostIp.isNotBlank() && meSyncLanServerStarted) {
                ticketPayload.put("h", hostIp)
                ticketPayload.put("tk", transfer.token)
            }
            val encodedPayload = Base64.encodeToString(
                ticketPayload.toString().toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val ticketUri = ME_SYNC_V3_URI_PREFIX + encodedPayload
            val qrDataUrl = makeQrDataUrl(ticketUri)
            val ticket = MeSyncV3Ticket(
                id = ticketId,
                transferId = transfer.id,
                createdAt = System.currentTimeMillis(),
                expiresAt = expiresAt,
                sessionNonce = sessionNonce,
                pairCode = pairCode,
                sourceName = sourceName,
                ticketUri = ticketUri,
                qrDataUrl = qrDataUrl
            )
            meSyncV3Tickets[ticket.id] = ticket
            meSyncNearbyTransport.publishTicket(
                ticketId = ticket.id,
                transferId = transfer.id,
                sessionNonce = ticket.sessionNonce,
                expiresAt = ticket.expiresAt
            )
            val meMeOfferDelivery = if (peerDeviceId.isNotBlank()) {
                val send = sendMeMeEncryptedMessage(
                    peerDeviceId = peerDeviceId,
                    type = "me_sync.ticket.offer",
                    payloadValue = JSONObject()
                        .put("ticket_id", ticket.id)
                        .put("ticket_uri", ticket.ticketUri)
                        .put("pair_code", ticket.pairCode)
                        .put("expires_at", ticket.expiresAt)
                        .put("source_name", ticket.sourceName)
                        .put("transfer_id", transfer.id),
                    transportHint = transportHint,
                    timeoutMs = 12_000L
                )
                formatMeMeDelivery(
                    peerDeviceId = peerDeviceId,
                    type = "me_sync.ticket.offer",
                    send = send
                )
            } else null
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("ticket_id", ticket.id)
                    .put("transfer_id", transfer.id)
                    .put("expires_at", ticket.expiresAt)
                    .put("pair_code", ticket.pairCode)
                    .put("source_name", ticket.sourceName)
                    .put("transport", "nearby_stream")
                    .put("ticket_uri", ticket.ticketUri)
                    .put("qr_data_url", ticket.qrDataUrl)
                    .put("fallback_me_sync_uri", transfer.meSyncUri)
                    .put("fallback_download_url", fallbackUrl)
                    .put("me_me_offer_delivery", meMeOfferDelivery ?: JSONObject.NULL)
            )
        } catch (ex: Exception) {
            Log.e(TAG, "me.sync v3 ticket create failed", ex)
            jsonError(Response.Status.INTERNAL_ERROR, "me_sync_v3_ticket_create_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleMeSyncV3TicketStatus(session: IHTTPSession): Response {
        cleanupExpiredMeSyncV3Tickets()
        val ticketId = firstParam(session, "ticket_id")
        if (ticketId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "ticket_id_required")
        val ticket = meSyncV3Tickets[ticketId] ?: return jsonError(Response.Status.NOT_FOUND, "ticket_not_found")
        val transfer = meSyncTransfers[ticket.transferId]
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("ticket", JSONObject()
                    .put("id", ticket.id)
                    .put("transfer_id", ticket.transferId)
                    .put("created_at", ticket.createdAt)
                    .put("expires_at", ticket.expiresAt)
                    .put("pair_code", ticket.pairCode)
                    .put("source_name", ticket.sourceName)
                    .put("ticket_uri", ticket.ticketUri)
                    .put("qr_data_url", ticket.qrDataUrl))
                .put("transfer", if (transfer != null) JSONObject()
                    .put("id", transfer.id)
                    .put("expires_at", transfer.expiresAt)
                    .put("download_count", transfer.downloadCount)
                    .put("transmitting", transfer.transmitting)
                    .put("bytes_sent", transfer.bytesSent)
                    .put("transfer_started_at", if (transfer.transferStartedAt > 0L) transfer.transferStartedAt else JSONObject.NULL)
                    .put("transfer_completed_at", if (transfer.transferCompletedAt > 0L) transfer.transferCompletedAt else JSONObject.NULL)
                    .put("me_sync_uri", transfer.meSyncUri)
                    .put("download_url_lan", if (transfer.downloadUrlLan.isNotBlank()) transfer.downloadUrlLan else JSONObject.NULL)
                    .put("download_url_local", transfer.downloadUrlLocal) else JSONObject.NULL)
        )
    }

    private fun handleMeSyncV3TicketCancel(payload: JSONObject): Response {
        cleanupExpiredMeSyncV3Tickets()
        val ticketId = payload.optString("ticket_id", "").trim()
        if (ticketId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "ticket_id_required")
        val removed = meSyncV3Tickets.remove(ticketId)
        if (removed != null) {
            meSyncNearbyTransport.unpublishTicket(ticketId)
        }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("cancelled", removed != null)
                .put("ticket_id", ticketId)
        )
    }

    private fun handleMeSyncV3ImportApply(payload: JSONObject): Response {
        val merged = JSONObject(payload.toString())
        val ticketUri = payload.optString("ticket_uri", "").trim()
            .ifBlank { payload.optString("payload", "").trim() }
        if (ticketUri.isNotBlank() && !merged.has("payload")) {
            merged.put("payload", ticketUri)
        }
        val parsed = parseMeSyncV3Ticket(ticketUri)
        if (parsed != null) {
            val timeoutMs = payload.optLong("nearby_timeout_ms", 120_000L).coerceIn(15_000L, 600_000L)
            val maxBytes = payload.optLong("nearby_max_bytes", ME_SYNC_IMPORT_MAX_BYTES_DEFAULT)
                .coerceIn(ME_SYNC_IMPORT_MAX_BYTES_MIN, ME_SYNC_IMPORT_MAX_BYTES_LIMIT)
            val wipeExisting = payload.optBoolean("wipe_existing", true)
            val allowFallback = payload.optBoolean("allow_fallback", true)
            val tmpRoot = File(context.cacheDir, "me_sync")
            tmpRoot.mkdirs()
            val inFile = File(tmpRoot, "import_nearby_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}.zip")
            try {
                updateMeSyncImportProgress(
                    state = "running",
                    phase = "nearby_receiving",
                    message = "Receiving data from nearby device...",
                    source = "nearby_v3"
                )
                val recv = meSyncNearbyTransport.receiveToFile(
                    ticketId = parsed.ticketId,
                    transferId = parsed.transferId,
                    sessionNonce = parsed.sessionNonce,
                    destFile = inFile,
                    timeoutMs = timeoutMs,
                    maxBytes = maxBytes
                )
                updateMeSyncImportProgress(
                    state = "running",
                    phase = "applying",
                    message = "Applying imported data...",
                    source = "nearby_v3",
                    bytesDownloaded = recv.bytesReceived
                )
                val result = importMeSyncPackage(inFile, wipeExisting = wipeExisting)
                result.put("transport", "nearby_stream")
                result.put(
                    "nearby",
                    JSONObject()
                        .put("ticket_id", parsed.ticketId)
                        .put("endpoint_id", recv.endpointId)
                        .put("bytes_received", recv.bytesReceived)
                )
                updateMeSyncImportProgress(
                    state = "completed",
                    phase = "completed",
                    message = "Import completed.",
                    source = "nearby_v3",
                    bytesDownloaded = recv.bytesReceived
                )
                return jsonResponse(result)
            } catch (ex: Exception) {
                Log.w(TAG, "me.sync v3 nearby import failed, fallback=${allowFallback}", ex)
                updateMeSyncImportProgress(
                    state = "failed",
                    phase = "nearby_failed",
                    message = "Nearby import failed.",
                    source = "nearby_v3",
                    detail = ex.message ?: ""
                )
                runCatching { inFile.delete() }
                if (!allowFallback) {
                    return jsonError(
                        Response.Status.INTERNAL_ERROR,
                        "me_sync_v3_nearby_failed",
                        JSONObject().put("detail", ex.message ?: "")
                    )
                }
                val wifiConnected = ((network.wifiStatus()["connected"] as? Boolean) == true)
                if (!wifiConnected) {
                    val detail = "Nearby import failed and LAN fallback is unavailable because Wi-Fi is disconnected."
                    updateMeSyncImportProgress(
                        state = "failed",
                        phase = "nearby_failed",
                        message = "Nearby import failed. Connect Wi-Fi to use LAN fallback.",
                        source = "nearby_v3",
                        detail = detail
                    )
                    return jsonError(
                        Response.Status.SERVICE_UNAVAILABLE,
                        "me_sync_v3_nearby_failed_wifi_required",
                        JSONObject()
                            .put("detail", detail)
                            .put("nearby_error", ex.message ?: "")
                            .put("fallback", "skipped_wifi_disconnected")
                    )
                }
            }
        }
        if (!merged.has("max_bytes")) {
            merged.put("max_bytes", payload.optLong("nearby_max_bytes", ME_SYNC_IMPORT_MAX_BYTES_DEFAULT))
        }
        return handleMeSyncImport(merged)
    }

    private fun openMeSyncTransferStreamForNearby(ticketId: String, transferId: String): InputStream? {
        cleanupExpiredMeSyncTransfers()
        cleanupExpiredMeSyncV3Tickets()
        val ticket = meSyncV3Tickets[ticketId] ?: run {
            Log.w(TAG, "me.sync nearby stream unavailable: ticket_not_found (ticket_id=$ticketId transfer_id=$transferId)")
            return null
        }
        if (ticket.transferId != transferId) {
            Log.w(
                TAG,
                "me.sync nearby stream unavailable: ticket_transfer_mismatch " +
                    "(ticket_id=$ticketId transfer_id=$transferId expected=${ticket.transferId})"
            )
            return null
        }
        val tr = meSyncTransfers[transferId] ?: run {
            Log.w(TAG, "me.sync nearby stream unavailable: transfer_not_found (ticket_id=$ticketId transfer_id=$transferId)")
            return null
        }
        val now = System.currentTimeMillis()
        if (tr.expiresAt > 0L && now > tr.expiresAt) {
            Log.w(
                TAG,
                "me.sync nearby stream unavailable: transfer_expired " +
                    "(ticket_id=$ticketId transfer_id=$transferId expires_at=${tr.expiresAt} now=$now)"
            )
            meSyncTransfers.remove(transferId)
            releaseMeSyncTransfer(tr)
            return null
        }
        markMeSyncTransferStart(tr)
        val stream = streamMeSyncArchive(
            includeUser = tr.includeUser,
            includeProtectedDb = tr.includeProtectedDb,
            includeIdentity = tr.includeIdentity
        )
        return withMeSyncTransferProgress(stream, tr)
    }

    private fun parseMeSyncV3Ticket(raw: String): MeSyncV3ParsedTicket? {
        val txt = raw.trim()
        if (!txt.startsWith(ME_SYNC_V3_URI_PREFIX, ignoreCase = true)) return null
        val obj = parseMeSyncPayloadObject(txt) ?: return null
        val ticketId = obj.optString("tid", "").trim()
        val transferId = obj.optString("t", "").trim()
            .ifBlank { obj.optString("transfer_id", "").trim() }
        val sessionNonce = obj.optString("n", "").trim()
            .ifBlank { obj.optString("session_nonce", "").trim() }
        if (ticketId.isBlank() || transferId.isBlank() || sessionNonce.isBlank()) return null
        return MeSyncV3ParsedTicket(ticketId = ticketId, transferId = transferId, sessionNonce = sessionNonce)
    }

    private fun shouldRequestPermissionForTempMeSyncSshd(): Boolean {
        return false
    }

    private fun handleMeSyncDownload(session: IHTTPSession): Response {
        cleanupExpiredMeSyncTransfers()
        val id = firstParam(session, "id")
        val token = firstParam(session, "token")
        if (id.isBlank() || token.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "id_and_token_required")
        val tr = meSyncTransfers[id] ?: return jsonError(Response.Status.NOT_FOUND, "transfer_not_found")
        if (tr.token != token) return jsonError(Response.Status.FORBIDDEN, "invalid_token")
        val now = System.currentTimeMillis()
        if (tr.expiresAt > 0L && now > tr.expiresAt) {
            meSyncTransfers.remove(id)
            releaseMeSyncTransfer(tr)
            return jsonError(Response.Status.GONE, "transfer_expired")
        }
        synchronized(tr) {
            if (tr.claimedOnce) {
                return jsonError(Response.Status.GONE, "transfer_already_used")
            }
            // One-shot export: first importer claim expires QR immediately.
            tr.claimedOnce = true
            tr.expiresAt = System.currentTimeMillis()
        }
        if (tr.streamingHttp) {
            markMeSyncTransferStart(tr)
            val stream = withMeSyncTransferProgress(
                streamMeSyncArchive(
                includeUser = tr.includeUser,
                includeProtectedDb = tr.includeProtectedDb,
                includeIdentity = tr.includeIdentity
                ),
                tr
            )
            val response = newChunkedResponse(Response.Status.OK, "application/zip", stream)
            response.addHeader("Cache-Control", "no-store")
            response.addHeader("Content-Disposition", "attachment; filename=\"methings-me-sync-${tr.id}.zip\"")
            return response
        }
        val trFile = tr.file
        if (trFile == null || !trFile.exists() || !trFile.isFile) {
            meSyncTransfers.remove(id)
            return jsonError(Response.Status.NOT_FOUND, "package_not_found")
        }
        markMeSyncTransferStart(tr)
        val stream: InputStream = withMeSyncTransferProgress(FileInputStream(trFile), tr)
        val response = newChunkedResponse(Response.Status.OK, "application/zip", stream)
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Content-Disposition", "attachment; filename=\"methings-me-sync-${tr.id}.zip\"")
        if (!tr.sha256.isNullOrBlank()) response.addHeader("X-Me-Sync-Sha256", tr.sha256)
        return response
    }

    private fun handleMeSyncImport(payload: JSONObject): Response {
        return try {
            val rawPayload = payload.optString("payload", "").trim()
            val rawUrl = payload.optString("url", "").trim()
                .ifBlank { payload.optString("download_url", "").trim() }
            val wipeExisting = payload.optBoolean("wipe_existing", true)
            val sourceHint = if (rawPayload.startsWith(ME_SYNC_V3_URI_PREFIX, ignoreCase = true)) "nearby_v3_fallback" else "direct"
            updateMeSyncImportProgress(
                state = "running",
                phase = "preparing",
                message = "Preparing import...",
                source = sourceHint
            )
            val source = parseMeSyncImportSource(rawPayload, rawUrl)
            if (!source.hasAnySource()) {
                return jsonError(Response.Status.BAD_REQUEST, "url_or_payload_required")
            }
            val tmpRoot = File(context.cacheDir, "me_sync")
            tmpRoot.mkdirs()
            val inFile = File(tmpRoot, "import_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}.zip")
            val maxBytes = payload.optLong("max_bytes", ME_SYNC_IMPORT_MAX_BYTES_DEFAULT)
                .coerceIn(ME_SYNC_IMPORT_MAX_BYTES_MIN, ME_SYNC_IMPORT_MAX_BYTES_LIMIT)
            var downloadError: Exception? = null
            if (!inFile.exists() || inFile.length() == 0L) {
                val httpUrl = source.httpUrl
                if (httpUrl.isBlank()) {
                    throw downloadError ?: IllegalStateException("no_download_source")
                }
                updateMeSyncImportProgress(
                    state = "running",
                    phase = "downloading",
                    message = "Downloading package...",
                    source = sourceHint
                )
                downloadMeSyncPackage(httpUrl, inFile, maxBytes = maxBytes) { total ->
                    updateMeSyncImportProgress(
                        state = "running",
                        phase = "downloading",
                        message = "Downloading package...",
                        source = sourceHint,
                        bytesDownloaded = total
                    )
                }
            }
            updateMeSyncImportProgress(
                state = "running",
                phase = "applying",
                message = "Applying imported data...",
                source = sourceHint,
                bytesDownloaded = inFile.length()
            )
            val result = importMeSyncPackage(inFile, wipeExisting = wipeExisting)
            updateMeSyncImportProgress(
                state = "completed",
                phase = "completed",
                message = "Import completed.",
                source = sourceHint,
                bytesDownloaded = inFile.length()
            )
            jsonResponse(result)
        } catch (ex: Exception) {
            Log.e(TAG, "me.sync import failed", ex)
            updateMeSyncImportProgress(
                state = "failed",
                phase = "failed",
                message = "Import failed.",
                detail = ex.message ?: ""
            )
            jsonError(Response.Status.INTERNAL_ERROR, "me_sync_import_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleMeSyncWipeAll(payload: JSONObject): Response {
        return try {
            val restartApp = payload.optBoolean("restart_app", false)
            val preserveSession = payload.optBoolean("preserve_session", true)
            val preserveSessionId = if (preserveSession) {
                payload.optString("session_id", "").trim()
                    .ifBlank { payload.optString("identity", "").trim() }
            } else {
                ""
            }
            wipeAllLocalState(preserveSessionId = preserveSessionId)
            runCatching {
                context.sendBroadcast(
                    Intent(ACTION_UI_CHAT_CACHE_CLEAR)
                        .apply {
                            setPackage(context.packageName)
                            putExtra(EXTRA_CHAT_PRESERVE_SESSION_ID, preserveSessionId)
                        }
                )
            }
            val restarted = if (restartApp) restartAppIfPossible() else false
            var restartedWorker = false
            if (!restarted) {
                restartedWorker = runCatching { runtimeManager.restartSoft() }.getOrDefault(false)
            }
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("wiped", true)
                    .put("restart_requested", restartApp)
                    .put("restarted_app", restarted)
                    .put("restarted_worker", restartedWorker)
                    .put("preserved_session_id", if (preserveSessionId.isNotBlank()) preserveSessionId else JSONObject.NULL)
            )
        } catch (ex: Exception) {
            Log.e(TAG, "me.sync wipe_all failed", ex)
            jsonError(Response.Status.INTERNAL_ERROR, "me_sync_wipe_all_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun parseMeSyncImportSource(rawPayload: String, rawUrl: String): MeSyncImportSource {
        val fromPayload = parseMeSyncImportSourceSingle(rawPayload)
        if (fromPayload.hasAnySource()) return fromPayload
        return parseMeSyncImportSourceSingle(rawUrl)
    }

    private fun parseMeSyncImportSourceSingle(raw: String): MeSyncImportSource {
        val txt = raw.trim()
        if (txt.isBlank()) return MeSyncImportSource()
        if (txt.startsWith("http://") || txt.startsWith("https://")) {
            return MeSyncImportSource(httpUrl = txt)
        }
        val obj = parseMeSyncPayloadObject(txt) ?: return MeSyncImportSource()
        val httpUrl = obj.optString("u", "").trim()
            .ifBlank { obj.optString("url", "").trim() }
            .ifBlank { obj.optString("download_url", "").trim() }
            .ifBlank { obj.optString("http_url", "").trim() }
            .ifBlank {
                // Reconstruct from compact v3 fields: h (host), tk (token), t (transfer id)
                val h = obj.optString("h", "").trim()
                val tk = obj.optString("tk", "").trim()
                val t = obj.optString("t", "").trim()
                if (h.isNotBlank() && tk.isNotBlank() && t.isNotBlank())
                    "http://$h:$ME_SYNC_LAN_PORT/me/sync/download?id=${URLEncoder.encode(t, "UTF-8")}&token=${URLEncoder.encode(tk, "UTF-8")}"
                else ""
            }
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: ""
        return MeSyncImportSource(
            httpUrl = httpUrl
        )
    }

    private fun parseMeSyncPayloadObject(raw: String): JSONObject? {
        val txt = raw.trim()
        if (txt.isBlank()) return null
        if (txt.startsWith(ME_SYNC_V3_URI_PREFIX, ignoreCase = true)) {
            val b64 = txt.substring(ME_SYNC_V3_URI_PREFIX.length).trim()
            if (b64.isBlank()) return null
            return try {
                val decoded = Base64.decode(normalizeBase64UrlNoPadding(b64), Base64.URL_SAFE or Base64.NO_WRAP)
                JSONObject(String(decoded, Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }
        if (txt.startsWith(ME_SYNC_URI_PREFIX, ignoreCase = true)) {
            val b64 = txt.substring(ME_SYNC_URI_PREFIX.length).trim()
            if (b64.isBlank()) return null
            return try {
                val decoded = Base64.decode(normalizeBase64UrlNoPadding(b64), Base64.URL_SAFE or Base64.NO_WRAP)
                JSONObject(String(decoded, Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }
        return try {
            JSONObject(txt)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeBase64UrlNoPadding(s: String): String {
        val t = s.trim().replace('\n', ' ').replace("\r", "").replace(" ", "")
        val mod = t.length % 4
        return if (mod == 0) t else t + "=".repeat(4 - mod)
    }

    private fun buildMeSyncExport(
        includeUser: Boolean,
        includeProtectedDb: Boolean,
        includeIdentity: Boolean,
        ttlMs: Long = ME_SYNC_QR_TTL_MS
    ): MeSyncTransfer {
        val id = "ms_" + System.currentTimeMillis() + "_" + Random.nextInt(1000, 9999)
        val token = randomToken(24)
        val expiresAt = System.currentTimeMillis() + ttlMs.coerceAtLeast(5_000L)
        val hostIp = ((network.wifiStatus()["ip_address"] as? String) ?: "").trim()
        val query = "id=" + URLEncoder.encode(id, "UTF-8") +
            "&token=" + URLEncoder.encode(token, "UTF-8")
        val localUrl = "http://127.0.0.1:$PORT/me/sync/download?$query"
        val lanUrl = if (hostIp.isNotBlank() && meSyncLanServerStarted) {
            "http://$hostIp:$ME_SYNC_LAN_PORT/me/sync/download?$query"
        } else ""
        val transport = "http"
        val exportPayload = JSONObject()
            .put("type", "me.sync")
            .put("version", 2)
            .put("id", id)
            .put("token", token)
            .put("transport", transport)
            .put("url", if (lanUrl.isNotBlank()) lanUrl else localUrl)
            .put("expires_at", expiresAt)
            .put("http_url", if (lanUrl.isNotBlank()) lanUrl else localUrl)
        val meSyncUriPayload = JSONObject()
            .put("v", 1)
            .put("u", if (lanUrl.isNotBlank()) lanUrl else localUrl)
            .put("e", expiresAt)
        val payloadJson = exportPayload.toString()
        val encodedPayload = Base64.encodeToString(
            meSyncUriPayload.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val meSyncUri = ME_SYNC_URI_PREFIX + encodedPayload
        val qrDataUrl = makeQrDataUrl(meSyncUri)
        return MeSyncTransfer(
            id = id,
            token = token,
            file = null,
            createdAt = System.currentTimeMillis(),
            expiresAt = expiresAt,
            sha256 = null,
            includeUser = includeUser,
            includeProtectedDb = includeProtectedDb,
            includeIdentity = includeIdentity,
            downloadUrlLocal = localUrl,
            downloadUrlLan = lanUrl,
            payloadJson = payloadJson,
            meSyncUri = meSyncUri,
            qrDataUrl = qrDataUrl,
            transport = transport,
            streamingHttp = true
        )
    }

    private fun findReusableMeSyncTransfer(includeUser: Boolean, includeProtectedDb: Boolean, includeIdentity: Boolean): MeSyncTransfer? {
        val now = System.currentTimeMillis()
        return meSyncTransfers.values
            .asSequence()
            .filter {
                it.includeUser == includeUser &&
                    it.includeProtectedDb == includeProtectedDb &&
                    it.includeIdentity == includeIdentity
            }
            .filter { it.expiresAt <= 0L || now < it.expiresAt }
            .sortedByDescending { it.createdAt }
            .firstOrNull()
    }

    private fun hasMinimumTransferTtlRemaining(transfer: MeSyncTransfer, minRemainingMs: Long): Boolean {
        val expiresAt = transfer.expiresAt
        if (expiresAt <= 0L) return true
        val remaining = expiresAt - System.currentTimeMillis()
        return remaining >= minRemainingMs.coerceAtLeast(0L)
    }

    private fun buildMeSyncPrepareResponse(transfer: MeSyncTransfer, reused: Boolean): JSONObject {
        return JSONObject()
            .put("status", "ok")
            .put("transfer_id", transfer.id)
            .put("reused", reused)
            .put("expires_at", transfer.expiresAt)
            .put("size", if (transfer.file != null) transfer.file.length() else JSONObject.NULL)
            .put("sha256", transfer.sha256 ?: JSONObject.NULL)
            .put("mode", if (transfer.includeIdentity) "migration" else "export")
            .put("include_identity", transfer.includeIdentity)
            .put("transport", transfer.transport)
            .put("download_url_local", transfer.downloadUrlLocal)
            .put("download_url_lan", if (transfer.downloadUrlLan.isNotBlank()) transfer.downloadUrlLan else JSONObject.NULL)
            .put("payload_json", transfer.payloadJson)
            .put("payload_b64", transfer.meSyncUri.removePrefix(ME_SYNC_URI_PREFIX))
            .put("me_sync_uri", transfer.meSyncUri)
            .put("qr_data_url", transfer.qrDataUrl)
            .put("note", "Share the QR/URI with target device. URI format: me.things:me.sync:<base64url>.")
    }

    private fun makeQrDataUrl(text: String): String {
        val size = 640
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$b64"
    }

    private fun exportRoomState(): JSONObject {
        val root = JSONObject()
        val creds = org.json.JSONArray()
        credentialStore.list().forEach { c ->
            creds.put(
                JSONObject()
                    .put("name", c.name)
                    .put("value", c.value)
                    .put("updated_at", c.updatedAt)
            )
        }
        root.put("credentials", creds)

        root.put(
            "permission_prefs",
            JSONObject()
                .put("remember_approvals", permissionPrefs.rememberApprovals())
                .put("dangerously_skip_permissions", permissionPrefs.dangerouslySkipPermissions())
        )
        root.put("chat_state", exportMeSyncChatState())
        root.put("shared_prefs", exportMeSyncSharedPrefs())
        root.put("install_identity", installIdentity.get())
        return root
    }

    private fun importMeSyncPackage(pkgFile: File, wipeExisting: Boolean): JSONObject {
        if (!pkgFile.exists() || !pkgFile.isFile) throw IllegalStateException("missing package")
        val tmpDir = File(context.cacheDir, "me_sync_import_" + System.currentTimeMillis() + "_" + Random.nextInt(1000, 9999))
        tmpDir.mkdirs()
        val rollbackDir = File(context.cacheDir, "me_sync_rollback_" + System.currentTimeMillis() + "_" + Random.nextInt(1000, 9999))
        rollbackDir.mkdirs()
        try {
            var copiedBytes = 0L
            var totalBytes = pkgFile.length().coerceAtLeast(1L)
            var lastProgressAt = 0L
            fun reportProgress(phase: String, message: String, force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && (now - lastProgressAt) < 250L) return
                lastProgressAt = now
                updateMeSyncImportProgress(
                    state = "running",
                    phase = phase,
                    message = message,
                    bytesCopied = copiedBytes,
                    totalBytes = totalBytes
                )
            }

            reportProgress("extracting", "Extracting package...", force = true)
            unzipInto(pkgFile, tmpDir) { n ->
                copiedBytes += n.coerceAtLeast(0L)
                reportProgress("extracting", "Extracting package...")
            }
            reportProgress("extracting", "Extracting package...", force = true)
            val roomStateFile = File(tmpDir, "room/state.json")
            val importedState = if (roomStateFile.exists()) {
                JSONObject(roomStateFile.readText(Charsets.UTF_8))
            } else {
                null
            }
            val importedActiveSessionId = if (importedState != null) readImportedActiveSessionId(importedState) else ""
            val importedUser = File(tmpDir, "user")
            val importedProtectedDb = File(tmpDir, "protected/app.db")
            val userRoot = File(context.filesDir, "user")
            val protectedDir = File(context.filesDir, "protected")
            val targetDb = File(protectedDir, "app.db")
            val backupUser = File(rollbackDir, "user_prev")
            val backupDb = File(rollbackDir, "protected_app.db_prev")
            val importedUserBytes = if (importedUser.exists() && importedUser.isDirectory) directoryFileBytes(importedUser) else 0L
            val importedDbBytes = if (importedProtectedDb.exists() && importedProtectedDb.isFile) importedProtectedDb.length() else 0L
            totalBytes = (totalBytes + importedUserBytes + importedDbBytes).coerceAtLeast(totalBytes)

            var swappedUser = false
            var swappedDb = false
            try {
                if (wipeExisting) {
                    if (userRoot.exists()) {
                        movePath(userRoot, backupUser)
                    }
                    if (importedUser.exists() && importedUser.isDirectory) {
                        movePath(importedUser, userRoot)
                    } else {
                        userRoot.mkdirs()
                    }
                    swappedUser = true

                    runCatching { runtimeManager.requestShutdown() }
                    runCatching { Thread.sleep(250) }
                    protectedDir.mkdirs()
                    if (targetDb.exists() && targetDb.isFile) {
                        movePath(targetDb, backupDb)
                    }
                    if (importedProtectedDb.exists() && importedProtectedDb.isFile) {
                        copyFile(importedProtectedDb, targetDb) { n ->
                            copiedBytes += n.coerceAtLeast(0L)
                            reportProgress("applying", "Applying imported data...")
                        }
                    } else {
                        runCatching { targetDb.delete() }
                    }
                    swappedDb = true

                    runCatching {
                        context.sendBroadcast(
                            Intent(ACTION_UI_CHAT_CACHE_CLEAR)
                                .apply {
                                    setPackage(context.packageName)
                                    putExtra(EXTRA_CHAT_PRESERVE_SESSION_ID, "")
                                }
                        )
                    }
                }

                if (!wipeExisting && importedUser.exists() && importedUser.isDirectory) {
                    userRoot.mkdirs()
                    copyDirectoryOverwrite(importedUser, userRoot) { n ->
                        copiedBytes += n.coerceAtLeast(0L)
                        reportProgress("applying", "Applying imported data...")
                    }
                }

                if (!wipeExisting && importedProtectedDb.exists() && importedProtectedDb.isFile) {
                    runtimeManager.requestShutdown()
                    Thread.sleep(250)
                    protectedDir.mkdirs()
                    copyFile(importedProtectedDb, targetDb) { n ->
                        copiedBytes += n.coerceAtLeast(0L)
                        reportProgress("applying", "Applying imported data...")
                    }
                    runCatching { clearPendingPermissionsInProtectedDb(targetDb) }
                } else if (wipeExisting && importedProtectedDb.exists() && importedProtectedDb.isFile) {
                    runCatching { clearPendingPermissionsInProtectedDb(targetDb) }
                }

                if (importedState != null) {
                    if (wipeExisting && !importedProtectedDb.exists()) {
                        clearCredentialStore()
                    }
                    importRoomState(importedState)
                }
                reportProgress("applying", "Applying imported data...", force = true)
            } catch (ex: Exception) {
                if (wipeExisting) {
                    restorePathFromBackup(userRoot, backupUser, swappedUser)
                    restorePathFromBackup(targetDb, backupDb, swappedDb)
                }
                throw ex
            }

            reportProgress("restarting_runtime", "Restarting local runtime...", force = true)
            runCatching { runtimeManager.restartSoft() }
            runCatching {
                context.sendBroadcast(
                    Intent(ACTION_UI_CHAT_CACHE_CLEAR)
                        .apply {
                            setPackage(context.packageName)
                            putExtra(EXTRA_CHAT_PRESERVE_SESSION_ID, importedActiveSessionId)
                        }
                )
            }
            runCatching {
                context.sendBroadcast(
                    Intent(ACTION_UI_RELOAD).apply {
                        setPackage(context.packageName)
                        putExtra(EXTRA_UI_RELOAD_TOAST, "me.sync import applied")
                    }
                )
            }

            return JSONObject()
                .put("status", "ok")
                .put("imported", true)
                .put("wipe_existing", wipeExisting)
                .put("restarted_worker", true)
                .put("active_session_id", if (importedActiveSessionId.isNotBlank()) importedActiveSessionId else JSONObject.NULL)
        } finally {
            runCatching { pkgFile.delete() }
            runCatching { deleteRecursively(tmpDir) }
            runCatching { deleteRecursively(rollbackDir) }
        }
    }

    private fun exportMeSyncChatState(): JSONObject {
        val activeSessionId = readBrainActiveSessionId()
        return JSONObject().put(
            "active_session_id",
            if (activeSessionId.isNotBlank()) activeSessionId else JSONObject.NULL
        )
    }

    private fun readImportedActiveSessionId(state: JSONObject): String {
        return state.optJSONObject("chat_state")
            ?.optString("active_session_id", "")
            ?.trim()
            ?.ifBlank { "" }
            ?: ""
    }

    private fun readBrainActiveSessionId(): String {
        return try {
            val conn = (java.net.URL("http://127.0.0.1:8776/brain/status").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1200
                readTimeout = 2000
            }
            val body = if (conn.responseCode in 200..299) {
                conn.inputStream?.bufferedReader()?.use { it.readText() } ?: "{}"
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}"
            }
            JSONObject(body).optString("current_session_id", "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun importRoomState(state: JSONObject) {
        val creds = state.optJSONArray("credentials")
        if (creds != null) {
            for (i in 0 until creds.length()) {
                val item = creds.optJSONObject(i) ?: continue
                val name = item.optString("name", "").trim()
                val value = item.optString("value", "")
                if (name.isBlank()) continue
                credentialStore.set(name, value)
            }
        }
        val prefs = state.optJSONObject("permission_prefs")
        if (prefs != null) {
            permissionPrefs.setRememberApprovals(prefs.optBoolean("remember_approvals", permissionPrefs.rememberApprovals()))
            permissionPrefs.setDangerouslySkipPermissions(
                prefs.optBoolean("dangerously_skip_permissions", permissionPrefs.dangerouslySkipPermissions())
            )
        }
        val sharedPrefs = state.optJSONObject("shared_prefs")
        if (sharedPrefs != null) {
            importMeSyncSharedPrefs(sharedPrefs)
        }
    }

    private fun exportMeSyncSharedPrefs(): JSONObject {
        val out = JSONObject()
        for (prefName in ME_SYNC_SHARED_PREFS_EXPORT) {
            val sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            val prefObj = JSONObject()
            for ((key, value) in sp.all) {
                val encoded = encodeSharedPrefValue(value) ?: continue
                prefObj.put(key, encoded)
            }
            out.put(prefName, prefObj)
        }
        return out
    }

    private fun importMeSyncSharedPrefs(root: JSONObject) {
        for (prefName in ME_SYNC_SHARED_PREFS_EXPORT) {
            val prefObj = root.optJSONObject(prefName) ?: continue
            val sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            val ed = sp.edit()
            ed.clear()
            val keys = prefObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val encoded = prefObj.optJSONObject(key) ?: continue
                val t = encoded.optString("t", "").trim()
                when (t) {
                    "b" -> ed.putBoolean(key, encoded.optBoolean("v", false))
                    "i" -> ed.putInt(key, encoded.optInt("v", 0))
                    "l" -> ed.putLong(key, encoded.optLong("v", 0L))
                    "f" -> ed.putFloat(key, encoded.optDouble("v", 0.0).toFloat())
                    "s" -> ed.putString(key, encoded.optString("v", ""))
                    "ss" -> {
                        val arr = encoded.optJSONArray("v")
                        if (arr == null) {
                            ed.putStringSet(key, emptySet())
                        } else {
                            val set = linkedSetOf<String>()
                            for (i in 0 until arr.length()) {
                                set.add(arr.optString(i, ""))
                            }
                            ed.putStringSet(key, set)
                        }
                    }
                }
            }
            ed.apply()
        }
    }

    private fun encodeSharedPrefValue(value: Any?): JSONObject? {
        val obj = JSONObject()
        return when (value) {
            is Boolean -> obj.put("t", "b").put("v", value)
            is Int -> obj.put("t", "i").put("v", value)
            is Long -> obj.put("t", "l").put("v", value)
            is Float -> obj.put("t", "f").put("v", value.toDouble())
            is String -> obj.put("t", "s").put("v", value)
            is Set<*> -> {
                val arr = JSONArray()
                value.forEach { arr.put((it as? String) ?: "") }
                obj.put("t", "ss").put("v", arr)
            }
            else -> null
        }
    }

    private fun wipeMeSyncLocalState(preserveSessionId: String = "") {
        runCatching { runtimeManager.requestShutdown() }
        runCatching { Thread.sleep(250) }

        val userRoot = File(context.filesDir, "user")
        runCatching { deleteRecursively(userRoot) }
        runCatching { userRoot.mkdirs() }

        runCatching { wipeProtectedDb(preserveSessionId = preserveSessionId) }

        runCatching {
            credentialStore.list().forEach { row ->
                credentialStore.delete(row.name)
            }
        }
    }

    private fun wipeAllLocalState(preserveSessionId: String = "") {
        runCatching { runtimeManager.requestShutdown() }
        runCatching { Thread.sleep(250) }

        runCatching { wipeMeSyncLocalState(preserveSessionId = preserveSessionId) }
        runCatching { permissionStore.clearAll() }
        runCatching { deviceGrantStore.clearAll() }
        runCatching {
            permissionPrefs.setRememberApprovals(true)
            permissionPrefs.setDangerouslySkipPermissions(false)
        }
        runCatching { installIdentity.reset() }

        runCatching {
            val tmpRoot = File(context.cacheDir, "me_sync")
            if (tmpRoot.exists()) deleteRecursively(tmpRoot)
        }
        runCatching {
            val stale = meSyncTransfers.values.toList()
            meSyncTransfers.clear()
            stale.forEach { tr -> releaseMeSyncTransfer(tr) }
        }
        runCatching {
            val staleTickets = meSyncV3Tickets.values.toList()
            meSyncV3Tickets.clear()
            staleTickets.forEach { t -> meSyncNearbyTransport.unpublishTicket(t.id) }
        }
    }

    private fun wipeProtectedDb(preserveSessionId: String = "") {
        val protectedDb = File(File(context.filesDir, "protected"), "app.db")
        if (!protectedDb.exists() || !protectedDb.isFile) return
        val keepSid = preserveSessionId.trim()
        if (keepSid.isBlank()) {
            runCatching { protectedDb.delete() }
            return
        }
        var db: SQLiteDatabase? = null
        var inTx = false
        try {
            db = SQLiteDatabase.openDatabase(protectedDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            db.beginTransaction()
            inTx = true
            db.execSQL("DELETE FROM permissions")
            db.execSQL("DELETE FROM credentials")
            db.execSQL("DELETE FROM services")
            db.execSQL("DELETE FROM service_credentials")
            db.execSQL("DELETE FROM audit_log")
            db.execSQL("DELETE FROM settings")
            db.execSQL("DELETE FROM chat_messages WHERE session_id <> ?", arrayOf(keepSid))
            db.setTransactionSuccessful()
        } catch (_: Exception) {
            runCatching { db?.close() }
            db = null
            runCatching { protectedDb.delete() }
            return
        } finally {
            if (inTx) runCatching { db?.endTransaction() }
            runCatching { db?.close() }
        }
    }

    private fun clearPendingPermissionsInProtectedDb(dbFile: File) {
        if (!dbFile.exists() || !dbFile.isFile) return
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            db.execSQL("DELETE FROM permissions WHERE status = 'pending'")
        } catch (_: Exception) {
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun restartAppIfPossible(): Boolean {
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            Handler(Looper.getMainLooper()).post { context.startActivity(launch) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadMeSyncPackage(url: String, destFile: File, maxBytes: Long, onProgress: ((Long) -> Unit)? = null) {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        conn.requestMethod = "GET"
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("download_failed_status_$code")
        }
        conn.inputStream.use { inp ->
            FileOutputStream(destFile).use { out ->
                copyStreamWithLimit(inp, out, maxBytes, onProgress)
            }
        }
    }

    private fun copyStreamWithLimit(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        onProgress: ((Long) -> Unit)? = null
    ) {
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n.toLong()
            if (total > maxBytes) throw IllegalStateException("package_too_large")
            output.write(buf, 0, n)
            onProgress?.invoke(total)
        }
    }

    private fun unzipInto(zipFile: File, outDir: File) {
        unzipInto(zipFile, outDir, null)
    }

    private fun unzipInto(zipFile: File, outDir: File, onBytes: ((Long) -> Unit)?) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            val buf = ByteArray(8192)
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name ?: ""
                if (name.isBlank() || name.contains("..")) {
                    zis.closeEntry()
                    continue
                }
                val out = File(outDir, name)
                val canonicalOut = out.canonicalFile
                val canonicalRoot = outDir.canonicalFile
                if (!canonicalOut.path.startsWith(canonicalRoot.path)) {
                    zis.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    canonicalOut.mkdirs()
                } else {
                    canonicalOut.parentFile?.mkdirs()
                    FileOutputStream(canonicalOut).use { fos ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            onBytes?.invoke(n.toLong())
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun copyDirectoryOverwrite(srcDir: File, dstDir: File, onBytes: ((Long) -> Unit)? = null) {
        srcDir.walkTopDown().forEach { src ->
            val rel = src.relativeTo(srcDir)
            if (rel.path.isBlank()) return@forEach
            val dst = File(dstDir, rel.path)
            if (src.isDirectory) {
                dst.mkdirs()
            } else if (src.isFile) {
                dst.parentFile?.mkdirs()
                copyFile(src, dst, onBytes)
            }
        }
    }

    private fun copyFile(src: File, dst: File, onBytes: ((Long) -> Unit)? = null) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    onBytes?.invoke(n.toLong())
                }
            }
        }
    }

    private fun movePath(src: File, dst: File) {
        if (!src.exists()) return
        dst.parentFile?.mkdirs()
        if (dst.exists()) {
            deleteRecursively(dst)
        }
        if (src.renameTo(dst)) return
        if (src.isDirectory) {
            dst.mkdirs()
            copyDirectoryOverwrite(src, dst)
            deleteRecursively(src)
            return
        }
        copyFile(src, dst)
        runCatching { src.delete() }
    }

    private fun directoryFileBytes(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                total += runCatching { f.length() }.getOrDefault(0L)
            }
        }
        return total.coerceAtLeast(0L)
    }

    private fun restorePathFromBackup(target: File, backup: File, shouldRestore: Boolean) {
        if (!shouldRestore) return
        runCatching {
            if (target.exists()) {
                deleteRecursively(target)
            }
        }
        runCatching {
            if (backup.exists()) {
                movePath(backup, target)
            }
        }
    }

    private fun clearCredentialStore() {
        runCatching {
            credentialStore.list().forEach { row ->
                credentialStore.delete(row.name)
            }
        }
    }

    private fun randomToken(bytes: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val sb = StringBuilder()
        repeat(bytes.coerceAtLeast(8)) {
            sb.append(alphabet[Random.nextInt(alphabet.length)])
        }
        return sb.toString()
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { inp ->
            val buf = ByteArray(8192)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cleanupExpiredMeSyncTransfers() {
        val now = System.currentTimeMillis()
        val toDelete = mutableListOf<MeSyncTransfer>()
        meSyncTransfers.entries.removeIf { ent ->
            val tr = ent.value
            val expired = tr.expiresAt > 0L && now > tr.expiresAt
            // Keep active transfer alive until stream completes.
            if (expired && tr.transmitting) return@removeIf false
            if (expired) toDelete.add(tr)
            expired
        }
        toDelete.forEach { releaseMeSyncTransfer(it) }
    }

    private fun cleanupExpiredMeSyncV3Tickets() {
        val now = System.currentTimeMillis()
        val removed = mutableListOf<String>()
        meSyncV3Tickets.entries.removeIf { ent ->
            val t = ent.value
            val expired = t.expiresAt > 0L && now > t.expiresAt
            if (expired) removed.add(t.id)
            expired
        }
        removed.forEach { meSyncNearbyTransport.unpublishTicket(it) }
        meSyncNearbyTransport.cleanupExpired(now)
    }

    private fun releaseMeSyncTransfer(transfer: MeSyncTransfer) {
        runCatching { transfer.file?.delete() }
    }

    private fun markMeSyncTransferStart(transfer: MeSyncTransfer) {
        synchronized(transfer) {
            transfer.downloadCount += 1
            transfer.bytesSent = 0L
            transfer.transmitting = true
            transfer.transferStartedAt = System.currentTimeMillis()
            transfer.transferCompletedAt = 0L
            transfer.activeDownloads += 1
        }
    }

    private fun withMeSyncTransferProgress(stream: InputStream, transfer: MeSyncTransfer): InputStream {
        return object : java.io.FilterInputStream(stream) {
            private var done = false

            private fun onBytes(n: Int) {
                if (n <= 0) return
                synchronized(transfer) { transfer.bytesSent += n.toLong() }
            }

            private fun onDone() {
                if (done) return
                done = true
                synchronized(transfer) {
                    transfer.activeDownloads = (transfer.activeDownloads - 1).coerceAtLeast(0)
                    if (transfer.activeDownloads == 0) {
                        transfer.transmitting = false
                        transfer.transferCompletedAt = System.currentTimeMillis()
                    }
                }
            }

            override fun read(): Int {
                val r = super.read()
                if (r >= 0) onBytes(1) else onDone()
                return r
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                if (n > 0) onBytes(n) else if (n < 0) onDone()
                return n
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    onDone()
                }
            }
        }
    }

    private fun streamMeSyncArchive(
        includeUser: Boolean,
        includeProtectedDb: Boolean,
        includeIdentity: Boolean
    ): InputStream {
        val inPipe = java.io.PipedInputStream(256 * 1024)
        val outPipe = java.io.PipedOutputStream(inPipe)
        Thread({
            try {
                ZipOutputStream(java.io.BufferedOutputStream(outPipe, 256 * 1024)).use { zos ->
                    writeMeSyncArchive(zos, includeUser, includeProtectedDb, includeIdentity)
                }
            } catch (_: Exception) {
                runCatching { outPipe.close() }
            }
        }, "me-sync-stream").start()
        return inPipe
    }

    private fun writeMeSyncArchive(
        zos: ZipOutputStream,
        includeUser: Boolean,
        includeProtectedDb: Boolean,
        includeIdentity: Boolean
    ) {
        val manifest = JSONObject()
            .put("schema", 1)
            .put("created_at", System.currentTimeMillis())
            .put("app_id", context.packageName)
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("git_sha", BuildConfig.GIT_SHA)
            .put("debug", BuildConfig.DEBUG)
            .put("include_user", includeUser)
            .put("include_protected_db", includeProtectedDb)
            .put("include_identity", includeIdentity)
        putZipBytes(zos, "manifest.json", manifest.toString(2).toByteArray(StandardCharsets.UTF_8))

        if (includeProtectedDb) {
            val dbFile = File(File(context.filesDir, "protected"), "app.db")
            if (dbFile.exists() && dbFile.isFile) {
                putZipFile(zos, "protected/app.db", dbFile)
            }
        }
        if (includeUser) {
            val userRoot = File(context.filesDir, "user")
            if (userRoot.exists() && userRoot.isDirectory) {
                zipDirectory(zos, userRoot, "user/")
            }
        }

        val roomState = exportRoomState()
        putZipBytes(zos, "room/state.json", roomState.toString(2).toByteArray(StandardCharsets.UTF_8))
    }

    private fun putZipBytes(zos: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path)
        entry.time = System.currentTimeMillis()
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun putZipFile(zos: ZipOutputStream, path: String, file: File) {
        val entry = ZipEntry(path)
        entry.time = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        zos.putNextEntry(entry)
        FileInputStream(file).use { inp -> inp.copyTo(zos, 8192) }
        zos.closeEntry()
    }

    private fun zipDirectory(zos: ZipOutputStream, root: File, prefix: String, excludeRelPath: ((String) -> Boolean)? = null) {
        val rootCanonical = root.canonicalFile
        rootCanonical.walkTopDown().forEach { f ->
            val rel = f.relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
            if (rel.isBlank()) return@forEach
            if (excludeRelPath?.invoke(rel) == true) return@forEach
            val entryPath = prefix + rel
            if (f.isDirectory) {
                val e = ZipEntry(entryPath.trimEnd('/') + "/")
                e.time = f.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                zos.putNextEntry(e)
                zos.closeEntry()
            } else if (f.isFile) {
                putZipFile(zos, entryPath, f)
            }
        }
    }

    private fun deleteRecursively(root: File) {
        if (!root.exists()) return
        if (root.isFile) {
            root.delete()
            return
        }
        root.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    private fun createTask(name: String, payload: JSONObject): AgentTask {
        val id = "t_${System.currentTimeMillis()}_${agentTasks.size}"
        val task = AgentTask(
            id = id,
            name = name,
            status = "queued",
            createdAt = System.currentTimeMillis(),
            payload = payload.toString()
        )
        agentTasks[id] = task
        return task
    }

    data class AgentTask(
        val id: String,
        val name: String,
        val status: String,
        val createdAt: Long,
        val payload: String
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("id", id)
                .put("name", name)
                .put("status", status)
                .put("created_at", createdAt)
                .put("payload", payload)
        }
    }

    private data class MeSyncTransfer(
        val id: String,
        val token: String,
        val file: File?,
        val createdAt: Long,
        @Volatile var expiresAt: Long,
        val sha256: String?,
        val includeUser: Boolean,
        val includeProtectedDb: Boolean,
        val includeIdentity: Boolean,
        val downloadUrlLocal: String,
        val downloadUrlLan: String,
        val payloadJson: String,
        val meSyncUri: String,
        val qrDataUrl: String,
        val transport: String,
        val streamingHttp: Boolean = false,
        @Volatile var downloadCount: Int = 0,
        @Volatile var transmitting: Boolean = false,
        @Volatile var bytesSent: Long = 0L,
        @Volatile var transferStartedAt: Long = 0L,
        @Volatile var transferCompletedAt: Long = 0L,
        @Volatile var activeDownloads: Int = 0,
        @Volatile var claimedOnce: Boolean = false
    )

    private data class MeSyncImportProgress(
        val state: String = "idle",
        val phase: String = "",
        val message: String = "",
        val source: String = "",
        val startedAt: Long = 0L,
        val updatedAt: Long = 0L,
        val bytesDownloaded: Long = 0L,
        val bytesCopied: Long = 0L,
        val totalBytes: Long = 0L,
        val copyStartedAt: Long = 0L,
        val detail: String = ""
    ) {
        fun toJson(now: Long, active: Boolean): JSONObject {
            val elapsedMs = if (startedAt > 0L) (now - startedAt).coerceAtLeast(0L) else 0L
            val copyElapsedMs = if (copyStartedAt > 0L) (now - copyStartedAt).coerceAtLeast(1L) else 0L
            val copyBps = if (copyElapsedMs > 0L && bytesCopied > 0L) ((bytesCopied * 1000L) / copyElapsedMs).coerceAtLeast(0L) else 0L
            return JSONObject()
                .put("active", active)
                .put("state", state)
                .put("phase", phase)
                .put("message", message)
                .put("source", source)
                .put("started_at", if (startedAt > 0L) startedAt else JSONObject.NULL)
                .put("updated_at", if (updatedAt > 0L) updatedAt else JSONObject.NULL)
                .put("elapsed_ms", elapsedMs)
                .put("bytes_downloaded", bytesDownloaded.coerceAtLeast(0L))
                .put("bytes_copied", bytesCopied.coerceAtLeast(0L))
                .put("total_bytes", totalBytes.coerceAtLeast(0L))
                .put("copy_bps", copyBps)
                .put("detail", if (detail.isNotBlank()) detail else JSONObject.NULL)
        }
    }

    private data class MeMeConfig(
        val deviceId: String,
        val deviceName: String,
        val deviceDescription: String,
        val deviceIcon: String,
        val allowDiscovery: Boolean,
        val connectionTimeoutSec: Int,
        val maxConnections: Int,
        val connectionMethods: List<String>,
        val autoReconnect: Boolean,
        val reconnectIntervalSec: Int,
        val discoveryIntervalSec: Int,
        val connectionCheckIntervalSec: Int,
        val blePreferredMaxBytes: Int,
        val autoApproveOwnDevices: Boolean,
        val ownerIdentities: List<String>,
        val allowedDevices: List<String>,
        val blockedDevices: List<String>,
        val notifyOnConnection: Boolean,
        val notifyOnDisconnection: Boolean
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("device_id", deviceId)
                .put("device_name", deviceName)
                .put("device_description", deviceDescription)
                .put("device_icon", deviceIcon)
                .put("allow_discovery", allowDiscovery)
                .put("connection_timeout", connectionTimeoutSec)
                .put("max_connections", maxConnections)
                .put("connection_methods", org.json.JSONArray(connectionMethods))
                .put("auto_reconnect", autoReconnect)
                .put("reconnect_interval", reconnectIntervalSec)
                .put("discovery_interval", discoveryIntervalSec)
                .put("connection_check_interval", connectionCheckIntervalSec)
                .put("ble_preferred_max_bytes", blePreferredMaxBytes)
                .put("auto_approve_own_devices", autoApproveOwnDevices)
                .put("owner_identities", org.json.JSONArray(ownerIdentities))
                .put("allowed_devices", org.json.JSONArray(allowedDevices))
                .put("blocked_devices", org.json.JSONArray(blockedDevices))
                .put("notify_on_connection", notifyOnConnection)
                .put("notify_on_disconnection", notifyOnDisconnection)
        }
    }

    private data class MeMeRelayConfig(
        val enabled: Boolean,
        val gatewayBaseUrl: String,
        val provider: String,
        val routeTokenTtlSec: Int,
        val gatewayAdminSecret: String
    ) {
        fun toJson(includeSecrets: Boolean, fcmToken: String = ""): JSONObject {
            val out = JSONObject()
                .put("enabled", enabled)
                .put("gateway_base_url", gatewayBaseUrl)
                .put("provider", provider)
                .put("route_token_ttl_sec", routeTokenTtlSec)
                .put("device_push_token", fcmToken)
                .put("gateway_admin_secret_configured", gatewayAdminSecret.isNotBlank())
            if (includeSecrets) {
                out.put("gateway_admin_secret", gatewayAdminSecret)
            }
            return out
        }
    }

    private data class MeMeConnectIntent(
        val id: String,
        val sourceDeviceId: String,
        val sourceDeviceName: String,
        val sourceDeviceDescription: String = "",
        val sourceDeviceIcon: String = "",
        val sourceBleAddress: String = "",
        val targetDeviceId: String,
        val targetDeviceName: String,
        val createdAt: Long,
        val expiresAt: Long,
        val accepted: Boolean,
        val acceptToken: String,
        val methodHint: String,
        val sessionId: String,
        val sessionKeyB64: String,
        val sourceOwnerIdentities: List<String> = emptyList(),
        val sourceSigAlgorithm: String = "ed25519",
        val sourceKexAlgorithm: String = "x25519",
        val sourceSigPublicKeyB64: String = "",
        val sourceEphemeralPublicKeyB64: String = "",
        val sourceEphemeralPrivateKeyB64: String = ""
    ) {
        fun toJson(includeToken: Boolean): JSONObject {
            val out = JSONObject()
                .put("id", id)
                .put("source_device_id", sourceDeviceId)
                .put("source_device_name", sourceDeviceName)
                .put("source_device_description", sourceDeviceDescription)
                .put("source_device_icon", sourceDeviceIcon)
                .put("source_ble_address", sourceBleAddress)
                .put("target_device_id", targetDeviceId)
                .put("target_device_name", targetDeviceName)
                .put("created_at", createdAt)
                .put("expires_at", expiresAt)
                .put("accepted", accepted)
                .put("method", methodHint)
                .put("session_id", sessionId)
                .put("source_owner_identities", org.json.JSONArray(sourceOwnerIdentities))
            if (includeToken) out.put("accept_token", acceptToken)
            return out
        }
    }

    private data class MeMeConnection(
        val id: String,
        val peerDeviceId: String,
        val peerDeviceName: String,
        val method: String,
        val connectedAt: Long,
        val state: String,
        val role: String,
        val sessionId: String,
        val sessionKeyB64: String
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("id", id)
                .put("peer_device_id", peerDeviceId)
                .put("peer_device_name", peerDeviceName)
                .put("method", method)
                .put("connected_at", connectedAt)
                .put("state", state)
                .put("role", role)
                .put("session_id", sessionId)
        }
    }

    private data class MeMeAcceptToken(
        val requestId: String,
        val sourceDeviceId: String,
        val targetDeviceId: String,
        val sessionId: String,
        val sourceSigAlgorithm: String,
        val sourceKexAlgorithm: String,
        val sourceSigPublicKeyB64: String,
        val sourceEphemeralPublicKeyB64: String,
        val nonceB64: String,
        val signatureB64: String,
        val expiresAt: Long
    )

    private data class MeMeIdentityKeyPair(
        val algorithm: String,
        val privateKeyB64: String,
        val publicKeyB64: String
    )

    private data class MeMePeerPresence(
        var deviceName: String,
        var lastSeenAt: Long,
        var online: Boolean
    )

    private data class MeSyncImportSource(
        val httpUrl: String = ""
    ) {
        fun hasAnySource(): Boolean = httpUrl.isNotBlank()
    }

    private data class MeSyncV3Ticket(
        val id: String,
        val transferId: String,
        val createdAt: Long,
        @Volatile var expiresAt: Long,
        val sessionNonce: String,
        val pairCode: String,
        val sourceName: String,
        val ticketUri: String,
        val qrDataUrl: String
    )

    private data class MeSyncV3ParsedTicket(
        val ticketId: String,
        val transferId: String,
        val sessionNonce: String
    )

    private fun autoApprovePermission(
        tool: String,
        detail: String,
        scope: String,
        identity: String,
        capability: String
    ): jp.espresso3389.methings.perm.PermissionStore.PermissionRequest {
        val req = permissionStore.create(
            tool = tool,
            detail = detail.take(240),
            scope = scope,
            identity = identity,
            capability = capability
        )
        val updated = permissionStore.updateStatus(req.id, "approved")
            ?: req.copy(status = "approved")
        if (updated.status == "approved") {
            maybeGrantDeviceCapability(updated)
        }
        sendPermissionResolved(updated.id, updated.status)
        notifyBrainPermissionAutoApproved(updated)
        // Also notify the agent runtime so it can resume the paused tool call,
        // same as the manual-approval path.
        Thread {
            notifyBrainPermissionResolved(updated)
        }.apply { isDaemon = true }.start()
        return updated
    }

    private fun sendPermissionPrompt(id: String, tool: String, detail: String, forceBiometric: Boolean) {
        // Throttle duplicate prompts: when permission requests are re-used (pending) the agent
        // may re-trigger the same request quickly. Avoid spamming, but ensure the user still
        // gets a timely prompt if the previous one was dismissed/missed.
        val now = System.currentTimeMillis()
        val last = lastPermissionPromptAt[id] ?: 0L
        if ((now - last) < 1500L) {
            return
        }
        lastPermissionPromptAt[id] = now
        val intent = android.content.Intent(ACTION_PERMISSION_PROMPT)
        intent.setPackage(context.packageName)
        intent.putExtra(EXTRA_PERMISSION_ID, id)
        intent.putExtra(EXTRA_PERMISSION_TOOL, tool)
        intent.putExtra(EXTRA_PERMISSION_DETAIL, detail)
        intent.putExtra(EXTRA_PERMISSION_BIOMETRIC, forceBiometric)
        context.sendBroadcast(intent)

        // Forward to connected provisioned siblings for remote approval.
        // Biometric permissions require local device auth and are never forwarded.
        if (!forceBiometric) {
            Thread {
                forwardPermissionToSiblings(id, tool, detail)
            }.apply { isDaemon = true }.start()
        }
    }

    private fun forwardPermissionToSiblings(permissionId: String, tool: String, detail: String) {
        val selfCfg = currentMeMeConfig()
        val siblingIds = getProvisionedSiblingDeviceIds()
        for ((peerId, conn) in meMeConnections) {
            if (conn.state != "connected") continue
            if (!siblingIds.contains(peerId)) continue
            try {
                sendMeMeEncryptedMessage(
                    peerDeviceId = peerId,
                    type = "permission_forward",
                    payloadValue = JSONObject()
                        .put("permission_id", permissionId)
                        .put("tool", tool)
                        .put("detail", detail)
                        .put("device_id", selfCfg.deviceId)
                        .put("device_name", selfCfg.deviceName),
                    transportHint = "",
                    timeoutMs = 5_000L
                )
            } catch (_: Exception) {}
        }
    }

    private fun dismissRemotePermission(permissionId: String, status: String) {
        val selfCfg = currentMeMeConfig()
        val siblingIds = getProvisionedSiblingDeviceIds()
        for ((peerId, conn) in meMeConnections) {
            if (conn.state != "connected") continue
            if (!siblingIds.contains(peerId)) continue
            try {
                sendMeMeEncryptedMessage(
                    peerDeviceId = peerId,
                    type = "permission_dismiss",
                    payloadValue = JSONObject()
                        .put("permission_id", permissionId)
                        .put("status", status)
                        .put("device_id", selfCfg.deviceId),
                    transportHint = "",
                    timeoutMs = 5_000L
                )
            } catch (_: Exception) {}
        }
    }

    private fun handleRemotePermissionForward(fromDeviceId: String, fromDeviceName: String, plain: JSONObject) {
        if (!isProvisionedSibling(fromDeviceId)) return
        val p = plain.optJSONObject("payload") ?: plain
        val permissionId = p.optString("permission_id", "").trim()
        if (permissionId.isBlank()) return
        val tool = p.optString("tool", "").trim()
        val detail = p.optString("detail", "").trim()
        val sourceDeviceId = p.optString("device_id", fromDeviceId).trim()
        val sourceDeviceName = p.optString("device_name", fromDeviceName).trim()
        meMeRemotePermissions[permissionId] = RemotePermission(
            permissionId = permissionId,
            tool = tool,
            detail = detail,
            sourceDeviceId = sourceDeviceId,
            sourceDeviceName = sourceDeviceName,
            receivedAt = System.currentTimeMillis()
        )
    }

    private fun handleRemotePermissionResolve(fromDeviceId: String, plain: JSONObject) {
        if (!isProvisionedSibling(fromDeviceId)) return
        val p = plain.optJSONObject("payload") ?: plain
        val permissionId = p.optString("permission_id", "").trim()
        if (permissionId.isBlank()) return
        val action = p.optString("action", "").trim()
        val status = when (action) {
            "approve" -> "approved"
            "deny" -> "denied"
            else -> return
        }
        val updated = permissionStore.updateStatus(permissionId, status) ?: return
        if (status == "approved") {
            maybeGrantDeviceCapability(updated)
        }
        sendPermissionResolved(updated.id, updated.status)
        // Dismiss the forwarded permission on all other siblings.
        Thread {
            dismissRemotePermission(updated.id, updated.status)
        }.apply { isDaemon = true }.start()
        Thread {
            notifyBrainPermissionResolved(updated)
        }.apply { isDaemon = true }.start()
    }

    private fun handleRemotePermissionDismiss(plain: JSONObject) {
        val p = plain.optJSONObject("payload") ?: plain
        val permissionId = p.optString("permission_id", "").trim()
        if (permissionId.isBlank()) return
        meMeRemotePermissions.remove(permissionId)
    }

    private fun handleMeMeRemotePermissionsList(): Response {
        // Evict stale entries older than 5 minutes.
        val cutoff = System.currentTimeMillis() - 5 * 60_000L
        meMeRemotePermissions.entries.removeIf { it.value.receivedAt < cutoff }
        val arr = org.json.JSONArray()
        for ((_, rp) in meMeRemotePermissions) {
            if (rp.status != "pending") continue
            arr.put(
                JSONObject()
                    .put("id", rp.permissionId)
                    .put("tool", rp.tool)
                    .put("detail", rp.detail)
                    .put("source_device_id", rp.sourceDeviceId)
                    .put("source_device_name", rp.sourceDeviceName)
                    .put("received_at", rp.receivedAt)
            )
        }
        return jsonResponse(JSONObject().put("items", arr))
    }

    private fun handleMeMeRemotePermissionResolve(uri: String, payload: JSONObject): Response {
        // URI: /me/me/permissions/remote/{id}/resolve
        val segments = uri.removePrefix("/me/me/permissions/remote/").removeSuffix("/resolve").trim()
        if (segments.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "missing_permission_id")
        val permissionId = segments
        val rp = meMeRemotePermissions[permissionId]
            ?: return jsonError(Response.Status.NOT_FOUND, "not_found")
        val action = payload.optString("action", "").trim()
        if (action !in setOf("approve", "deny")) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid_action")
        }
        // Send permission_resolve back to the source device.
        val conn = meMeConnections[rp.sourceDeviceId]
        if (conn == null || conn.state != "connected") {
            return jsonError(Response.Status.CONFLICT, "source_device_not_connected")
        }
        try {
            sendMeMeEncryptedMessage(
                peerDeviceId = rp.sourceDeviceId,
                type = "permission_resolve",
                payloadValue = JSONObject()
                    .put("permission_id", permissionId)
                    .put("action", action),
                transportHint = "",
                timeoutMs = 5_000L
            )
        } catch (e: Exception) {
            return jsonError(Response.Status.INTERNAL_ERROR, "send_failed: ${e.message}")
        }
        meMeRemotePermissions.remove(permissionId)
        return jsonResponse(JSONObject().put("status", "ok").put("action", action))
    }

    private fun sendPermissionResolved(id: String, status: String) {
        val intent = android.content.Intent(ACTION_PERMISSION_RESOLVED)
        intent.setPackage(context.packageName)
        intent.putExtra(EXTRA_PERMISSION_ID, id)
        intent.putExtra(EXTRA_PERMISSION_STATUS, status)
        context.sendBroadcast(intent)
    }

    private fun notifyBrainPermissionResolved(req: jp.espresso3389.methings.perm.PermissionStore.PermissionRequest) {
        // Best-effort: notify the native agent runtime that a permission was approved/denied so it
        // can resume without requiring the user to manually say "continue".
        try {
            val runtime = agentRuntime ?: return
            if (!runtime.isRunning()) return
            runtime.enqueueEvent(
                name = "permission.resolved",
                payload = JSONObject()
                    .put("permission_id", req.id)
                    .put("status", req.status)
                    .put("tool", req.tool)
                    .put("detail", req.detail)
                    .put("identity", req.identity)
                    .put("session_id", req.identity)
                    .put("capability", req.capability),
            )
        } catch (_: Exception) {
        }
    }

    private fun notifyBrainEvent(
        name: String,
        payload: JSONObject,
        priority: String = "normal",
        interruptPolicy: String = "turn_end",
        coalesceKey: String? = null,
        coalesceWindowMs: Long? = null
    ) {
        try {
            var runtime = agentRuntime
            if (runtime == null || !runtime.isRunning()) {
                // Auto-start agent for actionable events if brain is configured
                val autoStartEvents = setOf("me.me.received", "permission.resolved")
                if (name in autoStartEvents && agentConfigManager.isConfigured()) {
                    runtime = getOrCreateAgentRuntime()
                    runtime.start()
                } else {
                    return
                }
            }
            runtime.enqueueEvent(
                name = name.trim().ifBlank { "unnamed_event" },
                payload = payload,
                priority = priority.trim().ifBlank { "normal" },
                interruptPolicy = interruptPolicy.trim().ifBlank { "turn_end" },
                coalesceKey = coalesceKey?.trim() ?: "",
                coalesceWindowMs = coalesceWindowMs?.coerceIn(0L, 86_400_000L) ?: 0L,
            )
        } catch (_: Exception) {
        }
    }

    private fun notifyBrainPermissionAutoApproved(req: jp.espresso3389.methings.perm.PermissionStore.PermissionRequest) {
        // Best-effort: add a chat-visible reference entry when dangerous auto-approval is active.
        try {
            val runtime = agentRuntime ?: return
            if (!runtime.isRunning()) return
            runtime.enqueueEvent(
                name = "permission.auto_approved",
                payload = JSONObject()
                    .put("permission_id", req.id)
                    .put("status", req.status)
                    .put("tool", req.tool)
                    .put("detail", req.detail)
                    .put("identity", req.identity)
                    .put("session_id", req.identity.trim())
                    .put("capability", req.capability),
            )
        } catch (_: Exception) {
        }
    }

    private fun maybeGrantDeviceCapability(req: jp.espresso3389.methings.perm.PermissionStore.PermissionRequest) {
        val tool = req.tool
        if (!tool.startsWith("device.")) {
            return
        }
        val identity = req.identity.trim()
        val capability = req.capability.trim().ifBlank { tool.removePrefix("device.").trim() }
        if (identity.isBlank() || capability.isBlank()) {
            return
        }

        val now = System.currentTimeMillis()
        val expiresAt = when (req.scope) {
            "persistent" -> 0L
            "session" -> now + 60L * 60L * 1000L
            "program" -> now + 10L * 60L * 1000L
            "once" -> now + 2L * 60L * 1000L
            else -> now + 10L * 60L * 1000L
        }
        deviceGrantStore.upsertGrant(identity, capability, req.scope, expiresAt)
    }

    private fun mimeTypeFor(name: String): String {
        return when (name.substringAfterLast('.', "" ).lowercase()) {
            "html" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }

    private inner class MeSyncLanDownloadServer : NanoHTTPD("0.0.0.0", ME_SYNC_LAN_PORT) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            return if (session.method == Method.GET && uri == "/me/sync/download") {
                handleMeSyncDownload(session)
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"not_found\"}")
            }
        }
    }

    private inner class MeMeLanServer : NanoHTTPD("0.0.0.0", ME_ME_LAN_PORT) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            return when {
                session.method == Method.GET && uri == "/health" -> {
                    jsonResponse(JSONObject().put("status", "ok").put("service", "me_me_lan"))
                }
                session.method == Method.POST && uri == "/me/me/data/ingest" -> {
                    val payload = JSONObject(readBody(session).ifBlank { "{}" })
                    handleMeMeDataIngest(payload, sourceIp = session.remoteIpAddress ?: "")
                }
                session.method == Method.POST && uri == "/me/me/data/ack" -> {
                    val payload = JSONObject(readBody(session).ifBlank { "{}" })
                    handleMeMeDataAck(payload, sourceIp = session.remoteIpAddress ?: "")
                }
                session.method == Method.POST && uri == "/me/me/connect/confirm" -> {
                    val payload = JSONObject(readBody(session).ifBlank { "{}" })
                    handleMeMeConnectConfirm(payload)
                }
                session.method == Method.POST && uri == "/me/me/connect/offer" -> {
                    val payload = JSONObject(readBody(session).ifBlank { "{}" })
                    handleMeMeConnectOffer(payload, sourceIp = session.remoteIpAddress ?: "")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"not_found\"}")
            }
        }
    }

    // ==============================
    // /android — device info & runtime permissions
    // ==============================

    private fun routeAndroid(session: IHTTPSession, uri: String, postBody: String?): Response {
        return when {
            // GET /android/device — non-sensitive device info
            (uri == "/android/device" || uri == "/android/device/") && session.method == Method.GET -> {
                val dm = context.resources.displayMetrics
                val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    context.resources.configuration.locales[0] else
                    @Suppress("DEPRECATION") context.resources.configuration.locale
                val payload = JSONObject()
                    .put("status", "ok")
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("brand", Build.BRAND)
                    .put("device", Build.DEVICE)
                    .put("product", Build.PRODUCT)
                    .put("board", Build.BOARD)
                    .put("hardware", Build.HARDWARE)
                    .put("display", Build.DISPLAY)
                    .put("android_version", Build.VERSION.RELEASE)
                    .put("sdk_int", Build.VERSION.SDK_INT)
                    .put("security_patch", Build.VERSION.SECURITY_PATCH)
                    .put("build_id", Build.ID)
                    .put("fingerprint", Build.FINGERPRINT)
                    .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS))
                    .put("screen_density_dpi", dm.densityDpi)
                    .put("screen_width_px", dm.widthPixels)
                    .put("screen_height_px", dm.heightPixels)
                    .put("locale", locale.toLanguageTag())
                jsonResponse(payload)
            }

            // GET /android/permissions — list manifest-declared permissions with grant status
            (uri == "/android/permissions" || uri == "/android/permissions/") && session.method == Method.GET -> {
                val pi = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS
                )
                val arr = JSONArray()
                for (perm in pi.requestedPermissions ?: emptyArray()) {
                    val granted = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                    arr.put(JSONObject().put("name", perm).put("granted", granted))
                }
                jsonResponse(JSONObject().put("status", "ok").put("permissions", arr))
            }

            // POST /android/permissions/request — request runtime permissions via system dialog
            (uri == "/android/permissions/request" || uri == "/android/permissions/request/") && session.method == Method.POST -> {
                val payload = try {
                    JSONObject((postBody ?: "").ifBlank { "{}" })
                } catch (_: Exception) {
                    return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                }
                val permsArr = payload.optJSONArray("permissions")
                    ?: return jsonError(Response.Status.BAD_REQUEST, "missing_permissions")
                if (permsArr.length() == 0)
                    return jsonError(Response.Status.BAD_REQUEST, "empty_permissions")

                // Collect requested permission names
                val requested = (0 until permsArr.length()).map { permsArr.getString(it) }

                // Validate against manifest-declared permissions
                val pi = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS
                )
                val declared = (pi.requestedPermissions ?: emptyArray()).toSet()
                val unknown = requested.filter { it !in declared }
                if (unknown.isNotEmpty()) {
                    return jsonError(
                        Response.Status.BAD_REQUEST, "unknown_permissions",
                        JSONObject().put("unknown", JSONArray(unknown))
                    )
                }

                // Check current grant status
                val results = mutableMapOf<String, Boolean>()
                val missing = mutableListOf<String>()
                for (perm in requested) {
                    val granted = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                    results[perm] = granted
                    if (!granted) missing.add(perm)
                }

                // All already granted — return immediately
                if (missing.isEmpty()) {
                    val resultsJson = JSONObject()
                    for ((k, v) in results) resultsJson.put(k, v)
                    return jsonResponse(
                        JSONObject()
                            .put("status", "ok")
                            .put("results", resultsJson)
                            .put("all_granted", true)
                    )
                }

                // Request missing permissions via MainActivity
                val requestId = UUID.randomUUID().toString()
                AndroidPermissionWaiter.begin(requestId, requested)
                try {
                    context.sendBroadcast(Intent(ACTION_ANDROID_PERM_REQUEST).apply {
                        setPackage(context.packageName)
                        putExtra(EXTRA_ANDROID_PERM_REQUEST_ID, requestId)
                        putExtra(EXTRA_ANDROID_PERM_NAMES, requested.toTypedArray())
                    })
                    val waitResults = AndroidPermissionWaiter.await(requestId, 60_000L)
                    if (waitResults == null) {
                        // Timeout or no UI to handle — re-check grant status
                        for (perm in requested) {
                            results[perm] = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                        }
                    } else {
                        results.putAll(waitResults)
                    }
                } finally {
                    AndroidPermissionWaiter.clear(requestId)
                }

                val resultsJson = JSONObject()
                for ((k, v) in results) resultsJson.put(k, v)
                val allGranted = results.values.all { it }
                jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("results", resultsJson)
                        .put("all_granted", allGranted)
                )
            }

            else -> notFound()
        }
    }

    companion object {
        private const val TAG = "LocalHttpServer"
        private const val HOST = "127.0.0.1"
        private const val PORT = 33389
        private const val TERMUX_HOME_PREFIX = "/data/data/com.termux/files/home"
        private const val ME_SYNC_LAN_PORT = 8766
        private const val ME_ME_LAN_PORT = 8767
        private const val ME_ME_BLE_MAX_MESSAGE_BYTES = 1_000_000
        private const val ME_ME_BLE_PREFERRED_MAX_BYTES_DEFAULT = 512 * 1024
        private const val ME_ME_BLE_PREFERRED_MAX_BYTES_MIN = 32 * 1024
        private const val ME_SYNC_QR_TTL_MS = 40L * 1000L
        private const val ME_SYNC_V3_TRANSFER_TTL_MS = 20L * 60L * 1000L
        private const val ME_SYNC_V3_MIN_TRANSFER_REMAINING_MS = 3L * 60L * 1000L
        private const val ME_SYNC_PROGRESS_STICKY_MS = 20L * 1000L
        private const val ME_SYNC_IMPORT_MAX_BYTES_MIN = 64L * 1024L * 1024L
        private const val ME_SYNC_IMPORT_MAX_BYTES_DEFAULT = 2L * 1024L * 1024L * 1024L
        private const val ME_SYNC_IMPORT_MAX_BYTES_LIMIT = 8L * 1024L * 1024L * 1024L
        private const val ME_SYNC_URI_PREFIX = "me.things:me.sync:"
        private const val ME_SYNC_V3_URI_PREFIX = "me.things:me.sync.v3:"
        private const val ME_SYNC_V3_TICKET_TTL_MS = 5L * 60L * 1000L
        private const val NEARBY_ME_SYNC_SERVICE_ID = "jp.espresso3389.methings.me_sync.v3"
        private const val ME_ME_PREFS = "me_me_prefs"
        private const val DEFAULT_ME_ME_RELAY_BASE_URL = "https://hooks.methings.org"
        private const val ME_ME_RELAY_GATEWAY_ADMIN_SECRET_CREDENTIAL = "me_me.relay.gateway_admin_secret"
        private const val DEFAULT_ME_ME_SIGNALING_URL = "wss://hooks.methings.org/signaling"
        private const val ME_ME_P2P_SIGNALING_TOKEN_CREDENTIAL = "me_me.p2p.signaling_token"
        private const val ME_ME_RELAY_PULL_MIN_INTERVAL_MS = 15_000L
        private const val HOUSEKEEPING_INITIAL_DELAY_SEC = 120L
        private const val HOUSEKEEPING_INTERVAL_SEC = 60L * 60L
        private const val ME_ME_DISCOVERY_INITIAL_DELAY_SEC = 8L
        private const val ME_ME_DISCOVERY_SCAN_TIMEOUT_MS = 2500L
        private const val ME_ME_DEVICE_LOST_MIN_MS = 90_000L
        private const val ME_ME_DEVICE_PRESENCE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val ME_ME_CONNECTION_CHECK_INITIAL_DELAY_SEC = 10L
        private const val ME_ME_AUTO_CONNECT_MAX_ATTEMPTS_PER_TICK = 2
        private const val ME_ME_AUTO_CONNECT_WAIT_BUDGET_MS = 4_000L
        private const val ME_ME_AUTO_CONNECT_SETTLE_WAIT_MS = 1_600L
        private const val ME_ME_AUTO_CONNECT_RETRY_DELAY_MS = 500L
        // Keep local sockets open for long-running interactive sessions (SSH/WS/SSE).
        private const val SOCKET_READ_TIMEOUT = 0
        private const val BRAIN_SYSTEM_PROMPT = """You are the me.things Brain, an AI assistant running on an Android device.

Policies:
- For detailed operational rules and tool usage, read user-root docs: `AGENTS.md` and `TOOLS.md`.
- Your goal is to produce the user's requested outcome (artifact/state change). Use tools/code to do it.
- Device/resource actions require explicit user approval via the app UI.
- Persistent memory lives in `MEMORY.md`. Only update it if the user explicitly asks. (Procedure in `AGENTS.md`.)

## Current Memory
"""
        const val ACTION_PERMISSION_PROMPT = "jp.espresso3389.methings.action.PERMISSION_PROMPT"
        const val ACTION_PERMISSION_RESOLVED = "jp.espresso3389.methings.action.PERMISSION_RESOLVED"
        const val ACTION_UI_RELOAD = "jp.espresso3389.methings.action.UI_RELOAD"
        const val ACTION_UI_CHAT_CACHE_CLEAR = "jp.espresso3389.methings.action.UI_CHAT_CACHE_CLEAR"
        const val ACTION_UI_VIEWER_COMMAND = "jp.espresso3389.methings.action.UI_VIEWER_COMMAND"
        const val ACTION_UI_SETTINGS_NAVIGATE = "jp.espresso3389.methings.action.UI_SETTINGS_NAVIGATE"
        const val ACTION_UI_ME_SYNC_EXPORT_SHOW = "jp.espresso3389.methings.action.UI_ME_SYNC_EXPORT_SHOW"
        const val EXTRA_VIEWER_COMMAND = "viewer_command"
        const val EXTRA_VIEWER_PATH = "viewer_path"
        const val EXTRA_VIEWER_ENABLED = "viewer_enabled"
        const val EXTRA_VIEWER_PAGE = "viewer_page"
        const val EXTRA_SETTINGS_SECTION_ID = "settings_section_id"
        const val EXTRA_PERMISSION_ID = "permission_id"
        const val EXTRA_PERMISSION_TOOL = "permission_tool"
        const val EXTRA_PERMISSION_DETAIL = "permission_detail"
        const val EXTRA_PERMISSION_BIOMETRIC = "permission_biometric"
        const val EXTRA_PERMISSION_STATUS = "permission_status"
        const val EXTRA_CHAT_PRESERVE_SESSION_ID = "chat_preserve_session_id"
        const val EXTRA_UI_RELOAD_TOAST = "ui_reload_toast"
        const val ACTION_ANDROID_PERM_REQUEST = "jp.espresso3389.methings.action.ANDROID_PERM_REQUEST"
        const val EXTRA_ANDROID_PERM_REQUEST_ID = "android_perm_request_id"
        const val EXTRA_ANDROID_PERM_NAMES = "android_perm_names"
        private val ME_SYNC_SHARED_PREFS_EXPORT = listOf(
            "brain_config",
            "cloud_prefs",
            "task_completion_prefs",
            "browser_prefs",
            "audio_record_config",
            "video_record_config",
            "screen_record_config",

        )
        private val SETTINGS_SECTIONS = listOf(
            "brain" to "Brain",
            "web_search" to "Web Search",
            "memory" to "Memory",
            "task_notifications" to "Task Notifications",
            "audio_recording" to "Audio Recording",
            "video_recording" to "Video Recording",
            "agent_service" to "Agent Service",
            "user_interface" to "User Interface",
            "reset_restore" to "Reset & Restore",
            "android" to "Android",
            "permissions" to "Permissions",
            "file_transfer" to "File Transfer",
            "tts" to "Text-to-Speech",
            "me_me" to "me.me",
            "me_sync" to "me.sync",
            "app_update" to "App Update",
            "about" to "About",
        )
        private val SETTINGS_SECTION_IDS: Set<String> = SETTINGS_SECTIONS.map { it.first }.toSet()
    }
}
