package com.muyuchat.mca

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

interface ChatSessionMemoryDao {
    @Query("SELECT * FROM memories WHERE assistantId = :assistantId ORDER BY createdAt DESC")
    suspend fun memories(assistantId: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemories(memories: List<MemoryEntity>)

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun memoryById(id: String): MemoryEntity?

    @Query("SELECT id FROM memories WHERE assistantId != :assistantId AND id IN (:ids)")
    suspend fun memoryIdsOwnedByOtherAssistant(assistantId: String, ids: List<String>): List<String>

    @Query("DELETE FROM memories WHERE assistantId = :assistantId")
    suspend fun clearMemoriesForAssistant(assistantId: String)

    @Query("DELETE FROM assistant_memory_turns WHERE assistantId = :assistantId")
    suspend fun clearMemoryTurnsForAssistant(assistantId: String)

    @Query("DELETE FROM memories WHERE assistantId = :assistantId AND source IN ('automatic', 'auto')")
    suspend fun clearAutomaticMemoriesForAssistant(assistantId: String)

    @Query("DELETE FROM memories WHERE id = :id AND assistantId = :assistantId")
    suspend fun deleteMemoryForAssistant(id: String, assistantId: String): Int

    @Query(
        "SELECT * FROM assistant_memory_turns WHERE assistantId = :assistantId " +
            "AND isSummarized = 0 ORDER BY createdAt ASC, id ASC"
    )
    suspend fun pendingMemoryTurnCandidates(assistantId: String): List<AssistantMemoryTurnEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemoryTurn(turn: AssistantMemoryTurnEntity): Long

    @Query(
        "SELECT * FROM assistant_memory_turns WHERE assistantId = :assistantId " +
            "AND isSummarized = 0 AND id IN (:ids)"
    )
    suspend fun pendingMemoryTurnsById(
        assistantId: String,
        ids: List<String>
    ): List<AssistantMemoryTurnEntity>

    @Query(DELETE_CONSUMED_MEMORY_TURNS_SQL)
    suspend fun deleteConsumedMemoryTurns(assistantId: String, ids: List<String>): Int

    @Query(DELETE_MEMORY_TURNS_FOR_SESSIONS_SQL)
    suspend fun deleteMemoryTurnsForSessions(sessionIds: List<String>): Int

    @Query(DELETE_STALE_PENDING_MEMORY_TURNS_SQL)
    suspend fun deleteStalePendingMemoryTurns(): Int

    @Query("DELETE FROM assistant_memory_turns WHERE assistantId = :assistantId AND isSummarized = 1")
    suspend fun deleteLegacySummarizedMemoryTurns(assistantId: String): Int

    @Query(MEMORY_SESSION_OWNER_EXISTS_SQL)
    suspend fun memorySessionOwnedByAssistant(chatSessionId: String, assistantId: String): Boolean

    @Query(MEMORY_ASSISTANT_REPLY_EXISTS_SQL)
    suspend fun assistantMessageExists(chatSessionId: String, messageId: String): Boolean

    @Transaction
    suspend fun memoryRecords(assistantId: String): List<MemoryRecord> =
        memories(assistantId).map { it.toMemoryRecord() }

    @Transaction
    suspend fun replaceMemoriesForAssistant(assistantId: String, records: List<MemoryRecord>): Boolean {
        if (assistantId.isBlank() || records.any { it.assistantId != assistantId }) return false
        val ids = records.map { it.id }
        if (ids.distinct().size != ids.size) return false
        if (ids.isNotEmpty() && memoryIdsOwnedByOtherAssistant(assistantId, ids).isNotEmpty()) return false
        clearMemoriesForAssistant(assistantId)
        insertMemories(records.map { it.toEntity() })
        return true
    }

    @Transaction
    suspend fun deleteAssistantMemoryData(assistantId: String) {
        clearMemoriesForAssistant(assistantId)
        clearMemoryTurnsForAssistant(assistantId)
    }

    @Transaction
    suspend fun upsertMemoryRecord(record: MemoryRecord): Boolean {
        val existing = memoryById(record.id)
        if (existing != null && existing.assistantId != record.assistantId) return false
        if (existing?.source == "manual" && record.source == "automatic") return false
        insertMemories(listOf(record.toEntity()))
        return true
    }
}
