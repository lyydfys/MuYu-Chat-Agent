package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.engine.GenerationParams
import java.util.concurrent.atomic.AtomicLong


internal fun applyAssistantDefaultModelFor(
    store: UiStateReadPort,
    showStatus: (String) -> Unit,
    selectCloudChatModel: (String) -> Unit,
    loadModel: (ModelManifest) -> Unit,
    assistant: AssistantRecord
) {
    val modelId = assistant.defaultModelId?.trim().orEmpty()
    val state = store.state.value
    when (assistant.defaultModelMode.normalizedAssistantModelMode()) {
        ASSISTANT_MODEL_MODE_CLOUD -> {
            if (modelId.isBlank()) {
                showStatus("已切换助手：${assistant.name}，但该助手未绑定云端模型。")
                return
            }
            val model = state.cloudModels.firstOrNull { it.id == modelId && it.kind == CloudModelKind.CHAT }
            if (model == null) {
                showStatus("已切换助手：${assistant.name}，但绑定的云端模型已不可用。")
                return
            }
            selectCloudChatModel(model.id)
        }
        ASSISTANT_MODEL_MODE_LOCAL -> {
            if (modelId.isBlank()) {
                showStatus("已切换助手：${assistant.name}，但该助手未绑定本地模型。")
                return
            }
            val model = state.models.firstOrNull { it.id == modelId }
            if (model == null) {
                showStatus("已切换助手：${assistant.name}，但绑定的本地模型已不可用。")
                return
            }
            if (state.selectedChatBackend != ChatBackend.LOCAL || state.loadedModelId != model.id) {
                loadModel(model)
            }
        }
    }
}

internal fun finishSelectingNewAssistantFor(
    store: UiStateWriter<AssistantProfileFields>,
    assistantStore: AssistantStore,
    modelGenerationProfileStore: ModelGenerationProfileStore,
    application: Application,
    invalidatePendingChatImagePreparation: () -> Unit,
    contentImportSelectionRevision: AtomicLong,
    markLocalConversationContextInvalid: () -> Unit,
    applyAssistantDefaultModel: (AssistantRecord) -> Unit,
    currentModelGenerationProfileKey: () -> String?,
    assistant: AssistantRecord
) {
    invalidatePendingChatImagePreparation()
    contentImportSelectionRevision.incrementAndGet()
    assistantStore.saveSelectedAssistantId(assistant.id)
    val current = store.state.value
    val modelPreset = currentModelGenerationProfileKey()
        ?.let { key -> modelGenerationProfileStore.loadOrCreate(key, current.params) }
        ?: current.params
    val updatedParams = assistant.toGenerationParams(modelPreset)
    GenerationPreferences.persistGenerationParams(application, updatedParams)
    store.update {
        it.copy(
            selectedAssistantId = assistant.id,
            params = updatedParams,
            statusMessage = "已切换助手：${assistant.name}"
        )
    }
    markLocalConversationContextInvalid()
    applyAssistantDefaultModel(assistant)
}
