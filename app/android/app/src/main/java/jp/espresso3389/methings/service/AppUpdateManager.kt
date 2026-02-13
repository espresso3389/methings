package jp.espresso3389.methings.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class AppUpdateManager(private val context: Context) {

    data class ReleaseAsset(
        val name: String,
        val url: String,
        val size: Long,
    )

    data class ReleaseInfo(
        val tagName: String,
        val htmlUrl: String,
        val publishedAt: String,
        val body: String,
        val apk: ReleaseAsset?,
    )

    fun checkLatestRelease(): JSONObject {
        val currentVersion = currentVersionName()
        val release = fetchLatestRelease()
        val latestVersion = normalizeVersion(release.tagName)
        val hasUpdate = isNewerVersion(latestVersion, normalizeVersion(currentVersion))
        return JSONObject()
            .put("status", "ok")
            .put("current_version", currentVersion)
            .put("latest_tag", release.tagName)
            .put("latest_version", latestVersion)
            .put("has_update", hasUpdate)
            .put("published_at", release.publishedAt)
            .put("release_url", release.htmlUrl)
            .put("apk_name", release.apk?.name ?: "")
            .put("apk_size", release.apk?.size ?: 0L)
            .put("can_request_installs", canInstallPackages())
    }

    fun downloadAndStartInstall(): JSONObject {
        val release = fetchLatestRelease()
        val apk = release.apk ?: throw IllegalStateException("release_apk_not_found")
        val apkFile = downloadApk(apk, release.tagName)

        val canInstall = canRequestPackageInstalls()
        if (!canInstall) {
            return JSONObject()
                .put("status", "install_permission_required")
                .put("latest_tag", release.tagName)
                .put("apk_name", apk.name)
                .put("apk_path", apkFile.absolutePath)
                .put("message", "Allow installs from this app, then tap Install update again.")
        }

        startPackageInstaller(apkFile)
        return JSONObject()
            .put("status", "ok")
            .put("latest_tag", release.tagName)
            .put("apk_name", apk.name)
            .put("apk_path", apkFile.absolutePath)
    }

    fun canInstallPackages(): Boolean {
        return canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings() {
        openUnknownSourcesSettings()
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val url = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "methings/${currentVersionName()}")
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
        val htmlUrl = obj.optString("html_url", "").trim()
        val publishedAt = obj.optString("published_at", "").trim()
        val releaseBody = obj.optString("body", "")
        val assets = obj.optJSONArray("assets") ?: JSONArray()

        val apkAsset = pickApkAsset(assets)
        return ReleaseInfo(
            tagName = tagName,
            htmlUrl = htmlUrl,
            publishedAt = publishedAt,
            body = releaseBody,
            apk = apkAsset,
        )
    }

    private fun pickApkAsset(assets: JSONArray): ReleaseAsset? {
        val apks = mutableListOf<ReleaseAsset>()
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "").trim()
            val dl = a.optString("browser_download_url", "").trim()
            if (!name.lowercase(Locale.US).endsWith(".apk")) continue
            if (dl.isBlank()) continue
            apks.add(ReleaseAsset(name = name, url = dl, size = a.optLong("size", 0L)))
        }
        if (apks.isEmpty()) return null
        val preferred = apks.firstOrNull {
            val n = it.name.lowercase(Locale.US)
            n.contains("release") && !n.contains("debug")
        }
        return preferred ?: apks.first()
    }

    private fun downloadApk(asset: ReleaseAsset, tagName: String): File {
        val updateDir = File(context.filesDir, "user/updates")
        updateDir.mkdirs()
        val safeTag = tagName.trim().ifBlank { "latest" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(updateDir, "methings-$safeTag.apk")
        val tmpFile = File(updateDir, outFile.name + ".tmp")

        val conn = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "methings/${currentVersionName()}")
            setRequestProperty("Accept", "application/octet-stream")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText().take(500) } ?: ""
            throw IllegalStateException("apk_download_http_${code}:${err.ifBlank { "empty_error" }}")
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
        return outFile
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

    private fun openUnknownSourcesSettings() {
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

    private fun canRequestPackageInstalls(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    private fun currentVersionName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).versionName ?: "0.0.0"
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
            }
        } catch (_: Throwable) {
            "0.0.0"
        }
    }

    private fun normalizeVersion(v: String): String {
        return v.trim()
            .removePrefix("v")
            .substringBefore('-')
            .substringBefore('+')
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val lv = parseVersion(latest)
        val cv = parseVersion(current)
        if (lv == null || cv == null) return latest.isNotBlank() && latest != current
        val size = maxOf(lv.size, cv.size)
        for (i in 0 until size) {
            val a = if (i < lv.size) lv[i] else 0
            val b = if (i < cv.size) cv[i] else 0
            if (a > b) return true
            if (a < b) return false
        }
        return false
    }

    private fun parseVersion(v: String): List<Int>? {
        val t = v.trim()
        if (t.isBlank()) return null
        val out = mutableListOf<Int>()
        for (part in t.split('.')) {
            if (part.isBlank()) return null
            val n = part.toIntOrNull() ?: return null
            out.add(n)
        }
        return out
    }

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val OWNER = "espresso3389"
        private const val REPO = "methings"
    }
}
