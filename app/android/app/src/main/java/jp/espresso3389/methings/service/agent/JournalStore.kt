package jp.espresso3389.methings.service.agent

import org.json.JSONObject
import java.io.File

class JournalStore(private val rootDir: File) {

    init {
        rootDir.mkdirs()
    }

    fun config(): JSONObject = JSONObject().apply {
        put("max_current_bytes", MAX_CURRENT_BYTES)
        put("max_entry_inline_bytes", MAX_ENTRY_INLINE_BYTES)
        put("rotate_entries_bytes", ROTATE_ENTRIES_BYTES)
        put("max_list_limit", MAX_LIST_LIMIT)
        put("root", rootDir.absolutePath)
    }

    fun getCurrent(sessionId: String): JSONObject {
        val sid = sanitizeSessionId(sessionId)
        val p = currentPath(sid)
        return try {
            if (!p.exists()) {
                JSONObject().put("status", "ok").put("session_id", sid).put("text", "").put("updated_at", 0)
            } else {
                val text = p.readText(Charsets.UTF_8)
                val updatedAt = p.lastModified()
                JSONObject().put("status", "ok").put("session_id", sid).put("text", text).put("updated_at", updatedAt)
            }
        } catch (ex: Exception) {
            JSONObject().put("status", "error").put("error", "read_failed").put("detail", ex.message ?: "")
        }
    }

    fun setCurrent(sessionId: String, text: String): JSONObject {
        val sid = sanitizeSessionId(sessionId)
        val p = currentPath(sid)
        val body = text
        val raw = body.toByteArray(Charsets.UTF_8)
        val ts = System.currentTimeMillis()
        return try {
            p.parentFile?.mkdirs()
            if (raw.size > MAX_CURRENT_BYTES) {
                val rotated = File(p.parentFile, "CURRENT.$ts.md")
                rotated.writeBytes(raw)
                val headLen = (MAX_CURRENT_BYTES - 256).coerceAtLeast(0)
                val head = String(raw, 0, headLen.coerceAtMost(raw.size), Charsets.UTF_8).trimEnd()
                val note = "$head\n\n(journal: current note was too large and was moved to ${rotated.name})\n"
                p.writeText(note, Charsets.UTF_8)
                JSONObject()
                    .put("status", "ok")
                    .put("session_id", sid)
                    .put("rotated_to", rotated.absolutePath)
                    .put("truncated", true)
                    .put("updated_at", ts)
            } else {
                p.writeBytes(raw)
                JSONObject()
                    .put("status", "ok")
                    .put("session_id", sid)
                    .put("truncated", false)
                    .put("updated_at", ts)
            }
        } catch (ex: Exception) {
            JSONObject().put("status", "error").put("error", "write_failed").put("detail", ex.message ?: "")
        }
    }

    fun append(sessionId: String, kind: String, title: String, text: String, meta: JSONObject? = null): JSONObject {
        val sid = sanitizeSessionId(sessionId)
        val k = kind.trim().ifEmpty { "note" }
        val t = title.trim()
        val body = text
        val m = meta ?: JSONObject()
        val ts = System.currentTimeMillis()

        val sessDir = sessionDir(sid)
        val entriesPath = File(sessDir, "entries.jsonl")
        val rotated = rotateEntriesIfNeeded(entriesPath)

        var storedPath = ""
        var inlineText = body
        val raw = body.toByteArray(Charsets.UTF_8)
        if (raw.size > MAX_ENTRY_INLINE_BYTES) {
            val fname = if (t.isNotEmpty()) "entry.$ts.${safeBasename(t)}.md" else "entry.$ts.md"
            val entryFile = File(sessDir, fname)
            try {
                entryFile.writeBytes(raw)
                storedPath = entryFile.absolutePath
                inlineText = ""
            } catch (_: Exception) {
                inlineText = String(raw, 0, MAX_ENTRY_INLINE_BYTES.coerceAtMost(raw.size), Charsets.UTF_8)
            }
        }

        val rec = JSONObject().apply {
            put("ts", ts)
            put("kind", k)
            put("title", t)
            put("text", inlineText)
            put("stored_path", storedPath)
            put("meta", m)
        }

        return try {
            entriesPath.parentFile?.mkdirs()
            entriesPath.appendText(rec.toString() + "\n", Charsets.UTF_8)
            JSONObject()
                .put("status", "ok")
                .put("session_id", sid)
                .put("ts", ts)
                .put("rotated_from", rotated?.absolutePath ?: "")
                .put("stored_path", storedPath)
        } catch (ex: Exception) {
            JSONObject().put("status", "error").put("error", "append_failed").put("detail", ex.message ?: "")
        }
    }

