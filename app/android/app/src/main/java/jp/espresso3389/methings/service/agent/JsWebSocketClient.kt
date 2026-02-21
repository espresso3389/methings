package jp.espresso3389.methings.service.agent

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

/**
 * WebSocket client wrapper that bridges java-websocket callbacks to a Kotlin Channel,
 * enabling suspend-based receive() from the QuickJS async runtime.
 */
class JsWebSocketClient(private val uri: URI) {
    private val messageChannel = Channel<String>(capacity = 256)
    private var client: WebSocketClient? = null
    private val connectDeferred = CompletableDeferred<Boolean>()
    @Volatile var isOpen = false
    @Volatile var error: String? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val outer = this@JsWebSocketClient
        val ws = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                outer.isOpen = true
                connectDeferred.complete(true)
            }

            override fun onMessage(message: String?) {
                if (message != null) {
                    messageChannel.trySend(message)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                outer.isOpen = false
                messageChannel.close()
                if (!connectDeferred.isCompleted) {
                    connectDeferred.complete(false)
                }
            }

            override fun onError(ex: Exception?) {
                val msg = ex?.message ?: "websocket_error"
                outer.error = msg
                Log.w(TAG, "WebSocket error on $uri: $msg")
                if (!connectDeferred.isCompleted) {
                    connectDeferred.complete(false)
                }
            }
        }
        client = ws
        try {
            ws.connect()
        } catch (e: Exception) {
            outer.error = e.message ?: "connect_failed"
            if (!connectDeferred.isCompleted) {
                connectDeferred.complete(false)
            }
        }
        connectDeferred.await()
    }

    suspend fun receive(): String? {
        return messageChannel.receiveCatching().getOrNull()
    }

    fun send(message: String): Boolean {
        val ws = client ?: return false
        return try {
            ws.send(message)
            true
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket send failed: ${e.message}")
            false
        }
    }

    fun close(code: Int = 1000, reason: String = "") {
        try {
            client?.close(code, reason)
        } catch (_: Exception) {}
    }

    fun destroy() {
        try {
            client?.close()
        } catch (_: Exception) {}
        messageChannel.close()
    }

    companion object {
        private const val TAG = "JsWebSocketClient"
    }
}
