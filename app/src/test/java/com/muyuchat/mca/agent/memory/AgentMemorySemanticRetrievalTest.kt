package com.muyuchat.mca.agent.memory

import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Semantic retrieval seam (N14/N15) and the hybrid fallback contract.
 *
 * The embedder here is a deterministic *test double* (hashed bag of words). It is intentionally not
 * semantic: no production text-embedding backend exists yet, so the tests prove the plumbing —
 * space scoping, fingerprinting, fusion, authoritative re-read and fail-open to lexical — and never
 * claim semantic quality.
 */
class AgentMemorySemanticRetrievalTest {
    private val persistence = InMemoryAgentMemoryPersistence()
    private val lexicalStore = AgentMemoryTestFixtures.store(
        persistence,
        AgentMemoryTestFixtures.sequentialIds("lexical")
    )

    init {
        persistence.seedSpace("space-1")
        persistence.seedSpace("space-2")
    }

    // --- index / embedder fundamentals ---------------------------------------------------------

    @Test
    fun `the fake embedder is deterministic and unit normalized`() = runBlocking {
        val embedder = FakeAgentMemoryTextEmbedder()
        val first = embedder.embed(listOf("退款政策", "退款政策"))
        assertEquals(first[0].toList(), first[1].toList())
        val norm = sqrt(first[0].sumOf { it.toDouble() * it.toDouble() })
        assertEquals(1.0, norm, 1.0e-5)
        val other = embedder.embed(listOf("今天天气不错")).single()
        assertFalse(first[0].contentEquals(other))
        assertEquals(embedder.fingerprint.dimension, first[0].size)
    }

    @Test
    fun `the exact index scopes by space, orders by similarity and drops zero matches`() = runBlocking {
        val embedder = FakeAgentMemoryTextEmbedder()
        val index = InMemoryAgentMemoryVectorIndex(embedder.fingerprint)
        index.upsert(
            listOf(
                embedder.record("m-exact:0", "m-exact", "space-1", "退款政策"),
                embedder.record("m-partial:0", "m-partial", "space-1", "退款说明"),
                embedder.record("m-foreign:0", "m-foreign", "space-2", "退款政策"),
                embedder.record("m-noise:0", "m-noise", "space-1", "今天天气不错")
            )
        )
        val query = embedder.embed(listOf("退款政策")).single()

        val matches = index.search(listOf("space-1"), query, 10)

        assertEquals(listOf("m-exact:0", "m-partial:0"), matches.map { it.chunkId })
        assertTrue(matches.zipWithNext().all { (left, right) -> left.similarity >= right.similarity })
        assertTrue(matches.none { it.spaceId == "space-2" })
        assertEquals(listOf("m-exact:0"), index.search(listOf("space-1"), query, 1).map { it.chunkId })
    }

