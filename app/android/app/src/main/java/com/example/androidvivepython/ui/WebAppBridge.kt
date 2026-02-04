package jp.espresso3389.kugutz.ui

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.util.Log
import jp.espresso3389.kugutz.perm.PermissionBroker
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class WebAppBridge(private val activity: Activity) {
    private val tag = "KugutzBridge"
    private val broker = PermissionBroker(activity)
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun requestNativeConsent(requestId: String, tool: String, detail: String) {
        handler.post {
            Log.d(tag, "requestNativeConsent tool=$tool id=$requestId detail=$detail")
            broker.requestConsent(tool, detail) { approved ->
                val action = if (approved) "approve" else "deny"
                val url = URL("http://127.0.0.1:8765/permissions/$requestId/$action")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                try {
                    conn.outputStream.use { it.write(ByteArray(0)) }
                    conn.inputStream.use { }
                } catch (_: Exception) {
                    // Ignore network errors; UI will retry via polling.
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    @JavascriptInterface
    fun notifyPermissionPending(summary: String) {
        handler.post {
            broker.postNotification("Permission pending", summary)
        }
    }

    @JavascriptInterface
    fun setSshEnabled(enabled: Boolean) {
        handler.post {
            val detail = if (enabled) "Enable SSHD" else "Disable SSHD"
            Log.d(tag, "setSshEnabled enabled=$enabled")
            val requestId = try {
                requestPermission("ssh", detail)
            } catch (_: Exception) {
                null
            }
            Log.d(tag, "setSshEnabled requestId=$requestId")
            if (requestId == null) {
                return@post
            }
            broker.requestConsent("ssh", detail) { approved ->
                Log.d(tag, "setSshEnabled consent approved=$approved")
                val action = if (approved) "approve" else "deny"
                try {
                    postEmpty("http://127.0.0.1:8765/permissions/$requestId/$action")
                } catch (_: Exception) {
                    return@requestConsent
                }
                if (!approved) {
                    return@requestConsent
                }
                try {
                    val payload = JSONObject()
                        .put("enabled", enabled)
                        .put("permission_id", requestId)
                    postJson("http://127.0.0.1:8765/ssh/config", payload.toString())
                    Log.d(tag, "setSshEnabled config posted")
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
    }

    @JavascriptInterface
    fun allowSshNoAuth(seconds: Int) {
        handler.post {
            val duration = if (seconds > 0) seconds else 10
            val detail = "Allow no-auth SSH login (${duration}s)"
            Log.d(tag, "allowSshNoAuth seconds=$duration")
            broker.requestConsent("ssh_noauth", detail) { approved ->
                Log.d(tag, "allowSshNoAuth consent approved=$approved")
                if (!approved) {
                    if (activity is MainActivity) {
                        activity.notifyNoAuthResult(false, null)
                    }
                    return@requestConsent
                }
                Thread {
                    try {
                        val payload = JSONObject()
                            .put("seconds", duration)
                            .put("ui_consent", true)
                        val response = postJson(
                            "http://127.0.0.1:8765/ssh/noauth/allow",
                            payload.toString()
                        )
                        val expiresAt = try {
                            JSONObject(response).optLong("expires_at", 0L)
                        } catch (_: Exception) {
                            0L
                        }
                        if (activity is MainActivity) {
                            activity.notifyNoAuthResult(true, if (expiresAt > 0) expiresAt else null)
                        }
                    } catch (ex: Exception) {
                        Log.e(tag, "allowSshNoAuth request failed", ex)
                        if (activity is MainActivity) {
                            activity.notifyNoAuthResult(false, null)
                        }
                    }
                }.start()
            }
        }
    }

    @JavascriptInterface
    fun showPythonServiceDialog() {
        handler.post {
            if (activity is MainActivity) {
                activity.showStatusDialog()
            }
        }
    }

    @JavascriptInterface
    fun restartPythonService() {
        handler.post {
            val intent = Intent(activity, jp.espresso3389.kugutz.service.AgentService::class.java)
            intent.action = jp.espresso3389.kugutz.service.AgentService.ACTION_RESTART_PYTHON
            activity.startForegroundService(intent)
        }
    }

    @JavascriptInterface
    fun stopPythonService() {
        handler.post {
            val intent = Intent(activity, jp.espresso3389.kugutz.service.AgentService::class.java)
            intent.action = jp.espresso3389.kugutz.service.AgentService.ACTION_STOP_PYTHON
            activity.startForegroundService(intent)
        }
    }

    @JavascriptInterface
    fun resetUiToDefaults() {
        handler.post {
            val extractor = jp.espresso3389.kugutz.service.AssetExtractor(activity)
            extractor.resetUiAssets()
        }
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

    private fun requestPermission(tool: String, detail: String): String? {
        val payload = JSONObject()
            .put("tool", tool)
            .put("detail", detail)
            .put("scope", "once")
        val response = postJson("http://127.0.0.1:8765/permissions/request", payload.toString())
        return try {
            val id = JSONObject(response).optString("id", "")
            Log.d(tag, "requestPermission tool=$tool id=$id")
            if (id.isBlank()) null else id
        } catch (_: Exception) {
            null
        }
    }

    private fun postEmpty(urlString: String) {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        try {
            conn.outputStream.use { it.write(ByteArray(0)) }
            conn.inputStream.use { }
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(urlString: String, body: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
