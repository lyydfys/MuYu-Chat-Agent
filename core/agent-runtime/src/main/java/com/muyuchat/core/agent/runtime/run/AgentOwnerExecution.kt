package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApproval
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
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.ToolInvocationStatus
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
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/** Run scheduling, settlement and the single terminal write; serialized by [AgentRunOwner]. */
internal suspend fun AgentRunOwner.schedule(run: AgentRun) {
        if (active == null) {
            promote(run)
        } else {
            waiting.addLast(run.runId)
        }
    }

/**
 * Queues the exact resume of a run that already committed `RUNNING` after a park (S2).
 *
 * The resume is stored before [schedule], so both the immediate promotion and a later
 * [promoteNextWaiting] read the same one-shot material. The run never executes beside the active
 * run: [promote] re-validates the epoch/state and only then grants the single admission slot.
 */
internal suspend fun AgentRunOwner.scheduleResume(
        run: AgentRun,
        resume: AgentLoopResume,
        kind: PendingResumeKind,
        invocationId: String? = null
    ) {
        pendingResumes[run.runId] = PendingOwnerResume(
            runId = run.runId,
            epoch = run.epoch,
            resume = resume,
            kind = kind,
            invocationId = invocationId
        )
        schedule(run)
    }

internal suspend fun AgentRunOwner.promote(run: AgentRun) {
        val pending = pendingResumes.remove(run.runId)
        if (pending != null && run.state != AgentRunState.QUEUED) {
            // An already-RUNNING parked run: never re-promote it from a bare transcript. Any other
            // state is a stale resume and fails closed instead of being executed.
            promotePendingResume(run, pending)
            return
        }
        val now = clock.nowMillis()
        val running = run.copy(
            state = AgentRunState.RUNNING,
            revision = run.revision + 1,
            updatedAtMillis = now
        )
        try {
            journal.commit(
                UpdateRunTransition(
                    commandId = ids.nextId("cmd"),
                    run = running,
                    taskDisplay = AgentDisplayStatus.RUNNING,
                    expectedRevision = run.revision
                )
            )
        } catch (rejected: AgentJournalRejectedException) {
            promoteNextWaiting()
            return
        }
        activeRunIdValue = running.runId
        launchExecution(running.runId, running.epoch, suspended.remove(running.runId)?.let { transcript ->
            AgentLoopResume(transcript = transcript)
        })
    }

/**
 * Grants the single admission slot to an already-`RUNNING` parked run.
 *
 * The epoch and durable state are re-checked at the moment the slot is granted. A resume that no
 * longer belongs to this row fails closed: it is discarded, the run is paused with a recoverable
 * reason, and no executor is called. This is the only path that can start a parked resume.
 */
private suspend fun AgentRunOwner.promotePendingResume(run: AgentRun, pending: PendingOwnerResume) {
        if (run.runId == pending.runId &&
            run.epoch == pending.epoch &&
            run.state == AgentRunState.RUNNING
        ) {
            activeRunIdValue = run.runId
            launchExecution(run.runId, run.epoch, pending.resume)
            return
        }
        failClosedPendingResume(run, pending)
        promoteNextWaiting()
    }

/**
 * Discards an unusable queued resume without executing it.
 *
 * A run that another settlement already moved (cancelled/paused/terminal) is left untouched; a row
 * still `RUNNING` is paused with a recoverable reason so the user can continue the task. An approved
 * invocation that never executed is settled `NOT_EXECUTED`, so a later dispatch can never claim it.
 */
