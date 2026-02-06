package jp.espresso3389.kugutz.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.widget.FrameLayout
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AppCompatActivity
import jp.espresso3389.kugutz.service.AgentService
import jp.espresso3389.kugutz.service.PythonRuntimeManager
import jp.espresso3389.kugutz.service.LocalHttpServer
import jp.espresso3389.kugutz.ui.WebAppBridge
import jp.espresso3389.kugutz.perm.DevicePermissionPolicy
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val pendingAndroidPermRequestId = AtomicReference<String?>(null)
    private val pendingAndroidPermAction = AtomicReference<((Boolean) -> Unit)?>(null)
    private val androidPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val ok = results.values.all { it }
            val cb = pendingAndroidPermAction.getAndSet(null)
            pendingAndroidPermRequestId.set(null)
            cb?.invoke(ok)
        }
    private val pythonHealthReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(PythonRuntimeManager.EXTRA_STATUS) ?: return
            publishStatusToWeb(status)
        }
    }
    private val permissionPromptReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_ID) ?: return
            val tool = intent.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_TOOL) ?: return
            val detail = intent.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_DETAIL) ?: ""
            val forceBio = intent.getBooleanExtra(LocalHttpServer.EXTRA_PERMISSION_BIOMETRIC, false)
            handlePermissionPrompt(id, tool, detail, forceBio)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startForegroundService(Intent(this, AgentService::class.java))
        ensureNotificationPermission()

        val root = FrameLayout(this)
        webView = WebView(this)

        root.addView(webView)
        setContentView(root)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        webView.clearCache(true)
        webView.clearHistory()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.d(
                    "KugutzWeb",
                    "console: ${message.message()} @${message.lineNumber()} ${message.sourceId()}"
                )
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                android.util.Log.d("KugutzWeb", "page finished: $url")
                view.evaluateJavascript("console.log('Kugutz page loaded: ' + location.href)", null)
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    view.postDelayed({ view.reload() }, 1200)
                }
            }
        }
        webView.addJavascriptInterface(WebAppBridge(this), "AndroidBridge")

        // Load the local UI served by Kotlin.
        webView.loadUrl("http://127.0.0.1:8765/ui/index.html")
    }

    override fun onStart() {
        super.onStart()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                pythonHealthReceiver,
                IntentFilter(PythonRuntimeManager.ACTION_PYTHON_HEALTH),
                Context.RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                permissionPromptReceiver,
                IntentFilter(LocalHttpServer.ACTION_PERMISSION_PROMPT),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(pythonHealthReceiver, IntentFilter(PythonRuntimeManager.ACTION_PYTHON_HEALTH))
            registerReceiver(permissionPromptReceiver, IntentFilter(LocalHttpServer.ACTION_PERMISSION_PROMPT))
        }
    }

    override fun onStop() {
        unregisterReceiver(pythonHealthReceiver)
        unregisterReceiver(permissionPromptReceiver)
        super.onStop()
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

    fun reloadUi() {
        val url = "http://127.0.0.1:8765/ui/index.html?ts=${System.currentTimeMillis()}"
        webView.post { webView.loadUrl(url) }
        Toast.makeText(this, "UI reset applied", Toast.LENGTH_SHORT).show()
    }

    private fun handlePermissionPrompt(id: String, tool: String, detail: String, forceBiometric: Boolean) {
        val broker = jp.espresso3389.kugutz.perm.PermissionBroker(this)
        broker.requestConsent(tool, detail, forceBiometric) { approved ->
            if (!approved) {
                postPermissionDecision(id, approved = false)
                return@requestConsent
            }

            val required = DevicePermissionPolicy.requiredFor(tool, detail)
            val perms = required?.androidPermissions ?: emptyList()
            if (perms.isEmpty()) {
                postPermissionDecision(id, approved = true)
                return@requestConsent
            }

            // Request Android runtime permissions. If denied, we deny the tool request as well.
            // Avoid overlapping requests: if one is already in-flight, fail closed.
            if (pendingAndroidPermRequestId.get() != null) {
                postPermissionDecision(id, approved = false)
                return@requestConsent
            }

            val missing = perms.filter { p ->
                ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                postPermissionDecision(id, approved = true)
                return@requestConsent
            }

            pendingAndroidPermRequestId.set(id)
            pendingAndroidPermAction.set { ok -> postPermissionDecision(id, approved = ok) }
            androidPermLauncher.launch(missing.toTypedArray())
        }
    }

    private fun postPermissionDecision(id: String, approved: Boolean) {
        Thread {
            try {
                val action = if (approved) "approve" else "deny"
                val url = java.net.URL("http://127.0.0.1:8765/permissions/$id/$action")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.outputStream.use { it.write(ByteArray(0)) }
                conn.inputStream.use { }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun startPythonWorker() {
        val intent = Intent(this, AgentService::class.java)
        intent.action = AgentService.ACTION_START_PYTHON
        startForegroundService(intent)
        webView.post {
            webView.evaluateJavascript(
                "window.onPythonRestartRequested && window.onPythonRestartRequested()",
                null
            )
        }
        Toast.makeText(this, "Start request sent…", Toast.LENGTH_SHORT).show()
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
    }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            1001
        )
    }

}
