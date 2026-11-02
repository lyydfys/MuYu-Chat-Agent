package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
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
import com.muyuchat.core.designsystem.McaTheme
import com.muyuchat.feature.agent.workspace.AgentEventFeed
import com.muyuchat.feature.agent.workspace.AgentDeleteConfirmationUi
import com.muyuchat.feature.agent.workspace.AgentModelFeed
import com.muyuchat.feature.agent.workspace.AgentPackageOperationKind
import com.muyuchat.feature.agent.workspace.AgentPackageOperationUi
import com.muyuchat.feature.agent.workspace.AgentPackageUiState
import com.muyuchat.feature.agent.workspace.AgentRunFeed
import com.muyuchat.feature.agent.workspace.AgentTaskDetailUiState
import com.muyuchat.feature.agent.workspace.AgentTaskFeed
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions
import com.muyuchat.feature.agent.workspace.AgentWorkspaceEphemeralState
import com.muyuchat.feature.agent.workspace.AgentWorkspaceProjectionInput
import com.muyuchat.feature.agent.workspace.AgentWorkspaceStatus
import com.muyuchat.feature.agent.workspace.AgentWorkspaceUiState
import com.muyuchat.feature.agent.workspace.projectWorkspaceState
import com.muyuchat.feature.agent.workspace.statusLabel

/**
 * Fixed-state previews for every explicit Agent workspace visual state.
 *
 * Previews are compile-time documentation only. They are NOT the two-round device rendering
 * review: screenshot/TalkBack/IME/large-font evidence stays NOT_RUN until a real device pass.
 */

private const val PREVIEW_NOW = 1_760_000_000_000L

private fun previewTask(
    status: AgentDisplayStatus,
    taskId: String,
    goal: String
): AgentTask = AgentTask(
    taskId = taskId,
    workspaceId = "workspace-1",
    goal = goal,
    lifecycle = AgentTaskLifecycle.ACTIVE,
    createdAtMillis = PREVIEW_NOW - 3_600_000L,
    updatedAtMillis = PREVIEW_NOW - 60_000L,
    displayStatus = status,
    lastRunId = "run-$taskId",
    revision = 2L
)

private fun previewChoice(
    displayName: String,
    protocolMode: AgentProtocolMode,
    offline: Boolean,
    modelId: String = "model-1",
    providerId: String = "local",
    loadState: AgentModelLoadState = AgentModelLoadState.LOADED
): AgentModelChoice = AgentModelChoice(
    providerId = providerId,
    modelId = modelId,
    displayName = displayName,
    runtime = if (providerId.startsWith("cloud:")) "CLOUD_OPENAI_RESPONSES" else "mnn",
    protocolMode = protocolMode,
    assetFingerprint = "fingerprint-$modelId",
    availableOffline = offline,
    loadState = loadState
)

private fun previewBinding(protocolMode: AgentProtocolMode = AgentProtocolMode.PROMPTED_V1) =
    AgentModelBinding(
        providerId = "local",
        modelId = "model-1",
        runtime = "mnn",
        assetFingerprint = "fingerprint-model-1",
        protocolMode = protocolMode,
        modelDisplayName = "Qwen3 4B"
    )

private fun previewRun(
    state: AgentRunState,
    reason: AgentPauseReason = AgentPauseReason.NONE,
    spentBudget: AgentSpentBudget = AgentSpentBudget()
): AgentRun = AgentRun(
    runId = "run-task-1",
    taskId = "task-1",
    runOrdinal = 1L,
    state = state,
    reason = reason,
    epoch = 1L,
    modelBinding = previewBinding(),
    policy = AgentRunPolicy(),
    budget = AgentBudget(),
    spentBudget = spentBudget,
    lastSequence = 12L,
    revision = 3L,
    createdAtMillis = PREVIEW_NOW - 120_000L,
    updatedAtMillis = PREVIEW_NOW - 30_000L
)

private fun previewEvent(
    sequence: Long,
    type: String,
    summary: String?,
    causationId: String? = null
): AgentEvent = AgentEvent(
    eventId = "event-$sequence",
    runId = "run-task-1",
    sequence = sequence,
    type = type,
    createdAtMillis = PREVIEW_NOW - (20L - sequence) * 1_000L,
    causationId = causationId,
    summary = summary
)

