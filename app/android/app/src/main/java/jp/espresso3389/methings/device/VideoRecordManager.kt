package jp.espresso3389.methings.device

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Camera2 background thread for recording
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Recording state
    private val recordingLock = Any()
    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var captureSession: CameraCaptureSession? = null
    @Volatile private var mediaRecorder: MediaRecorder? = null
    @Volatile private var recordingState: String = "idle"
    @Volatile private var recordingFile: File? = null
    @Volatile private var recordingStartMs: Long = 0L
    @Volatile private var recordingCodec: String = "unknown"
    @Volatile private var lastRecordError: String? = null

    // Frame streaming state (CameraX — works fine without EncoderProfiles)
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

    // Device orientation tracking (accelerometer-based, works even when screen rotation is locked)
    @Volatile private var deviceOrientationDegrees: Int = 0
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            deviceOrientationDegrees = ((orientation + 45) / 90 * 90) % 360
        }
    }
    init { orientationListener.enable() }

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

    // ── Video Recording (Camera2 + MediaRecorder → .mp4) ─────────────────────

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
            val facing = if ((lens ?: "back").trim().lowercase() == "front")
                CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK

            val recDir = File(userRoot, "recordings/video")
            recDir.mkdirs()
            val fileName = outPath?.trim()?.ifBlank { null }
                ?: "vid_${System.currentTimeMillis()}.mp4"
            val outFile = if (fileName.startsWith("/")) File(fileName) else File(recDir, fileName)
            outFile.parentFile?.mkdirs()

            val videoSize = when (res) {
                "4k", "2160p" -> Size(3840, 2160)
                "1080p" -> Size(1920, 1080)
                else -> Size(1280, 720)
            }

            val codec = resolveCodec(prefCodec)
            recordingCodec = codec

            // Find the camera ID for the requested lens facing
            val cameraId = findCameraId(facing)
                ?: return mapOf("status" to "error", "error" to "no_camera", "detail" to "No camera found for lens=$lens")

            // Start camera background thread
            val thread = HandlerThread("VideoRecordCamera").also { it.start() }
            cameraThread = thread
            val handler = Handler(thread.looper)
            cameraHandler = handler

            // Configure MediaRecorder
            val recorder = MediaRecorder(context)
            try {
                configureRecorder(
                    recorder = recorder,
                    withAudio = true,
                    outFile = outFile,
                    videoSize = videoSize,
                    res = res,
                    codec = codec,
                    cameraId = cameraId,
                    maxS = maxS,
                    handler = handler
                )
            } catch (e: Exception) {
                val msg = (e.message ?: "").trim().lowercase()
                val shouldRetryVideoOnly =
                    msg.contains("setaudiosource failed") || msg.contains("audiosource") || e is SecurityException
                if (!shouldRetryVideoOnly) {
                    Log.e(TAG, "MediaRecorder configure/prepare failed", e)
                    recorder.release()
                    thread.quitSafely()
                    return mapOf("status" to "error", "error" to "recorder_prepare_failed", "detail" to (e.message ?: ""))
                }
                try {
                    Log.w(TAG, "Audio source unavailable; falling back to video-only recording: ${e.message}")
                    recorder.reset()
                    configureRecorder(
                        recorder = recorder,
                        withAudio = false,
                        outFile = outFile,
                        videoSize = videoSize,
                        res = res,
                        codec = codec,
                        cameraId = cameraId,
                        maxS = maxS,
                        handler = handler
                    )
                } catch (e2: Exception) {
                    Log.e(TAG, "Video-only MediaRecorder configure/prepare failed", e2)
                    recorder.release()
                    thread.quitSafely()
                    return mapOf("status" to "error", "error" to "recorder_prepare_failed", "detail" to (e2.message ?: ""))
                }
            }

            // Open camera and start recording
            val openLatch = CountDownLatch(1)
            var openError: String? = null

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        cameraDevice = camera
                        val recorderSurface = recorder.surface

                        // Use a dummy Preview surface to keep the camera pipeline happy
                        val dummyTexture = SurfaceTexture(0)
                        dummyTexture.setDefaultBufferSize(videoSize.width, videoSize.height)
                        val previewSurface = Surface(dummyTexture)

                        val surfaces = listOf(recorderSurface, previewSurface)
                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    captureSession = session
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                        addTarget(recorderSurface)
                                        addTarget(previewSurface)
                                        set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    }.build()
                                    session.setRepeatingRequest(request, null, handler)
                                    recorder.start()

                                    mediaRecorder = recorder
                                    recordingFile = outFile
                                    recordingStartMs = System.currentTimeMillis()
                                    recordingState = "recording"
                                    lastRecordError = null
                                } catch (e: Exception) {
                                    Log.e(TAG, "Session configure + start failed", e)
                                    openError = e.message ?: "session_start_failed"
                                    recorder.release()
                                    camera.close()
                                    previewSurface.release()
                                    dummyTexture.release()
                                } finally {
                                    openLatch.countDown()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                openError = "session_configure_failed"
                                recorder.release()
                                camera.close()
                                previewSurface.release()
                                dummyTexture.release()
                                openLatch.countDown()
                            }
                        }, handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera onOpened setup failed", e)
                        openError = e.message ?: "camera_setup_failed"
                        recorder.release()
                        camera.close()
                        openLatch.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                    if (recordingState == "recording") {
                        stopRecordingInternal()
                    }
                    openLatch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: $error")
                    openError = "camera_open_error_$error"
                    camera.close()
                    cameraDevice = null
                    openLatch.countDown()
                }
            }, handler)

            openLatch.await(10, TimeUnit.SECONDS)

            if (openError != null) {
                recordingState = "error"
                lastRecordError = openError
                cameraThread?.quitSafely()
                cameraThread = null
                cameraHandler = null
                return mapOf("status" to "error", "error" to "start_failed", "detail" to (openError ?: ""))
            }

            val relPath = outFile.absolutePath.removePrefix(userRoot.absolutePath).trimStart('/')
            return mapOf(
                "status" to "ok",
                "state" to "recording",
                "rel_path" to relPath,
                "resolution" to res,
                "codec" to codec,
                "max_duration_s" to maxS,
                "lens" to (if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"),
                "container" to "mp4"
            )
        }
    }

    fun stopRecording(): Map<String, Any?> {
        return stopRecordingInternal()
    }

    /**
     * Thread-safe stop. Called from HTTP thread (stopRecording) and from
     * MediaRecorder max-duration/error callbacks on the camera handler thread.
     */
    private fun stopRecordingInternal(): Map<String, Any?> {
        synchronized(recordingLock) {
            if (recordingState != "recording") {
                return mapOf("status" to "ok", "stopped" to false, "state" to recordingState)
            }
            recordingState = "idle"
        }
        val durationMs = System.currentTimeMillis() - recordingStartMs

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder.stop() failed", e)
            lastRecordError = "stop_failed: ${e.message}"
        }
        mediaRecorder?.release()
        mediaRecorder = null

        captureSession?.runCatching { close() }
        captureSession = null

        cameraDevice?.runCatching { close() }
        cameraDevice = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        val outFile = recordingFile
        val relPath = outFile?.absolutePath?.removePrefix(userRoot.absolutePath)?.trimStart('/') ?: ""
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

    private fun configureRecorder(
        recorder: MediaRecorder,
        withAudio: Boolean,
        outFile: File,
        videoSize: Size,
        res: String,
        codec: String,
        cameraId: String,
        maxS: Int,
        handler: Handler
    ) {
        if (withAudio) {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(outFile.absolutePath)
        recorder.setVideoSize(videoSize.width, videoSize.height)
        recorder.setVideoFrameRate(30)
        recorder.setVideoEncodingBitRate(
            when (res) {
                "4k", "2160p" -> 25_000_000
                "1080p" -> 12_000_000
                else -> 6_000_000
            }
        )
        if (withAudio) {
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44100)
        }
        recorder.setVideoEncoder(
            if (codec == "h265") MediaRecorder.VideoEncoder.HEVC
            else MediaRecorder.VideoEncoder.H264
        )
        if (withAudio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        }
        recorder.setOrientationHint(computeRotation(cameraId))
        recorder.setMaxDuration(maxS * 1000)
        recorder.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                Log.i(TAG, "Max duration reached, stopping recording")
                handler.post { stopRecordingInternal() }
            }
        }
        recorder.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaRecorder error: what=$what extra=$extra")
            lastRecordError = "media_recorder_error_$what"
            handler.post { stopRecordingInternal() }
        }
        recorder.prepare()
    }

    // ── Frame Streaming (CameraX ImageAnalysis → JPEG or RGBA over WebSocket) ─

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

        val latch = CountDownLatch(1)
        var error: String? = null

        main.post {
            try {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    try {
                        val provider = providerFuture.get()
                        streamProvider = provider

                        @Suppress("DEPRECATION")
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
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, selector, ana)
                        streamAnalysis = ana

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

        latch.await(10, TimeUnit.SECONDS)

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

    private fun findCameraId(lensFacing: Int): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == lensFacing) return id
        }
        return null
    }

    private fun parseLens(lens: String?): Int {
        return if ((lens ?: "back").trim().lowercase() == "front")
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
    }

    private fun computeRotation(cameraId: String): Int {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceOrientationDegrees) % 360
        } else {
            (sensorOrientation - deviceOrientationDegrees + 360) % 360
        }
    }

    private fun resolveCodec(preferred: String): String {
        if (preferred == "h264") return "h264"
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
