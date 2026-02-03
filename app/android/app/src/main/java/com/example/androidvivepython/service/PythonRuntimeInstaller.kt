package jp.espresso3389.kugutz.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

class PythonRuntimeInstaller(private val context: Context) {
    private val extractor = AssetExtractor(context)

    fun ensureInstalled(): Boolean {
        val pythonHome = File(context.filesDir, "pyenv")
        val stdlibZip = File(pythonHome, "stdlib.zip")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedVersion = prefs.getLong(KEY_RUNTIME_VERSION, -1)
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to read app version", ex)
            -1L
        }
        val assetStamp = readAssetStamp()
        val installedStamp = readInstalledStamp(pythonHome)

        val needsInstall = !stdlibZip.exists() ||
            storedVersion != currentVersion ||
            (assetStamp != null && assetStamp != installedStamp)
        if (!needsInstall) {
            return true
        }

        // Attempt to extract packaged runtime assets under assets/pyenv.
        return try {
            if (pythonHome.exists()) {
                pythonHome.deleteRecursively()
            }
            extractor.extractPythonRuntime()
            val ok = stdlibZip.exists()
            if (ok && currentVersion != -1L) {
                prefs.edit().putLong(KEY_RUNTIME_VERSION, currentVersion).apply()
            }
            if (ok && assetStamp != null) {
                writeInstalledStamp(pythonHome, assetStamp)
            }
            ok
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to install Python runtime", ex)
            false
        }
    }

    private fun readAssetStamp(): String? {
        return try {
            context.assets.open("pyenv/.runtime_stamp").use { input ->
                BufferedReader(InputStreamReader(input)).readLine()?.trim()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readInstalledStamp(pythonHome: File): String? {
        val stampFile = File(pythonHome, ".runtime_stamp")
        return try {
            if (!stampFile.exists()) {
                null
            } else {
                stampFile.readText().trim()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeInstalledStamp(pythonHome: File, stamp: String) {
        try {
            File(pythonHome, ".runtime_stamp").writeText(stamp)
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to write runtime stamp", ex)
        }
    }

    companion object {
        private const val TAG = "PythonRuntimeInstaller"
        private const val PREFS_NAME = "python_runtime"
        private const val KEY_RUNTIME_VERSION = "runtime_version"
    }
}
