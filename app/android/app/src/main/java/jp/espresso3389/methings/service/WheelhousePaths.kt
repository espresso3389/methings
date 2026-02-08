package jp.espresso3389.methings.service

import android.content.Context
import java.io.File

object WheelhousePaths {
    data class Paths(
        val root: File,
        val bundled: File,
        val user: File
    ) {
        fun ensureDirs() {
            root.mkdirs()
            bundled.mkdirs()
            user.mkdirs()
        }

        fun findLinksArgs(): List<String> {
            // Prefer bundled first (app-provided), then user cache.
            return listOf(
                "--find-links",
                bundled.absolutePath,
                "--find-links",
                user.absolutePath
            )
        }

        fun findLinksEnvValue(): String {
            // pip treats this like the config value for `--find-links`; whitespace-separated works for paths.
            return bundled.absolutePath + " " + user.absolutePath
        }
    }

    fun forCurrentAbi(context: Context): Paths? {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val root = File(context.filesDir, "wheelhouse/$abi")
        return Paths(
            root = root,
            bundled = File(root, "bundled"),
            user = File(root, "user")
        )
    }
}

