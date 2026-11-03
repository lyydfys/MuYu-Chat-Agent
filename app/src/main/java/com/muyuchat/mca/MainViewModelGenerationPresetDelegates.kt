package com.muyuchat.mca

import android.app.Application
import androidx.lifecycle.viewModelScope

fun MainViewModel.importGenerationPreset(uriString: String) =
    importGenerationPresetFor(
        scope = viewModelScope,
        application = getApplication<Application>(),
        uiStateWriter = generationPresetWriter,
        presetStore = generationPresetStore,
        uriString = uriString
    )

fun MainViewModel.deleteGenerationPreset(presetId: String) =
    deleteGenerationPresetFor(
        uiStateWriter = generationPresetWriter,
        presetStore = generationPresetStore,
        presetId = presetId
    )

fun MainViewModel.saveGenerationPresetBindings(
    presetId: String,
    name: String,
    boundLocalModelIds: Set<String>,
    boundCloudModelIds: Set<String>
) = saveGenerationPresetBindingsFor(
    application = getApplication<Application>(),
    uiStateWriter = generationPresetWriter,
    presetStore = generationPresetStore,
    modelGenerationProfileStore = modelGenerationProfileStore,
    presetId = presetId,
    name = name,
    boundLocalModelIds = boundLocalModelIds,
    boundCloudModelIds = boundCloudModelIds
)
