package com.muyuchat.mca

import android.app.Application
import android.util.Log
import com.muyuchat.core.engine.EngineLifecycleLease
import com.muyuchat.mca.presentation.LocalApiImageOperationBinding
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


/** Local API 只能通过唯一 owner 进入生图；busy 由操作适配层映射为 409。 */
internal class LocalApiImageOperationBusyException : IllegalStateException(
    "Another UI or Local API image request is already running."
)

/**
 * ImageGenerationOwner 内部的 Local API 操作生命周期闸门。
 *
 * 这里不持有第二套生图 owner：它只把 owner 已注入的宿主能力按 Local API 请求顺序串起来，
 * 保证租约、取消所有权、活动模型身份和 worker release barrier 始终成对。
 */
internal class LocalApiImageOperationGate(
    private val hasActiveUiImageJob: () -> Boolean,
    private val tryAcquireLease: (String) -> LocalImageGenerationCoordinator.Lease?,
    private val releaseLease: (LocalImageGenerationCoordinator.Lease) -> Boolean,
    private val cancelWorker: (String) -> Boolean,
    private val awaitWorkerRelease: suspend () -> Unit,
    private val setActiveModelId: (String?) -> Unit,
    private val finishUiOwnership: () -> Unit
) {
    private val lock = Any()
    private var activeOwnership: LocalApiImageGenerationOwnership? = null
    private var workerCancellationOwnership: LocalApiImageGenerationOwnership? = null

    suspend fun <T> run(
        requestId: String,
        block: suspend (LocalApiImageOperationBinding) -> T
    ): T {
        if (hasActiveUiImageJob()) throw LocalApiImageOperationBusyException()
        val requestJob = requireNotNull(currentCoroutineContext()[Job]) {
            "Local API image generation requires a request coroutine."
        }
        val lease = tryAcquireLease(requestId) ?: throw LocalApiImageOperationBusyException()
        val ownership = LocalApiImageGenerationOwnership(
            requestId = requestId,
            requestJob = requestJob
        )
        var ownershipRegistered = false
        try {
            synchronized(lock) {
                check(activeOwnership == null) {
                    "Another Local API image request already owns cancellation."
                }
                activeOwnership = ownership
                ownershipRegistered = true
            }
            currentCoroutineContext().ensureActive()
            return block(
                object : LocalApiImageOperationBinding {
                    override fun bindModel(modelId: String) = setActiveModelId(modelId)
                    override fun isCancellationRequested(): Boolean = requestJob.isCancelled
                }
            )
        } catch (cancelled: CancellationException) {
            cancelOwnedWorker(ownership)
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                // The same native-memory release proof as the UI path must precede the next
                // lease owner, even when this request failed or was cancelled.
                runCatching { awaitWorkerRelease() }
                if (ownershipRegistered) {
                    synchronized(lock) {
                        if (workerCancellationOwnership === ownership) workerCancellationOwnership = null
                    }
                    check(unregisterOwnership(ownership)) {
                        "Local API image cancellation ownership changed before request completion."
                    }
                    setActiveModelId(null)
                    finishUiOwnership()
                }
                check(releaseLease(lease)) {
                    "Local API image generation lease was replaced before request completion."
                }
            }
        }
    }

    fun cancelActive(reason: String): Boolean = synchronized(lock) {
        val ownership = activeOwnership ?: return@synchronized false
        if (workerCancellationOwnership !== ownership) {
            cancelWorker(ownership.requestId)
            workerCancellationOwnership = ownership
        }
        ownership.requestJob.cancel(CancellationException(reason))
        true
    }

    private fun cancelOwnedWorker(ownership: LocalApiImageGenerationOwnership): Boolean =
        synchronized(lock) {
            if (activeOwnership !== ownership) return@synchronized false
            if (workerCancellationOwnership === ownership) return@synchronized true
            cancelWorker(ownership.requestId)
            workerCancellationOwnership = ownership
            true
        }

    private fun unregisterOwnership(ownership: LocalApiImageGenerationOwnership): Boolean =
        synchronized(lock) {
            if (activeOwnership !== ownership) {
                false
            } else {
                activeOwnership = null
                true
            }
        }
}

