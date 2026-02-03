package jp.espresso3389.kugutz.service

import android.content.Context
import android.util.Log
import java.io.File

class PythonRuntimeManager(private val context: Context) {
    private var process: Process? = null
    private val extractor = AssetExtractor(context)
    private val installer = PythonRuntimeInstaller(context)
    private val keyManager = SqlcipherKeyManager(context)

    fun start(): Boolean {
        if (process != null) {
            return true
        }

        val serverDir = extractor.extractServerAssets()
        val keyFile = keyManager.ensureKeyFile()

        // TODO: Replace these paths with Python-for-Android packaged runtime locations.
        val pythonHome = File(context.filesDir, "pyenv")
        val pythonBin = File(pythonHome, "bin/python")
        val serverScript = File(context.filesDir, "server/app.py")

        if (!pythonBin.exists()) {
            installer.ensureInstalled()
        }

        if (!pythonBin.exists() || !serverScript.exists() || serverDir == null) {
            Log.w(TAG, "Python runtime not found. Expected $pythonBin and $serverScript")
            return false
        }

        val builder = ProcessBuilder(
            pythonBin.absolutePath,
            serverScript.absolutePath
        )
        builder.environment()["PYTHONHOME"] = pythonHome.absolutePath
        if (keyFile != null) {
            builder.environment()["SQLCIPHER_KEY_FILE"] = keyFile.absolutePath
        }
        builder.redirectErrorStream(true)

        return try {
            process = builder.start()
            Log.i(TAG, "Python service started")
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to start Python service", ex)
            process = null
            false
        }
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    companion object {
        private const val TAG = "PythonRuntimeManager"
    }
}
