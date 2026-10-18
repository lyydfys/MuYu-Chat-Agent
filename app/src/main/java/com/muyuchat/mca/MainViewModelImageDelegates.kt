package com.muyuchat.mca

import android.net.Uri
import com.muyuchat.core.engine.ChatGeneratedImageRequest

fun MainViewModel.upscaleImageAsset(imageId: String, targetScale: Int) =
    chatImageWorkflowCoordinator.upscaleImageAsset(imageId, targetScale)

fun MainViewModel.cancelImageUpscale() =
    chatImageWorkflowCoordinator.cancelImageUpscale()

fun MainViewModel.attachFile(uriString: String) =
    chatInputAndAttachmentCoordinator.attachFile(uriString)

fun MainViewModel.useImageAsset(imageId: String) =
    localImageLibraryAndModelCoordinator.useImageAsset(imageId)

fun MainViewModel.useFileAsset(fileId: String) =
    localImageLibraryAndModelCoordinator.useFileAsset(fileId)

fun MainViewModel.deleteFileAsset(fileId: String) =
    localImageLibraryAndModelCoordinator.deleteFileAsset(fileId)

fun MainViewModel.clearFileLibrary() {
    launchSettingsCleanup(SettingsCleanupOperation.FILE_LIBRARY) {
        localImageLibraryAndModelCoordinator.clearFileLibrary()
    }
}

fun MainViewModel.importLocalImageLora(uriString: String) =
    localImageLibraryAndModelCoordinator.importLocalImageLora(uriString)

fun MainViewModel.deleteLocalImageLora(id: String) =
    localImageLibraryAndModelCoordinator.deleteLocalImageLora(id)

fun MainViewModel.importLocalImageTextualInversion(uriString: String, trigger: String) =
    localImageLibraryAndModelCoordinator.importLocalImageTextualInversion(uriString, trigger)

fun MainViewModel.deleteLocalImageTextualInversion(id: String) =
    localImageLibraryAndModelCoordinator.deleteLocalImageTextualInversion(id)

fun MainViewModel.downloadOfficialViggleLora() =
    localImageLibraryAndModelCoordinator.downloadOfficialViggleLora()


fun MainViewModel.reportMissingLocalImageTextualInversionSelection() =
    localImageLibraryAndModelCoordinator.reportMissingLocalImageTextualInversionSelection()

fun MainViewModel.importLocalImageUpscaler(uriString: String) =
    localImageLibraryAndModelCoordinator.importLocalImageUpscaler(uriString)

fun MainViewModel.selectLocalImageUpscaler(id: String) =
    localImageLibraryAndModelCoordinator.selectLocalImageUpscaler(id)

fun MainViewModel.deleteLocalImageUpscaler(id: String) =
    localImageLibraryAndModelCoordinator.deleteLocalImageUpscaler(id)

fun MainViewModel.generateImageAsset(
    prompt: String,
    inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
    options: LocalImageGenerationOptions = LocalImageGenerationOptions()
): Boolean = chatImageWorkflowCoordinator.generateImageAsset(prompt, inputDraft, options)

fun MainViewModel.recreateImageAsset(imageId: String) =
    chatImageWorkflowCoordinator.recreateImageAsset(imageId)

fun MainViewModel.selectLocalImageModel(modelId: String) =
    chatImageWorkflowCoordinator.selectLocalImageModel(modelId)

fun MainViewModel.cancelImageGeneration() = chatImageWorkflowCoordinator.cancelImageGeneration()

internal fun MainViewModel.cancelImageGenerationForJob(expectedJobId: String?) =
    chatImageWorkflowCoordinator.cancelImageGenerationForJob(expectedJobId)

fun MainViewModel.exportImageLibraryBackup(uriString: String, favoritesOnly: Boolean) =
    chatImageWorkflowCoordinator.exportImageLibraryBackup(uriString, favoritesOnly)

fun MainViewModel.importImageLibraryBackup(uriString: String) =
    chatImageWorkflowCoordinator.importImageLibraryBackup(uriString)

fun MainViewModel.cancelImageLibraryBackup() =
    chatImageWorkflowCoordinator.cancelImageLibraryBackup()

fun MainViewModel.importLocalImageModel(uri: Uri) =
    localImageLibraryAndModelCoordinator.importLocalImageModel(uri)

fun MainViewModel.verifyLocalImageModel(modelId: String) =
    localImageLibraryAndModelCoordinator.verifyLocalImageModel(modelId)

fun MainViewModel.removeLocalImageModelRecord(modelId: String) =
    localImageLibraryAndModelCoordinator.removeLocalImageModelRecord(modelId)

fun MainViewModel.onRepoInputChange(value: String) = uiStateStore.update { it.copy(repoInput = value) }
fun MainViewModel.onHubQueryChange(value: String) = uiStateStore.update { it.copy(hubQuery = value) }

fun MainViewModel.buildChatImageRetrySnapshot(
    request: ChatGeneratedImageRequest,
    chatSessionId: String
): ImageGenerationJobSpec? =
    chatImageWorkflowCoordinator.buildChatImageRetrySnapshot(request, chatSessionId)
