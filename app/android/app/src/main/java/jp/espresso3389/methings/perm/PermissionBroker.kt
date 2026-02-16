package jp.espresso3389.methings.perm

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.fragment.app.FragmentActivity
import jp.espresso3389.methings.AppForegroundState
import jp.espresso3389.methings.R
import jp.espresso3389.methings.ui.MainActivity

class PermissionBroker(private val context: Context) {
    fun requestConsent(tool: String, detail: String, onResult: (Boolean) -> Unit): Boolean {
        return requestConsent(tool, detail, false, onResult)
    }

    /**
     * Show a native consent UI (biometric or AlertDialog).
     * Returns true if a native dialog was shown (onResult will be called).
     * Returns false if skipped because the app is foreground
     * (the WebView perm-card handles consent instead; onResult will NOT be called).
     */
    fun requestConsent(tool: String, detail: String, forceBiometric: Boolean, onResult: (Boolean) -> Unit): Boolean {
        android.util.Log.d("MethingsPerm", "requestConsent tool=$tool detail=$detail foreground=${AppForegroundState.isForeground}")
        val needsBiometric = forceBiometric || tool == "credentials" || tool == "ssh_pin"

        if (needsBiometric && context is FragmentActivity) {
            if (tool != "ssh_pin") {
                postNotification(tool, detail)
            }
            val manager = BiometricManager.from(context)
            val canAuth = manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
                val executor = ContextCompat.getMainExecutor(context)
                val prompt = BiometricPrompt(
                    context,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            onResult(true)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            onResult(false)
                        }
                    }
                )
                val subtitle = if (detail.isNotEmpty()) detail else "Credential Vault access"
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Confirm credential access")
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
                prompt.authenticate(promptInfo)
                return true
            }
        }

        // Foreground: let the WebView perm-card handle consent.
        // This covers both non-biometric tools and biometric tools whose biometric
        // check is unavailable — the WebView card is always preferable to a native
        // AlertDialog when the user is already looking at the app.
        if (AppForegroundState.isForeground) {
            android.util.Log.d("MethingsPerm", "skipping native dialog — foreground, WebView perm-card handles consent")
            return false
        }

        // Background fallback: native AlertDialog.
        if (tool != "ssh_pin") {
            postNotification(tool, detail)
        }
        val message = if (detail.isNotEmpty()) {
            "$tool: $detail"
        } else {
            tool
        }
        AlertDialog.Builder(context)
            .setTitle("Allow tool access?")
            .setMessage(message)
            .setPositiveButton("Allow") { _, _ -> onResult(true) }
            .setNegativeButton("Deny") { _, _ -> onResult(false) }
            .setCancelable(false)
            .show()
        return true
    }

    fun postNotification(tool: String, detail: String) {
        val channelId = "permission_requests"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Permission Requests",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }
        val intent = Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val message = if (detail.isNotEmpty()) "$tool: $detail" else tool
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Permission request")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(message.hashCode(), notification)
    }
}
