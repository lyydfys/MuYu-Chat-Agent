package com.muyuchat.mca.agent.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Append-only event log with sequence paging. No query can return an unbounded page. */
@Dao
interface AgentEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(events: List<AgentEventEntity>)

    @Query("SELECT * FROM agent_events WHERE eventId = :eventId")
    suspend fun find(eventId: String): AgentEventEntity?

    @Query(
        "SELECT * FROM agent_events WHERE runId = :runId AND sequence > :afterSequence " +
            "ORDER BY sequence ASC LIMIT :limit"
    )
    suspend fun page(runId: String, afterSequence: Long, limit: Int): List<AgentEventEntity>

    @Query(
        "SELECT * FROM agent_events WHERE runId = :runId AND sequence > :afterSequence " +
            "ORDER BY sequence ASC LIMIT :limit"
    )
    fun observePage(runId: String, afterSequence: Long, limit: Int): Flow<List<AgentEventEntity>>

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM agent_events WHERE runId = :runId")
    suspend fun maxSequence(runId: String): Long

    @Query("SELECT COUNT(*) FROM agent_events WHERE runId = :runId")
    suspend fun countByRun(runId: String): Int

    @Query(
        "DELETE FROM agent_events WHERE runId IN " +
            "(SELECT runId FROM agent_runs WHERE taskId = :taskId)"
    )
    suspend fun deleteForRunsOfTask(taskId: String): Int
}

/** Durable command idempotency log. Lookup happens before any revision CAS. */
@Dao
interface AgentCommandDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(command: AgentCommandEntity)

    @Query("SELECT * FROM agent_commands WHERE commandId = :commandId")
    suspend fun find(commandId: String): AgentCommandEntity?

    @Query("SELECT * FROM agent_commands WHERE scopeId = :scopeId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listByScope(scopeId: String, limit: Int): List<AgentCommandEntity>
}

/**
 * Runtime command-level idempotency rows (`agent_command_results`), distinct from
 * transition-level [AgentCommandDao]. `find` is called before revision validation; `insert` runs in
 * the same transaction as the business change.
 */
@Dao
interface AgentCommandResultDao {
    @Query("SELECT * FROM agent_command_results WHERE commandId = :commandId")
    suspend fun find(commandId: String): AgentCommandResultEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: AgentCommandResultEntity): Long
}

