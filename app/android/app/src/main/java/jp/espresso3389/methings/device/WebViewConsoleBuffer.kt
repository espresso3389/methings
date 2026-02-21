package jp.espresso3389.methings.device

import android.webkit.ConsoleMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared ring buffer that captures console output from all WebViews
 * (chat, browser panel, agent HTML).  Exposed via /webview/console API.
 */
object WebViewConsoleBuffer {

    data class Entry(
        val id: Long,
        val timestamp: Long,
        val level: String,
        val message: String,
        val lineNumber: Int,
        val sourceId: String,
        val source: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("timestamp", timestamp)
            put("level", level)
            put("message", message)
            put("line", lineNumber)
            put("source_id", sourceId)
            put("source", source)
        }
    }

    private const val MAX_ENTRIES = 500
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)
    private var nextId = 1L
    private val lock = Any()

    fun add(level: String, message: String, lineNumber: Int, sourceId: String, source: String) {
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(
                Entry(
                    id = nextId++,
                    timestamp = System.currentTimeMillis(),
                    level = level,
                    message = message,
                    lineNumber = lineNumber,
                    sourceId = sourceId,
                    source = source
                )
            )
        }
    }

    fun addFromConsoleMessage(msg: ConsoleMessage, source: String) {
        add(
            level = mapLevel(msg.messageLevel()),
            message = msg.message() ?: "",
            lineNumber = msg.lineNumber(),
            sourceId = msg.sourceId() ?: "",
            source = source
        )
    }

    fun getEntries(since: Long = 0, source: String? = null, limit: Int = 100): JSONArray {
        val result = JSONArray()
        synchronized(lock) {
            var count = 0
            // iterate newest-first so we can respect limit easily
            for (i in buffer.indices.reversed()) {
                val e = buffer[i]
                if (e.timestamp < since) continue
                if (source != null && e.source != source) continue
                count++
                if (count > limit) break
                result.put(e.toJson())
            }
        }
        // reverse so oldest-first in output
        val arr = JSONArray()
        for (i in (result.length() - 1) downTo 0) arr.put(result.get(i))
        return arr
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    private fun mapLevel(level: ConsoleMessage.MessageLevel): String = when (level) {
        ConsoleMessage.MessageLevel.LOG -> "log"
        ConsoleMessage.MessageLevel.WARNING -> "warn"
        ConsoleMessage.MessageLevel.ERROR -> "error"
        ConsoleMessage.MessageLevel.DEBUG -> "debug"
        ConsoleMessage.MessageLevel.TIP -> "info"
    }
}
