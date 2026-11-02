package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelChoice
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle

internal const val TEST_NOW = 1_760_000_000_000L
internal const val TEST_RUN_ID = "run-1"
internal const val TEST_TASK_ID = "task-1"

internal fun testBinding(
    protocolMode: AgentProtocolMode = AgentProtocolMode.PROMPTED_V1,
    displayName: String = "Qwen3 4B",
    contextWindowTokens: Int? = null
): AgentModelBinding = AgentModelBinding(
    providerId = "local",
    modelId = "model-1",
    runtime = "mnn",
    assetFingerprint = "fingerprint-1",
    protocolMode = protocolMode,
    modelDisplayName = displayName,
    contextWindowTokens = contextWindowTokens
)

internal fun testTask(
    taskId: String = TEST_TASK_ID,
    displayStatus: AgentDisplayStatus = AgentDisplayStatus.IDLE,
    goal: String = "整理 notes.md 的要点并写入 summary.md",
    updatedAtMillis: Long = TEST_NOW - 60_000L,
    lastRunId: String? = null,
    revision: Long = 1L
): AgentTask = AgentTask(
    taskId = taskId,
    workspaceId = "workspace-1",
    goal = goal,
    lifecycle = AgentTaskLifecycle.ACTIVE,
    createdAtMillis = TEST_NOW - 3_600_000L,
    updatedAtMillis = updatedAtMillis,
    displayStatus = displayStatus,
    lastRunId = lastRunId,
    revision = revision
)

internal fun testRun(
    state: AgentRunState = AgentRunState.RUNNING,
    reason: AgentPauseReason = AgentPauseReason.NONE,
    spentBudget: AgentSpentBudget = AgentSpentBudget(),
    runId: String = TEST_RUN_ID
): AgentRun = AgentRun(
    runId = runId,
    taskId = TEST_TASK_ID,
    runOrdinal = 1L,
    state = state,
    reason = reason,
    epoch = 1L,
    modelBinding = testBinding(),
    policy = AgentRunPolicy(),
    budget = AgentBudget(),
    spentBudget = spentBudget,
    lastSequence = 10L,
    revision = 2L,
    createdAtMillis = TEST_NOW - 600_000L,
    updatedAtMillis = TEST_NOW - 30_000L
)

internal fun testSnapshot(
    task: AgentTask = testTask(),
    run: AgentRun = testRun(),
    artifacts: List<AgentArtifact> = emptyList(),
    approval: AgentApproval? = null,
    question: AgentQuestion? = null
): AgentRunSnapshot = AgentRunSnapshot(
    run = run,
    task = task,
    activeQuestion = question,
    pendingApproval = approval,
    steps = emptyList(),
    artifacts = artifacts
)

internal fun testEvent(
    sequence: Long,
    type: String,
    summary: String? = null,
    eventId: String = "event-$sequence",
    causationId: String? = null,
    commandId: String? = null
): AgentEvent = AgentEvent(
    eventId = eventId,
    runId = TEST_RUN_ID,
    sequence = sequence,
    type = type,
    createdAtMillis = TEST_NOW - (100L - sequence) * 1_000L,
    commandId = commandId,
    causationId = causationId,
    summary = summary
)

internal fun testApproval(
    approvalId: String = "approval-1",
    invocationId: String = "invocation-1",
    status: AgentApprovalStatus = AgentApprovalStatus.PENDING,
    expiresInMillis: Long = 240_000L
): AgentApproval = AgentApproval(
    approvalId = approvalId,
    invocationId = invocationId,
    principal = "local-user",
    argumentsHash = "b".repeat(64),
    toolSchemaHash = "c".repeat(64),
    resourceFingerprint = "resource-fingerprint",
    policyVersion = 1L,
    runEpoch = 1L,
    expiresAtMillis = TEST_NOW + expiresInMillis,
    status = status,
    createdAtMillis = TEST_NOW - 20_000L
)

