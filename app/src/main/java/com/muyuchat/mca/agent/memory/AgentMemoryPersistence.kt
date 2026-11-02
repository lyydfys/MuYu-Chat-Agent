package com.muyuchat.mca.agent.memory

/**
 * Persistence seam for the memory domain (plan §7.1).
 *
 * The store depends only on this port, exactly like the Agent journal depends on its own small
 * ports. The Room implementation lives in [com.muyuchat.mca.agent.memory.data] and owns entity
 * mapping plus transactions; unit tests use a deterministic in-memory implementation.
 *
 * Invariants every implementation must keep:
 * - [insertItemWithChunks] is atomic, and a duplicate `(spaceId, contentHash)` pair is rejected by
 *   the storage unique index rather than silently merged;
 * - [revokeItem]/[restoreItem] are revision-CAS tombstone transitions: `revoked=1`+`revokedAt` and
 *   the reverse, each with a revision bump; every read path excludes revoked items unless a
 *   management query explicitly asks for them;
 * - [liveChunkPage] returns only chunks of non-revoked items, ordered by
 *   `(spaceId, memoryId, position)` so paging is deterministic;
 * - [listItemPage]/[countItems] are bounded, space-scoped management reads; the keyword filter is a
 *   literal (not a caller-built SQL fragment) and never crosses a space boundary;
 * - selection rows are insert-only per task (the frozen scope) and can only be revoked.
 */
interface AgentMemoryPersistence {
    suspend fun <T> inTransaction(block: suspend () -> T): T

    suspend fun insertSpace(space: AgentMemorySpaceRecord)

    suspend fun findSpace(spaceId: String): AgentMemorySpaceRecord?

    suspend fun listSpaces(limit: Int): List<AgentMemorySpaceRecord>

    suspend fun existingSpaceIds(spaceIds: List<String>): Set<String>

    suspend fun deleteSpace(spaceId: String): Int

    suspend fun insertItemWithChunks(item: AgentMemoryItemRecord, chunks: List<AgentMemoryChunkDraft>)

    /**
     * Revision-CAS update of one live item's title/text/hash and its chunks.
     *
     * Implementations must replace chunks inside the same transaction as the item update and must
     * never overwrite a revoked item. A duplicate `(spaceId, contentHash)` is reported, not merged.
     */
    suspend fun updateItemWithChunks(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        title: String,
        text: String,
        contentHash: String,
        tokenCount: Int,
        chunkCount: Int,
        chunks: List<AgentMemoryChunkDraft>,
        updatedAt: Long
    ): AgentMemoryUpdateOutcome

    suspend fun findItem(memoryId: String): AgentMemoryItemRecord?

    suspend fun findItemBySourceHash(spaceId: String, sourceHash: String): AgentMemoryItemRecord?

    /**
     * Revision-CAS revoke of one live item in [spaceId].
     *
     * A missing/foreign/already-revoked item is [AgentMemoryUpdateOutcome.NotFound]; a different
     * revision is [AgentMemoryUpdateOutcome.Conflict] and leaves the row untouched.
     */
    suspend fun revokeItem(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        atMillis: Long
    ): AgentMemoryUpdateOutcome

    /**
     * Revision-CAS restore of one revoked item in [spaceId] (tombstone reversal).
     *
     * A missing/foreign/live item is [AgentMemoryUpdateOutcome.NotFound]; a different revision is
     * [AgentMemoryUpdateOutcome.Conflict]. Chunks are not rewritten: revocation never deleted them.
     */
    suspend fun restoreItem(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        atMillis: Long
    ): AgentMemoryUpdateOutcome

    suspend fun deleteItemWithChunks(spaceId: String, memoryId: String): Int

    suspend fun deleteChunksForSpace(spaceId: String): Int

    suspend fun deleteItemsForSpace(spaceId: String): Int

    suspend fun liveItemCount(spaceId: String): Int

    /**
     * Bounded management page inside one space, newest update first.
     *
     * [query] is a literal substring filter over title/text (implementations fold case where the
     * storage supports it); null/blank means "no keyword". [includeRevoked] must not be combined
     * with implicit widening: the space id is always required.
     */
    suspend fun listItemPage(
        spaceId: String,
        query: String?,
        includeRevoked: Boolean,
        limit: Int,
        offset: Int
    ): List<AgentMemoryItemRecord>

    /** Count of rows matching the same filter as [listItemPage]. */
    suspend fun countItems(spaceId: String, query: String?, includeRevoked: Boolean): Int

    suspend fun liveChunkPage(spaceIds: List<String>, limit: Int, offset: Int): List<AgentMemoryChunkRecord>

    /**
     * Live chunks by id, restricted to [spaceIds].
     *
     * Used by hybrid retrieval to turn identity-only semantic hits back into authoritative text:
     * a chunk id that is unknown, belongs to another space, belongs to a revoked item or was deleted
     * after indexing is simply absent from the result. The implementation must never return a row
     * outside [spaceIds].
     */
    suspend fun liveChunks(spaceIds: List<String>, chunkIds: List<String>): List<AgentMemoryChunkRecord>

    suspend fun liveChunkCount(spaceIds: List<String>): Int

    suspend fun insertSelection(selection: AgentMemoryStoredSelection)

    suspend fun findSelection(taskId: String): AgentMemoryStoredSelection?

    suspend fun revokeSelection(taskId: String, atMillis: Long): Int
}

/** Outcome of one revision-CAS item update (MEM-04). */
sealed interface AgentMemoryUpdateOutcome {
    data class Updated(val revision: Long) : AgentMemoryUpdateOutcome

    data object NotFound : AgentMemoryUpdateOutcome

    data object Conflict : AgentMemoryUpdateOutcome

    data object DuplicateContent : AgentMemoryUpdateOutcome
}

/** Persisted frozen selection row. The JSON is the canonical sorted id array. */
data class AgentMemoryStoredSelection(
    val taskId: String,
    val spaceIdsJson: String,
    val selectionHash: String,
    val createdAt: Long,
    val revoked: Boolean,
    val revokedAt: Long?
)

/** Turns a stored selection row back into the frozen value object; a corrupt row is rejected. */
internal object AgentMemorySelectionCodec {
    fun encode(selection: AgentMemorySelection): String = selection.toJson()

    fun hash(json: String): String = AgentMemoryText.sha256(json)

    fun decode(stored: AgentMemoryStoredSelection): AgentMemorySelection? {
        if (stored.selectionHash != hash(stored.spaceIdsJson)) return null
        val ids = runCatching {
            val array = org.json.JSONArray(stored.spaceIdsJson)
            (0 until array.length()).map { index -> array.getString(index) }
        }.getOrNull() ?: return null
        return runCatching { AgentMemorySelection.of(ids) }.getOrNull()
            ?.takeIf { it.toJson() == stored.spaceIdsJson }
    }
}
