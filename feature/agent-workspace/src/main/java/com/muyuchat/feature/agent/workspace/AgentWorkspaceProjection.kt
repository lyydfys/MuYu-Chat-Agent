package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelChoice
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle

/**
 * Pure projection from contract observations to immutable page state. These functions carry no
 * side effects: they are safe to run off the main thread and are covered by JVM unit tests.
 */

internal const val MAX_DRAFT_CHARS = 8_000
private const val MAX_TARGET_TITLE_CHARS = 80
internal const val ARCHIVED_TASK_LABEL = "已归档/不可运行"
private const val ARCHIVED_TASK_DETAIL = "已导入的归档任务；只读，不可运行。可导出任务包或删除任务。"

internal data class AgentWorkspaceProjectionInput(
    val selectedTaskId: String?,
    val taskFeed: AgentTaskFeed,
    val selectedTask: AgentTask?,
    val runFeed: AgentRunFeed,
    val eventFeed: AgentEventFeed,
    val modelFeed: AgentModelFeed,
    val selectedModelKey: String?,
    val networkEnabled: Boolean,
    val draft: String,
    val detailGoalDraft: String,
    val questionAnswerDraft: String,
    val ephemeral: AgentWorkspaceEphemeralState,
    val nowMillis: Long,
    val packageState: AgentPackageUiState = AgentPackageUiState(),
    val skillsState: AgentSkillsUiState = AgentSkillsUiState(),
    val mcpState: AgentMcpUiState = AgentMcpUiState(),
    val settingsState: AgentSettingsUiState = AgentSettingsUiState()
)

internal fun modelChoiceKey(choice: AgentModelChoice): String =
    choice.providerId + "\u001F" + choice.modelId + "\u001F" + choice.assetFingerprint

/**
 * The catalog contract carries providerId only. The app adapter prefixes configured cloud records
 * with "cloud:", while every on-device engine provider keeps its own local id; grouping must not
 * guess from display text or availability.
 */
internal const val CLOUD_MODEL_PROVIDER_PREFIX = "cloud:"

internal fun isLocalModelProvider(providerId: String): Boolean =
    !providerId.startsWith(CLOUD_MODEL_PROVIDER_PREFIX)

/**
 * Default selection prefers a genuinely loaded model: loaded offline-capable protocol models first,
 * then any loaded protocol model, then the previous online fallbacks. Installed-but-unloaded entries
 * are never preferred over a runnable one; with only unloaded entries the first one is still selected
 * so the picker/page can explain the "未加载" state instead of pretending the catalog is empty.
 */
internal fun defaultModelChoice(choices: List<AgentModelChoice>): AgentModelChoice? =
    choices.firstOrNull {
        it.loadState == AgentModelLoadState.LOADED &&
            it.protocolMode != AgentProtocolMode.NONE &&
            it.availableOffline
    }
        ?: choices.firstOrNull {
            it.loadState == AgentModelLoadState.LOADED && it.protocolMode != AgentProtocolMode.NONE
        }
        ?: choices.firstOrNull { it.protocolMode != AgentProtocolMode.NONE && it.availableOffline }
        ?: choices.firstOrNull { it.protocolMode != AgentProtocolMode.NONE }
        ?: choices.firstOrNull { it.loadState == AgentModelLoadState.LOADED }
        ?: choices.firstOrNull()

internal fun resolveSelectedModel(
    choices: List<AgentModelChoice>,
    selectedKey: String?
): AgentModelChoice? {
    if (selectedKey != null) {
        choices.firstOrNull { modelChoiceKey(it) == selectedKey }?.let { return it }
    }
    return defaultModelChoice(choices)
}

