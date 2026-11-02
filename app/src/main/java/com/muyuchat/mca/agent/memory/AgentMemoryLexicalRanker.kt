package com.muyuchat.mca.agent.memory

import com.muyuchat.mca.KnowledgeLexicalRetriever

/**
 * Offline lexical ranker for memory chunks (plan §7.3).
 *
 * Tokenization is delegated to the accepted knowledge implementation
 * (`KnowledgeLexicalRetriever.terms`), so CJK bigrams, Latin terms, NFKC folding and lowercase
 * handling are byte-for-byte the same code path; the scoring weights are a copy of the same
 * algorithm with an explicit equivalence test against `KnowledgeLexicalRetriever.rank`. There is no
 * regex, no embedding and no network in this file.
 *
 * Ordering is total and deterministic: score descending, then `(spaceId, memoryId, position,
 * chunkId)` ascending. A caller that pages chunks therefore always sees the same ranking.
 */
object AgentMemoryLexicalRanker {
    data class Ranked(val chunk: AgentMemoryChunkRecord, val score: Int)

    private val RANK_ORDER: Comparator<Ranked> =
        compareByDescending<Ranked> { it.score }
            .thenBy { it.chunk.spaceId }
            .thenBy { it.chunk.memoryId }
            .thenBy { it.chunk.position }
            .thenBy { it.chunk.chunkId }

    fun terms(text: String): Set<String> = KnowledgeLexicalRetriever.terms(text)

    fun rank(query: String, chunks: List<AgentMemoryChunkRecord>): List<Ranked> {
        val queryTerms = terms(query)
        if (queryTerms.isEmpty()) return emptyList()
        return chunks.asSequence()
            .map { chunk -> Ranked(chunk, score(queryTerms, terms(chunk.text))) }
            .filter { it.score > 0 }
            .sortedWith(RANK_ORDER)
            .toList()
    }

    /** Same weights as the knowledge lexical retriever; kept here so memory does not import it. */
    fun score(queryTerms: Set<String>, chunkTerms: Set<String>): Int =
        queryTerms.sumOf { term ->
            when {
                term in chunkTerms && term.length >= 3 -> 6
                term in chunkTerms && term.length == 2 -> 3
                term in chunkTerms -> 1
                term.length >= 4 && chunkTerms.any { candidate ->
                    candidate.length >= 4 && (candidate.startsWith(term) || term.startsWith(candidate))
                } -> 2
                else -> 0
            }
        }
}
