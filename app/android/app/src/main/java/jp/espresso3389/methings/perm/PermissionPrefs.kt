package jp.espresso3389.methings.perm

import android.content.Context

class PermissionPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rememberApprovals(): Boolean = prefs.getBoolean(KEY_REMEMBER_APPROVALS, true)

    fun setRememberApprovals(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMEMBER_APPROVALS, enabled).apply()
    }

    fun dangerouslySkipPermissions(): Boolean = prefs.getBoolean(KEY_DANGEROUSLY_SKIP_PERMISSIONS, false)

    fun setDangerouslySkipPermissions(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DANGEROUSLY_SKIP_PERMISSIONS, enabled).apply()
    }

    companion object {
        private const val PREFS = "methings_permission_prefs"
        private const val KEY_REMEMBER_APPROVALS = "remember_approvals"
        private const val KEY_DANGEROUSLY_SKIP_PERMISSIONS = "dangerously_skip_permissions"
    }
}
