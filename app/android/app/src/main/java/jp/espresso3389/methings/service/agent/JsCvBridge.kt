package jp.espresso3389.methings.service.agent

import android.util.Base64
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * OpenCV bindings for the QuickJS engine.
 *
 * Exposes image processing operations via `__cv.*` internal functions,
 * wrapped by a `cv.*` global API in the JS bootstrap.
 *
 * Mat objects are stored as opaque integer handles in a ConcurrentHashMap.
 * All Mats are automatically released after each run_js execution.
 *
 * Most operations are async to avoid blocking the JS event loop during
 * heavy computation or I/O, allowing concurrent streaming (WebSocket, TCP)
 * to continue. Only trivial constant-time operations (release, info,
 * zeros, ones) are sync.
 */
class JsCvBridge(private val userDir: File) {
    private val mats = ConcurrentHashMap<Int, Mat>()
    private val nextMatId = AtomicInteger(0)
    private val initialized = AtomicBoolean(false)

    private fun ensureInit() {
        if (initialized.compareAndSet(false, true)) {
            if (!OpenCVLoader.initLocal()) {
                initialized.set(false)
                throw Exception("OpenCV initialization failed")
            }
            Log.i(TAG, "OpenCV ${OpenCVLoader.OPENCV_VERSION} initialized")
        }
    }

    private fun alloc(mat: Mat): Int {
        val id = nextMatId.getAndIncrement()
        mats[id] = mat
        return id
    }

    private fun get(id: Int): Mat {
        return mats[id] ?: throw Exception("cv: mat handle $id not found or already released")
    }

    private fun resolveSecure(path: String): File {
        val resolved = File(userDir, path).canonicalFile
        if (!resolved.absolutePath.startsWith(userDir.canonicalPath)) {
            throw Exception("cv: path outside user directory: $path")
        }
        return resolved
    }

