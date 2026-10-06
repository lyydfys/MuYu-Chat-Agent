package com.muyuchat.mca

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The owner seam for image-asset creation: the upscaled write, the re-created asset and the
 * local-image-model selection that depends on both.
 *
 * No lock, no job and no scope. The two active-generation identities are read through accessors
 * because the ViewModel still owns those `@Volatile` fields -- and this family only reads them,
 * to decide whether a switch is currently allowed.
 */
internal interface ImageAssetCreationPort {

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    val context: Application

    /** Runs [block] while holding the image-asset write lock; the lock never leaves the ViewModel. */
    suspend fun <T> withImageAssetWriteLock(block: suspend () -> T): T

    val imageAssetDirectory: File

    val localImageModelStore: LocalImageModelStore

    fun showStatus(message: String?)

    fun invalidatePendingChatImagePreparation()

    fun canReadGenerationHistoryInput(reference: String): Boolean

    fun rejectImageModelSwitchWhileGenerationIsActive(): Boolean

    fun enqueueImageGeneration(
prompt: String,
inputDraft: LocalImageInputDraft,
options: LocalImageGenerationOptions,
jobSnapshot: ImageGenerationJobSpec?,
// Keep the generated UI id in the function body so callers that retry
// or continue an assistant tool turn can provide their own stable id.
// This also keeps the public admission signature free of interpolation
// braces, which makes source-level lifecycle checks unambiguous.
requestedJobId: String? = null,
requestedChatSessionId: String? = null,
requestedChatMessageId: String? = null
): Boolean

    fun resolveCurrentLocalImageLoras(
selections: List<Pair<String, Double>>,
records: List<LocalImageLoraRecord>
): List<LocalImagePreparedLora>

    fun activeImageGenerationJobId(): String?

