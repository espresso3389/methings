package jp.espresso3389.methings.device

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class SttManager(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val active = AtomicBoolean(false)
    private val wsClients = CopyOnWriteArrayList<NanoWSD.WebSocket>()
    private val wsClientCount = AtomicInteger(0)
    private val lastEvent = AtomicReference("")
    private val lastTopResult = AtomicReference("")
    private val lastErrorCode = AtomicInteger(0)
    private val lastEventTsMs = AtomicLong(0L)

    fun addWsClient(ws: NanoWSD.WebSocket) {
        wsClients.add(ws)
        wsClientCount.set(wsClients.size)
        Log.d(TAG, "STT ws connected count=${wsClients.size}")
    }

    fun removeWsClient(ws: NanoWSD.WebSocket) {
        wsClients.remove(ws)
        wsClientCount.set(wsClients.size)
        Log.d(TAG, "STT ws disconnected count=${wsClients.size}")
    }

    fun status(): Map<String, Any> {
        return mapOf(
            "status" to "ok",
            "active" to active.get(),
            "ws_clients" to wsClientCount.get(),
            "last_event" to lastEvent.get(),
            "last_top_result" to lastTopResult.get(),
            "last_error_code" to lastErrorCode.get(),
            "last_event_ts_ms" to lastEventTsMs.get()
        )
    }

    fun start(localeTag: String? = null, partial: Boolean = true, maxResults: Int = 5): Map<String, Any> {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return mapOf("status" to "error", "error" to "recognition_not_available")
        }
        if (active.get()) {
            return mapOf("status" to "error", "error" to "already_active")
        }

        val loc = if (!localeTag.isNullOrBlank()) Locale.forLanguageTag(localeTag) else null
        main.post {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { r ->
                    r.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            emit("ready", JSONObject())
                        }

                        override fun onBeginningOfSpeech() {
                            emit("begin", JSONObject())
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            emit("rms", JSONObject().put("rms_db", rmsdB.toDouble()))
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            emit("end", JSONObject())
                        }

                        override fun onError(error: Int) {
                            active.set(false)
                            lastErrorCode.set(error)
                            emit("error", JSONObject().put("code", error))
                        }

                        override fun onResults(results: Bundle?) {
                            active.set(false)
                            val arr = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                            lastTopResult.set(arr.firstOrNull() ?: "")
                            emit("final", JSONObject().put("results", arr))
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val arr = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                            lastTopResult.set(arr.firstOrNull() ?: "")
                            emit("partial", JSONObject().put("results", arr))
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }

            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults.coerceIn(1, 20))
                if (loc != null) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, loc)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, loc)
                }
            }
            active.set(true)
            emit("start", JSONObject().put("locale", loc?.toLanguageTag() ?: ""))
            recognizer?.startListening(intent)
        }

        return mapOf("status" to "ok", "active" to true)
    }

    fun stop(): Map<String, Any> {
        main.post {
            active.set(false)
            runCatching { recognizer?.stopListening() }
        }
        return mapOf("status" to "ok")
    }

    fun cancel(): Map<String, Any> {
        main.post {
            active.set(false)
            runCatching { recognizer?.cancel() }
        }
        return mapOf("status" to "ok")
    }

    fun shutdown(): Map<String, Any> {
        main.post {
            active.set(false)
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        return mapOf("status" to "ok")
    }

    private fun emit(kind: String, data: JSONObject) {
        lastEvent.set(kind)
        lastEventTsMs.set(System.currentTimeMillis())
        try {
            val top = if (data.has("results")) {
                val a = data.optJSONArray("results")
                if (a != null && a.length() > 0) a.optString(0, "") else ""
            } else ""
            if (top.isNotBlank()) {
                lastTopResult.set(top)
            }
            Log.d(TAG, "STT event=$kind active=${active.get()} ws=${wsClients.size} top='${(top.take(80))}'")
        } catch (_: Exception) {
            Log.d(TAG, "STT event=$kind active=${active.get()} ws=${wsClients.size}")
        }
        val msg = JSONObject()
            .put("type", "stt")
            .put("event", kind)
            .put("ts_ms", System.currentTimeMillis())
        for (k in data.keys()) {
            msg.put(k, data.get(k))
        }
        val dead = ArrayList<NanoWSD.WebSocket>()
        val text = msg.toString()
        for (ws in wsClients) {
            try {
                if (ws.isOpen) ws.send(text) else dead.add(ws)
            } catch (_: Exception) {
                dead.add(ws)
            }
        }
        for (ws in dead) wsClients.remove(ws)
        wsClientCount.set(wsClients.size)
    }

    companion object {
        private const val TAG = "MethingsStt"
    }
}
