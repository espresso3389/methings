package jp.espresso3389.methings.service

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    fun extractUiAssetsIfMissing(): File? {
        return try {
            val targetDir = File(context.filesDir, "user/www")
            if (targetDir.exists()) {
                val entries = targetDir.list()
                if (entries != null && entries.isNotEmpty()) {
                    val localVersion = File(targetDir, ".version").takeIf { it.exists() }?.readText()?.trim()
                    val assetVersion = readAssetText("www/.version")
                    if (assetVersion != null && assetVersion.isNotBlank() && assetVersion != localVersion) {
                        val result = resetUiAssets(
                            reason = "version_upgrade",
                            oldVersion = localVersion,
                            newVersion = assetVersion,
                        )
                        cleanupLegacyUiDir()
                        return result
                    }
                    cleanupLegacyUiDir()
                    return targetDir
                }
            }
            targetDir.mkdirs()
            copyAssetDir("www", targetDir)
            cleanupLegacyUiDir()
            targetDir
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to extract UI assets", ex)
            null
        }
    }

    /** Remove the old filesDir/www/ directory left over from before the user/www migration. */
    private fun cleanupLegacyUiDir() {
        try {
            val legacyDir = File(context.filesDir, "www")
            if (legacyDir.exists()) {
                deleteRecursive(legacyDir)
                Log.i(TAG, "Cleaned up legacy UI directory: ${legacyDir.absolutePath}")
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to clean up legacy UI directory", ex)
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
            // so they always match the current app version.  Delete-then-copy ensures stale
            // files from older versions are purged.
            for (sub in listOf("docs", "examples", "lib")) {
                if (assetDirExists("system/$sub")) {
                    val subDir = File(targetDir, sub)
                    subDir.deleteRecursively()
                    subDir.mkdirs()
                    copyAssetDirOverwrite("system/$sub", subDir)
                }
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
        val currentVersion = "3"
        val existingVersion = if (markerFile.exists()) markerFile.readText().trim() else ""
        if (existingVersion == currentVersion) return

        try {
            // v1/v2 legacy cleanup: remove old bundled doc/example/lib files from user dir.
            if (existingVersion < "2") {
                val legacyDocs = File(userDir, "docs")
                if (legacyDocs.isDirectory) {
                    val bundledDocNames = setOf(
                        "api_reference.md", "camera.md", "uvc.md", "usb.md", "ble.md",
                        "tts.md", "stt.md", "sensors.md", "viewer.md",
                        "vision.md", "permissions.md", "file_endpoints.md", "brain_journal.md",
                        "vault.md", "cloud_broker.md", "health.md", "ssh.md", "sshd.md",
                        "me_me.md", "me_sync.md", "me_sync_v3.md",
                        "recording.md", "media_stream.md", "relay_integrations.md"
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
            }

            // v3 migration: AGENTS.md/TOOLS.md are now split into system (read-only) + user
            // (editable). Reset user copies one final time to deliver the lightweight templates.
            // After v3, user AGENTS.md/TOOLS.md are never force-overwritten again — system
            // content lives in $sys/docs/ and is always current via extractSystemAssets().
            if (existingVersion < "3") {
                // Back up existing files so user customizations aren't lost.
                // The agent can detect *.bak.md and offer to merge custom content.
                for (name in listOf("AGENTS.md", "TOOLS.md")) {
                    val src = File(userDir, name)
                    if (src.exists() && src.length() > 0) {
                        val bak = File(userDir, name.replace(".md", ".bak.md"))
                        src.copyTo(bak, overwrite = true)
                    }
                }
                resetUserDefaults()
            }

            markerFile.writeText(currentVersion)
        } catch (ex: Exception) {
            Log.w(TAG, "Legacy cleanup incomplete", ex)
        }
    }

    fun resetUiAssets(
        reason: String = "manual_reset",
        oldVersion: String? = null,
        newVersion: String? = null,
    ): File? {
        return try {
            val root = context.filesDir
            val targetDir = File(root, "user/www")
            val tmpDir = File(root, "user/www.tmp")
            val backupRoot = File(root, "user/www.bak")
            val backupDir = File(backupRoot, timestampTag())

            if (tmpDir.exists()) {
                deleteRecursive(tmpDir)
            }
            tmpDir.mkdirs()
            copyAssetDir("www", tmpDir)

            backupRoot.mkdirs()
            if (targetDir.exists()) {
                if (backupDir.exists()) deleteRecursive(backupDir)
                if (!targetDir.renameTo(backupDir)) {
                    targetDir.copyRecursively(backupDir, overwrite = true)
                    deleteRecursive(targetDir)
                }
            }

            if (!tmpDir.renameTo(targetDir)) {
                // Fallback: try to restore previous version.
                if (backupDir.exists()) {
                    if (!backupDir.renameTo(targetDir)) {
                        backupDir.copyRecursively(targetDir, overwrite = true)
                    }
                }
                deleteRecursive(tmpDir)
                return null
            }

            if (backupDir.exists()) {
                writeUiBackupMeta(backupDir, reason, oldVersion, newVersion)
                appendAgentUiNotice(backupDir.name, reason, oldVersion, newVersion)
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

    private fun timestampTag(): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return fmt.format(Date()) + "_" + System.currentTimeMillis().toString()
    }

    private fun writeUiBackupMeta(
        backupDir: File,
        reason: String,
        oldVersion: String?,
        newVersion: String?,
    ) {
        runCatching {
            val meta = buildString {
                append("reason=").append(reason).append('\n')
                append("old_version=").append(oldVersion ?: "").append('\n')
                append("new_version=").append(newVersion ?: "").append('\n')
                append("created_at_ms=").append(System.currentTimeMillis()).append('\n')
            }
            File(backupDir, ".meta").writeText(meta, Charsets.UTF_8)
        }
    }

    private fun appendAgentUiNotice(
        backupTag: String,
        reason: String,
        oldVersion: String?,
        newVersion: String?,
    ) {
        runCatching {
            val userDir = File(context.filesDir, "user")
            userDir.mkdirs()
            val noticesFile = File(userDir, "AGENT_NOTICES.md")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val oldV = (oldVersion ?: "").ifBlank { "unknown" }
            val newV = (newVersion ?: "").ifBlank { "unknown" }
            val line = "- [$ts] UI assets replaced (`$reason`). old=`$oldV` new=`$newV`; backup at `www.bak/$backupTag/`.\n"
            val existing = if (noticesFile.exists()) noticesFile.readText(Charsets.UTF_8) else ""
            val merged = (existing + line)
                .lines()
                .filter { it.isNotBlank() }
                .takeLast(50)
                .joinToString("\n", postfix = "\n")
            noticesFile.writeText(merged, Charsets.UTF_8)
        }
    }

    companion object {
        private const val TAG = "AssetExtractor"
    }
}
