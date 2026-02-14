package jp.espresso3389.methings.service

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

    fun extractWheelhouseForCurrentAbi(): File? {
        return try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return null
            val rootDir = File(context.filesDir, "wheelhouse/$abi")
            val bundledDir = File(rootDir, "bundled")
            // Keep user-downloaded wheels across app updates. Only reset bundled wheels.
            if (bundledDir.exists()) {
                bundledDir.deleteRecursively()
            }
            bundledDir.mkdirs()
            var copiedAny = false

            // Common wheels (pure-Python facades).
            val commonPath = "wheels/common"
            val commonEntries = context.assets.list(commonPath)
            if (commonEntries != null && commonEntries.isNotEmpty()) {
                copyAssetDir(commonPath, bundledDir)
                copiedAny = true
            }

            // ABI-specific wheels (native payloads like opencv_python).
            val abiPath = "wheels/$abi"
            val abiEntries = context.assets.list(abiPath)
            if (abiEntries != null && abiEntries.isNotEmpty()) {
                copyAssetDir(abiPath, bundledDir)
                copiedAny = true
            }

            if (!copiedAny) {
                // Nothing packaged for this ABI (and no common wheels).
                return null
            }
            bundledDir
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to extract wheelhouse", ex)
            null
        }
    }

    fun extractNodeAssetsIfMissing(): File? {
        return try {
            val assetRoot = "node"
            if (!assetDirExists(assetRoot)) {
                return null
            }
            val targetDir = File(context.filesDir, "node")
            if (targetDir.exists()) {
                val localVersion = File(targetDir, ".version").takeIf { it.exists() }?.readText()?.trim()
                val assetVersion = readAssetText("$assetRoot/.version")?.trim()
                if (assetVersion != null && assetVersion.isNotBlank() && assetVersion != localVersion) {
                    // Reset on version change so npm/corepack stay in sync with the bundled node binary.
                    targetDir.deleteRecursively()
                }
                // If the dir still has content after potential reset, keep it.
                val entries = targetDir.list()
                if (entries != null && entries.isNotEmpty()) {
                    return targetDir
                }
            }
            targetDir.mkdirs()
            copyAssetDir(assetRoot, targetDir)
            targetDir
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to extract Node assets", ex)
            null
        }
    }

    fun extractDropbearIfMissing(): File? {
        return try {
            val targetDir = File(context.filesDir, "bin")
            targetDir.mkdirs()
            val outFile = File(targetDir, "dropbear")
            if (outFile.exists()) {
                // Outbound ssh/scp are provided via native libs (libdbclient.so/libscp.so).
                // Clean up old extracted copies (Android commonly blocks exec from app data dirs).
                cleanupExtractedDropbearClientTools(targetDir)
                extractDropbearKeyIfMissing()
                return outFile
            }
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return null
            val assetPath = "bin/$abi/dropbear"
            if (!assetExists(assetPath)) {
                Log.w(TAG, "Dropbear binary not found in assets for ABI $abi")
                return null
            }
            copyAssetFile(assetPath, outFile)
            outFile.setExecutable(true, true)
            cleanupExtractedDropbearClientTools(targetDir)
            extractDropbearKeyIfMissing()
            outFile
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to extract Dropbear binary", ex)
            null
        }
    }

    private fun cleanupExtractedDropbearClientTools(binDir: File) {
        // If these exist under files/bin, they may shadow the native-lib backed tools and then fail
        // with EACCES on many Android builds (noexec / SELinux). Prefer native libs instead.
        for (name in listOf("ssh", "scp", "dbclient")) {
            try {
                val f = File(binDir, name)
                if (f.exists()) {
                    f.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun extractDropbearKeyIfMissing(): File? {
        return try {
            val targetDir = File(context.filesDir, "bin")
            targetDir.mkdirs()
            val outFile = File(targetDir, "dropbearkey")
            if (outFile.exists()) {
                return outFile
            }
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return null
            val assetPath = "bin/$abi/dropbearkey"
            if (!assetExists(assetPath)) {
                Log.w(TAG, "Dropbearkey binary not found in assets for ABI $abi")
                return null
            }
            copyAssetFile(assetPath, outFile)
            outFile.setExecutable(true, true)
            outFile
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to extract Dropbearkey binary", ex)
            null
        }
    }

    fun extractUiAssetsIfMissing(): File? {
        return try {
            val targetDir = File(context.filesDir, "www")
            if (targetDir.exists()) {
                val entries = targetDir.list()
                if (entries != null && entries.isNotEmpty()) {
                    val localVersion = File(targetDir, ".version").takeIf { it.exists() }?.readText()?.trim()
                    val assetVersion = readAssetText("www/.version")
                    if (assetVersion != null && assetVersion.isNotBlank() && assetVersion != localVersion) {
                        return resetUiAssets()
                    }
                    return targetDir
                }
            }
            targetDir.mkdirs()
            copyAssetDir("www", targetDir)
            targetDir
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to extract UI assets", ex)
            null
        }
    }

    fun extractUserDefaultsIfMissing(): File? {
        return try {
            val targetDir = File(context.filesDir, "user")
            targetDir.mkdirs()

            // Only seed defaults if missing, so we never overwrite user edits.
            val agentFile = File(targetDir, "AGENTS.md")
            val toolsFile = File(targetDir, "TOOLS.md")
            if (!agentFile.exists() && assetExists("user_defaults/AGENTS.md")) {
                copyAssetFile("user_defaults/AGENTS.md", agentFile)
            }
            if (!toolsFile.exists() && assetExists("user_defaults/TOOLS.md")) {
                copyAssetFile("user_defaults/TOOLS.md", toolsFile)
            }

            // Create empty agent workspace directory for the agent's own notes.
            val docsDir = File(targetDir, "docs")
            if (!docsDir.exists()) {
                docsDir.mkdirs()
            }

            // System reference docs, examples, and lib are now in files/system/ (always overwritten).
            extractSystemAssets()

            // One-time cleanup: remove old bundled files that were previously in user/ but are now
            // served from system/. Agent-created files are left untouched.
            cleanupLegacyUserBundledFiles(targetDir)

            targetDir
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to extract user defaults", ex)
            null
        }
    }

    fun extractSystemAssets(): File? {
        return try {
            val targetDir = File(context.filesDir, "system")
            targetDir.mkdirs()

            // Always overwrite system docs/examples/lib from APK assets on every app start,
            // so they always match the current app version.
            if (assetDirExists("system/docs")) {
                val docsDir = File(targetDir, "docs")
                docsDir.mkdirs()
                copyAssetDirOverwrite("system/docs", docsDir)
            }
            if (assetDirExists("system/examples")) {
                val examplesDir = File(targetDir, "examples")
                examplesDir.mkdirs()
                copyAssetDirOverwrite("system/examples", examplesDir)
            }
            if (assetDirExists("system/lib")) {
                val libDir = File(targetDir, "lib")
                libDir.mkdirs()
                copyAssetDirOverwrite("system/lib", libDir)
            }
            targetDir
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to extract system assets", ex)
            null
        }
    }

    private fun cleanupLegacyUserBundledFiles(userDir: File) {
        // Known filenames that were previously bundled under user/docs, user/examples, user/lib.
        // Remove them so the agent isn't confused by stale copies alongside the new $sys/ prefix.
        val markerFile = File(userDir, ".system_docs_migrated")
        if (markerFile.exists()) return

        try {
            val legacyDocs = File(userDir, "docs")
            if (legacyDocs.isDirectory) {
                val bundledDocNames = setOf(
                    "api_reference.md", "camera.md", "uvc.md", "usb.md", "ble.md",
                    "tts.md", "stt.md", "sensors.md", "viewer.md",
                    "vision.md", "permissions.md", "file_endpoints.md", "brain_journal.md",
                    "vault.md", "cloud_broker.md", "health.md", "ssh.md", "sshd.md",
                    "me_me.md", "me_sync.md", "me_sync_v3.md"
                )
                for (name in bundledDocNames) {
                    File(legacyDocs, name).takeIf { it.exists() }?.delete()
                }
                // Remove docs dir if now empty (agent workspace will be recreated above).
                if (legacyDocs.list()?.isEmpty() == true) {
                    legacyDocs.delete()
                }
            }

            val legacyExamples = File(userDir, "examples")
            if (legacyExamples.isDirectory) {
                deleteRecursive(legacyExamples)
            }

            val legacyLib = File(userDir, "lib")
            if (legacyLib.isDirectory) {
                deleteRecursive(legacyLib)
            }

            // Force-refresh AGENTS.md/TOOLS.md so old docs/ references become $sys/ paths.
            resetUserDefaults()

            markerFile.writeText("1")
        } catch (ex: Exception) {
            Log.w(TAG, "Legacy cleanup incomplete", ex)
        }
    }

    fun resetUiAssets(): File? {
        return try {
            val root = context.filesDir
            val targetDir = File(root, "www")
            val tmpDir = File(root, "www.tmp")
            val backupDir = File(root, "www.bak")

            if (tmpDir.exists()) {
                deleteRecursive(tmpDir)
            }
            tmpDir.mkdirs()
            copyAssetDir("www", tmpDir)

            if (backupDir.exists()) {
                deleteRecursive(backupDir)
            }
            if (targetDir.exists()) {
                if (!targetDir.renameTo(backupDir)) {
                    deleteRecursive(targetDir)
                }
            }

            if (!tmpDir.renameTo(targetDir)) {
                // Fallback: try to restore previous version.
                if (backupDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                deleteRecursive(tmpDir)
                return null
            }

            if (backupDir.exists()) {
                deleteRecursive(backupDir)
            }
            targetDir
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to reset UI assets", ex)
            null
        }
    }

    fun resetUserDefaults(): File? {
        return try {
            val targetDir = File(context.filesDir, "user")
            targetDir.mkdirs()

            // Overwrite agent operational docs (explicit user action).
            // System reference docs (docs/, examples/, lib/) live in files/system/ and are
            // always kept current by extractSystemAssets() — no reset needed here.
            val agentFile = File(targetDir, "AGENTS.md")
            val toolsFile = File(targetDir, "TOOLS.md")
            if (assetExists("user_defaults/AGENTS.md")) {
                copyAssetFile("user_defaults/AGENTS.md", agentFile)
            }
            if (assetExists("user_defaults/TOOLS.md")) {
                copyAssetFile("user_defaults/TOOLS.md", toolsFile)
            }

            targetDir
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to reset user defaults", ex)
            null
        }
    }

    private fun copyAssetDir(assetPath: String, outDir: File) {
        val assetManager = context.assets
        val entries = assetManager.list(assetPath) ?: return
        if (entries.isEmpty()) {
            val outFile = File(outDir, assetPath.substringAfterLast("/"))
            copyAssetFile(assetPath, outFile)
            return
        }
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childEntries = assetManager.list(childAssetPath)
            if (childEntries == null || childEntries.isEmpty()) {
                copyAssetFile(childAssetPath, File(outDir, entry))
            } else {
                val childOut = File(outDir, entry)
                childOut.mkdirs()
                copyAssetDir(childAssetPath, childOut)
            }
        }
    }

    private fun copyAssetDirOverwrite(assetPath: String, outDir: File) {
        val assetManager = context.assets
        val entries = assetManager.list(assetPath) ?: return
        if (entries.isEmpty()) {
            val outFile = File(outDir, assetPath.substringAfterLast("/"))
            copyAssetFile(assetPath, outFile)
            return
        }
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childEntries = assetManager.list(childAssetPath)
            if (childEntries == null || childEntries.isEmpty()) {
                copyAssetFile(childAssetPath, File(outDir, entry))
            } else {
                val childOut = File(outDir, entry)
                childOut.mkdirs()
                copyAssetDirOverwrite(childAssetPath, childOut)
            }
        }
    }

    private fun copyAssetDirMissingOnly(assetPath: String, outDir: File) {
        val assetManager = context.assets
        val entries = assetManager.list(assetPath) ?: return
        if (entries.isEmpty()) {
            val outFile = File(outDir, assetPath.substringAfterLast("/"))
            if (!outFile.exists()) {
                copyAssetFile(assetPath, outFile)
            }
            return
        }
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childEntries = assetManager.list(childAssetPath)
            if (childEntries == null || childEntries.isEmpty()) {
                val outFile = File(outDir, entry)
                if (!outFile.exists()) {
                    copyAssetFile(childAssetPath, outFile)
                }
            } else {
                val childOut = File(outDir, entry)
                childOut.mkdirs()
                copyAssetDirMissingOnly(childAssetPath, childOut)
            }
        }
    }

    private fun copyAssetFile(assetPath: String, outFile: File) {
        outFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun assetExists(assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun assetDirExists(assetPath: String): Boolean {
        return try {
            val entries = context.assets.list(assetPath)
            entries != null
        } catch (_: Exception) {
            false
        }
    }

    private fun readAssetText(assetPath: String): String? {
        return try {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursive(child)
            }
        }
        file.delete()
    }

    companion object {
        private const val TAG = "AssetExtractor"
    }
}
