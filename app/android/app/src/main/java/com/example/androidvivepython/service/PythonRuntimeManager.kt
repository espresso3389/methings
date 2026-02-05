package jp.espresso3389.kugutz.service

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import android.content.Intent

class PythonRuntimeManager(private val context: Context) {
    private var runtimeThread: Thread? = null
    private val extractor = AssetExtractor(context)
    private val installer = PythonRuntimeInstaller(context)
    private val keyManager = SqlcipherKeyManager(context)
    private val lock = Any()
    private var pendingRestart = false
    private var stopping = false

    fun start(): Boolean {
        synchronized(lock) {
            if (runtimeThread != null && runtimeThread?.isAlive == true) {
                Log.i(TAG, "Start requested but runtime already running")
                return true
            }
            runtimeThread = null
            stopping = false
            pendingRestart = false
        }

        val serverDir = extractor.extractServerAssets()
        val keyFile = keyManager.ensureKeyFile()
        keyManager.cleanupLegacySecretsDir()
        val pythonHome = File(context.filesDir, "pyenv")
        val serverScript = File(context.filesDir, "server/app.py")

        if (!installer.ensureInstalled()) {
            Log.w(TAG, "Python runtime install failed")
            return false
        }

        if (!pythonHome.exists() || !serverScript.exists() || serverDir == null) {
            Log.w(TAG, "Python runtime not found. Expected $pythonHome and $serverScript")
            return false
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        PythonBridge.loadNativeLibs(nativeLibDir)

        runtimeThread = Thread {
            try {
                val result = PythonBridge.start(
                    pythonHome.absolutePath,
                    serverDir.absolutePath,
                    keyFile?.absolutePath,
                    nativeLibDir
                )
                Log.i(TAG, "Python bridge exited with code $result")
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to start Python service", ex)
            } finally {
                keyManager.deleteKeyFile()
                val shouldRestart: Boolean
                synchronized(lock) {
                    runtimeThread = null
                    shouldRestart = pendingRestart
                    pendingRestart = false
                    stopping = false
                }
                if (shouldRestart) {
                    sendHealthUpdate("starting")
                    start()
                } else {
                    sendHealthUpdate("offline")
                }
            }
        }.apply { isDaemon = true }

        runtimeThread?.start()
        sendHealthUpdate("starting")
        startHealthProbe()
        Log.i(TAG, "Python service start requested")
        return true
    }

    fun stop() {
        // Py_FinalizeEx is not safe on Android for this embedded runtime; avoid crashing the UI.
        Log.w(TAG, "Python shutdown requested; skipping Py_FinalizeEx to keep UI stable")
        runtimeThread = null
    }

    fun restartSoft(): Boolean {
        var shouldStart = false
        synchronized(lock) {
            if (stopping) {
                if (runtimeThread == null || runtimeThread?.isAlive != true) {
                    Log.i(TAG, "Restart requested while stopping but runtime stopped; starting now")
                    stopping = false
                    pendingRestart = false
                    shouldStart = true
                } else {
                    Log.i(TAG, "Restart requested while stopping; queueing")
                    pendingRestart = true
                    return true
                }
            }
            if (runtimeThread != null && runtimeThread?.isAlive == true) {
                Log.i(TAG, "Restart requested; stopping running Python")
                pendingRestart = true
                requestShutdown()
                return true
            }
        }
        Log.i(TAG, "Restart requested; starting Python")
        sendHealthUpdate("starting")
        return if (shouldStart) start() else start()
    }

    fun requestShutdown() {
        synchronized(lock) {
            if (stopping) return
            stopping = true
        }
        if (runtimeThread == null || runtimeThread?.isAlive != true) {
            Log.i(TAG, "Shutdown requested but Python not running")
            synchronized(lock) {
                stopping = false
            }
            sendHealthUpdate("offline")
            return
        }
        sendHealthUpdate("stopping")
        Thread {
            try {
                val conn = URL("http://127.0.0.1:8765/shutdown").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.doOutput = true
                conn.outputStream.use { it.write(ByteArray(0)) }
                conn.inputStream.use { }
                conn.disconnect()
                Log.i(TAG, "Shutdown request sent")
            } catch (ex: Exception) {
                Log.w(TAG, "Python shutdown request failed", ex)
            } finally {
                awaitShutdownCompletion()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun awaitShutdownCompletion() {
        Thread {
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                if (runtimeThread == null || runtimeThread?.isAlive != true) {
                    synchronized(lock) {
                        stopping = false
                    }
                    return@Thread
                }
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    break
                }
            }
            synchronized(lock) {
                stopping = false
            }
            if (runtimeThread != null && runtimeThread?.isAlive == true) {
                Log.w(TAG, "Shutdown timed out; Python thread still running")
                sendHealthUpdate("ok")
            } else {
                sendHealthUpdate("offline")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startHealthProbe() {
        Thread {
            repeat(8) { attempt ->
                try {
                    val conn = URL("http://127.0.0.1:8765/health")
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    if (code in 200..299) {
                        Log.i(TAG, "Python service health OK")
                        sendHealthUpdate("ok")
                        return@Thread
                    }
                    Log.w(TAG, "Health check failed (attempt ${attempt + 1}): $code")
                } catch (ex: Exception) {
                    Log.w(TAG, "Health check failed (attempt ${attempt + 1})", ex)
                }
                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            Log.e(TAG, "Python service health check failed after retries")
            sendHealthUpdate("offline")
        }.apply { isDaemon = true }.start()
    }

    private fun sendHealthUpdate(status: String) {
        val intent = Intent(ACTION_PYTHON_HEALTH)
        intent.setPackage(context.packageName)
        intent.putExtra(EXTRA_STATUS, status)
        context.sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "PythonRuntimeManager"
        const val ACTION_PYTHON_HEALTH = "jp.espresso3389.kugutz.PYTHON_HEALTH"
        const val EXTRA_STATUS = "status"
    }
}
