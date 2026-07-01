package com.muyuchat.mca

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
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
            projectId = optString("projectId").takeIf { it.isNotBlank() }
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
                imageAttachments = json.optJSONArray("imageAttachments").toImageAttachments()
            )
        }
    }

    private fun JSONObject.optRole(): Role =
        runCatching { Role.valueOf(optString("role", Role.USER.name).uppercase()) }
            .getOrDefault(Role.USER)
}

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class, ImageAssetEntity::class],
    version = 7,
    exportSchema = false
)
abstract class McaRoomDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao

    companion object {
        @Volatile
        private var instance: McaRoomDatabase? = null

        fun get(context: Context): McaRoomDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    McaRoomDatabase::class.java,
                    "mca.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_6, MIGRATION_3_6, MIGRATION_4_6, MIGRATION_5_6)
                    .addMigrations(MIGRATION_6_7)
                    .build()
                    .also { instance = it }
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
                projectId = session.projectId
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
}

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val pinned: Boolean,
    val manualTitle: Boolean,
    val updatedAt: Long,
    val projectId: String? = null
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
    val reasoningContent: String = "",
    val reasoningDurationMs: Long = 0L,
    val imageAttachmentsJson: String = "[]"
)

private fun ChatSessionRecord.toEntity(): ChatSessionEntity =
    ChatSessionEntity(
        id = id,
        title = title,
        pinned = pinned,
        manualTitle = manualTitle,
        updatedAt = updatedAt,
        projectId = projectId
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
        imageAttachmentsJson = imageAttachments.toJsonArrayString(includeInlineData = false)
    )

private fun ChatMessageEntity.toChatMessage(): ChatMessage =
    ChatMessage(
        role = runCatching { Role.valueOf(role) }.getOrDefault(Role.USER),
        content = content,
        createdAt = createdAt,
        tokenCount = tokenCount,
        reasoningContent = reasoningContent,
        reasoningDurationMs = reasoningDurationMs,
        imageAttachments = runCatching { JSONArray(imageAttachmentsJson).toImageAttachments() }.getOrDefault(emptyList())
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
