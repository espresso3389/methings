package jp.espresso3389.methings.service

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the methings-notify-gateway API.
 *
 * Uses [HttpURLConnection] to stay consistent with existing app patterns.
 * Stores [pull_secret] in SharedPreferences for per-device pull authentication.
 */
object NotifyGatewayClient {
    private const val TAG = "NotifyGatewayClient"
    private const val BASE_URL = "https://hooks.methings.org"
    private const val PREFS = "methings_fcm"
    private const val KEY_PULL_SECRET = "pull_secret"

    data class RegisterResult(
        val ok: Boolean,
        val pullSecret: String = "",
        val error: String = ""
    )

    data class PullEvent(
        val eventId: String,
        val source: String,
        val provider: String,
        val payload: JSONObject,
        val createdAt: Long
    )

    data class PullResult(
        val ok: Boolean,
        val items: List<PullEvent> = emptyList(),
        val error: String = ""
    )

    fun registerDevice(context: Context, deviceId: String, fcmToken: String): RegisterResult {
        return try {
            val url = URL("$BASE_URL/devices/register")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("fcm_token", fcmToken)
                put("platform", "android")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val responseText = runCatching {
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrElse {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            conn.disconnect()

            if (code in 200..299) {
                val json = JSONObject(responseText)
                val pullSecret = json.optString("pull_secret", "")
                if (pullSecret.isNotBlank()) {
                    savePullSecret(context, pullSecret)
                }
                Log.i(TAG, "Device registered: deviceId=$deviceId")
                RegisterResult(ok = true, pullSecret = pullSecret)
            } else {
                Log.w(TAG, "Register failed: code=$code body=$responseText")
                RegisterResult(ok = false, error = "http_$code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Register error", e)
            RegisterResult(ok = false, error = e.message ?: "unknown")
        }
    }

    fun pullEvents(context: Context, deviceId: String, limit: Int = 50): PullResult {
        val pullSecret = loadPullSecret(context)
        if (pullSecret.isBlank()) {
            return PullResult(ok = false, error = "no_pull_secret")
        }
        return try {
            val url = URL("$BASE_URL/events/pull")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("pull_secret", pullSecret)
                put("limit", limit)
                put("consume", true)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val responseText = runCatching {
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrElse {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            conn.disconnect()

            if (code in 200..299) {
                val json = JSONObject(responseText)
                val itemsArray = json.optJSONArray("items") ?: JSONArray()
                val items = mutableListOf<PullEvent>()
                for (i in 0 until itemsArray.length()) {
                    val item = itemsArray.getJSONObject(i)
                    items.add(
                        PullEvent(
                            eventId = item.optString("event_id", ""),
                            source = item.optString("source", ""),
                            provider = item.optString("provider", ""),
                            payload = item.optJSONObject("payload") ?: JSONObject(),
                            createdAt = item.optLong("created_at", 0)
                        )
                    )
                }
                PullResult(ok = true, items = items)
            } else {
                Log.w(TAG, "Pull failed: code=$code body=$responseText")
                PullResult(ok = false, error = "http_$code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pull error", e)
            PullResult(ok = false, error = e.message ?: "unknown")
        }
    }

    private fun savePullSecret(context: Context, secret: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PULL_SECRET, secret)
            .apply()
    }

    private fun loadPullSecret(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PULL_SECRET, "") ?: ""
    }
}
