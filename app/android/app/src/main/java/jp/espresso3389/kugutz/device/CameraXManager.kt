package jp.espresso3389.kugutz.device

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Size
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraXManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val main = Handler(Looper.getMainLooper())
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

    fun captureStill(outFile: File, lens: String = "back"): Map<String, Any> {
        val facing = if (lens.trim().lowercase() == "front") CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)

        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            bindCaptureOnly(provider, facing)

            val cap = capture
            if (cap == null) {
                emitError("capture_not_ready")
                return@addListener
            }
            outFile.parentFile?.mkdirs()
            val opts = ImageCapture.OutputFileOptions.Builder(outFile).build()
            cap.takePicture(
                opts,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        emit("capture_saved", JSONObject().put("path", outFile.absolutePath))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        emitError("capture_failed", exception.message ?: "")
                    }
                }
            )
        }, executor)

        return mapOf("status" to "ok", "path" to outFile.absolutePath)
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

