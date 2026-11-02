package com.muyuchat.mca.agent.memory

import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Memory-space domain records and hard limits (plan §7.1/§7.3).
 *
 * Memory is a new Agent-owned domain. It never reads or writes the chat `memories`,
 * `assistant_memory_turns` or `knowledge_*` tables; those stay owned by their existing stores.
 * These records are persistence-agnostic and contain no Android type, so the store, the retriever
 * and the `memory.search` executor can be unit-tested without Room or a device.
 *
 * Every text bound is counted in UTF-8 bytes where the payload crosses storage, and in characters
 * where the value is a display/search field, matching the existing tool-argument rules.
 */
enum class AgentMemorySourceKind {
    /** Text typed or pasted by the user. */
    USER_TEXT,

    /** A UTF-8 text/Markdown file imported through the document picker. */
    FILE_IMPORT,

    /**
     * An item committed from a reviewed extraction candidate ([AgentMemoryExtractor]).
     *
     * The value is additive: rows are stored as text, so old databases keep reading and an unknown
     * value still falls back to [USER_TEXT].
     */
    AUTO_EXTRACTION;

    companion object {
        fun fromStored(stored: String): AgentMemorySourceKind =
            entries.firstOrNull { it.name == stored } ?: USER_TEXT
    }
}

/** One named memory space. `spaceId` is a generated stable id, never a path or user text. */
data class AgentMemorySpaceRecord(
    val spaceId: String,
    val displayName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long
)

/** One durable memory item. [revoked] items stay readable for audit but never reach retrieval. */
data class AgentMemoryItemRecord(
    val memoryId: String,
    val spaceId: String,
    val title: String,
    val text: String,
    val contentHash: String,
    val sourceKind: AgentMemorySourceKind,
    val sourceRef: String?,
    val tokenCount: Int,
    val chunkCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val revoked: Boolean,
    val revokedAt: Long?,
    /** Monotonic per-item revision used for update CAS (MEM-04). */
    val revision: Long = 1L
)

/** One searchable chunk plus the owning item's source hash (the dedupe identity). */
data class AgentMemoryChunkRecord(
    val chunkId: String,
    val memoryId: String,
    val spaceId: String,
    val position: Int,
    val text: String,
    val contentHash: String,
    val estimatedTokens: Int,
    val sourceHash: String
)

/** Insert payload of one chunk; [AgentMemoryChunkRecord] adds the joined source hash for reads. */
data class AgentMemoryChunkDraft(
    val chunkId: String,
    val memoryId: String,
    val spaceId: String,
    val position: Int,
    val text: String,
    val contentHash: String,
    val estimatedTokens: Int,
    val createdAt: Long
)

/** One bounded retrieval hit returned to the model. Content is an excerpt, never the full item. */
data class AgentMemoryCitation(
    val spaceId: String,
    val memoryId: String,
    val chunkId: String,
    val contentHash: String,
    val sourceHash: String,
    val excerpt: String,
    val score: Int,
    val estimatedTokens: Int
)

/** Which candidate sources contributed to one retrieval (lexical only, or fused with vectors). */
enum class AgentMemoryRetrievalMode {
    LEXICAL,
    HYBRID
}

/**
 * Result of one bounded retrieval.
 *
 * The default mode is [AgentMemoryRetrievalMode.LEXICAL] and [semanticFallback] is null, so a store
 * without a semantic source behaves exactly as before. [semanticCandidates] counts the semantic
 * chunks that actually contributed to the fused ranking (0 for lexical-only results).
 */
data class AgentMemorySearchResult(
    val citations: List<AgentMemoryCitation>,
    val truncated: Boolean,
    val scannedChunks: Int,
    val estimatedTokens: Int,
    val retrievalMode: AgentMemoryRetrievalMode = AgentMemoryRetrievalMode.LEXICAL,
    val semanticFallback: AgentMemorySemanticUnavailableReason? = null,
    val semanticCandidates: Int = 0
)

/**
 * One administration row: enough to identify and preview an item without shipping its full text.
 *
 * [excerpt] is bounded by [AgentMemoryLimits.MAX_ADMIN_EXCERPT_CHARS]; a surface that needs the full
 * body performs an explicit per-item read instead of receiving every body in a page.
 */
data class AgentMemoryItemSummary(
    val memoryId: String,
    val spaceId: String,
    val title: String,
    val excerpt: String,
    val contentHash: String,
    val sourceKind: AgentMemorySourceKind,
    val revision: Long,
    val updatedAt: Long,
    val revoked: Boolean,
    val hasSourceRef: Boolean
)

