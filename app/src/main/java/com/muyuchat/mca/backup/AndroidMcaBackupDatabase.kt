package com.muyuchat.mca.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.muyuchat.mca.McaRoomDatabase

/** Columns plus rows of one table, in declaration order. Values keep their SQLite type. */
internal data class McaRawTable(
    val columns: List<String>,
    val rows: List<List<Any?>>
)

/** Reads a whitelisted table for export. Injected so the export path stays testable. */
internal fun interface McaBackupRowSource {
    fun readTable(table: String): McaRawTable
}

/** SQL identifier guard: every table name here comes from [McaBackupTable], never from the file. */
private fun quoted(table: String): String {
    require(table.matches(IDENTIFIER)) { "unsafe table name: $table" }
    return "\"$table\""
}

private val IDENTIFIER = Regex("[A-Za-z0-9_]+")

/**
 * Reads the live `mca.db` for export.
 *
 * BLOB columns are skipped: the backup format is JSON text, and a blob would either bloat the file or
 * be silently mangled. Every other column is read with its SQLite type so `null`, integers, reals and
 * text survive the round trip.
 */
internal class AndroidMcaBackupRowSource(
    private val open: () -> SupportSQLiteDatabase
) : McaBackupRowSource {

    constructor(context: Context) : this({
        McaRoomDatabase.get(context.applicationContext).openHelper.writableDatabase
    })

    override fun readTable(table: String): McaRawTable {
        val database = open()
        val columns = readColumns(database, table).filter { it.second != TYPE_BLOB }.map { it.first }
        val rows = ArrayList<List<Any?>>()
        val sql = "SELECT * FROM ${quoted(table)}"
        database.query(sql).use { cursor ->
            val indexByColumn = columns.associateWith { cursor.getColumnIndexOrThrow(it) }
            while (cursor.moveToNext()) {
                rows += columns.map { column -> readCell(cursor, indexByColumn.getValue(column)) }
            }
        }
        return McaRawTable(columns = columns, rows = rows)
    }

    private fun readColumns(database: SupportSQLiteDatabase, table: String): List<Pair<String, String>> {
        val columns = ArrayList<Pair<String, String>>()
        database.query("PRAGMA table_info(${quoted(table)})").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex) to cursor.getString(typeIndex).orEmpty().uppercase()
            }
        }
        return columns
    }

    private fun readCell(cursor: android.database.Cursor, index: Int): Any? =
        when (cursor.getType(index)) {
            android.database.Cursor.FIELD_TYPE_NULL -> null
            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
            android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
            else -> null
        }

    private companion object {
        const val TYPE_BLOB = "BLOB"
    }
}

/**
 * Adapter over the live Room database for a restore.
 *
 * Everything runs inside one `beginTransaction`/`setTransactionSuccessful`/`endTransaction` unit, so
 * a failure anywhere leaves the previous rows in place. `endTransaction` is called exactly once per
 * `beginTransaction` because the engine balances `commit`/`rollBack` on every path.
 */
internal class AndroidMcaBackupRestoreTarget(
    private val database: SupportSQLiteDatabase
) : McaBackupRestoreTarget {

    private var transactionOpen = false

    constructor(context: Context) : this(
        McaRoomDatabase.get(context.applicationContext).openHelper.writableDatabase
    )

    override fun beginTransaction() {
        database.beginTransactionNonExclusive()
        transactionOpen = true
    }

    override fun tableColumns(table: String): List<String> {
        val columns = ArrayList<String>()
        database.query("PRAGMA table_info(${quoted(table)})").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        return columns
    }

    override fun rowCount(table: String): Int =
        database.query("SELECT COUNT(*) FROM ${quoted(table)}").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    override fun deleteAll(table: String) {
        database.execSQL("DELETE FROM ${quoted(table)}")
    }

    override fun insertRow(table: String, columns: List<String>, values: List<Any?>) {
        val content = ContentValues(columns.size)
        columns.forEachIndexed { index, column ->
            when (val value = values.getOrNull(index)) {
                null -> content.putNull(column)
                is Byte -> content.put(column, value.toLong())
                is Short -> content.put(column, value.toLong())
                is Int -> content.put(column, value.toLong())
                is Long -> content.put(column, value)
                is Float -> content.put(column, value.toDouble())
                is Double -> content.put(column, value)
                is Boolean -> content.put(column, if (value) 1L else 0L)
                else -> content.put(column, value.toString())
            }
        }
        val rowId = database.insert(table, SQLiteDatabase.CONFLICT_ABORT, content)
        if (rowId == -1L) error("插入 $table 失败")
    }

    override fun commit() {
        if (!transactionOpen) return
        database.setTransactionSuccessful()
        database.endTransaction()
        transactionOpen = false
    }

    override fun rollBack() {
        if (!transactionOpen) return
        runCatching { database.endTransaction() }
        transactionOpen = false
    }
}
