package jp.espresso3389.methings.service.agent

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

class ToolExecutor(
    private val userDir: File,
    private val sysDir: File,
    private val journalStore: JournalStore,
    private val deviceBridge: DeviceToolBridge,
    private val shellExec: ((cmd: String, args: String, cwd: String) -> JSONObject)?,
    private val sessionIdProvider: () -> String,
) {
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
                "run_python" -> executeShellExec("python", args)
                "run_pip" -> executeShellExec("pip", args)
                "run_curl" -> executeShellExec("curl", args)
                "shell_exec" -> {
                    val cmd = args.optString("cmd", "")
                    executeShellExec(cmd, args)
                }
                "web_search" -> executeWebSearch(args)
                "cloud_request" -> executeCloudRequest(args)
                "sleep" -> executeSleep(args)
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

    private fun executeShellExec(cmd: String, args: JSONObject): JSONObject {
        val allowed = setOf("python", "pip", "curl")
        if (cmd !in allowed) {
            return JSONObject().put("status", "error").put("error", "command_not_allowed").put("cmd", cmd)
        }
        val exec = shellExec
            ?: return JSONObject().put("status", "error").put("error", "shell_unavailable")
                .put("detail", "Termux is not installed or not running. Install Termux for Python/pip/curl support.")
        val cmdArgs = args.optString("args", "")
        val cwd = args.optString("cwd", "")
        return exec(cmd, cmdArgs, cwd)
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
