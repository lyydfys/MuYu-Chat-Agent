package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRetrievalTest {
    @Test
    fun chunkerBoundsLargeDocumentsAndKeepsDeterministicOverlap() {
        val chunks = KnowledgeChunker.chunk(
            knowledgeBaseId = "base-1",
            documentId = "doc-1",
            text = "a".repeat(2_800),
            contentHash = "document-hash"
        )

        assertEquals(3, chunks.size)
        assertEquals(listOf(0, 1, 2), chunks.map { it.position })
        assertEquals(listOf("doc-1:0", "doc-1:1", "doc-1:2"), chunks.map { it.id })
        assertTrue(chunks.all { it.content.length <= 1_200 })
        assertTrue(chunks.all { it.estimatedTokens > 0 && it.contentHash.isNotBlank() })
        assertEquals(chunks[0].content.takeLast(160), chunks[1].content.take(160))
        assertEquals(chunks[1].content.takeLast(160), chunks[2].content.take(160))
    }

    @Test
    fun lexicalRetrieverMatchesChineseAndLatinTermsWithoutReturningNoise() {
        val relevant = chunk(
            id = "relevant",
            documentId = "travel",
            content = "\u897f\u6e56\u4f4d\u4e8e\u676d\u5dde, also known as West Lake in Hangzhou."
        )
        val unrelated = chunk(
            id = "unrelated",
            documentId = "history",
            content = "\u6545\u5bab\u4f4d\u4e8e\u5317\u4eac, also known as the Forbidden City."
        )

        val ranked = KnowledgeLexicalRetriever.rank(
            query = "\u676d\u5dde\u897f\u6e56 hangzhou",
            chunks = listOf(unrelated, relevant)
        )

        assertEquals(listOf("relevant"), ranked.map { it.chunk.id })
        assertTrue(ranked.single().score > 0)
    }

    @Test
    fun termNormalizationHandlesFullWidthLatinAndCjkBigrams() {
        val terms = KnowledgeLexicalRetriever.terms("\uFF21\uFF29 Agent \u676d\u5dde\u897f\u6e56")

        assertTrue("ai" in terms)
        assertTrue("agent" in terms)
        assertTrue("\u676d\u5dde" in terms)
        assertTrue("\u5dde\u897f" in terms)
        assertTrue("\u897f\u6e56" in terms)
        assertFalse("a" in terms)
    }

    @Test
    fun equalScoresUseDocumentAndPositionForStableOrdering() {
        val ranked = KnowledgeLexicalRetriever.rank(
            query = "stable",
            chunks = listOf(
                chunk("b", "doc-b", 0, "stable value"),
                chunk("a2", "doc-a", 2, "stable value"),
                chunk("a1", "doc-a", 1, "stable value")
            )
        )

        assertEquals(listOf("a1", "a2", "b"), ranked.map { it.chunk.id })
    }

    private fun chunk(
        id: String,
        documentId: String,
        position: Int = 0,
        content: String
    ): KnowledgeChunkRecord = KnowledgeChunkRecord(
        id = id,
        knowledgeBaseId = "base-1",
        documentId = documentId,
        position = position,
        content = content,
        contentHash = "hash-$id",
        estimatedTokens = WorldBookResolver.estimateTokens(content)
    )
}
