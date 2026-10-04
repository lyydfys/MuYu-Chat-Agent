package com.muyuchat.mca

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * Room query rules for chat history: the @Dao interface.
 *
 * Moved out of `ChatSessionStore.kt` verbatim: every @Query/@Insert/@Upsert/@Transaction declaration
 * and its SQL string is byte-identical to the original (string-literal multiset unchanged). The DAO
 * return types are the @Entity contracts in `ChatSessionStoreEntities.kt`.
 */
private const val MAX_KNOWLEDGE_BASE_IDS_PER_ROOM_OPERATION = 32
private const val MAX_KNOWLEDGE_CHUNKS_PER_ROOM_PAGE = 256

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY pinned DESC, updatedAt DESC")
    suspend fun sessions(): List<ChatSessionEntity>

    @Query("SELECT id FROM chat_sessions WHERE mixedAssistantHistory = 1")
    suspend fun mixedSessionIds(): List<String>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun sessionById(sessionId: String): ChatSessionEntity?

    @Query("UPDATE chat_sessions SET modelId = :newModelId WHERE modelId = :oldModelId")
    suspend fun migrateSessionModelId(oldModelId: String, newModelId: String): Int

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

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun memoryById(id: String): MemoryEntity?

    @Query("SELECT id FROM memories WHERE assistantId != :assistantId AND id IN (:ids)")
    suspend fun memoryIdsOwnedByOtherAssistant(assistantId: String, ids: List<String>): List<String>

    @Query("DELETE FROM memories WHERE assistantId = :assistantId")
    suspend fun clearMemoriesForAssistant(assistantId: String)

    @Query("DELETE FROM assistant_memory_turns WHERE assistantId = :assistantId")
    suspend fun clearMemoryTurnsForAssistant(assistantId: String)

    @Query("DELETE FROM memories WHERE assistantId = :assistantId AND source IN ('automatic', 'auto')")
    suspend fun clearAutomaticMemoriesForAssistant(assistantId: String)

    @Query("DELETE FROM memories WHERE id = :id AND assistantId = :assistantId")
    suspend fun deleteMemoryForAssistant(id: String, assistantId: String): Int

    @Query(
        "SELECT * FROM assistant_memory_turns WHERE assistantId = :assistantId " +
            "AND isSummarized = 0 ORDER BY createdAt ASC, id ASC"
    )
    suspend fun pendingMemoryTurnCandidates(assistantId: String): List<AssistantMemoryTurnEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemoryTurn(turn: AssistantMemoryTurnEntity): Long

    @Query(
        "SELECT * FROM assistant_memory_turns WHERE assistantId = :assistantId " +
            "AND isSummarized = 0 AND id IN (:ids)"
    )
    suspend fun pendingMemoryTurnsById(
        assistantId: String,
        ids: List<String>
    ): List<AssistantMemoryTurnEntity>

    @Query(DELETE_CONSUMED_MEMORY_TURNS_SQL)
    suspend fun deleteConsumedMemoryTurns(assistantId: String, ids: List<String>): Int

    @Query(DELETE_MEMORY_TURNS_FOR_SESSIONS_SQL)
    suspend fun deleteMemoryTurnsForSessions(sessionIds: List<String>): Int

    @Query(DELETE_STALE_PENDING_MEMORY_TURNS_SQL)
    suspend fun deleteStalePendingMemoryTurns(): Int

    @Query("DELETE FROM assistant_memory_turns WHERE assistantId = :assistantId AND isSummarized = 1")
    suspend fun deleteLegacySummarizedMemoryTurns(assistantId: String): Int

    @Query("SELECT * FROM assistants ORDER BY updatedAt DESC")
    suspend fun assistants(): List<AssistantEntity>

    @Query("UPDATE assistants SET defaultModelId = :newModelId WHERE defaultModelId = :oldModelId")
    suspend fun migrateAssistantDefaultModelId(oldModelId: String, newModelId: String): Int

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

    @Query(MEMORY_SESSION_OWNER_EXISTS_SQL)
    suspend fun memorySessionOwnedByAssistant(chatSessionId: String, assistantId: String): Boolean

    @Query(MEMORY_ASSISTANT_REPLY_EXISTS_SQL)
    suspend fun assistantMessageExists(chatSessionId: String, messageId: String): Boolean

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
                mixedAssistantHistory = session.mixedAssistantHistory,
                modelMode = session.modelMode,
                modelId = session.modelId,
                appearanceOverride = ChatAppearance.fromJsonOrNull(session.appearanceJson),
                contextSummaries = contextSummariesFromJson(session.contextSummariesJson)
            )
        }

    @Transaction
    suspend fun repairPersistedAssistantOwnership(): Boolean {
        var changed = false
        sessions().forEach { session ->
            val repaired = AssistantSessionProvenance.resolve(
                persisted = session,
                requested = session,
                messages = messages(session.id)
            ).session
            if (repaired != session) {
                upsertSessions(listOf(repaired))
                changed = true
            }
        }
        return changed
    }

    @Transaction
    suspend fun trustedPendingMemoryTurns(
        assistantId: String,
        limit: Int
    ): List<AssistantMemoryTurnEntity> {
        val sessionById = sessions().associateBy { it.id }
        return pendingMemoryTurnCandidates(assistantId)
            .asSequence()
            .filter { turn ->
                sessionById[turn.sessionId]?.let {
                    AssistantSessionProvenance.isTrustedOwner(it, assistantId)
                } == true
            }
            .take(limit.coerceIn(0, AssistantMemoryTurnRecord.MAX_PENDING_FETCH))
            .toList()
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
            deleteStalePendingMemoryTurns()
            return
        }

        val requestedEntities = records.map { it.toEntity() }
        val liveSessionIds = requestedEntities.map { it.id }
        val persistedSessionsById = sessions().associateBy { it.id }
        val persistedMessagesBySession = messagesForSessions(liveSessionIds).groupBy { it.sessionId }
        val decisions = records.mapIndexed { index, record ->
            AssistantSessionProvenance.resolve(
                persisted = persistedSessionsById[record.id],
                requested = requestedEntities[index],
                messages = record.messages.mapIndexed { position, message ->
                    message.toEntity(record.id, position)
                }
            )
        }
        val sessionEntities = decisions.map { it.session }
        val changedSessions = sessionEntities.filter { session ->
            persistedSessionsById[session.id] != session
        }
        if (changedSessions.isNotEmpty()) {
            upsertSessions(changedSessions)
        }

        records.forEachIndexed { recordIndex, session ->
            val incomingMessages = session.messages.mapIndexed { index, message ->
                message.toEntity(session.id, index)
            }
            val priorMessages = persistedMessagesBySession[session.id].orEmpty()
            val desiredMessages = if (decisions[recordIndex].preservePersistedMessages) {
                priorMessages
            } else {
                incomingMessages
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
        deleteStalePendingMemoryTurns()
    }

    /** Keeps the chat's asset reference and image-library row in the same commit. */
    @Transaction
    suspend fun reconcileSnapshotWithImageAsset(
        records: List<ChatSessionRecord>,
        images: List<ImageAssetEntity>
    ) {
        reconcileSnapshot(records)
        if (images.isNotEmpty()) insertImages(images)
    }

    /** Session snapshot plus binding writes used by the first-message path. */
    @Transaction
    suspend fun replaceAllWithKnowledgeBindings(
        records: List<ChatSessionRecord>,
        bindingSessionIds: List<String>,
        bindings: List<ChatKnowledgeBaseBindingEntity>,
        deletedSessionIds: List<String> = emptyList()
    ) {
        val boundedRecords = ChatHistoryPersistenceBounds.bound(records)
        reconcileSnapshot(boundedRecords)
        val liveSessionIds = boundedRecords.mapTo(hashSetOf()) { it.id }
        val removedIds = deletedSessionIds.distinct().filter { it !in liveSessionIds }
        if (removedIds.isNotEmpty()) deleteMemoryTurnsForSessions(removedIds)
        if (boundedRecords.isEmpty() || bindingSessionIds.isEmpty()) return

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
    suspend fun replaceMemoriesForAssistant(assistantId: String, records: List<MemoryRecord>): Boolean {
        if (assistantId.isBlank() || records.any { it.assistantId != assistantId }) return false
        val ids = records.map { it.id }
        if (ids.distinct().size != ids.size) return false
        if (ids.isNotEmpty() && memoryIdsOwnedByOtherAssistant(assistantId, ids).isNotEmpty()) return false
        clearMemoriesForAssistant(assistantId)
        insertMemories(records.map { it.toEntity() })
        return true
    }

    @Transaction
    suspend fun deleteAssistantMemoryData(assistantId: String) {
        clearMemoriesForAssistant(assistantId)
        clearMemoryTurnsForAssistant(assistantId)
    }

    @Transaction
    suspend fun appendMemoryTurnForExistingSession(turn: AssistantMemoryTurnEntity): Boolean {
        val session = sessionById(turn.sessionId)
        if (session == null || !AssistantSessionProvenance.isTrustedOwner(session, turn.assistantId) ||
            !assistantMessageExists(turn.sessionId, turn.id)) return false
        return insertMemoryTurn(turn) != -1L
    }

    @Transaction
    suspend fun upsertMemoryRecord(record: MemoryRecord): Boolean {
        val existing = memoryById(record.id)
        if (existing != null && existing.assistantId != record.assistantId) return false
        if (existing?.source == "manual" && record.source == "automatic") return false
        insertMemories(listOf(record.toEntity()))
        return true
    }

    @Transaction
    suspend fun commitMemorySummary(
        assistantId: String,
        consumedTurnIds: List<String>,
        summary: MemoryRecord
    ): Boolean {
        val ids = consumedTurnIds.distinct()
        if (assistantId.isBlank() || summary.assistantId != assistantId ||
            summary.source != "automatic" || ids.isEmpty() ||
            ids.size != consumedTurnIds.size || ids.size > AssistantMemoryTurnRecord.MAX_PENDING_FETCH
        ) return false
        val pending = pendingMemoryTurnsById(assistantId, ids)
        if (pending.mapTo(hashSetOf()) { it.id } != ids.toSet()) return false
        if (pending.any { turn ->
                val session = sessionById(turn.sessionId)
                session == null || !AssistantSessionProvenance.isTrustedOwner(session, assistantId) ||
                    !assistantMessageExists(turn.sessionId, turn.id)
            }) return false
        val existing = memoryById(summary.id)
        if (existing != null && (existing.assistantId != assistantId || existing.source != "automatic")) {
            return false
        }
        check(deleteConsumedMemoryTurns(assistantId, ids) == ids.size) {
            "Pending memory turns changed during summary commit."
        }
        deleteLegacySummarizedMemoryTurns(assistantId)
        clearAutomaticMemoriesForAssistant(assistantId)
        insertMemories(listOf(summary.toEntity()))
        return true
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
