package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.EngineLifecycleLease
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * C2/C0-a：生图准入与执行的唯一 owner。enqueueImageGeneration 的 673 行体自 MainViewModel 移入
 * （体逐字；VM 私有面经本 host 注入：状态/写入/scope/application/运行态字段/锁/资产与 worker 协作）。
 * 三处端口实现与两个 VM 直连调用点收敛到同一 ImageGenerationOwner 实例。
 *
 * ⚠ 过渡 adapter（用户 2026-10-06 插队裁定）：本 host 的 VM 侧实现为匿名 `object : ImageGenerationHost`
 * 反向捕获 `this@MainViewModel`，属方案 §五 禁止清单的过渡形态。退出条件 = ImageGenerationOwner 改为
 * `McaAppContainer` 工厂创建、构造注入真实依赖（UiStateStore/scope/engine/资产与 worker 协作者），
 * VM 仅从容器取出 owner 并保留 intent 委托；随之删除本 host 接口与 VM 内匿名装配。
 * 期限 = C2 序列内、C3 开工前（蓝图样板为 C2-b TuningOwner，落地后即回改本片）。
 */
internal interface ImageGenerationHost {
    val scope: CoroutineScope

    val application: Application

    fun currentState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    fun showStatus(message: String?)

    val imageGenerationJob: Job?

    fun setImageGenerationJob(job: Job?)

    var activeImageGenerationJobId: String?

    var activeImageGenerationBackend: ImageBackend?

    var activeImageGenerationModelId: String?

    val localImageTextualInversionLifecycleLock: Any

    val localImageLoraLifecycleLock: Any

    val localImageLoraStore: LocalImageLoraStore

    val localImageWorkerClient: LocalImageWorkerClient

    val imageAssetDirectory: File

    val imageLibraryCommitPort: ImageLibraryCommitPort

    val chatImageRequestPort: ChatImageRequestPort

    val chatImageCommitJournal: ChatImageCommitJournal

    suspend fun awaitImageLibraryStartupReconciliation()

    fun tryAcquireObservedImageGenerationLease(requestId: String): LocalImageGenerationCoordinator.Lease?

    fun releaseObservedImageGenerationLease(lease: LocalImageGenerationCoordinator.Lease): Boolean

    fun resolveCurrentLocalImageLoras(
        selections: List<Pair<String, Double>>,
        records: List<LocalImageLoraRecord>
    ): List<LocalImagePreparedLora>

    fun resolveCurrentLocalImageTextualInversionIds(
        ids: List<String>,
        records: List<TextualInversionArtifact>
    ): List<String>

    suspend fun prepareLocalImagePromptExecution(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        captured: LocalImagePromptExecution?
    ): LocalImagePromptExecution

    suspend fun prepareLocalImageRuntimeMemory(): EngineLifecycleLease

    fun startLocalImageGenerationWatchdog(jobId: String)

    fun publishLocalImagePreview(jobId: String, progress: LocalImageProgress): PublishedLocalImagePreview?

    fun cleanupLocalImagePreviews(jobId: String)

    suspend fun createCloudGeneratedImageAssets(
        request: CloudImageRequest,
        config: CloudApiConfig,
        chatSessionId: String? = null,
        generationMetadata: ImageGenerationHistoryMetadata
    ): List<ImageAssetRecord>

    suspend fun createLocalGeneratedImageAsset(
        requestId: String,
        prompt: String,
        model: LocalImageModelRecord,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        chatSessionId: String? = null,
        generationMetadata: ImageGenerationHistoryMetadata,
        batchLineage: ImageGenerationBatchLineage? = null,
        outputLineages: List<ImageGenerationBatchLineage>,
        onProgress: (LocalImageProgress) -> Unit = {}
    ): List<ImageAssetRecord>

    fun completeAssistantImageToolTurnForJob(jobId: String)
}

/**
 * C2/C0-a：生图唯一执行 owner。submit 即原 MainViewModel.enqueueImageGeneration 体（逐字等价：
 * 仅 VM 私有成员经 host 显式访问；两处单参调用的默认记录参数改为调用点显式传当前状态值）。
 */
