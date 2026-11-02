package com.muyuchat.mca.agent.memory.data

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
 * Room's own 28 → 29 validation against the KSP-exported `28.json`/`29.json`.
 *
 * Creates the REAL v28 schema from the exported bundle, inserts legacy chat/assistant rows plus one
 * agent workspace, applies [AgentMemorySchema.MIGRATION_28_29], lets Room validate the resulting
 * schema against `29.json` (identity hash + TableInfo), and then proves the four memory tables are
 * actually usable with their unique/foreign-key constraints.
 *
 * NOT_RUN without a connected device: this is the instrumented evidence to run after the build that
 * regenerates `29.json`. The JVM `AgentMemorySchemaSqlTest` covers the same SQL without Room.
 */
@RunWith(AndroidJUnit4::class)
class AgentMemoryRoomMigrationTest {
    private lateinit var helper: MigrationTestHelper

    @Before
    fun setUp() {
        helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            McaRoomDatabase::class.java
        )
    }

    @Test
    fun migration28To29CreatesMemoryTablesAndPreservesLegacyRows() {
        helper.createDatabase(TEST_DATABASE, 28).use { db ->
            db.execSQL(
                "INSERT INTO chat_sessions (id, title, pinned, manualTitle, updatedAt) " +
                    "VALUES ('session-1', 'Legacy chat', 0, 0, 123)"
            )
            db.execSQL(
                "INSERT INTO chat_messages (sessionId, position, role, content, createdAt) " +
                    "VALUES ('session-1', 0, 'user', 'hello 世界', 124)"
            )
            db.execSQL(
                "INSERT INTO agent_workspaces (workspaceId, displayName, storageRef, createdAt, updatedAt) " +
                    "VALUES ('workspace-1', 'Legacy workspace', 'workspace-1', 1, 1)"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            29,
            true,
            AgentMemorySchema.MIGRATION_28_29
        )
        try {
            AgentMemorySchema.TABLE_NAMES.forEach { table ->
                assertTrue("missing memory table $table", hasTable(migrated, table))
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
                "Legacy workspace",
                singleString(migrated, "SELECT displayName FROM agent_workspaces WHERE workspaceId = 'workspace-1'")
            )

            migrated.execSQL(
                "INSERT INTO agent_memory_spaces (spaceId, displayName, createdAt, updatedAt, revision) " +
                    "VALUES ('space-1', 'Work', 1, 1, 1)"
            )
            migrated.execSQL(
                "INSERT INTO agent_memory_items (memoryId, spaceId, title, text, contentHash, sourceKind, " +
                    "sourceRef, tokenCount, chunkCount, createdAt, updatedAt, revision, revoked, revokedAt) VALUES " +
                    "('memory-1', 'space-1', 'title', 'text', '${"a".repeat(64)}', 'USER_TEXT', NULL, 1, 1, 1, 1, 1, 0, NULL)"
            )
            migrated.execSQL(
                "INSERT INTO agent_memory_chunks (chunkId, memoryId, spaceId, position, text, contentHash, " +
                    "estimatedTokens, createdAt) VALUES " +
                    "('memory-1:0', 'memory-1', 'space-1', 0, 'text', '${"b".repeat(64)}', 1, 1)"
            )
            migrated.execSQL(
                "INSERT INTO agent_memory_task_selections (taskId, spaceIdsJson, selectionHash, createdAt) " +
                    "VALUES ('task-1', '[\"space-1\"]', '${"c".repeat(64)}', 1)"
            )
            assertEquals(1, count(migrated, "SELECT COUNT(*) FROM agent_memory_items"))
            assertEquals(1, count(migrated, "SELECT COUNT(*) FROM agent_memory_chunks"))

            assertTrue(
                indexNames(migrated, "agent_memory_items")
                    .contains("index_agent_memory_items_spaceId_contentHash")
            )
            assertTrue(
                indexNames(migrated, "agent_memory_chunks")
                    .contains("index_agent_memory_chunks_spaceId_memoryId_position")
            )
            assertEquals("ok", singleString(migrated, "PRAGMA integrity_check"))
        } finally {
            migrated.close()
        }
    }

    private fun hasTable(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }

    private fun count(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

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

    private companion object {
        private const val TEST_DATABASE = "agent-memory-migration-28-29.db"
    }
}
