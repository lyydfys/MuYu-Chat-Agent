package com.muyuchat.mca.agent.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * Park/wake token queries.
 *
 * `upsert` replaces the single row for a run, so recording a newer park never leaves the previous
 * wake id behind. [consume] is the wake CAS: it only removes and returns the row when the presented
 * wake id still matches the durable one, which makes a duplicate or stale wake a no-op.
 */
@Dao
interface AgentParkTokenDao {
    @Upsert
    suspend fun upsert(token: AgentParkTokenEntity)

    @Query("SELECT * FROM agent_park_tokens WHERE runId = :runId")
    suspend fun find(runId: String): AgentParkTokenEntity?

    @Query("DELETE FROM agent_park_tokens WHERE runId = :runId")
    suspend fun delete(runId: String): Int

    @Query("SELECT * FROM agent_park_tokens ORDER BY createdAtMillis ASC")
    suspend fun listAll(): List<AgentParkTokenEntity>

    /**
     * Loads the token, returns null unless [wakeId] matches, deletes it and returns the removed row.
     *
     * The whole read-check-delete runs in one Room transaction, so two concurrent consumes of the
     * same wake id cannot both succeed: the second transaction sees no row and returns null.
     */
    @Transaction
    suspend fun consume(runId: String, wakeId: String): AgentParkTokenEntity? {
        val token = find(runId) ?: return null
        if (token.wakeId != wakeId) return null
        delete(runId)
        return token
    }
}
