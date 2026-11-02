package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentArtifactCommitPhase

/**
 * Transaction boundary between [AgentArtifactStore]'s filesystem phases and the Room commit journal.
 *
 * The filesystem has no cross-resource transaction with SQLite (architecture 10.3), so each call
 * here is a small, durable truth update. [begin] must be committed BEFORE the first byte is written
 * to staging; [commitArtifact] re-checks `expectedOldHash` inside the same transaction that flips the
 * artifact to COMMITTED and the invocation to SUCCEEDED.
 */
interface AgentArtifactLedger : AgentArtifactCatalog {
    /** Inserts artifact PREPARING + commit PREPARING atomically. Returns false if commitId exists. */
    suspend fun begin(commit: AgentArtifactCommit, artifact: AgentStoredArtifact): Boolean

    /** CAS phase advance; returns false when the row is not in one of [expectedFrom] phases. */
    suspend fun advance(
        commitId: String,
        phase: AgentArtifactCommitPhase,
        updatedAtMillis: Long,
        expectedFrom: Set<AgentArtifactCommitPhase> = PRE_RENAME_PHASES
    ): Boolean

    suspend fun findCommit(commitId: String): AgentArtifactCommit?

    suspend fun findArtifact(artifactId: String): AgentStoredArtifact?

    suspend fun nextVersion(taskId: String, logicalPath: String): Long

    suspend fun markFailed(commitId: String, updatedAtMillis: Long)

    suspend fun unsettledCommits(taskId: String, limit: Int): List<AgentArtifactCommit>

    suspend fun commitArtifact(
        commitId: String,
        storedArtifact: AgentStoredArtifact,
        committedAtMillis: Long,
        invocationResultRef: String,
        event: AgentArtifactCommitEvent?
    ): AgentArtifactCommitDecision

    companion object {
        val PRE_RENAME_PHASES: Set<AgentArtifactCommitPhase> =
            setOf(AgentArtifactCommitPhase.PREPARING, AgentArtifactCommitPhase.STAGED)
    }
}

/** Event data the ledger appends in the same transaction as the artifact COMMITTED transition. */
data class AgentArtifactCommitEvent(
    val eventId: String,
    val type: String,
    val summary: String,
    val commandId: String? = null,
    val createdAtMillis: Long
)

sealed interface AgentArtifactCommitDecision {
    data class Committed(val artifact: AgentStoredArtifact) : AgentArtifactCommitDecision

    data class PreconditionChanged(val message: String) : AgentArtifactCommitDecision

    data class Conflict(val message: String) : AgentArtifactCommitDecision
}
