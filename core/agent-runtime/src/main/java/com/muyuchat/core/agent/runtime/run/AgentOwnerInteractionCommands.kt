package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.AnswerAgentQuestion
import com.muyuchat.core.agent.contract.AnswerQuestionTransition
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.AskQuestionTransition
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.ConsumeApprovalTransition
import com.muyuchat.core.agent.contract.ContinueAgentTask
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.PauseAgentRun
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.contract.UpsertApprovalTransition
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.journal.AgentApprovalReader
import com.muyuchat.core.agent.runtime.journal.AgentCommandLedger
import com.muyuchat.core.agent.runtime.journal.AgentCommandRecord
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException
import com.muyuchat.core.agent.runtime.journal.AgentQuestionReader
import com.muyuchat.core.agent.runtime.journal.AgentTaskReader
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/** Continue/answer/approval command handlers; serialized by [AgentRunOwner]. */
internal suspend fun AgentRunOwner.continueLocked(command: ContinueAgentTask): AgentCommandResult {
        val fingerprint = fingerprintContinue(command)
        replay(command.commandId, fingerprint)?.let { return it }
        val task = taskReader.readTask(command.taskId)
            ?: return rejected(AgentErrorCodes.COMMAND_NOT_FOUND, "Task '${command.taskId}' not found.")
        if (task.revision != command.expectedTaskRevision) return revisionConflict(command.commandId)
        val previousSnapshot = task.lastRunId?.let { journal.readRun(it) }
            ?: return invalidState(command.commandId, "The task has no previous run to continue.")
        val previous = previousSnapshot.run
        if (previous.state != AgentRunState.PAUSED && previous.state != AgentRunState.NEEDS_REVIEW) {
            return invalidState(
                command.commandId,
                "Only a PAUSED or NEEDS_REVIEW run can be continued."
            )
        }
        capacityRejection()?.let { return it }
        val spec = specProvider.specFor(task)
        val binding = command.modelBinding ?: spec.modelBinding
        val policy = command.policy ?: spec.policy
        val budget = command.budget ?: spec.budget
        val now = clock.nowMillis()
        val run = AgentRun(
            runId = ids.nextId("run"),
            taskId = task.taskId,
            runOrdinal = previous.runOrdinal + 1,
            state = AgentRunState.QUEUED,
            reason = AgentPauseReason.NONE,
            epoch = previous.epoch + 1,
            modelBinding = binding,
            policy = policy,
            budget = budget,
            spentBudget = AgentSpentBudget(),
            lastSequence = 0L,
            revision = 1L,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        val result = try {
            journal.commit(
                CreateRunTransition(
                    commandId = command.commandId,
                    run = run,
                    taskRevision = command.expectedTaskRevision,
                    taskDisplay = AgentDisplayStatus.QUEUED,
                    successorOfRunId = previous.runId
                )
            )
        } catch (rejected: AgentJournalRejectedException) {
            return fromJournal(rejected)
        }
        ledger.recordCommand(
            AgentCommandRecord(
                commandId = command.commandId,
                contentHash = fingerprint,
                appliedRevision = result.appliedRevision,
                taskRevision = result.taskRevision,
                runId = run.runId
            )
        )
        schedule(run)
        return AgentCommandResult.Accepted(result.appliedRevision, run.runId)
    }

internal suspend fun AgentRunOwner.answerLocked(command: AnswerAgentQuestion): AgentCommandResult {
        val fingerprint = fingerprintAnswer(command)
        replay(command.commandId, fingerprint)?.let { return it }
        val snapshot = journal.readRun(command.runId)
            ?: return rejected(AgentErrorCodes.COMMAND_NOT_FOUND, "Run '${command.runId}' not found.")
        val run = snapshot.run
        if (run.state != AgentRunState.WAITING_USER) {
            return invalidState(command.commandId, "The run is not waiting for a user answer.")
        }
        if (run.revision != command.expectedRunRevision) return revisionConflict(command.commandId)
        val question = snapshot.activeQuestion
            ?: return invalidState(command.commandId, "The run has no open question.")
        if (question.questionId != command.questionId) {
            return invalidState(command.commandId, "The answer targets a different question.")
        }
        val transcript = suspended[run.runId]
            ?: parkedTranscript(run, AgentParkKind.USER)
            ?: return invalidState(
                command.commandId,
                "The in-memory transcript for this run is not available."
            )
        val now = clock.nowMillis()
        val next = run.copy(
            state = AgentRunState.RUNNING,
            reason = AgentPauseReason.NONE,
            revision = run.revision + 1,
            updatedAtMillis = now
        )
        try {
            journal.commit(
                AnswerQuestionTransition(
                    commandId = command.commandId,
                    questionId = question.questionId,
                    answer = command.answer,
                    answeredAtMillis = now,
                    nextRun = next,
                    taskDisplay = AgentDisplayStatus.RUNNING,
                    expectedRevision = run.revision
                )
            )
        } catch (rejected: AgentJournalRejectedException) {
            return fromJournal(rejected)
        }
        ledger.recordCommand(
            AgentCommandRecord(command.commandId, fingerprint, next.revision, runId = run.runId)
        )
        // S2: the durable WAITING_USER -> RUNNING transition is committed, but the parked run no
        // longer owns the admission slot. The exact resume is queued and can only start through the
        // single promotion path, never beside another active run.
        suspended.remove(run.runId)
        scheduleResume(
            run = next,
            resume = AgentLoopResume(
                transcript = transcript,
                questionId = question.questionId,
                userAnswer = command.answer
            ),
            kind = PendingResumeKind.ANSWER
        )
        clearParkToken(run.runId)
        return AgentCommandResult.Accepted(next.revision, run.runId)
    }

/**
 * Resolves the one pending approval of a WAITING_APPROVAL run.
 *
 * A resume is never launched from an in-memory "approved command". The owner requires the durable
 * PENDING approval bound to the durable invocation, asks the one-shot authorization seam, and only
 * launches when it returns [AgentToolAuthorizationResult.Authorized]. Deny and every failed
 * authorization settle a paired terminal invocation and never call an executor.
 */
internal suspend fun AgentRunOwner.approvalLocked(command: ResolveAgentApproval): AgentCommandResult {
        val fingerprint = fingerprintApproval(command)
        replay(command.commandId, fingerprint)?.let { return it }
        val snapshot = journal.readRun(command.runId)
            ?: return rejected(AgentErrorCodes.COMMAND_NOT_FOUND, "Run '${command.runId}' not found.")
        val run = snapshot.run
        if (run.state != AgentRunState.WAITING_APPROVAL) {
            return invalidState(command.commandId, "The run is not waiting for approval.")
        }
        if (run.revision != command.expectedRunRevision) return revisionConflict(command.commandId)
        val pending = pendingApprovals[run.runId]
            ?: rebuildPendingApproval(run, command.approvalId)
            ?: return invalidState(command.commandId, "The run has no pending frozen invocation.")
        val approval = (snapshot.pendingApproval
            ?: approvalReader.readApproval(command.approvalId)
                ?.takeIf { it.status == AgentApprovalStatus.PENDING })
            ?.takeIf { it.approvalId == command.approvalId }
            ?: return settleApprovalFailure(
                command = command,
                run = run,
                pending = pending,
                invocationStatus = ToolInvocationStatus.NOT_EXECUTED,
                decisionLabel = "NOT_PENDING",
                errorCode = AgentErrorCodes.PERMISSION_EXPIRED,
                safeMessage = "The approval is no longer pending.",
                pauseReason = AgentPauseReason.APPROVAL_STALE,
                fingerprint = fingerprint
            )
        if (approval.runEpoch != run.epoch) {
            return settleApprovalFailure(
                command = command,
                run = run,
                pending = pending,
                invocationStatus = ToolInvocationStatus.NOT_EXECUTED,
                decisionLabel = "STALE_EPOCH",
                errorCode = AgentErrorCodes.PERMISSION_SCOPE_REVOKED,
                safeMessage = "The approval belongs to a stale run epoch.",
                pauseReason = AgentPauseReason.APPROVAL_STALE,
                fingerprint = fingerprint
            )
        }
        if (approval.policyVersion != run.policy.policyVersion) {
            return settleApprovalFailure(
                command = command,
                run = run,
                pending = pending,
                invocationStatus = ToolInvocationStatus.NOT_EXECUTED,
                decisionLabel = "STALE_POLICY",
                errorCode = AgentErrorCodes.PERMISSION_SCOPE_REVOKED,
                safeMessage = "The approval belongs to a stale policy version.",
                pauseReason = AgentPauseReason.APPROVAL_STALE,
                fingerprint = fingerprint
            )
        }
        if (pending.approvalId != approval.approvalId || pending.invocationId != approval.invocationId) {
            return invalidState(command.commandId, "The approval targets a different invocation.")
        }
        val transcript = suspended[run.runId]
            ?: parkedTranscript(run, AgentParkKind.APPROVAL)
            ?: return invalidState(
                command.commandId,
                "The in-memory transcript for this run is not available."
            )
        val now = clock.nowMillis()
        if (now > approval.expiresAtMillis) {
            return settleApprovalFailure(
                command = command,
                run = run,
                pending = pending,
                invocationStatus = ToolInvocationStatus.NOT_EXECUTED,
                decisionLabel = "EXPIRED",
                errorCode = AgentErrorCodes.PERMISSION_EXPIRED,
                safeMessage = "The approval expired before it was resolved.",
                pauseReason = AgentPauseReason.APPROVAL_EXPIRED,
                fingerprint = fingerprint
            )
        }
        val request = AgentToolAuthorizationRequest(
            run = run,
            task = snapshot.task,
            invocationId = pending.invocationId,
            approvalId = approval.approvalId,
            nowMillis = now
        )
        if (command.decision == AgentApprovalDecision.DENY) {
            // N03: the denial CAS, the terminal invocation and the resume commit form one durable
            // unit. Without it a crash after consuming the approval leaves a run that can never
            // resolve the same invocation again.
            var prepared: PreparedApprovalResume? = null
            var denialOutcome: AgentToolDenialResult? = null
            try {
                transaction.inTransaction {
                    val denial = toolAuthorization.deny(request)
                    denialOutcome = denial
                    if (denial is AgentToolDenialResult.Denied) {
                        toolLedger.markTerminal(
                            invocationId = pending.invocationId,
                            status = ToolInvocationStatus.DENIED,
                            possibleSideEffects = false,
                            resultRef = null,
                            atMillis = now
                        )
                        prepared = prepareApprovalResume(command, run, fingerprint, now) {
                            AgentLoopResume(
                                transcript = transcript,
                                toolDecision = AgentResumeToolDecision.NotExecuted(
                                    invocationId = pending.invocationId,
                                    callHash = pending.callHash,
                                    toolKey = pending.toolKey,
                                    status = AgentToolResultStatus.DENIED,
                                    safeMessage = denial.safeMessage,
                                    errorCode = AgentErrorCodes.PERMISSION_DENIED
                                )
                            )
                        }
                    }
                }
            } catch (rejected: AgentJournalRejectedException) {
                return fromJournal(rejected)
            }
            val preparedResume = prepared
            if (preparedResume != null) {
                completeApprovalResume(preparedResume)
                return preparedResume.result
            }
            val denial = denialOutcome as AgentToolDenialResult.NotExecuted
            return settleApprovalFailure(
                command = command,
                run = run,
                pending = pending,
                invocationStatus = ToolInvocationStatus.NOT_EXECUTED,
                decisionLabel = "DENY_REJECTED",
                errorCode = denial.errorCode,
                safeMessage = denial.safeMessage,
                pauseReason = AgentPauseReason.APPROVAL_STALE,
                fingerprint = fingerprint
            )
        }
        // N03 allow path: consuming the one-shot approval and committing the run transition must be
        // atomic. The actual tool call still happens later, outside this transaction.
        var prepared: PreparedApprovalResume? = null
        var authorization: AgentToolAuthorizationResult? = null
        try {
            transaction.inTransaction {
                val result = toolAuthorization.authorize(request)
                authorization = result
                if (result is AgentToolAuthorizationResult.Authorized) {
                    prepared = prepareApprovalResume(
                        command,
                        run,
                        fingerprint,
                        now,
                        markInvocationApproved = pending.invocationId
                    ) {
                        AgentLoopResume(
                            transcript = transcript,
                            toolDecision = AgentResumeToolDecision.Authorized(
                                authorization = result.authorization,
                                callHash = pending.callHash
                            )
                        )
                    }
                }
            }
        } catch (rejected: AgentJournalRejectedException) {
            return fromJournal(rejected)
        }
        val preparedResume = prepared
        if (preparedResume != null) {
            completeApprovalResume(preparedResume)
            return preparedResume.result
        }
        val notExecuted = authorization as AgentToolAuthorizationResult.NotExecuted
        return settleApprovalFailure(
            command = command,
            run = run,
            pending = pending,
            invocationStatus = if (notExecuted.toolStatus == AgentToolResultStatus.DENIED) {
                ToolInvocationStatus.DENIED
            } else {
                ToolInvocationStatus.NOT_EXECUTED
            },
            decisionLabel = "NOT_EXECUTED",
            errorCode = notExecuted.errorCode,
            safeMessage = notExecuted.safeMessage,
            pauseReason = AgentPauseReason.APPROVAL_STALE,
            fingerprint = fingerprint
        )
}

/**
 * Durable part of an approved/denied resume: the WAITING_APPROVAL -> RUNNING transition and the
 * idempotency row. Callers run this inside [AgentRunTransaction] together with the approval CAS, so
 * either all of them commit or none do. Launching the loop and releasing in-memory state happen only
 * after that transaction returned successfully ([completeApprovalResume]).
 */
private class PreparedApprovalResume(
    val result: AgentCommandResult,
    val run: AgentRun,
    val resume: AgentLoopResume,
    /** Set only for the allow path: the invocation whose approval was just consumed. */
    val invocationId: String? = null
)

private suspend fun AgentRunOwner.prepareApprovalResume(
    command: ResolveAgentApproval,
    run: AgentRun,
    fingerprint: String,
    now: Long,
    /**
     * When set, the invocation is CASed to `APPROVED` in the same transaction: a durable dispatch
     * intent that recovery can find if the process dies before the executor starts (N03). The deny
     * path passes null because a denied call never becomes executable.
     */
    markInvocationApproved: String? = null,
    resume: () -> AgentLoopResume
): PreparedApprovalResume {
    val next = run.copy(
        state = AgentRunState.RUNNING,
        reason = AgentPauseReason.NONE,
        revision = run.revision + 1,
        updatedAtMillis = now
    )
    journal.commit(
        UpdateRunTransition(
            commandId = command.commandId,
            run = next,
            taskDisplay = AgentDisplayStatus.RUNNING,
            expectedRevision = run.revision
        )
    )
    ledger.recordCommand(
        AgentCommandRecord(command.commandId, fingerprint, next.revision, runId = run.runId)
    )
    if (markInvocationApproved != null) {
        when (val approved = toolLedger.markApproved(markInvocationApproved, now)) {
            is AgentToolInvocationUpdate.Updated -> Unit
            is AgentToolInvocationUpdate.Rejected -> throw AgentJournalRejectedException(
                approved.errorCode,
                approved.safeMessage
            )
        }
    }
    return PreparedApprovalResume(
        result = AgentCommandResult.Accepted(next.revision, run.runId),
        run = next,
        resume = resume(),
        invocationId = markInvocationApproved
    )
}

/**
 * Post-transaction half: in-memory state and resume scheduling, never inside the database
 * transaction.
 *
 * With a dispatch sink (production N03 wiring) the exact authorized resume is offered to the outbox
 * loop instead of being queued inline. The loop later calls [AgentRunOwner.dispatchApproved] and
 * consumes the in-memory hand-off exactly once, then queues it through the same admission path. If
 * no live consumer accepts it — or no hand-off is configured — the owner queues it directly, so a
 * mis-wired background loop cannot strand a run. Either way the resume never executes beside another
 * active run (S2).
 */
private suspend fun AgentRunOwner.completeApprovalResume(prepared: PreparedApprovalResume) {
    pendingApprovals.remove(prepared.run.runId)
    suspended.remove(prepared.run.runId)
    clearParkToken(prepared.run.runId)
    val invocationId = prepared.invocationId
    val sink = dispatchSink
    if (invocationId != null && sink != null && dispatchHandoff != null) {
        val dispatch = AgentApprovedDispatch(
            invocationId = invocationId,
            runId = prepared.run.runId,
            runEpoch = prepared.run.epoch,
            resume = prepared.resume
        )
        when (sink.offer(dispatch)) {
            AgentApprovedDispatchOffer.HandedOff -> return
            is AgentApprovedDispatchOffer.NotAccepted -> {
                // The owner stays the fallback consumer; never leave an unclaimed authorization
                // behind that a later dispatch pass could execute a second time.
                dispatchHandoff.discard(invocationId)
            }
        }
    }
    // S2: never launch beside another active run. A parked run no longer owns the admission slot,
    // so the exact resume is queued and re-validated (epoch/state) by the single promotion path.
    scheduleResume(
        run = prepared.run,
        resume = prepared.resume,
        kind = PendingResumeKind.APPROVAL,
        invocationId = invocationId
    )
}

/**
 * Fail-closed settlement of a resolution that must not execute: the invocation becomes terminal, a
 * paired TOOL_RESULT is journaled, and the run is paused with a recoverable approval reason so the
 * user can continue the task explicitly. No approval can be reused afterwards.
 */
private suspend fun AgentRunOwner.settleApprovalFailure(
    command: ResolveAgentApproval,
    run: AgentRun,
    pending: PendingApprovalExecution,
    invocationStatus: ToolInvocationStatus,
    decisionLabel: String,
    errorCode: String,
    safeMessage: String,
    pauseReason: AgentPauseReason,
    fingerprint: String
): AgentCommandResult {
    val now = clock.nowMillis()
    clearParkToken(run.runId)
    try {
        toolLedger.markTerminal(
            invocationId = pending.invocationId,
            status = invocationStatus,
            possibleSideEffects = false,
            resultRef = null,
            atMillis = now
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // The paired typed result below still documents that no execution happened.
    }
    val afterEvents = try {
        val resolved = AgentEvent(
            eventId = ids.nextId("event"),
            runId = run.runId,
            sequence = run.lastSequence + 1,
            type = AgentEventTypes.TOOL_APPROVAL_RESOLVED,
            createdAtMillis = now,
            causationId = pending.invocationId,
            summary = AgentToolLifecycle.approvalResolvedSummary(
                codec,
                pending.toolKey,
                pending.approvalId,
                decisionLabel
            )
        )
        val result = AgentEvent(
            eventId = ids.nextId("event"),
            runId = run.runId,
            sequence = run.lastSequence + 2,
            type = AgentEventTypes.TOOL_RESULT,
            createdAtMillis = now,
            causationId = pending.invocationId,
            summary = AgentToolLifecycle.notExecutedSummary(
                codec,
                pending.toolKey,
                if (invocationStatus == ToolInvocationStatus.DENIED) {
                    AgentToolResultStatus.DENIED
                } else {
                    AgentToolResultStatus.NOT_EXECUTED
                },
                errorCode
            )
        )
        val committed = journal.commit(
            AppendEventsTransition(
                commandId = ids.nextId("cmd"),
                runIdValue = run.runId,
                events = listOf(resolved, result),
                expectedRevision = run.revision
            )
        )
        run.copy(
            lastSequence = committed.eventSequences.lastOrNull() ?: (run.lastSequence + 2),
            revision = committed.runRevision ?: (run.revision + 1)
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: AgentJournalRejectedException) {
        return fromJournal(rejected)
    }
    val paused = afterEvents.copy(
        state = AgentRunState.PAUSED,
        reason = pauseReason,
        revision = afterEvents.revision + 1,
        updatedAtMillis = now
    )
    try {
        journal.commit(
            UpdateRunTransition(
                commandId = ids.nextId("cmd"),
                run = paused,
                taskDisplay = AgentDisplayStatus.PAUSED,
                expectedRevision = afterEvents.revision
            )
        )
    } catch (rejected: AgentJournalRejectedException) {
        return fromJournal(rejected)
    }
    ledger.recordCommand(
        AgentCommandRecord(command.commandId, fingerprint, paused.revision, runId = run.runId)
    )
    pendingApprovals.remove(run.runId)
    releaseActive(run.runId)
    return AgentCommandResult.Accepted(paused.revision, run.runId)
}

    // ------------------------------------------------------------------------------------------
    // Execution lifecycle (serialized settlements).
    // ------------------------------------------------------------------------------------------

