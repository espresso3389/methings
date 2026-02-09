package jp.espresso3389.methings.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import jp.espresso3389.methings.perm.InstallIdentity

class SshdManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val processRef = AtomicReference<Process?>(null)
    private val installIdentity = InstallIdentity(context)

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
        // Keep on-device shell binary name stable across app renames, but allow a new name too.
        // We prefer "methingssh" when present; otherwise we fall back to the historical "methingssh".
        val shellBin = ensureShellBinary(File(binDir, "methingssh"))
        if (dropbear == null) {
            Log.w(TAG, "Dropbear binary missing")
            return false
        }
        if (dropbearkey == null) {
            Log.w(TAG, "Dropbearkey binary missing")
            return false
        }
        if (shellBin == null) {
            Log.w(TAG, "methings shell binary missing")
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
        // Outbound ssh/scp (dbclient) defaults to ~/.ssh/id_dropbear.
        // Ensure client key exists and is distinct from the host key.
        val clientKey = File(sshDir, "id_dropbear")
        val ok = ensureClientKey(dropbearkey, hostKey, clientKey)
        if (!ok) {
            Log.w(TAG, "Failed to prepare client key")
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
            // pip build isolation spawns subprocesses that may override PYTHONPATH; make sure
            // the runtime is installed and pythonXY.zip is present so stdlib imports work.
            PythonRuntimeInstaller(context).ensureInstalled()

            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val pyenvDir = File(context.filesDir, "pyenv")
            val serverDir = File(context.filesDir, "server")
            val wheelhouse = WheelhousePaths.forCurrentAbi(context)?.also { it.ensureDirs() }

            // Ensure outbound ssh/scp from within the SSH session behaves reasonably even though
            // Android app sandboxes usually don't expose a real devpts mount (no PTY). In that case,
            // Dropbear's client tools need non-interactive defaults (host key auto-accept) and
            // ssh should force remote PTY when possible (-t) so interactive logins work.
            ensureSshClientWrappers(binDir, userHome)
            val mkshEnv = ensureMkshEnvFile(userHome)

            val pb = ProcessBuilder(args)
            pb.environment()["HOME"] = userHome.absolutePath
            pb.environment()["METHINGS_HOME"] = userHome.absolutePath
            pb.environment()["METHINGS_IDENTITY"] = installIdentity.get()
            if (shellBin != null) {
                pb.environment()["METHINGS_SHELL"] = shellBin.absolutePath
            }
            pb.environment()["USER"] = "methings"
            pb.environment()["DROPBEAR_PIN_FILE"] = pinFile.absolutePath
            // Python/pip environment for SSH sessions
            pb.environment()["METHINGS_PYENV"] = pyenvDir.absolutePath
            pb.environment()["METHINGS_NATIVELIB"] = nativeLibDir
            pb.environment()["METHINGS_BINDIR"] = binDir.absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
            pb.environment()["PYTHONHOME"] = pyenvDir.absolutePath
            pb.environment()["PYTHONPATH"] = listOf(
                serverDir.absolutePath,
                "${pyenvDir.absolutePath}/site-packages",
                "${pyenvDir.absolutePath}/modules",
                "${pyenvDir.absolutePath}/stdlib.zip"
            ).joinToString(":")
            if (wheelhouse != null) {
                pb.environment()["METHINGS_WHEELHOUSE"] = wheelhouse.findLinksEnvValue()
                // Let pip resolve prebuilt wheels packaged with the app (e.g. opencv-python wheels).
                pb.environment()["PIP_FIND_LINKS"] = wheelhouse.findLinksEnvValue()
            }
            // Prefer the managed CA bundle (app-private, refreshable) over certifi's baked-in file.
            val managedCa = File(context.filesDir, "protected/ca/cacert.pem")
            val fallbackCertifi = File(pyenvDir, "site-packages/certifi/cacert.pem")
            val caFile = when {
                managedCa.exists() && managedCa.length() > 0 -> managedCa
                fallbackCertifi.exists() -> fallbackCertifi
                else -> null
            }
            if (caFile != null) {
                pb.environment()["SSL_CERT_FILE"] = caFile.absolutePath
                pb.environment()["PIP_CERT"] = caFile.absolutePath
                pb.environment()["REQUESTS_CA_BUNDLE"] = caFile.absolutePath
            }
            // Add nativeLibDir to PATH so python3/pip are accessible via libmethingspy.so
            val existingPath = pb.environment()["PATH"] ?: "/usr/bin:/bin"
            pb.environment()["PATH"] = "${binDir.absolutePath}:$nativeLibDir:$existingPath"
            // mksh reads $ENV for startup config in both interactive and non-interactive shells.
            pb.environment()["ENV"] = mkshEnv.absolutePath
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
        val clientKeyInfo = getClientKeyInfo()
        return SshStatus(
            enabled = isEnabled(),
            running = running,
            port = getPort(),
            noauthEnabled = isNoAuthEnabled(),
            homeDir = File(context.filesDir, "user").absolutePath,
            authorizedKeys = File(context.filesDir, "user/.ssh/authorized_keys").absolutePath,
            clientKeyFingerprint = clientKeyInfo.first,
            clientKeyPublic = clientKeyInfo.second
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
        if (normalized != AUTH_MODE_NOTIFICATION) {
            prefs.edit().putString(KEY_AUTH_MODE_LAST_NON_NOTIFICATION, normalized).apply()
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

    fun enterNotificationMode() {
        val current = getAuthMode()
        val lastNonNotif = prefs.getString(KEY_AUTH_MODE_LAST_NON_NOTIFICATION, AUTH_MODE_PUBLIC_KEY)
            ?: AUTH_MODE_PUBLIC_KEY
        val snapshot = if (current == AUTH_MODE_NOTIFICATION) lastNonNotif else current
        prefs.edit().putString(KEY_AUTH_MODE_PRE_NOTIFICATION, snapshot).apply()
        setAuthMode(AUTH_MODE_NOTIFICATION)
    }

    fun exitNotificationMode() {
        val prev = prefs.getString(KEY_AUTH_MODE_PRE_NOTIFICATION, null)
        val fallback = prefs.getString(KEY_AUTH_MODE_LAST_NON_NOTIFICATION, AUTH_MODE_PUBLIC_KEY)
            ?: AUTH_MODE_PUBLIC_KEY
        prefs.edit().remove(KEY_AUTH_MODE_PRE_NOTIFICATION).apply()
        val normalizedPrev = when (prev) {
            AUTH_MODE_PUBLIC_KEY, AUTH_MODE_PIN -> prev
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

    private fun getClientKeyInfo(): Pair<String, String> {
        val clientKey = File(context.filesDir, "user/.ssh/id_dropbear")
        if (!clientKey.exists()) return Pair("", "")
        val binDir = File(context.filesDir, "bin")
        val dropbearkey = resolveBinary("libdropbearkey.so", File(binDir, "dropbearkey"))
            ?: return Pair("", "")
        return try {
            val proc = ProcessBuilder(
                dropbearkey.absolutePath, "-y", "-f", clientKey.absolutePath
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            if (rc != 0) return Pair("", "")
            var fingerprint = ""
            var pubKey = ""
            for (line in output.lines()) {
                if (line.startsWith("Fingerprint:")) {
                    fingerprint = line.removePrefix("Fingerprint:").trim()
                } else if (line.startsWith("ssh-")) {
                    pubKey = line.trim()
                }
            }
            Pair(fingerprint, pubKey)
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to read client key info", ex)
            Pair("", "")
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

    private fun ensureClientKey(dropbearkey: File, hostKey: File, clientKey: File): Boolean {
        return try {
            clientKey.parentFile?.mkdirs()
            if (clientKey.exists()) {
                // Keep existing client key unless it accidentally matches the host key.
                if (hostKey.exists() && hostKey.readBytes().contentEquals(clientKey.readBytes())) {
                    Log.w(TAG, "Client key matches host key; rotating client key")
                } else {
                    clientKey.setReadable(true, true)
                    clientKey.setWritable(true, true)
                    clientKey.setExecutable(false, false)
                    return clientKey.length() > 0
                }
            }
            if (clientKey.exists() && !clientKey.delete()) {
                Log.w(TAG, "Failed to remove existing client key before regeneration")
                return false
            }
            val proc = ProcessBuilder(
                dropbearkey.absolutePath,
                "-t",
                "ed25519",
                "-f",
                clientKey.absolutePath
            ).start()
            val rc = proc.waitFor()
            if (rc != 0 || !clientKey.exists()) {
                return false
            }
            clientKey.setReadable(true, true)
            clientKey.setWritable(true, true)
            clientKey.setExecutable(false, false)
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to prepare client key", ex)
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
        val nativeFile = when {
            File(nativeDir, "libmethingssh.so").exists() -> File(nativeDir, "libmethingssh.so")
            // Back-compat: old name (kept so we don't need to rebuild every dependent artifact
            // when changing app branding).
            File(nativeDir, "libmethingssh.so").exists() -> File(nativeDir, "libmethingssh.so")
            else -> File(nativeDir, "libmethingssh.so")
        }
        if (!nativeFile.exists()) {
            if (target.exists()) return target
            // Back-compat: old extracted shell name.
            val oldTarget = File(target.parentFile, "methingssh")
            return if (oldTarget.exists()) oldTarget else null
        }
        return try {
            val needsRefresh = !target.exists() ||
                target.length() != nativeFile.length() ||
                target.lastModified() < nativeFile.lastModified()
            if (needsRefresh) {
                target.parentFile?.mkdirs()
                nativeFile.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // Keep mtime aligned so subsequent starts can skip copy.
                target.setLastModified(nativeFile.lastModified())
            }
            target.setExecutable(true, true)
            target
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to prepare methingssh binary", ex)
            null
        }
    }

    private fun ensureSshClientWrappers(binDir: File, userHome: File) {
        // A small wrapper used by scp's `-S` option (it expects a program path, not args).
        val wrapper = File(binDir, "methings-dbclient")
        if (!wrapper.exists() || wrapper.length() == 0L) {
            wrapper.parentFile?.mkdirs()
            wrapper.writeText(
                "#!/system/bin/sh\n" +
                    "exec \"${'$'}METHINGS_NATIVELIB/libdbclient.so\" -y \"${'$'}@\"\n"
            )
        }
        wrapper.setExecutable(true, true)
        wrapper.setReadable(true, true)
        wrapper.setWritable(true, true)

        // mksh startup file. We keep it in HOME so the path stays stable across updates.
        val envFile = File(userHome, ".mkshrc")
        val content =
            "# methings mksh env (auto-generated)\n" +
                "PS1='methings> '\n" +
                // Override Android's built-in functions so they don't embed stale /data/app/... paths.
                "ssh() {\n" +
                "  want_t=1\n" +
                "  want_y=1\n" +
                "  for a in \"${'$'}@\"; do\n" +
                "    case \"${'$'}a\" in\n" +
                "      -t|-T) want_t=0;;\n" +
                "      -y) want_y=0;;\n" +
                "    esac\n" +
                "  done\n" +
                "  if [ \"${'$'}want_y\" -eq 1 ]; then set -- -y \"${'$'}@\"; fi\n" +
                // Without a local PTY, dbclient won't auto-request a remote PTY. Force it so
                // `ssh user@host` becomes usable for interactive sessions.
                "  if [ \"${'$'}want_t\" -eq 1 ]; then set -- -t \"${'$'}@\"; fi\n" +
                "  exec \"${'$'}METHINGS_NATIVELIB/libdbclient.so\" \"${'$'}@\"\n" +
                "}\n" +
                "dbclient() { ssh \"${'$'}@\"; }\n" +
                "scp() {\n" +
                "  exec \"${'$'}METHINGS_NATIVELIB/libscp.so\" -S \"${'$'}METHINGS_BINDIR/methings-dbclient\" \"${'$'}@\"\n" +
                "}\n"
        val needsWrite = !envFile.exists() || envFile.readText() != content
        if (needsWrite) {
            envFile.writeText(content)
        }
        envFile.setReadable(true, true)
        envFile.setWritable(true, true)
        envFile.setExecutable(false, false)
    }

    private fun ensureMkshEnvFile(userHome: File): File {
        // ensureSshClientWrappers() writes the file; return it for ProcessBuilder ENV=...
        val envFile = File(userHome, ".mkshrc")
        if (!envFile.exists()) {
            envFile.parentFile?.mkdirs()
            envFile.writeText("# methings mksh env\n")
        }
        return envFile
    }

    data class SshStatus(
        val enabled: Boolean,
        val running: Boolean,
        val port: Int,
        val noauthEnabled: Boolean,
        val homeDir: String,
        val authorizedKeys: String,
        val clientKeyFingerprint: String = "",
        val clientKeyPublic: String = ""
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
        const val KEY_AUTH_MODE_PRE_NOTIFICATION = "auth_mode_pre_notification"
        const val KEY_AUTH_MODE_LAST_NON_NOTIFICATION = "auth_mode_last_non_notification"
        const val AUTH_MODE_PUBLIC_KEY = "public_key"
        const val AUTH_MODE_NOTIFICATION = "notification"
        const val AUTH_MODE_PIN = "pin"
    }
}
