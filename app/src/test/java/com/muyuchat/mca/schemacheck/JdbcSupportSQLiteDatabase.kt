package com.muyuchat.mca.schemacheck

import android.content.ContentResolver
import android.content.ContentValues
import android.database.CharArrayBuffer
import android.database.ContentObserver
import android.database.Cursor
import android.database.DataSetObserver
import android.database.sqlite.SQLiteTransactionListener
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Pair
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.Locale

/**
 * Minimal `SupportSQLiteDatabase` over `sqlite-jdbc`, shared by the JVM schema/migration contracts.
 *
 * This is the same adapter (byte-for-byte class body, only visibility widened) that
 * `McaRoomMigrationExecutionTest` keeps privately, extracted so the 29→32 suite and the
 * interruption suite do not fork their own SQLite semantics. It implements exactly what the
 * production `Migration.migrate(db)` path and the PRAGMA assertions need.
 */
internal class JdbcSupportSQLiteDatabase(
    private val connection: Connection
) : SupportSQLiteDatabase {

    private var transactionSuccessful = false

    override fun compileStatement(sql: String): SupportSQLiteStatement = unsupported()

    override fun beginTransaction() = beginTransactionInternal(null)

    override fun beginTransactionNonExclusive() = beginTransactionInternal(null)

    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) =
        beginTransactionInternal(transactionListener)

    override fun beginTransactionWithListenerNonExclusive(
        transactionListener: SQLiteTransactionListener
    ) = beginTransactionInternal(transactionListener)

    override fun endTransaction() {
        if (connection.autoCommit) return
        try {
            if (transactionSuccessful) connection.commit() else connection.rollback()
        } finally {
            connection.autoCommit = true
            transactionSuccessful = false
        }
    }

    override fun setTransactionSuccessful() {
        check(!connection.autoCommit) { "No transaction is active." }
        transactionSuccessful = true
    }

    override fun inTransaction(): Boolean = !connection.autoCommit

    override val isDbLockedByCurrentThread: Boolean
        get() = false

    override fun yieldIfContendedSafely(): Boolean = false

    override fun yieldIfContendedSafely(sleepAfterYieldDelay: Long): Boolean = false

    override var version: Int
        get() = query("PRAGMA user_version").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
        set(value) {
            execSQL("PRAGMA user_version = $value")
        }

    override val maximumSize: Long
        get() = unsupported()

    override fun setMaximumSize(numBytes: Long): Long = unsupported()

    override var pageSize: Long
        get() = query("PRAGMA page_size").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        set(value) {
            execSQL("PRAGMA page_size = $value")
        }

    override fun query(query: String): Cursor = query(query, emptyArray())

    override fun query(query: String, bindArgs: Array<out Any?>): Cursor =
        connection.prepareStatement(query).use { statement ->
            statement.bind(bindArgs)
            statement.executeQuery().use { resultSet ->
                val metadata = resultSet.metaData
                val columnNames = List(metadata.columnCount) { index ->
                    metadata.getColumnLabel(index + 1)
                }
                val rows = buildList {
                    while (resultSet.next()) {
                        add(Array<Any?>(metadata.columnCount) { index -> resultSet.getObject(index + 1) })
                    }
                }
                JdbcCursor(columnNames, rows)
            }
        }

    override fun query(query: SupportSQLiteQuery): Cursor = unsupported()

    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
        unsupported()

    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long = unsupported()

    override fun delete(
        table: String,
        whereClause: String?,
        whereArgs: Array<out Any?>?
    ): Int = unsupported()

    override fun update(
        table: String,
        conflictAlgorithm: Int,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<out Any?>?
    ): Int = unsupported()

    override fun execSQL(sql: String) {
        connection.createStatement().use { statement -> statement.execute(sql) }
    }

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        connection.prepareStatement(sql).use { statement ->
            statement.bind(bindArgs)
            statement.execute()
        }
    }

    override val isReadOnly: Boolean
        get() = connection.isReadOnly

    override val isOpen: Boolean
        get() = !connection.isClosed

    override fun needUpgrade(newVersion: Int): Boolean = newVersion > version

    override val path: String?
        get() = connection.metaData.url

    override fun setLocale(locale: Locale) = Unit

    override fun setMaxSqlCacheSize(cacheSize: Int) = Unit

    override fun setForeignKeyConstraintsEnabled(enable: Boolean) {
        execSQL("PRAGMA foreign_keys=${if (enable) "ON" else "OFF"}")
    }

    override fun enableWriteAheadLogging(): Boolean = false

    override fun disableWriteAheadLogging() = Unit

    override val isWriteAheadLoggingEnabled: Boolean
        get() = false

    override val attachedDbs: List<Pair<String, String>>?
        get() = emptyList()

    override val isDatabaseIntegrityOk: Boolean
        get() = query("PRAGMA integrity_check").use { cursor ->
            cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
        }

    override fun close() {
        connection.close()
    }

    private fun beginTransactionInternal(listener: SQLiteTransactionListener?) {
        check(connection.autoCommit) { "Nested transactions are not supported by this test adapter." }
        connection.autoCommit = false
        transactionSuccessful = false
        listener?.onBegin()
    }

    private fun PreparedStatement.bind(bindArgs: Array<out Any?>) {
        bindArgs.forEachIndexed { index, value ->
            when (value) {
                is ByteArray -> setBytes(index + 1, value)
                else -> setObject(index + 1, value)
            }
        }
    }

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("Not required by this migration test adapter.")
}

