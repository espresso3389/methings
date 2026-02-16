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
import jp.espresso3389.methings.perm.SshKeyStore
import jp.espresso3389.methings.perm.SshKeyPolicy
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import jp.espresso3389.methings.device.UsbPermissionWaiter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONArray

class LocalHttpServer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val runtimeManager: PythonRuntimeManager,
    private val sshdManager: SshdManager,
    private val sshPinManager: SshPinManager,
    private val sshNoAuthModeManager: SshNoAuthModeManager
) : NanoWSD(HOST, PORT) {
    private val uiRoot = File(context.filesDir, "www")
    private val permissionStore = PermissionStoreFacade(context)
    private val permissionPrefs = PermissionPrefs(context)
    private val installIdentity = InstallIdentity(context)
    private val credentialStore = CredentialStore(context)
    private val sshKeyStore = SshKeyStore(context)
    private val sshKeyPolicy = SshKeyPolicy(context)
    private val deviceGrantStore = jp.espresso3389.methings.perm.DeviceGrantStoreFacade(context)
    private val agentTasks = java.util.concurrent.ConcurrentHashMap<String, AgentTask>()
    private val lastPermissionPromptAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val usbConnections = ConcurrentHashMap<String, UsbDeviceConnection>()
    private val usbDevicesByHandle = ConcurrentHashMap<String, UsbDevice>()
    private val usbStreams = ConcurrentHashMap<String, UsbStreamState>()

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
    private val meSyncImportProgressLock = Any()
    @Volatile private var meSyncImportProgress = MeSyncImportProgress()
    private val meMePrefs = context.getSharedPreferences(ME_ME_PREFS, Context.MODE_PRIVATE)
    private val meMeConnectIntents = ConcurrentHashMap<String, MeMeConnectIntent>()
    private val meMeConnections = ConcurrentHashMap<String, MeMeConnection>()
    private val meMeInboundMessages = ConcurrentHashMap<String, MutableList<JSONObject>>()
    private val meMeRelayEvents = mutableListOf<JSONObject>()
    private val meMeRelayEventsLock = Any()
    private val meMeDiscoveryAlertLastAt = ConcurrentHashMap<String, Long>()
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

    @Volatile private var authKeysLastMtime: Long = 0L

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
            meMeExecutor.execute {
                runCatching { meMeDiscovery.applyConfig(currentMeMeDiscoveryConfig()) }
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
        return when {
            uri == "/health" -> jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("service", "local")
                    .put("python", runtimeManager.getStatus())
            )
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
            uri == "/python/status" -> jsonResponse(
                JSONObject().put("status", runtimeManager.getStatus())
            )
            uri == "/python/start" -> {
                runtimeManager.startWorker()
                jsonResponse(JSONObject().put("status", "starting"))
            }
            uri == "/python/stop" -> {
                runtimeManager.requestShutdown()
                jsonResponse(JSONObject().put("status", "stopping"))
            }
            uri == "/python/restart" -> {
                runtimeManager.restartSoft()
                jsonResponse(JSONObject().put("status", "starting"))
            }
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
            uri == "/app/info" -> {
                handleAppInfo()
            }
            uri == "/me/sync/status" && session.method == Method.GET -> {
                handleMeSyncStatus()
            }
            uri == "/me/sync/progress" && session.method == Method.GET -> {
                handleMeSyncProgress()
            }
            uri == "/me/me/status" && session.method == Method.GET -> {
                handleMeMeStatus()
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
            uri == "/me/me/connect/confirm" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.connect",
                    detail = "Confirm me.things connection request"
                )
                if (!ok.first) return ok.second!!
                handleMeMeConnectConfirm(payload)
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
            uri == "/me/me/relay/notify" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.relay",
                    detail = "Send relay notification to a peer device"
                )
                if (!ok.first) return ok.second!!
                handleMeMeRelayNotify(payload)
            }
            uri == "/me/me/relay/ingest" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                handleMeMeRelayIngest(payload)
            }
            uri == "/me/me/relay/events/pull" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.relay",
                    detail = "Read relay events received from server push"
                )
                if (!ok.first) return ok.second!!
                handleMeMeRelayEventsPull(payload)
            }
            uri == "/me/me/relay/pull_gateway" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(
                    session,
                    payload,
                    tool = "device.me_me",
                    capability = "me_me.relay",
                    detail = "Pull queued me.me relay events from gateway"
                )
                if (!ok.first) return ok.second!!
                handleMeMeRelayPullFromGateway(payload, force = true)
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
                val uiInitiated = payload.optBoolean("ui_initiated", false)
                if (shouldRequestPermissionForTempMeSyncSshd() && !uiInitiated) {
                    val ok = ensureDevicePermission(
                        session,
                        payload,
                        tool = "device.me_sync",
                        capability = "me_sync.export_temp_sshd",
                        detail = "Temporarily enable SSHD for me.sync export"
                    )
                    if (!ok.first) return ok.second!!
                }
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
            uri == "/ui/version" -> {
                val versionFile = File(uiRoot, ".version")
                val version = if (versionFile.exists()) versionFile.readText().trim() else ""
                textResponse(version)
            }
            uri == "/ui/reload" && session.method == Method.POST -> {
                // Dev helper: hot-reload WebView UI after adb pushing files into files/www.
                // This avoids a full APK rebuild during UI iteration.
                context.sendBroadcast(
                    android.content.Intent(ACTION_UI_RELOAD).apply {
                        putExtra(EXTRA_UI_RELOAD_TOAST, "UI reloaded")
                    }
                )
                jsonResponse(JSONObject().put("status", "ok"))
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
                    tool == "ssh_keys" -> "once"
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
                        val forceBio = when (tool) {
                            "ssh_keys" -> sshKeyPolicy.isBiometricRequired()
                            "ssh_pin" -> true
                            else -> false
                        }
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
                val forceBio = when (tool) {
                    "ssh_keys" -> sshKeyPolicy.isBiometricRequired()
                    "ssh_pin" -> true
                    else -> false
                }
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
                        // Also notify the Python agent runtime so it can resume automatically.
                        notifyBrainPermissionResolved(updated)
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
            uri == "/builtins/tts" && session.method == Method.POST -> {
                return jsonError(Response.Status.NOT_IMPLEMENTED, "not_implemented", JSONObject().put("feature", "tts"))
            }
            uri == "/builtins/stt" && session.method == Method.POST -> {
                return jsonError(Response.Status.NOT_IMPLEMENTED, "not_implemented", JSONObject().put("feature", "stt"))
            }
            uri == "/brain/events" && session.method == Method.GET -> {
                if (runtimeManager.getStatus() != "ok") {
                    runtimeManager.startWorker()
                    waitForPythonHealth(5000)
                }
                proxyGetStreamFromWorker("/brain/events", session.queryParameterString)
                    ?: jsonError(Response.Status.SERVICE_UNAVAILABLE, "python_unavailable")
            }
            (
                uri == "/brain/status" ||
                    uri == "/brain/messages" ||
                    uri == "/brain/sessions" ||
                    uri == "/brain/journal/config" ||
                    uri == "/brain/journal/current" ||
                    uri == "/brain/journal/list"
                ) && session.method == Method.GET -> {
                if (runtimeManager.getStatus() != "ok") {
                    runtimeManager.startWorker()
                    waitForPythonHealth(5000)
                }
                val proxied = proxyWorkerRequest(
                    path = uri,
                    method = "GET",
                    body = null,
                    query = session.queryParameterString
                )
                proxied ?: jsonError(Response.Status.SERVICE_UNAVAILABLE, "python_unavailable")
            }
            (
                uri == "/brain/start" ||
                    uri == "/brain/stop" ||
                    uri == "/brain/interrupt" ||
                    uri == "/brain/retry" ||
                    uri == "/brain/inbox/chat" ||
                    uri == "/brain/inbox/event" ||
                    uri == "/brain/session/delete" ||
                    uri == "/brain/session/rename" ||
                    uri == "/brain/debug/comment" ||
                    uri == "/brain/journal/current" ||
                    uri == "/brain/journal/append"
                ) && session.method == Method.POST -> {
                if (runtimeManager.getStatus() != "ok") {
                    runtimeManager.startWorker()
                    waitForPythonHealth(5000)
                }
                if (uri == "/brain/debug/comment") {
                    val ip = (session.remoteIpAddress ?: "").trim()
                    if (ip.isNotEmpty() && ip != "127.0.0.1" && ip != "::1") {
                        return jsonError(Response.Status.FORBIDDEN, "debug_local_only")
                    }
                }
                val body = (postBody ?: "").ifBlank { "{}" }
                val proxied = proxyWorkerRequest(
                    path = uri,
                    method = "POST",
                    body = body
                )
                if (proxied == null) {
                    return jsonError(Response.Status.SERVICE_UNAVAILABLE, "python_unavailable")
                }
                if (uri == "/brain/inbox/chat" && proxied.status == Response.Status.BAD_REQUEST) {
                    // Help diagnose UI issues; keep it short and avoid logging secrets.
                    Log.w(TAG, "brain/inbox/chat 400 body.len=${body.length} body.head=${body.take(120)}")
                }
                proxied
            }
            (uri == "/shell/exec" || uri == "/shell/exec/") -> {
                if (session.method != Method.POST) {
                    return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
                }
                val body = postBody ?: ""
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                return handleShellExec(payload)
            }
            (uri == "/web/search" || uri == "/web/search/") -> {
                if (session.method != Method.POST) {
                    return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
                }
                val body = postBody ?: ""
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                return handleWebSearch(session, payload)
            }
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
            (uri == "/cloud/request" || uri == "/cloud/request/") -> {
                if (session.method != Method.POST) {
                    return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
                }
                val body = (postBody ?: "").ifBlank { "{}" }
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                return handleCloudRequest(session, payload)
            }
            uri == "/cloud/prefs" && session.method == Method.GET -> {
                val autoMb = cloudPrefs.getFloat("auto_upload_no_confirm_mb", 1.0f).toDouble()
                val minKbps = cloudPrefs.getFloat("min_transfer_kbps", 0.0f).toDouble()
                val imgResizeEnabled = cloudPrefs.getBoolean("image_resize_enabled", true)
                val imgMaxDim = cloudPrefs.getInt("image_resize_max_dim_px", 512)
                val imgJpegQ = cloudPrefs.getInt("image_resize_jpeg_quality", 70)
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
            uri == "/cloud/prefs" && session.method == Method.POST -> {
                val body = (postBody ?: "").ifBlank { "{}" }
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                val v = when {
                    payload.has("auto_upload_no_confirm_mb") ->
                        payload.optDouble("auto_upload_no_confirm_mb", cloudPrefs.getFloat("auto_upload_no_confirm_mb", 1.0f).toDouble())
                    payload.has("allow_auto_upload_payload_size_less_than_mb") ->
                        payload.optDouble("allow_auto_upload_payload_size_less_than_mb", cloudPrefs.getFloat("auto_upload_no_confirm_mb", 1.0f).toDouble())
                    else ->
                        cloudPrefs.getFloat("auto_upload_no_confirm_mb", 1.0f).toDouble()
                }
                val clamped = v.coerceIn(0.0, 25.0)
                val mk = payload.optDouble("min_transfer_kbps", cloudPrefs.getFloat("min_transfer_kbps", 0.0f).toDouble())
                val mkClamped = mk.coerceIn(0.0, 50_000.0)
                val imgEnabled = if (payload.has("image_resize_enabled")) payload.optBoolean("image_resize_enabled", true) else cloudPrefs.getBoolean("image_resize_enabled", true)
                val imgMaxDimRaw = if (payload.has("image_resize_max_dim_px")) payload.optInt("image_resize_max_dim_px", 512) else cloudPrefs.getInt("image_resize_max_dim_px", 512)
                val imgJpegQRaw = if (payload.has("image_resize_jpeg_quality")) payload.optInt("image_resize_jpeg_quality", 70) else cloudPrefs.getInt("image_resize_jpeg_quality", 70)
                val imgMaxDim = imgMaxDimRaw.coerceIn(64, 4096)
                val imgJpegQ = imgJpegQRaw.coerceIn(30, 95)
                cloudPrefs.edit()
                    .putFloat("auto_upload_no_confirm_mb", clamped.toFloat())
                    .putFloat("min_transfer_kbps", mkClamped.toFloat())
                    .putBoolean("image_resize_enabled", imgEnabled)
                    .putInt("image_resize_max_dim_px", imgMaxDim)
                    .putInt("image_resize_jpeg_quality", imgJpegQ)
                    .apply()
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
            (uri == "/screen/status" || uri == "/screen/status/") && session.method == Method.GET -> {
                return handleScreenStatus()
            }
            (uri == "/screen/keep_on" || uri == "/screen/keep_on/") && session.method == Method.POST -> {
                val body = (postBody ?: "").ifBlank { "{}" }
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                return handleScreenKeepOn(session, payload)
            }
            (uri == "/pip/status" || uri == "/pip/status/") -> {
                val wheelhouse = WheelhousePaths.forCurrentAbi(context)?.also { it.ensureDirs() }
                return jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "")
                        .put("python_home", File(context.filesDir, "pyenv").absolutePath)
                        .put("wheelhouse_root", wheelhouse?.root?.absolutePath ?: "")
                        .put("wheelhouse_bundled", wheelhouse?.bundled?.absolutePath ?: "")
                        .put("wheelhouse_user", wheelhouse?.user?.absolutePath ?: "")
                        .put("pip_find_links", wheelhouse?.findLinksEnvValue() ?: "")
                )
            }
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
                val file = userPath(outPath) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
                val q = payload.optInt("jpeg_quality", 95).coerceIn(40, 100)
                val exp = if (payload.has("exposure_compensation")) payload.optInt("exposure_compensation") else null
                val out = JSONObject(camera.captureStill(file, lens, jpegQuality = q, exposureCompensation = exp))
                // Absolute path is useful for logs/debugging, but tools should prefer rel_path under user root.
                out.put("rel_path", outPath)
                return jsonResponse(out)
            }
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

            // ── Audio Recording ──────────────────────────────────────────
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

            // ── Video Recording ──────────────────────────────────────────
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

            // ── Screen Recording ─────────────────────────────────────────
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
            (uri == "/network/status" || uri == "/network/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.network", capability = "network", detail = "Network connectivity status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(network.status()))
            }
            (uri == "/wifi/status" || uri == "/wifi/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.network", capability = "network", detail = "Wi-Fi status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(network.wifiStatus()))
            }
            (uri == "/mobile/status" || uri == "/mobile/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.network", capability = "network", detail = "Mobile signal status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(network.mobileStatus()))
            }
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
            uri == "/sshd/status" -> {
                val status = sshdManager.status()
                jsonResponse(
                    JSONObject()
                        .put("enabled", status.enabled)
                        .put("running", status.running)
                        .put("port", status.port)
                        .put("noauth_enabled", status.noauthEnabled)
                        .put("auth_mode", sshdManager.getAuthMode())
                        .put("host", sshdManager.getHostIp())
                        .put("client_key_fingerprint", status.clientKeyFingerprint)
                        .put("client_key_public", status.clientKeyPublic)
                )
            }
            uri == "/sshd/keys" -> {
                // If the authorized_keys file was edited externally (e.g., via SSH), the DB-backed
                // key list can get out of sync. Import any valid keys from the file so the UI
                // reflects reality and future syncs won't accidentally drop them.
                try {
                    importAuthorizedKeysFromFile()
                } catch (_: Exception) {
                }
                runHousekeepingOnce()
                val arr = org.json.JSONArray()
                sshKeyStore.listAll().forEach { key ->
                    arr.put(
                        JSONObject()
                            .put("fingerprint", key.fingerprint)
                            .put("label", key.label ?: "")
                            .put("expires_at", key.expiresAt ?: JSONObject.NULL)
                            .put("created_at", key.createdAt)
                    )
                }
                jsonResponse(JSONObject().put("items", arr))
            }
            uri == "/sshd/keys/policy" -> {
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
            uri == "/sshd/keys/add" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val key = payload.optString("key", "")
                val label = payload.optString("label", "")
                val expiresAt = if (payload.has("expires_at")) payload.optLong("expires_at", 0L) else null
                val permissionId = payload.optString("permission_id", "")
                if (!isPermissionApproved(permissionId, consume = true)) {
                    return forbidden("permission_required")
                }
                if (key.isBlank()) {
                    return badRequest("key_required")
                }
                val parsed = parseSshPublicKey(key) ?: return badRequest("invalid_public_key")
                val finalLabel = sanitizeSshKeyLabel(label).takeIf { it != null }
                    ?: sanitizeSshKeyLabel(parsed.comment ?: "")
                // Store canonical key WITHOUT comment so fingerprinting and de-duplication remain stable
                // even when labels/comments are edited.
                val entity = sshKeyStore.upsert(parsed.canonicalNoComment(), finalLabel, expiresAt)
                syncAuthorizedKeys()
                sshdManager.restartIfRunning()
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
                val permissionId = payload.optString("permission_id", "")
                if (!isPermissionApproved(permissionId, consume = true)) {
                    return forbidden("permission_required")
                }
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
                sshdManager.restartIfRunning()
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/sshd/pin/status" -> {
                val state = sshPinManager.status()
                if (state.expired) {
                    sshPinManager.stopPin()
                    sshdManager.exitPinMode()
                } else if (!state.active && sshdManager.getAuthMode() == "pin") {
                    sshdManager.exitPinMode()
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
                val permissionId = payload.optString("permission_id", "")
                val seconds = payload.optInt("seconds", 10)
                if (!isPermissionApproved(permissionId, consume = true)) {
                    return forbidden("permission_required")
                }
                Log.i(TAG, "PIN auth start requested")
                sshdManager.enterPinMode()
                val state = sshPinManager.startPin(seconds)
                Log.i(TAG, "PIN auth generated pin=${state.pin}")
                jsonResponse(
                    JSONObject()
                        .put("active", state.active)
                        .put("pin", state.pin ?: "")
                        .put("expires_at", state.expiresAt ?: JSONObject.NULL)
                )
            }
            uri == "/sshd/pin/stop" && session.method == Method.POST -> {
                Log.i(TAG, "PIN auth stop requested")
                sshPinManager.stopPin()
                sshdManager.exitPinMode()
                jsonResponse(JSONObject().put("active", false))
            }
            uri == "/sshd/noauth/status" -> {
                val state = sshNoAuthModeManager.status()
                if (state.expired) {
                    sshNoAuthModeManager.stop()
                    sshdManager.exitNotificationMode()
                } else if (!state.active && sshdManager.getAuthMode() == SshdManager.AUTH_MODE_NOTIFICATION) {
                    sshdManager.exitNotificationMode()
                }
                jsonResponse(
                    JSONObject()
                        .put("active", state.active)
                        .put("expires_at", state.expiresAt ?: JSONObject.NULL)
                )
            }
            uri == "/sshd/noauth/start" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val permissionId = payload.optString("permission_id", "")
                val seconds = payload.optInt("seconds", 30)
                if (!isPermissionApproved(permissionId, consume = true)) {
                    return forbidden("permission_required")
                }
                Log.i(TAG, "Notification auth start requested")
                sshdManager.enterNotificationMode()
                val state = sshNoAuthModeManager.start(seconds)
                jsonResponse(
                    JSONObject()
                        .put("active", state.active)
                        .put("expires_at", state.expiresAt ?: JSONObject.NULL)
                )
            }
            uri == "/sshd/noauth/stop" && session.method == Method.POST -> {
                Log.i(TAG, "Notification auth stop requested")
                sshNoAuthModeManager.stop()
                sshdManager.exitNotificationMode()
                jsonResponse(JSONObject().put("active", false))
            }
            uri == "/sshd/config" && session.method == Method.POST -> {
                val body = postBody ?: ""
                val payload = JSONObject(body.ifBlank { "{}" })
                val enabled = payload.optBoolean("enabled", sshdManager.isEnabled())
                val port = if (payload.has("port")) payload.optInt("port", sshdManager.getPort()) else null
                val authMode = payload.optString("auth_mode", "")
                val noauthEnabled = if (payload.has("noauth_enabled")) payload.optBoolean("noauth_enabled") else null
                if (authMode.isNotBlank()) {
                    sshdManager.setAuthMode(authMode)
                }
                val status = sshdManager.updateConfig(enabled, port, noauthEnabled)
                jsonResponse(
                    JSONObject()
                        .put("enabled", status.enabled)
                        .put("running", status.running)
                        .put("port", status.port)
                        .put("noauth_enabled", status.noauthEnabled)
                )
            }
            uri == "/ssh/exec" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.ssh", capability = "ssh.exec", detail = "Run one-shot SSH command")
                if (!ok.first) return ok.second!!
                return handleSshExec(payload)
            }
            uri == "/ssh/scp" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.ssh", capability = "ssh.scp", detail = "Transfer files with SCP")
                if (!ok.first) return ok.second!!
                return handleSshScp(payload)
            }
            uri == "/ssh/ws/contract" && session.method == Method.GET -> {
                return jsonResponse(
                    JSONObject()
                        .put("status", "ok")
                        .put("ws_path", "/ws/ssh/interactive")
                        .put("query", JSONObject()
                            .put("host", "required hostname or IP")
                            .put("user", "optional user")
                            .put("port", "optional port (default 22)")
                            .put("permission_id", "optional approved permission id")
                            .put("identity", "optional stable identity for permission reuse"))
                        .put("client_messages", org.json.JSONArray()
                            .put(JSONObject().put("type", "stdin").put("data", "utf-8 text"))
                            .put(JSONObject().put("type", "stdin").put("data_b64", "base64 bytes"))
                            .put(JSONObject().put("type", "signal").put("name", "interrupt|terminate")))
                        .put("server_messages", org.json.JSONArray()
                            .put(JSONObject().put("type", "hello").put("session_id", "uuid"))
                            .put(JSONObject().put("type", "stdout").put("data_b64", "base64 bytes"))
                            .put(JSONObject().put("type", "stderr").put("data_b64", "base64 bytes"))
                            .put(JSONObject().put("type", "exit").put("code", 0))
                            .put(JSONObject().put("type", "error").put("code", "reason")))
                )
            }
            uri == "/brain/config" && session.method == Method.GET -> handleBrainConfigGet()
            uri == "/brain/config" && session.method == Method.POST -> {
                val body = postBody ?: ""
                handleBrainConfigSet(body)
            }
            uri == "/brain/agent/bootstrap" && session.method == Method.POST -> {
                handleBrainAgentBootstrap()
            }
            // Chat-mode streaming (direct cloud) has been removed. Use agent mode instead.
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
            (uri == "/ui/viewer/open" || uri == "/ui/viewer/open/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val path = payload.optString("path", "").trim()
                if (path.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
                // Strip #page=N fragment for file validation; preserve full path for JS.
                val filePath = path.replace(Regex("#.*$"), "")
                val file = if (filePath.startsWith("\$sys/")) {
                    systemPath(filePath.removePrefix("\$sys/"))
                        ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_system_dir")
                } else {
                    userPath(filePath)
                        ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
                }
                if (!file.exists()) return jsonError(Response.Status.NOT_FOUND, "not_found")
                val intent = Intent(ACTION_UI_VIEWER_COMMAND).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_VIEWER_COMMAND, "open")
                    putExtra(EXTRA_VIEWER_PATH, path)
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
            uri == "/user/list" && session.method == Method.GET -> {
                handleUserList(session)
            }
            uri == "/user/file" && session.method == Method.GET -> {
                serveUserFile(session)
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
        val rel = firstParam(session, "path").trim().trimStart('/')
        val root = File(context.filesDir, "user").canonicalFile
        val dir = if (rel.isBlank()) root else userPath(rel) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
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

    private fun serveUserFile(session: IHTTPSession): Response {
        val rel = firstParam(session, "path")
        if (rel.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
        val file = userPath(rel) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
        if (!file.exists() || !file.isFile) return jsonError(Response.Status.NOT_FOUND, "not_found")

        val relLower = rel.lowercase()
        val nameLower = file.name.lowercase()
        val isAudioRecordingWebm =
            (nameLower.endsWith(".webm") && (nameLower.startsWith("audio_recording") || relLower.contains("uploads/recordings/")))
        val mime = if (isAudioRecordingWebm) {
            "audio/webm"
        } else {
            URLConnection.guessContentTypeFromName(file.name) ?: mimeTypeFor(file.name)
        }
        val stream: InputStream = FileInputStream(file)
        val response = newChunkedResponse(Response.Status.OK, mime, stream)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("X-Content-Type-Options", "nosniff")
        return response
    }

    private fun handleUserFileInfo(session: IHTTPSession): Response {
        val rel = firstParam(session, "path").replace(Regex("#.*$"), "")
        if (rel.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "path_required")
        val file = userPath(rel) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
        if (!file.exists() || !file.isFile) return jsonError(Response.Status.NOT_FOUND, "not_found")

        val ext = file.name.substringAfterLast('.', "").lowercase()
        val mime = URLConnection.guessContentTypeFromName(file.name) ?: mimeTypeFor(file.name)
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
            .put("name", file.name)
            .put("size", file.length())
            .put("mtime_ms", file.lastModified())
            .put("mime", mime)
            .put("kind", kind)
            .put("ext", ext)

        // Image dimensions
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

        // Marp detection for markdown files
        if (ext == "md") {
            try {
                // Read only first 1KB for front matter check
                val head = file.inputStream().use { inp ->
                    val buf = ByteArray(1024)
                    val n = inp.read(buf)
                    if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
                }
                val fmMatch = Regex("^---\\s*\\n([\\s\\S]*?)\\n---").find(head)
                val isMarp = fmMatch != null && Regex("^marp\\s*:\\s*true\\s*$", RegexOption.MULTILINE).containsMatchIn(fmMatch.groupValues[1])
                json.put("is_marp", isMarp)
                if (isMarp) {
                    // Read full file for slide count
                    val fullText = file.readText(Charsets.UTF_8)
                    val stripped = fullText.replace(Regex("^---\\s*\\n[\\s\\S]*?\\n---\\n?"), "")
                    val slideCount = stripped.split("\n---\n").size
                    json.put("slide_count", slideCount)
                }
            } catch (_: Exception) {
                json.put("is_marp", false)
            }
        }

        return jsonResponse(json)
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
        val dir = (parms["dir"]?.firstOrNull() ?: parms["path"]?.firstOrNull() ?: "").trim().trimStart('/')
        val relPath = if (dir.isBlank()) "uploads/$name" else (dir.trimEnd('/') + "/" + name)
        val out = userPath(relPath) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
        out.parentFile?.mkdirs()

        return try {
            File(tmp).inputStream().use { inp ->
                out.outputStream().use { outp ->
                    inp.copyTo(outp)
                }
            }
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
                    .put("path", relPath)
                    .put("size", out.length())
            )
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
        val conn = usbConnections.remove(handle)
        usbDevicesByHandle.remove(handle)
        try {
            conn?.close()
        } catch (_: Exception) {
        }
        return jsonResponse(JSONObject().put("status", "ok").put("closed", conn != null))
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

        if (uri == "/ws/ssh/interactive") {
            val params = handshake.parameters
            val host = (params["host"]?.firstOrNull() ?: "").trim()
            val user = (params["user"]?.firstOrNull() ?: "").trim()
            val port = (params["port"]?.firstOrNull() ?: "22").trim().toIntOrNull()?.coerceIn(1, 65535) ?: 22
            val permissionId = (params["permission_id"]?.firstOrNull() ?: "").trim()
            val identityQ = (params["identity"]?.firstOrNull() ?: "").trim()
            val sessionId = "sshws-" + UUID.randomUUID().toString()

            return object : NanoWSD.WebSocket(handshake) {
                private var proc: Process? = null
                private val closed = AtomicBoolean(false)

                override fun onOpen() {
                    if (host.isBlank()) {
                        runCatching {
                            send(JSONObject().put("type", "error").put("code", "host_required").toString())
                            close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "host_required", false)
                        }
                        return
                    }
                    val permission = ensureDevicePermissionForWs(
                        session = handshake,
                        permissionId = permissionId,
                        identityFromQuery = identityQ,
                        tool = "device.ssh",
                        capability = "ssh.interactive",
                        detail = "Open interactive SSH websocket"
                    )
                    if (permission != null) {
                        runCatching {
                            send(JSONObject().put("type", "permission_required").put("request", permission).toString())
                            close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "permission_required", false)
                        }
                        return
                    }

                    val dbclient = findDbclientBinary()
                    if (dbclient == null) {
                        runCatching {
                            send(JSONObject().put("type", "error").put("code", "ssh_client_missing").toString())
                            close(NanoWSD.WebSocketFrame.CloseCode.InternalServerError, "ssh_client_missing", false)
                        }
                        return
                    }

                    val args = mutableListOf(dbclient.absolutePath, "-y", "-t")
                    if (port != 22) {
                        args.add("-p")
                        args.add(port.toString())
                    }
                    args.add(sshTarget(user, host))

                    try {
                        proc = buildSshProcess(args).start()
                    } catch (ex: Exception) {
                        Log.e(TAG, "Failed to start interactive ssh websocket", ex)
                        runCatching {
                            send(JSONObject().put("type", "error").put("code", "ssh_start_failed").put("detail", ex.message ?: "").toString())
                            close(NanoWSD.WebSocketFrame.CloseCode.InternalServerError, "ssh_start_failed", false)
                        }
                        return
                    }

                    runCatching {
                        send(
                            JSONObject()
                                .put("type", "hello")
                                .put("session_id", sessionId)
                                .put("target", sshTarget(user, host))
                                .put("port", port)
                                .toString()
                        )
                    }

                    val running = proc ?: return
                    startSshWsPump(running.inputStream, "stdout", this, closed)
                    startSshWsPump(running.errorStream, "stderr", this, closed)
                    Thread {
                        val code = runCatching { running.waitFor() }.getOrElse { -1 }
                        if (closed.compareAndSet(false, true)) {
                            runCatching {
                                send(JSONObject().put("type", "exit").put("code", code).toString())
                                close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "done", false)
                            }
                        }
                    }.start()
                }

                override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                    closeProcess()
                }

                override fun onMessage(message: NanoWSD.WebSocketFrame?) {
                    val txt = runCatching { message?.textPayload ?: "" }.getOrDefault("")
                    if (txt.isBlank()) return
                    val payload = runCatching { JSONObject(txt) }.getOrNull() ?: return
                    val p = proc ?: return
                    when (payload.optString("type", "").trim()) {
                        "stdin" -> {
                            val dataB64 = payload.optString("data_b64", "").trim()
                            val bytes = if (dataB64.isNotBlank()) {
                                runCatching { Base64.decode(dataB64, Base64.DEFAULT) }.getOrNull()
                            } else {
                                payload.optString("data", "").toByteArray(StandardCharsets.UTF_8)
                            } ?: return
                            runCatching {
                                p.outputStream.write(bytes)
                                p.outputStream.flush()
                            }
                        }
                        "signal" -> {
                            when (payload.optString("name", "").trim().lowercase(Locale.US)) {
                                "interrupt" -> runCatching { p.outputStream.write(byteArrayOf(3)); p.outputStream.flush() }
                                "terminate" -> closeProcess()
                            }
                        }
                    }
                }

                override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

                override fun onException(exception: java.io.IOException?) {
                    closeProcess()
                }

                private fun closeProcess() {
                    if (!closed.compareAndSet(false, true)) return
                    val p = proc
                    proc = null
                    if (p != null) {
                        runCatching { p.outputStream.close() }
                        runCatching { p.destroy() }
                        try {
                            if (!p.waitFor(600, TimeUnit.MILLISECONDS)) {
                                p.destroyForcibly()
                            }
                        } catch (_: Exception) {
                        }
                    }
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
        val file = userPath(path) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
        val delegate = payload.optString("delegate", "none")
        val threads = payload.optInt("num_threads", 2)
        return try {
            val info = tflite.load(name, file, delegate, threads)
            jsonResponse(JSONObject(info))
        } catch (ex: Exception) {
            jsonError(Response.Status.BAD_REQUEST, "model_load_failed", JSONObject().put("detail", ex.message ?: ""))
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
        val file = userPath(path) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
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
        val outFile = userPath(outPath) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
        return try {
            VisionImageIo.encodeRgbaToFile(frame, format, outFile, jpegQuality)
            jsonResponse(JSONObject().put("status", "ok").put("saved", true).put("path", outFile.absolutePath))
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
        if (BuildConfig.DEBUG) {
            return jsonError(
                Response.Status.BAD_REQUEST,
                "debug_build_update_disabled",
                JSONObject().put("message", "Auto update/install is disabled for debug builds.")
            )
        }
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

    private fun handleSshExec(payload: JSONObject): Response {
        val host = payload.optString("host", "").trim()
        if (host.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "host_required")
        val user = payload.optString("user", "").trim()
        val port = payload.optInt("port", 22).coerceIn(1, 65535)
        val noTimeout = payload.optBoolean("no_timeout", false)
        val timeoutS = if (noTimeout) null else payload.optDouble("timeout_s", 30.0).coerceIn(2.0, 300.0)
        val maxOutputBytes = payload.optInt("max_output_bytes", 64 * 1024).coerceIn(4 * 1024, 512 * 1024)
        val pty = payload.optBoolean("pty", false)
        val acceptNewHostKey = payload.optBoolean("accept_new_host_key", true)

        val argv = payload.optJSONArray("argv")
        val remoteArgs = mutableListOf<String>()
        if (argv != null) {
            for (i in 0 until argv.length()) {
                val v = argv.optString(i, "").trim()
                if (v.isNotBlank()) remoteArgs.add(v)
            }
        } else {
            val command = payload.optString("command", "").trim()
            if (command.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "command_required")
            // dbclient appends remote argv without shell quoting. If we pass ["sh","-lc","echo foo"],
            // "echo" becomes the -c script and "foo" becomes $0. Pass one fully-quoted command string instead.
            remoteArgs.add("sh -lc ${shellSingleQuote(command)}")
        }
        if (remoteArgs.isEmpty()) return jsonError(Response.Status.BAD_REQUEST, "command_required")

        val dbclient = findDbclientBinary() ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "ssh_client_missing")
        val args = mutableListOf(dbclient.absolutePath)
        if (acceptNewHostKey) args.add("-y")
        if (pty) args.add("-t")
        if (port != 22) {
            args.add("-p")
            args.add(port.toString())
        }
        args.add(sshTarget(user, host))
        args.addAll(remoteArgs)

        val result = runProcessCapture(args, timeoutS, maxOutputBytes)
        val out = JSONObject()
            .put("status", if (result.timedOut) "timeout" else "ok")
            .put("target", sshTarget(user, host))
            .put("port", port)
            .put("argv", org.json.JSONArray(args))
            .put("exit_code", result.exitCode ?: JSONObject.NULL)
            .put("timed_out", result.timedOut)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("truncated", result.truncated)
        return if (result.timedOut) {
            newFixedLengthResponse(Response.Status.REQUEST_TIMEOUT, "application/json", out.toString())
        } else {
            jsonResponse(out)
        }
    }

    private fun handleSshScp(payload: JSONObject): Response {
        val direction = payload.optString("direction", "").trim().lowercase(Locale.US)
        if (direction != "upload" && direction != "download") {
            return jsonError(Response.Status.BAD_REQUEST, "direction_required")
        }
        val host = payload.optString("host", "").trim()
        if (host.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "host_required")
        val user = payload.optString("user", "").trim()
        val port = payload.optInt("port", 22).coerceIn(1, 65535)
        val remotePath = payload.optString("remote_path", "").trim()
        if (remotePath.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "remote_path_required")
        val localPath = payload.optString("local_path", "").trim()
        if (localPath.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "local_path_required")
        val recursive = payload.optBoolean("recursive", false)
        val noTimeout = payload.optBoolean("no_timeout", false)
        val timeoutS = if (noTimeout) null else payload.optDouble("timeout_s", 90.0).coerceIn(2.0, 600.0)
        val maxOutputBytes = payload.optInt("max_output_bytes", 64 * 1024).coerceIn(4 * 1024, 512 * 1024)

        val localRoot = File(context.filesDir, "user").canonicalFile
        val localFile = resolveUserPath(localRoot, localPath) ?: return jsonError(Response.Status.BAD_REQUEST, "path_outside_user_dir")
        if (direction == "upload" && !localFile.exists()) {
            return jsonError(Response.Status.NOT_FOUND, "local_path_not_found")
        }
        if (direction == "upload" && localFile.isDirectory && !recursive) {
            return jsonError(Response.Status.BAD_REQUEST, "recursive_required_for_directory")
        }
        if (direction == "download") {
            runCatching { localFile.parentFile?.mkdirs() }
        }

        val scp = findScpBinary() ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "scp_client_missing")
        val dbclientWrapper = ensureDbclientWrapper() ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "ssh_wrapper_missing")

        val args = mutableListOf(scp.absolutePath, "-S", dbclientWrapper.absolutePath, "-y")
        if (port != 22) {
            args.add("-P")
            args.add(port.toString())
        }
        if (recursive) args.add("-r")

        val remote = "${sshTarget(user, host)}:$remotePath"
        when (direction) {
            "upload" -> {
                args.add(localFile.absolutePath)
                args.add(remote)
            }
            "download" -> {
                args.add(remote)
                args.add(localFile.absolutePath)
            }
        }

        val result = runProcessCapture(args, timeoutS, maxOutputBytes)
        val out = JSONObject()
            .put("status", if (result.timedOut) "timeout" else "ok")
            .put("direction", direction)
            .put("target", sshTarget(user, host))
            .put("port", port)
            .put("local_path", localPath)
            .put("remote_path", remotePath)
            .put("exit_code", result.exitCode ?: JSONObject.NULL)
            .put("timed_out", result.timedOut)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("truncated", result.truncated)
        return if (result.timedOut) {
            newFixedLengthResponse(Response.Status.REQUEST_TIMEOUT, "application/json", out.toString())
        } else {
            jsonResponse(out)
        }
    }

    private fun findDbclientBinary(): File? {
        val lib = File(context.applicationInfo.nativeLibraryDir, "libdbclient.so")
        return if (lib.exists()) lib else null
    }

    private fun findScpBinary(): File? {
        val lib = File(context.applicationInfo.nativeLibraryDir, "libscp.so")
        return if (lib.exists()) lib else null
    }

    private fun ensureDbclientWrapper(): File? {
        val wrapper = File(File(context.filesDir, "bin"), "methings-dbclient")
        return try {
            if (!wrapper.exists() || wrapper.length() == 0L) {
                wrapper.parentFile?.mkdirs()
                wrapper.writeText(
                    "#!/system/bin/sh\n" +
                        "exec \"${'$'}METHINGS_NATIVELIB/libdbclient.so\" -y \"${'$'}@\"\n"
                )
            }
            wrapper.setExecutable(true, true)
            wrapper.setReadable(true, true)
            wrapper.setWritable(true, true)
            wrapper
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to ensure dbclient wrapper", ex)
            null
        }
    }

    private fun sshTarget(user: String, host: String): String {
        return if (user.isBlank()) host else "$user@$host"
    }

    private fun shellSingleQuote(v: String): String {
        return "'" + v.replace("'", "'\"'\"'") + "'"
    }

    private fun buildSshProcess(argv: List<String>): ProcessBuilder {
        val pb = ProcessBuilder(argv)
        val userHome = File(context.filesDir, "user")
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binDir = File(context.filesDir, "bin")
        pb.directory(userHome)
        pb.environment()["HOME"] = userHome.absolutePath
        pb.environment()["USER"] = "methings"
        pb.environment()["METHINGS_NATIVELIB"] = nativeLibDir
        pb.environment()["METHINGS_BINDIR"] = binDir.absolutePath
        val existingPath = pb.environment()["PATH"] ?: "/usr/bin:/bin"
        pb.environment()["PATH"] = "${binDir.absolutePath}:$nativeLibDir:$existingPath"
        return pb
    }

    private data class ProcessCaptureResult(
        val exitCode: Int?,
        val timedOut: Boolean,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean
    )

    private fun runProcessCapture(argv: List<String>, timeoutS: Double?, maxOutputBytes: Int): ProcessCaptureResult {
        return try {
            val proc = buildSshProcess(argv).start()
            val stdoutBuf = ByteArrayOutputStream()
            val stderrBuf = ByteArrayOutputStream()
            val stdoutState = AtomicBoolean(false)
            val stderrState = AtomicBoolean(false)
            val stdoutThread = Thread {
                copyLimited(proc.inputStream, stdoutBuf, maxOutputBytes, stdoutState)
            }
            val stderrThread = Thread {
                copyLimited(proc.errorStream, stderrBuf, maxOutputBytes, stderrState)
            }
            stdoutThread.start()
            stderrThread.start()

            val finished = if (timeoutS == null) {
                proc.waitFor()
                true
            } else {
                proc.waitFor((timeoutS * 1000.0).toLong(), TimeUnit.MILLISECONDS)
            }
            if (!finished) {
                runCatching { proc.destroy() }
                if (!proc.waitFor(300, TimeUnit.MILLISECONDS)) {
                    runCatching { proc.destroyForcibly() }
                }
            }

            runCatching { stdoutThread.join(600) }
            runCatching { stderrThread.join(600) }
            ProcessCaptureResult(
                exitCode = if (finished) runCatching { proc.exitValue() }.getOrNull() else null,
                timedOut = !finished,
                stdout = stdoutBuf.toString(StandardCharsets.UTF_8.name()),
                stderr = stderrBuf.toString(StandardCharsets.UTF_8.name()),
                truncated = stdoutState.get() || stderrState.get()
            )
        } catch (ex: Exception) {
            ProcessCaptureResult(
                exitCode = -1,
                timedOut = false,
                stdout = "",
                stderr = ex.message ?: "process_failed",
                truncated = false
            )
        }
    }

    private fun copyLimited(
        input: java.io.InputStream,
        out: ByteArrayOutputStream,
        maxBytes: Int,
        truncated: AtomicBoolean
    ) {
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = try {
                input.read(buf)
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) break
            val remaining = maxBytes - total
            if (remaining <= 0) {
                truncated.set(true)
                continue
            }
            if (n <= remaining) {
                out.write(buf, 0, n)
                total += n
            } else {
                out.write(buf, 0, remaining)
                total += remaining
                truncated.set(true)
            }
        }
    }

    private fun startSshWsPump(
        input: java.io.InputStream,
        streamType: String,
        ws: NanoWSD.WebSocket,
        closed: AtomicBoolean
    ) {
        Thread {
            val buf = ByteArray(4096)
            while (!closed.get()) {
                val n = try {
                    input.read(buf)
                } catch (_: Exception) {
                    -1
                }
                if (n <= 0) break
                val chunk = ByteArray(n)
                java.lang.System.arraycopy(buf, 0, chunk, 0, n)
                val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                runCatching {
                    ws.send(
                        JSONObject()
                            .put("type", streamType)
                            .put("data_b64", b64)
                            .toString()
                    )
                }
            }
        }.start()
    }

    private fun handleShellExec(payload: JSONObject): Response {
        val cmd = payload.optString("cmd")
        val args = payload.optString("args", "")
        val cwd = payload.optString("cwd", "")
        if (cmd != "python" && cmd != "pip" && cmd != "curl") {
            return jsonError(Response.Status.FORBIDDEN, "command_not_allowed")
        }

        // Always proxy to the embedded Python worker.
        // Executing the CLI binary directly (ProcessBuilder + libmethingspy.so) can crash when
        // Android/JNI integration modules (pyjnius) are imported without a proper JVM context.
        if (runtimeManager.getStatus() != "ok") {
            runtimeManager.startWorker()
            waitForPythonHealth(5000)
        }
        val proxied = proxyShellExecToWorker(cmd, args, cwd)
        if (proxied != null) {
            return proxied
        }
        return jsonError(Response.Status.SERVICE_UNAVAILABLE, "python_unavailable")
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
        val wheelhouse = WheelhousePaths.forCurrentAbi(context)?.also { it.ensureDirs() }
            ?: return jsonError(Response.Status.INTERNAL_ERROR, "wheelhouse_unavailable")

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

        val detail = "pip download (to wheelhouse): " + pkgs.joinToString(" ").take(180)
        val perm = ensurePipPermission(session, payload, capability = "pip.download", detail = detail)
        if (!perm.first) return perm.second!!

        val args = mutableListOf(
            "download",
            "--disable-pip-version-check",
            "--no-input",
            "--dest",
            wheelhouse.user.absolutePath
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
            waitForPythonHealth(5000)
        }
        val proxied = proxyShellExecToWorker("pip", args.joinToString(" "), "")
        if (proxied != null) return proxied
        return jsonError(Response.Status.SERVICE_UNAVAILABLE, "python_unavailable")
    }

    private fun handlePipInstall(session: IHTTPSession, payload: JSONObject): Response {
        val wheelhouse = WheelhousePaths.forCurrentAbi(context)?.also { it.ensureDirs() }
            ?: return jsonError(Response.Status.INTERNAL_ERROR, "wheelhouse_unavailable")

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
        args.addAll(wheelhouse.findLinksArgs())
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
            waitForPythonHealth(5000)
        }
        val proxied = proxyShellExecToWorker("pip", args.joinToString(" "), "")
        if (proxied != null) return proxied
        return jsonError(Response.Status.SERVICE_UNAVAILABLE, "python_unavailable")
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
        // ${file:rel_path[:base64|text]} -> read from user root (base64 or utf-8 text)
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
                val rel = parts[1].trim().trimStart('/')
                val mode = if (parts.size >= 3) parts[2].trim().lowercase() else "base64"
                val f = userPath(rel) ?: return@replace ""
                if (!f.exists() || !f.isFile) return@replace ""
                exp.usedFiles.add(rel)
                return@replace try {
                    val bytes: ByteArray = when (mode) {
                        "text" -> f.readBytes()
                        "base64_raw" -> f.readBytes()
                        else -> {
                            // Default: base64. For common image types, downscale/compress to reduce upload size.
                            val ext = f.name.substringAfterLast('.', "").lowercase()
                            val isImg = ext in setOf("jpg", "jpeg", "png", "webp")
                            val enabled = cloudPrefs.getBoolean("image_resize_enabled", true)
                            if (mode == "base64" && enabled && isImg) {
                                downscaleImageToJpeg(
                                    f,
                                    maxDimPx = cloudPrefs.getInt("image_resize_max_dim_px", 512).coerceIn(64, 4096),
                                    jpegQuality = cloudPrefs.getInt("image_resize_jpeg_quality", 70).coerceIn(30, 95)
                                ) ?: f.readBytes()
                            } else {
                                f.readBytes()
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

        val autoMb = cloudPrefs.getFloat("auto_upload_no_confirm_mb", 1.0f).toDouble().coerceIn(0.0, 25.0)
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
        val minBytesPerSFromPrefs = cloudPrefs.getFloat("min_transfer_kbps", 0.0f).toDouble() * 1024.0
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

    private fun resolvePythonBinary(): File? {
        // Prefer native lib (has correct SELinux context for execution)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativePython = File(nativeDir, "libmethingspy.so")
        if (nativePython.exists()) {
            return nativePython
        }
        // Wrapper script in bin/
        val binPython = File(context.filesDir, "bin/python3")
        if (binPython.exists() && binPython.canExecute()) {
            return binPython
        }
        return null
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
        query: String? = null
    ): Response? {
        return try {
            val fullPath = if (!query.isNullOrBlank()) "$path?$query" else path
            val url = java.net.URL("http://127.0.0.1:8776$fullPath")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 1500
            conn.readTimeout = 5000
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

    // --- Cloud request prefs ---
    private val cloudPrefs by lazy {
        context.getSharedPreferences("cloud_prefs", Context.MODE_PRIVATE)
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

    private fun buildWorkerSystemPrompt(): String {
        // Passed to the Python worker brain (tool-calling runtime).
        //
        // Keep this short. Detailed operational rules live in user-root docs so we can evolve them
        // without bloating the system prompt.
        return listOf(
            "You are a senior Android device programming professional (systems-level engineer). ",
            "You are expected to already know Android/USB/BLE/Camera/GPS basics and practical debugging techniques. ",
            "You are \"methings\" running on an Android device. ",
            "Your goal is to produce the user's requested outcome (artifact/state change), not to narrate steps. ",
            "You MUST use function tools for any real action (no pretending). ",
            "If you can satisfy a request by writing code/scripts, do it and execute them via tools. ",
            "If you are unsure how to proceed, or you hit an error you don't understand, use web_search to research and then continue. ",
            "If a needed device capability is not exposed by tools, say so and propose the smallest code change to add it. ",
            "Do not delegate implementable steps back to the user (implementation/builds/api calls/log inspection); do them yourself when possible. ",
            "User-root docs (`AGENTS.md`, `TOOLS.md`) are auto-injected into your context and reloaded if they change on disk; do not repeatedly read them via filesystem tools unless the user explicitly asks. ",
            "Prefer consulting the provided user-root docs under `docs/` and `examples/` (camera/usb/vision) before guessing tool names. ",
            "For files: use filesystem tools under the user root (not shell `ls`/`cat`). ",
            "For execution: use run_python/run_pip/run_curl only. ",
            "For cloud calls: prefer the configured Brain provider (Settings -> Brain). If Brain is not configured or has no API key, ask the user to configure it, then retry. ",
            "Device/resource access requires explicit user approval; if the user request implies consent, trigger the tool call immediately to surface the permission prompt (no pre-negotiation). If permission_required, ask the user to approve in the app UI and then retry automatically (approvals are remembered for the session). ",
            "Keep responses concise: do the work first, then summarize and include relevant tool output snippets."
        ).joinToString("")
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
        if (vendor == "anthropic") {
            return jsonError(Response.Status.BAD_REQUEST, "agent_vendor_not_supported")
        }

        if (runtimeManager.getStatus() != "ok") {
            runtimeManager.startWorker()
            waitForPythonHealth(5000)
        }

        val credentialBody = JSONObject().put("value", apiKey).toString()
        val setCred = proxyWorkerRequest("/vault/credentials/openai_api_key", "POST", credentialBody)
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "python_unavailable")
        if (setCred.status != Response.Status.OK) {
            return jsonError(Response.Status.INTERNAL_ERROR, "worker_credential_set_failed")
        }

        val providerUrl = if (vendor == "openai") {
            if (baseUrl.endsWith("/responses")) baseUrl else "$baseUrl/responses"
        } else {
            if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
        }
        val cfgBody = JSONObject()
            .put("enabled", true)
            .put("auto_start", true)
            .put("tool_policy", "required")
            .put("provider_url", providerUrl)
            .put("model", model)
            .put("api_key_credential", "openai_api_key")
            .put("system_prompt", buildWorkerSystemPrompt())
            .toString()
        val setCfg = proxyWorkerRequest("/brain/config", "POST", cfgBody)
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "python_unavailable")
        if (setCfg.status != Response.Status.OK) {
            return jsonError(Response.Status.INTERNAL_ERROR, "worker_config_set_failed")
        }

        val startResp = proxyWorkerRequest("/brain/start", "POST", "{}")
            ?: return jsonError(Response.Status.SERVICE_UNAVAILABLE, "python_unavailable")
        if (startResp.status != Response.Status.OK) {
            return jsonError(Response.Status.INTERNAL_ERROR, "worker_start_failed")
        }

        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("provider_url", providerUrl)
                .put("model", model)
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

    private fun waitForPythonHealth(timeoutMs: Long) {
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
                val file = userPath(streamRel)
                    ?: return jsonError(Response.Status.BAD_REQUEST, "stream_path_outside_user_dir")
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

    private data class ParsedSshPublicKey(
        val type: String,
        val b64: String,
        val comment: String?
    ) {
        fun canonicalNoComment(): String = "$type $b64"
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

    private fun syncAuthorizedKeys() {
        val userHome = File(context.filesDir, "user")
        val sshDir = File(userHome, ".ssh")
        sshDir.mkdirs()
        val authFile = File(sshDir, "authorized_keys")
        val now = System.currentTimeMillis()
        val active = sshKeyStore.listActive(now)
        val lines = active.mapNotNull { row ->
            val key = row.key.trim()
            if (key.isBlank()) return@mapNotNull null
            val label = sanitizeSshKeyLabel(row.label ?: "")
            if (label != null) "$key $label" else key
        }
        authFile.writeText(lines.joinToString("\n") + if (lines.isNotEmpty()) "\n" else "")
        authKeysLastMtime = authFile.lastModified()
    }

    private fun runHousekeepingOnce() {
        runCatching {
            val now = System.currentTimeMillis()
            val pruned = sshKeyStore.pruneExpired(now)
            if (pruned > 0) {
                Log.i(TAG, "Housekeeping pruned expired SSH keys: $pruned")
            }
            cleanupExpiredMeSyncTransfers()
            cleanupExpiredMeSyncV3Tickets()
            detectExternalAuthorizedKeysEdit()
            syncAuthorizedKeys()
            if (pruned > 0) {
                sshdManager.restartIfRunning()
            }
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
            handleMeMeDiscoveredAlerts(summary.discovered)
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

    private fun handleMeMeDiscoveredAlerts(discovered: List<JSONObject>) {
        val now = System.currentTimeMillis()
        discovered.forEach { peer ->
            val did = peer.optString("device_id", "").trim()
            if (did.isBlank()) return@forEach
            val last = meMeDiscoveryAlertLastAt[did] ?: 0L
            if (now - last < 60_000L) return@forEach
            meMeDiscoveryAlertLastAt[did] = now
            notifyBrainEvent(
                name = "me.me.device.discovered",
                payload = JSONObject()
                    .put("session_id", "default")
                    .put("source", "me_me.scan")
                    .put("device_id", did)
                    .put("device_name", peer.optString("device_name", "").trim())
                    .put("summary", "Nearby device discovered: $did"),
                priority = "low",
                interruptPolicy = "never",
                coalesceKey = "me_me_device_discovered_$did",
                coalesceWindowMs = 60_000L
            )
        }
    }

    private fun importAuthorizedKeysFromFile() {
        val userHome = File(context.filesDir, "user")
        val authFile = File(File(userHome, ".ssh"), "authorized_keys")
        if (!authFile.exists()) return
        val text = runCatching { authFile.readText() }.getOrNull() ?: return
        val lines = text.split("\n")
        for (raw in lines) {
            val line = raw.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#")) continue
            val parsed = parseAuthorizedKeysLine(line) ?: continue
            val label = sanitizeSshKeyLabel(parsed.comment ?: "")
            // Merge into DB without clobbering existing metadata.
            sshKeyStore.upsertMerge(parsed.canonicalNoComment(), label, expiresAt = null)
        }
    }

    /**
     * Detect if authorized_keys was edited externally (e.g. via SSH).
     * If the file's mtime changed since we last wrote it, import any new keys,
     * re-sync the file, and restart dropbear so the changes take effect.
     */
    private fun detectExternalAuthorizedKeysEdit() {
        val authFile = File(File(context.filesDir, "user/.ssh"), "authorized_keys")
        if (!authFile.exists()) return
        val mtime = authFile.lastModified()
        if (authKeysLastMtime != 0L && mtime != authKeysLastMtime) {
            Log.i(TAG, "authorized_keys changed externally (mtime $authKeysLastMtime -> $mtime); importing")
            importAuthorizedKeysFromFile()
            syncAuthorizedKeys()
            sshdManager.restartIfRunning()
        }
        authKeysLastMtime = mtime
    }

    private fun parseAuthorizedKeysLine(raw: String): ParsedSshPublicKey? {
        // authorized_keys can contain options before the key type:
        //   from="1.2.3.4" ssh-ed25519 AAAA... comment
        // We only import keys (ignore options). Prefer the first token that matches a key type.
        val line = raw.trim().replace("\r", " ").replace("\n", " ").trim()
        if (line.isBlank()) return null
        val toks = line.split(Regex("\\s+"))
        if (toks.size < 2) return null

        val allowed = setOf(
            "ssh-ed25519",
            "ssh-rsa",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521",
            "sk-ssh-ed25519@openssh.com",
            "sk-ecdsa-sha2-nistp256@openssh.com"
        )
        var idx = -1
        for (i in 0 until toks.size - 1) {
            val t = toks[i].trim()
            if (allowed.contains(t.lowercase(Locale.US))) {
                idx = i
                break
            }
        }
        if (idx < 0 || idx + 1 >= toks.size) return null
        val type = toks[idx].trim()
        val b64 = toks[idx + 1].trim()
        val comment = if (idx + 2 < toks.size) toks.subList(idx + 2, toks.size).joinToString(" ").trim().ifBlank { null } else null

        try {
            val decoded = Base64.decode(b64, Base64.DEFAULT)
            if (decoded.isEmpty()) return null
        } catch (_: Exception) {
            return null
        }
        return ParsedSshPublicKey(type = type, b64 = b64, comment = comment)
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
        val connections = org.json.JSONArray(
            meMeConnections.values
                .sortedByDescending { it.connectedAt }
                .map { it.toJson() }
        )
        val pending = org.json.JSONArray(
            meMeConnectIntents.values
                .filter { !it.accepted }
                .sortedByDescending { it.createdAt }
                .map { it.toJson(includeToken = false) }
        )
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("self", self)
                .put("connected_count", connections.length())
                .put("discovered_count", runtime.optInt("discovered_count", 0))
                .put("pending_request_count", pending.length())
                .put("pending_requests", pending)
                .put("connections", connections)
                .put("discovered", runtime.optJSONArray("discovered") ?: org.json.JSONArray())
                .put("advertising", runtime.optJSONObject("advertising") ?: JSONObject())
                .put("relay", relayCfg.toJson(includeSecrets = false))
                .put("relay_event_queue_count", relayQueueCount)
                .put("last_scan_at", runtime.opt("last_scan_at"))
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
        val summary = meMeDiscovery.scan(currentMeMeDiscoveryConfig(), timeoutMs)
        handleMeMeDiscoveredAlerts(summary.discovered)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("started_at", summary.startedAt)
                .put("timeout_ms", summary.timeoutMs)
                .put("discovered", org.json.JSONArray(summary.discovered))
                .put("warnings", org.json.JSONArray(summary.warnings))
        )
    }

    private fun handleMeMeConnect(payload: JSONObject): Response {
        cleanupExpiredMeMeState()
        val cfg = currentMeMeConfig()
        val targetDeviceId = payload.optString("target_device_id", "").trim()
        if (targetDeviceId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "target_device_id_required")
        if (targetDeviceId == cfg.deviceId) return jsonError(Response.Status.BAD_REQUEST, "cannot_connect_self")
        if (cfg.blockedDevices.contains(targetDeviceId)) {
            return jsonError(Response.Status.FORBIDDEN, "target_blocked")
        }
        if (cfg.allowedDevices.isNotEmpty() && !cfg.allowedDevices.contains(targetDeviceId)) {
            return jsonError(Response.Status.FORBIDDEN, "target_not_allowed")
        }
        val existing = meMeConnections[targetDeviceId]
        if (existing != null) {
            return jsonResponse(JSONObject().put("status", "ok").put("connection", existing.toJson()).put("already_connected", true))
        }
        if (meMeConnections.size >= cfg.maxConnections) {
            return jsonError(Response.Status.FORBIDDEN, "max_connections_reached")
        }
        val discovered = meMeDiscovery.statusJson(currentMeMeDiscoveryConfig(cfg)).optJSONArray("discovered")
        val peer = findDiscoveredPeer(discovered, targetDeviceId)
        if (peer == null && !payload.optBoolean("allow_unknown", false)) {
            return jsonError(Response.Status.NOT_FOUND, "target_not_discovered")
        }
        val reqId = "mmr_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
        val createdAt = System.currentTimeMillis()
        val expiresAt = createdAt + cfg.connectionTimeoutSec * 1000L
        val sessionId = "mms_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
        val sessionKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sessionKeyB64 = Base64.encodeToString(sessionKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val acceptToken = encodeMeMeAcceptToken(reqId, cfg.deviceId, targetDeviceId, sessionId, sessionKeyB64, expiresAt)
        val req = MeMeConnectIntent(
            id = reqId,
            sourceDeviceId = cfg.deviceId,
            sourceDeviceName = cfg.deviceName,
            targetDeviceId = targetDeviceId,
            targetDeviceName = peer?.optString("device_name", "")?.trim().orEmpty(),
            createdAt = createdAt,
            expiresAt = expiresAt,
            accepted = false,
            acceptToken = acceptToken,
            methodHint = payload.optString("method", "").trim().ifBlank { "auto" },
            sessionId = sessionId,
            sessionKeyB64 = sessionKeyB64
        )
        meMeConnectIntents[req.id] = req
        return jsonResponse(
            JSONObject()
                .put("status", "pending")
                .put("request", req.toJson(includeToken = true))
                .put("note", "Share accept_token with target device, then call /me/me/accept on the target.")
        )
    }

    private fun handleMeMeAccept(payload: JSONObject): Response {
        cleanupExpiredMeMeState()
        val cfg = currentMeMeConfig()
        val token = payload.optString("accept_token", "").trim()
        val requestId = payload.optString("request_id", "").trim()
        val req = when {
            token.isNotBlank() -> {
                val parsed = decodeMeMeAcceptToken(token) ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_accept_token")
                if (parsed.expiresAt in 1..System.currentTimeMillis()) return jsonError(Response.Status.GONE, "request_expired")
                if (parsed.targetDeviceId != cfg.deviceId) return jsonError(Response.Status.FORBIDDEN, "token_target_mismatch")
                meMeConnectIntents[parsed.requestId] ?: MeMeConnectIntent(
                    id = parsed.requestId,
                    sourceDeviceId = parsed.sourceDeviceId,
                    sourceDeviceName = "",
                    targetDeviceId = parsed.targetDeviceId,
                    targetDeviceName = cfg.deviceName,
                    createdAt = System.currentTimeMillis(),
                    expiresAt = parsed.expiresAt,
                    accepted = false,
                    acceptToken = token,
                    methodHint = payload.optString("method", "").trim().ifBlank { "auto" },
                    sessionId = parsed.sessionId,
                    sessionKeyB64 = parsed.sessionKeyB64
                )
            }
            requestId.isNotBlank() -> meMeConnectIntents[requestId]
            else -> null
        } ?: return jsonError(Response.Status.NOT_FOUND, "request_not_found")

        if (cfg.blockedDevices.contains(req.sourceDeviceId)) return jsonError(Response.Status.FORBIDDEN, "source_blocked")
        if (cfg.allowedDevices.isNotEmpty() && !cfg.allowedDevices.contains(req.sourceDeviceId)) {
            return jsonError(Response.Status.FORBIDDEN, "source_not_allowed")
        }
        if (meMeConnections.size >= cfg.maxConnections && !meMeConnections.containsKey(req.sourceDeviceId)) {
            return jsonError(Response.Status.FORBIDDEN, "max_connections_reached")
        }
        val connId = "mmc_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
        val conn = MeMeConnection(
            id = connId,
            peerDeviceId = req.sourceDeviceId,
            peerDeviceName = req.sourceDeviceName,
            method = req.methodHint,
            connectedAt = System.currentTimeMillis(),
            state = "connected",
            role = "acceptor",
            sessionId = req.sessionId,
            sessionKeyB64 = req.sessionKeyB64
        )
        meMeConnections[conn.peerDeviceId] = conn
        meMeReconnectAttemptAt.remove(conn.peerDeviceId)
        meMeConnectIntents[req.id] = req.copy(accepted = true)
        val autoConfirm = runAutoConfirmMeMeAccept(
            req = req,
            payload = payload,
            acceptedByName = cfg.deviceName
        )
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("ack_token", req.acceptToken)
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

        val conn = MeMeConnection(
            id = "mmc_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}",
            peerDeviceId = parsed.targetDeviceId,
            peerDeviceName = payload.optString("peer_device_name", "").trim(),
            method = payload.optString("method", "auto").trim().ifBlank { "auto" },
            connectedAt = System.currentTimeMillis(),
            state = "connected",
            role = "initiator",
            sessionId = parsed.sessionId,
            sessionKeyB64 = parsed.sessionKeyB64
        )
        meMeConnections[conn.peerDeviceId] = conn
        meMeReconnectAttemptAt.remove(conn.peerDeviceId)
        return jsonResponse(JSONObject().put("status", "ok").put("connection", conn.toJson()))
    }

    private fun runAutoConfirmMeMeAccept(
        req: MeMeConnectIntent,
        payload: JSONObject,
        acceptedByName: String
    ): JSONObject {
        val sourceHostOverride = payload.optString("source_host", "").trim()
        val sourcePortOverride = payload.optInt("source_port", 0).takeIf { it in 1..65535 }
        val route = resolveMeMePeerRoute(req.sourceDeviceId)
        val host = sourceHostOverride.ifBlank { route?.host.orEmpty() }
        val port = sourcePortOverride ?: route?.port ?: ME_ME_LAN_PORT
        val confirmPayload = JSONObject()
            .put("accept_token", req.acceptToken)
            .put("peer_device_name", acceptedByName)
            .put("method", req.methodHint)
        val delivery = if (host.isNotBlank()) {
            postMeMeLanJson(host, port, "/me/me/connect/confirm", confirmPayload).put("transport", "lan")
        } else {
            deliverMeMePayload(
                peerDeviceId = req.sourceDeviceId,
                transportHint = "ble",
                timeoutMs = 12_000L,
                lanPath = "/me/me/connect/confirm",
                lanPayload = confirmPayload,
                bleKind = "connect_confirm"
            )
        }
        return JSONObject()
            .put("attempted", true)
            .put("transport", delivery.optString("transport", if (host.isNotBlank()) "lan" else "ble"))
            .put("target_device_id", req.sourceDeviceId)
            .put("confirmed", delivery.optBoolean("ok", false))
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
        return jsonResponse(JSONObject().put("status", "ok").put("disconnected", true).put("connection", removed.toJson()))
    }

    private fun handleMeMeMessageSend(payload: JSONObject): Response {
        cleanupExpiredMeMeState()
        val peerDeviceId = payload.optString("peer_device_id", "").trim()
        if (peerDeviceId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "peer_device_id_required")
        val type = payload.optString("type", "message").trim().ifBlank { "message" }
        val send = sendMeMeEncryptedMessage(
            peerDeviceId = peerDeviceId,
            type = type,
            payloadValue = payload.opt("payload") ?: JSONObject.NULL,
            transportHint = payload.optString("transport", ""),
            timeoutMs = payload.optLong("timeout_ms", 12_000L)
        )
        val delivery = formatMeMeDelivery(peerDeviceId = peerDeviceId, type = type, send = send)
        if (!delivery.optBoolean("ok", false)) return jsonError(Response.Status.SERVICE_UNAVAILABLE, "peer_delivery_failed", delivery)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("delivered", delivery.optBoolean("delivered", false))
                .put("transport", delivery.optString("transport", "unknown"))
                .put("peer_device_id", peerDeviceId)
                .put("result", delivery.optJSONObject("result") ?: JSONObject())
                .put("delivery", delivery)
        )
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
        timeoutMs: Long = 12_000L
    ): JSONObject {
        val conn = meMeConnections[peerDeviceId] ?: return JSONObject().put("ok", false).put("error", "connection_not_found")
        if (conn.state != "connected") return JSONObject().put("ok", false).put("error", "connection_not_ready")
        val plaintext = JSONObject()
            .put("type", type.ifBlank { "message" })
            .put("payload", payloadValue ?: JSONObject.NULL)
            .put("sent_at", System.currentTimeMillis())
            .put("from_device_id", currentMeMeConfig().deviceId)
        val encrypted = encryptMeMePayload(conn.sessionKeyB64, plaintext)
            ?: return JSONObject().put("ok", false).put("error", "encrypt_failed")
        val req = JSONObject()
            .put("session_id", conn.sessionId)
            .put("from_device_id", currentMeMeConfig().deviceId)
            .put("to_device_id", peerDeviceId)
            .put("iv_b64", encrypted.ivB64)
            .put("ciphertext_b64", encrypted.ciphertextB64)
        return deliverMeMePayload(
            peerDeviceId = peerDeviceId,
            transportHint = transportHint,
            timeoutMs = timeoutMs,
            lanPath = "/me/me/data/ingest",
            lanPayload = req,
            bleKind = "data_ingest"
        )
    }

    private fun deliverMeMePayload(
        peerDeviceId: String,
        transportHint: String,
        timeoutMs: Long,
        lanPath: String,
        lanPayload: JSONObject,
        bleKind: String
    ): JSONObject {
        val conn = meMeConnections[peerDeviceId] ?: return JSONObject().put("ok", false).put("error", "connection_not_found")
        val route = resolveMeMePeerRoute(peerDeviceId)
        val normalizedHint = transportHint.trim().lowercase(Locale.US)
        val shouldUseBle = when (normalizedHint) {
            "ble" -> true
            "lan", "wifi" -> false
            else -> conn.method.trim().lowercase(Locale.US) == "ble" || (route?.host.isNullOrBlank() && (route?.hasBle ?: false))
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
        val host = route?.host.orEmpty()
        val port = route?.port ?: ME_ME_LAN_PORT
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
                .put("relay", cfg.toJson(includeSecrets = false))
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
                .put("config", currentMeMeRelayConfig().toJson(includeSecrets = false))
        )
    }

    private fun handleMeMeRelayConfigSet(payload: JSONObject): Response {
        val prev = currentMeMeRelayConfig()
        val next = MeMeRelayConfig(
            enabled = if (payload.has("enabled")) payload.optBoolean("enabled", prev.enabled) else prev.enabled,
            gatewayBaseUrl = normalizeMeMeRelayBaseUrl(payload.optString("gateway_base_url", prev.gatewayBaseUrl)),
            provider = payload.optString("provider", prev.provider).trim().ifBlank { prev.provider },
            routeTokenTtlSec = payload.optInt("route_token_ttl_sec", prev.routeTokenTtlSec).coerceIn(30, 86_400),
            devicePushToken = payload.optString("device_push_token", prev.devicePushToken).trim(),
            gatewayAdminSecret = prev.gatewayAdminSecret
        )
        val adminSecretOverride = if (payload.has("gateway_admin_secret")) payload.optString("gateway_admin_secret", "").trim() else null
        val clearAdminSecret = payload.optBoolean("clear_gateway_admin_secret", false)
        saveMeMeRelayConfig(next, adminSecretOverride = adminSecretOverride, clearAdminSecret = clearAdminSecret)
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("config", currentMeMeRelayConfig().toJson(includeSecrets = false))
        )
    }

    private fun handleMeMeRelayRegister(payload: JSONObject): Response {
        val cfg = currentMeMeRelayConfig()
        val pushToken = payload.optString("device_push_token", cfg.devicePushToken).trim()
        if (pushToken.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "device_push_token_required")
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
        val saved = cfg.copy(devicePushToken = pushToken)
        saveMeMeRelayConfig(saved, adminSecretOverride = null, clearAdminSecret = false)
        meMeRelayLastRegisterAtMs = System.currentTimeMillis()
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("registered", true)
                .put("relay_result", result)
                .put("config", currentMeMeRelayConfig().toJson(includeSecrets = false))
        )
    }

    private fun handleMeMeRelayNotify(payload: JSONObject): Response {
        val cfg = currentMeMeRelayConfig()
        if (!cfg.enabled) return jsonError(Response.Status.FORBIDDEN, "relay_disabled")
        val adminSecret = cfg.gatewayAdminSecret
        if (adminSecret.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "gateway_admin_secret_missing")
        val targetDeviceId = payload.optString("target_device_id", "").trim()
        if (targetDeviceId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "target_device_id_required")
        val source = payload.optString("source", "me_me").trim().ifBlank { "me_me" }
        val provider = payload.optString("provider", cfg.provider).trim().ifBlank { cfg.provider }
        val ttlSec = payload.optInt("route_token_ttl_sec", cfg.routeTokenTtlSec).coerceIn(30, 86_400)
        val issueBody = JSONObject()
            .put("device_id", targetDeviceId)
            .put("source", source)
            .put("ttl_sec", ttlSec)
        val issue = postMeMeRelayJson(
            baseUrl = cfg.gatewayBaseUrl,
            path = "/route_token/issue",
            payload = issueBody,
            headers = mapOf("X-Admin-Secret" to adminSecret)
        )
        if (!issue.optBoolean("ok", false)) {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "relay_route_token_issue_failed", issue)
        }
        val routeToken = issue.optJSONObject("body")?.optString("route_token", "")?.trim().orEmpty()
        if (routeToken.isBlank()) {
            return jsonError(Response.Status.INTERNAL_ERROR, "relay_route_token_missing", issue)
        }
        val body = JSONObject()
            .put("event", payload.optString("event", "me_me_notify").trim().ifBlank { "me_me_notify" })
            .put("from_device_id", currentMeMeConfig().deviceId)
            .put("to_device_id", targetDeviceId)
            .put("sent_at", System.currentTimeMillis())
        if (payload.has("payload")) body.put("payload", payload.opt("payload"))
        if (payload.has("meta")) body.put("meta", payload.opt("meta"))
        val webhook = postMeMeRelayJson(
            baseUrl = cfg.gatewayBaseUrl,
            path = "/webhook/$routeToken?provider=${URLEncoder.encode(provider, StandardCharsets.UTF_8.name())}",
            payload = body
        )
        if (!webhook.optBoolean("ok", false)) {
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "relay_notify_failed", JSONObject().put("issue", issue).put("webhook", webhook))
        }
        meMeRelayLastNotifyAtMs = System.currentTimeMillis()
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("target_device_id", targetDeviceId)
                .put("provider", provider)
                .put("route_token_issued", true)
                .put("issue_result", issue)
                .put("webhook_result", webhook)
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
            name = alert.optString("name", "me.me.relay.event.received"),
            payload = JSONObject()
                .put("session_id", "default")
                .put("source", "me_me.relay")
                .put("event_id", payload.optString("event_id", "").trim())
                .put("relay_source", source)
                .put("relay_provider", alert.optString("provider", source))
                .put("relay_kind", alert.optString("kind", "generic.event"))
                .put("summary", alert.optString("summary", "Relay event received from $source")),
            priority = alert.optString("priority", "normal"),
            interruptPolicy = "turn_end",
            coalesceKey = alert.optString("coalesce_key", "me_me_relay_received"),
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
            val coalesce = "me_me_relay_discord_${channelId.ifBlank { "na" }}_${authorId.ifBlank { "na" }}"
            return JSONObject()
                .put("name", "me.me.relay.discord.message")
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
            val coalesce = "me_me_relay_slack_${channelId.ifBlank { "na" }}_${eventType}"
            return JSONObject()
                .put("name", "me.me.relay.slack.event")
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
            rawEvent.isNotBlank() && rawFromDevice.isNotBlank() -> "Relay $rawEvent from $rawFromDevice"
            rawEvent.isNotBlank() -> "Relay $rawEvent received"
            else -> "Relay event received from $topSource"
        }
        return JSONObject()
            .put("name", "me.me.relay.event.received")
            .put("provider", provider)
            .put("kind", kind)
            .put("summary", summary)
            .put("priority", "normal")
            .put("coalesce_key", "me_me_relay_received")
            .put("event_id", eventId)
    }

    private fun truncateRelaySummary(text: String, maxChars: Int): String {
        val compact = text.replace("\\s+".toRegex(), " ").trim()
        if (compact.length <= maxChars) return compact
        return compact.substring(0, maxChars.coerceAtLeast(1)) + "..."
    }

    private fun handleMeMeRelayEventsPull(payload: JSONObject): Response {
        val limit = payload.optInt("limit", 50).coerceIn(1, 500)
        val consume = payload.optBoolean("consume", true)
        val items = synchronized(meMeRelayEventsLock) {
            val take = meMeRelayEvents.takeLast(limit)
            if (consume && take.isNotEmpty()) {
                repeat(take.size) { meMeRelayEvents.removeAt(0) }
            }
            take
        }
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("items", JSONArray(items))
                .put("count", items.size)
        )
    }

    private fun handleMeMeRelayPullFromGateway(payload: JSONObject, force: Boolean): Response {
        val limit = payload.optInt("limit", 50).coerceIn(1, 200)
        val consume = payload.optBoolean("consume", true)
        return jsonResponse(maybePullMeMeRelayEventsFromGateway(force = force, limit = limit, consume = consume))
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
        if (sessionId.isBlank() || fromDeviceId.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "invalid_ingest_payload")
        val conn = meMeConnections[fromDeviceId] ?: return jsonError(Response.Status.NOT_FOUND, "connection_not_found")
        if (conn.sessionId != sessionId) return jsonError(Response.Status.FORBIDDEN, "session_mismatch")
        val iv = payload.optString("iv_b64", "").trim()
        val ct = payload.optString("ciphertext_b64", "").trim()
        val plain = decryptMeMePayload(conn.sessionKeyB64, iv, ct)
            ?: return jsonError(Response.Status.BAD_REQUEST, "decrypt_failed")
        val bucket = meMeInboundMessages.computeIfAbsent(fromDeviceId) { mutableListOf() }
        synchronized(bucket) {
            bucket.add(
                JSONObject()
                    .put("received_at", System.currentTimeMillis())
                    .put("source_ip", sourceIp)
                    .put("session_id", sessionId)
                    .put("message", plain)
            )
            while (bucket.size > 200) bucket.removeAt(0)
        }
        notifyBrainEvent(
            name = "me.me.message.received",
            payload = JSONObject()
                .put("session_id", "default")
                .put("source", "me_me.data")
                .put("from_device_id", fromDeviceId)
                .put("summary", "Message received from $fromDeviceId"),
            priority = "high",
            interruptPolicy = "turn_end",
            coalesceKey = "me_me_message_from_$fromDeviceId",
            coalesceWindowMs = 3_000L
        )
        return jsonResponse(JSONObject().put("status", "ok").put("stored", true))
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
        val it = meMeConnectIntents.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.expiresAt in 1..now) it.remove()
        }
    }

    private fun encodeMeMeAcceptToken(
        requestId: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        sessionId: String,
        sessionKeyB64: String,
        expiresAt: Long
    ): String {
        val payload = JSONObject()
            .put("v", 1)
            .put("request_id", requestId)
            .put("source_device_id", sourceDeviceId)
            .put("target_device_id", targetDeviceId)
            .put("session_id", sessionId)
            .put("session_key", sessionKeyB64)
            .put("expires_at", expiresAt)
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
                sessionKeyB64 = obj.optString("session_key", "").trim(),
                expiresAt = obj.optLong("expires_at", 0L)
            )
        }.getOrNull()?.takeIf {
            it.requestId.isNotBlank() && it.sourceDeviceId.isNotBlank() &&
                it.targetDeviceId.isNotBlank() && it.sessionId.isNotBlank() && it.sessionKeyB64.isNotBlank()
        }
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
            "connect_confirm" -> {
                val payload = obj.optJSONObject("payload") ?: obj
                handleMeMeConnectConfirm(payload)
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
            devicePushToken = (meMePrefs.getString("relay_device_push_token", "") ?: "").trim(),
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
            .putString("relay_device_push_token", cfg.devicePushToken.trim())
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
            .putStringSet("allowed_devices", cfg.allowedDevices.toSet())
            .putStringSet("blocked_devices", cfg.blockedDevices.toSet())
            .putBoolean("notify_on_connection", cfg.notifyOnConnection)
            .putBoolean("notify_on_disconnection", cfg.notifyOnDisconnection)
            .apply()
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
        val sshKeys = runCatching { sshKeyStore.listAll().size }.getOrElse { 0 }
        val hasAny = userFileCount > 0 || protectedDb.exists() || credentials > 0 || sshKeys > 0
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("has_existing_data", hasAny)
                .put("user_file_count", userFileCount)
                .put("has_protected_db", protectedDb.exists())
                .put("credential_count", credentials)
                .put("ssh_key_count", sshKeys)
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
                .put("transfer_id", transfer.id)
                .put("session_nonce", sessionNonce)
                .put("pair_code", pairCode)
                .put("expires_at", expiresAt)
                .put("source_name", sourceName)
                .put("caps", JSONObject().put("nearby", true).put("lan_fallback", true))
                .put("u", fallbackUrl)
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
        val st = sshdManager.status()
        // Ask permission only when me.sync would temporarily start SSHD by itself.
        return !st.enabled && !st.running
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
            cleanupMeSyncTempSshKey(tr.tempSshKeyFingerprint)
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
            if (source.hasSshSource()) {
                try {
                    updateMeSyncImportProgress(
                        state = "running",
                        phase = "downloading",
                        message = "Downloading package (scp)...",
                        source = sourceHint
                    )
                    downloadMeSyncPackageViaScp(source, inFile, maxBytes)
                    updateMeSyncImportProgress(
                        state = "running",
                        phase = "downloading",
                        message = "Package downloaded.",
                        source = sourceHint,
                        bytesDownloaded = inFile.length()
                    )
                } catch (ex: Exception) {
                    downloadError = ex
                    runCatching { inFile.delete() }
                }
            }
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
            var restartedPython = false
            if (!restarted) {
                restartedPython = runCatching { runtimeManager.restartSoft() }.getOrDefault(false)
            }
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("wiped", true)
                    .put("restart_requested", restartApp)
                    .put("restarted_app", restarted)
                    .put("restarted_python", restartedPython)
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
        val compactSsh = obj.optJSONObject("s")
        val ssh = obj.optJSONObject("ssh")
        val httpUrl = obj.optString("u", "").trim()
            .ifBlank { obj.optString("url", "").trim() }
            .ifBlank { obj.optString("download_url", "").trim() }
            .ifBlank { obj.optString("http_url", "").trim() }
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: ""
        val sshHost = (
            compactSsh?.optString("h", "") ?: ssh?.optString("host", "") ?: obj.optString("ssh_host", "")
        ).trim()
        val sshPort = (
            when {
                compactSsh?.has("p") == true -> compactSsh.optInt("p", 22)
                ssh?.has("port") == true -> ssh.optInt("port", 22)
                else -> obj.optInt("ssh_port", 22)
            }
        )
            .coerceIn(1, 65535)
        val sshUser = (
            compactSsh?.optString("u", "") ?: ssh?.optString("user", "") ?: obj.optString("ssh_user", "")
        ).trim().ifBlank { "methings" }
        val sshRemotePath = (
            compactSsh?.optString("r", "") ?: ssh?.optString("remote_path", "") ?: obj.optString("ssh_remote_path", "")
        ).trim()
        val sshPrivateKeyB64 = (
            compactSsh?.optString("k", "") ?: ssh?.optString("private_key_b64", "") ?: obj.optString("ssh_private_key_b64", "")
        ).trim()
        return MeSyncImportSource(
            httpUrl = httpUrl,
            sshHost = sshHost,
            sshPort = sshPort,
            sshUser = sshUser,
            sshRemotePath = sshRemotePath,
            sshPrivateKeyB64 = sshPrivateKeyB64
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
        val hostIp = sshdManager.getHostIp().trim()
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
            sshHost = "",
            sshPort = 22,
            sshUser = "",
            sshRemotePath = "",
            tempSshKeyFingerprint = "",
            requiresTempSshd = false,
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

    private fun createMeSyncSshAccess(transferId: String, expiresAt: Long): MeSyncSshAccess? {
        val initial = sshdManager.status()
        val requiresTempSshd = !initial.enabled
        if (!initial.running) {
            val started = runCatching { sshdManager.start() }.getOrElse { false }
            if (!started) return null
        }
        val status = sshdManager.status()
        if (!status.running) return null
        val host = sshdManager.getHostIp().trim()
        if (host.isBlank()) {
            if (requiresTempSshd) stopTemporaryMeSyncSshdIfIdle()
            return null
        }
        val dropbearKey = findDropbearkeyBinary() ?: run {
            if (requiresTempSshd) stopTemporaryMeSyncSshdIfIdle()
            return null
        }

        val tmpRoot = File(context.cacheDir, "me_sync_ssh")
        tmpRoot.mkdirs()
        val keyFile = File(tmpRoot, "${transferId}_key")
        if (keyFile.exists()) runCatching { keyFile.delete() }
        return try {
            val gen = runProcessCapture(
                listOf(dropbearKey.absolutePath, "-t", "ed25519", "-f", keyFile.absolutePath),
                timeoutS = 8.0,
                maxOutputBytes = 8192
            )
            if (gen.timedOut || gen.exitCode != 0 || !keyFile.exists()) {
                if (requiresTempSshd) stopTemporaryMeSyncSshdIfIdle()
                return null
            }

            val info = readDropbearKeyInfo(dropbearKey, keyFile) ?: run {
                if (requiresTempSshd) stopTemporaryMeSyncSshdIfIdle()
                return null
            }
            val privateKeyBytes = runCatching { keyFile.readBytes() }.getOrNull() ?: run {
                if (requiresTempSshd) stopTemporaryMeSyncSshdIfIdle()
                return null
            }
            val privateKeyB64 = Base64.encodeToString(privateKeyBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

            val keyEntity = sshKeyStore.upsertMerge(info.publicKey, "me.sync:$transferId", expiresAt)
            runCatching { syncAuthorizedKeys() }
            runCatching { sshdManager.restartIfRunning() }

            MeSyncSshAccess(
                host = host,
                port = status.port,
                user = "methings",
                remotePath = File(context.cacheDir, "me_sync/$transferId.zip").absolutePath,
                privateKeyB64 = privateKeyB64,
                fingerprint = keyEntity.fingerprint,
                requiresTempSshd = requiresTempSshd
            )
        } catch (_: Exception) {
            if (requiresTempSshd) stopTemporaryMeSyncSshdIfIdle()
            null
        } finally {
            runCatching { keyFile.delete() }
        }
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

        val sshKeys = org.json.JSONArray()
        sshKeyStore.listAll().forEach { key ->
            sshKeys.put(
                JSONObject()
                    .put("key", key.key)
                    .put("label", key.label ?: JSONObject.NULL)
                    .put("expires_at", key.expiresAt ?: JSONObject.NULL)
                    .put("created_at", key.createdAt)
            )
        }
        root.put("ssh_keys", sshKeys)
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
                        clearCredentialAndSshStores()
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
            runCatching { syncAuthorizedKeys() }
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
                .put("restarted_python", true)
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
        val keys = state.optJSONArray("ssh_keys")
        if (keys != null) {
            for (i in 0 until keys.length()) {
                val item = keys.optJSONObject(i) ?: continue
                val key = item.optString("key", "").trim()
                if (key.isBlank()) continue
                val label = item.optString("label", "").trim().ifBlank { null }
                val expiresAt = if (item.has("expires_at") && !item.isNull("expires_at")) item.optLong("expires_at") else null
                sshKeyStore.upsertMerge(key, label, expiresAt)
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
        runCatching {
            sshKeyStore.listAll().forEach { row ->
                sshKeyStore.delete(row.fingerprint)
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

    private fun downloadMeSyncPackageViaScp(source: MeSyncImportSource, destFile: File, maxBytes: Long) {
        if (!source.hasSshSource()) throw IllegalStateException("ssh_source_required")
        val scp = findScpBinary() ?: throw IllegalStateException("scp_client_missing")
        val privateKey = try {
            Base64.decode(normalizeBase64UrlNoPadding(source.sshPrivateKeyB64), Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (_: Exception) {
            throw IllegalStateException("invalid_ssh_private_key")
        }
        if (privateKey.isEmpty()) throw IllegalStateException("invalid_ssh_private_key")

        val tmpDir = File(context.cacheDir, "me_sync_scp")
        tmpDir.mkdirs()
        val keyFile = File(tmpDir, "import_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}.key")
        val wrapper = File(tmpDir, keyFile.name + ".sh")
        keyFile.writeBytes(privateKey)
        keyFile.setReadable(true, true)
        keyFile.setWritable(true, true)
        keyFile.setExecutable(false, false)
        wrapper.writeText(
            "#!/system/bin/sh\n" +
                "exec \"${'$'}METHINGS_NATIVELIB/libdbclient.so\" -y -i ${shellSingleQuote(keyFile.absolutePath)} \"${'$'}@\"\n"
        )
        wrapper.setExecutable(true, true)
        wrapper.setReadable(true, true)
        wrapper.setWritable(true, true)

        try {
            val args = mutableListOf(
                scp.absolutePath,
                "-S",
                wrapper.absolutePath
            )
            if (source.sshPort != 22) {
                args.add("-P")
                args.add(source.sshPort.toString())
            }
            args.add("${sshTarget(source.sshUser, source.sshHost)}:${source.sshRemotePath}")
            args.add(destFile.absolutePath)
            val result = runProcessCapture(args, timeoutS = 180.0, maxOutputBytes = 128 * 1024)
            if (result.timedOut) throw IllegalStateException("scp_timeout")
            if (result.exitCode != 0 || !destFile.exists() || destFile.length() <= 0L) {
                throw IllegalStateException("scp_failed:${result.stderr.ifBlank { result.stdout }.take(240)}")
            }
            if (destFile.length() > maxBytes) throw IllegalStateException("package_too_large")
        } finally {
            runCatching { keyFile.delete() }
            runCatching { wrapper.delete() }
        }
    }

    private fun findDropbearkeyBinary(): File? {
        val native = File(context.applicationInfo.nativeLibraryDir, "libdropbearkey.so")
        if (native.exists()) return native
        val fallback = File(File(context.filesDir, "bin"), "dropbearkey")
        return if (fallback.exists()) fallback else null
    }

    private data class DropbearKeyInfo(
        val fingerprint: String,
        val publicKey: String
    )

    private fun readDropbearKeyInfo(dropbearKey: File, keyFile: File): DropbearKeyInfo? {
        val result = runProcessCapture(
            listOf(dropbearKey.absolutePath, "-y", "-f", keyFile.absolutePath),
            timeoutS = 6.0,
            maxOutputBytes = 16 * 1024
        )
        if (result.timedOut || result.exitCode != 0) return null
        var fingerprint = ""
        var publicKey = ""
        result.stdout.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith("Fingerprint:")) {
                fingerprint = t.removePrefix("Fingerprint:").trim()
            } else if (t.startsWith("ssh-")) {
                publicKey = t
            }
        }
        if (publicKey.isBlank()) return null
        return DropbearKeyInfo(fingerprint = fingerprint, publicKey = publicKey)
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

    private fun clearCredentialAndSshStores() {
        runCatching {
            credentialStore.list().forEach { row ->
                credentialStore.delete(row.name)
            }
        }
        runCatching {
            sshKeyStore.listAll().forEach { row ->
                sshKeyStore.delete(row.fingerprint)
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
        cleanupMeSyncTempSshKey(transfer.tempSshKeyFingerprint)
        if (transfer.requiresTempSshd) {
            stopTemporaryMeSyncSshdIfIdle()
        }
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
                zipDirectory(zos, userRoot, "user/") { rel ->
                    !includeIdentity && (rel == ".ssh/id_dropbear" || rel == ".ssh/id_dropbear.pub")
                }
            }
        }

        val roomState = exportRoomState()
        putZipBytes(zos, "room/state.json", roomState.toString(2).toByteArray(StandardCharsets.UTF_8))
    }

    private fun cleanupMeSyncTempSshKey(fingerprint: String?) {
        val fp = fingerprint?.trim().orEmpty()
        if (fp.isBlank()) return
        runCatching {
            sshKeyStore.delete(fp)
            syncAuthorizedKeys()
            sshdManager.restartIfRunning()
        }
    }

    private fun stopTemporaryMeSyncSshdIfIdle() {
        if (sshdManager.isEnabled()) return
        val hasPendingTempExports = meSyncTransfers.values.any { it.requiresTempSshd }
        if (!hasPendingTempExports) {
            runCatching { sshdManager.stop() }
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
        val sshHost: String,
        val sshPort: Int,
        val sshUser: String,
        val sshRemotePath: String,
        val tempSshKeyFingerprint: String,
        val requiresTempSshd: Boolean,
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
        val devicePushToken: String,
        val gatewayAdminSecret: String
    ) {
        fun toJson(includeSecrets: Boolean): JSONObject {
            val out = JSONObject()
                .put("enabled", enabled)
                .put("gateway_base_url", gatewayBaseUrl)
                .put("provider", provider)
                .put("route_token_ttl_sec", routeTokenTtlSec)
                .put("device_push_token", devicePushToken)
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
        val targetDeviceId: String,
        val targetDeviceName: String,
        val createdAt: Long,
        val expiresAt: Long,
        val accepted: Boolean,
        val acceptToken: String,
        val methodHint: String,
        val sessionId: String,
        val sessionKeyB64: String
    ) {
        fun toJson(includeToken: Boolean): JSONObject {
            val out = JSONObject()
                .put("id", id)
                .put("source_device_id", sourceDeviceId)
                .put("source_device_name", sourceDeviceName)
                .put("target_device_id", targetDeviceId)
                .put("target_device_name", targetDeviceName)
                .put("created_at", createdAt)
                .put("expires_at", expiresAt)
                .put("accepted", accepted)
                .put("method", methodHint)
                .put("session_id", sessionId)
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
        val sessionKeyB64: String,
        val expiresAt: Long
    )

    private data class MeSyncImportSource(
        val httpUrl: String = "",
        val sshHost: String = "",
        val sshPort: Int = 22,
        val sshUser: String = "methings",
        val sshRemotePath: String = "",
        val sshPrivateKeyB64: String = ""
    ) {
        fun hasSshSource(): Boolean =
            sshHost.isNotBlank() && sshRemotePath.isNotBlank() && sshPrivateKeyB64.isNotBlank()
        fun hasAnySource(): Boolean = httpUrl.isNotBlank() || hasSshSource()
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

    private data class MeSyncSshAccess(
        val host: String,
        val port: Int,
        val user: String,
        val remotePath: String,
        val privateKeyB64: String,
        val fingerprint: String,
        val requiresTempSshd: Boolean
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
    }

    private fun sendPermissionResolved(id: String, status: String) {
        val intent = android.content.Intent(ACTION_PERMISSION_RESOLVED)
        intent.setPackage(context.packageName)
        intent.putExtra(EXTRA_PERMISSION_ID, id)
        intent.putExtra(EXTRA_PERMISSION_STATUS, status)
        context.sendBroadcast(intent)
    }

    private fun notifyBrainPermissionResolved(req: jp.espresso3389.methings.perm.PermissionStore.PermissionRequest) {
        // Best-effort: notify the Python brain runtime that a permission was approved/denied so it
        // can resume without requiring the user to manually say "continue".
        //
        // Avoid starting Python just for this; if the worker isn't running yet, ignore.
        try {
            if (runtimeManager.getStatus() != "ok") return
            val body = JSONObject()
                .put("name", "permission.resolved")
                .put(
                    "payload",
                    JSONObject()
                        .put("permission_id", req.id)
                        .put("status", req.status)
                        .put("tool", req.tool)
                        .put("detail", req.detail)
                        .put("identity", req.identity)
                        // For agent-originated requests, identity is the chat session_id.
                        .put("session_id", req.identity)
                        .put("capability", req.capability)
                )
                .toString()
            proxyWorkerRequest("/brain/inbox/event", "POST", body)
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
            if (runtimeManager.getStatus() != "ok") return
            val body = JSONObject()
                .put("name", name.trim().ifBlank { "unnamed_event" })
                .put("payload", payload)
                .put("priority", priority.trim().ifBlank { "normal" })
                .put("interrupt_policy", interruptPolicy.trim().ifBlank { "turn_end" })
            if (!coalesceKey.isNullOrBlank()) body.put("coalesce_key", coalesceKey.trim())
            if ((coalesceWindowMs ?: 0L) > 0L) body.put("coalesce_window_ms", coalesceWindowMs!!.coerceIn(0L, 86_400_000L))
            proxyWorkerRequest("/brain/inbox/event", "POST", body.toString())
        } catch (_: Exception) {
        }
    }

    private fun notifyBrainPermissionAutoApproved(req: jp.espresso3389.methings.perm.PermissionStore.PermissionRequest) {
        // Best-effort: add a chat-visible reference entry when dangerous auto-approval is active.
        try {
            if (runtimeManager.getStatus() != "ok") return
            val sid = req.identity.trim()
            val body = JSONObject()
                .put("name", "permission.auto_approved")
                .put(
                    "payload",
                    JSONObject()
                        .put("permission_id", req.id)
                        .put("status", req.status)
                        .put("tool", req.tool)
                        .put("detail", req.detail)
                        .put("identity", req.identity)
                        .put("session_id", sid)
                        .put("capability", req.capability)
                )
                .toString()
            proxyWorkerRequest("/brain/inbox/event", "POST", body)
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
                session.method == Method.POST && uri == "/me/me/connect/confirm" -> {
                    val payload = JSONObject(readBody(session).ifBlank { "{}" })
                    handleMeMeConnectConfirm(payload)
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"not_found\"}")
            }
        }
    }

    companion object {
        private const val TAG = "LocalHttpServer"
        private const val HOST = "127.0.0.1"
        private const val PORT = 33389
        private const val ME_SYNC_LAN_PORT = 8766
        private const val ME_ME_LAN_PORT = 8767
        private const val ME_ME_BLE_MAX_MESSAGE_BYTES = 1_000_000
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
        private const val ME_ME_RELAY_PULL_MIN_INTERVAL_MS = 15_000L
        private const val HOUSEKEEPING_INITIAL_DELAY_SEC = 120L
        private const val HOUSEKEEPING_INTERVAL_SEC = 60L * 60L
        private const val ME_ME_DISCOVERY_INITIAL_DELAY_SEC = 8L
        private const val ME_ME_DISCOVERY_SCAN_TIMEOUT_MS = 2500L
        private const val ME_ME_CONNECTION_CHECK_INITIAL_DELAY_SEC = 10L
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
        private val ME_SYNC_SHARED_PREFS_EXPORT = listOf(
            "brain_config",
            "cloud_prefs",
            "task_completion_prefs",
            "browser_prefs",
            "audio_record_config",
            "video_record_config",
            "screen_record_config",
            SshdManager.PREFS,
        )
        private val SETTINGS_SECTIONS = listOf(
            "brain" to "Brain",
            "web_search" to "Web Search",
            "memory" to "Memory",
            "task_notifications" to "Task Notifications",
            "audio_recording" to "Audio Recording",
            "video_recording" to "Video Recording",
            "sshd" to "SSHD",
            "agent_service" to "Agent Service",
            "user_interface" to "User Interface",
            "reset_restore" to "Reset & Restore",
            "android" to "Android",
            "permissions" to "Permissions",
            "cloud" to "Cloud",
            "tts" to "Text-to-Speech",
            "me_me" to "me.me",
            "me_sync" to "me.sync",
            "app_update" to "App Update",
            "about" to "About",
        )
        private val SETTINGS_SECTION_IDS: Set<String> = SETTINGS_SECTIONS.map { it.first }.toSet()
    }
}
