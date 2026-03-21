package jp.espresso3389.methings.service

import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object MeMeFirebaseAuthManager {
    data class SignInResult(
        val ok: Boolean,
        val rtdbDatabaseUrl: String = "",
        val rtdbRootPath: String = "",
        val userSubject: String = "",
        val error: String = ""
    )

    fun ensureSignedIn(
        gatewayBaseUrl: String,
        deviceId: String,
        pullSecret: String,
        currentDatabaseUrl: String,
        currentRootPath: String,
        logger: (String, Throwable?) -> Unit = { _, _ -> }
    ): SignInResult {
        if (gatewayBaseUrl.isBlank()) return SignInResult(ok = false, error = "gateway_base_url_missing")
        if (deviceId.isBlank()) return SignInResult(ok = false, error = "device_id_missing")
        if (pullSecret.isBlank()) return SignInResult(ok = false, error = "pull_secret_missing")

        val tokenResponse = postJson(
            baseUrl = gatewayBaseUrl,
            path = "/p2p/firebase_token",
            payload = JSONObject()
                .put("device_id", deviceId)
                .put("pull_secret", pullSecret)
        )
        val body = tokenResponse.optJSONObject("body") ?: JSONObject()
        if (!tokenResponse.optBoolean("ok", false) || body.optString("status", "") != "ok") {
            return SignInResult(
                ok = false,
                error = body.optString("error", tokenResponse.optString("error", "firebase_token_failed"))
            )
        }

        val customToken = body.optString("firebase_custom_token", "").trim()
        if (customToken.isBlank()) return SignInResult(ok = false, error = "firebase_custom_token_missing")

        val rtdbDatabaseUrl = body.optString("rtdb_database_url", "").trim().ifBlank { currentDatabaseUrl.trim() }
        val rtdbRootPath = body.optString("rtdb_root_path", "").trim().ifBlank { currentRootPath.trim() }
        if (rtdbDatabaseUrl.isBlank()) return SignInResult(ok = false, error = "rtdb_database_url_missing")

        val auth = FirebaseAuth.getInstance()
        val currentUid = auth.currentUser?.uid?.trim().orEmpty()
        logger("me.me RTDB auth: current uid=$currentUid target=$deviceId", null)
        if (currentUid.isNotBlank() && currentUid != deviceId) {
            runCatching { auth.signOut() }.onFailure { logger("me.me RTDB auth: signOut failed", it) }
        }

        val latch = CountDownLatch(1)
        var signInError: Throwable? = null
        auth.signInWithCustomToken(customToken)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    signInError = task.exception ?: IllegalStateException("firebase_sign_in_failed")
                }
                latch.countDown()
            }

        if (!latch.await(15, TimeUnit.SECONDS)) {
            return SignInResult(ok = false, error = "firebase_sign_in_timeout")
        }
        signInError?.let {
            logger("me.me RTDB auth: signInWithCustomToken failed", it)
            return SignInResult(ok = false, error = it.message ?: "firebase_sign_in_failed")
        }

        val signedInUid = auth.currentUser?.uid?.trim().orEmpty()
        if (signedInUid != deviceId) {
            return SignInResult(ok = false, error = "firebase_uid_mismatch")
        }
        logger("me.me RTDB auth: signed in uid=$signedInUid", null)

        return SignInResult(
            ok = true,
            rtdbDatabaseUrl = rtdbDatabaseUrl,
            rtdbRootPath = rtdbRootPath,
            userSubject = body.optString("user_subject", "").trim()
        )
    }

    fun signOut(logger: (String, Throwable?) -> Unit = { _, _ -> }) {
        runCatching { FirebaseAuth.getInstance().signOut() }
            .onFailure { logger("me.me RTDB auth: signOut failed", it) }
    }

    private fun postJson(
        baseUrl: String,
        path: String,
        payload: JSONObject
    ): JSONObject {
        return try {
            val normalizedBase = baseUrl.trim().trimEnd('/')
            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            val url = URI("$normalizedBase$normalizedPath").toURL()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Connection", "close")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use {
                it.readBytes().toString(StandardCharsets.UTF_8)
            } ?: ""
            JSONObject()
                .put("ok", code in 200..299)
                .put("http_status", code)
                .put("body", runCatching { JSONObject(body) }.getOrDefault(JSONObject()))
        } catch (ex: Exception) {
            JSONObject().put("ok", false).put("error", ex.message ?: "request_failed")
        }
    }
}
