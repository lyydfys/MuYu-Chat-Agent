package com.muyuchat.mca.video.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.muyuchat.mca.agent.data.AgentDispatchOutboxSchema
import com.muyuchat.mca.agent.data.AgentParkTokenSchema
import com.muyuchat.mca.agent.data.AgentWorkflowCheckpointSchema
import com.muyuchat.mca.schemacheck.JdbcSupportSQLiteDatabase
import com.muyuchat.mca.schemacheck.SchemaJsonSupport
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM execution contract for the registered 29 → 30 → 31 → 32 video migration chain.
 *
 * Why this exists: before this test, the only execution evidence for the chain was the
 * instrumented `VideoRoomMigrationTest` (device-only, NOT_RUN in CI), and `30.json`/`31.json`
 * were never compared to a migrated database. This suite runs the **exact production Migration
 * objects** from [VideoDatabaseSchema] over a synthetic 29 database built from the KSP-exported
 * `29.json`, then asserts after every step that the physical schema matches that version's exported
 * JSON structurally.
 *
 * The comparison uses [SchemaJsonSupport] which compares columns by **name** (Room's `TableInfo`
 * semantics), so the `ALTER TABLE` appended v32 columns are accepted despite a different physical
 * ordinal; index column order and foreign keys are compared exactly.
 */
class VideoSchemaSqlTest {

    @Test
    fun migrationChainFrom29MatchesEachExportedSchemaAndKeepsRows() {
        newDatabase().use { database ->
            SchemaJsonSupport.buildDatabase(database, SchemaJsonSupport.loadDatabase(29))
            database.version = 29
            seedLegacyRows(database)
            SchemaJsonSupport.assertDatabaseMatches(
                database,
                SchemaJsonSupport.loadDatabase(29),
                "synthetic v29"
            )

            applyMigration(database, VideoDatabaseSchema.MIGRATION_29_30)
            SchemaJsonSupport.assertDatabaseMatches(
                database,
                SchemaJsonSupport.loadDatabase(30),
                "after 29->30"
            )
            assertLegacyRowsSurvive(database)

            applyMigration(database, VideoDatabaseSchema.MIGRATION_30_31)
            SchemaJsonSupport.assertDatabaseMatches(
                database,
                SchemaJsonSupport.loadDatabase(31),
                "after 30->31"
            )
            assertLegacyRowsSurvive(database)

            applyMigration(database, VideoDatabaseSchema.MIGRATION_31_32)
            // Room's RoomOpenHelper writes PRAGMA user_version after the whole migration path
            // succeeds; the Migration objects themselves do not touch it, so model that write here.
            database.version = 32
            assertEquals("user_version after the full chain", 32, database.version)
            SchemaJsonSupport.assertDatabaseMatches(
                database,
                SchemaJsonSupport.loadDatabase(32),
                "after 31->32"
            )
            SchemaJsonSupport.assertIntegrity(database, "after 31->32")
            assertLegacyRowsSurvive(database)
            assertVideoTablesAreWritable(database)
        }
    }

    @Test
    fun wholeChainFrom29To32SucceedsInOneRoomLikeTransaction() {
        newDatabase().use { database ->
            SchemaJsonSupport.buildDatabase(database, SchemaJsonSupport.loadDatabase(29))
            database.version = 29
            seedLegacyRows(database)

            // Room runs the whole migration path for one upgrade inside the SQLiteOpenHelper
            // transaction; either every step commits or the database stays at 29.
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

            assertEquals("user_version after one-pass 29->32", 32, database.version)
            SchemaJsonSupport.assertDatabaseMatches(
                database,
                SchemaJsonSupport.loadDatabase(32),
                "one-pass 29->32"
            )
            SchemaJsonSupport.assertIntegrity(database, "one-pass 29->32")
            assertLegacyRowsSurvive(database)
            assertVideoTablesAreWritable(database)
        }
    }

