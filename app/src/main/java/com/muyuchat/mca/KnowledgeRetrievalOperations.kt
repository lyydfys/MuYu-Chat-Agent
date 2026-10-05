package com.muyuchat.mca

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Offline knowledge retrieval operations (P7/S23), moved verbatim from KnowledgeBaseStore.kt:
 * the bounded chunker and the lexical retriever. Consumed by the store and by the
 * knowledge retrieval tests in the same package.
 */

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
