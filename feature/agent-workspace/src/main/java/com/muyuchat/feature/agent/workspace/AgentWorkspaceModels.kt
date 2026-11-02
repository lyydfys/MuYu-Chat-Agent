package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentModelChoice
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentTask

/**
 * Immutable, platform-free page state for the independent Agent workspace.
 *
 * Everything here is a projection of contract values plus page-local interaction state. No
 * business owner is duplicated: runs are advanced by AgentWorkspaceCommands and observed from
 * AgentWorkspaceQueries only.
 */

enum class AgentWorkspaceSurface { HOME, DETAIL, SKILLS, MCP, TOOL_PACKAGES, MEMORY, WORKFLOWS }

/**
 * The explicit visual states the page must render (architecture 13.4). Extra values
 * (PAUSING/PAUSED/NEEDS_REVIEW/QUEUED) exist because the frozen run-state contract has them and
 * the page must not invent a synonym state to hide a real state.
 */
enum class AgentWorkspaceStatus {
    EMPTY,
    LOADING,
    IDLE,
    QUEUED,
    GENERATING,
    WAITING_RESOURCE,
    WAITING_APPROVAL,
    WAITING_USER,
    PAUSING,
    STOPPING,
    PAUSED,
    DENIED,
    BUDGET_LIMIT,
    PROTOCOL_UNSUPPORTED,
    MODEL_UNLOADED,
    NO_MODEL,
    MCP_OFFLINE,
    SKILL_MISSING_TOOL,
    RESTORED,
    NEEDS_REVIEW,
    CANCELLED,
    FAILED,
    SUCCEEDED,
    PACKAGE_EXPORTING,
    PACKAGE_IMPORTING,
    PACKAGE_DELETING,
    PACKAGE_FAILED
}

enum class AgentStatusTone { NEUTRAL, ACTIVE, SUCCESS, WARNING, ERROR }

enum class AgentWorkspaceCommandKind {
    CREATE_TASK,
    START_RUN,
    CONTINUE_TASK,
    CANCEL_RUN,
    ANSWER_QUESTION,
    RESOLVE_APPROVAL
}

/** Typed, already-sanitized failure; never carries a raw exception or provider body. */
data class AgentWorkspaceErrorUi(
    val code: String,
    val message: String,
    val retryable: Boolean
)

/**
 * Bounded read model of the task list. [tasks] is the accumulated pages, never the whole DB.
 */
data class AgentTaskFeed(
    val tasks: List<AgentTask> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val nextCursor: String? = null,
    val endReached: Boolean = false,
    val errorMessage: String? = null,
    val includeArchived: Boolean = false
)

/** Observation state of the selected task's latest run. */
sealed interface AgentRunFeed {
    data object Empty : AgentRunFeed

    data object Loading : AgentRunFeed

    data object NoRun : AgentRunFeed

    data class Loaded(val snapshot: AgentRunSnapshot) : AgentRunFeed

    data class Failed(val message: String) : AgentRunFeed
}

/** Bounded read model of one run's append-only events. */
data class AgentEventFeed(
    val events: List<AgentEvent> = emptyList(),
    val loading: Boolean = false,
    val truncated: Boolean = false,
    val errorMessage: String? = null
)