    fun activeImageGenerationModelId(): String?
}

    internal fun recreateAsset(
    port: ImageAssetCreationPort,imageId: String) {
        val state = port.uiState()
        val asset = state.images.firstOrNull { it.id == imageId }
        val history = ImageGenerationHistoryMetadata.fromJsonOrNull(asset?.generationMetadataJson)
        if (asset == null || history == null) {
            port.showStatus("这张图片没有可复现的完整生成参数。")
            return
        }
        if (!history.canRecreate()) {
            port.updateUiState {
                it.copy(statusMessage = "历史记录缺少当前生成方式必需的输入图片，无法直接重现。")
            }
            return
        }
        val snapshot = when (history.backend) {
            ImageBackend.LOCAL -> {
                val model = state.localImageModels.firstOrNull { it.matchesCatalogId(history.modelId) }
                if (model == null) {
                    port.updateUiState {
                        it.copy(statusMessage = "原本地模型已删除；重新导入同一模型后才能按原参数生成。")
                    }
                    return
                }
                val resolvedLoras = runCatching {
                    port.resolveCurrentLocalImageLoras(
                        history.loras.map { selection -> selection.id to selection.multiplier },
                        records = port.uiState().localImageLoras
                    )
                }.getOrElse { error ->
                    port.updateUiState {
                        it.copy(
                            statusMessage = "历史任务需要的 LoRA 已缺失：${error.message ?: "请重新导入后再试"}"
                        )
                    }
                    return
                }
                ImageGenerationJobSpec(
                    prompt = history.requestPrompt,
                    backend = ImageBackend.LOCAL,
                    localModelSnapshot = model,
                    modelId = model.id,
                    modelName = history.modelName,
                    inputDraft = history.inputDraft,
                    options = history.options.copy(loras = resolvedLoras),
                    promptExecution = history.promptExecution?.takeIf { execution ->
                        execution.method.isReusableFromImageHistory()
                    },
                    chatSessionId = asset.chatSessionId
                )
            }
            ImageBackend.CLOUD -> {
                val config = state.cloudModels
                    .firstOrNull { it.id == history.modelId && it.kind == CloudModelKind.IMAGE }
                    ?.toImageConfig()
                    ?.normalized()
                if (config == null) {
                    port.updateUiState {
                        it.copy(statusMessage = "原云端图片模型已移除；恢复同一连接后才能重新生成。")
                    }
                    return
                }
                ImageGenerationJobSpec(
                    prompt = history.requestPrompt,
                    backend = ImageBackend.CLOUD,
                    cloudConfigSnapshot = config,
                    modelId = history.modelId,
                    modelName = history.modelName,
                    inputDraft = LocalImageInputDraft(),
                    options = LocalImageGenerationOptions(),
                    chatSessionId = asset.chatSessionId
                )
            }
        }
        val unreadableInput = history.requiredContentInputReferences().firstOrNull { reference ->
            !port.canReadGenerationHistoryInput(reference)
        }
        if (unreadableInput != null) {
            port.updateUiState {
                it.copy(statusMessage = "历史输入图片的读取权限已失效，请重新选择输入后再生成。")
            }
            return
        }
        port.enqueueImageGeneration(
            prompt = snapshot.prompt,
            inputDraft = snapshot.inputDraft,
            options = snapshot.options,
            jobSnapshot = snapshot
        )
    }

    internal fun applyLocalImageModelSelection(
    port: ImageAssetCreationPort,
    scope: CoroutineScope,modelId: String) {
        if (port.rejectImageModelSwitchWhileGenerationIsActive()) return
        port.invalidatePendingChatImagePreparation()
        scope.launch(Dispatchers.IO) {
            if (port.rejectImageModelSwitchWhileGenerationIsActive()) return@launch
            val model = port.uiState().localImageModels.firstOrNull { it.matchesCatalogId(modelId) }
            if (model == null) {
                port.showStatus("未找到本地图像生成引擎")
                return@launch
            }
            val qnnVerificationCurrentByModelId = currentQnnImageVerificationByModelId(port.context, listOf(model))
            model.localImageReadinessForUi(qnnVerificationCurrentByModelId[model.id])?.let { message ->
                port.updateUiState {
                    it.copy(
                        qnnImageVerificationCurrentByModelId = it.qnnImageVerificationCurrentByModelId +
                            qnnVerificationCurrentByModelId,
                        statusMessage = message
                    )
                }
                return@launch
            }
            val diagnostic = model.localImageVerificationDiagnosticMessage()
            val runningModelName = port.activeImageGenerationModelId()
                ?.let { activeId -> port.uiState().localImageModels.firstOrNull { it.matchesCatalogId(activeId) } }
                ?.displayName
            port.localImageModelStore.saveSelectedModelId(model.id)
            port.localImageModelStore.saveSelectedBackend(ImageBackend.LOCAL)
            port.updateUiState {
                it.copy(
                    qnnImageVerificationCurrentByModelId = it.qnnImageVerificationCurrentByModelId +
                        qnnVerificationCurrentByModelId,
                    selectedLocalImageModelId = model.id,
                    selectedImageBackend = ImageBackend.LOCAL,
                    statusMessage = buildString {
                        append("图片页已切换到本地生图：${model.displayName}")
                        if (port.activeImageGenerationJobId() != null) {
                            append("；当前任务仍固定使用")
                            append(runningModelName?.let { " $it" }.orEmpty())
                            append("，本次切换从下一任务生效")
                        }
                        diagnostic?.let { append("；").append(it) }
                    }
                )
            }
        }
    }

    internal suspend fun createUpscaledAsset(
    port: ImageAssetCreationPort,
        spec: ImageUpscaleJobSpec,
        result: LocalImageResult,
        requestId: String
    ): ImageAssetRecord = port.withImageAssetWriteLock {
        require(result.outputs.size == 1 && result.mimeType == "image/png") {
            "ESRGAN product upscale must publish exactly one PNG output."
        }
        val requestToken = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val timestamp = System.currentTimeMillis()
        val outputFile = writeImageAssetBytesAtomically(
            directory = port.imageAssetDirectory,
            fileName = "upscaled-image-$timestamp-$requestToken.png",
            bytes = result.bytes
        )
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(outputFile.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "Upscaled image could not be decoded after atomic publication."
            }
            val source = spec.sourceImageSnapshot
            val sourceDimensionsAreNative = source.source.startsWith("generated", true) ||
                source.source.startsWith("upscaled", true)
            val lineage = ImageUpscaleHistoryMetadata.fromNativeExecution(
                sourceImageId = source.id,
                sourceWidthHint = source.width.takeIf { sourceDimensionsAreNative } ?: 0,
                sourceHeightHint = source.height.takeIf { sourceDimensionsAreNative } ?: 0,
                upscaler = spec.upscalerSnapshot,
                targetScale = spec.targetScale,
                tileSize = spec.tileSize,
                threads = spec.threads,
                outputWidth = bounds.outWidth,
                outputHeight = bounds.outHeight,
                nativeExecutionJson = result.executionMetadataJson
            )
            val sourceMetadata = ImageGenerationHistoryMetadata.fromJsonOrNull(
                source.generationMetadataJson
            ) ?: ImageGenerationHistoryMetadata(
                backend = ImageBackend.LOCAL,
                modelId = "source-image:${source.id}",
                modelName = "原始图片",
                requestPrompt = source.prompt.trim().takeIf(String::isNotEmpty)
                    ?: source.name.ifBlank { "Source image" },
                options = LocalImageGenerationOptions(
                    width = source.width.takeIf { it > 0 },
                    height = source.height.takeIf { it > 0 }
                ),
                inputDraft = LocalImageInputDraft(),
                sourceGenerationAvailable = false
            )
            val metadata = sourceMetadata.withUpscale(lineage).toJsonString()
            ImageAssetRecord(
                id = UUID.randomUUID().toString(),
                name = "Upscaled Image ${java.text.SimpleDateFormat("HHmmss", Locale.getDefault()).format(java.util.Date(timestamp))}.png",
                uriString = Uri.fromFile(outputFile).toString(),
                source = "upscaled:ESRGAN",
                prompt = source.prompt,
                sizeBytes = outputFile.length(),
                width = bounds.outWidth,
                height = bounds.outHeight,
                generationMetadataJson = metadata,
                chatSessionId = source.chatSessionId,
                projectId = source.projectId
            )
        } catch (error: Throwable) {
            runCatching { outputFile.delete() }
            throw error
        }
    }

// R3/C3-S15：两个资产生成族助手顶层化（自 VM 迁出；体逐字，仅依赖参数化）。

internal fun canReadGenerationHistoryInputFor(application: Application, reference: String): Boolean {
    val uri = runCatching { Uri.parse(reference.trim()) }.getOrNull()
        ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
        ?: return false
    return runCatching {
        application.contentResolver.openInputStream(uri)
            ?.use { stream -> stream.read() >= 0 }
            ?: false
    }.getOrDefault(false)
}

internal fun rejectImageModelSwitchWhileGenerationIsActiveFor(
    store: UiStateStore,
    admission: RuntimeAdmissionState
): Boolean {
    val activeJobId = admission.activeImageGenerationJobId ?: return false
    val activeModelName = store.current.imageJobs
        .firstOrNull { job -> job.id == activeJobId }
        ?.modelName
        ?.takeIf(String::isNotBlank)
    store.update { state ->
        state.copy(
            statusMessage = buildString {
                append("当前图片任务已锁定")
                activeModelName?.let { append("使用 $it") }
                append("；完成或取消后再切换模型。")
            }
        )
    }
    return true
}
