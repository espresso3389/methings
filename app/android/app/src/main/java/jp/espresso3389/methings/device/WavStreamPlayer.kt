package jp.espresso3389.methings.device

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class WavStreamPlayer {
    data class WavHeader(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long,
    )

    @Volatile private var currentTrack: AudioTrack? = null

    fun stop(): Map<String, Any?> {
        val track = currentTrack
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
            currentTrack = null
            return mapOf("status" to "ok", "stopped" to true)
        }
        return mapOf("status" to "ok", "stopped" to false)
    }

    fun playGrowingWav(
        file: File,
        producerAlive: () -> Boolean,
        stopRequested: AtomicBoolean,
        waitHeaderMs: Long = 8000L,
        tailIdleMs: Long = 1500L
    ): Map<String, Any?> {
        val headerDeadline = System.currentTimeMillis() + waitHeaderMs
        var header: WavHeader? = null
        while (System.currentTimeMillis() < headerDeadline && !stopRequested.get()) {
            if (file.exists() && file.isFile) {
                header = runCatching { readHeader(file) }.getOrNull()
                if (header != null) break
            }
            Thread.sleep(25)
        }
        val h = header ?: return mapOf("status" to "error", "error" to "wav_header_not_ready")

        val channelMask = when (h.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return mapOf("status" to "error", "error" to "unsupported_channels", "channels" to h.channels)
        }
        val encoding = when {
            h.bitsPerSample == 8 && h.audioFormat == 1 -> AudioFormat.ENCODING_PCM_8BIT
            h.bitsPerSample == 16 && h.audioFormat == 1 -> AudioFormat.ENCODING_PCM_16BIT
            h.bitsPerSample == 32 && h.audioFormat == 3 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> return mapOf(
                "status" to "error",
                "error" to "unsupported_wav_format",
                "audio_format" to h.audioFormat,
                "bits_per_sample" to h.bitsPerSample,
            )
        }

        val minBuffer = AudioTrack.getMinBufferSize(h.sampleRate, channelMask, encoding).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(h.sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        currentTrack = track

        var pos = h.dataOffset
        val expectedEnd = if (h.dataSize > 0) h.dataOffset + h.dataSize else Long.MAX_VALUE
        val buf = ByteArray(16 * 1024)
        var lastDataAt = System.currentTimeMillis()

        return try {
            track.play()
            RandomAccessFile(file, "r").use { raf ->
                while (!stopRequested.get()) {
                    val len = raf.length()
                    val available = len - pos
                    if (available > 0) {
                        val toRead = minOf(buf.size.toLong(), available).toInt()
                        raf.seek(pos)
                        val n = raf.read(buf, 0, toRead)
                        if (n > 0) {
                            var written = 0
                            while (written < n && !stopRequested.get()) {
                                val w = track.write(buf, written, n - written, AudioTrack.WRITE_BLOCKING)
                                if (w <= 0) break
                                written += w
                            }
                            pos += n.toLong()
                            lastDataAt = System.currentTimeMillis()
                        }
                    } else {
                        val producerDone = !producerAlive()
                        val reachedHeaderSize = pos >= expectedEnd
                        val idleMs = System.currentTimeMillis() - lastDataAt
                        if ((producerDone && reachedHeaderSize) || (producerDone && idleMs > tailIdleMs)) {
                            break
                        }
                        Thread.sleep(20)
                    }
                }
            }
            mapOf("status" to "ok", "played_bytes" to (pos - h.dataOffset), "stopped" to stopRequested.get())
        } catch (ex: Exception) {
            mapOf("status" to "error", "error" to "playback_failed", "detail" to (ex.message ?: ""))
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
            if (currentTrack === track) currentTrack = null
        }
    }

    private fun readHeader(file: File): WavHeader? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return null
            val riff = ByteArray(4)
            raf.readFully(riff)
            if (String(riff) != "RIFF") return null
            raf.skipBytes(4)
            val wave = ByteArray(4)
            raf.readFully(wave)
            if (String(wave) != "WAVE") return null

            var fmtAudioFormat = 0
            var fmtChannels = 0
            var fmtSampleRate = 0
            var fmtBitsPerSample = 0
            var dataOffset = -1L
            var dataSize = 0L

            while (raf.filePointer + 8 <= raf.length()) {
                val idBytes = ByteArray(4)
                raf.readFully(idBytes)
                val chunkId = String(idBytes)
                val chunkSize = readU32LE(raf)
                val chunkDataPos = raf.filePointer
                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize >= 16 && chunkDataPos + chunkSize <= raf.length()) {
                            fmtAudioFormat = readU16LE(raf)
                            fmtChannels = readU16LE(raf)
                            fmtSampleRate = readU32LE(raf).toInt()
                            raf.skipBytes(6)
                            fmtBitsPerSample = readU16LE(raf)
                        } else {
                            return null
                        }
                    }
                    "data" -> {
                        dataOffset = chunkDataPos
                        dataSize = chunkSize
                        break
                    }
                }
                raf.seek(chunkDataPos + chunkSize + (chunkSize % 2))
            }
            if (fmtChannels <= 0 || fmtSampleRate <= 0 || fmtBitsPerSample <= 0 || dataOffset < 0L) return null
            return WavHeader(fmtAudioFormat, fmtChannels, fmtSampleRate, fmtBitsPerSample, dataOffset, dataSize)
        }
    }

    private fun readU16LE(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }

    private fun readU32LE(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return (ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)
    }
}
