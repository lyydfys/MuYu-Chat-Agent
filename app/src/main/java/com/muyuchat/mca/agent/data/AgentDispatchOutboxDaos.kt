package com.muyuchat.mca.agent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Outbox rows are mutated by compare-and-set on `(idempotencyKey, revision)`; the store composes
 * read/validate/update inside one [AgentTransactionRunner] transaction. Terminal statuses are the
 * same five names the runtime enum declares; the retention pass uses them for pruning.
 */
@Dao
interface AgentDispatchOutboxDao {
    @Query("SELECT * FROM agent_dispatch_outbox WHERE idempotencyKey = :idempotencyKey")
    suspend fun find(idempotencyKey: String): AgentDispatchOutboxEntity?

    @Query(
        "SELECT * FROM agent_dispatch_outbox WHERE status NOT IN " +
            "('SETTLED','EXPIRED','FAILED','NEEDS_REVIEW','BYPASSED') " +
            "ORDER BY updatedAtMillis ASC, idempotencyKey ASC LIMIT :limit"
    )
    suspend fun listActive(limit: Int): List<AgentDispatchOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: AgentDispatchOutboxEntity): Long

    @Query(
        "UPDATE agent_dispatch_outbox SET status = :status, leaseOwner = :leaseOwner, " +
            "leaseExpiresAtMillis = :leaseExpiresAtMillis, dispatchAttempts = :dispatchAttempts, " +
            "settledInvocationStatus = :settledInvocationStatus, errorCode = :errorCode, " +
            "updatedAtMillis = :updatedAtMillis, revision = :newRevision " +
            "WHERE idempotencyKey = :idempotencyKey AND revision = :expectedRevision"
    )
    suspend fun updateCas(
        idempotencyKey: String,
        expectedRevision: Long,
        status: String,
        leaseOwner: String?,
        leaseExpiresAtMillis: Long,
        dispatchAttempts: Int,
        settledInvocationStatus: String?,
        errorCode: String?,
        updatedAtMillis: Long,
        newRevision: Long
    ): Int

    @Query(
        "DELETE FROM agent_dispatch_outbox WHERE status IN " +
            "('SETTLED','EXPIRED','FAILED','NEEDS_REVIEW','BYPASSED') " +
            "AND updatedAtMillis < :cutoffMillis"
    )
    suspend fun pruneTerminal(cutoffMillis: Long): Int
}
