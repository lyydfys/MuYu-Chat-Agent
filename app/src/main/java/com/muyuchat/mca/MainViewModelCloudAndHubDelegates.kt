package com.muyuchat.mca

import com.muyuchat.core.download.ModelScopeHubModel
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.engine.GenerationParams

fun MainViewModel.deleteCloudModel(modelId: String) =
    chatImageWorkflowCoordinator.deleteCloudModel(modelId)

fun MainViewModel.updateCloudApiEnabled(enabled: Boolean) = chatModelCoordinator.updateCloudApiEnabled(enabled)
fun MainViewModel.updateCloudApiFormat(value: String) = chatModelCoordinator.updateCloudApiFormat(value)
fun MainViewModel.updateCloudImageApiFormat(value: String) = chatModelCoordinator.updateCloudImageApiFormat(value)
fun MainViewModel.applyCloudProviderPreset(value: String) = chatModelCoordinator.applyCloudProviderPreset(value)
fun MainViewModel.beginAddCloudModel(kind: String) = chatModelCoordinator.beginAddCloudModel(kind)
fun MainViewModel.editCloudModel(modelId: String) = chatModelCoordinator.editCloudModel(modelId)
fun MainViewModel.updateCloudBaseUrl(value: String) = chatModelCoordinator.updateCloudBaseUrl(value)
fun MainViewModel.updateCloudApiKey(value: String) = chatModelCoordinator.updateCloudApiKey(value)
fun MainViewModel.updateCloudChatModel(value: String) = chatModelCoordinator.updateCloudChatModel(value)
fun MainViewModel.updateCloudSupportsVision(value: Boolean) = chatModelCoordinator.updateCloudSupportsVision(value)
fun MainViewModel.updateModelBackendPreference(modelId: String, backendId: String) = chatModelCoordinator.updateModelBackendPreference(modelId, backendId)
fun MainViewModel.generationParamsForChatModel(modelId: String): GenerationParams = chatModelCoordinator.generationParamsForChatModel(modelId)
fun MainViewModel.updateCloudSupportsTools(value: Boolean) = chatModelCoordinator.updateCloudSupportsTools(value)
fun MainViewModel.updateCloudStreaming(value: Boolean) = chatModelCoordinator.updateCloudStreaming(value)
fun MainViewModel.updateCloudImageModel(value: String) = chatModelCoordinator.updateCloudImageModel(value)
fun MainViewModel.updateCloudResponsesReasoning(value: Boolean) = chatModelCoordinator.updateCloudResponsesReasoning(value)
fun MainViewModel.updateCloudImageSize(value: String) = chatModelCoordinator.updateCloudImageSize(value)
fun MainViewModel.updateCloudImageEndpointPath(value: String) = chatModelCoordinator.updateCloudImageEndpointPath(value)
fun MainViewModel.updateCloudDisplayName(value: String) = chatModelCoordinator.updateCloudDisplayName(value)
fun MainViewModel.saveCloudApiConfig() = chatModelCoordinator.saveCloudApiConfig()
fun MainViewModel.saveCloudChatModel() = chatModelCoordinator.saveCloudChatModel()
fun MainViewModel.saveCloudImageModel() = chatModelCoordinator.saveCloudImageModel()
fun MainViewModel.testCloudApiConfig() = chatModelCoordinator.testCloudApiConfig()
suspend fun MainViewModel.fetchCloudModelOptions(): Result<List<String>> = chatModelCoordinator.fetchCloudModelOptions()
suspend fun MainViewModel.fetchCloudImageModelOptions(): Result<List<String>> = chatModelCoordinator.fetchCloudImageModelOptions()
fun MainViewModel.selectCloudImageModel(modelId: String) = chatModelCoordinator.selectCloudImageModel(modelId)

fun MainViewModel.fetchRemoteFiles() = modelScopeHubCoordinator.fetchRemoteFiles()
fun MainViewModel.fetchRecommendedFiles(model: ModelScopeRecommendedModel) = modelScopeHubCoordinator.fetchRecommendedFiles(model)
fun MainViewModel.downloadRecommended(model: ModelScopeRecommendedModel) = downloadRecommended(model, useAfterDownload = false)
fun MainViewModel.downloadRecommended(
    model: ModelScopeRecommendedModel,
    useAfterDownload: Boolean,
    imageBundleVariantId: String? = null
) =
    modelScopeHubCoordinator.downloadRecommended(model, useAfterDownload, imageBundleVariantId)
fun MainViewModel.searchHubModels(reset: Boolean = true) = modelScopeHubCoordinator.searchHubModels(reset)
fun MainViewModel.fetchHubModelFiles(model: ModelScopeHubModel) = modelScopeHubCoordinator.fetchHubModelFiles(model)
fun MainViewModel.openModelScopePage(url: String) = modelScopeHubCoordinator.openModelScopePage(url)
fun MainViewModel.download(remote: RemoteModelFile) = modelImportAndDownloadCoordinator.download(remote, uiStateStore.current.loadedModelId)
