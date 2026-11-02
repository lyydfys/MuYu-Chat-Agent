package com.muyuchat.mca.schema

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.muyuchat.mca.schemacheck.JdbcSupportSQLiteDatabase
import com.muyuchat.mca.schemacheck.SchemaJsonSupport
import com.muyuchat.mca.video.data.VideoDatabaseSchema
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transactional-rollback contract for the 29 → 30 → 31 → 32 video migrations.
 *
 * Each production [Migration] is executed inside an explicit SQLite transaction (the shape Room's
 * `SQLiteOpenHelper.onUpgrade` gives it). A test-local [SupportSQLiteDatabase] wrapper runs the real
 * `execSQL` and then kills the "process" after the Nth statement. After the rollback the database
 * must still be the pre-migration version with **no partial video artefacts**; a fresh retry of the
 * chain must then succeed and reach schema 32.
 *
 * Honest limitation: this simulates process death *between* `execSQL` calls through the exact
 * production migration objects; it cannot kill a real Android process mid-statement, and Room's own
 * `RoomOpenHelper` wrapper is not present on the JVM. The transaction semantics under test are the
 * SQLite ones Room relies on (DDL rollback + `user_version` written only after success).
 */
class MigrationInterruptionContractTest {

    @Test
    fun failureAtEvery29To30StatementBoundaryRollsBackTo29AndRetrySucceeds() {
        val statements = statementCount(VideoDatabaseSchema.MIGRATION_29_30, schemaVersion = 29)
        assertEquals("29->30 statement count changed without updating this contract", 11, statements)
        for (failAfter in 1..statements) {
            newDatabase(29).use { database ->
                seedChatRow(database)
                database.beginTransaction()
                val failure = runCatching {
                    VideoDatabaseSchema.MIGRATION_29_30.migrate(FailingDatabase(database, failAfter))
                }.exceptionOrNull()
                database.endTransaction()

                assertTrue(
                    "boundary $failAfter must surface the simulated death, got $failure",
                    failure is SimulatedMigrationDeath
                )
                assertRolledBackTo29(database, "fail after 29->30 statement $failAfter")
                assertNoVideoTables(database, "fail after 29->30 statement $failAfter")

                retryFullChainFrom29(database)
                assertEquals(
                    "user_version after retry following boundary $failAfter",
                    32,
                    database.version
                )
                SchemaJsonSupport.assertDatabaseMatches(
                    database,
                    SchemaJsonSupport.loadDatabase(32),
                    "retry after 29->30 boundary $failAfter"
                )
                assertEquals(
                    "seeded chat row after retry following boundary $failAfter",
                    "Legacy chat",
                    SchemaJsonSupport.singleString(
                        database,
                        "SELECT title FROM chat_sessions WHERE id = 'legacy-session'"
                    )
                )
            }
        }
    }

