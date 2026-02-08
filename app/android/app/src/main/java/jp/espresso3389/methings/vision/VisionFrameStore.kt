package jp.espresso3389.methings.vision

import java.util.LinkedHashMap
import java.util.UUID

class VisionFrameStore(
    private val maxBytes: Long = 64L * 1024L * 1024L,
    private val maxFrames: Int = 16,
) {
    private data class Entry(val frame: RgbaFrame, val bytes: Int)

    private val lock = Any()
    private val lru = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalBytes: Long = 0

    fun put(frame: RgbaFrame): String {
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            val bytes = frame.rgba.size
            lru[id] = Entry(frame, bytes)
            totalBytes += bytes.toLong()
            evictLocked()
        }
        return id
    }

    fun get(id: String): RgbaFrame? {
        val key = id.trim()
        if (key.isEmpty()) return null
        synchronized(lock) {
            return lru[key]?.frame
        }
    }

    fun delete(id: String): Boolean {
        val key = id.trim()
        if (key.isEmpty()) return false
        synchronized(lock) {
            val removed = lru.remove(key) ?: return false
            totalBytes -= removed.bytes.toLong()
            if (totalBytes < 0) totalBytes = 0
            return true
        }
    }

    fun stats(): Map<String, Any> {
        synchronized(lock) {
            return mapOf(
                "frames" to lru.size,
                "bytes" to totalBytes,
                "max_bytes" to maxBytes,
                "max_frames" to maxFrames,
            )
        }
    }

    private fun evictLocked() {
        while (lru.size > maxFrames || totalBytes > maxBytes) {
            val it = lru.entries.iterator()
            if (!it.hasNext()) break
            val e = it.next()
            totalBytes -= e.value.bytes.toLong()
            it.remove()
        }
        if (totalBytes < 0) totalBytes = 0
    }
}

