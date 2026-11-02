package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Page-local workflow-recipe coordinator, manual-only in this slice.
 *
 * It lists recipes and stores a user-authored manual recipe through [AgentWorkflowAdminPort]. It
 * never creates a task/run, owns no scheduler and has no trigger or next-run state. "Create" means
 * persisting a recipe row; launching it remains the existing task/run owner's job in a later slice.
 */
internal class AgentWorkflowCoordinator(
    private val scope: CoroutineScope,
    private val port: AgentWorkflowAdminPort
) {
    private val mutable = MutableStateFlow(AgentWorkflowsUiState())
    val state: StateFlow<AgentWorkflowsUiState> = mutable.asStateFlow()

    fun open() {
        if (mutable.value.visible) return
        val shouldLoad = mutable.value.recipes.isEmpty() &&
            !mutable.value.loading &&
            mutable.value.busy == null
        mutable.update {
            it.copy(
                visible = true,
                loading = shouldLoad || it.loading,
                createDraft = null,
                notice = null
            )
        }
        if (shouldLoad) scope.launch { refreshNow() }
    }

    fun close() {
        mutable.update { it.copy(visible = false, createDraft = null) }
    }

    fun refresh() {
        if (mutable.value.busy != null) return
        scope.launch { refreshNow() }
    }

    fun openCreateDialog() {
        if (mutable.value.busy != null || mutable.value.createDraft != null) return
        mutable.update {
            it.copy(
                createDraft = AgentWorkflowDraftUi(),
                error = null,
                notice = null
            )
        }
    }

    fun updateName(value: String) {
        val draft = mutable.value.createDraft ?: return
        if (mutable.value.busy != null) return
        mutable.update {
            it.copy(
                createDraft = draft.copy(
                    name = value.take(AgentWorkflowLimits.MAX_NAME_CHARS),
                    validationMessage = null
                )
            )
        }
    }

    fun updateGoal(value: String) {
        val draft = mutable.value.createDraft ?: return
        if (mutable.value.busy != null) return
        mutable.update {
            it.copy(
                createDraft = draft.copy(
                    goalTemplate = value.take(AgentWorkflowLimits.MAX_GOAL_TEMPLATE_CHARS),
                    validationMessage = null
                )
            )
        }
    }

    fun dismissCreateDialog() {
        if (mutable.value.busy != null) return
        mutable.update { it.copy(createDraft = null) }
    }

    fun submitCreate() {
        val draft = mutable.value.createDraft ?: return
        if (mutable.value.busy != null) return
        scope.launch { createNow(draft.name, draft.goalTemplate) }
    }

    fun dismissStatus() {
        if (mutable.value.busy != null) return
        mutable.update { it.copy(error = null, notice = null) }
    }

    // ------------------------------------------------------------ deterministic suspend entry points

    internal suspend fun refreshNow() {
        if (mutable.value.busy != null) return
        begin(AgentWorkflowOperationKind.LISTING)
        val result = try {
            port.list()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentWorkflowListResult.Rejected(
                code = "WORKFLOW_ADMIN_FAILED",
                safeMessage = "工作流配方列表暂时无法载入，请稍后重试。"
            )
        }
        when (result) {
            is AgentWorkflowListResult.Loaded -> {
                val projected = projectWorkflowState(
                    AgentWorkflowProjectionInput(
                        visible = mutable.value.visible,
                        loading = false,
                        recipes = result.recipes
                    )
                )
                mutable.update {
                    it.copy(loading = false, busy = null, recipes = projected.recipes, error = null)
                }
            }

            is AgentWorkflowListResult.Rejected -> mutable.update {
                it.copy(
                    loading = false,
                    busy = null,
                    error = AgentWorkflowErrorUi(result.code, result.safeMessage)
                )
            }
        }
    }

    internal suspend fun createNow(name: String, goalTemplate: String) {
        if (mutable.value.busy != null) return
        val normalizedName = normalizedWorkflowName(name)
        val normalizedGoal = normalizedWorkflowGoal(goalTemplate)
        val validation = workflowDraftValidationError(normalizedName, normalizedGoal)
        if (validation != null) {
            mutable.update { current ->
                current.copy(
                    createDraft = (current.createDraft ?: AgentWorkflowDraftUi()).copy(
                        name = normalizedName,
                        goalTemplate = normalizedGoal,
                        validationMessage = validation
                    )
                )
            }
            return
        }
        begin(AgentWorkflowOperationKind.CREATING)
        val outcome = try {
            port.create(normalizedName, normalizedGoal)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentWorkflowAdminOutcome.Rejected(
                code = "WORKFLOW_ADMIN_FAILED",
                safeMessage = "工作流配方未能创建，请稍后重试。"
            )
        }
        when (outcome) {
            is AgentWorkflowAdminOutcome.Created -> {
                val projected = projectWorkflowRecipe(outcome.item)
                mutable.update { current ->
                    val replaced = current.recipes.filterNot {
                        it.recipeId == projected.recipeId
                    } + projected
                    current.copy(
                        recipes = replaced.sortedBy { it.name },
                        loading = false,
                        busy = null,
                        error = null,
                        createDraft = null,
                        notice = "已创建手动配方：${projected.name}"
                    )
                }
            }

            is AgentWorkflowAdminOutcome.Rejected -> mutable.update { current ->
                current.copy(
                    loading = false,
                    busy = null,
                    error = AgentWorkflowErrorUi(outcome.code, outcome.safeMessage),
                    createDraft = (current.createDraft ?: AgentWorkflowDraftUi()).copy(
                        name = normalizedName,
                        goalTemplate = normalizedGoal,
                        validationMessage = outcome.safeMessage
                    )
                )
            }
        }
    }

    private fun begin(kind: AgentWorkflowOperationKind) {
        mutable.update {
            it.copy(
                busy = kind,
                loading = kind == AgentWorkflowOperationKind.LISTING || it.loading,
                error = null,
                notice = null
            )
        }
    }
}
