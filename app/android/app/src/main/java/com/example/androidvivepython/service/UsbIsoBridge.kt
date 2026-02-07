package jp.espresso3389.kugutz.service

import android.util.Log

object UsbIsoBridge {
    private const val TAG = "UsbIsoBridge"
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        runCatching { System.loadLibrary("usbiso") }
            .onFailure { Log.e(TAG, "Failed to load usbiso JNI", it) }
        loaded = true
    }

    external fun isochIn(
        fd: Int,
        endpointAddress: Int,
        packetSize: Int,
        numPackets: Int,
        timeoutMs: Int,
    ): ByteArray?
}

