package com.muyuchat.feature.agent.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.muyuchat.feature.agent.workspace.memory.MemoryItemReader
import com.muyuchat.feature.agent.workspace.workflow.WorkflowRunRoute
import com.muyuchat.feature.agent.workspace.workflow.WorkflowRunStateHolder
import com.muyuchat.feature.agent.workspace.memory.MemoryManagementRoute
import com.muyuchat.feature.agent.workspace.memory.MemoryManagementStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.muyuchat.core.agent.contract.AgentModelCatalogPort
import com.muyuchat.core.agent.contract.AgentWorkspaceCommands
import com.muyuchat.core.agent.contract.AgentWorkspaceQueries
import com.muyuchat.feature.agent.workspace.ui.AgentWorkspaceContent

/**
 * Route for the independent Agent workspace tab.
 *
 * The host (K09) only supplies the three contract ports and the leave-tab callback. No runtime,
 * Room, engine or app state is reachable from here; the host keeps owning navigation, theme and
 * activity composition.
 */
@Composable
fun AgentWorkspaceRoute(
    queries: AgentWorkspaceQueries,
    commands: AgentWorkspaceCommands,
    modelCatalog: AgentModelCatalogPort,
    onBack: () -> Unit,
    onLoadLocalModel: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    packageActions: AgentPackageActions = AgentPackageActions.Unavailable,
    documentLauncher: AgentDocumentLauncher = AgentDocumentLauncher.Unavailable,
    skillAdmin: AgentSkillAdminPort = AgentSkillAdminPort.Unavailable,
    mcpAdmin: AgentMcpAdminPort = AgentMcpAdminPort.Unavailable,
    toolPackageAdmin: AgentToolPackageAdminPort = AgentToolPackageAdminPort.Unavailable,
    memoryAdmin: AgentMemoryAdminPort = AgentMemoryAdminPort.Unavailable,
    workflowAdmin: AgentWorkflowAdminPort = AgentWorkflowAdminPort.Unavailable,
    workflowLaunch: AgentWorkflowLaunchPort = AgentWorkflowLaunchPort.Unavailable,
    workflowSchedule: AgentWorkflowSchedulePort = AgentWorkflowSchedulePort.Unavailable,
    viewModel: AgentWorkspaceViewModel = viewModel(
        factory = AgentWorkspaceViewModelFactory.create(
            queries = queries,
            commands = commands,
            modelCatalog = modelCatalog,
            packageActions = packageActions,
            documentLauncher = documentLauncher,
            skillAdmin = skillAdmin,
            mcpAdmin = mcpAdmin,
            toolPackageAdmin = toolPackageAdmin,
            memoryAdmin = memoryAdmin,
            workflowAdmin = workflowAdmin,
            workflowLaunch = workflowLaunch,
            workflowSchedule = workflowSchedule
        )
    )
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var memoryManagementOpen by rememberSaveable { mutableStateOf(false) }
    var workflowRunOpen by rememberSaveable { mutableStateOf(false) }
    val workflowRunHolder = remember(workflowAdmin) {
        WorkflowRunStateHolder(
            definitions = workflowAdmin,
            history = { limit -> workflowAdmin.workflowRunHistory(limit) },
            runManual = { definitionId, version -> workflowAdmin.runWorkflow(definitionId, version) }
        )
    }
    val memoryManagementHolder = remember(memoryAdmin) {
        MemoryManagementStateHolder(
            admin = memoryAdmin,
            reader = memoryAdmin as? MemoryItemReader
        )
    }
    val actions = AgentWorkspaceActions(
        onBack = onBack,
        onBackToHome = viewModel::backToHome,
        onOpenTask = viewModel::selectTask,
        onDraftChange = viewModel::onGoalDraftChanged,
        onCreateTask = viewModel::createAndStartTask,
        onStartTask = viewModel::startTask,
        onStop = viewModel::stopTask,
        onContinue = viewModel::continueTask,
        onDetailGoalChange = viewModel::onDetailGoalDraftChanged,
        onQuestionAnswerChange = viewModel::onQuestionAnswerChanged,
        onAnswerQuestion = viewModel::answerQuestion,
        onApprovalDecision = viewModel::resolveApproval,
        onOpenApproval = viewModel::openApprovalSheet,
        onDismissApproval = viewModel::dismissApprovalSheet,
        onOpenModelPicker = viewModel::openModelPicker,
        onDismissModelPicker = viewModel::dismissModelPicker,
        onSelectModel = viewModel::selectModel,
        onNetworkEnabledChange = viewModel::setNetworkEnabled,
        // Agent has no model loader of its own: loading stays on the Chat page's engine path, which
        // the host reaches through this same leave-tab callback used by the empty-catalog notice.
        onOpenChatToLoad = onBack,
        onLoadLocalModel = onLoadLocalModel,
        onToggleTimelineRow = viewModel::toggleTimelineRow,
        onLoadMoreTasks = viewModel::loadMoreTasks,
        onToggleIncludeArchived = viewModel::toggleIncludeArchived,
        onOpenArtifact = viewModel::openArtifact,
        onDismissArtifact = viewModel::dismissArtifact,
        onDismissNotice = viewModel::dismissBanner,
        onRetryTasks = viewModel::retryTasks,
        onRetryDetail = viewModel::retryDetail,
        onHomeScrollAnchor = viewModel::onHomeScrollAnchorChanged,
        onDetailScrollAnchor = viewModel::onDetailScrollAnchorChanged,
        onExportTaskPackage = viewModel::exportTaskPackage,
        onExportTaskPackageToDownloads = viewModel::exportTaskPackageToDownloads,
        onImportTaskPackage = viewModel::importTaskPackage,
        onRequestDeleteTask = viewModel::requestDeleteTask,
        onConfirmDeleteTask = viewModel::confirmDeleteTask,
        onCancelDeleteTask = viewModel::cancelDeleteTask,
        onDismissPackageStatus = viewModel::dismissPackageStatus,
        onOpenSkills = viewModel.skillsBinding::open,
        onCloseSkills = viewModel.skillsBinding::close,
        onRefreshSkills = viewModel.skillsBinding::refresh,
        onImportSkillZip = viewModel.skillsBinding::importZip,
        onImportSkillTree = viewModel.skillsBinding::importTree,
        onSetSkillEnabled = viewModel.skillsBinding::setEnabled,
        onRequestSkillUninstall = viewModel.skillsBinding::requestUninstall,
        onConfirmSkillUninstall = viewModel.skillsBinding::confirmUninstall,
        onCancelSkillUninstall = viewModel.skillsBinding::cancelUninstall,
        onDismissSkillStatus = viewModel.skillsBinding::dismissStatus,
        onOpenMcp = viewModel.mcpBinding::open,
        onCloseMcp = viewModel.mcpBinding::close,
        onRefreshMcp = viewModel.mcpBinding::refresh,
        onOpenMcpAddDialog = viewModel.mcpBinding::openAddDialog,
        onMcpAddNameChange = viewModel.mcpBinding::updateAddDisplayName,
        onMcpAddEndpointChange = viewModel.mcpBinding::updateAddEndpoint,
        onMcpAddAllowLocalChange = viewModel.mcpBinding::updateAddAllowLocal,
        onSubmitMcpAdd = viewModel.mcpBinding::submitAdd,
        onDismissMcpAddDialog = viewModel.mcpBinding::dismissAddDialog,
        onSetMcpEnabled = viewModel.mcpBinding::setEnabled,
        onSetMcpAgentNetworkAllowed = viewModel.mcpBinding::setAgentNetworkAllowed,
        onRequestMcpRemove = viewModel.mcpBinding::requestRemove,
        onConfirmMcpRemove = viewModel.mcpBinding::confirmRemove,
        onCancelMcpRemove = viewModel.mcpBinding::cancelRemove,
        onDismissMcpStatus = viewModel.mcpBinding::dismissStatus,
        settings = AgentSettingsActions(
            onOpenToolPackages = viewModel.settingsBinding::openToolPackages,
            onCloseToolPackages = viewModel.settingsBinding::closeToolPackages,
            onRefreshToolPackages = viewModel.settingsBinding::refreshToolPackages,
            onPreviewToolPackages = viewModel.settingsBinding::previewToolPackage,
            onImportToolPackages = viewModel.settingsBinding::importToolPackage,
            onDismissToolPackageStatus = viewModel.settingsBinding::dismissToolPackageStatus,
            onInstallExecutableToolPackage = viewModel.settingsBinding::installExecutableToolPackage,
            onExecutableToolPackageAction = viewModel.settingsBinding::requestExecutableToolPackageAction,
            onConfirmExecutableToolPackageAction = viewModel.settingsBinding::confirmExecutableToolPackageAction,
            onCancelExecutableToolPackageAction = viewModel.settingsBinding::cancelExecutableToolPackageAction,
            onOpenMemory = viewModel.settingsBinding::openMemory,
            onCloseMemory = viewModel.settingsBinding::closeMemory,
            onOpenMemoryManagement = { memoryManagementOpen = true },
            onRefreshMemory = viewModel.settingsBinding::refreshMemory,
            onSelectMemorySpace = viewModel.settingsBinding::selectMemorySpace,
            onDismissMemoryStatus = viewModel.settingsBinding::dismissMemoryStatus,
            onOpenWorkflows = viewModel.settingsBinding::openWorkflows,
            onOpenWorkflowRun = { workflowRunOpen = true },
            onCloseWorkflows = viewModel.settingsBinding::closeWorkflows,
            onRefreshWorkflows = viewModel.settingsBinding::refreshWorkflows,
            onOpenWorkflowCreate = viewModel.settingsBinding::openWorkflowCreate,
            onWorkflowNameChange = viewModel.settingsBinding::updateWorkflowName,
            onWorkflowGoalChange = viewModel.settingsBinding::updateWorkflowGoal,
            onSubmitWorkflowCreate = viewModel.settingsBinding::submitWorkflowCreate,
            onDismissWorkflowCreate = viewModel.settingsBinding::dismissWorkflowCreate,
            onDismissWorkflowStatus = viewModel.settingsBinding::dismissWorkflowStatus,
            onSelectWorkflow = viewModel.settingsBinding::selectWorkflow,
            onWorkflowInputChange = viewModel.settingsBinding::updateWorkflowInput,
            onWorkflowCreateTask = viewModel.settingsBinding::createWorkflowTask,
            onOpenWorkflowSchedule = viewModel.settingsBinding::openWorkflowSchedule,
            onCloseWorkflowSchedule = viewModel.settingsBinding::closeWorkflowSchedule,
            onRetryWorkflowSchedule = viewModel.settingsBinding::retryWorkflowSchedule,
            onWorkflowScheduleKindChange = viewModel.settingsBinding::updateWorkflowScheduleKind,
            onWorkflowScheduleMinutesChange = viewModel.settingsBinding::updateWorkflowScheduleMinutes,
            onWorkflowScheduleTimeChange = viewModel.settingsBinding::updateWorkflowScheduleTime,
            onWorkflowScheduleWeekdayToggle = viewModel.settingsBinding::toggleWorkflowScheduleWeekday,
            onWorkflowScheduleEnabledChange = viewModel.settingsBinding::updateWorkflowScheduleEnabled,
            onSubmitWorkflowSchedule = viewModel.settingsBinding::submitWorkflowSchedule,
            onDeleteWorkflowSchedule = viewModel.settingsBinding::deleteWorkflowSchedule
        )
    )
    BackHandler {
        if (viewModel.settingsBinding.closeVisibleSchedule()) {
            return@BackHandler
        } else if (workflowRunOpen) {
            workflowRunOpen = false
        } else if (memoryManagementOpen) {
            memoryManagementOpen = false
        } else if (!viewModel.handleBack()) {
            onBack()
        }
    }
    if (workflowRunOpen && state.surface == AgentWorkspaceSurface.WORKFLOWS) {
        WorkflowRunRoute(
            holder = workflowRunHolder,
            onBack = { workflowRunOpen = false },
            modifier = modifier
        )
        return
    }
    if (memoryManagementOpen && state.surface == AgentWorkspaceSurface.MEMORY) {
        MemoryManagementRoute(
            holder = memoryManagementHolder,
            onBack = { memoryManagementOpen = false },
            modifier = modifier
        )
        return
    }
    AgentWorkspaceContent(
        state = state,
        actions = actions,
        homeScrollAnchor = viewModel.homeScrollAnchor(),
        detailScrollAnchor = viewModel.detailScrollAnchor(),
        modifier = modifier
    )
}
