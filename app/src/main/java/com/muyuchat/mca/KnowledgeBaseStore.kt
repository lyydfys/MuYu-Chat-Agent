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

data class KnowledgeBaseRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val embeddingModelFingerprint: String? = null,
    val indexState: KnowledgeIndexState = KnowledgeIndexState.LEXICAL_READY,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        require(name.isNotBlank()) { "Knowledge base name is required." }
    }
}

enum class KnowledgeIndexState {
    LEXICAL_READY,
    EMBEDDING_PENDING,
    EMBEDDING_READY,
    REINDEX_REQUIRED,
    FAILED
}

data class KnowledgeDocumentRecord(
    val id: String,
    val knowledgeBaseId: String,
    val title: String,
    val source: String,
    val contentHash: String,
    val contentLength: Int,
    val chunkCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class KnowledgeChunkRecord(
    val id: String,
    val knowledgeBaseId: String,
    val documentId: String,
    val position: Int,
    val content: String,
    val contentHash: String,
    val estimatedTokens: Int
)

data class KnowledgeRetrieval(
    val chunks: List<KnowledgeChunkRecord> = emptyList(),
    val context: String = "",
    val estimatedTokens: Int = 0,
    val skippedChunkIds: List<String> = emptyList(),
    val selectedSources: List<KnowledgeSource> = emptyList(),
    val skippedSources: List<KnowledgeSource> = emptyList()
)

data class KnowledgeSource(
    val knowledgeBaseId: String,
    val documentId: String,
    val chunkId: String,
    val excerpt: String,
    val lexicalScore: Int,
    val estimatedTokens: Int,
    val reason: String
)

internal data class PreparedKnowledgeDocument(
    val document: KnowledgeDocumentRecord,
    val chunks: List<KnowledgeChunkRecord>,
    val originalText: String
)

internal object KnowledgeDocumentImportCodec {
    const val MAX_SOURCE_BYTES = 1_048_576

    suspend fun readTextCancellable(input: InputStream): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { input.close() } }
        try {
            val text = readText(input) {
                if (!continuation.isActive) throw CancellationException("Knowledge document read cancelled.")
            }
            if (continuation.isActive) continuation.resume(text)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    fun readText(input: InputStream): String {
        return readText(input, beforeRead = {})
    }

    private fun readText(input: InputStream, beforeRead: () -> Unit): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            beforeRead()
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= MAX_SOURCE_BYTES - output.size()) { "知识库文件超过 1 MiB，请拆分后导入。" }
            output.write(buffer, 0, count)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray())).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("知识库文档不是有效的 UTF-8 文本，请转换编码后重试。", error)
        }
    }

    fun prepare(
        knowledgeBaseId: String,
        title: String,
        text: String,
        source: String = "local",
        now: Long = System.currentTimeMillis()
    ): PreparedKnowledgeDocument {
        require(knowledgeBaseId.isNotBlank()) { "Knowledge base ID must not be blank." }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) { "知识库文件超过 1 MiB，请拆分后导入。" }
        require('\u0000' !in text) { "知识库文档包含二进制空字节，请选择文本文件。" }
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").trim()
        require(normalized.isNotBlank()) { "知识库文档为空。" }
        val hash = knowledgeDocumentSha256(normalized)
        val documentId = "$knowledgeBaseId:$hash"
        val chunks = KnowledgeChunker.chunk(knowledgeBaseId, documentId, normalized, hash)
        require(chunks.isNotEmpty()) { "知识库文档没有可索引的文本。" }
        return PreparedKnowledgeDocument(
            document = KnowledgeDocumentRecord(
                id = documentId,
                knowledgeBaseId = knowledgeBaseId,
                title = title.trim().take(96).ifBlank { "Imported document" },
                source = source.trim().take(128).ifBlank { "local" },
                contentHash = hash,
                contentLength = normalized.length,
                chunkCount = chunks.size,
                createdAt = now,
                updatedAt = now
            ),
            chunks = chunks,
            originalText = text
        )
    }
}

