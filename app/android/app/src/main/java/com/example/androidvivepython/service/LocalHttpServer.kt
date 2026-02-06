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
    private val agentTasks = java.util.concurrent.ConcurrentHashMap<String, AgentTask>()

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
                val payload = JSONObject(readBody(session).ifBlank { "{}" })
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
                val payload = JSONObject(readBody(session).ifBlank { "{}" })
                val tool = payload.optString("tool", "unknown")
                val detail = payload.optString("detail", "")
                val requestedScope = payload.optString("scope", "once")
                val scope = if (tool == "ssh_keys") "once" else requestedScope
                val req = permissionStore.create(tool, detail, scope)
                if (tool == "credentials" || tool == "ssh_keys" || tool == "ssh_pin") {
                    val forceBio = when (tool) {
                        "ssh_keys" -> sshKeyPolicy.isBiometricRequired()
                        "ssh_pin" -> true
                        else -> false
                    }
                    sendPermissionPrompt(req.id, tool, detail, forceBio)
                }
                jsonResponse(
                    JSONObject()
                        .put("id", req.id)
                        .put("status", req.status)
                        .put("tool", req.tool)
                        .put("detail", req.detail)
                        .put("scope", req.scope)
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
                val payload = JSONObject(readBody(session).ifBlank { "{}" })
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
                val payload = JSONObject(readBody(session).ifBlank { "{}" })
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
            uri == "/builtins/tts" && session.method == Method.POST -> {
                return jsonError(Response.Status.NOT_IMPLEMENTED, "not_implemented", JSONObject().put("feature", "tts"))
            }
            uri == "/builtins/stt" && session.method == Method.POST -> {
                return jsonError(Response.Status.NOT_IMPLEMENTED, "not_implemented", JSONObject().put("feature", "stt"))
            }
            (uri == "/brain/status" || uri == "/brain/config" || uri == "/brain/messages") && session.method == Method.GET -> {
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
            uri.startsWith("/brain/") && session.method == Method.POST -> {
                if (runtimeManager.getStatus() != "ok") {
                    runtimeManager.startWorker()
                    waitForPythonHealth(5000)
                }
                val body = readBody(session).ifBlank { "{}" }
                val proxied = proxyWorkerRequest(
                    path = uri,
                    method = "POST",
                    body = body
                )
                proxied ?: jsonError(Response.Status.SERVICE_UNAVAILABLE, "python_unavailable")
            }
            (uri == "/shell/exec" || uri == "/shell/exec/") -> {
                if (session.method != Method.POST) {
                    return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
                }
                val body = readBody(session)
                val payload = runCatching { JSONObject(body) }.getOrNull()
                    ?: return jsonError(Response.Status.BAD_REQUEST, "invalid_json")
                return handleShellExec(payload)
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
                val payload = JSONObject(readBody(session).ifBlank { "{}" })
                val requireBio = payload.optBoolean("require_biometric", sshKeyPolicy.isBiometricRequired())
                sshKeyPolicy.setBiometricRequired(requireBio)
                jsonResponse(JSONObject().put("require_biometric", sshKeyPolicy.isBiometricRequired()))
            }
            uri == "/ssh/keys/add" && session.method == Method.POST -> {
                val payload = JSONObject(readBody(session).ifBlank { "{}" })
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
                val payload = JSONObject(readBody(session).ifBlank { "{}" })
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
                val payload = JSONObject(readBody(session).ifBlank { "{}" })
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
                val body = readBody(session)
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
        if (cmd != "python" && cmd != "pip" && cmd != "uv") {
            return jsonError(Response.Status.FORBIDDEN, "command_not_allowed")
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
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
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
        return try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            map["postData"] ?: ""
        } catch (_: Exception) {
            ""
        }
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
        val intent = android.content.Intent(ACTION_PERMISSION_PROMPT)
        intent.setPackage(context.packageName)
        intent.putExtra(EXTRA_PERMISSION_ID, id)
        intent.putExtra(EXTRA_PERMISSION_TOOL, tool)
        intent.putExtra(EXTRA_PERMISSION_DETAIL, detail)
        intent.putExtra(EXTRA_PERMISSION_BIOMETRIC, forceBiometric)
        context.sendBroadcast(intent)
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
        const val ACTION_PERMISSION_PROMPT = "jp.espresso3389.kugutz.action.PERMISSION_PROMPT"
        const val EXTRA_PERMISSION_ID = "permission_id"
        const val EXTRA_PERMISSION_TOOL = "permission_tool"
        const val EXTRA_PERMISSION_DETAIL = "permission_detail"
        const val EXTRA_PERMISSION_BIOMETRIC = "permission_biometric"
    }
}