/** Read-only catalog observation. [loaded] separates "still loading" from "no model installed". */
data class AgentModelFeed(
    val choices: List<AgentModelChoice> = emptyList(),
    val loaded: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Page-local interaction state that never becomes a second source of business facts.
 */
data class AgentWorkspaceEphemeralState(
    val commandInFlight: AgentWorkspaceCommandKind? = null,
    val notice: String? = null,
    val error: AgentWorkspaceErrorUi? = null,
    val modelPickerOpen: Boolean = false,
    val dismissedApprovalId: String? = null,
    val openArtifactId: String? = null,
    val expandedTimelineRows: Set<String> = emptySet(),
    val createdTaskPending: Boolean = false
)

data class AgentTaskListItem(
    val taskId: String,
    val goal: String,
    val status: AgentWorkspaceStatus,
    val statusLabel: String,
    val updatedText: String,
    val isActive: Boolean,
    val archived: Boolean = false
)

data class AgentHomeUiState(
    val status: AgentWorkspaceStatus = AgentWorkspaceStatus.LOADING,
    val tasks: List<AgentTaskListItem> = emptyList(),
    val activeTasks: List<AgentTaskListItem> = emptyList(),
    val recentTasks: List<AgentTaskListItem> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val endReached: Boolean = true,
    val empty: Boolean = false,
    val errorMessage: String? = null,
    val includeArchived: Boolean = false
)

data class AgentModelOption(
    val key: String,
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val runtimeLabel: String,
    val protocolMode: AgentProtocolMode,
    val protocolLabel: String,
    val offlineAvailable: Boolean,
    val offlineLabel: String,
    val loadState: AgentModelLoadState = AgentModelLoadState.LOADED,
    /**
     * Local = served by the on-device engine; cloud = configured remote provider. Derived from the
     * catalog providerId only, because AgentModelChoice has no separate local flag.
     */
    val isLocal: Boolean = true,
    /**
     * Whether the row can become the effective selection. A cloud protocol with no Agent terminal
     * policy is shown but cannot be chosen; an unloaded local model stays selectable so the page can
     * explain why starting is blocked.
     */
    val selectable: Boolean = true
)

data class AgentModelPickerUiState(
    val loaded: Boolean = false,
    val options: List<AgentModelOption> = emptyList(),
    /** Catalog options served by the on-device engine, in catalog order. */
    val localOptions: List<AgentModelOption> = emptyList(),
    /** Configured cloud options, in catalog order. Never synthesised from availability guesses. */
    val cloudOptions: List<AgentModelOption> = emptyList(),
    val selectedKey: String? = null,
    val selected: AgentModelOption? = null,
    val networkEnabled: Boolean = false,
    val offlineCompatible: Boolean = true,
    val protocolSupported: Boolean = true,
    /** Load state of the effective selection; null when the catalog has no selection at all. */
    val loadState: AgentModelLoadState? = null,
    /** One-line reason why the effective selection cannot start a task; null when runnable. */
    val selectionReason: String? = null,
    val canCreate: Boolean = false,
    val blockedStatus: AgentWorkspaceStatus? = null,
    val errorMessage: String? = null
)

enum class AgentTimelineKind { MODEL, TOOL, APPROVAL, QUESTION, ARTIFACT, BUDGET, RUN, ERROR, OTHER }

/**
 * One compact activity row. Consecutive repeated activity is collapsed and [repeatCount] > 1;
 * [details] are bounded, sanitized event summaries and never raw chain-of-thought.
 */
data class AgentTimelineRow(
    val key: String,
    val kind: AgentTimelineKind,
    val tone: AgentStatusTone,
    val title: String,
    val summary: String?,
    val timeText: String,
    val eventIds: List<String>,
    val details: List<String>,
    val repeatCount: Int,
    val expandable: Boolean,
    val expanded: Boolean,
    /**
     * Tool-lifecycle facts. [toolKey] and [toolState] are null for non-tool rows; [source] is the
     * bounded provenance token P0 wrote into the event summary (`来源未知` when it did not).
     */
    val toolKey: String? = null,
    val toolState: AgentToolLifecycleState? = null,
    val source: AgentToolSourceUi? = null
)

data class AgentOverwriteUi(
    val path: String?,
    val beforeHash: String?,
    val afterHash: String?
)

data class AgentApprovalUi(
    val approvalId: String,
    val invocationId: String,
    val statusLabel: String,
    val toolAction: String,
    val readTargets: List<String>,
    val writeTargets: List<String>,
    val networkTargets: List<String>,
    val scopeSummary: String? = null,
    val argumentsSummary: String?,
    val argumentsTruncated: Boolean,
    val overwrite: AgentOverwriteUi?,
    val expiresText: String,
    val expired: Boolean,
    val argsHashShort: String,
    val schemaHashShort: String,
    val resourceFingerprintShort: String,
    val policyVersion: Long,
    val runEpoch: Long,
    val hasStructuredDetails: Boolean,
    val pending: Boolean,
    /** Null when the runner supplied no structural tool name; the UI must not invent one. */
    val toolLabel: String? = null,
    val actionLabel: String? = null,
    val source: AgentToolSourceUi = AgentToolSourceUi.unknown(),
    /** Bound identity from the frozen approval record, never inferred from model text. */
    val principal: String? = null,
    val approvalIdShort: String = "",
    val invocationIdShort: String = "",
    /** False once the one-shot approval expires or is no longer pending. */
    val actionable: Boolean = pending
)

data class AgentArtifactUi(
    val artifactId: String,
    val relativePath: String,
    val mediaType: String,
    val sizeText: String,
    val sha256Short: String,
    val committed: Boolean,
    val statusText: String,
    val createdText: String
)

data class AgentQuestionUi(
    val questionId: String,
    val text: String,
    val options: List<String>
)

/**
 * Real run-cost telemetry projected from the frozen run contract.
 *
 * Metric fields are optional so the composer row can omit a segment instead of inventing a value.
 * In this revision [contextPercent] and [cacheHitPercent] are always null: no prompt-token or
 * cache-usage field is projected into AgentRunSnapshot (AgentModelAttempt.usageJson is persisted
 * but not exposed), so the page must not claim a percentage it cannot source.
 */
data class AgentRunStatsUiState(
    val rounds: Int? = null,
    val steps: Int? = null,
    val tokensPerSecond: Double? = null,
    val totalTokens: Int? = null,
    val contextPercent: Int? = null,
    val cacheHitPercent: Int? = null,
    val running: Boolean = false
)

data class AgentTaskDetailUiState(
    val taskId: String,
    val goal: String,
    val loading: Boolean = false,
    val status: AgentWorkspaceStatus = AgentWorkspaceStatus.LOADING,
    val statusLabel: String = "",
    val statusDetail: String? = null,
    val modelLabel: String? = null,
    val runLabel: String? = null,
    val runStats: AgentRunStatsUiState? = null,
    val finalAnswer: String? = null,
    /**
     * Completed with no tool invocation and no artifact: the visible answer is the model's own claim,
     * not verified work. The UI must say so instead of presenting it as executed evidence.
     */
    val selfReportedCompletion: Boolean = false,
    val timeline: List<AgentTimelineRow> = emptyList(),
    val timelineLoading: Boolean = false,
    val timelineTruncated: Boolean = false,
    val timelineErrorMessage: String? = null,
    val artifacts: List<AgentArtifactUi> = emptyList(),
    val approval: AgentApprovalUi? = null,
    val question: AgentQuestionUi? = null,
    val waitingForApproval: Boolean = false,
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    val canContinue: Boolean = false,
    val canAnswerQuestion: Boolean = false,
    val stopInFlight: Boolean = false,
    val loadErrorMessage: String? = null,
    val archived: Boolean = false
)

/**
 * Grouped page state for the three Agent settings list surfaces. They stay page-local: opening one
 * is a saveable navigation flag, exactly like Skills/MCP, not global app state.
 */
data class AgentSettingsUiState(
    val toolPackages: AgentToolPackagesUiState = AgentToolPackagesUiState(),
    val memory: AgentMemoryUiState = AgentMemoryUiState(),
    val workflows: AgentWorkflowsUiState = AgentWorkflowsUiState(),
    /** Manual launch state for the recipe selected in [workflows]; no schedule field exists. */
    val workflowLaunch: AgentWorkflowLaunchUiState = AgentWorkflowLaunchUiState(),
    /** The one gated schedule dialog; it never starts a run or installs a wake itself. */
    val workflowSchedule: AgentWorkflowScheduleUiState = AgentWorkflowScheduleUiState()
)

data class AgentWorkspaceUiState(
    val surface: AgentWorkspaceSurface = AgentWorkspaceSurface.HOME,
    val home: AgentHomeUiState = AgentHomeUiState(),
    val detail: AgentTaskDetailUiState? = null,
    val draft: String = "",
    val detailGoalDraft: String = "",
    val questionAnswerDraft: String = "",
    val models: AgentModelPickerUiState = AgentModelPickerUiState(),
    val modelPickerOpen: Boolean = false,
    val approvalSheetVisible: Boolean = false,
    val openArtifact: AgentArtifactUi? = null,
    val packageState: AgentPackageUiState = AgentPackageUiState(),
    val skills: AgentSkillsUiState = AgentSkillsUiState(),
    val mcp: AgentMcpUiState = AgentMcpUiState(),
    /** Tool-package / memory / workflow settings lists; page-local navigation and ports only. */
    val settings: AgentSettingsUiState = AgentSettingsUiState(),
    val commandInFlight: AgentWorkspaceCommandKind? = null,
    val notice: String? = null,
    val error: AgentWorkspaceErrorUi? = null
)

/**
 * Callback surface of the three Agent settings lists.
 *
 * Kept as one typed group instead of extending the main actions bag by twenty unrelated fields;
 * every callback is a real ViewModel intent and defaults to a no-op for previews/tests. The preview
 * callback opens the read-only ZIP picker; the import callback is gated by the still-closed catalog
 * exposure and only explains why nothing can be installed for a run.
 */
data class AgentSettingsActions(
    val onOpenToolPackages: () -> Unit = {},
    val onCloseToolPackages: () -> Unit = {},
    val onRefreshToolPackages: () -> Unit = {},
    val onPreviewToolPackages: () -> Unit = {},
    val onImportToolPackages: () -> Unit = {},
    val onDismissToolPackageStatus: () -> Unit = {},
    /** R6: install one executable (format v2) package from the system ZIP picker. */
    val onInstallExecutableToolPackage: () -> Unit = {},
    /** R6: request enable/disable/rollback/revoke/uninstall; destructive ones ask for confirmation. */
    val onExecutableToolPackageAction: (String, AgentExecutablePackageAction) -> Unit = { _, _ -> },
    val onConfirmExecutableToolPackageAction: () -> Unit = {},
    val onCancelExecutableToolPackageAction: () -> Unit = {},
    val onOpenMemory: () -> Unit = {},
    val onCloseMemory: () -> Unit = {},
    val onOpenMemoryManagement: () -> Unit = {},
    val onRefreshMemory: () -> Unit = {},
    val onSelectMemorySpace: (String) -> Unit = {},
    val onDismissMemoryStatus: () -> Unit = {},
    val onOpenWorkflows: () -> Unit = {},
    val onOpenWorkflowRun: () -> Unit = {},
    val onCloseWorkflows: () -> Unit = {},
    val onRefreshWorkflows: () -> Unit = {},
    val onOpenWorkflowCreate: () -> Unit = {},
    val onWorkflowNameChange: (String) -> Unit = {},
    val onWorkflowGoalChange: (String) -> Unit = {},
    val onSubmitWorkflowCreate: () -> Unit = {},
    val onDismissWorkflowCreate: () -> Unit = {},
    val onDismissWorkflowStatus: () -> Unit = {},
    /** Selects one recipe and loads its manual preview; never creates or starts anything. */
    val onSelectWorkflow: (String) -> Unit = {},
    val onWorkflowInputChange: (String, String) -> Unit = { _, _ -> },
    /** The one explicit manual action: create one task and start one run through the owner path. */
    val onWorkflowCreateTask: () -> Unit = {},
    /** Opens the gated schedule dialog for one recipe; it never creates a run or a wake by itself. */
    val onOpenWorkflowSchedule: (String, String) -> Unit = { _, _ -> },
    val onCloseWorkflowSchedule: () -> Unit = {},
    val onRetryWorkflowSchedule: () -> Unit = {},
    val onWorkflowScheduleKindChange: (AgentWorkflowScheduleKind) -> Unit = {},
    val onWorkflowScheduleMinutesChange: (String) -> Unit = {},
    val onWorkflowScheduleTimeChange: (String) -> Unit = {},
    val onWorkflowScheduleWeekdayToggle: (AgentWorkflowWeekday) -> Unit = {},
    val onWorkflowScheduleEnabledChange: (Boolean) -> Unit = {},
    /** Persists the plan through the host; the host alone owns the row and the platform wake. */
    val onSubmitWorkflowSchedule: () -> Unit = {},
    val onDeleteWorkflowSchedule: () -> Unit = {}
)

/** Callback surface of the page. The Route owns the mapping from these to ViewModel intents. */
data class AgentWorkspaceActions(
    val onBack: () -> Unit = {},
    val onBackToHome: () -> Unit = {},
    val onOpenTask: (String) -> Unit = {},
    val onDraftChange: (String) -> Unit = {},
    val onCreateTask: () -> Unit = {},
    val onStartTask: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onContinue: () -> Unit = {},
    val onDetailGoalChange: (String) -> Unit = {},
    val onQuestionAnswerChange: (String) -> Unit = {},
    val onAnswerQuestion: () -> Unit = {},
    val onApprovalDecision: (AgentApprovalDecision) -> Unit = {},
    val onOpenApproval: () -> Unit = {},
    val onDismissApproval: () -> Unit = {},
    val onOpenModelPicker: () -> Unit = {},
    val onDismissModelPicker: () -> Unit = {},
    val onSelectModel: (String) -> Unit = {},
    val onNetworkEnabledChange: (Boolean) -> Unit = {},
    /** The only real "load this model" recovery path: leave for the Chat page's engine load UI. */
    val onOpenChatToLoad: () -> Unit = {},
    /** Loads an installed local model through the host's existing engine load lease. */
    val onLoadLocalModel: (String) -> Unit = {},
    val onToggleTimelineRow: (String) -> Unit = {},
    val onLoadMoreTasks: () -> Unit = {},
    val onToggleIncludeArchived: () -> Unit = {},
    val onOpenArtifact: (String) -> Unit = {},
    val onDismissArtifact: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onRetryTasks: () -> Unit = {},
    val onRetryDetail: () -> Unit = {},
    val onHomeScrollAnchor: (Int) -> Unit = {},
    val onDetailScrollAnchor: (Int) -> Unit = {},
    val onExportTaskPackage: (String) -> Unit = {},
    val onExportTaskPackageToDownloads: (String) -> Unit = {},
    val onImportTaskPackage: () -> Unit = {},
    val onRequestDeleteTask: (String) -> Unit = {},
    val onConfirmDeleteTask: () -> Unit = {},
    val onCancelDeleteTask: () -> Unit = {},
    val onDismissPackageStatus: () -> Unit = {},
    val onOpenSkills: () -> Unit = {},
    val onCloseSkills: () -> Unit = {},
    val onRefreshSkills: () -> Unit = {},
    val onImportSkillZip: () -> Unit = {},
    val onImportSkillTree: () -> Unit = {},
    val onSetSkillEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val onRequestSkillUninstall: (String) -> Unit = {},
    val onConfirmSkillUninstall: () -> Unit = {},
    val onCancelSkillUninstall: () -> Unit = {},
    val onDismissSkillStatus: () -> Unit = {},
    val onOpenMcp: () -> Unit = {},
    val onCloseMcp: () -> Unit = {},
    val onRefreshMcp: () -> Unit = {},
    val onOpenMcpAddDialog: () -> Unit = {},
    val onMcpAddTokenChange: (String) -> Unit = {},
    val onMcpAddNameChange: (String) -> Unit = {},
    val onMcpAddEndpointChange: (String) -> Unit = {},
    val onMcpAddAllowLocalChange: (Boolean) -> Unit = {},
    val onSubmitMcpAdd: () -> Unit = {},
    val onDismissMcpAddDialog: () -> Unit = {},
    val onSetMcpEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val onSetMcpAgentNetworkAllowed: (String, Boolean) -> Unit = { _, _ -> },
    val onRequestMcpRemove: (String) -> Unit = {},
    val onConfirmMcpRemove: () -> Unit = {},
    val onCancelMcpRemove: () -> Unit = {},
    val onDismissMcpStatus: () -> Unit = {},
    /** Grouped settings-list callbacks; see [AgentSettingsActions]. */
    val settings: AgentSettingsActions = AgentSettingsActions()
)
