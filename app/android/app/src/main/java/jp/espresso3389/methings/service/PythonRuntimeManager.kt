package jp.espresso3389.methings.service

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import android.content.Intent
import jp.espresso3389.methings.perm.InstallIdentity
import org.json.JSONObject

class PythonRuntimeManager(private val context: Context) {
    private var runtimeThread: Thread? = null
    private val extractor = AssetExtractor(context)
    private val installer = PythonRuntimeInstaller(context)
    private val installIdentity = InstallIdentity(context)
    private val lock = Any()
    private val statusLock = Any()
    private var status: String = "offline"
    private var pendingRestart = false
    private var stopping = false
    private var startInProgress = false
    private val bootstrapLock = Any()
    private var bootstrapInProgress = false

    fun startWorker(): Boolean {
        synchronized(lock) {
            if (startInProgress) {
                Log.i(TAG, "Start requested while start already in progress")
                return true
            }
            if (runtimeThread != null && runtimeThread?.isAlive == true) {
                Log.i(TAG, "Start requested but runtime already running")
                return true
            }
            startInProgress = true
            runtimeThread = null
            stopping = false
            pendingRestart = false
        }

        val serverDir = extractor.extractServerAssets()
        val pythonHome = File(context.filesDir, "pyenv")
        val serverScript = File(context.filesDir, "server/app.py")

        if (!installer.ensureInstalled()) {
            Log.w(TAG, "Python runtime install failed")
            synchronized(lock) {
                startInProgress = false
            }
            return false
        }

        if (!pythonHome.exists() || !serverScript.exists() || serverDir == null) {
            Log.w(TAG, "Python runtime not found. Expected $pythonHome and $serverScript")
            synchronized(lock) {
                startInProgress = false
            }
            return false
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        PythonBridge.loadNativeLibs(nativeLibDir)
        try {
            android.system.Os.setenv("METHINGS_IDENTITY", installIdentity.get(), true)
            android.system.Os.setenv("METHINGS_NATIVELIB", nativeLibDir, true)
            android.system.Os.setenv("METHINGS_PYTHON_EXE", File(nativeLibDir, "libmethingspy.so").absolutePath, true)
            android.system.Os.setenv("METHINGS_PYENV", pythonHome.absolutePath, true)
        } catch (_: Throwable) {}

        runtimeThread = Thread {
            try {
                val result = PythonBridge.start(
                    pythonHome.absolutePath,
                    serverDir.absolutePath,
                    null,
                    nativeLibDir
                )
                Log.i(TAG, "Python bridge exited with code $result")
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to start Python service", ex)
            } finally {
                val shouldRestart: Boolean
                synchronized(lock) {
                    runtimeThread = null
                    shouldRestart = pendingRestart
                    pendingRestart = false
                    stopping = false
                    startInProgress = false
                }
                if (shouldRestart) {
                    updateStatus("starting")
                    startWorker()
                } else {
                    updateStatus("offline")
                }
            }
        }.apply { isDaemon = true }

        runtimeThread?.start()
        updateStatus("starting")
        startHealthProbe()
        Log.i(TAG, "Python service start requested")
        return true
    }

    fun stop() {
        Log.w(TAG, "Python shutdown requested; skipping Py_FinalizeEx to keep UI stable")
        runtimeThread = null
        updateStatus("offline")
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
        updateStatus("starting")
        return if (shouldStart) startWorker() else startWorker()
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
            updateStatus("offline")
            return
        }
        updateStatus("stopping")
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
                updateStatus("ok")
            } else {
                updateStatus("offline")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startHealthProbe() {
        Thread {
            repeat(8) { attempt ->
                try {
                    if (isWorkerResponding()) {
                        Log.i(TAG, "Python service health OK")
                        updateStatus("ok")
                        ensureBundledPackagesInstalledAsync()
                        return@Thread
                    }
                    Log.w(TAG, "Health check failed (attempt ${attempt + 1})")
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
            updateStatus("offline")
        }.apply { isDaemon = true }.start()
    }

    private fun isWorkerResponding(): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$WORKER_PORT/health")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureBundledPackagesInstalledAsync() {
        synchronized(bootstrapLock) {
            if (bootstrapInProgress) return
            bootstrapInProgress = true
        }

        Thread {
            try {
                val currentVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                } catch (_: Exception) {
                    -1L
                }
                if (currentVersion == -1L) return@Thread

                val desiredSpec = bundledBootstrapSpec()
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val storedVersion = prefs.getLong(KEY_BUNDLED_BOOTSTRAP_VERSION, -1L)
                val storedSpec = prefs.getString(KEY_BUNDLED_BOOTSTRAP_SPEC, "") ?: ""
                if (storedVersion == currentVersion && storedSpec == desiredSpec) return@Thread

                val wheelhouse = WheelhousePaths.forCurrentAbi(context)?.also { it.ensureDirs() } ?: return@Thread

                val args = mutableListOf(
                    "install",
                    "--disable-pip-version-check",
                    "--no-input",
                    "--no-index",
                )
                args.addAll(wheelhouse.findLinksArgs())
                args.addAll(
                    listOf(
                        "--only-binary=:all:",
                        "--prefer-binary",
                        "--no-deps",
                        "--upgrade",
                        "pyusb",
                        "pyuvc",
                        "pupil-labs-uvc",
                        "opencv-python",
                    )
                )

                val result = workerShellExec("pip", args.joinToString(" "), "")
                val outFile = File(context.filesDir, "pyenv/bootstrap_pip.log")
                runCatching { outFile.parentFile?.mkdirs() }
                runCatching { outFile.writeText(result.optString("output", "")) }

                val rc = result.optInt("code", 1)
                if (rc == 0) {
                    prefs.edit()
                        .putLong(KEY_BUNDLED_BOOTSTRAP_VERSION, currentVersion)
                        .putString(KEY_BUNDLED_BOOTSTRAP_SPEC, desiredSpec)
                        .apply()
                    Log.i(TAG, "Bundled pip bootstrap OK ($desiredSpec)")
                } else {
                    Log.w(TAG, "Bundled pip bootstrap failed rc=$rc (see ${outFile.absolutePath})")
                }
            } finally {
                synchronized(bootstrapLock) {
                    bootstrapInProgress = false
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun bundledBootstrapSpec(): String {
        return listOf(
            "pyusb",
            "pyuvc",
            "pupil-labs-uvc",
            "opencv-python",
        ).joinToString(",")
    }

    private fun workerShellExec(cmd: String, args: String, cwd: String): JSONObject {
        val payload = JSONObject()
            .put("cmd", cmd)
            .put("args", args)
            .put("cwd", cwd)
        val conn = (URL("http://127.0.0.1:$WORKER_PORT/shell/exec").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val body = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        return JSONObject(body.ifBlank { "{}" })
    }

    fun getStatus(): String {
        val current = synchronized(statusLock) { status }
        if (current == "starting" || current == "stopping") {
            return current
        }
        val normalized = if (isWorkerResponding()) "ok" else "offline"
        if (normalized != current) {
            updateStatus(normalized)
        }
        return normalized
    }

    fun ensureWorkerForShell(): Boolean {
        if (getStatus() == "ok") return true
        return startWorker()
    }

    private fun updateStatus(status: String) {
        synchronized(statusLock) {
            this.status = status
        }
        val intent = Intent(ACTION_WORKER_HEALTH)
        intent.setPackage(context.packageName)
        intent.putExtra(EXTRA_STATUS, status)
        context.sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "PythonRuntimeManager"
        private const val PREFS_NAME = "python_runtime"
        private const val KEY_BUNDLED_BOOTSTRAP_VERSION = "bundled_bootstrap_version"
        private const val KEY_BUNDLED_BOOTSTRAP_SPEC = "bundled_bootstrap_spec"
        const val WORKER_PORT = 8776
        const val ACTION_WORKER_HEALTH = "jp.espresso3389.methings.WORKER_HEALTH"
        const val EXTRA_STATUS = "status"
    }
}
