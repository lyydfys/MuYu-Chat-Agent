package com.muyuchat.mca

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.muyuchat.mca.agent.data.AgentApprovalDao
import com.muyuchat.mca.agent.data.AgentApprovalEntity
import com.muyuchat.mca.agent.data.AgentArtifactCommitDao
import com.muyuchat.mca.agent.data.AgentArtifactCommitEntity
import com.muyuchat.mca.agent.data.AgentArtifactDao
import com.muyuchat.mca.agent.data.AgentArtifactEntity
import com.muyuchat.mca.agent.data.AgentCommandDao
import com.muyuchat.mca.agent.data.AgentCommandEntity
import com.muyuchat.mca.agent.data.AgentCommandResultDao
import com.muyuchat.mca.agent.data.AgentCommandResultEntity
import com.muyuchat.mca.agent.data.AgentDatabaseMigration
import com.muyuchat.mca.agent.data.AgentEventDao
import com.muyuchat.mca.agent.data.AgentEventEntity
import com.muyuchat.mca.agent.data.AgentExtensionDao
import com.muyuchat.mca.agent.data.AgentExtensionEntity
import com.muyuchat.mca.agent.data.AgentGrantDao
import com.muyuchat.mca.agent.data.AgentGrantEntity
import com.muyuchat.mca.agent.data.AgentInvocationDao
import com.muyuchat.mca.agent.data.AgentInvocationEntity
import com.muyuchat.mca.agent.data.AgentModelAttemptDao
import com.muyuchat.mca.agent.data.AgentModelAttemptEntity
import com.muyuchat.mca.agent.data.AgentDispatchOutboxDao
import com.muyuchat.mca.agent.data.AgentDispatchOutboxEntity
import com.muyuchat.mca.agent.data.AgentDispatchOutboxSchema
import com.muyuchat.mca.agent.data.AgentWorkflowCheckpointDao
import com.muyuchat.mca.agent.data.AgentWorkflowCheckpointEntity
import com.muyuchat.mca.agent.data.AgentWorkflowCheckpointSchema
import com.muyuchat.mca.agent.data.AgentParkTokenDao
import com.muyuchat.mca.agent.data.AgentParkTokenEntity
import com.muyuchat.mca.agent.data.AgentParkTokenSchema
import com.muyuchat.mca.agent.data.AgentQuestionDao
import com.muyuchat.mca.agent.data.AgentQuestionEntity
import com.muyuchat.mca.agent.data.AgentRunDao
import com.muyuchat.mca.agent.data.AgentRunEntity
import com.muyuchat.mca.agent.data.AgentStepDao
import com.muyuchat.mca.agent.data.AgentStepEntity
import com.muyuchat.mca.agent.data.AgentTaskDao
import com.muyuchat.mca.agent.data.AgentTaskEntity
import com.muyuchat.mca.agent.data.AgentWorkspaceDao
import com.muyuchat.mca.agent.data.AgentWorkspaceEntity
import com.muyuchat.mca.agent.memory.data.AgentMemoryChunkEntity
import com.muyuchat.mca.agent.memory.data.AgentMemoryDao
import com.muyuchat.mca.agent.memory.data.AgentMemoryItemEntity
import com.muyuchat.mca.agent.memory.data.AgentMemorySchema
import com.muyuchat.mca.agent.memory.data.AgentMemorySpaceEntity
import com.muyuchat.mca.agent.memory.data.AgentMemoryTaskSelectionEntity
import com.muyuchat.mca.video.data.VideoDatabaseSchema
import com.muyuchat.mca.video.data.VideoMediaAssetEntity
import com.muyuchat.mca.video.data.VideoRunEntity
import com.muyuchat.mca.video.data.VideoRunEventEntity
import com.muyuchat.mca.video.data.VideoWorkflowDao
import com.muyuchat.mca.video.data.VideoWorkflowRevisionEntity

/**
 * The Room database definition and its migration chain for chat history.
 *
 * Moved out of `ChatSessionStore.kt` verbatim: the @Database declaration (entity list, version,
 * exportSchema), the abstract DAO accessor, the builder and every Migration. **No schema, version or
 * migration was edited**: the version number, the entity list, the SQL strings and the exported
 * schema flag are byte-identical to the original.
 */
