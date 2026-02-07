package jp.espresso3389.kugutz.service

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SshAuthModeMonitor(
    private val sshdManager: SshdManager,
    private val pinManager: SshPinManager,
    private val noAuthModeManager: SshNoAuthModeManager,
    private val notifier: SshAuthModeNotificationManager
) {
    private val executor = Executors.newSingleThreadScheduledExecutor()

    fun start() {
        executor.scheduleAtFixedRate({ tick() }, 0, 1, TimeUnit.SECONDS)
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun tick() {
        try {
            val pin = pinManager.status()
            if (pin.expired) {
                Log.i(TAG, "PIN auth expired; exiting PIN mode")
                pinManager.stopPin()
                sshdManager.exitPinMode()
            }
            val noauth = noAuthModeManager.status()
            if (noauth.expired) {
                Log.i(TAG, "Notification auth expired; exiting notification mode")
                noAuthModeManager.stop()
                sshdManager.exitNotificationMode()
            }

            val authMode = sshdManager.getAuthMode()
            when {
                authMode == SshdManager.AUTH_MODE_PIN && pin.active -> notifier.showPinActive(pin.expiresAt)
                authMode == SshdManager.AUTH_MODE_NOTIFICATION && noauth.active -> notifier.showNotificationActive(noauth.expiresAt)
                else -> notifier.cancel()
            }
        } catch (ex: Exception) {
            Log.w(TAG, "monitor tick failed", ex)
        }
    }

    companion object {
        private const val TAG = "SshAuthModeMonitor"
    }
}

