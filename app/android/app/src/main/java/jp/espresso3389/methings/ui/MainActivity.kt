package jp.espresso3389.methings.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.Toast
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import jp.espresso3389.methings.AppForegroundState
import jp.espresso3389.methings.R
import jp.espresso3389.methings.device.UsbPermissionResultReceiver
import jp.espresso3389.methings.device.UsbPermissionWaiter
import jp.espresso3389.methings.service.AgentService
import jp.espresso3389.methings.service.PythonRuntimeManager
import jp.espresso3389.methings.service.LocalHttpServer
import jp.espresso3389.methings.ui.WebAppBridge
import jp.espresso3389.methings.perm.DevicePermissionPolicy
import org.kivy.android.PythonActivity
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class MainActivity : AppCompatActivity() {
    private companion object {
        const val STATE_WEBVIEW = "main_webview_state"
    }

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
    private val viewerCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocalHttpServer.ACTION_UI_VIEWER_COMMAND) return
            val cmd = intent.getStringExtra(LocalHttpServer.EXTRA_VIEWER_COMMAND) ?: return
            when (cmd) {
                "open" -> {
                    val path = intent.getStringExtra(LocalHttpServer.EXTRA_VIEWER_PATH) ?: return
                    val escaped = path.replace("\\", "\\\\").replace("'", "\\'")
                    evalJs("window.uiViewerOpen && window.uiViewerOpen('$escaped')")
                }
                "close" -> {
                    if (isImmersive) exitImmersiveMode()
                    evalJs("window.uiViewerClose && window.uiViewerClose()")
                }
                "immersive" -> {
                    val enabled = intent.getBooleanExtra(LocalHttpServer.EXTRA_VIEWER_ENABLED, true)
                    if (enabled) enterImmersiveMode() else exitImmersiveMode()
                    evalJs("window.uiViewerImmersive && window.uiViewerImmersive($enabled)")
                }
                "slideshow" -> {
                    val enabled = intent.getBooleanExtra(LocalHttpServer.EXTRA_VIEWER_ENABLED, true)
                    evalJs("window.uiViewerSlideshow && window.uiViewerSlideshow($enabled)")
                }
                "goto" -> {
                    val page = intent.getIntExtra(LocalHttpServer.EXTRA_VIEWER_PAGE, 0)
                    evalJs("window.uiViewerGoto && window.uiViewerGoto($page)")
                }
            }
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
                    "MethingsWeb",
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
                android.util.Log.d("MethingsWeb", "page finished: $url")
                view.evaluateJavascript("console.log('methings page loaded: ' + location.href)", null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url ?: return false
                if (u.scheme == "methings" && u.host == "open_user_html") {
                    val rel = (u.getQueryParameter("path") ?: "").trim().trimStart('/')
                    if (rel.isBlank() || rel.contains("..")) return true
                    try {
                        val i = Intent(this@MainActivity, AgentHtmlActivity::class.java)
                        i.putExtra(AgentHtmlActivity.EXTRA_REL_PATH, rel)
                        startActivity(i)
                    } catch (_: Exception) {}
                    return true
                }
                return false
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

        // Restore the current session (scroll/form/history/viewer state) after recreation.
        val restored = savedInstanceState?.getBundle(STATE_WEBVIEW)?.let { webView.restoreState(it) } != null
        if (!restored) {
            // Initial launch.
            webView.loadUrl("http://127.0.0.1:8765/ui/index.html")
        }

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
        AppForegroundState.isForeground = true
        // Heal possible WebView CSS/state desync after activity/window transitions.
        if (!isImmersive) {
            evalJs("window.onExitImmersiveMode && window.onExitImmersiveMode()")
        }
        // Cancel "Agent is working" and permission summary notifications when app comes to foreground.
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.cancel(82010)  // brain work notification
            nm.cancel(200201) // permission summary notification
        } catch (_: Exception) {}
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
            registerReceiver(
                viewerCommandReceiver,
                IntentFilter(LocalHttpServer.ACTION_UI_VIEWER_COMMAND),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(pythonHealthReceiver, IntentFilter(PythonRuntimeManager.ACTION_PYTHON_HEALTH))
            registerReceiver(permissionPromptReceiver, IntentFilter(LocalHttpServer.ACTION_PERMISSION_PROMPT))
            registerReceiver(uiReloadReceiver, IntentFilter(LocalHttpServer.ACTION_UI_RELOAD))
            registerReceiver(viewerCommandReceiver, IntentFilter(LocalHttpServer.ACTION_UI_VIEWER_COMMAND))
        }
    }

    override fun onStop() {
        if (isImmersive) exitImmersiveMode()
        unregisterReceiver(pythonHealthReceiver)
        unregisterReceiver(permissionPromptReceiver)
        unregisterReceiver(uiReloadReceiver)
        unregisterReceiver(viewerCommandReceiver)
        AppForegroundState.isForeground = false
        super.onStop()
    }

    override fun onDestroy() {
        pendingFilePathCallback?.onReceiveValue(null)
        pendingFilePathCallback = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val webViewState = Bundle()
        webView.saveState(webViewState)
        outState.putBundle(STATE_WEBVIEW, webViewState)
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

        val name = device.deviceName
        UsbPermissionWaiter.begin(name)
        // Prefer the trampoline activity: some Android builds auto-deny background/non-UI requests
        // or fail to show the OS prompt reliably.
        runCatching {
            startActivity(
                Intent(this, UsbPermissionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(UsbPermissionActivity.EXTRA_DEVICE_NAME, name)
                }
            )
        }.onFailure {
            // Fallback to direct requestPermission() if the activity launch fails.
            runCatching {
                val pi = PendingIntent.getBroadcast(
                    this,
                    name.hashCode(),
                    Intent(this, UsbPermissionResultReceiver::class.java).apply {
                        action = UsbPermissionResultReceiver.USB_PERMISSION_ACTION
                        setPackage(packageName)
                    },
                    // Must be mutable: the platform attaches extras (UsbDevice, granted flag).
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                usb.requestPermission(device, pi)
            }.onFailure {
                UsbPermissionWaiter.clear(name)
                cb(false)
                return
            }
        }

        Thread {
            // Bound the wait: some OEM builds never deliver the broadcast when the dialog
            // fails to appear. In that case we show help UI instead of hanging forever.
            val granted = UsbPermissionWaiter.await(name, timeoutMs = 60_000L)
            UsbPermissionWaiter.clear(name)
            val ok = granted && usb.hasPermission(device)
            runOnUiThread { cb(ok) }
        }.start()
    }

    private fun showUsbPermissionHelpDialog(device: UsbDevice) {
        AlertDialog.Builder(this)
            .setTitle("USB permission required")
            .setMessage(
                "Android denied USB access without showing the OS dialog.\n\n" +
                    "Try:\n" +
                    "1) Unplug and replug the USB device.\n" +
                    "2) Open app settings and clear any USB/default associations, then retry.\n\n" +
                    "Device: ${device.deviceName} (vid=${device.vendorId} pid=${device.productId})"
            )
            .setPositiveButton("Open app settings") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun handlePermissionPrompt(id: String, tool: String, detail: String, forceBiometric: Boolean) {
        val broker = jp.espresso3389.methings.perm.PermissionBroker(this)
        val nativeShown = broker.requestConsent(tool, detail, forceBiometric) { approved ->
            if (!approved) {
                postPermissionDecision(id, approved = false)
                return@requestConsent
            }
            handlePostConsent(id, tool, detail)
        }
        if (!nativeShown) {
            // Foreground + non-biometric: WebView perm-card handles consent; poll until resolved.
            awaitWebViewPermission(id, tool, detail)
        }
    }

    private fun awaitWebViewPermission(id: String, tool: String, detail: String) {
        Thread {
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val url = java.net.URL("http://127.0.0.1:8765/permissions/$id")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    if (body.contains("\"approved\"")) {
                        runOnUiThread { handlePostConsent(id, tool, detail) }
                        return@Thread
                    }
                    if (body.contains("\"denied\"")) {
                        return@Thread
                    }
                } catch (_: Exception) {}
                Thread.sleep(800)
            }
        }.start()
    }

    private fun handlePostConsent(id: String, tool: String, detail: String) {
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
                return
            }
            ensureUsbPermissionAsync(dev) { granted ->
                if (!granted) {
                    Toast.makeText(this, "USB permission denied by Android OS", Toast.LENGTH_SHORT).show()
                    showUsbPermissionHelpDialog(dev)
                    postPermissionDecision(id, approved = false)
                } else {
                    continueWithAndroidPermsAndPost()
                }
            }
            return
        }

        continueWithAndroidPermsAndPost()
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

    // ==============================
    // Immersive mode
    // ==============================
    private var isImmersive = false

    fun enterImmersiveMode() {
        if (isImmersive) return
        isImmersive = true
        hideSystemBars()
    }

    fun exitImmersiveMode() {
        val wasImmersive = isImmersive
        isImmersive = false
        if (wasImmersive) {
            showSystemBars()
        }
        evalJs("window.onExitImmersiveMode && window.onExitImmersiveMode()")
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { ctrl ->
                ctrl.hide(WindowInsets.Type.systemBars())
                ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

}
