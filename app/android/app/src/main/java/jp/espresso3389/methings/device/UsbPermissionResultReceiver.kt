package jp.espresso3389.methings.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

class UsbPermissionResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        if (intent.action != USB_PERMISSION_ACTION) return
        val dev = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        }
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val name = dev?.deviceName ?: return
        android.util.Log.i("UsbPermissionResultReceiver", "USB permission broadcast: name=$name granted=$granted")
        UsbPermissionWaiter.complete(name, granted)
    }

    companion object {
        const val USB_PERMISSION_ACTION = "jp.espresso3389.methings.USB_PERMISSION"
    }
}