private fun knowledgeDocumentSha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }

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
                .map { candidate -> candidate.toSource("lexical_match") },
            skippedSources = ranked.filter { it.chunk.id in skippedIds }
                .map { candidate -> candidate.toSource("budget_or_limit") }
        )
    }

    private fun KnowledgeLexicalRetriever.RankedChunk.toSource(reason: String): KnowledgeSource =
        KnowledgeSource(
            knowledgeBaseId = chunk.knowledgeBaseId,
            documentId = chunk.documentId,
            chunkId = chunk.id,
            excerpt = chunk.content,
            lexicalScore = score,
            estimatedTokens = chunk.estimatedTokens.coerceAtLeast(1),
            reason = reason
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

object KnowledgeChunker {
    private const val TARGET_CHARS = 1_200
    private const val OVERLAP_CHARS = 160

    fun chunk(
        knowledgeBaseId: String,
        documentId: String,
        text: String,
        contentHash: String
    ): List<KnowledgeChunkRecord> {
        val result = mutableListOf<KnowledgeChunkRecord>()
        var start = 0
        var position = 0
        while (start < text.length) {
            var end = (start + TARGET_CHARS).coerceAtMost(text.length)
            if (end < text.length) {
                val preferredBreak = text.lastIndexOfAny(charArrayOf('\n', ' ', '。', '！', '？', '.', '!', '?'), end - 1)
                if (preferredBreak > start + TARGET_CHARS / 2) end = preferredBreak + 1
            }
            val content = text.substring(start, end).trim()
            if (content.isNotBlank()) {
                result += KnowledgeChunkRecord(
                    id = "$documentId:$position",
                    knowledgeBaseId = knowledgeBaseId,
                    documentId = documentId,
                    position = position,
                    content = content,
                    contentHash = sha256(content),
                    estimatedTokens = WorldBookResolver.estimateTokens(content)
                )
                position++
            }
            if (end >= text.length) break
            start = (end - OVERLAP_CHARS).coerceAtLeast(start + 1)
        }
        return result
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

object KnowledgeLexicalRetriever {
    data class RankedChunk(val chunk: KnowledgeChunkRecord, val score: Int)

    private val rankedChunkOrder: Comparator<RankedChunk> =
        compareByDescending<RankedChunk> { it.score }
            .thenBy { it.chunk.documentId }
            .thenBy { it.chunk.position }
            .thenBy { it.chunk.id }

    fun rank(query: String, chunks: List<KnowledgeChunkRecord>): List<RankedChunk> {
        val queryTerms = terms(query)
        if (queryTerms.isEmpty()) return emptyList()
        return chunks.asSequence()
            .map { chunk -> RankedChunk(chunk, score(queryTerms, terms(chunk.content))) }
            .filter { it.score > 0 }
            .sortedWith(rankedChunkOrder)
            .toList()
    }

    fun mergeTopRanked(
        existing: List<RankedChunk>,
        incoming: List<RankedChunk>,
        limit: Int
    ): List<RankedChunk> =
        if (limit <= 0) {
            emptyList()
        } else {
            (existing + incoming)
                .sortedWith(rankedChunkOrder)
                .take(limit)
        }

    fun terms(text: String): Set<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val result = linkedSetOf<String>()
        val latin = StringBuilder()
        fun flushLatin() {
            if (latin.length >= 2) result += latin.toString()
            latin.clear()
        }
        normalized.forEach { character ->
            if (character.isLetterOrDigit() && !isCjk(character)) {
                latin.append(character)
            } else {
                flushLatin()
            }
        }
        flushLatin()
        normalized.windowed(size = 2, step = 1, partialWindows = false)
            .filter { it.all(::isCjk) }
            .forEach { result += it }
        normalized.filter(::isCjk).forEach { result += it.toString() }
        return result
    }

    private fun score(queryTerms: Set<String>, chunkTerms: Set<String>): Int =
        queryTerms.sumOf { term ->
            when {
                term in chunkTerms && term.length >= 3 -> 6
                term in chunkTerms && term.length == 2 -> 3
                term in chunkTerms -> 1
                // Keep the first release offline and deterministic, but tolerate common
                // inflection/derivation differences (refund/refunded, model/models). This
                // avoids reporting an empty knowledge result when the user changes grammar.
                term.length >= 4 && chunkTerms.any { candidate ->
                    candidate.length >= 4 &&
                        (candidate.startsWith(term) || term.startsWith(candidate))
                } -> 2
                else -> 0
            }
        }

    private fun isCjk(character: Char): Boolean =
        character.code in 0x2E80..0x9FFF ||
            character.code in 0xAC00..0xD7AF ||
            character.code in 0x3040..0x30FF
}

internal fun KnowledgeBaseRecord.toEntity(): KnowledgeBaseEntity = KnowledgeBaseEntity(
    id = id,
    name = name,
    description = description,
    embeddingModelFingerprint = embeddingModelFingerprint,
    indexState = indexState.name,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun KnowledgeBaseEntity.toRecord(): KnowledgeBaseRecord = KnowledgeBaseRecord(
    id = id,
    name = name,
    description = description,
    embeddingModelFingerprint = embeddingModelFingerprint,
    indexState = runCatching { KnowledgeIndexState.valueOf(indexState) }.getOrDefault(KnowledgeIndexState.LEXICAL_READY),
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun KnowledgeDocumentRecord.toEntity(): KnowledgeDocumentEntity = KnowledgeDocumentEntity(
    id = id,
    knowledgeBaseId = knowledgeBaseId,
    title = title,
    source = source,
    contentHash = contentHash,
    contentLength = contentLength,
    chunkCount = chunkCount,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun KnowledgeDocumentEntity.toRecord(): KnowledgeDocumentRecord = KnowledgeDocumentRecord(
    id = id,
    knowledgeBaseId = knowledgeBaseId,
    title = title,
    source = source,
    contentHash = contentHash,
    contentLength = contentLength,
    chunkCount = chunkCount,
    createdAt = createdAt,
    updatedAt = updatedAt
)

internal fun KnowledgeChunkRecord.toEntity(): KnowledgeChunkEntity = KnowledgeChunkEntity(
    id = id,
    knowledgeBaseId = knowledgeBaseId,
    documentId = documentId,
    position = position,
    content = content,
    contentHash = contentHash,
    estimatedTokens = estimatedTokens
)

internal fun KnowledgeChunkEntity.toRecord(): KnowledgeChunkRecord = KnowledgeChunkRecord(
    id = id,
    knowledgeBaseId = knowledgeBaseId,
    documentId = documentId,
    position = position,
    content = content,
    contentHash = contentHash,
    estimatedTokens = estimatedTokens
)
