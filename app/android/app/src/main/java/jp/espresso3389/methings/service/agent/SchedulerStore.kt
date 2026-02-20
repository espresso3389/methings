package jp.espresso3389.methings.service.agent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.UUID

data class ScheduleRow(
    val id: String,
    val name: String,
    val launchType: String,
    val schedulePattern: String,
    val runtime: String,
    val code: String,
    val args: String,
    val cwd: String,
    val timeoutMs: Long,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long,
    val nextRunAt: Long?,
    val runCount: Int,
    val errorCount: Int,
    val meta: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("launch_type", launchType)
        put("schedule_pattern", schedulePattern)
        put("runtime", runtime)
        put("code", code)
        put("args", args)
        put("cwd", cwd)
        put("timeout_ms", timeoutMs)
        put("enabled", enabled)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("last_run_at", lastRunAt)
        put("next_run_at", nextRunAt ?: JSONObject.NULL)
        put("run_count", runCount)
        put("error_count", errorCount)
        put("meta", meta)
    }
}

data class ExecutionLogRow(
    val id: Long,
    val scheduleId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val status: String,
    val result: String?,
    val consoleOutput: String?,
    val error: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("schedule_id", scheduleId)
        put("started_at", startedAt)
        put("finished_at", finishedAt)
        put("status", status)
        put("result", result ?: JSONObject.NULL)
        put("console_output", consoleOutput ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
    }
}

