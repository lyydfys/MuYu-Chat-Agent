package com.muyuchat.mca

import com.muyuchat.mca.agent.data.AgentParkTokenSchema
import com.muyuchat.mca.schemacheck.JdbcSupportSQLiteDatabase
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM contract for the 32 → 33 park-token migration.
 *
 * Builds a minimal v32-shaped database with a representative `agent_runs` row, executes the real
 * [AgentParkTokenSchema.MIGRATION_32_33] through the same sqlite-jdbc adapter the other migration
 * suites use, and asserts the pre-existing row is untouched, the new table/index have the exact
 * shape [com.muyuchat.mca.agent.data.AgentParkTokenEntity] declares, and a token round-trips with
 * every field. Room's own identity-hash validation still happens on the next instrumented run
 * against the exported `33.json`.
 */
class AgentParkTokenMigrationTest {
    @Test
    fun migration32To33AddsParkTokenTableAndPreservesExistingRunRows() {
        Class.forName("org.sqlite.JDBC")
        JdbcSupportSQLiteDatabase(DriverManager.getConnection("jdbc:sqlite::memory:")).use { database ->
            createV32Fixture(database)
            database.version = 32

            val migration = AgentParkTokenSchema.MIGRATION_32_33
            migration.migrate(database)
            database.version = migration.endVersion

            assertEquals(32, migration.startVersion)
            assertEquals(33, migration.endVersion)
            assertEquals(33, database.version)

            // The run parked at v32 keeps its identity, state, epoch and revision.
            database.query(
                "SELECT taskId, state, epoch, revision FROM agent_runs WHERE runId = 'run-existing'"
            ).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("task-existing", row.getString(0))
                assertEquals("WAITING_APPROVAL", row.getString(1))
                assertEquals(4L, row.getLong(2))
                assertEquals(9L, row.getLong(3))
                assertTrue(!row.moveToNext())
            }
            database.query("SELECT title FROM chat_sessions WHERE id = 'session-1'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("Legacy chat", row.getString(0))
            }

            assertTrue(
                "agent_park_tokens must exist after the migration",
                tableExists(database, "agent_park_tokens")
            )
            assertEquals(
                listOf(
                    Column("runId", notNull = true, primaryKeyPosition = 1),
                    Column("epoch", notNull = true),
                    Column("checkpointRevision", notNull = true),
                    Column("wakeId", notNull = true),
                    Column("kind", notNull = true),
                    Column("approvalId", notNull = false),
                    Column("invocationId", notNull = false),
                    Column("questionId", notNull = false),
                    Column("transcriptJson", notNull = true),
                    Column("createdAtMillis", notNull = true)
                ),
                columns(database, "agent_park_tokens")
            )

            database.query("PRAGMA index_list(`agent_park_tokens`)").use { indices ->
                var found = false
                while (indices.moveToNext()) {
                    if (indices.getString(indices.getColumnIndexOrThrow("name")) ==
                        "index_agent_park_tokens_wakeId"
                    ) {
                        found = true
                        assertEquals(
                            "the wake index must be unique",
                            1,
                            indices.getInt(indices.getColumnIndexOrThrow("unique"))
                        )
                    }
                }
                assertTrue("index_agent_park_tokens_wakeId must exist", found)
            }
            assertEquals(
                listOf("wakeId"),
                indexedColumns(database, "index_agent_park_tokens_wakeId")
            )

            val transcript = """{"turns":[{"role":"user","text":"run the approved tool"}]}"""
            database.execSQL(
                "INSERT INTO agent_park_tokens " +
                    "(runId, epoch, checkpointRevision, wakeId, kind, approvalId, invocationId, " +
                    "questionId, transcriptJson, createdAtMillis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "run-1", 7L, 12L, "wake-approval-1", "APPROVAL", "approval-1",
                    "invocation-1", null, transcript, 1234L
                )
            )
            database.execSQL(
                "INSERT INTO agent_park_tokens " +
                    "(runId, epoch, checkpointRevision, wakeId, kind, approvalId, invocationId, " +
                    "questionId, transcriptJson, createdAtMillis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "run-2", 8L, 13L, "wake-question-1", "USER", null, null,
                    "question-1", "{}", 1235L
                )
            )

            database.query(
                "SELECT runId, epoch, checkpointRevision, wakeId, kind, approvalId, invocationId, " +
                    "questionId, transcriptJson, createdAtMillis " +
                    "FROM agent_park_tokens WHERE runId = 'run-1'"
            ).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("run-1", row.getString(0))
                assertEquals(7L, row.getLong(1))
                assertEquals(12L, row.getLong(2))
                assertEquals("wake-approval-1", row.getString(3))
                assertEquals("APPROVAL", row.getString(4))
                assertEquals("approval-1", row.getString(5))
                assertEquals("invocation-1", row.getString(6))
                assertTrue(row.isNull(7))
                assertEquals(transcript, row.getString(8))
                assertEquals(1234L, row.getLong(9))
            }
            database.query(
                "SELECT approvalId, invocationId, questionId FROM agent_park_tokens WHERE runId = 'run-2'"
            ).use { row ->
                assertTrue(row.moveToFirst())
                assertTrue(row.isNull(0))
                assertTrue(row.isNull(1))
                assertEquals("question-1", row.getString(2))
            }

            // The unique wake id is what makes consume a CAS rather than a best-effort delete.
            try {
                database.execSQL(
                    "INSERT INTO agent_park_tokens " +
                        "(runId, epoch, checkpointRevision, wakeId, kind, approvalId, invocationId, " +
                        "questionId, transcriptJson, createdAtMillis) VALUES " +
                        "('run-3', 9, 14, 'wake-approval-1', 'APPROVAL', NULL, NULL, NULL, '{}', 1236)"
                )
                fail("a duplicate wakeId must be rejected by the unique index")
            } catch (_: SQLException) {
                // expected
            }
            database.query("SELECT COUNT(*) FROM agent_park_tokens").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(2, row.getInt(0))
            }
        }
    }

    private fun createV32Fixture(database: JdbcSupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE chat_sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)"
        )
        database.execSQL("INSERT INTO chat_sessions (id, title) VALUES ('session-1', 'Legacy chat')")
        database.execSQL(
            "CREATE TABLE agent_runs (" +
                "runId TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, state TEXT NOT NULL, " +
                "epoch INTEGER NOT NULL, revision INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
        )
        database.execSQL(
            "INSERT INTO agent_runs (runId, taskId, state, epoch, revision, updatedAt) " +
                "VALUES ('run-existing', 'task-existing', 'WAITING_APPROVAL', 4, 9, 100)"
        )
    }

    private fun tableExists(database: JdbcSupportSQLiteDatabase, table: String): Boolean =
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { row -> row.moveToFirst() }

    private data class Column(
        val name: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int = 0
    )

    private fun columns(database: JdbcSupportSQLiteDatabase, table: String): List<Column> {
        val result = mutableListOf<Column>()
        database.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                val primaryKey = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                assertNull(
                    "$table.$name must not declare a default; Room's 33.json has none",
                    cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                )
                result += Column(name, notNull, primaryKey)
            }
        }
        return result
    }

    private fun indexedColumns(database: JdbcSupportSQLiteDatabase, index: String): List<String> {
        val result = mutableListOf<String>()
        database.query("PRAGMA index_info(`$index`)").use { cursor ->
            while (cursor.moveToNext()) {
                result += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        return result
    }
}
