package jp.espresso3389.methings.service.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class ProviderKind { ANTHROPIC, OPENAI_RESPONSES, OPENAI_CHAT, GOOGLE_GEMINI }

class LlmClient {

    fun detectProviderKind(url: String, vendor: String): ProviderKind {
        val v = vendor.trim().lowercase(Locale.US)
        val u = url.trim().lowercase(Locale.US)
        return when {
            v == "anthropic" || u.contains("anthropic.com") -> ProviderKind.ANTHROPIC
            v == "gemini" || (u.contains("generativelanguage.googleapis.com") && !u.contains("/openai/"))
                -> ProviderKind.GOOGLE_GEMINI
            u.endsWith("/responses") || u.contains("/responses?") -> ProviderKind.OPENAI_RESPONSES
            else -> ProviderKind.OPENAI_CHAT
        }
    }

    fun buildHeaders(kind: ProviderKind, apiKey: String, cfg: Map<String, String> = emptyMap()): Map<String, String> {
        val headers = mutableMapOf("Content-Type" to "application/json")
        when (kind) {
            ProviderKind.ANTHROPIC -> {
                headers["x-api-key"] = apiKey
                headers["anthropic-version"] = "2023-06-01"
            }
            ProviderKind.OPENAI_RESPONSES, ProviderKind.OPENAI_CHAT -> {
                headers["Authorization"] = "Bearer $apiKey"
            }
            ProviderKind.GOOGLE_GEMINI -> {
                // Gemini uses API key as URL query param, not in headers
            }
        }
        return headers
    }

    /**
     * Streaming POST to LLM provider with SSE parsing.
     *
     * For OpenAI Responses API: collects until `response.completed` event, returns the response payload.
     * For Anthropic Messages API: accumulates content_block_delta events, builds final response.
     *
     * @param interruptCheck called between SSE lines; throw InterruptedException to cancel
     */
    fun streamingPost(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        kind: ProviderKind,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 80_000,
        interruptCheck: () -> Boolean = { false },
    ): JSONObject {
        val streamBody = JSONObject(body.toString())
        if (kind != ProviderKind.GOOGLE_GEMINI) {
            // Gemini uses alt=sse in URL, not a body param
            streamBody.put("stream", true)
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }

        try {
            conn.outputStream.use { os ->
                os.write(streamBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val errorBody = try {
                    (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }
                throw LlmApiException(responseCode, errorBody, url)
            }

            val contentType = conn.getHeaderField("Content-Type") ?: ""
            if ("text/event-stream" !in contentType) {
                // Not SSE — read full body
                val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                return JSONObject(responseBody)
            }

            return when (kind) {
                ProviderKind.OPENAI_RESPONSES -> parseOpenAiResponsesSse(conn, interruptCheck)
                ProviderKind.OPENAI_CHAT -> parseOpenAiChatSse(conn, interruptCheck)
                ProviderKind.ANTHROPIC -> parseAnthropicSse(conn, interruptCheck)
                ProviderKind.GOOGLE_GEMINI -> parseGeminiSse(conn, interruptCheck)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseOpenAiResponsesSse(conn: HttpURLConnection, interruptCheck: () -> Boolean): JSONObject {
        var completedPayload: JSONObject? = null
        var failedError: String? = null

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (interruptCheck()) throw InterruptedException("interrupted during streaming")
                val l = line?.trim() ?: continue
                if (!l.startsWith("data:")) continue
                val dataStr = l.substring(5).trim()
                if (dataStr.isEmpty()) continue
                if (dataStr == "[DONE]") break

                val event = try { JSONObject(dataStr) } catch (_: Exception) { continue }
                val eventType = event.optString("type", "")

                when (eventType) {
                    "response.completed" -> {
                        completedPayload = event.optJSONObject("response")
                        break
                    }
                    "response.failed", "response.incomplete" -> {
                        failedError = (event.optJSONObject("error") ?: event).toString()
                        break
                    }
                }
            }
        } finally {
            reader.close()
        }

        if (completedPayload != null) return completedPayload
        if (failedError != null) throw RuntimeException("provider_stream_failed: $failedError")
        throw RuntimeException("provider_stream_incomplete: SSE ended without response.completed")
    }

    private fun parseOpenAiChatSse(conn: HttpURLConnection, interruptCheck: () -> Boolean): JSONObject {
        // Chat Completions SSE: accumulate delta chunks into a final message
        var contentText = StringBuilder()
        var finishReason: String? = null
        var model = ""
        var responseId = ""
        // Tool calls: index -> (id, name, arguments accumulator)
        data class ToolCallAccum(var id: String, var name: String, val args: StringBuilder = StringBuilder())
        val toolCallMap = mutableMapOf<Int, ToolCallAccum>()

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (interruptCheck()) throw InterruptedException("interrupted during streaming")
                val l = line?.trim() ?: continue
                if (!l.startsWith("data:")) continue
                val dataStr = l.substring(5).trim()
                if (dataStr.isEmpty()) continue
                if (dataStr == "[DONE]") break

                val chunk = try { JSONObject(dataStr) } catch (_: Exception) { continue }
                if (responseId.isEmpty()) responseId = chunk.optString("id", "")
                if (model.isEmpty()) model = chunk.optString("model", "")

                val choices = chunk.optJSONArray("choices") ?: continue
                for (ci in 0 until choices.length()) {
                    val choice = choices.optJSONObject(ci) ?: continue
                    val delta = choice.optJSONObject("delta") ?: continue
                    val fr = choice.optString("finish_reason", "")
                    if (fr.isNotEmpty() && fr != "null") finishReason = fr

                    if (!delta.isNull("content")) {
                        val dc = delta.optString("content", "")
                        if (dc.isNotEmpty()) contentText.append(dc)
                    }

                    val toolCalls = delta.optJSONArray("tool_calls")
                    if (toolCalls != null) {
                        for (ti in 0 until toolCalls.length()) {
                            val tc = toolCalls.optJSONObject(ti) ?: continue
                            val idx = tc.optInt("index", ti)
                            val accum = toolCallMap.getOrPut(idx) { ToolCallAccum("", "") }
                            val tcId = tc.optString("id", "")
                            if (tcId.isNotEmpty()) accum.id = tcId
                            val fn = tc.optJSONObject("function")
                            if (fn != null) {
                                val fname = fn.optString("name", "")
                                if (fname.isNotEmpty()) accum.name = fname
                                accum.args.append(fn.optString("arguments", ""))
                            }
                        }
                    }
                }
            }
        } finally {
            reader.close()
        }

        // Build a normalized response matching Chat Completions non-streaming format
        val message = JSONObject().apply {
            put("role", "assistant")
            if (contentText.isNotEmpty()) put("content", contentText.toString())
            if (toolCallMap.isNotEmpty()) {
                val tcArr = JSONArray()
                for ((_, accum) in toolCallMap.entries.sortedBy { it.key }) {
                    tcArr.put(JSONObject().apply {
                        put("id", accum.id)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", accum.name)
                            put("arguments", accum.args.toString())
                        })
                    })
                }
                put("tool_calls", tcArr)
            }
        }

