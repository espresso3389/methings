package jp.espresso3389.methings.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File

class CameraCaptureActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LENS = "lens"
        const val EXTRA_REL_PATH = "rel_path"
        const val EXTRA_ABS_PATH = "abs_path"
        const val EXTRA_ERROR = "error"
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var captureButton: Button
    private lateinit var switchButton: Button
    private lateinit var closeButton: ImageButton

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var captureInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lensFacing =
            if ((intent?.getStringExtra(EXTRA_LENS) ?: "back").trim().equals("front", ignoreCase = true)) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finishWithError("camera_permission_missing")
            return
        }
        startCamera()
    }

    override fun onDestroy() {
        runCatching { cameraProvider?.unbindAll() }
        super.onDestroy()
    }

    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(Color.BLACK)
        }
        root.addView(previewView)

        statusView = TextView(this).apply {
            setTextColor(0xFFF3F4F6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "Preparing camera..."
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xAA111827.toInt())
                cornerRadius = 14f * dp
            }
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                topMargin = (28 * dp).toInt()
            }
        )

        closeButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = "Close"
            setColorFilter(0xFFF3F4F6.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xAA111827.toInt())
                cornerRadius = 18f * dp
            }
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        root.addView(
            closeButton,
            FrameLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt(), Gravity.TOP or Gravity.END).apply {
                topMargin = (24 * dp).toInt()
                marginEnd = (20 * dp).toInt()
            }
        )

        val controls = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xCC000000.toInt(), 0x00000000)
            )
        }
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (180 * dp).toInt(),
                Gravity.BOTTOM,
            )
        )

        switchButton = Button(this).apply {
            text = "Front"
            isAllCaps = false
            setOnClickListener { switchLens() }
        }
        controls.addView(
            switchButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply {
                marginStart = (20 * dp).toInt()
                bottomMargin = (36 * dp).toInt()
            }
        )

        captureButton = Button(this).apply {
            text = "Capture"
            isAllCaps = false
            setOnClickListener { captureStill() }
        }
        controls.addView(
            captureButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = (30 * dp).toInt()
            }
        )

        setContentView(root)
        updateLensButtonLabel()
    }

    private fun updateLensButtonLabel() {
        if (!::switchButton.isInitialized) return
        switchButton.text = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "Back" else "Front"
    }

    private fun setStatus(text: String) {
        if (::statusView.isInitialized) statusView.text = text
    }

    private fun startCamera() {
        setStatus("Preparing camera...")
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            bindCamera(provider, lensFacing, allowFallback = true)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider, facing: Int, allowFallback: Boolean) {
        val selector = CameraSelector.Builder().requireLensFacing(facing).build()
        val fallbackFacing =
            if (facing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT
        if (!provider.hasCamera(selector)) {
            if (allowFallback) {
                lensFacing = fallbackFacing
                updateLensButtonLabel()
                bindCamera(provider, lensFacing, allowFallback = false)
                Toast.makeText(this, "Requested camera not available", Toast.LENGTH_SHORT).show()
                return
            }
            finishWithError("camera_unavailable")
            return
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.getSurfaceProvider())
        }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)
            .build()

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, imageCapture)
        }.onSuccess {
            setStatus("Ready")
            updateLensButtonLabel()
        }.onFailure {
            finishWithError("camera_bind_failed")
        }
    }

    private fun switchLens() {
        if (captureInFlight) return
        val provider = cameraProvider ?: return
        lensFacing =
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
        bindCamera(provider, lensFacing, allowFallback = true)
    }

    private fun captureStill() {
        if (captureInFlight) return
        val capture = imageCapture ?: return
        val (file, relPath) = createOutputFile()
        capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        captureInFlight = true
        captureButton.isEnabled = false
        switchButton.isEnabled = false
        closeButton.isEnabled = false
        setStatus("Capturing...")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val data = intentWithResult(relPath, file)
                    setResult(RESULT_OK, data)
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    captureInFlight = false
                    captureButton.isEnabled = true
                    switchButton.isEnabled = true
                    closeButton.isEnabled = true
                    setStatus("Capture failed")
                    Toast.makeText(
                        this@CameraCaptureActivity,
                        exception.message ?: "Capture failed",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        )
    }

    private fun createOutputFile(): Pair<File, String> {
        val userRoot = File(filesDir, "user")
        val dir = File(userRoot, "captures/chat").also { it.mkdirs() }
        val name = "chat_capture_${System.currentTimeMillis()}.jpg"
        return File(dir, name) to "captures/chat/$name"
    }

    private fun intentWithResult(relPath: String, file: File) =
        android.content.Intent()
            .putExtra(EXTRA_REL_PATH, relPath)
            .putExtra(EXTRA_ABS_PATH, file.absolutePath)

    private fun finishWithError(error: String) {
        setResult(RESULT_CANCELED, android.content.Intent().putExtra(EXTRA_ERROR, error))
        finish()
    }
}
