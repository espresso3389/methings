package jp.espresso3389.kugutz.device

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TtsManager(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)

    fun init(engine: String? = null, timeoutMs: Long = 5000): Map<String, Any> {
        if (tts != null && ready.get()) {
            return mapOf("status" to "ok", "ready" to true)
        }

        val latch = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        main.post {
            if (tts == null) {
                tts = if (!engine.isNullOrBlank()) {
                    TextToSpeech(context, { status ->
                        initStatus = status
                        ready.set(status == TextToSpeech.SUCCESS)
                        latch.countDown()
                    }, engine)
                } else {
                    TextToSpeech(context) { status ->
                        initStatus = status
                        ready.set(status == TextToSpeech.SUCCESS)
                        latch.countDown()
                    }
                }
            } else {
                initStatus = if (ready.get()) TextToSpeech.SUCCESS else TextToSpeech.ERROR
                latch.countDown()
            }
        }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return mapOf(
            "status" to "ok",
            "ready" to ready.get(),
            "init_status" to initStatus,
        )
    }

    fun listVoices(): Map<String, Any> {
        init()
        val inst = tts ?: return mapOf("status" to "error", "error" to "tts_not_initialized")
        val voices = inst.voices?.map { v ->
            mapOf(
                "name" to v.name,
                "locale" to v.locale.toLanguageTag(),
                "quality" to v.quality,
                "latency" to v.latency,
                "requires_network" to v.isNetworkConnectionRequired,
                "features" to (v.features?.toList() ?: emptyList()),
            )
        } ?: emptyList()
        return mapOf("status" to "ok", "voices" to voices)
    }

    fun speak(
        text: String,
        voiceName: String? = null,
        localeTag: String? = null,
        rate: Float? = null,
        pitch: Float? = null,
    ): Map<String, Any> {
        require(text.isNotBlank()) { "text_required" }
        init()
        val inst = tts ?: return mapOf("status" to "error", "error" to "tts_not_initialized")
        val utteranceId = "utt_" + UUID.randomUUID().toString().replace("-", "")

        main.post {
            if (!voiceName.isNullOrBlank()) {
                val v = inst.voices?.firstOrNull { it.name == voiceName }
                if (v != null) inst.voice = v
            }
            if (!localeTag.isNullOrBlank()) {
                inst.language = Locale.forLanguageTag(localeTag)
            }
            if (rate != null) inst.setSpeechRate(rate.coerceIn(0.1f, 3.0f))
            if (pitch != null) inst.setPitch(pitch.coerceIn(0.1f, 3.0f))
            inst.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }

        return mapOf("status" to "ok", "utterance_id" to utteranceId)
    }

    fun stop(): Map<String, Any> {
        val inst = tts ?: return mapOf("status" to "ok", "stopped" to false)
        main.post { runCatching { inst.stop() } }
        return mapOf("status" to "ok", "stopped" to true)
    }

    fun shutdown(): Map<String, Any> {
        val inst = tts ?: return mapOf("status" to "ok", "shutdown" to false)
        main.post {
            runCatching { inst.stop() }
            runCatching { inst.shutdown() }
            tts = null
            ready.set(false)
        }
        return mapOf("status" to "ok", "shutdown" to true)
    }
}