/** One bounded administration page plus the filtered total for the same filter. */
data class AgentMemoryItemPage(
    val spaceId: String,
    val items: List<AgentMemoryItemSummary>,
    val total: Int,
    val offset: Int,
    val limit: Int
) {
    val hasMore: Boolean get() = offset + items.size < total
}

/** Outcome of one administration page query. */
sealed interface AgentMemoryItemPageOutcome {
    data class Found(val page: AgentMemoryItemPage) : AgentMemoryItemPageOutcome

    data class Rejected(val error: AgentMemoryError) : AgentMemoryItemPageOutcome
}

/** Input of one bounded ingest. [text] is bounded by [AgentMemoryLimits.MAX_TEXT_BYTES]. */
data class AgentMemoryIngestRequest(
    val title: String,
    val text: String,
    val sourceKind: AgentMemorySourceKind,
    val sourceRef: String? = null
)

/** Stable, content-free memory failure. Messages never contain memory text or file paths. */
data class AgentMemoryError(
    val code: String,
    val safeMessage: String
) {
    init {
        require(CODE_PATTERN.matches(code)) { "memory error code must be UPPER_SNAKE_CASE" }
        require(safeMessage.isNotBlank() && safeMessage.length <= 200) {
            "memory error message must stay short"
        }
    }

    override fun toString(): String = "AgentMemoryError($code)"

    private companion object {
        val CODE_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,63}$")
    }
}

/** Stable codes used by the store and the tool; adding a code is an additive change. */
object AgentMemoryErrorCodes {
    const val SPACE_NOT_FOUND = "MEMORY_SPACE_NOT_FOUND"
    const val SPACE_LIMIT = "MEMORY_SPACE_LIMIT"
    const val SPACE_NAME_INVALID = "MEMORY_SPACE_NAME_INVALID"
    const val SELECTION_EMPTY = "MEMORY_SELECTION_EMPTY"
    const val SELECTION_TOO_LARGE = "MEMORY_SELECTION_TOO_LARGE"
    const val SELECTION_ALREADY_FROZEN = "MEMORY_SELECTION_ALREADY_FROZEN"
    const val SELECTION_REVOKED = "MEMORY_SELECTION_REVOKED"
    const val SPACE_NOT_SELECTED = "MEMORY_SPACE_NOT_SELECTED"
    const val ITEM_NOT_FOUND = "MEMORY_ITEM_NOT_FOUND"
    const val CITATION_STALE = "MEMORY_CITATION_STALE"
    const val REVISION_CONFLICT = "MEMORY_REVISION_CONFLICT"
    const val DUPLICATE_CONTENT = "MEMORY_DUPLICATE_CONTENT"
    const val ITEM_TEXT_TOO_LARGE = "MEMORY_TEXT_TOO_LARGE"
    const val TITLE_TOO_LARGE = "MEMORY_TITLE_TOO_LARGE"
    const val SOURCE_REF_TOO_LARGE = "MEMORY_SOURCE_REF_TOO_LARGE"
    const val TEXT_EMPTY = "MEMORY_TEXT_EMPTY"
    const val TEXT_NOT_UTF8 = "MEMORY_TEXT_NOT_UTF8"
    const val CHUNK_LIMIT = "MEMORY_CHUNK_LIMIT"
    const val ITEM_LIMIT = "MEMORY_ITEM_LIMIT"
    const val WRITE_NOT_APPROVED = "MEMORY_WRITE_NOT_APPROVED"
    const val QUERY_EMPTY = "MEMORY_QUERY_EMPTY"
    const val QUERY_TOO_LONG = "MEMORY_QUERY_TOO_LONG"
    const val MAX_ITEMS_INVALID = "MEMORY_MAX_ITEMS_INVALID"
    const val STORAGE_FAILED = "MEMORY_STORAGE_FAILED"

    /** Administration paging (N13/N16): a bounded page/offset request is a typed failure. */
    const val PAGE_INVALID = "MEMORY_PAGE_INVALID"

    /** Extraction (N16): request/source/candidate failures are distinct from ingest failures. */
    const val EXTRACTION_REQUEST_INVALID = "MEMORY_EXTRACTION_REQUEST_INVALID"
    const val EXTRACTION_SOURCE_TOO_LARGE = "MEMORY_EXTRACTION_SOURCE_TOO_LARGE"
    const val CANDIDATE_INVALID = "MEMORY_CANDIDATE_INVALID"
}

/** Hard limits. They are deliberately public so tests and admin surfaces can render them. */
object AgentMemoryLimits {
    /** Maximum number of spaces a database can hold (bounded admin surface). */
    const val MAX_SPACES = 64

