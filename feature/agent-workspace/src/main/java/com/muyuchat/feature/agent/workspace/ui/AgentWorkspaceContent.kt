package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions
import com.muyuchat.feature.agent.workspace.AgentWorkspaceCommandKind
import com.muyuchat.feature.agent.workspace.AgentWorkspaceSurface
import com.muyuchat.feature.agent.workspace.AgentWorkspaceUiState

/**
 * Stateless page root, split from the Route so previews and Compose tests can drive fixed states
 * without constructing a ViewModel. The host must provide McaTheme above this surface.
 */
@Composable
fun AgentWorkspaceContent(
    state: AgentWorkspaceUiState,
    actions: AgentWorkspaceActions,
    homeScrollAnchor: Int = 0,
    detailScrollAnchor: Int = 0,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        val detail = state.detail
        AnimatedContent(
            // AnimatedContent retains each target value until its exit finishes. Supply the
            // immutable presentation snapshot, not a Boolean that would make outgoing content
            // read the new state's cleared detail. contentKey keeps live updates on the same
            // task in place while task-to-task navigation still gets a real transition.
            targetState = state,
            contentKey = { it.surfaceContentKey() },
            transitionSpec = {
                (fadeIn(animationSpec = McaMotion.fade(millis = 140)) +
                    slideInHorizontally(animationSpec = McaMotion.pageSlide) { it / 16 }) togetherWith
                    fadeOut(animationSpec = McaMotion.fade(millis = 90))
            },
            label = "agentWorkspaceSurfaceTransition",
            modifier = Modifier.fillMaxSize()
        ) { presentation ->
            val surface = presentation.surface
            if (surface == AgentWorkspaceSurface.MCP) {
                AgentMcpScreen(
                    state = presentation.mcp,
                    actions = actions,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (surface == AgentWorkspaceSurface.SKILLS) {
                AgentSkillsScreen(
                    state = presentation.skills,
                    actions = actions,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (surface == AgentWorkspaceSurface.TOOL_PACKAGES) {
                AgentToolPackagesScreen(
                    state = presentation.settings.toolPackages,
                    actions = actions,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (surface == AgentWorkspaceSurface.MEMORY) {
                AgentMemoryScreen(
                    state = presentation.settings.memory,
                    actions = actions,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (surface == AgentWorkspaceSurface.WORKFLOWS) {
                AgentWorkflowsScreen(
                    state = presentation.settings.workflows,
                    launch = presentation.settings.workflowLaunch,
                    schedule = presentation.settings.workflowSchedule,
                    actions = actions,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (presentation.detail == null) {
                AgentWorkspaceHomeScreen(
                    state = presentation,
                    actions = actions,
                    initialScrollIndex = homeScrollAnchor,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AgentTaskDetailScreen(
                    state = presentation,
                    actions = actions,
                    initialScrollIndex = detailScrollAnchor,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val approval = detail?.approval
        if (state.approvalSheetVisible && approval != null) {
            val approvalError = state.error?.takeIf {
                it.code.startsWith("PERMISSION_") || it.code.startsWith("COMMAND_")
            }?.message
            AgentApprovalSheet(
                approval = approval,
                inFlight = state.commandInFlight == AgentWorkspaceCommandKind.RESOLVE_APPROVAL,
                errorMessage = approvalError,
                onDecision = actions.onApprovalDecision,
                onDismiss = actions.onDismissApproval
            )
        }

        if (state.modelPickerOpen) {
            AgentModelPickerSheet(
                models = state.models,
                onSelect = actions.onSelectModel,
                onNetworkEnabledChange = actions.onNetworkEnabledChange,
                onLoadModel = { modelId ->
                    actions.onDismissModelPicker()
                    actions.onLoadLocalModel(modelId)
                },
                onOpenChatToLoad = {
                    actions.onDismissModelPicker()
                    actions.onOpenChatToLoad()
                },
                onDismiss = actions.onDismissModelPicker
            )
        }

        state.openArtifact?.let { artifact ->
            AgentArtifactInfoDialog(artifact = artifact, onDismiss = actions.onDismissArtifact)
        }

        state.packageState.deleteConfirmation?.let { confirmation ->
            AgentDeleteTaskDialog(
                confirmation = confirmation,
                busy = state.packageState.busy,
                onConfirm = actions.onConfirmDeleteTask,
                onCancel = actions.onCancelDeleteTask
            )
        }
    }
}

/** Presentation identity only: changing stream content must not restart a page transition. */
internal fun AgentWorkspaceUiState.surfaceContentKey(): Pair<AgentWorkspaceSurface, String?> =
    surface to when (surface) {
        AgentWorkspaceSurface.MCP,
        AgentWorkspaceSurface.SKILLS,
        AgentWorkspaceSurface.TOOL_PACKAGES,
        AgentWorkspaceSurface.MEMORY,
        AgentWorkspaceSurface.WORKFLOWS -> null
        else -> detail?.taskId
    }
