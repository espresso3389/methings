package jp.espresso3389.kugutz.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.net.Uri
import android.widget.FrameLayout
import android.widget.Toast
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AppCompatActivity
import jp.espresso3389.kugutz.service.AgentService
import jp.espresso3389.kugutz.service.PythonRuntimeManager
import jp.espresso3389.kugutz.service.LocalHttpServer
import jp.espresso3389.kugutz.ui.WebAppBridge
import jp.espresso3389.kugutz.perm.DevicePermissionPolicy
import org.kivy.android.PythonActivity
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

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

    private val pendingWebViewPermAction = AtomicReference<((Boolean) -> Unit)?>(null)
    private val webViewPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val ok = results.values.all { it }
            val cb = pendingWebViewPermAction.getAndSet(null)
            cb?.invoke(ok)
        }

    private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserMimeTypes: Array<String> = arrayOf("*/*")
    private var pendingFileChooserMultiple: Boolean = false
    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cb = pendingFilePathCallback
            pendingFilePathCallback = null
            if (cb == null) return@registerForActivityResult
            if (uri == null) cb.onReceiveValue(null) else cb.onReceiveValue(arrayOf(uri))
        }
    private val openMultipleDocumentsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val cb = pendingFilePathCallback
            pendingFilePathCallback = null
            if (cb == null) return@registerForActivityResult
            if (uris.isNullOrEmpty()) cb.onReceiveValue(null) else cb.onReceiveValue(uris.toTypedArray())
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
    private val uiReloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocalHttpServer.ACTION_UI_RELOAD) return
            reloadUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Compatibility shim: provide a non-Kivy Activity handle for p4a code paths
        // that look up `org.kivy.android.PythonActivity.mActivity`.
        PythonActivity.mActivity = this

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

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                // Cancel any previous pending chooser.
                pendingFilePathCallback?.onReceiveValue(null)
                pendingFilePathCallback = filePathCallback

                val accept = fileChooserParams?.acceptTypes
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toTypedArray()
                    ?: emptyArray()
                pendingFileChooserMimeTypes = if (accept.isNotEmpty()) accept else arrayOf("*/*")
                pendingFileChooserMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                return try {
                    if (pendingFileChooserMultiple) {
                        openMultipleDocumentsLauncher.launch(pendingFileChooserMimeTypes)
                    } else {
                        openDocumentLauncher.launch(pendingFileChooserMimeTypes)
                    }
                    true
                } catch (_: Exception) {
                    pendingFilePathCallback?.onReceiveValue(null)
                    pendingFilePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val wanted = req.resources?.toList() ?: emptyList()
                val perms = ArrayList<String>()
                if (wanted.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) perms.add(Manifest.permission.CAMERA)
                if (wanted.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) perms.add(Manifest.permission.RECORD_AUDIO)
                if (perms.isEmpty()) {
                    // e.g. protected media id, midi, etc. Fail closed.
                    req.deny()
                    return
                }

                val missing = perms.filter { p ->
                    ActivityCompat.checkSelfPermission(this@MainActivity, p) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    req.grant(req.resources)
                    return
                }

                // Avoid overlapping permission requests.
                if (pendingWebViewPermAction.get() != null) {
                    req.deny()
                    return
                }
                pendingWebViewPermAction.set { ok ->
                    if (ok) req.grant(req.resources) else req.deny()
                }
                webViewPermLauncher.launch(missing.toTypedArray())
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

        // If launched from a permission notification, handle it immediately.
        maybeHandlePermissionIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandlePermissionIntent(intent)
    }

    private fun maybeHandlePermissionIntent(intent: Intent?) {
        val id = intent?.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_ID) ?: return
        val tool = intent.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_TOOL) ?: return
        val detail = intent.getStringExtra(LocalHttpServer.EXTRA_PERMISSION_DETAIL) ?: ""
        val forceBio = intent.getBooleanExtra(LocalHttpServer.EXTRA_PERMISSION_BIOMETRIC, false)
        // Clear extras so we don't re-trigger on rotation or process recreation.
        intent.removeExtra(LocalHttpServer.EXTRA_PERMISSION_ID)
        intent.removeExtra(LocalHttpServer.EXTRA_PERMISSION_TOOL)
        intent.removeExtra(LocalHttpServer.EXTRA_PERMISSION_DETAIL)
        intent.removeExtra(LocalHttpServer.EXTRA_PERMISSION_BIOMETRIC)
        handlePermissionPrompt(id, tool, detail, forceBio)
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
            registerReceiver(
                uiReloadReceiver,
                IntentFilter(LocalHttpServer.ACTION_UI_RELOAD),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(pythonHealthReceiver, IntentFilter(PythonRuntimeManager.ACTION_PYTHON_HEALTH))
            registerReceiver(permissionPromptReceiver, IntentFilter(LocalHttpServer.ACTION_PERMISSION_PROMPT))
            registerReceiver(uiReloadReceiver, IntentFilter(LocalHttpServer.ACTION_UI_RELOAD))
        }
    }

    override fun onStop() {
        unregisterReceiver(pythonHealthReceiver)
        unregisterReceiver(permissionPromptReceiver)
        unregisterReceiver(uiReloadReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        pendingFilePathCallback?.onReceiveValue(null)
        pendingFilePathCallback = null
        super.onDestroy()
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

    fun evalJs(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun extractUsbHint(detail: String): Triple<String?, Int?, Int?> {
        // Heuristic parse from the permission detail text. DeviceApiTool typically embeds JSON like:
        // usb.open: {"name":"...","vendor_id":1234,"product_id":5678}
        val s = detail ?: ""
        fun mInt(key: String): Int? {
            val r = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\\d+)")
            val m = r.find(s) ?: return null
            return try { m.groupValues[1].toInt() } catch (_: Exception) { null }
        }
        fun mIntLoose(key: String): Int? {
            // Also match patterns like: vid=1234, vendor_id=1234
            val r = Regex("(^|\\W)" + Regex.escape(key) + "\\s*=\\s*(\\d+)(\\W|$)")
            val m = r.find(s) ?: return null
            return try { m.groupValues[2].toInt() } catch (_: Exception) { null }
        }
        fun mStr(key: String): String? {
            val r = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"]+)\"")
            val m = r.find(s) ?: return null
            return m.groupValues[1]
        }
        fun mStrLoose(key: String): String? {
            // Match: name=/dev/bus/usb/..., name = ...
            val r = Regex("(^|\\W)" + Regex.escape(key) + "\\s*=\\s*([^\\s,;]+)")
            val m = r.find(s) ?: return null
            return m.groupValues[2]
        }
        val name = (mStr("name") ?: mStrLoose("name"))?.trim()?.ifBlank { null }
        val vid = mInt("vendor_id") ?: mIntLoose("vendor_id") ?: mIntLoose("vid")
        val pid = mInt("product_id") ?: mIntLoose("product_id") ?: mIntLoose("pid")
        return Triple(name, vid, pid)
    }

    private fun findUsbDeviceByHint(name: String?, vendorId: Int?, productId: Int?): UsbDevice? {
        val usb = getSystemService(UsbManager::class.java)
        val list = usb.deviceList.values
        if (!name.isNullOrBlank()) {
            return list.firstOrNull { it.deviceName == name }
        }
        if (vendorId != null && productId != null && vendorId >= 0 && productId >= 0) {
            return list.firstOrNull { it.vendorId == vendorId && it.productId == productId }
        }
        return null
    }

    private fun ensureUsbPermissionAsync(device: UsbDevice, cb: (Boolean) -> Unit) {
        val usb = getSystemService(UsbManager::class.java)
        if (usb.hasPermission(device)) {
            cb(true)
            return
        }

        val action = "jp.espresso3389.kugutz.USB_PERMISSION_UI"
        val latch = CountDownLatch(1)
        val ok = AtomicReference<Boolean?>(null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != action) return
                val dev = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                }
                if (dev?.deviceName != device.deviceName) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                ok.set(granted)
                latch.countDown()
            }
        }

        try {
            val filter = IntentFilter(action)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            val pi = PendingIntent.getBroadcast(
                this,
                device.deviceName.hashCode(),
                Intent(action).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(device, pi)
        } catch (_: Exception) {
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {}
            cb(false)
            return
        }

        Thread {
            try {
                // Do not time out user interaction; just avoid leaking the receiver forever.
                // If this hits, treat as denied.
                latch.await(10, TimeUnit.MINUTES)
            } catch (_: Exception) {
            } finally {
                try {
                    unregisterReceiver(receiver)
                } catch (_: Exception) {}
            }
            val granted = ok.get() == true && usb.hasPermission(device)
            runOnUiThread { cb(granted) }
        }.start()
    }

    private fun handlePermissionPrompt(id: String, tool: String, detail: String, forceBiometric: Boolean) {
        val broker = jp.espresso3389.kugutz.perm.PermissionBroker(this)
        broker.requestConsent(tool, detail, forceBiometric) { approved ->
            if (!approved) {
                postPermissionDecision(id, approved = false)
                return@requestConsent
            }

            fun continueWithAndroidPermsAndPost() {
                val required = DevicePermissionPolicy.requiredFor(tool, detail)
                val perms = required?.androidPermissions ?: emptyList()
                if (perms.isEmpty()) {
                    postPermissionDecision(id, approved = true)
                    return
                }

                // Request Android runtime permissions. If denied, we deny the tool request as well.
                // Avoid overlapping requests: if one is already in-flight, fail closed.
                if (pendingAndroidPermRequestId.get() != null) {
                    postPermissionDecision(id, approved = false)
                    return
                }

                val missing = perms.filter { p ->
                    ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    postPermissionDecision(id, approved = true)
                    return
                }

                pendingAndroidPermRequestId.set(id)
                pendingAndroidPermAction.set { ok -> postPermissionDecision(id, approved = ok) }
                androidPermLauncher.launch(missing.toTypedArray())
            }

            // Special-case: for USB tools, complete the Android OS USB permission dialog before
            // approving the in-app tool request, so the Python agent can auto-resume reliably.
            if (tool == "device.usb") {
                val (name, vid, pid) = extractUsbHint(detail)
                val dev = findUsbDeviceByHint(name, vid, pid)
                if (dev == null) {
                    // No device hint; approve the tool request and let the tool call request USB later.
                    continueWithAndroidPermsAndPost()
                    return@requestConsent
                }
                ensureUsbPermissionAsync(dev) { granted ->
                    if (!granted) {
                        Toast.makeText(this, "USB permission denied", Toast.LENGTH_SHORT).show()
                        postPermissionDecision(id, approved = false)
                    } else {
                        continueWithAndroidPermsAndPost()
                    }
                }
                return@requestConsent
            }

            continueWithAndroidPermsAndPost()
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
