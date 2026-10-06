package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun Pair<Boolean, String>.toLocalImageVerificationStatus(
    runtime: LocalImageRuntime,
    family: LocalImageModelFamily
): LocalImageVerificationStatus =
    when {
        !first -> LocalImageVerificationStatus.FAILED
        runtime == LocalImageRuntime.MNN_DIFFUSION -> LocalImageVerificationStatus.MNN_SMOKE_PASSED
        runtime == LocalImageRuntime.QNN_HTP && family == LocalImageModelFamily.SDXL ->
            LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED
        else -> LocalImageVerificationStatus.PASSED
    }

internal data class QnnVerificationPersistence(
    val verification: Pair<Boolean, String>,
    val stamp: String
)

internal fun qnnVerificationPersistence(
    application: Application,
    model: LocalImageModelRecord,
    verification: Pair<Boolean, String>,
    execution: QnnImageExecutionEvidence? = null
): QnnVerificationPersistence {
    if (model.runtime != LocalImageRuntime.QNN_HTP || !verification.first) {
        return QnnVerificationPersistence(verification, "")
    }
    return runCatching {
        val bundleRoot = model.bundleRoot
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(model.path).parentFile
            ?: error("QNN 图像引擎缺少完整组件目录。")
        QnnVerificationPersistence(
            verification = verification,
            stamp = qnnImageVerificationStampFor(
                application, bundleRoot,
                execution = requireNotNull(execution) { "QNN execution evidence is missing." }
            )
        )
    }.getOrElse { error ->
        QnnVerificationPersistence(
            verification = false to "QNN 生图校验完成，但无法保存当前设备/运行时校验戳：${error.message ?: "未知错误"}",
            stamp = ""
        )
    }
}

internal fun verifyLocalImageModelFor(
    store: UiStateStore,
    scope: CoroutineScope,
    application: Application,
    localImageModelStore: LocalImageModelStore,
    localImageWorkerClient: LocalImageWorkerClient,
    tryAcquireObservedImageGenerationLease: (String) -> LocalImageGenerationCoordinator.Lease?,
    releaseObservedImageGenerationLease: (LocalImageGenerationCoordinator.Lease) -> Boolean,
    managedRuntimeReadinessRefreshGate: ManagedRuntimeReadinessRefreshGate,
    showStatus: (String) -> Unit,
    busy: (String) -> Unit,
    modelId: String
) {
    scope.launch(Dispatchers.IO) {
        val model = store.state.value.localImageModels.firstOrNull { it.matchesCatalogId(modelId) }
        if (model == null) {
            showStatus("未找到本地图像生成引擎")
            return@launch
        }
        val verificationRequestId = "verify-image-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val generationLease = tryAcquireObservedImageGenerationLease(verificationRequestId)
        if (generationLease == null) {
            store.update {
                it.copy(statusMessage = "已有图片任务正在运行，请等待完成后再校验模型。")
            }
            return@launch
        }
        try {
            model.localImageStructuralReadinessMessage()?.let { readiness ->
                store.update {
                    it.copy(statusMessage = "图像引擎校验未开始：$readiness")
                }
                return@launch
            }
            busy("正在校验 ${model.displayName}...")
            val primary = File(model.path)
            val readiness = model.localImageReadinessMessage()
            val structuralReadiness = model.localImageStructuralReadinessMessage()
            val readable = model.configured && primary.isFile && primary.canRead()
            var qnnExecution: QnnImageExecutionEvidence? = null
            var verification = when {
                !readable -> false to "主模型文件不可读，请重新导入或重新下载。"
                model.runtime == LocalImageRuntime.MNN_DIFFUSION && structuralReadiness != null ->
                    false to structuralReadiness
                model.runtime == LocalImageRuntime.MNN_DIFFUSION ->
                    verifyMnnDiffusionImageRuntime(localImageWorkerClient, model)
                model.runtime == LocalImageRuntime.QNN_HTP && structuralReadiness != null ->
                    false to structuralReadiness
                model.runtime == LocalImageRuntime.QNN_HTP ->
                    verifyQnnImageRuntime(localImageWorkerClient, model).also { qnnExecution = it.execution }.verification
                readiness != null -> false to readiness
                else -> {
                    val componentText = if (model.componentCount > 1) "，组件 ${model.componentCount} 个" else ""
                    true to "主模型可读$componentText，可用于图片页本地生图。"
                }
            }
            val qnnPersistence = qnnVerificationPersistence(application, model, verification, qnnExecution)
            verification = qnnPersistence.verification
            val verifiedModel = model.copy(
                verificationStatus = verification.toLocalImageVerificationStatus(model.runtime, model.family),
                verificationMessage = verification.second,
                verifiedAt = System.currentTimeMillis(),
                qnnVerificationStamp = qnnPersistence.stamp
            )
            val models = localImageModelStore.updateModel(verifiedModel)
            val qnnVerificationCurrentByModelId = currentQnnImageVerificationByModelId(application, models)
            val selectedId = selectedReadyLocalImageModelId(
                models = models,
                qnnVerificationCurrentByModelId = qnnVerificationCurrentByModelId,
                preferredId = localImageModelStore.loadSelectedModelId()
            )
            if (selectedId != localImageModelStore.loadSelectedModelId()) {
                localImageModelStore.saveSelectedModelId(selectedId)
            }
            val message = if (verification.first) {
                "图像引擎校验通过：${verification.second}"
            } else {
                "图像引擎校验失败：${verification.second}"
            }
            managedRuntimeReadinessRefreshGate.invalidate()
            store.update {
                it.copy(
                    localImageModels = models,
                    qnnImageVerificationCurrentByModelId = qnnVerificationCurrentByModelId,
                    selectedLocalImageModelId = selectedId,
                    selectedImageBackend = if (selectedId == null && it.selectedImageBackend == ImageBackend.LOCAL) {
                        ImageBackend.CLOUD
                    } else {
                        it.selectedImageBackend
                    },
                    busy = false,
                    statusMessage = message
                )
            }
        } finally {
            check(releaseObservedImageGenerationLease(generationLease)) {
                "Image verification lease was replaced before request completion."
            }
        }
    }
}

