package com.muyuchat.mca.agent.platform

import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentArtifactCommitPhase
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentStepKind
import com.muyuchat.core.agent.contract.AgentStepStatus
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.contract.UpsertApprovalTransition
import com.muyuchat.core.agent.contract.UpsertArtifactTransition
import com.muyuchat.core.agent.contract.UpsertInvocationTransition
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * Explicit startup reconciler for all non-terminal Agent runs.
 *
 * It never resumes a run, never calls a network/model/worker restart path and never touches the
 * engine. It only reads the journal/records and commits domain transitions through [AgentJournal].
 * Command ids are deterministic per run and recovery action, so a crash between commits replays as
 * an idempotent command instead of duplicating work.
 */
class AgentStartupRecovery(
    private val journal: AgentJournal,
    private val records: AgentRecoveryRecordSource = AgentRecoveryRecordSource.Unavailable,
    private val artifactReconciler: AgentArtifactReconciler = AgentArtifactReconciler.Never,
    private val stopProofSource: AgentStopProofSource = AgentStopProofSource.Unavailable,
    /**
     * S4 durable park marker. A valid token means the run's park survived the process and its next
     * move is the wake/answer/approval command, not this reconciler's pause or approval expiry.
     */
    private val parkTokens: com.muyuchat.core.agent.runtime.run.AgentParkTokenStore =
        com.muyuchat.core.agent.runtime.run.NoAgentParkTokenStore,
    /** Reads the durable question so a RUNNING user-answer park can be armed instead of paused. */
    private val questionReader: com.muyuchat.core.agent.runtime.journal.AgentQuestionReader =
        object : com.muyuchat.core.agent.runtime.journal.AgentQuestionReader {
            override suspend fun readQuestion(questionId: String): com.muyuchat.core.agent.contract.AgentQuestion? =
                null
        },
    private val clock: () -> Long = System::currentTimeMillis,
    private val stopProofTimeoutMillis: Long = DEFAULT_STOP_PROOF_TIMEOUT_MILLIS
) {
    suspend fun reconcile(limit: Int = DEFAULT_SCAN_LIMIT): AgentStartupRecoveryReport {
        require(limit > 0) { "recovery scan limit must be positive" }
        val listed = try {
            journal.listNonTerminalRuns(limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return AgentStartupRecoveryReport(
                scanned = 0,
                results = emptyList(),
                scanError = recoveryError(null, "startup_scan_failed", failure)
            )
        }
        val results = ArrayList<AgentRunRecoveryResult>(listed.size)
        for (snapshot in listed) {
            val result = try {
                reconcileRun(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                AgentRunRecoveryResult.Failed(
                    runId = snapshot.run.runId,
                    error = recoveryError(snapshot.run.runId, "startup_recovery_failed", failure)
                )
            }
            results += result
        }
        return AgentStartupRecoveryReport(scanned = listed.size, results = results)
    }

    private suspend fun reconcileRun(listed: AgentRunSnapshot): AgentRunRecoveryResult {
        val runId = listed.run.runId
        val snapshot = journal.readRun(runId)
            ?: return AgentRunRecoveryResult.NoOp(
                runId = runId,
                state = listed.run.state,
                reason = AgentRecoveryNoOpReason.RUN_DISAPPEARED
            )
        val run = snapshot.run
        if (run.state.isTerminal) {
            return AgentRunRecoveryResult.NoOp(runId, run.state, AgentRecoveryNoOpReason.TERMINAL)
        }
        if (run.state == AgentRunState.NEEDS_REVIEW || run.state == AgentRunState.INTERRUPTED) {
            return AgentRunRecoveryResult.NoOp(runId, run.state, AgentRecoveryNoOpReason.ALREADY_SETTLED)
        }

        val commits = mutableListOf<String>()
        var revision = run.revision
        val artifactPass = reconcileArtifacts(snapshot, revision, commits)
        revision = artifactPass.revision
        if (artifactPass.needsReviewReason != null) {
            return commitReview(run, revision, artifactPass.needsReviewReason, commits)
        }
        if (run.state == AgentRunState.PAUSED) {
            return AgentRunRecoveryResult.NoOp(runId, run.state, AgentRecoveryNoOpReason.ALREADY_SETTLED)
        }

        // S4 fence: only an exact (epoch, revision, parked state) token protects the park. Any
        // other token is stale and is removed before the legacy fail-closed recovery runs.
        val parkToken = runCatching { parkTokens.load(runId) }.getOrNull()
        if (parkToken != null) {
            val sameEpoch = parkToken.epoch == run.epoch
            val armedPark = sameEpoch &&
                parkToken.checkpointRevision == run.revision &&
                (run.state == AgentRunState.WAITING_APPROVAL ||
                    run.state == AgentRunState.WAITING_USER)
            val armedUserResume = sameEpoch &&
                run.state == AgentRunState.RUNNING &&
                parkToken.kind == com.muyuchat.core.agent.runtime.run.AgentParkKind.USER &&
                parkToken.checkpointRevision <= run.revision &&
                hasDurableAnswer(runId, parkToken.questionId)
            if (armedPark || armedUserResume) {
                return AgentRunRecoveryResult.NoOp(
                    runId = runId,
                    state = run.state,
                    reason = AgentRecoveryNoOpReason.PARKED_WAKE_ARMED
                )
            }
            runCatching { parkTokens.delete(runId) }
        }

        return when (run.state) {
            AgentRunState.QUEUED,
            AgentRunState.WAITING_RESOURCE,
            AgentRunState.WAITING_USER -> commitPause(
                run = run,
                revision = revision,
                reason = AgentPauseReason.INTERRUPTED,
                action = "pause_interrupted",
                commits = commits
            )

            AgentRunState.RUNNING -> {
                if (externalSideEffectUnknown(snapshot, artifactPass.resolvedArtifactIds)) {
                    commitReview(run, revision, "external_side_effect_unknown", commits)
                } else {
                    commitPause(
                        run = run,
                        revision = revision,
                        reason = AgentPauseReason.INTERRUPTED,
                        action = "pause_model_generation_interrupted",
                        commits = commits
                    )
                }
            }

            AgentRunState.WAITING_APPROVAL -> recoverWaitingApproval(snapshot, revision, commits)

            AgentRunState.PAUSING,
            AgentRunState.STOPPING -> recoverStopping(snapshot, revision, commits)

            AgentRunState.PAUSED,
            AgentRunState.NEEDS_REVIEW,
            AgentRunState.INTERRUPTED -> AgentRunRecoveryResult.NoOp(
                runId,
                run.state,
                AgentRecoveryNoOpReason.ALREADY_SETTLED
            )

            AgentRunState.SUCCEEDED,
            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.SUPERSEDED -> AgentRunRecoveryResult.NoOp(
                runId,
                run.state,
                AgentRecoveryNoOpReason.TERMINAL
            )
        }
    }

    private suspend fun hasDurableAnswer(runId: String, questionId: String?): Boolean {
        if (questionId == null) return false
        val question = runCatching { questionReader.readQuestion(questionId) }.getOrNull()
            ?: return false
        return question.runId == runId &&
            question.status == com.muyuchat.core.agent.contract.AgentQuestionStatus.ANSWERED &&
            !question.answer.isNullOrBlank()
    }

    private suspend fun recoverWaitingApproval(
        snapshot: AgentRunSnapshot,
        startRevision: Long,
        commits: MutableList<String>
    ): AgentRunRecoveryResult {
        val run = snapshot.run
        val approval = snapshot.pendingApproval
        if (approval == null) {
            // Legacy/partial restart: the invocation row exists but its approval row is gone.
            // Fail closed by pairing the pre-execution invocation as NOT_EXECUTED, then pause the
            // run. Never leave a WAITING_APPROVAL invocation looking reusable.
            val orphanInvocationId = snapshot.steps
                .asSequence()
                .mapNotNull { it.invocationId }
                .firstOrNull()
            val orphan = orphanInvocationId?.let { records.findInvocation(it) }
            if (orphan == null || orphan.status !in PRE_EXECUTION_STATUSES) {
                return commitReview(run, startRevision, "approval_missing", commits)
            }
            val commandId = recoveryCommandId(
                run.runId,
                "invocation:${orphan.invocationId}:not_executed"
            )
            val result = journal.commit(
                UpsertInvocationTransition(
                    commandId = commandId,
                    invocation = orphan.copy(
                        status = ToolInvocationStatus.NOT_EXECUTED,
                        resultRef = null,
                        updatedAtMillis = nowMillis()
                    ),
                    expectedRevision = startRevision
                )
            )
            commits += commandId
            return commitPause(
                run = run,
                revision = result.runRevision ?: startRevision,
                reason = AgentPauseReason.APPROVAL_EXPIRED,
                action = "pause_approval_missing",
                commits = commits
            )
        }
        if (approval.status != AgentApprovalStatus.PENDING &&
            approval.status != AgentApprovalStatus.EXPIRED
        ) {
            return commitReview(run, startRevision, "approval_status_${approval.status.name}", commits)
        }
        var revision = startRevision
        if (approval.status == AgentApprovalStatus.PENDING) {
            val commandId = recoveryCommandId(run.runId, "approval:${approval.approvalId}:expired")
            val result = journal.commit(
                UpsertApprovalTransition(
                    commandId = commandId,
                    approval = approval.copy(status = AgentApprovalStatus.EXPIRED),
                    expectedRevision = revision
                )
            )
            commits += commandId
            revision = result.runRevision ?: revision
        }

        val invocation = records.findInvocation(approval.invocationId)
            ?: return commitReview(run, revision, "approval_invocation_missing", commits)
        if (invocation.status != ToolInvocationStatus.NOT_EXECUTED) {
            if (invocation.status !in PRE_EXECUTION_STATUSES) {
                return commitReview(run, revision, "approval_invocation_may_have_executed", commits)
            }
            val commandId = recoveryCommandId(run.runId, "invocation:${invocation.invocationId}:not_executed")
            val result = journal.commit(
                UpsertInvocationTransition(
                    commandId = commandId,
                    invocation = invocation.copy(
                        status = ToolInvocationStatus.NOT_EXECUTED,
                        resultRef = null,
                        updatedAtMillis = nowMillis()
                    ),
                    expectedRevision = revision
                )
            )
            commits += commandId
            revision = result.runRevision ?: revision
        }

        return commitPause(
            run = run,
            revision = revision,
            reason = AgentPauseReason.APPROVAL_EXPIRED,
            action = "pause_approval_expired",
            commits = commits
        )
    }

    private suspend fun recoverStopping(
        snapshot: AgentRunSnapshot,
        startRevision: Long,
        commits: MutableList<String>
    ): AgentRunRecoveryResult {
        val run = snapshot.run
        val attempt = records.findLatestAttempt(run.runId)
            ?: return commitReview(run, startRevision, "stop_proof_reference_missing", commits)
        val ref = AgentExecutionRef(
            runId = run.runId,
            runEpoch = run.epoch,
            attemptId = attempt.attemptId,
            backendRequestId = attempt.backendRequestId
        )
        val proof = stopProofSource.awaitQuiescent(ref, stopProofTimeoutMillis)
        return when (proof) {
            is AgentQuiescenceEvidence.Quiescent -> commitPause(
                run = run,
                revision = startRevision,
                reason = AgentPauseReason.INTERRUPTED,
                action = "pause_after_stop_proof",
                commits = commits
            )

            is AgentQuiescenceEvidence.NotQuiescent -> commitReview(
                run,
                startRevision,
                "stop_not_confirmed:${proof.evidenceRef.take(80)}",
                commits
            )

            is AgentQuiescenceEvidence.TimedOut -> commitReview(
                run,
                startRevision,
                "stop_proof_timeout",
                commits
            )

            is AgentQuiescenceEvidence.Unknown -> commitReview(
                run,
                startRevision,
                "stop_proof_unknown:${proof.reasonRef.take(80)}",
                commits
            )
        }
    }

    private suspend fun commitPause(
        run: AgentRun,
        revision: Long,
        reason: AgentPauseReason,
        action: String,
        commits: MutableList<String>
    ): AgentRunRecoveryResult {
        val commandId = recoveryCommandId(run.runId, action)
        journal.commit(
            UpdateRunTransition(
                commandId = commandId,
                run = run.copy(
                    state = AgentRunState.PAUSED,
                    reason = reason,
                    revision = revision + 1L,
                    updatedAtMillis = nowMillis()
                ),
                taskDisplay = AgentDisplayStatus.PAUSED,
                expectedRevision = revision
            )
        )
        commits += commandId
        return AgentRunRecoveryResult.Recovered(
            runId = run.runId,
            fromState = run.state,
            toState = AgentRunState.PAUSED,
            pauseReason = reason,
            commits = commits.toList()
        )
    }

    private suspend fun commitReview(
        run: AgentRun,
        revision: Long,
        reasonRef: String,
        commits: MutableList<String>
    ): AgentRunRecoveryResult {
        val commandId = recoveryCommandId(run.runId, "needs_review")
        journal.commit(
            UpdateRunTransition(
                commandId = commandId,
                run = run.copy(
                    state = AgentRunState.NEEDS_REVIEW,
                    reason = AgentPauseReason.INTERRUPTED,
                    revision = revision + 1L,
                    updatedAtMillis = nowMillis()
                ),
                taskDisplay = AgentDisplayStatus.NEEDS_REVIEW,
                expectedRevision = revision
            )
        )
        commits += commandId
        return AgentRunRecoveryResult.NeedsReview(
            runId = run.runId,
            fromState = run.state,
            reasonRef = reasonRef,
            commits = commits.toList()
        )
    }

    private suspend fun reconcileArtifacts(
        snapshot: AgentRunSnapshot,
        startRevision: Long,
        commits: MutableList<String>
    ): ArtifactPass {
        var revision = startRevision
        val resolvedArtifactIds = mutableSetOf<String>()
        for (artifact in snapshot.artifacts) {
            if (artifact.commitState == AgentArtifactCommitState.FAILED) continue
            val commit = records.findArtifactCommit(artifact.artifactId)
            if (commit == null) {
                if (artifact.commitState == AgentArtifactCommitState.PREPARING ||
                    artifact.commitState == AgentArtifactCommitState.UNKNOWN
                ) {
                    return ArtifactPass(
                        revision,
                        "artifact_commit_missing:${artifact.artifactId}",
                        resolvedArtifactIds
                    )
                }
                continue
            }
            when (commit.phase) {
                AgentArtifactCommitPhase.COMMITTED -> {
                    if (artifact.commitState != AgentArtifactCommitState.COMMITTED) {
                        revision = commitCommittedArtifact(
                            runId = snapshot.run.runId,
                            artifact = artifact,
                            commit = commit,
                            revision = revision,
                            commits = commits
                        )
                    }
                    resolvedArtifactIds += artifact.artifactId
                }

                AgentArtifactCommitPhase.PREPARING,
                AgentArtifactCommitPhase.STAGED,
                AgentArtifactCommitPhase.RENAMED,
                AgentArtifactCommitPhase.UNKNOWN -> {
                    when (artifactReconciler.confirmCommitted(commit)) {
                        is AgentArtifactReconcileResult.Committed -> {
                            revision = commitCommittedArtifact(
                                runId = snapshot.run.runId,
                                artifact = artifact,
                                commit = commit,
                                revision = revision,
                                commits = commits
                            )
                            resolvedArtifactIds += artifact.artifactId
                        }

                        AgentArtifactReconcileResult.NotCommitted -> return ArtifactPass(
                            revision,
                            "artifact_commit_not_committed:${artifact.artifactId}",
                            resolvedArtifactIds
                        )

                        AgentArtifactReconcileResult.Unknown -> return ArtifactPass(
                            revision,
                            "artifact_commit_unknown:${artifact.artifactId}",
                            resolvedArtifactIds
                        )
                    }
                }

                AgentArtifactCommitPhase.FAILED -> {
                    if (artifact.commitState == AgentArtifactCommitState.UNKNOWN ||
                        artifact.commitState == AgentArtifactCommitState.COMMITTED
                    ) {
                        return ArtifactPass(
                            revision,
                            "artifact_commit_failed_inconsistent:${artifact.artifactId}",
                            resolvedArtifactIds
                        )
                    }
                    continue
                }
            }
        }
        return ArtifactPass(
            revision = revision,
            needsReviewReason = null,
            resolvedArtifactIds = resolvedArtifactIds
        )
    }

    private suspend fun commitCommittedArtifact(
        runId: String,
        artifact: AgentArtifact,
        commit: AgentArtifactCommit,
        revision: Long,
        commits: MutableList<String>
    ): Long {
        val commandId = recoveryCommandId(runId, "artifact:${artifact.artifactId}:committed")
        val result = journal.commit(
            UpsertArtifactTransition(
                commandId = commandId,
                artifact = artifact.copy(commitState = AgentArtifactCommitState.COMMITTED),
                commit = commit.copy(
                    phase = AgentArtifactCommitPhase.COMMITTED,
                    updatedAtMillis = nowMillis()
                ),
                expectedRevision = revision
            )
        )
        commits += commandId
        return result.runRevision ?: revision
    }

    private suspend fun externalSideEffectUnknown(
        snapshot: AgentRunSnapshot,
        resolvedArtifactIds: Set<String>
    ): Boolean {
        if (snapshot.artifacts.any {
                it.commitState == AgentArtifactCommitState.UNKNOWN &&
                    it.artifactId !in resolvedArtifactIds
            }
        ) {
            return true
        }
        if (snapshot.steps.any { it.status == AgentStepStatus.RUNNING && it.kind != AgentStepKind.MODEL }) {
            return true
        }
        for (step in snapshot.steps) {
            val invocationId = step.invocationId ?: continue
            val invocation = try {
                records.findInvocation(invocationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return true
            } ?: continue
            if (invocation.status == ToolInvocationStatus.RUNNING ||
                invocation.status == ToolInvocationStatus.UNKNOWN ||
                // N03: authorized but not confirmed executed (dispatch intent). The loop state that
                // would resume it is gone, so recovery must not treat it as idle or as success.
                invocation.status == ToolInvocationStatus.APPROVED
            ) {
                return true
            }
        }
        return false
    }

    private fun nowMillis(): Long = clock()

    private fun recoveryCommandId(runId: String, action: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$runId|$action".toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
        return "sr-" + hex.take(32)
    }

    private fun recoveryError(runId: String?, causeRef: String, failure: Throwable): AgentError =
        AgentError(
            code = AgentErrorCodes.INTERNAL_UNEXPECTED,
            category = AgentErrorCategory.INTERNAL,
            retryability = AgentRetryability.NEVER,
            safeMessage = "启动恢复未能完成一个任务，数据已保留。",
            correlationId = runId ?: "agent-startup-recovery",
            causeRef = "$causeRef:${failure.javaClass.simpleName}",
            possibleSideEffects = false
        )

    private data class ArtifactPass(
        val revision: Long,
        val needsReviewReason: String?,
        val resolvedArtifactIds: Set<String> = emptySet()
    )

    private companion object {
        const val DEFAULT_SCAN_LIMIT = 100
        const val DEFAULT_STOP_PROOF_TIMEOUT_MILLIS = 2_000L
        val HEX = "0123456789abcdef".toCharArray()
        val PRE_EXECUTION_STATUSES = setOf(
            ToolInvocationStatus.PROPOSED,
            ToolInvocationStatus.VALIDATED,
            ToolInvocationStatus.WAITING_APPROVAL,
            ToolInvocationStatus.APPROVED
        )
    }
}
