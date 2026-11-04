package com.muyuchat.mca

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Knowledge-base domain models, import codec and entity mappers (P7/S22), moved verbatim
 * from KnowledgeBaseStore.kt. The store class, chunker and lexical retriever keep their
 * own owners; this file holds the pure declarations they consume.
 */

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
    val reason: String,
    /**
     * Names at selection time, persisted in the context trace so the "上下文来源" panel shows
     * "项目资料库 · 部署说明" instead of two UUIDs — and still does after the base or the document
     * was deleted, which a live lookup alone could not.
     */
    val baseName: String? = null,
    val documentName: String? = null
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

// P7/S22: widened private->internal (consumed by the store body in KnowledgeBaseStore.kt).
internal fun knowledgeDocumentSha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }

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
