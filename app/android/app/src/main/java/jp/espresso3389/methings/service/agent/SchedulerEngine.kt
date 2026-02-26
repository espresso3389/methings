package jp.espresso3389.methings.service.agent

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class SchedulerEngine(
    private val store: SchedulerStore,
    private val userDir: File,
    private val executeRunJs: (code: String, timeoutMs: Long) -> JsResult,
    private val executeShellExec: ((cmd: String, args: String, cwd: String) -> JSONObject)?,
) {
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "scheduler-tick").apply { isDaemon = true }
    }
    private val executionPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "scheduler-exec").apply { isDaemon = true }
    }
    private val runningSchedules = ConcurrentHashMap<String, Future<*>>()

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "SchedulerEngine starting")
        try {
            store.markStaleRunningAsInterrupted()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark stale logs", e)
        }

        // Run daemons
        try {
            for (schedule in store.listDaemonSchedules()) {
                submitExecution(schedule)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start daemon schedules", e)
        }

        // Catch up overdue periodic/one_time and recompute next_run_at
        try {
            val now = System.currentTimeMillis()
            val due = store.listDueSchedules(now)
            for (schedule in due) {
                submitExecution(schedule)
            }
            // Recompute next_run_at for all enabled periodic schedules from NOW
            for (schedule in store.listSchedules()) {
                if (schedule.enabled && schedule.launchType == "periodic") {
                    val next = SchedulerStore.computeNextRun(schedule.schedulePattern, now)
                    store.updateNextRunAt(schedule.id, next)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to catch up overdue schedules", e)
        }

        // Start 60-second ticker
        ticker.scheduleAtFixedRate({ tick() }, TICK_INTERVAL_S, TICK_INTERVAL_S, TimeUnit.SECONDS)
        Log.i(TAG, "SchedulerEngine started (${TICK_INTERVAL_S}s tick)")
    }

    fun stop() {
        if (!started) return
        started = false
        Log.i(TAG, "SchedulerEngine stopping")
        try { ticker.shutdownNow() } catch (_: Exception) {}
        // Cancel running executions
        for ((_, future) in runningSchedules) {
            try { future.cancel(true) } catch (_: Exception) {}
        }
        runningSchedules.clear()
        try { executionPool.shutdownNow() } catch (_: Exception) {}
        Log.i(TAG, "SchedulerEngine stopped")
    }

    fun triggerNow(scheduleId: String): JSONObject {
        val schedule = store.getSchedule(scheduleId)
            ?: return JSONObject().put("status", "error").put("error", "not_found")
        if (isRunning(scheduleId)) {
            return JSONObject().put("status", "error").put("error", "already_running")
        }
        submitExecution(schedule)
        return JSONObject().put("status", "ok").put("message", "triggered")
    }

    fun isRunning(scheduleId: String): Boolean {
        val future = runningSchedules[scheduleId] ?: return false
        return !future.isDone
    }

    fun status(): JSONObject {
        return JSONObject().apply {
            put("started", started)
            put("running_count", runningSchedules.count { !it.value.isDone })
            put("running_ids", org.json.JSONArray().apply {
                runningSchedules.forEach { (id, f) -> if (!f.isDone) put(id) }
            })
        }
    }

    private fun tick() {
        if (!started) return
        try {
            val now = System.currentTimeMillis()
            val due = store.listDueSchedules(now)
            for (schedule in due) {
                if (!isRunning(schedule.id)) {
                    submitExecution(schedule)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Scheduler tick failed", e)
        }
    }

    private fun submitExecution(schedule: ScheduleRow) {
        if (runningSchedules.containsKey(schedule.id)) {
            val existing = runningSchedules[schedule.id]
            if (existing != null && !existing.isDone) return
        }
        val future = executionPool.submit {
            executeSchedule(schedule)
        }
        runningSchedules[schedule.id] = future
    }

    private fun executeSchedule(schedule: ScheduleRow) {
        val startedAt = System.currentTimeMillis()
        var logId = -1L
        try {
            logId = store.insertExecutionLog(schedule.id, startedAt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to insert execution log for ${schedule.id}", e)
        }

        var status = "ok"
        var result: String? = null
        var consoleOutput: String? = null
        var error: String? = null

        try {
            when (schedule.runtime) {
                "run_js" -> {
                    val jsResult = executeRunJs(schedule.code, schedule.timeoutMs)
                    status = jsResult.status
                    result = jsResult.result
                    consoleOutput = jsResult.consoleOutput
                    if (jsResult.error.isNotEmpty()) {
                        error = jsResult.error
                        if (status != "ok") status = "error"
                    }
                }
                "run_python" -> {
                    val shellExec = executeShellExec
                    if (shellExec == null) {
                        status = "error"
                        error = "shell_unavailable: embedded worker not available"
                    } else {
                        val tempFile = File(userDir, ".scheduler_tmp_${schedule.id}.py")
                        try {
                            tempFile.writeText(schedule.code)
                            val cwd = schedule.cwd.ifBlank { userDir.absolutePath }
                            val args = buildString {
                                append(tempFile.absolutePath)
                                if (schedule.args.isNotBlank()) {
                                    append(" ")
                                    append(schedule.args)
                                }
                            }
                            val shellResult = shellExec("python", args, cwd)
                            val shellStatus = shellResult.optString("status", "error")
                            result = shellResult.optString("stdout", "")
                            consoleOutput = shellResult.optString("stderr", "")
                            if (shellStatus != "ok" && shellStatus != "success") {
                                status = "error"
                                error = shellResult.optString("error", "shell_exec_failed")
                            }
                        } finally {
                            try { tempFile.delete() } catch (_: Exception) {}
                        }
                    }
                }
                else -> {
                    status = "error"
                    error = "unsupported_runtime: ${schedule.runtime}"
                }
            }
        } catch (e: Exception) {
            status = "error"
            error = e.message ?: "execution_failed"
        }

        val finishedAt = System.currentTimeMillis()
        val isError = status != "ok"

        // Update execution log
        if (logId > 0) {
            try {
                store.finishExecutionLog(logId, finishedAt, status, result, consoleOutput, error)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to finalize execution log $logId", e)
            }
        }

        // Update schedule state
        try {
            store.updateLastRun(schedule.id, startedAt, incrementRun = true, incrementError = isError)

            when (schedule.launchType) {
                "one_time" -> {
                    store.setEnabled(schedule.id, false)
                    store.updateNextRunAt(schedule.id, null)
                }
                "periodic" -> {
                    val now = System.currentTimeMillis()
                    val next = SchedulerStore.computeNextRun(schedule.schedulePattern, now)
                    store.updateNextRunAt(schedule.id, next)
                }
                // daemon: no next_run_at update needed
            }

            store.pruneExecutionLog(schedule.id)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update schedule state for ${schedule.id}", e)
        }

        runningSchedules.remove(schedule.id)
        Log.d(TAG, "Schedule ${schedule.id} (${schedule.name}) finished: $status (${finishedAt - startedAt}ms)")
    }

    companion object {
        private const val TAG = "SchedulerEngine"
        private const val TICK_INTERVAL_S = 60L
    }
}
