package jp.espresso3389.kugutz.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        if (data == null) {
            finish()
            return
        }

        val code = data.getQueryParameter("code") ?: ""
        val state = data.getQueryParameter("state") ?: ""
        val provider = data.getQueryParameter("provider") ?: ""

        Thread {
            try {
                val url = if (provider.isNotEmpty()) {
                    URL("http://127.0.0.1:8765/auth/$provider/callback")
                } else {
                    URL("http://127.0.0.1:8765/auth/callback")
                }
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val payload = """{"code":"$code","state":"$state"}"""
                OutputStreamWriter(conn.outputStream).use { it.write(payload) }
                conn.inputStream.use { }
                conn.disconnect()
            } catch (ex: Exception) {
                Log.e("OAuthActivity", "Callback post failed", ex)
            } finally {
                finish()
            }
        }.start()
    }
}
