package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelCatalogPort
import com.muyuchat.core.agent.contract.AgentModelChoice
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentTaskPage
import com.muyuchat.core.agent.contract.AgentTaskQuery
import com.muyuchat.core.agent.contract.AgentWorkspaceCommands
import com.muyuchat.core.agent.contract.AgentWorkspaceQueries
import com.muyuchat.core.agent.contract.AnswerAgentQuestion
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.ContinueAgentTask
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.PauseAgentRun
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Compose-test fakes. These live only in androidTest; no sample task or run exists in production
 * source.
 */

internal const val TEST_TIME = 1_760_000_000_000L
internal const val TEST_RUN = "run-ui-1"
internal const val TEST_TASK = "task-ui-1"

internal fun uiTask(
    displayStatus: com.muyuchat.core.agent.contract.AgentDisplayStatus =
        com.muyuchat.core.agent.contract.AgentDisplayStatus.RUNNING,
    goal: String = "整理 notes.md 并写入 summary.md"
): AgentTask = AgentTask(
    taskId = TEST_TASK,
    workspaceId = "workspace-ui-1",
    goal = goal,
    lifecycle = AgentTaskLifecycle.ACTIVE,
    createdAtMillis = TEST_TIME - 600_000L,
    updatedAtMillis = TEST_TIME - 30_000L,
    displayStatus = displayStatus,
    lastRunId = TEST_RUN,
    revision = 1L
)

internal fun uiRun(state: AgentRunState = AgentRunState.RUNNING): AgentRun = AgentRun(
    runId = TEST_RUN,
    taskId = TEST_TASK,
    runOrdinal = 1L,
    state = state,
    reason = com.muyuchat.core.agent.contract.AgentPauseReason.NONE,
    epoch = 1L,
    modelBinding = AgentModelBinding(
        providerId = "local",
        modelId = "model-ui-1",
        runtime = "mnn",
        assetFingerprint = "fingerprint-ui-1",
        protocolMode = AgentProtocolMode.PROMPTED_V1,
        modelDisplayName = "Qwen3 4B"
    ),
    policy = AgentRunPolicy(),
    budget = AgentBudget(),
    spentBudget = com.muyuchat.core.agent.contract.AgentSpentBudget(),
    lastSequence = 3L,
    revision = 1L,
    createdAtMillis = TEST_TIME - 120_000L,
    updatedAtMillis = TEST_TIME - 30_000L
)

internal fun uiSnapshot(
    task: AgentTask = uiTask(),
    run: AgentRun = uiRun(),
    events: List<AgentEvent> = uiEvents(),
    approval: AgentApproval? = null,
    artifacts: List<AgentArtifact> = emptyList()
): AgentRunSnapshot = AgentRunSnapshot(
    run = run,
    task = task,
    pendingApproval = approval,
    artifacts = artifacts
)

internal fun uiEvents(): List<AgentEvent> = listOf(
    AgentEvent(
        eventId = "event-ui-1",
        runId = TEST_RUN,
        sequence = 1L,
        type = "model.attempt_started",
        createdAtMillis = TEST_TIME - 20_000L,
        summary = "开始生成下一步"
    ),
    AgentEvent(
        eventId = "event-ui-2",
        runId = TEST_RUN,
        sequence = 2L,
        type = "tool.result",
        createdAtMillis = TEST_TIME - 10_000L,
        summary = "workspace.read_text: 已读取 2.1 KB"
    )
)

internal fun uiChoice(): AgentModelChoice = AgentModelChoice(
    providerId = "local",
    modelId = "model-ui-1",
    displayName = "Qwen3 4B",
    runtime = "mnn",
    protocolMode = AgentProtocolMode.PROMPTED_V1,
    assetFingerprint = "fingerprint-ui-1",
    availableOffline = true
)

internal class FakeAgentWorkspaceQueries(
    tasks: List<AgentTask> = emptyList(),
    snapshot: AgentRunSnapshot? = null,
    events: List<AgentEvent> = emptyList()
) : AgentWorkspaceQueries {
    private val taskPages = MutableStateFlow(AgentTaskPage(tasks = tasks))
    private val snapshots = MutableStateFlow(snapshot)
    private val eventPages = MutableStateFlow(events)

    override fun observeTasks(query: AgentTaskQuery): Flow<AgentTaskPage> = taskPages

    override fun observeRun(runId: String): Flow<AgentRunSnapshot> = snapshots.filterNotNull()

    override fun observeEvents(
        runId: String,
        afterSequence: Long,
        limit: Int
    ): Flow<AgentEventPage> = eventPages.map { events ->
        val filtered = events
            .filter { it.sequence > afterSequence }
            .sortedBy { it.sequence }
            .take(limit)
        AgentEventPage(events = filtered, nextSequence = filtered.lastOrNull()?.sequence)
    }
}

