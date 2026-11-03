package com.muyuchat.mca

import com.muyuchat.feature.modelhub.CloudModelUiItem

/**
 * One projection of the connected cloud models shared by the model hub page and the standalone
 * preset page so both surfaces show identical names, provider labels and selection state.
 */
internal fun MainUiState.cloudModelUiItems(): List<CloudModelUiItem> = cloudModels.map { model ->
    CloudModelUiItem(
        id = model.id,
        kind = model.kind.name,
        displayName = model.displayName.ifBlank { model.modelName },
        providerName = model.providerName,
        protocolLabel = model.protocolLabel,
        modelName = model.modelName,
        baseUrl = model.baseUrl,
        supportsVision = model.supportsVision,
        supportsTools = model.supportsTools,
        imageSize = if (model.kind == CloudModelKind.IMAGE) model.imageSize else "",
        selected = when (model.kind) {
            CloudModelKind.CHAT -> selectedChatBackend == ChatBackend.CLOUD && model.id == selectedCloudChatModelId
            CloudModelKind.IMAGE -> selectedImageBackend == ImageBackend.CLOUD && model.id == selectedCloudImageModelId
        }
    )
}

/** Preset bindings only manage chat models; image models never appear in the binding dialog. */
internal fun MainUiState.cloudChatModelUiItems(): List<CloudModelUiItem> =
    cloudModelUiItems().filter { it.kind == CloudModelKind.CHAT.name }
