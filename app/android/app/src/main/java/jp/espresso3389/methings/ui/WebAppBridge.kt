package jp.espresso3389.methings.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import jp.espresso3389.methings.BuildConfig
import jp.espresso3389.methings.perm.CredentialStore
import jp.espresso3389.methings.service.AgentService
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest
import java.util.Locale

class WebAppBridge(private val activity: MainActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private val credentialStore = CredentialStore(activity)
    private val brainPrefs = activity.getSharedPreferences("brain_config", Context.MODE_PRIVATE)
    private val notifPrefs = activity.getSharedPreferences("task_completion_prefs", Context.MODE_PRIVATE)
    private val browserPrefs = activity.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    private val audioRecordPrefs = activity.getSharedPreferences("audio_record_config", Context.MODE_PRIVATE)
    private val videoRecordPrefs = activity.getSharedPreferences("video_record_config", Context.MODE_PRIVATE)
    private val servicePrefs = activity.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)

    @Volatile
    private var settingsUnlockedUntilMs: Long = 0L

    private val settingsUnlockTimeoutMs = 30_000L

    @JavascriptInterface
    fun startWorker() {
        handler.post {
            val intent = Intent(activity, AgentService::class.java)
            intent.action = AgentService.ACTION_START_WORKER
            activity.startForegroundService(intent)
        }
    }

    @JavascriptInterface
    fun restartWorker() {
        handler.post {
            val intent = Intent(activity, AgentService::class.java)
            intent.action = AgentService.ACTION_RESTART_WORKER
            activity.startForegroundService(intent)
        }
    }

    @JavascriptInterface
    fun stopWorker() {
        handler.post {
            val intent = Intent(activity, AgentService::class.java)
            intent.action = AgentService.ACTION_STOP_WORKER
            activity.startForegroundService(intent)
        }
    }

    @JavascriptInterface
    fun stopService() {
        handler.post {
            val intent = Intent(activity, AgentService::class.java)
            intent.action = AgentService.ACTION_STOP_SERVICE
            activity.startForegroundService(intent)
        }
    }

    @JavascriptInterface
    fun getStartOnBoot(): Boolean {
        return servicePrefs.getBoolean("start_on_boot", true)
    }

    @JavascriptInterface
    fun setStartOnBoot(enabled: Boolean) {
        servicePrefs.edit().putBoolean("start_on_boot", enabled).apply()
    }

    @JavascriptInterface
    fun resetUiToDefaults() {
        handler.post {
            val extractor = jp.espresso3389.methings.service.AssetExtractor(activity)
            extractor.resetUiAssets()
            activity.reloadUi()
        }
    }

    @JavascriptInterface
    fun shareUserFile(relPath: String, mime: String?) {
        handler.post {
            val p = relPath.trim().trimStart('/')
            if (p.isBlank() || p.contains("..")) return@post
            val root = File(activity.filesDir, "user").canonicalFile
            val file = File(root, p).canonicalFile
            if (!file.path.startsWith(root.path + File.separator) || !file.exists() || !file.isFile) return@post

            val authority = activity.packageName + ".fileprovider"
            val uri = runCatching { FileProvider.getUriForFile(activity, authority, file) }.getOrNull()
                ?: return@post
            val guessed = mime?.trim().orEmpty().ifBlank {
                URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = guessed
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share")
            activity.startActivity(chooser)
        }
    }

    @JavascriptInterface
    fun shareApp() {
        handler.post {
            val src = File(activity.applicationInfo.sourceDir)
            val shareDir = File(activity.cacheDir, "share").also { it.mkdirs() }
            val dest = File(shareDir, "methings.apk")
            src.inputStream().use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }

            val authority = activity.packageName + ".fileprovider"
            val uri = runCatching { FileProvider.getUriForFile(activity, authority, dest) }.getOrNull()
                ?: return@post

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Share methings"))
        }
    }

    @JavascriptInterface
    fun getSettingsUnlockRemainingMs(): Long {
        val rem = settingsUnlockedUntilMs - System.currentTimeMillis()
        return if (rem > 0) rem else 0L
    }

    @JavascriptInterface
    fun requestSettingsUnlock() {
        handler.post {
            val rem = getSettingsUnlockRemainingMs()
            if (rem > 0) {
                activity.evalJs("window.onSettingsUnlockResult && window.onSettingsUnlockResult({ok:true,remaining_ms:${rem}})")
                return@post
            }

            val manager = BiometricManager.from(activity)
            val canAuth = manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                activity.evalJs("window.onSettingsUnlockResult && window.onSettingsUnlockResult({ok:false,error:'biometric_unavailable'})")
                return@post
            }

            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        settingsUnlockedUntilMs = System.currentTimeMillis() + settingsUnlockTimeoutMs
                        val r = getSettingsUnlockRemainingMs()
                        activity.evalJs("window.onSettingsUnlockResult && window.onSettingsUnlockResult({ok:true,remaining_ms:${r}})")
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        activity.evalJs("window.onSettingsUnlockResult && window.onSettingsUnlockResult({ok:false,error:'auth_error'})")
                    }

                    override fun onAuthenticationFailed() {
                        // Ignore; user can retry.
                    }
                }
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock settings")
                .setSubtitle("View and edit API keys")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(promptInfo)
        }
    }

    @JavascriptInterface
    fun getBrainApiKeyPlain(): String {
        if (getSettingsUnlockRemainingMs() <= 0) return ""
        val vendor = brainPrefs.getString("vendor", "") ?: ""
        val baseUrl = brainPrefs.getString("base_url", "") ?: ""
        return getBrainApiKeyFor(vendor, baseUrl)
    }

    @JavascriptInterface
    fun setBrainApiKeyPlain(value: String) {
        if (getSettingsUnlockRemainingMs() <= 0) return
        // Back-compat: set for the currently selected vendor/base_url.
        val vendor = brainPrefs.getString("vendor", "") ?: ""
        val baseUrl = brainPrefs.getString("base_url", "") ?: ""
        setBrainApiKeyFor(vendor, baseUrl, value)
    }

    private fun sanitizeVendor(vendor: String): String {
        val v = vendor.trim().lowercase(Locale.US)
        if (v.isBlank()) return "custom"
        return v.replace(Regex("[^a-z0-9_\\-]"), "_")
    }

    private fun shortHashHex(s: String): String {
        return try {
            val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            dig.take(6).joinToString("") { b -> "%02x".format(b) } // 12 hex chars
        } catch (_: Exception) {
            // Fall back to hashCode if MessageDigest is unavailable (shouldn't happen).
            val h = s.hashCode().toUInt().toString(16)
            h.padStart(12, '0').take(12)
        }
    }

    private fun keySlotFor(vendor: String, baseUrl: String): String {
        val v = sanitizeVendor(vendor)
        val b = baseUrl.trim().trimEnd('/').lowercase(Locale.US)
        val hx = shortHashHex(v + "|" + b)
        return "api_key_for_${v}_${hx}"
    }

    @JavascriptInterface
    fun getBrainApiKeyFor(vendor: String, baseUrl: String): String {
        if (getSettingsUnlockRemainingMs() <= 0) return ""
        val slot = keySlotFor(vendor, baseUrl)
        return brainPrefs.getString(slot, "")?.trim().orEmpty()
    }

    @JavascriptInterface
    fun setBrainApiKeyFor(vendor: String, baseUrl: String, value: String) {
        if (getSettingsUnlockRemainingMs() <= 0) return
        val key = value.trim()
        val slot = keySlotFor(vendor, baseUrl)
        val e = brainPrefs.edit()
        e.putString(slot, key)
        // Also update the currently active key if this matches the active vendor/base_url.
        val curVendor = (brainPrefs.getString("vendor", "") ?: "").trim()
        val curBase = (brainPrefs.getString("base_url", "") ?: "").trim().trimEnd('/')
        if (curVendor.equals(vendor.trim(), ignoreCase = true) &&
            curBase.equals(baseUrl.trim().trimEnd('/'), ignoreCase = true)
        ) {
            e.putString("api_key", key)
        }
        e.apply()
    }

    @JavascriptInterface
    fun getBraveSearchApiKeyPlain(): String {
        if (getSettingsUnlockRemainingMs() <= 0) return ""
        return credentialStore.get("brave_search_api_key")?.value?.trim().orEmpty()
    }

    @JavascriptInterface
    fun setBraveSearchApiKeyPlain(value: String) {
        if (getSettingsUnlockRemainingMs() <= 0) return
        credentialStore.set("brave_search_api_key", value.trim())
    }

    @JavascriptInterface
    fun getWifiIp(): String {
        return try {
            val cm = activity.applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            val network = cm?.activeNetwork ?: return ""
            val caps = cm.getNetworkCapabilities(network) ?: return ""
            if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                return ""
            }
            val linkProps = cm.getLinkProperties(network) ?: return ""
            val addr = linkProps.linkAddresses
                .mapNotNull { it.address }
                .firstOrNull { it is java.net.Inet4Address }
            addr?.hostAddress ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun openAppDetailsSettings() {
        handler.post {
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                )
            }
        }
    }

    @JavascriptInterface
    fun enterImmersiveMode() {
        handler.post { activity.enterImmersiveMode() }
    }

    @JavascriptInterface
    fun exitImmersiveMode() {
        handler.post { activity.exitImmersiveMode() }
    }

    @JavascriptInterface
    fun openAppOpenByDefaultSettings() {
        handler.post {
            // Some OEM builds don't support this action; fail soft back to app details.
            val pkg = activity.packageName
            val intent = Intent("android.settings.APP_OPEN_BY_DEFAULT_SETTINGS").apply {
                data = Uri.parse("package:$pkg")
                putExtra("android.provider.extra.APP_PACKAGE", pkg)
                putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
            }
            val ok = runCatching { activity.startActivity(intent) }.isSuccess
            if (!ok) {
                openAppDetailsSettings()
            }
        }
    }

    @JavascriptInterface
    fun scanMeSyncQr() {
        handler.post {
            runCatching { activity.startMeSyncQrScan() }
                .onFailure {
                    activity.evalJs("window.onMeSyncQrScanResult && window.onMeSyncQrScanResult({ok:false,error:'scanner_unavailable'})")
                }
        }
    }

    @JavascriptInterface
    fun getTaskCompleteNotifyAndroid(): Boolean {
        return notifPrefs.getBoolean("notify_android", true)
    }

    @JavascriptInterface
    fun setTaskCompleteNotifyAndroid(enabled: Boolean) {
        notifPrefs.edit().putBoolean("notify_android", enabled).apply()
    }

    @JavascriptInterface
    fun getTaskCompleteNotifySound(): Boolean {
        return notifPrefs.getBoolean("notify_sound", false)
    }

    @JavascriptInterface
    fun setTaskCompleteNotifySound(enabled: Boolean) {
        notifPrefs.edit().putBoolean("notify_sound", enabled).apply()
    }

    @JavascriptInterface
    fun getTaskCompleteWebhookUrl(): String {
        return notifPrefs.getString("notify_webhook_url", "") ?: ""
    }

    @JavascriptInterface
    fun setTaskCompleteWebhookUrl(url: String) {
        notifPrefs.edit().putString("notify_webhook_url", url.trim()).apply()
    }

    @JavascriptInterface
    fun getOpenLinksExternal(): Boolean {
        return browserPrefs.getBoolean("open_links_external", false)
    }

    @JavascriptInterface
    fun setOpenLinksExternal(enabled: Boolean) {
        browserPrefs.edit().putBoolean("open_links_external", enabled).apply()
    }

    /** Open a URL in the embedded browser panel (no timeline card). */
    @JavascriptInterface
    fun openInWebview(url: String) {
        handler.post { activity.showBrowserPanel(url) }
    }

    /** Open current webview URL in external/in-app browser per user preference. */
    @JavascriptInterface
    fun openWebviewUrlInBrowser() {
        val url = jp.espresso3389.methings.device.WebViewBrowserManager.currentUrl
        if (url.isBlank()) return
        handler.post { activity.openUrlInBrowser(url) }
    }

    /** Open a URL in CustomTabs / external browser as a separate task (not the embedded WebView). */
    @JavascriptInterface
    fun openInBrowser(url: String) {
        if (url.isBlank()) return
        handler.post { activity.openUrlInBrowserNewTask(url) }
    }

    // ── Audio Recording Config ───────────────────────────────────────────────

    @JavascriptInterface
    fun getAudioRecordSampleRate(): Int {
        return audioRecordPrefs.getInt("sample_rate", 44100)
    }

    @JavascriptInterface
    fun setAudioRecordSampleRate(v: Int) {
        audioRecordPrefs.edit().putInt("sample_rate", v.coerceIn(8000, 48000)).apply()
    }

    @JavascriptInterface
    fun getAudioRecordChannels(): Int {
        return audioRecordPrefs.getInt("channels", 1)
    }

    @JavascriptInterface
    fun setAudioRecordChannels(v: Int) {
        audioRecordPrefs.edit().putInt("channels", v.coerceIn(1, 2)).apply()
    }

    @JavascriptInterface
    fun getAudioRecordBitrate(): Int {
        return audioRecordPrefs.getInt("bitrate", 128000)
    }

    @JavascriptInterface
    fun setAudioRecordBitrate(v: Int) {
        audioRecordPrefs.edit().putInt("bitrate", v.coerceIn(32000, 320000)).apply()
    }

    @JavascriptInterface
    fun getAudioRecordMaxDurationS(): Int {
        return audioRecordPrefs.getInt("max_duration_s", 300)
    }

    @JavascriptInterface
    fun setAudioRecordMaxDurationS(v: Int) {
        audioRecordPrefs.edit().putInt("max_duration_s", v.coerceIn(5, 3600)).apply()
    }

    // ── Video Recording Config ───────────────────────────────────────────────

    @JavascriptInterface
    fun getVideoRecordResolution(): String {
        return videoRecordPrefs.getString("resolution", "720p") ?: "720p"
    }

    @JavascriptInterface
    fun setVideoRecordResolution(v: String) {
        val r = v.trim().lowercase()
        if (r in listOf("720p", "1080p", "4k")) {
            videoRecordPrefs.edit().putString("resolution", r).apply()
        }
    }

    @JavascriptInterface
    fun getVideoRecordCodec(): String {
        return videoRecordPrefs.getString("codec", "h265") ?: "h265"
    }

    @JavascriptInterface
    fun setVideoRecordCodec(v: String) {
        val c = v.trim().lowercase()
        if (c in listOf("h265", "h264")) {
            videoRecordPrefs.edit().putString("codec", c).apply()
        }
    }

    @JavascriptInterface
    fun getVideoRecordMaxDurationS(): Int {
        return videoRecordPrefs.getInt("max_duration_s", 300)
    }

    @JavascriptInterface
    fun setVideoRecordMaxDurationS(v: Int) {
        videoRecordPrefs.edit().putInt("max_duration_s", v.coerceIn(5, 3600)).apply()
    }

    @JavascriptInterface
    fun hasNearbyPermissions(): Boolean {
        return activity.hasNearbyPermissions()
    }

    @JavascriptInterface
    fun requestNearbyPermissions() {
        handler.post {
            activity.requestNearbyPermissions { ok ->
                activity.evalJs("window.onNearbyPermissionsResult && window.onNearbyPermissionsResult($ok)")
            }
        }
    }

    @JavascriptInterface
    fun setMeSyncQrDisplayMode(enabled: Boolean) {
        handler.post {
            activity.setMeSyncQrDisplayMode(enabled)
        }
    }

    // ── Google Sign-In (Owner Identity) ─────────────────────────────────────

    @JavascriptInterface
    fun startGoogleSignIn() {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
        if (clientId.isBlank()) {
            handler.post {
                activity.evalJs("window.onGoogleSignInResult && window.onGoogleSignInResult({ok:false,error:'no_client_id'})")
            }
            return
        }
        handler.post {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(clientId)
                .setFilterByAuthorizedAccounts(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val credManager = CredentialManager.create(activity)
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val result = credManager.getCredential(activity, request)
                    val googleCred = GoogleIdTokenCredential.createFrom(result.credential.data)
                    val email = googleCred.id.trim().lowercase(Locale.US)
                    val idToken = googleCred.idToken ?: ""
                    if (email.isNotBlank()) {
                        credentialStore.set("me_me_owner:google", email)
                        if (idToken.isNotBlank()) {
                            credentialStore.set("me_me_owner:google:id_token", idToken)
                        }
                    }
                    val safeEmail = email.replace("'", "\\'")
                    activity.evalJs("window.onGoogleSignInResult && window.onGoogleSignInResult({ok:true,email:'$safeEmail'})")
                } catch (e: GetCredentialException) {
                    val msg = (e.message ?: "sign_in_failed").replace("'", "\\'")
                    activity.evalJs("window.onGoogleSignInResult && window.onGoogleSignInResult({ok:false,error:'$msg'})")
                } catch (e: Exception) {
                    val msg = (e.message ?: "unknown_error").replace("'", "\\'")
                    activity.evalJs("window.onGoogleSignInResult && window.onGoogleSignInResult({ok:false,error:'$msg'})")
                }
            }
        }
    }

    @JavascriptInterface
    fun getVerifiedOwnerIdentity(): String {
        val email = credentialStore.get("me_me_owner:google")?.value?.trim().orEmpty()
        if (email.isBlank()) return ""
        return "google:$email"
    }

    @JavascriptInterface
    fun signOutOwnerIdentity() {
        credentialStore.delete("me_me_owner:google")
        credentialStore.delete("me_me_owner:google:id_token")
        handler.post {
            activity.evalJs("window.onOwnerSignOutResult && window.onOwnerSignOutResult({ok:true})")
        }
    }
}
