package jp.espresso3389.methings.service.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native shell executor using ProcessBuilder with /system/bin/sh.
 * Provides one-shot command execution and pipe-based persistent sessions
 * as a fallback when Termux is unavailable.
 */
class NativeShellExecutor(private val defaultCwd: File) {

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
            put("HOME", defaultCwd.absolutePath)
            put("TMPDIR", defaultCwd.absolutePath)
            put("SHELL", "/system/bin/sh")
            put("TERM", "dumb")
            if (env != null) {
                val keys = env.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, env.optString(k, ""))
                }
            }
        }

        val process = pb.start()

        // Read stdout and stderr on background threads to avoid pipe buffer deadlocks
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
                .put("backend", "native")
        }

        stdoutThread.join(2000)
        stderrThread.join(2000)

        return JSONObject()
            .put("status", "ok")
            .put("exit_code", process.exitValue())
            .put("stdout", stdoutBuilder.toString())
            .put("stderr", stderrBuilder.toString())
            .put("backend", "native")
    }

    // ── Pipe-based sessions ─────────────────────────────────────────────

    private val sessions = ConcurrentHashMap<String, NativeSession>()
    private val reaper = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "native-session-reaper").apply { isDaemon = true }
    }

    init {
        reaper.scheduleAtFixedRate({
            try { reapIdleSessions() } catch (e: Exception) {
                Log.w(TAG, "Session reaper error", e)
            }
        }, 5, 5, TimeUnit.MINUTES)
    }

    fun sessionStart(cwd: String = "", env: JSONObject? = null): JSONObject {
        val sessionId = UUID.randomUUID().toString().replace("-", "").take(12)
        val workDir = if (cwd.isNotEmpty()) File(cwd) else defaultCwd
        if (!workDir.isDirectory) {
            return JSONObject()
                .put("status", "error")
                .put("error", "invalid_cwd")
                .put("detail", "Working directory does not exist: $cwd")
        }

        val pb = ProcessBuilder("/system/bin/sh")
        pb.directory(workDir)
        pb.redirectErrorStream(true) // merge stderr into stdout for sessions
        pb.environment().apply {
            put("HOME", defaultCwd.absolutePath)
            put("TMPDIR", defaultCwd.absolutePath)
            put("SHELL", "/system/bin/sh")
            put("TERM", "dumb")
            put("PS1", "$ ")
            if (env != null) {
                val keys = env.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, env.optString(k, ""))
                }
            }
        }

        val process = pb.start()
        val session = NativeSession(sessionId, process, workDir)
        sessions[sessionId] = session

        // Wait briefly for initial prompt
        Thread.sleep(100)
        val initialOutput = session.readOutput()

        return JSONObject()
            .put("status", "ok")
            .put("session_id", sessionId)
            .put("output", initialOutput)
            .put("backend", "native")
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

        // Write command + newline
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
                break // output has settled
            }
            lastLength = output.length
            if (!session.isAlive()) break
        }

        return JSONObject()
            .put("status", "ok")
            .put("output", output)
            .put("alive", session.isAlive())
            .put("backend", "native")
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

        return JSONObject().put("status", "ok").put("backend", "native")
    }

    fun sessionRead(sessionId: String): JSONObject {
        val session = sessions[sessionId]
            ?: return JSONObject().put("status", "error").put("error", "session_not_found")

        session.touchActivity()
        return JSONObject()
            .put("status", "ok")
            .put("output", session.readOutput())
            .put("alive", session.isAlive())
            .put("backend", "native")
    }

    fun sessionKill(sessionId: String): JSONObject {
        val session = sessions.remove(sessionId)
            ?: return JSONObject().put("status", "error").put("error", "session_not_found")

        session.destroy()
        return JSONObject().put("status", "ok").put("backend", "native")
    }

    fun sessionList(): JSONObject {
        val arr = JSONArray()
        val now = System.currentTimeMillis()
        for ((id, s) in sessions) {
            arr.put(JSONObject().apply {
                put("session_id", id)
                put("alive", s.isAlive())
                put("idle_seconds", (now - s.lastActivity) / 1000)
                put("uptime_seconds", (now - s.startTime) / 1000)
            })
        }
        return JSONObject()
            .put("status", "ok")
            .put("sessions", arr)
            .put("backend", "native")
    }

    private fun reapIdleSessions() {
        val now = System.currentTimeMillis()
        val iter = sessions.entries.iterator()
        while (iter.hasNext()) {
            val (id, session) = iter.next()
            if (!session.isAlive() || (now - session.lastActivity) > SESSION_IDLE_TIMEOUT_MS) {
                Log.i(TAG, "Reaping idle/dead native session $id")
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

    // ── NativeSession ───────────────────────────────────────────────────

    private class NativeSession(
        val id: String,
        val process: Process,
        val workDir: File,
    ) {
        val startTime = System.currentTimeMillis()
        @Volatile var lastActivity = System.currentTimeMillis()

        private val outputBuffer = StringBuilder()
        private val closed = AtomicBoolean(false)
        private val writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)

        // Background reader that continuously drains stdout+stderr
        private val readerThread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                val buf = CharArray(4096)
                var n: Int
                while (reader.read(buf).also { n = it } != -1) {
                    synchronized(outputBuffer) {
                        outputBuffer.append(buf, 0, n)
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        fun isAlive(): Boolean = process.isAlive

        fun touchActivity() {
            lastActivity = System.currentTimeMillis()
        }

        fun write(data: String) {
            writer.write(data)
            writer.flush()
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
                try { writer.close() } catch (_: Exception) {}
                process.destroyForcibly()
                readerThread.join(1000)
            }
        }
    }

    companion object {
        private const val TAG = "NativeShellExecutor"
        private const val SESSION_IDLE_TIMEOUT_MS = 30L * 60 * 1000 // 30 minutes
    }
}
