package jp.espresso3389.methings.perm

import android.content.Context
import java.util.UUID

/**
 * Stable, per-install identity used for permission reuse.
 *
 * This mimics Android runtime permission behavior: once a user approves an action,
 * we can treat that approval as long-lived until explicitly revoked/cleared.
 */
class InstallIdentity(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(): String {
        val existing = (prefs.getString(KEY_IDENTITY, null) ?: "").trim()
        if (existing.isNotBlank()) {
            val migrated = migrateLegacyIdentity(existing)
            if (migrated != existing) prefs.edit().putString(KEY_IDENTITY, migrated).apply()
            return migrated
        }
        val created = newIdentity()
        prefs.edit().putString(KEY_IDENTITY, created).apply()
        return created
    }

    fun reset(): String {
        val created = newIdentity()
        prefs.edit().putString(KEY_IDENTITY, created).apply()
        return created
    }

    private fun newIdentity(): String {
        // Short per-install ID for device-to-device UX, e.g. d_a1b2c3.
        val shortHex = UUID.randomUUID().toString().replace("-", "").take(6)
        return "d_$shortHex"
    }

    private fun migrateLegacyIdentity(existing: String): String {
        if (!existing.startsWith("install_")) return existing
        val raw = existing.removePrefix("install_").replace("-", "")
        val shortHex = raw.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            .take(6)
            .lowercase()
        return if (shortHex.length == 6) "d_$shortHex" else newIdentity()
    }

    companion object {
        private const val PREFS = "methings_install"
        private const val KEY_IDENTITY = "identity"
    }
}
