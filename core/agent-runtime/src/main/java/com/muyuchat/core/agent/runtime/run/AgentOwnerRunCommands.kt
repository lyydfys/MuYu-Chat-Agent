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

/** Create/start/pause/cancel command handlers; serialized by [AgentRunOwner]. */
internal suspend fun AgentRunOwner.createTaskLocked(command: CreateAgentTask): AgentCommandResult {
        val fingerprint = fingerprintCreate(command)
        replay(command.commandId, fingerprint)?.let { return it }
        val now = clock.nowMillis()
        val task = AgentTask(
            taskId = ids.nextId("task"),
            workspaceId = ids.nextId("ws"),
            goal = command.goal,
            lifecycle = AgentTaskLifecycle.ACTIVE,
            createdAtMillis = now,
            updatedAtMillis = now,
            displayStatus = AgentDisplayStatus.IDLE,
            lastRunId = null,
            revision = 0L
        )
        return commitAndRecord(
            commandId = command.commandId,
            fingerprint = fingerprint,
            runId = null,
            transition = CreateTaskTransition(command.commandId, task)
        )
    }

internal suspend fun AgentRunOwner.startRunLocked(command: StartAgentRun): AgentCommandResult {
        val fingerprint = fingerprintStart(command)
        replay(command.commandId, fingerprint)?.let { return it }
        val task = taskReader.readTask(command.taskId)
            ?: return rejected(AgentErrorCodes.COMMAND_NOT_FOUND, "Task '${command.taskId}' not found.")
        if (task.revision != command.expectedTaskRevision) return revisionConflict(command.commandId)
        if (task.lastRunId != null) {
            return invalidState(
                command.commandId,
                "This task already has a run; use ContinueAgentTask for a successor run."
            )
        }
        capacityRejection()?.let { return it }
        val spec = specProvider.specFor(task)
        val now = clock.nowMillis()
        val run = AgentRun(
            runId = ids.nextId("run"),
            taskId = task.taskId,
            runOrdinal = 1L,
            state = AgentRunState.QUEUED,
            reason = AgentPauseReason.NONE,
            epoch = 1L,
            modelBinding = spec.modelBinding,
            policy = spec.policy,
            budget = spec.budget,
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
                    taskDisplay = AgentDisplayStatus.QUEUED
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

internal suspend fun AgentRunOwner.pauseLocked(command: PauseAgentRun): AgentCommandResult {
        val fingerprint = fingerprintPause(command)
        replay(command.commandId, fingerprint)?.let { return it }
        val snapshot = journal.readRun(command.runId)
            ?: return rejected(AgentErrorCodes.COMMAND_NOT_FOUND, "Run '${command.runId}' not found.")
        val run = snapshot.run
        if (run.state.isTerminal) {
            return invalidState(command.commandId, "A terminal run cannot be paused.")
        }
        if (run.state == AgentRunState.PAUSING || run.state == AgentRunState.PAUSED) {
            return AgentCommandResult.Accepted(run.revision, run.runId)
        }
        if (run.revision != command.expectedRunRevision) return revisionConflict(command.commandId)
        val decision = AgentRunStateMachine.evaluate(run.state, AgentRunEvent(AgentRunEventType.PAUSE_REQUESTED))
        if (decision is AgentRunStateDecision.Rejected) {
            return invalidState(command.commandId, decision.safeMessage)
        }
        val now = clock.nowMillis()
        val pausing = run.copy(
            state = AgentRunState.PAUSING,
            reason = command.reason,
            revision = run.revision + 1,
            updatedAtMillis = now
        )
        try {
            journal.commit(
                UpdateRunTransition(
                    commandId = command.commandId,
                    run = pausing,
                    taskDisplay = AgentDisplayStatus.PAUSING,
                    expectedRevision = run.revision
                )
            )
        } catch (rejected: AgentJournalRejectedException) {
            return fromJournal(rejected)
        }
        ledger.recordCommand(
            AgentCommandRecord(command.commandId, fingerprint, pausing.revision, runId = run.runId)
        )
        val job = active?.takeIf { it.runId == run.runId }?.job
        if (job == null) {
            settlePause(run.runId)
        } else {
            scope.launch {
                // A pause never settles PAUSED over an execution owner that is still running. A
                // wedged model/network call leaves the run in PAUSING after the bounded wait.
                val joined = kotlinx.coroutines.withTimeoutOrNull(stopJoinTimeoutMillis) {
                    job.cancelAndJoin()
                    true
                } ?: false
                if (!joined) return@launch
                processor.submit(AgentCommandPriority.CONTROL) {
                    settlePause(run.runId)
                    AgentCommandResult.Accepted(run.revision, run.runId)
                }
            }
        }
        return AgentCommandResult.Accepted(pausing.revision, run.runId)
    }

internal suspend fun AgentRunOwner.cancelLocked(command: CancelAgentRun): AgentCommandResult {
        val fingerprint = fingerprintCancel(command)
        replay(command.commandId, fingerprint)?.let { return it }
        val snapshot = journal.readRun(command.runId)
            ?: return rejected(AgentErrorCodes.COMMAND_NOT_FOUND, "Run '${command.runId}' not found.")
        val run = snapshot.run
        if (run.state == AgentRunState.CANCELLED || run.state == AgentRunState.STOPPING) {
            return AgentCommandResult.Accepted(run.revision, run.runId)
        }
        if (run.state.isTerminal) {
            return invalidState(command.commandId, "A terminal run cannot be cancelled.")
        }
        if (run.revision != command.expectedRunRevision) return revisionConflict(command.commandId)
        val decision = AgentRunStateMachine.evaluate(
            run.state,
            AgentRunEvent(AgentRunEventType.CANCEL_REQUESTED)
        )
        if (decision is AgentRunStateDecision.Rejected) {
            return invalidState(command.commandId, decision.safeMessage)
        }
        val now = clock.nowMillis()
        val target = (decision as AgentRunStateDecision.Allowed).next
        if (target == AgentRunState.CANCELLED) {
            val cancelled = run.copy(
                state = AgentRunState.CANCELLED,
                reason = AgentPauseReason.NONE,
                revision = run.revision + 1,
                updatedAtMillis = now
            )
            try {
                journal.commit(
                    UpdateRunTransition(
                        command.commandId,
                        cancelled,
                        AgentDisplayStatus.CANCELLED,
                        run.revision
                    )
                )
            } catch (rejected: AgentJournalRejectedException) {
                return fromJournal(rejected)
            }
            waiting.remove(run.runId)
            ledger.recordCommand(
                AgentCommandRecord(command.commandId, fingerprint, cancelled.revision, runId = run.runId)
            )
            return AgentCommandResult.Accepted(cancelled.revision, run.runId)
        }
        val stopping = run.copy(
            state = AgentRunState.STOPPING,
            reason = AgentPauseReason.NONE,
            revision = run.revision + 1,
            updatedAtMillis = now
        )
        try {
            journal.commit(
                UpdateRunTransition(
                    command.commandId,
                    stopping,
                    AgentDisplayStatus.STOPPING,
                    run.revision
                )
            )
        } catch (rejected: AgentJournalRejectedException) {
            return fromJournal(rejected)
        }
        ledger.recordCommand(
            AgentCommandRecord(command.commandId, fingerprint, stopping.revision, runId = run.runId)
        )
        // STOPPING is not CANCELLED. The active slot and the resource fence stay with this run
        // until the real settlement source (or a late report) proves quiescence.
        val job = active?.takeIf { it.runId == run.runId }?.job
        startStopSettlement(
            runId = run.runId,
            runEpoch = run.epoch,
            job = job,
            executionReturned = job == null
        )
        return AgentCommandResult.Accepted(stopping.revision, run.runId)
    }

