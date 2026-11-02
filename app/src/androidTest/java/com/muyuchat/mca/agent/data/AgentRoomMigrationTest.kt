package com.muyuchat.mca.agent.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.mca.McaRoomDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room's own 27 → 28 validation against the KSP-exported `27.json`/`28.json`.
 *
 * Creates the REAL v27 schema from `app/schemas/com.muyuchat.mca.McaRoomDatabase/27.json` (packaged
 * into the androidTest assets — see the `androidTest.assets.srcDir("schemas")` integration note in
 * [AgentDatabaseMigration]), inserts chat/assistant/attachment fixtures, applies
 * [AgentDatabaseMigration.MIGRATION_27_28], lets Room validate the resulting schema against `28.json`,
 * and verifies both the legacy rows and the new Agent tables/indices.
 *
 * NOT_RUN in this session: androidTest requires a connected device/emulator and the Lead's
 * `@Database(version = 28)` + entity registration must be integrated first. This file is the
 * instrumented evidence to run after that integration.
 */
@RunWith(AndroidJUnit4::class)
class AgentRoomMigrationTest {
    private lateinit var helper: MigrationTestHelper

    @Before
    fun setUp() {
        helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            McaRoomDatabase::class.java
        )
    }

    @Test
    fun migration27To28PreservesChatAssistantAndAttachmentFixtures() {
        helper.createDatabase(TEST_DATABASE, 27).use { db ->
            insertV27Fixtures(db)
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            28,
            true,
            AgentDatabaseMigration.MIGRATION_27_28
        )
        try {
            assertTrue(hasTable(migrated, "chat_sessions"))
            assertTrue(hasTable(migrated, "chat_messages"))
            assertTrue(hasTable(migrated, "assistants"))
            assertTrue(hasTable(migrated, "file_assets"))
            AgentDatabaseMigration.AGENT_TABLE_NAMES.forEach { table ->
                assertTrue("missing agent table $table", hasTable(migrated, table))
            }

            assertEquals(
                "Legacy chat",
                singleString(migrated, "SELECT title FROM chat_sessions WHERE id = 'session-1'")
            )
            assertEquals(
                "hello 世界",
                singleString(
                    migrated,
                    "SELECT content FROM chat_messages WHERE sessionId = 'session-1' AND position = 0"
                )
            )
            assertEquals(
                "Legacy assistant",
                singleString(migrated, "SELECT name FROM assistants WHERE id = 'assistant-1'")
            )
            assertEquals(
                "attached brief",
                singleString(migrated, "SELECT text FROM file_assets WHERE id = 'file-1'")
            )

            assertTrue(
                indexNames(migrated, "agent_events").contains("index_agent_events_runId_sequence")
            )
            assertTrue(
                indexNames(migrated, "agent_invocations")
                    .contains("index_agent_invocations_idempotencyKey")
            )
            assertTrue(
                indexNames(migrated, "agent_artifacts")
                    .contains("index_agent_artifacts_taskId_relativePath_version")
            )
            assertTrue(
                indexNames(migrated, "agent_commands").contains("index_agent_commands_contentHash")
            )
            assertEquals(
                "ok",
                singleString(migrated, "PRAGMA integrity_check")
            )
        } finally {
            migrated.close()
        }
    }

    private fun insertV27Fixtures(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO chat_sessions (id, title, pinned, manualTitle, updatedAt, mixedAssistantHistory) " +
                "VALUES ('session-1', 'Legacy chat', 0, 0, 123, 0)"
        )
        db.execSQL(
            "INSERT INTO chat_messages (sessionId, position, role, content, createdAt) " +
                "VALUES ('session-1', 0, 'user', 'hello 世界', 124)"
        )
        db.execSQL(
            "INSERT INTO assistants (id, name, avatar, systemPrompt, defaultModelMode, paramsJson, " +
                "memoryEnabled, webSearchEnabled, fileContextEnabled, createdAt, updatedAt) " +
                "VALUES ('assistant-1', 'Legacy assistant', '', '', 'local', '{}', 0, 0, 0, 1, 1)"
        )
        db.execSQL(
            "INSERT INTO file_assets (id, name, mimeType, text, preview, truncated, source, createdAt, sizeBytes) " +
                "VALUES ('file-1', 'brief.txt', 'text/plain', 'attached brief', 'attached', 0, 'import', 1, 14)"
        )
    }

    private fun hasTable(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }

    private fun singleString(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = linkedSetOf<String>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) names += cursor.getString(nameIndex)
        }
        return names
    }

    companion object {
        private const val TEST_DATABASE = "agent-migration-27-28.db"
    }
}
