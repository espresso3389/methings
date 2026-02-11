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
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLConnection
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import jp.espresso3389.methings.perm.PermissionStoreFacade
import jp.espresso3389.methings.perm.CredentialStore
import jp.espresso3389.methings.perm.SshKeyStore
import jp.espresso3389.methings.perm.SshKeyPolicy
import jp.espresso3389.methings.perm.InstallIdentity
import jp.espresso3389.methings.perm.PermissionPrefs
import jp.espresso3389.methings.device.BleManager
import jp.espresso3389.methings.device.CameraXManager
import jp.espresso3389.methings.device.DeviceLocationManager
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
import java.util.Locale
import jp.espresso3389.methings.device.UsbPermissionWaiter

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
    private val tts = TtsManager(context)
    private val stt = SttManager(context)
    private val location = DeviceLocationManager(context)
    private val sensors = SensorsStreamManager(context)

    @Volatile private var keepScreenOnWakeLock: PowerManager.WakeLock? = null
    @Volatile private var keepScreenOnExpiresAtMs: Long = 0L
    private val screenScheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var screenReleaseFuture: ScheduledFuture<*>? = null

    fun startServer(): Boolean {
        return try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Local HTTP server started on $HOST:$PORT")
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
            setKeepScreenOn(false, timeoutS = 0)
        } catch (_: Exception) {
        }
        try {
            screenScheduler.shutdownNow()
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
                context.sendBroadcast(android.content.Intent(ACTION_UI_RELOAD))
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

                if (identity.isNotBlank() && scope != "once") {
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
                        .put("identity", installIdentity.get())
                )
            }
            uri == "/permissions/prefs" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val remember = payload.optBoolean("remember_approvals", true)
                permissionPrefs.setRememberApprovals(remember)
                jsonResponse(JSONObject().put("remember_approvals", permissionPrefs.rememberApprovals()))
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
            (uri == "/stt/status" || uri == "/stt/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.mic", capability = "stt", detail = "Speech recognizer status")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(stt.status()))
            }
            (uri == "/stt/start" || uri == "/stt/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.mic", capability = "stt", detail = "Start speech recognition")
                if (!ok.first) return ok.second!!
                val locale = payload.optString("locale", "").trim().ifBlank { null }
                val partial = payload.optBoolean("partial", true)
                val maxResults = payload.optInt("max_results", 5)
                val resp = JSONObject(stt.start(locale, partial, maxResults)).put("ws_path", "/ws/stt/events")
                return jsonResponse(resp)
            }
            (uri == "/stt/stop" || uri == "/stt/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.mic", capability = "stt", detail = "Stop speech recognition")
                if (!ok.first) return ok.second!!
                return jsonResponse(JSONObject(stt.stop()))
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
            (uri == "/sensors/list" || uri == "/sensors/list/" || uri == "/sensor/list" || uri == "/sensor/list/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.sensors", capability = "sensors", detail = "List available sensors")
                if (!ok.first) return ok.second!!
                val out = JSONObject(sensors.listSensors())
                if (out.has("items")) {
                    out.put("items", org.json.JSONArray(out.getString("items")))
                }
                return jsonResponse(out)
            }
            (uri == "/sensors/stream/status" || uri == "/sensors/stream/status/") && session.method == Method.GET -> {
                val ok = ensureDevicePermission(session, JSONObject(), tool = "device.sensors", capability = "sensors", detail = "Sensors stream status")
                if (!ok.first) return ok.second!!
                val out = JSONObject(sensors.streamsStatus())
                if (out.has("items")) {
                    out.put("items", org.json.JSONArray(out.getString("items")))
                }
                return jsonResponse(out)
            }
            (uri == "/sensors/stream/start" || uri == "/sensors/stream/start/" || uri == "/sensor/stream/start" || uri == "/sensor/stream/start/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.sensors", capability = "sensors", detail = "Start sensors stream")
                if (!ok.first) return ok.second!!
                val sensorsArr = payload.optJSONArray("sensors")
                val names = mutableListOf<String>()
                if (sensorsArr != null) {
                    for (i in 0 until sensorsArr.length()) {
                        val s = sensorsArr.optString(i, "").trim()
                        if (s.isNotBlank()) names.add(s)
                    }
                }
                val rateHz = payload.optInt("rate_hz", 200).coerceIn(1, 1000)
                val latency = payload.optString("latency", "realtime").trim()
                val timestamp = payload.optString("timestamp", "mono").trim()
                val bufferMax = payload.optInt("buffer_max", 4096).coerceIn(64, 50_000)
                val out = sensors.start(
                    SensorsStreamManager.StreamStart(
                        sensors = names,
                        rateHz = rateHz,
                        latency = latency,
                        timestamp = timestamp,
                        bufferMax = bufferMax,
                    )
                )
                return jsonResponse(JSONObject(out))
            }
            (uri == "/sensors/stream/stop" || uri == "/sensors/stream/stop/" || uri == "/sensor/stream/stop" || uri == "/sensor/stream/stop/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.sensors", capability = "sensors", detail = "Stop sensors stream")
                if (!ok.first) return ok.second!!
                val id = payload.optString("stream_id", "").trim()
                if (id.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "stream_id_required")
                return jsonResponse(JSONObject(sensors.stop(id)))
            }
            (uri == "/sensors/stream/latest" || uri == "/sensors/stream/latest/" || uri == "/sensor/stream/latest" || uri == "/sensor/stream/latest/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.sensors", capability = "sensors", detail = "Get latest sensors frame")
                if (!ok.first) return ok.second!!
                val id = payload.optString("stream_id", "").trim()
                if (id.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "stream_id_required")
                return jsonResponse(sensors.latest(id))
            }
            (uri == "/sensors/stream/batch" || uri == "/sensors/stream/batch/" || uri == "/sensor/stream/batch" || uri == "/sensor/stream/batch/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.sensors", capability = "sensors", detail = "Get sensors frames batch")
                if (!ok.first) return ok.second!!
                val id = payload.optString("stream_id", "").trim()
                if (id.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "stream_id_required")
                val sinceQ = payload.optLong("since_q_exclusive", payload.optLong("since_seq_exclusive", 0L))
                val limit = payload.optInt("limit", 500).coerceIn(1, 5000)
                return jsonResponse(sensors.batch(id, sinceQ, limit))
            }
            (uri == "/sensor/stream/status" || uri == "/sensor/stream/status/") && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val ok = ensureDevicePermission(session, payload, tool = "device.sensors", capability = "sensors", detail = "Sensors stream status (single)")
                if (!ok.first) return ok.second!!
                val id = payload.optString("stream_id", "").trim()
                if (id.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "stream_id_required")
                return jsonResponse(sensors.streamStatus(id))
            }
            uri == "/ssh/status" -> {
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
            uri == "/ssh/keys" -> {
                // If the authorized_keys file was edited externally (e.g., via SSH), the DB-backed
                // key list can get out of sync. Import any valid keys from the file so the UI
                // reflects reality and future syncs won't accidentally drop them.
                try {
                    importAuthorizedKeysFromFile()
                } catch (_: Exception) {
                }
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
            uri == "/ssh/keys/policy" -> {
                jsonResponse(
                    JSONObject()
                        .put("require_biometric", sshKeyPolicy.isBiometricRequired())
                )
            }
            uri == "/ssh/keys/policy" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val requireBio = payload.optBoolean("require_biometric", sshKeyPolicy.isBiometricRequired())
                sshKeyPolicy.setBiometricRequired(requireBio)
                jsonResponse(JSONObject().put("require_biometric", sshKeyPolicy.isBiometricRequired()))
            }
            uri == "/ssh/keys/add" && session.method == Method.POST -> {
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
                jsonResponse(
                    JSONObject()
                        .put("fingerprint", entity.fingerprint)
                        .put("status", "ok")
                )
            }
            uri == "/ssh/keys/delete" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val fingerprint = payload.optString("fingerprint", "")
                val permissionId = payload.optString("permission_id", "")
                if (!isPermissionApproved(permissionId, consume = true)) {
                    return forbidden("permission_required")
                }
                if (fingerprint.isBlank()) {
                    return badRequest("fingerprint_required")
                }
                sshKeyStore.delete(fingerprint)
                syncAuthorizedKeys()
                jsonResponse(JSONObject().put("status", "ok"))
            }
            uri == "/ssh/pin/status" -> {
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
            uri == "/ssh/pin/start" && session.method == Method.POST -> {
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
            uri == "/ssh/pin/stop" && session.method == Method.POST -> {
                Log.i(TAG, "PIN auth stop requested")
                sshPinManager.stopPin()
                sshdManager.exitPinMode()
                jsonResponse(JSONObject().put("active", false))
            }
            uri == "/ssh/noauth/status" -> {
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
            uri == "/ssh/noauth/start" && session.method == Method.POST -> {
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
            uri == "/ssh/noauth/stop" && session.method == Method.POST -> {
                Log.i(TAG, "Notification auth stop requested")
                sshNoAuthModeManager.stop()
                sshdManager.exitNotificationMode()
                jsonResponse(JSONObject().put("active", false))
            }
            uri == "/ssh/config" && session.method == Method.POST -> {
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
            uri == "/user/upload" && session.method == Method.POST -> {
                handleUserUpload(session)
            }
            else -> notFound()
        }
    }

    private fun firstParam(session: IHTTPSession, name: String): String {
        return session.parameters[name]?.firstOrNull()?.trim() ?: ""
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
                            "If no dialog appears, bring methings to foreground and retry. " +
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

        val sensorsPrefixes = listOf("/ws/sensors/stream/", "/ws/sensor/stream/")
        val sensorsPrefix = sensorsPrefixes.firstOrNull { uri.startsWith(it) }
        if (sensorsPrefix != null) {
            val streamId = uri.removePrefix(sensorsPrefix).trim()
            return object : NanoWSD.WebSocket(handshake) {
                override fun onOpen() {
                    val ok = sensors.addWsClient(streamId, this)
                    if (!ok) {
                        runCatching { close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "stream_not_found", false) }
                    }
                }

                override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                    sensors.removeWsClient(streamId, this)
                }

                override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
                override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
                override fun onException(exception: java.io.IOException?) {
                    sensors.removeWsClient(streamId, this)
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
            val req = permissionStore.create(
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
            val req = permissionStore.create(
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
            val req = permissionStore.create(
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
                    setRequestProperty("Accept-Encoding", "gzip")
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
            val req = permissionStore.create(
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

    private fun textResponse(text: String): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "text/plain", text)
        response.addHeader("Cache-Control", "no-cache")
        return response
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

    companion object {
        private const val TAG = "LocalHttpServer"
        private const val HOST = "127.0.0.1"
        private const val PORT = 8765
    private const val BRAIN_SYSTEM_PROMPT = """You are the methings Brain, an AI assistant running on an Android device.

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
        const val EXTRA_PERMISSION_ID = "permission_id"
        const val EXTRA_PERMISSION_TOOL = "permission_tool"
        const val EXTRA_PERMISSION_DETAIL = "permission_detail"
        const val EXTRA_PERMISSION_BIOMETRIC = "permission_biometric"
        const val EXTRA_PERMISSION_STATUS = "permission_status"
    }
}
