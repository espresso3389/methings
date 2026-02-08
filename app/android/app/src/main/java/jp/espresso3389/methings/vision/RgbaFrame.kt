package jp.espresso3389.methings.vision

import java.nio.ByteBuffer

/**
 * Internal image/frame format for methings vision pipeline.
 *
 * - Pixel format: RGBA8888
 * - Byte order: repeating [R, G, B, A] bytes
 */
data class RgbaFrame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "invalid_size" }
        require(rgba.size == width * height * 4) { "invalid_rgba_length" }
    }

    fun asDirectBuffer(): ByteBuffer {
        // Convenience; callers doing high-rate processing should manage their own reusable buffers.
        return ByteBuffer.allocateDirect(rgba.size).apply {
            put(rgba)
            rewind()
        }
    }
}
