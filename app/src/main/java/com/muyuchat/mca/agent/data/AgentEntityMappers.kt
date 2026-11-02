package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentModelAttempt
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentStep
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.ToolInvocation

/**
 * Persisted artifact row: the immutable contract artifact plus the storage/version facts that the
 * contract deliberately keeps out of the cross-module record (`storageRef`, `version`, latest flag).
 */
data class AgentStoredArtifact(
    val artifact: AgentArtifact,
    val storageRef: String,
    val version: Long,
    val isLatest: Boolean,
    val commitId: String?
)

/**
 * Pure entity ↔ contract mapping. No SQL, no coroutines, no Android types: malformed persisted
 * values raise [AgentDataIntegrityException] immediately and correctly (never a silent default).
 */
object AgentEntityMappers {

    // --- Task ------------------------------------------------------------------------------
    fun toContract(entity: AgentTaskEntity): AgentTask = AgentTask(
        taskId = entity.taskId,
        workspaceId = entity.workspaceId,
        goal = entity.goal,
        lifecycle = AgentEnumCodec.decodeTaskLifecycle(entity.lifecycle),
        createdAtMillis = entity.createdAt,
        updatedAtMillis = entity.updatedAt,
        displayStatus = AgentEnumCodec.decodeDisplayStatus(entity.displayStatus),
        lastRunId = entity.lastRunId,
        revision = entity.revision,
        schemaVersion = entity.schemaVersion
    )

    fun toEntity(
        task: AgentTask,
        displayTitle: String,
        modelBindingJson: String?,
        policyJson: String?,
        budgetJson: String?
    ): AgentTaskEntity = AgentTaskEntity(
        taskId = task.taskId,
        workspaceId = task.workspaceId,
        goal = task.goal,
        displayTitle = displayTitle,
        lifecycle = task.lifecycle.encode(),
        lastRunId = task.lastRunId,
        displayStatus = task.displayStatus.encode(),
        revision = task.revision,
        createdAt = task.createdAtMillis,
        updatedAt = task.updatedAtMillis,
        modelBindingJson = modelBindingJson,
        policyJson = policyJson,
        budgetJson = budgetJson,
        schemaVersion = task.schemaVersion
    )

    // --- Run -------------------------------------------------------------------------------
    fun toContract(entity: AgentRunEntity): AgentRun = AgentRun(
        runId = entity.runId,
        taskId = entity.taskId,
        runOrdinal = entity.runOrdinal,
        state = AgentEnumCodec.decodeRunState(entity.state),
        reason = AgentEnumCodec.decodePauseReason(entity.reason),
        epoch = entity.epoch,
        modelBinding = AgentRecordJson.decodeBinding(entity.modelBindingJson),
        policy = AgentRecordJson.decodePolicy(entity.policyJson),
        budget = AgentRecordJson.decodeBudget(entity.budgetJson),
        spentBudget = AgentRecordJson.decodeSpent(entity.spentBudgetJson),
        lastSequence = entity.lastSequence,
        revision = entity.revision,
        createdAtMillis = entity.createdAt,
        updatedAtMillis = entity.updatedAt,
        schemaVersion = entity.schemaVersion
    )

    fun toEntity(run: AgentRun): AgentRunEntity = AgentRunEntity(
        runId = run.runId,
        taskId = run.taskId,
        runOrdinal = run.runOrdinal,
        state = run.state.encode(),
        reason = run.reason.encode(),
        epoch = run.epoch,
        modelBindingJson = AgentRecordJson.encodeBinding(run.modelBinding),
        policyJson = AgentRecordJson.encodePolicy(run.policy),
        budgetJson = AgentRecordJson.encodeBudget(run.budget),
        spentBudgetJson = AgentRecordJson.encodeSpent(run.spentBudget),
        lastSequence = run.lastSequence,
        revision = run.revision,
        successorRunId = null,
        createdAt = run.createdAtMillis,
        updatedAt = run.updatedAtMillis,
        schemaVersion = run.schemaVersion
    )

    // --- Model attempt ---------------------------------------------------------------------
    fun toContract(entity: AgentModelAttemptEntity): AgentModelAttempt = AgentModelAttempt(
        attemptId = entity.attemptId,
        runId = entity.runId,
        stepIndex = entity.stepIndex,
        ordinal = entity.ordinal,
        backendRequestId = entity.backendRequestId,
        backendIdentityRef = entity.backendIdentityRef,
        bindingFingerprint = entity.bindingFingerprint,
        startedAtMillis = entity.startedAt,
        terminal = AgentEnumCodec.decodeAttemptTerminal(entity.terminal),
        finishedAtMillis = entity.finishedAt,
        usageJson = entity.usageJson,
        schemaVersion = entity.schemaVersion
    )

