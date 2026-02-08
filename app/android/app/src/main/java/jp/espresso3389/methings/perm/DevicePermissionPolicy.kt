package jp.espresso3389.methings.perm

import android.Manifest
import android.os.Build

object DevicePermissionPolicy {
    data class Required(
        val androidPermissions: List<String>,
        val userFacingLabel: String
    )

    fun requiredFor(tool: String, detail: String): Required? {
        val t = tool.trim().lowercase()
        if (!t.startsWith("device.")) {
            // Back-compat: older callers use "device_api" with the action encoded in detail.
            if (t == "device_api") {
                val d = detail.lowercase()
                return when {
                    d.contains("camera") -> Required(listOf(Manifest.permission.CAMERA), "Camera")
                    d.contains("mic") || d.contains("stt") ->
                        Required(listOf(Manifest.permission.RECORD_AUDIO), "Microphone")
                    d.contains("tts") ->
                        // TextToSpeech does not require RECORD_AUDIO. (Some engines may use network;
                        // INTERNET is already granted via manifest.)
                        Required(emptyList(), "Text-to-speech")
                    d.contains("gps") || d.contains("location") ->
                        Required(
                            listOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ),
                            "Location"
                        )
                    d.contains("ble") || d.contains("bluetooth") ->
                        Required(bluetoothPermissions(), "Bluetooth")
                    d.contains("usb") ->
                        Required(emptyList(), "USB device access")
                    else -> Required(emptyList(), "Device access")
                }
            }
            return null
        }

        return when {
            t == "device.camera2" || t.startsWith("device.camera") ->
                Required(listOf(Manifest.permission.CAMERA), "Camera")
            t == "device.mic" || t.startsWith("device.audio") || t.contains(".stt") ->
                Required(listOf(Manifest.permission.RECORD_AUDIO), "Microphone")
            t == "device.tts" || t.contains(".tts") ->
                Required(emptyList(), "Text-to-speech")
            t == "device.gps" || t.startsWith("device.location") ->
                Required(
                    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    "Location"
                )
            t.startsWith("device.ble") || t.startsWith("device.bluetooth") ->
                Required(bluetoothPermissions(), "Bluetooth")
            t.startsWith("device.usb") || t.startsWith("device.libusb") || t.startsWith("device.libuvc") ->
                // USB requires a per-device user grant via UsbManager.requestPermission (system dialog),
                // not a Manifest runtime permission. We still show our own prompt for audit/consent.
                Required(emptyList(), "USB device access")
            else -> Required(emptyList(), "Device access")
        }
    }

    private fun bluetoothPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}
