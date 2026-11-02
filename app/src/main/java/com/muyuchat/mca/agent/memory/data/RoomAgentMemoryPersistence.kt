package com.muyuchat.mca.agent.memory.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.muyuchat.mca.agent.memory.AgentMemoryChunkDraft
import com.muyuchat.mca.agent.memory.AgentMemoryChunkRecord
import com.muyuchat.mca.agent.memory.AgentMemoryItemRecord
import com.muyuchat.mca.agent.memory.AgentMemoryPersistence
import com.muyuchat.mca.agent.memory.AgentMemorySourceKind
import com.muyuchat.mca.agent.memory.AgentMemorySpaceRecord
import com.muyuchat.mca.agent.memory.AgentMemoryStoredSelection
import com.muyuchat.mca.agent.memory.AgentMemoryUpdateOutcome

/**
 * Room implementation of [AgentMemoryPersistence].
 *
 * Owns entity ↔ domain mapping and the two multi-row transactions (item + chunks, space deletion).
 * It is created once by the app composition root over the shared `mca.db`; the memory store and the
 * `memory.search` executor share that one instance, so there is no second database handle.
 */
class RoomAgentMemoryPersistence(
    private val database: RoomDatabase,
    private val dao: AgentMemoryDao
) : AgentMemoryPersistence {

    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction { block() }

    override suspend fun insertSpace(space: AgentMemorySpaceRecord) {
        dao.insertSpace(space.toEntity())
    }

    override suspend fun findSpace(spaceId: String): AgentMemorySpaceRecord? =
        dao.findSpace(spaceId)?.toRecord()

    override suspend fun listSpaces(limit: Int): List<AgentMemorySpaceRecord> =
        dao.listSpaces(limit).map { it.toRecord() }

    override suspend fun existingSpaceIds(spaceIds: List<String>): Set<String> =
        if (spaceIds.isEmpty()) emptySet() else dao.existingSpaceIds(spaceIds).toSet()

    override suspend fun deleteSpace(spaceId: String): Int = dao.deleteSpaceRow(spaceId)

    override suspend fun insertItemWithChunks(item: AgentMemoryItemRecord, chunks: List<AgentMemoryChunkDraft>) {
        database.withTransaction {
            dao.insertItem(item.toEntity())
            if (chunks.isNotEmpty()) dao.insertChunks(chunks.map { it.toEntity() })
        }
    }

    override suspend fun findItem(memoryId: String): AgentMemoryItemRecord? = dao.findItem(memoryId)?.toRecord()

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
    ): com.muyuchat.mca.agent.memory.AgentMemoryUpdateOutcome {
        var outcome: com.muyuchat.mca.agent.memory.AgentMemoryUpdateOutcome =
            com.muyuchat.mca.agent.memory.AgentMemoryUpdateOutcome.NotFound
        try {
            database.withTransaction {
                val item = dao.findItem(memoryId)
                if (item == null || item.spaceId != spaceId || item.revoked != 0) return@withTransaction
                if (item.revision != expectedRevision) {
                    outcome = com.muyuchat.mca.agent.memory.AgentMemoryUpdateOutcome.Conflict
                    return@withTransaction
                }
                val rows = dao.updateItemCas(
                    spaceId = spaceId,
                    memoryId = memoryId,
                    expectedRevision = expectedRevision,
                    title = title,
                    text = text,
                    contentHash = contentHash,
                    tokenCount = tokenCount,
                    chunkCount = chunkCount,
                    updatedAt = updatedAt
                )
                if (rows != 1) {
                    outcome = com.muyuchat.mca.agent.memory.AgentMemoryUpdateOutcome.Conflict
                    return@withTransaction
                }
                dao.deleteChunksForItem(memoryId)
                if (chunks.isNotEmpty()) dao.insertChunks(chunks.map { it.toEntity() })
                outcome = com.muyuchat.mca.agent.memory.AgentMemoryUpdateOutcome.Updated(
                    expectedRevision + 1L
                )
            }
        } catch (failure: Exception) {
            // A duplicate (spaceId, contentHash) is a storage unique-index violation; report it as a
            // typed content conflict instead of leaking SQL details.
            return com.muyuchat.mca.agent.memory.AgentMemoryUpdateOutcome.DuplicateContent
        }
        return outcome
    }

    override suspend fun findItemBySourceHash(spaceId: String, sourceHash: String): AgentMemoryItemRecord? =
        dao.findItemByContentHash(spaceId, sourceHash)?.toRecord()

    override suspend fun revokeItem(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        atMillis: Long
    ): AgentMemoryUpdateOutcome = casTransition(
        spaceId = spaceId,
        memoryId = memoryId,
        expectedRevision = expectedRevision,
        matching = { item -> item.revoked == 0 },
        update = { dao.revokeItemCas(spaceId, memoryId, expectedRevision, atMillis) }
    )

    override suspend fun restoreItem(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        atMillis: Long
    ): AgentMemoryUpdateOutcome = casTransition(
        spaceId = spaceId,
        memoryId = memoryId,
        expectedRevision = expectedRevision,
        matching = { item -> item.revoked == 1 },
        update = { dao.restoreItemCas(spaceId, memoryId, expectedRevision, atMillis) }
    )

    /**
     * Shared atomic CAS transition for the tombstone pair.
     *
     * The pre-read and the conditional update run in one transaction so the outcome taxonomy is
     * exact: missing/foreign/wrong-state rows are `NotFound`, a different revision is `Conflict`,
     * and only a real single-row update is `Updated(revision + 1)`.
     */
    private suspend fun casTransition(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        matching: (AgentMemoryItemEntity) -> Boolean,
        update: suspend () -> Int
    ): AgentMemoryUpdateOutcome {
        var outcome: AgentMemoryUpdateOutcome = AgentMemoryUpdateOutcome.NotFound
        database.withTransaction {
            val item = dao.findItem(memoryId)
            if (item == null || item.spaceId != spaceId || !matching(item)) return@withTransaction
            if (item.revision != expectedRevision) {
                outcome = AgentMemoryUpdateOutcome.Conflict
                return@withTransaction
            }
            if (update() == 1) outcome = AgentMemoryUpdateOutcome.Updated(expectedRevision + 1L)
        }
        return outcome
    }

    /**
     * Deletes one item and its chunks only when the item really belongs to [spaceId].
     *
     * The ownership check and the child-row deletion share one transaction: a mismatched
     * space/item pair (for example space A with an item id from space B) must leave every row of
     * the other space untouched instead of deleting chunks by item id first.
     */
    override suspend fun deleteItemWithChunks(spaceId: String, memoryId: String): Int {
        var removed = 0
        database.withTransaction {
            val item = dao.findItem(memoryId)
            if (item == null || item.spaceId != spaceId) return@withTransaction
            dao.deleteChunksForItem(memoryId)
            removed = dao.deleteItemRow(spaceId, memoryId)
        }
        return removed
    }

    override suspend fun deleteChunksForSpace(spaceId: String): Int = dao.deleteChunksForSpace(spaceId)

    override suspend fun deleteItemsForSpace(spaceId: String): Int = dao.deleteItemsForSpace(spaceId)

    override suspend fun liveItemCount(spaceId: String): Int = dao.liveItemCount(spaceId)

    override suspend fun listItemPage(
        spaceId: String,
        query: String?,
        includeRevoked: Boolean,
        limit: Int,
        offset: Int
    ): List<AgentMemoryItemRecord> =
        dao.listItemPage(
            spaceId = spaceId,
            pattern = likePattern(query),
            includeRevoked = if (includeRevoked) 1 else 0,
            limit = limit,
            offset = offset
        ).map { it.toRecord() }

    override suspend fun countItems(spaceId: String, query: String?, includeRevoked: Boolean): Int =
        dao.countItems(
            spaceId = spaceId,
            pattern = likePattern(query),
            includeRevoked = if (includeRevoked) 1 else 0
        )

    /** Escapes LIKE metacharacters so a keyword is matched literally; null/blank means no filter. */
    private fun likePattern(query: String?): String? {
        val trimmed = query?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val escaped = buildString(trimmed.length + 2) {
            trimmed.forEach { character ->
                when (character) {
                    '\\', '%', '_' -> {
                        append('\\')
                        append(character)
                    }
                    else -> append(character)
                }
            }
        }
        return "%$escaped%"
    }

    override suspend fun liveChunkPage(
        spaceIds: List<String>,
        limit: Int,
        offset: Int
    ): List<AgentMemoryChunkRecord> =
        if (spaceIds.isEmpty()) emptyList() else dao.liveChunkPage(spaceIds, limit, offset)

    override suspend fun liveChunks(
        spaceIds: List<String>,
        chunkIds: List<String>
    ): List<AgentMemoryChunkRecord> =
        if (spaceIds.isEmpty() || chunkIds.isEmpty()) {
            emptyList()
        } else {
            dao.liveChunks(spaceIds, chunkIds)
        }

    override suspend fun liveChunkCount(spaceIds: List<String>): Int =
        if (spaceIds.isEmpty()) 0 else dao.liveChunkCount(spaceIds)

    override suspend fun insertSelection(selection: AgentMemoryStoredSelection) {
        dao.insertSelection(selection.toEntity())
    }

    override suspend fun findSelection(taskId: String): AgentMemoryStoredSelection? =
        dao.findSelection(taskId)?.toRecord()

    override suspend fun revokeSelection(taskId: String, atMillis: Long): Int =
        dao.revokeSelection(taskId, atMillis)
}

