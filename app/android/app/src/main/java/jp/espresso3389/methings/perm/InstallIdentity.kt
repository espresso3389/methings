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
        if (existing.isNotBlank()) return existing
        val created = "install_" + UUID.randomUUID().toString()
        prefs.edit().putString(KEY_IDENTITY, created).apply()
        return created
    }

    companion object {
        private const val PREFS = "kugutz_install"
        private const val KEY_IDENTITY = "identity"
    }
}

