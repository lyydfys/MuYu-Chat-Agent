package com.muyuchat.mca

import android.content.Context
import android.net.Uri
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
import com.muyuchat.core.engine.deduplicateVisionAttachments
import com.muyuchat.core.engine.visionDeduplicationKey
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGenerationMetrics
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
import java.security.MessageDigest

private const val MAX_PERSISTED_VISION_HASH_BYTES = 64L * 1024L * 1024L
private const val DEFAULT_HASH_BUFFER_BYTES = 32 * 1024
internal const val DELETE_CONSUMED_MEMORY_TURNS_SQL =
    "DELETE FROM assistant_memory_turns WHERE assistantId = :assistantId " +
        "AND isSummarized = 0 AND id IN (:ids)"
internal const val DELETE_MEMORY_TURNS_FOR_SESSIONS_SQL =
    "DELETE FROM assistant_memory_turns WHERE sessionId IN (:sessionIds)"
internal const val MEMORY_SESSION_OWNER_EXISTS_SQL =
    "SELECT COUNT(*) > 0 FROM chat_sessions WHERE id = :chatSessionId " +
        "AND assistantId = :assistantId AND assistantSnapshotJson IS NOT NULL " +
        "AND mixedAssistantHistory = 0"
internal const val MEMORY_ASSISTANT_REPLY_EXISTS_SQL =
    "SELECT COUNT(*) > 0 FROM chat_messages WHERE sessionId = :chatSessionId " +
        "AND messageId = :messageId AND role = 'ASSISTANT'"
internal const val DELETE_STALE_PENDING_MEMORY_TURNS_SQL =
    "DELETE FROM assistant_memory_turns WHERE isSummarized = 0 AND NOT EXISTS (" +
        "SELECT 1 FROM chat_messages INNER JOIN chat_sessions " +
        "ON chat_sessions.id = chat_messages.sessionId " +
        "WHERE chat_messages.sessionId = assistant_memory_turns.sessionId " +
        "AND chat_messages.messageId = assistant_memory_turns.id " +
        "AND chat_messages.role = 'ASSISTANT' " +
        "AND (chat_sessions.assistantId = assistant_memory_turns.assistantId " +
        "OR chat_sessions.mixedAssistantHistory = 1))"

internal object GeneratedImagePersistenceAssociation {
    /**
     * A batch is persisted only when one retained request references its whole
     * asset set. This prevents committing a partial batch beside a message that
     * claims the complete batch is available.
     */
    fun snapshotReferencesCompleteAssetSet(
        records: List<ChatSessionRecord>,
        assetIds: List<String>
    ): Boolean {
        if (assetIds.isEmpty() || assetIds.any(String::isBlank)) return false
        val requestedIds = assetIds.toSet()
        if (requestedIds.size != assetIds.size) return false

        return records.asSequence()
            .flatMap { it.messages.asSequence() }
            .mapNotNull { it.generatedImageRequest }
            .any { request ->
                request.imageAssetIds.isNotEmpty() &&
                    request.imageAssetIds.none(String::isBlank) &&
                    request.imageAssetIds.toSet() == requestedIds
            }
    }
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

