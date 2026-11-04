package com.muyuchat.mca.backup

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the transactional restore: either every restorable table shows the backup's rows, or the
 * database is untouched.
 *
 * The target is an in-memory fake, so each failure path is asserted on real state (what the "database"
 * holds after the call) instead of on log text. That is what makes "rollback actually restores the
 * original data" a test rather than a comment.
 */
class McaBackupRestoreTest {

    private val sessions = McaBackupTable.CHAT_SESSIONS
    private val messages = McaBackupTable.CHAT_MESSAGES

    @Test
    fun `a valid payload replaces the rows and commits`() {
        val target = fakeTarget(initialRows = 3)
        val engine = McaBackupRestoreEngine(target)

        val result = engine.restore(readyPlan(sessions to 2, messages to 1))

        assertTrue(result is McaDatabaseRestoreResult.Restored)
        val restored = result as McaDatabaseRestoreResult.Restored
        assertEquals(2, restored.tables)
        assertEquals(3, restored.rows)
        assertTrue(target.committed)
        assertFalse(target.rolledBack)
        assertEquals(2, target.rowCount("chat_sessions"))
        assertEquals(1, target.rowCount("chat_messages"))
        assertEquals("title-0", target.valueAt("chat_sessions", 0, "title"))
    }

    @Test
    fun `a backup column the database does not have is refused before anything is deleted`() {
        val target = fakeTarget(initialRows = 2)
        val plan = readyPlanWithTable(
            McaBackupTableRows(
                table = sessions,
                columns = listOf("id", "not_a_real_column"),
                rows = listOf(listOf("s1", "x"))
            )
        )

        val result = McaBackupRestoreEngine(target).restore(plan)

        val failed = result as McaDatabaseRestoreResult.Failed
        assertEquals(McaDatabaseRestoreFailure.SCHEMA_MISMATCH, failed.failure)
        assertTrue(failed.detail.contains("chat_sessions"))
        assertTrue(failed.detail.contains("not_a_real_column"))
        assertEquals("schema problems must not open a transaction", 0, target.beginCalls.get())
        assertEquals(2, target.rowCount("chat_sessions"))
    }

    @Test
    fun `a schema column missing from the backup is written as null`() {
        val target = fakeTarget(initialRows = 1, schema = mapOf("chat_sessions" to listOf("id", "title", "extra")))
        val plan = readyPlanWithTable(
            McaBackupTableRows(
                table = sessions,
                columns = listOf("id", "title"),
                rows = listOf(listOf("s1", "只有两列"))
            )
        )

        val result = McaBackupRestoreEngine(target).restore(plan)

        assertTrue(result is McaDatabaseRestoreResult.Restored)
        assertNull(target.valueAt("chat_sessions", 0, "extra"))
        assertEquals("只有两列", target.valueAt("chat_sessions", 0, "title"))
    }

    @Test
    fun `a row count that does not survive the transaction rolls back`() {
        val target = fakeTarget(initialRows = 4, dropOneRowOnInsert = true)
        val plan = readyPlan(sessions to 2, messages to 1)

        val result = McaBackupRestoreEngine(target).restore(plan)

        val failed = result as McaDatabaseRestoreResult.Failed
        assertEquals(McaDatabaseRestoreFailure.ROW_COUNT_MISMATCH, failed.failure)
        assertTrue(target.rolledBack)
        assertEquals("the original rows must be back", 4, target.rowCount("chat_sessions"))
    }

    @Test
    fun `an insert that throws rolls back and keeps the original data`() {
        val target = fakeTarget(initialRows = 2, failingTable = "chat_messages")
        val plan = readyPlan(sessions to 1, messages to 1)

        val result = McaBackupRestoreEngine(target).restore(plan)

        val failed = result as McaDatabaseRestoreResult.Failed
        assertEquals(McaDatabaseRestoreFailure.INSERT_FAILED, failed.failure)
        assertTrue(target.rolledBack)
        assertEquals(2, target.rowCount("chat_sessions"))
        assertEquals(2, target.rowCount("chat_messages"))
    }

    @Test
    fun `a transaction that cannot start is reported without touching data`() {
        val target = fakeTarget(initialRows = 2, failBegin = true)

        val result = McaBackupRestoreEngine(target).restore(readyPlan(sessions to 1, messages to 1))

        val failed = result as McaDatabaseRestoreResult.Failed
        assertEquals(McaDatabaseRestoreFailure.TRANSACTION_FAILED, failed.failure)
        assertEquals(2, target.rowCount("chat_sessions"))
    }