/** Artifact rows: logical versions, latest mapping and invocation links. */
@Dao
interface AgentArtifactDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(artifact: AgentArtifactEntity)

    @Upsert
    suspend fun upsert(artifact: AgentArtifactEntity)

    @Query("SELECT * FROM agent_artifacts WHERE artifactId = :artifactId")
    suspend fun find(artifactId: String): AgentArtifactEntity?

    @Query(
        "SELECT * FROM agent_artifacts WHERE taskId = :taskId AND relativePath = :relativePath " +
            "AND isLatest = 1 LIMIT 1"
    )
    suspend fun findLatest(taskId: String, relativePath: String): AgentArtifactEntity?

    @Query(
        "SELECT COALESCE(MAX(version), 0) FROM agent_artifacts " +
            "WHERE taskId = :taskId AND relativePath = :relativePath"
    )
    suspend fun maxVersion(taskId: String, relativePath: String): Long

    /**
     * Next committed version for a path, EXCLUDING the row currently being committed (its PREPARING
     * row already exists and must not consume a version number).
     */
    @Query(
        "SELECT COALESCE(MAX(version), 0) FROM agent_artifacts " +
            "WHERE taskId = :taskId AND relativePath = :relativePath AND artifactId != :artifactId"
    )
    suspend fun maxVersionExcluding(taskId: String, relativePath: String, artifactId: String): Long

    @Query(
        "UPDATE agent_artifacts SET isLatest = 0, latestKey = NULL " +
            "WHERE taskId = :taskId AND relativePath = :relativePath AND artifactId != :keepArtifactId"
    )
    suspend fun clearLatest(taskId: String, relativePath: String, keepArtifactId: String): Int

    @Query(
        "UPDATE agent_artifacts SET commitState = :commitState, sizeBytes = :sizeBytes, " +
            "sha256 = :sha256, storageRef = :storageRef, mediaType = :mediaType, isLatest = :isLatest, " +
            "latestKey = :latestKey, commitId = :commitId, version = :version " +
            "WHERE artifactId = :artifactId"
    )
    suspend fun markCommitResult(
        artifactId: String,
        commitState: String,
        sizeBytes: Long,
        sha256: String,
        storageRef: String,
        mediaType: String,
        isLatest: Int,
        latestKey: String?,
        commitId: String?,
        version: Long
    ): Int

    @Query(
        "UPDATE agent_artifacts SET commitState = 'FAILED', isLatest = 0, latestKey = NULL " +
            "WHERE artifactId = :artifactId"
    )
    suspend fun markFailed(artifactId: String): Int

    @Query(
        "SELECT * FROM agent_artifacts WHERE invocationId = :invocationId " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun listByInvocation(invocationId: String, limit: Int): List<AgentArtifactEntity>

    @Query(
        "SELECT a.* FROM agent_artifacts a " +
            "INNER JOIN agent_invocations i ON a.invocationId = i.invocationId " +
            "WHERE i.runId = :runId ORDER BY a.createdAt DESC LIMIT :limit"
    )
    suspend fun listByRun(runId: String, limit: Int): List<AgentArtifactEntity>

    @Query(
        "SELECT * FROM agent_artifacts WHERE taskId = :taskId AND commitState = 'COMMITTED' " +
            "ORDER BY relativePath ASC, version DESC LIMIT :limit"
    )
    suspend fun listCommitted(taskId: String, limit: Int): List<AgentArtifactEntity>

    @Query(
        "SELECT * FROM agent_artifacts WHERE taskId = :taskId AND commitState = 'COMMITTED' " +
            "AND isLatest = 1 ORDER BY relativePath ASC LIMIT :limit"
    )
    suspend fun listLatestCommitted(taskId: String, limit: Int): List<AgentArtifactEntity>

    @Query("DELETE FROM agent_artifacts WHERE taskId = :taskId")
    suspend fun deleteByTaskId(taskId: String): Int
}

/** Artifact commit journal reads/writes used by the recovery reconciler. */
@Dao
interface AgentArtifactCommitDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(commit: AgentArtifactCommitEntity)

    @Upsert
    suspend fun upsert(commit: AgentArtifactCommitEntity)

    @Query("SELECT * FROM agent_artifact_commits WHERE commitId = :commitId")
    suspend fun find(commitId: String): AgentArtifactCommitEntity?

    @Query("SELECT * FROM agent_artifact_commits WHERE artifactId = :artifactId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun listByArtifact(artifactId: String, limit: Int): List<AgentArtifactCommitEntity>

    @Query(
        "UPDATE agent_artifact_commits SET phase = :phase, updatedAt = :updatedAt " +
            "WHERE commitId = :commitId AND phase IN (:expectedPhases)"
    )
    suspend fun advancePhase(
        commitId: String,
        phase: String,
        updatedAt: Long,
        expectedPhases: List<String>
    ): Int

    @Query(
        "SELECT c.* FROM agent_artifact_commits c " +
            "INNER JOIN agent_artifacts a ON c.artifactId = a.artifactId " +
            "WHERE a.taskId = :taskId AND c.phase IN ('PREPARING', 'STAGED', 'RENAMED') " +
            "ORDER BY c.updatedAt ASC LIMIT :limit"
    )
    suspend fun listUnsettledForTask(taskId: String, limit: Int): List<AgentArtifactCommitEntity>

    @Query(
        "DELETE FROM agent_artifact_commits WHERE artifactId IN " +
            "(SELECT artifactId FROM agent_artifacts WHERE taskId = :taskId)"
    )
    suspend fun deleteForTaskArtifacts(taskId: String): Int
}

/** Installed extension metadata only — never credentials. */
@Dao
interface AgentExtensionDao {
    @Upsert
    suspend fun upsert(extension: AgentExtensionEntity)

    @Query("SELECT * FROM agent_extensions WHERE extensionId = :extensionId")
    suspend fun find(extensionId: String): AgentExtensionEntity?

