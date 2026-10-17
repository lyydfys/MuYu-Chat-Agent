package com.muyuchat.mca

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        ChatKnowledgeBaseBindingEntity::class
    ],
    version = 27,
    // Exported so the migration chain can be checked against the exact structure Room
    // validates at runtime; see the room.schemaLocation argument in app/build.gradle.kts.
    exportSchema = true
)
abstract class McaRoomDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao

    companion object {
        @Volatile
        private var instance: McaRoomDatabase? = null

        fun get(context: Context): McaRoomDatabase =
            instance ?: synchronized(this) {
                val appContext = context.applicationContext
                repairLegacySchemaIfNeeded(appContext)
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
                        MIGRATION_26_27
                    )
                    .build()
                    .also { instance = it }
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

        private fun addProjectIdColumnIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN projectId TEXT")
            }
        }
    }
}
