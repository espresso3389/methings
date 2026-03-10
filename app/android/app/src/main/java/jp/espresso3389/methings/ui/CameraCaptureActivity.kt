package jp.espresso3389.methings.ui

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.TimeUnit

class CameraCaptureActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_LENS = "lens"
        const val EXTRA_REL_PATH = "rel_path"
        const val EXTRA_ABS_PATH = "abs_path"
        const val EXTRA_ERROR = "error"
        private const val MAX_REVIEW_ZOOM = 5f
    }

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: View
    private lateinit var switchButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var zoomLabel: TextView
    private lateinit var zoomBar: LinearLayout
    private val zoomLevelButtons = mutableListOf<TextView>()

    // Review layer
    private lateinit var reviewLayer: FrameLayout
    private lateinit var reviewImage: ImageView
    private lateinit var reviewControls: FrameLayout

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var captureInFlight = false
    private var deviceRotation: Int = Surface.ROTATION_0
    private var orientationListener: android.view.OrientationEventListener? = null

    // Zoom levels (rebuilt when camera binds)
    private var zoomLevels = listOf<Float>()

    // Pending result for review
    private var pendingFile: File? = null
    private var pendingRelPath: String? = null
    private var reviewBitmap: Bitmap? = null

    // Review zoom/pan state
    private val reviewMatrix = Matrix()
    private val reviewSavedMatrix = Matrix()
    private val reviewTouchStart = PointF()
    private val reviewMidPoint = PointF()
    private var reviewOldDist = 1f
    private var reviewMode = 0 // 0=none, 1=drag, 2=zoom
    private var reviewBaseScale = 1f

    // Zoom indicator fade
    private val zoomFadeRunnable = Runnable {
        zoomLabel.animate().alpha(0f).setDuration(400).start()
    }

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
        applyImmersive()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finishWithError("camera_permission_missing")
            return
        }

        // Track device orientation for correct EXIF rotation in captured photos
        orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceRotation = when {
                    orientation in 315..360 || orientation in 0..44 -> Surface.ROTATION_0
                    orientation in 45..134 -> Surface.ROTATION_270  // device tilted right → 270
                    orientation in 135..224 -> Surface.ROTATION_180
                    orientation in 225..314 -> Surface.ROTATION_90  // device tilted left → 90
                    else -> Surface.ROTATION_0
                }
            }
        }.also { it.enable() }

        startCamera()
    }

    override fun onDestroy() {
        orientationListener?.disable()
        orientationListener = null
        runCatching { cameraProvider?.unbindAll() }
        runCatching { reviewBitmap?.recycle() }
        reviewBitmap = null
        camera = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val bmp = reviewBitmap ?: return
        if (reviewLayer.visibility != View.VISIBLE) return
        reviewImage.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                reviewImage.viewTreeObserver.removeOnGlobalLayoutListener(this)
                fitReviewImage(bmp)
            }
        })
    }

    override fun onBackPressed() {
        if (reviewLayer.visibility == View.VISIBLE) {
            dismissReview()
            return
        }
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    private fun applyImmersive() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val dp = resources.displayMetrics.density

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            fitsSystemWindows = false
        }

        // Camera preview — full screen
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

        // ── Touch handling for pinch-zoom + tap-to-focus ──
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zoomState = cam.cameraInfo.zoomState.value ?: return false
                val newRatio = (zoomState.zoomRatio * detector.scaleFactor)
                    .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                cam.cameraControl.setZoomRatio(newRatio)
                showZoomIndicator(newRatio)
                updateZoomBarSelection(newRatio)
                return true
            }
        })

        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.pointerCount == 1 && event.actionMasked == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                handleTapToFocus(event.x, event.y)
            }
            true
        }

        // Top bar overlay (gradient)
        val topOverlay = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), 0x00000000)
            )
        }
        root.addView(
            topOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (100 * dp).toInt(),
                Gravity.TOP,
            )
        )

        // Close button (top-left)
        closeButton = ImageButton(this).apply {
            contentDescription = "Close"
            setImageDrawable(XIconDrawable((14 * dp).toInt(), 0xFFF3F4F6.toInt(), (2.2f * dp)))
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                setColor(0x44FFFFFF)
                cornerRadius = 20f * dp
            }
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        root.addView(
            closeButton,
            FrameLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt(), Gravity.TOP or Gravity.START).apply {
                topMargin = (44 * dp).toInt()
                marginStart = (16 * dp).toInt()
            }
        )

        // Zoom indicator (center, initially hidden — shown on pinch)
        zoomLabel = TextView(this).apply {
            setTextColor(0xFFF3F4F6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0x88000000.toInt())
                cornerRadius = 22f * dp
            }
            alpha = 0f
        }
        root.addView(
            zoomLabel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            ).apply {
                topMargin = (48 * dp).toInt()
            }
        )

        // Bottom controls area (gradient overlay)
        val controls = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xCC000000.toInt(), 0x00000000)
            )
        }
        root.addView(
            controls,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (220 * dp).toInt(),
                Gravity.BOTTOM,
            )
        )

        // ── Zoom level bar (built dynamically when camera binds) ──
        zoomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0x55000000)
                cornerRadius = 20f * dp
            }
            setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
        }
        controls.addView(
            zoomBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = (124 * dp).toInt()
            }
        )

        // Shutter button (center, circular ring)
        val shutterSize = (72 * dp).toInt()
        captureButton = View(this).apply {
            background = ShutterDrawable(dp)
            contentDescription = "Capture"
            isClickable = true
            isFocusable = true
            setOnClickListener { captureStill() }
        }
        controls.addView(
            captureButton,
            FrameLayout.LayoutParams(shutterSize, shutterSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (40 * dp).toInt()
            }
        )

        // Switch camera button (right of shutter)
        val switchSize = (48 * dp).toInt()
        switchButton = ImageButton(this).apply {
            contentDescription = "Switch camera"
            setImageDrawable(FlipCameraIconDrawable((22 * dp).toInt(), 0xFFF3F4F6.toInt(), (1.8f * dp)))
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                setColor(0x44FFFFFF)
                cornerRadius = switchSize / 2f
            }
            setOnClickListener { switchLens() }
        }
        controls.addView(
            switchButton,
            FrameLayout.LayoutParams(switchSize, switchSize, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = (52 * dp).toInt()
                marginEnd = (32 * dp).toInt()
            }
        )

        // ── Review overlay (initially hidden) ──
        reviewLayer = FrameLayout(this).apply {
            setBackgroundColor(0xF0101014.toInt())
            visibility = View.GONE
        }
        root.addView(
            reviewLayer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        reviewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(Color.TRANSPARENT)
        }
        reviewLayer.addView(
            reviewImage,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // Review touch: pinch-zoom + pan
        reviewImage.setOnTouchListener { _, event -> handleReviewTouch(event) }

        // Review top gradient
        val reviewTopOverlay = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xAA000000.toInt(), 0x00000000)
            )
        }
        reviewLayer.addView(
            reviewTopOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (100 * dp).toInt(),
                Gravity.TOP,
            )
        )

        // Review title
        val reviewTitle = TextView(this).apply {
            text = "Review Photo"
            setTextColor(0xFFF3F4F6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
        }
        reviewLayer.addView(
            reviewTitle,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                topMargin = (48 * dp).toInt()
            }
        )

        // Review bottom gradient
        reviewControls = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xDD000000.toInt(), 0x00000000)
            )
        }
        reviewLayer.addView(
            reviewControls,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (160 * dp).toInt(),
                Gravity.BOTTOM,
            )
        )

        // "Retake" button (left) — pill with border
        val retakeBtn = makePillButton("Retake", dp, outlined = true).apply {
            setOnClickListener { dismissReview() }
        }
        reviewControls.addView(
            retakeBtn,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (44 * dp).toInt(),
                Gravity.BOTTOM or Gravity.START,
            ).apply {
                marginStart = (24 * dp).toInt()
                bottomMargin = (48 * dp).toInt()
            }
        )

        // "Use Photo" button (right) — filled pill
        val useBtn = makePillButton("Use Photo", dp, outlined = false).apply {
            setOnClickListener { acceptReview() }
        }
        reviewControls.addView(
            useBtn,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (44 * dp).toInt(),
                Gravity.BOTTOM or Gravity.END,
            ).apply {
                marginEnd = (24 * dp).toInt()
                bottomMargin = (48 * dp).toInt()
            }
        )

        // Zoom hint on review
        val reviewHint = TextView(this).apply {
            text = "Pinch to zoom"
            setTextColor(0x99F3F4F6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(0x55000000)
                cornerRadius = 14f * dp
            }
        }
        reviewControls.addView(
            reviewHint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply {
                bottomMargin = (100 * dp).toInt()
            }
        )

        setContentView(root)
    }

    private fun makePillButton(label: String, dp: Float, outlined: Boolean): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), 0)
            isClickable = true
            isFocusable = true
            if (outlined) {
                setTextColor(0xFFF3F4F6.toInt())
                background = GradientDrawable().apply {
                    setColor(0x33FFFFFF)
                    setStroke((1.5f * dp).toInt(), 0x88FFFFFF.toInt())
                    cornerRadius = 22f * dp
                }
            } else {
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF6366F1.toInt())
                    cornerRadius = 22f * dp
                }
            }
        }
    }

    // ── Zoom level control ──

    private fun rebuildZoomBar(minRatio: Float, maxRatio: Float) {
        val dp = resources.displayMetrics.density
        zoomBar.removeAllViews()
        zoomLevelButtons.clear()

        // Pick levels that are within the camera's supported range
        val candidates = listOf(0.5f, 1f, 2f, 4f, 8f)
        zoomLevels = candidates.filter { it in minRatio..maxRatio }
        // Always include at least the min ratio as "1x equivalent"
        if (zoomLevels.isEmpty()) zoomLevels = listOf(minRatio)

        for (ratio in zoomLevels) {
            val label = if (ratio < 1f) ".${(ratio * 10).toInt()}" else ratio.toInt().toString()
            val btn = TextView(this).apply {
                text = "${label}×"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                val btnSize = (36 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginStart = (2 * dp).toInt()
                    marginEnd = (2 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = btnSize / 2f
                    setColor(Color.TRANSPARENT)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { setZoomLevel(ratio) }
            }
            zoomLevelButtons.add(btn)
            zoomBar.addView(btn)
        }
        zoomBar.visibility = if (zoomLevels.size > 1) View.VISIBLE else View.GONE
    }

    private fun setZoomLevel(ratio: Float) {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        cam.cameraControl.setZoomRatio(clamped)
        showZoomIndicator(clamped)
        updateZoomBarSelection(clamped)
    }

    private fun updateZoomBarSelection(currentRatio: Float) {
        val closest = zoomLevels.minByOrNull { kotlin.math.abs(it - currentRatio) } ?: return
        for ((i, btn) in zoomLevelButtons.withIndex()) {
            val isSelected = zoomLevels[i] == closest && kotlin.math.abs(currentRatio - closest) < 0.15f
            if (isSelected) {
                btn.setTextColor(0xFF6366F1.toInt())
                (btn.background as? GradientDrawable)?.setColor(0x33FFFFFF)
            } else {
                btn.setTextColor(0xCCF3F4F6.toInt())
                (btn.background as? GradientDrawable)?.setColor(Color.TRANSPARENT)
            }
        }
    }

    // ── Tap to focus ──

    private fun handleTapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
        showFocusRing(x, y)
    }

    private fun showFocusRing(x: Float, y: Float) {
        val dp = resources.displayMetrics.density
        val size = (64 * dp).toInt()
        val ring = FocusRingView(this, dp)
        root.addView(
            ring,
            FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (x - size / 2f).toInt()
                topMargin = (y - size / 2f).toInt()
            }
        )
        ring.scaleX = 1.4f
        ring.scaleY = 1.4f
        ring.alpha = 0f
        val appear = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(ring, View.SCALE_X, 1.4f, 1f),
                ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1.4f, 1f),
                ObjectAnimator.ofFloat(ring, View.ALPHA, 0f, 1f),
            )
            duration = 200
            interpolator = DecelerateInterpolator()
        }
        val hold = ObjectAnimator.ofFloat(ring, View.ALPHA, 1f, 1f).apply { duration = 600 }
        val disappear = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 0.85f),
                ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 0.85f),
                ObjectAnimator.ofFloat(ring, View.ALPHA, 1f, 0f),
            )
            duration = 300
        }
        AnimatorSet().apply {
            playSequentially(appear, hold, disappear)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    runCatching { root.removeView(ring) }
                }
            })
            start()
        }
    }

    // ── Zoom indicator ──

    private fun showZoomIndicator(ratio: Float) {
        zoomLabel.text = "×%.1f".format(ratio)
        zoomLabel.alpha = 1f
        zoomLabel.handler?.removeCallbacks(zoomFadeRunnable)
        zoomLabel.postDelayed(zoomFadeRunnable, 1200)
    }

    // ── Review zoom/pan touch handling ──

    @SuppressLint("ClickableViewAccessibility")
    private fun handleReviewTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reviewSavedMatrix.set(reviewMatrix)
                reviewTouchStart.set(event.x, event.y)
                reviewMode = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                reviewOldDist = spacing(event)
                if (reviewOldDist > 10f) {
                    reviewSavedMatrix.set(reviewMatrix)
                    midPoint(reviewMidPoint, event)
                    reviewMode = 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (reviewMode == 1) {
                    reviewMatrix.set(reviewSavedMatrix)
                    reviewMatrix.postTranslate(event.x - reviewTouchStart.x, event.y - reviewTouchStart.y)
                } else if (reviewMode == 2 && event.pointerCount >= 2) {
                    val newDist = spacing(event)
                    if (newDist > 10f) {
                        reviewMatrix.set(reviewSavedMatrix)
                        val scale = newDist / reviewOldDist
                        reviewMatrix.postScale(scale, scale, reviewMidPoint.x, reviewMidPoint.y)
                    }
                }
                clampReviewMatrix()
                reviewImage.imageMatrix = reviewMatrix
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                reviewMode = 0
            }
        }
        return true
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        if (event.pointerCount < 2) return
        point.set((event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
    }

    private fun clampReviewMatrix() {
        val values = FloatArray(9)
        reviewMatrix.getValues(values)
        val currentScale = values[Matrix.MSCALE_X]
        val minScale = reviewBaseScale
        val maxScale = reviewBaseScale * MAX_REVIEW_ZOOM

        if (currentScale < minScale) {
            val fix = minScale / currentScale
            reviewMatrix.postScale(fix, fix, reviewImage.width / 2f, reviewImage.height / 2f)
            reviewMatrix.getValues(values)
        } else if (currentScale > maxScale) {
            val fix = maxScale / currentScale
            reviewMatrix.postScale(fix, fix, reviewImage.width / 2f, reviewImage.height / 2f)
            reviewMatrix.getValues(values)
        }

        val bmp = reviewBitmap ?: return
        val imgW = bmp.width * values[Matrix.MSCALE_X]
        val imgH = bmp.height * values[Matrix.MSCALE_Y]
        val viewW = reviewImage.width.toFloat()
        val viewH = reviewImage.height.toFloat()
        var tx = values[Matrix.MTRANS_X]
        var ty = values[Matrix.MTRANS_Y]

        if (imgW <= viewW) {
            tx = (viewW - imgW) / 2f
        } else {
            tx = tx.coerceIn(viewW - imgW, 0f)
        }
        if (imgH <= viewH) {
            ty = (viewH - imgH) / 2f
        } else {
            ty = ty.coerceIn(viewH - imgH, 0f)
        }
        values[Matrix.MTRANS_X] = tx
        values[Matrix.MTRANS_Y] = ty
        reviewMatrix.setValues(values)
    }

    // ── Review show/dismiss ──

    private fun decodeWithExifRotation(file: File): Bitmap? {
        val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val rotation = try {
            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) { 0f }
        if (rotation == 0f) return raw
        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    private fun showReview(file: File) {
        val bmp = decodeWithExifRotation(file) ?: return
        runCatching { reviewBitmap?.recycle() }
        reviewBitmap = bmp
        reviewImage.setImageBitmap(bmp)

        reviewLayer.visibility = View.VISIBLE
        reviewLayer.alpha = 0f
        reviewLayer.animate().alpha(1f).setDuration(250).setInterpolator(DecelerateInterpolator()).start()

        reviewImage.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                reviewImage.viewTreeObserver.removeOnGlobalLayoutListener(this)
                fitReviewImage(bmp)
            }
        })
    }

    private fun fitReviewImage(bmp: Bitmap) {
        val vw = reviewImage.width.toFloat()
        val vh = reviewImage.height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        reviewBaseScale = scale
        reviewMatrix.reset()
        reviewMatrix.postScale(scale, scale)
        reviewMatrix.postTranslate((vw - bmp.width * scale) / 2f, (vh - bmp.height * scale) / 2f)
        reviewImage.imageMatrix = reviewMatrix
    }

    private fun dismissReview() {
        pendingFile?.let { runCatching { it.delete() } }
        pendingFile = null
        pendingRelPath = null
        runCatching { reviewBitmap?.recycle() }
        reviewBitmap = null
        reviewImage.setImageDrawable(null)

        reviewLayer.animate().alpha(0f).setDuration(200).withEndAction {
            reviewLayer.visibility = View.GONE
        }.start()

        captureInFlight = false
        captureButton.isEnabled = true
        switchButton.isEnabled = true
        closeButton.isEnabled = true
    }

    private fun acceptReview() {
        val rel = pendingRelPath ?: return
        val file = pendingFile ?: return
        setResult(RESULT_OK, intentWithResult(rel, file))
        finish()
    }

    // ── Camera lifecycle ──

    private fun startCamera() {
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
            val cam = provider.bindToLifecycle(this, selector, preview, imageCapture)
            camera = cam
            // Rebuild zoom bar based on this camera's actual zoom range
            val zs = cam.cameraInfo.zoomState.value
            if (zs != null) {
                rebuildZoomBar(zs.minZoomRatio, zs.maxZoomRatio)
                updateZoomBarSelection(zs.zoomRatio)
            }
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
        switchButton.animate().rotationBy(180f).setDuration(300).start()
        bindCamera(provider, lensFacing, allowFallback = true)
    }

    private fun captureStill() {
        if (captureInFlight) return
        val capture = imageCapture ?: return
        val (file, relPath) = createOutputFile()
        capture.targetRotation = deviceRotation
        captureInFlight = true
        captureButton.isEnabled = false
        switchButton.isEnabled = false
        closeButton.isEnabled = false

        // Shutter press animation
        captureButton.animate()
            .scaleX(0.85f).scaleY(0.85f).setDuration(80)
            .withEndAction {
                captureButton.animate().scaleX(1f).scaleY(1f).setDuration(80)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }.start()

        // Flash overlay
        val flash = View(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(
            flash,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        ValueAnimator.ofFloat(0.7f, 0f).apply {
            duration = 250
            addUpdateListener { flash.alpha = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    runCatching { root.removeView(flash) }
                }
            })
            start()
        }

        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    pendingFile = file
                    pendingRelPath = relPath
                    showReview(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    captureInFlight = false
                    captureButton.isEnabled = true
                    switchButton.isEnabled = true
                    closeButton.isEnabled = true
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

    // ── Custom views & drawables ──

    /** Focus ring drawn on tap */
    private class FocusRingView(context: android.content.Context, dp: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDDFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * dp
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - paint.strokeWidth
            val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, -45f - 20f, 40f, false, paint)
            canvas.drawArc(rect, 45f - 20f, 40f, false, paint)
            canvas.drawArc(rect, 135f - 20f, 40f, false, paint)
            canvas.drawArc(rect, 225f - 20f, 40f, false, paint)
        }
    }

    /** Shutter button: white ring with a slightly smaller filled circle inside */
    private class ShutterDrawable(private val dp: Float) : Drawable() {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF3F4F6.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f * dp
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF3F4F6.toInt()
            style = Paint.Style.FILL
        }

        override fun draw(canvas: Canvas) {
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val outerR = (bounds.width() / 2f) - ringPaint.strokeWidth / 2f
            canvas.drawCircle(cx, cy, outerR, ringPaint)
            canvas.drawCircle(cx, cy, outerR - 6f * dp, fillPaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Simple "X" icon drawn as two crossed lines */
    private class XIconDrawable(
        private val size: Int,
        private val color: Int,
        private val strokeWidth: Float,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@XIconDrawable.color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            this.strokeWidth = this@XIconDrawable.strokeWidth
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size

        override fun draw(canvas: Canvas) {
            val inset = size * 0.15f
            canvas.drawLine(inset, inset, size - inset, size - inset, paint)
            canvas.drawLine(size - inset, inset, inset, size - inset, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Camera flip icon: rectangle with two arrows */
    private class FlipCameraIconDrawable(
        private val size: Int,
        private val color: Int,
        private val strokeWidth: Float,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@FlipCameraIconDrawable.color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.strokeWidth = this@FlipCameraIconDrawable.strokeWidth
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@FlipCameraIconDrawable.color
            style = Paint.Style.FILL
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size

        override fun draw(canvas: Canvas) {
            val s = size.toFloat()
            val m = s * 0.15f
            val w = s - 2 * m
            val h = w * 0.7f
            val top = (s - h) / 2f
            val r = w * 0.08f

            val rect = android.graphics.RectF(m, top, m + w, top + h)
            canvas.drawRoundRect(rect, r, r, paint)

            val cx = s / 2f
            val cy = top + h / 2f
            val lr = h * 0.2f
            canvas.drawCircle(cx, cy, lr, paint)

            val arrowR = h * 0.38f
            val arrowRect = android.graphics.RectF(cx - arrowR, cy - arrowR, cx + arrowR, cy + arrowR)
            canvas.drawArc(arrowRect, -30f, 120f, false, paint)
            canvas.drawArc(arrowRect, 150f, 120f, false, paint)

            val arrSize = s * 0.06f
            val path = android.graphics.Path()

            val a1x = cx + arrowR * kotlin.math.cos(Math.toRadians(90.0)).toFloat()
            val a1y = cy + arrowR * kotlin.math.sin(Math.toRadians(90.0)).toFloat()
            path.moveTo(a1x - arrSize * 1.5f, a1y - arrSize)
            path.lineTo(a1x, a1y + arrSize)
            path.lineTo(a1x + arrSize * 1.5f, a1y - arrSize)
            path.close()
            canvas.drawPath(path, fillPaint)

            path.reset()
            val a2x = cx + arrowR * kotlin.math.cos(Math.toRadians(-90.0)).toFloat()
            val a2y = cy + arrowR * kotlin.math.sin(Math.toRadians(-90.0)).toFloat()
            path.moveTo(a2x - arrSize * 1.5f, a2y + arrSize)
            path.lineTo(a2x, a2y - arrSize)
            path.lineTo(a2x + arrSize * 1.5f, a2y + arrSize)
            path.close()
            canvas.drawPath(path, fillPaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