    @Test
    fun `a commit that throws rolls back`() {
        val target = fakeTarget(initialRows = 2, failCommit = true)

        val result = McaBackupRestoreEngine(target).restore(readyPlan(sessions to 1, messages to 1))

        val failed = result as McaDatabaseRestoreResult.Failed
        assertEquals(McaDatabaseRestoreFailure.TRANSACTION_FAILED, failed.failure)
        assertTrue(target.rolledBack)
        assertEquals(2, target.rowCount("chat_sessions"))
    }

    @Test
    fun `a second restore is refused while one is running, and allowed afterwards`() {
        val target = fakeTarget(initialRows = 2, blockBeginUntilReleased = true)
        val firstFailure = AtomicReference<McaDatabaseRestoreResult>()
        val worker = Thread {
            firstFailure.set(McaBackupRestoreEngine(target).restore(readyPlan(sessions to 1, messages to 1)))
        }
        worker.start()
        assertTrue("first restore should reach the target", target.beginEntered.await(5, TimeUnit.SECONDS))

        val second = McaBackupRestoreEngine(target).restore(readyPlan(sessions to 1, messages to 1))

        val rejected = second as McaDatabaseRestoreResult.Failed
        assertEquals(McaDatabaseRestoreFailure.IN_PROGRESS, rejected.failure)
        assertEquals("restore_in_progress", rejected.failure.code)

        target.releaseBegin.countDown()
        worker.join(10_000)
        assertTrue(firstFailure.get() is McaDatabaseRestoreResult.Restored)

        // The lock must be released even by the successful path.
        val third = McaBackupRestoreEngine(target).restore(readyPlan(sessions to 1, messages to 1))
        assertTrue("a later restore must proceed", third is McaDatabaseRestoreResult.Restored)
    }

    @Test
    fun `the lock is released after a failed restore too`() {
        val target = fakeTarget(initialRows = 2, failingTable = "chat_messages")

        val first = McaBackupRestoreEngine(target).restore(readyPlan(sessions to 1, messages to 1))
        assertTrue(first is McaDatabaseRestoreResult.Failed)

        target.failingTable = null
        val second = McaBackupRestoreEngine(target).restore(readyPlan(sessions to 1, messages to 1))
        assertTrue(second is McaDatabaseRestoreResult.Restored)
    }

    @Test
    fun `an empty table restores successfully`() {
        val target = fakeTarget(initialRows = 2)

        val result = McaBackupRestoreEngine(target).restore(readyPlan(sessions to 0, messages to 0))

        assertTrue(result is McaDatabaseRestoreResult.Restored)
        assertEquals(0, (result as McaDatabaseRestoreResult.Restored).rows)
        assertEquals(0, target.rowCount("chat_sessions"))
    }

    @Test
    fun `a plan without any restorable table is refused`() {
        val target = fakeTarget()
        val plan = McaBackupPayloadPlan.Ready(
            tables = listOf(
                McaBackupTableRows(McaBackupTable.WORLD_BOOKS, listOf("id"), listOf(listOf("w1")))
            ),
            totalRows = 1,
            redacted = emptyMap()
        )

        val result = McaBackupRestoreEngine(target).restore(plan)

        val failed = result as McaDatabaseRestoreResult.Failed
        assertEquals(McaDatabaseRestoreFailure.PAYLOAD_INVALID, failed.failure)
        assertEquals(0, target.beginCalls.get())
    }

    @Test
    fun `file-backed families are ignored instead of written`() {
        val target = fakeTarget(initialRows = 1)
        val plan = McaBackupPayloadPlan.Ready(
            tables = listOf(
                McaBackupTableRows(McaBackupTable.WORLD_BOOKS, listOf("id"), listOf(listOf("w1"), listOf("w2"))),
                McaBackupTableRows(sessions, listOf("id", "title"), listOf(listOf("s1", "新标题")))
            ),
            totalRows = 3,
            redacted = emptyMap()
        )

        val result = McaBackupRestoreEngine(target).restore(plan)

        val restored = result as McaDatabaseRestoreResult.Restored
        assertEquals("only the restorable table is counted", 1, restored.tables)
        assertEquals(1, restored.rows)
        assertFalse("world books are not a database table", target.writtenTables.contains("world_books"))
    }

