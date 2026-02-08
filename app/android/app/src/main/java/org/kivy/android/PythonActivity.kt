package org.kivy.android

import android.app.Activity

/**
 * Minimal compatibility shim for Python-for-Android environments.
 *
 * Some stdlib / third-party Python code paths (notably ctypes.util on Android in
 * certain p4a builds) attempt to access `org.kivy.android.PythonActivity` to
 * locate the app's `nativeLibraryDir`. methings is not a Kivy app, but providing
 * this class avoids hard failures and lets pure-Python packages work normally.
 */
class PythonActivity : Activity() {
    companion object {
        @JvmField
        var mActivity: Activity? = null
    }
}
