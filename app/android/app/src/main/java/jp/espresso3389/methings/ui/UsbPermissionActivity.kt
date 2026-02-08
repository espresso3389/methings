package jp.espresso3389.methings.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import jp.espresso3389.methings.device.UsbPermissionResultReceiver

/**
 * Trampoline activity to request Android's OS-level USB permission prompt from a UI context.
 *
 * Some devices/Android versions auto-deny UsbManager.requestPermission() calls made from
 * background/service threads. This activity ensures the request is initiated from an Activity.
 */
class UsbPermissionActivity : Activity() {
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceName = (intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "").trim()
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val device: UsbDevice? = usb.deviceList.values.firstOrNull { it.deviceName == deviceName }

        if (device != null && !usb.hasPermission(device)) {
            android.util.Log.i("UsbPermissionActivity", "Requesting OS USB permission: name=${device.deviceName} vid=${device.vendorId} pid=${device.productId}")
            // Keep this activity alive until the OS responds; some devices appear to auto-deny
            // requests if the requester immediately goes into background/finishes.
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != UsbPermissionResultReceiver.USB_PERMISSION_ACTION) return
                    finish()
                }
            }.also { r ->
                val filter = IntentFilter(UsbPermissionResultReceiver.USB_PERMISSION_ACTION)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    registerReceiver(r, filter)
                }
            }

            val pi = PendingIntent.getBroadcast(
                this,
                device.deviceName.hashCode(),
                Intent(this, UsbPermissionResultReceiver::class.java).apply {
                    action = UsbPermissionResultReceiver.USB_PERMISSION_ACTION
                    setPackage(packageName)
                },
                // Must be mutable: the platform attaches extras (UsbDevice, granted flag).
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            try {
                usb.requestPermission(device, pi)
            } catch (_: Exception) {
                // Best-effort only; caller will observe denial via timeout.
            }
            // Fallback: don't hang forever if the platform never responds.
            window?.decorView?.postDelayed({ finish() }, 60_000L)
        } else {
            android.util.Log.i("UsbPermissionActivity", "No OS USB permission request needed: deviceFound=${device != null} hasPermission=${device != null && usb.hasPermission(device)}")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val r = receiver
        receiver = null
        if (r != null) {
            try {
                unregisterReceiver(r)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}
