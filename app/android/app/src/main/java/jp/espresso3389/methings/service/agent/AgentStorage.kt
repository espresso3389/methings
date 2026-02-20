package jp.espresso3389.methings.service.agent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONObject
import java.io.File

class AgentStorage(context: Context) : SQLiteOpenHelper(
    context,
    File(context.filesDir, "agent/agent.db").also { it.parentFile?.mkdirs() }.absolutePath,
    null,
    DB_VERSION,
) {
    private val filesDir = context.filesDir

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                meta TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_chat_messages_session ON chat_messages(session_id, id)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event TEXT NOT NULL,
                data TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
    }

    fun addChatMessage(sessionId: String, role: String, text: String, metaJson: String) {
        val sid = sessionId.trim().ifEmpty { "default" }
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.execSQL(
            "INSERT INTO chat_messages (session_id, role, text, meta, created_at) VALUES (?, ?, ?, ?, ?)",
            arrayOf(sid, role, text, metaJson, now)
        )
        // Prune per-session
        db.execSQL(
            """
            DELETE FROM chat_messages
            WHERE session_id = ? AND id NOT IN (
                SELECT id FROM chat_messages WHERE session_id = ? ORDER BY id DESC LIMIT ?
            )
            """.trimIndent(),
            arrayOf(sid, sid, MAX_PER_SESSION)
        )
        // Prune global
        db.execSQL(
            """
            DELETE FROM chat_messages
            WHERE id NOT IN (
                SELECT id FROM chat_messages ORDER BY id DESC LIMIT ?
            )
            """.trimIndent(),
            arrayOf(MAX_GLOBAL)
        )
    }

    fun listChatMessages(sessionId: String, limit: Int = 200): List<Map<String, Any?>> {
        val sid = sessionId.trim().ifEmpty { "default" }
        val lim = limit.coerceIn(1, 1000)
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT role, text, meta, created_at FROM chat_messages WHERE session_id = ? ORDER BY id DESC LIMIT ?",
            arrayOf(sid, lim.toString())
        )
        val rows = mutableListOf<Map<String, Any?>>()
        cursor.use {
            while (it.moveToNext()) {
                val metaStr = it.getString(2)
                val meta = try {
                    if (!metaStr.isNullOrBlank()) JSONObject(metaStr) else null
                } catch (_: Exception) {
                    null
                }
                rows.add(
                    mapOf(
                        "role" to it.getString(0),
                        "text" to it.getString(1),
                        "meta" to metaStr,
                        "created_at" to it.getLong(3),
                    )
                )
            }
        }
        return rows.reversed()
    }

    fun listChatSessions(limit: Int = 50): List<Map<String, Any?>> {
        val lim = limit.coerceIn(1, 200)
        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT session_id, COUNT(*) AS count, MAX(created_at) AS last_created_at
            FROM chat_messages
            GROUP BY session_id
            ORDER BY last_created_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(lim.toString())
        )
        val rows = mutableListOf<Map<String, Any?>>()
        cursor.use {
            while (it.moveToNext()) {
                rows.add(
                    mapOf(
                        "session_id" to it.getString(0),
                        "count" to it.getInt(1),
                        "last_created_at" to it.getLong(2),
                    )
                )
            }
        }
        return rows
    }

    fun renameChatSession(oldId: String, newId: String): Int {
        val old = oldId.trim()
        val new_ = newId.trim()
        if (old.isEmpty() || new_.isEmpty() || old == new_) return 0
        val db = writableDatabase
        db.execSQL(
            "UPDATE chat_messages SET session_id = ? WHERE session_id = ?",
            arrayOf(new_, old)
        )
        return db.compileStatement("SELECT changes()").simpleQueryForLong().toInt()
    }

    fun deleteChatSession(sessionId: String): Int {
        val sid = sessionId.trim()
        if (sid.isEmpty()) return 0
        val db = writableDatabase
        db.execSQL("DELETE FROM chat_messages WHERE session_id = ?", arrayOf(sid))
        return db.compileStatement("SELECT changes()").simpleQueryForLong().toInt()
    }

    fun getSetting(key: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key))
        cursor.use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun setSetting(key: String, value: String) {
        val db = writableDatabase
        db.execSQL(
            """
            INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at
            """.trimIndent(),
            arrayOf(key, value, System.currentTimeMillis())
        )
    }

    fun addAudit(event: String, data: String) {
        val db = writableDatabase
        db.execSQL(
            "INSERT INTO audit_log (event, data, created_at) VALUES (?, ?, ?)",
            arrayOf(event, data, System.currentTimeMillis())
        )
    }

    /**
     * One-time migration: copy chat_messages from the old Python app.db into agent.db.
     * Safe to call multiple times — skips if already migrated or old DB doesn't exist.
     */
    fun migrateFromPythonDbIfNeeded() {
        if (getSetting("python_db_migrated") != null) return

        val oldDb = File(filesDir, "protected/app.db")
        if (!oldDb.exists()) {
            setSetting("python_db_migrated", "no_source")
            return
        }

        try {
            val src = SQLiteDatabase.openDatabase(oldDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            src.use { db ->
                // Verify old DB has the expected table
                val tables = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='chat_messages'", null
                )
                val hasTable = tables.use { it.moveToFirst() }
                if (!hasTable) {
                    setSetting("python_db_migrated", "no_table")
                    return
                }

                val cursor = db.rawQuery(
                    "SELECT session_id, role, text, meta, created_at FROM chat_messages ORDER BY id ASC",
                    null
                )
                var count = 0
                val dst = writableDatabase
                dst.beginTransaction()
                try {
                    cursor.use {
                        while (it.moveToNext()) {
                            dst.execSQL(
                                "INSERT INTO chat_messages (session_id, role, text, meta, created_at) VALUES (?, ?, ?, ?, ?)",
                                arrayOf(
                                    it.getString(0) ?: "default",
                                    it.getString(1) ?: "user",
                                    it.getString(2) ?: "",
                                    it.getString(3),
                                    it.getLong(4),
                                )
                            )
                            count++
                        }
                    }
                    dst.setTransactionSuccessful()
                } finally {
                    dst.endTransaction()
                }
                Log.i(TAG, "Migrated $count chat messages from Python app.db")
            }
            setSetting("python_db_migrated", System.currentTimeMillis().toString())
        } catch (ex: Exception) {
            Log.w(TAG, "Python DB migration failed (non-fatal)", ex)
            setSetting("python_db_migrated", "error:${ex.message?.take(100)}")
        }
    }

    companion object {
        private const val TAG = "AgentStorage"
        private const val DB_VERSION = 1
        private const val MAX_PER_SESSION = 400
        private const val MAX_GLOBAL = 4000
    }
}
