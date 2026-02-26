package jp.espresso3389.methings.service.agent

import android.content.Context
import android.util.Log
import jp.espresso3389.methings.service.PythonRuntimeInstaller
import jp.espresso3389.methings.service.WheelhousePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unified shell executor providing one-shot command execution (ProcessBuilder)
 * and PTY-based persistent sessions (JNI forkpty). Replaces both the Python
 * worker and the old pipe-based NativeShellExecutor.
 */
class ShellExecutor(private val context: Context) {

    private val userDir = File(context.filesDir, "user")
    private val defaultCwd: File get() = userDir

    // ── Environment builder ────────────────────────────────────────────

    /**
     * Build the full shell environment map, mirroring what SshdManager
     * provides for SSH sessions.
     */
    fun buildEnv(extra: JSONObject? = null): Map<String, String> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binDir = File(context.filesDir, "bin")
        val pyenvDir = File(context.filesDir, "pyenv")
        val serverDir = File(context.filesDir, "server")
        val nodeRoot = File(context.filesDir, "node")
        val nodeLibDir = File(nodeRoot, "lib").absolutePath
        val npmBin = File(userDir, "npm-prefix/bin").absolutePath
        val wheelhouse = WheelhousePaths.forCurrentAbi(context)?.also { it.ensureDirs() }

        val env = mutableMapOf<String, String>()

        // Core paths
        env["HOME"] = userDir.absolutePath
        env["USER"] = "methings"
        env["TMPDIR"] = userDir.absolutePath
        env["TERM"] = "xterm-256color"
        env["SHELL"] = "/system/bin/sh"

        // PATH: binDir + nativeLibDir + npm-prefix/bin + system
        val systemPath = System.getenv("PATH") ?: "/usr/bin:/bin"
        env["PATH"] = "${binDir.absolutePath}:$nativeLibDir:$npmBin:$systemPath"

        // METHINGS vars
        env["METHINGS_HOME"] = userDir.absolutePath
        env["METHINGS_PYENV"] = pyenvDir.absolutePath
        env["METHINGS_NATIVELIB"] = nativeLibDir
        env["METHINGS_BINDIR"] = binDir.absolutePath
        env["METHINGS_NODE_ROOT"] = nodeRoot.absolutePath
        val methingspyPath = File(nativeLibDir, "libmethingspy.so")
        if (methingspyPath.exists()) {
            env["METHINGS_PYTHON_EXE"] = methingspyPath.absolutePath
        }
        try {
            env["METHINGS_IDENTITY"] = jp.espresso3389.methings.perm.InstallIdentity(context).get()
        } catch (_: Throwable) {}

        // LD_LIBRARY_PATH
        env["LD_LIBRARY_PATH"] = "$nodeLibDir:$nativeLibDir"

        // Python
        env["PYTHONHOME"] = pyenvDir.absolutePath
        env["PYTHONPATH"] = listOf(
            serverDir.absolutePath,
            "${pyenvDir.absolutePath}/site-packages",
            "${pyenvDir.absolutePath}/modules",
            "${pyenvDir.absolutePath}/stdlib.zip"
        ).joinToString(":")

        // npm script shell
        val scriptShell = File(nativeLibDir, "libmethingssh.so").absolutePath
        env["npm_config_script_shell"] = scriptShell
        env["NPM_CONFIG_SCRIPT_SHELL"] = scriptShell

        // Wheelhouse
        if (wheelhouse != null) {
            env["METHINGS_WHEELHOUSE"] = wheelhouse.findLinksEnvValue()
            env["PIP_FIND_LINKS"] = wheelhouse.findLinksEnvValue()
        }

        // TLS cert bundle
        val managedCa = File(context.filesDir, "protected/ca/cacert.pem")
        val fallbackCertifi = File(pyenvDir, "site-packages/certifi/cacert.pem")
        val caFile = when {
            managedCa.exists() && managedCa.length() > 0 -> managedCa
            fallbackCertifi.exists() -> fallbackCertifi
            else -> null
        }
        if (caFile != null) {
            env["SSL_CERT_FILE"] = caFile.absolutePath
            env["PIP_CERT"] = caFile.absolutePath
            env["REQUESTS_CA_BUNDLE"] = caFile.absolutePath
        }

