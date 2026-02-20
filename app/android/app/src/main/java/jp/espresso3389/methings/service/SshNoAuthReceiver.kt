package jp.espresso3389.methings.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SshNoAuthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESPOND) return
        val reqId = intent.getStringExtra(EXTRA_ID) ?: return
        if (!isSafeId(reqId)) return
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        SshNoAuthManager.instance?.respond(reqId, allow)
    }

    private fun isSafeId(value: String): Boolean {
        return value.matches(Regex("^[A-Za-z0-9_:-]+$"))
    }

    companion object {
        const val ACTION_RESPOND = "jp.espresso3389.methings.action.SSH_NOAUTH_RESPOND"
        const val EXTRA_ID = "request_id"
        const val EXTRA_ALLOW = "allow"
    }
}
