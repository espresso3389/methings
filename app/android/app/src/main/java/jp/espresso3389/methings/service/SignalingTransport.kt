package jp.espresso3389.methings.service

import org.json.JSONObject

interface SignalingTransport {
    val isConnected: Boolean

    fun connect()

    fun disconnect()

    fun send(msg: JSONObject): Boolean

    fun reconnect()

    fun shutdown()
}
