package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifactCommitPhase
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AnswerQuestionTransition
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.ConsumeApprovalTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.SupersedeRunTransition
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.contract.UpsertApprovalTransition
import com.muyuchat.core.agent.contract.UpsertArtifactTransition
import com.muyuchat.core.agent.contract.UpsertAttemptTransition
import com.muyuchat.core.agent.contract.UpsertInvocationTransition
import com.muyuchat.core.agent.contract.UpsertStepTransition
import com.muyuchat.core.agent.contract.AskQuestionTransition
import com.muyuchat.core.agent.contract.AgentTransition

/**
 * Applies one already-fingerprinted transition inside the caller's Room transaction.
 *
 * Every method re-reads the persisted current row, enforces the revision CAS and the frozen
 * terminal/ordering guards, and writes the related task display summary in the same transaction.
 * Any failure throws [AgentJournalException]; the surrounding transaction rolls back, so no partial
 * row, sequence or command-log entry survives.
 */
internal class AgentTransitionApplier(
    private val taskDao: AgentTaskDao,
    private val workspaceDao: AgentWorkspaceDao,
    private val runDao: AgentRunDao,
    private val stepDao: AgentStepDao,
    private val attemptDao: AgentModelAttemptDao,
    private val invocationDao: AgentInvocationDao,
    private val approvalDao: AgentApprovalDao,
    private val eventDao: AgentEventDao,
    private val artifactDao: AgentArtifactDao,
    private val commitDao: AgentArtifactCommitDao,
    private val questionDao: AgentQuestionDao,
    private val clock: AgentClock
) {
    suspend fun apply(command: AgentTransition): AgentCommitResult = when (command) {
        is CreateTaskTransition -> createTask(command)
        is CreateRunTransition -> createRun(command)
        is UpdateRunTransition -> updateRun(command)
        is AppendEventsTransition -> appendEvents(command)
        is UpsertStepTransition -> upsertStep(command)
        is UpsertAttemptTransition -> upsertAttempt(command)
        is UpsertInvocationTransition -> upsertInvocation(command)
        is UpsertApprovalTransition -> upsertApproval(command)
        is ConsumeApprovalTransition -> consumeApproval(command)
        is UpsertArtifactTransition -> upsertArtifact(command)
        is AskQuestionTransition -> askQuestion(command)
        is AnswerQuestionTransition -> answerQuestion(command)
        is SupersedeRunTransition -> supersedeRun(command)
    }

    private suspend fun createTask(command: CreateTaskTransition): AgentCommitResult {
        val task = command.task
        if (taskDao.find(task.taskId) != null) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "task ${task.taskId} already exists"
            )
        }
        if (taskDao.findByWorkspace(task.workspaceId) != null) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "workspace ${task.workspaceId} already belongs to another task"
            )
        }
        val now = clock.nowMillis()
        if (workspaceDao.find(task.workspaceId) == null) {
            workspaceDao.insert(
                AgentWorkspaceEntity(
                    workspaceId = task.workspaceId,
                    displayName = "Agent workspace",
                    storageRef = task.workspaceId,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        taskDao.insert(
            AgentEntityMappers.toEntity(
                task = task,
                displayTitle = displayTitleFrom(task.goal),
                modelBindingJson = null,
                policyJson = null,
                budgetJson = null
            )
        )
        return AgentCommitResult(
            appliedRevision = task.revision,
            taskRevision = task.revision
        )
    }

    private suspend fun createRun(command: CreateRunTransition): AgentCommitResult {
        val run = command.run
        if (run.runOrdinal < 1L) {
            throw AgentJournalException(AgentDataErrorCodes.COMMAND_INVALID_STATE, "run ordinal must be >= 1")
        }
        if (run.state != AgentRunState.QUEUED) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "a newly created run must be QUEUED, was ${run.state}"
            )
        }
        val task = taskDao.find(run.taskId)
            ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "task ${run.taskId} not found")
        if (task.lifecycle != AgentTaskLifecycle.ACTIVE.name) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "task ${task.taskId} is not ACTIVE"
            )
        }
        AgentJournalRules.requireRevision(task.revision, command.taskRevision, "task")
        if (runDao.find(run.runId) != null) {
            throw AgentJournalException(AgentDataErrorCodes.COMMAND_INVALID_STATE, "run ${run.runId} already exists")
        }
        val nextOrdinal = runDao.maxOrdinal(task.taskId) + 1
        if (run.runOrdinal != nextOrdinal) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "run ordinal ${run.runOrdinal} is not the next ordinal $nextOrdinal"
            )
        }
        val now = clock.nowMillis()
        command.successorOfRunId?.let { previousId ->
            val previous = runDao.find(previousId)
                ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "previous run $previousId not found")
            if (previous.taskId != task.taskId) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "previous run $previousId does not belong to task ${task.taskId}"
                )
            }
            if (AgentEnumCodec.decodeRunState(previous.state).isTerminal) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "previous run $previousId is already terminal"
                )
            }
            val superseded = previous.copy(
                state = AgentRunState.SUPERSEDED.name,
                successorRunId = run.runId,
                revision = previous.revision + 1,
                updatedAt = now
            )
            writeRun(superseded, expectedRevision = previous.revision)
            approvalDao.invalidatePendingForRun(previousId)
        }
        runDao.insert(AgentEntityMappers.toEntity(run))
        updateTaskSummary(task.taskId, command.taskDisplay, run.runId, now)
        return AgentCommitResult(
            appliedRevision = run.revision,
            taskRevision = task.revision + 1,
            runRevision = run.revision
        )
    }

    private suspend fun updateRun(command: UpdateRunTransition): AgentCommitResult {
        val current = requireRun(command.runId, command.expectedRevision)
        val next = command.run
        if (next.taskId != current.taskId || next.runOrdinal != current.runOrdinal) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "run identity cannot change in an update"
            )
        }
        AgentJournalRules.requireTransitionAllowed(
            AgentEnumCodec.decodeRunState(current.state),
            next.state
        )
        if (next.epoch < current.epoch) {
            throw AgentJournalException(AgentDataErrorCodes.COMMAND_INVALID_STATE, "run epoch cannot decrease")
        }
        if (next.lastSequence < current.lastSequence) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "run lastSequence cannot decrease"
            )
        }
        val now = clock.nowMillis()
        val updated = AgentEntityMappers.toEntity(next).copy(
            revision = current.revision + 1,
            successorRunId = current.successorRunId,
            updatedAt = now
        )
        writeRun(updated, expectedRevision = current.revision)
        updateTaskSummary(current.taskId, command.taskDisplay, current.runId, now)
        if (next.state.isTerminal) {
            // Same transaction as the terminal run write: an OPEN question left behind by a
            // cancelled/failed/superseded run can never be answered and would project
            // "waiting for answer" after a process restart.
            questionDao.closeOpenForTerminalRun(current.runId, now)
        }
        return AgentCommitResult(
            appliedRevision = updated.revision,
            taskRevision = taskRevision(current.taskId),
            runRevision = updated.revision
        )
    }

    private suspend fun appendEvents(command: AppendEventsTransition): AgentCommitResult {
        val current = runDao.find(command.runIdValue)
            ?: throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_NOT_FOUND,
                "run ${command.runIdValue} not found"
            )
        command.expectedRevision?.let { AgentJournalRules.requireRevision(current.revision, it, "run") }
        command.events.forEach { event ->
            if (event.runId != current.runId) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "event ${event.eventId} belongs to run ${event.runId}, not ${current.runId}"
                )
            }
        }
        val sequences = AgentJournalRules.validateEventSequences(current.lastSequence, command.events)
        eventDao.insertAll(command.events.map(AgentEntityMappers::toEntity))
        val now = clock.nowMillis()
        val updated = current.copy(
            lastSequence = sequences.last(),
            revision = current.revision + 1,
            updatedAt = now
        )
        writeRun(updated, expectedRevision = current.revision)
        return AgentCommitResult(
            appliedRevision = updated.revision,
            runRevision = updated.revision,
            eventSequences = sequences
        )
    }

    private suspend fun upsertStep(command: UpsertStepTransition): AgentCommitResult {
        val current = requireRun(command.step.runId, command.expectedRevision)
        stepDao.upsert(AgentEntityMappers.toEntity(command.step))
        val now = clock.nowMillis()
        val updated = bumpRun(current, now)
        return AgentCommitResult(
            appliedRevision = updated.revision,
            runRevision = updated.revision
        )
    }

    private suspend fun upsertAttempt(command: UpsertAttemptTransition): AgentCommitResult {
        val attempt = command.attempt
        val current = requireRun(attempt.runId, command.expectedRevision)
        attemptDao.findByBackendRequestId(attempt.backendRequestId)?.let { existing ->
            if (existing.attemptId != attempt.attemptId) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "backendRequestId ${attempt.backendRequestId} is already used by ${existing.attemptId}"
                )
            }
        }
        attemptDao.find(attempt.attemptId)?.let { existing ->
            if (existing.backendRequestId != attempt.backendRequestId) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "attempt ${attempt.attemptId} cannot switch backendRequestId"
                )
            }
        }
        val stepId = stepDao.findByRunIndex(current.runId, attempt.stepIndex)?.stepId
        attemptDao.upsert(AgentEntityMappers.toEntity(attempt, stepId, stopRequestedAt = null))
        val now = clock.nowMillis()
        val updated = bumpRun(current, now)
        return AgentCommitResult(
            appliedRevision = updated.revision,
            runRevision = updated.revision
        )
    }

    private suspend fun upsertInvocation(command: UpsertInvocationTransition): AgentCommitResult {
        val invocation = command.invocation
        val current = requireRun(invocation.runId, command.expectedRevision)
        if (stepDao.find(invocation.stepId) == null) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "step ${invocation.stepId} must be persisted before its invocation"
            )
        }
        invocationDao.findByIdempotencyKey(invocation.idempotencyKey)?.let { existing ->
            if (existing.invocationId != invocation.invocationId) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "idempotencyKey is already used by ${existing.invocationId}"
                )
            }
        }
        if (invocation.status == ToolInvocationStatus.RUNNING) {
            invocationDao.findRunningForStep(invocation.stepId)?.let { existing ->
                if (existing.invocationId != invocation.invocationId) {
                    throw AgentJournalException(
                        AgentDataErrorCodes.COMMAND_INVALID_STATE,
                        "step ${invocation.stepId} already has a running tool"
                    )
                }
            }
        }
        invocationDao.upsert(AgentEntityMappers.toEntity(invocation))
        val now = clock.nowMillis()
        val updated = bumpRun(current, now)
        return AgentCommitResult(
            appliedRevision = updated.revision,
            runRevision = updated.revision
        )
    }

    private suspend fun upsertApproval(command: UpsertApprovalTransition): AgentCommitResult {
        val approval = command.approval
        val invocation = invocationDao.find(approval.invocationId)
            ?: throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_NOT_FOUND,
                "invocation ${approval.invocationId} not found"
            )
        val current = requireRun(invocation.runId, command.expectedRevision)
        requireApprovalBinding(approval, invocation, current)
        if (approval.status == AgentApprovalStatus.PENDING) {
            approvalDao.findPendingForInvocation(approval.invocationId)?.let { existing ->
                if (existing.approvalId != approval.approvalId) {
                    throw AgentJournalException(
                        AgentDataErrorCodes.COMMAND_INVALID_STATE,
                        "invocation ${approval.invocationId} already has a pending approval"
                    )
                }
            }
        }
        approvalDao.upsert(AgentEntityMappers.toEntity(approval, current.runId))
        val now = clock.nowMillis()
        val updated = bumpRun(current, now)
        return AgentCommitResult(
            appliedRevision = updated.revision,
            runRevision = updated.revision
        )
    }

    private suspend fun consumeApproval(command: ConsumeApprovalTransition): AgentCommitResult {
        val approvalEntity = approvalDao.find(command.approvalId)
            ?: throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_NOT_FOUND,
                "approval ${command.approvalId} not found"
            )
        val invocation = invocationDao.find(approvalEntity.invocationId)
            ?: throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_NOT_FOUND,
                "invocation ${approvalEntity.invocationId} not found"
            )
        val current = requireRun(invocation.runId, command.expectedRevision)
        val approval = AgentEntityMappers.toContract(approvalEntity)
        requireApprovalBinding(approval, invocation, current)
        val status = AgentEnumCodec.decodeApprovalStatus(approvalEntity.status)
        if (status != AgentApprovalStatus.PENDING && status != AgentApprovalStatus.APPROVED) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "approval ${command.approvalId} is not consumable (status $status)"
            )
        }
        if (command.consumedAtMillis < approvalEntity.createdAt ||
            command.consumedAtMillis > approvalEntity.expiresAt
        ) {
            throw AgentJournalException(
                AgentDataErrorCodes.PERMISSION_EXPIRED,
                "approval ${command.approvalId} has expired"
            )
        }
        val allowed = command.decision == com.muyuchat.core.agent.contract.AgentApprovalDecision.ALLOW_ONCE
        val consumed = approvalDao.consumeCas(
            approvalId = command.approvalId,
            status = if (allowed) {
                AgentApprovalStatus.CONSUMED.encode()
            } else {
                AgentApprovalStatus.DENIED.encode()
            },
            decision = command.decision.name,
            consumedAt = command.consumedAtMillis
        )
        if (consumed != 1) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "approval ${command.approvalId} could not be consumed atomically"
            )
        }
        val invocationUpdated = invocationDao.updateStatusCas(
            invocationId = invocation.invocationId,
            status = if (allowed) {
                ToolInvocationStatus.RUNNING.encode()
            } else {
                ToolInvocationStatus.DENIED.encode()
            },
            resultRef = null,
            possibleSideEffects = 0,
            runningStepKey = if (allowed) invocation.stepId else null,
            updatedAt = command.consumedAtMillis,
            expectedStatuses = listOf(
                ToolInvocationStatus.WAITING_APPROVAL.encode(),
                ToolInvocationStatus.APPROVED.encode()
            )
        )
        if (invocationUpdated != 1) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "invocation ${invocation.invocationId} is not executable after approval consumption"
            )
        }
        val now = clock.nowMillis()
        val updatedRun: AgentRunEntity
        if (command.nextRun != null) {
            val next = requireNotNull(command.nextRun)
            if (next.runId != current.runId) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "approval consumption cannot move to another run"
                )
            }
            AgentJournalRules.requireTransitionAllowed(
                AgentEnumCodec.decodeRunState(current.state),
                next.state
            )
            val entity = AgentEntityMappers.toEntity(next).copy(
                revision = current.revision + 1,
                successorRunId = current.successorRunId,
                updatedAt = now
            )
            writeRun(entity, expectedRevision = current.revision)
            updatedRun = entity
            command.taskDisplay?.let { updateTaskSummary(current.taskId, it, current.runId, now) }
        } else {
            updatedRun = bumpRun(current, now)
        }
        return AgentCommitResult(
            appliedRevision = updatedRun.revision,
            taskRevision = taskRevisionOrNull(command.taskDisplay?.let { current.taskId }),
            runRevision = updatedRun.revision
        )
    }

    private suspend fun upsertArtifact(command: UpsertArtifactTransition): AgentCommitResult {
        val artifact = command.artifact
        val task = taskDao.find(artifact.taskId)
            ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "task ${artifact.taskId} not found")
        val currentRun: AgentRunEntity?
        val now = clock.nowMillis()
        val invocationId = artifact.invocationId
        if (invocationId != null) {
            val invocation = invocationDao.find(invocationId)
                ?: throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_NOT_FOUND,
                    "invocation $invocationId not found"
                )
            currentRun = requireRun(invocation.runId, command.expectedRevision)
        } else {
            AgentJournalRules.requireRevision(task.revision, command.expectedRevision, "task")
            currentRun = null
        }
        command.commit?.let { commit ->
            if (commit.artifactId != artifact.artifactId ||
                commit.invocationId != artifact.invocationId ||
                commit.targetLogicalPath != artifact.relativePath ||
                commit.newHash != artifact.sha256 ||
                commit.newSizeBytes != artifact.sizeBytes
            ) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "artifact commit does not describe artifact ${artifact.artifactId}"
                )
            }
        }
        val existing = artifactDao.find(artifact.artifactId)
        val version = existing?.version
            ?: (artifactDao.maxVersion(artifact.taskId, artifact.relativePath) + 1)
        val commitId = command.commit?.commitId
        val storageRef = existing?.storageRef
            ?: AgentArtifactLayout.physicalStorageRef(artifact.artifactId, commitId ?: artifact.artifactId)
        val isLatest = artifact.commitState == AgentArtifactCommitState.COMMITTED
        if (isLatest) {
            artifactDao.clearLatest(artifact.taskId, artifact.relativePath, artifact.artifactId)
        }
        artifactDao.upsert(
            AgentEntityMappers.toEntity(
                AgentStoredArtifact(
                    artifact = artifact,
                    storageRef = storageRef,
                    version = version,
                    isLatest = isLatest,
                    commitId = commitId
                )
            ).copy(
                createdAt = existing?.createdAt ?: now,
                commitId = commitId,
                isLatest = if (isLatest) 1 else 0,
                latestKey = if (isLatest) artifact.relativePath else null
            )
        )
        command.commit?.let { commit ->
            val existingCommit = commitDao.find(commit.commitId)
            commitDao.upsert(
                AgentEntityMappers.toEntity(
                    commit = commit,
                    createdAtMillis = existingCommit?.createdAt ?: now
                )
            )
        }
        val taskRevision: Long?
        val runRevision: Long?
        if (currentRun != null) {
            val updated = bumpRun(currentRun, now)
            taskRevision = taskRevisionOrNull(currentRun.taskId)
            runRevision = updated.revision
        } else {
            updateTaskSummary(artifact.taskId, taskDisplayFrom(task), task.lastRunId, now)
            taskRevision = taskRevision(task.taskId)
            runRevision = null
        }
        return AgentCommitResult(
            appliedRevision = runRevision ?: taskRevision ?: 0L,
            taskRevision = taskRevision,
            runRevision = runRevision
        )
    }

    private suspend fun askQuestion(command: AskQuestionTransition): AgentCommitResult {
        val question = command.question
        val next = command.nextRun
        val current = requireRun(next.runId, command.expectedRevision)
        if (next.state != AgentRunState.WAITING_USER) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "askQuestion must move the run to WAITING_USER"
            )
        }
        AgentJournalRules.requireTransitionAllowed(AgentEnumCodec.decodeRunState(current.state), next.state)
        if (question.runId != current.runId || question.status != AgentQuestionStatus.OPEN) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "question must be OPEN and belong to run ${current.runId}"
            )
        }
        if (questionDao.findOpenForRun(current.runId) != null) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "run ${current.runId} already has an open question"
            )
        }
        val now = clock.nowMillis()
        questionDao.insert(AgentEntityMappers.toEntity(question))
        val entity = AgentEntityMappers.toEntity(next).copy(
            revision = current.revision + 1,
            successorRunId = current.successorRunId,
            updatedAt = now
        )
        writeRun(entity, expectedRevision = current.revision)
        updateTaskSummary(current.taskId, command.taskDisplay, current.runId, now)
        return AgentCommitResult(
            appliedRevision = entity.revision,
            taskRevision = taskRevision(current.taskId),
            runRevision = entity.revision
        )
    }

    private suspend fun answerQuestion(command: AnswerQuestionTransition): AgentCommitResult {
        val next = command.nextRun
        val current = requireRun(next.runId, command.expectedRevision)
        if (current.state != AgentRunState.WAITING_USER.name) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "run ${current.runId} is not waiting for a user answer"
            )
        }
        AgentJournalRules.requireTransitionAllowed(AgentEnumCodec.decodeRunState(current.state), next.state)
        val answered = questionDao.answerCas(
            questionId = command.questionId,
            runId = current.runId,
            answer = command.answer,
            answeredAt = command.answeredAtMillis
        )
        if (answered != 1) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "question ${command.questionId} is not open for run ${current.runId}"
            )
        }
        val now = clock.nowMillis()
        val entity = AgentEntityMappers.toEntity(next).copy(
            revision = current.revision + 1,
            successorRunId = current.successorRunId,
            updatedAt = now
        )
        writeRun(entity, expectedRevision = current.revision)
        updateTaskSummary(current.taskId, command.taskDisplay, current.runId, now)
        return AgentCommitResult(
            appliedRevision = entity.revision,
            taskRevision = taskRevision(current.taskId),
            runRevision = entity.revision
        )
    }

    private suspend fun supersedeRun(command: SupersedeRunTransition): AgentCommitResult {
        val current = requireRun(command.previousRunId, command.expectedRevision)
        val updated = command.updatedRun
        if (updated.runId != command.previousRunId || updated.taskId != current.taskId) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "supersede must update the previous run identity"
            )
        }
        if (updated.state != AgentRunState.SUPERSEDED) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "superseded run must be SUPERSEDED"
            )
        }
        val now = clock.nowMillis()
        val entity = AgentEntityMappers.toEntity(updated).copy(
            revision = current.revision + 1,
            successorRunId = command.successorRunId,
            createdAt = current.createdAt,
            updatedAt = now
        )
        writeRun(entity, expectedRevision = current.revision)
        approvalDao.invalidatePendingForRun(current.runId)
        questionDao.closeOpenForTerminalRun(current.runId, now)
        return AgentCommitResult(
            appliedRevision = entity.revision,
            runRevision = entity.revision
        )
    }

    // --- shared guards ---------------------------------------------------------------------
    private suspend fun requireRun(runId: String, expectedRevision: Long): AgentRunEntity {
        val current = runDao.find(runId)
            ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "run $runId not found")
        AgentJournalRules.requireRevision(current.revision, expectedRevision, "run")
        return current
    }

    private suspend fun writeRun(entity: AgentRunEntity, expectedRevision: Long) {
        val rows = runDao.updateCas(
            runId = entity.runId,
            state = entity.state,
            reason = entity.reason,
            epoch = entity.epoch,
            modelBindingJson = entity.modelBindingJson,
            policyJson = entity.policyJson,
            budgetJson = entity.budgetJson,
            spentBudgetJson = entity.spentBudgetJson,
            lastSequence = entity.lastSequence,
            revision = entity.revision,
            successorRunId = entity.successorRunId,
            updatedAt = entity.updatedAt,
            expectedRevision = expectedRevision
        )
        if (rows != 1) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_REVISION_CONFLICT,
                "run ${entity.runId} revision CAS failed"
            )
        }
    }

    private suspend fun bumpRun(current: AgentRunEntity, now: Long): AgentRunEntity {
        val updated = current.copy(revision = current.revision + 1, updatedAt = now)
        writeRun(updated, expectedRevision = current.revision)
        return updated
    }

    private suspend fun updateTaskSummary(
        taskId: String,
        display: AgentDisplayStatus,
        lastRunId: String?,
        now: Long
    ) {
        val task = taskDao.find(taskId)
            ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "task $taskId not found")
        val rows = taskDao.updateSummaryCas(
            taskId = taskId,
            displayStatus = display.encode(),
            lastRunId = lastRunId,
            revision = task.revision + 1,
            updatedAt = now,
            expectedRevision = task.revision
        )
        if (rows != 1) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_REVISION_CONFLICT,
                "task $taskId revision CAS failed"
            )
        }
    }

    private suspend fun taskRevision(taskId: String): Long =
        taskDao.find(taskId)?.revision
            ?: throw AgentJournalException(AgentDataErrorCodes.COMMAND_NOT_FOUND, "task $taskId not found")

    private suspend fun taskRevisionOrNull(taskId: String?): Long? =
        taskId?.let { taskRevision(it) }

    private fun requireApprovalBinding(
        approval: com.muyuchat.core.agent.contract.AgentApproval,
        invocation: AgentInvocationEntity,
        run: AgentRunEntity
    ) {
        val runPolicyVersion = AgentRecordJson.decodePolicy(run.policyJson).policyVersion
        if (approval.invocationId != invocation.invocationId ||
            approval.argumentsHash != invocation.argumentsHash ||
            approval.toolSchemaHash != invocation.schemaHash ||
            approval.resourceFingerprint != invocation.resourceFingerprint ||
            approval.runEpoch != run.epoch ||
            approval.policyVersion != runPolicyVersion
        ) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "approval ${approval.approvalId} is not bound to the frozen invocation"
            )
        }
    }

    private fun displayTitleFrom(goal: String): String =
        goal.lineSequence().firstOrNull()?.trim()?.take(120)?.takeIf { it.isNotEmpty() } ?: "Agent task"

    private fun taskDisplayFrom(task: AgentTaskEntity): AgentDisplayStatus =
        AgentEnumCodec.decodeDisplayStatus(task.displayStatus)
}
