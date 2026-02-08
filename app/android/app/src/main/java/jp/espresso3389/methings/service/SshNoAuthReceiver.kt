package jp.espresso3389.methings.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import java.io.File

class SshNoAuthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESPOND) return
        val reqId = intent.getStringExtra(EXTRA_ID) ?: return
        if (!isSafeId(reqId)) return
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(reqId.hashCode())
        Thread {
            writeResponse(context, reqId, allow)
        }.start()
    }

    private fun writeResponse(context: Context, reqId: String, allow: Boolean) {
        val dir = File(context.filesDir, "protected/ssh/noauth_prompts")
        dir.mkdirs()
        val respFile = File(dir, "$reqId.resp")
        try {
            respFile.writeText(if (allow) "allow" else "deny")
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun isSafeId(value: String): Boolean {
        return value.matches(Regex("^[A-Za-z0-9_-]+$"))
    }

    companion object {
        const val ACTION_RESPOND = "jp.espresso3389.methings.action.SSH_NOAUTH_RESPOND"
        const val EXTRA_ID = "request_id"
        const val EXTRA_ALLOW = "allow"
    }
}
