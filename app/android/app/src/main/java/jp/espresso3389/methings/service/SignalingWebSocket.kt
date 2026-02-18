package jp.espresso3389.methings.service

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SignalingWebSocket(
    private val signalingUrl: String,
    private val deviceId: String,
    private val token: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: (code: Int, reason: String) -> Unit = { _, _ -> },
    private val onError: (Exception) -> Unit = {},
    private val logger: (String, Throwable?) -> Unit = { _, _ -> }
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SignalingWS-scheduler").apply { isDaemon = true }
    }
    private var client: WebSocketClient? = null
    private val reconnectAttempt = AtomicInteger(0)
    private var reconnectFuture: ScheduledFuture<*>? = null
    private val active = AtomicBoolean(false)
    @Volatile var isConnected = false
        private set

    fun connect() {
        active.set(true)
        reconnectAttempt.set(0)
        createAndConnect()
    }

    fun disconnect() {
        active.set(false)
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        runCatching { client?.close() }
        client = null
        isConnected = false
    }

    fun send(msg: JSONObject): Boolean {
        val c = client ?: return false
        return runCatching {
            c.send(msg.toString())
            true
        }.getOrDefault(false)
    }

    fun reconnect() {
        runCatching { client?.close() }
        client = null
        isConnected = false
        if (active.get()) {
            reconnectAttempt.set(0)
            createAndConnect()
        }
    }

    fun shutdown() {
        disconnect()
        runCatching { scheduler.shutdownNow() }
    }

    private fun createAndConnect() {
        if (!active.get()) return
        val uri = buildUri()
        logger("SignalingWebSocket: connecting to $uri", null)
        val ws = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                logger("SignalingWebSocket: connected", null)
                isConnected = true
                reconnectAttempt.set(0)
                val registerMsg = JSONObject()
                    .put("type", "register")
                    .put("device_id", deviceId)
                    .put("token", token)
                runCatching { send(registerMsg.toString()) }
                runCatching { onConnected() }
            }

            override fun onMessage(message: String?) {
                if (message == null) return
                val json = runCatching { JSONObject(message) }.getOrElse {
                    logger("SignalingWebSocket: failed to parse message: $message", it)
                    return
                }
                runCatching { this@SignalingWebSocket.onMessage(json) }.onFailure {
                    logger("SignalingWebSocket: onMessage callback error", it)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                logger("SignalingWebSocket: closed code=$code reason=$reason remote=$remote", null)
                isConnected = false
                runCatching { onDisconnected(code, reason.orEmpty()) }
                scheduleReconnect()
            }

            override fun onError(ex: Exception?) {
                logger("SignalingWebSocket: error", ex)
                if (ex != null) runCatching { this@SignalingWebSocket.onError(ex) }
            }
        }
        ws.connectionLostTimeout = 30
        client = ws
        runCatching { ws.connect() }.onFailure {
            logger("SignalingWebSocket: connect() failed", it)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!active.get()) return
        val attempt = reconnectAttempt.getAndIncrement()
        val delayMs = backoffDelay(attempt)
        logger("SignalingWebSocket: scheduling reconnect in ${delayMs}ms (attempt $attempt)", null)
        reconnectFuture?.cancel(false)
        reconnectFuture = scheduler.schedule({
            if (active.get()) createAndConnect()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun buildUri(): URI {
        val sep = if (signalingUrl.contains('?')) '&' else '?'
        return URI("${signalingUrl}${sep}device_id=${deviceId}&token=${token}")
    }

    companion object {
        private const val BASE_DELAY_MS = 1000L
        private const val MAX_DELAY_MS = 30_000L

        private fun backoffDelay(attempt: Int): Long {
            val delay = BASE_DELAY_MS * (1L shl attempt.coerceAtMost(5))
            return delay.coerceAtMost(MAX_DELAY_MS)
        }
    }
}
