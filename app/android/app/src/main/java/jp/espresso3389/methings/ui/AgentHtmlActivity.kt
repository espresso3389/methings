package jp.espresso3389.methings.ui

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

class AgentHtmlActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_REL_PATH = "rel_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val relPath = (intent?.getStringExtra(EXTRA_REL_PATH) ?: "").trim().trimStart('/')
        if (relPath.isBlank() || relPath.contains("..")) {
            finish()
            return
        }

        val dp = resources.displayMetrics.density

        // --- Title TextView ---
        val titleView = TextView(this).apply {
            text = ""
            setTextColor(0xFFECECF1.toInt())           // --ink
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }

        // --- Close button with programmatic X drawable ---
        val closeBtn = ImageButton(this).apply {
            contentDescription = "Close"
            setImageDrawable(XDrawable((14 * dp).toInt(), 0xFFA1A1AA.toInt()))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF24242A.toInt())             // --surface
                cornerRadius = 8 * dp
            }
            background = bg
            setOnClickListener { finish() }
        }

        // --- Toolbar (horizontal LinearLayout, 44dp) ---
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF18181B.toInt())       // --bg-raised
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (8 * dp).toInt()
            setPadding(hPad, 0, hPad, 0)
        }

        val titleLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        toolbar.addView(titleView, titleLp)

        val btnSize = (34 * dp).toInt()
        val btnLp = LinearLayout.LayoutParams(btnSize, btnSize).apply {
            marginStart = (4 * dp).toInt()
        }
        toolbar.addView(closeBtn, btnLp)

        // --- Divider (1dp) ---
        val divider = View(this).apply {
            setBackgroundColor(0xFF2E2E36.toInt())       // --border
        }

        // --- WebView ---
        val webView = WebView(this)

        // --- Root vertical LinearLayout ---
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E0E10.toInt())       // --bg
        }

        val toolbarHeight = (44 * dp).toInt()
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, toolbarHeight))
        root.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt()))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        // --- WebView configuration ---
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        webView.clearCache(true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                titleView.text = title ?: ""
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val s = u.scheme ?: ""
                if (s == "http" || s == "https") {
                    // Keep the agent view scoped to local content; open external links in browser.
                    val host = (u.host ?: "").lowercase()
                    if (host != "127.0.0.1" && host != "localhost") {
                        try {
                            val useExternal = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                                .getBoolean("open_links_external", false)
                            if (useExternal) {
                                startActivity(Intent(Intent.ACTION_VIEW, u))
                            } else {
                                val params = CustomTabColorSchemeParams.Builder()
                                    .setToolbarColor(0xFF0e0e10.toInt())
                                    .build()
                                CustomTabsIntent.Builder()
                                    .setDefaultColorSchemeParams(params)
                                    .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                                    .build()
                                    .launchUrl(this@AgentHtmlActivity, u)
                            }
                        } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        }

        val url = Uri.parse("http://127.0.0.1:8765/user/www/").buildUpon()
            .appendEncodedPath(Uri.encode(relPath, "/"))
            .build()
            .toString()
        webView.loadUrl(url)
    }

    /** Draws an X (two diagonal lines) with rounded stroke caps. */
    private class XDrawable(private val size: Int, private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@XDrawable.color
            style = Paint.Style.STROKE
            strokeWidth = size / 7f
            strokeCap = Paint.Cap.ROUND
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size

        override fun draw(canvas: Canvas) {
            val b = bounds
            val inset = size * 0.18f
            val l = b.left + inset
            val t = b.top + inset
            val r = b.right - inset
            val bo = b.bottom - inset
            canvas.drawLine(l, t, r, bo, paint)
            canvas.drawLine(r, t, l, bo, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
