package com.muyuchat.mca.agent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Run queries. [updateCas] is the single CAS write used by the journal; direct callers may not use
 * it. `listNonTerminalActive` is the explicit recovery scan: it includes STOPPING/PAUSING/
 * INTERRUPTED/PAUSED/NEEDS_REVIEW and excludes IMPORTED_ARCHIVED/TOMBSTONED tasks.
 */
@Dao
interface AgentRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: AgentRunEntity)

    @Upsert
    suspend fun upsert(run: AgentRunEntity)

    @Query("SELECT * FROM agent_runs WHERE runId = :runId")
    suspend fun find(runId: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs WHERE runId = :runId")
    fun observe(runId: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_runs WHERE taskId = :taskId ORDER BY runOrdinal DESC LIMIT 1")
    suspend fun findLatestForTask(taskId: String): AgentRunEntity?

    @Query("SELECT COALESCE(MAX(runOrdinal), 0) FROM agent_runs WHERE taskId = :taskId")
    suspend fun maxOrdinal(taskId: String): Long

    @Query(
        "UPDATE agent_runs SET state = :state, reason = :reason, epoch = :epoch, " +
            "modelBindingJson = :modelBindingJson, policyJson = :policyJson, budgetJson = :budgetJson, " +
            "spentBudgetJson = :spentBudgetJson, lastSequence = :lastSequence, revision = :revision, " +
            "successorRunId = :successorRunId, updatedAt = :updatedAt " +
            "WHERE runId = :runId AND revision = :expectedRevision"
    )
    suspend fun updateCas(
        runId: String,
        state: String,
        reason: String,
        epoch: Long,
        modelBindingJson: String,
        policyJson: String,
        budgetJson: String,
        spentBudgetJson: String,
        lastSequence: Long,
        revision: Long,
        successorRunId: String?,
        updatedAt: Long,
        expectedRevision: Long
    ): Int

    @Query(
        "UPDATE agent_runs SET lastSequence = :lastSequence, revision = :revision, updatedAt = :updatedAt " +
            "WHERE runId = :runId AND revision = :expectedRevision"
    )
    suspend fun advanceSequenceCas(
        runId: String,
        lastSequence: Long,
        revision: Long,
        updatedAt: Long,
        expectedRevision: Long
    ): Int

    @Query(
        "SELECT r.* FROM agent_runs r INNER JOIN agent_tasks t ON r.taskId = t.taskId " +
            "WHERE t.lifecycle = 'ACTIVE' AND r.state IN (:states) " +
            "ORDER BY r.updatedAt DESC LIMIT :limit"
    )
    suspend fun listNonTerminalActive(states: List<String>, limit: Int): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs WHERE taskId = :taskId AND state IN (:states) ORDER BY runOrdinal DESC LIMIT :limit")
    suspend fun listByTaskAndStates(taskId: String, states: List<String>, limit: Int): List<AgentRunEntity>

    @Query("SELECT COUNT(*) FROM agent_runs WHERE taskId = :taskId AND state IN (:states)")
    suspend fun countByTaskAndStates(taskId: String, states: List<String>): Int

    /** K14 deletion: runs are removed last, after every child row. */
    @Query("DELETE FROM agent_runs WHERE taskId = :taskId")
    suspend fun deleteByTaskId(taskId: String): Int
}

/** Step ledger. `upsert` is only called inside a journal transaction after the run CAS. */
@Dao
interface AgentStepDao {
    @Upsert
    suspend fun upsert(step: AgentStepEntity)

    @Query("SELECT * FROM agent_steps WHERE stepId = :stepId")
    suspend fun find(stepId: String): AgentStepEntity?

    @Query("SELECT * FROM agent_steps WHERE runId = :runId AND stepIndex = :stepIndex LIMIT 1")
    suspend fun findByRunIndex(runId: String, stepIndex: Int): AgentStepEntity?

    @Query("SELECT * FROM agent_steps WHERE runId = :runId ORDER BY stepIndex ASC LIMIT :limit")
    suspend fun listByRun(runId: String, limit: Int): List<AgentStepEntity>

    @Query("SELECT COUNT(*) FROM agent_steps WHERE runId = :runId")
    suspend fun countByRun(runId: String): Int

    @Query(
        "DELETE FROM agent_steps WHERE runId IN " +
            "(SELECT runId FROM agent_runs WHERE taskId = :taskId)"
    )
    suspend fun deleteForRunsOfTask(taskId: String): Int
}

/** Model attempts. `backendRequestId` is unique; retries never reuse one. */
@Dao
interface AgentModelAttemptDao {
    @Upsert
    suspend fun upsert(attempt: AgentModelAttemptEntity)

    @Query("SELECT * FROM agent_model_attempts WHERE attemptId = :attemptId")
    suspend fun find(attemptId: String): AgentModelAttemptEntity?

    @Query("SELECT * FROM agent_model_attempts WHERE backendRequestId = :backendRequestId LIMIT 1")
    suspend fun findByBackendRequestId(backendRequestId: String): AgentModelAttemptEntity?

    @Query("SELECT * FROM agent_model_attempts WHERE runId = :runId ORDER BY stepIndex ASC, ordinal ASC LIMIT :limit")
    suspend fun listByRun(runId: String, limit: Int): List<AgentModelAttemptEntity>

    @Query("SELECT COUNT(*) FROM agent_model_attempts WHERE runId = :runId")
    suspend fun countByRun(runId: String): Int

    @Query(
        "DELETE FROM agent_model_attempts WHERE runId IN " +
            "(SELECT runId FROM agent_runs WHERE taskId = :taskId)"
    )
    suspend fun deleteForRunsOfTask(taskId: String): Int
}

/**
 * Frozen tool invocations and their status.
 *
 * `runningStepKey` is maintained by the journal/ledger and is non-null only while RUNNING; the
 * unique index enforces at most one running tool per step.
 */
@Dao
interface AgentInvocationDao {
    @Upsert
    suspend fun upsert(invocation: AgentInvocationEntity)

    @Query("SELECT * FROM agent_invocations WHERE invocationId = :invocationId")
    suspend fun find(invocationId: String): AgentInvocationEntity?

    @Query("SELECT * FROM agent_invocations WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun findByIdempotencyKey(idempotencyKey: String): AgentInvocationEntity?

    @Query("SELECT * FROM agent_invocations WHERE runId = :runId ORDER BY createdAt ASC, callOrdinal ASC LIMIT :limit")
    suspend fun listByRun(runId: String, limit: Int): List<AgentInvocationEntity>

    @Query("SELECT * FROM agent_invocations WHERE stepId = :stepId ORDER BY callOrdinal ASC LIMIT :limit")
    suspend fun listByStep(stepId: String, limit: Int): List<AgentInvocationEntity>

    @Query("SELECT * FROM agent_invocations WHERE stepId = :stepId AND callOrdinal = :callOrdinal LIMIT 1")
    suspend fun findByStepAndOrdinal(stepId: String, callOrdinal: Int): AgentInvocationEntity?

    @Query("SELECT * FROM agent_invocations WHERE runningStepKey = :stepId LIMIT 1")
    suspend fun findRunningForStep(stepId: String): AgentInvocationEntity?

    /**
     * PROPOSED -> VALIDATED with the exact frozen identity. The proposed arguments hash and
     * resource fingerprint are replaced atomically; no approval may bind before this succeeds.
     */
    @Query(
        "UPDATE agent_invocations SET status = 'VALIDATED', argumentsHash = :argumentsHash, " +
            "resourceFingerprint = :resourceFingerprint, frozenArgumentsRef = :frozenArgumentsRef, " +
            "updatedAt = :updatedAt " +
            "WHERE invocationId = :invocationId AND status = 'PROPOSED'"
    )
    suspend fun markValidatedCas(
        invocationId: String,
        argumentsHash: String,
        resourceFingerprint: String,
        frozenArgumentsRef: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE agent_invocations SET status = 'WAITING_APPROVAL', updatedAt = :updatedAt " +
            "WHERE invocationId = :invocationId AND status = 'VALIDATED'"
    )
    suspend fun markWaitingApprovalCas(invocationId: String, updatedAt: Long): Int

    /**
     * Durable dispatch intent (N03): the invocation is authorized but not yet handed to an executor.
     * Written in the same transaction as the approval CAS; repeated approvals are idempotent.
     */
    @Query(
        "UPDATE agent_invocations SET status = 'APPROVED', updatedAt = :updatedAt " +
            "WHERE invocationId = :invocationId " +
            "AND status IN ('VALIDATED', 'WAITING_APPROVAL', 'APPROVED')"
    )
    suspend fun markApprovedCas(invocationId: String, updatedAt: Long): Int

    /** The single execution gate: a pre-execution row may enter RUNNING exactly once. */
    @Query(
        "UPDATE agent_invocations SET status = 'RUNNING', runningStepKey = :runningStepKey, " +
            "updatedAt = :updatedAt " +
            "WHERE invocationId = :invocationId " +
            "AND status IN ('VALIDATED', 'WAITING_APPROVAL', 'APPROVED')"
    )
    suspend fun markRunningCas(
        invocationId: String,
        runningStepKey: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE agent_invocations SET status = :status, resultRef = :resultRef, " +
            "possibleSideEffects = :possibleSideEffects, runningStepKey = NULL, " +
            "updatedAt = :updatedAt " +
            "WHERE invocationId = :invocationId AND status IN " +
            "('PROPOSED', 'VALIDATED', 'WAITING_APPROVAL', 'APPROVED', 'RUNNING')"
    )
    suspend fun markTerminalCas(
        invocationId: String,
        status: String,
        resultRef: String?,
        possibleSideEffects: Int,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE agent_invocations SET status = :status, resultRef = :resultRef, " +
            "possibleSideEffects = :possibleSideEffects, runningStepKey = :runningStepKey, " +
            "updatedAt = :updatedAt " +
            "WHERE invocationId = :invocationId AND status IN (:expectedStatuses)"
    )
    suspend fun updateStatusCas(
        invocationId: String,
        status: String,
        resultRef: String?,
        possibleSideEffects: Int,
        runningStepKey: String?,
        updatedAt: Long,
        expectedStatuses: List<String>
    ): Int

    @Query(
        "UPDATE agent_invocations SET status = 'SUCCEEDED', updatedAt = :updatedAt " +
            "WHERE invocationId = :invocationId AND status = 'RUNNING'"
    )
    suspend fun markSucceeded(invocationId: String, updatedAt: Long): Int

    @Query("SELECT COUNT(*) FROM agent_invocations WHERE runId = :runId")
    suspend fun countByRun(runId: String): Int

    @Query(
        "DELETE FROM agent_invocations WHERE runId IN " +
            "(SELECT runId FROM agent_runs WHERE taskId = :taskId)"
    )
    suspend fun deleteForRunsOfTask(taskId: String): Int
}

/** Approvals. `pendingInvocationKey` uniqueness enforces one pending approval per invocation. */
@Dao
interface AgentApprovalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(approval: AgentApprovalEntity)

    @Upsert
    suspend fun upsert(approval: AgentApprovalEntity)

    @Query("SELECT * FROM agent_approvals WHERE approvalId = :approvalId")
    suspend fun find(approvalId: String): AgentApprovalEntity?

    @Query(
        "SELECT * FROM agent_approvals WHERE invocationId = :invocationId AND status = 'PENDING' LIMIT 1"
    )
    suspend fun findPendingForInvocation(invocationId: String): AgentApprovalEntity?

    @Query(
        "SELECT a.* FROM agent_approvals a " +
            "INNER JOIN agent_invocations i ON a.invocationId = i.invocationId " +
            "WHERE i.runId = :runId AND a.status = 'PENDING' ORDER BY a.createdAt DESC LIMIT 1"
    )
    suspend fun findPendingForRun(runId: String): AgentApprovalEntity?

    @Query("SELECT * FROM agent_approvals WHERE invocationId = :invocationId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listByInvocation(invocationId: String, limit: Int): List<AgentApprovalEntity>

    @Query(
        "UPDATE agent_approvals SET status = :status, decision = :decision, consumedAt = :consumedAt, " +
            "pendingInvocationKey = NULL " +
            "WHERE approvalId = :approvalId AND status IN ('PENDING', 'APPROVED') " +
            "AND expiresAt >= :consumedAt"
    )
    suspend fun consumeCas(
        approvalId: String,
        status: String,
        decision: String?,
        consumedAt: Long
    ): Int

    /**
     * The one-shot execution gate. A single conditional UPDATE that accepts only PENDING and
     * compares the complete binding, run epoch, policy version and expiry in the same statement.
     * Concurrent callers can therefore produce exactly one winning row count.
     */
    @Query(
        "UPDATE agent_approvals SET status = 'CONSUMED', decision = 'ALLOW_ONCE', " +
            "consumedAt = :now, pendingInvocationKey = NULL " +
            "WHERE approvalId = :approvalId AND status = 'PENDING' " +
            "AND invocationId = :invocationId AND argumentsHash = :argumentsHash " +
            "AND toolSchemaHash = :toolSchemaHash AND resourceFingerprint = :resourceFingerprint " +
            "AND runEpoch = :runEpoch AND policyVersion = :policyVersion AND expiresAt >= :now"
    )
    suspend fun consumePendingCas(
        approvalId: String,
        invocationId: String,
        argumentsHash: String,
        toolSchemaHash: String,
        resourceFingerprint: String,
        runEpoch: Long,
        policyVersion: Long,
        now: Long
    ): Int

    @Query(
        "UPDATE agent_approvals SET status = 'EXPIRED', pendingInvocationKey = NULL " +
            "WHERE approvalId IN (SELECT approvalId FROM agent_approvals " +
            "WHERE status = 'PENDING' AND expiresAt < :now LIMIT :limit)"
    )
    suspend fun expireOverdue(now: Long, limit: Int): Int

    @Query(
        "UPDATE agent_approvals SET status = 'INVALIDATED', pendingInvocationKey = NULL " +
            "WHERE runId = :runId AND status = 'PENDING'"
    )
    suspend fun invalidatePendingForRun(runId: String): Int

    @Query(
        "DELETE FROM agent_approvals WHERE runId IN " +
            "(SELECT runId FROM agent_runs WHERE taskId = :taskId)"
    )
    suspend fun deleteForRunsOfTask(taskId: String): Int
}