    /** Maximum number of spaces one task may freeze (plan §5.1 example bound). */
    const val MAX_SELECTED_SPACES = 16

    /** Maximum number of live items per space. */
    const val MAX_ITEMS_PER_SPACE = 1_000

    /** Maximum UTF-8 size of one memory text (64 KiB). */
    const val MAX_TEXT_BYTES = 64 * 1024

    const val MAX_TITLE_CHARS = 200
    const val MAX_SOURCE_REF_CHARS = 512
    const val MAX_SPACE_NAME_CHARS = 80
    const val MAX_SPACE_ID_CHARS = 64

    /** Chunking bounds; a text over [MAX_CHUNKS_PER_ITEM] chunks is rejected, not silently cut. */
    const val CHUNK_TARGET_CHARS = 1_200
    const val CHUNK_OVERLAP_CHARS = 160
    const val MAX_CHUNKS_PER_ITEM = 256

    /** `memory.search` argument bounds. */
    const val MAX_QUERY_CHARS = 256
    const val MAX_MEMORY_ID_CHARS = 64
    const val MIN_MAX_ITEMS = 1
    const val MAX_MAX_ITEMS = 10

    /**
     * Retrieval budget (plan §7.3). The scan budget is independent from the candidate cap: the store
     * inspects up to [MAX_SCAN_CHUNKS] chunks to find query matches, keeps at most
     * [MAX_CANDIDATE_CHUNKS] of them for ranking, and returns at most
     * [MAX_RETURNED_CITATIONS]/[MAX_RETURNED_TOKENS].
     */
    const val MAX_CANDIDATE_CHUNKS = 64
    const val MAX_SCAN_CHUNKS = 4 * 1_024
    const val CHUNK_PAGE_SIZE = 256
    const val MAX_RETURNED_CITATIONS = 6
    const val MAX_RETURNED_TOKENS = 1_024
    const val MAX_EXCERPT_CHARS = 240

    /** Default citations when the model does not choose `maxItems`. */
    const val DEFAULT_MAX_ITEMS = 4

    /**
     * Hybrid retrieval (N14/N15) and extraction (N16) budgets.
     *
     * The semantic candidate cap is independent from the lexical scan/candidate caps: a hybrid query
     * fuses at most [MAX_SEMANTIC_CANDIDATES] vector candidates with the lexical ranking, using
     * reciprocal-rank fusion with [RRF_K]. The vector index is a bounded exact index in this slice;
     * [MAX_VECTOR_RECORDS] is the rebuild threshold, not a silent eviction policy.
     */
    const val MAX_SEMANTIC_CANDIDATES = 32
    const val RRF_K = 60
    const val MAX_VECTOR_RECORDS = 20_000

    /** Extraction bounds: candidates are reviewed before any write, so the caps stay small. */
    const val MAX_EXTRACTION_CANDIDATES = 24
    const val MAX_EXTRACTION_MODEL_PROPOSALS = 12
    const val MIN_EXTRACTION_CANDIDATE_CHARS = 4
    const val MAX_EXTRACTION_CANDIDATE_CHARS = 800

    /** Administration listing bounds (N13/N16): one page never becomes a full-table dump. */
    const val MAX_ADMIN_QUERY_CHARS = 128
    const val MIN_ADMIN_PAGE_SIZE = 1
    const val MAX_ADMIN_PAGE_SIZE = 50
    const val MAX_ADMIN_OFFSET = 10_000
    const val MAX_ADMIN_EXCERPT_CHARS = 160
}

/** Normalizes an imported text exactly once, before hashing, for every source kind. */
internal object AgentMemoryText {
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /**
     * Canonical text: strict UTF-8 (never silently replaced), BOM removed, CRLF folded and outer
     * whitespace trimmed. The same normalization feeds the hash and the chunker, so a re-import of
     * an unchanged file always dedupes.
     */
    fun normalize(text: String): String {
        val withoutBom = text.removePrefix("\uFEFF")
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n').trim()
    }

    fun normalizeStrictUtf8(bytes: ByteArray): String {
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw AgentMemoryTextException(
                AgentMemoryError(
                    AgentMemoryErrorCodes.TEXT_NOT_UTF8,
                    "Imported memory text is not valid UTF-8; convert it and import it again."
                )
            )
        }
        return normalize(decoded)
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    fun isSha256(value: String): Boolean = SHA256.matches(value)

    fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}

/** Internal typed failure so a store method can map it to a user-facing outcome. */
internal class AgentMemoryTextException(val error: AgentMemoryError) : Exception(error.safeMessage)
