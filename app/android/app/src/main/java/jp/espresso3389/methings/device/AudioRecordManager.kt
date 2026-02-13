package jp.espresso3389.methings.device

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecordManager(private val context: Context) {
    companion object {
        private const val TAG = "AudioRecordManager"
        private const val PREFS = "audio_record_config"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val userRoot = File(context.filesDir, "user")
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()

    // Recording state
    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var recordingState: String = "idle"
    @Volatile private var recordingFile: File? = null
    @Volatile private var recordingStartMs: Long = 0L
    @Volatile private var lastRecordError: String? = null

    // PCM streaming state
    private val streaming = AtomicBoolean(false)
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var streamThread: Thread? = null
    @Volatile private var streamSampleRate: Int = 44100
    @Volatile private var streamChannels: Int = 1
    private val wsClients = CopyOnWriteArrayList<NanoWSD.WebSocket>()

    // ── Config ────────────────────────────────────────────────────────────────

    fun getConfig(): Map<String, Any?> {
        return mapOf(
            "status" to "ok",
            "sample_rate" to prefs.getInt("sample_rate", 44100),
            "channels" to prefs.getInt("channels", 1),
            "bitrate" to prefs.getInt("bitrate", 128000),
            "max_duration_s" to prefs.getInt("max_duration_s", 300)
        )
    }

    fun setConfig(payload: JSONObject): Map<String, Any?> {
        val ed = prefs.edit()
        if (payload.has("sample_rate")) {
            ed.putInt("sample_rate", payload.optInt("sample_rate", 44100).coerceIn(8000, 48000))
        }
        if (payload.has("channels")) {
            ed.putInt("channels", payload.optInt("channels", 1).coerceIn(1, 2))
        }
        if (payload.has("bitrate")) {
            ed.putInt("bitrate", payload.optInt("bitrate", 128000).coerceIn(32000, 320000))
        }
        if (payload.has("max_duration_s")) {
            ed.putInt("max_duration_s", payload.optInt("max_duration_s", 300).coerceIn(5, 3600))
        }
        ed.apply()
        return getConfig()
    }

    // ── Status ────────────────────────────────────────────────────────────────

    fun status(): Map<String, Any?> {
        val durationMs = if (recordingState == "recording") System.currentTimeMillis() - recordingStartMs else null
        return mapOf(
            "status" to "ok",
            "recording" to (recordingState == "recording"),
            "recording_state" to recordingState,
            "recording_duration_ms" to durationMs,
            "streaming" to streaming.get(),
            "stream_sample_rate" to if (streaming.get()) streamSampleRate else null,
            "stream_channels" to if (streaming.get()) streamChannels else null,
            "ws_clients" to wsClients.size,
            "last_error" to lastRecordError
        )
    }

    // ── Recording (AAC → .m4a) ────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startRecording(
        path: String?,
        maxDurationS: Int?,
        sampleRate: Int?,
        channels: Int?,
        bitrate: Int?
    ): Map<String, Any?> {
        synchronized(lock) {
            if (recordingState == "recording") {
                return mapOf("status" to "error", "error" to "already_recording")
            }

            val sr = (sampleRate ?: prefs.getInt("sample_rate", 44100)).coerceIn(8000, 48000)
            val ch = (channels ?: prefs.getInt("channels", 1)).coerceIn(1, 2)
            val br = (bitrate ?: prefs.getInt("bitrate", 128000)).coerceIn(32000, 320000)
            val maxS = (maxDurationS ?: prefs.getInt("max_duration_s", 300)).coerceIn(5, 3600)

            val recDir = File(userRoot, "recordings/audio")
            recDir.mkdirs()
            val fileName = path?.trim()?.ifBlank { null }
                ?: "rec_${System.currentTimeMillis()}.m4a"
            val outFile = if (fileName.startsWith("/")) File(fileName) else File(recDir, fileName)
            outFile.parentFile?.mkdirs()

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            return try {
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioSamplingRate(sr)
                mr.setAudioChannels(ch)
                mr.setAudioEncodingBitRate(br)
                mr.setMaxDuration(maxS * 1000)
                mr.setOutputFile(outFile.absolutePath)
                mr.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Log.i(TAG, "Max duration reached, auto-stopping")
                        executor.execute { stopRecording() }
                    }
                }
                mr.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error: what=$what extra=$extra")
                    lastRecordError = "recorder_error_$what"
                    executor.execute { stopRecording() }
                }
                mr.prepare()
                mr.start()

                recorder = mr
                recordingFile = outFile
                recordingStartMs = System.currentTimeMillis()
                recordingState = "recording"
                lastRecordError = null

                val relPath = outFile.absolutePath.removePrefix(userRoot.absolutePath).trimStart('/')
                mapOf(
                    "status" to "ok",
                    "state" to "recording",
                    "rel_path" to relPath,
                    "sample_rate" to sr,
                    "channels" to ch,
                    "bitrate" to br,
                    "max_duration_s" to maxS,
                    "format" to "aac",
                    "container" to "m4a"
                )
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                mr.runCatching { release() }
                recordingState = "error"
                lastRecordError = e.message ?: "start_failed"
                mapOf("status" to "error", "error" to "start_failed", "detail" to (e.message ?: ""))
            }
        }
    }

    fun stopRecording(): Map<String, Any?> {
        synchronized(lock) {
            val mr = recorder
            if (mr == null || recordingState != "recording") {
                return mapOf("status" to "ok", "stopped" to false, "state" to recordingState)
            }

            val durationMs = System.currentTimeMillis() - recordingStartMs
            return try {
                mr.stop()
                mr.release()
                recorder = null
                recordingState = "idle"

                val outFile = recordingFile
                val relPath = outFile?.absolutePath?.removePrefix(userRoot.absolutePath)?.trimStart('/') ?: ""
                val sizeBytes = outFile?.length() ?: 0L
                recordingFile = null

                mapOf(
                    "status" to "ok",
                    "stopped" to true,
                    "state" to "idle",
                    "rel_path" to relPath,
                    "duration_ms" to durationMs,
                    "size_bytes" to sizeBytes
                )
            } catch (e: Exception) {
                Log.e(TAG, "stopRecording failed", e)
                mr.runCatching { release() }
                recorder = null
                recordingState = "error"
                lastRecordError = e.message ?: "stop_failed"
                mapOf("status" to "error", "error" to "stop_failed", "detail" to (e.message ?: ""))
            }
        }
    }

    // ── PCM Streaming ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startStream(sampleRate: Int?, channels: Int?): Map<String, Any?> {
        if (streaming.get()) {
            return mapOf("status" to "error", "error" to "already_streaming")
        }

        val sr = (sampleRate ?: prefs.getInt("sample_rate", 44100)).coerceIn(8000, 48000)
        val ch = (channels ?: prefs.getInt("channels", 1)).coerceIn(1, 2)
        val channelConfig = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val bufSize = AudioRecord.getMinBufferSize(sr, channelConfig, encoding)
        if (bufSize <= 0) {
            return mapOf("status" to "error", "error" to "unsupported_config",
                "detail" to "AudioRecord.getMinBufferSize returned $bufSize")
        }

        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sr, channelConfig, encoding,
            bufSize * 2
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return mapOf("status" to "error", "error" to "audio_record_init_failed")
        }

        audioRecord = ar
        streamSampleRate = sr
        streamChannels = ch
        streaming.set(true)

        val readBuf = ByteArray(bufSize)
        val thread = Thread({
            try {
                ar.startRecording()
                // Send hello message to all connected clients
                broadcastHello()
                while (streaming.get()) {
                    val read = ar.read(readBuf, 0, readBuf.size)
                    if (read > 0) {
                        val payload = if (read == readBuf.size) readBuf else readBuf.copyOf(read)
                        broadcastBinary(payload)
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord.read returned $read")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PCM stream thread error", e)
            } finally {
                ar.runCatching { stop() }
                ar.runCatching { release() }
                streaming.set(false)
                audioRecord = null
            }
        }, "AudioPcmStream")
        thread.start()
        streamThread = thread

        return mapOf(
            "status" to "ok",
            "streaming" to true,
            "ws_path" to "/ws/audio/pcm",
            "sample_rate" to sr,
            "channels" to ch,
            "encoding" to "pcm_s16le"
        )
    }

    fun stopStream(): Map<String, Any?> {
        if (!streaming.getAndSet(false)) {
            return mapOf("status" to "ok", "stopped" to false)
        }
        streamThread?.runCatching { join(3000) }
        streamThread = null
        return mapOf("status" to "ok", "stopped" to true)
    }

    // ── WebSocket client management ───────────────────────────────────────────

    fun addWsClient(ws: NanoWSD.WebSocket) {
        wsClients.add(ws)
        // If already streaming, send hello to the new client
        if (streaming.get()) {
            val hello = JSONObject()
                .put("type", "hello")
                .put("sample_rate", streamSampleRate)
                .put("channels", streamChannels)
                .put("encoding", "pcm_s16le")
            runCatching { ws.send(hello.toString()) }
        }
    }

    fun removeWsClient(ws: NanoWSD.WebSocket) {
        wsClients.remove(ws)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun broadcastHello() {
        val hello = JSONObject()
            .put("type", "hello")
            .put("sample_rate", streamSampleRate)
            .put("channels", streamChannels)
            .put("encoding", "pcm_s16le")
        val text = hello.toString()
        val dead = ArrayList<NanoWSD.WebSocket>()
        for (ws in wsClients) {
            try {
                if (ws.isOpen) ws.send(text) else dead.add(ws)
            } catch (_: Exception) {
                dead.add(ws)
            }
        }
        for (ws in dead) wsClients.remove(ws)
    }

    private fun broadcastBinary(payload: ByteArray) {
        if (wsClients.isEmpty()) return
        val dead = ArrayList<NanoWSD.WebSocket>()
        for (ws in wsClients) {
            try {
                if (ws.isOpen) ws.send(payload) else dead.add(ws)
            } catch (_: Exception) {
                dead.add(ws)
            }
        }
        for (ws in dead) wsClients.remove(ws)
    }
}