    @Test
    fun `redacted columns are simply absent and do not block the restore`() {
        val target = fakeTarget(initialRows = 1, schema = mapOf("chat_sessions" to listOf("id", "title", "apiKey")))
        val plan = readyPlanWithTable(
            McaBackupTableRows(
                table = sessions,
                columns = listOf("id", "title"),
                rows = listOf(listOf("s1", "t")),
                redactedColumns = listOf("apiKey")
            )
        )

        val result = McaBackupRestoreEngine(target).restore(plan)

        assertTrue(result is McaDatabaseRestoreResult.Restored)
        assertNull("the stripped credential column is written as null, never restored", target.valueAt("chat_sessions", 0, "apiKey"))
    }

    @Test
    fun `the snapshot is taken before the commit and its note is returned`() {
        val target = fakeTarget(initialRows = 1)
        var snapshotTakenWhileUncommitted: Boolean? = null
        val notes = AtomicInteger()

        val result = McaBackupRestoreEngine(target, snapshot = {
            notes.incrementAndGet()
            snapshotTakenWhileUncommitted = !target.committed && target.beginCalls.get() > 0
            "已先备份当前数据到 cache/backup/restore-snapshot.json"
        }).restore(readyPlan(sessions to 1, messages to 1))

        val restored = result as McaDatabaseRestoreResult.Restored
        assertEquals(1, notes.get())
        assertEquals(true, snapshotTakenWhileUncommitted)
        assertTrue(restored.snapshot.orEmpty().contains("restore-snapshot.json"))
    }

    @Test
    fun `a failing snapshot does not change the restore outcome`() {
        val target = fakeTarget(initialRows = 1)

        val result = McaBackupRestoreEngine(target, snapshot = { error("磁盘已满") })
            .restore(readyPlan(sessions to 1, messages to 1))

        val restored = result as McaDatabaseRestoreResult.Restored
        assertNull("a lost fallback copy must not be reported as a failure", restored.snapshot)
        assertTrue(target.committed)
    }

    @Test
    fun `failure codes are stable identifiers, not copy`() {
        assertEquals("restore_in_progress", McaDatabaseRestoreFailure.IN_PROGRESS.code)
        assertEquals("schema_mismatch", McaDatabaseRestoreFailure.SCHEMA_MISMATCH.code)
        assertEquals("row_count_mismatch", McaDatabaseRestoreFailure.ROW_COUNT_MISMATCH.code)
        assertEquals("insert_failed", McaDatabaseRestoreFailure.INSERT_FAILED.code)
        assertEquals("transaction_failed", McaDatabaseRestoreFailure.TRANSACTION_FAILED.code)
        assertEquals("payload_invalid", McaDatabaseRestoreFailure.PAYLOAD_INVALID.code)
    }

    @Test
    fun `an exported payload restores into an empty database with the same rows`() {
        val restorable = McaBackupTable.RESTORABLE
        val exported = restorable.map { table ->
            when (table) {
                McaBackupTable.CHAT_SESSIONS -> McaBackupRows.build(
                    table,
                    listOf("id", "title"),
                    listOf(listOf("s1", "标题"), listOf("s2", null))
                )

                McaBackupTable.CHAT_MESSAGES -> McaBackupRows.build(
                    table,
                    listOf("id", "title"),
                    listOf(listOf("m1", "内容"))
                )

                else -> McaBackupRows.build(table, listOf("id", "title"), emptyList())
            }
        }
        val envelope = McaBackupEnvelope(
            createdAtMillis = 1_700_000_000_000L,
            appVersionName = "1.0",
            tables = restorable.map { table ->
                McaBackupTableCount(table, exported.first { it.table == table }.rowCount)
            },
            settings = emptyMap(),
            payload = exported
        )
        val decoded = decodeMcaBackupEnvelope(envelope.toJson()) as McaBackupDecodeResult.Decoded
        val plan = McaBackupRows.plan(decoded.envelope, decoded.envelope.payload)
        assertTrue("plan must be ready, was $plan", plan is McaBackupPayloadPlan.Ready)

        val target = fakeTarget(initialRows = 0)
        val result = McaBackupRestoreEngine(target).restore(plan as McaBackupPayloadPlan.Ready)

        assertTrue(result is McaDatabaseRestoreResult.Restored)
        assertEquals(2, target.rowCount("chat_sessions"))
        assertEquals("标题", target.valueAt("chat_sessions", 0, "title"))
        assertNull(target.valueAt("chat_sessions", 1, "title"))
        assertEquals("内容", target.valueAt("chat_messages", 0, "title"))
        assertEquals(restorable.size, (result as McaDatabaseRestoreResult.Restored).tables)
    }

    // ---- helpers ----