    fun toEntity(
        attempt: AgentModelAttempt,
        stepId: String?,
        stopRequestedAt: Long?
    ): AgentModelAttemptEntity = AgentModelAttemptEntity(
        attemptId = attempt.attemptId,
        runId = attempt.runId,
        stepId = stepId,
        stepIndex = attempt.stepIndex,
        ordinal = attempt.ordinal,
        backendRequestId = attempt.backendRequestId,
        backendIdentityRef = attempt.backendIdentityRef,
        bindingFingerprint = attempt.bindingFingerprint,
        terminal = attempt.terminal.encode(),
        usageJson = attempt.usageJson,
        stopRequestedAt = stopRequestedAt,
        startedAt = attempt.startedAtMillis,
        finishedAt = attempt.finishedAtMillis,
        schemaVersion = attempt.schemaVersion
    )

    // --- Step ------------------------------------------------------------------------------
    fun toContract(entity: AgentStepEntity): AgentStep = AgentStep(
        stepId = entity.stepId,
        runId = entity.runId,
        index = entity.stepIndex,
        kind = AgentEnumCodec.decodeStepKind(entity.kind),
        status = AgentEnumCodec.decodeStepStatus(entity.status),
        modelAttemptId = entity.modelAttemptId,
        invocationId = entity.invocationId,
        startedAtMillis = entity.startedAt,
        endedAtMillis = entity.endedAt,
        schemaVersion = entity.schemaVersion
    )

    fun toEntity(step: AgentStep): AgentStepEntity = AgentStepEntity(
        stepId = step.stepId,
        runId = step.runId,
        stepIndex = step.index,
        kind = step.kind.encode(),
        status = step.status.encode(),
        modelAttemptId = step.modelAttemptId,
        invocationId = step.invocationId,
        startedAt = step.startedAtMillis,
        endedAt = step.endedAtMillis,
        schemaVersion = step.schemaVersion
    )

    // --- Invocation ------------------------------------------------------------------------
    fun toContract(entity: AgentInvocationEntity): ToolInvocation = ToolInvocation(
        invocationId = entity.invocationId,
        runId = entity.runId,
        stepId = entity.stepId,
        callOrdinal = entity.callOrdinal,
        toolKey = entity.toolKey,
        toolVersion = entity.toolVersion,
        schemaHash = entity.schemaHash,
        frozenArgumentsRef = entity.frozenArgumentsRef,
        argumentsHash = entity.argumentsHash,
        resourceFingerprint = entity.resourceFingerprint,
        status = AgentEnumCodec.decodeInvocationStatus(entity.status),
        idempotencyKey = entity.idempotencyKey,
        resultRef = entity.resultRef,
        createdAtMillis = entity.createdAt,
        updatedAtMillis = entity.updatedAt,
        schemaVersion = entity.schemaVersion
    )

    fun toEntity(invocation: ToolInvocation): AgentInvocationEntity = AgentInvocationEntity(
        invocationId = invocation.invocationId,
        runId = invocation.runId,
        stepId = invocation.stepId,
        callOrdinal = invocation.callOrdinal,
        toolKey = invocation.toolKey,
        toolVersion = invocation.toolVersion,
        schemaHash = invocation.schemaHash,
        frozenArgumentsRef = invocation.frozenArgumentsRef,
        argumentsHash = invocation.argumentsHash,
        resourceFingerprint = invocation.resourceFingerprint,
        status = invocation.status.encode(),
        idempotencyKey = invocation.idempotencyKey,
        resultRef = invocation.resultRef,
        possibleSideEffects = 0,
        runningStepKey = if (invocation.status == com.muyuchat.core.agent.contract.ToolInvocationStatus.RUNNING) {
            invocation.stepId
        } else {
            null
        },
        createdAt = invocation.createdAtMillis,
        updatedAt = invocation.updatedAtMillis,
        schemaVersion = invocation.schemaVersion
    )

