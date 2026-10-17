package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.feature.chat.ChatBackendFamily
import com.muyuchat.feature.chat.ChatModelChoice
import com.muyuchat.feature.chat.chatBackendFamilyForRuntime
import com.muyuchat.feature.chat.chatBackendOptionsFor
import com.muyuchat.feature.chat.npuAvailabilityForChatBackend
import com.muyuchat.feature.chat.selectedChatBackendId
import com.muyuchat.mca.presentation.existingImageModelChoiceIds

/** 由 ChatUiStateMapping.kt 平移的纯映射块（同包拆分，无行为变化）。 */

internal fun buildLocalChatModelChoices(
    state: MainUiState,
    generationParamsForChatModel: (String) -> GenerationParams
): List<ChatModelChoice> =
    buildList {
        addAll(
            state.cloudModels
                .filter { it.kind == CloudModelKind.CHAT && it.configured }
                .sortedByDescending { it.updatedAt }
                .map { model ->
                ChatModelChoice(
                    id = MainViewModel.CLOUD_MODEL_CHOICE_PREFIX + model.id,
                    displayName = model.displayName.ifBlank { model.modelName },
                    quant = "云端推理",
                    loaded = state.selectedChatBackend == ChatBackend.CLOUD && model.id == state.selectedCloudChatModelId,
                    subtitle = model.apiFormat.label,
                    cloud = true
                )
            }
        )
        addAll(
            state.models
                .sortedWith(
                    compareByDescending<com.muyuchat.core.modelstore.ModelManifest> {
                        if (it.id == state.loadedModelId) 1 else 0
                    }
                        .thenByDescending { it.lastLoadedAt ?: it.createdAt }
                )
                .map { model ->
                    val backendFamily = chatBackendFamilyForRuntime(model.runtime.storageValue)
                    val modelIsLoaded = state.isLocalChatModelLoaded(model.id)
                    val acceleration = state.deviceProfile?.accelerationProfile
                    val npuAvailable: Boolean? = when (backendFamily) {
                        // LiteRT Qualcomm assets are staged on
                        // first NPU load, so the generic QNN
                        // inspector may still be looking at
                        // GenieX's APK runtime here.  Treat an
                        // exact packaged V73/V75/V79/V81
                        // transport as actionable and let the
                        // LiteRT native load perform the final
                        // graph compatibility check.
                        ChatBackendFamily.LITERT_LM,
                        ChatBackendFamily.QAIRT -> npuAvailabilityForChatBackend(
                            family = backendFamily,
                            chipsetCode = acceleration?.chipsetCode,
                            qnnRuntimeUsableForSmoke = acceleration?.qnnRuntime?.usableForSmoke,
                            packagedLiteRtTransportAvailable =
                                backendFamily == ChatBackendFamily.LITERT_LM &&
                                    LiteRtQualcommRuntimeStager.variantForSocModel(
                                        acceleration?.chipsetCode
                                    ) != null
                        )
                        else -> null
                    }
                    val backendOptions = chatBackendOptionsFor(
                        family = backendFamily,
                        npuAvailable = npuAvailable,
                        gpuAvailable = if (
                            backendFamily == ChatBackendFamily.LLAMA_CPP && modelIsLoaded
                        ) {
                            state.stats.gpuOffloadSupported
                        } else {
                            null
                        }
                    )
                    val selectedBackend = selectedChatBackendId(
                        family = backendFamily,
                        params = if (modelIsLoaded) {
                            state.params
                        } else {
                            generationParamsForChatModel(model.id)
                        },
                        stats = if (model.id == state.loadedModelId) state.stats else com.muyuchat.core.engine.RuntimeStats()
                    )
                    val customGpuLayers = if (backendFamily == ChatBackendFamily.LLAMA_CPP) {
                        LlamaAdvancedParams.parse(
                            if (modelIsLoaded) state.params.advancedJson
                            else generationParamsForChatModel(model.id).advancedJson
                        ).params?.nGpuLayers?.takeIf { it >= 0 }
                    } else {
                        null
                    }
                    ChatModelChoice(
                        id = model.id,
                        displayName = model.displayName,
                        quant = model.quant,
                        sizeBytes = model.sizeBytes,
                        loaded = modelIsLoaded,
                        subtitle = listOfNotNull(
                            model.runtime.label,
                            state.localChatReadinessLabel().takeIf {
                                state.selectedChatBackend == ChatBackend.LOCAL &&
                                    model.id == state.loadedModelId
                            }
                        ).joinToString(" · "),
                        backendOptions = backendOptions,
                        selectedBackendId = selectedBackend,
                        customGpuLayers = customGpuLayers
                    )
                }
        )
    }

internal fun existingImageModelIdsFor(state: MainUiState): Set<String> =
    existingImageModelChoiceIds(
        localModelIds = state.localImageModels.map { model -> model.id },
        cloudImageModelIds = state.cloudModels
            .asSequence()
            .filter { model -> model.kind == CloudModelKind.IMAGE }
            .map { model -> model.id }
            .asIterable()
    )
