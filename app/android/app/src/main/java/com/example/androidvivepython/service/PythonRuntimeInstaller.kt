package jp.espresso3389.kugutz.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class PythonRuntimeInstaller(private val context: Context) {
    private val extractor = AssetExtractor(context)

    fun ensureInstalled(): Boolean {
        val pythonHome = File(context.filesDir, "pyenv")
        val stdlibZip = File(pythonHome, "stdlib.zip")
        val pipPackage = File(pythonHome, "site-packages/pip")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedVersion = prefs.getLong(KEY_RUNTIME_VERSION, -1)
        val currentVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to read app version", ex)
            -1L
        }
        val assetStamp = readAssetStamp()
        val installedStamp = readInstalledStamp(pythonHome)

        val needsInstall = !stdlibZip.exists() ||
            !pipPackage.exists() ||
            storedVersion != currentVersion ||
            (assetStamp != null && assetStamp != installedStamp)
        if (!needsInstall) {
            ensureStdlibZipAlias(pythonHome)
            ensureEncodingsExtracted(pythonHome)
            ensureSiteCustomize(pythonHome)
            ensurePythonBinaries()
            return true
        }

        // Attempt to extract packaged runtime assets under assets/pyenv.
        return try {
            if (pythonHome.exists()) {
                pythonHome.deleteRecursively()
            }
            extractor.extractPythonRuntime()
            val ok = stdlibZip.exists()
            if (ok && currentVersion != -1L) {
                prefs.edit().putLong(KEY_RUNTIME_VERSION, currentVersion).apply()
            }
            if (ok && assetStamp != null) {
                writeInstalledStamp(pythonHome, assetStamp)
            }
            if (ok) {
                ensureStdlibZipAlias(pythonHome)
                ensureEncodingsExtracted(pythonHome)
                ensureSiteCustomize(pythonHome)
                ensurePythonBinaries()
            }
            ok
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to install Python runtime", ex)
            false
        }
    }

    /**
     * pip build isolation rewrites PYTHONPATH and can drop our custom stdlib.zip entry.
     * CPython still auto-adds $PYTHONHOME/lib/pythonXY?.zip, so create that zip as an alias
     * of stdlib.zip to keep core modules (e.g. encodings) importable in subprocesses.
     */
    private fun ensureStdlibZipAlias(pythonHome: File) {
        try {
            val stdlibZip = File(pythonHome, "stdlib.zip")
            if (!stdlibZip.exists() || stdlibZip.length() <= 0L) return

            val libDir = File(pythonHome, "lib")
            libDir.mkdirs()

            // Find python version directory like lib/python3.11 and derive python311.zip.
            val verDir = libDir.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("python3.") }
            val verName = verDir?.name ?: "python3.11"
            val m = Regex("""python(\d+)\.(\d+)""").find(verName)
            val major = m?.groupValues?.getOrNull(1) ?: "3"
            val minor = m?.groupValues?.getOrNull(2) ?: "11"
            val zipName = "python${major}${minor}.zip"
            val target = File(libDir, zipName)

            if (target.exists() && target.length() == stdlibZip.length()) return
            val tmp = File(libDir, "$zipName.tmp")
            stdlibZip.inputStream().use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to ensure stdlib zip alias", ex)
        }
    }

    /**
     * Some pip build-isolation subprocesses appear to fail early if the stdlib is only present
     * in a zip. To make startup robust, extract the encodings package into lib/pythonX.Y so
     * init_fs_encoding can import it without relying on zipimport.
     */
    private fun ensureEncodingsExtracted(pythonHome: File) {
        try {
            val stdlibZip = File(pythonHome, "stdlib.zip")
            if (!stdlibZip.exists() || stdlibZip.length() <= 0L) return

            val libDir = File(pythonHome, "lib")
            libDir.mkdirs()

            val verDir = libDir.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("python3.") }
            val verName = verDir?.name ?: "python3.11"
            val stdlibDir = File(libDir, verName)
            stdlibDir.mkdirs()

            val encDir = File(stdlibDir, "encodings")
            val marker = File(encDir, "aliases.py")
            if (marker.exists() && marker.length() > 0L) return

            encDir.mkdirs()
            ZipInputStream(stdlibZip.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = e.name ?: ""
                    if (!name.startsWith("encodings/") || name.contains("..")) {
                        continue
                    }
                    if (e.isDirectory) {
                        File(stdlibDir, name).mkdirs()
                        continue
                    }
                    val outFile = File(stdlibDir, name)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zin.copyTo(out) }
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to extract encodings package", ex)
        }
    }

    /**
     * Make our non-standard layout robust to pip build isolation.
     *
     * Build isolation often sets PYTHONPATH to a temporary build-env directory, which can
     * drop our custom module paths (pyenv/site-packages, pyenv/modules). That can break
     * imports for core extension modules like zlib.
     *
     * sitecustomize.py is imported by the stdlib site module (if present on sys.path),
     * so place it under lib/pythonX.Y which is always on sys.path for our PYTHONHOME.
     */
    private fun ensureSiteCustomize(pythonHome: File) {
        try {
            val libDir = File(pythonHome, "lib")
            libDir.mkdirs()
            val verDir = libDir.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("python3.") }
            val verName = verDir?.name ?: "python3.11"
            val stdlibDir = File(libDir, verName)
            stdlibDir.mkdirs()

            val f = File(stdlibDir, "sitecustomize.py")
            val content = """
                # Auto-generated by Kugutz.
                #
                # Ensure Kugutz's embedded runtime directories are present on sys.path even when
                # pip build isolation overrides PYTHONPATH.
                import os
                import sys

                _home = sys.prefix
                _paths = [
                    os.path.join(_home, "site-packages"),
                    os.path.join(_home, "modules"),
                    os.path.join(_home, "stdlib.zip"),
                ]
                for _p in _paths:
                    if _p and _p not in sys.path and os.path.exists(_p):
                        sys.path.append(_p)
            """.trimIndent() + "\n"

            if (f.exists()) {
                val existing = runCatching { f.readText() }.getOrNull() ?: ""
                if (existing == content) return
            }
            f.writeText(content)
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to write sitecustomize.py", ex)
        }
    }

    /**
     * Create wrapper scripts in bin/ that exec libkugutzpy.so from the native
     * lib directory.  Running from nativeLibraryDir avoids SELinux app_data_file
     * execute restrictions that would block a copied binary.
     */
    /**
     * Ensure the bin/ directory exists for other tools (dropbear, kugutzsh).
     * python3/pip commands are handled by dropbear shell function injection
     * (avoids SELinux app_data_file execution restrictions).
     */
    private fun ensurePythonBinaries() {
        try {
            val binDir = File(context.filesDir, "bin")
            binDir.mkdirs()
            // Clean up any stale python/pip entries from previous versions
            for (name in listOf("python3", "python", "pip", "pip3")) {
                val f = File(binDir, name)
                if (f.exists()) f.delete()
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to set up bin directory", ex)
        }
    }

    private fun readAssetStamp(): String? {
        return try {
            context.assets.open("pyenv/.runtime_stamp").use { input ->
                BufferedReader(InputStreamReader(input)).readLine()?.trim()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readInstalledStamp(pythonHome: File): String? {
        val stampFile = File(pythonHome, ".runtime_stamp")
        return try {
            if (!stampFile.exists()) {
                null
            } else {
                stampFile.readText().trim()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeInstalledStamp(pythonHome: File, stamp: String) {
        try {
            File(pythonHome, ".runtime_stamp").writeText(stamp)
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to write runtime stamp", ex)
        }
    }

    companion object {
        private const val TAG = "PythonRuntimeInstaller"
        private const val PREFS_NAME = "python_runtime"
        private const val KEY_RUNTIME_VERSION = "runtime_version"
    }
}