    @Test
    fun failureAtEvery30To31StatementBoundaryRollsBackTo30AndRetrySucceeds() {
        val statements = statementCount(VideoDatabaseSchema.MIGRATION_30_31, schemaVersion = 30)
        assertEquals("30->31 statement count changed without updating this contract", 4, statements)
        for (failAfter in 1..statements) {
            newDatabase(30).use { database ->
                seedChatRow(database)
                database.beginTransaction()
                val failure = runCatching {
                    VideoDatabaseSchema.MIGRATION_30_31.migrate(FailingDatabase(database, failAfter))
                }.exceptionOrNull()
                database.endTransaction()

                assertTrue(
                    "boundary $failAfter must surface the simulated death, got $failure",
                    failure is SimulatedMigrationDeath
                )
                assertEquals(
                    "user_version must stay 30 after a rolled-back 30->31 boundary $failAfter",
                    30,
                    database.version
                )
                assertFalse(
                    "video_media_assets must not survive rollback at boundary $failAfter",
                    SchemaJsonSupport.actualTableNames(database).contains("video_media_assets")
                )
                SchemaJsonSupport.assertIntegrity(database, "rolled back 30->31 boundary $failAfter")

                database.beginTransaction()
                try {
                    VideoDatabaseSchema.MIGRATION_30_31.migrate(database)
                    VideoDatabaseSchema.MIGRATION_31_32.migrate(database)
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                database.version = 32
                SchemaJsonSupport.assertDatabaseMatches(
                    database,
                    SchemaJsonSupport.loadDatabase(32),
                    "retry after 30->31 boundary $failAfter"
                )
                assertEquals(
                    "user_version after retry following boundary $failAfter",
                    32,
                    database.version
                )
            }
        }
    }

    @Test
    fun failureAtEvery31To32StatementBoundaryRollsBackTo31AndRetrySucceeds() {
        val statements = statementCount(VideoDatabaseSchema.MIGRATION_31_32, schemaVersion = 31)
        assertEquals("31->32 statement count changed without updating this contract", 2, statements)
        for (failAfter in 1..statements) {
            newDatabase(31).use { database ->
                database.beginTransaction()
                val failure = runCatching {
                    VideoDatabaseSchema.MIGRATION_31_32.migrate(FailingDatabase(database, failAfter))
                }.exceptionOrNull()
                database.endTransaction()

                assertTrue(
                    "boundary $failAfter must surface the simulated death, got $failure",
                    failure is SimulatedMigrationDeath
                )
                assertEquals(
                    "user_version must stay 31 after a rolled-back 31->32 boundary $failAfter",
                    31,
                    database.version
                )
                val columnsAfterRollback = SchemaJsonSupport.tableInfo(database, "video_runs")
                assertFalse(
                    "remoteEndpointId must not survive rollback at boundary $failAfter",
                    columnsAfterRollback.containsKey("remoteEndpointId")
                )
                assertFalse(
                    "remotePromptId must not survive rollback at boundary $failAfter",
                    columnsAfterRollback.containsKey("remotePromptId")
                )
                SchemaJsonSupport.assertIntegrity(database, "rolled back 31->32 boundary $failAfter")

                database.beginTransaction()
                try {
                    VideoDatabaseSchema.MIGRATION_31_32.migrate(database)
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
                database.version = 32
                SchemaJsonSupport.assertDatabaseMatches(
                    database,
                    SchemaJsonSupport.loadDatabase(32),
                    "retry after 31->32 boundary $failAfter"
                )
            }
        }
    }

    private fun assertRolledBackTo29(database: SupportSQLiteDatabase, label: String) {
        assertEquals("$label: user_version must stay 29", 29, database.version)
        assertEquals(
            "$label: seeded chat row must survive the rollback",
            "Legacy chat",
            SchemaJsonSupport.singleString(
                database,
                "SELECT title FROM chat_sessions WHERE id = 'legacy-session'"
            )
        )
        SchemaJsonSupport.assertIntegrity(database, label)
    }

    private fun assertNoVideoTables(database: SupportSQLiteDatabase, label: String) {
        val videoTables = SchemaJsonSupport.actualTableNames(database).filter { it.startsWith("video_") }
        assertEquals("$label: no video table may survive the rollback", emptyList<String>(), videoTables)
    }

    private fun retryFullChainFrom29(database: SupportSQLiteDatabase) {
        database.beginTransaction()
        try {
            VideoDatabaseSchema.MIGRATION_29_30.migrate(database)
            VideoDatabaseSchema.MIGRATION_30_31.migrate(database)
            VideoDatabaseSchema.MIGRATION_31_32.migrate(database)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        database.version = 32
    }

    private fun statementCount(migration: Migration, schemaVersion: Int): Int =
        newDatabase(schemaVersion).use { database ->
            val counting = CountingDatabase(database)
            database.beginTransaction()
            try {
                migration.migrate(counting)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            counting.statements
        }

    private fun newDatabase(version: Int): JdbcSupportSQLiteDatabase {
        Class.forName("org.sqlite.JDBC")
        val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val database = JdbcSupportSQLiteDatabase(connection)
        SchemaJsonSupport.buildDatabase(connection, SchemaJsonSupport.loadDatabase(version))
        database.version = version
        return database
    }

    private fun seedChatRow(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO chat_sessions (id, title, pinned, manualTitle, updatedAt) " +
                "VALUES ('legacy-session', 'Legacy chat', 0, 0, 123)"
        )
    }

    /** Counts `execSQL` statements without failing; used to discover the production boundary count. */
    private class CountingDatabase(
        private val delegate: SupportSQLiteDatabase
    ) : SupportSQLiteDatabase by delegate {
        var statements: Int = 0
            private set

        override fun execSQL(sql: String) {
            delegate.execSQL(sql)
            statements += 1
        }

        override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
            delegate.execSQL(sql, bindArgs)
            statements += 1
        }
    }

    /**
     * Executes the real statement and then throws; the enclosing SQLite transaction is rolled back by
     * the test, exactly like a process death between two `execSQL` calls inside `onUpgrade`.
     */
    private class FailingDatabase(
        private val delegate: SupportSQLiteDatabase,
        private val failAfter: Int
    ) : SupportSQLiteDatabase by delegate {
        private var executed: Int = 0

        override fun execSQL(sql: String) {
            delegate.execSQL(sql)
            executed += 1
            if (executed == failAfter) throw SimulatedMigrationDeath()
        }

        override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
            delegate.execSQL(sql, bindArgs)
            executed += 1
            if (executed == failAfter) throw SimulatedMigrationDeath()
        }
    }

    private class SimulatedMigrationDeath : Error("simulated process death during onUpgrade")
}