private fun previewTimelineEvents(): List<AgentEvent> = listOf(
    previewEvent(1, AgentEventTypes.MODEL_ATTEMPT_STARTED, "开始生成下一步"),
    previewEvent(
        2,
        AgentEventTypes.TOOL_PROPOSED,
        """{"tool":"workspace.read_text","source":"builtin","state":"proposed","summary":"读取 inputs/notes.md"}""",
        causationId = "invocation-builtin"
    ),
    previewEvent(
        3,
        AgentEventTypes.TOOL_APPROVAL_REQUESTED,
        """{"tool":"workspace.read_text","source":"builtin","action":"读取任务资料","read":["inputs/notes.md"],"state":"waiting_approval"}""",
        causationId = "invocation-builtin"
    ),
    previewEvent(
        4,
        AgentEventTypes.TOOL_APPROVAL_RESOLVED,
        """{"tool":"workspace.read_text","source":"builtin","state":"approved","summary":"已允许一次"}""",
        causationId = "invocation-builtin"
    ),
    previewEvent(
        5,
        AgentEventTypes.TOOL_STARTED,
        """{"tool":"workspace.read_text","source":"builtin","state":"running","summary":"读取 inputs/notes.md"}""",
        causationId = "invocation-builtin"
    ),
    previewEvent(
        6,
        AgentEventTypes.TOOL_RESULT,
        """{"tool":"workspace.read_text","source":"builtin","state":"succeeded","summary":"已读取 2.1 KB"}""",
        causationId = "invocation-builtin"
    ),
    previewEvent(
        7,
        AgentEventTypes.TOOL_PROPOSED,
        """{"tool":"package:research-basics@1.0.0:read_brief","source":"package:research-basics@1.0.0","state":"proposed"}""",
        causationId = "invocation-package"
    ),
    previewEvent(
        8,
        AgentEventTypes.TOOL_APPROVAL_REQUESTED,
        """{"tool":"package:research-basics@1.0.0:read_brief","source":"package:research-basics@1.0.0","action":"读取任务包资源","read":["inputs/brief.txt"],"state":"waiting_approval"}""",
        causationId = "invocation-package"
    ),
    previewEvent(
        9,
        AgentEventTypes.TOOL_APPROVAL_RESOLVED,
        """{"tool":"package:research-basics@1.0.0:read_brief","source":"package:research-basics@1.0.0","state":"denied","summary":"用户拒绝"}""",
        causationId = "invocation-package"
    ),
    previewEvent(
        10,
        AgentEventTypes.TOOL_RESULT,
        """{"tool":"package:research-basics@1.0.0:read_brief","source":"package:research-basics@1.0.0","state":"denied","summary":"授权被拒绝，未执行"}""",
        causationId = "invocation-package"
    ),
    previewEvent(
        11,
        AgentEventTypes.TOOL_STARTED,
        """{"tool":"mcp:docs:search","source":"mcp:docs","state":"running","summary":"检索文档"}""",
        causationId = "invocation-mcp"
    ),
    previewEvent(
        12,
        AgentEventTypes.TOOL_RESULT,
        """{"tool":"mcp:docs:search","source":"mcp:docs","state":"cancelled","summary":"用户取消，远程执行可能未停止"}""",
        causationId = "invocation-mcp"
    ),
    previewEvent(
        13,
        AgentEventTypes.TOOL_RESULT,
        """{"tool":"skill:summarize:condense","source":"skill:summarize","state":"succeeded","summary":"已压缩为三条结论"}""",
        causationId = "invocation-skill"
    ),
    previewEvent(14, AgentEventTypes.ARTIFACT_COMMITTED, "artifacts/summary.md"),
    previewEvent(15, AgentEventTypes.RUN_TERMINAL, "已整理 notes.md 的要点并写入 summary.md。")
)

private fun previewArtifact(
    commitState: AgentArtifactCommitState = AgentArtifactCommitState.COMMITTED,
    artifactId: String = "artifact-1",
    path: String = "artifacts/summary.md"
): AgentArtifact = AgentArtifact(
    artifactId = artifactId,
    taskId = "task-1",
    invocationId = "invocation-1",
    relativePath = path,
    mediaType = "text/markdown",
    sizeBytes = 1_536L,
    sha256 = "a".repeat(64),
    commitState = commitState,
    createdAtMillis = PREVIEW_NOW - 40_000L
)