    // --- Approval --------------------------------------------------------------------------
    fun toContract(entity: AgentApprovalEntity): AgentApproval = AgentApproval(
        approvalId = entity.approvalId,
        invocationId = entity.invocationId,
        principal = entity.principal,
        argumentsHash = entity.argumentsHash,
        toolSchemaHash = entity.toolSchemaHash,
        resourceFingerprint = entity.resourceFingerprint,
        policyVersion = entity.policyVersion,
        runEpoch = entity.runEpoch,
        expiresAtMillis = entity.expiresAt,
        status = AgentEnumCodec.decodeApprovalStatus(entity.status),
        createdAtMillis = entity.createdAt,
        consumedAtMillis = entity.consumedAt,
        schemaVersion = entity.schemaVersion
    )

    fun toEntity(approval: AgentApproval, runId: String): AgentApprovalEntity = AgentApprovalEntity(
        approvalId = approval.approvalId,
        runId = runId,
        invocationId = approval.invocationId,
        principal = approval.principal,
        argumentsHash = approval.argumentsHash,
        toolSchemaHash = approval.toolSchemaHash,
        resourceFingerprint = approval.resourceFingerprint,
        policyVersion = approval.policyVersion,
        runEpoch = approval.runEpoch,
        expiresAt = approval.expiresAtMillis,
        status = approval.status.encode(),
        decision = null,
        createdAt = approval.createdAtMillis,
        consumedAt = approval.consumedAtMillis,
        pendingInvocationKey = if (approval.status == AgentApprovalStatus.PENDING) {
            approval.invocationId
        } else {
            null
        },
        schemaVersion = approval.schemaVersion
    )

    // --- Event -----------------------------------------------------------------------------
    fun toContract(entity: AgentEventEntity): AgentEvent = AgentEvent(
        eventId = entity.eventId,
        runId = entity.runId,
        sequence = entity.sequence,
        type = entity.type,
        createdAtMillis = entity.createdAt,
        commandId = entity.commandId,
        causationId = entity.causationId,
        redactedPayloadRef = entity.redactedPayloadRef,
        summary = entity.summary,
        version = entity.version,
        schemaVersion = entity.schemaVersion
    )

    fun toEntity(event: AgentEvent): AgentEventEntity = AgentEventEntity(
        eventId = event.eventId,
        runId = event.runId,
        sequence = event.sequence,
        type = event.type,
        version = event.version,
        createdAt = event.createdAtMillis,
        commandId = event.commandId,
        causationId = event.causationId,
        redactedPayloadRef = event.redactedPayloadRef,
        summary = event.summary,
        schemaVersion = event.schemaVersion
    )

    // --- Question --------------------------------------------------------------------------
    fun toContract(entity: AgentQuestionEntity): AgentQuestion = AgentQuestion(
        questionId = entity.questionId,
        runId = entity.runId,
        stepIndex = entity.stepIndex,
        text = entity.text,
        options = AgentRecordJson.decodeQuestionOptions(entity.optionsJson),
        status = AgentEnumCodec.decodeQuestionStatus(entity.status),
        askedAtMillis = entity.askedAt,
        answeredAtMillis = entity.answeredAt,
        answer = entity.answer,
        schemaVersion = entity.schemaVersion
    )

    fun toEntity(question: AgentQuestion): AgentQuestionEntity = AgentQuestionEntity(
        questionId = question.questionId,
        runId = question.runId,
        stepIndex = question.stepIndex,
        text = question.text,
        optionsJson = AgentRecordJson.encodeQuestionOptions(question.options),
        status = question.status.encode(),
        askedAt = question.askedAtMillis,
        answeredAt = question.answeredAtMillis,
        answer = question.answer,
        schemaVersion = question.schemaVersion
    )

    // --- Artifact --------------------------------------------------------------------------
    fun toStoredArtifact(entity: AgentArtifactEntity): AgentStoredArtifact = AgentStoredArtifact(
        artifact = AgentArtifact(
            artifactId = entity.artifactId,
            taskId = entity.taskId,
            invocationId = entity.invocationId,
            relativePath = entity.relativePath,
            mediaType = entity.mediaType,
            sizeBytes = entity.sizeBytes,
            sha256 = entity.sha256,
            commitState = AgentEnumCodec.decodeArtifactCommitState(entity.commitState),
            createdAtMillis = entity.createdAt,
            schemaVersion = entity.schemaVersion
        ),
        storageRef = entity.storageRef,
        version = entity.version,
        isLatest = entity.isLatest == 1,
        commitId = entity.commitId
    )