internal fun projectModelOption(
    choice: AgentModelChoice,
    networkEnabled: Boolean
): AgentModelOption = AgentModelOption(
    key = modelChoiceKey(choice),
    providerId = choice.providerId,
    modelId = choice.modelId,
    displayName = choice.displayName,
    runtimeLabel = choice.runtime,
    protocolMode = choice.protocolMode,
    protocolLabel = protocolLabel(choice.protocolMode),
    offlineAvailable = choice.availableOffline,
    offlineLabel = if (!networkEnabled && !choice.availableOffline) {
        "需要联网 · 当前离线"
    } else {
        offlineLabel(choice.availableOffline)
    },
    loadState = choice.loadState,
    isLocal = isLocalModelProvider(choice.providerId),
    selectable = choice.protocolMode != AgentProtocolMode.NONE
)

internal fun isActiveDisplayStatus(status: AgentDisplayStatus): Boolean = when (status) {
    AgentDisplayStatus.QUEUED,
    AgentDisplayStatus.RUNNING,
    AgentDisplayStatus.WAITING_RESOURCE,
    AgentDisplayStatus.WAITING_APPROVAL,
    AgentDisplayStatus.WAITING_USER,
    AgentDisplayStatus.PAUSING,
    AgentDisplayStatus.STOPPING -> true

    AgentDisplayStatus.IDLE,
    AgentDisplayStatus.PAUSED,
    AgentDisplayStatus.NEEDS_REVIEW,
    AgentDisplayStatus.SUCCEEDED,
    AgentDisplayStatus.FAILED,
    AgentDisplayStatus.CANCELLED,
    AgentDisplayStatus.INTERRUPTED -> false
}

internal fun statusFromDisplayStatus(status: AgentDisplayStatus): AgentWorkspaceStatus = when (status) {
    AgentDisplayStatus.IDLE -> AgentWorkspaceStatus.IDLE
    AgentDisplayStatus.QUEUED -> AgentWorkspaceStatus.QUEUED
    AgentDisplayStatus.RUNNING -> AgentWorkspaceStatus.GENERATING
    AgentDisplayStatus.WAITING_RESOURCE -> AgentWorkspaceStatus.WAITING_RESOURCE
    AgentDisplayStatus.WAITING_APPROVAL -> AgentWorkspaceStatus.WAITING_APPROVAL
    AgentDisplayStatus.WAITING_USER -> AgentWorkspaceStatus.WAITING_USER
    AgentDisplayStatus.PAUSING -> AgentWorkspaceStatus.PAUSING
    AgentDisplayStatus.STOPPING -> AgentWorkspaceStatus.STOPPING
    AgentDisplayStatus.PAUSED -> AgentWorkspaceStatus.PAUSED
    AgentDisplayStatus.NEEDS_REVIEW -> AgentWorkspaceStatus.NEEDS_REVIEW
    AgentDisplayStatus.SUCCEEDED -> AgentWorkspaceStatus.SUCCEEDED
    AgentDisplayStatus.FAILED -> AgentWorkspaceStatus.FAILED
    AgentDisplayStatus.CANCELLED -> AgentWorkspaceStatus.CANCELLED
    AgentDisplayStatus.INTERRUPTED -> AgentWorkspaceStatus.RESTORED
}

/**
 * Active tasks are pinned first, then both groups are ordered by the last update. The ordering is
 * presentation-only; the task query remains the source of membership.
 */
internal fun orderTasks(tasks: List<AgentTask>): List<AgentTask> = tasks.sortedWith(
    compareByDescending<AgentTask> { isActiveDisplayStatus(it.displayStatus) }
        .thenByDescending { it.updatedAtMillis }
        .thenByDescending { it.createdAtMillis }
)

internal fun projectTaskItem(task: AgentTask, nowMillis: Long): AgentTaskListItem {
    val status = statusFromDisplayStatus(task.displayStatus)
    val archived = task.lifecycle == AgentTaskLifecycle.IMPORTED_ARCHIVED
    return AgentTaskListItem(
        taskId = task.taskId,
        goal = task.goal,
        status = status,
        statusLabel = if (archived) ARCHIVED_TASK_LABEL else statusLabel(status),
        updatedText = formatRelativeTime(nowMillis, task.updatedAtMillis),
        isActive = !archived && isActiveDisplayStatus(task.displayStatus),
        archived = archived
    )
}