private fun previewApproval(): AgentApproval = AgentApproval(
    approvalId = "approval-1",
    invocationId = "invocation-1",
    principal = "local-user",
    argumentsHash = "b".repeat(64),
    toolSchemaHash = "c".repeat(64),
    resourceFingerprint = "resource-fp",
    policyVersion = 1L,
    runEpoch = 1L,
    expiresAtMillis = PREVIEW_NOW + 240_000L,
    status = AgentApprovalStatus.PENDING,
    createdAtMillis = PREVIEW_NOW - 20_000L
)

private fun previewApprovalEvent(): AgentEvent = previewEvent(
    3,
    AgentEventTypes.TOOL_APPROVAL_REQUESTED,
    """
    {"tool":"artifact.write_text","action":"覆盖已有概要文件","source":"package:research-basics@1.0.0","read":["inputs/notes.md"],"write":["artifacts/summary.md"],"network":[],"path":"artifacts/summary.md","args":"path=artifacts/summary.md; content=32 KiB text","principal":"local-user","oldHash":"${"d".repeat(64)}","newHash":"${"e".repeat(64)}"}
    """.trimIndent(),
    causationId = "invocation-1"
)

private fun previewQuestion(): AgentQuestion = AgentQuestion(
    questionId = "question-1",
    runId = "run-task-1",
    stepIndex = 4,
    text = "概要比原文长，需要保留完整细节还是只保留结论？",
    options = listOf("保留完整细节", "只保留结论"),
    status = AgentQuestionStatus.OPEN,
    askedAtMillis = PREVIEW_NOW - 15_000L
)

private fun previewState(
    displayStatus: AgentDisplayStatus = AgentDisplayStatus.RUNNING,
    runState: AgentRunState? = AgentRunState.RUNNING,
    reason: AgentPauseReason = AgentPauseReason.NONE,
    events: List<AgentEvent> = previewTimelineEvents(),
    artifacts: List<AgentArtifact> = listOf(previewArtifact()),
    approval: AgentApproval? = null,
    question: AgentQuestion? = null,
    spentBudget: AgentSpentBudget = AgentSpentBudget(),
    ephemeral: AgentWorkspaceEphemeralState = AgentWorkspaceEphemeralState()
): AgentWorkspaceUiState {
    val task = previewTask(displayStatus, "task-1", "整理 notes.md，输出结构化摘要")
    val run = runState?.let { previewRun(it, reason, spentBudget) }
    val snapshot = run?.let {
        AgentRunSnapshot(
            run = it,
            task = task,
            activeQuestion = question,
            pendingApproval = approval,
            artifacts = artifacts
        )
    }
    return projectWorkspaceState(
        AgentWorkspaceProjectionInput(
            selectedTaskId = "task-1",
            taskFeed = AgentTaskFeed(tasks = listOf(task), loading = false),
            selectedTask = task,
            runFeed = if (snapshot != null) AgentRunFeed.Loaded(snapshot) else AgentRunFeed.NoRun,
            eventFeed = AgentEventFeed(events = events),
            modelFeed = AgentModelFeed(choices = defaultChoices(), loaded = true),
            selectedModelKey = null,
            networkEnabled = false,
            draft = "",
            detailGoalDraft = "",
            questionAnswerDraft = "",
            ephemeral = ephemeral,
            nowMillis = PREVIEW_NOW
        )
    )
}

private fun defaultChoices(): List<AgentModelChoice> = listOf(
    previewChoice("Qwen3 4B", AgentProtocolMode.PROMPTED_V1, offline = true),
    previewChoice(
        "DeepSeek Chat",
        AgentProtocolMode.NATIVE,
        offline = false,
        modelId = "cloud-1",
        providerId = "cloud:1"
    )
)

private fun homeState(
    tasks: List<AgentTask>,
    choices: List<AgentModelChoice> = defaultChoices(),
    loaded: Boolean = true,
    networkEnabled: Boolean = false
): AgentWorkspaceUiState = projectWorkspaceState(
    AgentWorkspaceProjectionInput(
        selectedTaskId = null,
        taskFeed = AgentTaskFeed(tasks = tasks, loading = false, endReached = true),
        selectedTask = null,
        runFeed = AgentRunFeed.Empty,
        eventFeed = AgentEventFeed(),
        modelFeed = AgentModelFeed(choices = choices, loaded = loaded),
        selectedModelKey = null,
        networkEnabled = networkEnabled,
        draft = "",
        detailGoalDraft = "",
        questionAnswerDraft = "",
        ephemeral = AgentWorkspaceEphemeralState(),
        nowMillis = PREVIEW_NOW
    )
)

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    McaTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