internal fun testQuestion(
    status: AgentQuestionStatus = AgentQuestionStatus.OPEN,
    options: List<String> = listOf("覆盖", "另存")
): AgentQuestion = AgentQuestion(
    questionId = "question-1",
    runId = TEST_RUN_ID,
    stepIndex = 3,
    text = "要覆盖已有文件吗？",
    options = options,
    status = status,
    askedAtMillis = TEST_NOW - 10_000L
)

internal fun testArtifact(
    artifactId: String = "artifact-1",
    path: String = "artifacts/summary.md",
    commitState: AgentArtifactCommitState = AgentArtifactCommitState.COMMITTED,
    createdAtMillis: Long = TEST_NOW - 30_000L
): AgentArtifact = AgentArtifact(
    artifactId = artifactId,
    taskId = TEST_TASK_ID,
    invocationId = "invocation-1",
    relativePath = path,
    mediaType = "text/markdown",
    sizeBytes = 1_536L,
    sha256 = "a".repeat(64),
    commitState = commitState,
    createdAtMillis = createdAtMillis
)

internal fun testChoice(
    modelId: String = "model-1",
    displayName: String = "Qwen3 4B",
    protocolMode: AgentProtocolMode = AgentProtocolMode.PROMPTED_V1,
    availableOffline: Boolean = true,
    runtime: String = "mnn",
    loadState: AgentModelLoadState = AgentModelLoadState.LOADED,
    providerId: String = "local"
): AgentModelChoice = AgentModelChoice(
    providerId = providerId,
    modelId = modelId,
    displayName = displayName,
    runtime = runtime,
    protocolMode = protocolMode,
    assetFingerprint = "fingerprint-$modelId",
    availableOffline = availableOffline,
    loadState = loadState
)

internal fun projectionInput(
    selectedTaskId: String? = null,
    tasks: List<AgentTask> = emptyList(),
    selectedTask: AgentTask? = null,
    runFeed: AgentRunFeed = AgentRunFeed.Empty,
    events: List<AgentEvent> = emptyList(),
    choices: List<AgentModelChoice> = listOf(testChoice()),
    modelsLoaded: Boolean = true,
    selectedModelKey: String? = null,
    networkEnabled: Boolean = false,
    draft: String = "",
    detailGoalDraft: String = "",
    questionAnswerDraft: String = "",
    ephemeral: AgentWorkspaceEphemeralState = AgentWorkspaceEphemeralState(),
    packageState: AgentPackageUiState = AgentPackageUiState(),
    skillsState: AgentSkillsUiState = AgentSkillsUiState(),
    mcpState: AgentMcpUiState = AgentMcpUiState(),
    settingsState: AgentSettingsUiState = AgentSettingsUiState(),
    includeArchived: Boolean = false
): AgentWorkspaceProjectionInput = AgentWorkspaceProjectionInput(
    selectedTaskId = selectedTaskId,
    taskFeed = AgentTaskFeed(
        tasks = tasks,
        loading = false,
        endReached = true,
        includeArchived = includeArchived
    ),
    selectedTask = selectedTask,
    runFeed = runFeed,
    eventFeed = AgentEventFeed(events = events),
    modelFeed = AgentModelFeed(choices = choices, loaded = modelsLoaded),
    selectedModelKey = selectedModelKey,
    networkEnabled = networkEnabled,
    draft = draft,
    detailGoalDraft = detailGoalDraft,
    questionAnswerDraft = questionAnswerDraft,
    ephemeral = ephemeral,
    nowMillis = TEST_NOW,
    packageState = packageState,
    skillsState = skillsState,
    mcpState = mcpState,
    settingsState = settingsState
)

internal fun approvalRequestEvent(summary: String): AgentEvent = testEvent(
    sequence = 2,
    type = AgentEventTypes.TOOL_APPROVAL_REQUESTED,
    summary = summary,
    causationId = "invocation-1"
)
