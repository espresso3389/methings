package jp.espresso3389.methings.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL

class BrainInterruptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_INTERRUPT) return
        // Best-effort: tell the local control plane to interrupt the current brain item.
        try {
            val url = URL("http://127.0.0.1:8765/brain/interrupt")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1200
                readTimeout = 2000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            runCatching { conn.inputStream.use { it.readBytes() } }
            conn.disconnect()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_INTERRUPT = "jp.espresso3389.methings.BRAIN_INTERRUPT"
    }
}

