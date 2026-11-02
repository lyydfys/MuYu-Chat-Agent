package com.muyuchat.mca

import com.muyuchat.mca.agent.data.AgentDispatchOutboxSchema
import com.muyuchat.mca.schemacheck.JdbcSupportSQLiteDatabase
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM contract for the 33 → 34 additive outbox migration.
 *
 * Builds a v33-shaped database with the S4 park token and a representative run row, applies the
 * real migration, and asserts the existing rows survive, the new table/index match the entity, and
 * a dispatch row round-trips. Room's own identity check uses the exported 34.json on device.
 */
class AgentDispatchOutboxMigrationTest {
    @Test
    fun migration33To34AddsOutboxAndPreservesExistingRows() {
        Class.forName("org.sqlite.JDBC")
        JdbcSupportSQLiteDatabase(DriverManager.getConnection("jdbc:sqlite::memory:")).use { database ->
            database.execSQL("CREATE TABLE agent_runs (runId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL)")
            database.execSQL("INSERT INTO agent_runs VALUES ('run-existing', 'WAITING_APPROVAL')")
            database.execSQL(
                "CREATE TABLE agent_park_tokens (runId TEXT NOT NULL PRIMARY KEY, wakeId TEXT NOT NULL)"
            )
            database.execSQL("INSERT INTO agent_park_tokens VALUES ('run-existing', 'wake-existing')")
            database.version = 33

            val migration = AgentDispatchOutboxSchema.MIGRATION_33_34
            migration.migrate(database)
            database.version = migration.endVersion

            assertEquals(33, migration.startVersion)
            assertEquals(34, migration.endVersion)
            assertEquals(34, database.version)

            database.query("SELECT state FROM agent_runs WHERE runId = 'run-existing'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("WAITING_APPROVAL", row.getString(0))
            }
            database.query("SELECT wakeId FROM agent_park_tokens WHERE runId = 'run-existing'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("wake-existing", row.getString(0))
            }

            assertTrue(tableExists(database, "agent_dispatch_outbox"))
            val columns = mutableListOf<Triple<String, Boolean, Int>>()
            database.query("PRAGMA table_info(`agent_dispatch_outbox`)").use { row ->
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
                    Triple("idempotencyKey", true, 1),
                    Triple("invocationId", true, 0),
                    Triple("runId", true, 0),
                    Triple("stepId", true, 0),
                    Triple("callOrdinal", true, 0),
                    Triple("toolKey", true, 0),
                    Triple("toolVersion", true, 0),
                    Triple("approvedAtMillis", true, 0),
                    Triple("status", true, 0),
                    Triple("leaseOwner", false, 0),
                    Triple("leaseExpiresAtMillis", true, 0),
                    Triple("dispatchAttempts", true, 0),
                    Triple("settledInvocationStatus", false, 0),
                    Triple("errorCode", false, 0),
                    Triple("updatedAtMillis", true, 0),
                    Triple("revision", true, 0)
                ),
                columns
            )

            var indexFound = false
            database.query("PRAGMA index_list(`agent_dispatch_outbox`)").use { row ->
                while (row.moveToNext()) {
                    if (row.getString(row.getColumnIndexOrThrow("name")) ==
                        "index_agent_dispatch_outbox_status_updatedAtMillis"
                    ) {
                        indexFound = true
                    }
                }
            }
            assertTrue("the outbox status/updated index must exist", indexFound)

            database.execSQL(
                "INSERT INTO agent_dispatch_outbox (" +
                    "idempotencyKey, invocationId, runId, stepId, callOrdinal, toolKey, toolVersion, " +
                    "approvedAtMillis, status, leaseOwner, leaseExpiresAtMillis, dispatchAttempts, " +
                    "settledInvocationStatus, errorCode, updatedAtMillis, revision" +
                    ") VALUES ('obx-1','inv-1','run-1','step-1',0,'artifact.write_text','1'," +
                    "1000,'PENDING',NULL,0,0,NULL,NULL,1000,0)"
            )
            database.query("SELECT status, revision FROM agent_dispatch_outbox WHERE idempotencyKey = 'obx-1'")
                .use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals("PENDING", row.getString(0))
                    assertEquals(0L, row.getLong(1))
                }
        }
    }

    private fun tableExists(database: JdbcSupportSQLiteDatabase, table: String): Boolean {
        database.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'")
            .use { row -> return row.moveToFirst() }
    }
}