        return JSONObject().apply {
            put("id", responseId)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("message", message)
                put("finish_reason", finishReason ?: "stop")
            }))
        }
    }

    private fun parseAnthropicSse(conn: HttpURLConnection, interruptCheck: () -> Boolean): JSONObject {
        // Anthropic Messages API uses event: and data: lines
        val contentBlocks = mutableListOf<JSONObject>()
        var currentBlockType = ""
        var currentBlockText = StringBuilder()
        var currentBlockIndex = -1
        var stopReason: String? = null
        var messageId = ""
        var model = ""
        var inputTokens = 0
        var outputTokens = 0

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        try {
            var currentEvent = ""
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (interruptCheck()) throw InterruptedException("interrupted during streaming")
                val l = line ?: continue
                if (l.startsWith("event:")) {
                    currentEvent = l.substring(6).trim()
                    continue
                }
                if (!l.startsWith("data:")) continue
                val dataStr = l.substring(5).trim()
                if (dataStr.isEmpty()) continue
                if (dataStr == "[DONE]") break

                val event = try { JSONObject(dataStr) } catch (_: Exception) { continue }

                when (currentEvent) {
                    "message_start" -> {
                        val msg = event.optJSONObject("message")
                        if (msg != null) {
                            messageId = msg.optString("id", "")
                            model = msg.optString("model", "")
                            val usage = msg.optJSONObject("usage")
                            if (usage != null) {
                                inputTokens = usage.optInt("input_tokens", 0)
                            }
                        }
                    }
                    "content_block_start" -> {
                        currentBlockIndex = event.optInt("index", currentBlockIndex + 1)
                        val block = event.optJSONObject("content_block")
                        if (block != null) {
                            currentBlockType = block.optString("type", "text")
                            if (currentBlockType == "tool_use") {
                                val toolBlock = JSONObject().apply {
                                    put("type", "tool_use")
                                    put("id", block.optString("id", ""))
                                    put("name", block.optString("name", ""))
                                    put("input", JSONObject())
                                }
                                contentBlocks.add(toolBlock)
                                currentBlockText = StringBuilder()
                            } else {
                                contentBlocks.add(JSONObject().put("type", "text").put("text", ""))
                                currentBlockText = StringBuilder()
                            }
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta")
                        if (delta != null) {
                            when (delta.optString("type", "")) {
                                "text_delta" -> {
                                    currentBlockText.append(delta.optString("text", ""))
                                    if (contentBlocks.isNotEmpty()) {
                                        contentBlocks.last().put("text", currentBlockText.toString())
                                    }
                                }
                                "input_json_delta" -> {
                                    currentBlockText.append(delta.optString("partial_json", ""))
                                }
                            }
                        }
                    }
                    "content_block_stop" -> {
                        if (contentBlocks.isNotEmpty() && currentBlockType == "tool_use") {
                            val inputStr = currentBlockText.toString()
                            val inputJson = try { JSONObject(inputStr) } catch (_: Exception) { JSONObject() }
                            contentBlocks.last().put("input", inputJson)
                        }
                        currentBlockText = StringBuilder()
                    }
                    "message_delta" -> {
                        val delta = event.optJSONObject("delta")
                        if (delta != null) {
                            stopReason = delta.optString("stop_reason", "").ifEmpty { null }
                        }
                        val usage = event.optJSONObject("usage")
                        if (usage != null) {
                            outputTokens = usage.optInt("output_tokens", outputTokens)
                        }
                    }
                    "message_stop" -> {
                        break
                    }
                }
            }
        } finally {
            reader.close()
        }

        // Build a response object similar to Anthropic Messages API response
        val contentArray = JSONArray()
        for (block in contentBlocks) contentArray.put(block)

        return JSONObject().apply {
            put("id", messageId)
            put("type", "message")
            put("role", "assistant")
            put("content", contentArray)
            put("model", model)
            put("stop_reason", stopReason ?: "end_turn")
            put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens))
        }
    }

    private fun parseGeminiSse(conn: HttpURLConnection, interruptCheck: () -> Boolean): JSONObject {
        // Gemini SSE: each data chunk is a full JSON object with candidates array
        val textParts = StringBuilder()
        val functionCalls = JSONArray()
        var finishReason = ""

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (interruptCheck()) throw InterruptedException("interrupted during streaming")
                val l = line?.trim() ?: continue
                if (!l.startsWith("data:")) continue
                val dataStr = l.substring(5).trim()
                if (dataStr.isEmpty() || dataStr == "[DONE]") continue

                val chunk = try { JSONObject(dataStr) } catch (_: Exception) { continue }
                val candidates = chunk.optJSONArray("candidates") ?: continue

                for (ci in 0 until candidates.length()) {
                    val candidate = candidates.optJSONObject(ci) ?: continue
                    val fr = candidate.optString("finishReason", "")
                    if (fr.isNotEmpty()) finishReason = fr

                    val content = candidate.optJSONObject("content") ?: continue
                    val parts = content.optJSONArray("parts") ?: continue

                    for (pi in 0 until parts.length()) {
                        val part = parts.optJSONObject(pi) ?: continue
                        val text = part.optString("text", "")
                        if (text.isNotEmpty()) textParts.append(text)

                        val fc = part.optJSONObject("functionCall")
                        if (fc != null) {
                            functionCalls.put(JSONObject().apply {
                                put("name", fc.optString("name", ""))
                                put("call_id", "gemini_call_${System.nanoTime()}")
                                put("arguments", fc.optJSONObject("args") ?: JSONObject())
                            })
                        }
                    }
                }
            }
        } finally {
            reader.close()
        }

        // Build a normalized response
        val contentArr = JSONArray()
        val fullText = textParts.toString().trim()
        if (fullText.isNotEmpty()) {
            contentArr.put(JSONObject().put("type", "text").put("text", fullText))
        }
        for (i in 0 until functionCalls.length()) {
            val fc = functionCalls.getJSONObject(i)
            contentArr.put(JSONObject().apply {
                put("type", "function_call")
                put("name", fc.optString("name", ""))
                put("call_id", fc.optString("call_id", ""))
                put("arguments", fc.optJSONObject("arguments") ?: JSONObject())
            })
        }

        return JSONObject().apply {
            put("type", "gemini_response")
            put("content", contentArr)
            put("finish_reason", finishReason.ifEmpty { "STOP" })
        }
    }

    companion object {
        private const val TAG = "LlmClient"
    }
}

class LlmApiException(
    val httpStatus: Int,
    val responseBody: String,
    val url: String,
) : RuntimeException("LLM API error $httpStatus from $url: ${responseBody.take(500)}")