internal fun verifyModelFor(
    store: UiStateStore,
    application: Application,
    modelStore: ModelStoreRepository,
    managedRuntimeReadinessRefreshGate: ManagedRuntimeReadinessRefreshGate,
    publishManagedChatCatalogAfterValidation: () -> ManagedChatCatalogSnapshot,
    busy: (String) -> Unit,
    launchModelOperation: (String, suspend CoroutineScope.() -> Unit) -> Job,
    model: ModelManifest
) {
    launchModelOperation("模型操作未完成") {
        busy("正在校验 ${model.displayName}...")
        val result = modelStore.validateForLoad(model.id)
        val validatedCatalog = publishManagedChatCatalogAfterValidation()
        val validatedModel = validatedCatalog.models.firstOrNull { it.id == model.id } ?: model
        val qairtDryRun = if (
            result.canLoad &&
            validatedModel.runtime == ChatModelRuntime.GENIEX_QAIRT &&
            validatedModel.id !in validatedCatalog.qairtVerifiedLocalModelIds
        ) {
            store.update {
                it.copy(statusMessage = "模型包校验通过，正在独立进程执行 QAIRT create/generate/destroy 诊断…")
            }
            runCatching {
                val params = store.state.value.params
                QairtDryRunWorkerClient(application).certify(
                    modelId = validatedModel.id,
                    nCtx = params.nCtx,
                    nThreads = params.nThreads
                ) { progress ->
                    store.update { state ->
                        if (state.busy) state.copy(statusMessage = progress.message) else state
                    }
                }
            }
        } else {
            null
        }
        val localModels = modelStore.listModels()
        val qairtVerifiedIds = currentQairtVerifiedLocalModelIds(application, localModels)
        val qairtVerified = validatedModel.id in qairtVerifiedIds
        managedRuntimeReadinessRefreshGate.invalidate()
        store.update {
            it.copy(
                busy = false,
                models = localModels,
                qairtVerifiedLocalModelIds = qairtVerifiedIds,
                qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                    models = localModels,
                    verifiedLocalModelIds = qairtVerifiedIds,
                    recommendations = it.recommendedRemoteModels
                ),
                statusMessage = when {
                    !result.canLoad -> "模型校验失败：${result.message}"
                    qairtDryRun?.isFailure == true ->
                        "QAIRT 运行诊断失败：${qairtDryRun.exceptionOrNull()?.message.orEmpty()}。仍可直接尝试加载，实际 native 结果决定兼容性。"
                    qairtDryRun?.isSuccess == true && !qairtVerified ->
                        "QAIRT 运行诊断完成但未写入证据记录；这不会阻止模型加载。"
                    validatedModel.runtime == ChatModelRuntime.GENIEX_QAIRT && !qairtVerified ->
                        "模型包完整性校验通过；可直接在隔离 native worker 中尝试加载。"
                    qairtDryRun?.isSuccess == true ->
                        "QAIRT 运行诊断通过：已确认骁龙 NPU、固定回答与干净卸载。"
                    validatedModel.runtime == ChatModelRuntime.GENIEX_QAIRT ->
                        "模型包完整性校验通过，且已有当前设备运行诊断证据。"
                    else -> "模型校验通过：${result.message}"
                }
            )
        }
    }
}