    @Test
    fun `the index rejects a mismatched dimension and an over-budget upsert`() {
        runBlocking {
            val embedder = FakeAgentMemoryTextEmbedder()
            val index = InMemoryAgentMemoryVectorIndex(embedder.fingerprint)
            val wrongDimension = embedder.record("a:0", "a", "space-1", "退款政策")
                .copy(embedding = FloatArray(embedder.fingerprint.dimension - 1) { 1f })
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { index.upsert(listOf(wrongDimension)) }
            }

            val tiny = InMemoryAgentMemoryVectorIndex(embedder.fingerprint, maxRecords = 1)
            assertThrows(AgentMemoryVectorIndexLimitException::class.java) {
                runBlocking {
                    tiny.upsert(
                        listOf(
                            embedder.record("a:0", "a", "space-1", "退款政策"),
                            embedder.record("b:0", "b", "space-1", "退款说明")
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `the index removes items and spaces with their vectors`() = runBlocking {
        val embedder = FakeAgentMemoryTextEmbedder()
        val index = InMemoryAgentMemoryVectorIndex(embedder.fingerprint)
        index.upsert(
            listOf(
                embedder.record("a:0", "a", "space-1", "退款政策"),
                embedder.record("a:1", "a", "space-1", "退款说明"),
                embedder.record("b:0", "b", "space-2", "退款政策")
            )
        )

        assertEquals(2, index.removeItem("a"))
        assertEquals(1, index.size())
        assertEquals(1, index.removeSpace("space-2"))
        assertEquals(0, index.size())
    }

    @Test
    fun `reciprocal rank fusion prefers a chunk matched by both sources`() = runBlocking {
        val chunkA = chunk("a", "退款政策说明")
        val chunkB = chunk("b", "退款政策")
        val chunkC = chunk("c", "退款时效")

        val fused = AgentMemoryHybridFusion.fuse(
            lexical = listOf(
                AgentMemoryLexicalRanker.Ranked(chunkA, 10),
                AgentMemoryLexicalRanker.Ranked(chunkB, 6)
            ),
            semantic = listOf(chunkB, chunkC),
            limit = 10
        )

        assertEquals(listOf("b", "a", "c"), fused.map { it.chunk.memoryId })
        assertEquals(0, fused.first().semanticRank)
        assertEquals(1, fused.first().lexicalRank)
        assertTrue(fused.all { it.score > 0 })
        assertTrue(fused.first().score > fused[1].score)
    }

    // --- store-level hybrid behavior ------------------------------------------------------------

    @Test
    fun `hybrid search adds semantic-only chunks and keeps lexical citations`() = runBlocking {
        seed("space-1", "memory-a", "退款政策：五个工作日到账。")
        seed("space-1", "memory-b", "到账时间以短信通知为准。")
        val source = RecordingSource(
            result = available(hit("memory-b:0", "memory-b", "space-1", 0.91f))
        )
        val store = storeWith(source)

        val result = search(store)

        assertEquals(
            listOf("memory-a", "memory-b"),
            result.citations.map { it.memoryId }.sorted()
        )
        assertEquals(AgentMemoryRetrievalMode.HYBRID, result.retrievalMode)
        assertEquals(1, result.semanticCandidates)
        assertNull(result.semanticFallback)
        // Text comes from persistence, never from the index hit.
        assertTrue(
            result.citations.first { it.memoryId == "memory-b" }.excerpt.contains("到账时间")
        )
        assertEquals(listOf("space-1"), source.lastSpaceIds)
        assertEquals(AgentMemoryLimits.MAX_SEMANTIC_CANDIDATES, source.lastLimit)
    }

    @Test
    fun `a semantic hit outside the granted scope is dropped before any read`() = runBlocking {
        seed("space-1", "memory-a", "退款政策：五个工作日到账。")
        seed("space-2", "memory-secret", "退款政策：另一个空间的秘密。")
        val source = RecordingSource(
            result = available(hit("memory-secret:0", "memory-secret", "space-2", 0.99f))
        )
        val store = storeWith(source)

        val result = search(store)

        assertEquals(listOf("memory-a"), result.citations.map { it.memoryId })
        assertEquals(AgentMemoryRetrievalMode.LEXICAL, result.retrievalMode)
        assertEquals(0, result.semanticCandidates)
        assertTrue(result.citations.none { it.excerpt.contains("秘密") })
    }

    @Test
    fun `a throwing semantic source fails open to the lexical result`() = runBlocking {
        seed("space-1", "memory-a", "退款政策：五个工作日到账。")
        val lexical = search(lexicalStore)
        val store = storeWith(RecordingSource(result = available(), failure = true))

        val result = search(store)

        assertEquals(lexical.citations.map { it.memoryId }, result.citations.map { it.memoryId })
        assertEquals(AgentMemoryRetrievalMode.LEXICAL, result.retrievalMode)
        assertEquals(
            AgentMemorySemanticUnavailableReason.INDEX_UNAVAILABLE,
            result.semanticFallback
        )
    }

    @Test
    fun `an unavailable semantic source reports the reason and keeps lexical results`() = runBlocking {
        seed("space-1", "memory-a", "退款政策：五个工作日到账。")
        val lexical = search(lexicalStore)
        val store = storeWith(
            RecordingSource(
                result = AgentMemorySemanticQuery.Unavailable(
                    AgentMemorySemanticUnavailableReason.EMBEDDING_FAILED
                )
            )
        )

        val result = search(store)

        assertEquals(lexical.citations.map { it.memoryId }, result.citations.map { it.memoryId })
        assertEquals(AgentMemoryRetrievalMode.LEXICAL, result.retrievalMode)
        assertEquals(AgentMemorySemanticUnavailableReason.EMBEDDING_FAILED, result.semanticFallback)
    }

    @Test
    fun `an empty semantic index is not a fallback`() = runBlocking {
        seed("space-1", "memory-a", "退款政策：五个工作日到账。")
        val store = storeWith(RecordingSource(result = available()))

        val result = search(store)

        assertEquals(listOf("memory-a"), result.citations.map { it.memoryId })
        assertEquals(AgentMemoryRetrievalMode.LEXICAL, result.retrievalMode)
        assertNull(result.semanticFallback)
        assertEquals(0, result.semanticCandidates)
    }

    @Test
    fun `a stale semantic chunk id resolves to nothing and never surfaces content`() = runBlocking {
        seed("space-1", "memory-a", "退款政策：五个工作日到账。")
        val store = storeWith(
            RecordingSource(result = available(hit("deleted:0", "deleted", "space-1", 0.95f)))
        )

        val result = search(store)

        assertEquals(listOf("memory-a"), result.citations.map { it.memoryId })
        assertEquals(AgentMemoryRetrievalMode.LEXICAL, result.retrievalMode)
        assertNull(result.semanticFallback)
    }

    @Test
    fun `the store stays lexical when no semantic source is configured`() = runBlocking {
        seed("space-1", "memory-a", "退款政策：五个工作日到账。")

        val result = search(lexicalStore)

        assertEquals(AgentMemoryRetrievalMode.LEXICAL, result.retrievalMode)
        assertNull(result.semanticFallback)
        assertEquals(0, result.semanticCandidates)
        assertFalse(result.citations.isEmpty())
    }

    // --- vector candidate source ----------------------------------------------------------------

    @Test
    fun `the vector candidate source reports a fingerprint mismatch without searching`() = runBlocking {
        val embedder = FakeAgentMemoryTextEmbedder()
        val index = StubVectorIndex(
            AgentMemoryEmbeddingFingerprint(
                modelId = embedder.fingerprint.modelId,
                dimension = embedder.fingerprint.dimension + 1
            )
        )
        val source = AgentMemoryVectorCandidateSource(embedder, index)

        val outcome = source.candidates(listOf("space-1"), "退款政策", 5)

        assertEquals(
            AgentMemorySemanticQuery.Unavailable(AgentMemorySemanticUnavailableReason.FINGERPRINT_MISMATCH),
            outcome
        )
        assertEquals(0, index.searches)
    }

    @Test
    fun `the vector candidate source maps an unusable embedding to unavailability`() = runBlocking {
        val embedder = FakeAgentMemoryTextEmbedder()
        val index = InMemoryAgentMemoryVectorIndex(embedder.fingerprint)
        val failing = object : AgentMemoryTextEmbedder {
            override val fingerprint: AgentMemoryEmbeddingFingerprint = embedder.fingerprint

            override suspend fun embed(texts: List<String>): List<FloatArray> =
                throw IllegalStateException("model unloaded")
        }
        val failed = AgentMemoryVectorCandidateSource(failing, index)
            .candidates(listOf("space-1"), "退款政策", 5)
        assertEquals(
            AgentMemorySemanticQuery.Unavailable(AgentMemorySemanticUnavailableReason.EMBEDDING_FAILED),
            failed
        )

        val wrongSize = object : AgentMemoryTextEmbedder {
            override val fingerprint: AgentMemoryEmbeddingFingerprint = embedder.fingerprint

            override suspend fun embed(texts: List<String>): List<FloatArray> =
                listOf(FloatArray(embedder.fingerprint.dimension - 1) { 1f })
        }
        val mismatched = AgentMemoryVectorCandidateSource(wrongSize, index)
            .candidates(listOf("space-1"), "退款政策", 5)
        assertEquals(
            AgentMemorySemanticQuery.Unavailable(AgentMemorySemanticUnavailableReason.DIMENSION_MISMATCH),
            mismatched
        )
    }

    @Test
    fun `the vector candidate source returns hits from the requested spaces only`() = runBlocking {
        val embedder = FakeAgentMemoryTextEmbedder()
        val index = InMemoryAgentMemoryVectorIndex(embedder.fingerprint)
        index.upsert(
            listOf(
                embedder.record("m-1:0", "m-1", "space-1", "退款政策"),
                embedder.record("m-2:0", "m-2", "space-2", "退款政策")
            )
        )
        val source = AgentMemoryVectorCandidateSource(embedder, index)

        val outcome = source.candidates(listOf("space-1"), "退款政策", 5) as
            AgentMemorySemanticQuery.Available

        assertEquals(listOf("m-1:0"), outcome.hits.map { it.chunkId })
        assertEquals(embedder.fingerprint.stableKey, outcome.fingerprint)
        assertEquals("space-1", outcome.hits.single().spaceId)
    }

    @Test
    fun `the semantic wiring is lexical when no embedder or index exists`() {
        val noEmbedder = AgentMemorySemanticWiring.select(embedder = null, index = null)
        assertNull(noEmbedder.source)
        assertEquals(
            AgentMemorySemanticStatus.Disabled(AgentMemorySemanticUnavailableReason.NOT_CONFIGURED),
            noEmbedder.status
        )

        val embedder = FakeAgentMemoryTextEmbedder()
        val noIndex = AgentMemorySemanticWiring.select(embedder = embedder, index = null)
        assertNull(noIndex.source)
        assertEquals(
            AgentMemorySemanticStatus.Disabled(AgentMemorySemanticUnavailableReason.INDEX_UNAVAILABLE),
            noIndex.status
        )

        val disabledByPolicy = AgentMemorySemanticWiring.select(
            embedder = embedder,
            index = InMemoryAgentMemoryVectorIndex(embedder.fingerprint),
            policy = AgentMemorySemanticPolicy(enabled = false)
        )
        assertNull(disabledByPolicy.source)
        assertEquals(
            AgentMemorySemanticStatus.Disabled(AgentMemorySemanticUnavailableReason.DISABLED_BY_POLICY),
            disabledByPolicy.status
        )
    }

    @Test
    fun `a wired embedder and index report ready and the store exposes the same status`() = runBlocking {
        val embedder = FakeAgentMemoryTextEmbedder()
        val index = InMemoryAgentMemoryVectorIndex(embedder.fingerprint)
        index.upsert(listOf(embedder.record("m-1:0", "m-1", "space-1", "退款政策")))
        val selection = AgentMemorySemanticWiring.select(embedder = embedder, index = index)

        assertEquals(
            AgentMemorySemanticStatus.Ready(embedder.fingerprint.stableKey),
            selection.status
        )
        val store = AgentMemoryStore(
            persistence = persistence,
            clock = { AgentMemoryTestFixtures.NOW },
            idFactory = AgentMemoryTestFixtures.sequentialIds("wired"),
            semantic = selection.source
        )
        assertEquals(
            AgentMemorySemanticStatus.Ready(embedder.fingerprint.stableKey, recordCount = 1),
            store.semanticStatus()
        )
        assertEquals(
            AgentMemorySemanticStatus.Disabled(AgentMemorySemanticUnavailableReason.NOT_CONFIGURED),
            lexicalStore.semanticStatus()
        )
    }

    @Test
    fun `a fingerprint mismatch stays observable and the query falls back to lexical`() = runBlocking {
        seed("space-1", "memory-a", "退款政策：五个工作日到账。")
        val embedder = FakeAgentMemoryTextEmbedder()
        val index = InMemoryAgentMemoryVectorIndex(
            AgentMemoryEmbeddingFingerprint(
                modelId = embedder.fingerprint.modelId,
                dimension = embedder.fingerprint.dimension + 1
            )
        )
        val selection = AgentMemorySemanticWiring.select(embedder = embedder, index = index)
        assertEquals(
            AgentMemorySemanticStatus.Unavailable(AgentMemorySemanticUnavailableReason.FINGERPRINT_MISMATCH),
            selection.status
        )
        val store = AgentMemoryStore(
            persistence = persistence,
            clock = { AgentMemoryTestFixtures.NOW },
            idFactory = AgentMemoryTestFixtures.sequentialIds("mismatch"),
            semantic = selection.source
        )
        assertEquals(
            AgentMemorySemanticStatus.Unavailable(AgentMemorySemanticUnavailableReason.FINGERPRINT_MISMATCH),
            store.semanticStatus()
        )

        val result = search(store)

        assertEquals(AgentMemoryRetrievalMode.LEXICAL, result.retrievalMode)
        assertEquals(
            AgentMemorySemanticUnavailableReason.FINGERPRINT_MISMATCH,
            result.semanticFallback
        )
        assertEquals(listOf("memory-a"), result.citations.map { it.memoryId })
    }

    // --- helpers --------------------------------------------------------------------------------

    private suspend fun seed(spaceId: String, memoryId: String, text: String) {
        persistence.seedSpace(spaceId)
        persistence.seedItem(memoryId, spaceId, text)
    }

    private fun storeWith(source: AgentMemorySemanticCandidateSource): AgentMemoryStore =
        AgentMemoryStore(
            persistence = persistence,
            clock = { AgentMemoryTestFixtures.NOW },
            idFactory = AgentMemoryTestFixtures.sequentialIds("hybrid"),
            semantic = source
        )

    private suspend fun search(store: AgentMemoryStore): AgentMemorySearchResult {
        val grant = AgentMemoryReadGrant("task-1", listOf("space-1"))
        return (store.search(grant, "退款政策", null, AgentMemoryLimits.DEFAULT_MAX_ITEMS) as
            AgentMemorySearchOutcome.Found).result
    }

    private fun available(vararg hits: AgentMemorySemanticHit): AgentMemorySemanticQuery =
        AgentMemorySemanticQuery.Available(
            hits.toList(),
            fingerprint = FakeAgentMemoryTextEmbedder().fingerprint.stableKey
        )

    private fun hit(
        chunkId: String,
        memoryId: String,
        spaceId: String,
        similarity: Float
    ): AgentMemorySemanticHit = AgentMemorySemanticHit(chunkId, memoryId, spaceId, similarity)

    private fun chunk(memoryId: String, text: String): AgentMemoryChunkRecord = AgentMemoryChunkRecord(
        chunkId = "$memoryId:0",
        memoryId = memoryId,
        spaceId = "space-1",
        position = 0,
        text = text,
        contentHash = AgentMemoryText.sha256(text),
        estimatedTokens = 1,
        sourceHash = AgentMemoryText.sha256(text)
    )

    private class RecordingSource(
        private val result: AgentMemorySemanticQuery,
        private val failure: Boolean = false
    ) : AgentMemorySemanticCandidateSource {
        var calls: Int = 0
            private set
        var lastSpaceIds: List<String> = emptyList()
            private set
        var lastLimit: Int = 0
            private set

        override suspend fun candidates(
            spaceIds: List<String>,
            query: String,
            limit: Int
        ): AgentMemorySemanticQuery {
            calls++
            lastSpaceIds = spaceIds
            lastLimit = limit
            if (failure) throw IllegalStateException("index unavailable")
            return result
        }
    }

    private class StubVectorIndex(
        override val fingerprint: AgentMemoryEmbeddingFingerprint
    ) : AgentMemoryVectorIndex {
        var searches: Int = 0
            private set

        override suspend fun upsert(records: List<AgentMemoryVectorRecord>) = Unit

        override suspend fun removeItem(memoryId: String): Int = 0

        override suspend fun removeSpace(spaceId: String): Int = 0

        override suspend fun size(): Int = 0

        override suspend fun search(
            spaceIds: Collection<String>,
            query: FloatArray,
            limit: Int
        ): List<AgentMemoryVectorMatch> {
            searches++
            return emptyList()
        }
    }
}

/**
 * Deterministic hashed bag-of-words embedder — a **test double**, not a semantic model.
 *
 * It reuses the project tokenizer so a query and a chunk that share terms produce a non-zero cosine.
 * It exists to verify the plumbing (scoping, fingerprints, fusion, fail-open), never to claim
 * semantic recall in production evidence.
 */
private class FakeAgentMemoryTextEmbedder(
    override val fingerprint: AgentMemoryEmbeddingFingerprint = AgentMemoryEmbeddingFingerprint(
        modelId = "fake-semantic-v1",
        dimension = 1024
    )
) : AgentMemoryTextEmbedder {

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map(::vectorOf)

    fun record(
        chunkId: String,
        memoryId: String,
        spaceId: String,
        text: String
    ): AgentMemoryVectorRecord = AgentMemoryVectorRecord(
        chunkId = chunkId,
        memoryId = memoryId,
        spaceId = spaceId,
        contentHash = AgentMemoryText.sha256(text),
        embedding = vectorOf(text)
    )

    private fun vectorOf(text: String): FloatArray {
        val vector = FloatArray(fingerprint.dimension)
        AgentMemoryLexicalRanker.terms(text).forEach { term ->
            vector[term.hashCode().mod(fingerprint.dimension)] += 1f
        }
        if (vector.all { it == 0f }) vector[0] = 1f
        val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        return if (norm == 0f) vector else FloatArray(vector.size) { vector[it] / norm }
    }
}
