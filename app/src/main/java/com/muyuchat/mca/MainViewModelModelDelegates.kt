package com.muyuchat.mca

import android.net.Uri
import com.muyuchat.core.modelstore.ModelManifest
import kotlinx.coroutines.Job

fun MainViewModel.refreshLocalModels() = modelImportAndDownloadCoordinator.refreshLocalModels()
fun MainViewModel.importModel(uri: Uri) = importModel(listOf(uri))
fun MainViewModel.importModel(uris: List<Uri>, textOnly: Boolean = false, persistentAccess: Boolean = false) =
    modelImportAndDownloadCoordinator.importModel(uris, textOnly, persistentAccess)
fun MainViewModel.importModelDirectory(uri: Uri, textOnly: Boolean = false, persistentAccess: Boolean = false) =
    modelImportAndDownloadCoordinator.importModelDirectory(uri, textOnly, persistentAccess)
fun MainViewModel.pauseManagedImport(requestId: String) = modelImportAndDownloadCoordinator.pauseManagedImport(requestId)
fun MainViewModel.resumeManagedImport(requestId: String) = modelImportAndDownloadCoordinator.resumeManagedImport(requestId)
fun MainViewModel.pauseManagedDownloads() = modelImportAndDownloadCoordinator.pauseManagedDownloads()
fun MainViewModel.resumeManagedDownloads() = modelImportAndDownloadCoordinator.resumeManagedDownloads()
internal fun MainViewModel.enqueueManagedDownload(request: ManagedDownloadRequest, useAfterDownload: Boolean) =
    modelImportAndDownloadCoordinator.enqueueManagedDownload(request, useAfterDownload)
fun MainViewModel.attachVisionProjector(modelId: String, uri: Uri) =
    modelImportAndDownloadCoordinator.attachVisionProjector(modelId, uri, uiStateStore.current.loadedModelId, uiStateStore.current.selectedChatBackend)

internal fun MainViewModel.launchModelOperation(
    message: String,
    operation: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
): Job = chatModelCoordinator.launchModelOperation(message, operation)

fun MainViewModel.selectChatModel(choiceId: String) = chatModelCoordinator.selectChatModel(choiceId)
internal fun MainViewModel.selectCloudChatModel(modelId: String?) = chatModelCoordinator.selectCloudChatModel(modelId)
fun MainViewModel.loadModel(requestedModel: ModelManifest) = chatModelCoordinator.loadModel(requestedModel)
fun MainViewModel.verifyModel(model: ModelManifest) = chatModelCoordinator.verifyModel(model)
fun MainViewModel.unloadModel(model: ModelManifest) = chatModelCoordinator.unloadModel(model)
fun MainViewModel.deleteModel(model: ModelManifest) = chatModelCoordinator.deleteModel(model)