/**
 * C2/C0-a：生图唯一执行 owner。submit 保留原 MainViewModel.enqueueImageGeneration 的准入、租约与完成接线骨架；
 * 执行/提交/释放相位（T2B20/R7-C9）逐字平移至 ImageGenerationOwnerExecution.kt。
 * 逐字等价口径：仅 VM 私有成员经 host 显式访问；两处单参调用的默认记录参数改为调用点显式传当前状态值。
 */
internal class ImageGenerationOwner(private val host: ImageGenerationHost) {
    private val localApiImageOperations = LocalApiImageOperationGate(
        hasActiveUiImageJob = { host.imageGenerationJob?.isActive == true },
        tryAcquireLease = host::tryAcquireObservedImageGenerationLease,
        releaseLease = host::releaseObservedImageGenerationLease,
        cancelWorker = host.localImageWorkerClient::cancel,
        awaitWorkerRelease = host.localImageWorkerClient::awaitOwnedWorkerRelease,
        setActiveModelId = { host.activeLocalApiImageModelId = it },
        finishUiOwnership = {
            synchronized(host.localImageLoraLifecycleLock) {
                host.updateUiState {
                    it.copy(
                        activeLocalImageLoraIds = emptySet(),
                        activeLocalImageTextualInversionIds = emptySet()
                    )
                }
            }
        }
    )

    suspend fun <T> runLocalApiImageOperation(
        requestId: String,
        block: suspend (LocalApiImageOperationBinding) -> T
    ): T = localApiImageOperations.run(requestId, block)

    fun cancelActiveLocalApiImageGeneration(reason: String): Boolean =
        localApiImageOperations.cancelActive(reason)

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
            Log.i(
                IMAGE_COMMIT_LOG_TAG,
                "image_admit_reject code=empty_prompt jobId=${requestedJobId ?: "unassigned"}"
            )
            host.showStatus("请输入图片描述")
            return false
        }
        if (cleanPrompt.length > LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS) {
            Log.i(
                IMAGE_COMMIT_LOG_TAG,
                "image_admit_reject code=prompt_too_long jobId=${requestedJobId ?: "unassigned"}"
            )
            host.updateUiState {
                it.copy(
                    statusMessage = "图片提示词超过 " +
                        "${LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS} 字符上限。"
                )
            }
            return false
        }
        if (host.imageGenerationJob?.isActive == true) {
            Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=ui_job_active jobId=${requestedJobId ?: "unassigned"}")
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
            Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=chat_generating jobId=$jobId")
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
            Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=no_local_model jobId=$jobId")
            host.showStatus("请先在模型管理的本地页导入并选择图像生成引擎。")
            return false
        }
        requestedLocalModel?.localImageStructuralReadinessMessage()?.let { readiness ->
            Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=local_readiness jobId=$jobId")
            host.updateUiState {
                it.copy(statusMessage = "图片生成尚未启动：$readiness")
            }
            return false
        }
        if (requestedBackend == ImageBackend.CLOUD && requestedCloudConfig == null) {
            Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=no_cloud_config jobId=$jobId")
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
            Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=ti_loading jobId=$jobId")
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
                Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=local_extension jobId=$jobId")
                host.updateUiState {
                    it.copy(statusMessage = "图片任务的本地扩展不可用：${error.message ?: "请重新选择"}")
                }
                return false
            }
        } else {
            if (baseOptions.loras.isNotEmpty() || baseOptions.textualInversionIds.isNotEmpty() ||
                baseOptions.ultraFix != null
            ) {
                Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=cloud_local_extensions jobId=$jobId")
                host.showStatus("云端图片连接器不接受本地模型扩展。")
                return false
            }
            baseOptions
        }
        // Preview is an explicit per-job UI/API option. Do not silently turn it back on here:
        // retries and history recreation must preserve the captured job snapshot exactly.
        var queuedOptions = currentOptions
        val queuedInputDraft = jobSnapshot?.inputDraft ?: inputDraft
        // 生成前的资产存在性检查：缺失/不可读的输入图在这里就被拦下，并给出可操作原因，
        // 而不是让任务入队后以 worker 的原始 ENOENT 失败收场（也不留下悬空引用）。
        if (requestedBackend == ImageBackend.LOCAL) {
            val missingInputs = missingGenerationImageInputRoles(
                references = localImageInputReferences(queuedInputDraft),
                isReadable = { reference -> generationImageReferenceReadable(host.application, reference) },
            )
            if (missingInputs.isNotEmpty()) {
                Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=missing_input jobId=$jobId")
                host.showStatus(generationImageInputMissingMessage(missingInputs).orEmpty())
                return false
            }
        }
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
                Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=sampler jobId=$jobId")
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
            Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=lease_busy jobId=$jobId")
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
            Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=ti_reservation jobId=$jobId")
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
            Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=lora_refresh jobId=$jobId")
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
                Log.i(IMAGE_COMMIT_LOG_TAG, "image_admit_reject code=batch_plan jobId=$jobId")
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
        // The lease is acquired outside the coroutine, so the body's finally block is not
        // sufficient: `launch` never runs the body of a job that is already cancelled (scope
        // cancelled, or cancelled between launch and dispatch). Without this handler the lease
        // stays in the registry, the last-owner stopService is never dispatched and the ongoing
        // generation notification is pinned forever. `release` is idempotent and identity-checked,
        // so the normal path releasing here as well cannot double-stop or stop another owner.
        val releaseForegroundLease = { McaGenerationForegroundService.release(host.application, foregroundLease) }
        val executionJob = host.scope.launch(Dispatchers.IO) {
            runImageGenerationExecution(
                host = host,
                jobId = jobId,
                requestedBackend = requestedBackend,
                requestedLocalModel = requestedLocalModel,
                requestedCloudConfig = requestedCloudConfig,
                executionJobSpec = executionJobSpec,
                queuedInputDraft = queuedInputDraft,
                owningChatSessionId = owningChatSessionId,
                executionOptions = executionOptions,
                cleanPrompt = cleanPrompt,
                localBatchPlan = localBatchPlan
            )
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
                releaseForegroundLease()
            }
        }
        // Compensating release for the case where the coroutine body never started. Idempotent:
        // a normal run already released above, so this only fires when the body was skipped.
        executionJob.invokeOnCompletion { releaseForegroundLease() }
        return true
    }
}