internal fun AgentMemorySpaceRecord.toEntity(): AgentMemorySpaceEntity = AgentMemorySpaceEntity(
    spaceId = spaceId,
    displayName = displayName,
    createdAt = createdAt,
    updatedAt = updatedAt,
    revision = revision
)

internal fun AgentMemorySpaceEntity.toRecord(): AgentMemorySpaceRecord = AgentMemorySpaceRecord(
    spaceId = spaceId,
    displayName = displayName,
    createdAt = createdAt,
    updatedAt = updatedAt,
    revision = revision
)

internal fun AgentMemoryItemRecord.toEntity(): AgentMemoryItemEntity = AgentMemoryItemEntity(
    memoryId = memoryId,
    spaceId = spaceId,
    title = title,
    text = text,
    contentHash = contentHash,
    sourceKind = sourceKind.name,
    sourceRef = sourceRef,
    tokenCount = tokenCount,
    chunkCount = chunkCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    revoked = if (revoked) 1 else 0,
    revokedAt = revokedAt,
    revision = revision
)

internal fun AgentMemoryItemEntity.toRecord(): AgentMemoryItemRecord = AgentMemoryItemRecord(
    memoryId = memoryId,
    spaceId = spaceId,
    title = title,
    text = text,
    contentHash = contentHash,
    sourceKind = AgentMemorySourceKind.fromStored(sourceKind),
    sourceRef = sourceRef,
    tokenCount = tokenCount,
    chunkCount = chunkCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    revoked = revoked != 0,
    revokedAt = revokedAt,
    revision = revision
)

internal fun AgentMemoryChunkDraft.toEntity(): AgentMemoryChunkEntity = AgentMemoryChunkEntity(
    chunkId = chunkId,
    memoryId = memoryId,
    spaceId = spaceId,
    position = position,
    text = text,
    contentHash = contentHash,
    estimatedTokens = estimatedTokens,
    createdAt = createdAt
)

internal fun AgentMemoryStoredSelection.toEntity(): AgentMemoryTaskSelectionEntity = AgentMemoryTaskSelectionEntity(
    taskId = taskId,
    spaceIdsJson = spaceIdsJson,
    selectionHash = selectionHash,
    createdAt = createdAt,
    revoked = if (revoked) 1 else 0,
    revokedAt = revokedAt
)

internal fun AgentMemoryTaskSelectionEntity.toRecord(): AgentMemoryStoredSelection = AgentMemoryStoredSelection(
    taskId = taskId,
    spaceIdsJson = spaceIdsJson,
    selectionHash = selectionHash,
    createdAt = createdAt,
    revoked = revoked != 0,
    revokedAt = revokedAt
)
