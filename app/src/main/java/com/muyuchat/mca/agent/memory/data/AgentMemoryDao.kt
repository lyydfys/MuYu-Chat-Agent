package com.muyuchat.mca.agent.memory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.muyuchat.mca.agent.memory.AgentMemoryChunkRecord

/**
 * Bounded DAO for the four memory tables (plan §7.1).
 *
 * Every query is scoped (`spaceId`, `taskId`), paged or single-row: there is no "select all memory"
 * statement and no query that crosses space boundaries implicitly. Multi-statement atomicity is
 * owned by [RoomAgentMemoryPersistence]'s transaction, so this interface stays declarative.
 */
@Dao
interface AgentMemoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSpace(space: AgentMemorySpaceEntity)

    @Query("SELECT * FROM agent_memory_spaces WHERE spaceId = :spaceId LIMIT 1")
    suspend fun findSpace(spaceId: String): AgentMemorySpaceEntity?

    @Query("SELECT * FROM agent_memory_spaces ORDER BY createdAt ASC, spaceId ASC LIMIT :limit")
    suspend fun listSpaces(limit: Int): List<AgentMemorySpaceEntity>

    @Query("SELECT spaceId FROM agent_memory_spaces WHERE spaceId IN (:spaceIds)")
    suspend fun existingSpaceIds(spaceIds: List<String>): List<String>

    @Query("DELETE FROM agent_memory_spaces WHERE spaceId = :spaceId")
    suspend fun deleteSpaceRow(spaceId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: AgentMemoryItemEntity)

    @Query("SELECT * FROM agent_memory_items WHERE memoryId = :memoryId LIMIT 1")
    suspend fun findItem(memoryId: String): AgentMemoryItemEntity?

    @Query("SELECT * FROM agent_memory_items WHERE spaceId = :spaceId AND contentHash = :contentHash LIMIT 1")
    suspend fun findItemByContentHash(spaceId: String, contentHash: String): AgentMemoryItemEntity?

    /**
     * Revision-CAS revoke (tombstone) of one live item.
     *
     * A zero-row result means "not found, foreign space, already revoked, or stale revision"; the
     * persistence adapter re-reads the row to distinguish those into typed outcomes.
     */
    @Query(
        "UPDATE agent_memory_items SET revoked = 1, revokedAt = :atMillis, updatedAt = :atMillis, " +
            "revision = revision + 1 " +
            "WHERE spaceId = :spaceId AND memoryId = :memoryId AND revision = :expectedRevision " +
            "AND revoked = 0"
    )
    suspend fun revokeItemCas(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        atMillis: Long
    ): Int

    /** Revision-CAS restore (undelete) of one revoked item; chunks were never deleted. */
    @Query(
        "UPDATE agent_memory_items SET revoked = 0, revokedAt = NULL, updatedAt = :atMillis, " +
            "revision = revision + 1 " +
            "WHERE spaceId = :spaceId AND memoryId = :memoryId AND revision = :expectedRevision " +
            "AND revoked = 1"
    )
    suspend fun restoreItemCas(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        atMillis: Long
    ): Int

    @Query(
        "UPDATE agent_memory_items SET title = :title, text = :text, contentHash = :contentHash, " +
            "tokenCount = :tokenCount, chunkCount = :chunkCount, updatedAt = :updatedAt, " +
            "revision = revision + 1 " +
            "WHERE spaceId = :spaceId AND memoryId = :memoryId AND revision = :expectedRevision " +
            "AND revoked = 0"
    )
    suspend fun updateItemCas(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        title: String,
        text: String,
        contentHash: String,
        tokenCount: Int,
        chunkCount: Int,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM agent_memory_items WHERE spaceId = :spaceId AND memoryId = :memoryId")
    suspend fun deleteItemRow(spaceId: String, memoryId: String): Int

    @Query("DELETE FROM agent_memory_items WHERE spaceId = :spaceId")
    suspend fun deleteItemsForSpace(spaceId: String): Int

    @Query("SELECT COUNT(*) FROM agent_memory_items WHERE spaceId = :spaceId AND revoked = 0")
    suspend fun liveItemCount(spaceId: String): Int

    /**
     * Bounded management page inside one space (N13/N16), newest update first.
     *
     * [pattern] is either null (no keyword) or an already-escaped LIKE pattern supplied by
     * [RoomAgentMemoryPersistence]; the caller never concatenates user text into SQL. `ESCAPE '\'`
     * keeps `%`/`_` in the user's keyword literal.
     */
    @Query(
        "SELECT * FROM agent_memory_items WHERE spaceId = :spaceId " +
            "AND (:includeRevoked = 1 OR revoked = 0) " +
            "AND (:pattern IS NULL OR title LIKE :pattern ESCAPE '\\' OR text LIKE :pattern ESCAPE '\\') " +
            "ORDER BY updatedAt DESC, memoryId ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun listItemPage(
        spaceId: String,
        pattern: String?,
        includeRevoked: Int,
        limit: Int,
        offset: Int
    ): List<AgentMemoryItemEntity>

    @Query(
        "SELECT COUNT(*) FROM agent_memory_items WHERE spaceId = :spaceId " +
            "AND (:includeRevoked = 1 OR revoked = 0) " +
            "AND (:pattern IS NULL OR title LIKE :pattern ESCAPE '\\' OR text LIKE :pattern ESCAPE '\\')"
    )
    suspend fun countItems(spaceId: String, pattern: String?, includeRevoked: Int): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChunks(chunks: List<AgentMemoryChunkEntity>)

    @Query("DELETE FROM agent_memory_chunks WHERE memoryId = :memoryId")
    suspend fun deleteChunksForItem(memoryId: String): Int

    @Query("DELETE FROM agent_memory_chunks WHERE spaceId = :spaceId")
    suspend fun deleteChunksForSpace(spaceId: String): Int

    /**
     * Live chunks inside the given spaces, ordered by `(spaceId, memoryId, position)`.
     *
     * The join is what excludes a revoked item's chunks and supplies the item's source hash, so the
     * retrieval path never needs a second per-item query.
     */
    @Query(
        "SELECT c.chunkId AS chunkId, c.memoryId AS memoryId, c.spaceId AS spaceId, " +
            "c.position AS position, c.text AS text, c.contentHash AS contentHash, " +
            "c.estimatedTokens AS estimatedTokens, i.contentHash AS sourceHash " +
            "FROM agent_memory_chunks AS c INNER JOIN agent_memory_items AS i ON i.memoryId = c.memoryId " +
            "WHERE i.revoked = 0 AND c.spaceId IN (:spaceIds) " +
            "ORDER BY c.spaceId ASC, c.memoryId ASC, c.position ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun liveChunkPage(spaceIds: List<String>, limit: Int, offset: Int): List<AgentMemoryChunkRecord>

    /**
     * Live chunks by id inside the given spaces (N15 hybrid retrieval).
     *
     * Same join as [liveChunkPage], so a semantic hit can only ever resolve to an authoritative,
     * non-revoked row of a space the caller already passed in. Order is irrelevant here; the store
     * restores the semantic ranking.
     */
    @Query(
        "SELECT c.chunkId AS chunkId, c.memoryId AS memoryId, c.spaceId AS spaceId, " +
            "c.position AS position, c.text AS text, c.contentHash AS contentHash, " +
            "c.estimatedTokens AS estimatedTokens, i.contentHash AS sourceHash " +
            "FROM agent_memory_chunks AS c INNER JOIN agent_memory_items AS i ON i.memoryId = c.memoryId " +
            "WHERE i.revoked = 0 AND c.spaceId IN (:spaceIds) AND c.chunkId IN (:chunkIds)"
    )
    suspend fun liveChunks(spaceIds: List<String>, chunkIds: List<String>): List<AgentMemoryChunkRecord>

    @Query(
        "SELECT COUNT(*) FROM agent_memory_chunks AS c INNER JOIN agent_memory_items AS i " +
            "ON i.memoryId = c.memoryId WHERE i.revoked = 0 AND c.spaceId IN (:spaceIds)"
    )
    suspend fun liveChunkCount(spaceIds: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSelection(selection: AgentMemoryTaskSelectionEntity)

    @Query("SELECT * FROM agent_memory_task_selections WHERE taskId = :taskId LIMIT 1")
    suspend fun findSelection(taskId: String): AgentMemoryTaskSelectionEntity?

    @Query(
        "UPDATE agent_memory_task_selections SET revoked = 1, revokedAt = :atMillis " +
            "WHERE taskId = :taskId AND revoked = 0"
    )
    suspend fun revokeSelection(taskId: String, atMillis: Long): Int
}
