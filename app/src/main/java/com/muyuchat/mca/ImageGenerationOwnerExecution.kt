package com.muyuchat.mca

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * T2B20/R7-C9: `submit` 的执行/提交/释放相位（原 L425-762）自 ImageGenerationOwner.kt 逐字节平移。
 *
 * 唯一结构性包装 = `launch@{}` 标签 suspend lambda：原体中的 `return@launch` 早退（x5）保持逐字节，
 * 语义与原 launch 体一致（同一协程、同一异常传播、同一完成语义）。
 */
internal suspend fun runImageGenerationExecution(
    host: ImageGenerationHost,
    jobId: String,
    requestedBackend: ImageBackend,
    requestedLocalModel: LocalImageModelRecord?,
    requestedCloudConfig: CloudApiConfig?,
    executionJobSpec: ImageGenerationJobSpec,
    queuedInputDraft: LocalImageInputDraft,
    owningChatSessionId: String?,
    executionOptions: LocalImageGenerationOptions,
    cleanPrompt: String,
    localBatchPlan: LocalImageBatchPlan?
) {
    val executionBlock: suspend () -> Unit = launch@{

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
                    val cancelled = commitError is CancellationException ||
                        host.currentState().imageJobs.firstOrNull { it.id == jobId }?.status ==
                        ImageGenerationStatusRecord.CANCEL_REQUESTED
                    // Only a cancellation may destroy the produced copy and its staged record.
                    // Any other failure keeps both: the startup recovery path needs them to retry
                    // the commit or preserve the file in the gallery, and without them a
                    // recoverable commit failure becomes permanent data loss.
                    settleFailedImageCommit(
                        commitError = commitError,
                        cancelRequested = cancelled,
                        images = generatedImages,
                        assetDirectory = host.imageAssetDirectory,
                        requestId = {
                            host.currentState().imageJobs.firstOrNull { it.id == jobId }
                                ?.spec?.chatMessageId
                        },
                        journalJobId = { image -> chatImageCommitJournalJobId(jobId, image.id) },
                        removeJournalEntry = { journalJobId, requestId ->
                            host.chatImageCommitJournal.removeAfterConfirmedAssetRollback(
                                jobId = journalJobId,
                                requestId = requestId,
                                rollbackConfirmed = true
                            )
                        }
                    )
                    if (!cancelled) {
                        Log.e(IMAGE_COMMIT_LOG_TAG, "image commit failed", commitError)
                    }
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
    executionBlock()
}
