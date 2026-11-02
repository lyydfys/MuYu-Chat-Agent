package com.muyuchat.mca.agent.memory

import com.muyuchat.mca.KnowledgeChunkRecord
import com.muyuchat.mca.KnowledgeLexicalRetriever
import com.muyuchat.mca.WorldBookResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retrieval behavior: lexical ranking, citations, cross-space isolation, deterministic ordering and
 * every retrieval budget.
 *
 * The ranker shares its tokenizer with the accepted knowledge implementation and copies its scoring
 * weights; the equivalence test below pins that claim against `KnowledgeLexicalRetriever.rank`.
 */
class AgentMemoryRetrievalTest {
    private val persistence = InMemoryAgentMemoryPersistence()
    private val store = AgentMemoryTestFixtures.store(persistence, AgentMemoryTestFixtures.sequentialIds())

    @Test
    fun `ranking favors exact matches and is deterministic across repeated calls`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "The refund policy is five business days.")
        persistence.seedItem("memory-2", "space-1", "Refund requests are reviewed by support.")
        persistence.seedItem("memory-3", "space-1", "Unrelated note about the office coffee machine.")
        val grant = grant("space-1")

        val first = search(grant, "refund policy")
        val second = search(grant, "refund policy")
        assertEquals(first.citations.map { it.memoryId }, second.citations.map { it.memoryId })
        assertEquals("memory-1", first.citations.first().memoryId)
        assertTrue(first.citations.first().score > 0)
        assertFalse(first.citations.any { it.memoryId == "memory-3" })
        // Citations carry every identity the UI/model needs and never a raw path.
        val citation = first.citations.first()
        assertEquals("space-1", citation.spaceId)
        assertEquals("memory-1:0", citation.chunkId)
        assertEquals(AgentMemoryText.sha256("The refund policy is five business days."), citation.sourceHash)
        assertTrue(citation.contentHash.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(citation.excerpt.length <= AgentMemoryLimits.MAX_EXCERPT_CHARS)
    }

    @Test
    fun `CJK queries match through the shared bigram tokenizer`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "退款政策：五个工作日到账。")
        persistence.seedItem("memory-2", "space-1", "今天的天气不错。")
        val result = search(grant("space-1"), "退款政策")
        assertEquals(listOf("memory-1"), result.citations.map { it.memoryId })
    }

    @Test
    fun `grant scope isolates spaces and never leaks a cross-space chunk`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedSpace("space-2")
        persistence.seedItem("memory-1", "space-1", "alpha budget line")
        persistence.seedItem("memory-2", "space-2", "alpha secret line")
        val scoped = search(grant("space-1"), "alpha")
        assertEquals(listOf("memory-1"), scoped.citations.map { it.memoryId })
        assertTrue(scoped.citations.none { it.spaceId == "space-2" })
    }

    @Test
    fun `a requested space outside the grant is rejected before any query`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedSpace("space-2")
        persistence.seedItem("memory-2", "space-2", "alpha secret line")
        val outcome = store.search(grant("space-1"), "alpha", requestedSpaceId = "space-2", maxItems = 4)
        assertEquals(
            AgentMemoryErrorCodes.SPACE_NOT_SELECTED,
            (outcome as AgentMemorySearchOutcome.Rejected).error.code
        )
    }

    @Test
    fun `requested space inside the grant narrows the search`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedSpace("space-2")
        persistence.seedItem("memory-1", "space-1", "alpha budget line")
        persistence.seedItem("memory-2", "space-2", "alpha plan line")
        val result = search(grant("space-1", "space-2"), "alpha", requestedSpaceId = "space-2")
        assertEquals(listOf("memory-2"), result.citations.map { it.memoryId })
    }

    @Test
    fun `revoked items disappear from retrieval`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "alpha live line")
        persistence.seedItem("memory-2", "space-1", "alpha revoked line", revoked = true)
        val result = search(grant("space-1"), "alpha")
        assertEquals(listOf("memory-1"), result.citations.map { it.memoryId })
    }

    @Test
    fun `citations are deduplicated by memory and keep the best chunk`() = runBlocking {
        persistence.seedSpace("space-1")
        val text = "${"alpha ".repeat(300)}target sentence here ${"filler ".repeat(200)}alpha again"
        persistence.seedItem("memory-1", "space-1", text)
        val result = search(grant("space-1"), "alpha", maxItems = 6)
        assertEquals(1, result.citations.size)
        assertEquals("memory-1", result.citations.first().memoryId)
    }

    @Test
    fun `maxItems bounds the citation list and reports truncation`() = runBlocking {
        persistence.seedSpace("space-1")
        (1..6).forEach { index ->
            persistence.seedItem("memory-$index", "space-1", "alpha note number $index")
        }
        val result = search(grant("space-1"), "alpha", maxItems = 3)
        assertEquals(3, result.citations.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `the context budget counts the delivered excerpt, not the whole chunk`() = runBlocking {
        persistence.seedSpace("space-1")
        // 300 CJK characters per item: the chunk keeps all of them, the citation delivers a
        // MAX_EXCERPT_CHARS excerpt, and the budget must be charged for what is actually delivered.
        val body = ("阿尔法记录段落内容用于预算一致性校验" + "填充文字").repeat(20)
        (1..6).forEach { index -> persistence.seedItem("memory-$index", "space-1", body) }

        val result = search(grant("space-1"), "阿尔法", maxItems = 6)

        assertTrue(result.citations.isNotEmpty())
        result.citations.forEach { citation ->
            assertTrue(
                "an excerpt never exceeds the excerpt bound",
                citation.excerpt.length <= AgentMemoryLimits.MAX_EXCERPT_CHARS
            )
            assertEquals(
                "the citation budget is the token estimate of the excerpt it delivers",
                WorldBookResolver.estimateTokens(citation.excerpt),
                citation.estimatedTokens
            )
        }
        assertEquals(
            "the result total is exactly the sum of the delivered excerpts",
            result.citations.sumOf { it.estimatedTokens },
            result.estimatedTokens
        )
        assertTrue(result.estimatedTokens <= AgentMemoryLimits.MAX_RETURNED_TOKENS)
        assertTrue(
            "the token budget still binds before maxItems with full-size excerpts",
            result.citations.size < 6
        )
        assertTrue(result.truncated)
    }

    @Test
    fun `an empty result is a successful empty citation list`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "completely unrelated content")
        val result = search(grant("space-1"), "nonexistentterm")
        assertTrue(result.citations.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun `query and maxItems bounds are rejected with typed errors`() = runBlocking {
        persistence.seedSpace("space-1")
        val approval = grant("space-1")
        assertEquals(
            AgentMemoryErrorCodes.QUERY_EMPTY,
            rejected(store.search(approval, "   ", null, 4))
        )
        assertEquals(
            AgentMemoryErrorCodes.QUERY_TOO_LONG,
            rejected(store.search(approval, "q".repeat(AgentMemoryLimits.MAX_QUERY_CHARS + 1), null, 4))
        )
        assertEquals(
            AgentMemoryErrorCodes.MAX_ITEMS_INVALID,
            rejected(store.search(approval, "alpha", null, AgentMemoryLimits.MAX_MAX_ITEMS + 1))
        )
    }

    @Test
    fun `a revoked grant and a deleted selected space deny retrieval`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "alpha line")
        val revoked = AgentMemoryReadGrant("task-1", listOf("space-1"), revoked = true)
        assertEquals(
            AgentMemoryErrorCodes.SELECTION_REVOKED,
            rejected(store.search(revoked, "alpha", null, 4))
        )
        val includesDeleted = AgentMemoryReadGrant("task-1", listOf("space-1", "space-missing"))
        assertEquals(
            AgentMemoryErrorCodes.SPACE_NOT_FOUND,
            rejected(store.search(includesDeleted, "alpha", null, 4))
        )
    }

    @Test
    fun `ranking scores match the accepted knowledge lexical retriever`() {
        val texts = listOf(
            "refund policy and refund timing",
            "the refunded invoice from last week",
            "unrelated content about coffee",
            "REFUND history: model refunds, refunded"
        )
        val memoryChunks = texts.mapIndexed { index, text ->
            AgentMemoryChunkRecord(
                chunkId = "memory-${index + 1}:0",
                memoryId = "memory-${index + 1}",
                spaceId = "space-1",
                position = 0,
                text = text,
                contentHash = AgentMemoryText.sha256(text),
                estimatedTokens = 1,
                sourceHash = AgentMemoryText.sha256(text)
            )
        }
        val knowledgeChunks = texts.mapIndexed { index, text ->
            KnowledgeChunkRecord(
                id = "memory-${index + 1}:0",
                knowledgeBaseId = "kb",
                documentId = "memory-${index + 1}",
                position = 0,
                content = text,
                contentHash = AgentMemoryText.sha256(text),
                estimatedTokens = 1
            )
        }
        val queries = listOf("refund", "refunded invoice", "coffee", "模型", "ref")
        queries.forEach { query ->
            val memoryScores = AgentMemoryLexicalRanker.rank(query, memoryChunks)
                .associate { it.chunk.chunkId to it.score }
            val knowledgeScores = KnowledgeLexicalRetriever.rank(query, knowledgeChunks)
                .associate { it.chunk.id to it.score }
            assertEquals("scores must stay equivalent for query '$query'", knowledgeScores, memoryScores)
        }
    }

    @Test
    fun `a matching chunk beyond the first page is still retrievable`() = runBlocking {
        persistence.seedSpace("space-1")
        // More than one scan page of noise; the only matching item sorts last by (memoryId,pos).
        repeat(300) { index ->
            persistence.seedItem("noise-%03d".format(index), "space-1", "unrelated office note $index")
        }
        persistence.seedItem("zzz-needle", "space-1", "the quarterly refund policy is late")

        val result = search(grant("space-1"), "refund policy")

        assertEquals(listOf("zzz-needle"), result.citations.map { it.memoryId })
        assertTrue(
            "the scan must get past the first page to find it: ${result.scannedChunks}",
            result.scannedChunks > AgentMemoryLimits.CHUNK_PAGE_SIZE
        )
    }

    @Test
    fun `the candidate cap is applied while scanning instead of after a whole page`() = runBlocking {
        persistence.seedSpace("space-1")
        repeat(120) { index ->
            persistence.seedItem("match-%03d".format(index), "space-1", "refund policy note $index")
        }

        val result = search(grant("space-1"), "refund policy")

        assertTrue(
            "citations stay below the returned cap: ${result.citations.size}",
            result.citations.size <= AgentMemoryLimits.MAX_RETURNED_CITATIONS
        )
        assertTrue("a capped scan must report truncation", result.truncated)
        assertTrue(
            "the scan must not examine the whole space once the candidate cap is full",
            result.scannedChunks <= AgentMemoryLimits.CHUNK_PAGE_SIZE
        )
    }

    @Test
    fun `getItem reads in-scope items and hides foreign or revoked ones`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedSpace("space-2")
        persistence.seedItem("memory-1", "space-1", "visible text")
        persistence.seedItem("memory-2", "space-2", "other space")
        persistence.seedItem("memory-3", "space-1", "revoked text", revoked = true)
        val grant = grant("space-1")

        val found = store.getItem(grant, "memory-1", null) as AgentMemoryGetOutcome.Found
        assertEquals("visible text", found.item.text)
        assertEquals("space-1", found.item.spaceId)

        listOf("memory-2", "memory-3", "memory-404").forEach { itemId ->
            val rejected = store.getItem(grant, itemId, null) as AgentMemoryGetOutcome.Rejected
            assertEquals(AgentMemoryErrorCodes.ITEM_NOT_FOUND, rejected.error.code)
        }

        val outside = store.getItem(grant, "memory-1", "space-2") as AgentMemoryGetOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.SPACE_NOT_SELECTED, outside.error.code)

        val revokedGrant = AgentMemoryReadGrant("task-1", listOf("space-1"), revoked = true)
        val revoked = store.getItem(revokedGrant, "memory-1", null) as AgentMemoryGetOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.SELECTION_REVOKED, revoked.error.code)

        val currentHash = AgentMemoryText.sha256("visible text")
        val fresh = store.getItem(grant, "memory-1", null, currentHash) as AgentMemoryGetOutcome.Found
        assertEquals("visible text", fresh.item.text)
        val stale = store.getItem(grant, "memory-1", null, "b".repeat(64)) as AgentMemoryGetOutcome.Rejected
        assertEquals(AgentMemoryErrorCodes.CITATION_STALE, stale.error.code)
    }

    private suspend fun search(
        grant: AgentMemoryReadGrant,
        query: String,
        requestedSpaceId: String? = null,
        maxItems: Int = AgentMemoryLimits.DEFAULT_MAX_ITEMS
    ): AgentMemorySearchResult {
        val outcome = store.search(grant, query, requestedSpaceId, maxItems)
        return (outcome as AgentMemorySearchOutcome.Found).result
    }

    private fun rejected(outcome: AgentMemorySearchOutcome): String =
        (outcome as AgentMemorySearchOutcome.Rejected).error.code

    private fun grant(vararg spaceIds: String): AgentMemoryReadGrant =
        AgentMemoryReadGrant("task-1", spaceIds.toList().sorted())
    @Test
    fun `a match beyond 256 chunks and a newly created space stay reachable`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedSpace("space-2")
        repeat(300) { index ->
            persistence.seedItem("filler-$index", "space-1", "routine filler note number $index")
        }
        persistence.seedItem("tail-hit", "space-1", "the needle phrase appears only at the tail")
        persistence.seedItem("new-space-hit", "space-2", "the needle phrase in a new space")

        val grant = grant("space-1", "space-2")
        val tail = search(grant, "needle phrase")
        assertTrue(
            "tail item beyond 256 chunks must be reachable, got ${tail.citations.map { it.memoryId }}",
            tail.citations.any { it.memoryId == "tail-hit" }
        )
        assertTrue(
            "a newly created selected space must be searched",
            tail.citations.any { it.memoryId == "new-space-hit" }
        )
    }

}
