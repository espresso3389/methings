package jp.espresso3389.methings.device

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class AudioPlaybackManager(private val context: Context) {
    private val lock = Any()
    private val userRoot = File(context.filesDir, "user")
    private val tmpRoot = File(userRoot, "tmp")

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var state: String = "idle"
    @Volatile private var playbackId: String? = null
    @Volatile private var sourcePath: String? = null
    @Volatile private var startedAtMs: Long? = null
    @Volatile private var lastError: String? = null

    fun status(): Map<String, Any?> {
        return mapOf(
            "status" to "ok",
            "state" to state,
            "playback_id" to playbackId,
            "source_path" to sourcePath,
            "started_at_ms" to startedAtMs,
            "last_error" to lastError
        )
    }

    fun stop(): Map<String, Any?> {
        synchronized(lock) {
            val had = player != null
            releasePlayerLocked()
            if (had) state = "stopped"
            return mapOf("status" to "ok", "stopped" to had, "state" to state)
        }
    }

    fun play(path: String?, audioB64: String?, ext: String?): Map<String, Any?> {
        val source = resolveSource(path, audioB64, ext)
            ?: return mapOf("status" to "error", "error" to "missing_source", "detail" to "set path or audio_b64")

        synchronized(lock) {
            releasePlayerLocked()
            val mp = MediaPlayer()
            return try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setDataSource(source.absolutePath)
                mp.setOnCompletionListener {
                    synchronized(lock) {
                        state = "completed"
                        releasePlayerLocked()
                    }
                }
                mp.setOnErrorListener { _, what, extra ->
                    synchronized(lock) {
                        lastError = "MediaPlayer error what=$what extra=$extra"
                        state = "error"
                        releasePlayerLocked()
                    }
                    true
                }
                mp.prepare()
                mp.start()
                player = mp
                playbackId = "pb_" + UUID.randomUUID().toString().replace("-", "")
                sourcePath = relativizeToUser(source)
                startedAtMs = System.currentTimeMillis()
                state = "playing"
                lastError = null
                mapOf(
                    "status" to "ok",
                    "state" to state,
                    "playback_id" to playbackId,
                    "source_path" to sourcePath,
                    "started_at_ms" to startedAtMs
                )
            } catch (ex: Exception) {
                runCatching { mp.release() }
                lastError = ex.message ?: ex.javaClass.simpleName
                state = "error"
                mapOf("status" to "error", "error" to "play_failed", "detail" to lastError)
            }
        }
    }

    private fun releasePlayerLocked() {
        val p = player ?: return
        runCatching { p.stop() }
        runCatching { p.reset() }
        runCatching { p.release() }
        player = null
    }

    private fun resolveSource(path: String?, audioB64: String?, ext: String?): File? {
        val p = (path ?: "").trim()
        if (p.isNotEmpty()) {
            val f = if (File(p).isAbsolute) File(p) else File(userRoot, p)
            if (f.exists() && f.isFile) return f
        }
        val b64 = (audioB64 ?: "").trim()
        if (b64.isEmpty()) return null
        val data = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        tmpRoot.mkdirs()
        val safeExt = (ext ?: "wav").trim().lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "wav" }
        val file = File(tmpRoot, "play_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$safeExt")
        FileOutputStream(file).use { it.write(data) }
        return file
    }

    private fun relativizeToUser(file: File): String {
        return runCatching { userRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/') }
            .getOrDefault(file.absolutePath)
    }
}
