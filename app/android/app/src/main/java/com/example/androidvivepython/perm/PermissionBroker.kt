package jp.espresso3389.kugutz.perm

import android.app.AlertDialog
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
import jp.espresso3389.kugutz.ui.MainActivity

class PermissionBroker(private val context: Context) {
    fun requestConsent(tool: String, detail: String, onResult: (Boolean) -> Unit) {
        android.util.Log.d("KugutzPerm", "requestConsent tool=$tool detail=$detail")
        postNotification(tool, detail)
        if ((tool == "credentials" || tool == "ssh_noauth") && context is FragmentActivity) {
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
                return
            }
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
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(message.hashCode(), notification)
    }
}
