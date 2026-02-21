package jp.espresso3389.methings.service.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

object ImageEncoder {
    const val MAX_DIMENSION = 1568   // Anthropic's recommended max
    const val JPEG_QUALITY = 70

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

    fun isImagePath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    /**
     * Encode an image file to base64, resizing if needed.
     * @return (base64, mimeType) or null if the file can't be decoded
     */
    fun encodeImageFile(
        file: File,
        maxDim: Int = MAX_DIMENSION,
        quality: Int = JPEG_QUALITY,
    ): Pair<String, String>? {
        if (!file.exists() || !file.isFile) return null

        // 1. Decode bounds only
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val origW = opts.outWidth
        val origH = opts.outHeight
        if (origW <= 0 || origH <= 0) return null

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
        val sampled = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null

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
            sampled.recycle()
            return null
        }

        // 5. Compress to JPEG and base64-encode
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            Pair(b64, "image/jpeg")
        } finally {
            bitmap.recycle()
        }
    }
}