    /** Histories with uncertain persona provenance stay readable but cannot feed automatic memory. */
    fun mixedSessionIds(): Set<String> = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().mixedSessionIds().toSet()
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
        knowledgeBindings: Map<String, Set<String>>,
        deletedSessionIds: Set<String> = emptySet()
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
            },
            deletedSessionIds = deletedSessionIds.toList()
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

    /**
     * Persists all generated image assets and the chat snapshot that references them
     * in one Room transaction. Call from a coroutine so Room I/O does not block
     * the caller thread while waiting for the transaction.
     *
     * Returns false when persistence bounds would discard the request or the
     * retained request does not reference the complete batch; in that case no
     * rows are written.
     */
    suspend fun saveGeneratedImageResult(
        sessions: List<ChatSessionRecord>,
        images: List<ImageAssetRecord>
    ): Boolean = withContext(Dispatchers.IO) {
        val boundedSessions = ChatHistoryPersistenceBounds.bound(sessions)
        if (!GeneratedImagePersistenceAssociation.snapshotReferencesCompleteAssetSet(
                records = boundedSessions,
                assetIds = images.map { it.id }
            )
        ) return@withContext false

        database.chatSessionDao().reconcileSnapshotWithImageAsset(
            records = boundedSessions,
            images = images.map { it.toEntity() }
        )
        true
    }

    suspend fun saveGeneratedImageResult(
        sessions: List<ChatSessionRecord>,
        image: ImageAssetRecord
    ): Boolean = saveGeneratedImageResult(sessions, listOf(image))

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
        val bounded = memories.map { record ->
            requireNotNull(record.boundedForPersistence()) { "Invalid memory record." }
        }
        bounded.groupBy { it.assistantId }.forEach { (assistantId, records) ->
            check(database.chatSessionDao().replaceMemoriesForAssistant(assistantId, records)) {
                "Memory IDs must belong to their assistant."
            }
        }
    }

    fun saveMemories(assistantId: String, memories: List<MemoryRecord>): Boolean =
        runBlocking(Dispatchers.IO) {
            val bounded = memories.map { it.boundedForPersistence() ?: return@runBlocking false }
            database.chatSessionDao().replaceMemoriesForAssistant(assistantId, bounded)
        }

    fun loadMemoryTurns(assistantId: String): List<AssistantMemoryTurnRecord> =
        runBlocking(Dispatchers.IO) {
            database.chatSessionDao().trustedPendingMemoryTurns(
                assistantId,
                AssistantMemoryTurnRecord.MAX_PENDING_FETCH
            ).map { it.toRecord() }
        }

    fun appendMemoryTurn(turn: AssistantMemoryTurnRecord): Boolean = runBlocking(Dispatchers.IO) {
        val bounded = turn.boundedForPersistence() ?: return@runBlocking false
        database.chatSessionDao().appendMemoryTurnForExistingSession(bounded.toEntity())
    }

    fun commitMemorySummary(
        assistantId: String,
        consumedTurnIds: List<String>,
        summary: MemoryRecord
    ): Boolean = runBlocking(Dispatchers.IO) {
        val bounded = summary.boundedForPersistence() ?: return@runBlocking false
        database.chatSessionDao().commitMemorySummary(assistantId, consumedTurnIds, bounded)
    }

    fun upsertMemory(record: MemoryRecord): Boolean = runBlocking(Dispatchers.IO) {
        val bounded = record.boundedForPersistence() ?: return@runBlocking false
        database.chatSessionDao().upsertMemoryRecord(bounded)
    }

    fun deleteMemory(id: String, assistantId: String): Boolean = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().deleteMemoryForAssistant(id, assistantId) > 0
    }

    fun deleteAssistantMemoryData(assistantId: String) = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().deleteAssistantMemoryData(assistantId)
    }

    private suspend fun migrateLegacyJsonIfNeeded(): List<ChatSessionRecord> {
        if (!legacyFile.exists()) return emptyList()
        val records = runCatching {
            val array = JSONArray(legacyFile.readText(Charsets.UTF_8))
            List(array.length()) { index ->
                array.getJSONObject(index).toChatSessionRecord()
            }.sortedForHistoryOffThread()
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
        val repairedOwnership = database.chatSessionDao().repairPersistedAssistantOwnership()
        val ownedRecords = if (repairedOwnership) database.chatSessionDao().loadRecords() else records
        // load() invokes this on Dispatchers.IO. Normalize old JSON snapshots
        // here as well as Room rows so a legacy history never returns one
        // frame with duplicate attachments before the migrated DB is reread.
        // A persisted content:// URI cannot be compared by the shared cheap
        // deduplicator alone: pickers and document providers commonly expose
        // the same bytes through different URI instances. Resolve and hash
        // those streams once per load so old rows are repaired in the database
        // before the first frame is rendered or sent to a vision runner.
        val contentKeyCache = HashMap<String, String?>()
        val normalizedRecords = ownedRecords.map { session ->
            session.copy(
                messages = session.messages.map { message ->
                    val attachments = deduplicatePersistedVisionAttachments(
                        message.imageAttachments,
                        contentKeyCache
                    )
                    if (attachments == message.imageAttachments) message
                    else message.copy(imageAttachments = attachments)
                }
            )
        }
        val boundedRecords = ChatHistoryPersistenceBounds.bound(normalizedRecords)
        if (boundedRecords != ownedRecords) {
            database.chatSessionDao().replaceAll(boundedRecords)
        }
        return if (boundedRecords != ownedRecords) {
            database.chatSessionDao().loadRecords()
        } else {
            boundedRecords
        }
    }

    private fun deduplicatePersistedVisionAttachments(
        attachments: List<ChatImageAttachment>,
        contentKeyCache: MutableMap<String, String?>
    ): List<ChatImageAttachment> {
        if (attachments.size < 2) return attachments
        val seen = LinkedHashSet<String>(attachments.size)
        return attachments.filter { attachment ->
            val source = attachment.uriString.trim()
            val key = if (source.startsWith("content:", ignoreCase = true)) {
                val resolved = if (contentKeyCache.containsKey(source)) {
                    contentKeyCache[source]
                } else {
                    contentUriVisionKey(source).also { contentKeyCache[source] = it }
                }
                resolved ?: attachment.visionDeduplicationKey()
            } else {
                attachment.visionDeduplicationKey()
            }
            seen.add(key)
        }
    }

    /**
     * Returns a byte-identity key for a readable content URI. A null result
     * deliberately falls back to the URI identity: unreadable or oversized
     * providers must never make an attachment disappear silently.
     */
    private fun contentUriVisionKey(source: String): String? {
        val uri = runCatching { Uri.parse(source) }.getOrNull() ?: return null
        if (!uri.scheme.equals("content", ignoreCase = true)) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val buffer = ByteArray(DEFAULT_HASH_BUFFER_BYTES)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_PERSISTED_VISION_HASH_BYTES) return@runCatching null
                    digest.update(buffer, 0, read)
                }
            } ?: return@runCatching null
            "bytes:" + digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }.getOrNull()
    }

    private fun JSONObject.toChatSessionRecord(): ChatSessionRecord =
        ChatSessionRecord(
            id = optString("id"),
            title = optString("title", "新对话"),
            messages = optJSONArray("messages").toChatMessages(optString("id")),
            pinned = optBoolean("pinned", false),
            manualTitle = optBoolean("manualTitle", false),
            updatedAt = optLong("updatedAt", System.currentTimeMillis()),
            projectId = optString("projectId").takeIf { it.isNotBlank() },
            assistantId = optString("assistantId").takeIf { it.isNotBlank() },
            assistantSnapshot = AssistantConversationSnapshot.fromJsonOrNull(
                optString("assistantSnapshotJson").takeIf { it.isNotBlank() }
                    ?: optJSONObject("assistantSnapshot")?.toString()
            ),
            mixedAssistantHistory = optBoolean("mixedAssistantHistory", false),
            modelMode = optString("modelMode").takeIf { it.isNotBlank() },
            modelId = optString("modelId").takeIf { it.isNotBlank() },
            contextSummaries = contextSummariesFromJson(optString("contextSummariesJson")),
            appearanceOverride = ChatAppearance.fromJsonOrNull(
                optJSONObject("appearanceOverride")?.toString()
                    ?: optString("appearanceOverrideJson").takeIf { it.isNotBlank() }
            )
        )

    private fun JSONArray?.toChatMessages(sessionId: String): List<ChatMessage> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val json = getJSONObject(index)
            ChatMessage(
                id = json.optString("id").takeIf { it.isNotBlank() }
                    ?: "$sessionId:$index:${json.optLong("createdAt")}",
                role = json.optRole(),
                content = json.optString("content"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                tokenCount = if (json.isNull("tokenCount")) null else json.optInt("tokenCount"),
                reasoningContent = json.optString("reasoningContent"),
                reasoningDurationMs = json.optLong("reasoningDurationMs", 0L),
                imageAttachments = json.optJSONArray("imageAttachments").chatMessageImageAttachments(),
                sourceReferences = json.optJSONArray("sourceReferences").chatMessageSourceReferences(),
                webSearchTrace = json.optJSONObject("webSearchTrace").chatMessageWebSearchTrace(),
                generationMetrics = ChatGenerationMetrics.fromJson(json.optJSONObject("generationMetrics")),
                generatedImageRequest = ChatGeneratedImageRequest.fromJsonOrNull(
                    json.optJSONObject("generatedImageRequest")
                ),
                pinned = json.optBoolean("pinned", false),
                contextAssemblyTraceJson = json.optJSONObject("contextAssemblyTrace")?.toString()
                    ?: json.optString("contextAssemblyTraceJson").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun JSONObject.optRole(): Role =
        runCatching { Role.valueOf(optString("role", Role.USER.name).uppercase()) }
            .getOrDefault(Role.USER)
}

/**
 * Conversation history order, computed off the caller's thread.
 *
 * The comparator itself lives in DisplayOrdering so it cannot drift from the ordering the
 * rest of the app uses; this wrapper only keeps the legacy-migration sort off the calling
 * thread, which is why it stays suspend.
 */
private suspend fun List<ChatSessionRecord>.sortedForHistoryOffThread(): List<ChatSessionRecord> =
    withContext(Dispatchers.Default) {
        sortedForHistory()
    }
