package com.muyuchat.mca

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.ColumnInfo
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
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

class ChatSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val database = McaRoomDatabase.get(appContext)
    private val legacyFile = File(appContext.filesDir, "chat_sessions.json")

    fun load(): List<ChatSessionRecord> = runBlocking(Dispatchers.IO) {
        runCatching {
            val records = database.chatSessionDao().loadRecords()
            if (records.isNotEmpty()) {
                records
            } else {
                migrateLegacyJsonIfNeeded()
            }
        }.getOrElse {
            runCatching { migrateLegacyJsonIfNeeded() }.getOrElse { emptyList() }
        }
    }

    fun save(sessions: List<ChatSessionRecord>) = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().replaceAll(sessions)
    }

    fun loadImages(): List<ImageAssetRecord> = runBlocking(Dispatchers.IO) {
        runCatching { database.chatSessionDao().loadImageRecords() }
            .getOrElse { emptyList() }
    }

    fun saveImages(images: List<ImageAssetRecord>) = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().replaceImages(images)
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
        }.getOrElse {
            emptyList()
        }
        if (records.isNotEmpty()) {
            database.chatSessionDao().replaceAll(records)
        }
        runCatching {
            if (!legacyFile.delete()) {
                legacyFile.renameTo(File(legacyFile.parentFile, "${legacyFile.name}.migrated"))
            }
        }
        return records
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
            modelMode = optString("modelMode").takeIf { it.isNotBlank() },
            modelId = optString("modelId").takeIf { it.isNotBlank() }
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
        AssistantEntity::class
    ],
    version = 14,
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
                    .addMigrations(MIGRATION_12_13, MIGRATION_13_14)
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
                    chatSessionId TEXT,
                    projectId TEXT
                )
                """.trimIndent()
            )
            if (existed) {
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO image_assets_new (
                        id, name, uriString, source, prompt, createdAt, sizeBytes, width, height, chatSessionId, projectId
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<ChatSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages")
    suspend fun clearMessages()

    @Query("DELETE FROM chat_sessions")
    suspend fun clearSessions()

    @Query("SELECT * FROM image_assets ORDER BY createdAt DESC")
    suspend fun images(): List<ImageAssetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<ImageAssetEntity>)

    @Query("DELETE FROM image_assets")
    suspend fun clearImages()

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssistants(assistants: List<AssistantEntity>)

    @Query("DELETE FROM assistants")
    suspend fun clearAssistants()

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
                modelMode = session.modelMode,
                modelId = session.modelId
            )
        }

    @Transaction
    suspend fun replaceAll(records: List<ChatSessionRecord>) {
        clearMessages()
        clearSessions()
        if (records.isEmpty()) return
        insertSessions(records.map { it.toEntity() })
        insertMessages(
            records.flatMap { session ->
                session.messages.mapIndexed { index, message ->
                    message.toEntity(session.id, index)
                }
            }
        )
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
    val modelMode: String? = null,
    val modelId: String? = null
)

@Entity(
    tableName = "image_assets",
    indices = [Index("createdAt"), Index("chatSessionId"), Index("projectId")]
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
    val memoryEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val fileContextEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
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

private fun ChatSessionRecord.toEntity(): ChatSessionEntity =
    ChatSessionEntity(
        id = id,
        title = title,
        pinned = pinned,
        manualTitle = manualTitle,
        updatedAt = updatedAt,
        projectId = projectId,
        assistantId = assistantId,
        modelMode = modelMode,
        modelId = modelId
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
        memoryEnabled = memoryEnabled,
        webSearchEnabled = webSearchEnabled,
        fileContextEnabled = fileContextEnabled,
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
        memoryEnabled = memoryEnabled,
        webSearchEnabled = webSearchEnabled,
        fileContextEnabled = fileContextEnabled,
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
