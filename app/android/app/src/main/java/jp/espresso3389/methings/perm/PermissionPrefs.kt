package jp.espresso3389.methings.perm

import android.content.Context

class PermissionPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rememberApprovals(): Boolean = prefs.getBoolean(KEY_REMEMBER_APPROVALS, true)

    fun setRememberApprovals(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMEMBER_APPROVALS, enabled).apply()
    }

    companion object {
        private const val PREFS = "kugutz_permission_prefs"
        private const val KEY_REMEMBER_APPROVALS = "remember_approvals"
    }
}