    private fun readyPlan(vararg counts: Pair<McaBackupTable, Int>): McaBackupPayloadPlan.Ready =
        readyPlanWithTables(
            *counts.map { (table, rows) ->
                McaBackupTableRows(
                    table = table,
                    columns = listOf("id", "title"),
                    rows = List(rows) { index -> listOf("${table.wireId}-$index", "title-$index") }
                )
            }.toTypedArray()
        )

    private fun readyPlanWithTable(table: McaBackupTableRows): McaBackupPayloadPlan.Ready =
        readyPlanWithTables(table)

    private fun readyPlanWithTables(vararg tables: McaBackupTableRows): McaBackupPayloadPlan.Ready =
        McaBackupPayloadPlan.Ready(
            tables = tables.toList(),
            totalRows = tables.sumOf { it.rowCount },
            redacted = emptyMap()
        )

    private fun fakeTarget(
        initialRows: Int = 0,
        schema: Map<String, List<String>> = McaBackupTable.RESTORABLE.associate { table ->
            table.databaseTable!! to listOf("id", "title")
        },
        failingTable: String? = null,
        dropOneRowOnInsert: Boolean = false,
        failBegin: Boolean = false,
        failCommit: Boolean = false,
        blockBeginUntilReleased: Boolean = false
    ) = FakeRestoreTarget(
        schema = schema,
        initialRows = initialRows,
        failingTable = failingTable,
        dropOneRowOnInsert = dropOneRowOnInsert,
        failBegin = failBegin,
        failCommit = failCommit,
        blockBeginUntilReleased = blockBeginUntilReleased
    )

    /** In-memory database: rows per table, one snapshot for rollback. */
    private class FakeRestoreTarget(
        private val schema: Map<String, List<String>>,
        initialRows: Int,
        var failingTable: String? = null,
        private val dropOneRowOnInsert: Boolean = false,
        private val failBegin: Boolean = false,
        private val failCommit: Boolean = false,
        private val blockBeginUntilReleased: Boolean = false
    ) : McaBackupRestoreTarget {

        val tables = LinkedHashMap<String, MutableList<MutableList<Any?>>>()
        val writtenTables = LinkedHashSet<String>()
        val beginCalls = AtomicInteger()
        val beginEntered = CountDownLatch(1)
        val releaseBegin = CountDownLatch(1)
        var committed = false
        var rolledBack = false

        private var snapshot: Map<String, List<List<Any?>>>? = null
        private var open = false

        init {
            schema.keys.forEach { table ->
                val rows = MutableList(initialRows) { index ->
                    MutableList<Any?>(schema.getValue(table).size) { columnIndex ->
                        if (columnIndex == 0) "$table-$index" else "$table-cell-$index-$columnIndex"
                    }
                }
                tables[table] = rows
            }
        }

        override fun rowCount(table: String): Int = tables[table]?.size ?: 0

        fun valueAt(table: String, row: Int, column: String): Any? {
            val columns = schema.getValue(table)
            return tables.getValue(table)[row][columns.indexOf(column)]
        }

        override fun beginTransaction() {
            beginCalls.incrementAndGet()
            if (blockBeginUntilReleased) {
                beginEntered.countDown()
                releaseBegin.await(10, TimeUnit.SECONDS)
            }
            if (failBegin) error("begin failed")
            snapshot = tables.mapValues { (_, rows) -> rows.map { it.toList() } }
            open = true
        }

        override fun tableColumns(table: String): List<String> = schema[table].orEmpty()

        override fun deleteAll(table: String) {
            tables.getOrPut(table) { mutableListOf() }.clear()
            writtenTables += table
        }

        override fun insertRow(table: String, columns: List<String>, values: List<Any?>) {
            if (table == failingTable) error("insert failed for $table")
            val rows = tables.getOrPut(table) { mutableListOf() }
            if (dropOneRowOnInsert && rows.isNotEmpty()) return
            val schemaColumns = schema.getValue(table)
            val aligned = MutableList<Any?>(schemaColumns.size) { null }
            columns.forEachIndexed { index, column ->
                val target = schemaColumns.indexOf(column)
                if (target >= 0) aligned[target] = values.getOrNull(index)
            }
            rows += aligned
        }

        override fun commit() {
            if (failCommit) error("commit failed")
            committed = true
            open = false
        }

        override fun rollBack() {
            rolledBack = true
            snapshot?.let { saved ->
                tables.clear()
                saved.forEach { (table, rows) ->
                    tables[table] = rows.map { it.toMutableList() }.toMutableList()
                }
            }
            open = false
        }
    }
}
