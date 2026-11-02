package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentArtifactCommitPhase
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentAttemptTerminal
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentModelAttempt
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentStep
import com.muyuchat.core.agent.contract.AgentStepKind
import com.muyuchat.core.agent.contract.AgentStepStatus
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus

/** Deterministic record builders for the K05/K06 JVM tests. */
object AgentTestFixtures {
    const val WORKSPACE_ID: String = "workspace-1"
    const val TASK_ID: String = "task-1"
    const val RUN_ID: String = "run-1"
    const val STEP_ID: String = "step-1"
    const val ATTEMPT_ID: String = "attempt-1"
    const val INVOCATION_ID: String = "invocation-1"
    const val APPROVAL_ID: String = "approval-1"
    const val QUESTION_ID: String = "question-1"
    const val ARTIFACT_ID: String = "artifact-1"
    const val COMMIT_ID: String = "commit-1"
    const val EVENT_ID: String = "event-1"

    fun binding(providerId: String = "local"): AgentModelBinding = AgentModelBinding(
        providerId = providerId,
        modelId = "mnn-qwen",
        runtime = "mnn",
        assetFingerprint = "asset-sha",
        protocolMode = AgentProtocolMode.PROMPTED_V1,
        modelDisplayName = "Qwen Local",
        contextWindowTokens = 8192,
        samplingJson = "{\"temperature\":0.2}",
        capabilityEvidenceVersion = "g0-1"
    )

    fun policy(version: Long = 1L): AgentRunPolicy = AgentRunPolicy(
        networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
        policyVersion = version,
        enabledToolKeys = setOf("workspace.read_text", "artifact.write_text"),
        skillIds = setOf("brief-skill"),
        offlineForced = true
    )

    fun budget(): AgentBudget = AgentBudget()

    fun spent(): AgentSpentBudget = AgentSpentBudget(
        modelAttempts = 1,
        toolInvocations = 2,
        protocolRepairs = 0,
        modelWallTimeMillis = 1_500L,
        runActiveTimeMillis = 3_000L,
        generatedTokens = 128,
        estimated = true
    )

    fun task(
        taskId: String = TASK_ID,
        workspaceId: String = WORKSPACE_ID,
        lifecycle: AgentTaskLifecycle = AgentTaskLifecycle.ACTIVE,
        displayStatus: AgentDisplayStatus = AgentDisplayStatus.RUNNING,
        revision: Long = 2L,
        lastRunId: String? = RUN_ID
    ): AgentTask = AgentTask(
        taskId = taskId,
        workspaceId = workspaceId,
        goal = "Summarize the attached brief",
        lifecycle = lifecycle,
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L,
        displayStatus = displayStatus,
        lastRunId = lastRunId,
        revision = revision
    )

    fun run(
        runId: String = RUN_ID,
        taskId: String = TASK_ID,
        runOrdinal: Long = 1L,
        state: AgentRunState = AgentRunState.RUNNING,
        reason: AgentPauseReason = AgentPauseReason.NONE,
        epoch: Long = 7L,
        lastSequence: Long = 0L,
        revision: Long = 3L
    ): AgentRun = AgentRun(
        runId = runId,
        taskId = taskId,
        runOrdinal = runOrdinal,
        state = state,
        reason = reason,
        epoch = epoch,
        modelBinding = binding(),
        policy = policy(),
        budget = budget(),
        spentBudget = spent(),
        lastSequence = lastSequence,
        revision = revision,
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L
    )

    fun step(
        stepId: String = STEP_ID,
        runId: String = RUN_ID,
        index: Int = 0,
        status: AgentStepStatus = AgentStepStatus.RUNNING
    ): AgentStep = AgentStep(
        stepId = stepId,
        runId = runId,
        index = index,
        kind = AgentStepKind.TOOL,
        status = status,
        modelAttemptId = null,
        invocationId = null,
        startedAtMillis = 1_000L,
        endedAtMillis = null
    )

    fun attempt(
        attemptId: String = ATTEMPT_ID,
        runId: String = RUN_ID,
        stepIndex: Int = 0,
        ordinal: Int = 1,
        backendRequestId: String = "agent:$RUN_ID:0:1"
    ): AgentModelAttempt = AgentModelAttempt(
        attemptId = attemptId,
        runId = runId,
        stepIndex = stepIndex,
        ordinal = ordinal,
        backendRequestId = backendRequestId,
        backendIdentityRef = "identity-ref",
        bindingFingerprint = "binding-sha",
        startedAtMillis = 1_000L,
        terminal = AgentAttemptTerminal.RUNNING
    )

