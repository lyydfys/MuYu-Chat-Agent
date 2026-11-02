package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentModelBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Page-local manual-launch state for one selected task recipe.
 *
 * It owns only selection, the bounded input draft, the preview and the one explicit create action.
 * It never creates a task itself, never starts a second loop and has no schedule/trigger/next-run
 * field: the app port maps the one call to the existing task/run owner.
 */
data class AgentWorkflowLaunchUiState(
    val selectedRecipeId: String? = null,
    val preview: AgentWorkflowLaunchPreviewUi? = null,
    val inputValues: Map<String, String> = emptyMap(),
    val loadingPreview: Boolean = false,
    val busy: Boolean = false,
    val error: AgentWorkflowErrorUi? = null,
    val notice: String? = null,
    val lastCreatedTaskId: String? = null
) {
    val hasSelection: Boolean get() = selectedRecipeId != null

    val canCreate: Boolean
        get() = selectedRecipeId != null &&
            preview?.canCreate == true &&
            !loadingPreview &&
            !busy
}

/** Bounds the editable input draft; the host re-validates every value in its own preflight. */
object AgentWorkflowLaunchLimits {
    const val MAX_INPUT_CHARS: Int = 2_000
}

internal class AgentWorkflowLaunchCoordinator(
    private val scope: CoroutineScope,
    private val port: AgentWorkflowLaunchPort,
    private val modelProvider: () -> AgentModelBinding?
) {
    private val mutable = MutableStateFlow(AgentWorkflowLaunchUiState())
    val state: StateFlow<AgentWorkflowLaunchUiState> = mutable.asStateFlow()

    private var previewToken = 0L

    fun select(recipeId: String) {
        if (mutable.value.busy || recipeId.isBlank()) return
        if (mutable.value.selectedRecipeId == recipeId) return
        mutable.update {
            it.copy(
                selectedRecipeId = recipeId,
                preview = null,
                inputValues = emptyMap(),
                loadingPreview = true,
                error = null,
                notice = null,
                lastCreatedTaskId = null
            )
        }
        requestPreview()
    }

    fun clear() {
        if (mutable.value.busy) return
        previewToken++
        mutable.value = AgentWorkflowLaunchUiState()
    }

    fun updateInput(inputId: String, value: String) {
        val current = mutable.value
        if (current.busy) return
        val input = current.preview?.inputs?.firstOrNull {
            it.inputId == inputId && it.editable
        } ?: return
        val bounded = value.take(input.maxChars ?: AgentWorkflowLaunchLimits.MAX_INPUT_CHARS)
        if (current.inputValues[inputId] == bounded) return
        mutable.update {
            it.copy(
                inputValues = it.inputValues + (inputId to bounded),
                loadingPreview = true,
                error = null,
                notice = null
            )
        }
        requestPreview()
    }

    /** Re-reads the preview (for example after the user selected a different model). */
    fun refreshPreview() {
        val current = mutable.value
        if (!current.hasSelection || current.busy || current.loadingPreview) return
        mutable.update { it.copy(loadingPreview = true) }
        requestPreview()
    }

    fun createTask() {
        val current = mutable.value
        val recipeId = current.selectedRecipeId ?: return
        if (!current.canCreate) return
        val model = modelProvider()
        if (model == null) {
            setModelUnavailable()
            return
        }
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        scope.launch { createNow(recipeId, current.inputValues.toMap(), model) }
    }

    fun dismissStatus() {
        if (mutable.value.busy) return
        mutable.update { it.copy(error = null, notice = null) }
    }

    // ------------------------------------------------------------ deterministic suspend entry points

    internal suspend fun previewNow(token: Long) {
        val current = mutable.value
        val recipeId = current.selectedRecipeId ?: return
        val model = modelProvider()
        if (model == null) {
            if (token == previewToken) {
                mutable.update {
                    it.copy(
                        loadingPreview = false,
                        preview = null,
                        error = AgentWorkflowErrorUi(
                            code = "WORKFLOW_MODEL_UNAVAILABLE",
                            message = "没有可用模型；请先在首页选择模型。"
                        )
                    )
                }
            }
            return
        }
        val result = try {
            port.preview(recipeId, current.inputValues, model)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentWorkflowLaunchPreviewResult.Rejected(
                code = "WORKFLOW_ADMIN_FAILED",
                safeMessage = "任务模板预览暂时无法载入，请稍后重试。"
            )
        }
        if (token != previewToken || mutable.value.selectedRecipeId != recipeId) return
        when (result) {
            is AgentWorkflowLaunchPreviewResult.Loaded -> mutable.update {
                it.copy(
                    loadingPreview = false,
                    preview = result.preview,
                    error = null
                )
            }

            is AgentWorkflowLaunchPreviewResult.Rejected -> mutable.update {
                it.copy(
                    loadingPreview = false,
                    preview = null,
                    error = AgentWorkflowErrorUi(result.code, result.safeMessage)
                )
            }
        }
    }

    internal suspend fun createNow(
        recipeId: String,
        inputValues: Map<String, String>,
        model: AgentModelBinding
    ) {
        val outcome = try {
            port.createManualTask(recipeId, inputValues, model)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentWorkflowLaunchOutcome.Rejected(
                code = "WORKFLOW_ADMIN_FAILED",
                safeMessage = "任务模板未能启动，请稍后重试。"
            )
        }
        when (outcome) {
            is AgentWorkflowLaunchOutcome.Started -> mutable.update {
                it.copy(
                    busy = false,
                    error = null,
                    notice = "已创建任务并开始运行；可在任务列表打开。",
                    lastCreatedTaskId = outcome.taskId
                )
            }

            is AgentWorkflowLaunchOutcome.TaskCreatedRunRejected -> mutable.update {
                it.copy(
                    busy = false,
                    notice = null,
                    error = AgentWorkflowErrorUi(
                        code = outcome.code,
                        message = outcome.safeMessage + "（任务已创建，未开始运行）"
                    ),
                    lastCreatedTaskId = outcome.taskId
                )
            }

            is AgentWorkflowLaunchOutcome.Rejected -> mutable.update {
                it.copy(
                    busy = false,
                    notice = null,
                    error = AgentWorkflowErrorUi(outcome.code, outcome.safeMessage)
                )
            }
        }
    }

    private fun requestPreview() {
        val token = ++previewToken
        scope.launch { previewNow(token) }
    }

    private fun setModelUnavailable() {
        previewToken++
        mutable.update {
            it.copy(
                busy = false,
                loadingPreview = false,
                preview = null,
                error = AgentWorkflowErrorUi(
                    code = "WORKFLOW_MODEL_UNAVAILABLE",
                    message = "没有可用模型；请先在首页选择模型。"
                )
            )
        }
    }
}
