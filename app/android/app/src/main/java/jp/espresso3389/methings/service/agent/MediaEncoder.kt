package jp.espresso3389.methings.service.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

object MediaEncoder {
    const val MAX_DIMENSION = 1568   // Anthropic's recommended max
    const val JPEG_QUALITY = 70
    private const val TAG = "MediaEncoder"
    private const val DEFAULT_MAX_AUDIO_BYTES = 15_000_000L // 15 MB

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "m4a", "aac", "ogg", "flac", "opus", "amr", "3gp")

    val AUDIO_MIME_MAP = mapOf(
        "wav" to "audio/wav",
        "mp3" to "audio/mp3",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "flac" to "audio/flac",
        "opus" to "audio/opus",
        "amr" to "audio/amr",
        "3gp" to "audio/3gpp",
    )

    data class MediaResult(
        val base64: String,
        val mimeType: String,
        val mediaType: String,       // "image" or "audio"
        val metadata: JSONObject,    // width/height for images; duration_ms/size for audio
    )

    fun isImagePath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    fun isAudioPath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    fun isMediaPath(path: String): Boolean = isImagePath(path) || isAudioPath(path)

    /**
     * Encode an image file to base64, resizing if needed.
     * Returns a MediaResult or null if the file can't be decoded.
     */
    fun encodeImage(
        file: File,
        maxDim: Int = MAX_DIMENSION,
        quality: Int = JPEG_QUALITY,
    ): MediaResult? {
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "encodeImage: file not found or not a file: ${file.absolutePath}")
            return null
        }

        return try {
            // 1. Decode bounds only
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val origW = opts.outWidth
            val origH = opts.outHeight
            if (origW <= 0 || origH <= 0) {
                Log.w(TAG, "encodeImage: could not decode bounds for ${file.name} (${origW}x${origH})")
                return null
            }

            // 2. Calculate inSampleSize for coarse downsampling
            var sampleSize = 1
            var w = origW
            var h = origH
            while (w / 2 >= maxDim && h / 2 >= maxDim) {
                sampleSize *= 2
                w /= 2
                h /= 2
            }

            // 3. Decode with sample size
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            if (sampled == null) {
                Log.w(TAG, "encodeImage: BitmapFactory.decodeFile returned null for ${file.name}")
                return null
            }

            // 4. Fine-scale to fit within maxDim
            val bitmap = try {
                val sw = sampled.width
                val sh = sampled.height
                if (sw <= maxDim && sh <= maxDim) {
                    sampled
                } else {
                    val scale = minOf(maxDim.toFloat() / sw, maxDim.toFloat() / sh)
                    val newW = (sw * scale).toInt().coerceAtLeast(1)
                    val newH = (sh * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(sampled, newW, newH, true)
                    if (scaled !== sampled) sampled.recycle()
                    scaled
                }
            } catch (e: Exception) {
                Log.w(TAG, "encodeImage: scaling failed for ${file.name}", e)
                sampled.recycle()
                return null
            }

            // 5. Compress to JPEG and base64-encode
            try {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                val bytes = baos.toByteArray()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                Log.i(TAG, "encodeImage: encoded ${file.name} (${origW}x${origH} -> ${bitmap.width}x${bitmap.height}, ${bytes.size} bytes)")
                MediaResult(
                    base64 = b64,
                    mimeType = "image/jpeg",
                    mediaType = "image",
                    metadata = JSONObject().apply {
                        put("width", bitmap.width)
                        put("height", bitmap.height)
                        put("original_width", origW)
                        put("original_height", origH)
                        put("size", bytes.size)
                    },
                )
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "encodeImage: unexpected error for ${file.absolutePath}", e)
            null
        }
    }

    /**
     * Encode an audio file to base64 with MIME type detection and metadata extraction.
     * Returns a MediaResult or null if the file is too large or unreadable.
     */
    fun encodeAudio(
        file: File,
        maxBytes: Long = DEFAULT_MAX_AUDIO_BYTES,
    ): MediaResult? {
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "encodeAudio: file not found or not a file: ${file.absolutePath}")
            return null
        }

        if (file.length() > maxBytes) {
            Log.w(TAG, "encodeAudio: file too large (${file.length()} bytes > $maxBytes limit): ${file.name}")
            return null
        }

        val ext = file.extension.lowercase()
        val mimeType = AUDIO_MIME_MAP[ext]
        if (mimeType == null) {
            Log.w(TAG, "encodeAudio: unsupported audio extension '$ext' for ${file.name}")
            return null
        }

        return try {
            val bytes = file.readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            // Extract duration via MediaMetadataRetriever
            var durationMs = 0L
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durationStr?.toLongOrNull() ?: 0L
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "encodeAudio: could not extract duration for ${file.name}", e)
            }

            Log.i(TAG, "encodeAudio: encoded ${file.name} ($ext, ${bytes.size} bytes, ${durationMs}ms)")
            MediaResult(
                base64 = b64,
                mimeType = mimeType,
                mediaType = "audio",
                metadata = JSONObject().apply {
                    put("duration_ms", durationMs)
                    put("size", bytes.size)
                    put("format", ext)
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "encodeAudio: unexpected error for ${file.absolutePath}", e)
            null
        }
    }
}
