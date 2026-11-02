package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Internal page-state bundles and the one pure projection assembly used by
 * [AgentWorkspaceViewModel]. Kept outside the view model so its file stays within the hard review
 * budget; nothing here owns behaviour or writes state.
 */

internal data class Drafts(val goal: String, val detailGoal: String, val answer: String)

internal data class NavigationSnapshot(
    val selectedTaskId: String?,
    val selectedTask: com.muyuchat.core.agent.contract.AgentTask?,
    val runFeed: AgentRunFeed
)

internal data class ContentSnapshot(
    val tasks: AgentTaskFeed,
    val events: AgentEventFeed,
    val navigation: NavigationSnapshot,
    val drafts: Drafts,
    val ephemeral: AgentWorkspaceEphemeralState
)

internal data class PageContent(
    val content: ContentSnapshot,
    val packageState: AgentPackageUiState
)

internal data class ModelSnapshot(
    val feed: AgentModelFeed,
    val selectedModelKey: String?,
    val networkEnabled: Boolean
)

internal data class DetailKey(val taskId: String?, val runId: String?, val refresh: Int)

internal fun agentWorkspaceUiStateFlow(
    scope: CoroutineScope,
    pageContent: Flow<PageContent>,
    modelInputs: Flow<ModelSnapshot>,
    skillsState: StateFlow<AgentSkillsUiState>,
    mcpState: StateFlow<AgentMcpUiState>,
    settingsState: StateFlow<AgentSettingsUiState>
): StateFlow<AgentWorkspaceUiState> = combine(
    pageContent,
    modelInputs,
    skillsState,
    mcpState,
    settingsState
) { page, models, skills, mcp, settings ->
    projectWorkspaceState(
        AgentWorkspaceProjectionInput(
            selectedTaskId = page.content.navigation.selectedTaskId,
            taskFeed = page.content.tasks,
            selectedTask = page.content.navigation.selectedTask,
            runFeed = page.content.navigation.runFeed,
            eventFeed = page.content.events,
            modelFeed = models.feed,
            selectedModelKey = models.selectedModelKey,
            networkEnabled = models.networkEnabled,
            draft = page.content.drafts.goal,
            detailGoalDraft = page.content.drafts.detailGoal,
            questionAnswerDraft = page.content.drafts.answer,
            ephemeral = page.content.ephemeral,
            nowMillis = System.currentTimeMillis(),
            packageState = page.packageState,
            skillsState = skills,
            mcpState = mcp,
            settingsState = settings
        )
    )
}.flowOn(Dispatchers.Default).stateIn(
    scope = scope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = AgentWorkspaceUiState()
)
