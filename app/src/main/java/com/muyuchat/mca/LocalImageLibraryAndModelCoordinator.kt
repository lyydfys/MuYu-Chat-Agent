package com.muyuchat.mca

import android.app.Application
import android.net.Uri
import com.muyuchat.mca.composition.McaAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Result of settling the active local image model selection against installed models.
 */
data class LocalImageSelection(
    val models: List<LocalImageModelRecord>,
    val selectedId: String?,
    val selectedBackend: ImageBackend,
    val qnnVerificationCurrentByModelId: Map<String, Boolean>
)

private fun LocalImageModelRecord.isReadyForManagedSelection(
    qnnVerificationCurrentByModelId: Map<String, Boolean>
): Boolean = localImageReadinessForUi(qnnVerificationCurrentByModelId[id]) == null

/**
 * Coordinates local image model management (import, verification, catalog settlement, removal),
 * image and file asset insertion/clearing, LoRAs, Textual Inversions, upscalers, and image asset deletion.
 */
internal class LocalImageLibraryAndModelCoordinator(
    private val store: UiStateStore,
    private val scope: CoroutineScope,
    private val application: Application,
    private val container: McaAppContainer,
    private val localImageModelStore: LocalImageModelStore,
    private val localImageWorkerClient: LocalImageWorkerClient,
    private val localImageLoraStore: LocalImageLoraStore,
    private val managedRuntimeReadinessRefreshGate: ManagedRuntimeReadinessRefreshGate,
    private val launchModelOperation: (String, suspend CoroutineScope.() -> Unit) -> Unit,
    private val tryAcquireObservedImageGenerationLease: (String) -> LocalImageGenerationCoordinator.Lease?,
    private val releaseObservedImageGenerationLease: (LocalImageGenerationCoordinator.Lease) -> Boolean,
    private val showStatus: (String?) -> Unit,
    private val busy: (String) -> Unit,
    private val fail: (String) -> Unit,
    private val persistFiles: (List<FileAssetRecord>) -> Unit,
    private val activeImageGenerationModelId: () -> String?,
    private val activeLocalApiImageModelId: () -> String?,
    private val imageUpscaleOwnerProvider: () -> ImageUpscaleOwner
) {
    private val localImageAdapterPort: LocalImageAdapterPort =
        container.createLocalImageAdapterPort(store)

    private val localImageUpscalerPort: LocalImageUpscalerPort =
        container.createLocalImageUpscalerPort(
            store,
            imageUpscaleOwnerProvider
        )

    fun useImageAsset(imageId: String) {
        val image = store.state.value.images.firstOrNull { it.id == imageId } ?: return
        store.update { state ->
            val alreadyAttached = imageAttachmentRegex.findAll(state.input).any { match ->
                match.groupValues.getOrNull(2)?.trim() == image.uriString.trim()
            }
            if (alreadyAttached) {
                return@update state.copy(statusMessage = "这张图片已在当前消息中：${image.name}")
            }
            val separator = if (state.input.isBlank()) "" else "\n\n"
            state.copy(
                input = state.input + separator + image.toInputAttachment(),
                statusMessage = "已插入图片：${image.name}"
            )
        }
    }

    fun useFileAsset(fileId: String) {
        val file = store.state.value.files.firstOrNull { it.id == fileId } ?: return
        store.update { state ->
            val separator = if (state.input.isBlank()) "" else "\n\n"
            state.copy(
                input = state.input + separator + file.toInputAttachment(),
                statusMessage = "已插入文件：${file.name}"
            )
        }
    }

    fun deleteFileAsset(fileId: String) {
        deleteFileAssetFor(
            store = store,
            persistFiles = persistFiles,
            fileId = fileId
        )
    }

    fun clearFileLibrary() {
        clearFileLibraryFor(
            store = store,
            showStatus = showStatus,
            persistFiles = persistFiles
        )
    }

    fun importLocalImageLora(uriString: String) =
        importLocalImageLoraAdapter(localImageAdapterPort, scope, uriString)

    fun deleteLocalImageLora(id: String) =
        deleteLocalImageLoraAdapter(localImageAdapterPort, scope, id)

    fun importLocalImageTextualInversion(uriString: String, trigger: String) =
        importLocalImageTextualInversionArtifact(localImageAdapterPort, scope, uriString, trigger)

    fun deleteLocalImageTextualInversion(id: String) =
        deleteLocalImageTextualInversionArtifact(localImageAdapterPort, scope, id)

    fun downloadOfficialViggleLora() =
        downloadOfficialViggleLoraFor(store, scope, localImageLoraStore)

    fun reportMissingLocalImageTextualInversionSelection() {
        store.update {
            it.copy(
                localImageTextualInversionMessage =
                    "所选 Textual Inversion 已删除，请重新选择。",
                statusMessage = "所选 Textual Inversion 已删除，请重新选择。"
            )
        }
    }

    fun importLocalImageUpscaler(uriString: String) =
        importLocalImageUpscalerModel(localImageUpscalerPort, scope, uriString)

    fun selectLocalImageUpscaler(id: String) =
        selectLocalImageUpscalerModel(localImageUpscalerPort, id)

    fun deleteLocalImageUpscaler(id: String) =
        deleteLocalImageUpscalerModel(localImageUpscalerPort, scope, id)


    fun importLocalImageModel(uri: Uri) {
        launchModelOperation("模型操作未完成") {
            busy("正在导入本地图像生成引擎...")
            runCatching {
                localImageModelStore.importFromUri(uri)
            }
                .onSuccess { model ->
                    val selection = settleLocalImageSelection(model)
                    val readiness = model.localImageReadinessForUi(
                        selection.qnnVerificationCurrentByModelId[model.id]
                    )
                    val diagnostic = model.localImageVerificationDiagnosticMessage()
                    managedRuntimeReadinessRefreshGate.invalidate()
                    store.update {
                        it.copy(
                            localImageModels = selection.models,
                            qnnImageVerificationCurrentByModelId = selection.qnnVerificationCurrentByModelId,
                            selectedLocalImageModelId = selection.selectedId,
                            selectedImageBackend = selection.selectedBackend,
                            busy = false,
                            statusMessage = if (readiness != null) {
                                "已导入 ${model.displayName}，但暂不能生成：$readiness"
                            } else if (diagnostic != null) {
                                "已导入本地图像生成引擎：${model.displayName}；$diagnostic"
                            } else {
                                "已导入本地图像生成引擎：${model.displayName}"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    fail(error.message ?: "本地图像生成引擎导入失败")
                }
        }
    }

    fun verifyLocalImageModel(modelId: String) {
        verifyLocalImageModelFor(
            store = store,
            scope = scope,
            application = application,
            localImageModelStore = localImageModelStore,
            localImageWorkerClient = localImageWorkerClient,
            tryAcquireObservedImageGenerationLease = tryAcquireObservedImageGenerationLease,
            releaseObservedImageGenerationLease = releaseObservedImageGenerationLease,
            managedRuntimeReadinessRefreshGate = managedRuntimeReadinessRefreshGate,
            showStatus = showStatus,
            busy = busy,
            modelId = modelId
        )
    }

    fun removeLocalImageModelRecord(modelId: String) {
        launchModelOperation("模型记录操作未完成") {
            if (activeImageGenerationModelId() == modelId || activeLocalApiImageModelId() == modelId) {
                showStatus("当前图片任务正在使用该模型，请结束后再移除记录。")
                return@launchModelOperation
            }
            val removed = store.state.value.localImageModels.firstOrNull { it.matchesCatalogId(modelId) }
            val success = localImageModelStore.removeModelRecord(modelId)
            settleAndApplyLocalImageModelCatalogUpdate(
                store = store,
                localImageModelStore = localImageModelStore,
                context = application,
                managedRuntimeReadinessRefreshGate = managedRuntimeReadinessRefreshGate,
                statusMessage = if (success) "已移除目录记录：${removed?.displayName ?: "模型"}；文件仍保留。"
                    else "未找到本地图像模型记录。"
            )
        }
    }

    /** Must be called from an IO coroutine. */
    fun settleLocalImageSelection(
        preferred: LocalImageModelRecord
    ): LocalImageSelection {
        val models = localImageModelStore.loadModels()
        val preferredModel = models.firstOrNull { it.matchesCatalogId(preferred.id) } ?: preferred
        val qnnVerificationCurrentByModelId = currentQnnImageVerificationByModelId(application, models)
        val currentSelectedId = localImageModelStore.loadSelectedModelId()
        val currentBackend = localImageModelStore.loadSelectedBackend()
        val currentReadyId = currentSelectedId?.takeIf { id ->
            models.any {
                it.id == id && it.isReadyForManagedSelection(qnnVerificationCurrentByModelId)
            }
        }
        val preferredReady = preferredModel.isReadyForManagedSelection(qnnVerificationCurrentByModelId)
        val selectedId = when {
            preferredReady -> preferredModel.id
            currentReadyId != null -> currentReadyId
            else -> models.firstOrNull {
                it.isReadyForManagedSelection(qnnVerificationCurrentByModelId)
            }?.id
        }
        localImageModelStore.saveSelectedModelId(selectedId)
        val selectedBackend = when {
            preferredReady -> ImageBackend.LOCAL
            selectedId == null && currentBackend == ImageBackend.LOCAL -> ImageBackend.CLOUD
            else -> currentBackend
        }
        localImageModelStore.saveSelectedBackend(selectedBackend)
        return LocalImageSelection(
            models = models,
            selectedId = selectedId,
            selectedBackend = selectedBackend,
            qnnVerificationCurrentByModelId = qnnVerificationCurrentByModelId
        )
    }
}