class SchedulerStore(context: Context) : SQLiteOpenHelper(
    context,
    File(context.filesDir, "agent/scheduler.db").also { it.parentFile?.mkdirs() }.absolutePath,
    null,
    DB_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS schedules (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                launch_type TEXT NOT NULL,
                schedule_pattern TEXT NOT NULL DEFAULT '',
                runtime TEXT NOT NULL,
                code TEXT NOT NULL,
                args TEXT NOT NULL DEFAULT '',
                cwd TEXT NOT NULL DEFAULT '',
                timeout_ms INTEGER NOT NULL DEFAULT 60000,
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_run_at INTEGER NOT NULL DEFAULT 0,
                next_run_at INTEGER,
                run_count INTEGER NOT NULL DEFAULT 0,
                error_count INTEGER NOT NULL DEFAULT 0,
                meta TEXT NOT NULL DEFAULT '{}'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS execution_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schedule_id TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'running',
                result TEXT,
                console_output TEXT,
                error TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exec_log_schedule ON execution_log(schedule_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations
    }

    // --- Schedule CRUD ---

    fun createSchedule(
        name: String,
        launchType: String,
        schedulePattern: String,
        runtime: String,
        code: String,
        args: String = "",
        cwd: String = "",
        timeoutMs: Long = 60_000,
        enabled: Boolean = true,
        meta: String = "{}",
    ): ScheduleRow {
        if (countSchedules() >= MAX_SCHEDULES) throw IllegalStateException("schedule_limit_reached")
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val nextRunAt = when (launchType) {
            "one_time" -> now
            "periodic" -> computeNextRun(schedulePattern, now)
            else -> null // daemon
        }
        val db = writableDatabase
        db.execSQL(
            """INSERT INTO schedules (id, name, launch_type, schedule_pattern, runtime, code, args, cwd, timeout_ms, enabled, created_at, updated_at, next_run_at, meta)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf(id, name, launchType, schedulePattern, runtime, code, args, cwd, timeoutMs, if (enabled) 1 else 0, now, now, nextRunAt, meta)
        )
        return getSchedule(id)!!
    }

    fun getSchedule(id: String): ScheduleRow? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM schedules WHERE id = ?", arrayOf(id))
        cursor.use {
            if (!it.moveToFirst()) return null
            return readScheduleRow(it)
        }
    }

    fun listSchedules(): List<ScheduleRow> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM schedules ORDER BY created_at ASC", null)
        val rows = mutableListOf<ScheduleRow>()
        cursor.use {
            while (it.moveToNext()) rows.add(readScheduleRow(it))
        }
        return rows
    }

    fun updateSchedule(id: String, updates: JSONObject): ScheduleRow? {
        val existing = getSchedule(id) ?: return null
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val sets = mutableListOf<String>()
        val vals = mutableListOf<Any?>()
        fun maybeSet(jsonKey: String, col: String) {
            if (updates.has(jsonKey)) {
                sets.add("$col = ?")
                vals.add(updates.get(jsonKey).let { if (it == JSONObject.NULL) null else it })
            }
        }
        maybeSet("name", "name")
        maybeSet("schedule_pattern", "schedule_pattern")
        maybeSet("runtime", "runtime")
        maybeSet("code", "code")
        maybeSet("args", "args")
        maybeSet("cwd", "cwd")
        maybeSet("timeout_ms", "timeout_ms")
        maybeSet("meta", "meta")
        if (updates.has("enabled")) {
            sets.add("enabled = ?")
            vals.add(if (updates.optBoolean("enabled", true)) 1 else 0)
        }
        if (sets.isEmpty()) return existing
        sets.add("updated_at = ?")
        vals.add(now)
        vals.add(id)
        db.execSQL("UPDATE schedules SET ${sets.joinToString(", ")} WHERE id = ?", vals.toTypedArray())

        // Recompute next_run_at if pattern or enabled changed
        val updated = getSchedule(id) ?: return null
        if (updated.launchType == "periodic" && updated.enabled) {
            val next = computeNextRun(updated.schedulePattern, now)
            updateNextRunAt(id, next)
        }
        return getSchedule(id)
    }

    fun deleteSchedule(id: String): Boolean {
        val db = writableDatabase
        db.execSQL("DELETE FROM execution_log WHERE schedule_id = ?", arrayOf(id))
        db.execSQL("DELETE FROM schedules WHERE id = ?", arrayOf(id))
        return db.compileStatement("SELECT changes()").simpleQueryForLong() > 0
    }

    fun countSchedules(): Int {
        val db = readableDatabase
        return db.compileStatement("SELECT COUNT(*) FROM schedules").simpleQueryForLong().toInt()
    }

    // --- Scheduling state ---

    fun updateNextRunAt(id: String, nextRunAt: Long?) {
        val db = writableDatabase
        db.execSQL("UPDATE schedules SET next_run_at = ? WHERE id = ?", arrayOf(nextRunAt, id))
    }

    fun updateLastRun(id: String, lastRunAt: Long, incrementRun: Boolean, incrementError: Boolean) {
        val db = writableDatabase
        val runInc = if (incrementRun) ", run_count = run_count + 1" else ""
        val errInc = if (incrementError) ", error_count = error_count + 1" else ""
        db.execSQL("UPDATE schedules SET last_run_at = ?$runInc$errInc WHERE id = ?", arrayOf(lastRunAt, id))
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL("UPDATE schedules SET enabled = ?, updated_at = ? WHERE id = ?", arrayOf(if (enabled) 1 else 0, now, id))
    }

    fun listDueSchedules(nowMs: Long): List<ScheduleRow> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM schedules WHERE enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= ?",
            arrayOf(nowMs.toString())
        )
        val rows = mutableListOf<ScheduleRow>()
        cursor.use {
            while (it.moveToNext()) rows.add(readScheduleRow(it))
        }
        return rows
    }

    fun listDaemonSchedules(): List<ScheduleRow> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM schedules WHERE enabled = 1 AND launch_type = 'daemon'", null)
        val rows = mutableListOf<ScheduleRow>()
        cursor.use {
            while (it.moveToNext()) rows.add(readScheduleRow(it))
        }
        return rows
    }

    // --- Execution log ---

    fun insertExecutionLog(scheduleId: String, startedAt: Long): Long {
        val db = writableDatabase
        db.execSQL(
            "INSERT INTO execution_log (schedule_id, started_at, status) VALUES (?, ?, 'running')",
            arrayOf(scheduleId, startedAt)
        )
        return db.compileStatement("SELECT last_insert_rowid()").simpleQueryForLong()
    }

    fun finishExecutionLog(logId: Long, finishedAt: Long, status: String, result: String?, consoleOutput: String?, error: String?) {
        val db = writableDatabase
        db.execSQL(
            "UPDATE execution_log SET finished_at = ?, status = ?, result = ?, console_output = ?, error = ? WHERE id = ?",
            arrayOf(finishedAt, status, result?.take(MAX_RESULT_SIZE), consoleOutput?.take(MAX_RESULT_SIZE), error?.take(MAX_RESULT_SIZE), logId)
        )
    }

    fun listExecutionLog(scheduleId: String, limit: Int = 20): List<ExecutionLogRow> {
        val lim = limit.coerceIn(1, 200)
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM execution_log WHERE schedule_id = ? ORDER BY id DESC LIMIT ?",
            arrayOf(scheduleId, lim.toString())
        )
        val rows = mutableListOf<ExecutionLogRow>()
        cursor.use {
            while (it.moveToNext()) rows.add(readLogRow(it))
        }
        return rows.reversed()
    }

    fun pruneExecutionLog(scheduleId: String, keep: Int = MAX_LOG_PER_SCHEDULE) {
        val db = writableDatabase
        db.execSQL(
            """DELETE FROM execution_log WHERE schedule_id = ? AND id NOT IN (
                SELECT id FROM execution_log WHERE schedule_id = ? ORDER BY id DESC LIMIT ?
            )""",
            arrayOf(scheduleId, scheduleId, keep)
        )
        // Global prune
        db.execSQL(
            """DELETE FROM execution_log WHERE id NOT IN (
                SELECT id FROM execution_log ORDER BY id DESC LIMIT ?
            )""",
            arrayOf(MAX_LOG_GLOBAL)
        )
    }

    fun markStaleRunningAsInterrupted() {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL(
            "UPDATE execution_log SET status = 'interrupted', finished_at = ? WHERE status = 'running'",
            arrayOf(now)
        )
    }

    // --- Helpers ---

    private fun readScheduleRow(c: android.database.Cursor): ScheduleRow {
        return ScheduleRow(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            launchType = c.getString(c.getColumnIndexOrThrow("launch_type")),
            schedulePattern = c.getString(c.getColumnIndexOrThrow("schedule_pattern")),
            runtime = c.getString(c.getColumnIndexOrThrow("runtime")),
            code = c.getString(c.getColumnIndexOrThrow("code")),
            args = c.getString(c.getColumnIndexOrThrow("args")),
            cwd = c.getString(c.getColumnIndexOrThrow("cwd")),
            timeoutMs = c.getLong(c.getColumnIndexOrThrow("timeout_ms")),
            enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
            lastRunAt = c.getLong(c.getColumnIndexOrThrow("last_run_at")),
            nextRunAt = if (c.isNull(c.getColumnIndexOrThrow("next_run_at"))) null else c.getLong(c.getColumnIndexOrThrow("next_run_at")),
            runCount = c.getInt(c.getColumnIndexOrThrow("run_count")),
            errorCount = c.getInt(c.getColumnIndexOrThrow("error_count")),
            meta = c.getString(c.getColumnIndexOrThrow("meta")),
        )
    }

    private fun readLogRow(c: android.database.Cursor): ExecutionLogRow {
        return ExecutionLogRow(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            scheduleId = c.getString(c.getColumnIndexOrThrow("schedule_id")),
            startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
            finishedAt = c.getLong(c.getColumnIndexOrThrow("finished_at")),
            status = c.getString(c.getColumnIndexOrThrow("status")),
            result = c.getString(c.getColumnIndexOrThrow("result")),
            consoleOutput = c.getString(c.getColumnIndexOrThrow("console_output")),
            error = c.getString(c.getColumnIndexOrThrow("error")),
        )
    }

    companion object {
        private const val DB_VERSION = 1
        private const val MAX_SCHEDULES = 50
        private const val MAX_LOG_PER_SCHEDULE = 200
        private const val MAX_LOG_GLOBAL = 2000
        private const val MAX_RESULT_SIZE = 8192

        fun computeNextRun(pattern: String, fromMs: Long): Long? {
            val cal = Calendar.getInstance()
            cal.timeInMillis = fromMs
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            return when {
                pattern == "minutely" -> {
                    cal.add(Calendar.MINUTE, 1)
                    cal.timeInMillis
                }
                pattern == "hourly" -> {
                    cal.set(Calendar.MINUTE, 0)
                    cal.add(Calendar.HOUR_OF_DAY, 1)
                    cal.timeInMillis
                }
                pattern == "daily" -> {
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                    cal.timeInMillis
                }
                pattern.startsWith("weekly:") -> {
                    val dayStr = pattern.removePrefix("weekly:")
                    val targetDay = parseDayOfWeek(dayStr) ?: return null
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    val current = cal.get(Calendar.DAY_OF_WEEK)
                    var daysUntil = (targetDay - current + 7) % 7
                    if (daysUntil == 0) daysUntil = 7
                    cal.add(Calendar.DAY_OF_MONTH, daysUntil)
                    cal.timeInMillis
                }
                pattern.startsWith("monthly:") -> {
                    val dayNum = pattern.removePrefix("monthly:").toIntOrNull()?.coerceIn(1, 28) ?: return null
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.DAY_OF_MONTH, dayNum)
                    if (cal.timeInMillis <= fromMs) {
                        cal.add(Calendar.MONTH, 1)
                        cal.set(Calendar.DAY_OF_MONTH, dayNum)
                    }
                    cal.timeInMillis
                }
                else -> null
            }
        }

        private fun parseDayOfWeek(day: String): Int? {
            return when (day.lowercase(Locale.US)) {
                "sun" -> Calendar.SUNDAY
                "mon" -> Calendar.MONDAY
                "tue" -> Calendar.TUESDAY
                "wed" -> Calendar.WEDNESDAY
                "thu" -> Calendar.THURSDAY
                "fri" -> Calendar.FRIDAY
                "sat" -> Calendar.SATURDAY
                else -> null
            }
        }
    }
}
