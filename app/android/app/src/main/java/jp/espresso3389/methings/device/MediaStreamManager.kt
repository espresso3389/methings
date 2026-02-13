package jp.espresso3389.methings.device

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class MediaStreamManager(private val context: Context) {
    companion object {
        private const val TAG = "MediaStreamManager"
    }

    private val userRoot = File(context.filesDir, "user")

    data class StreamState(
        val id: String,
        val type: String,  // "audio" or "video"
        val sourceFile: File,
        val running: AtomicBoolean = AtomicBoolean(true),
        val wsClients: CopyOnWriteArrayList<NanoWSD.WebSocket> = CopyOnWriteArrayList(),
        var thread: Thread? = null
    )

    private val streams = ConcurrentHashMap<String, StreamState>()

    // ── Status ────────────────────────────────────────────────────────────────

    fun status(): Map<String, Any?> {
        val arr = JSONArray()
        for ((id, st) in streams) {
            arr.put(JSONObject()
                .put("stream_id", id)
                .put("type", st.type)
                .put("source", st.sourceFile.name)
                .put("running", st.running.get())
                .put("ws_clients", st.wsClients.size))
        }
        return mapOf("status" to "ok", "streams" to arr.toString(), "count" to streams.size)
    }

    // ── Audio decode ──────────────────────────────────────────────────────────

    fun startAudioDecode(
        sourceFile: String?,
        sampleRate: Int?,
        channels: Int?
    ): Map<String, Any?> {
        val file = resolveFile(sourceFile)
            ?: return mapOf("status" to "error", "error" to "missing_source", "detail" to "Provide source_file (user-root relative path)")

        if (!file.exists()) {
            return mapOf("status" to "error", "error" to "file_not_found", "detail" to file.absolutePath)
        }

        val id = "adec-" + UUID.randomUUID().toString().take(8)
        val state = StreamState(id = id, type = "audio", sourceFile = file)
        streams[id] = state

        val targetSr = (sampleRate ?: 44100).coerceIn(8000, 48000)
        val targetCh = (channels ?: 0) // 0 = use source channels

        val thread = Thread({
            try {
                decodeAudio(state, targetSr, targetCh)
            } catch (e: Exception) {
                Log.e(TAG, "Audio decode error for $id", e)
            } finally {
                state.running.set(false)
                broadcastEnd(state)
                streams.remove(id)
            }
        }, "MediaDecode-$id")
        thread.start()
        state.thread = thread

        return mapOf(
            "status" to "ok",
            "stream_id" to id,
            "ws_path" to "/ws/media/stream/$id",
            "type" to "audio",
            "encoding" to "pcm_s16le"
        )
    }

    // ── Video decode ──────────────────────────────────────────────────────────

    fun startVideoDecode(
        sourceFile: String?,
        format: String?,
        fps: Int?,
        jpegQuality: Int?
    ): Map<String, Any?> {
        val file = resolveFile(sourceFile)
            ?: return mapOf("status" to "error", "error" to "missing_source", "detail" to "Provide source_file (user-root relative path)")

        if (!file.exists()) {
            return mapOf("status" to "error", "error" to "file_not_found", "detail" to file.absolutePath)
        }

        val fmt = (format ?: "jpeg").trim().lowercase()
        if (fmt !in listOf("jpeg", "rgba")) {
            return mapOf("status" to "error", "error" to "invalid_format", "detail" to "Use 'jpeg' or 'rgba'")
        }

        val id = "vdec-" + UUID.randomUUID().toString().take(8)
        val state = StreamState(id = id, type = "video", sourceFile = file)
        streams[id] = state

        val targetFps = (fps ?: 10).coerceIn(1, 60)
        val jq = (jpegQuality ?: 70).coerceIn(10, 95)

        val thread = Thread({
            try {
                decodeVideo(state, fmt, targetFps, jq)
            } catch (e: Exception) {
                Log.e(TAG, "Video decode error for $id", e)
            } finally {
                state.running.set(false)
                broadcastEnd(state)
                streams.remove(id)
            }
        }, "MediaDecode-$id")
        thread.start()
        state.thread = thread

        return mapOf(
            "status" to "ok",
            "stream_id" to id,
            "ws_path" to "/ws/media/stream/$id",
            "type" to "video",
            "format" to fmt
        )
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    fun stopDecode(streamId: String?): Map<String, Any?> {
        val id = (streamId ?: "").trim()
        val state = streams[id]
            ?: return mapOf("status" to "error", "error" to "stream_not_found")
        state.running.set(false)
        state.thread?.runCatching { join(3000) }
        streams.remove(id)
        return mapOf("status" to "ok", "stopped" to true, "stream_id" to id)
    }

    // ── WebSocket client management ───────────────────────────────────────────

    fun addWsClient(streamId: String, ws: NanoWSD.WebSocket): Boolean {
        val st = streams[streamId.trim()] ?: return false
        st.wsClients.add(ws)
        return true
    }

    fun removeWsClient(streamId: String, ws: NanoWSD.WebSocket) {
        streams[streamId.trim()]?.wsClients?.remove(ws)
    }

    // ── Internal: Audio decode pipeline ───────────────────────────────────────

    @Suppress("UNUSED_PARAMETER")
    private fun decodeAudio(state: StreamState, targetSr: Int, targetCh: Int) {
        val extractor = MediaExtractor()
        extractor.setDataSource(state.sourceFile.absolutePath)

        val trackIndex = findTrack(extractor, "audio/")
        if (trackIndex < 0) {
            Log.w(TAG, "No audio track in ${state.sourceFile.name}")
            return
        }
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME) ?: return
        val codec = MediaCodec.createDecoderByType(mime)



        codec.configure(format, null, null, 0)
        codec.start()

        val srcSr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        // Send hello
        val hello = JSONObject()
            .put("type", "hello")
            .put("sample_rate", srcSr)
            .put("channels", srcCh)
            .put("encoding", "pcm_s16le")
        broadcastText(state, hello.toString())

        val info = MediaCodec.BufferInfo()
        var eos = false

        while (state.running.get() && !eos) {
            // Feed input
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val inBuf = codec.getInputBuffer(inIdx) ?: continue
                val read = extractor.readSampleData(inBuf, 0)
                if (read < 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            // Drain output
            while (state.running.get()) {
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx < 0) break
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    eos = true
                    codec.releaseOutputBuffer(outIdx, false)
                    break
                }
                val outBuf = codec.getOutputBuffer(outIdx)
                if (outBuf != null && info.size > 0) {
                    val pcm = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(pcm, 0, info.size)
                    broadcastBinary(state, pcm)
                }
                codec.releaseOutputBuffer(outIdx, false)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()
    }

    // ── Internal: Video decode pipeline ───────────────────────────────────────

    private fun decodeVideo(state: StreamState, format: String, fps: Int, jpegQuality: Int) {
        val extractor = MediaExtractor()
        extractor.setDataSource(state.sourceFile.absolutePath)

        val trackIndex = findTrack(extractor, "video/")
        if (trackIndex < 0) {
            Log.w(TAG, "No video track in ${state.sourceFile.name}")
            return
        }
        extractor.selectTrack(trackIndex)
        val trackFormat = extractor.getTrackFormat(trackIndex)

        val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return
        val width = trackFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = trackFormat.getInteger(MediaFormat.KEY_HEIGHT)

        // Configure codec to output YUV420
        val codec = MediaCodec.createDecoderByType(mime)
        trackFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec.configure(trackFormat, null, null, 0)
        codec.start()

        // Send hello
        val hello = JSONObject()
            .put("type", "hello")
            .put("format", format)
            .put("width", width)
            .put("height", height)
            .put("fps", fps)
        broadcastText(state, hello.toString())

        val info = MediaCodec.BufferInfo()
        var eos = false
        val frameIntervalMs = (1000.0 / fps).toLong()
        var lastFrameMs = 0L

        while (state.running.get() && !eos) {
            // Feed input
            val inIdx = codec.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val inBuf = codec.getInputBuffer(inIdx) ?: continue
                val read = extractor.readSampleData(inBuf, 0)
                if (read < 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            // Drain output
            while (state.running.get()) {
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx < 0) break
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    eos = true
                    codec.releaseOutputBuffer(outIdx, false)
                    break
                }

                // Rate-limit frames
                val now = System.currentTimeMillis()
                if ((now - lastFrameMs) >= frameIntervalMs && state.wsClients.isNotEmpty()) {
                    lastFrameMs = now
                    val image = codec.getOutputImage(outIdx)
                    if (image != null) {
                        try {
                            if (format == "rgba") {
                                val rgba = yuvImageToRgba(image)
                                val tsMs = (now and 0xFFFFFFFFL).toInt()
                                val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                                    .putInt(image.width).putInt(image.height).putInt(tsMs).array()
                                val payload = ByteArray(12 + rgba.size)
                                System.arraycopy(header, 0, payload, 0, 12)
                                System.arraycopy(rgba, 0, payload, 12, rgba.size)
                                broadcastBinary(state, payload)
                            } else {
                                val jpeg = yuvImageToJpeg(image, jpegQuality)
                                broadcastBinary(state, jpeg)
                            }
                        } finally {
                            image.close()
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun resolveFile(path: String?): File? {
        val p = (path ?: "").trim()
        if (p.isBlank()) return null
        return if (p.startsWith("/")) File(p) else File(userRoot, p)
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun yuvImageToJpeg(image: android.media.Image, quality: Int): ByteArray {
        val w = image.width
        val h = image.height
        val yBuf = image.planes[0].buffer
        val uBuf = image.planes[1].buffer
        val vBuf = image.planes[2].buffer
        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixStride = image.planes[1].pixelStride

        val nv21 = ByteArray(w * h * 3 / 2)
        var off = 0
        for (row in 0 until h) {
            for (col in 0 until w) {
                nv21[off++] = yBuf.get(row * yRowStride + col)
            }
        }
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[off++] = vBuf.get(row * uvRowStride + col * uvPixStride)
                nv21[off++] = uBuf.get(row * uvRowStride + col * uvPixStride)
            }
        }

        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }

    private fun yuvImageToRgba(image: android.media.Image): ByteArray {
        val w = image.width
        val h = image.height
        val yBuf = image.planes[0].buffer
        val uBuf = image.planes[1].buffer
        val vBuf = image.planes[2].buffer
        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixStride = image.planes[1].pixelStride

        val rgba = ByteArray(w * h * 4)
        var off = 0
        for (row in 0 until h) {
            for (col in 0 until w) {
                val y = (yBuf.get(row * yRowStride + col).toInt() and 0xFF)
                val u = (uBuf.get((row / 2) * uvRowStride + (col / 2) * uvPixStride).toInt() and 0xFF) - 128
                val v = (vBuf.get((row / 2) * uvRowStride + (col / 2) * uvPixStride).toInt() and 0xFF) - 128
                rgba[off++] = (y + 1.402 * v).toInt().coerceIn(0, 255).toByte()
                rgba[off++] = (y - 0.344136 * u - 0.714136 * v).toInt().coerceIn(0, 255).toByte()
                rgba[off++] = (y + 1.772 * u).toInt().coerceIn(0, 255).toByte()
                rgba[off++] = 0xFF.toByte()
            }
        }
        return rgba
    }

    private fun broadcastText(state: StreamState, text: String) {
        val dead = ArrayList<NanoWSD.WebSocket>()
        for (ws in state.wsClients) {
            try {
                if (ws.isOpen) ws.send(text) else dead.add(ws)
            } catch (_: Exception) {
                dead.add(ws)
            }
        }
        for (ws in dead) state.wsClients.remove(ws)
    }

    private fun broadcastBinary(state: StreamState, payload: ByteArray) {
        if (state.wsClients.isEmpty()) return
        val dead = ArrayList<NanoWSD.WebSocket>()
        for (ws in state.wsClients) {
            try {
                if (ws.isOpen) ws.send(payload) else dead.add(ws)
            } catch (_: Exception) {
                dead.add(ws)
            }
        }
        for (ws in dead) state.wsClients.remove(ws)
    }

    private fun broadcastEnd(state: StreamState) {
        val msg = JSONObject().put("type", "end").toString()
        broadcastText(state, msg)
    }
}
