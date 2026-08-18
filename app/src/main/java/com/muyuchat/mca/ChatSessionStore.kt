package com.muyuchat.mca

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.ColumnInfo
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val MAX_KNOWLEDGE_BASE_IDS_PER_ROOM_OPERATION = 32
private const val MAX_KNOWLEDGE_CHUNKS_PER_ROOM_PAGE = 256

/** Hard caps for persisted chat history, independent of the live conversation buffer. */
internal data class ChatHistoryPersistenceLimits(
    val maxSessions: Int = 100,
    val maxMessages: Int = 2_000,
    val maxSerializedBytes: Long = 16L * 1024L * 1024L
) {
    init {
        require(maxSessions > 0) { "maxSessions must be positive." }
        require(maxMessages > 0) { "maxMessages must be positive." }
        require(maxSerializedBytes > 0L) { "maxSerializedBytes must be positive." }
    }
}

/**
 * Bounds the Room representation before a write. Inline image bytes are not
 * counted because [ChatMessage.toEntity] deliberately never persists them.
 */
internal object ChatHistoryPersistenceBounds {
    fun bound(
        records: List<ChatSessionRecord>,
        limits: ChatHistoryPersistenceLimits = ChatHistoryPersistenceLimits()
    ): List<ChatSessionRecord> {
        val prioritized = records
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<ChatSessionRecord>> { it.value.pinned }
                    .thenByDescending { it.value.updatedAt }
                    .thenBy { it.index }
            )
            .asSequence()
            .distinctBy { it.value.id }
            .take(limits.maxSessions)
            .map { it.value }
            .toList()

        var usedBytes = 0L
        var usedMessages = 0
        val retained = mutableListOf<ChatSessionRecord>()
        for (record in prioritized) {
            val sessionBytes = record.toEntity().serializedByteCount()
            if (!fitsWithin(usedBytes, sessionBytes, limits.maxSerializedBytes)) continue

            var recordBytes = sessionBytes
            val tailMessages = mutableListOf<ChatMessage>()
            for (index in record.messages.indices.reversed()) {
                if (usedMessages + tailMessages.size >= limits.maxMessages) break
                val message = record.messages[index]
                val messageBytes = message.toEntity(record.id, index).serializedByteCount()
                if (
                    !fitsWithin(
                        usedBytes.saturatingAdd(recordBytes),
                        messageBytes,
                        limits.maxSerializedBytes
                    )
                ) {
                    // Keeping an older message while dropping the newest one
                    // makes the persisted transcript misleading. Stop at its tail.
                    break
                }
                tailMessages += message
                recordBytes = recordBytes.saturatingAdd(messageBytes)
            }

            retained += record.copy(messages = tailMessages.asReversed())
            usedBytes = usedBytes.saturatingAdd(recordBytes)
            usedMessages += tailMessages.size
        }
        return retained
    }

    internal fun serializedByteCount(records: List<ChatSessionRecord>): Long =
        records.fold(0L) { total, record ->
            val sessionBytes = record.toEntity().serializedByteCount()
            val messageBytes = record.messages.foldIndexed(0L) { index, messagesTotal, message ->
                messagesTotal.saturatingAdd(message.toEntity(record.id, index).serializedByteCount())
            }
            total.saturatingAdd(sessionBytes).saturatingAdd(messageBytes)
        }

    private fun fitsWithin(current: Long, next: Long, limit: Long): Boolean =
        current <= limit && next <= limit - current
}

class ChatSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val database = McaRoomDatabase.get(appContext)
    private val legacyFile = File(appContext.filesDir, "chat_sessions.json")

    fun load(): List<ChatSessionRecord> = runBlocking(Dispatchers.IO) {
        runCatching {
            val records = database.chatSessionDao().loadRecords()
            val loaded = if (records.isNotEmpty()) {
                records
            } else {
                migrateLegacyJsonIfNeeded()
            }
            normalizePersistedHistory(loaded)
        }.getOrElse {
            runCatching { normalizePersistedHistory(migrateLegacyJsonIfNeeded()) }
                .getOrElse { emptyList() }
        }
    }

    fun save(sessions: List<ChatSessionRecord>) = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().replaceAll(ChatHistoryPersistenceBounds.bound(sessions))
    }

    /** Updates only the persisted appearance override for an existing session. */
    fun updateAppearance(sessionId: String, appearance: ChatAppearance?) = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().setSessionAppearance(sessionId, appearance?.toJsonString())
    }

    /**
     * Replaces the session snapshot and any newly-created knowledge bindings in
     * one Room transaction.  A first message can create both rows at once;
     * keeping them together prevents the binding from being lost when a newer
     * coalesced snapshot supersedes the original write.
     */
    fun save(
        sessions: List<ChatSessionRecord>,
        knowledgeBindings: Map<String, Set<String>>
    ) = runBlocking(Dispatchers.IO) {
        val boundedSessions = ChatHistoryPersistenceBounds.bound(sessions)
        database.chatSessionDao().replaceAllWithKnowledgeBindings(
            records = boundedSessions,
            bindingSessionIds = knowledgeBindings.keys.toList(),
            bindings = knowledgeBindings.flatMap { (chatSessionId, knowledgeBaseIds) ->
                knowledgeBaseIds.map { knowledgeBaseId ->
                    ChatKnowledgeBaseBindingEntity(
                        chatSessionId = chatSessionId,
                        knowledgeBaseId = knowledgeBaseId
                    )
                }
            }
        )
    }

    fun loadImages(): List<ImageAssetRecord> = runBlocking(Dispatchers.IO) {
        runCatching { database.chatSessionDao().loadImageRecords() }
            .getOrElse { emptyList() }
    }

    fun saveImages(images: List<ImageAssetRecord>) = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().replaceImages(images)
    }

    fun upsertImages(images: List<ImageAssetRecord>) = runBlocking(Dispatchers.IO) {
        if (images.isNotEmpty()) {
            database.chatSessionDao().insertImages(images.map { it.toEntity() })
        }
    }

    fun deleteImages(imageIds: List<String>) = runBlocking(Dispatchers.IO) {
        if (imageIds.isNotEmpty()) {
            database.chatSessionDao().deleteImages(imageIds.distinct())
        }
    }

    fun setImageFavorite(imageId: String, favorite: Boolean) = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().setImageFavorite(imageId, favorite)
    }

    fun clearImages() = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().clearImages()
    }

    fun loadFiles(): List<FileAssetRecord> = runBlocking(Dispatchers.IO) {
        runCatching { database.chatSessionDao().loadFileRecords() }
            .getOrElse { emptyList() }
    }

    fun saveFiles(files: List<FileAssetRecord>) = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().replaceFiles(files)
    }

    fun loadMemories(assistantId: String): List<MemoryRecord> = runBlocking(Dispatchers.IO) {
        runCatching { database.chatSessionDao().memoryRecords(assistantId) }
            .getOrElse { emptyList() }
    }

    fun saveMemories(memories: List<MemoryRecord>) = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().replaceMemories(memories)
    }

    private suspend fun migrateLegacyJsonIfNeeded(): List<ChatSessionRecord> {
        if (!legacyFile.exists()) return emptyList()
        val records = runCatching {
            val array = JSONArray(legacyFile.readText(Charsets.UTF_8))
            List(array.length()) { index ->
                array.getJSONObject(index).toChatSessionRecord()
            }.sortedForHistory()
        }.getOrElse { return emptyList() }
        val boundedRecords = ChatHistoryPersistenceBounds.bound(records)
        database.chatSessionDao().replaceAll(boundedRecords)
        runCatching {
            if (!legacyFile.delete()) {
                legacyFile.renameTo(File(legacyFile.parentFile, "${legacyFile.name}.migrated"))
            }
        }
        return boundedRecords
    }

    private suspend fun normalizePersistedHistory(
        records: List<ChatSessionRecord>
    ): List<ChatSessionRecord> {
        val boundedRecords = ChatHistoryPersistenceBounds.bound(records)
        if (boundedRecords != records) {
            database.chatSessionDao().replaceAll(boundedRecords)
        }
        return boundedRecords
    }

    private fun JSONObject.toChatSessionRecord(): ChatSessionRecord =
        ChatSessionRecord(
            id = optString("id"),
            title = optString("title", "新对话"),
            messages = optJSONArray("messages").toChatMessages(),
            pinned = optBoolean("pinned", false),
            manualTitle = optBoolean("manualTitle", false),
            updatedAt = optLong("updatedAt", System.currentTimeMillis()),
            projectId = optString("projectId").takeIf { it.isNotBlank() },
            assistantId = optString("assistantId").takeIf { it.isNotBlank() },
            assistantSnapshot = AssistantConversationSnapshot.fromJsonOrNull(
                optString("assistantSnapshotJson").takeIf { it.isNotBlank() }
                    ?: optJSONObject("assistantSnapshot")?.toString()
            ),
            modelMode = optString("modelMode").takeIf { it.isNotBlank() },
            modelId = optString("modelId").takeIf { it.isNotBlank() },
            appearanceOverride = ChatAppearance.fromJsonOrNull(
                optJSONObject("appearanceOverride")?.toString()
                    ?: optString("appearanceOverrideJson").takeIf { it.isNotBlank() }
            )
        )

    private fun JSONArray?.toChatMessages(): List<ChatMessage> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val json = getJSONObject(index)
            ChatMessage(
                role = json.optRole(),
                content = json.optString("content"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                tokenCount = if (json.isNull("tokenCount")) null else json.optInt("tokenCount"),
                reasoningContent = json.optString("reasoningContent"),
                reasoningDurationMs = json.optLong("reasoningDurationMs", 0L),
                imageAttachments = json.optJSONArray("imageAttachments").toImageAttachments(),
                sourceReferences = json.optJSONArray("sourceReferences").toSourceReferences(),
                webSearchTrace = json.optJSONObject("webSearchTrace").toWebSearchTrace()
            )
        }
    }

    private fun JSONObject.optRole(): Role =
        runCatching { Role.valueOf(optString("role", Role.USER.name).uppercase()) }
            .getOrDefault(Role.USER)
}

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ImageAssetEntity::class,
        FileAssetEntity::class,
        MemoryEntity::class,
        AssistantEntity::class,
        KnowledgeBaseEntity::class,
        KnowledgeDocumentEntity::class,
        KnowledgeChunkEntity::class,
        ChatKnowledgeBaseBindingEntity::class
    ],
    version = 21,
    exportSchema = false
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
                        MIGRATION_20_21
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
            val textExpression = legacyFirstColumnOrDefault(columns, listOf("text", "contentPreview", "preview"), "''")
            val previewExpression = legacyFirstColumnOrDefault(columns, listOf("preview", "contentPreview", "text"), "''")
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
                    PRIMARY KEY(sessionId, position)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO chat_messages_new (
                    sessionId, position, role, content, createdAt, tokenCount,
                    reasoningContent, reasoningDurationMs, imageAttachmentsJson, sourceReferencesJson, webSearchTraceJson
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
                    ${legacyColumnOrDefault(columns, "webSearchTraceJson", "'{}'")}
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

        private fun legacyFirstColumnOrDefault(columns: Set<String>, names: List<String>, defaultSql: String): String =
            names.firstOrNull { it in columns }?.let { "`$it`" } ?: defaultSql
    }
}

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY pinned DESC, updatedAt DESC")
    suspend fun sessions(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY position ASC")
    suspend fun messages(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE sessionId IN (:sessionIds) ORDER BY sessionId ASC, position ASC")
    suspend fun messagesForSessions(sessionIds: List<String>): List<ChatMessageEntity>

    @Upsert
    suspend fun upsertSessions(sessions: List<ChatSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE sessionId NOT IN (:sessionIds)")
    suspend fun pruneMessages(sessionIds: List<String>)

    @Query("DELETE FROM chat_messages")
    suspend fun clearMessages()

    @Query("DELETE FROM chat_sessions")
    suspend fun clearSessions()

    @Query("DELETE FROM chat_sessions WHERE id NOT IN (:sessionIds)")
    suspend fun pruneSessions(sessionIds: List<String>)

    @Query("SELECT * FROM image_assets ORDER BY createdAt DESC")
    suspend fun images(): List<ImageAssetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<ImageAssetEntity>)

    @Query("DELETE FROM image_assets")
    suspend fun clearImages()

    @Query("DELETE FROM image_assets WHERE id IN (:imageIds)")
    suspend fun deleteImages(imageIds: List<String>)

    @Query("UPDATE image_assets SET favorite = :favorite WHERE id = :imageId")
    suspend fun setImageFavorite(imageId: String, favorite: Boolean): Int

    @Query("SELECT * FROM file_assets ORDER BY createdAt DESC")
    suspend fun files(): List<FileAssetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<FileAssetEntity>)

    @Query("DELETE FROM file_assets")
    suspend fun clearFiles()

    @Query("SELECT * FROM memories WHERE assistantId = :assistantId ORDER BY createdAt DESC")
    suspend fun memories(assistantId: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemories(memories: List<MemoryEntity>)

    @Query("DELETE FROM memories")
    suspend fun clearMemories()

    @Query("SELECT * FROM assistants ORDER BY updatedAt DESC")
    suspend fun assistants(): List<AssistantEntity>

    @Query("UPDATE chat_sessions SET appearanceJson = :appearanceJson WHERE id = :sessionId")
    suspend fun setSessionAppearance(sessionId: String, appearanceJson: String?): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssistants(assistants: List<AssistantEntity>)

    @Query("DELETE FROM assistants")
    suspend fun clearAssistants()

    @Query("SELECT * FROM knowledge_bases ORDER BY updatedAt DESC, name ASC")
    suspend fun knowledgeBases(): List<KnowledgeBaseEntity>

    @Upsert
    suspend fun insertKnowledgeBases(records: List<KnowledgeBaseEntity>)

    @Query("DELETE FROM knowledge_bases WHERE id = :knowledgeBaseId")
    suspend fun deleteKnowledgeBase(knowledgeBaseId: String)

    @Query("SELECT * FROM knowledge_documents WHERE knowledgeBaseId = :knowledgeBaseId ORDER BY updatedAt DESC, title ASC")
    suspend fun knowledgeDocuments(knowledgeBaseId: String): List<KnowledgeDocumentEntity>

    @Query("SELECT * FROM knowledge_documents WHERE id = :documentId LIMIT 1")
    suspend fun knowledgeDocument(documentId: String): KnowledgeDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnowledgeDocuments(records: List<KnowledgeDocumentEntity>)

    @Query("DELETE FROM knowledge_documents WHERE id = :documentId")
    suspend fun deleteKnowledgeDocument(documentId: String)

    @Query("DELETE FROM knowledge_documents WHERE knowledgeBaseId = :knowledgeBaseId")
    suspend fun deleteKnowledgeDocumentsForBase(knowledgeBaseId: String)

    @Query(
        """
        SELECT knowledge_chunks.* FROM knowledge_chunks
        INNER JOIN knowledge_bases ON knowledge_bases.id = knowledge_chunks.knowledgeBaseId
        INNER JOIN knowledge_documents
            ON knowledge_documents.id = knowledge_chunks.documentId
            AND knowledge_documents.knowledgeBaseId = knowledge_chunks.knowledgeBaseId
        WHERE knowledge_chunks.knowledgeBaseId IN (:knowledgeBaseIds)
            AND (
                :afterKnowledgeBaseId IS NULL
                OR knowledge_chunks.knowledgeBaseId > :afterKnowledgeBaseId
                OR (
                    knowledge_chunks.knowledgeBaseId = :afterKnowledgeBaseId
                    AND knowledge_chunks.documentId > :afterDocumentId
                )
                OR (
                    knowledge_chunks.knowledgeBaseId = :afterKnowledgeBaseId
                    AND knowledge_chunks.documentId = :afterDocumentId
                    AND knowledge_chunks.position > :afterPosition
                )
                OR (
                    knowledge_chunks.knowledgeBaseId = :afterKnowledgeBaseId
                    AND knowledge_chunks.documentId = :afterDocumentId
                    AND knowledge_chunks.position = :afterPosition
                    AND knowledge_chunks.id > :afterId
                )
            )
        ORDER BY
            knowledge_chunks.knowledgeBaseId ASC,
            knowledge_chunks.documentId ASC,
            knowledge_chunks.position ASC,
            knowledge_chunks.id ASC
        LIMIT :limit
        """
    )
    suspend fun knowledgeChunkPageForBases(
        knowledgeBaseIds: List<String>,
        afterKnowledgeBaseId: String?,
        afterDocumentId: String?,
        afterPosition: Int?,
        afterId: String?,
        limit: Int
    ): List<KnowledgeChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnowledgeChunks(records: List<KnowledgeChunkEntity>)

    @Query("DELETE FROM knowledge_chunks WHERE documentId = :documentId")
    suspend fun deleteKnowledgeChunksForDocument(documentId: String)

    @Query("DELETE FROM knowledge_chunks WHERE knowledgeBaseId = :knowledgeBaseId")
    suspend fun deleteKnowledgeChunksForBase(knowledgeBaseId: String)

    @Query(
        """
        SELECT chat_knowledge_base_bindings.knowledgeBaseId
        FROM chat_knowledge_base_bindings
        INNER JOIN chat_sessions ON chat_sessions.id = chat_knowledge_base_bindings.chatSessionId
        INNER JOIN knowledge_bases ON knowledge_bases.id = chat_knowledge_base_bindings.knowledgeBaseId
        WHERE chat_knowledge_base_bindings.chatSessionId = :chatSessionId
        ORDER BY chat_knowledge_base_bindings.knowledgeBaseId ASC
        """
    )
    suspend fun knowledgeBaseIdsForChat(chatSessionId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnowledgeBindings(records: List<ChatKnowledgeBaseBindingEntity>)

    @Query("DELETE FROM chat_knowledge_base_bindings WHERE chatSessionId = :chatSessionId")
    suspend fun deleteKnowledgeBindingsForChat(chatSessionId: String)

    @Query("DELETE FROM chat_knowledge_base_bindings WHERE knowledgeBaseId = :knowledgeBaseId")
    suspend fun deleteKnowledgeBindingsForBase(knowledgeBaseId: String)

    @Query("DELETE FROM chat_knowledge_base_bindings")
    suspend fun clearKnowledgeBindings()

    @Query("DELETE FROM chat_knowledge_base_bindings WHERE chatSessionId NOT IN (:chatSessionIds)")
    suspend fun pruneKnowledgeBindings(chatSessionIds: List<String>)

    @Query("SELECT COUNT(*) > 0 FROM knowledge_bases WHERE id = :knowledgeBaseId")
    suspend fun knowledgeBaseExists(knowledgeBaseId: String): Boolean

    @Query("SELECT COUNT(*) > 0 FROM chat_sessions WHERE id = :chatSessionId")
    suspend fun chatSessionExists(chatSessionId: String): Boolean

    @Query("SELECT id FROM knowledge_bases WHERE id IN (:knowledgeBaseIds)")
    suspend fun existingKnowledgeBaseIds(knowledgeBaseIds: List<String>): List<String>

    @Query(
        """
        UPDATE knowledge_bases
        SET indexState = CASE
                WHEN embeddingModelFingerprint IS NULL THEN 'LEXICAL_READY'
                ELSE 'REINDEX_REQUIRED'
            END,
            updatedAt = :updatedAt
        WHERE id = :knowledgeBaseId
        """
    )
    suspend fun invalidateKnowledgeBaseIndex(knowledgeBaseId: String, updatedAt: Long)

    @Transaction
    suspend fun loadRecords(): List<ChatSessionRecord> =
        sessions().map { session ->
            ChatSessionRecord(
                id = session.id,
                title = session.title,
                messages = messages(session.id).map { it.toChatMessage() },
                pinned = session.pinned,
                manualTitle = session.manualTitle,
                updatedAt = session.updatedAt,
                projectId = session.projectId,
                assistantId = session.assistantId,
                assistantSnapshot = AssistantConversationSnapshot.fromJsonOrNull(
                    session.assistantSnapshotJson
                ),
                modelMode = session.modelMode,
                modelId = session.modelId,
                appearanceOverride = ChatAppearance.fromJsonOrNull(session.appearanceJson)
            )
        }

    @Transaction
    suspend fun replaceAll(records: List<ChatSessionRecord>) {
        reconcileSnapshot(ChatHistoryPersistenceBounds.bound(records))
    }

    /** Reconciles a full UI snapshot without deleting and recreating unchanged rows. */
    @Transaction
    suspend fun reconcileSnapshot(records: List<ChatSessionRecord>) {
        if (records.isEmpty()) {
            clearMessages()
            clearKnowledgeBindings()
            clearSessions()
            return
        }

        val sessionEntities = records.map { it.toEntity() }
        val liveSessionIds = sessionEntities.map { it.id }
        val persistedSessionsById = sessions().associateBy { it.id }
        val changedSessions = sessionEntities.filter { session ->
            persistedSessionsById[session.id] != session
        }
        if (changedSessions.isNotEmpty()) {
            upsertSessions(changedSessions)
        }

        val persistedMessagesBySession = messagesForSessions(liveSessionIds)
            .groupBy { it.sessionId }
        records.forEach { session ->
            val desiredMessages = session.messages.mapIndexed { index, message ->
                message.toEntity(session.id, index)
            }
            if (persistedMessagesBySession[session.id].orEmpty() != desiredMessages) {
                deleteMessagesForSession(session.id)
                if (desiredMessages.isNotEmpty()) {
                    insertMessages(desiredMessages)
                }
            }
        }
        // Chat messages predate Room foreign keys, so prune them explicitly.
        pruneMessages(liveSessionIds)
        pruneKnowledgeBindings(liveSessionIds)
        pruneSessions(liveSessionIds)
    }

    /** Session snapshot plus binding writes used by the first-message path. */
    @Transaction
    suspend fun replaceAllWithKnowledgeBindings(
        records: List<ChatSessionRecord>,
        bindingSessionIds: List<String>,
        bindings: List<ChatKnowledgeBaseBindingEntity>
    ) {
        val boundedRecords = ChatHistoryPersistenceBounds.bound(records)
        reconcileSnapshot(boundedRecords)
        if (boundedRecords.isEmpty() || bindingSessionIds.isEmpty()) return

        val liveSessionIds = boundedRecords.mapTo(hashSetOf()) { it.id }
        val bindingsBySession = bindings.groupBy { it.chatSessionId }
        bindingSessionIds
            .asSequence()
            .filter { it in liveSessionIds }
            .distinct()
            .forEach { chatSessionId ->
                replaceKnowledgeBindings(
                    chatSessionId = chatSessionId,
                    knowledgeBaseIds = bindingsBySession[chatSessionId]
                        .orEmpty()
                        .map { it.knowledgeBaseId }
                )
            }
    }

    @Transaction
    suspend fun loadImageRecords(): List<ImageAssetRecord> =
        images().map { it.toImageAssetRecord() }

    @Transaction
    suspend fun replaceImages(records: List<ImageAssetRecord>) {
        clearImages()
        if (records.isEmpty()) return
        insertImages(records.map { it.toEntity() })
    }

    @Transaction
    suspend fun loadFileRecords(): List<FileAssetRecord> =
        files().map { it.toFileAssetRecord() }

    @Transaction
    suspend fun replaceFiles(records: List<FileAssetRecord>) {
        clearFiles()
        if (records.isEmpty()) return
        insertFiles(records.map { it.toEntity() })
    }

    @Transaction
    suspend fun memoryRecords(assistantId: String): List<MemoryRecord> =
        memories(assistantId).map { it.toMemoryRecord() }

    @Transaction
    suspend fun replaceMemories(records: List<MemoryRecord>) {
        clearMemories()
        if (records.isEmpty()) return
        insertMemories(records.map { it.toEntity() })
    }

    @Transaction
    suspend fun assistantRecords(): List<AssistantRecord> =
        assistants().map { it.toAssistantRecord() }

    @Transaction
    suspend fun replaceAssistants(records: List<AssistantRecord>) {
        clearAssistants()
        if (records.isEmpty()) return
        insertAssistants(records.map { it.toEntity() })
    }

    @Transaction
    suspend fun knowledgeBaseRecords(): List<KnowledgeBaseRecord> =
        knowledgeBases().map { it.toRecord() }

    @Transaction
    suspend fun upsertKnowledgeBases(records: List<KnowledgeBaseEntity>) {
        if (records.isNotEmpty()) insertKnowledgeBases(records)
    }

    @Transaction
    suspend fun knowledgeDocumentRecords(knowledgeBaseId: String): List<KnowledgeDocumentRecord> =
        knowledgeDocuments(knowledgeBaseId).map { it.toRecord() }

    @Transaction
    suspend fun replaceKnowledgeDocument(
        document: KnowledgeDocumentEntity,
        chunks: List<KnowledgeChunkEntity>
    ) {
        require(chunks.all { chunk ->
            chunk.knowledgeBaseId == document.knowledgeBaseId && chunk.documentId == document.id
        }) {
            "Knowledge chunks must belong to the document and knowledge base being replaced."
        }
        check(knowledgeBaseExists(document.knowledgeBaseId)) {
            "Knowledge base no longer exists."
        }
        deleteKnowledgeChunksForDocument(document.id)
        deleteKnowledgeDocument(document.id)
        insertKnowledgeDocuments(listOf(document))
        if (chunks.isNotEmpty()) insertKnowledgeChunks(chunks)
        invalidateKnowledgeBaseIndex(document.knowledgeBaseId, document.updatedAt)
    }

    @Transaction
    suspend fun deleteKnowledgeDocumentCompletely(
        documentId: String,
        updatedAt: Long
    ) {
        val document = knowledgeDocument(documentId) ?: return
        if (!knowledgeBaseExists(document.knowledgeBaseId)) return
        deleteKnowledgeChunksForDocument(documentId)
        deleteKnowledgeDocument(documentId)
        invalidateKnowledgeBaseIndex(document.knowledgeBaseId, updatedAt)
    }

    @Transaction
    suspend fun deleteKnowledgeBaseCompletely(knowledgeBaseId: String) {
        deleteKnowledgeBindingsForBase(knowledgeBaseId)
        deleteKnowledgeChunksForBase(knowledgeBaseId)
        deleteKnowledgeDocumentsForBase(knowledgeBaseId)
        deleteKnowledgeBase(knowledgeBaseId)
    }

    @Transaction
    suspend fun replaceKnowledgeBindings(chatSessionId: String, knowledgeBaseIds: List<String>) {
        require(knowledgeBaseIds.size <= MAX_KNOWLEDGE_BASE_IDS_PER_ROOM_OPERATION) {
            "At most $MAX_KNOWLEDGE_BASE_IDS_PER_ROOM_OPERATION knowledge bases can be selected."
        }
        require(knowledgeBaseIds.all { it.isNotBlank() }) {
            "Knowledge base IDs must not be blank."
        }
        val normalizedKnowledgeBaseIds = knowledgeBaseIds.distinct().sorted()
        check(chatSessionExists(chatSessionId)) {
            "Chat session no longer exists."
        }
        if (normalizedKnowledgeBaseIds.isNotEmpty()) {
            check(existingKnowledgeBaseIds(normalizedKnowledgeBaseIds).toSet() == normalizedKnowledgeBaseIds.toSet()) {
                "One or more knowledge bases no longer exist."
            }
        }
        deleteKnowledgeBindingsForChat(chatSessionId)
        if (normalizedKnowledgeBaseIds.isNotEmpty()) {
            insertKnowledgeBindings(
                normalizedKnowledgeBaseIds.map { knowledgeBaseId ->
                    ChatKnowledgeBaseBindingEntity(
                        chatSessionId = chatSessionId,
                        knowledgeBaseId = knowledgeBaseId
                    )
                }
            )
        }
    }

    @Transaction
    suspend fun knowledgeChunkPageForBaseRecords(
        knowledgeBaseIds: List<String>,
        afterKnowledgeBaseId: String?,
        afterDocumentId: String?,
        afterPosition: Int?,
        afterId: String?,
        limit: Int
    ): List<KnowledgeChunkRecord> {
        val boundedKnowledgeBaseIds = knowledgeBaseIds.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_KNOWLEDGE_BASE_IDS_PER_ROOM_OPERATION)
            .sorted()
            .toList()
        return if (boundedKnowledgeBaseIds.isEmpty() || limit <= 0) {
            emptyList()
        } else {
            knowledgeChunkPageForBases(
                knowledgeBaseIds = boundedKnowledgeBaseIds,
                afterKnowledgeBaseId = afterKnowledgeBaseId,
                afterDocumentId = afterDocumentId,
                afterPosition = afterPosition,
                afterId = afterId,
                limit = limit.coerceAtMost(MAX_KNOWLEDGE_CHUNKS_PER_ROOM_PAGE)
            ).map { it.toRecord() }
        }
    }
}

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val pinned: Boolean,
    val manualTitle: Boolean,
    val updatedAt: Long,
    val projectId: String? = null,
    val assistantId: String? = null,
    val assistantSnapshotJson: String? = null,
    val modelMode: String? = null,
    val modelId: String? = null,
    val appearanceJson: String? = null
)

