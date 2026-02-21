package jp.espresso3389.methings.service.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class JsFetchResult(
    val status: Int,
    val ok: Boolean,
    val headers: Map<String, String>,
    val body: String,
)

/**
 * Simple HTTP fetch client for the JS sandbox.
 * Uses HttpURLConnection wrapped in Dispatchers.IO.
 */
object JsFetchClient {
    suspend fun fetch(
        url: String,
        method: String = "GET",
        headers: Map<String, String>? = null,
        body: String? = null,
        timeoutMs: Int = 30_000,
    ): JsFetchResult = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method.uppercase()
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true

            headers?.forEach { (key, value) ->
                conn.setRequestProperty(key, value)
            }

            if (!body.isNullOrEmpty() && method.uppercase() in setOf("POST", "PUT", "PATCH")) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val httpStatus = conn.responseCode
            val respHeaders = mutableMapOf<String, String>()
            conn.headerFields?.forEach { (key, values) ->
                if (key != null && values != null) {
                    respHeaders[key] = values.joinToString(", ")
                }
            }

            val respBody = try {
                val stream = if (httpStatus in 200..399) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }

            JsFetchResult(
                status = httpStatus,
                ok = httpStatus in 200..299,
                headers = respHeaders,
                body = respBody,
            )
        } finally {
            conn.disconnect()
        }
    }
}
