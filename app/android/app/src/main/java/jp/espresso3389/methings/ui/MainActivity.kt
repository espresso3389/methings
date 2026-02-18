package jp.espresso3389.methings.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import jp.espresso3389.methings.AppForegroundState
import jp.espresso3389.methings.R
import jp.espresso3389.methings.device.AndroidPermissionWaiter
import jp.espresso3389.methings.device.UsbPermissionResultReceiver
import jp.espresso3389.methings.device.UsbPermissionWaiter
import jp.espresso3389.methings.device.WebViewBrowserManager
import jp.espresso3389.methings.service.AgentService
import jp.espresso3389.methings.service.PythonRuntimeManager
import jp.espresso3389.methings.service.LocalHttpServer
import jp.espresso3389.methings.ui.WebAppBridge
import jp.espresso3389.methings.perm.DevicePermissionPolicy
import org.kivy.android.PythonActivity
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {
    private companion object {
        const val STATE_WEBVIEW = "main_webview_state"
        const val STATE_BROWSER_WEBVIEW = "browser_webview_state"
        const val STATE_BROWSER_VISIBLE = "browser_visible"
        const val STATE_BROWSER_FULLSCREEN = "browser_fullscreen"
        const val STATE_BROWSER_POSITION = "browser_position"
    }

    private lateinit var webView: WebView
    private lateinit var rootLayout: LinearLayout
    private lateinit var browserPanel: LinearLayout
    private lateinit var browserWebView: WebView
    private lateinit var browserTitleView: TextView
    private lateinit var browserUrlView: TextView
    private lateinit var browserDivider: View
    private lateinit var chatContainer: FrameLayout
    private lateinit var browserPosBtn: ImageButton
    private lateinit var browserFsBtn: ImageButton
    private var browserInitialized = false
    private var browserFullscreen = false
    /** Set to true when the agent explicitly loads a URL; cleared after the card is shown. */
    private var browserAgentNavigation = false
    /** "end" = browser at bottom/right (default), "start" = browser at top/left */
    private var browserPosition = "end"

    private var backCallback: OnBackPressedCallback? = null
    private var startupBanner: View? = null
    private var meSyncQrDisplayBoosted: Boolean = false
    private var meSyncPrevKeepScreenOn: Boolean = false
    @Volatile private var pendingMeSyncDeepLink: String? = null
    @Volatile private var pendingProvisionDeepLink: String? = null
    @Volatile private var mainUiLoaded: Boolean = false
    private var mainFrameError = false
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
    private val meSyncQrScanLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val txt = (result.contents ?: "").trim()
            if (txt.isBlank()) {
                evalJs("window.onMeSyncQrScanResult && window.onMeSyncQrScanResult({ok:false,cancelled:true})")
                return@registerForActivityResult
            }
            val escaped = jsString(txt)
            evalJs("window.onMeSyncQrScanResult && window.onMeSyncQrScanResult({ok:true,text:'$escaped'})")
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
            val toastText = intent.getStringExtra(LocalHttpServer.EXTRA_UI_RELOAD_TOAST)
            reloadUi(showToast = !toastText.isNullOrBlank(), toastText = toastText ?: "")
        }
    }
    private val uiChatCacheClearReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocalHttpServer.ACTION_UI_CHAT_CACHE_CLEAR) return
            val preserve = intent.getStringExtra(LocalHttpServer.EXTRA_CHAT_PRESERVE_SESSION_ID) ?: ""
            val escaped = jsString(preserve)
            evalJs("window.uiClearChatCache && window.uiClearChatCache({ preserve_session_id: '$escaped', quiet: true })")
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
    private val settingsNavigateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocalHttpServer.ACTION_UI_SETTINGS_NAVIGATE) return
            val sectionId = intent.getStringExtra(LocalHttpServer.EXTRA_SETTINGS_SECTION_ID) ?: return
            val escaped = sectionId.replace("\\", "\\\\").replace("'", "\\'")
            evalJs("window.uiOpenSettingsSection && window.uiOpenSettingsSection('$escaped')")
        }
    }
    private val meSyncExportShowReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocalHttpServer.ACTION_UI_ME_SYNC_EXPORT_SHOW) return
            evalJs("window.uiShowMeSyncExport && window.uiShowMeSyncExport()")
        }
    }
    private val browserShowReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WebViewBrowserManager.ACTION_BROWSER_SHOW) return
            val url = intent.getStringExtra(WebViewBrowserManager.EXTRA_URL)
            val fullscreen = intent.getBooleanExtra(WebViewBrowserManager.EXTRA_FULLSCREEN, false)
            val position = intent.getStringExtra(WebViewBrowserManager.EXTRA_POSITION)
            // Agent-initiated navigation → show a URL card on the chat timeline
            if (!url.isNullOrBlank()) browserAgentNavigation = true
            showBrowserPanel(url, fullscreen, position)
        }
    }
    private val browserCloseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WebViewBrowserManager.ACTION_BROWSER_CLOSE) return
            hideBrowserPanel()
        }
    }
    private val androidPermRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != LocalHttpServer.ACTION_ANDROID_PERM_REQUEST) return
            val requestId = intent.getStringExtra(LocalHttpServer.EXTRA_ANDROID_PERM_REQUEST_ID) ?: return
            val permNames = intent.getStringArrayExtra(LocalHttpServer.EXTRA_ANDROID_PERM_NAMES) ?: return
            if (permNames.isEmpty()) return

            val allPerms = permNames.toList()

            // Determine which are still missing
            val missing = allPerms.filter { p ->
                ActivityCompat.checkSelfPermission(this@MainActivity, p) != PackageManager.PERMISSION_GRANTED
            }

            if (missing.isEmpty()) {
                // All already granted — complete immediately
                val results = allPerms.associateWith { true }
                AndroidPermissionWaiter.complete(requestId, results)
                return
            }

            // If another permission request is already in-flight, complete with current status
            if (pendingAndroidPermAction.get() != null) {
                val results = allPerms.associateWith { p ->
                    ActivityCompat.checkSelfPermission(this@MainActivity, p) == PackageManager.PERMISSION_GRANTED
                }
                AndroidPermissionWaiter.complete(requestId, results)
                return
            }

            pendingAndroidPermRequestId.set(requestId)
            pendingAndroidPermAction.set { _ ->
                // Re-check all requested permissions after the dialog returns
                val results = allPerms.associateWith { p ->
                    ActivityCompat.checkSelfPermission(this@MainActivity, p) == PackageManager.PERMISSION_GRANTED
                }
                AndroidPermissionWaiter.complete(requestId, results)
            }
            androidPermLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Compatibility shim: provide a non-Kivy Activity handle for p4a code paths
        // that look up `org.kivy.android.PythonActivity.mActivity`.
        PythonActivity.mActivity = this

        startForegroundService(Intent(this, AgentService::class.java))
        ensureStartupPermissions()

        val dp = resources.displayMetrics.density
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        // Root: LinearLayout that can split chat + browser
        rootLayout = LinearLayout(this).apply {
            orientation = if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0E0E10.toInt())
        }

        // Chat container (FrameLayout wrapping the chat WebView + startup banner)
        chatContainer = FrameLayout(this)
        webView = WebView(this)
        webView.setBackgroundColor(0xFF0E0E10.toInt())
        chatContainer.addView(webView)

        // Startup banner
        val banner = FrameLayout(this).apply {
            setBackgroundColor(0xFF0E0E10.toInt())
            val label = TextView(this@MainActivity).apply {
                text = "me.things"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            addView(label, lp)
        }
        startupBanner = banner
        chatContainer.addView(banner)

        rootLayout.addView(chatContainer, LinearLayout.LayoutParams(0, 0, 1f).apply {
            width = if (isPortrait) ViewGroup.LayoutParams.MATCH_PARENT else 0
            height = if (isPortrait) 0 else ViewGroup.LayoutParams.MATCH_PARENT
        })

        // Divider between chat and browser
        browserDivider = View(this).apply {
            setBackgroundColor(0xFF2E2E36.toInt())
            visibility = View.GONE
        }
        rootLayout.addView(browserDivider, LinearLayout.LayoutParams(
            if (isPortrait) ViewGroup.LayoutParams.MATCH_PARENT else (1 * dp).toInt(),
            if (isPortrait) (1 * dp).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Browser panel (starts GONE)
        browserPanel = buildBrowserPanel(dp)
        browserPanel.visibility = View.GONE
        rootLayout.addView(browserPanel, LinearLayout.LayoutParams(0, 0, 1f).apply {
            width = if (isPortrait) ViewGroup.LayoutParams.MATCH_PARENT else 0
            height = if (isPortrait) 0 else ViewGroup.LayoutParams.MATCH_PARENT
        })

        setContentView(rootLayout)

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
                if (url.contains("/ui/index.html")) {
                    mainUiLoaded = true
                    flushPendingMeSyncDeepLink()
                    flushPendingProvisionDeepLink()
                }
                if (!mainFrameError) {
                    dismissStartupBanner()
                }
                mainFrameError = false
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url ?: return false
                val raw = u.toString()
                if (raw.startsWith("me.things:me.sync:", ignoreCase = true)) {
                    pendingMeSyncDeepLink = raw
                    flushPendingMeSyncDeepLink()
                    return true
                }
                if (raw.startsWith("me.things://provision", ignoreCase = true)) {
                    pendingProvisionDeepLink = raw
                    flushPendingProvisionDeepLink()
                    return true
                }
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
                val s = u.scheme ?: ""
                if (s == "http" || s == "https") {
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
                                    .launchUrl(this@MainActivity, u)
                            }
                        } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    mainFrameError = true
                    view.postDelayed({ view.reload() }, 1200)
                }
            }
        }
        webView.addJavascriptInterface(WebAppBridge(this), "AndroidBridge")

        // Restore the current session (scroll/form/history/viewer state) after recreation.
        val restored = savedInstanceState?.getBundle(STATE_WEBVIEW)?.let { webView.restoreState(it) } != null
        if (!restored) {
            // Initial launch.
            webView.loadUrl("http://127.0.0.1:33389/ui/index.html")
        }

        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handlePredictableBack()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback!!)

        maybeHandleDeepLinkIntent(intent)

        // Restore browser panel visibility if recreated (e.g. after process death)
        if (savedInstanceState?.getBoolean(STATE_BROWSER_VISIBLE, false) == true) {
            val wasFullscreen = savedInstanceState.getBoolean(STATE_BROWSER_FULLSCREEN, false)
            val savedPosition = savedInstanceState.getString(STATE_BROWSER_POSITION)
            showBrowserPanel(null, fullscreen = wasFullscreen, position = savedPosition)
            savedInstanceState.getBundle(STATE_BROWSER_WEBVIEW)?.let { browserWebView.restoreState(it) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val portrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        rootLayout.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        relayoutSplit(portrait)
        updateToolbarIcons()
    }

    /** Recompute layout params for chat / divider / browser based on orientation. */
    private fun relayoutSplit(portrait: Boolean = isPortrait()) {
        val dp = resources.displayMetrics.density
        chatContainer.layoutParams = LinearLayout.LayoutParams(
            if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else 0,
            if (portrait) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            1f
        )
        browserDivider.layoutParams = LinearLayout.LayoutParams(
            if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else (1 * dp).toInt(),
            if (portrait) (1 * dp).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        )
        browserPanel.layoutParams = LinearLayout.LayoutParams(
            if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else 0,
            if (portrait) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
            1f
        )
    }

    private fun buildBrowserPanel(dp: Float): LinearLayout {
        // Title
        browserTitleView = TextView(this).apply {
            text = ""
            setTextColor(0xFFECECF1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        // URL
        browserUrlView = TextView(this).apply {
            text = ""
            setTextColor(0xFF71717A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        // Title column
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleColumn.addView(browserTitleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        titleColumn.addView(browserUrlView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val iconSize = (14 * dp).toInt()
        val btnSize = (34 * dp).toInt()
        val btnMargin = (4 * dp).toInt()
        fun toolbarButton(desc: String, drawable: Drawable, onClick: () -> Unit) = ImageButton(this).apply {
            contentDescription = desc
            setImageDrawable(drawable)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF24242A.toInt())
                cornerRadius = 8 * dp
            }
            setOnClickListener { onClick() }
        }

        // Position toggle button (swap arrows — vertical in portrait, horizontal in landscape)
        browserPosBtn = toolbarButton("Swap position", SwapDrawable(iconSize, 0xFFA1A1AA.toInt(), vertical = isPortrait())) {
            val newPos = if (browserPosition == "end") "start" else "end"
            setBrowserPosition(newPos)
        }

        // Fullscreen toggle button (expand ↔ split)
        browserFsBtn = toolbarButton("Fullscreen", ExpandDrawable(iconSize, 0xFFA1A1AA.toInt())) {
            setBrowserFullscreen(!browserFullscreen)
        }

        // Open in browser button
        val openInBrowserBtn = toolbarButton("Open in browser", ExternalLinkDrawable(iconSize, 0xFFA1A1AA.toInt())) {
            val url = WebViewBrowserManager.currentUrl
            if (url.isNotBlank()) openUrlInBrowser(url)
        }

        // Close button
        val closeBtn = toolbarButton("Close", XDrawable(iconSize, 0xFFA1A1AA.toInt())) {
            hideBrowserPanel()
        }

        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF18181B.toInt())
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (8 * dp).toInt()
            setPadding(hPad, 0, hPad, 0)
        }
        val groupGap = (12 * dp).toInt()
        toolbar.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Group 1: open in browser
        toolbar.addView(openInBrowserBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { marginStart = btnMargin })
        // Group 2: layout controls
        toolbar.addView(browserPosBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { marginStart = groupGap })
        toolbar.addView(browserFsBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { marginStart = btnMargin })
        // Group 3: close
        toolbar.addView(closeBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { marginStart = groupGap })

        // Toolbar divider
        val toolbarDivider = View(this).apply {
            setBackgroundColor(0xFF2E2E36.toInt())
        }

        // Browser WebView
        WebView.enableSlowWholeDocumentDraw()
        browserWebView = WebView(this).apply {
            setBackgroundColor(0xFF0E0E10.toInt())
        }

        // Assemble panel
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E0E10.toInt())
        }
        val toolbarHeight = (44 * dp).toInt()
        panel.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, toolbarHeight))
        panel.addView(toolbarDivider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt()))
        panel.addView(browserWebView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return panel
    }

    private fun initBrowserWebView() {
        if (browserInitialized) return
        browserInitialized = true

        browserWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        browserWebView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                WebViewBrowserManager.isLoading = true
                WebViewBrowserManager.currentUrl = url ?: ""
                browserUrlView.text = url ?: ""
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val pageUrl = url ?: ""
                val pageTitle = view?.title ?: ""
                WebViewBrowserManager.notifyPageLoaded(pageUrl, pageTitle)
                // Show a clickable card in the chat timeline only for agent-initiated opens
                if (browserAgentNavigation && pageUrl.isNotBlank()) {
                    browserAgentNavigation = false
                    val safeUrl = pageUrl.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")
                    val safeTitle = pageTitle.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")
                    evalJs("window.uiBrowserNavigated && window.uiBrowserNavigated('$safeUrl','$safeTitle')")
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        error?.description?.toString() ?: "unknown_error"
                    } else {
                        "unknown_error"
                    }
                    WebViewBrowserManager.notifyPageError(desc)
                }
            }
        }

        browserWebView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                browserTitleView.text = title ?: ""
                WebViewBrowserManager.currentTitle = title ?: ""
            }
        }
    }

    fun showBrowserPanel(url: String?, fullscreen: Boolean = false, position: String? = null) {
        initBrowserWebView()
        WebViewBrowserManager.webView = browserWebView
        val wasVisible = browserPanel.visibility == View.VISIBLE
        browserPanel.visibility = View.VISIBLE
        // On first show with no explicit position, default to "start" (top) in portrait
        if (position != null) {
            setBrowserPosition(position)
        } else if (!wasVisible && isPortrait() && browserPosition == "end") {
            setBrowserPosition("start")
        }
        setBrowserFullscreen(fullscreen)
        if (!url.isNullOrBlank()) {
            browserUrlView.text = url
            browserWebView.loadUrl(url)
        }
    }

    fun hideBrowserPanel() {
        browserFullscreen = false
        chatContainer.visibility = View.VISIBLE
        browserPanel.visibility = View.GONE
        browserDivider.visibility = View.GONE
        WebViewBrowserManager.webView = null
        WebViewBrowserManager.currentUrl = ""
        WebViewBrowserManager.currentTitle = ""
        WebViewBrowserManager.isLoading = false
        browserTitleView.text = ""
        browserUrlView.text = ""
    }

    /** Open a URL in external or in-app browser per user preference. */
    fun openUrlInBrowser(url: String) {
        val uri = Uri.parse(url)
        try {
            val useExternal = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                .getBoolean("open_links_external", false)
            if (useExternal) {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } else {
                val params = CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(0xFF0e0e10.toInt())
                    .build()
                CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(params)
                    .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                    .build()
                    .launchUrl(this, uri)
            }
        } catch (_: Exception) {}
    }

    /**
     * Open a URL in a browser as a separate task so it doesn't remain in the
     * app's back stack. Used for OAuth flows where the callback deep-links back.
     */
    fun openUrlInBrowserNewTask(url: String) {
        val uri = Uri.parse(url)
        try {
            val useExternal = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                .getBoolean("open_links_external", false)
            if (useExternal) {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val cti = CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(
                        CustomTabColorSchemeParams.Builder()
                            .setToolbarColor(0xFF0e0e10.toInt())
                            .build()
                    )
                    .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                    .build()
                cti.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                cti.launchUrl(this, uri)
            }
        } catch (_: Exception) {}
    }

    fun setBrowserFullscreen(fullscreen: Boolean) {
        browserFullscreen = fullscreen
        if (fullscreen) {
            chatContainer.visibility = View.GONE
            browserDivider.visibility = View.GONE
        } else {
            chatContainer.visibility = View.VISIBLE
            browserDivider.visibility = if (browserPanel.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        }
        updateToolbarIcons()
    }

    fun setBrowserPosition(position: String) {
        val pos = if (position == "start") "start" else "end"
        if (pos == browserPosition) return
        browserPosition = pos
        // Reorder children: remove all, re-add in the right order
        rootLayout.removeAllViews()
        if (pos == "start") {
            rootLayout.addView(browserPanel)
            rootLayout.addView(browserDivider)
            rootLayout.addView(chatContainer)
        } else {
            rootLayout.addView(chatContainer)
            rootLayout.addView(browserDivider)
            rootLayout.addView(browserPanel)
        }
        relayoutSplit()
        updateToolbarIcons()
    }

    fun isBrowserVisible(): Boolean = browserPanel.visibility == View.VISIBLE
    fun isBrowserFullscreen(): Boolean = browserFullscreen

    private fun isPortrait(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private fun updateToolbarIcons() {
        val dp = resources.displayMetrics.density
        val iconSize = (14 * dp).toInt()
        val iconColor = 0xFFA1A1AA.toInt()
        // Swap: vertical arrows in portrait, horizontal in landscape
        browserPosBtn.setImageDrawable(SwapDrawable(iconSize, iconColor, vertical = isPortrait()))
        // Fullscreen: expand icon when split, split-view icon when fullscreen
        if (browserFullscreen) {
            browserFsBtn.setImageDrawable(SplitDrawable(iconSize, iconColor, vertical = !isPortrait()))
        } else {
            browserFsBtn.setImageDrawable(ExpandDrawable(iconSize, iconColor))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandlePermissionIntent(intent)
        maybeHandleDeepLinkIntent(intent)
    }

    private fun maybeHandleDeepLinkIntent(intent: Intent?) {
        val data = intent?.dataString?.trim().orEmpty()
        if (data.startsWith("me.things:me.sync:", ignoreCase = true)) {
            pendingMeSyncDeepLink = data
            flushPendingMeSyncDeepLink()
            clearDeepLinkIntent(intent)
        } else if (data.startsWith("me.things://provision", ignoreCase = true)) {
            pendingProvisionDeepLink = data
            flushPendingProvisionDeepLink()
            clearDeepLinkIntent(intent)
        }
    }

    /** Replace the activity's intent so a stale deep-link is not replayed on recreation. */
    private fun clearDeepLinkIntent(intent: Intent?) {
        try { intent?.data = null } catch (_: Exception) {}
        setIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
    }

    private fun flushPendingMeSyncDeepLink() {
        val deep = pendingMeSyncDeepLink ?: return
        if (!mainUiLoaded || !::webView.isInitialized) return
        pendingMeSyncDeepLink = null
        val escaped = jsString(deep)
        evalJs("window.uiHandleMeSyncDeepLink && window.uiHandleMeSyncDeepLink('$escaped')")
    }

    private fun flushPendingProvisionDeepLink() {
        val deep = pendingProvisionDeepLink ?: return
        if (!mainUiLoaded || !::webView.isInitialized) return
        pendingProvisionDeepLink = null
        // Parse query params from me.things://provision?token=...&status=...
        val uri = android.net.Uri.parse(deep)
        val token = uri.getQueryParameter("token").orEmpty().trim()
        val status = uri.getQueryParameter("status").orEmpty().trim()
        val error = uri.getQueryParameter("error").orEmpty().trim()
        if (status == "ok" && token.isNotBlank()) {
            // Claim the provision token via local HTTP API on a background thread
            Thread {
                try {
                    val url = java.net.URL("http://127.0.0.1:33389/me/me/provision/claim")
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10_000
                        readTimeout = 15_000
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true
                    }
                    val body = org.json.JSONObject()
                        .put("provision_token", token)
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = conn.responseCode
                    val respText = runCatching {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    }.getOrElse {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    }
                    conn.disconnect()
                    val result = runCatching { org.json.JSONObject(respText) }.getOrDefault(org.json.JSONObject())
                    val ok = code in 200..299 && result.optString("status", "") == "ok"
                    runOnUiThread {
                        val escaped = jsString(result.toString())
                        evalJs("window.onProvisionResult && window.onProvisionResult($ok, '$escaped')")
                    }
                } catch (ex: Exception) {
                    runOnUiThread {
                        val errMsg = jsString(ex.message ?: "claim_failed")
                        evalJs("window.onProvisionResult && window.onProvisionResult(false, '{\"error\":\"$errMsg\"}')")
                    }
                }
            }.start()
        } else {
            val errEscaped = jsString(error.ifBlank { "provision_failed" })
            evalJs("window.onProvisionResult && window.onProvisionResult(false, '{\"error\":\"$errEscaped\"}')")
        }
    }

    private fun jsString(raw: String): String {
        return raw
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    fun startMeSyncQrScan() {
        val opts = ScanOptions()
        opts.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        opts.setPrompt("Scan me.sync QR")
        opts.setBeepEnabled(false)
        opts.setOrientationLocked(false)
        opts.setCaptureActivity(MeSyncQrScanActivity::class.java)
        opts.setBarcodeImageEnabled(false)
        opts.setCameraId(0)
        meSyncQrScanLauncher.launch(opts)
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

    private fun handlePredictableBack() {
        // If browser panel is visible, hide it on back press.
        if (isBrowserVisible()) {
            hideBrowserPanel()
            return
        }
        if (!::webView.isInitialized) {
            finishFromBack()
            return
        }
        webView.evaluateJavascript("window.uiHandleBackGesture && window.uiHandleBackGesture()") { raw ->
            val handled = (raw ?: "null").trim().equals("true", ignoreCase = true)
            if (handled) return@evaluateJavascript

            val currentUrl = (webView.url ?: "").trim()
            val isUiPage = currentUrl.contains("/ui/index.html")
            if (!isUiPage && webView.canGoBack()) {
                webView.goBack()
                return@evaluateJavascript
            }
            finishFromBack()
        }
    }

    private fun finishFromBack() {
        val cb = backCallback
        if (cb != null) {
            cb.isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            cb.isEnabled = true
            return
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        AppForegroundState.isForeground = true
        // Process permission intent now that isForeground is true.
        // This must run after isForeground=true so PermissionBroker routes foreground
        // requests to the WebView perm-card instead of a native AlertDialog.
        maybeHandlePermissionIntent(intent)
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
                uiChatCacheClearReceiver,
                IntentFilter(LocalHttpServer.ACTION_UI_CHAT_CACHE_CLEAR),
                Context.RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                viewerCommandReceiver,
                IntentFilter(LocalHttpServer.ACTION_UI_VIEWER_COMMAND),
                Context.RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                settingsNavigateReceiver,
                IntentFilter(LocalHttpServer.ACTION_UI_SETTINGS_NAVIGATE),
                Context.RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                meSyncExportShowReceiver,
                IntentFilter(LocalHttpServer.ACTION_UI_ME_SYNC_EXPORT_SHOW),
                Context.RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                browserShowReceiver,
                IntentFilter(WebViewBrowserManager.ACTION_BROWSER_SHOW),
                Context.RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                browserCloseReceiver,
                IntentFilter(WebViewBrowserManager.ACTION_BROWSER_CLOSE),
                Context.RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                androidPermRequestReceiver,
                IntentFilter(LocalHttpServer.ACTION_ANDROID_PERM_REQUEST),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(pythonHealthReceiver, IntentFilter(PythonRuntimeManager.ACTION_PYTHON_HEALTH))
            registerReceiver(permissionPromptReceiver, IntentFilter(LocalHttpServer.ACTION_PERMISSION_PROMPT))
            registerReceiver(uiReloadReceiver, IntentFilter(LocalHttpServer.ACTION_UI_RELOAD))
            registerReceiver(uiChatCacheClearReceiver, IntentFilter(LocalHttpServer.ACTION_UI_CHAT_CACHE_CLEAR))
            registerReceiver(viewerCommandReceiver, IntentFilter(LocalHttpServer.ACTION_UI_VIEWER_COMMAND))
            registerReceiver(settingsNavigateReceiver, IntentFilter(LocalHttpServer.ACTION_UI_SETTINGS_NAVIGATE))
            registerReceiver(meSyncExportShowReceiver, IntentFilter(LocalHttpServer.ACTION_UI_ME_SYNC_EXPORT_SHOW))
            registerReceiver(browserShowReceiver, IntentFilter(WebViewBrowserManager.ACTION_BROWSER_SHOW))
            registerReceiver(browserCloseReceiver, IntentFilter(WebViewBrowserManager.ACTION_BROWSER_CLOSE))
            registerReceiver(androidPermRequestReceiver, IntentFilter(LocalHttpServer.ACTION_ANDROID_PERM_REQUEST))
        }
    }

    override fun onStop() {
        if (isImmersive) exitImmersiveMode()
        unregisterReceiver(pythonHealthReceiver)
        unregisterReceiver(permissionPromptReceiver)
        unregisterReceiver(uiReloadReceiver)
        unregisterReceiver(uiChatCacheClearReceiver)
        unregisterReceiver(viewerCommandReceiver)
        unregisterReceiver(settingsNavigateReceiver)
        unregisterReceiver(meSyncExportShowReceiver)
        unregisterReceiver(browserShowReceiver)
        unregisterReceiver(browserCloseReceiver)
        unregisterReceiver(androidPermRequestReceiver)
        AppForegroundState.isForeground = false
        super.onStop()
    }

    override fun onDestroy() {
        if (WebViewBrowserManager.webView === browserWebView) {
            WebViewBrowserManager.webView = null
        }
        setMeSyncQrDisplayMode(false)
        pendingFilePathCallback?.onReceiveValue(null)
        pendingFilePathCallback = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val webViewState = Bundle()
        webView.saveState(webViewState)
        outState.putBundle(STATE_WEBVIEW, webViewState)
        outState.putBoolean(STATE_BROWSER_VISIBLE, isBrowserVisible())
        outState.putBoolean(STATE_BROWSER_FULLSCREEN, browserFullscreen)
        outState.putString(STATE_BROWSER_POSITION, browserPosition)
        if (browserInitialized) {
            val browserState = Bundle()
            browserWebView.saveState(browserState)
            outState.putBundle(STATE_BROWSER_WEBVIEW, browserState)
        }
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

    fun reloadUi(showToast: Boolean = true, toastText: String = "UI reset applied") {
        val url = "http://127.0.0.1:33389/ui/index.html?ts=${System.currentTimeMillis()}"
        webView.post { webView.loadUrl(url) }
        if (showToast) {
            Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
        }
    }

    fun setMeSyncQrDisplayMode(enabled: Boolean) {
        runOnUiThread {
            val win = window ?: return@runOnUiThread
            if (enabled) {
                if (!meSyncQrDisplayBoosted) {
                    meSyncPrevKeepScreenOn = win.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                    meSyncQrDisplayBoosted = true
                }
                win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                if (!meSyncQrDisplayBoosted) return@runOnUiThread
                if (!meSyncPrevKeepScreenOn) {
                    win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                meSyncQrDisplayBoosted = false
            }
        }
    }

    private fun dismissStartupBanner() {
        val banner = startupBanner ?: return
        startupBanner = null
        banner.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction { (banner.parent as? FrameLayout)?.removeView(banner) }
            .start()
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
                    val url = java.net.URL("http://127.0.0.1:33389/permissions/$id")
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
                val url = java.net.URL("http://127.0.0.1:33389/permissions/$id/$action")
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

    private fun ensureStartupPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        // BLE/nearby permissions are now requested via the me.me onboarding popup in the UI.
        if (needed.isEmpty()) return
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
    }

    fun hasNearbyPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
    }

    fun requestNearbyPermissions(callback: (Boolean) -> Unit) {
        if (hasNearbyPermissions()) {
            callback(true)
            return
        }
        if (pendingAndroidPermAction.get() != null) {
            callback(false)
            return
        }
        val perms = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        val missing = perms.filter { p ->
            ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            callback(true)
            return
        }
        pendingAndroidPermAction.set(callback)
        androidPermLauncher.launch(missing.toTypedArray())
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

    /** Draws an X (close icon). */
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

    /** Draws two opposing arrows. vertical=true → ↕, vertical=false → ⇄. */
    private class SwapDrawable(
        private val size: Int, private val color: Int, private val vertical: Boolean
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@SwapDrawable.color
            style = Paint.Style.STROKE
            strokeWidth = size / 7f
            strokeCap = Paint.Cap.ROUND
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size

        override fun draw(canvas: Canvas) {
            val b = bounds
            val inset = size * 0.20f
            val arrow = size * 0.18f
            val gap = size * 0.18f
            if (vertical) {
                val cx = (b.left + b.right) / 2f
                val t = b.top + inset
                val bo = b.bottom - inset
                // Left arrow pointing up
                val x1 = cx - gap
                canvas.drawLine(x1, bo, x1, t, paint)
                canvas.drawLine(x1 - arrow, t + arrow, x1, t, paint)
                canvas.drawLine(x1 + arrow, t + arrow, x1, t, paint)
                // Right arrow pointing down
                val x2 = cx + gap
                canvas.drawLine(x2, t, x2, bo, paint)
                canvas.drawLine(x2 - arrow, bo - arrow, x2, bo, paint)
                canvas.drawLine(x2 + arrow, bo - arrow, x2, bo, paint)
            } else {
                val l = b.left + inset
                val r = b.right - inset
                val cy = (b.top + b.bottom) / 2f
                // Top arrow pointing right
                val y1 = cy - gap
                canvas.drawLine(l, y1, r, y1, paint)
                canvas.drawLine(r - arrow, y1 - arrow, r, y1, paint)
                canvas.drawLine(r - arrow, y1 + arrow, r, y1, paint)
                // Bottom arrow pointing left
                val y2 = cy + gap
                canvas.drawLine(r, y2, l, y2, paint)
                canvas.drawLine(l + arrow, y2 - arrow, l, y2, paint)
                canvas.drawLine(l + arrow, y2 + arrow, l, y2, paint)
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Draws four outward-pointing corner arrows (expand/fullscreen icon). */
    private class ExpandDrawable(private val size: Int, private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@ExpandDrawable.color
            style = Paint.Style.STROKE
            strokeWidth = size / 7f
            strokeCap = Paint.Cap.ROUND
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size

        override fun draw(canvas: Canvas) {
            val b = bounds
            val inset = size * 0.15f
            val l = b.left + inset
            val t = b.top + inset
            val r = b.right - inset
            val bo = b.bottom - inset
            val arm = size * 0.28f
            // Top-left corner
            canvas.drawLine(l, t + arm, l, t, paint)
            canvas.drawLine(l, t, l + arm, t, paint)
            // Top-right corner
            canvas.drawLine(r - arm, t, r, t, paint)
            canvas.drawLine(r, t, r, t + arm, paint)
            // Bottom-right corner
            canvas.drawLine(r, bo - arm, r, bo, paint)
            canvas.drawLine(r, bo, r - arm, bo, paint)
            // Bottom-left corner
            canvas.drawLine(l + arm, bo, l, bo, paint)
            canvas.drawLine(l, bo, l, bo - arm, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * Draws a split-view icon: two rectangles with a divider.
     * vertical=true → vertical divider (left|right), vertical=false → horizontal divider (top/bottom).
     */
    private class SplitDrawable(
        private val size: Int, private val color: Int, private val vertical: Boolean
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@SplitDrawable.color
            style = Paint.Style.STROKE
            strokeWidth = size / 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size

        override fun draw(canvas: Canvas) {
            val b = bounds
            val inset = size * 0.12f
            val l = b.left + inset
            val t = b.top + inset
            val r = b.right - inset
            val bo = b.bottom - inset
            val radius = size * 0.10f
            // Outer rounded rect
            canvas.drawRoundRect(l, t, r, bo, radius, radius, paint)
            // Divider line
            if (vertical) {
                val cx = (l + r) / 2f
                canvas.drawLine(cx, t, cx, bo, paint)
            } else {
                val cy = (t + bo) / 2f
                canvas.drawLine(l, cy, r, cy, paint)
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Draws an external-link icon: box with arrow pointing top-right. */
    private class ExternalLinkDrawable(private val size: Int, private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@ExternalLinkDrawable.color
            style = Paint.Style.STROKE
            strokeWidth = size / 7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size

        override fun draw(canvas: Canvas) {
            val b = bounds
            val inset = size * 0.12f
            val l = b.left + inset
            val t = b.top + inset
            val r = b.right - inset
            val bo = b.bottom - inset
            val radius = size * 0.10f
            val mid = (l + r) / 2f
            // Open box (bottom-left portion): left side, bottom, right side up to mid, top to mid
            val path = android.graphics.Path().apply {
                moveTo(mid, t)
                lineTo(l + radius, t)
                // Top-left corner
                quadTo(l, t, l, t + radius)
                lineTo(l, bo - radius)
                // Bottom-left corner
                quadTo(l, bo, l + radius, bo)
                lineTo(r - radius, bo)
                // Bottom-right corner
                quadTo(r, bo, r, bo - radius)
                lineTo(r, mid)
            }
            canvas.drawPath(path, paint)
            // Arrow: diagonal line from center to top-right
            val ax = r
            val ay = t
            canvas.drawLine(mid, mid, ax, ay, paint)
            // Arrow head
            val arm = size * 0.22f
            canvas.drawLine(ax - arm, ay, ax, ay, paint)
            canvas.drawLine(ax, ay + arm, ax, ay, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

}
