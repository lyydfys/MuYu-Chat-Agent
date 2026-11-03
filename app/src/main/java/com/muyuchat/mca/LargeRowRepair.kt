package com.muyuchat.mca

import android.database.sqlite.SQLiteDatabase
import android.util.Log

/**
 * One-shot, idempotent repair for rows that are too large for a `CursorWindow`.
 *
 * It must run before Room opens a reader: the failure mode is that `SELECT *` cannot even
 * materialise one row, so the value cannot be moved through the normal DAO. Two details make it
 * work:
 *
 *  - oversized rows are found with `length(col)`, which needs no row payload;
 *  - the value is pulled in bounded `substr(col, ?, ?)` slices, each far below the window limit,
 *    and the column is replaced by the small reference object produced by [LargeRowOffload].
 *
 * `rowid` is the row handle for every Room table that lacks an INTEGER PRIMARY KEY (all of the ones
 * listed below), so the same predicate works for composite-key tables such as `chat_messages`.
 *
 * Nothing is truncated: the full original text lands in the offload file before the column is
 * replaced. Every step is best effort and reported by its return value; a repair failure must not
 * block start-up, but it is logged so diagnostics can show it.
 */
internal object LargeRowRepair {

    /** A column whose oversized values are moved out of SQLite. Mirrors the resolving mappers. */
    data class Column(val table: String, val column: String)

    /**
     * Every column [LargeRowOffload] may move. A column listed here must be resolved back in its
     * entity mapper (see `ChatSessionPersistenceMapping`), otherwise the UI would show raw
     * reference JSON instead of the stored text.
     */
    val COLUMNS: List<Column> = listOf(
        // Chat session documents.
        Column("chat_sessions", "assistantSnapshotJson"),
        Column("chat_sessions", "appearanceJson"),
        Column("chat_sessions", "contextSummariesJson"),
        // Role records: a preset may inject a very large system prompt or card blob.
        Column("assistants", "systemPrompt"),
        Column("assistants", "paramsJson"),
        Column("assistants", "characterCardJson"),
        Column("assistants", "appearanceJson"),
        // Message bodies: a runaway model reply can be megabytes long.
        Column("chat_messages", "content"),
        Column("chat_messages", "reasoningContent"),
        // Character memory: read only on the send path, which is why the failure surfaced there.
        Column("memories", "content"),
        Column("assistant_memory_turns", "userText"),
        Column("assistant_memory_turns", "assistantText"),
        // Imported file index text.
        Column("file_assets", "text"),
        Column("file_assets", "preview")
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
     * Moves every oversized value to the offload store and rewrites the column as a reference.
     * Safe to call on every open: already-migrated rows are below the threshold.
     */
    fun repair(
        db: SQLiteDatabase,
        limitChars: Int = LargeRowOffload.MAX_INLINE_CHARS,
        columns: List<Column> = COLUMNS
    ): Outcome {
        if (!LargeRowOffload.isConfigured()) {
            return Outcome(repairedRows = 0, failedRows = 0, failureMessage = "offload_store_not_configured")
        }
        var repaired = 0
        var failed = 0
        var failureMessage: String? = null
        db.beginTransaction()
        try {
            columns.forEach { target ->
                if (target.column !in existingColumns(db, target.table)) return@forEach
                oversizedRows(db, target, limitChars).forEach { (rowId, length) ->
                    val moved = runCatching { moveOne(db, target, rowId, length) }.getOrDefault(false)
                    if (moved) repaired += 1 else failed += 1
                }
            }
            db.setTransactionSuccessful()
        } catch (error: Throwable) {
            failureMessage = error.message ?: error::class.java.simpleName
        } finally {
            runCatching { db.endTransaction() }
        }
        // Always logged: on a user device this line is the only evidence of what the repair saw.
        Log.i(
            "MCA-ChatHistory",
            "large_row_offload repaired=$repaired failed=$failed reason=$failureMessage"
        )
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

    private fun oversizedRows(
        db: SQLiteDatabase,
        target: Column,
        limitChars: Int
    ): List<Pair<Long, Int>> = runCatching {
        db.rawQuery(
            "SELECT rowid, length(${target.column}) FROM ${target.table} " +
                "WHERE ${target.column} IS NOT NULL AND length(${target.column}) > ?",
            arrayOf(limitChars.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val rowId = cursor.getLong(0)
                    val length = cursor.getInt(1)
                    if (rowId >= 0L && length > limitChars) add(rowId to length)
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun moveOne(db: SQLiteDatabase, target: Column, rowId: Long, length: Int): Boolean {
        val value = readInChunks(db, target, rowId, length) ?: return false
        if (value.length <= LargeRowOffload.MAX_INLINE_CHARS) return false
        val reference = LargeRowOffload.offload(target.table + "-" + target.column, value) ?: return false
        // If the offload store could not write, `offload` returns the original text; rewriting it
        // would be a no-op that risks another oversized write, so leave the row untouched.
        if (!LargeRowOffload.isReference(reference)) return false
        db.execSQL(
            "UPDATE ${target.table} SET ${target.column} = ? WHERE rowid = ?",
            arrayOf<Any>(reference, rowId)
        )
        return true
    }

    /**
     * Reassembles one oversized value from bounded slices. `substr` is 1-based; each slice is a
     * few dozen KiB so the cursor window never has to hold the whole row.
     */
    private fun readInChunks(db: SQLiteDatabase, target: Column, rowId: Long, length: Int): String? {
        if (length <= 0) return null
        val builder = StringBuilder(length)
        var offset = 1
        while (offset <= length) {
            val chunk = db.rawQuery(
                "SELECT substr(${target.column}, ?, ?) FROM ${target.table} WHERE rowid = ?",
                arrayOf(offset.toString(), CHUNK_CHARS.toString(), rowId.toString())
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
