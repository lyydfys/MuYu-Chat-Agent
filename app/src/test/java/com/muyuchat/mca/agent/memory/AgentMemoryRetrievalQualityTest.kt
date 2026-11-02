package com.muyuchat.mca.agent.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixed-query/qrels retrieval evaluation over the real lexical store path.
 *
 * This does not prove semantic/embedding quality: the store is built with `semantic = null`, so the
 * measured mode is LEXICAL. It records Recall@3 / MRR for an unchanged corpus + query + qrels set so
 * later embedding work can be compared against the same denominator. Device/airplane-mode evidence
 * remains NOT_RUN.
 */
class AgentMemoryRetrievalQualityTest {

    private data class Seed(val memoryId: String, val spaceId: String, val text: String)

    private data class Query(val text: String, val relevant: Set<String>)

    private val work = "space-work"
    private val personal = "space-personal"

    private val corpus = listOf(
        Seed("m-kubernetes", work, "kubernetes pod crashloopbackoff restart backoff limit"),
        Seed("m-postgres", work, "postgres slow query index vacuum analyze planner"),
        Seed("m-redis", work, "redis cache eviction maxmemory policy volatile-lru"),
        Seed("m-kafka", work, "kafka consumer lag partition rebalance group coordinator"),
        Seed("m-otel", work, "opentelemetry trace span exporter sampler batch"),
        Seed("m-lunch", work, "team lunch menu friday cafeteria vegetarian"),
        Seed("m-sourdough", personal, "sourdough starter hydration fermentation schedule warm"),
        Seed("m-marathon", personal, "marathon training weekly mileage taper race"),
        Seed("m-japanese", personal, "japanese grammar particles wa ga sentence"),
        Seed("m-tax", personal, "tax documents receipts deduction deadline filing")
    )

    private val queries = listOf(
        Query("kubernetes crashloopbackoff", setOf("m-kubernetes")),
        Query("postgres vacuum analyze", setOf("m-postgres")),
        Query("redis maxmemory eviction", setOf("m-redis")),
        Query("kafka consumer rebalance", setOf("m-kafka")),
        Query("opentelemetry exporter sampler", setOf("m-otel")),
        Query("sourdough fermentation hydration", setOf("m-sourdough")),
        Query("marathon taper mileage", setOf("m-marathon")),
        Query("japanese grammar particles", setOf("m-japanese"))
    )

    private fun store(): Pair<AgentMemoryStore, InMemoryAgentMemoryPersistence> {
        val persistence = InMemoryAgentMemoryPersistence()
        persistence.seedSpace(work)
        persistence.seedSpace(personal)
        corpus.forEach { seed -> persistence.seedItem(seed.memoryId, seed.spaceId, seed.text) }
        return AgentMemoryStore(persistence) to persistence
    }

    private suspend fun search(
        store: AgentMemoryStore,
        spaces: List<String>,
        query: String,
        maxItems: Int = 3
    ): List<String> {
        val outcome = store.search(
            grant = AgentMemoryReadGrant(taskId = "qrels-task", spaceIds = spaces),
            query = query,
            requestedSpaceId = null,
            maxItems = maxItems
        )
        assertTrue("expected Found, got $outcome", outcome is AgentMemorySearchOutcome.Found)
        return (outcome as AgentMemorySearchOutcome.Found).result.citations
            .map { it.memoryId }
            .distinct()
    }

    @Test
    fun `fixed qrels measure Recall@3 and MRR over the lexical path`() = runBlocking {
        val (store, _) = store()
        val spaces = listOf(work, personal).sorted()
        var reciprocalRankSum = 0.0
        var recallSum = 0.0
        val lines = ArrayList<String>()
        for (query in queries) {
            val ranked = search(store, spaces, query.text)
            val firstRelevant = ranked.indexOfFirst { it in query.relevant }
            val reciprocalRank = if (firstRelevant >= 0) 1.0 / (firstRelevant + 1) else 0.0
            val recall = ranked.count { it in query.relevant }.toDouble() / query.relevant.size
            reciprocalRankSum += reciprocalRank
            recallSum += recall
            lines += "${query.text} -> $ranked (RR=${"%.3f".format(reciprocalRank)})"
        }
        val recallAt3 = recallSum / queries.size
        val mrr = reciprocalRankSum / queries.size
        println("LEXICAL Recall@3=${"%.3f".format(recallAt3)} MRR=${"%.3f".format(mrr)}")
        lines.forEach(::println)

        assertTrue("Recall@3 must be measured at >= 0.75, got $recallAt3", recallAt3 >= 0.75)
        assertTrue("MRR must be measured at >= 0.75, got $mrr", mrr >= 0.75)
    }

    @Test
    fun `revoked items and out-of-grant spaces never enter the qrels result`() = runBlocking {
        val (store, persistence) = store()
        persistence.revokeItem(work, "m-kubernetes", expectedRevision = 1L, atMillis = 2L)

        val afterRevoke = search(
            store,
            listOf(work, personal).sorted(),
            "kubernetes crashloopbackoff"
        )
        assertTrue("revoked item must not be cited", "m-kubernetes" !in afterRevoke)

        val outOfGrant = search(store, listOf(personal), "kubernetes crashloopbackoff")
        assertTrue("out-of-grant space leak: $outOfGrant", outOfGrant.isEmpty())
    }
}
