package com.muyuchat.mca

import android.app.Application
import com.muyuchat.mca.composition.settingsParameterWriter
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
    val modelPreset = loadModelGenerationParams(
        state.selectedChatBackend,
        state.currentChatModelId(),
        previous
    )
    val updatedModelPreset = modelPreset.withChangedFieldsFrom(previous, scopedParams)
    persistModelGenerationParams(state.selectedChatBackend, state.currentChatModelId(), updatedModelPreset)
    val selectedAssistant = state.selectedAssistant()
    val effectiveParams = selectedAssistant?.toGenerationParams(updatedModelPreset) ?: updatedModelPreset
    GenerationPreferences.persistGenerationParams(getApplication(), effectiveParams)
    val hasLoadedModel = uiStateStore.current.loadedModelId != null
    val reloadRequired = hasLoadedModel && profileDiffersFrom(effectiveParams, runtimeAssembly.activeExecutionProfile())
    val roleCardOverrides = selectedAssistant
        ?.let { assistant ->
            assistantGenerationOverrideFieldsOrNull(assistant.paramsJson)
                ?: com.muyuchat.core.engine.GenerationParamOverrideFields.legacyFields
        }
        .orEmpty()
    uiStateStore.settingsParameterWriter().update { value ->
        value.copy(
            params = effectiveParams,
            reloadRequired = reloadRequired,
            statusMessage = when {
                effectiveParams != scopedParams && roleCardOverrides.isNotEmpty() ->
                    "模型预设已保存；当前角色卡覆盖了部分参数，角色卡设置优先。"
                reloadRequired ->
                    "运行参数已修改，正在建立可回滚的待应用配置；当前模型继续使用原配置。"
                changedRuntimeFields.isNotEmpty() && value.loadedModelId == null ->
                    "运行参数已保存；下次加载模型时将按自定义值生效。"
                else -> value.statusMessage
            }
        )
    }
    if (reloadRequired && changedRuntimeFields.isNotEmpty()) {
        stageDirectParameterProfile(effectiveParams)
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

/** Pure model preset for the selected model; role-card overrides are applied separately. */
fun MainViewModel.currentModelGenerationPreset(): GenerationParams {
    val state = uiStateStore.current
    val key = GenerationPreferences.modelGenerationProfileKey(
        state.selectedChatBackend,
        state.currentChatModelId()
    ) ?: return state.params
    return modelGenerationProfileStore.load(key, state.params)
}

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