    private fun argInt(args: Array<Any?>, idx: Int, default: Int = 0): Int {
        return when (val v = args.getOrNull(idx)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun argDouble(args: Array<Any?>, idx: Int, default: Double = 0.0): Double {
        return when (val v = args.getOrNull(idx)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: default
            else -> default
        }
    }

    private fun argStr(args: Array<Any?>, idx: Int, default: String = ""): String {
        return args.getOrNull(idx)?.toString() ?: default
    }

    fun registerBindings(js: QuickJs) {
        js.define("__cv") {
            // ── Image I/O (async — disk I/O) ──

            asyncFunction("imread") { args ->
                ensureInit()
                val path = argStr(args, 0)
                val flags = argInt(args, 1, Imgcodecs.IMREAD_COLOR)
                withContext(Dispatchers.IO) {
                    val file = resolveSecure(path)
                    if (!file.exists()) throw Exception("cv.imread: not found: $path")
                    val mat = Imgcodecs.imread(file.absolutePath, flags)
                    if (mat.empty()) throw Exception("cv.imread: failed to decode: $path")
                    alloc(mat)
                }
            }

            asyncFunction("imwrite") { args ->
                ensureInit()
                val matId = argInt(args, 0)
                val path = argStr(args, 1)
                val mat = get(matId)
                withContext(Dispatchers.IO) {
                    val file = resolveSecure(path)
                    file.parentFile?.mkdirs()
                    Imgcodecs.imwrite(file.absolutePath, mat)
                }
            }

            asyncFunction("imdecode") { args ->
                ensureInit()
                val data = args.getOrNull(0)
                val flags = argInt(args, 1, Imgcodecs.IMREAD_COLOR)
                val bytes = when (data) {
                    is UByteArray -> data.asByteArray()
                    is ByteArray -> data
                    else -> throw Exception("cv.imdecode: requires Uint8Array data")
                }
                withContext(Dispatchers.Default) {
                    val buf = MatOfByte(*bytes)
                    val mat = Imgcodecs.imdecode(buf, flags)
                    buf.release()
                    if (mat.empty()) throw Exception("cv.imdecode: failed to decode image data")
                    alloc(mat)
                }
            }

            asyncFunction("imencode") { args ->
                ensureInit()
                val matId = argInt(args, 0)
                val ext = argStr(args, 1, ".jpg")
                val mat = get(matId)
                withContext(Dispatchers.Default) {
                    val buf = MatOfByte()
                    val ok = Imgcodecs.imencode(ext, mat, buf)
                    if (!ok) {
                        buf.release()
                        throw Exception("cv.imencode: encoding failed for $ext")
                    }
                    val result = buf.toArray().asUByteArray()
                    buf.release()
                    result
                }
            }

            // ── Mat lifecycle (sync — constant time) ──

            function("release") { args ->
                val matId = argInt(args, 0)
                mats.remove(matId)?.release()
                null
            }

            function("info") { args ->
                val mat = get(argInt(args, 0))
                mapOf<String, Any?>(
                    "rows" to mat.rows(),
                    "cols" to mat.cols(),
                    "channels" to mat.channels(),
                    "type" to mat.type(),
                    "depth" to mat.depth(),
                    "elemSize" to mat.elemSize().toInt(),
                )
            }

            function("zeros") { args ->
                ensureInit()
                val rows = argInt(args, 0)
                val cols = argInt(args, 1)
                val type = argInt(args, 2, CvType.CV_8UC3)
                alloc(Mat.zeros(rows, cols, type))
            }

            function("ones") { args ->
                ensureInit()
                val rows = argInt(args, 0)
                val cols = argInt(args, 1)
                val type = argInt(args, 2, CvType.CV_8UC3)
                alloc(Mat.ones(rows, cols, type))
            }

            // ── Mat lifecycle (async — scales with image size) ──

            asyncFunction("clone") { args ->
                val mat = get(argInt(args, 0))
                withContext(Dispatchers.Default) {
                    alloc(mat.clone())
                }
            }

            // ── Mat data transfer (async — scales with image size) ──

            asyncFunction("matToBytes") { args ->
                val mat = get(argInt(args, 0))
                withContext(Dispatchers.Default) {
                    val bytes = ByteArray((mat.total() * mat.elemSize()).toInt())
                    mat.get(0, 0, bytes)
                    bytes.asUByteArray()
                }
            }

            asyncFunction("matFromBytes") { args ->
                ensureInit()
                val data = args.getOrNull(0)
                val rows = argInt(args, 1)
                val cols = argInt(args, 2)
                val type = argInt(args, 3, CvType.CV_8UC3)
                val bytes = when (data) {
                    is UByteArray -> data.asByteArray()
                    is ByteArray -> data
                    else -> throw Exception("cv.matFromBytes: requires Uint8Array data")
                }
                withContext(Dispatchers.Default) {
                    val mat = Mat(rows, cols, type)
                    mat.put(0, 0, bytes)
                    alloc(mat)
                }
            }

            asyncFunction("matToBase64") { args ->
                ensureInit()
                val matId = argInt(args, 0)
                val ext = argStr(args, 1, ".jpg")
                val mat = get(matId)
                withContext(Dispatchers.Default) {
                    val buf = MatOfByte()
                    Imgcodecs.imencode(ext, mat, buf)
                    val result = Base64.encodeToString(buf.toArray(), Base64.NO_WRAP)
                    buf.release()
                    result
                }
            }

            asyncFunction("matFromBase64") { args ->
                ensureInit()
                val b64 = argStr(args, 0)
                val flags = argInt(args, 1, Imgcodecs.IMREAD_COLOR)
                withContext(Dispatchers.Default) {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val buf = MatOfByte(*bytes)
                    val mat = Imgcodecs.imdecode(buf, flags)
                    buf.release()
                    if (mat.empty()) throw Exception("cv.matFromBase64: failed to decode")
                    alloc(mat)
                }
            }

            // ── Color conversion (async — per-pixel) ──

            asyncFunction("cvtColor") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val code = argInt(args, 1)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.cvtColor(src, dst, code)
                    alloc(dst)
                }
            }

            // ── Geometric transforms (async — per-pixel) ──

            asyncFunction("resize") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val w = argInt(args, 1)
                val h = argInt(args, 2)
                val interp = argInt(args, 3, Imgproc.INTER_LINEAR)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.resize(src, dst, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, interp)
                    alloc(dst)
                }
            }

