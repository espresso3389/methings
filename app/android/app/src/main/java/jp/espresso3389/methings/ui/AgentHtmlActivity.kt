package jp.espresso3389.methings.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

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

        val root = FrameLayout(this)
        val webView = WebView(this)
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = "Close"
            setBackgroundColor(0x66000000) // semi-transparent for visibility
            setOnClickListener { finish() }
        }

        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lp = FrameLayout.LayoutParams(140, 140).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 24
            rightMargin = 24
        }
        root.addView(close, lp)
        setContentView(root)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        webView.clearCache(true)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val s = u.scheme ?: ""
                if (s == "http" || s == "https") {
                    // Keep the agent view scoped to local content; open external links in browser.
                    val host = (u.host ?: "").lowercase()
                    if (host != "127.0.0.1" && host != "localhost") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, u))
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
}

