package com.muyuchat.mca

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun MainViewModel.importGenerationPreset(uriString: String) =
    importGenerationPresetFor(
        scope = viewModelScope,
        application = getApplication<Application>(),
        uiStateWriter = generationPresetWriter,
        presetStore = generationPresetStore,
        uriString = uriString
    )

/** Batch import: one picker result carrying several preset files. */
fun MainViewModel.importGenerationPresets(uriStrings: List<String>) =
    importGenerationPresetsFor(
        scope = viewModelScope,
        application = getApplication<Application>(),
        uiStateWriter = generationPresetWriter,
        presetStore = generationPresetStore,
        uriStrings = uriStrings
    )

/**
 * Delete and binding writes touch SharedPreferences and a disk-backed model profile store, so they
 * leave the main thread like the import path does. The UI projection still updates from whichever
 * thread the writer publishes on.
 */
fun MainViewModel.deleteGenerationPreset(presetId: String) =
    viewModelScope.launch(Dispatchers.IO) {
        deleteGenerationPresetFor(
            uiStateWriter = generationPresetWriter,
            presetStore = generationPresetStore,
            presetId = presetId
        )
    }

fun MainViewModel.saveGenerationPresetBindings(
    presetId: String,
    name: String,
    boundLocalModelIds: Set<String>,
    boundCloudModelIds: Set<String>
) = viewModelScope.launch(Dispatchers.IO) {
    saveGenerationPresetBindingsFor(
        application = getApplication<Application>(),
        uiStateWriter = generationPresetWriter,
        presetStore = generationPresetStore,
        modelGenerationProfileStore = modelGenerationProfileStore,
        presetId = presetId,
        name = name,
        boundLocalModelIds = boundLocalModelIds,
        boundCloudModelIds = boundCloudModelIds
    )
}