/**
 * Run-state projection (architecture 7.3/7.4). Waiting/approval/denial are shown from the run and
 * its pending records; the page never infers a different owner state and never uses a synonym
 * cancel state.
 */
internal fun projectRunStatus(
    snapshot: AgentRunSnapshot?,
    events: List<AgentEvent>
): AgentWorkspaceStatus {
    val run = snapshot?.run ?: return AgentWorkspaceStatus.IDLE
    val hint = detectTimelineHint(events)
    return when (run.state) {
        AgentRunState.QUEUED -> AgentWorkspaceStatus.QUEUED
        AgentRunState.RUNNING -> when (hint) {
            AgentTimelineHint.MCP_OFFLINE -> AgentWorkspaceStatus.MCP_OFFLINE
            AgentTimelineHint.SKILL_MISSING_TOOL -> AgentWorkspaceStatus.SKILL_MISSING_TOOL
            AgentTimelineHint.NONE -> AgentWorkspaceStatus.GENERATING
        }

        AgentRunState.WAITING_RESOURCE -> AgentWorkspaceStatus.WAITING_RESOURCE
        AgentRunState.WAITING_APPROVAL -> when (snapshot?.pendingApproval?.status) {
            AgentApprovalStatus.DENIED,
            AgentApprovalStatus.EXPIRED,
            AgentApprovalStatus.INVALIDATED -> AgentWorkspaceStatus.DENIED

            else -> AgentWorkspaceStatus.WAITING_APPROVAL
        }

        AgentRunState.WAITING_USER -> AgentWorkspaceStatus.WAITING_USER
        AgentRunState.PAUSING -> AgentWorkspaceStatus.PAUSING
        AgentRunState.STOPPING -> AgentWorkspaceStatus.STOPPING
        AgentRunState.PAUSED -> when (run.reason) {
            AgentPauseReason.APPROVAL_EXPIRED,
            AgentPauseReason.APPROVAL_STALE,
            AgentPauseReason.PERMISSION_DENIED_LOOP -> AgentWorkspaceStatus.DENIED

            AgentPauseReason.BUDGET_LIMIT,
            AgentPauseReason.CONTEXT_LIMIT -> AgentWorkspaceStatus.BUDGET_LIMIT

            AgentPauseReason.MODEL_PROTOCOL_ERROR -> AgentWorkspaceStatus.PROTOCOL_UNSUPPORTED
            AgentPauseReason.INTERRUPTED -> AgentWorkspaceStatus.RESTORED
            AgentPauseReason.NONE,
            AgentPauseReason.RESOURCE_BUSY,
            AgentPauseReason.MODEL_BINDING_CHANGED,
            AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE,
            AgentPauseReason.USER_REQUESTED,
            AgentPauseReason.UNKNOWN -> AgentWorkspaceStatus.PAUSED
        }

        AgentRunState.NEEDS_REVIEW -> AgentWorkspaceStatus.NEEDS_REVIEW
        AgentRunState.SUCCEEDED -> AgentWorkspaceStatus.SUCCEEDED
        AgentRunState.FAILED -> AgentWorkspaceStatus.FAILED
        AgentRunState.CANCELLED -> AgentWorkspaceStatus.CANCELLED
        AgentRunState.SUPERSEDED -> AgentWorkspaceStatus.PAUSED
        AgentRunState.INTERRUPTED -> AgentWorkspaceStatus.RESTORED
    }
}

