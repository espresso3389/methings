package jp.espresso3389.methings.service.agent

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ToolExecutor(
    private val userDir: File,
    private val sysDir: File,
    private val journalStore: JournalStore,
    private val deviceBridge: DeviceToolBridge,
    private val sessionIdProvider: () -> String,
    private val jsRuntime: JsRuntime,
    private val shellExecutor: ShellExecutor,
) {
    /** Media types the current provider supports natively (e.g. "image", "audio").
     *  Set by AgentRuntime before the tool execution loop each turn. */
    @Volatile var supportedMediaTypes: Set<String> = emptySet()

    /** Image encoding settings from File Transfer prefs.
     *  Set at construction and read by both ToolExecutor and AgentRuntime. */
    @Volatile var imageResizeEnabled: Boolean = true
    @Volatile var imageMaxDimPx: Int = MediaEncoder.MAX_DIMENSION
    @Volatile var imageJpegQuality: Int = MediaEncoder.IMAGE_QUALITY

    fun executeFunctionTool(
        toolName: String,
        args: JSONObject,
        userText: String,
    ): JSONObject {
        return try {
            when (toolName) {
                "list_dir" -> executeListDir(args)
                "read_file" -> executeReadFile(args)
                "read_binary_file" -> executeReadBinaryFile(args)
                "write_file" -> executeWriteFile(args)
                "mkdir" -> executeMkdir(args)
                "move_path" -> executeMovePath(args)
                "delete_path" -> executeDeletePath(args)
                "device_api" -> executeDeviceApi(args, userText)
                "memory_get" -> executeMemoryGet()
                "memory_set" -> executeMemorySet(args, userText)
                "journal_get_current" -> executeJournalGetCurrent(args)
                "journal_set_current" -> executeJournalSetCurrent(args)
                "journal_append" -> executeJournalAppend(args)
                "journal_list" -> executeJournalList(args)
                "run_js" -> executeRunJs(args)
                "run_python" -> executeRunPython(args)
                "run_pip" -> executeRunPip(args)
                "run_shell" -> executeRunShell(args)
                "shell_session" -> executeShellSession(args)
                "run_curl" -> executeRunCurl(args)
                "shell_exec" -> {
                    val cmd = args.optString("cmd", "")
                    val cmdArgs = args.optString("args", "")
                    val cwd = args.optString("cwd", "")
                    val allowed = setOf("python", "pip", "curl")
                    if (cmd !in allowed) {
                        JSONObject().put("status", "error").put("error", "command_not_allowed").put("cmd", cmd)
                    } else {
                        val command = when (cmd) {
                            "python" -> "python3 $cmdArgs"
                            "pip" -> "pip3 $cmdArgs"
                            else -> "$cmd $cmdArgs"
                        }
                        shellExecutor.exec(command, cwd, 300_000)
                    }
                }
                "web_search" -> executeWebSearch(args)
                "cloud_request" -> executeCloudRequest(args)
                "sleep" -> executeSleep(args)
                "analyze_image" -> executeAnalyzeMedia(args, "image")
                "analyze_audio" -> executeAnalyzeMedia(args, "audio")
                else -> JSONObject().put("status", "error").put("error", "unknown_tool").put("tool", toolName)
            }
        } catch (ex: InterruptedException) {
            throw ex
        } catch (ex: Exception) {
            Log.w(TAG, "Tool execution failed: $toolName", ex)
            JSONObject().put("status", "error").put("error", ex.message ?: "execution_failed")
        }
    }

    private fun executeListDir(args: JSONObject): JSONObject {
        val path = args.optString("path", ".")
        val showHidden = args.optBoolean("show_hidden", false)
        val limit = args.optInt("limit", 200).coerceIn(1, 2000)

        val baseDir = if (path.startsWith("\$sys/")) sysDir else userDir
        val relPath = if (path.startsWith("\$sys/")) path.substring(5) else path
        val target = resolveSecure(baseDir, relPath) ?: return pathError("path_outside_base")

        if (!target.exists()) return JSONObject().put("status", "error").put("error", "not_found")
        if (!target.isDirectory) return JSONObject().put("status", "error").put("error", "not_a_directory")

        val items = JSONArray()
        val files = target.listFiles() ?: emptyArray()
        var count = 0
        for (f in files.sortedBy { it.name }) {
            if (count >= limit) break
            if (!showHidden && f.name.startsWith(".")) continue
            items.put(JSONObject().put("name", f.name).put("is_dir", f.isDirectory))
            count++
        }

        return JSONObject().put("status", "ok").put("items", items).put("path", path)
    }

    private fun executeReadFile(args: JSONObject): JSONObject {
        val path = args.optString("path", "")
        val maxBytes = args.optInt("max_bytes", 262144).coerceIn(1, 1_048_576)

        val baseDir = if (path.startsWith("\$sys/")) sysDir else userDir
        val relPath = if (path.startsWith("\$sys/")) path.substring(5) else path
        val target = resolveSecure(baseDir, relPath) ?: return pathError("path_outside_base")

        if (!target.exists()) return JSONObject().put("status", "error").put("error", "not_found")
        if (!target.isFile) return JSONObject().put("status", "error").put("error", "not_a_file")

        val bytes = target.readBytes()
        val content = if (bytes.size > maxBytes) {
            String(bytes, 0, maxBytes, Charsets.UTF_8) + "\n...[truncated at $maxBytes bytes, total ${bytes.size}]..."
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return JSONObject()
            .put("status", "ok")
            .put("path", path)
            .put("content", content)
            .put("size", bytes.size)
            .put("truncated", bytes.size > maxBytes)
    }

    private fun executeReadBinaryFile(args: JSONObject): JSONObject {
        val path = args.optString("path", "")
        if (path.startsWith("\$sys/")) return JSONObject().put("status", "error").put("error", "system_binary_read_unsupported")

        val target = resolveSecure(userDir, path) ?: return pathError("path_outside_base")
        if (!target.exists()) return JSONObject().put("status", "error").put("error", "not_found")
        if (!target.isFile) return JSONObject().put("status", "error").put("error", "not_a_file")

        val offsetBytes = args.optInt("offset_bytes", 0).coerceAtLeast(0)
        val sizeBytes = args.optInt("size_bytes", 262144).coerceIn(1, 1_048_576)

        val bytes = target.readBytes()
        val end = (offsetBytes + sizeBytes).coerceAtMost(bytes.size)
        val slice = if (offsetBytes < bytes.size) bytes.copyOfRange(offsetBytes, end) else ByteArray(0)
        val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)

        return JSONObject()
            .put("status", "ok")
            .put("path", path)
            .put("data_b64", b64)
            .put("offset_bytes", offsetBytes)
            .put("size_bytes", slice.size)
            .put("total_size", bytes.size)
    }

    private fun executeWriteFile(args: JSONObject): JSONObject {
        val path = args.optString("path", "")
        val content = args.optString("content", "")
        if (path.startsWith("\$sys/")) return JSONObject().put("status", "error").put("error", "system_write_not_allowed")

        val target = resolveSecure(userDir, path) ?: return pathError("path_outside_base")
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
        return JSONObject().put("status", "ok").put("path", path).put("size", content.toByteArray(Charsets.UTF_8).size)
    }

    private fun executeMkdir(args: JSONObject): JSONObject {
        val path = args.optString("path", "")
        val parents = args.optBoolean("parents", true)

        val target = resolveSecure(userDir, path) ?: return pathError("path_outside_base")
        if (parents) target.mkdirs() else target.mkdir()
        return JSONObject().put("status", "ok").put("path", path).put("exists", target.exists())
    }

    private fun executeMovePath(args: JSONObject): JSONObject {
        val src = args.optString("src", "")
        val dst = args.optString("dst", "")
        val overwrite = args.optBoolean("overwrite", false)

        val srcFile = resolveSecure(userDir, src) ?: return pathError("invalid_path")
        val dstFile = resolveSecure(userDir, dst) ?: return pathError("invalid_path")

        if (!srcFile.exists()) return JSONObject().put("status", "error").put("error", "source_not_found")
        if (dstFile.exists() && !overwrite) return JSONObject().put("status", "error").put("error", "destination_exists")

        dstFile.parentFile?.mkdirs()
        if (dstFile.exists() && overwrite) dstFile.delete()
        srcFile.renameTo(dstFile)
        return JSONObject().put("status", "ok").put("src", src).put("dst", dst)
    }

    private fun executeDeletePath(args: JSONObject): JSONObject {
        val path = args.optString("path", "")
        val recursive = args.optBoolean("recursive", false)

        val target = resolveSecure(userDir, path) ?: return pathError("invalid_path")
        if (!target.exists()) return JSONObject().put("status", "ok").put("path", path).put("existed", false)

        val deleted = if (recursive) target.deleteRecursively() else target.delete()
        return JSONObject().put("status", "ok").put("path", path).put("deleted", deleted)
    }

    private fun executeDeviceApi(args: JSONObject, userText: String): JSONObject {
        val actionName = args.optString("action", "")
        if (actionName.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_action")

        // Block brain.memory.set unless user explicitly requested persist
        if (actionName == "brain.memory.set" && !explicitPersistMemoryRequested(userText)) {
            return JSONObject()
                .put("status", "error")
                .put("error", "command_not_allowed")
                .put("detail", "Persistent memory writes require an explicit user request to save/persist.")
        }

        val payload = args.optJSONObject("payload") ?: JSONObject()
        val detail = args.optString("detail", "")
        return deviceBridge.execute(actionName, payload, detail)
    }

    private fun executeMemoryGet(): JSONObject {
        return deviceBridge.execute("brain.memory.get", JSONObject(), "Read persistent memory")
    }

    private fun executeMemorySet(args: JSONObject, userText: String): JSONObject {
        if (!explicitPersistMemoryRequested(userText)) {
            return JSONObject()
                .put("status", "error")
                .put("error", "command_not_allowed")
                .put("detail", "Persistent memory writes require an explicit user request to save/persist.")
        }
        val payload = JSONObject().put("content", args.optString("content", ""))
        return deviceBridge.execute("brain.memory.set", payload, "Write persistent memory")
    }

    private fun executeJournalGetCurrent(args: JSONObject): JSONObject {
        val sid = args.optString("session_id", "").ifEmpty { sessionIdProvider() }
        return journalStore.getCurrent(sid)
    }

    private fun executeJournalSetCurrent(args: JSONObject): JSONObject {
        val sid = args.optString("session_id", "").ifEmpty { sessionIdProvider() }
        val text = args.optString("text", "")
        return journalStore.setCurrent(sid, text)
    }

    private fun executeJournalAppend(args: JSONObject): JSONObject {
        val sid = args.optString("session_id", "").ifEmpty { sessionIdProvider() }
        val kind = args.optString("kind", "note")
        val title = args.optString("title", "")
        val text = args.optString("text", "")
        val meta = args.optJSONObject("meta")
        return journalStore.append(sid, kind, title, text, meta)
    }

    private fun executeJournalList(args: JSONObject): JSONObject {
        val sid = args.optString("session_id", "").ifEmpty { sessionIdProvider() }
        val limit = try { args.optInt("limit", 50) } catch (_: Exception) { 50 }
        return journalStore.listEntries(sid, limit)
    }

    private fun executeRunJs(args: JSONObject): JSONObject {
        val code = args.optString("code", "")
        if (code.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_code")
        val timeoutMs = args.optLong("timeout_ms", 30_000).coerceIn(1_000, 120_000)
        return jsRuntime.executeBlocking(code, timeoutMs).toJson()
    }

    private fun executeRunCurl(args: JSONObject): JSONObject {
        // Native HTTP implementation
        val url = args.optString("url", "")
        if (url.isEmpty()) {
            // Legacy fallback: if "args" field is present, delegate to shell curl
            if (args.has("args")) {
                val cmdArgs = args.optString("args", "")
                val cwd = args.optString("cwd", "")
                return shellExecutor.exec("curl $cmdArgs", cwd, 300_000)
            }
            return JSONObject().put("status", "error").put("error", "missing_url")
        }

        val method = args.optString("method", "GET").uppercase()
        val headers = args.optJSONObject("headers")
        val body = args.optString("body", "")
        val timeoutMs = args.optInt("timeout_ms", 30_000).coerceIn(1_000, 120_000)

        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true

            if (headers != null) {
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    conn.setRequestProperty(key, headers.optString(key, ""))
                }
            }

            if (body.isNotEmpty() && method in setOf("POST", "PUT", "PATCH")) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val httpStatus = conn.responseCode
            val respHeaders = JSONObject()
            conn.headerFields?.forEach { (key, values) ->
                if (key != null && values != null) {
                    respHeaders.put(key, values.joinToString(", "))
                }
            }

            val respBody = try {
                val stream = if (httpStatus in 200..399) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }

            conn.disconnect()

            JSONObject()
                .put("status", "ok")
                .put("http_status", httpStatus)
                .put("headers", respHeaders)
                .put("body", respBody)
        } catch (e: Exception) {
            JSONObject()
                .put("status", "error")
                .put("error", e.message ?: "request_failed")
        }
    }

    private fun executeRunPython(args: JSONObject): JSONObject {
        val cmdArgs = args.optString("args", "")
        val cwd = args.optString("cwd", "")
        return shellExecutor.exec("python3 $cmdArgs", cwd, 300_000)
    }

    private fun executeRunPip(args: JSONObject): JSONObject {
        val cmdArgs = args.optString("args", "")
        val cwd = args.optString("cwd", "")
        return shellExecutor.exec("pip3 $cmdArgs", cwd, 300_000)
    }

    private fun executeRunShell(args: JSONObject): JSONObject {
        val command = args.optString("command", "")
        if (command.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_command")
        val cwd = args.optString("cwd", "")
        val timeoutMs = args.optLong("timeout_ms", 60_000).coerceIn(1_000, 300_000)
        val env = args.optJSONObject("env")
        return shellExecutor.exec(command, cwd, timeoutMs, env)
    }

    private fun executeShellSession(args: JSONObject): JSONObject {
        val action = args.optString("action", "")
        if (action.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_action")

        return when (action) {
            "start" -> {
                val cwd = args.optString("cwd", "")
                val rows = args.optInt("rows", 24)
                val cols = args.optInt("cols", 80)
                val env = args.optJSONObject("env")
                shellExecutor.sessionStart(cwd, env, rows, cols)
            }
            "exec" -> {
                val sid = args.optString("session_id", "")
                if (sid.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_session_id")
                val command = args.optString("command", "")
                if (command.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_command")
                val timeout = args.optInt("timeout", 30)
                shellExecutor.sessionExec(sid, command, timeout)
            }
            "write" -> {
                val sid = args.optString("session_id", "")
                if (sid.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_session_id")
                val input = args.optString("input", "")
                if (input.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_input")
                shellExecutor.sessionWrite(sid, input)
            }
            "read" -> {
                val sid = args.optString("session_id", "")
                if (sid.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_session_id")
                shellExecutor.sessionRead(sid)
            }
            "resize" -> {
                val sid = args.optString("session_id", "")
                if (sid.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_session_id")
                val rows = args.optInt("rows", 24)
                val cols = args.optInt("cols", 80)
                shellExecutor.sessionResize(sid, rows, cols)
            }
            "kill" -> {
                val sid = args.optString("session_id", "")
                if (sid.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_session_id")
                shellExecutor.sessionKill(sid)
            }
            "list" -> shellExecutor.sessionList()
            else -> JSONObject().put("status", "error").put("error", "unknown_action").put("action", action)
        }
    }

    private fun executeWebSearch(args: JSONObject): JSONObject {
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) return JSONObject().put("status", "error").put("error", "missing_query")
        val maxResults = args.optInt("max_results", 5).coerceIn(1, 10)
        val provider = args.optString("provider", "").trim()
        // permission_id handling is done at the AgentRuntime level
        return deviceBridge.executeWebSearch(query, maxResults, provider, "")
    }

    private fun executeCloudRequest(args: JSONObject): JSONObject {
        var req = args.optJSONObject("request")
        if (req == null) {
            // Ergonomic fallback: accept top-level request fields
            val hasReqFields = args.has("url") || args.has("method") || args.has("headers") || args.has("json") || args.has("body")
            if (hasReqFields) {
                req = args
            } else {
                return JSONObject().put("status", "error").put("error", "invalid_request")
            }
        }
        val payload = JSONObject().put("request", req).put("identity", sessionIdProvider())
        return deviceBridge.executeCloudRequest(payload)
    }

    private fun executeSleep(args: JSONObject): JSONObject {
        val seconds = args.optDouble("seconds", 1.0).coerceIn(0.1, 30.0)
        Thread.sleep((seconds * 1000).toLong())
        return JSONObject().put("status", "ok").put("slept", seconds)
    }

    private fun executeAnalyzeMedia(args: JSONObject, mediaType: String): JSONObject {
        // Guard: fail early if the current provider doesn't support this media type
        if (mediaType !in supportedMediaTypes) {
            val detail = if (mediaType == "audio") {
                "The current provider does not support audio input. Use a Gemini model for audio analysis."
            } else {
                "The current provider does not support $mediaType input."
            }
            return JSONObject()
                .put("status", "error")
                .put("error", "media_not_supported")
                .put("media_type", mediaType)
                .put("supported_types", JSONArray(supportedMediaTypes.toList()))
                .put("detail", detail)
        }

        val path = args.optString("path", "")
        val dataB64 = args.optString("data_b64", "")
        val mimeTypeOverride = args.optString("mime_type", "")
        val prompt = args.optString("prompt", "")

        if (path.isEmpty() && dataB64.isEmpty()) {
            return JSONObject().put("status", "error").put("error", "missing_path_or_data")
                .put("detail", "Provide either 'path' (file path) or 'data_b64' (base64 data)")
        }

        // If base64 data is provided directly
        if (dataB64.isNotEmpty()) {
            val mime = mimeTypeOverride.ifEmpty {
                if (mediaType == "image") "image/webp" else "audio/mp4"
            }
            val result = JSONObject().put("status", "ok")
                .put("_media", JSONObject().apply {
                    put("type", mediaType)
                    put("base64", dataB64)
                    put("mime_type", mime)
                })
                .put("source", "data_b64")
            if (prompt.isNotEmpty()) result.put("prompt", prompt)
            return result
        }

        // Resolve file path
        val target = resolveSecure(userDir, path) ?: return pathError("path_outside_base")
        if (!target.exists()) return JSONObject().put("status", "error").put("error", "not_found").put("path", path)
        if (!target.isFile) return JSONObject().put("status", "error").put("error", "not_a_file").put("path", path)

        val encoded = if (mediaType == "image") {
            val maxDim = if (imageResizeEnabled) imageMaxDimPx else Int.MAX_VALUE
            MediaEncoder.encodeImage(target, maxDim, imageJpegQuality)
        } else {
            MediaEncoder.encodeAudio(target)
        }

        if (encoded == null) {
            return JSONObject().put("status", "error")
                .put("error", "encode_failed")
                .put("detail", "Could not encode $mediaType file: ${target.name}")
                .put("path", path)
        }

        val result = JSONObject().put("status", "ok")
            .put("_media", JSONObject().apply {
                put("type", encoded.mediaType)
                put("base64", encoded.base64)
                put("mime_type", encoded.mimeType)
            })
            .put("file", target.name)
            .put("path", path)
        // Merge metadata (width/height for images, duration_ms/size for audio)
        val meta = encoded.metadata
        val metaKeys = meta.keys()
        while (metaKeys.hasNext()) {
            val key = metaKeys.next()
            result.put(key, meta.get(key))
        }
        if (prompt.isNotEmpty()) result.put("prompt", prompt)
        return result
    }

    private fun resolveSecure(baseDir: File, relativePath: String): File? {
        val resolved = File(baseDir, relativePath).canonicalFile
        return if (resolved.absolutePath.startsWith(baseDir.canonicalPath)) resolved else null
    }

    private fun pathError(error: String): JSONObject {
        return JSONObject().put("status", "error").put("error", error)
    }

    private fun explicitPersistMemoryRequested(userText: String): Boolean {
        val lower = userText.lowercase()
        return lower.contains("save") || lower.contains("store") || lower.contains("persist") ||
            lower.contains("remember") || lower.contains("memo") || lower.contains("write") ||
            lower.contains("記憶") || lower.contains("覚え") || lower.contains("保存")
    }

    companion object {
        private const val TAG = "ToolExecutor"

        /** Strip base64 media data fields when media is sent as a separate multimodal block. */
        fun stripMediaData(result: JSONObject): JSONObject {
            val out = JSONObject(result.toString())
            var stripped = false
            // Strip _media marker (from analyze_image/analyze_audio)
            if (out.has("_media")) {
                out.remove("_media")
                stripped = true
            }
            for (key in listOf("data_b64", "body_base64")) {
                if (out.has(key)) {
                    out.remove(key)
                    out.put("${key}_stripped", true)
                    stripped = true
                }
            }
            // Also strip from nested "result" object (e.g. device_api responses)
            val nested = out.optJSONObject("result")
            if (nested != null) {
                for (key in listOf("data_b64", "body_base64")) {
                    if (nested.has(key)) {
                        nested.remove(key)
                        nested.put("${key}_stripped", true)
                        stripped = true
                    }
                }
            }
            if (stripped) out.put("media_sent_separately", true)
            return out
        }

        fun truncateToolOutput(result: JSONObject, maxChars: Int = 12000, maxListItems: Int = 80): JSONObject {
            val raw = result.toString()
            if (raw.length <= maxChars) return result

            return try {
                shrinkJson(result, maxChars, maxListItems, 0)
            } catch (_: Exception) {
                JSONObject().apply {
                    put("status", result.optString("status", ""))
                    put("error", result.optString("error", ""))
                    put("http_status", result.optInt("http_status", 0))
                    put("truncated_for_model", true)
                    put("detail", "tool_output_too_large")
                }
            }
        }

        private fun shrinkJson(obj: JSONObject, maxChars: Int, maxListItems: Int, depth: Int): JSONObject {
            if (depth > 5) return JSONObject().put("truncated_for_model", true)

            val out = JSONObject()
            val priorityKeys = listOf("status", "error", "http_status", "code", "path", "truncated", "detail")
            val orderedKeys = mutableListOf<String>()
            for (k in priorityKeys) {
                if (obj.has(k)) orderedKeys.add(k)
            }
            val iter = obj.keys()
            while (iter.hasNext()) {
                val k = iter.next()
                if (k !in orderedKeys) orderedKeys.add(k)
            }

            for (k in orderedKeys.take(200)) {
                val v = obj.opt(k)
                when {
                    k in setOf("content", "output", "stderr", "stdout") && v is String -> {
                        out.put(k, clipStr(v, 4096))
                        if (v.length > 4096) {
                            out.put("truncated_for_model", true)
                            out.put("${k}_len", v.length)
                        }
                    }
                    k in setOf("data_b64", "body_base64") && v is String -> {
                        out.put(k, clipStr(v, 2048))
                        out.put("truncated_for_model", true)
                        out.put("${k}_len", v.length)
                    }
                    v is JSONArray -> {
                        val arr = JSONArray()
                        for (i in 0 until v.length().coerceAtMost(maxListItems)) {
                            val item = v.opt(i)
                            arr.put(if (item is String) clipStr(item, 4096) else item)
                        }
                        if (v.length() > maxListItems) {
                            arr.put(JSONObject().put("truncated_for_model", true).put("omitted_items", v.length() - maxListItems))
                        }
                        out.put(k, arr)
                    }
                    v is JSONObject -> out.put(k, shrinkJson(v, maxChars, maxListItems, depth + 1))
                    v is String -> out.put(k, clipStr(v, 4096))
                    else -> out.put(k, v)
                }
            }
            return out
        }

        private fun clipStr(s: String, n: Int): String {
            if (s.length <= n) return s
            val headLen = (n - 200).coerceAtLeast(0)
            val head = s.substring(0, headLen)
            val tail = if (n > 400) s.substring(s.length - 200) else ""
            return head + "\n...[truncated_for_model]...\n" + tail
        }
    }
}
