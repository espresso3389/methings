package jp.espresso3389.methings.service

import java.security.SecureRandom

class SshPinManager {
    private val random = SecureRandom()

    @Volatile private var currentPin: String? = null
    @Volatile private var expiresAt: Long = 0L

    fun startPin(ttlSeconds: Int = DEFAULT_TTL): PinState {
        val ttl = ttlSeconds.coerceIn(MIN_TTL, MAX_TTL)
        val pin = String.format("%06d", random.nextInt(1_000_000))
        val exp = System.currentTimeMillis() + ttl * 1000L
        currentPin = pin
        expiresAt = exp
        return PinState(active = true, pin = pin, expiresAt = exp)
    }

    fun stopPin() {
        currentPin = null
        expiresAt = 0L
    }

    fun status(): PinState {
        val pin = currentPin ?: return PinState(active = false, pin = null, expiresAt = null)
        if (expiresAt <= System.currentTimeMillis()) {
            return PinState(active = false, pin = null, expiresAt = expiresAt, expired = true)
        }
        return PinState(active = true, pin = pin, expiresAt = expiresAt)
    }

    fun verifyPin(pin: String): Boolean {
        val expected = currentPin ?: return false
        if (expiresAt <= System.currentTimeMillis()) return false
        return pin.trim() == expected
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