internal fun describeRunStatus(
    snapshot: AgentRunSnapshot?,
    status: AgentWorkspaceStatus,
    events: List<AgentEvent> = emptyList()
): String? {
    val run = snapshot?.run
    val approval = snapshot?.pendingApproval
    return when (status) {
        AgentWorkspaceStatus.WAITING_RESOURCE -> "模型资源正被其他任务使用；最多等待 2 分钟。"
        AgentWorkspaceStatus.WAITING_APPROVAL -> "有工具调用等待你的授权；授权 5 分钟内有效。"
        AgentWorkspaceStatus.WAITING_USER -> "模型需要你的回答后才能继续。"
        AgentWorkspaceStatus.STOPPING -> "正在停止执行并确认模型资源已释放。"
        AgentWorkspaceStatus.PAUSING -> "正在暂停当前执行。"
        AgentWorkspaceStatus.PAUSED -> when (run?.reason) {
            AgentPauseReason.MODEL_BINDING_CHANGED ->
                "运行绑定已变化；继续会在同一任务中新建一次运行。"

            AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE ->
                "应用当前不允许后台保活，任务已暂停。"

            AgentPauseReason.USER_REQUESTED -> "任务已暂停，可继续。"
            AgentPauseReason.RESOURCE_BUSY -> "等待模型资源超时，任务已暂停。"
            else -> "任务已暂停，可继续。"
        }

        AgentWorkspaceStatus.DENIED -> when {
            run?.reason == AgentPauseReason.APPROVAL_EXPIRED ->
                "授权已过期（5 分钟有效），可继续后重新审批。"

            run?.reason == AgentPauseReason.APPROVAL_STALE ->
                "审批范围已变化，旧授权已失效。"

            run?.reason == AgentPauseReason.PERMISSION_DENIED_LOOP ->
                "连续拒绝工具调用，任务已暂停。"

            approval?.status == AgentApprovalStatus.DENIED -> "该工具调用已被拒绝。"
            approval?.status == AgentApprovalStatus.EXPIRED -> "授权已过期。"
            else -> "工具调用被拒绝。"
        }

        AgentWorkspaceStatus.BUDGET_LIMIT -> when {
            run?.reason == AgentPauseReason.CONTEXT_LIMIT ->
                "上下文接近模型上限；继续时可调整目标或输入。"

            // A protocol-repair loop charges the run budget; saying only "budget used" hides the
            // real cause and sends the user looking for a tool/budget setting that is not at fault.
            events.any { it.type == com.muyuchat.core.agent.contract.AgentEventTypes.PROTOCOL_ERROR } ->
                "模型连续输出非法协议（JSON），重试预算已用尽；请更换模型或重试。"

            else -> {
                val spent = run?.spentBudget
                val budget = run?.budget
                "本次运行预算已用尽：模型 ${spent?.modelAttempts ?: 0}/${budget?.modelAttempts ?: 0} 次，" +
                    "工具 ${spent?.toolInvocations ?: 0}/${budget?.toolInvocations ?: 0} 次。"
            }
        }

        AgentWorkspaceStatus.PROTOCOL_UNSUPPORTED ->
            "当前模型无法按 Agent 协议返回合法控制指令，任务已暂停。"

        AgentWorkspaceStatus.MCP_OFFLINE -> "MCP 服务器连接已断开；本次工具调用没有执行。"
        AgentWorkspaceStatus.SKILL_MISSING_TOOL -> "所选 Skill 声明的工具当前不可用。"
        AgentWorkspaceStatus.RESTORED -> "上次运行因进程中断而停止；已保留记录，不会自动继续。"
        AgentWorkspaceStatus.NEEDS_REVIEW -> "有工具副作用无法确认；继续前需要人工核对产物与工作区。"
        AgentWorkspaceStatus.CANCELLED -> "任务已取消；已经提交的产物仍然保留。"
        AgentWorkspaceStatus.FAILED -> "运行失败；可查看执行活动中的错误摘要后继续。"
        AgentWorkspaceStatus.IDLE -> "任务已创建但尚未开始。"
        else -> null
    }
}