    @Query(
        "SELECT * FROM agent_extensions WHERE (:kind IS NULL OR kind = :kind) " +
            "ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun list(kind: String?, limit: Int): List<AgentExtensionEntity>
}

/** Local user grants (export-excluded). */
@Dao
interface AgentGrantDao {
    @Upsert
    suspend fun upsert(grant: AgentGrantEntity)

    @Query("SELECT * FROM agent_grants WHERE grantId = :grantId")
    suspend fun find(grantId: String): AgentGrantEntity?

    @Query(
        "SELECT * FROM agent_grants WHERE taskId = :taskId AND revokedAt IS NULL " +
            "AND (expiresAt IS NULL OR expiresAt > :nowMillis) " +
            "ORDER BY grantedAt DESC LIMIT :limit"
    )
    suspend fun listActiveForTask(taskId: String, nowMillis: Long, limit: Int): List<AgentGrantEntity>

    /** Egress-only active page so unrelated workspace grants cannot crowd the store's limit. */
    @Query(
        "SELECT * FROM agent_grants WHERE taskId = :taskId AND scopeType = 'EGRESS_PROVIDER' " +
            "AND revokedAt IS NULL AND (expiresAt IS NULL OR expiresAt > :nowMillis) " +
            "ORDER BY grantedAt DESC LIMIT :limit"
    )
    suspend fun listActiveEgressForTask(
        taskId: String,
        nowMillis: Long,
        limit: Int
    ): List<AgentGrantEntity>

    @Query(
        "UPDATE agent_grants SET revokedAt = :revokedAt, revokedReason = :reason " +
            "WHERE taskId = :taskId AND scopeType = :scopeType AND runId IS NULL AND revokedAt IS NULL"
    )
    suspend fun revokeTaskScope(taskId: String, scopeType: String, revokedAt: Long, reason: String): Int

    /** Single-grant CAS revoke used by the egress grant store; 0 when already revoked/missing. */
    @Query(
        "UPDATE agent_grants SET revokedAt = :revokedAt, revokedReason = :reason " +
            "WHERE grantId = :grantId AND scopeType = :scopeType AND revokedAt IS NULL"
    )
    suspend fun revokeCas(
        grantId: String,
        scopeType: String,
        revokedAt: Long,
        reason: String
    ): Int

    @Query("DELETE FROM agent_grants WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String): Int
}

/** Durable question rows; only one OPEN question per run is answerable. */
@Dao
interface AgentQuestionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(question: AgentQuestionEntity)

    @Query("SELECT * FROM agent_questions WHERE questionId = :questionId")
    suspend fun find(questionId: String): AgentQuestionEntity?

    @Query(
        "SELECT * FROM agent_questions WHERE runId = :runId AND status = 'OPEN' " +
            "ORDER BY askedAt DESC LIMIT 1"
    )
    suspend fun findOpenForRun(runId: String): AgentQuestionEntity?

    @Query("SELECT * FROM agent_questions WHERE runId = :runId ORDER BY askedAt DESC LIMIT :limit")
    suspend fun listByRun(runId: String, limit: Int): List<AgentQuestionEntity>

    @Query(
        "UPDATE agent_questions SET status = 'ANSWERED', answer = :answer, answeredAt = :answeredAt " +
            "WHERE questionId = :questionId AND runId = :runId AND status = 'OPEN'"
    )
    suspend fun answerCas(questionId: String, runId: String, answer: String, answeredAt: Long): Int

    /**
     * A terminal run must not keep a question OPEN. Nothing can answer it any more, and a
     * leftover row would resurrect a "waiting for answer" projection after a restart.
     */
    @Query(
        "UPDATE agent_questions SET status = 'CANCELLED', answeredAt = :closedAt " +
            "WHERE runId = :runId AND status = 'OPEN'"
    )
    suspend fun closeOpenForTerminalRun(runId: String, closedAt: Long): Int

    @Query(
        "DELETE FROM agent_questions WHERE runId IN " +
            "(SELECT runId FROM agent_runs WHERE taskId = :taskId)"
    )
    suspend fun deleteForRunsOfTask(taskId: String): Int
}
