package com.example.androidvivepython.service

import android.content.Context
import android.util.Log
import java.io.File

class AssetExtractor(private val context: Context) {
    fun extractServerAssets(): File? {
        return try {
            val targetDir = File(context.filesDir, "server")
            targetDir.mkdirs()
            copyAssetDir("server", targetDir)
            targetDir
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to extract server assets", ex)
            null
        }
    }

    fun extractPythonRuntime(): File? {
        return try {
            val targetDir = File(context.filesDir, "pyenv")
            targetDir.mkdirs()
            copyAssetDir("pyenv", targetDir)
            targetDir
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to extract Python runtime", ex)
            null
        }
    }

    private fun copyAssetDir(assetPath: String, outDir: File) {
        val assetManager = context.assets
        val entries = assetManager.list(assetPath) ?: return
        if (entries.isEmpty()) {
            copyAssetFile(assetPath, outDir)
            return
        }
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childOut = File(outDir, entry)
            val childEntries = assetManager.list(childAssetPath)
            if (childEntries == null || childEntries.isEmpty()) {
                copyAssetFile(childAssetPath, outDir)
            } else {
                childOut.mkdirs()
                copyAssetDir(childAssetPath, childOut)
            }
        }
    }

    private fun copyAssetFile(assetPath: String, outDir: File) {
        val name = assetPath.substringAfterLast("/")
        val outFile = File(outDir, name)
        context.assets.open(assetPath).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    companion object {
        private const val TAG = "AssetExtractor"
    }
}
