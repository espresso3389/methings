package jp.espresso3389.methings.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("start_on_boot", true)) return
        ctx.startForegroundService(Intent(ctx, AgentService::class.java))
    }
}
