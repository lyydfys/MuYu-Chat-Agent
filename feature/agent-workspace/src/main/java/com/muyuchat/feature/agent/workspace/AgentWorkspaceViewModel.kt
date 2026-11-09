package com.muyuchat.feature.agent.workspace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelCatalogPort
import com.muyuchat.core.agent.contract.AgentModelChoice
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskQuery
import com.muyuchat.core.agent.contract.AgentWorkspaceCommands
import com.muyuchat.core.agent.contract.AgentWorkspaceQueries
import com.muyuchat.core.agent.contract.AnswerAgentQuestion
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.ContinueAgentTask
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Page state holder for the independent Agent workspace.
 *
 * Responsibilities: bounded observation (tasks/run/events/model catalog), projection to immutable
 * UI state, and intent -> command mapping. It never starts a run from observation, never writes a
 * global app state, and imports no Room/engine/runtime implementation.
 *
 * Saveable state: selectedTaskId, goal draft, detail goal draft, question answer draft, list
 * anchors. A process restart recreates the page from these plus fresh queries; no command is
 * replayed.
 */
class AgentWorkspaceViewModel(
    private val queries: AgentWorkspaceQueries,
    private val commands: AgentWorkspaceCommands,
    private val modelCatalog: AgentModelCatalogPort,
    private val savedStateHandle: SavedStateHandle,
    packageActions: AgentPackageActions = AgentPackageActions.Unavailable,
    documentLauncher: AgentDocumentLauncher = AgentDocumentLauncher.Unavailable,
    skillAdmin: AgentSkillAdminPort = AgentSkillAdminPort.Unavailable,
    mcpAdmin: AgentMcpAdminPort = AgentMcpAdminPort.Unavailable,
    toolPackageAdmin: AgentToolPackageAdminPort = AgentToolPackageAdminPort.Unavailable,
    memoryAdmin: AgentMemoryAdminPort = AgentMemoryAdminPort.Unavailable,
    workflowAdmin: AgentWorkflowAdminPort = AgentWorkflowAdminPort.Unavailable,
    workflowLaunch: AgentWorkflowLaunchPort = AgentWorkflowLaunchPort.Unavailable,
    workflowSchedule: AgentWorkflowSchedulePort = AgentWorkflowSchedulePort.Unavailable
) : ViewModel() {

    private val selectedTaskId = savedStateHandle.getStateFlow<String?>(KEY_SELECTED_TASK_ID, null)
    private val goalDraft = savedStateHandle.getStateFlow(KEY_GOAL_DRAFT, "")
    private val detailGoalDraft = savedStateHandle.getStateFlow(KEY_DETAIL_GOAL_DRAFT, "")
    private val questionAnswerDraft = savedStateHandle.getStateFlow(KEY_QUESTION_ANSWER_DRAFT, "")
    private val selectedModelKey = savedStateHandle.getStateFlow<String?>(KEY_SELECTED_MODEL, null)
    private val networkEnabled = savedStateHandle.getStateFlow(KEY_NETWORK_ENABLED, false)

    private val archivedFilter = AgentArchivedFilterCoordinator(savedStateHandle) { include ->
        restartTaskFeed(include)
    }

    private val taskFeed = MutableStateFlow(
        AgentTaskFeed(includeArchived = archivedFilter.includeArchived)
    )
    private val selectedTask = MutableStateFlow<AgentTask?>(null)
    private val runFeed = MutableStateFlow<AgentRunFeed>(AgentRunFeed.Empty)
    private val eventFeed = MutableStateFlow(AgentEventFeed())
    private val modelFeed = MutableStateFlow(AgentModelFeed())
    private val ephemeral = MutableStateFlow(AgentWorkspaceEphemeralState())
    private val detailRefresh = MutableStateFlow(0)

    private val taskPageJobs = mutableListOf<Job>()
    private val requestedTaskCursors = mutableSetOf<String>()

    private val packageCoordinator = AgentWorkspacePackageCoordinator(
        scope = viewModelScope,
        actions = packageActions,
        documents = documentLauncher,
        findTask = { taskId ->
            taskFeed.value.tasks.firstOrNull { it.taskId == taskId }
                ?: selectedTask.value?.takeIf { it.taskId == taskId }
        },
        onNotice = ::setNotice,
        onTasksChanged = ::retryTasks,
        onTaskDeleted = { taskId ->
            if (selectedTaskId.value == taskId) backToHome()
        }
    )

    internal val skillsBinding = AgentSkillsVmBinding(
        scope = viewModelScope,
        port = skillAdmin,
        documents = documentLauncher,
        savedStateHandle = savedStateHandle
    )

    internal val mcpBinding = AgentMcpVmBinding(
        scope = viewModelScope,
        port = mcpAdmin,
        savedStateHandle = savedStateHandle
    )

    internal val settingsBinding = AgentSettingsVmBinding(
        scope = viewModelScope,
        toolPackages = toolPackageAdmin,
        documents = documentLauncher,
        memory = memoryAdmin,
        workflows = workflowAdmin,
        workflowLaunch = workflowLaunch,
        workflowSchedule = workflowSchedule,
        modelProvider = ::currentAgentModelBinding,
        savedStateHandle = savedStateHandle
    )

    init {
        observeTaskPage(cursor = null)
        observeModelCatalog()
        observeSelectedTask()
        observeDetail()
    }

    // ---------------------------------------------------------------- observations

    private fun observeTaskPage(cursor: String?) {
        val includeArchived = archivedFilter.includeArchived
        val query = AgentTaskQuery(
            limit = TASK_PAGE_SIZE,
            cursor = cursor,
            includeArchived = includeArchived
        )
        val job = viewModelScope.launch {
            queries.observeTasks(query)
                .catch { error ->
                    if (error is CancellationException) throw error
                    if (cursor != null) requestedTaskCursors.remove(cursor)
                    taskFeed.update {
                        it.copy(
                            loading = it.tasks.isEmpty(),
                            loadingMore = false,
                            errorMessage = "任务列表暂时无法载入"
                        )
                    }
                }
                .collect { page ->
                    taskFeed.update { current ->
                        val merged = mergeTaskPage(current.tasks, page)
                        val capped = merged.size >= MAX_TASKS
                        val next = if (capped) null else page.nextCursor
                        current.copy(
                            tasks = merged,
                            loading = false,
                            loadingMore = false,
                            nextCursor = next ?: current.nextCursor,
                            endReached = current.endReached || next == null || capped,
                            errorMessage = null,
                            includeArchived = includeArchived
                        )
                    }
                }
        }
        taskPageJobs += job
    }

    private fun observeModelCatalog() {
        viewModelScope.launch {
            modelCatalog.observeModelChoices()
                .catch { error ->
                    if (error is CancellationException) throw error
                    modelFeed.value = AgentModelFeed(
                        loaded = true,
                        errorMessage = "模型目录暂时无法载入"
                    )
                }
                .collect { choices ->
                    modelFeed.value = AgentModelFeed(choices = choices, loaded = true)
                }
        }
    }

    private fun observeSelectedTask() {
        viewModelScope.launch {
            combine(selectedTaskId, taskFeed) { id, feed -> id to feed }
                .collect { (id, feed) ->
                    if (id == null) {
                        selectedTask.value = null
                        return@collect
                    }
                    feed.tasks.firstOrNull { it.taskId == id }?.let { selectedTask.value = it }
                }
        }
    }

    private fun observeDetail() {
        viewModelScope.launch {
            val keyFlow = combine(selectedTask, detailRefresh) { task, refresh ->
                DetailKey(task?.taskId, task?.lastRunId, refresh)
            }.distinctUntilChanged()
            keyFlow.collectLatest { key ->
                if (key.taskId == null) {
                    runFeed.value = AgentRunFeed.Empty
                    eventFeed.value = AgentEventFeed()
                    return@collectLatest
                }
                val runId = key.runId
                if (runId == null) {
                    runFeed.value = AgentRunFeed.NoRun
                    eventFeed.value = AgentEventFeed()
                    return@collectLatest
                }
                runFeed.value = AgentRunFeed.Loading
                eventFeed.value = AgentEventFeed(loading = true)
                coroutineScope {
                    launch { collectRun(runId) }
                    launch { collectEvents(runId) }
                }
            }
        }
    }

    private suspend fun collectRun(runId: String) {
        queries.observeRun(runId)
            .catch { error ->
                if (error is CancellationException) throw error
                runFeed.value = AgentRunFeed.Failed("运行状态暂时无法载入")
            }
            .collect { snapshot -> runFeed.value = AgentRunFeed.Loaded(snapshot) }
    }

    private suspend fun collectEvents(runId: String) {
        eventsForRun(runId)
            .catch { error ->
                if (error is CancellationException) throw error
                eventFeed.update { it.copy(loading = false, errorMessage = "执行活动暂时无法载入") }
            }
            .collect { eventFeed.value = it }
    }

    /**
     * Initial page of 100 events, then forward pages by sequence while under the page-holder cap.
     * Once the cap is reached (or the page has no next sequence) the collector stays on the tail
     * so later appends are observed without ever holding the whole run in memory.
     */
    private fun eventsForRun(runId: String): Flow<AgentEventFeed> = flow {
        var after = 0L
        var loaded: List<AgentEvent> = emptyList()
        var truncated = false
        emit(AgentEventFeed(loading = true))
        while (true) {
            val page = queries.observeEvents(runId, after, EVENT_PAGE_SIZE).first()
            val beforeSize = loaded.size
            val merged = mergeEventPages(loaded, page)
            loaded = merged.events
            truncated = truncated || merged.truncated
            emit(AgentEventFeed(events = loaded, loading = false, truncated = truncated))
            val next = nextEventPageAfter(page, after, loaded.size)
            if (next == null || page.events.isEmpty() ||
                (loaded.size == beforeSize && beforeSize > 0) ||
                loaded.size >= MAX_EVENTS
            ) {
                after = next ?: after
                break
            }
            after = next
        }
        val tailAfter = maxOf(after, loaded.lastOrNull()?.sequence ?: 0L)
        queries.observeEvents(runId, tailAfter, EVENT_PAGE_SIZE).collect { page ->
            val merged = mergeEventPages(loaded, page)
            loaded = merged.events
            truncated = truncated || merged.truncated
            emit(AgentEventFeed(events = loaded, loading = false, truncated = truncated))
        }
    }

    // ---------------------------------------------------------------- ui state projection

    private val drafts = combine(goalDraft, detailGoalDraft, questionAnswerDraft) { goal, detail, answer ->
        Drafts(goal, detail, answer)
    }
    private val navigation = combine(selectedTaskId, selectedTask, runFeed) { id, task, run ->
        NavigationSnapshot(id, task, run)
    }
    private val content = combine(taskFeed, eventFeed, navigation, drafts, ephemeral) {
            tasks, events, nav, draftValues, local ->
        ContentSnapshot(tasks, events, nav, draftValues, local)
    }
    private val modelInputs = combine(modelFeed, selectedModelKey, networkEnabled) { feed, key, enabled ->
        ModelSnapshot(feed, key, enabled)
    }
    private val pageContent = combine(content, packageCoordinator.state) { page, packageState ->
        PageContent(page, packageState)
    }

    val uiState: StateFlow<AgentWorkspaceUiState> = agentWorkspaceUiStateFlow(
        scope = viewModelScope,
        pageContent = pageContent,
        modelInputs = modelInputs,
        skillsState = skillsBinding.state,
        mcpState = mcpBinding.state,
        settingsState = settingsBinding.state
    )

    // ---------------------------------------------------------------- navigation / local intents

    fun selectTask(taskId: String) {
        savedStateHandle[KEY_SELECTED_TASK_ID] = taskId
        selectedTask.value = taskFeed.value.tasks.firstOrNull { it.taskId == taskId }
        ephemeral.update {
            it.copy(
                notice = null,
                error = null,
                dismissedApprovalId = null,
                openArtifactId = null,
                modelPickerOpen = false
            )
        }
    }

    fun backToHome() {
        savedStateHandle[KEY_SELECTED_TASK_ID] = null
        selectedTask.value = null
        ephemeral.update {
            it.copy(notice = null, error = null, openArtifactId = null, modelPickerOpen = false)
        }
    }

    /** Returns true when the page consumed back; false means the host should leave the tab. */
    fun handleBack(): Boolean {
        val current = ephemeral.value
        if (mcpBinding.isVisible) {
            mcpBinding.close()
            return true
        }
        if (skillsBinding.isVisible) {
            skillsBinding.close()
            return true
        }
        if (settingsBinding.closeVisibleSurface()) return true
        if (current.modelPickerOpen) {
            ephemeral.update { it.copy(modelPickerOpen = false) }
            return true
        }
        if (current.openArtifactId != null) {
            ephemeral.update { it.copy(openArtifactId = null) }
            return true
        }
        if (selectedTaskId.value != null) {
            backToHome()
            return true
        }
        return false
    }

    fun onGoalDraftChanged(value: String) {
        savedStateHandle[KEY_GOAL_DRAFT] = value.take(MAX_DRAFT_CHARS)
    }

    fun onDetailGoalDraftChanged(value: String) {
        savedStateHandle[KEY_DETAIL_GOAL_DRAFT] = value.take(MAX_DRAFT_CHARS)
    }

    fun onQuestionAnswerChanged(value: String) {
        savedStateHandle[KEY_QUESTION_ANSWER_DRAFT] = value.take(MAX_DRAFT_CHARS)
    }

    fun openModelPicker() {
        ephemeral.update { it.copy(modelPickerOpen = true, notice = null) }
    }

    fun dismissModelPicker() {
        ephemeral.update { it.copy(modelPickerOpen = false) }
    }

    fun selectModel(key: String) {
        if (modelFeed.value.choices.none { modelChoiceKey(it) == key }) return
        savedStateHandle[KEY_SELECTED_MODEL] = key
        ephemeral.update { it.copy(modelPickerOpen = false, notice = null, error = null) }
    }

    fun setNetworkEnabled(enabled: Boolean) {
        savedStateHandle[KEY_NETWORK_ENABLED] = enabled
    }

    fun toggleTimelineRow(rowKey: String) {
        ephemeral.update { current ->
            val next = if (rowKey in current.expandedTimelineRows) {
                current.expandedTimelineRows - rowKey
            } else {
                (current.expandedTimelineRows + rowKey).let { expanded ->
                    if (expanded.size <= MAX_EXPANDED_ROWS) expanded else expanded.drop(1).toSet()
                }
            }
            current.copy(expandedTimelineRows = next)
        }
    }

    fun openArtifact(artifactId: String) {
        val snapshot = (runFeed.value as? AgentRunFeed.Loaded)?.snapshot ?: return
        val committed = snapshot.artifacts.any {
            it.artifactId == artifactId && it.commitState == AgentArtifactCommitState.COMMITTED
        }
        if (!committed) return
        ephemeral.update { it.copy(openArtifactId = artifactId) }
    }

    fun dismissArtifact() {
        ephemeral.update { it.copy(openArtifactId = null) }
    }

    fun dismissApprovalSheet() {
        val approvalId = (runFeed.value as? AgentRunFeed.Loaded)?.snapshot?.pendingApproval?.approvalId
            ?: return
        ephemeral.update { it.copy(dismissedApprovalId = approvalId) }
    }

    fun openApprovalSheet() {
        ephemeral.update { it.copy(dismissedApprovalId = null) }
    }

    fun dismissBanner() {
        ephemeral.update { it.copy(notice = null, error = null) }
    }

    // ---------------------------------------------------------------- task package intents

    fun exportTaskPackage(taskId: String) {
        packageCoordinator.requestExport(taskId)
    }

    fun exportTaskPackageToDownloads(taskId: String) {
        packageCoordinator.requestExportToDownloads(taskId)
    }

    fun importTaskPackage() {
        packageCoordinator.requestImport()
    }

    fun requestDeleteTask(taskId: String) {
        packageCoordinator.requestDelete(taskId)
    }

    fun confirmDeleteTask() {
        packageCoordinator.confirmDelete()
    }

    fun cancelDeleteTask() {
        packageCoordinator.cancelDelete()
    }

    fun dismissPackageStatus() {
        packageCoordinator.dismissStatus()
    }

    fun retryTasks() {
        restartTaskFeed(archivedFilter.includeArchived)
    }

    private fun restartTaskFeed(includeArchived: Boolean) {
        taskPageJobs.forEach { it.cancel() }
        taskPageJobs.clear()
        requestedTaskCursors.clear()
        taskFeed.value = AgentTaskFeed(loading = true, includeArchived = includeArchived)
        observeTaskPage(cursor = null)
    }

    fun retryDetail() {
        detailRefresh.update { it + 1 }
    }

    fun loadMoreTasks() {
        val feed = taskFeed.value
        if (feed.loading || feed.loadingMore || feed.endReached) return
        val cursor = feed.nextCursor ?: return
        if (!requestedTaskCursors.add(cursor)) return
        taskFeed.update { it.copy(loadingMore = true) }
        observeTaskPage(cursor)
    }

    fun toggleIncludeArchived() {
        archivedFilter.toggle()
    }

    fun onHomeScrollAnchorChanged(index: Int) {
        savedStateHandle[KEY_HOME_SCROLL_INDEX] = index.coerceAtLeast(0)
    }

    fun onDetailScrollAnchorChanged(index: Int) {
        savedStateHandle[KEY_DETAIL_SCROLL_INDEX] = index.coerceAtLeast(0)
    }

    fun homeScrollAnchor(): Int = savedStateHandle.get<Int>(KEY_HOME_SCROLL_INDEX) ?: 0

    fun detailScrollAnchor(): Int = savedStateHandle.get<Int>(KEY_DETAIL_SCROLL_INDEX) ?: 0

    // ---------------------------------------------------------------- command intents

    fun createAndStartTask() {
        if (ephemeral.value.commandInFlight != null) return
        val goal = goalDraft.value.trim()
        if (goal.isEmpty()) {
            setNotice("先写下要完成的目标")
            return
        }
        val choice = resolveSelectedModel(modelFeed.value.choices, selectedModelKey.value)
        if (choice == null) {
            setNotice("没有可用模型；请先在模型管理中下载或导入模型")
            return
        }
        if (choice.protocolMode == AgentProtocolMode.NONE) {
            setNotice("当前模型不支持 Agent 工具协议")
            return
        }
        if (choice.loadState != AgentModelLoadState.LOADED) {
            setNotice("当前模型尚未加载；请先去聊天页加载模型")
            return
        }
        if (!networkEnabled.value && !choice.availableOffline) {
            setNotice("当前为离线模式，所选模型需要联网")
            return
        }
        val binding = choice.toBinding()
        val policy = currentPolicy()
        launchCommand(AgentWorkspaceCommandKind.CREATE_TASK) {
            val command = CreateAgentTask(
                commandId = newCommandId(),
                goal = goal,
                displayTitle = taskDisplayTitle(goal),
                modelBinding = binding,
                policy = policy
            )
            when (val result = commands.createTask(command)) {
                is AgentCommandResult.Accepted -> {
                    savedStateHandle[KEY_GOAL_DRAFT] = ""
                    // The creation receipt is the durable command -> task anchor (N01). Without it
                    // the page must not guess a task by goal text or unseen ids.
                    val receipt = result.creationReceipt
                    if (receipt == null) {
                        showCommandError(taskAnchorUnavailable())
                        return@launchCommand
                    }
                    selectTask(receipt.taskId)
                    startRunInternal(receipt.taskId, receipt.appliedRevision)
                }

                is AgentCommandResult.Rejected -> showCommandError(result.error)
            }
        }
    }

    fun startTask() {
        if (ephemeral.value.commandInFlight != null) return
        val task = selectedTask.value ?: return
        launchCommand(AgentWorkspaceCommandKind.START_RUN) {
            startRunInternal(task.taskId, task.revision)
        }
    }

    fun stopTask() {
        if (ephemeral.value.commandInFlight != null) return
        val snapshot = (runFeed.value as? AgentRunFeed.Loaded)?.snapshot ?: return
        val run = snapshot.run
        // Stop must work on a parked run as well: WAITING_USER holds no execution slot, but it
        // is exactly the state where a user wants to give up. Gating on holdsActiveSlot made the
        // button silently do nothing there.
        if (run.state.isTerminal || run.state == AgentRunState.STOPPING) return
        launchCommand(AgentWorkspaceCommandKind.CANCEL_RUN) {
            when (val result = commands.requestCancel(
                CancelAgentRun(
                    commandId = newCommandId(),
                    runId = run.runId,
                    expectedRunRevision = run.revision
                )
            )) {
                is AgentCommandResult.Accepted -> Unit
                is AgentCommandResult.Rejected -> showCommandError(result.error)
            }
        }
    }

    fun continueTask() {
        if (ephemeral.value.commandInFlight != null) return
        val snapshot = (runFeed.value as? AgentRunFeed.Loaded)?.snapshot ?: return
        val task = selectedTask.value ?: snapshot.task
        val goalOverride = detailGoalDraft.value.trim().takeIf { it.isNotEmpty() && it != task.goal }
        launchCommand(AgentWorkspaceCommandKind.CONTINUE_TASK) {
            val command = ContinueAgentTask(
                commandId = newCommandId(),
                taskId = task.taskId,
                expectedTaskRevision = task.revision,
                goalOverride = goalOverride
            )
            when (val result = commands.continueTask(command)) {
                is AgentCommandResult.Accepted -> savedStateHandle[KEY_DETAIL_GOAL_DRAFT] = ""
                is AgentCommandResult.Rejected -> showCommandError(result.error)
            }
        }
    }

    fun answerQuestion() {
        if (ephemeral.value.commandInFlight != null) return
        val answer = questionAnswerDraft.value.trim()
        if (answer.isEmpty()) {
            setNotice("请选择或输入回答")
            return
        }
        val snapshot = (runFeed.value as? AgentRunFeed.Loaded)?.snapshot ?: return
        val question = snapshot.activeQuestion?.takeIf { it.status == AgentQuestionStatus.OPEN } ?: return
        launchCommand(AgentWorkspaceCommandKind.ANSWER_QUESTION) {
            val command = AnswerAgentQuestion(
                commandId = newCommandId(),
                runId = snapshot.run.runId,
                expectedRunRevision = snapshot.run.revision,
                questionId = question.questionId,
                answer = answer
            )
            when (val result = commands.answerQuestion(command)) {
                is AgentCommandResult.Accepted -> savedStateHandle[KEY_QUESTION_ANSWER_DRAFT] = ""
                is AgentCommandResult.Rejected -> showCommandError(result.error)
            }
        }
    }

    fun resolveApproval(decision: AgentApprovalDecision) {
        if (ephemeral.value.commandInFlight != null) return
        val snapshot = (runFeed.value as? AgentRunFeed.Loaded)?.snapshot ?: return
        val approval = snapshot.pendingApproval?.takeIf { it.status == AgentApprovalStatus.PENDING }
            ?: return
        launchCommand(AgentWorkspaceCommandKind.RESOLVE_APPROVAL) {
            val command = ResolveAgentApproval(
                commandId = newCommandId(),
                runId = snapshot.run.runId,
                expectedRunRevision = snapshot.run.revision,
                approvalId = approval.approvalId,
                decision = decision
            )
            when (val result = commands.resolveApproval(command)) {
                is AgentCommandResult.Accepted -> ephemeral.update {
                    // Close optimistically: the run owner settles the approval asynchronously and
                    // a second tap on the same frozen approval must not be possible.
                    it.copy(dismissedApprovalId = approval.approvalId)
                }

                is AgentCommandResult.Rejected -> showCommandError(result.error)
            }
        }
    }

    // ---------------------------------------------------------------- command plumbing

    private fun launchCommand(kind: AgentWorkspaceCommandKind, block: suspend () -> Unit) {
        if (ephemeral.value.commandInFlight != null) return
        ephemeral.update { it.copy(commandInFlight = kind, notice = null, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                ephemeral.update {
                    it.copy(
                        error = AgentWorkspaceErrorUi(
                            code = "INTERNAL_UNEXPECTED",
                            message = "操作未能完成，请稍后重试。",
                            retryable = true
                        )
                    )
                }
            } finally {
                ephemeral.update { current ->
                    if (current.commandInFlight == kind) {
                        current.copy(commandInFlight = null)
                    } else {
                        current
                    }
                }
            }
        }
    }

    private suspend fun startRunInternal(taskId: String, expectedTaskRevision: Long) {
        val command = StartAgentRun(
            commandId = newCommandId(),
            taskId = taskId,
            expectedTaskRevision = expectedTaskRevision
        )
        when (val result = commands.startRun(command)) {
            is AgentCommandResult.Accepted -> Unit
            is AgentCommandResult.Rejected -> showCommandError(result.error)
        }
    }

    /**
     * An accepted create command without a [com.muyuchat.core.agent.contract.AgentCreationReceipt] is
     * an unrecoverable anchor failure: the page cannot know which task was created and must not fall
     * back to scanning unseen ids or matching the goal text.
     */
    private fun taskAnchorUnavailable(): AgentError = AgentError(
        code = AgentErrorCodes.TASK_ANCHOR_UNAVAILABLE,
        category = AgentErrorCategory.INTERNAL,
        retryability = AgentRetryability.NEVER,
        safeMessage = "任务已创建但缺少可恢复的创建回执，请在任务列表中打开它。",
        correlationId = "agent-workspace-create"
    )

    private fun showCommandError(error: AgentError) {
        ephemeral.update {
            it.copy(
                error = AgentWorkspaceErrorUi(
                    code = error.code,
                    message = error.safeMessage,
                    retryable = error.retryability != AgentRetryability.NEVER
                )
            )
        }
    }

    private fun setNotice(message: String) {
        ephemeral.update { it.copy(notice = message, error = null) }
    }

    private fun AgentModelChoice.toBinding(): AgentModelBinding = AgentModelBinding(
        providerId = providerId,
        modelId = modelId,
        runtime = runtime,
        assetFingerprint = assetFingerprint,
        protocolMode = protocolMode,
        modelDisplayName = displayName
    )

    /**
     * The user's current explicit model choice, frozen into a manual workflow task.
     *
     * Returns null only when there is no catalog selection or the selected protocol cannot run
     * Agent tools. Load state is deliberately not checked here: the normal run owner owns that
     * failure, and the workflow preview must not pretend a model is loaded when it is not.
     */
    internal fun currentAgentModelBinding(): AgentModelBinding? {
        val choice = resolveSelectedModel(modelFeed.value.choices, selectedModelKey.value) ?: return null
        if (choice.protocolMode == AgentProtocolMode.NONE) return null
        return choice.toBinding()
    }

    private fun currentPolicy(): AgentRunPolicy = if (networkEnabled.value) {
        AgentRunPolicy(
            networkPolicy = AgentNetworkPolicy.NETWORK_ENABLED,
            offlineForced = false
        )
    } else {
        AgentRunPolicy(
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
            offlineForced = true
        )
    }

    private fun newCommandId(): String = UUID.randomUUID().toString()

    companion object {
        private const val MAX_EXPANDED_ROWS = 40

        private const val KEY_SELECTED_TASK_ID = "agent.workspace.selectedTaskId"
        private const val KEY_GOAL_DRAFT = "agent.workspace.goalDraft"
        private const val KEY_DETAIL_GOAL_DRAFT = "agent.workspace.detailGoalDraft"
        private const val KEY_QUESTION_ANSWER_DRAFT = "agent.workspace.questionAnswerDraft"
        private const val KEY_SELECTED_MODEL = "agent.workspace.selectedModel"
        private const val KEY_NETWORK_ENABLED = "agent.workspace.networkEnabled"
        private const val KEY_HOME_SCROLL_INDEX = "agent.workspace.homeScrollIndex"
        private const val KEY_DETAIL_SCROLL_INDEX = "agent.workspace.detailScrollIndex"
    }
}
