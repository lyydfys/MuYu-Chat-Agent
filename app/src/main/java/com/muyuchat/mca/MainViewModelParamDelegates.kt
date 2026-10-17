package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.GenerationParams

fun MainViewModel.updateParams(params: GenerationParams) {
    val state = uiStateStore.current
    val previous = state.params
    val scopedParams = params.copy(systemPrompt = state.selectedAssistant()?.systemPrompt ?: previous.systemPrompt)
    val changedRuntimeFields = runtimeParameterChanges(previous, scopedParams)
    if (changedRuntimeFields.isNotEmpty()) {
        container.runtimeAdmission.runtimeUserOverrideFields += changedRuntimeFields
        GenerationPreferences.persistRuntimeUserOverrideFields(getApplication(), container.runtimeAdmission.runtimeUserOverrideFields)
    }
    GenerationPreferences.persistGenerationParams(getApplication(), scopedParams)
    persistModelGenerationParams(state, scopedParams)
    val updatedAssistants = updatedAssistantsWithParams(state.assistants, state.selectedAssistantId, scopedParams)
    val hasLoadedModel = uiStateStore.current.loadedModelId != null
    val reloadRequired = hasLoadedModel && profileDiffersFrom(scopedParams, runtimeAssembly.activeExecutionProfile())
    uiStateStore.update { state ->
        state.copy(
            params = scopedParams,
            assistants = updatedAssistants,
            reloadRequired = reloadRequired,
            statusMessage = if (reloadRequired) {
                "运行参数已修改，正在建立可回滚的待应用配置；当前模型继续使用原配置。"
            } else if (changedRuntimeFields.isNotEmpty() && state.loadedModelId == null) {
                "运行参数已保存；下次加载模型时将按自定义值生效。"
            } else {
                state.statusMessage
            }
        )
    }
    assistantProfileCoordinator.persistAssistantsAsync()
    if (reloadRequired && changedRuntimeFields.isNotEmpty()) {
        stageDirectParameterProfile(scopedParams)
    } else if (!reloadRequired && changedRuntimeFields.isNotEmpty()) {
        container.runtimeAdmission.directParameterStageGeneration.incrementAndGet()
        container.runtimeAdmission.directParameterStageJob?.cancel()
        if (container.runtimeAdmission.pendingProfileTransactionId != null) discardPendingAgentProfile()
    }
}

internal fun MainViewModel.stageDirectParameterProfile(params: GenerationParams) =
    assistantProfileCoordinator.stageDirectParameterProfile(params)

internal fun MainViewModel.loadModelGenerationParams(backend: ChatBackend, modelId: String?, defaults: GenerationParams): GenerationParams =
    loadModelGenerationParamsFor(modelGenerationProfileStore, backend, modelId, defaults)

internal fun MainViewModel.persistModelGenerationParams(state: MainUiState, params: GenerationParams) =
    persistModelGenerationParams(backend = state.selectedChatBackend, modelId = state.currentChatModelId(), params = params)

internal fun MainViewModel.persistModelGenerationParams(backend: ChatBackend, modelId: String?, params: GenerationParams) =
    GenerationPreferences.persistModelGenerationParams(modelGenerationProfileStore, backend, modelId, params)

internal fun MainViewModel.loadGenerationParams(application: Application): GenerationParams =
    GenerationPreferences.loadGenerationParams(application)

internal fun MainViewModel.schedulePersistentPrefixCacheClear(
    operation: Long,
    successMessage: String,
    failureMessage: String
) = cacheManagementCoordinator.schedulePersistentPrefixCacheClear(operation, successMessage, failureMessage)

internal fun MainViewModel.refreshPersistentPrefixCacheSummary() =
    mainPostStartupCoordinator.refreshPersistentPrefixCacheSummary()