            asyncFunction("crop") { args ->
                val src = get(argInt(args, 0))
                val x = argInt(args, 1)
                val y = argInt(args, 2)
                val w = argInt(args, 3)
                val h = argInt(args, 4)
                withContext(Dispatchers.Default) {
                    val roi = Rect(x, y, w, h)
                    alloc(Mat(src, roi).clone())
                }
            }

            asyncFunction("rotate") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val code = argInt(args, 1)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Core.rotate(src, dst, code)
                    alloc(dst)
                }
            }

            asyncFunction("flip") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val flipCode = argInt(args, 1)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Core.flip(src, dst, flipCode)
                    alloc(dst)
                }
            }

            asyncFunction("warpAffine") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val matrix = get(argInt(args, 1))
                val dstW = argInt(args, 2, src.cols())
                val dstH = argInt(args, 3, src.rows())
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.warpAffine(src, dst, matrix, Size(dstW.toDouble(), dstH.toDouble()))
                    alloc(dst)
                }
            }

            asyncFunction("warpPerspective") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val matrix = get(argInt(args, 1))
                val dstW = argInt(args, 2, src.cols())
                val dstH = argInt(args, 3, src.rows())
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.warpPerspective(src, dst, matrix, Size(dstW.toDouble(), dstH.toDouble()))
                    alloc(dst)
                }
            }

            asyncFunction("getRotationMatrix2D") { args ->
                ensureInit()
                val cx = argDouble(args, 0)
                val cy = argDouble(args, 1)
                val angle = argDouble(args, 2)
                val scale = argDouble(args, 3, 1.0)
                withContext(Dispatchers.Default) {
                    alloc(Imgproc.getRotationMatrix2D(Point(cx, cy), angle, scale))
                }
            }

            asyncFunction("getPerspectiveTransform") { args ->
                ensureInit()
                val srcPts = MatOfPoint2f(
                    Point(argDouble(args, 0), argDouble(args, 1)),
                    Point(argDouble(args, 2), argDouble(args, 3)),
                    Point(argDouble(args, 4), argDouble(args, 5)),
                    Point(argDouble(args, 6), argDouble(args, 7)),
                )
                val dstPts = MatOfPoint2f(
                    Point(argDouble(args, 8), argDouble(args, 9)),
                    Point(argDouble(args, 10), argDouble(args, 11)),
                    Point(argDouble(args, 12), argDouble(args, 13)),
                    Point(argDouble(args, 14), argDouble(args, 15)),
                )
                withContext(Dispatchers.Default) {
                    val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
                    srcPts.release()
                    dstPts.release()
                    alloc(m)
                }
            }

            // ── Filtering (async — per-pixel) ──

            asyncFunction("blur") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val kw = argInt(args, 1, 5)
                val kh = argInt(args, 2, kw)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.blur(src, dst, Size(kw.toDouble(), kh.toDouble()))
                    alloc(dst)
                }
            }

            asyncFunction("gaussianBlur") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val kw = argInt(args, 1, 5)
                val kh = argInt(args, 2, kw)
                val sigmaX = argDouble(args, 3, 0.0)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.GaussianBlur(src, dst, Size(kw.toDouble(), kh.toDouble()), sigmaX)
                    alloc(dst)
                }
            }

            asyncFunction("medianBlur") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val ksize = argInt(args, 1, 5)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.medianBlur(src, dst, ksize)
                    alloc(dst)
                }
            }

            asyncFunction("bilateralFilter") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val d = argInt(args, 1, 9)
                val sigmaColor = argDouble(args, 2, 75.0)
                val sigmaSpace = argDouble(args, 3, 75.0)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.bilateralFilter(src, dst, d, sigmaColor, sigmaSpace)
                    alloc(dst)
                }
            }

            // ── Edge detection (async — per-pixel) ──

            asyncFunction("canny") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val threshold1 = argDouble(args, 1, 100.0)
                val threshold2 = argDouble(args, 2, 200.0)
                val apertureSize = argInt(args, 3, 3)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.Canny(src, dst, threshold1, threshold2, apertureSize)
                    alloc(dst)
                }
            }

            asyncFunction("laplacian") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val ddepth = argInt(args, 1, CvType.CV_16S)
                val ksize = argInt(args, 2, 1)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.Laplacian(src, dst, ddepth, ksize)
                    alloc(dst)
                }
            }

            asyncFunction("sobel") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val ddepth = argInt(args, 1, CvType.CV_16S)
                val dx = argInt(args, 2, 1)
                val dy = argInt(args, 3, 0)
                val ksize = argInt(args, 4, 3)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.Sobel(src, dst, ddepth, dx, dy, ksize)
                    alloc(dst)
                }
            }

            // ── Thresholding (async — per-pixel) ──

            asyncFunction("threshold") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val thresh = argDouble(args, 1, 127.0)
                val maxval = argDouble(args, 2, 255.0)
                val type = argInt(args, 3, Imgproc.THRESH_BINARY)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    val computed = Imgproc.threshold(src, dst, thresh, maxval, type)
                    val matId = alloc(dst)
                    mapOf<String, Any?>("matId" to matId, "threshold" to computed)
                }
            }

            asyncFunction("adaptiveThreshold") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val maxval = argDouble(args, 1, 255.0)
                val adaptiveMethod = argInt(args, 2, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C)
                val thresholdType = argInt(args, 3, Imgproc.THRESH_BINARY)
                val blockSize = argInt(args, 4, 11)
                val c = argDouble(args, 5, 2.0)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.adaptiveThreshold(src, dst, maxval, adaptiveMethod, thresholdType, blockSize, c)
                    alloc(dst)
                }
            }

            // ── Morphology (async — per-pixel) ──

            asyncFunction("erode") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val kw = argInt(args, 1, 3)
                val kh = argInt(args, 2, kw)
                val iterations = argInt(args, 3, 1)
                withContext(Dispatchers.Default) {
                    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kw.toDouble(), kh.toDouble()))
                    val dst = Mat()
                    Imgproc.erode(src, dst, kernel, Point(-1.0, -1.0), iterations)
                    kernel.release()
                    alloc(dst)
                }
            }

            asyncFunction("dilate") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val kw = argInt(args, 1, 3)
                val kh = argInt(args, 2, kw)
                val iterations = argInt(args, 3, 1)
                withContext(Dispatchers.Default) {
                    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kw.toDouble(), kh.toDouble()))
                    val dst = Mat()
                    Imgproc.dilate(src, dst, kernel, Point(-1.0, -1.0), iterations)
                    kernel.release()
                    alloc(dst)
                }
            }

            asyncFunction("morphologyEx") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val op = argInt(args, 1)
                val kw = argInt(args, 2, 3)
                val kh = argInt(args, 3, kw)
                val shape = argInt(args, 4, Imgproc.MORPH_RECT)
                val iterations = argInt(args, 5, 1)
                withContext(Dispatchers.Default) {
                    val kernel = Imgproc.getStructuringElement(shape, Size(kw.toDouble(), kh.toDouble()))
                    val dst = Mat()
                    Imgproc.morphologyEx(src, dst, op, kernel, Point(-1.0, -1.0), iterations)
                    kernel.release()
                    alloc(dst)
                }
            }

            // ── Contours (async — per-pixel + allocation) ──

            asyncFunction("findContours") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val mode = argInt(args, 1, Imgproc.RETR_EXTERNAL)
                val method = argInt(args, 2, Imgproc.CHAIN_APPROX_SIMPLE)
                withContext(Dispatchers.Default) {
                    val contours = mutableListOf<MatOfPoint>()
                    val hierarchy = Mat()
                    val work = src.clone()
                    Imgproc.findContours(work, contours, hierarchy, mode, method)
                    work.release()
                    hierarchy.release()
                    val result = contours.map { contour ->
                        val pts = contour.toList().map { listOf(it.x.toInt(), it.y.toInt()) }
                        val area = Imgproc.contourArea(contour)
                        val arcLen = Imgproc.arcLength(MatOfPoint2f(*contour.toArray().map { Point(it.x, it.y) }.toTypedArray()), true)
                        val br = Imgproc.boundingRect(contour)
                        contour.release()
                        mapOf<String, Any?>(
                            "points" to pts,
                            "area" to area,
                            "arcLength" to arcLen,
                            "boundingRect" to mapOf("x" to br.x, "y" to br.y, "w" to br.width, "h" to br.height),
                        )
                    }
                    result
                }
            }

            asyncFunction("drawContours") { args ->
                ensureInit()
                val matId = argInt(args, 0)
                val mat = get(matId)
                val contoursData = args.getOrNull(1)
                val idx = argInt(args, 2, -1)
                val r = argDouble(args, 3, 0.0)
                val g = argDouble(args, 4, 255.0)
                val b = argDouble(args, 5, 0.0)
                val thickness = argInt(args, 6, 2)
                val contoursList = when (contoursData) {
                    is List<*> -> contoursData.mapNotNull { contourObj ->
                        when (contourObj) {
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                val pts = contourObj["points"] as? List<List<Number>> ?: return@mapNotNull null
                                MatOfPoint(*pts.map { Point(it[0].toDouble(), it[1].toDouble()) }.toTypedArray())
                            }
                            else -> null
                        }
                    }
                    else -> throw Exception("cv.drawContours: contours must be an array")
                }
                withContext(Dispatchers.Default) {
                    Imgproc.drawContours(mat, contoursList, idx, Scalar(b, g, r), thickness)
                    contoursList.forEach { it.release() }
                    matId
                }
            }

            // ── Drawing (async — scales with image, mutates in-place) ──

            asyncFunction("rectangle") { args ->
                val matId = argInt(args, 0)
                val mat = get(matId)
                val x = argInt(args, 1)
                val y = argInt(args, 2)
                val w = argInt(args, 3)
                val h = argInt(args, 4)
                val r = argDouble(args, 5, 0.0)
                val g = argDouble(args, 6, 255.0)
                val b = argDouble(args, 7, 0.0)
                val thickness = argInt(args, 8, 2)
                withContext(Dispatchers.Default) {
                    Imgproc.rectangle(mat, Rect(x, y, w, h), Scalar(b, g, r), thickness)
                    matId
                }
            }

            asyncFunction("circle") { args ->
                val matId = argInt(args, 0)
                val mat = get(matId)
                val cx = argInt(args, 1)
                val cy = argInt(args, 2)
                val radius = argInt(args, 3)
                val r = argDouble(args, 4, 0.0)
                val g = argDouble(args, 5, 255.0)
                val b = argDouble(args, 6, 0.0)
                val thickness = argInt(args, 7, 2)
                withContext(Dispatchers.Default) {
                    Imgproc.circle(mat, Point(cx.toDouble(), cy.toDouble()), radius, Scalar(b, g, r), thickness)
                    matId
                }
            }

            asyncFunction("line") { args ->
                val matId = argInt(args, 0)
                val mat = get(matId)
                val x1 = argInt(args, 1)
                val y1 = argInt(args, 2)
                val x2 = argInt(args, 3)
                val y2 = argInt(args, 4)
                val r = argDouble(args, 5, 0.0)
                val g = argDouble(args, 6, 255.0)
                val b = argDouble(args, 7, 0.0)
                val thickness = argInt(args, 8, 2)
                withContext(Dispatchers.Default) {
                    Imgproc.line(mat, Point(x1.toDouble(), y1.toDouble()), Point(x2.toDouble(), y2.toDouble()), Scalar(b, g, r), thickness)
                    matId
                }
            }

            asyncFunction("putText") { args ->
                val matId = argInt(args, 0)
                val mat = get(matId)
                val text = argStr(args, 1)
                val x = argInt(args, 2)
                val y = argInt(args, 3)
                val fontFace = argInt(args, 4, Imgproc.FONT_HERSHEY_SIMPLEX)
                val fontScale = argDouble(args, 5, 1.0)
                val r = argDouble(args, 6, 255.0)
                val g = argDouble(args, 7, 255.0)
                val b = argDouble(args, 8, 255.0)
                val thickness = argInt(args, 9, 1)
                withContext(Dispatchers.Default) {
                    Imgproc.putText(mat, text, Point(x.toDouble(), y.toDouble()), fontFace, fontScale, Scalar(b, g, r), thickness)
                    matId
                }
            }

            // ── Features (async — heavy computation) ──

            asyncFunction("goodFeaturesToTrack") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val maxCorners = argInt(args, 1, 100)
                val qualityLevel = argDouble(args, 2, 0.01)
                val minDistance = argDouble(args, 3, 10.0)
                withContext(Dispatchers.Default) {
                    val corners = MatOfPoint()
                    Imgproc.goodFeaturesToTrack(src, corners, maxCorners, qualityLevel, minDistance)
                    val result = corners.toList().map { listOf(it.x, it.y) }
                    corners.release()
                    result
                }
            }

            asyncFunction("matchTemplate") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val tmpl = get(argInt(args, 1))
                val method = argInt(args, 2, Imgproc.TM_CCOEFF_NORMED)
                withContext(Dispatchers.Default) {
                    val result = Mat()
                    Imgproc.matchTemplate(src, tmpl, result, method)
                    val minMaxLoc = Core.minMaxLoc(result)
                    val matId = alloc(result)
                    mapOf<String, Any?>(
                        "matId" to matId,
                        "minVal" to minMaxLoc.minVal,
                        "maxVal" to minMaxLoc.maxVal,
                        "minLoc" to listOf(minMaxLoc.minLoc.x, minMaxLoc.minLoc.y),
                        "maxLoc" to listOf(minMaxLoc.maxLoc.x, minMaxLoc.maxLoc.y),
                    )
                }
            }

            asyncFunction("detectORB") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val maxFeatures = argInt(args, 1, 500)
                withContext(Dispatchers.Default) {
                    val orb = ORB.create(maxFeatures)
                    val keypoints = MatOfKeyPoint()
                    val descriptors = Mat()
                    orb.detectAndCompute(src, Mat(), keypoints, descriptors)
                    val kpList = keypoints.toList().map { kp ->
                        mapOf<String, Any?>(
                            "x" to kp.pt.x,
                            "y" to kp.pt.y,
                            "size" to kp.size.toDouble(),
                            "angle" to kp.angle.toDouble(),
                            "response" to kp.response.toDouble(),
                        )
                    }
                    val descId = if (!descriptors.empty()) alloc(descriptors) else -1
                    keypoints.release()
                    mapOf<String, Any?>("keypoints" to kpList, "descriptorsMatId" to descId)
                }
            }

            // ── Histograms (async — per-pixel) ──

            asyncFunction("calcHist") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val channel = argInt(args, 1, 0)
                val bins = argInt(args, 2, 256)
                withContext(Dispatchers.Default) {
                    val hist = Mat()
                    Imgproc.calcHist(
                        listOf(src),
                        MatOfInt(channel),
                        Mat(),
                        hist,
                        MatOfInt(bins),
                        MatOfFloat(0f, 256f),
                    )
                    val result = FloatArray(hist.rows())
                    hist.get(0, 0, result)
                    hist.release()
                    result.map { it.toDouble() }
                }
            }

            asyncFunction("equalizeHist") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Imgproc.equalizeHist(src, dst)
                    alloc(dst)
                }
            }

            // ── Arithmetic / Pixel ops (async — per-pixel) ──

            asyncFunction("convertTo") { args ->
                val src = get(argInt(args, 0))
                val rtype = argInt(args, 1, CvType.CV_8UC3)
                val alpha = argDouble(args, 2, 1.0)
                val beta = argDouble(args, 3, 0.0)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    src.convertTo(dst, rtype, alpha, beta)
                    alloc(dst)
                }
            }

            asyncFunction("absdiff") { args ->
                ensureInit()
                val src1 = get(argInt(args, 0))
                val src2 = get(argInt(args, 1))
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Core.absdiff(src1, src2, dst)
                    alloc(dst)
                }
            }

            asyncFunction("addWeighted") { args ->
                ensureInit()
                val src1 = get(argInt(args, 0))
                val alpha = argDouble(args, 1, 1.0)
                val src2 = get(argInt(args, 2))
                val beta = argDouble(args, 3, 1.0)
                val gamma = argDouble(args, 4, 0.0)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Core.addWeighted(src1, alpha, src2, beta, gamma, dst)
                    alloc(dst)
                }
            }

            asyncFunction("bitwiseAnd") { args ->
                ensureInit()
                val src1 = get(argInt(args, 0))
                val src2 = get(argInt(args, 1))
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Core.bitwise_and(src1, src2, dst)
                    alloc(dst)
                }
            }

            asyncFunction("bitwiseOr") { args ->
                ensureInit()
                val src1 = get(argInt(args, 0))
                val src2 = get(argInt(args, 1))
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Core.bitwise_or(src1, src2, dst)
                    alloc(dst)
                }
            }

            asyncFunction("bitwiseNot") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Core.bitwise_not(src, dst)
                    alloc(dst)
                }
            }

            asyncFunction("inRange") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val lowerB = argDouble(args, 1)
                val lowerG = argDouble(args, 2)
                val lowerR = argDouble(args, 3)
                val upperB = argDouble(args, 4)
                val upperG = argDouble(args, 5)
                val upperR = argDouble(args, 6)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Core.inRange(src, Scalar(lowerB, lowerG, lowerR), Scalar(upperB, upperG, upperR), dst)
                    alloc(dst)
                }
            }

            // ── Denoising (async — very heavy) ──

            asyncFunction("fastNlMeansDenoising") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val h = argDouble(args, 1, 3.0)
                val templateWindowSize = argInt(args, 2, 7)
                val searchWindowSize = argInt(args, 3, 21)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Photo.fastNlMeansDenoising(src, dst, h.toFloat(), templateWindowSize, searchWindowSize)
                    alloc(dst)
                }
            }

            asyncFunction("fastNlMeansDenoisingColored") { args ->
                ensureInit()
                val src = get(argInt(args, 0))
                val h = argDouble(args, 1, 3.0)
                val hColor = argDouble(args, 2, 3.0)
                val templateWindowSize = argInt(args, 3, 7)
                val searchWindowSize = argInt(args, 4, 21)
                withContext(Dispatchers.Default) {
                    val dst = Mat()
                    Photo.fastNlMeansDenoisingColored(src, dst, h.toFloat(), hColor.toFloat(), templateWindowSize, searchWindowSize)
                    alloc(dst)
                }
            }
        }
    }

    fun cleanup() {
        for ((_, mat) in mats) {
            try { mat.release() } catch (_: Exception) {}
        }
        mats.clear()
    }

    companion object {
        private const val TAG = "JsCvBridge"
    }
}
