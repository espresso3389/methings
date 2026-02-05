package jp.espresso3389.kugutz.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SshNoAuthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESPOND) return
        val reqId = intent.getStringExtra(EXTRA_ID) ?: return
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(reqId.hashCode())
        Thread {
            postResponse(reqId, allow)
        }.start()
    }

    private fun postResponse(reqId: String, allow: Boolean) {
        val url = URL("http://127.0.0.1:8765/ssh/noauth/respond")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        try {
            val payload = JSONObject()
                .put("id", reqId)
                .put("allow", allow)
                .put("ui_consent", true)
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            conn.inputStream.use { }
        } catch (_: Exception) {
            // ignore
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val ACTION_RESPOND = "jp.espresso3389.kugutz.action.SSH_NOAUTH_RESPOND"
        const val EXTRA_ID = "request_id"
        const val EXTRA_ALLOW = "allow"
    }
}
