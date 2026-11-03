package com.muyuchat.mca

import android.database.sqlite.SQLiteDatabase

/**
 * One-shot, idempotent repair for rows that are too large for a `CursorWindow`.
 *
 * This must run before Room opens a reader: the failure mode is that `SELECT *` cannot even
 * materialise one row, so the fix cannot be "load it and move it" through the normal DAO. Two
 * details make it work:
 *
 *  - oversized rows are found with `length(col)`, which needs no row payload;
 *  - the value is pulled in bounded `substr(col, ?, ?)` slices, each far below the window limit,
 *    and the column is replaced by the small reference object produced by [SessionJsonOffload].
 *
 * Nothing is truncated: the full original text lands in the offload file before the column is
 * replaced. Every step is best effort and reported by its return value; a repair failure must not
 * block app start-up, but it must be visible in diagnostics rather than silently swallowed.
 */
internal object SessionJsonRepair {

    /** `chat_sessions` carries the character-card snapshot, appearance and summary documents. */
    private val SESSION_JSON_COLUMNS = listOf(
        "assistantSnapshotJson",
        "appearanceJson",
        "contextSummariesJson"
    )

    private const val CHUNK_CHARS = 32 * 1024

    data class Outcome(
        val repairedRows: Int,
        val failedRows: Int,
        val failureMessage: String? = null
    ) {
        val changed: Boolean get() = repairedRows > 0
    }

    /**
     * Moves every oversized session JSON value to the offload store and rewrites the column as a
     * reference. Safe to call on every open: already-migrated rows are below the threshold.
     */
    fun repairOversizedSessionJson(
        db: SQLiteDatabase,
        limitChars: Int = SessionJsonOffload.MAX_INLINE_JSON_CHARS,
        columns: List<String> = SESSION_JSON_COLUMNS
    ): Outcome {
        if (!SessionJsonOffload.isConfigured()) {
            return Outcome(repairedRows = 0, failedRows = 0, failureMessage = "offload_store_not_configured")
        }
        val present = existingColumns(db, "chat_sessions").let { available ->
            columns.filter { it in available }
        }
        if (present.isEmpty()) return Outcome(repairedRows = 0, failedRows = 0)
        var repaired = 0
        var failed = 0
        var failureMessage: String? = null
        db.beginTransaction()
        try {
            present.forEach { column ->
                oversizedSessionIds(db, column, limitChars).forEach { (id, length) ->
                    val moved = runCatching { moveOne(db, column, id, length) }.getOrDefault(false)
                    if (moved) repaired += 1 else failed += 1
                }
            }
            db.setTransactionSuccessful()
        } catch (error: Throwable) {
            failureMessage = error.message ?: error::class.java.simpleName
        } finally {
            runCatching { db.endTransaction() }
        }
        return Outcome(repairedRows = repaired, failedRows = failed, failureMessage = failureMessage)
    }

    /** Column presence check, so a pre-migration database is skipped instead of throwing. */
    private fun existingColumns(db: SQLiteDatabase, table: String): Set<String> = runCatching {
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return@buildSet
                while (cursor.moveToNext()) {
                    cursor.getString(nameIndex)?.let { add(it) }
                }
            }
        }
    }.getOrDefault(emptySet())

    private fun oversizedSessionIds(
        db: SQLiteDatabase,
        column: String,
        limitChars: Int
    ): List<Pair<String, Int>> = runCatching {
        db.rawQuery(
            "SELECT id, length($column) FROM chat_sessions " +
                "WHERE $column IS NOT NULL AND length($column) > ?",
            arrayOf(limitChars.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val length = cursor.getInt(1)
                    if (length > limitChars) add(id to length)
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun moveOne(db: SQLiteDatabase, column: String, id: String, length: Int): Boolean {
        val json = readInChunks(db, column, id, length) ?: return false
        if (json.length <= SessionJsonOffload.MAX_INLINE_JSON_CHARS) return false
        val reference = SessionJsonOffload.offload(id, json) ?: return false
        // If the offload store could not write, `offload` returns the original text; rewriting it
        // would be a no-op that risks another oversized write, so leave the row untouched.
        if (!SessionJsonOffload.isReference(reference)) return false
        db.execSQL(
            "UPDATE chat_sessions SET $column = ? WHERE id = ?",
            arrayOf<Any>(reference, id)
        )
        return true
    }

    /**
     * Reassembles one oversized value from bounded slices. `substr` is 1-based; each slice is a
     * few dozen KiB so the cursor window never has to hold the whole row.
     */
    private fun readInChunks(db: SQLiteDatabase, column: String, id: String, length: Int): String? {
        if (length <= 0) return null
        val builder = StringBuilder(length)
        var offset = 1
        while (offset <= length) {
            val chunk = db.rawQuery(
                "SELECT substr($column, ?, ?) FROM chat_sessions WHERE id = ?",
                arrayOf(offset.toString(), CHUNK_CHARS.toString(), id)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: return null
            if (chunk.isEmpty()) return null
            builder.append(chunk)
            offset += chunk.length
        }
        return builder.toString()
    }
}
