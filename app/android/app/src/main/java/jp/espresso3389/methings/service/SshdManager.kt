package jp.espresso3389.methings.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import jp.espresso3389.methings.device.DeviceNetworkManager

class SshdManager(
    private val context: Context,
    private val termuxManager: TermuxManager
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun startIfEnabled() {
        if (isEnabled()) {
            start()
        }
    }

    fun start(): Boolean {
        if (!termuxManager.isTermuxInstalled()) {
            Log.w(TAG, "Termux not installed; cannot start sshd")
            return false
        }
        if (!termuxManager.hasRunCommandPermission()) {
            Log.w(TAG, "RUN_COMMAND permission not granted; cannot start sshd")
            return false
        }
        configureSshd()
        termuxManager.startSshd()
        Log.i(TAG, "SSHD start requested on port ${getPort()}")
        return true
    }

    fun stop() {
        termuxManager.stopSshd()
        Log.i(TAG, "SSHD stop requested")
    }

    fun isRunning(): Boolean {
        return termuxManager.isSshdRunning()
    }

    fun status(): SshdStatus {
        return SshdStatus(
            enabled = isEnabled(),
            running = isRunning(),
            port = getPort(),
            authMode = getAuthMode(),
            host = getHostIp()
        )
    }

    fun updateConfig(enabled: Boolean, port: Int?, authMode: String?): SshdStatus {
        val wasRunning = isRunning()
        val prevPort = getPort()
        val prevAuthMode = getAuthMode()

        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (port != null && port > 0) {
            prefs.edit().putInt(KEY_PORT, port).apply()
        }
        if (authMode != null) {
            setAuthMode(authMode)
        }

        val portChanged = port != null && port > 0 && port != prevPort
        val authModeChanged = authMode != null && authMode != prevAuthMode
        val needsRestart = wasRunning && enabled && (portChanged || authModeChanged)

        if (enabled) {
            if (needsRestart) {
                stop()
                Thread.sleep(500)
                start()
            } else if (!wasRunning) {
                start()
            }
        } else {
            stop()
        }
        return status()
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun getPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    fun getAuthMode(): String =
        prefs.getString(KEY_AUTH_MODE, AUTH_MODE_PUBLIC_KEY) ?: AUTH_MODE_PUBLIC_KEY

    fun setAuthMode(mode: String) {
        val normalized = when (mode) {
            AUTH_MODE_NOTIFICATION -> AUTH_MODE_NOTIFICATION
            AUTH_MODE_PIN -> AUTH_MODE_PIN
            else -> AUTH_MODE_PUBLIC_KEY
        }
        prefs.edit().putString(KEY_AUTH_MODE, normalized).apply()
        if (normalized != AUTH_MODE_PIN) {
            prefs.edit().putString(KEY_AUTH_MODE_LAST_NON_PIN, normalized).apply()
        }
        if (normalized != AUTH_MODE_NOTIFICATION) {
            prefs.edit().putString(KEY_AUTH_MODE_LAST_NON_NOTIFICATION, normalized).apply()
        }
    }

    fun enterPinMode() {
        val current = getAuthMode()
        val lastNonPin = prefs.getString(KEY_AUTH_MODE_LAST_NON_PIN, AUTH_MODE_PUBLIC_KEY)
            ?: AUTH_MODE_PUBLIC_KEY
        val snapshot = if (current == AUTH_MODE_PIN) lastNonPin else current
        prefs.edit().putString(KEY_AUTH_MODE_PRE_PIN, snapshot).apply()
        setAuthMode(AUTH_MODE_PIN)
        restartIfRunning()
    }

    fun exitPinMode() {
        prefs.edit().remove(KEY_AUTH_MODE_PRE_PIN).apply()
        // PIN mode is temporary; always return to default public_key mode on exit.
        setAuthMode(AUTH_MODE_PUBLIC_KEY)
        restartIfRunning()
    }

    fun enterNotificationMode() {
        val current = getAuthMode()
        val lastNonNotif = prefs.getString(KEY_AUTH_MODE_LAST_NON_NOTIFICATION, AUTH_MODE_PUBLIC_KEY)
            ?: AUTH_MODE_PUBLIC_KEY
        val snapshot = if (current == AUTH_MODE_NOTIFICATION) lastNonNotif else current
        prefs.edit().putString(KEY_AUTH_MODE_PRE_NOTIFICATION, snapshot).apply()
        setAuthMode(AUTH_MODE_NOTIFICATION)
        restartIfRunning()
    }

    fun exitNotificationMode() {
        prefs.edit().remove(KEY_AUTH_MODE_PRE_NOTIFICATION).apply()
        // Notification mode is temporary; always return to default public_key mode on exit.
        setAuthMode(AUTH_MODE_PUBLIC_KEY)
        restartIfRunning()
    }

    fun restartIfRunning() {
        if (isRunning()) {
            stop()
            Thread.sleep(500)
            start()
        }
    }

    fun getHostIp(): String {
        return try {
            val status = DeviceNetworkManager(context).wifiStatus()
            (status["ip_address"] as? String) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Deploy helper scripts and patch sshd_config in Termux.
     *
     * Uses AuthorizedKeysFile (not AuthorizedKeysCommand) because Termux's
     * OpenSSH 10.x sshd-auth rejects AuthorizedKeysCommand scripts that are
     * not owned by root.
     *
     * Auth modes work via command= prefixes in the authorized_keys file:
     * - public_key: plain keys (no command=)
     * - pin: keys with command="methings-pin-check"
     * - notification: keys with command="methings-notif-check"
     */
    private fun configureSshd() {
        val port = getPort()

        val cmd = """
            cat > ${'$'}PREFIX/bin/methings-pin-check << 'SCRIPT_EOF'
            #!/data/data/com.termux/files/usr/bin/bash
            export PATH="/data/data/com.termux/files/usr/bin:${'$'}PATH"
            if [ -n "${'$'}SSH_ORIGINAL_COMMAND" ]; then
              echo "PIN auth only supports interactive sessions."
              exit 1
            fi
            echo -n "PIN: "
            read -r pin
            result=${'$'}(curl -sf "http://127.0.0.1:33389/sshd/pin/verify?pin=${'$'}pin" 2>/dev/null)
            if echo "${'$'}result" | grep -q '"valid":true'; then
              exec bash -l
            fi
            echo "Invalid PIN"
            exit 1
            SCRIPT_EOF
            chmod 755 ${'$'}PREFIX/bin/methings-pin-check && \
            cat > ${'$'}PREFIX/bin/methings-notif-check << 'SCRIPT_EOF'
            #!/data/data/com.termux/files/usr/bin/bash
            export PATH="/data/data/com.termux/files/usr/bin:${'$'}PATH"
            if [ -n "${'$'}SSH_ORIGINAL_COMMAND" ]; then
              echo "Notification auth only supports interactive sessions."
              exit 1
            fi
            echo "Waiting for approval on device..."
            result=${'$'}(curl -sf --max-time 35 "http://127.0.0.1:33389/sshd/noauth/wait" 2>/dev/null)
            if echo "${'$'}result" | grep -q '"approved":true'; then
              exec bash -l
            fi
            echo "Connection denied."
            exit 1
            SCRIPT_EOF
            chmod 755 ${'$'}PREFIX/bin/methings-notif-check && \
            mkdir -p ${'$'}HOME/.ssh && \
            touch ${'$'}HOME/.ssh/methings_keys && \
            chmod 600 ${'$'}HOME/.ssh/methings_keys && \
            SSHD_CONFIG="${'$'}PREFIX/etc/ssh/sshd_config" && \
            sed -i '/^# methings-auth-start/,/^# methings-auth-end/d' "${'$'}SSHD_CONFIG" 2>/dev/null; \
            sed -i '/^AuthorizedKeysCommand /d; /^AuthorizedKeysCommandUser /d' "${'$'}SSHD_CONFIG" 2>/dev/null; \
            sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' "${'$'}SSHD_CONFIG" 2>/dev/null; \
            sed -i 's/^AuthorizedKeysFile/#AuthorizedKeysFile/' "${'$'}SSHD_CONFIG" 2>/dev/null; \
            grep -q '^Port ' "${'$'}SSHD_CONFIG" && sed -i 's/^Port .*/Port $port/' "${'$'}SSHD_CONFIG" || echo "Port $port" >> "${'$'}SSHD_CONFIG"; \
            echo '' >> "${'$'}SSHD_CONFIG" && \
            echo '# methings-auth-start' >> "${'$'}SSHD_CONFIG" && \
            echo 'AuthorizedKeysFile .ssh/methings_keys' >> "${'$'}SSHD_CONFIG" && \
            echo '# methings-auth-end' >> "${'$'}SSHD_CONFIG"
        """.trimIndent()

        termuxManager.runCommand(cmd)
    }

    /**
     * Write the authorized_keys file in Termux based on current auth mode.
     * Called when keys change or auth mode changes.
     *
     * @param keys list of public key strings from the DB
     */
    fun writeAuthorizedKeys(keys: List<String>) {
        val authMode = getAuthMode()
        val lines = keys.filter { it.isNotBlank() }.map { key ->
            when (authMode) {
                AUTH_MODE_PIN -> "command=\"/data/data/com.termux/files/usr/bin/methings-pin-check\",restrict,pty $key"
                AUTH_MODE_NOTIFICATION -> "command=\"/data/data/com.termux/files/usr/bin/methings-notif-check\",restrict,pty $key"
                else -> key
            }
        }
        val content = lines.joinToString("\\n")
        // Write via Termux since we can't access its filesystem directly
        val cmd = if (content.isBlank()) {
            "> \$HOME/.ssh/methings_keys"
        } else {
            "printf '%b\\n' '$content' > \$HOME/.ssh/methings_keys"
        }
        termuxManager.runCommand(cmd)
        Log.i(TAG, "Wrote ${lines.size} keys to methings_keys (mode=$authMode)")
    }

    data class SshdStatus(
        val enabled: Boolean,
        val running: Boolean,
        val port: Int,
        val authMode: String,
        val host: String
    )

    companion object {
        private const val TAG = "SshdManager"
        const val PREFS = "sshd_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_PORT = "port"
        const val KEY_AUTH_MODE = "auth_mode"
        const val KEY_AUTH_MODE_PRE_PIN = "auth_mode_pre_pin"
        const val KEY_AUTH_MODE_LAST_NON_PIN = "auth_mode_last_non_pin"
        const val KEY_AUTH_MODE_PRE_NOTIFICATION = "auth_mode_pre_notification"
        const val KEY_AUTH_MODE_LAST_NON_NOTIFICATION = "auth_mode_last_non_notification"
        const val AUTH_MODE_PUBLIC_KEY = "public_key"
        const val AUTH_MODE_NOTIFICATION = "notification"
        const val AUTH_MODE_PIN = "pin"
        const val DEFAULT_PORT = 8022
    }
}