internal fun projectRunLabel(run: AgentRun): String {
    val spent = run.spentBudget
    val budget = run.budget
    val builder = StringBuilder()
    builder.append("第 ").append(run.runOrdinal).append(" 次运行")
    builder.append(" · 模型 ").append(spent.modelAttempts).append('/').append(budget.modelAttempts)
    builder.append(" · 工具 ").append(spent.toolInvocations).append('/').append(budget.toolInvocations)
    if (spent.runActiveTimeMillis > 0L) {
        builder.append(" · ").append(formatDurationShort(spent.runActiveTimeMillis))
    }
    return builder.toString()
}

internal fun projectBindingLabel(binding: AgentModelBinding): String =
    binding.modelDisplayName + " · " + protocolLabel(binding.protocolMode)

internal fun projectArtifacts(artifacts: List<AgentArtifact>): List<AgentArtifactUi> =
    artifacts.sortedByDescending { it.createdAtMillis }.map { artifact ->
        val committed = artifact.commitState == AgentArtifactCommitState.COMMITTED
        AgentArtifactUi(
            artifactId = artifact.artifactId,
            relativePath = artifact.relativePath,
            mediaType = artifact.mediaType,
            sizeText = formatBytes(artifact.sizeBytes),
            sha256Short = formatShortHash(artifact.sha256),
            committed = committed,
            statusText = when (artifact.commitState) {
                AgentArtifactCommitState.COMMITTED -> "已提交"
                AgentArtifactCommitState.PREPARING -> "写入中"
                AgentArtifactCommitState.FAILED -> "写入失败"
                AgentArtifactCommitState.UNKNOWN -> "状态未知"
            },
            createdText = formatClockTime(artifact.createdAtMillis)
        )
    }

