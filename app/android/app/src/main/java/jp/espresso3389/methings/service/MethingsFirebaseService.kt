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
        NotifyGatewayClient.saveFcmToken(applicationContext, token)
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
        // Route me.me encrypted payloads to the relay ingest handler for
        // decryption. This source value is set by deliverMeMeRelayPayload when
        // issuing the route token.
        if (event.source == "me_me_data") {
            routeToRelayIngest(event)
            return
        }
        if (event.source == "provision") {
            routeToProvisionRefresh(event)
            return
        }
        injectToBrainInbox(event)
    }

    private fun routeToRelayIngest(event: NotifyGatewayClient.PullEvent) {
        try {
            val body = JSONObject().apply {
                put("source", event.source)
                put("event_id", event.eventId)
                put("provider", event.provider)
                put("payload", event.payload)
            }
            val url = URL("$LOCAL_SERVER_URL/me/me/relay/ingest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..299) {
                Log.i(TAG, "Routed me.me relay event ${event.eventId} to data ingest")
            } else {
                Log.w(TAG, "Relay ingest failed: code=$code for ${event.eventId}, falling back to brain inject")
                injectToBrainInbox(event)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to route me.me relay event ${event.eventId}", e)
            injectToBrainInbox(event)
        }
    }

    private fun routeToProvisionRefresh(event: NotifyGatewayClient.PullEvent) {
        try {
            val url = URL("$LOCAL_SERVER_URL/me/me/provision/refresh")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write("{}") }
            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..299) {
                Log.i(TAG, "Routed provision event ${event.eventId} to provision refresh")
            } else {
                Log.w(TAG, "Provision refresh failed: code=$code for ${event.eventId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to route provision event ${event.eventId}", e)
        }
    }

    private fun injectToBrainInbox(event: NotifyGatewayClient.PullEvent) {
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
        private const val BRAIN_BASE_URL = "http://127.0.0.1:33389"
        private const val LOCAL_SERVER_URL = "http://127.0.0.1:33389"
    }
}
