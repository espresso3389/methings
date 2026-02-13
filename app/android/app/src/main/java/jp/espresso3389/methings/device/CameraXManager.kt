package jp.espresso3389.methings.device

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CameraXManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    companion object {
        private const val TAG = "CameraXManager"
    }
    private val main = Handler(Looper.getMainLooper())
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val wsClients = CopyOnWriteArrayList<NanoWSD.WebSocket>()
    private val started = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var capture: ImageCapture? = null
    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastFrameAtMs: Long = 0
    private var previewSize: Size = Size(640, 480)
    private var previewFps: Int = 5
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    // Device orientation tracking (accelerometer-based, works even when screen rotation is locked)
    @Volatile private var deviceOrientationDegrees: Int = 0
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            deviceOrientationDegrees = ((orientation + 45) / 90 * 90) % 360
        }
    }
    init { orientationListener.enable() }

    fun isPreviewActive(): Boolean = started.get()

    fun addWsClient(ws: NanoWSD.WebSocket) {
        wsClients.add(ws)
    }

    fun removeWsClient(ws: NanoWSD.WebSocket) {
        wsClients.remove(ws)
    }

    fun listCameras(): Map<String, Any> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return mapOf("status" to "error", "error" to "camera_manager_unavailable")
        val out = JSONArray()
        for (id in mgr.cameraIdList) {
            val ch = mgr.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val facingStr = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "unknown"
            }
            out.put(JSONObject().put("id", id).put("facing", facingStr))
        }
        return mapOf("status" to "ok", "cameras" to out.toString())
    }

    fun status(): Map<String, Any> {
        return mapOf(
            "status" to "ok",
            "preview_active" to started.get(),
            "lens" to (if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back"),
            "preview_width" to previewSize.width,
            "preview_height" to previewSize.height,
            "preview_fps" to previewFps,
            "ws_clients" to wsClients.size,
        )
    }

    fun startPreview(
        lens: String = "back",
        width: Int = 640,
        height: Int = 480,
        fps: Int = 5,
        jpegQuality: Int = 70,
    ): Map<String, Any> {
        val facing = if (lens.trim().lowercase() == "front") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        lensFacing = facing
        previewSize = Size(width.coerceIn(160, 1920), height.coerceIn(120, 1080))
        previewFps = fps.coerceIn(1, 30)

        val providerFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            bindUseCases(provider, facing, jpegQuality)
            started.set(true)
        }, executor)

        return mapOf(
            "status" to "ok",
            "ws_path" to "/ws/camera/preview",
            "lens" to (if (facing == CameraSelector.LENS_FACING_FRONT) "front" else "back"),
            "preview_width" to previewSize.width,
            "preview_height" to previewSize.height,
            "preview_fps" to previewFps,
        )
    }

    fun stopPreview(): Map<String, Any> {
        started.set(false)
        val provider = cameraProvider
        if (provider != null) {
            main.post { runCatching { provider.unbindAll() } }
        }
        analysis = null
        capture = null
        camera = null
        return mapOf("status" to "ok", "stopped" to true)
    }

    fun captureStill(
        outFile: File,
        lens: String = "back",
        timeoutMs: Long = 12_000,
        jpegQuality: Int = 95,
        exposureCompensation: Int? = null,
    ): Map<String, Any> {
        // CameraX ImageCapture can be flaky on some devices when invoked from a foreground service.
        // Use Camera2 for still capture to maximize compatibility.
        fun once(): Map<String, Any> = captureStillCamera2(
            outFile,
            lens = lens,
            timeoutMs = timeoutMs,
            jpegQuality = jpegQuality,
            exposureCompensation = exposureCompensation,
        )

        val r1 = once()
        val status = (r1["status"] as? String) ?: ""
        val error = (r1["error"] as? String) ?: ""
        val detail = (r1["detail"] as? String) ?: ""
        if (status == "error" && error == "capture_failed" && detail.contains("Failed to submit capture request", ignoreCase = true)) {
            // Observed transient on some devices; retry once with a fresh camera session.
            Log.w(TAG, "camera2.capture transient submit failure; retrying once: $detail")
            Thread.sleep(250)
            return once()
        }
        return r1
    }

    @SuppressLint("MissingPermission")
    private fun captureStillCamera2(
        outFile: File,
        lens: String,
        timeoutMs: Long,
        jpegQuality: Int,
        exposureCompensation: Int?,
    ): Map<String, Any> {
        val stage = AtomicReference("init")
        fun setStage(s: String) {
            stage.set(s)
            Log.i(TAG, "camera2.capture stage=$s lens=$lens out=${outFile.absolutePath}")
        }

        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return mapOf("status" to "error", "error" to "camera_manager_unavailable", "stage" to "camera_manager_unavailable")

        val wantFront = lens.trim().lowercase() == "front"
        var chosenId: String? = null
        var chosenSizes: Array<Size>? = null
        var chosenChars: CameraCharacteristics? = null
        for (id in mgr.cameraIdList) {
            val ch = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
            val isBack = facing == CameraCharacteristics.LENS_FACING_BACK
            if (wantFront && !isFront) continue
            if (!wantFront && !isBack) continue

            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            if (sizes == null || sizes.isEmpty()) continue
            chosenId = id
            chosenSizes = sizes
            chosenChars = ch
            break
        }
        val cameraId = chosenId ?: return mapOf("status" to "error", "error" to "no_camera_found")
        val jpegSizes = chosenSizes ?: return mapOf("status" to "error", "error" to "no_jpeg_sizes")
        val chars = chosenChars

        // Choose a reasonable size: prefer <= 1920px wide, else use the first.
        val sorted = jpegSizes.sortedWith(compareBy({ it.width * it.height }))
        val pref = sorted.lastOrNull { it.width <= 1920 } ?: sorted.lastOrNull() ?: sorted.first()
        setStage("selected_camera_${cameraId}_${pref.width}x${pref.height}")

        outFile.parentFile?.mkdirs()

        val thread = HandlerThread("camera2-capture").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        val lastDetail = AtomicReference("")
        val result = AtomicReference<Map<String, Any>>(
            mapOf(
                "status" to "error",
                "error" to "capture_timeout",
                "path" to outFile.absolutePath,
                "stage" to "timeout"
            )
        )

        val reader = ImageReader.newInstance(pref.width, pref.height, ImageFormat.JPEG, 1)
        reader.setOnImageAvailableListener({ r ->
            try {
                setStage("image_available")
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    FileOutputStream(outFile).use { it.write(bytes) }
                } finally {
                    runCatching { image.close() }
                }
                result.set(mapOf("status" to "ok", "path" to outFile.absolutePath, "method" to "camera2", "stage" to stage.get()))
                emit("capture_saved", JSONObject().put("path", outFile.absolutePath).put("method", "camera2"))
            } catch (ex: Exception) {
                val msg = ex.message ?: ""
                lastDetail.set(msg)
                result.set(
                    mapOf(
                        "status" to "error",
                        "error" to "capture_failed",
                        "detail" to msg,
                        "path" to outFile.absolutePath,
                        "method" to "camera2",
                        "stage" to stage.get()
                    )
                )
                emitError("capture_failed", ex.message ?: "")
            } finally {
                latch.countDown()
            }
        }, handler)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        val cb = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                setStage("device_opened")
                device = camera
                try {
                    setStage("create_session")
                    camera.createCaptureSession(
                        Collections.singletonList(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                setStage("session_configured")
                                session = s
                                try {
                                    setStage("submit_still_request")
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.JPEG_QUALITY, jpegQuality.coerceIn(40, 100).toByte())
                                        set(CaptureRequest.JPEG_ORIENTATION, computeJpegOrientation(chars))
                                        // Exposure compensation (AE) in device-specific steps.
                                        if (exposureCompensation != null) {
                                            val range = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                                            if (range != null) {
                                                set(
                                                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                                                    exposureCompensation.coerceIn(range.lower, range.upper)
                                                )
                                            }
                                        }
                                    }.build()
                                    s.capture(req, object : CameraCaptureSession.CaptureCallback() {}, handler)
                                    setStage("capture_submitted")
                                } catch (ex: Exception) {
                                    val msg = ex.message ?: ""
                                    lastDetail.set(msg)
                                    result.set(
                                        mapOf(
                                            "status" to "error",
                                            "error" to "capture_failed",
                                            "detail" to msg,
                                            "path" to outFile.absolutePath,
                                            "method" to "camera2",
                                            "stage" to stage.get()
                                        )
                                    )
                                    emitError("capture_failed", ex.message ?: "")
                                    latch.countDown()
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                lastDetail.set("configure_failed")
                                result.set(
                                    mapOf(
                                        "status" to "error",
                                        "error" to "capture_failed",
                                        "detail" to "configure_failed",
                                        "path" to outFile.absolutePath,
                                        "method" to "camera2",
                                        "stage" to stage.get()
                                    )
                                )
                                emitError("capture_failed", "configure_failed")
                                latch.countDown()
                            }
                        },
                        handler
                    )
                } catch (ex: Exception) {
                    val msg = ex.message ?: ""
                    lastDetail.set(msg)
                    result.set(
                        mapOf(
                            "status" to "error",
                            "error" to "capture_failed",
                            "detail" to msg,
                            "path" to outFile.absolutePath,
                            "method" to "camera2",
                            "stage" to stage.get()
                        )
                    )
                    emitError("capture_failed", ex.message ?: "")
                    latch.countDown()
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                setStage("device_disconnected")
                runCatching { camera.close() }
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                setStage("device_error_$error")
                runCatching { camera.close() }
                lastDetail.set("camera_error_$error")
                result.set(
                    mapOf(
                        "status" to "error",
                        "error" to "capture_failed",
                        "detail" to "camera_error_$error",
                        "path" to outFile.absolutePath,
                        "method" to "camera2",
                        "stage" to stage.get()
                    )
                )
                emitError("capture_failed", "camera_error_$error")
                latch.countDown()
            }
        }

        try {
            setStage("open_camera_call")
            mgr.openCamera(cameraId, cb, handler)
        } catch (ex: Exception) {
            val msg = ex.message ?: ""
            lastDetail.set(msg)
            reader.close()
            thread.quitSafely()
            return mapOf(
                "status" to "error",
                "error" to "capture_failed",
                "detail" to msg,
                "path" to outFile.absolutePath,
                "method" to "camera2",
                "stage" to stage.get()
            )
        }

        val ok = latch.await(timeoutMs.coerceIn(2_000, 60_000), TimeUnit.MILLISECONDS)
        if (!ok) {
            setStage("timeout")
            emitError("capture_timeout")
            val detail = lastDetail.get()
            result.set(
                mapOf(
                    "status" to "error",
                    "error" to "capture_timeout",
                    "detail" to detail,
                    "path" to outFile.absolutePath,
                    "method" to "camera2",
                    "stage" to stage.get()
                )
            )
        }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader.close() }
        runCatching { thread.quitSafely() }
        return result.get()
    }

    private fun captureSingleFrameJpeg(
        provider: ProcessCameraProvider,
        facing: Int,
        outFile: File,
        jpegQuality: Int,
        latch: CountDownLatch,
        finished: AtomicBoolean,
        finishOnce: (Map<String, Any>) -> Unit,
    ) {
        val selector = CameraSelector.Builder().requireLensFacing(facing).build()
        val ana = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(previewSize)
            .build()

        // Use a one-shot executor to avoid any stalls from the shared analysis thread.
        val exec: ExecutorService = Executors.newSingleThreadExecutor()
        ana.setAnalyzer(exec) { img ->
            try {
                if (finished.get()) return@setAnalyzer
                val jpeg = yuv420ToJpeg(img, jpegQuality.coerceIn(10, 95))
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { it.write(jpeg) }
                emit("capture_saved", JSONObject().put("path", outFile.absolutePath).put("method", "analysis_fallback"))
                finishOnce(
                    mapOf(
                        "status" to "ok",
                        "path" to outFile.absolutePath,
                        "method" to "analysis_fallback"
                    )
                )
            } catch (ex: Exception) {
                emitError("capture_failed", ex.message ?: "")
                finishOnce(
                    mapOf(
                        "status" to "error",
                        "error" to "capture_failed",
                        "detail" to (ex.message ?: ""),
                        "path" to outFile.absolutePath,
                        "method" to "analysis_fallback"
                    )
                )
            } finally {
                img.close()
                runCatching { ana.clearAnalyzer() }
                exec.shutdown()
                // CameraX requires bind/unbind on the main thread.
                main.post {
                    runCatching { provider.unbindAll() }
                    analysis = null
                    capture = null
                    camera = null
                }
            }
        }

        // CameraX requires bind/unbind on the main thread.
        main.post {
            runCatching {
                provider.unbindAll()
                analysis = ana
                // Some devices won't deliver ImageAnalysis frames reliably unless another use case
                // (e.g., ImageCapture) is also bound.
                val cap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                capture = cap
                camera = provider.bindToLifecycle(lifecycleOwner, selector, cap, ana)
            }.onFailure { ex ->
                emitError("capture_failed", ex.message ?: "")
                finishOnce(
                    mapOf(
                        "status" to "error",
                        "error" to "capture_failed",
                        "detail" to (ex.message ?: ""),
                        "path" to outFile.absolutePath,
                        "method" to "analysis_fallback"
                    )
                )
            }
        }
    }

    private fun bindCaptureOnly(provider: ProcessCameraProvider, facing: Int) {
        val selector = CameraSelector.Builder().requireLensFacing(facing).build()
        val cap = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        provider.unbindAll()
        camera = provider.bindToLifecycle(lifecycleOwner, selector, cap)
        capture = cap
        analysis = null
    }

    private fun computeJpegOrientation(chars: CameraCharacteristics?): Int {
        val sensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = chars?.get(CameraCharacteristics.LENS_FACING)
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceOrientationDegrees) % 360
        } else {
            (sensorOrientation - deviceOrientationDegrees + 360) % 360
        }
    }

    private fun bindUseCases(provider: ProcessCameraProvider, facing: Int, jpegQuality: Int) {
        val selector = CameraSelector.Builder().requireLensFacing(facing).build()
        val cap = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val ana = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(previewSize)
            .build()

        val exec: ExecutorService = analysisExecutor
        ana.setAnalyzer(exec) { img ->
            try {
                if (!started.get()) return@setAnalyzer
                val now = System.currentTimeMillis()
                val minDelta = (1000.0 / previewFps.toDouble()).toLong()
                if ((now - lastFrameAtMs) < minDelta) return@setAnalyzer
                lastFrameAtMs = now
                val jpeg = yuv420ToJpeg(img, jpegQuality.coerceIn(10, 95))
                broadcastBinary(jpeg)
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }

        provider.unbindAll()
        camera = provider.bindToLifecycle(lifecycleOwner, selector, cap, ana)
        capture = cap
        analysis = ana
    }

    private fun yuv420ToJpeg(image: ImageProxy, jpegQuality: Int): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = yuv420888ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), jpegQuality, out)
        return out.toByteArray()
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val w = image.width
        val h = image.height
        val ySize = w * h
        val out = ByteArray(ySize + (w * h / 2))

        // Copy Y.
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

        // Interleave VU for NV21.
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

    private fun emit(kind: String, data: JSONObject) {
        val msg = JSONObject()
            .put("type", "camera")
            .put("event", kind)
            .put("ts_ms", System.currentTimeMillis())
        for (k in data.keys()) {
            msg.put(k, data.get(k))
        }
        val dead = ArrayList<NanoWSD.WebSocket>()
        val text = msg.toString()
        for (ws in wsClients) {
            try {
                if (ws.isOpen) ws.send(text) else dead.add(ws)
            } catch (_: Exception) {
                dead.add(ws)
            }
        }
        for (ws in dead) wsClients.remove(ws)
    }

    private fun emitError(code: String, detail: String = "") {
        emit("error", JSONObject().put("code", code).put("detail", detail))
    }
}