        // Merge caller-supplied env overrides
        if (extra != null) {
            val keys = extra.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                env[k] = extra.optString(k, "")
            }
        }

        return env
    }

    /** Build env as KEY=VALUE array suitable for JNI / execve. */
    fun buildEnvArray(extra: JSONObject? = null): Array<String> {
        return buildEnv(extra).map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    // ── One-shot execution ──────────────────────────────────────────────

    fun exec(
        command: String,
        cwd: String = "",
        timeoutMs: Long = 60_000,
        env: JSONObject? = null,
    ): JSONObject {
        val workDir = if (cwd.isNotEmpty()) File(cwd) else defaultCwd
        if (!workDir.isDirectory) {
            return JSONObject()
                .put("status", "error")
                .put("error", "invalid_cwd")
                .put("detail", "Working directory does not exist: $cwd")
        }

        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
        pb.directory(workDir)
        pb.redirectErrorStream(false)
        pb.environment().apply {
            putAll(buildEnv(env))
        }

        val process = pb.start()

        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()
        val stdoutThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    val buf = CharArray(4096)
                    var n: Int
                    while (reader.read(buf).also { n = it } != -1) {
                        stdoutBuilder.append(buf, 0, n)
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val stderrThread = Thread {
            try {
                BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
                    val buf = CharArray(4096)
                    var n: Int
                    while (reader.read(buf).also { n = it } != -1) {
                        stderrBuilder.append(buf, 0, n)
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(1000)
            stderrThread.join(1000)
            return JSONObject()
                .put("status", "timeout")
                .put("exit_code", -1)
                .put("stdout", stdoutBuilder.toString())
                .put("stderr", "Command timed out after ${timeoutMs / 1000}s")
        }

        stdoutThread.join(2000)
        stderrThread.join(2000)

        return JSONObject()
            .put("status", "ok")
            .put("exit_code", process.exitValue())
            .put("stdout", stdoutBuilder.toString())
            .put("stderr", stderrBuilder.toString())
    }

    // ── PTY sessions ────────────────────────────────────────────────────

    private val sessions = ConcurrentHashMap<String, PtySession>()
    private val reaper = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pty-session-reaper").apply { isDaemon = true }
    }

    init {
        reaper.scheduleAtFixedRate({
            try { reapIdleSessions() } catch (e: Exception) {
                Log.w(TAG, "Session reaper error", e)
            }
        }, 5, 5, TimeUnit.MINUTES)
    }

    fun sessionStart(
        cwd: String = "",
        env: JSONObject? = null,
        rows: Int = 24,
        cols: Int = 80,
    ): JSONObject {
        val sessionId = UUID.randomUUID().toString().replace("-", "").take(12)
        val workDir = if (cwd.isNotEmpty()) File(cwd) else defaultCwd
        if (!workDir.isDirectory) {
            return JSONObject()
                .put("status", "error")
                .put("error", "invalid_cwd")
                .put("detail", "Working directory does not exist: $cwd")
        }

        val envArray = buildEnvArray(env)
        val shell = "/system/bin/sh"

        val result = PtyBridge.nativeCreateSession(shell, workDir.absolutePath, envArray, rows, cols)
        if (result == null || result.size < 2) {
            return JSONObject()
                .put("status", "error")
                .put("error", "pty_create_failed")
                .put("detail", "forkpty() failed")
        }

        val pid = result[0]
        val masterFd = result[1]

        val session = PtySession(sessionId, pid, masterFd, workDir)
        sessions[sessionId] = session

        // Wait briefly for initial prompt
        Thread.sleep(150)
        val initialOutput = session.readOutput()

        return JSONObject()
            .put("status", "ok")
            .put("session_id", sessionId)
            .put("pid", pid)
            .put("output", initialOutput)
    }

    fun sessionExec(sessionId: String, command: String, timeoutSec: Int = 30): JSONObject {
        val session = sessions[sessionId]
            ?: return JSONObject().put("status", "error").put("error", "session_not_found")

        if (!session.isAlive()) {
            return JSONObject()
                .put("status", "error")
                .put("error", "session_dead")
                .put("output", session.readOutput())
        }

        session.touchActivity()

        try {
            session.write(command + "\n")
        } catch (e: Exception) {
            return JSONObject()
                .put("status", "error")
                .put("error", "write_failed")
                .put("detail", e.message ?: "")
        }

        // Wait for output to settle (no new output for 200ms, or timeout)
        val deadlineMs = System.currentTimeMillis() + timeoutSec * 1000L
        var output = ""
        var lastLength = -1
        while (System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(200)
            output = session.readOutput()
            if (output.length == lastLength && output.isNotEmpty()) {
                break
            }
            lastLength = output.length
            if (!session.isAlive()) break
        }

        return JSONObject()
            .put("status", "ok")
            .put("output", output)
            .put("alive", session.isAlive())
    }

    fun sessionWrite(sessionId: String, input: String): JSONObject {
        val session = sessions[sessionId]
            ?: return JSONObject().put("status", "error").put("error", "session_not_found")

        if (!session.isAlive()) {
            return JSONObject().put("status", "error").put("error", "session_dead")
        }

        session.touchActivity()
        try {
            session.write(input)
        } catch (e: Exception) {
            return JSONObject()
                .put("status", "error")
                .put("error", "write_failed")
                .put("detail", e.message ?: "")
        }

        return JSONObject().put("status", "ok")
    }

    fun sessionRead(sessionId: String): JSONObject {
        val session = sessions[sessionId]
            ?: return JSONObject().put("status", "error").put("error", "session_not_found")

        session.touchActivity()
        return JSONObject()
            .put("status", "ok")
            .put("output", session.readOutput())
            .put("alive", session.isAlive())
    }

    fun sessionResize(sessionId: String, rows: Int, cols: Int): JSONObject {
        val session = sessions[sessionId]
            ?: return JSONObject().put("status", "error").put("error", "session_not_found")

        session.touchActivity()
        PtyBridge.nativeResize(session.masterFd, session.pid, rows, cols)
        return JSONObject().put("status", "ok")
    }

    fun sessionKill(sessionId: String): JSONObject {
        val session = sessions.remove(sessionId)
            ?: return JSONObject().put("status", "error").put("error", "session_not_found")

        session.destroy()
        return JSONObject().put("status", "ok")
    }

    fun sessionList(): JSONObject {
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        for ((id, s) in sessions) {
            arr.put(JSONObject().apply {
                put("session_id", id)
                put("alive", s.isAlive())
                put("pid", s.pid)
                put("idle_seconds", (now - s.lastActivity) / 1000)
                put("uptime_seconds", (now - s.startTime) / 1000)
            })
        }
        return JSONObject()
            .put("status", "ok")
            .put("sessions", arr)
    }

    private fun reapIdleSessions() {
        val now = System.currentTimeMillis()
        val iter = sessions.entries.iterator()
        while (iter.hasNext()) {
            val (id, session) = iter.next()
            if (!session.isAlive() || (now - session.lastActivity) > SESSION_IDLE_TIMEOUT_MS) {
                Log.i(TAG, "Reaping idle/dead PTY session $id (pid=${session.pid})")
                session.destroy()
                iter.remove()
            }
        }
    }

    fun shutdown() {
        reaper.shutdownNow()
        for ((_, session) in sessions) {
            session.destroy()
        }
        sessions.clear()
    }

    // ── PtySession ─────────────────────────────────────────────────────

    private class PtySession(
        val id: String,
        val pid: Int,
        val masterFd: Int,
        val workDir: File,
    ) {
        val startTime = System.currentTimeMillis()
        @Volatile var lastActivity = System.currentTimeMillis()

        private val outputBuffer = StringBuilder()
        private val closed = AtomicBoolean(false)

        // Background reader drains PTY master fd
        private val readerThread = Thread {
            try {
                while (!closed.get()) {
                    val data = PtyBridge.nativeRead(masterFd, 8192)
                    if (data != null && data.isNotEmpty()) {
                        val text = String(data, Charsets.UTF_8)
                        synchronized(outputBuffer) {
                            outputBuffer.append(text)
                        }
                    } else {
                        // Check if process exited
                        val exitCode = PtyBridge.nativeWaitpid(pid, true)
                        if (exitCode != -1) {
                            break // Process exited
                        }
                        // Nothing to read, brief sleep
                        Thread.sleep(20)
                    }
                }
            } catch (_: InterruptedException) {
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        fun isAlive(): Boolean {
            return PtyBridge.nativeWaitpid(pid, true) == -1
        }

        fun touchActivity() {
            lastActivity = System.currentTimeMillis()
        }

        fun write(data: String) {
            val bytes = data.toByteArray(Charsets.UTF_8)
            val written = PtyBridge.nativeWrite(masterFd, bytes)
            if (written < 0) {
                throw java.io.IOException("PTY write failed (fd=$masterFd)")
            }
        }

        fun readOutput(): String {
            synchronized(outputBuffer) {
                val content = outputBuffer.toString()
                outputBuffer.setLength(0)
                return content
            }
        }

        fun destroy() {
            if (closed.compareAndSet(false, true)) {
                try { PtyBridge.nativeKill(pid, 9) } catch (_: Exception) {} // SIGKILL
                try { PtyBridge.nativeClose(masterFd) } catch (_: Exception) {}
                readerThread.join(1000)
                try { PtyBridge.nativeWaitpid(pid, false) } catch (_: Exception) {} // Reap zombie
            }
        }
    }

    companion object {
        private const val TAG = "ShellExecutor"
        private const val SESSION_IDLE_TIMEOUT_MS = 30L * 60 * 1000 // 30 minutes
    }
}
