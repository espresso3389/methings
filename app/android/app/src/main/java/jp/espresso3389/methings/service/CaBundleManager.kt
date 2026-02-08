package jp.espresso3389.methings.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class CaBundleManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun caDir(): File = File(context.filesDir, "protected/ca")

    fun caBundleFile(): File = File(caDir(), "cacert.pem")

    fun caMetaFile(): File = File(caDir(), "cacert.meta.json")

    fun caLogFile(): File = File(caDir(), "ca_update.log")

    fun ensureSeededFromPyenv(pyenvDir: File) {
        val dir = caDir()
        dir.mkdirs()

        val dst = caBundleFile()
        if (dst.exists() && dst.length() > 0) {
            return
        }

        val certifi = File(pyenvDir, "site-packages/certifi/cacert.pem")
        if (!certifi.exists()) {
            appendLog("seed_missing_certifi", "path=${certifi.absolutePath}")
            return
        }

        try {
            copyAtomic(certifi, dst)
            appendLog("seeded_from_certifi", "size=${dst.length()}")
        } catch (ex: Throwable) {
            appendLog("seed_failed", "error=${ex.javaClass.simpleName}:${ex.message}")
        }
    }

    fun updateIfDue(force: Boolean = false): UpdateResult {
        if (!prefs.getBoolean(KEY_AUTO_UPDATE_ENABLED, true)) {
            return UpdateResult.skipped("disabled")
        }

        val now = System.currentTimeMillis()
        val lastOk = prefs.getLong(KEY_LAST_OK_MS, 0L)
        val minAgeMs = prefs.getLong(KEY_MIN_UPDATE_INTERVAL_MS, DEFAULT_MIN_UPDATE_INTERVAL_MS)
        if (!force && lastOk > 0L && (now - lastOk) < minAgeMs) {
            return UpdateResult.skipped("not_due")
        }

        // Always ensure there is some bundle present before attempting the network fetch.
        caDir().mkdirs()
        if (!caBundleFile().exists()) {
            appendLog("update_no_bundle_present", "")
        }

        return try {
            val meta = readMeta()
            val url = URL(DEFAULT_CA_BUNDLE_URL)
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 12_000
                readTimeout = 18_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "methings/0.1 (ca-bundle)")
                if (meta.etag.isNotBlank()) {
                    setRequestProperty("If-None-Match", meta.etag)
                }
                if (meta.lastModified.isNotBlank()) {
                    setRequestProperty("If-Modified-Since", meta.lastModified)
                }
            }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                prefs.edit().putLong(KEY_LAST_OK_MS, now).apply()
                appendLog("update_not_modified", "")
                return UpdateResult.notModified()
            }
            if (code !in 200..299) {
                val msg = try {
                    conn.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.trim()?.take(240) ?: ""
                } catch (_: Throwable) {
                    ""
                }
                appendLog("update_http_error", "code=$code body=${msg.ifBlank { "(empty)" }}")
                return UpdateResult.failed("http_$code")
            }

            val bytes = conn.inputStream.use { it.readBytes() }
            if (!looksLikePemBundle(bytes)) {
                appendLog("update_invalid_payload", "bytes=${bytes.size}")
                return UpdateResult.failed("invalid_payload")
            }

            val dst = caBundleFile()
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                appendLog("update_rename_failed", "tmp=${tmp.absolutePath}")
                return UpdateResult.failed("rename_failed")
            }

            val newMeta = JSONObject()
                .put("url", DEFAULT_CA_BUNDLE_URL)
                .put("etag", conn.getHeaderField("ETag") ?: "")
                .put("last_modified", conn.getHeaderField("Last-Modified") ?: "")
                .put("updated_at_ms", now)
                .toString()
            caMetaFile().writeText(newMeta)

            prefs.edit().putLong(KEY_LAST_OK_MS, now).apply()
            appendLog("update_ok", "bytes=${bytes.size}")
            UpdateResult.updated(bytes.size.toLong())
        } catch (ex: Throwable) {
            appendLog("update_failed", "error=${ex.javaClass.simpleName}:${ex.message}")
            UpdateResult.failed("exception")
        }
    }

    private data class Meta(val etag: String, val lastModified: String)

    private fun readMeta(): Meta {
        val f = caMetaFile()
        if (!f.exists()) return Meta(etag = "", lastModified = "")
        return try {
            val obj = JSONObject(f.readText())
            Meta(
                etag = obj.optString("etag", ""),
                lastModified = obj.optString("last_modified", "")
            )
        } catch (_: Throwable) {
            Meta(etag = "", lastModified = "")
        }
    }

    private fun looksLikePemBundle(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val head = bytes.copyOfRange(0, minOf(bytes.size, 16 * 1024)).toString(Charsets.US_ASCII)
        return head.contains("BEGIN CERTIFICATE")
    }

    private fun copyAtomic(src: File, dst: File) {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        tmp.outputStream().use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            throw IllegalStateException("rename failed")
        }
    }

    private fun appendLog(event: String, detail: String) {
        try {
            caDir().mkdirs()
            val line = "${System.currentTimeMillis()}\t$event\t$detail\n"
            caLogFile().appendText(line)
        } catch (ex: Throwable) {
            Log.w(TAG, "Failed to write CA update log", ex)
        }
    }

    data class UpdateResult(val status: String, val detail: String) {
        companion object {
            fun updated(bytes: Long) = UpdateResult("updated", "bytes=$bytes")
            fun notModified() = UpdateResult("not_modified", "")
            fun skipped(reason: String) = UpdateResult("skipped", reason)
            fun failed(reason: String) = UpdateResult("failed", reason)
        }
    }

    companion object {
        private const val TAG = "CaBundleManager"
        private const val PREFS = "methings.ca_bundle"
        private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
        private const val KEY_LAST_OK_MS = "last_ok_ms"
        private const val KEY_MIN_UPDATE_INTERVAL_MS = "min_update_interval_ms"

        // Keep it conservative; weekly is plenty for pip trust roots.
        private const val DEFAULT_MIN_UPDATE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L

        // Mozilla-derived bundle maintained for curl; good general-purpose CA set.
        private const val DEFAULT_CA_BUNDLE_URL = "https://curl.se/ca/cacert.pem"
    }
}
