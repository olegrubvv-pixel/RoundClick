package com.example.yt3chardirect

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db(context: Context) : SQLiteOpenHelper(context, "yt3char.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE candidates(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                handle TEXT NOT NULL UNIQUE,
                score INTEGER NOT NULL,
                state TEXT NOT NULL,
                reason TEXT NOT NULL,
                clear_count INTEGER NOT NULL DEFAULT 0,
                checks INTEGER NOT NULL DEFAULT 0,
                channel_id TEXT,
                last_error TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_state ON candidates(state)")
        db.execSQL("CREATE INDEX idx_score ON candidates(score DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun replaceAll(handles: List<String>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("candidates", null, null)
            for (raw in handles) {
                val h = raw.trim().removePrefix("@").lowercase()
                if (h.isBlank()) continue
                val bad = HandleScoring.validate(h)
                val v = ContentValues().apply {
                    put("handle", h)
                    put("score", HandleScoring.score(h))
                    put("state", if (bad == null) States.QUEUED else States.INVALID)
                    put("reason", bad ?: "ожидает проверки")
                    put("clear_count", 0)
                    put("checks", 0)
                }
                writableDatabase.insertWithOnConflict("candidates", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun resetNetworkStates() {
        writableDatabase.execSQL("""
            UPDATE candidates SET
                state = CASE WHEN state='invalid' THEN 'invalid' ELSE 'queued' END,
                reason = CASE WHEN state='invalid' THEN reason ELSE 'ожидает проверки' END,
                clear_count=0, checks=0, channel_id=NULL, last_error=NULL
        """.trimIndent())
    }

    @Synchronized
    fun update(c: Candidate) {
        val v = ContentValues().apply {
            put("score", c.score)
            put("state", c.state)
            put("reason", c.reason)
            put("clear_count", c.clearCount)
            put("checks", c.checks)
            put("channel_id", c.channelId)
            put("last_error", c.lastError)
        }
        writableDatabase.update("candidates", v, "id=?", arrayOf(c.id.toString()))
    }

    @Synchronized
    fun getByState(state: String, clearCount: Int? = null, scoreDesc: Boolean = false): List<Candidate> {
        val where = if (clearCount == null) "state=?" else "state=? AND clear_count=?"
        val args = if (clearCount == null) arrayOf(state) else arrayOf(state, clearCount.toString())
        val order = if (scoreDesc) "score DESC, id ASC" else "id ASC"
        return query(where, args, order, 100000)
    }

    @Synchronized
    fun list(filter: String, limit: Int = 1200): List<Candidate> {
        val where = when (filter) {
            "strong" -> "state='strong'"
            "prelim" -> "state='prelim'"
            "busy" -> "state='busy'"
            "unknown" -> "state='unknown'"
            "invalid" -> "state='invalid'"
            else -> "1=1"
        }
        val order = if (filter == "strong" || filter == "unknown") "score DESC, id ASC" else "id ASC"
        return query(where, emptyArray(), order, limit)
    }

    @Synchronized
    fun counts(): Map<String, Int> {
        val out = mutableMapOf("total" to 0, "strong" to 0, "prelim" to 0, "busy" to 0, "unknown" to 0, "queued" to 0, "invalid" to 0)
        readableDatabase.rawQuery("SELECT state, COUNT(*) FROM candidates GROUP BY state", null).use { c ->
            var total = 0
            while (c.moveToNext()) {
                val st = c.getString(0)
                val n = c.getInt(1)
                out[st] = n
                total += n
            }
            out["total"] = total
        }
        return out
    }

    @Synchronized
    fun total(): Int = counts()["total"] ?: 0

    private fun query(where: String, args: Array<String>, order: String, limit: Int): List<Candidate> {
        val out = ArrayList<Candidate>()
        readableDatabase.query(
            "candidates",
            arrayOf("id","handle","score","state","reason","clear_count","checks","channel_id","last_error"),
            where, args, null, null, order, limit.toString()
        ).use { c ->
            while (c.moveToNext()) out += fromCursor(c)
        }
        return out
    }

    private fun fromCursor(c: Cursor) = Candidate(
        id = c.getLong(0),
        handle = c.getString(1),
        score = c.getInt(2),
        state = c.getString(3),
        reason = c.getString(4),
        clearCount = c.getInt(5),
        checks = c.getInt(6),
        channelId = if (c.isNull(7)) null else c.getString(7),
        lastError = if (c.isNull(8)) null else c.getString(8)
    )
}
