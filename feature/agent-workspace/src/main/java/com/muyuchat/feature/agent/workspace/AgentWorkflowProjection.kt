package com.muyuchat.feature.agent.workspace

import java.util.Locale

/** Operation owned by [AgentWorkflowCoordinator]; at most one is in flight. */
enum class AgentWorkflowOperationKind { LISTING, CREATING }

data class AgentWorkflowErrorUi(
    val code: String,
    val message: String
)

/**
 * Manual create-dialog draft. A rejected submit keeps both fields and shows only the port-provided
 * safe message; the page never echoes an exception.
 */
data class AgentWorkflowDraftUi(
    val name: String = "",
    val goalTemplate: String = "",
    val validationMessage: String? = null
)

/** Projected manual recipe row; no schedule/trigger/next-run value exists to render. */
data class AgentWorkflowRecipeUi(
    val recipeId: String,
    val name: String,
    val goalTemplatePreview: String,
    val versionLabel: String,
    val updatedLabel: String?,
    val manualLabel: String = "手动"
)

/**
 * Workflow surface state. The page is manual-only in this revision: [createDraft] is the manual
 * create dialog, and there is no schedule field, no trigger field and no run-now action.
 */
data class AgentWorkflowsUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val recipes: List<AgentWorkflowRecipeUi> = emptyList(),
    val busy: AgentWorkflowOperationKind? = null,
    val error: AgentWorkflowErrorUi? = null,
    val notice: String? = null,
    val createDraft: AgentWorkflowDraftUi? = null
) {
    val empty: Boolean get() = !loading && recipes.isEmpty()

    val canRefresh: Boolean get() = busy == null && !loading

    val canOpenCreate: Boolean get() = busy == null && createDraft == null

    val canSubmitCreate: Boolean get() = busy == null && createDraft != null
}

internal data class AgentWorkflowProjectionInput(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val recipes: List<AgentWorkflowRecipeItem> = emptyList(),
    val busy: AgentWorkflowOperationKind? = null,
    val error: AgentWorkflowErrorUi? = null,
    val notice: String? = null,
    val createDraft: AgentWorkflowDraftUi? = null
)

internal fun projectWorkflowState(input: AgentWorkflowProjectionInput): AgentWorkflowsUiState =
    AgentWorkflowsUiState(
        visible = input.visible,
        loading = input.loading,
        recipes = input.recipes.map(::projectWorkflowRecipe)
            .sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.recipeId })),
        busy = input.busy,
        error = input.error,
        notice = input.notice,
        createDraft = input.createDraft
    )

internal fun projectWorkflowRecipe(item: AgentWorkflowRecipeItem): AgentWorkflowRecipeUi =
    AgentWorkflowRecipeUi(
        recipeId = item.recipeId,
        name = item.name.take(AgentWorkflowLimits.MAX_NAME_CHARS),
        goalTemplatePreview = item.goalTemplate.take(MAX_WORKFLOW_GOAL_PREVIEW_CHARS),
        versionLabel = item.versionLabel.take(AgentWorkflowLimits.MAX_LABEL_CHARS),
        updatedLabel = item.updatedLabel
            ?.takeIf { it.isNotBlank() }
            ?.take(AgentWorkflowLimits.MAX_LABEL_CHARS)
    )

/**
 * Local form validation before a create call. The host remains authoritative; this only avoids a
 * pointless port call for an empty name/goal and bounds what can be typed.
 */
internal fun workflowDraftValidationError(name: String, goalTemplate: String): String? {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) return "请输入配方名称。"
    if (trimmedName.length > AgentWorkflowLimits.MAX_NAME_CHARS) {
        return "配方名称不能超过 ${AgentWorkflowLimits.MAX_NAME_CHARS} 个字符。"
    }
    if (trimmedName.any { it == '\n' || it == '\r' || it.isISOControl() }) {
        return "配方名称只能是一行，不能包含控制字符。"
    }
    val trimmedGoal = goalTemplate.trim()
    if (trimmedGoal.isEmpty()) return "请输入任务目标。"
    if (trimmedGoal.length > AgentWorkflowLimits.MAX_GOAL_TEMPLATE_CHARS) {
        return "任务目标不能超过 ${AgentWorkflowLimits.MAX_GOAL_TEMPLATE_CHARS} 个字符。"
    }
    if (trimmedGoal.any { it.isISOControl() && it != '\n' && it != '\t' }) {
        return "任务目标不能包含控制字符。"
    }
    return null
}

internal fun normalizedWorkflowName(value: String): String = value.trim()

internal fun normalizedWorkflowGoal(value: String): String = value.trim()

private const val MAX_WORKFLOW_GOAL_PREVIEW_CHARS = 160
