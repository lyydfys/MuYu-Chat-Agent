package com.muyuchat.mca.agent.memory

import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/**
 * Semantic retrieval seam (N14/N15) with a bounded, rebuildable vector index.
 *
 * Design constraints that keep this honest and safe:
 *
 * - **No embedding model is bundled.** [AgentMemoryTextEmbedder] is a capability port; this slice
 *   has no production implementation, so the store stays lexical by default. N15a must first prove a
 *   real local backend emits text vectors (the card records that probe as NOT_RUN).
 * - **Room stays authoritative.** A semantic hit carries only ids. [AgentMemoryStore] re-reads the
 *   chunk text live from the persistence port, so a stale, fabricated or foreign-space hit can only
 *   *name* a chunk it cannot make searchable; unknown/revoked/out-of-scope ids are dropped.
 * - **Fail-open, never fail-closed.** A missing embedder, a fingerprint mismatch, an embedder error
 *   or an index error leaves the lexical result exactly as it was and reports the reason on
 *   [AgentMemorySearchResult.semanticFallback]. Semantic retrieval can add recall; it can never turn
 *   a working lexical search into an error.
 * - **The index is derived.** [InMemoryAgentMemoryVectorIndex] can be discarded and rebuilt from
 *   Room at any time; it is not a second memory store and it never decides revocation.
 */