internal fun projectWorkspaceState(input: AgentWorkspaceProjectionInput): AgentWorkspaceUiState {
    val selectedChoice = resolveSelectedModel(input.modelFeed.choices, input.selectedModelKey)
    val projectedOptions = input.modelFeed.choices.map {
        projectModelOption(it, input.networkEnabled)
    }
    val selectedOption = selectedChoice?.let { projectModelOption(it, input.networkEnabled) }
    val protocolSupported = selectedChoice == null ||
        selectedChoice.protocolMode != AgentProtocolMode.NONE
    val offlineCompatible = selectedChoice == null ||
        input.networkEnabled || selectedChoice.availableOffline
    val loadStateCompatible = selectedChoice == null ||
        selectedChoice.loadState == AgentModelLoadState.LOADED
    val blockedStatus = when {
        input.modelFeed.loaded && input.modelFeed.choices.isEmpty() -> AgentWorkspaceStatus.NO_MODEL
        selectedChoice != null && !protocolSupported -> AgentWorkspaceStatus.PROTOCOL_UNSUPPORTED
        selectedChoice != null && !loadStateCompatible -> AgentWorkspaceStatus.MODEL_UNLOADED
        selectedChoice != null && !offlineCompatible -> AgentWorkspaceStatus.NO_MODEL
        else -> null
    }
    val models = AgentModelPickerUiState(
        loaded = input.modelFeed.loaded,
        options = projectedOptions,
        localOptions = projectedOptions.filter { it.isLocal },
        cloudOptions = projectedOptions.filterNot { it.isLocal },
        selectedKey = selectedChoice?.let(::modelChoiceKey),
        selected = selectedOption,
        networkEnabled = input.networkEnabled,
        offlineCompatible = offlineCompatible,
        protocolSupported = protocolSupported,
        loadState = selectedChoice?.loadState,
        selectionReason = selectedChoice?.let {
            modelSelectionReason(
                protocolMode = it.protocolMode,
                loadState = it.loadState,
                availableOffline = it.availableOffline,
                networkEnabled = input.networkEnabled
            )
        },
        canCreate = selectedChoice != null &&
            loadStateCompatible &&
            protocolSupported &&
            offlineCompatible,
        blockedStatus = blockedStatus,
        errorMessage = input.modelFeed.errorMessage
    )

    val visibleTasks = if (input.taskFeed.includeArchived) {
        input.taskFeed.tasks
    } else {
        input.taskFeed.tasks.filterNot { it.lifecycle == AgentTaskLifecycle.IMPORTED_ARCHIVED }
    }
    val orderedTasks = orderTasks(visibleTasks)
    val homeItems = orderedTasks.map { projectTaskItem(it, input.nowMillis) }
    val homeLoading = input.taskFeed.loading && homeItems.isEmpty()
    val homeEmpty = !homeLoading && homeItems.isEmpty()
    val home = AgentHomeUiState(
        status = when {
            homeLoading -> AgentWorkspaceStatus.LOADING
            homeEmpty -> AgentWorkspaceStatus.EMPTY
            else -> AgentWorkspaceStatus.IDLE
        },
        tasks = homeItems,
        activeTasks = homeItems.filter { it.isActive },
        recentTasks = homeItems.filterNot { it.isActive },
        loading = homeLoading,
        loadingMore = input.taskFeed.loadingMore,
        endReached = input.taskFeed.endReached,
        empty = homeEmpty,
        errorMessage = input.taskFeed.errorMessage,
        includeArchived = input.taskFeed.includeArchived
    )

    val detail = if (input.selectedTaskId == null) null else projectDetail(input, models)
    val approval = detail?.approval
    val approvalSheetVisible = approval?.pending == true &&
        input.ephemeral.dismissedApprovalId != approval.approvalId
    val openArtifact = input.ephemeral.openArtifactId?.let { artifactId ->
        detail?.artifacts?.firstOrNull { it.artifactId == artifactId && it.committed }
    }

    return AgentWorkspaceUiState(
        surface = when {
            input.mcpState.visible -> AgentWorkspaceSurface.MCP
            input.skillsState.visible -> AgentWorkspaceSurface.SKILLS
            input.settingsState.toolPackages.visible -> AgentWorkspaceSurface.TOOL_PACKAGES
            input.settingsState.memory.visible -> AgentWorkspaceSurface.MEMORY
            input.settingsState.workflows.visible -> AgentWorkspaceSurface.WORKFLOWS
            input.selectedTaskId == null -> AgentWorkspaceSurface.HOME
            else -> AgentWorkspaceSurface.DETAIL
        },
        home = home,
        detail = detail,
        draft = input.draft.take(MAX_DRAFT_CHARS),
        detailGoalDraft = input.detailGoalDraft.take(MAX_DRAFT_CHARS),
        questionAnswerDraft = input.questionAnswerDraft,
        models = models,
        modelPickerOpen = input.ephemeral.modelPickerOpen,
        approvalSheetVisible = approvalSheetVisible,
        openArtifact = openArtifact,
        packageState = input.packageState,
        skills = input.skillsState,
        mcp = input.mcpState,
        settings = input.settingsState,
        commandInFlight = input.ephemeral.commandInFlight,
        notice = input.ephemeral.notice,
        error = input.ephemeral.error
    )
}