private suspend fun AgentRunOwner.failClosedPendingResume(run: AgentRun, pending: PendingOwnerResume) {
        suspended.remove(run.runId)
        pendingApprovals.remove(run.runId)
        val invocationId = pending.invocationId
        if (run.state == AgentRunState.RUNNING) {
            if (invocationId != null) {
                try {
                    toolLedger.markTerminal(
                        invocationId = invocationId,
                        status = ToolInvocationStatus.NOT_EXECUTED,
                        possibleSideEffects = false,
                        resultRef = null,
                        atMillis = clock.nowMillis()
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The pause below still fails closed; the terminal invocation is defense in depth.
                }
            }
            val paused = run.copy(
                state = AgentRunState.PAUSED,
                reason = pending.failClosedReason,
                revision = run.revision + 1,
                updatedAtMillis = clock.nowMillis()
            )
            try {
                journal.commit(
                    UpdateRunTransition(ids.nextId("cmd"), paused, AgentDisplayStatus.PAUSED, run.revision)
                )
            } catch (_: AgentJournalRejectedException) {
                return
            }
        }
    }

internal fun AgentRunOwner.promoteNextWaiting() {
        val nextId = waiting.removeFirstOrNull() ?: return
        scope.launch {
            processor.submit(AgentCommandPriority.CONTROL) {
                if (active != null) {
                    // Another run won the slot first; keep this queued run for the next release.
                    waiting.addFirst(nextId)
                } else {
                    val snapshot = journal.readRun(nextId)
                    when {
                        snapshot == null -> {
                            // The run row disappeared; drop the stale in-memory material.
                            pendingResumes.remove(nextId)
                            suspended.remove(nextId)
                        }
                        snapshot.run.state == AgentRunState.QUEUED -> promote(snapshot.run)
                        // A parked run whose answer/approval resume committed RUNNING while queued.
                        pendingResumes.containsKey(nextId) -> promote(snapshot.run)
                        // The run settled elsewhere while queued (cancel/pause/terminal): never start.
                        else -> Unit
                    }
                }
                AgentCommandResult.Accepted(0L, nextId)
            }
        }
    }

internal fun AgentRunOwner.launchExecution(runId: String, epoch: Long, resume: AgentLoopResume?) {
        active = ActiveExecution(runId, epoch, null)
        val job = scope.launch {
            val snapshot = journal.readRun(runId) ?: return@launch
            // A cancel that landed between scheduling and job start must not run the model.
            if (snapshot.run.state != AgentRunState.RUNNING) return@launch
            val result = try {
                executor.execute(snapshot.run, snapshot.task, resume, AgentModelProgressSink { })
            } catch (cancelled: CancellationException) {
                return@launch
            } catch (failure: Throwable) {
                processor.submit(AgentCommandPriority.CONTROL) {
                    applyLoopFailure(runId, failure)
                    AgentCommandResult.Accepted(0L, runId)
                }
                return@launch
            }
            processor.submit(AgentCommandPriority.CONTROL) {
                applyLoopResult(runId, result)
                AgentCommandResult.Accepted(result.run.revision, runId)
            }
        }
        active = active?.copy(job = job)
    }

internal suspend fun AgentRunOwner.settlePause(runId: String) {
        val snapshot = journal.readRun(runId) ?: return
        val run = snapshot.run
        if (run.state != AgentRunState.PAUSING) return
        val now = clock.nowMillis()
        // Carry the typed request reason through the PAUSING window; legacy/unknown values fall
        // back to USER_REQUESTED rather than persisting an internal recovery reason.
        val pauseReason = when (run.reason) {
            AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE -> AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE
            else -> AgentPauseReason.USER_REQUESTED
        }
        val paused = run.copy(
            state = AgentRunState.PAUSED,
            reason = pauseReason,
            revision = run.revision + 1,
            updatedAtMillis = now
        )
        try {
            journal.commit(
                UpdateRunTransition(
                    ids.nextId("cmd"),
                    paused,
                    AgentDisplayStatus.PAUSED,
                    run.revision
                )
            )
        } catch (_: AgentJournalRejectedException) {
            return
        }
        releaseActive(runId)
    }

internal suspend fun AgentRunOwner.settleCancel(runId: String) {
        val snapshot = journal.readRun(runId) ?: return
        val run = snapshot.run
        if (run.state != AgentRunState.STOPPING) return
        val now = clock.nowMillis()
        val cancelled = run.copy(
            state = AgentRunState.CANCELLED,
            reason = AgentPauseReason.NONE,
            revision = run.revision + 1,
            updatedAtMillis = now
        )
        try {
            journal.commit(
                UpdateRunTransition(
                    ids.nextId("cmd"),
                    cancelled,
                    AgentDisplayStatus.CANCELLED,
                    run.revision
                )
            )
        } catch (_: AgentJournalRejectedException) {
            return
        }
        releaseActive(runId)
    }

internal suspend fun AgentRunOwner.applyLoopResult(runId: String, result: AgentLoopResult) {
        val snapshot = journal.readRun(runId) ?: return
        val current = snapshot.run
        if (current.state.isTerminal) {
            releaseActive(runId)
            return
        }
        if (current.state == AgentRunState.STOPPING) {
            // The loop ended, but the native/network owner may still be winding down. Settle only
            // through the typed resource settlement; never write CANCELLED from loop completion.
            startStopSettlement(
                runId = runId,
                runEpoch = current.epoch,
                job = null,
                executionReturned = true
            )
            return
        }
        when (result) {
            is AgentLoopResult.Completed -> completeRun(runId, current, result)
            is AgentLoopResult.WaitingUser -> waitForUser(runId, current, result)
            is AgentLoopResult.WaitingApproval -> waitForApproval(runId, current, result)
            is AgentLoopResult.Paused -> {
                if (current.state != AgentRunState.RUNNING) {
                    releaseActive(runId)
                    return
                }
                val paused = current.copy(
                    state = AgentRunState.PAUSED,
                    reason = result.reason,
                    revision = current.revision + 1,
                    updatedAtMillis = clock.nowMillis(),
                    spentBudget = result.run.spentBudget
                )
                commitOrRelease(runId, paused, AgentDisplayStatus.PAUSED)
            }
            is AgentLoopResult.NeedsReview -> {
                if (current.state != AgentRunState.RUNNING) {
                    releaseActive(runId)
                    return
                }
                val review = current.copy(
                    state = AgentRunState.NEEDS_REVIEW,
                    reason = AgentPauseReason.UNKNOWN,
                    revision = current.revision + 1,
                    updatedAtMillis = clock.nowMillis(),
                    spentBudget = result.run.spentBudget
                )
                commitOrRelease(runId, review, AgentDisplayStatus.NEEDS_REVIEW)
            }
            is AgentLoopResult.Failed -> {
                if (current.state == AgentRunState.PAUSING) {
                    return
                }
                val failed = current.copy(
                    state = AgentRunState.FAILED,
                    reason = AgentPauseReason.NONE,
                    revision = current.revision + 1,
                    updatedAtMillis = clock.nowMillis(),
                    spentBudget = result.run.spentBudget
                )
                commitOrRelease(runId, failed, AgentDisplayStatus.FAILED)
            }
            is AgentLoopResult.Cancelled -> {
                if (current.state == AgentRunState.PAUSING) {
                    settlePause(runId)
                } else {
                    val interrupted = current.copy(
                        state = AgentRunState.INTERRUPTED,
                        reason = AgentPauseReason.INTERRUPTED,
                        revision = current.revision + 1,
                        updatedAtMillis = clock.nowMillis(),
                        spentBudget = result.run.spentBudget
                    )
                    commitOrRelease(runId, interrupted, AgentDisplayStatus.INTERRUPTED)
                }
            }
        }
    }

internal suspend fun AgentRunOwner.completeRun(
        runId: String,
        current: AgentRun,
        result: AgentLoopResult.Completed
    ) {
        if (current.state != AgentRunState.RUNNING) {
            releaseActive(runId)
            return
        }
        val succeeded = current.copy(
            state = AgentRunState.SUCCEEDED,
            reason = AgentPauseReason.NONE,
            revision = current.revision + 1,
            updatedAtMillis = clock.nowMillis(),
            spentBudget = result.run.spentBudget
        )
        commitOrRelease(
            runId,
            succeeded,
            AgentDisplayStatus.SUCCEEDED,
            terminalSummary = result.finalText
        )
    }

internal suspend fun AgentRunOwner.waitForUser(
        runId: String,
        current: AgentRun,
        result: AgentLoopResult.WaitingUser
    ) {
        if (current.state != AgentRunState.RUNNING) {
            releaseActive(runId)
            return
        }
        val now = clock.nowMillis()
        val question = AgentQuestion(
            questionId = ids.nextId("question"),
            runId = runId,
            stepIndex = result.transcript.nextStepIndex - 1,
            text = result.questionText,
            options = result.options,
            status = AgentQuestionStatus.OPEN,
            askedAtMillis = now
        )
        val next = current.copy(
            state = AgentRunState.WAITING_USER,
            reason = AgentPauseReason.NONE,
            revision = current.revision + 1,
            updatedAtMillis = now,
            spentBudget = result.run.spentBudget
        )
        val committed = try {
            journal.commit(
                AskQuestionTransition(
                    commandId = ids.nextId("cmd"),
                    question = question,
                    nextRun = next,
                    taskDisplay = AgentDisplayStatus.WAITING_USER,
                    expectedRevision = current.revision
                )
            )
        } catch (_: AgentJournalRejectedException) {
            return
        }
        if (committed.runRevision == null) return
        suspended[runId] = result.transcript
        // S4: the durable token is the restart material for this exact park.
        recordParkToken(
            run = next,
            transcript = result.transcript,
            kind = AgentParkKind.USER,
            questionId = question.questionId
        )
        // S2 / WF-17: an ordinary park releases the single admission slot. The durable question and
        // the in-memory transcript stay; only the owner's slot is handed to the next queued run.
        releaseSlotForPark(runId)
    }

/**
 * Enters WAITING_APPROVAL only for a durable invocation and a PENDING approval that are fully bound
 * to each other and to the current run epoch/policy version. A missing or unbound pairing fails the
 * run closed instead of leaving a pending prompt with no invocation behind it.
 */
internal suspend fun AgentRunOwner.waitForApproval(
        runId: String,
        current: AgentRun,
        result: AgentLoopResult.WaitingApproval
    ) {
        if (current.state != AgentRunState.RUNNING) {
            releaseActive(runId)
            return
        }
        val approval = result.approval
        if (approval == null) {
            failApprovalWait(runId, current, "The runtime produced no approval for the waiting tool call.")
            return
        }
        val invocation = toolLedger.readInvocation(result.invocationId)
        val bound = invocation != null &&
            invocation.runId == runId &&
            invocation.status == ToolInvocationStatus.WAITING_APPROVAL &&
            approval.invocationId == invocation.invocationId &&
            approval.argumentsHash == invocation.argumentsHash &&
            approval.toolSchemaHash == invocation.schemaHash &&
            approval.resourceFingerprint == invocation.resourceFingerprint &&
            approval.runEpoch == current.epoch &&
            approval.policyVersion == current.policy.policyVersion
        if (!bound) {
            failApprovalWait(
                runId,
                current,
                "The pending approval is not bound to a recorded invocation."
            )
            return
        }
        val committed = try {
            journal.commit(
                UpsertApprovalTransition(
                    commandId = ids.nextId("cmd"),
                    approval = approval,
                    expectedRevision = current.revision
                )
            )
        } catch (_: AgentJournalRejectedException) {
            return
        }
        val revision = committed.runRevision ?: current.revision
        val now = clock.nowMillis()
        val next = current.copy(
            state = AgentRunState.WAITING_APPROVAL,
            reason = AgentPauseReason.NONE,
            revision = revision + 1,
            updatedAtMillis = now,
            spentBudget = result.run.spentBudget
        )
        try {
            journal.commit(
                UpdateRunTransition(
                    ids.nextId("cmd"),
                    next,
                    AgentDisplayStatus.WAITING_APPROVAL,
                    revision
                )
            )
        } catch (_: AgentJournalRejectedException) {
            return
        }
        pendingApprovals[runId] = PendingApprovalExecution(
            approvalId = approval.approvalId,
            invocationId = invocation.invocationId,
            callHash = result.callHash,
            toolKey = invocation.toolKey
        )
        suspended[runId] = result.transcript
        // S4: persist the exact approval-park material before releasing the admission slot.
        recordParkToken(
            run = next,
            transcript = result.transcript,
            kind = AgentParkKind.APPROVAL,
            approvalId = approval.approvalId,
            invocationId = invocation.invocationId
        )
        // S2 / WF-17: an ordinary tool-approval park releases the single admission slot too. The
        // pending approval + invocation stay durable; only the in-memory slot is freed.
        releaseSlotForPark(runId)
    }

/** Fails the run closed when a waiting result cannot be paired with a durable invocation. */
private suspend fun AgentRunOwner.failApprovalWait(
    runId: String,
    current: AgentRun,
    message: String
) {
    val failed = current.copy(
        state = AgentRunState.FAILED,
        reason = AgentPauseReason.NONE,
        revision = current.revision + 1,
        updatedAtMillis = clock.nowMillis()
    )
    try {
        journal.commit(
            UpdateRunTransition(
                ids.nextId("cmd"),
                failed,
                AgentDisplayStatus.FAILED,
                current.revision
            )
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AgentJournalRejectedException) {
        releaseActive(runId)
        return
    }
    appendTerminalEventIfNeeded(failed, AgentDisplayStatus.FAILED, message)
    clearParkToken(runId)
    releaseActive(runId)
}

internal suspend fun AgentRunOwner.commitOrRelease(
        runId: String,
        updated: AgentRun,
        display: AgentDisplayStatus,
        terminalSummary: String? = null
    ) {
        val snapshot = journal.readRun(runId) ?: return
        try {
            journal.commit(
                UpdateRunTransition(
                    ids.nextId("cmd"),
                    updated.copy(revision = snapshot.run.revision + 1),
                    display,
                    snapshot.run.revision
                )
            )
            appendTerminalEventIfNeeded(updated, display, terminalSummary)
        } catch (_: AgentJournalRejectedException) {
            return
        }
        clearParkToken(runId)
        releaseActive(runId)
    }

internal suspend fun AgentRunOwner.appendTerminalEventIfNeeded(
        updated: AgentRun,
        display: AgentDisplayStatus,
        terminalSummary: String? = null
    ) {
        if (display != AgentDisplayStatus.SUCCEEDED &&
            display != AgentDisplayStatus.FAILED &&
            display != AgentDisplayStatus.CANCELLED
        ) {
            return
        }
        val snapshot = journal.readRun(updated.runId) ?: return
        val event = AgentEvent(
            eventId = ids.nextId("event"),
            runId = updated.runId,
            sequence = snapshot.run.lastSequence + 1,
            type = AgentEventTypes.RUN_TERMINAL,
            createdAtMillis = clock.nowMillis(),
            summary = if (display == AgentDisplayStatus.SUCCEEDED) {
                boundedTerminalSummary(terminalSummary) ?: display.name
            } else {
                display.name
            }
        )
        try {
            journal.commit(
                AppendEventsTransition(
                    ids.nextId("cmd"),
                    updated.runId,
                    listOf(event),
                    snapshot.run.revision
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AgentJournalRejectedException) {
            // A concurrent terminal/recovery commit already settled; the audit event is optional.
        }
    }

internal suspend fun AgentRunOwner.applyLoopFailure(runId: String, failure: Throwable) {
        val snapshot = journal.readRun(runId) ?: return
        val current = snapshot.run
        if (current.state.isTerminal) {
            releaseActive(runId)
            return
        }
        if (current.state == AgentRunState.STOPPING) {
            // A throw while stopping is still a stop: keep the fence until real quiescence.
            startStopSettlement(
                runId = runId,
                runEpoch = current.epoch,
                job = null,
                executionReturned = true
            )
            return
        }
        val failed = current.copy(
            state = AgentRunState.FAILED,
            reason = AgentPauseReason.NONE,
            revision = current.revision + 1,
            updatedAtMillis = clock.nowMillis()
        )
        try {
            journal.commit(
                UpdateRunTransition(ids.nextId("cmd"), failed, AgentDisplayStatus.FAILED, current.revision)
            )
        } catch (_: AgentJournalRejectedException) {
            return
        }
        appendTerminalEventIfNeeded(failed, AgentDisplayStatus.FAILED)
        clearParkToken(runId)
        releaseActive(runId)
    }

/**
 * Releases the single owner admission slot for a run that is durably parked (S2 / WF-17).
 *
 * Deliberately NOT [releaseActive]: a park must keep [AgentRunOwner.suspended] and
 * [AgentRunOwner.pendingApprovals] because the later answer/approval resume consumes them. Only the
 * in-memory slot is freed, then the next queued run is promoted.
 */
internal fun AgentRunOwner.releaseSlotForPark(runId: String) {
    if (active?.runId != runId) return
    active = null
    activeRunIdValue = null
    promoteNextWaiting()
}

internal fun AgentRunOwner.releaseActive(runId: String) {
    val current = active
    suspended.remove(runId)
    pendingApprovals.remove(runId)
    pendingResumes.remove(runId)
    stopSettlements.remove(runId)
    // A terminal run can never consume a buffered interjection; dropping it here keeps the registry
    // from growing per run and stops stale text from reaching a later run.
    AgentInterjectionQueues.global.clear(runId)
    scope.launch { clearParkToken(runId) }
    if (current?.runId == runId) {
        active = null
        activeRunIdValue = null
        promoteNextWaiting()
    } else if (current == null) {
        promoteNextWaiting()
    }
}

    // ------------------------------------------------------------------------------------------
    // Idempotency, errors and fingerprints.
    // ------------------------------------------------------------------------------------------

/** Bounded visible-answer text for the terminal audit event (4 KiB summary hard limit). */
internal fun boundedTerminalSummary(text: String?): String? {
    val normalized = text
        ?.filterNot { it.isISOControl() && it != '\n' && it != '\t' }
        ?.trim()
        .orEmpty()
    return normalized
        .take(1_200)
        .takeIf { it.isNotEmpty() }
}

