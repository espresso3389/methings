package jp.espresso3389.methings.service.agent

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Executes device API actions by calling the local HTTP server directly.
 *
 * For now, uses HTTP loopback calls to LocalHttpServer (127.0.0.1:33389).
 * This avoids needing to refactor all 50+ handler methods into a callable interface.
 * The overhead is negligible since it's loopback (no TLS, no network hop).
 */
class DeviceToolBridge(
    private val identity: () -> String,
    private val port: Int = 33389,
) {
    fun execute(action: String, payload: JSONObject, detail: String): JSONObject {
        val spec = ToolDefinitions.ACTIONS[action]
        // Also allow virtual UVC PTZ actions
        val isVirtualUvc = action.startsWith("uvc.ptz.")
        if (spec == null && !isVirtualUvc) {
            return JSONObject().put("status", "error").put("error", "unknown_action").put("action", action)
        }

        val method = spec?.method ?: "POST"
        val path = spec?.path ?: "/usb/control_transfer"  // virtual UVC actions go through USB
        val timeoutMs = (ToolDefinitions.timeoutForAction(action) * 1000).toInt()

        return try {
            requestJson(method, path, payload, timeoutMs)
        } catch (ex: Exception) {
            Log.w(TAG, "DeviceToolBridge.execute failed for $action", ex)
            JSONObject().put("status", "error").put("error", ex.message ?: "request_failed")
        }
    }

    fun executeCloudRequest(requestPayload: JSONObject): JSONObject {
        return try {
            requestJson("POST", "/cloud/request", requestPayload, 120_000)
        } catch (ex: Exception) {
            JSONObject().put("status", "error").put("error", ex.message ?: "cloud_request_failed")
        }
    }

    fun executeWebSearch(query: String, maxResults: Int, provider: String, permissionId: String): JSONObject {
        val body = JSONObject().apply {
            put("query", query)
            put("max_results", maxResults)
            put("provider", provider)
            put("permission_id", permissionId)
            put("identity", identity())
        }
        return try {
            requestJson("POST", "/web/search", body, 15_000)
        } catch (ex: Exception) {
            JSONObject().put("status", "error").put("error", "search_failed").put("detail", ex.message ?: "")
        }
    }

    private fun requestJson(method: String, path: String, body: JSONObject?, timeoutMs: Int): JSONObject {
        val url = URL("http://127.0.0.1:$port$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2000
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            val id = identity()
            if (id.isNotEmpty()) {
                setRequestProperty("X-Methings-Identity", id)
            }
        }

        try {
            if (method == "POST" && body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { os ->
                    os.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: "{}"

            val parsed = try {
                JSONObject(responseBody)
            } catch (_: Exception) {
                JSONObject().put("raw", responseBody)
            }

            return if (responseCode in 200..299) {
                if (!parsed.has("status")) parsed.put("status", "ok")
                parsed.put("http_status", responseCode)
            } else {
                // Check for permission_required
                if (responseCode == 403 && parsed.optString("status") == "permission_required") {
                    parsed
                } else {
                    JSONObject()
                        .put("status", "http_error")
                        .put("http_status", responseCode)
                        .put("body", parsed)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "DeviceToolBridge"
    }
}
