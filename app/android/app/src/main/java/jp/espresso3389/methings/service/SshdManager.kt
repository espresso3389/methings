package jp.espresso3389.methings.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import jp.espresso3389.methings.device.DeviceNetworkManager
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

    /** Watchdog: restart SSHD if it was enabled but killed externally (e.g. Phantom Process Killer). */
    fun ensureRunning() {
        if (!isEnabled()) return
        if (processRef.get()?.isAlive == true) return
        if (isPortOpen(getPort())) return
        Log.w(TAG, "SSHD not running but enabled; restarting")
        start()
    }

    fun start(): Boolean {
        if (processRef.get()?.isAlive == true) {
            return true
        }
        val binDir = File(context.filesDir, "bin")
        val dropbear = resolveBinary("libdropbear.so", File(binDir, "dropbear"))
        val dropbearkey = resolveBinary("libdropbearkey.so", File(binDir, "dropbearkey"))
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
            PythonRuntimeInstaller(context).ensureInstalled()

            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val pyenvDir = File(context.filesDir, "pyenv")
            val serverDir = File(context.filesDir, "server")
            val wheelhouse = WheelhousePaths.forCurrentAbi(context)?.also { it.ensureDirs() }

            ensureSshClientWrappers(binDir, userHome)
            ensureNpmPrefixBinExecutable(userHome)
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
            pb.environment()["METHINGS_PYENV"] = pyenvDir.absolutePath
            pb.environment()["METHINGS_NATIVELIB"] = nativeLibDir
            pb.environment()["METHINGS_BINDIR"] = binDir.absolutePath
            val nodeRoot = File(context.filesDir, "node")
            pb.environment()["METHINGS_NODE_ROOT"] = nodeRoot.absolutePath
            val nodeLibDir = File(nodeRoot, "lib").absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = "$nodeLibDir:$nativeLibDir"
            val scriptShell = File(binDir, "methings-sh").absolutePath
            pb.environment()["npm_config_script_shell"] = scriptShell
            pb.environment()["NPM_CONFIG_SCRIPT_SHELL"] = scriptShell
            pb.environment()["PYTHONHOME"] = pyenvDir.absolutePath
            pb.environment()["PYTHONPATH"] = listOf(
                serverDir.absolutePath,
                "${pyenvDir.absolutePath}/site-packages",
                "${pyenvDir.absolutePath}/modules",
                "${pyenvDir.absolutePath}/stdlib.zip"
            ).joinToString(":")
            if (wheelhouse != null) {
                pb.environment()["METHINGS_WHEELHOUSE"] = wheelhouse.findLinksEnvValue()
                pb.environment()["PIP_FIND_LINKS"] = wheelhouse.findLinksEnvValue()
            }
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
            val existingPath = pb.environment()["PATH"] ?: "/usr/bin:/bin"
            val npmBin = File(userHome, "npm-prefix/bin").absolutePath
            pb.environment()["PATH"] = "${binDir.absolutePath}:$nativeLibDir:$npmBin:$existingPath"
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

    fun isRunning(): Boolean {
        return processRef.get()?.isAlive == true || isPortOpen(getPort())
    }

    fun restartIfRunning() {
        if (processRef.get()?.isAlive != true && !isPortOpen(getPort())) return
        stop()
        start()
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
        prefs.edit().remove(KEY_AUTH_MODE_PRE_PIN).apply()
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
    }

    fun exitNotificationMode() {
        prefs.edit().remove(KEY_AUTH_MODE_PRE_NOTIFICATION).apply()
        setAuthMode(AUTH_MODE_PUBLIC_KEY)
        restartIfRunning()
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
     * Write authorized_keys to the appropriate Dropbear auth directory.
     * For public_key mode: writes to ~/.ssh/authorized_keys
     * For pin/notification modes: writes to the respective auth dir
     * Called when keys change or auth mode changes.
     */
    fun writeAuthorizedKeys(keys: List<String>) {
        val authMode = getAuthMode()
        val userHome = File(context.filesDir, "user")
        val sshDir = File(userHome, ".ssh")
        val protectedDir = File(context.filesDir, "protected/ssh")

        val authDir = when (authMode) {
            AUTH_MODE_NOTIFICATION -> File(protectedDir, "noauth_keys")
            AUTH_MODE_PIN -> File(protectedDir, "pin_keys")
            else -> sshDir
        }
        authDir.mkdirs()

        val authKeysFile = File(authDir, "authorized_keys")
        val content = keys.filter { it.isNotBlank() }.joinToString("\n") + "\n"
        authKeysFile.writeText(content)
        authKeysFile.setReadable(true, true)
        authKeysFile.setWritable(true, true)
        Log.i(TAG, "Wrote ${keys.size} keys to ${authKeysFile.absolutePath} (mode=$authMode)")
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
        val nativeFile = File(nativeDir, "libmethingssh.so")
        if (!nativeFile.exists()) {
            if (target.exists()) return target
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

        val envFile = File(userHome, ".mkshrc")
        val content =
            "# me.things mksh env (auto-generated)\n" +
                "PS1='me.things> '\n" +
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
                "  if [ \"${'$'}want_t\" -eq 1 ]; then set -- -t \"${'$'}@\"; fi\n" +
                "  \"${'$'}METHINGS_NATIVELIB/libdbclient.so\" \"${'$'}@\"\n" +
                "}\n" +
                "dbclient() { ssh \"${'$'}@\"; }\n" +
                "scp() {\n" +
                "  \"${'$'}METHINGS_NATIVELIB/libscp.so\" -S \"${'$'}METHINGS_BINDIR/methings-dbclient\" \"${'$'}@\"\n" +
                "}\n" +
                // SELinux blocks direct execution of npm shims from app_data_file.
                // Register aliases for all npm-prefix/bin entries and dispatch by shebang.
                "_methings_npm_run() {\n" +
                "  _cmd=\"${'$'}1\"; shift\n" +
                "  [ -r \"${'$'}_cmd\" ] || { echo \"not found: ${'$'}_cmd\" 1>&2; return 127; }\n" +
                "  IFS= read -r _head < \"${'$'}_cmd\" || _head=''\n" +
                "  case \"${'$'}_head\" in\n" +
                "    '#!'*'env node'*|'#!'*'/node'*)\n" +
                "      _nr=\"${'$'}{METHINGS_NODE_ROOT:-${'$'}HOME/../node}\"\n" +
                "      LD_LIBRARY_PATH=\"${'$'}_nr/lib:${'$'}{LD_LIBRARY_PATH:-}\" \"${'$'}METHINGS_NATIVELIB/libnode.so\" \"${'$'}_cmd\" \"${'$'}@\"\n" +
                "      ;;\n" +
                "    '#!'*'env python3'*|'#!'*'/python3'*|'#!'*'/python'*)\n" +
                "      \"${'$'}METHINGS_NATIVELIB/libmethingspy.so\" \"${'$'}_cmd\" \"${'$'}@\"\n" +
                "      ;;\n" +
                "    '#!'*'/bin/sh'*|'#!'*'/bin/bash'*)\n" +
                "      /system/bin/sh \"${'$'}_cmd\" \"${'$'}@\"\n" +
                "      ;;\n" +
                "    *)\n" +
                "      _nr=\"${'$'}{METHINGS_NODE_ROOT:-${'$'}HOME/../node}\"\n" +
                "      LD_LIBRARY_PATH=\"${'$'}_nr/lib:${'$'}{LD_LIBRARY_PATH:-}\" \"${'$'}METHINGS_NATIVELIB/libnode.so\" \"${'$'}_cmd\" \"${'$'}@\"\n" +
                "      ;;\n" +
                "  esac\n" +
                "}\n" +
                "_methings_register_npm_bins() {\n" +
                "  _dir=\"${'$'}HOME/npm-prefix/bin\"\n" +
                "  [ -d \"${'$'}_dir\" ] || return 0\n" +
                "  for _p in \"${'$'}_dir\"/*; do\n" +
                "    [ -f \"${'$'}_p\" ] || continue\n" +
                "    _n=\"${'$'}{_p##*/}\"\n" +
                "    case \"${'$'}_n\" in\n" +
                "      *[!A-Za-z0-9._+-]*|'') continue;;\n" +
                "    esac\n" +
                "    eval \"alias ${'$'}_n='_methings_npm_run \\\"${'$'}HOME/npm-prefix/bin/${'$'}_n\\\"'\"\n" +
                "  done\n" +
                "}\n" +
                "npm() {\n" +
                "  command npm \"${'$'}@\"\n" +
                "  _rc=\"${'$'}?\"\n" +
                "  [ \"${'$'}_rc\" -eq 0 ] && _methings_register_npm_bins\n" +
                "  return \"${'$'}_rc\"\n" +
                "}\n" +
                "_methings_register_npm_bins\n"
        val needsWrite = !envFile.exists() || envFile.readText() != content
        if (needsWrite) {
            envFile.writeText(content)
        }
        envFile.setReadable(true, true)
        envFile.setWritable(true, true)
        envFile.setExecutable(false, false)
    }

    private fun ensureMkshEnvFile(userHome: File): File {
        val envFile = File(userHome, ".mkshrc")
        if (!envFile.exists()) {
            envFile.parentFile?.mkdirs()
            envFile.writeText("# me.things mksh env\n")
        }
        return envFile
    }

    /**
     * npm-installed CLI shims under user/npm-prefix/bin may occasionally lose +x,
     * which causes "Permission denied" when invoked from SSH sessions.
     */
    private fun ensureNpmPrefixBinExecutable(userHome: File) {
        try {
            val npmBin = File(userHome, "npm-prefix/bin")
            val entries = npmBin.listFiles() ?: return
            for (entry in entries) {
                if (!entry.isFile) continue
                entry.setReadable(true, true)
                entry.setWritable(true, true)
                entry.setExecutable(true, true)
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to normalize npm-prefix/bin permissions", ex)
        }
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
    }
}