    fun listEntries(sessionId: String, limit: Int = 50): JSONObject {
        val sid = sanitizeSessionId(sessionId)
        val lim = limit.coerceIn(1, MAX_LIST_LIMIT)

        val items = mutableListOf<JSONObject>()
        for (p in entriesFilesNewestFirst(sid)) {
            if (items.size >= lim) break
            val text = try {
                p.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            val lines = text.lines().filter { it.isNotBlank() }
            for (ln in lines.reversed()) {
                if (items.size >= lim) break
                val rec = try {
                    JSONObject(ln)
                } catch (_: Exception) {
                    continue
                }
                items.add(
                    JSONObject().apply {
                        put("ts", rec.optLong("ts", 0))
                        put("kind", rec.optString("kind", ""))
                        put("title", rec.optString("title", ""))
                        put("text", rec.optString("text", ""))
                        put("stored_path", rec.optString("stored_path", ""))
                        put("meta", rec.optJSONObject("meta") ?: JSONObject())
                    }
                )
            }
        }
        items.sortBy { it.optLong("ts", 0) }
        val trimmed = if (items.size > lim) items.takeLast(lim) else items

        val arr = org.json.JSONArray()
        for (item in trimmed) arr.put(item)

        return JSONObject()
            .put("status", "ok")
            .put("session_id", sid)
            .put("entries", arr)
            .put("limit", lim)
    }

    fun renameSession(oldId: String, newId: String): JSONObject {
        val oldSid = sanitizeSessionId(oldId)
        val newSid = sanitizeSessionId(newId)
        if (oldSid == newSid) {
            return JSONObject().put("status", "ok").put("renamed", false).put("reason", "same_id")
        }
        val oldDir = File(rootDir, oldSid).let {
            val resolved = it.canonicalFile
            if (!resolved.absolutePath.startsWith(rootDir.canonicalPath) || !resolved.exists()) {
                return JSONObject().put("status", "ok").put("renamed", false).put("reason", "source_not_found")
            }
            resolved
        }
        val newDir = File(rootDir, newSid).canonicalFile
        if (!newDir.absolutePath.startsWith(rootDir.canonicalPath)) {
            return JSONObject().put("status", "error").put("error", "invalid_new_id")
        }
        if (newDir.exists() && (newDir.list()?.isNotEmpty() == true)) {
            return JSONObject().put("status", "error").put("error", "target_exists")
        }
        return try {
            oldDir.renameTo(newDir)
            JSONObject().put("status", "ok").put("renamed", true).put("old_id", oldSid).put("new_id", newSid)
        } catch (ex: Exception) {
            JSONObject().put("status", "error").put("error", "rename_failed").put("detail", ex.message ?: "")
        }
    }

    private fun sessionDir(sessionId: String): File {
        val sid = sanitizeSessionId(sessionId)
        val dir = File(rootDir, sid).canonicalFile
        if (!dir.absolutePath.startsWith(rootDir.canonicalPath)) {
            return File(rootDir, "default").also { it.mkdirs() }
        }
        dir.mkdirs()
        return dir
    }

    private fun currentPath(sessionId: String): File = File(sessionDir(sessionId), "CURRENT.md")

    private fun rotateEntriesIfNeeded(entriesPath: File): File? {
        return try {
            if (!entriesPath.exists()) return null
            if (entriesPath.length() < ROTATE_ENTRIES_BYTES) return null
            val ts = System.currentTimeMillis()
            val rotated = File(entriesPath.parentFile, "entries.$ts.jsonl")
            entriesPath.renameTo(rotated)
            rotated
        } catch (_: Exception) {
            null
        }
    }

    private fun entriesFilesNewestFirst(sessionId: String): List<File> {
        val sessDir = sessionDir(sessionId)
        val cur = File(sessDir, "entries.jsonl")
        val rotated = sessDir.listFiles { _, name -> name.startsWith("entries.") && name.endsWith(".jsonl") && name != "entries.jsonl" }
            ?.sortedByDescending { it.name }
            ?: emptyList()
        val out = mutableListOf<File>()
        if (cur.exists()) out.add(cur)
        out.addAll(rotated)
        return out
    }

    companion object {
        private const val MAX_CURRENT_BYTES = 8 * 1024
        private const val MAX_ENTRY_INLINE_BYTES = 16 * 1024
        private const val ROTATE_ENTRIES_BYTES = 256L * 1024
        private const val MAX_LIST_LIMIT = 200

        private val SESSION_ID_SAFE = Regex("[^A-Za-z0-9_.-]+")

        fun sanitizeSessionId(sessionId: String): String {
            var s = sessionId.trim()
            if (s.isEmpty()) return "default"
            s = SESSION_ID_SAFE.replace(s, "_")
            s = s.trim('.', '_', '-')
            if (s.isEmpty()) return "default"
            return s.take(80)
        }

        private fun safeBasename(name: String): String {
            var s = name.trim()
            if (s.isEmpty()) return "note"
            s = SESSION_ID_SAFE.replace(s, "_")
            s = s.trim('.', '_', '-')
            return (s.ifEmpty { "note" }).take(80)
        }
    }
}