    fun toEntity(stored: AgentStoredArtifact): AgentArtifactEntity = AgentArtifactEntity(
        artifactId = stored.artifact.artifactId,
        taskId = stored.artifact.taskId,
        invocationId = stored.artifact.invocationId,
        relativePath = stored.artifact.relativePath,
        mediaType = stored.artifact.mediaType,
        sizeBytes = stored.artifact.sizeBytes,
        sha256 = stored.artifact.sha256,
        commitState = stored.artifact.commitState.encode(),
        storageRef = stored.storageRef,
        version = stored.version,
        isLatest = if (stored.isLatest) 1 else 0,
        latestKey = if (stored.isLatest) stored.artifact.relativePath else null,
        commitId = stored.commitId,
        createdAt = stored.artifact.createdAtMillis,
        schemaVersion = stored.artifact.schemaVersion
    )

    // --- Artifact commit -------------------------------------------------------------------
    fun toContract(entity: AgentArtifactCommitEntity): AgentArtifactCommit = AgentArtifactCommit(
        commitId = entity.commitId,
        invocationId = entity.invocationId,
        artifactId = entity.artifactId,
        stagingRef = entity.stagingRef,
        targetLogicalPath = entity.targetLogicalPath,
        expectedOldHash = entity.expectedOldHash,
        newHash = entity.newHash,
        newSizeBytes = entity.newSizeBytes,
        phase = AgentEnumCodec.decodeArtifactCommitPhase(entity.phase),
        updatedAtMillis = entity.updatedAt,
        schemaVersion = entity.schemaVersion
    )

    fun toEntity(commit: AgentArtifactCommit, createdAtMillis: Long): AgentArtifactCommitEntity =
        AgentArtifactCommitEntity(
            commitId = commit.commitId,
            invocationId = commit.invocationId,
            artifactId = commit.artifactId,
            stagingRef = commit.stagingRef,
            targetLogicalPath = commit.targetLogicalPath,
            expectedOldHash = commit.expectedOldHash,
            newHash = commit.newHash,
            newSizeBytes = commit.newSizeBytes,
            phase = commit.phase.encode(),
            createdAt = createdAtMillis,
            updatedAt = commit.updatedAtMillis,
            schemaVersion = commit.schemaVersion
        )
}

/** Terminal-state guard shared by the Room journal and the in-memory test journal. */
object AgentJournalRules {
    fun isTerminal(state: AgentRunState): Boolean = state.isTerminal

    /** Terminal runs may never leave their terminal value (architecture 7.3). */
    fun requireTransitionAllowed(current: AgentRunState, next: AgentRunState) {
        if (current.isTerminal && next != current) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "terminal run state $current cannot transition to $next"
            )
        }
    }

    fun requireRevision(actual: Long, expected: Long, entity: String) {
        if (actual != expected) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_REVISION_CONFLICT,
                "$entity revision conflict: expected $expected but found $actual"
            )
        }
    }

    /**
     * Sequences must be exactly contiguous after [currentLastSequence]; the journal never rewrites
     * a caller-provided sequence because that would hide a lost/duplicated event.
     */
    fun validateEventSequences(currentLastSequence: Long, events: List<AgentEvent>): List<Long> {
        if (events.isEmpty()) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "event transition must append at least one event"
            )
        }
        if (events.size > AgentDataLimits.MAX_EVENTS_PER_TRANSITION) {
            throw AgentJournalException(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "event transition appends at most ${AgentDataLimits.MAX_EVENTS_PER_TRANSITION} events"
            )
        }
        val seen = HashSet<String>(events.size)
        val sequences = ArrayList<Long>(events.size)
        events.forEachIndexed { index, event ->
            if (!seen.add(event.eventId)) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "duplicate eventId '${event.eventId}' in one transition"
                )
            }
            val expected = currentLastSequence + index + 1
            if (event.sequence != expected) {
                throw AgentJournalException(
                    AgentDataErrorCodes.COMMAND_INVALID_STATE,
                    "event sequence gap: expected $expected but received ${event.sequence}"
                )
            }
            sequences += event.sequence
        }
        return sequences
    }

    /** Idempotency decision: replay only for the exact same content. */
    enum class CommandLookup { EXECUTE, REPLAY, CONFLICT }

    fun commandLookup(existingContentHash: String?, incomingContentHash: String): CommandLookup =
        when (existingContentHash) {
            null -> CommandLookup.EXECUTE
            incomingContentHash -> CommandLookup.REPLAY
            else -> CommandLookup.CONFLICT
        }
}