/** Cursor over materialized JDBC rows; same shape as the one in `McaRoomMigrationExecutionTest`. */
internal class JdbcCursor(
    private val columnNames: List<String>,
    private val rows: List<Array<Any?>>
) : Cursor {
    private var position = -1
    private var closed = false

    override fun close() {
        closed = true
    }

    override fun copyStringToBuffer(columnIndex: Int, buffer: CharArrayBuffer) {
        throw UnsupportedOperationException("Not required by this migration test cursor.")
    }

    override fun deactivate() = Unit

    override fun getBlob(columnIndex: Int): ByteArray? = value(columnIndex) as? ByteArray

    override fun getColumnCount(): Int = columnNames.size

    override fun getColumnIndex(columnName: String): Int =
        columnNames.indexOfFirst { it.equals(columnName, ignoreCase = true) }

    override fun getColumnIndexOrThrow(columnName: String): Int =
        getColumnIndex(columnName).also { index ->
            require(index >= 0) { "No column named $columnName" }
        }

    override fun getColumnName(columnIndex: Int): String = columnNames[columnIndex]

    override fun getColumnNames(): Array<String> = columnNames.toTypedArray()

    override fun getCount(): Int = rows.size

    override fun getDouble(columnIndex: Int): Double = (value(columnIndex) as Number).toDouble()

    override fun getExtras(): Bundle = unsupported()

    override fun getFloat(columnIndex: Int): Float = (value(columnIndex) as Number).toFloat()

    override fun getInt(columnIndex: Int): Int = (value(columnIndex) as Number).toInt()

    override fun getLong(columnIndex: Int): Long = (value(columnIndex) as Number).toLong()

    override fun getNotificationUri(): Uri? = null

    override fun getPosition(): Int = position

    override fun getShort(columnIndex: Int): Short = (value(columnIndex) as Number).toShort()

    override fun getString(columnIndex: Int): String? = value(columnIndex)?.toString()

    override fun getType(columnIndex: Int): Int = when (val current = value(columnIndex)) {
        null -> Cursor.FIELD_TYPE_NULL
        is ByteArray -> Cursor.FIELD_TYPE_BLOB
        is Float, is Double -> Cursor.FIELD_TYPE_FLOAT
        is Number -> Cursor.FIELD_TYPE_INTEGER
        else -> Cursor.FIELD_TYPE_STRING
    }

    override fun getWantsAllOnMoveCalls(): Boolean = false

    override fun isAfterLast(): Boolean = rows.isNotEmpty() && position >= rows.size

    override fun isBeforeFirst(): Boolean = position < 0

    override fun isClosed(): Boolean = closed

    override fun isFirst(): Boolean = position == 0 && rows.isNotEmpty()

    override fun isLast(): Boolean = position == rows.lastIndex && rows.isNotEmpty()

    override fun isNull(columnIndex: Int): Boolean = value(columnIndex) == null

    override fun move(offset: Int): Boolean = moveToPosition(position + offset)

    override fun moveToFirst(): Boolean = moveToPosition(0)

    override fun moveToLast(): Boolean = moveToPosition(rows.lastIndex)

    override fun moveToNext(): Boolean = moveToPosition(position + 1)

    override fun moveToPosition(newPosition: Int): Boolean {
        position = when {
            newPosition < 0 -> -1
            newPosition >= rows.size -> rows.size
            else -> newPosition
        }
        return position in rows.indices
    }

    override fun moveToPrevious(): Boolean = moveToPosition(position - 1)

    override fun registerContentObserver(observer: ContentObserver) = Unit

    override fun registerDataSetObserver(observer: DataSetObserver) = Unit

    override fun requery(): Boolean = false

    override fun respond(extras: Bundle): Bundle = unsupported()

    override fun setExtras(extras: Bundle) = Unit

    override fun setNotificationUri(cr: ContentResolver, uri: Uri) = Unit

    override fun unregisterContentObserver(observer: ContentObserver) = Unit

    override fun unregisterDataSetObserver(observer: DataSetObserver) = Unit

    private fun value(columnIndex: Int): Any? {
        check(position in rows.indices) { "Cursor is not positioned on a row." }
        return rows[position][columnIndex]
    }

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("Not required by this migration test cursor.")
}