@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ImageAssetEntity::class,
        FileAssetEntity::class,
        MemoryEntity::class,
        AssistantMemoryTurnEntity::class,
        AssistantEntity::class,
        KnowledgeBaseEntity::class,
        KnowledgeDocumentEntity::class,
        KnowledgeChunkEntity::class,
        ChatKnowledgeBaseBindingEntity::class,
        // Agent V1 tables (architecture 10.1). They share mca.db but have an independent owner,
        // DAO set and migration; no Agent row is read by the chat/assistant/knowledge queries.
        AgentWorkspaceEntity::class,
        AgentTaskEntity::class,
        AgentRunEntity::class,
        AgentQuestionEntity::class,
        AgentModelAttemptEntity::class,
        AgentStepEntity::class,
        AgentInvocationEntity::class,
        AgentApprovalEntity::class,
        AgentEventEntity::class,
        AgentCommandEntity::class,
        AgentCommandResultEntity::class,
        AgentExtensionEntity::class,
        AgentGrantEntity::class,
        AgentArtifactEntity::class,
        AgentArtifactCommitEntity::class,
        // Durable park/wake token for a run parked in WAITING_APPROVAL / WAITING_USER. Additive:
        // one row per run, written by the Agent data layer; no chat query reads it.
        AgentParkTokenEntity::class,
        // Durable approved-dispatch outbox row (N03/N19 follow-up). Additive: one row per approved
        // invocation, written inside the owner's approval transaction.
        AgentDispatchOutboxEntity::class,
        // Durable workflow frames (N19 follow-up). Additive: one row per task-root frame key.
        AgentWorkflowCheckpointEntity::class,
        // Agent V1.1 memory tables (plan §7.1). Same additive ownership rule as the Agent tables:
        // one mca.db, one migration chain, no chat/knowledge query reads these rows.
        AgentMemorySpaceEntity::class,
        AgentMemoryItemEntity::class,
        AgentMemoryChunkEntity::class,
        AgentMemoryTaskSelectionEntity::class,
        // Native video workflow tables (proposal mca-native-video-workflow-v1 §8.1). Additive
        // only: revisions/runs/events with their own DAO and owner; image_assets is untouched.
        VideoWorkflowRevisionEntity::class,
        VideoRunEntity::class,
        VideoRunEventEntity::class,
        VideoMediaAssetEntity::class
    ],
    version = 35,
    // Exported so the migration chain can be checked against the exact structure Room
    // validates at runtime; see the room.schemaLocation argument in app/build.gradle.kts.
    exportSchema = true
)
abstract class McaRoomDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao

    // Agent V1 DAOs. The database is only the schema/transaction host; Agent business rules stay
    // in AgentRunOwner/AgentJournal implementations under app/.../agent.
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun agentWorkspaceDao(): AgentWorkspaceDao
    abstract fun agentRunDao(): AgentRunDao
    abstract fun agentStepDao(): AgentStepDao
    abstract fun agentModelAttemptDao(): AgentModelAttemptDao
    abstract fun agentInvocationDao(): AgentInvocationDao
    abstract fun agentApprovalDao(): AgentApprovalDao
    abstract fun agentEventDao(): AgentEventDao
    abstract fun agentArtifactDao(): AgentArtifactDao
    abstract fun agentArtifactCommitDao(): AgentArtifactCommitDao
    abstract fun agentQuestionDao(): AgentQuestionDao
    abstract fun agentCommandDao(): AgentCommandDao
    abstract fun agentCommandResultDao(): AgentCommandResultDao
    abstract fun agentExtensionDao(): AgentExtensionDao
    abstract fun agentGrantDao(): AgentGrantDao
    abstract fun agentParkTokenDao(): AgentParkTokenDao
    abstract fun agentDispatchOutboxDao(): AgentDispatchOutboxDao
    abstract fun agentWorkflowCheckpointDao(): AgentWorkflowCheckpointDao

    // Agent V1.1 memory DAO. One accessor: the memory domain owns exactly one bounded DAO, and the
    // store/composition code never opens a second database handle.
    abstract fun agentMemoryDao(): AgentMemoryDao
    abstract fun videoWorkflowDao(): VideoWorkflowDao

    companion object {
        @Volatile
        private var instance: McaRoomDatabase? = null

        fun get(context: Context): McaRoomDatabase =
            instance ?: synchronized(this) {
                val appContext = context.applicationContext
                repairLegacySchemaIfNeeded(appContext)
                repairOversizedRowsIfNeeded(appContext)
                instance ?: Room.databaseBuilder(
                    appContext,
                    McaRoomDatabase::class.java,
                    "mca.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_6, MIGRATION_3_6, MIGRATION_4_6, MIGRATION_5_6)
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .addMigrations(
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                        // 27 -> 28 creates the Agent tables/indices; SQL lives in the Agent data
                        // package so this file stays the single registration point only.
                        MIGRATION_27_28,
                        // 28 -> 29 creates the memory tables/indices; SQL lives in the memory data
                        // package. Additive only; no earlier table is rewritten.
                        MIGRATION_28_29,
                        // 29 -> 30 creates the video workflow/run/event tables; SQL lives in the
                        // video data package. Additive only; no earlier table is rewritten.
                        VideoDatabaseSchema.MIGRATION_29_30,
                        // 30 -> 31 adds the published video media table (V1-04). Additive only.
                        VideoDatabaseSchema.MIGRATION_30_31,
                        // 31 -> 32 adds non-secret remote prompt identity (V3-01). Additive only.
                        VideoDatabaseSchema.MIGRATION_31_32,
                        // 32 -> 33 adds the durable park/wake token (one row per parked run).
                        // SQL lives in the Agent data package; additive only.
                        AgentParkTokenSchema.MIGRATION_32_33,
                        // 33 -> 34 adds the durable approved-dispatch outbox row. Additive only.
                        AgentDispatchOutboxSchema.MIGRATION_33_34,
                        // 34 -> 35 moves workflow frames from the file CAS store into Room.
                        AgentWorkflowCheckpointSchema.MIGRATION_34_35
                    )
                    .build()
                    .also { instance = it }
            }

        /**
         * Moves oversized `chat_sessions` JSON out of SQLite *before* Room opens a reader.
         *
         * One oversized row (a character card with an embedded lorebook, for example) exceeds the
         * ~2 MB `CursorWindow`, so `SELECT * FROM chat_sessions` fails for every caller — including
         * the chat send path. The repair must therefore run on the raw database, where bounded
         * `substr` slices can still be read. Failures are logged and never block start-up.
         */
        private fun repairOversizedRowsIfNeeded(context: Context) {
            // Configured for every open, not just repair: the entity mappers use the same store.
            LargeRowOffload.configure(java.io.File(context.filesDir, "row_text"))
            val databaseFile = context.getDatabasePath("mca.db")
            if (!databaseFile.exists()) return
            runCatching {
                SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    val outcome = LargeRowRepair.repair(db)
                    if (outcome.changed || outcome.failedRows > 0 || outcome.failureMessage != null) {
                        Log.w(
                            "MCA-ChatHistory",
                            "session_json_offload repaired=${outcome.repairedRows} " +
                                "failed=${outcome.failedRows} reason=${outcome.failureMessage}"
                        )
                    }
                }
            }.onFailure { error ->
                Log.e("MCA-ChatHistory", "session_json_offload_failed", error)
            }
        }

        private fun repairLegacySchemaIfNeeded(context: Context) {
            val databaseFile = context.getDatabasePath("mca.db")
            if (!databaseFile.exists()) return
            runCatching {
                SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    if (db.version >= 14) return@use
                    db.beginTransaction()
                    try {
                        normalizeLegacyChatMessagesTable(db)
                        normalizeLegacyFileAssetsTable(db)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN reasoningContent TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN reasoningDurationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_6 = object : Migration(2, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addProjectIdColumnIfMissing(db)
                normalizeImageAssetsTable(db)
            }
        }

        private val MIGRATION_3_6 = object : Migration(3, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addProjectIdColumnIfMissing(db)
                normalizeImageAssetsTable(db)
            }
        }

        private val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addProjectIdColumnIfMissing(db)
                normalizeImageAssetsTable(db)
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addProjectIdColumnIfMissing(db)
                normalizeImageAssetsTable(db)
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addMessageAttachmentsColumnIfMissing(db)
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addChatSessionBindingColumnsIfMissing(db)
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createMemoriesTableIfMissing(db)
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createAssistantsTableIfMissing(db)
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                normalizeFileAssetsTable(db)
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addAssistantTagColumnIfMissing(db)
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addMessageSourceReferencesColumnIfMissing(db)
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addMessageWebSearchTraceColumnIfMissing(db)
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addImageGenerationMetadataColumnIfMissing(db)
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addImageFavoriteColumnIfMissing(db)
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createKnowledgeBaseTablesIfMissing(db)
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addAssistantCharacterCardJsonColumnIfMissing(db)
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addChatSessionAssistantSnapshotColumnIfMissing(db)
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                rebuildKnowledgeTablesWithForeignKeys(db)
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addChatAppearanceColumnsIfMissing(db)
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val exists = db.query("PRAGMA table_info(`chat_messages`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    var found = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == "generationMetricsJson") found = true
                    }
                    found
                }
                if (!exists) db.execSQL("ALTER TABLE chat_messages ADD COLUMN generationMetricsJson TEXT")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val exists = db.query("PRAGMA table_info(`chat_messages`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    var found = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == "generatedImageJson") found = true
                    }
                    found
                }
                if (!exists) db.execSQL("ALTER TABLE chat_messages ADD COLUMN generatedImageJson TEXT")
            }
        }

        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN contextSummariesJson TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN messageId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE chat_messages SET messageId = sessionId || ':' || position || ':' || createdAt WHERE messageId = ''")
            }
        }

        internal val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN contextAssemblyTraceJson TEXT")
            }
        }

        internal val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE assistants ADD COLUMN memorySummaryInterval INTEGER NOT NULL DEFAULT 12"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS assistant_memory_turns (
                        id TEXT NOT NULL PRIMARY KEY,
                        assistantId TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        userText TEXT NOT NULL,
                        assistantText TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isSummarized INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_assistant_memory_turns_assistantId_isSummarized_createdAt " +
                        "ON assistant_memory_turns(assistantId, isSummarized, createdAt)"
                )
            }
        }

        internal val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE chat_sessions ADD COLUMN mixedAssistantHistory INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 27 -> 28 wrapper. The SQL is owned by [AgentDatabaseMigration] under app/.../agent/data;
         * this declaration keeps the migration registration and the schema-contract tests, which
         * read this file as the database owner, in one place.
         */
        internal val MIGRATION_27_28: Migration = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AgentDatabaseMigration.MIGRATION_27_28.migrate(db)
            }
        }

        /**
         * 29 -> 30 compatibility wrapper. This declaration is intentionally **not** registered on the
         * builder: the production chain registers [VideoDatabaseSchema.MIGRATION_29_30] directly
         * (see the builder above), and this file remains the schema-contract registration point only.
         *
         * The wrapper is kept as a typed edge for reflection-based JVM migration tests that execute
         * the exact production object without importing the video package. Do not add it to the
         * builder twice and do not edit its SQL here; SQL authority is [VideoDatabaseSchema].
         */
        internal val MIGRATION_29_30: Migration = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQL lives with the video tables; this file is the registration point only.
                com.muyuchat.mca.video.data.VideoDatabaseSchema.MIGRATION_29_30.migrate(db)
            }
        }

        /**
         * 28 -> 29 wrapper. The SQL is owned by [AgentMemorySchema] under app/.../agent/memory/data;
         * this declaration is the production registration point and the schema-contract walk.
         */
        internal val MIGRATION_28_29: Migration = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AgentMemorySchema.MIGRATION_28_29.migrate(db)
            }
        }

        private fun addProjectIdColumnIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN projectId TEXT")
            }
        }
    }
}
