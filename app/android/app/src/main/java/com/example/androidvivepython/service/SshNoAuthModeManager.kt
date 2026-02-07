package jp.espresso3389.kugutz.service

import android.content.Context
import java.io.File

class SshNoAuthModeManager(context: Context) {
    private val stateFile: File

    init {
        val dir = File(context.filesDir, "protected/ssh")
        dir.mkdirs()
        stateFile = File(dir, "noauth_mode")
    }

    fun start(ttlSeconds: Int = DEFAULT_TTL): NoAuthState {
        val ttl = ttlSeconds.coerceIn(MIN_TTL, MAX_TTL)
        val expiresAt = System.currentTimeMillis() + ttl * 1000L
        stateFile.writeText("${expiresAt / 1000}")
        return NoAuthState(active = true, expiresAt = expiresAt)
    }

    fun stop() {
        if (stateFile.exists()) {
            stateFile.delete()
        }
    }

    fun status(): NoAuthState {
        val raw = try {
            if (!stateFile.exists()) return NoAuthState(active = false, expiresAt = null)
            stateFile.readText().trim()
        } catch (_: Exception) {
            return NoAuthState(active = false, expiresAt = null)
        }
        val expiresSec = raw.toLongOrNull() ?: 0L
        val expiresAt = expiresSec * 1000L
        if (expiresAt <= System.currentTimeMillis()) {
            return NoAuthState(active = false, expiresAt = expiresAt, expired = true)
        }
        return NoAuthState(active = true, expiresAt = expiresAt)
    }

    data class NoAuthState(
        val active: Boolean,
        val expiresAt: Long?,
        val expired: Boolean = false
    )

    companion object {
        private const val DEFAULT_TTL = 30
        private const val MIN_TTL = 5
        private const val MAX_TTL = 600
    }
}