data class AgentMemoryEmbeddingFingerprint(
    val modelId: String,
    val dimension: Int,
    val pooling: String = "mean",
    val normalization: String = "l2"
) {
    init {
        require(modelId.isNotBlank() && modelId.length <= 128) { "embedding model id must be 1-128 chars" }
        require(dimension in 1..8_192) { "embedding dimension must be between 1 and 8192" }
        require(NAME_PATTERN.matches(pooling)) { "embedding pooling must be a short lower_snake token" }
        require(NAME_PATTERN.matches(normalization)) {
            "embedding normalization must be a short lower_snake token"
        }
    }

    /** Stable identity used in diagnostics; vectors are only comparable within one fingerprint. */
    val stableKey: String get() = "$modelId|d$dimension|p$pooling|n$normalization"

    private companion object {
        val NAME_PATTERN = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}

/**
 * Text-embedding capability port (N15a).
 *
 * A batch call is the whole capability: implementations must reject an unloadable model instead of
 * returning placeholder vectors, and must keep one vector per input in order.
 */
interface AgentMemoryTextEmbedder {
    val fingerprint: AgentMemoryEmbeddingFingerprint

    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** One derived vector row: the chunk it embeds plus the chunk hash it was built from. */
data class AgentMemoryVectorRecord(
    val chunkId: String,
    val memoryId: String,
    val spaceId: String,
    val contentHash: String,
    val embedding: FloatArray
) {
    init {
        require(chunkId.isNotBlank()) { "vector chunk id must not be blank" }
        require(memoryId.isNotBlank()) { "vector memory id must not be blank" }
        require(spaceId.isNotBlank()) { "vector space id must not be blank" }
        require(contentHash.isNotBlank()) { "vector content hash must not be blank" }
        require(embedding.isNotEmpty()) { "vector embedding must not be empty" }
    }
}

/** Compare two vectors only when they come from the same fingerprint (model/tokenizer/pooling). */
data class AgentMemoryVectorMatch(
    val chunkId: String,
    val memoryId: String,
    val spaceId: String,
    val similarity: Float
)

/** Bounded exact vector index. Implementations must never return rows outside [spaceIds]. */
interface AgentMemoryVectorIndex {
    val fingerprint: AgentMemoryEmbeddingFingerprint

    suspend fun upsert(records: List<AgentMemoryVectorRecord>)

    suspend fun removeItem(memoryId: String): Int

    suspend fun removeSpace(spaceId: String): Int

    suspend fun size(): Int

    suspend fun search(
        spaceIds: Collection<String>,
        query: FloatArray,
        limit: Int
    ): List<AgentMemoryVectorMatch>
}

/** Raised when an upsert would exceed the configured record budget; the caller must rebuild. */
class AgentMemoryVectorIndexLimitException(val maxRecords: Int) :
    Exception("vector index record limit of $maxRecords reached")

/**
 * In-memory exact cosine index over one fingerprint.
 *
 * Exact search is the DESIGN §6.3 first step ("bounded exact search before ANN"): it is verifiable,
 * deterministic and dependency-free. The record budget is enforced on the projected size so a
 * rebuild that does not fit fails loudly instead of dropping vectors silently.
 */
class InMemoryAgentMemoryVectorIndex(
    override val fingerprint: AgentMemoryEmbeddingFingerprint,
    private val maxRecords: Int = AgentMemoryLimits.MAX_VECTOR_RECORDS
) : AgentMemoryVectorIndex {
    private val lock = Any()
    private val records = LinkedHashMap<String, AgentMemoryVectorRecord>()

    init {
        require(maxRecords > 0) { "vector index budget must be positive" }
    }

    override suspend fun upsert(records: List<AgentMemoryVectorRecord>) {
        if (records.isEmpty()) return
        synchronized(lock) {
            records.forEach { record ->
                require(record.embedding.size == fingerprint.dimension) {
                    "vector for chunk ${record.chunkId} has dimension ${record.embedding.size}, " +
                        "expected ${fingerprint.dimension}"
                }
            }
            val replacing = records.count { this.records.containsKey(it.chunkId) }
            val projected = this.records.size - replacing + records.size
            if (projected > maxRecords) throw AgentMemoryVectorIndexLimitException(maxRecords)
            records.forEach { record ->
                this.records[record.chunkId] = record.copy(embedding = record.embedding.copyOf())
            }
        }
    }

    override suspend fun removeItem(memoryId: String): Int = synchronized(lock) {
        val before = records.size
        records.entries.removeAll { entry -> entry.value.memoryId == memoryId }
        before - records.size
    }

    override suspend fun removeSpace(spaceId: String): Int = synchronized(lock) {
        val before = records.size
        records.entries.removeAll { entry -> entry.value.spaceId == spaceId }
        before - records.size
    }

    override suspend fun size(): Int = synchronized(lock) { records.size }

    override suspend fun search(
        spaceIds: Collection<String>,
        query: FloatArray,
        limit: Int
    ): List<AgentMemoryVectorMatch> {
        if (limit <= 0 || spaceIds.isEmpty()) return emptyList()
        require(query.size == fingerprint.dimension) {
            "query vector has dimension ${query.size}, expected ${fingerprint.dimension}"
        }
        val queryNorm = l2(query)
        if (queryNorm == 0.0) return emptyList()
        return synchronized(lock) {
            records.values.asSequence()
                .filter { it.spaceId in spaceIds }
                .map { record ->
                    val norm = l2(record.embedding)
                    val similarity = if (norm == 0.0) 0.0 else dot(record.embedding, query) / (norm * queryNorm)
                    AgentMemoryVectorMatch(
                        chunkId = record.chunkId,
                        memoryId = record.memoryId,
                        spaceId = record.spaceId,
                        similarity = similarity.toFloat()
                    )
                }
                .filter { it.similarity > 0f }
                .sortedWith(MATCH_ORDER)
                .take(limit)
                .toList()
        }
    }

    private companion object {
        val MATCH_ORDER: Comparator<AgentMemoryVectorMatch> =
            compareByDescending<AgentMemoryVectorMatch> { it.similarity }.thenBy { it.chunkId }

        fun dot(left: FloatArray, right: FloatArray): Double {
            var sum = 0.0
            for (index in left.indices) sum += left[index].toDouble() * right[index].toDouble()
            return sum
        }

        fun l2(vector: FloatArray): Double {
            var sum = 0.0
            for (value in vector) sum += value.toDouble() * value.toDouble()
            return kotlin.math.sqrt(sum)
        }
    }
}

/** Why semantic retrieval could not contribute to this query. */
enum class AgentMemorySemanticUnavailableReason {
    /** No embedder/index was wired into the store. */
    NOT_CONFIGURED,

    /** A configured hybrid policy explicitly turned semantic retrieval off. */
    DISABLED_BY_POLICY,

    /** The embedder threw or returned an unusable batch. */
    EMBEDDING_FAILED,

    /** The index and the embedder disagree on model/dimension/pooling. */
    FINGERPRINT_MISMATCH,

    /** The embedder returned a vector whose size differs from the fingerprint. */
    DIMENSION_MISMATCH,

    /** The index threw (for example, a rebuild is needed after the record budget was exceeded). */
    INDEX_UNAVAILABLE,

    /** The caller asked for an empty scope or a non-positive limit. */
    INVALID_REQUEST
}

/**
 * Observable capability of the configured semantic path.
 *
 * A lexical-only store reports [Disabled]; it never reports [Ready] without a real Embedder and
 * index, so a diagnostics surface cannot mistake "no vectors" for "semantic search works".
 */
sealed interface AgentMemorySemanticStatus {
    data class Disabled(val reason: AgentMemorySemanticUnavailableReason) : AgentMemorySemanticStatus

    data class Ready(val fingerprint: String?, val recordCount: Int? = null) : AgentMemorySemanticStatus

    data class Unavailable(val reason: AgentMemorySemanticUnavailableReason) : AgentMemorySemanticStatus
}

/** Hybrid switch/config. Disabled means "lexical only", never "fake empty vectors". */
data class AgentMemorySemanticPolicy(
    val enabled: Boolean = true
)

/** Result of selecting a semantic source for the store: the source (or null) plus its status. */
data class AgentMemorySemanticSelection(
    val source: AgentMemorySemanticCandidateSource?,
    val status: AgentMemorySemanticStatus
)

/** One semantic query result. [Available] with no hits is a valid "no semantic recall". */
sealed interface AgentMemorySemanticQuery {
    data class Available(
        val hits: List<AgentMemorySemanticHit>,
        val fingerprint: String? = null
    ) : AgentMemorySemanticQuery

    data class Unavailable(val reason: AgentMemorySemanticUnavailableReason) : AgentMemorySemanticQuery
}

/** Identity-only semantic hit; the store resolves text from the authoritative persistence. */
data class AgentMemorySemanticHit(
    val chunkId: String,
    val memoryId: String,
    val spaceId: String,
    val similarity: Float
) {
    init {
        require(chunkId.isNotBlank()) { "semantic hit chunk id must not be blank" }
        require(memoryId.isNotBlank()) { "semantic hit memory id must not be blank" }
        require(spaceId.isNotBlank()) { "semantic hit space id must not be blank" }
    }
}

/**
 * Narrow query seam the store calls for semantic candidates.
 *
 * Implementations must scope the query to [spaceIds] (never "query everything then filter") and
 * must report inability as [AgentMemorySemanticQuery.Unavailable] instead of throwing.
 */
interface AgentMemorySemanticCandidateSource {
    suspend fun candidates(
        spaceIds: List<String>,
        query: String,
        limit: Int
    ): AgentMemorySemanticQuery

    /**
     * Capability snapshot for diagnostics. The default is a bare "ready" for lightweight test
     * doubles; the real vector source reports its fingerprint or an unavailability reason.
     */
    suspend fun status(): AgentMemorySemanticStatus = AgentMemorySemanticStatus.Ready(fingerprint = null)
}

/**
 * Adapter that turns "embed the query + search one fingerprint's index" into the narrow seam.
 *
 * Every failure mode is mapped to a typed unavailability so the store can log/report it and keep the
 * lexical path. Cancellation is always rethrown.
 */
class AgentMemoryVectorCandidateSource(
    private val embedder: AgentMemoryTextEmbedder,
    private val index: AgentMemoryVectorIndex
) : AgentMemorySemanticCandidateSource {

    override suspend fun candidates(
        spaceIds: List<String>,
        query: String,
        limit: Int
    ): AgentMemorySemanticQuery {
        if (spaceIds.isEmpty() || limit <= 0 || query.isBlank()) {
            return AgentMemorySemanticQuery.Unavailable(AgentMemorySemanticUnavailableReason.INVALID_REQUEST)
        }
        if (index.fingerprint != embedder.fingerprint) {
            return AgentMemorySemanticQuery.Unavailable(
                AgentMemorySemanticUnavailableReason.FINGERPRINT_MISMATCH
            )
        }
        val vectors = try {
            embedder.embed(listOf(query))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AgentMemorySemanticQuery.Unavailable(
                AgentMemorySemanticUnavailableReason.EMBEDDING_FAILED
            )
        }
        val queryVector = vectors.singleOrNull() ?: return AgentMemorySemanticQuery.Unavailable(
            AgentMemorySemanticUnavailableReason.EMBEDDING_FAILED
        )
        if (queryVector.size != embedder.fingerprint.dimension) {
            return AgentMemorySemanticQuery.Unavailable(
                AgentMemorySemanticUnavailableReason.DIMENSION_MISMATCH
            )
        }
        val matches = try {
            index.search(spaceIds, queryVector, limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AgentMemorySemanticQuery.Unavailable(
                AgentMemorySemanticUnavailableReason.INDEX_UNAVAILABLE
            )
        }
        return AgentMemorySemanticQuery.Available(
            hits = matches.map { match ->
                AgentMemorySemanticHit(
                    chunkId = match.chunkId,
                    memoryId = match.memoryId,
                    spaceId = match.spaceId,
                    similarity = match.similarity
                )
            },
            fingerprint = embedder.fingerprint.stableKey
        )
    }

    /**
     * Observability for the wiring: a fingerprint mismatch or an unreadable index is reported here
     * without waiting for a query to fail, so a diagnostics surface can show why retrieval is lexical.
     */
    override suspend fun status(): AgentMemorySemanticStatus {
        if (index.fingerprint != embedder.fingerprint) {
            return AgentMemorySemanticStatus.Unavailable(
                AgentMemorySemanticUnavailableReason.FINGERPRINT_MISMATCH
            )
        }
        val count = try {
            index.size()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AgentMemorySemanticStatus.Unavailable(
                AgentMemorySemanticUnavailableReason.INDEX_UNAVAILABLE
            )
        }
        return AgentMemorySemanticStatus.Ready(embedder.fingerprint.stableKey, count)
    }
}

/**
 * Deterministic reciprocal-rank fusion of the lexical ranking and semantic candidates.
 *
 * RRF is used because lexical weights and cosine similarities are not comparable; only the ranks
 * inside each list are. Fusion is stable: equal fused scores fall back to lexical rank, then
 * semantic rank, then the chunk's `(spaceId, memoryId, position, chunkId)` identity.
 */
object AgentMemoryHybridFusion {
    data class Fused(
        val chunk: AgentMemoryChunkRecord,
        val score: Int,
        val lexicalRank: Int?,
        val semanticRank: Int?
    )

    private const val SCORE_SCALE = 1_000.0

    private val FUSED_ORDER: Comparator<Fused> =
        compareByDescending<Fused> { it.score }
            .thenBy { it.lexicalRank ?: Int.MAX_VALUE }
            .thenBy { it.semanticRank ?: Int.MAX_VALUE }
            .thenBy { it.chunk.spaceId }
            .thenBy { it.chunk.memoryId }
            .thenBy { it.chunk.position }
            .thenBy { it.chunk.chunkId }

    fun fuse(
        lexical: List<AgentMemoryLexicalRanker.Ranked>,
        semantic: List<AgentMemoryChunkRecord>,
        limit: Int
    ): List<Fused> {
        if (limit <= 0) return emptyList()
        val lexicalRanks = LinkedHashMap<String, Int>(lexical.size)
        val semanticRanks = LinkedHashMap<String, Int>(semantic.size)
        val chunks = LinkedHashMap<String, AgentMemoryChunkRecord>(lexical.size + semantic.size)
        lexical.forEachIndexed { index, hit ->
            if (lexicalRanks.putIfAbsent(hit.chunk.chunkId, index) == null) {
                chunks[hit.chunk.chunkId] = hit.chunk
            }
        }
        semantic.forEachIndexed { index, chunk ->
            if (semanticRanks.putIfAbsent(chunk.chunkId, index) == null) {
                chunks.putIfAbsent(chunk.chunkId, chunk)
            }
        }
        return chunks.values.map { chunk ->
            val lexicalRank = lexicalRanks[chunk.chunkId]
            val semanticRank = semanticRanks[chunk.chunkId]
            var reciprocalRank = 0.0
            if (lexicalRank != null) reciprocalRank += 1.0 / (AgentMemoryLimits.RRF_K + lexicalRank + 1)
            if (semanticRank != null) reciprocalRank += 1.0 / (AgentMemoryLimits.RRF_K + semanticRank + 1)
            Fused(
                chunk = chunk,
                score = (reciprocalRank * SCORE_SCALE).roundToInt(),
                lexicalRank = lexicalRank,
                semanticRank = semanticRank
            )
        }.sortedWith(FUSED_ORDER).take(limit)
    }
}

/**
 * Selection policy for the production semantic path (N15).
 *
 * The store only ever receives a source that is backed by a real [AgentMemoryTextEmbedder] and a real
 * [AgentMemoryVectorIndex]; when either is missing the selection returns a null source and a typed
 * [AgentMemorySemanticStatus.Disabled], so the store stays lexical. There is deliberately no
 * fallback embedder: a fake hashed bag-of-words must never ship as "semantic memory".
 *
 * Wiring contract for the composition root (owned by the integrator):
 * ```
 * val selection = AgentMemorySemanticWiring.select(embedder = null, index = null)
 * AgentMemoryStore(persistence, semantic = selection.source)
 * ```
 * With the current repository there is no local text-embedding backend, so production passes nulls
 * and `AgentMemoryStore.semanticStatus()` reports `NOT_CONFIGURED` (N15a is still NOT_RUN).
 */
object AgentMemorySemanticWiring {
    fun select(
        embedder: AgentMemoryTextEmbedder?,
        index: AgentMemoryVectorIndex?,
        policy: AgentMemorySemanticPolicy = AgentMemorySemanticPolicy()
    ): AgentMemorySemanticSelection {
        if (!policy.enabled) {
            return AgentMemorySemanticSelection(
                source = null,
                status = AgentMemorySemanticStatus.Disabled(
                    AgentMemorySemanticUnavailableReason.DISABLED_BY_POLICY
                )
            )
        }
        if (embedder == null) {
            return AgentMemorySemanticSelection(
                source = null,
                status = AgentMemorySemanticStatus.Disabled(
                    AgentMemorySemanticUnavailableReason.NOT_CONFIGURED
                )
            )
        }
        if (index == null) {
            return AgentMemorySemanticSelection(
                source = null,
                status = AgentMemorySemanticStatus.Disabled(
                    AgentMemorySemanticUnavailableReason.INDEX_UNAVAILABLE
                )
            )
        }
        val status = if (index.fingerprint != embedder.fingerprint) {
            AgentMemorySemanticStatus.Unavailable(AgentMemorySemanticUnavailableReason.FINGERPRINT_MISMATCH)
        } else {
            AgentMemorySemanticStatus.Ready(embedder.fingerprint.stableKey)
        }
        return AgentMemorySemanticSelection(
            source = AgentMemoryVectorCandidateSource(embedder, index),
            status = status
        )
    }
}