/** R3/C3-S8：自 MainViewModel 顶层化的租约延迟位写入（体逐字等价，store 参数化）。 */
internal fun updateGenerationImageGrantReleaseDeferInto(store: UiStateWriter<ImageGenerationFields>, defer: Boolean) {
    store.update { state ->
        if (state.deferGenerationImageGrantRelease == defer) {
            state
        } else {
            state.copy(deferGenerationImageGrantRelease = defer)
        }
    }
}

/** R3/C3-S15：租约观测对顶层化（自 VM 迁出；体逐字，仅依赖参数化）。 */
internal fun tryAcquireObservedImageGenerationLeaseFor(
    setDeferGenerationImageGrantRelease: (Boolean) -> Unit,
    coordinator: LocalImageGenerationCoordinator,
    observationLock: Any,
    requestId: String
): LocalImageGenerationCoordinator.Lease? =
    synchronized(observationLock) {
        setDeferGenerationImageGrantRelease(true)
        coordinator.tryAcquire(requestId).also { lease ->
            if (lease == null && coordinator.activeRequestId() == null) {
                setDeferGenerationImageGrantRelease(false)
            }
        }
    }

/** A stale epoch cannot clear the observable protection for the current lease holder. */
internal fun releaseObservedImageGenerationLeaseFor(
    setDeferGenerationImageGrantRelease: (Boolean) -> Unit,
    coordinator: LocalImageGenerationCoordinator,
    observationLock: Any,
    lease: LocalImageGenerationCoordinator.Lease
): Boolean = synchronized(observationLock) {
    val released = coordinator.release(lease)
    setDeferGenerationImageGrantRelease(coordinator.activeRequestId() != null)
    released
}


/** The image pipeline's diagnostic tag, shared with the worker-side image logs. */
internal const val IMAGE_COMMIT_LOG_TAG = "MCA-LocalImage"
