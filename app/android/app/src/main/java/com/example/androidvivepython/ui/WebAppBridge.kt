package jp.espresso3389.kugutz.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import jp.espresso3389.kugutz.perm.CredentialStore
import jp.espresso3389.kugutz.service.AgentService

class WebAppBridge(private val activity: MainActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private val credentialStore = CredentialStore(activity)
    private val brainPrefs = activity.getSharedPreferences("brain_config", Context.MODE_PRIVATE)

    @Volatile
    private var settingsUnlockedUntilMs: Long = 0L

    private val settingsUnlockTimeoutMs = 30_000L

    @JavascriptInterface
    fun startPythonWorker() {
        handler.post {
            val intent = Intent(activity, AgentService::class.java)
            intent.action = AgentService.ACTION_START_PYTHON
            activity.startForegroundService(intent)
        }
    }

    @JavascriptInterface
    fun restartPythonWorker() {
        handler.post {
            val intent = Intent(activity, AgentService::class.java)
            intent.action = AgentService.ACTION_RESTART_PYTHON
            activity.startForegroundService(intent)
        }
    }

    @JavascriptInterface
    fun stopPythonWorker() {
        handler.post {
            val intent = Intent(activity, AgentService::class.java)
            intent.action = AgentService.ACTION_STOP_PYTHON
            activity.startForegroundService(intent)
        }
    }

    @JavascriptInterface
    fun resetUiToDefaults() {
        handler.post {
            val extractor = jp.espresso3389.kugutz.service.AssetExtractor(activity)
            extractor.resetUiAssets()
            activity.reloadUi()
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
        return brainPrefs.getString("api_key", "")?.trim().orEmpty()
    }

    @JavascriptInterface
    fun setBrainApiKeyPlain(value: String) {
        if (getSettingsUnlockRemainingMs() <= 0) return
        brainPrefs.edit().putString("api_key", value.trim()).apply()
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
}
