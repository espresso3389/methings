package jp.espresso3389.kugutz.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import jp.espresso3389.kugutz.service.AgentService
import jp.espresso3389.kugutz.service.PythonRuntimeManager
import jp.espresso3389.kugutz.ui.WebAppBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var statusBadge: TextView
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val healthReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(PythonRuntimeManager.EXTRA_STATUS) ?: return
            updateStatusBadge(status)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(Intent(this, AgentService::class.java))

        val root = FrameLayout(this)
        webView = WebView(this)
        statusBadge = TextView(this)
        statusBadge.text = "Python: starting"
        statusBadge.setTextColor(Color.WHITE)
        statusBadge.setBackgroundColor(Color.parseColor("#88000000"))
        statusBadge.setPadding(20, 10, 20, 10)
        statusBadge.setOnClickListener { showStatusDialog() }

        val badgeParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        badgeParams.gravity = Gravity.TOP or Gravity.END
        badgeParams.setMargins(16, 16, 16, 16)

        root.addView(webView)
        root.addView(statusBadge, badgeParams)
        setContentView(root)

        val lastStatus = prefs.getString(PREF_STATUS, "starting") ?: "starting"
        val lastTs = prefs.getLong(PREF_STATUS_TS, 0L)
        updateStatusBadge(lastStatus)
        if (lastTs > 0L) {
            statusBadge.tag = lastTs
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        webView.clearCache(true)
        webView.clearHistory()
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(WebAppBridge(this), "AndroidBridge")

        // Load local UI shell. It can call http://127.0.0.1:8765 once the service is running.
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    override fun onStart() {
        super.onStart()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                healthReceiver,
                IntentFilter(PythonRuntimeManager.ACTION_PYTHON_HEALTH),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(healthReceiver, IntentFilter(PythonRuntimeManager.ACTION_PYTHON_HEALTH))
        }
    }

    override fun onStop() {
        unregisterReceiver(healthReceiver)
        super.onStop()
    }

    private fun updateStatusBadge(status: String) {
        prefs.edit().putString(PREF_STATUS, status).apply()
        val now = System.currentTimeMillis()
        prefs.edit().putLong(PREF_STATUS_TS, now).apply()
        statusBadge.tag = now
        val rel = formatRelativeTime(now)
        when (status) {
            "ok" -> {
                statusBadge.text = "Python: OK • $rel"
                statusBadge.setBackgroundColor(Color.parseColor("#2E7D32"))
            }
            "offline" -> {
                statusBadge.text = "Python: offline • $rel"
                statusBadge.setBackgroundColor(Color.parseColor("#C62828"))
            }
            "stopping" -> {
                statusBadge.text = "Python: stopping • $rel"
                statusBadge.setBackgroundColor(Color.parseColor("#8D6E63"))
            }
            else -> {
                statusBadge.text = "Python: starting • $rel"
                statusBadge.setBackgroundColor(Color.parseColor("#616161"))
            }
        }
        publishStatusToWeb(status)
    }

    private fun publishStatusToWeb(status: String) {
        val payload = status.replace("'", "\\'")
        webView.post {
            webView.evaluateJavascript(
                "window.onPythonStatus && window.onPythonStatus('${payload}')",
                null
            )
        }
    }

    fun showStatusDialog() {
        val lastTs = statusBadge.tag as? Long ?: 0L
        val tsText = if (lastTs > 0L) {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val rel = formatRelativeTime(lastTs)
            "Last update: ${fmt.format(Date(lastTs))} ($rel)"
        } else {
            "Last update: unknown"
        }
        AlertDialog.Builder(this)
            .setTitle("Python Service")
            .setMessage("${statusBadge.text}\n$tsText")
            .setPositiveButton("Restart") { _, _ -> restartService() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun restartService() {
        val intent = Intent(this, AgentService::class.java)
        intent.action = AgentService.ACTION_RESTART_PYTHON
        startForegroundService(intent)
        webView.post {
            webView.evaluateJavascript(
                "window.onPythonRestartRequested && window.onPythonRestartRequested()",
                null
            )
        }
        Toast.makeText(this, "Restart request sent…", Toast.LENGTH_SHORT).show()
        updateStatusBadge("starting")
    }

    companion object {
        private const val PREFS_NAME = "python_status"
        private const val PREF_STATUS = "last_status"
        private const val PREF_STATUS_TS = "last_status_ts"
    }

    private fun formatRelativeTime(timestamp: Long): String {
        val deltaMs = abs(System.currentTimeMillis() - timestamp)
        val seconds = deltaMs / 1000
        return when {
            seconds < 10 -> "just now"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }
}
