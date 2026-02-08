package jp.espresso3389.methings.perm

import android.content.Context
import android.content.SharedPreferences

class SshKeyPolicy(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isBiometricRequired(): Boolean {
        return prefs.getBoolean(KEY_REQUIRE_BIOMETRIC, true)
    }

    fun setBiometricRequired(required: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_BIOMETRIC, required).apply()
    }

    companion object {
        private const val PREFS = "ssh_key_policy"
        private const val KEY_REQUIRE_BIOMETRIC = "require_biometric"
    }
}