    @Test
    fun wholeChainFrom29To35PreservesLegacyRowsAndMatchesRoom35() {
        newDatabase().use { database ->
            SchemaJsonSupport.buildDatabase(database, SchemaJsonSupport.loadDatabase(29))
            database.version = 29
            seedLegacyRows(database)

            database.beginTransaction()
            try {
                VideoDatabaseSchema.MIGRATION_29_30.migrate(database)
                VideoDatabaseSchema.MIGRATION_30_31.migrate(database)
                VideoDatabaseSchema.MIGRATION_31_32.migrate(database)
                AgentParkTokenSchema.MIGRATION_32_33.migrate(database)
                AgentDispatchOutboxSchema.MIGRATION_33_34.migrate(database)
                AgentWorkflowCheckpointSchema.MIGRATION_34_35.migrate(database)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            database.version = 35

            assertEquals("user_version after one-pass 29->35", 35, database.version)
            SchemaJsonSupport.assertDatabaseMatches(
                database,
                SchemaJsonSupport.loadDatabase(35),
                "one-pass 29->35"
            )
            SchemaJsonSupport.assertIntegrity(database, "one-pass 29->35")
            assertLegacyRowsSurvive(database)

            database.execSQL(
                "INSERT INTO agent_park_tokens (runId, epoch, checkpointRevision, wakeId, kind, " +
                    "approvalId, invocationId, questionId, transcriptJson, createdAtMillis) VALUES " +
                    "('run-29', 1, 1, 'wake-29', 'APPROVAL', 'approval-29', 'invocation-29', NULL, '{}', 1)"
            )
            database.execSQL(
                "INSERT INTO agent_dispatch_outbox (idempotencyKey, invocationId, runId, stepId, " +
                    "callOrdinal, toolKey, toolVersion, approvedAtMillis, status, leaseOwner, " +
                    "leaseExpiresAtMillis, dispatchAttempts, settledInvocationStatus, errorCode, " +
                    "updatedAtMillis, revision) VALUES ('obx-29','invocation-29','run-29','step-29',0," +
                    "'artifact.write_text','1',1,'PENDING',NULL,0,0,NULL,NULL,1,0)"
            )
            database.execSQL(
                "INSERT INTO agent_workflow_checkpoints (frameKey, revision, definitionId, " +
                    "definitionVersion, payloadJson, updatedAtMillis) VALUES ('wftask-29',1,'wf',1,'{}',1)"
            )
            assertEquals(
                1,
                SchemaJsonSupport.rowCount(database, "agent_park_tokens")
            )
            assertEquals(
                1,
                SchemaJsonSupport.rowCount(database, "agent_dispatch_outbox")
            )
            assertEquals(
                1,
                SchemaJsonSupport.rowCount(database, "agent_workflow_checkpoints")
            )
        }
    }

    private fun newDatabase(): JdbcSupportSQLiteDatabase {
        Class.forName("org.sqlite.JDBC")
        val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        return JdbcSupportSQLiteDatabase(connection)
    }

    private fun applyMigration(database: SupportSQLiteDatabase, migration: Migration) {
        database.beginTransaction()
        try {
            migration.migrate(database)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun seedLegacyRows(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO chat_sessions (id, title, pinned, manualTitle, updatedAt) " +
                "VALUES ('legacy-session', 'Legacy chat', 0, 0, 123)"
        )
        database.execSQL(
            "INSERT INTO chat_messages (sessionId, position, role, content, createdAt) " +
                "VALUES ('legacy-session', 0, 'user', 'hello 世界', 124)"
        )
        database.execSQL(
            "INSERT INTO agent_workspaces (workspaceId, displayName, storageRef, createdAt, updatedAt) " +
                "VALUES ('workspace-1', 'Legacy workspace', 'workspace-1', 1, 1)"
        )
        database.execSQL(
            "INSERT INTO agent_memory_spaces (spaceId, displayName, createdAt, updatedAt, revision) " +
                "VALUES ('space-1', 'Legacy space', 1, 1, 1)"
        )
        database.execSQL(
            "INSERT INTO agent_memory_items (memoryId, spaceId, title, text, contentHash, sourceKind, " +
                "sourceRef, tokenCount, chunkCount, createdAt, updatedAt, revision, revoked, revokedAt) VALUES " +
                "('memory-1', 'space-1', 'title', 'text', '${"a".repeat(64)}', 'USER_TEXT', NULL, 1, 1, 1, 1, 1, 0, NULL)"
        )
    }

    private fun assertLegacyRowsSurvive(database: SupportSQLiteDatabase) {
        assertEquals(
            "chat title",
            "Legacy chat",
            SchemaJsonSupport.singleString(database, "SELECT title FROM chat_sessions WHERE id = 'legacy-session'")
        )
        assertEquals(
            "chat message content",
            "hello 世界",
            SchemaJsonSupport.singleString(
                database,
                "SELECT content FROM chat_messages WHERE sessionId = 'legacy-session' AND position = 0"
            )
        )
        assertEquals(
            "agent workspace",
            "Legacy workspace",
            SchemaJsonSupport.singleString(
                database,
                "SELECT displayName FROM agent_workspaces WHERE workspaceId = 'workspace-1'"
            )
        )
        assertEquals(
            "agent memory space",
            "Legacy space",
            SchemaJsonSupport.singleString(
                database,
                "SELECT displayName FROM agent_memory_spaces WHERE spaceId = 'space-1'"
            )
        )
        assertEquals(
            "agent memory item",
            "text",
            SchemaJsonSupport.singleString(database, "SELECT text FROM agent_memory_items WHERE memoryId = 'memory-1'")
        )
        assertEquals(
            "agent memory row count",
            1,
            SchemaJsonSupport.rowCount(database, "agent_memory_items")
        )
    }

    private fun assertVideoTablesAreWritable(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO video_workflow_revisions (revisionId, workflowId, source, schemaVersion, " +
                "canonicalGraphJson, rawSourceRef, canonicalSha256, createdAt, updatedAt) VALUES " +
                "('revision-1', 'workflow-1', 'local', 1, '{}', NULL, 'sha', 1, 1)"
        )
        database.execSQL(
            "INSERT INTO video_runs (runId, attemptId, requestId, stateVersion, workflowId, revisionId, " +
                "backend, modelFingerprint, profileFingerprint, snapshotJson, state, resourceState, stage, " +
                "step, stepTotal, errorCode, errorDetail, outputMediaId, createdAt, updatedAt, terminalAt) VALUES " +
                "('run-1', 'attempt-1', 'request-1', 1, 'workflow-1', 'revision-1', 'LOCAL_NATIVE', 'mf', 'pf', " +
                "'{}', 'QUEUED', 'NOT_ACQUIRED', 'ENQUEUE', NULL, NULL, NULL, NULL, NULL, 1, 1, NULL)"
        )
        database.execSQL(
            "INSERT INTO video_run_events (runId, attemptId, state, stage, message, atMs) " +
                "VALUES ('run-1', 'attempt-1', 'QUEUED', 'ENQUEUE', NULL, 1)"
        )
        database.execSQL(
            "INSERT INTO video_media_assets (mediaId, runId, mimeType, relativePath, sizeBytes, sha256, " +
                "width, height, durationUs, fps, frameCount, codec, hasAudio, createdAt, deletedAt) VALUES " +
                "('media-1', 'run-1', 'video/mp4', 'video/media-1.mp4', 10, 'sha', 16, 16, 1000, 24, 24, 'h264', 0, 1, NULL)"
        )
        // The v32 columns must be nullable and writable for a legacy row.
        assertNull(
            SchemaJsonSupport.singleString(
                database,
                "SELECT remoteEndpointId FROM video_runs WHERE runId = 'run-1'"
            )
        )
        assertNull(
            SchemaJsonSupport.singleString(
                database,
                "SELECT remotePromptId FROM video_runs WHERE runId = 'run-1'"
            )
        )
        database.execSQL(
            "UPDATE video_runs SET remoteEndpointId = 'endpoint-1', remotePromptId = 'prompt-1' WHERE runId = 'run-1'"
        )
        assertEquals(
            "remote endpoint",
            "endpoint-1",
            SchemaJsonSupport.singleString(
                database,
                "SELECT remoteEndpointId FROM video_runs WHERE runId = 'run-1'"
            )
        )
        assertEquals(
            "remote prompt",
            "prompt-1",
            SchemaJsonSupport.singleString(
                database,
                "SELECT remotePromptId FROM video_runs WHERE runId = 'run-1'"
            )
        )
        assertEquals(1, SchemaJsonSupport.rowCount(database, "video_workflow_revisions"))
        assertEquals(1, SchemaJsonSupport.rowCount(database, "video_run_events"))
        assertEquals(1, SchemaJsonSupport.rowCount(database, "video_media_assets"))
        assertTrue("video run row must exist", SchemaJsonSupport.rowCount(database, "video_runs") == 1)
    }
}
