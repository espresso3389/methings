package jp.espresso3389.methings.device

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Singleton manager for the agent-controllable WebView browser.
 *
 * Holds a reference to the browser WebView embedded as a split panel in
 * [jp.espresso3389.methings.ui.MainActivity].
 * All public methods are thread-safe and designed to be called from NanoHTTPD threads;
 * WebView work is dispatched to the main thread via [Handler] + [CountDownLatch].
 */
object WebViewBrowserManager {
    const val ACTION_BROWSER_CLOSE = "jp.espresso3389.methings.BROWSER_CLOSE"
    const val ACTION_BROWSER_SHOW = "jp.espresso3389.methings.BROWSER_SHOW"
    const val EXTRA_URL = "url"
    const val EXTRA_FULLSCREEN = "fullscreen"
    const val EXTRA_POSITION = "position"

    private val handler = Handler(Looper.getMainLooper())

    @Volatile var webView: WebView? = null
    @Volatile var currentUrl: String = ""
    @Volatile var currentTitle: String = ""
    @Volatile var isLoading: Boolean = false
    @Volatile var pageLoadError: String? = null

    /** Signalled by the browser WebViewClient when a page finishes loading or errors. */
    @Volatile var pageLoadLatch: CountDownLatch? = null

    // ── Open ──

    fun open(context: Context, url: String, timeoutS: Long = 30): JSONObject {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            return JSONObject().put("status", "error").put("error", "url_required")
        }

        pageLoadError = null
        val latch = CountDownLatch(1)
        pageLoadLatch = latch

        val wv = webView
        if (wv != null) {
            // Panel already open — just load the new URL.
            handler.post { wv.loadUrl(trimmedUrl) }
        } else {
            // Ask MainActivity to show the embedded browser panel.
            val intent = Intent(ACTION_BROWSER_SHOW).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_URL, trimmedUrl)
            }
            context.sendBroadcast(intent)
        }

        val loaded = latch.await(timeoutS, TimeUnit.SECONDS)
        pageLoadLatch = null

        val err = pageLoadError
        return if (err != null) {
            JSONObject()
                .put("status", "error")
                .put("error", "page_load_error")
                .put("detail", err)
                .put("url", currentUrl)
        } else if (!loaded) {
            JSONObject()
                .put("status", "error")
                .put("error", "timeout")
                .put("url", currentUrl)
        } else {
            JSONObject()
                .put("status", "ok")
                .put("url", currentUrl)
                .put("title", currentTitle)
        }
    }

    // ── Close ──

    fun close(context: Context): JSONObject {
        if (webView == null) {
            return JSONObject().put("status", "ok").put("detail", "not_open")
        }
        val intent = Intent(ACTION_BROWSER_CLOSE).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        return JSONObject().put("status", "ok")
    }

    // ── Status ──

    fun status(): JSONObject {
        val wv = webView ?: return JSONObject()
            .put("status", "ok")
            .put("open", false)

        val result = JSONObject()
        val latch = CountDownLatch(1)
        handler.post {
            try {
                result.put("width", wv.width)
                result.put("height", wv.height)
                result.put("content_height", (wv.contentHeight * wv.resources.displayMetrics.density).toInt())
                result.put("can_go_back", wv.canGoBack())
                result.put("can_go_forward", wv.canGoForward())
            } catch (_: Exception) {}
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)

        result.put("status", "ok")
        result.put("open", true)
        result.put("url", currentUrl)
        result.put("title", currentTitle)
        result.put("loading", isLoading)
        return result
    }

    // ── Screenshot ──

    fun screenshot(outFile: File, quality: Int = 80, timeoutS: Long = 10): JSONObject {
        val wv = webView ?: return JSONObject()
            .put("status", "error")
            .put("error", "browser_not_open")

        val latch = CountDownLatch(1)
        var error: String? = null
        handler.post {
            try {
                val w = wv.width
                val h = wv.height
                if (w <= 0 || h <= 0) {
                    error = "webview_has_no_size"
                    latch.countDown()
                    return@post
                }
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                wv.draw(canvas)
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), fos)
                }
                bitmap.recycle()
            } catch (e: Exception) {
                error = e.message ?: "screenshot_failed"
            }
            latch.countDown()
        }

        val ok = latch.await(timeoutS, TimeUnit.SECONDS)
        if (!ok) return JSONObject().put("status", "error").put("error", "timeout")
        if (error != null) return JSONObject().put("status", "error").put("error", error)
        return JSONObject()
            .put("status", "ok")
            .put("width", outFile.length()) // file size, but width/height set below
    }

    // ── JavaScript execution ──

    fun evaluateJs(script: String, timeoutS: Long = 10): JSONObject {
        val wv = webView ?: return JSONObject()
            .put("status", "error")
            .put("error", "browser_not_open")

        val latch = CountDownLatch(1)
        var result: String? = null
        var error: String? = null

        handler.post {
            try {
                wv.evaluateJavascript(script) { value ->
                    result = value
                    latch.countDown()
                }
            } catch (e: Exception) {
                error = e.message ?: "js_eval_failed"
                latch.countDown()
            }
        }

        val ok = latch.await(timeoutS, TimeUnit.SECONDS)
        if (!ok) return JSONObject().put("status", "error").put("error", "timeout")
        if (error != null) return JSONObject().put("status", "error").put("error", error)
        return JSONObject()
            .put("status", "ok")
            .put("result", result ?: "null")
    }

    // ── Tap ──

    fun tap(x: Float, y: Float): JSONObject {
        val wv = webView ?: return JSONObject()
            .put("status", "error")
            .put("error", "browser_not_open")

        val latch = CountDownLatch(1)
        handler.post {
            try {
                val downTime = android.os.SystemClock.uptimeMillis()
                val downEvent = android.view.MotionEvent.obtain(
                    downTime, downTime,
                    android.view.MotionEvent.ACTION_DOWN, x, y, 0
                )
                wv.dispatchTouchEvent(downEvent)
                downEvent.recycle()

                val upEvent = android.view.MotionEvent.obtain(
                    downTime, downTime + 50,
                    android.view.MotionEvent.ACTION_UP, x, y, 0
                )
                wv.dispatchTouchEvent(upEvent)
                upEvent.recycle()
            } catch (_: Exception) {}
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return JSONObject().put("status", "ok")
    }

    // ── Scroll ──

    fun scroll(dx: Int, dy: Int): JSONObject {
        val wv = webView ?: return JSONObject()
            .put("status", "error")
            .put("error", "browser_not_open")

        val latch = CountDownLatch(1)
        handler.post {
            wv.scrollBy(dx, dy)
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return JSONObject().put("status", "ok")
    }

    // ── Back / Forward ──

    fun goBack(): JSONObject {
        val wv = webView ?: return JSONObject()
            .put("status", "error")
            .put("error", "browser_not_open")

        val latch = CountDownLatch(1)
        handler.post {
            if (wv.canGoBack()) wv.goBack()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return JSONObject().put("status", "ok")
    }

    fun goForward(): JSONObject {
        val wv = webView ?: return JSONObject()
            .put("status", "error")
            .put("error", "browser_not_open")

        val latch = CountDownLatch(1)
        handler.post {
            if (wv.canGoForward()) wv.goForward()
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return JSONObject().put("status", "ok")
    }

    // ── Callbacks from browser WebView ──

    fun notifyPageLoaded(url: String, title: String) {
        currentUrl = url
        currentTitle = title
        isLoading = false
        pageLoadError = null
        pageLoadLatch?.countDown()
    }

    fun notifyPageError(error: String) {
        pageLoadError = error
        isLoading = false
        pageLoadLatch?.countDown()
    }
}