private fun projectDetail(
    input: AgentWorkspaceProjectionInput,
    models: AgentModelPickerUiState
): AgentTaskDetailUiState {
    val selectedTaskId = requireNotNull(input.selectedTaskId)
    val snapshot = (input.runFeed as? AgentRunFeed.Loaded)?.snapshot
    val task = snapshot?.task ?: input.selectedTask
    if (task == null || task.taskId != selectedTaskId) {
        val loadError = (input.runFeed as? AgentRunFeed.Failed)?.message
        val status = if (loadError != null) AgentWorkspaceStatus.FAILED else AgentWorkspaceStatus.LOADING
        return AgentTaskDetailUiState(
            taskId = selectedTaskId,
            goal = "",
            loading = loadError == null,
            status = status,
            statusLabel = statusLabel(status),
            statusDetail = loadError,
            loadErrorMessage = loadError,
            timelineLoading = loadError == null
        )
    }

    val run = snapshot?.run
    val archived = task.lifecycle == AgentTaskLifecycle.IMPORTED_ARCHIVED
    val events = input.eventFeed.events
    val approvalRecord = snapshot?.pendingApproval
    val approval = if (archived) null else approvalRecord?.let { projectApprovalUi(it, events, input.nowMillis) }
    val question = if (archived) {
        null
    } else {
        snapshot?.activeQuestion?.takeIf {
            it.status == AgentQuestionStatus.OPEN && run?.state == AgentRunState.WAITING_USER
        }
    }
    val status = if (archived) {
        AgentWorkspaceStatus.IDLE
    } else {
        when (val feed = input.runFeed) {
            is AgentRunFeed.Failed -> AgentWorkspaceStatus.FAILED
            is AgentRunFeed.Loading -> AgentWorkspaceStatus.LOADING
            is AgentRunFeed.Empty, AgentRunFeed.NoRun -> AgentWorkspaceStatus.IDLE
            is AgentRunFeed.Loaded -> projectRunStatus(feed.snapshot, events)
        }
    }
    val loadError = (input.runFeed as? AgentRunFeed.Failed)?.message
    val active = !archived && run?.state?.holdsActiveSlot == true
    val modelReady = models.canCreate
    val commandIdle = input.ephemeral.commandInFlight == null

    val projectedArtifacts = projectArtifacts(snapshot?.artifacts.orEmpty())
    val detail = AgentTaskDetailUiState(
        taskId = task.taskId,
        goal = task.goal,
        loading = !archived && status == AgentWorkspaceStatus.LOADING,
        status = status,
        statusLabel = if (archived) ARCHIVED_TASK_LABEL else statusLabel(status),
        statusDetail = if (archived) ARCHIVED_TASK_DETAIL else {
            loadError ?: describeRunStatus(snapshot, status, events)
        },
        modelLabel = if (archived) null else {
            run?.modelBinding?.let(::projectBindingLabel) ?: models.selected?.let {
                it.displayName + " · " + it.protocolLabel
            }
        },
        runLabel = if (archived) null else run?.let(::projectRunLabel),
        runStats = if (archived) null else run?.let(::projectRunStats),
        finalAnswer = if (!archived && status == AgentWorkspaceStatus.SUCCEEDED) {
            extractFinalAnswer(events)
        } else {
            null
        },
        selfReportedCompletion = !archived &&
            status == AgentWorkspaceStatus.SUCCEEDED &&
            projectedArtifacts.isEmpty() &&
            (run?.spentBudget?.toolInvocations ?: 0) == 0,
        timeline = if (archived) emptyList() else {
            projectTimeline(events, input.ephemeral.expandedTimelineRows)
        },
        timelineLoading = !archived && input.eventFeed.loading && events.isEmpty(),
        timelineTruncated = !archived && input.eventFeed.truncated,
        timelineErrorMessage = if (archived) null else input.eventFeed.errorMessage,
        artifacts = projectedArtifacts,
        approval = approval,
        question = question?.let { AgentQuestionUi(it.questionId, it.text, it.options) },
        waitingForApproval = !archived && approval?.pending == true,
        canStart = !archived && run == null && modelReady && commandIdle &&
            !input.ephemeral.createdTaskPending,
        canStop = !archived && active && run?.state != AgentRunState.STOPPING && commandIdle,
        canContinue = !archived && run != null && !active &&
            run.state != AgentRunState.SUPERSEDED && commandIdle,
        canAnswerQuestion = !archived && question != null &&
            input.questionAnswerDraft.isNotBlank() && commandIdle,
        stopInFlight = !archived &&
            input.ephemeral.commandInFlight == AgentWorkspaceCommandKind.CANCEL_RUN,
        loadErrorMessage = if (archived) null else loadError,
        archived = archived
    )
    return detail
}

internal fun taskDisplayTitle(goal: String): String {
    val singleLine = goal.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { goal.trim() }
    return if (singleLine.length <= MAX_TARGET_TITLE_CHARS) {
        singleLine
    } else {
        singleLine.take(MAX_TARGET_TITLE_CHARS) + "…"
    }
}
