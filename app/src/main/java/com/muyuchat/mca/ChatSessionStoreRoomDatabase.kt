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

        private fun addMessageAttachmentsColumnIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN imageAttachmentsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private fun addMessageSourceReferencesColumnIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN sourceReferencesJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        private fun addMessageWebSearchTraceColumnIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN webSearchTraceJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        private fun createKnowledgeBaseTablesIfMissing(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_bases (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    embeddingModelFingerprint TEXT,
                    indexState TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    knowledgeBaseId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    source TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    contentLength INTEGER NOT NULL,
                    chunkCount INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS knowledge_chunks (
                    id TEXT NOT NULL PRIMARY KEY,
                    knowledgeBaseId TEXT NOT NULL,
                    documentId TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    estimatedTokens INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_knowledge_base_bindings (
                    chatSessionId TEXT NOT NULL,
                    knowledgeBaseId TEXT NOT NULL,
                    PRIMARY KEY(chatSessionId, knowledgeBaseId)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_bases_updatedAt ON knowledge_bases(updatedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_knowledgeBaseId ON knowledge_documents(knowledgeBaseId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_documents_contentHash ON knowledge_documents(contentHash)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunks_knowledgeBaseId ON knowledge_chunks(knowledgeBaseId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunks_documentId ON knowledge_chunks(documentId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_knowledge_base_bindings_knowledgeBaseId ON chat_knowledge_base_bindings(knowledgeBaseId)")
        }

        /**
         * SQLite cannot add a foreign key to an existing table. Rebuild only
         * the dependent knowledge tables and copy rows whose parents still
         * exist, preserving all valid v19 data before replacing the old tables.
         */
        private fun rebuildKnowledgeTablesWithForeignKeys(db: SupportSQLiteDatabase) {
            rebuildKnowledgeDocumentsWithForeignKeys(db)
            rebuildKnowledgeChunksWithForeignKeys(db)
            rebuildChatKnowledgeBaseBindingsWithForeignKeys(db)
        }

        private fun rebuildKnowledgeDocumentsWithForeignKeys(db: SupportSQLiteDatabase) {
            if (!tableExists(db, "knowledge_documents")) {
                createKnowledgeDocumentsTableWithForeignKeys(db, "knowledge_documents")
                createKnowledgeDocumentIndices(db)
                return
            }

            createKnowledgeDocumentsTableWithForeignKeys(db, "knowledge_documents_new")
            db.execSQL(
                """
                INSERT INTO knowledge_documents_new (
                    id, knowledgeBaseId, title, source, contentHash, contentLength, chunkCount, createdAt, updatedAt
                )
                SELECT
                    documents.id,
                    documents.knowledgeBaseId,
                    documents.title,
                    documents.source,
                    documents.contentHash,
                    documents.contentLength,
                    documents.chunkCount,
                    documents.createdAt,
                    documents.updatedAt
                FROM knowledge_documents AS documents
                INNER JOIN knowledge_bases AS bases ON bases.id = documents.knowledgeBaseId
                """.trimIndent()
            )
            db.execSQL("DROP TABLE knowledge_documents")
            db.execSQL("ALTER TABLE knowledge_documents_new RENAME TO knowledge_documents")
            createKnowledgeDocumentIndices(db)
        }

        private fun rebuildKnowledgeChunksWithForeignKeys(db: SupportSQLiteDatabase) {
            if (!tableExists(db, "knowledge_chunks")) {
                createKnowledgeChunksTableWithForeignKeys(db, "knowledge_chunks")
                createKnowledgeChunkIndices(db)
                return
            }

            createKnowledgeChunksTableWithForeignKeys(db, "knowledge_chunks_new")
            db.execSQL(
                """
                INSERT INTO knowledge_chunks_new (
                    id, knowledgeBaseId, documentId, position, content, contentHash, estimatedTokens
                )
                SELECT
                    chunks.id,
                    chunks.knowledgeBaseId,
                    chunks.documentId,
                    chunks.position,
                    chunks.content,
                    chunks.contentHash,
                    chunks.estimatedTokens
                FROM knowledge_chunks AS chunks
                INNER JOIN knowledge_bases AS bases ON bases.id = chunks.knowledgeBaseId
                INNER JOIN knowledge_documents AS documents
                    ON documents.id = chunks.documentId
                    AND documents.knowledgeBaseId = chunks.knowledgeBaseId
                """.trimIndent()
            )
            db.execSQL("DROP TABLE knowledge_chunks")
            db.execSQL("ALTER TABLE knowledge_chunks_new RENAME TO knowledge_chunks")
            createKnowledgeChunkIndices(db)
        }

        private fun rebuildChatKnowledgeBaseBindingsWithForeignKeys(db: SupportSQLiteDatabase) {
            if (!tableExists(db, "chat_knowledge_base_bindings")) {
                createChatKnowledgeBaseBindingsTableWithForeignKeys(db, "chat_knowledge_base_bindings")
                createChatKnowledgeBaseBindingIndices(db)
                return
            }

            createChatKnowledgeBaseBindingsTableWithForeignKeys(
                db,
                "chat_knowledge_base_bindings_new"
            )
            db.execSQL(
                """
                INSERT INTO chat_knowledge_base_bindings_new (chatSessionId, knowledgeBaseId)
                SELECT bindings.chatSessionId, bindings.knowledgeBaseId
                FROM chat_knowledge_base_bindings AS bindings
                INNER JOIN chat_sessions AS sessions ON sessions.id = bindings.chatSessionId
                INNER JOIN knowledge_bases AS bases ON bases.id = bindings.knowledgeBaseId
                """.trimIndent()
            )
            db.execSQL("DROP TABLE chat_knowledge_base_bindings")
            db.execSQL(
                "ALTER TABLE chat_knowledge_base_bindings_new RENAME TO chat_knowledge_base_bindings"
            )
            createChatKnowledgeBaseBindingIndices(db)
        }

        private fun createKnowledgeDocumentsTableWithForeignKeys(
            db: SupportSQLiteDatabase,
            tableName: String
        ) {
            db.execSQL(
                """
                CREATE TABLE $tableName (
                    id TEXT NOT NULL PRIMARY KEY,
                    knowledgeBaseId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    source TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    contentLength INTEGER NOT NULL,
                    chunkCount INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    FOREIGN KEY(knowledgeBaseId) REFERENCES knowledge_bases(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }

        private fun createKnowledgeChunksTableWithForeignKeys(
            db: SupportSQLiteDatabase,
            tableName: String
        ) {
            db.execSQL(
                """
                CREATE TABLE $tableName (
                    id TEXT NOT NULL PRIMARY KEY,
                    knowledgeBaseId TEXT NOT NULL,
                    documentId TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    contentHash TEXT NOT NULL,
                    estimatedTokens INTEGER NOT NULL,
                    FOREIGN KEY(knowledgeBaseId) REFERENCES knowledge_bases(id) ON DELETE CASCADE,
                    FOREIGN KEY(documentId) REFERENCES knowledge_documents(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }

        private fun createChatKnowledgeBaseBindingsTableWithForeignKeys(
            db: SupportSQLiteDatabase,
            tableName: String
        ) {
            db.execSQL(
                """
                CREATE TABLE $tableName (
                    chatSessionId TEXT NOT NULL,
                    knowledgeBaseId TEXT NOT NULL,
                    PRIMARY KEY(chatSessionId, knowledgeBaseId),
                    FOREIGN KEY(chatSessionId) REFERENCES chat_sessions(id) ON DELETE CASCADE,
                    FOREIGN KEY(knowledgeBaseId) REFERENCES knowledge_bases(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }

        private fun createKnowledgeDocumentIndices(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_knowledge_documents_knowledgeBaseId " +
                    "ON knowledge_documents(knowledgeBaseId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_knowledge_documents_contentHash " +
                    "ON knowledge_documents(contentHash)"
            )
        }

        private fun createKnowledgeChunkIndices(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_knowledge_chunks_knowledgeBaseId " +
                    "ON knowledge_chunks(knowledgeBaseId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_knowledge_chunks_documentId " +
                    "ON knowledge_chunks(documentId)"
            )
        }

        private fun createChatKnowledgeBaseBindingIndices(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chat_knowledge_base_bindings_knowledgeBaseId " +
                    "ON chat_knowledge_base_bindings(knowledgeBaseId)"
            )
        }

        private fun addImageGenerationMetadataColumnIfMissing(db: SupportSQLiteDatabase) {
            if ("generationMetadataJson" !in tableColumns(db, "image_assets")) {
                db.execSQL(
                    "ALTER TABLE image_assets ADD COLUMN generationMetadataJson TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private fun addImageFavoriteColumnIfMissing(db: SupportSQLiteDatabase) {
            if ("favorite" !in tableColumns(db, "image_assets")) {
                db.execSQL(
                    "ALTER TABLE image_assets ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0"
                )
            }
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_image_assets_favorite ON image_assets (favorite)"
            )
        }

        private fun addChatSessionBindingColumnsIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN assistantId TEXT")
            }
            runCatching {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN modelMode TEXT")
            }
            runCatching {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN modelId TEXT")
            }
        }

        private fun addChatSessionAssistantSnapshotColumnIfMissing(db: SupportSQLiteDatabase) {
            if ("assistantSnapshotJson" !in tableColumns(db, "chat_sessions")) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN assistantSnapshotJson TEXT")
            }
        }

        private fun addChatAppearanceColumnsIfMissing(db: SupportSQLiteDatabase) {
            if ("appearanceJson" !in tableColumns(db, "chat_sessions")) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN appearanceJson TEXT")
            }
            if ("appearanceJson" !in tableColumns(db, "assistants")) {
                db.execSQL("ALTER TABLE assistants ADD COLUMN appearanceJson TEXT")
            }
        }

        private fun createMemoriesTableIfMissing(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memories (
                    id TEXT NOT NULL PRIMARY KEY,
                    assistantId TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    content TEXT NOT NULL,
                    source TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_assistantId ON memories(assistantId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_scope ON memories(scope)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_createdAt ON memories(createdAt)")
        }

        private fun createAssistantsTableIfMissing(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS assistants (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    avatar TEXT NOT NULL,
                    tag TEXT NOT NULL DEFAULT '',
                    systemPrompt TEXT NOT NULL,
                    defaultModelMode TEXT NOT NULL,
                    defaultModelId TEXT,
                    paramsJson TEXT NOT NULL,
                    memoryEnabled INTEGER NOT NULL,
                    webSearchEnabled INTEGER NOT NULL,
                    fileContextEnabled INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_assistants_updatedAt ON assistants(updatedAt)")
            addAssistantTagColumnIfMissing(db)
        }

        private fun addAssistantTagColumnIfMissing(db: SupportSQLiteDatabase) {
            runCatching {
                db.execSQL("ALTER TABLE assistants ADD COLUMN tag TEXT NOT NULL DEFAULT ''")
            }
        }

        private fun addAssistantCharacterCardJsonColumnIfMissing(db: SupportSQLiteDatabase) {
            if ("characterCardJson" !in tableColumns(db, "assistants")) {
                db.execSQL("ALTER TABLE assistants ADD COLUMN characterCardJson TEXT")
            }
        }

        private fun normalizeFileAssetsTable(db: SupportSQLiteDatabase) {
            val existed = tableExists(db, "file_assets")
            val columns = if (existed) tableColumns(db, "file_assets") else emptySet()
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS file_assets_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    text TEXT NOT NULL,
                    preview TEXT NOT NULL,
                    truncated INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    sizeBytes INTEGER NOT NULL,
                    chatSessionId TEXT,
                    projectId TEXT
                )
                """.trimIndent()
            )
            if (existed) {
                val textExpression = firstColumnOrDefault(columns, listOf("text", "contentPreview", "preview"), "''")
                val previewExpression = firstColumnOrDefault(columns, listOf("preview", "contentPreview", "text"), "''")
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO file_assets_new (
                        id, name, mimeType, text, preview, truncated, source, createdAt, sizeBytes, chatSessionId, projectId
                    )
                    SELECT
                        ${columnOrDefault(columns, "id", "hex(randomblob(16))")},
                        ${columnOrDefault(columns, "name", "'文件'")},
                        ${columnOrDefault(columns, "mimeType", "'text/plain'")},
                        $textExpression,
                        $previewExpression,
                        ${columnOrDefault(columns, "truncated", "0")},
                        ${columnOrDefault(columns, "source", "'uploaded'")},
                        ${columnOrDefault(columns, "createdAt", "(strftime('%s','now') * 1000)")},
                        ${columnOrDefault(columns, "sizeBytes", "0")},
                        ${nullableColumn(columns, "chatSessionId")},
                        ${nullableColumn(columns, "projectId")}
                    FROM file_assets
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE file_assets")
            }
            db.execSQL("ALTER TABLE file_assets_new RENAME TO file_assets")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_createdAt ON file_assets(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_chatSessionId ON file_assets(chatSessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_projectId ON file_assets(projectId)")
        }

        private fun normalizeImageAssetsTable(db: SupportSQLiteDatabase) {
            val existed = tableExists(db, "image_assets")
            val columns = if (existed) tableColumns(db, "image_assets") else emptySet()
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS image_assets_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    uriString TEXT NOT NULL,
                    source TEXT NOT NULL,
                    prompt TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    sizeBytes INTEGER NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    generationMetadataJson TEXT NOT NULL DEFAULT '',
                    chatSessionId TEXT,
                    projectId TEXT
                )
                """.trimIndent()
            )
            if (existed) {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO image_assets_new (
                        id, name, uriString, source, prompt, createdAt, sizeBytes, width, height, generationMetadataJson, chatSessionId, projectId
                    )
                    SELECT
                        ${columnOrDefault(columns, "id", "hex(randomblob(16))")},
                        ${columnOrDefault(columns, "name", "'图片'")},
                        ${columnOrDefault(columns, "uriString", "''")},
                        ${columnOrDefault(columns, "source", "'uploaded'")},
                        ${columnOrDefault(columns, "prompt", "''")},
                        ${columnOrDefault(columns, "createdAt", "(strftime('%s','now') * 1000)")},
                        ${columnOrDefault(columns, "sizeBytes", "0")},
                        ${columnOrDefault(columns, "width", "0")},
                        ${columnOrDefault(columns, "height", "0")},
                        ${columnOrDefault(columns, "generationMetadataJson", "''")},
                        ${nullableColumn(columns, "chatSessionId")},
                        ${nullableColumn(columns, "projectId")}
                    FROM image_assets
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE image_assets")
            }
            db.execSQL("ALTER TABLE image_assets_new RENAME TO image_assets")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_image_assets_createdAt ON image_assets(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_image_assets_chatSessionId ON image_assets(chatSessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_image_assets_projectId ON image_assets(projectId)")
        }

        private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean =
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { cursor ->
                cursor.moveToFirst()
            }

        private fun tableColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> =
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(nameIndex))
                    }
                }
            }

        private fun columnOrDefault(columns: Set<String>, name: String, defaultSql: String): String =
            if (name in columns) "COALESCE(`$name`, $defaultSql)" else defaultSql

        private fun nullableColumn(columns: Set<String>, name: String): String =
            if (name in columns) "`$name`" else "NULL"

        private fun firstColumnOrDefault(columns: Set<String>, names: List<String>, defaultSql: String): String =
            names.firstOrNull { it in columns }?.let { "`$it`" } ?: defaultSql

        private fun normalizeLegacyFileAssetsTable(db: SQLiteDatabase) {
            if (!legacyTableExists(db, "file_assets")) return
            val columns = legacyTableColumns(db, "file_assets")
            val expectedColumns = setOf(
                "id",
                "name",
                "mimeType",
                "text",
                "preview",
                "truncated",
                "source",
                "createdAt",
                "sizeBytes",
                "chatSessionId",
                "projectId"
            )
            if (columns.containsAll(expectedColumns) && "contentPreview" !in columns && "uriString" !in columns) return
            val textExpression = firstColumnOrDefault(columns, listOf("text", "contentPreview", "preview"), "''")
            val previewExpression = firstColumnOrDefault(columns, listOf("preview", "contentPreview", "text"), "''")
            db.execSQL("DROP TABLE IF EXISTS file_assets_new")
            db.execSQL(
                """
                CREATE TABLE file_assets_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    text TEXT NOT NULL,
                    preview TEXT NOT NULL,
                    truncated INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    sizeBytes INTEGER NOT NULL,
                    chatSessionId TEXT,
                    projectId TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO file_assets_new (
                    id, name, mimeType, text, preview, truncated, source, createdAt, sizeBytes, chatSessionId, projectId
                )
                SELECT
                    ${legacyColumnOrDefault(columns, "id", "hex(randomblob(16))")},
                    ${legacyColumnOrDefault(columns, "name", "'文件'")},
                    ${legacyColumnOrDefault(columns, "mimeType", "'text/plain'")},
                    $textExpression,
                    $previewExpression,
                    ${legacyColumnOrDefault(columns, "truncated", "0")},
                    ${legacyColumnOrDefault(columns, "source", "'uploaded'")},
                    ${legacyColumnOrDefault(columns, "createdAt", "(strftime('%s','now') * 1000)")},
                    ${legacyColumnOrDefault(columns, "sizeBytes", "0")},
                    ${legacyNullableColumn(columns, "chatSessionId")},
                    ${legacyNullableColumn(columns, "projectId")}
                FROM file_assets
                """.trimIndent()
            )
            db.execSQL("DROP TABLE file_assets")
            db.execSQL("ALTER TABLE file_assets_new RENAME TO file_assets")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_createdAt ON file_assets(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_chatSessionId ON file_assets(chatSessionId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_assets_projectId ON file_assets(projectId)")
        }

        private fun normalizeLegacyChatMessagesTable(db: SQLiteDatabase) {
            if (!legacyTableExists(db, "chat_messages")) return
            val columns = legacyTableColumns(db, "chat_messages")
            db.execSQL("DROP TABLE IF EXISTS chat_messages_new")
            db.execSQL(
                """
                CREATE TABLE chat_messages_new (
                    sessionId TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    tokenCount INTEGER,
                    reasoningContent TEXT NOT NULL DEFAULT '',
                    reasoningDurationMs INTEGER NOT NULL DEFAULT 0,
                    imageAttachmentsJson TEXT NOT NULL DEFAULT '[]',
                    sourceReferencesJson TEXT NOT NULL DEFAULT '[]',
                    webSearchTraceJson TEXT NOT NULL DEFAULT '{}',
                    generationMetricsJson TEXT,
                    pinned INTEGER NOT NULL DEFAULT 0,
                    contextAssemblyTraceJson TEXT,
                    PRIMARY KEY(sessionId, position)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO chat_messages_new (
                    sessionId, position, role, content, createdAt, tokenCount,
                    reasoningContent, reasoningDurationMs, imageAttachmentsJson, sourceReferencesJson, webSearchTraceJson,
                    generationMetricsJson, pinned, contextAssemblyTraceJson
                )
                SELECT
                    ${legacyColumnOrDefault(columns, "sessionId", "''")},
                    ${legacyColumnOrDefault(columns, "position", "0")},
                    ${legacyColumnOrDefault(columns, "role", "'USER'")},
                    ${legacyColumnOrDefault(columns, "content", "''")},
                    ${legacyColumnOrDefault(columns, "createdAt", "(strftime('%s','now') * 1000)")},
                    ${legacyNullableColumn(columns, "tokenCount")},
                    ${legacyColumnOrDefault(columns, "reasoningContent", "''")},
                    ${legacyColumnOrDefault(columns, "reasoningDurationMs", "0")},
                    ${legacyColumnOrDefault(columns, "imageAttachmentsJson", "'[]'")},
                    ${legacyColumnOrDefault(columns, "sourceReferencesJson", "'[]'")},
                    ${legacyColumnOrDefault(columns, "webSearchTraceJson", "'{}'")},
                    ${legacyNullableColumn(columns, "generationMetricsJson")},
                    ${legacyColumnOrDefault(columns, "pinned", "0")},
                    ${legacyNullableColumn(columns, "contextAssemblyTraceJson")}
                FROM chat_messages
                """.trimIndent()
            )
            db.execSQL("DROP TABLE chat_messages")
            db.execSQL("ALTER TABLE chat_messages_new RENAME TO chat_messages")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_sessionId ON chat_messages(sessionId)")
        }

        private fun legacyTableExists(db: SQLiteDatabase, tableName: String): Boolean =
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { cursor ->
                cursor.moveToFirst()
            }

        private fun legacyTableColumns(db: SQLiteDatabase, tableName: String): Set<String> =
            db.rawQuery("PRAGMA table_info(`$tableName`)", emptyArray()).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                buildSet {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(nameIndex))
                    }
                }
            }

        private fun legacyColumnOrDefault(columns: Set<String>, name: String, defaultSql: String): String =
            if (name in columns) "`$name`" else defaultSql

        private fun legacyNullableColumn(columns: Set<String>, name: String): String =
            if (name in columns) "`$name`" else "NULL"

    }
}