    fun invocation(
        invocationId: String = INVOCATION_ID,
        runId: String = RUN_ID,
        stepId: String = STEP_ID,
        status: ToolInvocationStatus = ToolInvocationStatus.WAITING_APPROVAL,
        idempotencyKey: String = "idem-1",
        callOrdinal: Int = 0
    ): ToolInvocation = ToolInvocation(
        invocationId = invocationId,
        runId = runId,
        stepId = stepId,
        callOrdinal = callOrdinal,
        toolKey = "artifact.write_text",
        toolVersion = "1.0.0",
        schemaHash = "schema-sha",
        frozenArgumentsRef = "payloads/frozen_args/abc.1.v1.pay",
        argumentsHash = "args-sha",
        resourceFingerprint = "resource-sha",
        status = status,
        idempotencyKey = idempotencyKey,
        resultRef = null,
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L
    )

    fun approval(
        approvalId: String = APPROVAL_ID,
        status: AgentApprovalStatus = AgentApprovalStatus.PENDING,
        expiresAtMillis: Long = 301_000L
    ): AgentApproval = AgentApproval(
        approvalId = approvalId,
        invocationId = INVOCATION_ID,
        principal = "local-user",
        argumentsHash = "args-sha",
        toolSchemaHash = "schema-sha",
        resourceFingerprint = "resource-sha",
        policyVersion = 1L,
        runEpoch = 7L,
        expiresAtMillis = expiresAtMillis,
        status = status,
        createdAtMillis = 1_000L
    )

    fun question(
        status: AgentQuestionStatus = AgentQuestionStatus.OPEN,
        questionId: String = QUESTION_ID
    ): AgentQuestion = AgentQuestion(
        questionId = questionId,
        runId = RUN_ID,
        stepIndex = 3,
        text = "Which report format do you want?",
        options = listOf("Markdown", "Plain text"),
        status = status,
        askedAtMillis = 1_000L
    )

    fun event(
        sequence: Long = 1L,
        eventId: String = EVENT_ID,
        type: String = AgentEventTypes.RUN_STATE_CHANGED,
        runId: String = RUN_ID
    ): AgentEvent = AgentEvent(
        eventId = eventId,
        runId = runId,
        sequence = sequence,
        type = type,
        createdAtMillis = 1_000L + sequence,
        commandId = "command-1",
        causationId = null,
        redactedPayloadRef = null,
        summary = "state changed"
    )

    fun artifact(
        artifactId: String = ARTIFACT_ID,
        taskId: String = TASK_ID,
        invocationId: String? = INVOCATION_ID,
        relativePath: String = "artifacts/report.md",
        sha256: String = "a".repeat(64),
        commitState: AgentArtifactCommitState = AgentArtifactCommitState.PREPARING
    ): AgentArtifact = AgentArtifact(
        artifactId = artifactId,
        taskId = taskId,
        invocationId = invocationId,
        relativePath = relativePath,
        mediaType = "text/markdown",
        sizeBytes = 12L,
        sha256 = sha256,
        commitState = commitState,
        createdAtMillis = 1_000L
    )

    fun storedArtifact(
        artifact: AgentArtifact = artifact(),
        version: Long = 1L,
        isLatest: Boolean = false
    ): AgentStoredArtifact = AgentStoredArtifact(
        artifact = artifact,
        storageRef = AgentArtifactLayout.physicalStorageRef(artifact.artifactId, COMMIT_ID),
        version = version,
        isLatest = isLatest,
        commitId = COMMIT_ID
    )

    fun commit(
        phase: AgentArtifactCommitPhase = AgentArtifactCommitPhase.PREPARING,
        expectedOldHash: String? = null,
        newHash: String = "a".repeat(64)
    ): AgentArtifactCommit = AgentArtifactCommit(
        commitId = COMMIT_ID,
        invocationId = INVOCATION_ID,
        artifactId = ARTIFACT_ID,
        stagingRef = "staging/$COMMIT_ID.part",
        targetLogicalPath = "artifacts/report.md",
        expectedOldHash = expectedOldHash,
        newHash = newHash,
        newSizeBytes = 12L,
        phase = phase,
        updatedAtMillis = 1_000L
    )

    class MutableClock(private var value: Long = 1_000L) : AgentClock {
        override fun nowMillis(): Long = value

        fun advance(millis: Long = 1_000L): Long {
            value += millis
            return value
        }
    }

    class SequentialIds(private var index: Int = 0) : () -> String {
        override fun invoke(): String {
            index += 1
            return "generated-$index"
        }
    }
}
