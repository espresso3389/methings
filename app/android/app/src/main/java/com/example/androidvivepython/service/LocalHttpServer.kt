package jp.espresso3389.kugutz.service

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import jp.espresso3389.kugutz.perm.PermissionStoreFacade
import jp.espresso3389.kugutz.perm.CredentialStore
import jp.espresso3389.kugutz.perm.SshKeyStore
import jp.espresso3389.kugutz.perm.SshKeyPolicy

class LocalHttpServer(
    private val context: Context,
    private val runtimeManager: PythonRuntimeManager
) : NanoHTTPD(HOST, PORT) {
    private val uiRoot = File(context.filesDir, "www")
    private val sshdManager = SshdManager(context)
    private val permissionStore = PermissionStoreFacade(context)
    private val credentialStore = CredentialStore(context)
    private val sshKeyStore = SshKeyStore(context)
    private val sshKeyPolicy = SshKeyPolicy(context)
    private val sshPinManager = SshPinManager(context)
    private val deviceGrantStore = jp.espresso3389.kugutz.perm.DeviceGrantStoreFacade(context)
    private val agentTasks = java.util.concurrent.ConcurrentHashMap<String, AgentTask>()
    private val lastPermissionPromptAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        // NanoHTTPD keeps connections alive; if we return early on a POST without consuming the body,
        // leftover bytes can corrupt the next request line (e.g. "{}POST ...").
        // Always read the POST body once up-front and reuse it across handlers.
        val postBody: String? = if (session.method == Method.POST) readBody(session) else null
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
            uri == "/permissions/request" && session.method == Method.POST -> {
                val payload = JSONObject((postBody ?: "").ifBlank { "{}" })
                val tool = payload.optString("tool", "unknown")
                val detail = payload.optString("detail", "")
                val requestedScope = payload.optString("scope", "once")
                val scope = if (tool == "ssh_keys") "once" else requestedScope
                val headerIdentity = (session.headers["x-kugutz-identity"] ?: "").trim()
                val identity = payload.optString("identity", "").trim().ifBlank { headerIdentity }
                val capabilityFromTool = if (tool.startsWith("device.")) tool.removePrefix("device.").trim() else ""
                val capability = payload.optString("capability", "").trim().ifBlank { capabilityFromTool }

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
            (uri == "/brain/status" || uri == "/brain/messages") && session.method == Method.GET -> {
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
            (uri == "/brain/start" || uri == "/brain/stop" || uri == "/brain/inbox/chat" || uri == "/brain/inbox/event") && session.method == Method.POST -> {
                if (runtimeManager.getStatus() != "ok") {
                    runtimeManager.startWorker()
                    waitForPythonHealth(5000)
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
                val entity = sshKeyStore.upsert(key, if (label.isBlank()) null else label, expiresAt)
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
            else -> notFound()
        }
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
        if (cmd != "python" && cmd != "pip" && cmd != "uv" && cmd != "curl") {
            return jsonError(Response.Status.FORBIDDEN, "command_not_allowed")
        }

        if (cmd == "curl") {
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

        val pythonExe = resolvePythonBinary()
        if (pythonExe == null) {
            if (runtimeManager.getStatus() != "ok") {
                runtimeManager.startWorker()
                waitForPythonHealth(5000)
            }
            val proxied = proxyShellExecToWorker(cmd, args, cwd)
            if (proxied != null) {
                return proxied
            }
            return jsonError(Response.Status.SERVICE_UNAVAILABLE, "python_missing")
        }
        val userHome = File(context.filesDir, "user")
        val resolvedCwd = resolveUserPath(userHome, cwd) ?: userHome

        val argList = if (args.isBlank()) emptyList() else args.split(Regex("\\s+"))
        val command = when (cmd) {
            "pip" -> listOf(pythonExe.absolutePath, "-m", "pip") + argList
            "uv" -> listOf(pythonExe.absolutePath, "-m", "uv") + argList
            else -> listOf(pythonExe.absolutePath) + argList
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val pyenvDir = File(context.filesDir, "pyenv")
        val serverDir = File(context.filesDir, "server")
        return try {
            fun runPythonCommand(cmdline: List<String>): Pair<Int, String> {
                val pb = ProcessBuilder(cmdline)
                pb.directory(resolvedCwd)
                pb.redirectErrorStream(true)
                pb.environment()["KUGUTZ_PYENV"] = pyenvDir.absolutePath
                pb.environment()["KUGUTZ_NATIVELIB"] = nativeLibDir
                pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
                pb.environment()["PYTHONHOME"] = pyenvDir.absolutePath
                pb.environment()["PYTHONPATH"] = listOf(
                    serverDir.absolutePath,
                    "${pyenvDir.absolutePath}/site-packages",
                    "${pyenvDir.absolutePath}/modules",
                    "${pyenvDir.absolutePath}/stdlib.zip"
                ).joinToString(":")
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                return Pair(code, output)
            }

            var result = runPythonCommand(command)
            val uvMissingPattern = Regex("No module named ['\\\"]?uv['\\\"]?")
            if (cmd == "uv" && result.first != 0 && uvMissingPattern.containsMatchIn(result.second)) {
                val install = runPythonCommand(listOf(pythonExe.absolutePath, "-m", "pip", "install", "uv"))
                result = if (install.first == 0) {
                    runPythonCommand(command)
                } else {
                    Pair(result.first, result.second + "\nuv bootstrap failed:\n" + install.second)
                }
            }
            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("code", result.first)
                    .put("output", result.second)
            )
        } catch (ex: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "exec_failed", JSONObject().put("detail", ex.message ?: ""))
        }
    }

    private fun handleWebSearch(session: IHTTPSession, payload: JSONObject): Response {
        val q = payload.optString("q", payload.optString("query", "")).trim()
        if (q.isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "query_required")
        }
        val maxResults = payload.optInt("max_results", payload.optInt("limit", 5)).coerceIn(1, 10)
        val headerIdentity = (session.headers["x-kugutz-identity"] ?: "").trim()
        val identity = payload.optString("identity", "").trim().ifBlank { headerIdentity }
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
                detail = "DuckDuckGo search: " + q.take(200),
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
                    "&format=json&no_html=1&skip_disambig=1&t=kugutz"
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

    private fun resolvePythonBinary(): File? {
        // Prefer native lib (has correct SELinux context for execution)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativePython = File(nativeDir, "libkugutzpy.so")
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
            "You are Kugutz Brain running on an Android device. ",
            "You MUST use function tools for any real action (no pretending). ",
            "At the start of a session, read user-root docs: `AGENTS.md` and `TOOLS.md`. ",
            "For files: use filesystem tools under the user root (not shell `ls`/`cat`). ",
            "For execution: use run_python/run_pip/run_uv/run_curl only. ",
            "Device/resource access requires explicit user approval; if permission_required, ask the user to approve in the app UI and then retry. ",
            "Keep responses concise and include relevant tool output snippets."
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

    private fun handleBrainConfigSet(body: String): Response {
        val payload = runCatching { JSONObject(body) }.getOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
        val editor = brainPrefs.edit()
        if (payload.has("vendor")) {
            editor.putString("vendor", payload.optString("vendor", "").trim())
        }
        if (payload.has("base_url")) {
            editor.putString("base_url", payload.optString("base_url", "").trim().trimEnd('/'))
        }
        if (payload.has("model")) {
            editor.putString("model", payload.optString("model", "").trim())
        }
        if (payload.has("api_key")) {
            editor.putString("api_key", payload.optString("api_key", "").trim())
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

    private fun syncAuthorizedKeys() {
        val userHome = File(context.filesDir, "user")
        val sshDir = File(userHome, ".ssh")
        sshDir.mkdirs()
        val authFile = File(sshDir, "authorized_keys")
        val now = System.currentTimeMillis()
        val active = sshKeyStore.listActive(now)
        val lines = active.map { it.key.trim() }.filter { it.isNotBlank() }
        authFile.writeText(lines.joinToString("\n") + if (lines.isNotEmpty()) "\n" else "")
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

    private fun maybeGrantDeviceCapability(req: jp.espresso3389.kugutz.perm.PermissionStore.PermissionRequest) {
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
        private const val BRAIN_SYSTEM_PROMPT = """You are the Kugutz Brain, an AI assistant running on an Android device.

Policies:
- For detailed operational rules and tool usage, read user-root docs: `AGENTS.md` and `TOOLS.md`.
- Device/resource actions require explicit user approval via the app UI.
- Persistent memory lives in `MEMORY.md`. Only update it if the user explicitly asks. (Procedure in `AGENTS.md`.)

## Current Memory
"""
        const val ACTION_PERMISSION_PROMPT = "jp.espresso3389.kugutz.action.PERMISSION_PROMPT"
        const val EXTRA_PERMISSION_ID = "permission_id"
        const val EXTRA_PERMISSION_TOOL = "permission_tool"
        const val EXTRA_PERMISSION_DETAIL = "permission_detail"
        const val EXTRA_PERMISSION_BIOMETRIC = "permission_biometric"
    }
}
