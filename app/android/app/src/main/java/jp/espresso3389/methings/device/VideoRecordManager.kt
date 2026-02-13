package jp.espresso3389.methings.device

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VideoRecordManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    companion object {
        private const val TAG = "VideoRecordManager"
        private const val PREFS = "video_record_config"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val userRoot = File(context.filesDir, "user")
    private val main = Handler(Looper.getMainLooper())

    // Recording state
    private val recordingLock = Any()
    @Volatile private var activeRecording: Recording? = null
    @Volatile private var recordingState: String = "idle"
    @Volatile private var recordingFile: File? = null
    @Volatile private var recordingStartMs: Long = 0L
    @Volatile private var recordingCodec: String = "unknown"
    @Volatile private var lastRecordError: String? = null

    // CameraX recording use cases (bound on main thread)
    @Volatile private var recordProvider: ProcessCameraProvider? = null
    @Volatile private var videoCapture: VideoCapture<Recorder>? = null

    // Frame streaming state
    private val streaming = AtomicBoolean(false)
    @Volatile private var streamProvider: ProcessCameraProvider? = null
    @Volatile private var streamAnalysis: ImageAnalysis? = null
    private val streamExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var streamFormat: String = "jpeg"
    @Volatile private var streamJpegQuality: Int = 70
    @Volatile private var streamFps: Int = 5
    @Volatile private var streamWidth: Int = 640
    @Volatile private var streamHeight: Int = 480
    @Volatile private var lastFrameAtMs: Long = 0
    private val wsClients = CopyOnWriteArrayList<NanoWSD.WebSocket>()

    // ── Config ────────────────────────────────────────────────────────────────

    fun getConfig(): Map<String, Any?> {
        return mapOf(
            "status" to "ok",
            "resolution" to prefs.getString("resolution", "720p"),
            "codec" to prefs.getString("codec", "h265"),
            "max_duration_s" to prefs.getInt("max_duration_s", 300)
        )
    }

    fun setConfig(payload: JSONObject): Map<String, Any?> {
        val ed = prefs.edit()
        if (payload.has("resolution")) {
            val r = payload.optString("resolution", "720p").trim().lowercase()
            if (r in listOf("720p", "1080p", "4k", "2160p")) {
                ed.putString("resolution", if (r == "2160p") "4k" else r)
            }
        }
        if (payload.has("codec")) {
            val c = payload.optString("codec", "h265").trim().lowercase()
            if (c in listOf("h265", "hevc", "h264", "avc")) {
                ed.putString("codec", if (c == "hevc") "h265" else if (c == "avc") "h264" else c)
            }
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
            "recording_codec" to if (recordingState == "recording") recordingCodec else null,
            "streaming" to streaming.get(),
            "stream_format" to if (streaming.get()) streamFormat else null,
            "stream_fps" to if (streaming.get()) streamFps else null,
            "ws_clients" to wsClients.size,
            "last_error" to lastRecordError
        )
    }

    // ── Video Recording (CameraX VideoCapture → .mp4) ────────────────────────

    @SuppressLint("MissingPermission")
    fun startRecording(
        outPath: String?,
        lens: String?,
        maxDurationS: Int?,
        resolution: String?
    ): Map<String, Any?> {
        synchronized(recordingLock) {
            if (recordingState == "recording") {
                return mapOf("status" to "error", "error" to "already_recording")
            }

            val res = (resolution ?: prefs.getString("resolution", "720p") ?: "720p").trim().lowercase()
            val prefCodec = (prefs.getString("codec", "h265") ?: "h265").trim().lowercase()
            val maxS = (maxDurationS ?: prefs.getInt("max_duration_s", 300)).coerceIn(5, 3600)
            val facing = parseLens(lens)

            val recDir = File(userRoot, "recordings/video")
            recDir.mkdirs()
            val fileName = outPath?.trim()?.ifBlank { null }
                ?: "vid_${System.currentTimeMillis()}.mp4"
            val outFile = if (fileName.startsWith("/")) File(fileName) else File(recDir, fileName)
            outFile.parentFile?.mkdirs()

            val quality = when (res) {
                "4k", "2160p" -> Quality.UHD
                "1080p" -> Quality.FHD
                else -> Quality.HD
            }

            val codec = resolveCodec(prefCodec)
            recordingCodec = codec

            // We need to bind on main thread and get the recording started
            val latch = java.util.concurrent.CountDownLatch(1)
            var error: String? = null

            main.post {
                try {
                    val providerFuture = ProcessCameraProvider.getInstance(context)
                    providerFuture.addListener({
                        try {
                            val provider = providerFuture.get()
                            recordProvider = provider
                            provider.unbindAll()

                            val recorder = Recorder.Builder()
                                .setQualitySelector(QualitySelector.from(quality))
                                .build()
                            val vc = VideoCapture.withOutput(recorder)
                            videoCapture = vc

                            val selector = CameraSelector.Builder().requireLensFacing(facing).build()
                            provider.bindToLifecycle(lifecycleOwner, selector, vc)

                            val fileOpts = FileOutputOptions.Builder(outFile).apply {
                                setDurationLimitMillis(maxS * 1000L)
                            }.build()

                            val recording = vc.output
                                .prepareRecording(context, fileOpts)
                                .withAudioEnabled()
                                .start(ContextCompat.getMainExecutor(context)) { event ->
                                    when (event) {
                                        is VideoRecordEvent.Finalize -> {
                                            if (event.hasError()) {
                                                Log.e(TAG, "Recording finalize error: ${event.error}")
                                                lastRecordError = "finalize_error_${event.error}"
                                            }
                                            recordingState = "idle"
                                            activeRecording = null
                                        }
                                    }
                                }

                            activeRecording = recording
                            recordingFile = outFile
                            recordingStartMs = System.currentTimeMillis()
                            recordingState = "recording"
                            lastRecordError = null
                        } catch (e: Exception) {
                            Log.e(TAG, "startRecording bind failed", e)
                            error = e.message ?: "bind_failed"
                        } finally {
                            latch.countDown()
                        }
                    }, ContextCompat.getMainExecutor(context))
                } catch (e: Exception) {
                    Log.e(TAG, "startRecording failed", e)
                    error = e.message ?: "start_failed"
                    latch.countDown()
                }
            }

            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

            if (error != null) {
                recordingState = "error"
                lastRecordError = error
                return mapOf("status" to "error", "error" to "start_failed", "detail" to (error ?: ""))
            }

            val relPath = outFile.absolutePath.removePrefix(userRoot.absolutePath).trimStart('/')
            return mapOf(
                "status" to "ok",
                "state" to "recording",
                "rel_path" to relPath,
                "resolution" to res,
                "codec" to codec,
                "max_duration_s" to maxS,
                "lens" to (if (facing == CameraSelector.LENS_FACING_FRONT) "front" else "back"),
                "container" to "mp4"
            )
        }
    }

    fun stopRecording(): Map<String, Any?> {
        synchronized(recordingLock) {
            val rec = activeRecording
            if (rec == null || recordingState != "recording") {
                return mapOf("status" to "ok", "stopped" to false, "state" to recordingState)
            }

            val durationMs = System.currentTimeMillis() - recordingStartMs
            rec.stop()
            activeRecording = null
            recordingState = "idle"

            // Unbind recording use cases
            main.post {
                recordProvider?.runCatching { unbindAll() }
                recordProvider = null
                videoCapture = null
            }

            val outFile = recordingFile
            val relPath = outFile?.absolutePath?.removePrefix(userRoot.absolutePath)?.trimStart('/') ?: ""
            // File size may not be final yet since finalize happens async; report what we have
            val sizeBytes = outFile?.length() ?: 0L
            recordingFile = null

            return mapOf(
                "status" to "ok",
                "stopped" to true,
                "state" to "idle",
                "rel_path" to relPath,
                "duration_ms" to durationMs,
                "size_bytes" to sizeBytes,
                "codec" to recordingCodec
            )
        }
    }

    // ── Frame Streaming (ImageAnalysis → JPEG or RGBA over WebSocket) ─────────

    fun startStream(
        lens: String?,
        width: Int?,
        height: Int?,
        fps: Int?,
        format: String?,
        jpegQuality: Int?
    ): Map<String, Any?> {
        if (streaming.get()) {
            return mapOf("status" to "error", "error" to "already_streaming")
        }

        val facing = parseLens(lens)
        val w = (width ?: 640).coerceIn(160, 1920)
        val h = (height ?: 480).coerceIn(120, 1080)
        val f = (fps ?: 5).coerceIn(1, 30)
        val fmt = (format ?: "jpeg").trim().lowercase()
        val jq = (jpegQuality ?: 70).coerceIn(10, 95)

        if (fmt !in listOf("jpeg", "rgba")) {
            return mapOf("status" to "error", "error" to "invalid_format", "detail" to "Use 'jpeg' or 'rgba'")
        }

        streamWidth = w
        streamHeight = h
        streamFps = f
        streamFormat = fmt
        streamJpegQuality = jq
        streaming.set(true)
        lastFrameAtMs = 0

        val latch = java.util.concurrent.CountDownLatch(1)
        var error: String? = null

        main.post {
            try {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        streamProvider = provider

                        val ana = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(Size(w, h))
                            .build()

                        ana.setAnalyzer(streamExecutor) { img ->
                            try {
                                if (!streaming.get()) return@setAnalyzer
                                val now = System.currentTimeMillis()
                                val minDelta = (1000.0 / f.toDouble()).toLong()
                                if ((now - lastFrameAtMs) < minDelta) return@setAnalyzer
                                lastFrameAtMs = now

                                if (fmt == "rgba") {
                                    broadcastRgba(img)
                                } else {
                                    broadcastJpeg(img, jq)
                                }
                            } catch (_: Exception) {
                            } finally {
                                img.close()
                            }
                        }

                        val selector = CameraSelector.Builder().requireLensFacing(facing).build()
                        // Don't unbind all — only bind the analysis use case for streaming
                        // If recording is active, they share the camera but separate providers
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, selector, ana)
                        streamAnalysis = ana

                        // Send hello to connected clients
                        broadcastHello()
                    } catch (e: Exception) {
                        Log.e(TAG, "startStream bind failed", e)
                        error = e.message ?: "bind_failed"
                        streaming.set(false)
                    } finally {
                        latch.countDown()
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) {
                Log.e(TAG, "startStream failed", e)
                error = e.message ?: "start_failed"
                streaming.set(false)
                latch.countDown()
            }
        }

        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

        if (error != null) {
            return mapOf("status" to "error", "error" to "start_failed", "detail" to (error ?: ""))
        }

        return mapOf(
            "status" to "ok",
            "streaming" to true,
            "ws_path" to "/ws/video/frames",
            "format" to fmt,
            "width" to w,
            "height" to h,
            "fps" to f,
            "lens" to (if (facing == CameraSelector.LENS_FACING_FRONT) "front" else "back")
        )
    }

    fun stopStream(): Map<String, Any?> {
        if (!streaming.getAndSet(false)) {
            return mapOf("status" to "ok", "stopped" to false)
        }

        main.post {
            streamProvider?.runCatching { unbindAll() }
            streamProvider = null
            streamAnalysis = null
        }

        return mapOf("status" to "ok", "stopped" to true)
    }

    // ── WebSocket client management ───────────────────────────────────────────

    fun addWsClient(ws: NanoWSD.WebSocket) {
        wsClients.add(ws)
        if (streaming.get()) {
            val hello = JSONObject()
                .put("type", "hello")
                .put("format", streamFormat)
                .put("width", streamWidth)
                .put("height", streamHeight)
                .put("fps", streamFps)
            runCatching { ws.send(hello.toString()) }
        }
    }

    fun removeWsClient(ws: NanoWSD.WebSocket) {
        wsClients.remove(ws)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun parseLens(lens: String?): Int {
        return if ((lens ?: "back").trim().lowercase() == "front")
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
    }

    private fun resolveCodec(preferred: String): String {
        if (preferred == "h264") return "h264"
        // Check if HEVC encoder is available
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hasHevc = codecList.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
        }
        return if (hasHevc) "h265" else "h264"
    }

    private fun broadcastHello() {
        val hello = JSONObject()
            .put("type", "hello")
            .put("format", streamFormat)
            .put("width", streamWidth)
            .put("height", streamHeight)
            .put("fps", streamFps)
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

    private fun broadcastJpeg(image: ImageProxy, quality: Int) {
        if (wsClients.isEmpty()) return
        val w = image.width
        val h = image.height
        val nv21 = yuv420888ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), quality, out)
        broadcastBinary(out.toByteArray())
    }

    private fun broadcastRgba(image: ImageProxy) {
        if (wsClients.isEmpty()) return
        val w = image.width
        val h = image.height
        val rgba = yuv420888ToRgba(image)

        // 12-byte header: [width:u32le][height:u32le][ts_ms:u32le]
        val tsMs = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(w).putInt(h).putInt(tsMs).array()
        val payload = ByteArray(12 + rgba.size)
        System.arraycopy(header, 0, payload, 0, 12)
        System.arraycopy(rgba, 0, payload, 12, rgba.size)
        broadcastBinary(payload)
    }

    private fun broadcastBinary(payload: ByteArray) {
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

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val w = image.width
        val h = image.height
        val ySize = w * h
        val out = ByteArray(ySize + (w * h / 2))

        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        var outOff = 0
        for (row in 0 until h) {
            var inOff = row * yRowStride
            for (col in 0 until w) {
                out[outOff++] = yBuf.get(inOff)
                inOff += yPixStride
            }
        }

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride
        val chromaH = h / 2
        val chromaW = w / 2
        for (row in 0 until chromaH) {
            var uIn = row * uRowStride
            var vIn = row * vRowStride
            for (col in 0 until chromaW) {
                out[outOff++] = vBuf.get(vIn)
                out[outOff++] = uBuf.get(uIn)
                uIn += uPixStride
                vIn += vPixStride
            }
        }
        return out
    }

    private fun yuv420888ToRgba(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val w = image.width
        val h = image.height
        val rgba = ByteArray(w * h * 4)

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride

        var outOff = 0
        for (row in 0 until h) {
            for (col in 0 until w) {
                val y = (yBuf.get(row * yRowStride + col * yPixStride).toInt() and 0xFF)
                val u = (uBuf.get((row / 2) * uRowStride + (col / 2) * uPixStride).toInt() and 0xFF) - 128
                val v = (vBuf.get((row / 2) * vRowStride + (col / 2) * vPixStride).toInt() and 0xFF) - 128

                val r = (y + 1.402 * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344136 * u - 0.714136 * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772 * u).toInt().coerceIn(0, 255)

                rgba[outOff++] = r.toByte()
                rgba[outOff++] = g.toByte()
                rgba[outOff++] = b.toByte()
                rgba[outOff++] = 0xFF.toByte()
            }
        }
        return rgba
    }
}