internal class FakeAgentWorkspaceCommands(
    private val result: AgentCommandResult = AgentCommandResult.Accepted(appliedRevision = 0L)
) : AgentWorkspaceCommands {
    val createCommands = mutableListOf<CreateAgentTask>()
    val startCommands = mutableListOf<StartAgentRun>()
    val pauseCommands = mutableListOf<PauseAgentRun>()
    val cancelCommands = mutableListOf<CancelAgentRun>()
    val continueCommands = mutableListOf<ContinueAgentTask>()
    val answerCommands = mutableListOf<AnswerAgentQuestion>()
    val approvalCommands = mutableListOf<ResolveAgentApproval>()

    override suspend fun createTask(command: CreateAgentTask): AgentCommandResult {
        createCommands += command
        return result
    }

    override suspend fun startRun(command: StartAgentRun): AgentCommandResult {
        startCommands += command
        return result
    }

    override suspend fun requestPause(command: PauseAgentRun): AgentCommandResult {
        pauseCommands += command
        return result
    }

    override suspend fun requestCancel(command: CancelAgentRun): AgentCommandResult {
        cancelCommands += command
        return result
    }

    override suspend fun continueTask(command: ContinueAgentTask): AgentCommandResult {
        continueCommands += command
        return result
    }

    override suspend fun answerQuestion(command: AnswerAgentQuestion): AgentCommandResult {
        answerCommands += command
        return result
    }

    override suspend fun resolveApproval(command: ResolveAgentApproval): AgentCommandResult {
        approvalCommands += command
        return result
    }
}

internal class FakeAgentModelCatalog(
    private val choices: List<AgentModelChoice> = listOf(uiChoice())
) : AgentModelCatalogPort {
    override fun observeModelChoices(): Flow<List<AgentModelChoice>> = flowOf(choices)
}

internal fun approvalUi(): AgentApprovalUi = AgentApprovalUi(
    approvalId = "approval-ui-1",
    invocationId = "invocation-ui-1",
    statusLabel = "待授权",
    toolAction = "artifact.write_text · 覆盖概要",
    readTargets = listOf("inputs/notes.md"),
    writeTargets = listOf("artifacts/summary.md"),
    networkTargets = emptyList(),
    argumentsSummary = "path=artifacts/summary.md",
    argumentsTruncated = false,
    overwrite = AgentOverwriteUi(
        path = "artifacts/summary.md",
        beforeHash = "d".repeat(64),
        afterHash = "e".repeat(64)
    ),
    expiresText = "有效期至 12:00（剩余约 4 分钟）",
    expired = false,
    argsHashShort = "bbbbbbbbbbbb…",
    schemaHashShort = "cccccccccccc…",
    resourceFingerprintShort = "resource-fp",
    policyVersion = 1L,
    runEpoch = 1L,
    hasStructuredDetails = true,
    pending = true,
    toolLabel = "artifact.write_text",
    actionLabel = "覆盖概要",
    source = AgentToolSourceUi(AgentToolSourceKind.BUILT_IN, "内置工具"),
    principal = "local-user",
    approvalIdShort = "approval-ui-…",
    invocationIdShort = "invocation-…",
    actionable = true
)

internal fun detailState(
    status: AgentWorkspaceStatus = AgentWorkspaceStatus.GENERATING,
    approval: AgentApprovalUi? = null,
    waitingForApproval: Boolean = false
): AgentTaskDetailUiState = AgentTaskDetailUiState(
    taskId = TEST_TASK,
    goal = "整理 notes.md 并写入 summary.md",
    status = status,
    statusLabel = status.name,
    statusDetail = "任务正在执行。",
    modelLabel = "Qwen3 4B · 提示词协议 V1",
    timeline = emptyList(),
    artifacts = emptyList(),
    approval = approval,
    waitingForApproval = waitingForApproval,
    canStop = status == AgentWorkspaceStatus.GENERATING
)
