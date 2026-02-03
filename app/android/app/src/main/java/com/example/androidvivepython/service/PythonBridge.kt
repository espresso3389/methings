package jp.espresso3389.kugutz.service

import android.util.Log
import java.io.File

object PythonBridge {
    private const val TAG = "PythonBridge"
    private var libsLoaded = false

    fun loadNativeLibs(nativeLibDir: String) {
        if (libsLoaded) {
            return
        }
        val dir = File(nativeLibDir)
        val libs = listOf(
            "libcrypto1.1.so",
            "libssl1.1.so",
            "libsqlcipher.so",
            "libsqlite3.so",
            "libpython3.11.so"
        )
        for (lib in libs) {
            val path = File(dir, lib)
            if (path.exists()) {
                try {
                    System.load(path.absolutePath)
                } catch (ex: UnsatisfiedLinkError) {
                    Log.w(TAG, "Failed to load $lib", ex)
                }
            }
        }
        System.loadLibrary("pythonbridge")
        libsLoaded = true
    }

    external fun start(
        pythonHome: String,
        serverDir: String,
        keyFile: String?,
        nativeLibDir: String
    ): Int

    external fun stop(): Int
}