@Preview(name = "Home empty", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewHomeEmpty() {
    PreviewFrame { AgentWorkspaceContent(state = homeState(emptyList()), actions = AgentWorkspaceActions()) }
}

@Preview(name = "Home tasks running first", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewHomeTasks() {
    val running = previewTask(
        AgentDisplayStatus.RUNNING,
        "task-running",
        "检查 inputs 下的资料并生成摘要"
    )
    val recent = previewTask(
        AgentDisplayStatus.SUCCEEDED,
        "task-recent",
        "把上次的摘要改写成三条结论"
    )
    PreviewFrame {
        AgentWorkspaceContent(state = homeState(listOf(recent, running)), actions = AgentWorkspaceActions())
    }
}

@Preview(name = "Home no model", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewHomeNoModel() {
    PreviewFrame { AgentWorkspaceContent(state = homeState(emptyList(), emptyList()), actions = AgentWorkspaceActions()) }
}

@Preview(name = "Home protocol unsupported NONE", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewHomeProtocolUnsupported() {
    val noneModel = previewChoice("无工具模型", AgentProtocolMode.NONE, offline = true)
    PreviewFrame {
        AgentWorkspaceContent(
            state = homeState(emptyList(), listOf(noneModel)),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail loading", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailLoading() {
    val state = AgentWorkspaceUiState(
        detail = AgentTaskDetailUiState(
            taskId = "task-1",
            goal = "",
            loading = true,
            status = AgentWorkspaceStatus.LOADING,
            statusLabel = statusLabel(AgentWorkspaceStatus.LOADING),
            timelineLoading = true
        ),
        models = homeState(emptyList()).models
    )
    PreviewFrame { AgentWorkspaceContent(state = state, actions = AgentWorkspaceActions()) }
}

@Preview(name = "Detail generating", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailGenerating() {
    PreviewFrame { AgentWorkspaceContent(state = previewState(), actions = AgentWorkspaceActions()) }
}

@Preview(name = "Detail running with real run stats", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailRunStats() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                spentBudget = AgentSpentBudget(
                    modelAttempts = 1,
                    toolInvocations = 1,
                    generatedTokens = 14_600,
                    modelWallTimeMillis = 63_478L
                )
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail waiting resource", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailWaitingResource() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.WAITING_RESOURCE,
                runState = AgentRunState.WAITING_RESOURCE,
                events = emptyList(),
                artifacts = emptyList()
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail waiting approval (sheet dismissed)", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailWaitingApproval() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.WAITING_APPROVAL,
                runState = AgentRunState.WAITING_APPROVAL,
                events = listOf(previewApprovalEvent()),
                approval = previewApproval(),
                ephemeral = AgentWorkspaceEphemeralState(dismissedApprovalId = "approval-1")
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Approval sheet", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewApprovalSheet() {
    val approval = previewState(
        displayStatus = AgentDisplayStatus.WAITING_APPROVAL,
        runState = AgentRunState.WAITING_APPROVAL,
        approval = previewApproval(),
        events = listOf(previewApprovalEvent())
    ).detail?.approval
    PreviewFrame {
        if (approval != null) {
            AgentApprovalSheet(
                approval = approval,
                inFlight = false,
                errorMessage = null,
                onDecision = {},
                onDismiss = {}
            )
        }
    }
}

@Preview(name = "Detail tool lifecycle states", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailToolLifecycleStates() {
    val events = listOf(
        previewEvent(
            1,
            AgentEventTypes.TOOL_PROPOSED,
            """{"tool":"workspace.read_text","source":"builtin","state":"proposed"}""",
            causationId = "invocation-a"
        ),
        previewEvent(
            2,
            AgentEventTypes.TOOL_STARTED,
            """{"tool":"workspace.read_text","source":"builtin","state":"running"}""",
            causationId = "invocation-a"
        ),
        previewEvent(
            3,
            AgentEventTypes.TOOL_RESULT,
            """{"tool":"workspace.read_text","source":"builtin","state":"succeeded","summary":"已读取 2.1 KB"}""",
            causationId = "invocation-a"
        ),
        previewEvent(
            4,
            AgentEventTypes.TOOL_RESULT,
            """{"tool":"mcp:docs:search","source":"mcp:docs","state":"denied","summary":"授权被拒绝，未执行"}""",
            causationId = "invocation-b"
        ),
        previewEvent(
            5,
            AgentEventTypes.TOOL_RESULT,
            """{"tool":"skill:summarize:condense","source":"skill:summarize","state":"cancelled","summary":"用户取消"}""",
            causationId = "invocation-c"
        ),
        previewEvent(
            6,
            AgentEventTypes.TOOL_RESULT,
            """{"tool":"custom.do_thing","state":"succeeded","summary":"完成"}""",
            causationId = "invocation-d"
        )
    )
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(events = events, artifacts = emptyList()),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Approval sheet unknown details", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewApprovalSheetUnknownDetails() {
    val approval = previewState(
        displayStatus = AgentDisplayStatus.WAITING_APPROVAL,
        runState = AgentRunState.WAITING_APPROVAL,
        events = emptyList(),
        approval = previewApproval()
    ).detail?.approval
    PreviewFrame {
        if (approval != null) {
            AgentApprovalSheet(
                approval = approval,
                inFlight = false,
                errorMessage = null,
                onDecision = {},
                onDismiss = {}
            )
        }
    }
}

@Preview(name = "Approval sheet expired", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewApprovalSheetExpired() {
    val approval = previewState(
        displayStatus = AgentDisplayStatus.WAITING_APPROVAL,
        runState = AgentRunState.WAITING_APPROVAL,
        events = listOf(previewApprovalEvent()),
        approval = previewApproval().copy(expiresAtMillis = PREVIEW_NOW - 5_000L)
    ).detail?.approval
    PreviewFrame {
        if (approval != null) {
            AgentApprovalSheet(
                approval = approval,
                inFlight = false,
                errorMessage = null,
                onDecision = {},
                onDismiss = {}
            )
        }
    }
}

@Preview(name = "Approval sheet MCP source", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewApprovalSheetMcpSource() {
    val request = previewEvent(
        3,
        AgentEventTypes.TOOL_APPROVAL_REQUESTED,
        """{"tool":"mcp:docs:search","source":"mcp:docs","action":"搜索文档","read":[],"network":["https://docs.example.com/mcp"],"principal":"local-user","state":"waiting_approval"}""",
        causationId = "invocation-1"
    )
    val approval = previewState(
        displayStatus = AgentDisplayStatus.WAITING_APPROVAL,
        runState = AgentRunState.WAITING_APPROVAL,
        events = listOf(request),
        approval = previewApproval()
    ).detail?.approval
    PreviewFrame {
        if (approval != null) {
            AgentApprovalSheet(
                approval = approval,
                inFlight = false,
                errorMessage = null,
                onDecision = {},
                onDismiss = {}
            )
        }
    }
}

@Preview(name = "Detail waiting user question", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailWaitingUser() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.WAITING_USER,
                runState = AgentRunState.WAITING_USER,
                question = previewQuestion(),
                artifacts = emptyList()
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail succeeded", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailSucceeded() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.SUCCEEDED,
                runState = AgentRunState.SUCCEEDED,
                artifacts = listOf(
                    previewArtifact(),
                    previewArtifact(
                        commitState = AgentArtifactCommitState.PREPARING,
                        artifactId = "artifact-2",
                        path = "artifacts/outline.md"
                    )
                )
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail failed", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailFailed() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.FAILED,
                runState = AgentRunState.FAILED
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail denied", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailDenied() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.PAUSED,
                runState = AgentRunState.PAUSED,
                reason = AgentPauseReason.PERMISSION_DENIED_LOOP
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail stopping", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailStopping() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.STOPPING,
                runState = AgentRunState.STOPPING
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail cancelled", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailCancelled() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.CANCELLED,
                runState = AgentRunState.CANCELLED
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail restored", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailRestored() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.INTERRUPTED,
                runState = AgentRunState.INTERRUPTED
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail budget paused", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailBudget() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.PAUSED,
                runState = AgentRunState.PAUSED,
                reason = AgentPauseReason.BUDGET_LIMIT,
                spentBudget = AgentSpentBudget(modelAttempts = 12, toolInvocations = 8)
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail MCP offline", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailMcpOffline() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                events = previewTimelineEvents() + previewEvent(
                    12,
                    AgentEventTypes.TOOL_RESULT,
                    "MCP_OFFLINE: server docs 已断线，调用未执行"
                )
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail skill missing tool", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailSkillMissingTool() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                events = previewTimelineEvents() + previewEvent(
                    12,
                    AgentEventTypes.TOOL_RESULT,
                    "SKILL_MISSING_TOOL: Skill 声明的 bash 工具当前不可用"
                )
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail needs review", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailNeedsReview() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.NEEDS_REVIEW,
                runState = AgentRunState.NEEDS_REVIEW
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Detail paused", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailPaused() {
    PreviewFrame {
        AgentWorkspaceContent(
            state = previewState(
                displayStatus = AgentDisplayStatus.PAUSED,
                runState = AgentRunState.PAUSED,
                reason = AgentPauseReason.USER_REQUESTED
            ),
            actions = AgentWorkspaceActions()
        )
    }
}

@Preview(name = "Model picker sheet", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewModelPicker() {
    val models = homeState(emptyList()).models
    PreviewFrame {
        AgentModelPickerSheet(
            models = models,
            onSelect = {},
            onNetworkEnabledChange = {},
            onLoadModel = {},
            onOpenChatToLoad = {},
            onDismiss = {}
        )
    }
}

@Preview(
    name = "Model picker local and cloud states",
    showBackground = true,
    widthDp = 390,
    heightDp = 844
)
@Composable
private fun PreviewModelPickerSections() {
    val choices = listOf(
        previewChoice(
            "Qwen3 4B",
            AgentProtocolMode.PROMPTED_V1,
            offline = true,
            modelId = "local-loaded"
        ),
        previewChoice(
            "Qwen3 8B",
            AgentProtocolMode.PROMPTED_V1,
            offline = false,
            modelId = "local-unloaded",
            loadState = AgentModelLoadState.UNLOADED
        ),
        previewChoice(
            "DeepSeek Chat",
            AgentProtocolMode.NATIVE,
            offline = false,
            modelId = "cloud-native",
            providerId = "cloud:1"
        ),
        previewChoice(
            "Unsupported Cloud",
            AgentProtocolMode.NONE,
            offline = false,
            modelId = "cloud-none",
            providerId = "cloud:2"
        )
    )
    val models = homeState(emptyList(), choices, networkEnabled = true).models
    PreviewFrame {
        AgentModelPickerSheet(
            models = models,
            onSelect = {},
            onNetworkEnabledChange = {},
            onLoadModel = {},
            onOpenChatToLoad = {},
            onDismiss = {}
        )
    }
}

@Preview(name = "Home package export in progress", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewHomePackageExporting() {
    val state = homeState(emptyList()).copy(
        packageState = AgentPackageUiState(
            operation = AgentPackageOperationUi(
                kind = AgentPackageOperationKind.EXPORT,
                status = AgentWorkspaceStatus.PACKAGE_EXPORTING,
                taskId = "task-1"
            )
        )
    )
    PreviewFrame { AgentWorkspaceContent(state = state, actions = AgentWorkspaceActions()) }
}

@Preview(name = "Home package import failed", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewHomePackageImportFailed() {
    val state = homeState(emptyList()).copy(
        packageState = AgentPackageUiState(
            operation = AgentPackageOperationUi(
                kind = AgentPackageOperationKind.IMPORT,
                status = AgentWorkspaceStatus.PACKAGE_FAILED,
                message = "任务包校验失败：manifest 与产物不匹配。"
            )
        )
    )
    PreviewFrame { AgentWorkspaceContent(state = state, actions = AgentWorkspaceActions()) }
}

@Preview(name = "Detail package deleting", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDetailPackageDeleting() {
    val state = previewState(
        displayStatus = AgentDisplayStatus.PAUSED,
        runState = AgentRunState.PAUSED
    ).copy(
        packageState = AgentPackageUiState(
            operation = AgentPackageOperationUi(
                kind = AgentPackageOperationKind.DELETE,
                status = AgentWorkspaceStatus.PACKAGE_DELETING,
                taskId = "task-1"
            )
        )
    )
    PreviewFrame { AgentWorkspaceContent(state = state, actions = AgentWorkspaceActions()) }
}

@Preview(name = "Delete task confirmation", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewDeleteTaskConfirmation() {
    val state = previewState().copy(
        packageState = AgentPackageUiState(
            deleteConfirmation = AgentDeleteConfirmationUi(
                taskId = "task-1",
                goal = "整理 notes.md，输出结构化摘要"
            )
        )
    )
    PreviewFrame { AgentWorkspaceContent(state = state, actions = AgentWorkspaceActions()) }
}
