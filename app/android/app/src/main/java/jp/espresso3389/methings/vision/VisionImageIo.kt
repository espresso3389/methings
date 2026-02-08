package jp.espresso3389.methings.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

object VisionImageIo {
    fun decodeFileToRgba(path: File, maxPixels: Long = 16L * 1024L * 1024L): RgbaFrame {
        val bmp = BitmapFactory.decodeFile(path.absolutePath)
            ?: throw IllegalArgumentException("decode_failed")
        val argb = if (bmp.config != Bitmap.Config.ARGB_8888) {
            bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
        } else {
            bmp
        }
        val w = argb.width
        val h = argb.height
        val pixels = w.toLong() * h.toLong()
        if (pixels <= 0 || pixels > maxPixels) {
            argb.recycle()
            throw IllegalArgumentException("image_too_large")
        }
        val ints = IntArray(w * h)
        argb.getPixels(ints, 0, w, 0, 0, w, h)
        argb.recycle()
        val rgba = ByteArray(w * h * 4)
        var di = 0
        for (p in ints) {
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            rgba[di++] = r.toByte()
            rgba[di++] = g.toByte()
            rgba[di++] = b.toByte()
            rgba[di++] = a.toByte()
        }
        return RgbaFrame(w, h, rgba)
    }

    fun encodeRgbaToFile(
        frame: RgbaFrame,
        format: String,
        outFile: File,
        jpegQuality: Int = 90,
    ) {
        val fmt = format.trim().lowercase()
        val w = frame.width
        val h = frame.height
        val ints = IntArray(w * h)
        val rgba = frame.rgba
        var si = 0
        for (i in ints.indices) {
            val r = rgba[si++].toInt() and 0xFF
            val g = rgba[si++].toInt() and 0xFF
            val b = rgba[si++].toInt() and 0xFF
            val a = rgba[si++].toInt() and 0xFF
            ints[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(ints, 0, w, 0, 0, w, h)
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { fos ->
            when (fmt) {
                "png" -> bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                "jpg", "jpeg" -> bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(1, 100), fos)
                else -> throw IllegalArgumentException("unsupported_format")
            }
        }
        bmp.recycle()
    }

    fun decodeRgbaB64(width: Int, height: Int, dataB64: String): RgbaFrame {
        val raw = Base64.decode(dataB64, Base64.DEFAULT)
        return RgbaFrame(width, height, raw)
    }

    fun encodeRgbaB64(frame: RgbaFrame): String {
        return Base64.encodeToString(frame.rgba, Base64.NO_WRAP)
    }
}

