package com.muyuchat.mca

import com.muyuchat.mca.agent.data.AgentWorkflowCheckpointSchema
import com.muyuchat.mca.schemacheck.JdbcSupportSQLiteDatabase
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM contract for the additive 34 → 35 workflow-frame migration. */
class AgentWorkflowCheckpointMigrationTest {
    @Test
    fun migration34To35AddsFrameTableAndPreservesDispatchRows() {
        Class.forName("org.sqlite.JDBC")
        JdbcSupportSQLiteDatabase(DriverManager.getConnection("jdbc:sqlite::memory:")).use { database ->
            database.execSQL("CREATE TABLE agent_runs (runId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL)")
            database.execSQL("INSERT INTO agent_runs VALUES ('run-existing', 'WAITING_APPROVAL')")
            database.execSQL(
                "CREATE TABLE agent_dispatch_outbox (idempotencyKey TEXT NOT NULL PRIMARY KEY, " +
                    "status TEXT NOT NULL, revision INTEGER NOT NULL)"
            )
            database.execSQL("INSERT INTO agent_dispatch_outbox VALUES ('obx-existing', 'PENDING', 0)")
            database.version = 34

            val migration = AgentWorkflowCheckpointSchema.MIGRATION_34_35
            migration.migrate(database)
            database.version = migration.endVersion

            assertEquals(34, migration.startVersion)
            assertEquals(35, migration.endVersion)
            assertEquals(35, database.version)

            database.query("SELECT state FROM agent_runs WHERE runId = 'run-existing'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("WAITING_APPROVAL", row.getString(0))
            }
            database.query(
                "SELECT status, revision FROM agent_dispatch_outbox WHERE idempotencyKey = 'obx-existing'"
            ).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("PENDING", row.getString(0))
                assertEquals(0L, row.getLong(1))
            }

            val columns = mutableListOf<Triple<String, Boolean, Int>>()
            database.query("PRAGMA table_info(`agent_workflow_checkpoints`)").use { row ->
                while (row.moveToNext()) {
                    columns += Triple(
                        row.getString(row.getColumnIndexOrThrow("name")),
                        row.getInt(row.getColumnIndexOrThrow("notnull")) == 1,
                        row.getInt(row.getColumnIndexOrThrow("pk"))
                    )
                }
            }
            assertEquals(
                listOf(
                    Triple("frameKey", true, 1),
                    Triple("revision", true, 0),
                    Triple("definitionId", true, 0),
                    Triple("definitionVersion", true, 0),
                    Triple("payloadJson", true, 0),
                    Triple("updatedAtMillis", true, 0)
                ),
                columns
            )

            database.execSQL(
                "INSERT INTO agent_workflow_checkpoints VALUES " +
                    "('wftask-1', 2, 'wf', 1, '{}', 1000)"
            )
            database.query("SELECT revision, payloadJson FROM agent_workflow_checkpoints").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(2L, row.getLong(0))
                assertEquals("{}", row.getString(1))
            }
        }
    }
}
