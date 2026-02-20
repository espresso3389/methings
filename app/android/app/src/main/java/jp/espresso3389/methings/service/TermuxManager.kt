package jp.espresso3389.methings.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

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

    fun launchTermux() {
        val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

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
