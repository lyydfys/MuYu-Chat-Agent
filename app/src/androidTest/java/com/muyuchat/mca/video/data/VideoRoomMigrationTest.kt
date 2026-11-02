package com.muyuchat.mca.video.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.mca.McaRoomDatabase
import com.muyuchat.mca.agent.data.AgentDispatchOutboxSchema
import com.muyuchat.mca.agent.data.AgentParkTokenSchema
import com.muyuchat.mca.agent.data.AgentWorkflowCheckpointSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room validation for the video schema chain (29 → 30 → 31 → 32) against the exported schema
 * JSONs. The legacy run row inserted at v30 must survive; the v31 media table and the v32 remote
 * prompt columns are additive. Device evidence only (MigrationTestHelper).
 */
@RunWith(AndroidJUnit4::class)
class VideoRoomMigrationTest {

    private lateinit var helper: MigrationTestHelper

    @Before
    fun setUp() {
        helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            McaRoomDatabase::class.java,
        )
    }

    @Test
    fun migration30To32PreservesRunAndAddsMediaAndRemoteColumns() {
        helper.createDatabase(TEST_DATABASE, 30).use { db ->
            db.execSQL(
                "INSERT INTO video_runs (runId, attemptId, requestId, stateVersion, workflowId, revisionId, " +
                    "backend, modelFingerprint, profileFingerprint, snapshotJson, state, resourceState, stage, " +
                    "step, stepTotal, errorCode, errorDetail, outputMediaId, createdAt, updatedAt, terminalAt) " +
                    "VALUES ('v-legacy','v-a1','v-r1',3,'wf','rev','LOCAL_NATIVE','mf','pf','{}'," +
                    "'COMPLETED','RELEASED','FINALIZE',NULL,NULL,NULL,NULL,NULL,100,200,300)",
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            32,
            true,
            VideoDatabaseSchema.MIGRATION_30_31,
            VideoDatabaseSchema.MIGRATION_31_32,
        )
        try {
            assertTrue(hasTable(migrated, "video_media_assets"))
            assertEquals(
                3,
                singleInt(migrated, "SELECT stateVersion FROM video_runs WHERE runId = 'v-legacy'"),
            )
            assertEquals(
                "COMPLETED",
                singleString(migrated, "SELECT state FROM video_runs WHERE runId = 'v-legacy'"),
            )
            // v32 columns exist and are null for the legacy row.
            assertEquals(
                1,
                singleInt(
                    migrated,
                    "SELECT COUNT(*) FROM video_runs WHERE runId = 'v-legacy' " +
                        "AND remoteEndpointId IS NULL AND remotePromptId IS NULL",
                ),
            )
            assertEquals(
                0,
                singleInt(migrated, "SELECT COUNT(*) FROM video_media_assets"),
            )
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migration29To32CreatesTheWholeVideoSchema() {
        helper.createDatabase(TEST_DATABASE, 29).use { db ->
            db.execSQL(
                "INSERT INTO chat_sessions (id, title, pinned, manualTitle, updatedAt) " +
                    "VALUES ('legacy-session','Legacy chat',0,0,123)",
            )
        }
        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            32,
            true,
            VideoDatabaseSchema.MIGRATION_29_30,
            VideoDatabaseSchema.MIGRATION_30_31,
            VideoDatabaseSchema.MIGRATION_31_32,
        )
        try {
            assertTrue(hasTable(migrated, "video_workflow_revisions"))
            assertTrue(hasTable(migrated, "video_runs"))
            assertTrue(hasTable(migrated, "video_run_events"))
            assertTrue(hasTable(migrated, "video_media_assets"))
            assertEquals(
                "Legacy chat",
                singleString(migrated, "SELECT title FROM chat_sessions WHERE id = 'legacy-session'"),
            )
        } finally {
            migrated.close()
        }
    }

    /**
     * The full production chain v29 -> v35 on a device: video tables (29-32) plus the Agent tables
     * (32-33 park tokens, 33-34 dispatch outbox, 34-35 workflow checkpoints). Legacy chat, image and
     * file rows must survive every additive step; the exported 35.json structure is validated by
     * Room's MigrationTestHelper, so a mismatch between migration SQL and the @Entity DDL fails.
     */
    @Test
    fun migration29To35KeepsChatImageFileRowsAndAddsAgentTables() {
        helper.createDatabase(TEST_DATABASE, 29).use { db ->
            db.execSQL(
                "INSERT INTO chat_sessions (id, title, pinned, manualTitle, updatedAt, mixedAssistantHistory) " +
                    "VALUES ('legacy-session','Legacy chat',0,0,123,0)",
            )
            db.execSQL(
                "INSERT INTO image_assets (id, name, uriString, source, prompt, createdAt, sizeBytes, width, height, " +
                    "generationMetadataJson, favorite) " +
                    "VALUES ('legacy-image','pic.png','content://legacy/pic','GENERATED','a cat',111,222,64,64,'{}',0)",
            )
            db.execSQL(
                "INSERT INTO file_assets (id, name, mimeType, text, preview, truncated, source, createdAt, sizeBytes) " +
                    "VALUES ('legacy-file','doc.txt','text/plain','hello','',0,'IMPORT',333,5)",
            )
        }
        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            35,
            true,
            VideoDatabaseSchema.MIGRATION_29_30,
            VideoDatabaseSchema.MIGRATION_30_31,
            VideoDatabaseSchema.MIGRATION_31_32,
            AgentParkTokenSchema.MIGRATION_32_33,
            AgentDispatchOutboxSchema.MIGRATION_33_34,
            AgentWorkflowCheckpointSchema.MIGRATION_34_35,
        )
        try {
            assertTrue(hasTable(migrated, "video_workflow_revisions"))
            assertTrue(hasTable(migrated, "video_runs"))
            assertTrue(hasTable(migrated, "video_run_events"))
            assertTrue(hasTable(migrated, "video_media_assets"))
            assertTrue(hasTable(migrated, "agent_park_tokens"))
            assertTrue(hasTable(migrated, "agent_dispatch_outbox"))
            assertTrue(hasTable(migrated, "agent_workflow_checkpoints"))
            assertEquals(
                "Legacy chat",
                singleString(migrated, "SELECT title FROM chat_sessions WHERE id = 'legacy-session'"),
            )
            assertEquals(
                "pic.png",
                singleString(migrated, "SELECT name FROM image_assets WHERE id = 'legacy-image'"),
            )
            assertEquals(
                "doc.txt",
                singleString(migrated, "SELECT name FROM file_assets WHERE id = 'legacy-file'"),
            )
            assertEquals(0, singleInt(migrated, "SELECT COUNT(*) FROM video_runs"))
        } finally {
            migrated.close()
        }
    }

    private fun hasTable(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun singleString(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun singleInt(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val TEST_DATABASE = "video-migration-test"
    }
}
