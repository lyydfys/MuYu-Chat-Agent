package com.muyuchat.mca.backup

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The minimum a restore needs from a database.
 *
 * Deliberately tiny and SQL-free so the whole transaction can be driven by an in-memory fake in a
 * JVM test; the Android implementation is a thin adapter over `SupportSQLiteDatabase`.
 */
internal interface McaBackupRestoreTarget {
    /** Opens the outer transaction. Must be balanced by exactly one [commit] or [rollBack]. */
    fun beginTransaction()

    /** Real columns of [table] in declaration order; empty when the table does not exist. */
    fun tableColumns(table: String): List<String>

    fun rowCount(table: String): Int

    fun deleteAll(table: String)

    fun insertRow(table: String, columns: List<String>, values: List<Any?>)

    fun commit()

    fun rollBack()
}

/** Stable failure codes; the UI maps them to sentences and tests pin the code, not the wording. */
internal enum class McaDatabaseRestoreFailure(val code: String, val message: String) {
    IN_PROGRESS("restore_in_progress", "已有恢复正在进行，请稍后再试"),
    SCHEMA_MISMATCH("schema_mismatch", "备份的列与当前数据库不一致"),
    ROW_COUNT_MISMATCH("row_count_mismatch", "恢复后的行数与备份清单不一致"),
    INSERT_FAILED("insert_failed", "写入数据失败"),
    TRANSACTION_FAILED("transaction_failed", "恢复事务失败"),
    PAYLOAD_INVALID("payload_invalid", "备份没有可恢复的行数据")
}

internal sealed interface McaDatabaseRestoreResult {
    /** Committed. [snapshot] is the human fallback note ("已先备份当前数据到 …") when available. */
    data class Restored(
        val tables: Int,
        val rows: Int,
        val snapshot: String?
    ) : McaDatabaseRestoreResult

    data class Failed(
        val failure: McaDatabaseRestoreFailure,
        /** Concrete detail (table/column names, exception message); never a stack trace. */
        val detail: String
    ) : McaDatabaseRestoreResult

    val isSuccess: Boolean get() = this is Restored
}

/**
 * All-or-nothing restore of one verified payload into the live database.
 *
 * Why a transaction and not a file swap: `McaRoomDatabase` is a process-wide singleton and every
 * store captured that instance, so replacing `mca.db` under it would corrupt live connections. One
 * SQLite transaction gives the same user-visible guarantee — either every whitelisted table shows the
 * backup's rows, or nothing changed at all.
 *
 * Order, and where each failure returns to:
 *  1. process-wide lock → [McaDatabaseRestoreFailure.IN_PROGRESS] (second caller refused immediately);
 *  2. read the real schema, refuse foreign columns → `schema_mismatch` (nothing touched);
 *  3. `beginTransaction` → `transaction_failed` when it cannot even start;
 *  4. per table `deleteAll` + `insertRow` → `insert_failed`, rolled back;
 *  5. row counts re-read **before** commit → `row_count_mismatch`, rolled back;
 *  6. snapshot (human fallback only — it is taken after the writes but before the commit, so a
 *     successful snapshot can never be mistaken for a committed restore);
 *  7. `commit`. Any exception on the way is rolled back; the original data stays untouched.
 */
internal class McaBackupRestoreEngine(
    private val target: McaBackupRestoreTarget,
    /** Writes the "current data" fallback copy; returns the note shown on success, or null. */
    private val snapshot: () -> String? = { null }
) {
    fun restore(plan: McaBackupPayloadPlan.Ready): McaDatabaseRestoreResult {
        if (!IN_PROGRESS.compareAndSet(false, true)) {
            return McaDatabaseRestoreResult.Failed(
                McaDatabaseRestoreFailure.IN_PROGRESS,
                "上一次恢复尚未结束"
            )
        }
        try {
            val tables = plan.tables.filter { it.table.restorable }
            if (tables.isEmpty()) {
                return McaDatabaseRestoreResult.Failed(
                    McaDatabaseRestoreFailure.PAYLOAD_INVALID,
                    "备份不含本机可恢复的数据表"
                )
            }

            // Schema first: a foreign column must be refused before anything is deleted.
            val schemaByTable = LinkedHashMap<McaBackupTable, List<String>>()
            tables.forEach { table ->
                val dbTable = requireNotNull(table.table.databaseTable)
                val schema = target.tableColumns(dbTable)
                if (schema.isEmpty()) {
                    return McaDatabaseRestoreResult.Failed(
                        McaDatabaseRestoreFailure.SCHEMA_MISMATCH,
                        "数据库缺少表 $dbTable"
                    )
                }
                val unknown = table.columns.filterNot { it in schema }
                if (unknown.isNotEmpty()) {
                    return McaDatabaseRestoreResult.Failed(
                        McaDatabaseRestoreFailure.SCHEMA_MISMATCH,
                        "表 $dbTable 含当前数据库没有的列：${unknown.joinToString(", ")}"
                    )
                }
                schemaByTable[table.table] = schema
            }

            try {
                target.beginTransaction()
            } catch (error: Throwable) {
                return McaDatabaseRestoreResult.Failed(
                    McaDatabaseRestoreFailure.TRANSACTION_FAILED,
                    error.message ?: error.javaClass.simpleName
                )
            }

            try {
                tables.forEach { table ->
                    val dbTable = requireNotNull(table.table.databaseTable)
                    val schema = requireNotNull(schemaByTable[table.table])
                    val sourceIndex = schema.map { column -> table.columns.indexOf(column) }
                    target.deleteAll(dbTable)
                    table.rows.forEach { row ->
                        val values = sourceIndex.map { index -> if (index >= 0) row[index] else null }
                        target.insertRow(dbTable, schema, values)
                    }
                }
            } catch (error: Throwable) {
                runCatching { target.rollBack() }
                return McaDatabaseRestoreResult.Failed(
                    McaDatabaseRestoreFailure.INSERT_FAILED,
                    error.message ?: error.javaClass.simpleName
                )
            }

            tables.forEach { table ->
                val dbTable = requireNotNull(table.table.databaseTable)
                val restored = runCatching { target.rowCount(dbTable) }
                val actual = restored.getOrElse { -1 }
                if (actual != table.rowCount) {
                    runCatching { target.rollBack() }
                    return McaDatabaseRestoreResult.Failed(
                        McaDatabaseRestoreFailure.ROW_COUNT_MISMATCH,
                        "表 $dbTable 期望 ${table.rowCount} 行，实际 ${if (actual < 0) "读取失败" else "$actual 行"}"
                    )
                }
            }

            // Human fallback only: never part of the rollback decision.
            val snapshotNote = runCatching { snapshot() }.getOrNull()
            return try {
                target.commit()
                McaDatabaseRestoreResult.Restored(
                    tables = tables.size,
                    rows = tables.sumOf { it.rowCount },
                    snapshot = snapshotNote
                )
            } catch (error: Throwable) {
                runCatching { target.rollBack() }
                McaDatabaseRestoreResult.Failed(
                    McaDatabaseRestoreFailure.TRANSACTION_FAILED,
                    error.message ?: error.javaClass.simpleName
                )
            }
        } finally {
            IN_PROGRESS.set(false)
        }
    }

    private companion object {
        /** One restore at a time per process; the second caller is refused, never queued. */
        val IN_PROGRESS = AtomicBoolean(false)
    }
}
