package jp.espresso3389.methings.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale

class TermuxManager(private val context: Context) {

    fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isTermuxReady(): Boolean {
        if (!isTermuxInstalled()) return false
        // Probe the worker port as readiness indicator
        return isPortOpen(WORKER_PORT)
    }

    fun isSshdRunning(): Boolean {
        return isPortOpen(TERMUX_SSHD_PORT)
    }

    fun hasRunCommandPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, RUN_COMMAND_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun launchTermux() {
        val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Execute a command in Termux via the RUN_COMMAND intent.
     * @throws SecurityException if RUN_COMMAND permission is not granted.
     */
    fun runCommand(command: String, background: Boolean = true) {
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "$TERMUX_PACKAGE.app.RunCommandService")
            action = "$TERMUX_PACKAGE.RUN_COMMAND"
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_PATH", "/data/data/$TERMUX_PACKAGE/files/usr/bin/bash")
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND", background)
        }
        context.startForegroundService(intent)
    }

    fun startWorker() {
        runCommand("cd ~/methings/server && exec python worker.py")
    }

    fun stopWorker() {
        Thread {
            try {
                val conn = URL("http://127.0.0.1:$WORKER_PORT/shutdown").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.doOutput = true
                conn.outputStream.use { it.write(ByteArray(0)) }
                conn.inputStream.use { }
                conn.disconnect()
            } catch (ex: Exception) {
                Log.w(TAG, "Worker shutdown request failed", ex)
            }
        }.apply { isDaemon = true }.start()
    }

    fun updateServerCode() {
        runCommand("mkdir -p ~/methings/server && curl -sf http://127.0.0.1:33389/termux/server.tar.gz | tar xz -C ~/methings/server")
    }

    fun startSshd() {
        runCommand("sshd")
    }

    fun stopSshd() {
        runCommand("pkill sshd")
    }

    fun getBootstrapScript(): String {
        return try {
            context.assets.open("termux/bootstrap.sh").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            DEFAULT_BOOTSTRAP
        }
    }

    fun checkTermuxRelease(): JSONObject {
        val url = URL("https://api.github.com/repos/termux/termux-app/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "methings")
        }
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText().take(500) } ?: ""
            throw IllegalStateException("github_release_http_${code}:${err.ifBlank { "empty_error" }}")
        }

        val obj = JSONObject(body)
        val tagName = obj.optString("tag_name", "").trim()
        if (tagName.isBlank()) throw IllegalStateException("release_tag_missing")
        val assets = obj.optJSONArray("assets") ?: JSONArray()
        val apk = pickTermuxApkAsset(assets)

        return JSONObject()
            .put("status", "ok")
            .put("tag_name", tagName)
            .put("apk_name", apk?.getString("name") ?: "")
            .put("apk_size", apk?.optLong("size", 0L) ?: 0L)
            .put("apk_url", apk?.optString("url", "") ?: "")
            .put("can_request_installs", canInstallPackages())
    }

    fun downloadAndInstallTermux(): JSONObject {
        val releaseInfo = checkTermuxRelease()
        val apkUrl = releaseInfo.optString("apk_url", "").trim()
        if (apkUrl.isBlank()) throw IllegalStateException("termux_apk_not_found")
        val tagName = releaseInfo.optString("tag_name", "").trim()
        val apkName = releaseInfo.optString("apk_name", "")

        val updateDir = File(context.filesDir, "user/updates")
        updateDir.mkdirs()
        val safeTag = tagName.ifBlank { "latest" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(updateDir, "termux-$safeTag.apk")
        val tmpFile = File(updateDir, outFile.name + ".tmp")

        val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "methings")
            setRequestProperty("Accept", "application/octet-stream")
        }
        val dlCode = conn.responseCode
        if (dlCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText().take(500) } ?: ""
            throw IllegalStateException("apk_download_http_${dlCode}:${err.ifBlank { "empty_error" }}")
        }
        conn.inputStream.use { input ->
            FileOutputStream(tmpFile).use { output ->
                input.copyTo(output)
            }
        }
        if (!tmpFile.renameTo(outFile)) {
            tmpFile.delete()
            throw IllegalStateException("apk_rename_failed")
        }

        if (!canInstallPackages()) {
            return JSONObject()
                .put("status", "install_permission_required")
                .put("tag_name", tagName)
                .put("apk_name", apkName)
                .put("apk_path", outFile.absolutePath)
                .put("message", "Allow installs from this app, then tap Install Termux again.")
        }

        startPackageInstaller(outFile)
        return JSONObject()
            .put("status", "ok")
            .put("tag_name", tagName)
            .put("apk_name", apkName)
            .put("apk_path", outFile.absolutePath)
    }

    fun canInstallPackages(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Failed to open unknown-sources settings", it) }
    }

    private fun pickTermuxApkAsset(assets: JSONArray): JSONObject? {
        val apks = mutableListOf<JSONObject>()
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "").trim()
            val dl = a.optString("browser_download_url", "").trim()
            if (!name.lowercase(Locale.US).endsWith(".apk")) continue
            if (dl.isBlank()) continue
            apks.add(JSONObject()
                .put("name", name)
                .put("url", dl)
                .put("size", a.optLong("size", 0L)))
        }
        if (apks.isEmpty()) return null

        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull()?.lowercase(Locale.US) ?: ""
        // Try arch-specific match first
        val abiMatch = if (primaryAbi.isNotBlank()) {
            apks.firstOrNull { it.getString("name").lowercase(Locale.US).contains(primaryAbi) }
        } else null
        if (abiMatch != null) return abiMatch
        // Fallback to universal
        val universal = apks.firstOrNull { it.getString("name").lowercase(Locale.US).contains("universal") }
        if (universal != null) return universal
        // Fallback to first APK
        return apks.first()
    }

    private fun startPackageInstaller(apkFile: File) {
        val authority = context.packageName + ".fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun isPortOpen(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 300)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "TermuxManager"
        const val TERMUX_PACKAGE = "com.termux"
        const val WORKER_PORT = 8776
        const val TERMUX_SSHD_PORT = 8022
        const val RUN_COMMAND_PERMISSION = "$TERMUX_PACKAGE.permission.RUN_COMMAND"
        const val TERMUX_RELEASES_URL = "https://github.com/termux/termux-app/releases"

        private const val DEFAULT_BOOTSTRAP = """#!/data/data/com.termux/files/usr/bin/bash
# methings Termux bootstrap
set -e

# 1. Enable external app access (needed for RUN_COMMAND intent)
mkdir -p ~/.termux
grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null || \
  echo 'allow-external-apps=true' >> ~/.termux/termux.properties
termux-reload-settings

# 2. Install system packages
pkg update -y && pkg install -y python openssh

# 3. Download server code from the app
mkdir -p ~/methings/server
curl -sf http://127.0.0.1:33389/termux/server.tar.gz | tar xz -C ~/methings/server

# 4. Install Python dependencies from requirements.txt
# Use extra index for prebuilt pydantic-core wheels (Rust compilation fails on Termux)
pip install --extra-index-url https://eutalix.github.io/android-pydantic-core/ \
  -r ~/methings/server/requirements.txt

echo ""
echo "Bootstrap complete!"
echo "The app can now start the agent worker automatically."
echo "To start manually: cd ~/methings/server && python worker.py"
"""
    }
}