@Entity(
    tableName = "image_assets",
    indices = [Index("createdAt"), Index("chatSessionId"), Index("projectId"), Index("favorite")]
)
data class ImageAssetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val uriString: String,
    val source: String,
    val prompt: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(defaultValue = "")
    val generationMetadataJson: String,
    @ColumnInfo(defaultValue = "0")
    val favorite: Boolean = false,
    val chatSessionId: String?,
    val projectId: String? = null
)

@Entity(
    tableName = "file_assets",
    indices = [Index("createdAt"), Index("chatSessionId"), Index("projectId")]
)
data class FileAssetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mimeType: String,
    val text: String,
    val preview: String,
    val truncated: Boolean,
    val source: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val chatSessionId: String?,
    val projectId: String? = null
)

@Entity(
    tableName = "memories",
    indices = [Index("assistantId"), Index("scope"), Index("createdAt")]
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val assistantId: String,
    val scope: String,
    val content: String,
    val source: String,
    val createdAt: Long
)

@Entity(
    tableName = "assistants",
    indices = [Index("updatedAt")]
)
data class AssistantEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatar: String,
    @ColumnInfo(defaultValue = "")
    val tag: String,
    val systemPrompt: String,
    val defaultModelMode: String,
    val defaultModelId: String?,
    val paramsJson: String,
    val characterCardJson: String?,
    val memoryEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val fileContextEnabled: Boolean,
    val appearanceJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "knowledge_bases",
    indices = [Index("updatedAt")]
)
data class KnowledgeBaseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val embeddingModelFingerprint: String?,
    val indexState: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "knowledge_documents",
    indices = [Index("knowledgeBaseId"), Index("contentHash")],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledgeBaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class KnowledgeDocumentEntity(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val title: String,
    val source: String,
    val contentHash: String,
    val contentLength: Int,
    val chunkCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "knowledge_chunks",
    indices = [Index("knowledgeBaseId"), Index("documentId")],
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledgeBaseId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = KnowledgeDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class KnowledgeChunkEntity(
    @PrimaryKey val id: String,
    val knowledgeBaseId: String,
    val documentId: String,
    val position: Int,
    val content: String,
    val contentHash: String,
    val estimatedTokens: Int
)

@Entity(
    tableName = "chat_knowledge_base_bindings",
    primaryKeys = ["chatSessionId", "knowledgeBaseId"],
    indices = [Index("knowledgeBaseId")],
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatSessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledgeBaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatKnowledgeBaseBindingEntity(
    val chatSessionId: String,
    val knowledgeBaseId: String
)

@Entity(
    tableName = "chat_messages",
    primaryKeys = ["sessionId", "position"],
    indices = [Index("sessionId")]
)
data class ChatMessageEntity(
    val sessionId: String,
    val position: Int,
    val role: String,
    val content: String,
    val createdAt: Long,
    val tokenCount: Int?,
    @ColumnInfo(defaultValue = "")
    val reasoningContent: String = "",
    @ColumnInfo(defaultValue = "0")
    val reasoningDurationMs: Long = 0L,
    @ColumnInfo(defaultValue = "[]")
    val imageAttachmentsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]")
    val sourceReferencesJson: String = "[]",
    @ColumnInfo(defaultValue = "{}")
    val webSearchTraceJson: String = "{}"
)

private const val SERIALIZED_ROW_OVERHEAD_BYTES = 16L
private const val SERIALIZED_STRING_LENGTH_BYTES = 4L
private const val SERIALIZED_NULL_FIELD_BYTES = 1L

/**
 * Counts persisted column payloads without allocating a UTF-8 byte array.
 * The fixed overhead keeps the limit conservative with respect to Room row
 * metadata while avoiding image attachment inline data, which is not stored.
 */
private fun ChatSessionEntity.serializedByteCount(): Long =
    SERIALIZED_ROW_OVERHEAD_BYTES
        .saturatingAdd(id.serializedFieldByteCount())
        .saturatingAdd(title.serializedFieldByteCount())
        .saturatingAdd(1L) // pinned
        .saturatingAdd(1L) // manualTitle
        .saturatingAdd(8L) // updatedAt
        .saturatingAdd(projectId.nullableSerializedFieldByteCount())
        .saturatingAdd(assistantId.nullableSerializedFieldByteCount())
        .saturatingAdd(assistantSnapshotJson.nullableSerializedFieldByteCount())
        .saturatingAdd(modelMode.nullableSerializedFieldByteCount())
        .saturatingAdd(modelId.nullableSerializedFieldByteCount())
        .saturatingAdd(appearanceJson.nullableSerializedFieldByteCount())

private fun ChatMessageEntity.serializedByteCount(): Long =
    SERIALIZED_ROW_OVERHEAD_BYTES
        .saturatingAdd(sessionId.serializedFieldByteCount())
        .saturatingAdd(4L) // position
        .saturatingAdd(role.serializedFieldByteCount())
        .saturatingAdd(content.serializedFieldByteCount())
        .saturatingAdd(8L) // createdAt
        .saturatingAdd(if (tokenCount == null) SERIALIZED_NULL_FIELD_BYTES else 5L)
        .saturatingAdd(reasoningContent.serializedFieldByteCount())
        .saturatingAdd(8L) // reasoningDurationMs
        .saturatingAdd(imageAttachmentsJson.serializedFieldByteCount())
        .saturatingAdd(sourceReferencesJson.serializedFieldByteCount())
        .saturatingAdd(webSearchTraceJson.serializedFieldByteCount())

private fun String.serializedFieldByteCount(): Long =
    SERIALIZED_STRING_LENGTH_BYTES.saturatingAdd(serializedUtf8ByteCount())

private fun String?.nullableSerializedFieldByteCount(): Long =
    if (this == null) SERIALIZED_NULL_FIELD_BYTES else serializedFieldByteCount()

private fun String.serializedUtf8ByteCount(): Long {
    var total = 0L
    var index = 0
    while (index < length) {
        val character = this[index]
        val bytes = when {
            character <= '\u007f' -> 1L
            character <= '\u07ff' -> 2L
            Character.isHighSurrogate(character) &&
                index + 1 < length && Character.isLowSurrogate(this[index + 1]) -> {
                index += 1
                4L
            }
            else -> 3L
        }
        total = total.saturatingAdd(bytes)
        index += 1
    }
    return total
}

private fun Long.saturatingAdd(value: Long): Long = when {
    this < 0L || value < 0L -> Long.MAX_VALUE
    this > Long.MAX_VALUE - value -> Long.MAX_VALUE
    else -> this + value
}

private fun ChatSessionRecord.toEntity(): ChatSessionEntity =
    ChatSessionEntity(
        id = id,
        title = title,
        pinned = pinned,
        manualTitle = manualTitle,
        updatedAt = updatedAt,
        projectId = projectId,
        assistantId = assistantId,
        assistantSnapshotJson = assistantSnapshot?.toJsonString(),
        modelMode = modelMode,
        modelId = modelId,
        appearanceJson = appearanceOverride?.toJsonString()
    )

private fun ImageAssetRecord.toEntity(): ImageAssetEntity =
    ImageAssetEntity(
        id = id,
        name = name,
        uriString = uriString,
        source = source,
        prompt = prompt,
        createdAt = createdAt,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        generationMetadataJson = generationMetadataJson,
        favorite = favorite,
        chatSessionId = chatSessionId,
        projectId = projectId
    )

private fun FileAssetRecord.toEntity(): FileAssetEntity =
    FileAssetEntity(
        id = id,
        name = name,
        mimeType = mimeType,
        text = text,
        preview = preview,
        truncated = truncated,
        source = source,
        createdAt = createdAt,
        sizeBytes = sizeBytes,
        chatSessionId = chatSessionId,
        projectId = projectId
    )

private fun MemoryRecord.toEntity(): MemoryEntity =
    MemoryEntity(
        id = id,
        assistantId = assistantId,
        scope = scope,
        content = content,
        source = source,
        createdAt = createdAt
    )

private fun AssistantRecord.toEntity(): AssistantEntity =
    AssistantEntity(
        id = id,
        name = name,
        avatar = avatar,
        tag = tag,
        systemPrompt = systemPrompt,
        defaultModelMode = defaultModelMode,
        defaultModelId = defaultModelId,
        paramsJson = paramsJson,
        characterCardJson = characterCardJson,
        memoryEnabled = memoryEnabled,
        webSearchEnabled = webSearchEnabled,
        fileContextEnabled = fileContextEnabled,
        appearanceJson = appearance.takeUnless { it.isDefault }?.toJsonString(),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

private fun ChatMessage.toEntity(sessionId: String, position: Int): ChatMessageEntity =
    ChatMessageEntity(
        sessionId = sessionId,
        position = position,
        role = role.name,
        content = content,
        createdAt = createdAt,
        tokenCount = tokenCount,
        reasoningContent = reasoningContent,
        reasoningDurationMs = reasoningDurationMs,
        imageAttachmentsJson = imageAttachments.toJsonArrayString(includeInlineData = false),
        sourceReferencesJson = sourceReferences.toJsonArrayString(),
        webSearchTraceJson = webSearchTrace.toJsonString()
    )

private fun ChatMessageEntity.toChatMessage(): ChatMessage =
    ChatMessage(
        role = runCatching { Role.valueOf(role) }.getOrDefault(Role.USER),
        content = content,
        createdAt = createdAt,
        tokenCount = tokenCount,
        reasoningContent = reasoningContent,
        reasoningDurationMs = reasoningDurationMs,
        imageAttachments = runCatching { JSONArray(imageAttachmentsJson).toImageAttachments() }.getOrDefault(emptyList()),
        sourceReferences = runCatching { JSONArray(sourceReferencesJson).toSourceReferences() }.getOrDefault(emptyList()),
        webSearchTrace = runCatching { JSONObject(webSearchTraceJson).toWebSearchTrace() }.getOrNull()
    )

private fun ImageAssetEntity.toImageAssetRecord(): ImageAssetRecord =
    ImageAssetRecord(
        id = id,
        name = name,
        uriString = uriString,
        source = source,
        prompt = prompt,
        createdAt = createdAt,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        generationMetadataJson = generationMetadataJson,
        favorite = favorite,
        chatSessionId = chatSessionId,
        projectId = projectId
    )

private fun FileAssetEntity.toFileAssetRecord(): FileAssetRecord =
    FileAssetRecord(
        id = id,
        name = name,
        mimeType = mimeType,
        text = text,
        preview = preview,
        truncated = truncated,
        source = source,
        createdAt = createdAt,
        sizeBytes = sizeBytes,
        chatSessionId = chatSessionId,
        projectId = projectId
    )

private fun MemoryEntity.toMemoryRecord(): MemoryRecord =
    MemoryRecord(
        id = id,
        assistantId = assistantId,
        scope = scope,
        content = content,
        source = source,
        createdAt = createdAt
    )

private fun AssistantEntity.toAssistantRecord(): AssistantRecord =
    AssistantRecord(
        id = id,
        name = name,
        avatar = avatar,
        tag = tag,
        systemPrompt = systemPrompt,
        defaultModelMode = defaultModelMode,
        defaultModelId = defaultModelId,
        paramsJson = paramsJson,
        characterCardJson = characterCardJson,
        memoryEnabled = memoryEnabled,
        webSearchEnabled = webSearchEnabled,
        fileContextEnabled = fileContextEnabled,
        appearance = ChatAppearance.fromJsonOrNull(appearanceJson) ?: ChatAppearance(),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

private suspend fun List<ChatSessionRecord>.sortedForHistory(): List<ChatSessionRecord> =
    withContext(Dispatchers.Default) {
        sortedWith(
            compareByDescending<ChatSessionRecord> { it.pinned }
                .thenByDescending { it.updatedAt }
        )
    }

private fun JSONArray?.toImageAttachments(): List<ChatImageAttachment> {
    if (this == null) return emptyList()
    return List(length()) { index ->
        val json = optJSONObject(index) ?: JSONObject()
        ChatImageAttachment(
            name = json.optString("name"),
            uriString = json.optString("uriString"),
            mimeType = json.optString("mimeType", "image/jpeg"),
            dataBase64 = json.optString("dataBase64"),
            width = json.optInt("width", 0),
            height = json.optInt("height", 0),
            sizeBytes = json.optLong("sizeBytes", 0L)
        )
    }.filter { it.uriString.isNotBlank() || it.dataBase64.isNotBlank() }
}

private fun List<ChatImageAttachment>.toJsonArrayString(includeInlineData: Boolean): String {
    val array = JSONArray()
    forEach { attachment ->
        array.put(
            JSONObject()
                .put("name", attachment.name)
                .put("uriString", attachment.uriString)
                .put("mimeType", attachment.mimeType)
                .put("dataBase64", if (includeInlineData) attachment.dataBase64 else "")
                .put("width", attachment.width)
                .put("height", attachment.height)
                .put("sizeBytes", attachment.sizeBytes)
        )
    }
    return array.toString()
}

private fun JSONArray?.toSourceReferences(): List<ChatSourceReference> {
    if (this == null) return emptyList()
    return List(length()) { index ->
        val json = optJSONObject(index) ?: JSONObject()
        ChatSourceReference(
            title = json.optString("title"),
            url = json.optString("url"),
            snippet = json.optString("snippet"),
            provider = json.optString("provider"),
            hostLabel = json.optString("hostLabel"),
            trustLabel = json.optString("trustLabel"),
            trustReason = json.optString("trustReason")
        )
    }.filter { it.url.isNotBlank() }
}

private fun List<ChatSourceReference>.toJsonArrayString(): String {
    val array = JSONArray()
    forEach { source ->
        array.put(
            JSONObject()
                .put("title", source.title)
                .put("url", source.url)
                .put("snippet", source.snippet)
                .put("provider", source.provider)
                .put("hostLabel", source.hostLabel)
                .put("trustLabel", source.trustLabel)
                .put("trustReason", source.trustReason)
        )
    }
    return array.toString()
}

private fun JSONObject?.toWebSearchTrace(): ChatWebSearchTrace? {
    if (this == null || length() == 0) return null
    val trace = ChatWebSearchTrace(
        query = optString("query"),
        providerLabel = optString("providerLabel"),
        triggerModeLabel = optString("triggerModeLabel"),
        running = optBoolean("running", false),
        stageLabel = optString("stageLabel"),
        searchedQueries = optJSONArray("searchedQueries").toStringList(),
        directUrls = optJSONArray("directUrls").toStringList(),
        sourceCount = optInt("sourceCount", 0),
        elapsedMs = optLong("elapsedMs", 0L),
        success = optBoolean("success", false),
        message = optString("message"),
        healthScore = optInt("healthScore", 0),
        healthLabel = optString("healthLabel"),
        qualityScore = optInt("qualityScore", 0),
        qualityLabel = optString("qualityLabel"),
        researchConfidenceScore = optInt("researchConfidenceScore", 0),
        researchConfidenceLabel = optString("researchConfidenceLabel"),
        evidenceGroups = optJSONArray("evidenceGroups").toStringList(),
        conflictWarnings = optJSONArray("conflictWarnings").toStringList(),
        synthesisGuidance = optJSONArray("synthesisGuidance").toStringList(),
        triggerReasons = optJSONArray("triggerReasons").toStringList(),
        warnings = optJSONArray("warnings").toStringList(),
        cacheStatus = optString("cacheStatus"),
        closedLoopChecks = optJSONArray("closedLoopChecks").toStringList()
    )
    return trace.takeIf { it.hasContent }
}

private fun ChatWebSearchTrace?.toJsonString(): String {
    if (this == null || !hasContent) return "{}"
    return JSONObject()
        .put("query", query)
        .put("providerLabel", providerLabel)
        .put("triggerModeLabel", triggerModeLabel)
        .put("running", running)
        .put("stageLabel", stageLabel)
        .put("searchedQueries", searchedQueries.toJsonArray())
        .put("directUrls", directUrls.toJsonArray())
        .put("sourceCount", sourceCount)
        .put("elapsedMs", elapsedMs)
        .put("success", success)
        .put("message", message)
        .put("healthScore", healthScore)
        .put("healthLabel", healthLabel)
        .put("qualityScore", qualityScore)
        .put("qualityLabel", qualityLabel)
        .put("researchConfidenceScore", researchConfidenceScore)
        .put("researchConfidenceLabel", researchConfidenceLabel)
        .put("evidenceGroups", evidenceGroups.toJsonArray())
        .put("conflictWarnings", conflictWarnings.toJsonArray())
        .put("synthesisGuidance", synthesisGuidance.toJsonArray())
        .put("triggerReasons", triggerReasons.toJsonArray())
        .put("warnings", warnings.toJsonArray())
        .put("cacheStatus", cacheStatus)
        .put("closedLoopChecks", closedLoopChecks.toJsonArray())
        .toString()
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { index -> optString(index) }
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun List<String>.toJsonArray(): JSONArray =
    JSONArray().also { array -> forEach { value -> array.put(value) } }
