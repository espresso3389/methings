package jp.espresso3389.kugutz.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

class SshdManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val processRef = AtomicReference<Process?>(null)

    fun startIfEnabled() {
        if (isEnabled()) {
            start()
        }
    }

    fun start(): Boolean {
        if (processRef.get()?.isAlive == true) {
            return true
        }
        val binDir = File(context.filesDir, "bin")
        val dropbear = resolveBinary("libdropbear.so", File(binDir, "dropbear"))
        val dropbearkey = resolveBinary("libdropbearkey.so", File(binDir, "dropbearkey"))
        val shellBin = ensureShellBinary(File(binDir, "kugutzsh"))
        if (dropbear == null) {
            Log.w(TAG, "Dropbear binary missing")
            return false
        }
        if (dropbearkey == null) {
            Log.w(TAG, "Dropbearkey binary missing")
            return false
        }
        if (shellBin == null) {
            Log.w(TAG, "Kugutz shell binary missing")
        }
        val userHome = File(context.filesDir, "user")
        val sshDir = File(userHome, ".ssh")
        val protectedDir = File(context.filesDir, "protected/ssh")
        sshDir.mkdirs()
        protectedDir.mkdirs()
        val logFile = File(protectedDir, "dropbear.log")
        val pidFile = File(protectedDir, "dropbear.pid")
        val noauthDir = File(protectedDir, "noauth_prompts")
        noauthDir.mkdirs()
        val pinFile = File(protectedDir, "pin_auth")
        val authMode = getAuthMode()
        val authDir = when (authMode) {
            AUTH_MODE_NOTIFICATION -> File(protectedDir, "noauth_keys")
            AUTH_MODE_PIN -> File(protectedDir, "pin_keys")
            else -> sshDir
        }
        authDir.mkdirs()

        if (pidFile.exists()) {
            try {
                val pid = pidFile.readText().trim()
                if (pid.isNotBlank()) {
                    ProcessBuilder("kill", "-9", pid).start().waitFor()
                }
            } catch (_: Exception) {
            } finally {
                pidFile.delete()
            }
        }

        val hostKey = File(protectedDir, "dropbear_host_key")
        if (!hostKey.exists()) {
            val ok = generateHostKey(dropbearkey, hostKey)
            if (!ok) {
                Log.w(TAG, "Failed to generate host key")
                return false
            }
        }
        val authKeys = File(sshDir, "authorized_keys")
        if (!authKeys.exists()) {
            authKeys.writeText("")
        }

        val port = getPort()
        val args = listOf(
            dropbear.absolutePath,
            "-F",
            "-p",
            port.toString(),
            "-r",
            hostKey.absolutePath,
            "-D",
            authDir.absolutePath,
            "-P",
            pidFile.absolutePath
        )
        return try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val pyenvDir = File(context.filesDir, "pyenv")
            val serverDir = File(context.filesDir, "server")
            val pb = ProcessBuilder(args)
            pb.environment()["HOME"] = userHome.absolutePath
            pb.environment()["KUGUTZ_HOME"] = userHome.absolutePath
            if (shellBin != null) {
                pb.environment()["KUGUTZ_SHELL"] = shellBin.absolutePath
            }
            pb.environment()["USER"] = "kugutz"
            pb.environment()["DROPBEAR_PIN_FILE"] = pinFile.absolutePath
            // Python/pip environment for SSH sessions
            pb.environment()["KUGUTZ_PYENV"] = pyenvDir.absolutePath
            pb.environment()["KUGUTZ_NATIVELIB"] = nativeLibDir
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
            pb.environment()["PYTHONHOME"] = pyenvDir.absolutePath
            pb.environment()["PYTHONPATH"] = listOf(
                serverDir.absolutePath,
                "${pyenvDir.absolutePath}/site-packages",
                "${pyenvDir.absolutePath}/modules",
                "${pyenvDir.absolutePath}/stdlib.zip"
            ).joinToString(":")
            val certFile = File(pyenvDir, "site-packages/certifi/cacert.pem")
            if (certFile.exists()) {
                pb.environment()["SSL_CERT_FILE"] = certFile.absolutePath
                pb.environment()["PIP_CERT"] = certFile.absolutePath
            }
            // Add nativeLibDir to PATH so python3/pip are accessible via libkugutzpy.so
            val existingPath = pb.environment()["PATH"] ?: "/usr/bin:/bin"
            pb.environment()["PATH"] = "${binDir.absolutePath}:$nativeLibDir:$existingPath"
            if (authMode == AUTH_MODE_NOTIFICATION) {
                pb.environment()["DROPBEAR_NOAUTH_PROMPT_DIR"] = noauthDir.absolutePath
                pb.environment()["DROPBEAR_NOAUTH_PROMPT_TIMEOUT"] = "10"
            }
            pb.redirectErrorStream(true)
            pb.redirectOutput(logFile)
            val proc = pb.start()
            processRef.set(proc)
            Log.i(TAG, "SSHD started on port $port")
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to start SSHD", ex)
            false
        }
    }

    fun stop() {
        val proc = processRef.getAndSet(null) ?: return
        try {
            proc.destroy()
        } catch (_: Exception) {
        }
    }

    fun status(): SshStatus {
        val running = processRef.get()?.isAlive == true || isPortOpen(getPort())
        return SshStatus(
            enabled = isEnabled(),
            running = running,
            port = getPort(),
            noauthEnabled = isNoAuthEnabled(),
            homeDir = File(context.filesDir, "user").absolutePath,
            authorizedKeys = File(context.filesDir, "user/.ssh/authorized_keys").absolutePath
        )
    }

    fun updateConfig(enabled: Boolean, port: Int?, noauthEnabled: Boolean?): SshStatus {
        val wasRunning = processRef.get()?.isAlive == true
        val prevPort = getPort()
        val prevNoauth = isNoAuthEnabled()
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (port != null && port > 0) {
            prefs.edit().putInt(KEY_PORT, port).apply()
        }
        if (noauthEnabled != null) {
            prefs.edit().putBoolean(KEY_NOAUTH, noauthEnabled).apply()
        }
        val portChanged = port != null && port > 0 && port != prevPort
        val noauthChanged = noauthEnabled != null && noauthEnabled != prevNoauth
        val needsRestart = wasRunning && enabled && (portChanged || noauthChanged)
        if (enabled) {
            if (needsRestart) {
                stop()
                start()
            } else if (!wasRunning) {
                start()
            }
        } else {
            stop()
        }
        return status()
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun getPort(): Int = prefs.getInt(KEY_PORT, 2222)

    fun isNoAuthEnabled(): Boolean = prefs.getBoolean(KEY_NOAUTH, false)

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
        prefs.edit().putBoolean(KEY_NOAUTH, normalized == AUTH_MODE_NOTIFICATION).apply()
        if (normalized != AUTH_MODE_PIN) {
            val pinFile = File(context.filesDir, "protected/ssh/pin_auth")
            if (pinFile.exists()) {
                pinFile.delete()
            }
        }
        if (isEnabled()) {
            stop()
            start()
        }
    }

    fun enterPinMode() {
        val current = getAuthMode()
        val lastNonPin = prefs.getString(KEY_AUTH_MODE_LAST_NON_PIN, AUTH_MODE_PUBLIC_KEY)
            ?: AUTH_MODE_PUBLIC_KEY
        val snapshot = if (current == AUTH_MODE_PIN) lastNonPin else current
        prefs.edit().putString(KEY_AUTH_MODE_PRE_PIN, snapshot).apply()
        setAuthMode(AUTH_MODE_PIN)
    }

    fun exitPinMode() {
        val prev = prefs.getString(KEY_AUTH_MODE_PRE_PIN, null)
        val fallback = prefs.getString(KEY_AUTH_MODE_LAST_NON_PIN, AUTH_MODE_PUBLIC_KEY)
            ?: AUTH_MODE_PUBLIC_KEY
        prefs.edit().remove(KEY_AUTH_MODE_PRE_PIN).apply()
        val normalizedPrev = when (prev) {
            AUTH_MODE_PUBLIC_KEY, AUTH_MODE_NOTIFICATION -> prev
            else -> null
        }
        setAuthMode(normalizedPrev ?: fallback)
    }

    fun getAuthMode(): String =
        prefs.getString(KEY_AUTH_MODE, AUTH_MODE_PUBLIC_KEY) ?: AUTH_MODE_PUBLIC_KEY

    fun getHostIp(): String {
        return try {
            val cm = context.applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            val network = cm?.activeNetwork ?: return ""
            val caps = cm.getNetworkCapabilities(network) ?: return ""
            if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                return ""
            }
            val linkProps = cm.getLinkProperties(network) ?: return ""
            val addr = linkProps.linkAddresses
                .mapNotNull { it.address }
                .firstOrNull { it is java.net.Inet4Address }
            addr?.hostAddress ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun generateHostKey(dropbearkey: File, hostKey: File): Boolean {
        return try {
            val proc = ProcessBuilder(
                dropbearkey.absolutePath,
                "-t",
                "ed25519",
                "-f",
                hostKey.absolutePath
            ).start()
            val rc = proc.waitFor()
            rc == 0 && hostKey.exists()
        } catch (ex: Exception) {
            Log.e(TAG, "dropbearkey failed", ex)
            false
        }
    }

    private fun resolveBinary(nativeName: String, fallback: File): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativeFile = File(nativeDir, nativeName)
        if (nativeFile.exists()) {
            return nativeFile
        }
        if (fallback.exists()) {
            return fallback
        }
        return null
    }

    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureShellBinary(target: File): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativeFile = File(nativeDir, "libkugutzsh.so")
        if (!nativeFile.exists()) {
            return if (target.exists()) target else null
        }
        if (target.exists()) {
            target.setExecutable(true, true)
            return target
        }
        return try {
            target.parentFile?.mkdirs()
            nativeFile.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            target.setExecutable(true, true)
            target
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to prepare kugutzsh binary", ex)
            null
        }
    }

    data class SshStatus(
        val enabled: Boolean,
        val running: Boolean,
        val port: Int,
        val noauthEnabled: Boolean,
        val homeDir: String,
        val authorizedKeys: String
    )

    companion object {
        private const val TAG = "SshdManager"
        const val PREFS = "sshd_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_PORT = "port"
        const val KEY_NOAUTH = "noauth_enabled"
        const val KEY_AUTH_MODE = "auth_mode"
        const val KEY_AUTH_MODE_PRE_PIN = "auth_mode_pre_pin"
        const val KEY_AUTH_MODE_LAST_NON_PIN = "auth_mode_last_non_pin"
        const val AUTH_MODE_PUBLIC_KEY = "public_key"
        const val AUTH_MODE_NOTIFICATION = "notification"
        const val AUTH_MODE_PIN = "pin"
    }
}
