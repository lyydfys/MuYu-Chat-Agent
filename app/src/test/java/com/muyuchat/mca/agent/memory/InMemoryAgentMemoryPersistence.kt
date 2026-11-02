package com.muyuchat.mca.agent.memory

/**
 * Deterministic in-memory [AgentMemoryPersistence] for JVM tests.
 *
 * It mirrors the Room implementation's contract: unique `(spaceId, contentHash)`, insert-only
 * selections, revocation as a tombstone, and `(spaceId, memoryId, position)` chunk paging that skips
 * revoked items. It also exposes injected failures so the store's error mapping can be tested
 * without a database.
 */
internal class InMemoryAgentMemoryPersistence : AgentMemoryPersistence {
    private val spaces = LinkedHashMap<String, AgentMemorySpaceRecord>()
    private val items = LinkedHashMap<String, AgentMemoryItemRecord>()
    private val chunks = LinkedHashMap<String, AgentMemoryChunkDraft>()
    private val selections = LinkedHashMap<String, AgentMemoryStoredSelection>()

    /** When set, the next write throws instead of committing (storage-failure tests). */
    var failWrites: Boolean = false

    var transactionCount: Int = 0
        private set

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        transactionCount++
        return block()
    }

    override suspend fun insertSpace(space: AgentMemorySpaceRecord) {
        checkWritable()
        check(!spaces.containsKey(space.spaceId)) { "duplicate space" }
        spaces[space.spaceId] = space
    }

    override suspend fun findSpace(spaceId: String): AgentMemorySpaceRecord? = spaces[spaceId]

    override suspend fun listSpaces(limit: Int): List<AgentMemorySpaceRecord> =
        spaces.values.sortedWith(compareBy({ it.createdAt }, { it.spaceId })).take(limit)

    override suspend fun existingSpaceIds(spaceIds: List<String>): Set<String> =
        spaceIds.filterTo(LinkedHashSet()) { spaces.containsKey(it) }

    override suspend fun deleteSpace(spaceId: String): Int =
        if (spaces.remove(spaceId) != null) 1 else 0

    override suspend fun insertItemWithChunks(item: AgentMemoryItemRecord, chunks: List<AgentMemoryChunkDraft>) {
        checkWritable()
        check(items.values.none { it.spaceId == item.spaceId && it.contentHash == item.contentHash }) {
            "duplicate (spaceId, contentHash)"
        }
        items[item.memoryId] = item
        chunks.forEach { chunk -> this.chunks[chunk.chunkId] = chunk }
    }

    override suspend fun findItem(memoryId: String): AgentMemoryItemRecord? = items[memoryId]

    override suspend fun updateItemWithChunks(
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
    ): AgentMemoryUpdateOutcome {
        checkWritable()
        val current = items[memoryId] ?: return AgentMemoryUpdateOutcome.NotFound
        if (current.spaceId != spaceId || current.revoked) return AgentMemoryUpdateOutcome.NotFound
        if (current.revision != expectedRevision) return AgentMemoryUpdateOutcome.Conflict
        if (items.values.any {
                it.memoryId != memoryId && it.spaceId == spaceId && it.contentHash == contentHash
            }
        ) {
            return AgentMemoryUpdateOutcome.DuplicateContent
        }
        items[memoryId] = current.copy(
            title = title,
            text = text,
            contentHash = contentHash,
            tokenCount = tokenCount,
            chunkCount = chunkCount,
            updatedAt = updatedAt,
            revision = expectedRevision + 1L
        )
        this.chunks.entries.removeAll { entry -> entry.value.memoryId == memoryId }
        chunks.forEach { chunk -> this.chunks[chunk.chunkId] = chunk }
        return AgentMemoryUpdateOutcome.Updated(expectedRevision + 1L)
    }

    override suspend fun findItemBySourceHash(spaceId: String, sourceHash: String): AgentMemoryItemRecord? =
        items.values.firstOrNull { it.spaceId == spaceId && it.contentHash == sourceHash }

    override suspend fun revokeItem(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        atMillis: Long
    ): AgentMemoryUpdateOutcome {
        checkWritable()
        val current = items[memoryId] ?: return AgentMemoryUpdateOutcome.NotFound
        if (current.spaceId != spaceId || current.revoked) return AgentMemoryUpdateOutcome.NotFound
        if (current.revision != expectedRevision) return AgentMemoryUpdateOutcome.Conflict
        items[memoryId] = current.copy(
            revoked = true,
            revokedAt = atMillis,
            updatedAt = atMillis,
            revision = current.revision + 1L
        )
        return AgentMemoryUpdateOutcome.Updated(current.revision + 1L)
    }

    override suspend fun restoreItem(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        atMillis: Long
    ): AgentMemoryUpdateOutcome {
        checkWritable()
        val current = items[memoryId] ?: return AgentMemoryUpdateOutcome.NotFound
        if (current.spaceId != spaceId || !current.revoked) return AgentMemoryUpdateOutcome.NotFound
        if (current.revision != expectedRevision) return AgentMemoryUpdateOutcome.Conflict
        items[memoryId] = current.copy(
            revoked = false,
            revokedAt = null,
            updatedAt = atMillis,
            revision = current.revision + 1L
        )
        return AgentMemoryUpdateOutcome.Updated(current.revision + 1L)
    }

    override suspend fun deleteItemWithChunks(spaceId: String, memoryId: String): Int {
        checkWritable()
        val current = items[memoryId] ?: return 0
        if (current.spaceId != spaceId) return 0
        items.remove(memoryId)
        chunks.entries.removeAll { entry -> entry.value.memoryId == memoryId }
        return 1
    }

    override suspend fun deleteChunksForSpace(spaceId: String): Int {
        checkWritable()
        val before = chunks.size
        chunks.entries.removeAll { entry -> entry.value.spaceId == spaceId }
        return before - chunks.size
    }

    override suspend fun deleteItemsForSpace(spaceId: String): Int {
        checkWritable()
        val before = items.size
        items.entries.removeAll { entry -> entry.value.spaceId == spaceId }
        return before - items.size
    }

    override suspend fun liveItemCount(spaceId: String): Int =
        items.values.count { it.spaceId == spaceId && !it.revoked }

    override suspend fun listItemPage(
        spaceId: String,
        query: String?,
        includeRevoked: Boolean,
        limit: Int,
        offset: Int
    ): List<AgentMemoryItemRecord> = matchingItems(spaceId, query, includeRevoked)
        .drop(offset)
        .take(limit)
        .map { it.copy() }

    override suspend fun countItems(spaceId: String, query: String?, includeRevoked: Boolean): Int =
        matchingItems(spaceId, query, includeRevoked).size

    private fun matchingItems(
        spaceId: String,
        query: String?,
        includeRevoked: Boolean
    ): List<AgentMemoryItemRecord> {
        val keyword = query?.trim()?.takeIf(String::isNotEmpty)
        return items.values
            .asSequence()
            .filter { item -> item.spaceId == spaceId && (includeRevoked || !item.revoked) }
            .filter { item ->
                keyword == null ||
                    item.title.contains(keyword, ignoreCase = true) ||
                    item.text.contains(keyword, ignoreCase = true)
            }
            .sortedWith(compareByDescending<AgentMemoryItemRecord> { it.updatedAt }.thenBy { it.memoryId })
            .toList()
    }

    override suspend fun liveChunkPage(
        spaceIds: List<String>,
        limit: Int,
        offset: Int
    ): List<AgentMemoryChunkRecord> {
        val live = chunks.values
            .filter { chunk -> chunk.spaceId in spaceIds && items[chunk.memoryId]?.revoked == false }
            .sortedWith(compareBy({ it.spaceId }, { it.memoryId }, { it.position }))
            .drop(offset)
            .take(limit)
        return live.map { chunk ->
            AgentMemoryChunkRecord(
                chunkId = chunk.chunkId,
                memoryId = chunk.memoryId,
                spaceId = chunk.spaceId,
                position = chunk.position,
                text = chunk.text,
                contentHash = chunk.contentHash,
                estimatedTokens = chunk.estimatedTokens,
                sourceHash = items.getValue(chunk.memoryId).contentHash
            )
        }
    }

    override suspend fun liveChunks(
        spaceIds: List<String>,
        chunkIds: List<String>
    ): List<AgentMemoryChunkRecord> {
        if (spaceIds.isEmpty() || chunkIds.isEmpty()) return emptyList()
        val wanted = chunkIds.toSet()
        return chunks.values
            .filter { chunk ->
                chunk.chunkId in wanted &&
                    chunk.spaceId in spaceIds &&
                    items[chunk.memoryId]?.revoked == false
            }
            .sortedWith(compareBy({ it.spaceId }, { it.memoryId }, { it.position }))
            .map { chunk ->
                AgentMemoryChunkRecord(
                    chunkId = chunk.chunkId,
                    memoryId = chunk.memoryId,
                    spaceId = chunk.spaceId,
                    position = chunk.position,
                    text = chunk.text,
                    contentHash = chunk.contentHash,
                    estimatedTokens = chunk.estimatedTokens,
                    sourceHash = items.getValue(chunk.memoryId).contentHash
                )
            }
    }

    override suspend fun liveChunkCount(spaceIds: List<String>): Int =
        chunks.values.count { chunk -> chunk.spaceId in spaceIds && items[chunk.memoryId]?.revoked == false }

    override suspend fun insertSelection(selection: AgentMemoryStoredSelection) {
        checkWritable()
        check(!selections.containsKey(selection.taskId)) { "duplicate selection" }
        selections[selection.taskId] = selection
    }

    override suspend fun findSelection(taskId: String): AgentMemoryStoredSelection? = selections[taskId]

    override suspend fun revokeSelection(taskId: String, atMillis: Long): Int {
        checkWritable()
        val current = selections[taskId] ?: return 0
        if (current.revoked) return 0
        selections[taskId] = current.copy(revoked = true, revokedAt = atMillis)
        return 1
    }

    /** Direct seeding for retrieval tests: bypasses the ingest API but keeps the same invariants. */
    fun seedSpace(spaceId: String, displayName: String = spaceId, createdAt: Long = 1L) {
        spaces[spaceId] = AgentMemorySpaceRecord(spaceId, displayName, createdAt, createdAt, 1L)
    }

    /** Corrupts/replaces a stored selection so tamper handling can be tested. */
    fun overwriteSelectionForTest(selection: AgentMemoryStoredSelection) {
        selections[selection.taskId] = selection
    }

    fun seedItem(memoryId: String, spaceId: String, text: String, revoked: Boolean = false) {
        val chunks = AgentMemoryChunker.chunk(memoryId, spaceId, text, 1L)
        items[memoryId] = AgentMemoryItemRecord(
            memoryId = memoryId,
            spaceId = spaceId,
            title = "seed",
            text = text,
            contentHash = AgentMemoryText.sha256(text),
            sourceKind = AgentMemorySourceKind.USER_TEXT,
            sourceRef = null,
            tokenCount = chunks.sumOf { it.estimatedTokens },
            chunkCount = chunks.size,
            createdAt = 1L,
            updatedAt = 1L,
            revoked = revoked,
            revokedAt = if (revoked) 1L else null
        )
        chunks.forEach { chunk -> this.chunks[chunk.chunkId] = chunk }
    }

    private fun checkWritable() {
        check(!failWrites) { "injected storage failure" }
    }
}
