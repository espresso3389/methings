package jp.espresso3389.kugutz.service

import android.content.Context
import java.io.File
import java.security.SecureRandom

class SshPinManager(context: Context) {
    private val pinFile: File
    private val random = SecureRandom()

    init {
        val dir = File(context.filesDir, "protected/ssh")
        dir.mkdirs()
        pinFile = File(dir, "pin_auth")
    }

    fun startPin(ttlSeconds: Int = DEFAULT_TTL): PinState {
        val ttl = ttlSeconds.coerceIn(MIN_TTL, MAX_TTL)
        val pin = String.format("%06d", random.nextInt(1_000_000))
        val expiresAt = System.currentTimeMillis() + ttl * 1000L
        pinFile.writeText("${expiresAt / 1000} $pin")
        return PinState(active = true, pin = pin, expiresAt = expiresAt)
    }

    fun stopPin() {
        if (pinFile.exists()) {
            pinFile.delete()
        }
    }

    fun status(): PinState {
        val raw = try {
            if (!pinFile.exists()) return PinState(active = false, pin = null, expiresAt = null)
            pinFile.readText().trim()
        } catch (_: Exception) {
            return PinState(active = false, pin = null, expiresAt = null)
        }
        val parts = raw.split(" ")
        if (parts.size < 2) {
            return PinState(active = false, pin = null, expiresAt = null)
        }
        val expiresSec = parts[0].toLongOrNull() ?: 0L
        val expiresAt = expiresSec * 1000L
        if (expiresAt <= System.currentTimeMillis()) {
            return PinState(active = false, pin = null, expiresAt = expiresAt, expired = true)
        }
        return PinState(active = true, pin = null, expiresAt = expiresAt)
    }

    data class PinState(
        val active: Boolean,
        val pin: String?,
        val expiresAt: Long?,
        val expired: Boolean = false
    )

    companion object {
        private const val DEFAULT_TTL = 10
        private const val MIN_TTL = 5
        private const val MAX_TTL = 60
    }
}
