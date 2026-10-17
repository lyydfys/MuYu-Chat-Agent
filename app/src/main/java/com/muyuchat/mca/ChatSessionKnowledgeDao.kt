package com.muyuchat.mca

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

private const val MAX_KNOWLEDGE_BASE_IDS_PER_ROOM_OPERATION = 32
private const val MAX_KNOWLEDGE_CHUNKS_PER_ROOM_PAGE = 256

interface ChatSessionKnowledgeDao {

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