internal class ImageGenerationOwner(private val host: ImageGenerationHost) {
    fun submit(
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
    ): Boolean {
        val cleanPrompt = (jobSnapshot?.prompt ?: prompt).trim()
        if (cleanPrompt.isBlank()) {
            host.showStatus("请输入图片描述")
            return false
        }
        if (cleanPrompt.length > LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS) {
            host.updateUiState {
                it.copy(
                    statusMessage = "图片提示词超过 " +
                        "${LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS} 字符上限。"
                )
            }
            return false
        }
        if (host.imageGenerationJob?.isActive == true) {
            host.showStatus("已有图片生成任务正在运行，请先停止当前任务")
            return false
        }
        val jobId = requestedJobId ?: "ui-img-${UUID.randomUUID()}"
        val enqueueState = host.currentState()
        val requestedBackend = jobSnapshot?.backend ?: enqueueState.selectedImageBackend
        val requestedLocalModel = if (requestedBackend == ImageBackend.LOCAL) {
            jobSnapshot?.localModelSnapshot ?: enqueueState.selectedLocalImageModel()
        } else {
            null
        }
        if (enqueueState.isGenerating && requestedBackend == ImageBackend.LOCAL &&
            enqueueState.selectedChatBackend == ChatBackend.LOCAL
        ) {
            host.updateUiState {
                it.copy(statusMessage = "本地聊天正在生成，请等本轮结束后再启动本地生图，避免设备内存冲突。")
            }
            return false
        }
        val requestedCloudConfig = if (requestedBackend == ImageBackend.CLOUD) {
            jobSnapshot?.cloudConfigSnapshot ?: enqueueState.selectedImageCloudConfig()?.normalized()
        } else {
            null
        }
        if (requestedBackend == ImageBackend.LOCAL && requestedLocalModel == null) {
            host.showStatus("请先在模型管理的本地页导入并选择图像生成引擎。")
            return false
        }
        requestedLocalModel?.localImageStructuralReadinessMessage()?.let { readiness ->
            host.updateUiState {
                it.copy(statusMessage = "图片生成尚未启动：$readiness")
            }
            return false
        }
        if (requestedBackend == ImageBackend.CLOUD && requestedCloudConfig == null) {
            host.showStatus("请先在模型管理的云端页接入并选择图像生成模型。")
            return false
        }
        val requestedModelId = jobSnapshot?.modelId
            ?: requestedLocalModel?.id
            ?: enqueueState.selectedCloudImageModelId
        val requestedModelName = jobSnapshot?.modelName
            ?: requestedLocalModel?.displayName
            ?: requestedCloudConfig?.displayName?.takeIf(String::isNotBlank)
            ?: requestedCloudConfig?.imageModel.orEmpty()
        val owningChatSessionId = jobSnapshot?.chatSessionId
            ?: requestedChatSessionId
            ?: enqueueState.activeChatSessionId
        val owningChatMessageId = jobSnapshot?.chatMessageId ?: requestedChatMessageId
        val baseOptions = jobSnapshot?.options ?: options
        if (requestedBackend == ImageBackend.LOCAL &&
            baseOptions.textualInversionIds.isNotEmpty() &&
            enqueueState.localImageTextualInversionLoading
        ) {
            host.updateUiState {
                it.copy(statusMessage = "Textual Inversion 库仍在加载，请稍后重试。")
            }
            return false
        }
        val currentOptions = if (requestedBackend == ImageBackend.LOCAL) {
            try {
                baseOptions.copy(
                    loras = host.resolveCurrentLocalImageLoras(
                        baseOptions.loras.map { adapter -> adapter.id to adapter.multiplier },
                        host.currentState().localImageLoras
                    ),
                    textualInversionIds = host.resolveCurrentLocalImageTextualInversionIds(
                        baseOptions.textualInversionIds,
                        host.currentState().localImageTextualInversions
                    )
                ).also { resolvedOptions ->
                    requireTextualInversionPromptTriggers(
                        prompt = cleanPrompt,
                        negativePrompt = resolvedOptions.negativePrompt,
                        ids = resolvedOptions.textualInversionIds,
                        records = enqueueState.localImageTextualInversions
                    )
                }
            } catch (error: Exception) {
                host.updateUiState {
                    it.copy(statusMessage = "图片任务的本地扩展不可用：${error.message ?: "请重新选择"}")
                }
                return false
            }
        } else {
            if (baseOptions.loras.isNotEmpty() || baseOptions.textualInversionIds.isNotEmpty() ||
                baseOptions.ultraFix != null
            ) {
                host.showStatus("云端图片连接器不接受本地模型扩展。")
                return false
            }
            baseOptions
        }
        // Preview is an explicit per-job UI/API option. Do not silently turn it back on here:
        // retries and history recreation must preserve the captured job snapshot exactly.
        var queuedOptions = currentOptions
        val queuedInputDraft = jobSnapshot?.inputDraft ?: inputDraft
        if (requestedBackend == ImageBackend.LOCAL) {
            try {
                val resolvedSampler = requireNotNull(requestedLocalModel).validateProductTaskSampler(
                    taskMode = queuedInputDraft.taskMode,
                    sampleMethod = queuedOptions.sampleMethod
                )
                // Capture the effective task-aware sampler in the retryable job spec. In
                // particular, an omitted sampler on a generic shared-QNN img2img request must
                // not fall through to that profile's legacy PNDM default.
                queuedOptions = queuedOptions.copy(
                    sampleMethod = imageSchedulerProductName(resolvedSampler)
                )
            } catch (error: Exception) {
                host.updateUiState {
                    it.copy(
                        statusMessage = "图片采样器不可用：${error.message ?: "请重新选择采样器"}"
                    )
                }
                return false
            }
        }
        var queuedJobSpec = (jobSnapshot ?: ImageGenerationJobSpec(
            prompt = cleanPrompt,
            backend = requestedBackend,
            localModelSnapshot = requestedLocalModel,
            cloudConfigSnapshot = requestedCloudConfig,
            modelId = requireNotNull(requestedModelId) { "The queued image model id is missing." },
            modelName = requestedModelName,
            inputDraft = queuedInputDraft,
            options = queuedOptions,
            chatSessionId = owningChatSessionId,
            chatMessageId = owningChatMessageId
        )).copy(
            localModelSnapshot = requestedLocalModel,
            cloudConfigSnapshot = requestedCloudConfig,
            options = queuedOptions,
            chatSessionId = owningChatSessionId,
            chatMessageId = owningChatMessageId
        )
        val generationLease = host.tryAcquireObservedImageGenerationLease(jobId)
        if (generationLease == null) {
            host.updateUiState {
                it.copy(statusMessage = "已有 UI 或 Local API 图片任务正在运行，请等待完成或先停止当前任务")
            }
            return false
        }
        val textualInversionReservationError =
            synchronized(host.localImageTextualInversionLifecycleLock) {
                val state = host.currentState()
                val ids = queuedOptions.textualInversionIds.toSet()
                when {
                    ids.any(state.deletingLocalImageTextualInversionIds::contains) ->
                        "所选 Textual Inversion 正在删除，请重新选择。"
                    ids.any { requestedId ->
                        state.localImageTextualInversions.none { it.id == requestedId }
                    } -> "所选 Textual Inversion 已删除，请重新选择。"
                    else -> {
                        host.updateUiState {
                            it.copy(activeLocalImageTextualInversionIds = ids)
                        }
                        null
                    }
                }
            }
        if (textualInversionReservationError != null) {
            check(host.releaseObservedImageGenerationLease(generationLease)) {
                "UI image generation lease was replaced during Textual Inversion reservation."
            }
            host.updateUiState {
                it.copy(
                    activeLocalImageTextualInversionIds = emptySet(),
                    localImageTextualInversionMessage = textualInversionReservationError,
                    statusMessage = textualInversionReservationError
                )
            }
            return false
        }
        val leasedLoraRefreshError = synchronized(host.localImageLoraLifecycleLock) {
            runCatching {
                val currentLoras = host.localImageLoraStore.load()
                queuedOptions = queuedOptions.copy(
                    loras = host.resolveCurrentLocalImageLoras(
                        queuedOptions.loras.map { adapter -> adapter.id to adapter.multiplier },
                        currentLoras
                    )
                )
                queuedJobSpec = queuedJobSpec.copy(options = queuedOptions)
                host.updateUiState {
                    it.copy(
                        localImageLoras = currentLoras,
                        activeLocalImageLoraIds = queuedOptions.loras
                            .mapTo(mutableSetOf()) { adapter -> adapter.id }
                    )
                }
            }.exceptionOrNull()
        }
        if (leasedLoraRefreshError != null) {
            check(host.releaseObservedImageGenerationLease(generationLease)) {
                "UI image generation lease was replaced during LoRA refresh."
            }
            synchronized(host.localImageLoraLifecycleLock) {
                host.updateUiState {
                    it.copy(
                        activeLocalImageLoraIds = emptySet(),
                        activeLocalImageTextualInversionIds = emptySet(),
                        statusMessage = "图片任务的 LoRA 不可用：${leasedLoraRefreshError.message ?: "请重新选择"}"
                    )
                }
            }
            return false
        }
        val localBatchPlan = requestedLocalModel?.let { model ->
            runCatching {
                planLocalImageBatch(
                    parentRequestId = jobId,
                    runtime = model.runtime,
                    requestedOptions = queuedOptions
                )
            }.getOrElse { error ->
                check(host.releaseObservedImageGenerationLease(generationLease)) {
                    "UI image generation lease was replaced during batch planning."
                }
                synchronized(host.localImageLoraLifecycleLock) {
                    host.updateUiState {
                        it.copy(
                            activeLocalImageLoraIds = emptySet(),
                            activeLocalImageTextualInversionIds = emptySet(),
                            statusMessage = "图片批次参数无效：${error.message ?: "请检查数量和 seed"}"
                        )
                    }
                }
                return false
            }
        }
        // Nothing after the lease-protected refresh may observe another value. Keeping these
        // snapshots immutable also makes the coroutine capture explicit for retry/history parity.
        val executionOptions = localBatchPlan?.parentOptions ?: queuedOptions
        val executionJobSpec = queuedJobSpec.copy(options = executionOptions)
        host.activeImageGenerationJobId = jobId
        host.activeImageGenerationBackend = requestedBackend
        host.activeImageGenerationModelId = requestedModelId
        host.updateUiState { state ->
            state.copy(
                imageJobs = (
                    listOf(
                        ImageGenerationJobRecord(
                            id = jobId,
                            prompt = cleanPrompt,
                            status = ImageGenerationStatusRecord.QUEUED,
                            backend = requestedBackend,
                            modelId = requestedModelId,
                            modelName = requestedModelName,
                            spec = executionJobSpec,
                            startedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
                            message = if (requestedBackend == ImageBackend.LOCAL) "等待本地生图" else "等待云端生图"
                        )
                    ) + state.imageJobs
                ).take(8),
                statusMessage = "图片任务已排队"
            )
        }
        // Protect preparation, worker callbacks and the final library commit,
        // including cloud images which do not have a local worker service.
        val foregroundLease = McaGenerationForegroundService.acquire(
            host.application,
            McaGenerationForegroundService.KIND_IMAGE
        )
        val executionJob = host.scope.launch(Dispatchers.IO) {
            var preparedJobSpec = executionJobSpec
            var chatRuntimeLease: com.muyuchat.core.engine.EngineLifecycleLease? = null
            try {
                host.awaitImageLibraryStartupReconciliation()
                host.updateUiState { state ->
                    state.copy(
                        imageJobs = state.imageJobs.updateImageJob(
                            jobId,
                            ImageGenerationStatusRecord.GENERATING,
                            if (requestedBackend == ImageBackend.LOCAL) "正在调用本地图像生成引擎，本地生成可能需要数分钟" else "正在调用云端图片模型"
                        )
                    )
                }
                if (requestedBackend == ImageBackend.LOCAL) {
                    val model = requireNotNull(requestedLocalModel) {
                        "The queued local image model snapshot is missing."
                    }
                    model.localImageStructuralReadinessMessage()?.let { readiness ->
                        val message = "图片生成尚未启动：$readiness"
                        host.updateUiState { state ->
                            val updatedJobs = state.imageJobs.withLocalImagePromptPreparationFailureIfActive(
                                activeJobId = host.activeImageGenerationJobId,
                                jobId = jobId,
                                message = message
                            )
                            if (updatedJobs === state.imageJobs) state
                            else state.copy(
                                imageJobs = updatedJobs,
                                statusMessage = message
                            )
                        }
                        return@launch
                    }
                    val promptExecution = try {
                        host.prepareLocalImagePromptExecution(
                            model = model,
                            prompt = executionJobSpec.prompt,
                            options = executionJobSpec.options,
                            captured = executionJobSpec.promptExecution
                        ).also { execution ->
                            requireTextualInversionPromptTriggers(
                                prompt = execution.effectivePrompt,
                                negativePrompt = execution.effectiveNegativePrompt,
                                ids = executionJobSpec.options.textualInversionIds,
                                records = host.currentState().localImageTextualInversions
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        val message = localImagePromptPreparationFailureMessage(error)
                        host.updateUiState { state ->
                            val updatedJobs =
                                state.imageJobs.withLocalImagePromptPreparationFailureIfActive(
                                    activeJobId = host.activeImageGenerationJobId,
                                    jobId = jobId,
                                    message = message
                                )
                            if (updatedJobs === state.imageJobs) {
                                state
                            } else {
                                state.copy(
                                    imageJobs = updatedJobs,
                                    statusMessage = "图片生成失败：$message"
                                )
                            }
                        }
                        return@launch
                    }
                    preparedJobSpec = executionJobSpec.copy(promptExecution = promptExecution)
                    currentCoroutineContext().ensureActive()
                    chatRuntimeLease = host.prepareLocalImageRuntimeMemory()
                    currentCoroutineContext().ensureActive()
                    host.localImageWorkerClient.begin(model.runtime)
                    currentCoroutineContext().ensureActive()
                    val translationStatusMessage = if (
                        promptExecution.method in setOf(
                            LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN,
                            LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN
                        )
                    ) {
                        val (label, translatedText) = if (promptExecution.originalPrompt.containsHanScript()) {
                            "英文译文" to promptExecution.effectivePrompt
                        } else {
                            "负向词英文译文" to promptExecution.effectiveNegativePrompt
                        }
                        val preview = translatedText
                            .replace('\n', ' ')
                            .replace('\r', ' ')
                            .trim()
                            .take(96)
                        "$label：$preview${if (translatedText.length > 96) "..." else ""}"
                    } else {
                        null
                    }
                    host.updateUiState { state ->
                        val currentJob = state.imageJobs.firstOrNull { it.id == jobId }
                        if (currentJob?.status != ImageGenerationStatusRecord.GENERATING) {
                            state
                        } else {
                            state.copy(
                                imageJobs = state.imageJobs.map { job ->
                                    if (job.id == jobId) {
                                        job.copy(
                                            spec = preparedJobSpec,
                                            message = translationStatusMessage ?: job.message
                                        )
                                    } else {
                                        job
                                    }
                                }
                            )
                        }
                    }
                    host.startLocalImageGenerationWatchdog(jobId)
                }
                val generatedImages = runCatching {
                    when (requestedBackend) {
                        ImageBackend.LOCAL -> {
                            val model = requireNotNull(requestedLocalModel) {
                                "The queued local image model snapshot is missing."
                            }
                            val batchPlan = requireNotNull(localBatchPlan) {
                                "The queued local image batch plan is missing."
                            }
                            executeLocalImageBatchPlan(
                                plan = batchPlan,
                                cancellationRequested = {
                                    host.activeImageGenerationJobId != jobId ||
                                        host.currentState().imageJobs.firstOrNull { it.id == jobId }
                                            ?.status != ImageGenerationStatusRecord.GENERATING
                                },
                                execute = { child ->
                                    host.createLocalGeneratedImageAsset(
                                        requestId = child.requestId,
                                        prompt = preparedJobSpec.effectivePrompt(),
                                        model = model,
                                        options = preparedJobSpec.effectiveOptions(child.options),
                                        inputDraft = queuedInputDraft,
                                        chatSessionId = owningChatSessionId,
                                        generationMetadata = preparedJobSpec.toHistoryMetadata(),
                                        batchLineage = child.batchLineage,
                                        outputLineages = child.outputLineages,
                                        onProgress = { childProgress ->
                                            val progress = child.parentProgress(childProgress)
                                            val message = progress.toImageGenerationMessage()
                                            val publishedPreview = host.publishLocalImagePreview(jobId, progress)
                                            host.updateUiState { state ->
                                                val job = state.imageJobs.firstOrNull { it.id == jobId }
                                                if (job?.status == ImageGenerationStatusRecord.GENERATING) {
                                                    state.copy(
                                                        imageJobs = state.imageJobs.updateImageJob(
                                                            jobId,
                                                            ImageGenerationStatusRecord.GENERATING,
                                                            message,
                                                            preview = publishedPreview
                                                        )
                                                    )
                                                } else {
                                                    state
                                                }
                                            }
                                        }
                                    )
                                },
                                onChildCompleted = { _, completedCount ->
                                    host.updateUiState { state -> state.copy(
                                        imageJobs = state.imageJobs.map { job ->
                                            if (job.id == jobId && job.status == ImageGenerationStatusRecord.GENERATING) {
                                                job.copy(
                                                    completedProgressCount = completedCount,
                                                    message = "已完成 $completedCount/${batchPlan.outputCount} 张，正在准备下一张"
                                                )
                                            } else job
                                        }
                                    ) }
                                },
                                cleanup = { candidates ->
                                    candidates.forEach { image ->
                                        image.deleteLocalCopy(host.imageAssetDirectory)
                                    }
                                }
                            )
                        }
                        ImageBackend.CLOUD -> {
                            require(queuedInputDraft.taskMode == LocalImageTaskMode.TEXT_TO_IMAGE) {
                                "The selected cloud image connector does not implement ${queuedInputDraft.taskMode.wireName}; no request was sent."
                            }
                            require(executionOptions.clipSkip == null &&
                                executionOptions.vaeTiling == null &&
                                executionOptions.preview == null
                            ) {
                                "The selected cloud image connector does not implement local advanced image controls."
                            }
                            val imageConfig = requireNotNull(requestedCloudConfig) {
                                "The queued cloud image model snapshot is missing."
                            }
                            host.createCloudGeneratedImageAssets(
                                request = CloudImageRequest(
                                    positivePrompt = cleanPrompt,
                                    negativePrompt = executionOptions.negativePrompt,
                                    count = executionOptions.batchCount,
                                    size = executionOptions.width?.let { width ->
                                        executionOptions.height?.let { height -> "$width" + "x" + "$height" }
                                    }
                                ),
                                config = imageConfig,
                                chatSessionId = owningChatSessionId,
                                generationMetadata = executionJobSpec.toHistoryMetadata()
                            )
                        }
                    }
                }.getOrElse { error ->
                    val cancelled = error is CancellationException ||
                        error is LocalImageWorkerCancelledException
                    val message = if (cancelled) {
                        "已取消图片生成"
                    } else {
                        val nativeMessage = localImageGenerationFailureMessage(error)
                        val translatedPrompt = preparedJobSpec.promptExecution
                            ?.effectivePrompt
                            ?.takeIf { it.isNotBlank() && it != preparedJobSpec.prompt }
                        if (translatedPrompt != null) {
                            "$nativeMessage\n建议使用英文提示词重试：$translatedPrompt"
                        } else {
                            nativeMessage
                        }
                    }
                    host.updateUiState { state ->
                        state.copy(
                            imageJobs = state.imageJobs.updateImageJob(
                                jobId,
                                if (cancelled) {
                                    ImageGenerationStatusRecord.CANCELLED
                                } else {
                                    ImageGenerationStatusRecord.FAILED
                                },
                                message
                            ),
                            statusMessage = if (cancelled) message else "图片生成失败：$message"
                        )
                    }
                    return@launch
                }
                // A worker/provider is allowed to return an empty list when it has
                // cancelled or failed after creating no artifact.  Treat that as a
                // normal task failure before the commit code dereferences
                // generatedImages.first(); the old path crashed the coroutine (and
                // on some native worker builds the process) while publishing the
                // result, which looked like an immediate app crash to the user.
                if (generatedImages.isEmpty()) {
                    val message = "图像引擎未返回图片文件，请检查模型完整性后重试。"
                    host.updateUiState { state ->
                        state.copy(
                            imageJobs = state.imageJobs.updateImageJob(
                                jobId,
                                ImageGenerationStatusRecord.FAILED,
                                message
                            ),
                            statusMessage = message
                        )
                    }
                    return@launch
                }
                val commitError = commitImageLibraryTransaction(
                    host.imageLibraryCommitPort, jobId, generatedImages
                )
                if (commitError != null) {
                    generatedImages.forEach { image ->
                        val rolledBack = image.deleteLocalCopy(host.imageAssetDirectory)
                        val requestId = host.currentState().imageJobs.firstOrNull { it.id == jobId }
                            ?.spec?.chatMessageId
                        if (rolledBack && !requestId.isNullOrBlank()) {
                            runCatching {
                                host.chatImageCommitJournal.removeAfterConfirmedAssetRollback(
                                    jobId = chatImageCommitJournalJobId(jobId, image.id),
                                    requestId = requestId,
                                    rollbackConfirmed = true
                                )
                            }
                        }
                    }
                    val cancelled = commitError is CancellationException ||
                        host.currentState().imageJobs.firstOrNull { it.id == jobId }?.status ==
                        ImageGenerationStatusRecord.CANCEL_REQUESTED
                    val message = if (cancelled) {
                        "已取消图片生成"
                    } else {
                        commitError.message ?: "图片库数据库提交失败"
                    }
                    host.updateUiState { state ->
                        val job = state.imageJobs.firstOrNull { it.id == jobId }
                        if (job?.status == ImageGenerationStatusRecord.DONE) {
                            state
                        } else {
                            state.copy(
                                imageJobs = state.imageJobs.updateImageJob(
                                    jobId,
                                    if (cancelled) {
                                        ImageGenerationStatusRecord.CANCELLED
                                    } else {
                                        ImageGenerationStatusRecord.FAILED
                                    },
                                    message
                                ),
                                statusMessage = if (cancelled) {
                                    message
                                } else {
                                    "图片生成完成，但保存失败：$message"
                                }
                            )
                        }
                    }
                    return@launch
                }
            } finally {
                withContext(NonCancellable) {
                    // The image client unbinds before returning, but the disposable worker may
                    // still hold native mappings until its process dies. Confirm that boundary
                    // before allowing the chat engine to map its weights again.
                    if (chatRuntimeLease != null) {
                        runCatching { host.localImageWorkerClient.awaitOwnedWorkerRelease() }
                        chatRuntimeLease?.release()
                    }
                }
                host.cleanupLocalImagePreviews(jobId)
            }
        }
        host.setImageGenerationJob(executionJob)
        executionJob.invokeOnCompletion { completion ->
            try {
                synchronized(host.localImageLoraLifecycleLock) {
                    val ownsActiveGeneration = host.activeImageGenerationJobId == jobId
                    if (ownsActiveGeneration) {
                        host.activeImageGenerationJobId = null
                        host.activeImageGenerationBackend = null
                        host.activeImageGenerationModelId = null
                    }
                    host.updateUiState { state ->
                        val job = state.imageJobs.firstOrNull { it.id == jobId }
                        val status = when {
                            job == null || job.status.terminal -> null
                            completion is CancellationException -> ImageGenerationStatusRecord.CANCELLED
                            else -> ImageGenerationStatusRecord.FAILED
                        }
                        val fallbackMessage = when {
                            completion is CancellationException -> "已取消图片生成"
                            completion != null -> "图片任务异常结束：${completion.message ?: completion::class.java.simpleName}"
                            else -> "图片任务提前结束，未返回生成结果。"
                        }
                        state.copy(
                            activeLocalImageLoraIds = if (ownsActiveGeneration) emptySet() else state.activeLocalImageLoraIds,
                            activeLocalImageTextualInversionIds = if (ownsActiveGeneration) {
                                emptySet()
                            } else {
                                state.activeLocalImageTextualInversionIds
                            },
                            imageJobs = if (status != null) {
                                state.imageJobs.updateImageJob(jobId, status, fallbackMessage)
                            } else {
                                state.imageJobs
                            },
                            statusMessage = if (status == ImageGenerationStatusRecord.FAILED) {
                                fallbackMessage
                            } else {
                                state.statusMessage
                            }
                        )
                    }
                    check(host.releaseObservedImageGenerationLease(generationLease)) {
                        "UI image generation lease was replaced before request completion."
                    }
                }
                persistFinalChatImageRequest(host.chatImageRequestPort, jobId)
                host.completeAssistantImageToolTurnForJob(jobId)
                if (host.imageGenerationJob === executionJob) host.setImageGenerationJob(null)
            } finally {
                McaGenerationForegroundService.release(host.application, foregroundLease)
            }
        }
        return true
    }
}

// R3/C2: 以下 4 个扩展原为 MainViewModel 类成员，随 enqueue 体移至顶层 internal 函数（同包 VM 与 owner 共用），体逐字等价。
internal fun MainUiState.selectedLocalImageModel(): LocalImageModelRecord? =
    localImageModels.firstOrNull { it.id == selectedLocalImageModelId && it.configured }


internal fun MainUiState.selectedImageCloudConfig(): CloudApiConfig? =
    cloudModels
        .firstOrNull { it.id == selectedCloudImageModelId && it.kind == CloudModelKind.IMAGE }
        ?.toImageConfig()


internal fun List<ImageGenerationJobRecord>.updateImageJob(
    jobId: String,
    status: ImageGenerationStatusRecord,
    message: String,
    imageAssetId: String? = null,
    preview: PublishedLocalImagePreview? = null,
    imageAssetIds: List<String> = emptyList()
): List<ImageGenerationJobRecord> =
    map { job ->
        if (job.id == jobId) {
            job.withCommittedImageJobUpdate(
                status = status,
                message = message,
                imageAssetId = imageAssetId ?: job.imageAssetId,
                preview = preview,
                imageAssetIds = imageAssetIds
            )
        } else {
            job
        }
    }

internal fun LocalImageProgress.toImageGenerationMessage(): String {
    val elapsedSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val progress = if (steps > 0) "第 ${step.coerceIn(0, steps)}/$steps 步" else "准备中"
    val size = if (width > 0 && height > 0) " · ${width}x$height" else ""
    val threadText = if (threads > 0) " · ${threads} 线程" else ""
    val batchPrefix = message.substringBefore(" · ")
        .takeIf { prefix -> Regex("^第 [1-8]/[1-8] 张$").matches(prefix) }
    val detailMessage = batchPrefix
        ?.let { prefix -> message.removePrefix("$prefix · ") }
        ?: message
    val status = when {
        cancelRequested || phase == "cancelling" -> "正在停止本地生图 · ${elapsedSeconds}s"
        phase == "loading" -> "正在加载本地图像引擎 · ${elapsedSeconds}s$threadText"
        phase == "conditioning" -> "正在编码提示词 · ${elapsedSeconds}s$threadText"
        phase == "encoding" -> "正在准备初始图像 · ${elapsedSeconds}s$size$threadText"
        phase == "decoding" -> "正在解码图片 · ${elapsedSeconds}s$size"
        phase == "preparing" -> "正在准备采样 · ${elapsedSeconds}s$size$threadText"
        phase == "sampling" && step <= 0 -> {
            if (elapsedSeconds >= 60L) {
                "首步计算中 · ${elapsedSeconds}s$size$threadText"
            } else {
                "即将开始采样 · ${elapsedSeconds}s$size$threadText"
            }
        }
        phase == "sampling" -> "本地生图 $progress · ${elapsedSeconds}s$size$threadText"
        phase == "writing" -> "正在写入图片 · ${elapsedSeconds}s"
        phase == "completed" -> "本地生图完成 · ${elapsedSeconds}s"
        phase == "failed" -> detailMessage.ifBlank { "本地生图失败" }
        else -> "正在准备本地生图 · ${elapsedSeconds}s$threadText"
    }
    return appendLocalImagePreviewDegradationMessage(
        message = batchPrefix?.let { "$it · $status" } ?: status,
        previewFailureCode = previewFailureCode
    )
}


