package jp.espresso3389.methings.vision

import com.google.android.renderscript.Toolkit
import com.google.android.renderscript.YuvFormat

object ImageConvert {
    /**
     * Convert NV21 (YUV420sp) to RGBA8888.
     *
     * This uses RenderScript Toolkit CPU intrinsics (fast and small; avoids OpenCV).
     */
    fun nv21ToRgba(nv21: ByteArray, width: Int, height: Int): RgbaFrame {
        require(width > 0 && height > 0) { "invalid_size" }
        // Toolkit returns 4 bytes per pixel (RGBA8888).
        val rgba = Toolkit.yuvToRgb(nv21, width, height, YuvFormat.NV21)
        require(rgba.size == width * height * 4) { "unexpected_output_size" }
        return RgbaFrame(width, height, rgba)
    }

    /**
     * Convert YUYV (YUY2) to RGBA8888.
     *
     * Note: RenderScript Toolkit does not provide YUYV conversion. This is a straightforward scalar
     * implementation; it is OK for snapshots / low FPS. For high-rate streaming, prefer NV21 or
     * add a native SIMD conversion (libyuv / NEON).
     */
    fun yuyvToRgba(yuyv: ByteArray, width: Int, height: Int): RgbaFrame {
        require(width > 0 && height > 0) { "invalid_size" }
        val expected = width * height * 2
        require(yuyv.size >= expected) { "yuyv_too_small" }

        val out = ByteArray(width * height * 4)
        var si = 0
        var di = 0
        val n = expected
        while (si + 3 < n) {
            val y0 = yuyv[si].toInt() and 0xFF
            val u = yuyv[si + 1].toInt() and 0xFF
            val y1 = yuyv[si + 2].toInt() and 0xFF
            val v = yuyv[si + 3].toInt() and 0xFF

            // BT.601 conversion (integer-ish).
            di = writeRgbaPixel(out, di, y0, u, v)
            di = writeRgbaPixel(out, di, y1, u, v)
            si += 4
        }
        return RgbaFrame(width, height, out)
    }

    private fun clamp8(x: Int): Int = when {
        x < 0 -> 0
        x > 255 -> 255
        else -> x
    }

    private fun writeRgbaPixel(dst: ByteArray, di0: Int, y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val d = u - 128
        val e = v - 128
        // R = 1.164*C + 1.596*E
        // G = 1.164*C - 0.392*D - 0.813*E
        // B = 1.164*C + 2.017*D
        val r = clamp8((298 * c + 409 * e + 128) shr 8)
        val g = clamp8((298 * c - 100 * d - 208 * e + 128) shr 8)
        val b = clamp8((298 * c + 516 * d + 128) shr 8)
        var di = di0
        dst[di++] = r.toByte()
        dst[di++] = g.toByte()
        dst[di++] = b.toByte()
        dst[di++] = 0xFF.toByte()
        return di
    }
}
