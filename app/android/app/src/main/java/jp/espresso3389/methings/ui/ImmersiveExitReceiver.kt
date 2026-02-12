package jp.espresso3389.methings.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ImmersiveExitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_EXIT_IMMERSIVE) return
        val ctx = context ?: return
        val launch = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_EXIT_IMMERSIVE, true)
        }
        ctx.startActivity(launch)
    }

    companion object {
        const val ACTION_EXIT_IMMERSIVE = "jp.espresso3389.methings.EXIT_IMMERSIVE"
        const val EXTRA_EXIT_IMMERSIVE = "exit_immersive"
    }
}
