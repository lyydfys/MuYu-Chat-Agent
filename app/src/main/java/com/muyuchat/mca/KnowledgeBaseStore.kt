package com.muyuchat.mca

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline, bounded retrieval for the first knowledge-base release. The schema
 * records an embedding fingerprint and index state so a future native embedding
 * worker can re-index without invalidating documents or chat selections.
 */
class KnowledgeBaseStore(context: Context) {
    private val database = McaRoomDatabase.get(context.applicationContext)
    private val originalSourceDirectory = File(context.applicationContext.filesDir, "knowledge_document_sources_v1")

    fun loadBases(): List<KnowledgeBaseRecord> = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().knowledgeBaseRecords()
    }

    fun create(name: String, description: String = ""): KnowledgeBaseRecord {
        val record = KnowledgeBaseRecord(
            name = name.trim().take(MAX_NAME_CHARS).ifBlank { "Untitled knowledge base" },
            description = description.trim().take(MAX_DESCRIPTION_CHARS)
        )
        upsert(record)
        return record
    }

    fun upsert(record: KnowledgeBaseRecord) = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().upsertKnowledgeBases(listOf(record.toEntity()))
    }

    fun remove(knowledgeBaseId: String) {
        val documentIds = documents(knowledgeBaseId).map(KnowledgeDocumentRecord::id)
        runBlocking(Dispatchers.IO) {
            database.chatSessionDao().deleteKnowledgeBaseCompletely(knowledgeBaseId)
        }
        documentIds.forEach { id ->
            File(originalSourceDirectory, "${knowledgeDocumentSha256(id)}.txt").delete()
        }
    }

    fun documents(knowledgeBaseId: String): List<KnowledgeDocumentRecord> = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().knowledgeDocumentRecords(knowledgeBaseId)
    }

    fun importDocument(
        knowledgeBaseId: String,
        title: String,
        text: String,
        source: String = "local"
    ): KnowledgeDocumentRecord {
        val prepared = KnowledgeDocumentImportCodec.prepare(knowledgeBaseId, title, text, source)
        val existing = documents(knowledgeBaseId).firstOrNull { it.id == prepared.document.id }
        val document = prepared.document.copy(createdAt = existing?.createdAt ?: prepared.document.createdAt)
        // Retain the original UTF-8 source before committing its searchable projection. A
        // failed Room write can be retried with the same document id without losing the source.
        retainOriginalSource(document.id, prepared.originalText)
        runBlocking(Dispatchers.IO) {
            database.chatSessionDao().replaceKnowledgeDocument(
                document = document.toEntity(),
                chunks = prepared.chunks.map { it.toEntity() }
            )
        }
        return document
    }

    fun originalDocumentText(documentId: String): String? {
        val file = File(originalSourceDirectory, "${knowledgeDocumentSha256(documentId)}.txt")
        return if (file.isFile) file.inputStream().use(KnowledgeDocumentImportCodec::readText) else null
    }

    private fun retainOriginalSource(documentId: String, text: String) {
        check(originalSourceDirectory.isDirectory || originalSourceDirectory.mkdirs()) {
            "Unable to create the knowledge document source directory."
        }
        val target = File(originalSourceDirectory, "${knowledgeDocumentSha256(documentId)}.txt")
        val pending = File(originalSourceDirectory, ".${target.name}.${UUID.randomUUID()}.pending")
        try {
            FileOutputStream(pending).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            pending.delete()
        }
    }

    fun deleteDocument(documentId: String) {
        runBlocking(Dispatchers.IO) {
            database.chatSessionDao().deleteKnowledgeDocumentCompletely(
                documentId = documentId,
                updatedAt = System.currentTimeMillis()
            )
        }
        File(originalSourceDirectory, "${knowledgeDocumentSha256(documentId)}.txt").delete()
    }

    fun selectedKnowledgeBaseIds(chatSessionId: String): Set<String> = runBlocking(Dispatchers.IO) {
        database.chatSessionDao().knowledgeBaseIdsForChat(chatSessionId).toSet()
    }

    fun setSelectedKnowledgeBaseIds(chatSessionId: String, ids: Set<String>) = runBlocking(Dispatchers.IO) {
        require(ids.size <= MAX_SELECTED_KNOWLEDGE_BASES) {
            "At most $MAX_SELECTED_KNOWLEDGE_BASES knowledge bases can be selected."
        }
        require(ids.all { it.isNotBlank() }) {
            "Knowledge base IDs must not be blank."
        }
        val boundedKnowledgeBaseIds = ids.asSequence()
            .distinct()
            .sorted()
            .take(MAX_SELECTED_KNOWLEDGE_BASES)
            .toList()
        database.chatSessionDao().replaceKnowledgeBindings(
            chatSessionId = chatSessionId,
            knowledgeBaseIds = boundedKnowledgeBaseIds
        )
    }

    fun retrieve(
        knowledgeBaseIds: Set<String>,
        query: String,
        maxChunks: Int = DEFAULT_MAX_CHUNKS,
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET
    ): KnowledgeRetrieval {
        if (knowledgeBaseIds.isEmpty() || query.isBlank() || maxChunks <= 0 || tokenBudget <= 0) {
            return KnowledgeRetrieval()
        }
        val boundedKnowledgeBaseIds = knowledgeBaseIds
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .take(MAX_SELECTED_KNOWLEDGE_BASES)
            .toList()
        val ranked = runBlocking(Dispatchers.IO) {
            var cursor: KnowledgeChunkRecord? = null
            var topCandidates = emptyList<KnowledgeLexicalRetriever.RankedChunk>()

            while (true) {
                val page = database.chatSessionDao().knowledgeChunkPageForBaseRecords(
                    knowledgeBaseIds = boundedKnowledgeBaseIds,
                    afterKnowledgeBaseId = cursor?.knowledgeBaseId,
                    afterDocumentId = cursor?.documentId,
                    afterPosition = cursor?.position,
                    afterId = cursor?.id,
                    limit = RETRIEVAL_PAGE_SIZE
                )
                if (page.isEmpty()) break

                topCandidates = KnowledgeLexicalRetriever.mergeTopRanked(
                    existing = topCandidates,
                    incoming = KnowledgeLexicalRetriever.rank(query, page),
                    limit = MAX_RANKED_CANDIDATES
                )
                cursor = page.last()
                if (page.size < RETRIEVAL_PAGE_SIZE) break
            }
            topCandidates
        }
        // Read the names once, on the same IO dispatcher, so a trace can keep naming the base and
        // the document after they are deleted. Empty maps are safe: the source keeps null names and
        // the panel falls back to its readable placeholder.
        val sourceNames = runBlocking(Dispatchers.IO) {
            val bases = runCatching {
                database.chatSessionDao().knowledgeBaseRecords().associate { it.id to it.name }
            }.getOrDefault(emptyMap())
            val documents = runCatching {
                boundedKnowledgeBaseIds
                    .flatMap { baseId -> database.chatSessionDao().knowledgeDocumentRecords(baseId) }
                    .associate { it.id to it.title }
            }.getOrDefault(emptyMap())
            bases to documents
        }
        val baseNames = sourceNames.first
        val documentNames = sourceNames.second
        var used = 0
        val selected = mutableListOf<KnowledgeChunkRecord>()
        val skipped = mutableListOf<String>()
        ranked.forEach { candidate ->
            val cost = candidate.chunk.estimatedTokens.coerceAtLeast(1)
            if (selected.size < maxChunks && cost <= tokenBudget - used) {
                selected += candidate.chunk
                used += cost
            } else {
                skipped += candidate.chunk.id
            }
        }
        val context = selected.takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { chunk ->
                "[Knowledge]\n${chunk.content}"
            }
            .orEmpty()
        val selectedIds = selected.mapTo(hashSetOf()) { it.id }
        val skippedIds = skipped.toSet()
        return KnowledgeRetrieval(
            chunks = selected,
            context = context,
            estimatedTokens = used,
            skippedChunkIds = skipped,
            selectedSources = ranked.filter { it.chunk.id in selectedIds }
                .map { candidate -> candidate.toSource("lexical_match", baseNames, documentNames) },
            skippedSources = ranked.filter { it.chunk.id in skippedIds }
                .map { candidate -> candidate.toSource("budget_or_limit", baseNames, documentNames) }
        )
    }

    private fun KnowledgeLexicalRetriever.RankedChunk.toSource(
        reason: String,
        baseNames: Map<String, String>,
        documentNames: Map<String, String>
    ): KnowledgeSource =
        KnowledgeSource(
            knowledgeBaseId = chunk.knowledgeBaseId,
            documentId = chunk.documentId,
            chunkId = chunk.id,
            excerpt = chunk.content,
            lexicalScore = score,
            estimatedTokens = chunk.estimatedTokens.coerceAtLeast(1),
            reason = reason,
            baseName = baseNames[chunk.knowledgeBaseId]?.takeIf { it.isNotBlank() },
            documentName = documentNames[chunk.documentId]?.takeIf { it.isNotBlank() }
        )

    fun markEmbeddingModel(knowledgeBaseId: String, fingerprint: String?) {
        val current = loadBases().firstOrNull { it.id == knowledgeBaseId } ?: return
        val normalized = fingerprint?.trim()?.takeIf { it.isNotBlank() }
        upsert(
            current.copy(
                embeddingModelFingerprint = normalized,
                indexState = if (normalized == current.embeddingModelFingerprint) {
                    current.indexState
                } else {
                    KnowledgeIndexState.REINDEX_REQUIRED
                },
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private companion object {
        const val MAX_NAME_CHARS = 96
        const val MAX_DESCRIPTION_CHARS = 512
        const val DEFAULT_MAX_CHUNKS = 4
        const val DEFAULT_TOKEN_BUDGET = 768
        const val MAX_RANKED_CANDIDATES = 64
        const val MAX_SELECTED_KNOWLEDGE_BASES = 32
        const val RETRIEVAL_PAGE_SIZE = 256
    }
}
