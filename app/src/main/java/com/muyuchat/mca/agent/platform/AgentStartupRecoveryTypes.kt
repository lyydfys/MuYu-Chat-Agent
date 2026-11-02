package com.muyuchat.mca.agent.platform

import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentModelAttempt
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.ToolInvocation

/** Result of asking the typed artifact reconciler whether a PREPARING/RENAMED commit landed. */
sealed interface AgentArtifactReconcileResult {
    data class Committed(val evidenceRef: String) : AgentArtifactReconcileResult

    data object NotCommitted : AgentArtifactReconcileResult

    data object Unknown : AgentArtifactReconcileResult
}

/** Narrow port implemented by the workspace/K06 owner; startup recovery never renames files itself. */
interface AgentArtifactReconciler {
    suspend fun confirmCommitted(commit: AgentArtifactCommit): AgentArtifactReconcileResult

    companion object {
        val Never: AgentArtifactReconciler = object : AgentArtifactReconciler {
            override suspend fun confirmCommitted(
                commit: AgentArtifactCommit
            ): AgentArtifactReconcileResult = AgentArtifactReconcileResult.Unknown
        }
    }
}

/**
 * Reads the records that are not part of [com.muyuchat.core.agent.contract.AgentRunSnapshot]: the
 * pending invocation behind an approval, the artifact commit journal, and the latest model attempt
 * for a stop-proof reference. The data/K05 owner implements this; recovery only reads and commits
 * through [com.muyuchat.core.agent.contract.AgentJournal].
 */
interface AgentRecoveryRecordSource {
    suspend fun findInvocation(invocationId: String): ToolInvocation?

    suspend fun findArtifactCommit(artifactId: String): AgentArtifactCommit?

    suspend fun findLatestAttempt(runId: String): AgentModelAttempt?

    companion object {
        val Unavailable: AgentRecoveryRecordSource = object : AgentRecoveryRecordSource {
            override suspend fun findInvocation(invocationId: String): ToolInvocation? = null

            override suspend fun findArtifactCommit(artifactId: String): AgentArtifactCommit? = null

            override suspend fun findLatestAttempt(runId: String): AgentModelAttempt? = null
        }
    }
}

enum class AgentRecoveryNoOpReason {
    TERMINAL,
    ALREADY_SETTLED,
    RUN_DISAPPEARED,

    /** A valid durable park token owns this run's next move; startup must not expire or pause it. */
    PARKED_WAKE_ARMED
}

sealed interface AgentRunRecoveryResult {
    val runId: String

    data class Recovered(
        override val runId: String,
        val fromState: AgentRunState,
        val toState: AgentRunState,
        val pauseReason: AgentPauseReason,
        val commits: List<String>
    ) : AgentRunRecoveryResult

    data class NeedsReview(
        override val runId: String,
        val fromState: AgentRunState,
        val reasonRef: String,
        val commits: List<String>
    ) : AgentRunRecoveryResult

    data class NoOp(
        override val runId: String,
        val state: AgentRunState,
        val reason: AgentRecoveryNoOpReason
    ) : AgentRunRecoveryResult

    data class Failed(
        override val runId: String,
        val error: AgentError
    ) : AgentRunRecoveryResult
}

data class AgentStartupRecoveryReport(
    val scanned: Int,
    val results: List<AgentRunRecoveryResult>,
    val scanError: AgentError? = null
)
