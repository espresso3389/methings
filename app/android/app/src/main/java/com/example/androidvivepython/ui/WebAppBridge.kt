package jp.espresso3389.kugutz.ui

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.browser.customtabs.CustomTabsIntent
import jp.espresso3389.kugutz.perm.PermissionBroker
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class WebAppBridge(private val activity: Activity) {
    private val broker = PermissionBroker(activity)
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun requestNativeConsent(requestId: String, tool: String, detail: String) {
        handler.post {
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
    fun startOAuth(provider: String) {
        Thread {
            try {
                val url = URL("http://127.0.0.1:8765/auth/$provider/start")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.outputStream.use { it.write("{}".toByteArray()) }

                val response = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        response.append(line)
                        line = reader.readLine()
                    }
                }
                conn.disconnect()

                val json = JSONObject(response.toString())
                val authUrl = json.getString("auth_url")
                handler.post {
                    val intent = CustomTabsIntent.Builder().build()
                    intent.launchUrl(activity, Uri.parse(authUrl))
                }
            } catch (ex: Exception) {
                Log.e("WebAppBridge", "OAuth start failed", ex)
            }
        }.start()
    }
}
