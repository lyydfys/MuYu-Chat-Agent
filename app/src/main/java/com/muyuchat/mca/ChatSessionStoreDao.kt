package com.muyuchat.mca

import android.util.Log
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

@Dao
interface ChatSessionDao : ChatSessionKnowledgeDao, ChatSessionMemoryDao {
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

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

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

    @Transaction
    suspend fun loadRecords(): List<ChatSessionRecord> =
        sessions().mapNotNull { session ->
            runCatching {
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
                        SessionJsonOffload.resolve(session.assistantSnapshotJson)
                    ),
                    mixedAssistantHistory = session.mixedAssistantHistory,
                    modelMode = session.modelMode,
                    modelId = session.modelId,
                    appearanceOverride = ChatAppearance.fromJsonOrNull(
                        SessionJsonOffload.resolve(session.appearanceJson)
                    ),
                    contextSummaries = contextSummariesFromJson(
                        SessionJsonOffload.resolve(session.contextSummariesJson)
                    )
                )
            }.onFailure { error ->
                // One malformed row must not hide every readable conversation. The row remains
                // in Room for a later repair/export instead of being silently rewritten away.
                Log.e("MCA-ChatHistory", "session_load_failed id=${session.id}", error)
            }.getOrNull()
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
    suspend fun reconcileSnapshot(
        records: List<ChatSessionRecord>,
        explicitlyDeletedSessionIds: Set<String> = emptySet()
    ) {
        val persistedSessions = sessions()
        val persistedIds = persistedSessions.mapTo(hashSetOf()) { it.id }
        val requestedIds = records.mapTo(hashSetOf()) { it.id }
        val removedIds = sessionsEligibleForDeletion(
            persistedIds = persistedIds,
            requestedIds = requestedIds,
            explicitlyDeletedSessionIds = explicitlyDeletedSessionIds
        )
        if (records.isEmpty()) {
            // An empty UI snapshot is not proof that the user deleted history.  Only an
            // explicit delete may clear rows; a transient load failure must not wipe them.
            if (removedIds.isEmpty()) return
            removedIds.forEach { sessionId ->
                deleteMessagesForSession(sessionId)
                deleteKnowledgeBindingsForChat(sessionId)
                deleteSession(sessionId)
            }
            deleteMemoryTurnsForSessions(removedIds.toList())
            deleteStalePendingMemoryTurns()
            return
        }

        val requestedEntities = records.map { it.toEntity() }
        val persistedSessionsById = persistedSessions.associateBy { it.id }
        val persistedMessagesBySession = messagesForSessions(requestedIds.toList()).groupBy { it.sessionId }
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
        // Missing-from-snapshot rows are preserved unless the deletion was explicit. This
        // protects history from a transient empty/partial UI projection while still honouring
        // user deletions through the destructive mutation path.
        removedIds.forEach { sessionId ->
            deleteMessagesForSession(sessionId)
            deleteKnowledgeBindingsForChat(sessionId)
            deleteSession(sessionId)
        }
        deleteMemoryTurnsForSessions(removedIds.toList())
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
        reconcileSnapshot(boundedRecords, deletedSessionIds.toSet())
        val liveSessionIds = boundedRecords.mapTo(hashSetOf()) { it.id }
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
    suspend fun appendMemoryTurnForExistingSession(turn: AssistantMemoryTurnEntity): Boolean {
        val session = sessionById(turn.sessionId)
        if (session == null || !AssistantSessionProvenance.isTrustedOwner(session, turn.assistantId) ||
            !assistantMessageExists(turn.sessionId, turn.id)) return false
        return insertMemoryTurn(turn) != -1L
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

}
