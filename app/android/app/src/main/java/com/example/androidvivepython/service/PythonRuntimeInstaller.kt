package jp.espresso3389.kugutz.service

import android.content.Context
import android.util.Log
import java.io.File

class PythonRuntimeInstaller(private val context: Context) {
    private val extractor = AssetExtractor(context)

    fun ensureInstalled(): Boolean {
        val pythonHome = File(context.filesDir, "pyenv")
        val pythonBin = File(pythonHome, "bin/python")
        if (pythonBin.exists()) {
            return true
        }

        // Attempt to extract packaged runtime assets under assets/pyenv.
        return try {
            extractor.extractPythonRuntime()
            pythonBin.exists()
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to install Python runtime", ex)
            false
        }
    }

    companion object {
        private const val TAG = "PythonRuntimeInstaller"
    }
}
