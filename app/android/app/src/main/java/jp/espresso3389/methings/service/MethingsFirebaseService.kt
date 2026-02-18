package jp.espresso3389.methings.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import jp.espresso3389.methings.perm.InstallIdentity
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Receives FCM data messages from the notify gateway and injects events
 * into the local brain via [POST /brain/inbox/event].
 */
class MethingsFirebaseService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token refreshed")
        Thread {
            try {
                val deviceId = InstallIdentity(applicationContext).get()
                NotifyGatewayClient.registerDevice(applicationContext, deviceId, token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-register on token refresh", e)
            }
        }.start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["t"] ?: return
        if (type != "nudge") return

        Log.i(TAG, "Nudge received: src=${message.data["src"]} eid=${message.data["eid"]}")

        Thread {
            try {
                val deviceId = InstallIdentity(applicationContext).get()
                val result = NotifyGatewayClient.pullEvents(applicationContext, deviceId)
                if (!result.ok) {
                    Log.w(TAG, "Pull events failed: ${result.error}")
                    return@Thread
                }
                Log.i(TAG, "Pulled ${result.items.size} events")
                for (event in result.items) {
                    injectIntoBrain(event)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing nudge", e)
            }
        }.start()
    }

    private fun injectIntoBrain(event: NotifyGatewayClient.PullEvent) {
        try {
            val kind = event.payload.optJSONObject("normalized")?.optString("kind", "")
                ?: ""
            val eventName = if (kind.isNotBlank()) {
                "notify_gateway.$kind"
            } else {
                "notify_gateway.${event.provider}.event"
            }

            val body = JSONObject().apply {
                put("name", eventName)
                put("payload", event.payload)
                put("priority", "normal")
                put("coalesce_key", "notify_gateway.${event.eventId}")
                put("coalesce_window_ms", 5000)
            }

            val url = URL("$BRAIN_BASE_URL/brain/inbox/event")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..299) {
                Log.i(TAG, "Injected event ${event.eventId} as $eventName")
            } else {
                Log.w(TAG, "Brain inject failed: code=$code for ${event.eventId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject event ${event.eventId}", e)
        }
    }

    companion object {
        private const val TAG = "MethingsFirebaseService"
        private const val BRAIN_BASE_URL = "http://127.0.0.1:8776"
    }
}
