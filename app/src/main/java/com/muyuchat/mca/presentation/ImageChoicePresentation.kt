package com.muyuchat.mca.presentation

// Image model choice mapping for the UI, moved verbatim from MainActivity during P2.


import com.muyuchat.feature.chat.ImageGenerationUiOptions
import com.muyuchat.mca.LocalImagePreviewMode
import com.muyuchat.mca.LocalImagePreviewOptions
import com.muyuchat.mca.MainViewModel

internal fun existingImageModelChoiceIds(
    localModelIds: Iterable<String>,
    cloudImageModelIds: Iterable<String>
): Set<String> = buildSet {
    localModelIds.forEach { modelId ->
        if (modelId.isNotBlank()) add(MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX + modelId)
    }
    cloudImageModelIds.forEach { modelId ->
        if (modelId.isNotBlank()) add(MainViewModel.CLOUD_IMAGE_MODEL_CHOICE_PREFIX + modelId)
    }
}

internal fun ImageGenerationUiOptions.toLocalImagePreviewOptions(): LocalImagePreviewOptions? {
    val interval = previewInterval ?: return null
    val uiMode = requireNotNull(previewMode) {
        "Image live preview requires an explicit mode when an interval is requested."
    }
    return LocalImagePreviewOptions(
        interval = interval,
        mode = LocalImagePreviewMode.fromWireName(uiMode.wireName)
    )
}
