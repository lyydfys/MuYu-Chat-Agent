package com.muyuchat.mca.presentation

import com.muyuchat.mca.*
import com.muyuchat.api.local.ImageGenerationApiContract
import com.muyuchat.api.local.ImageGenerationProviderException
import com.muyuchat.core.engine.EngineLifecycleLease
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.mca.ImageGenerationBatchLineage
import com.muyuchat.mca.LocalImageGenerationOptions
import com.muyuchat.mca.LocalApiImageOperationBusyException
import com.muyuchat.mca.LocalImageInputDraft
import com.muyuchat.mca.LocalImageLoraRecord
import com.muyuchat.mca.LocalImageModelRecord
import com.muyuchat.mca.LocalImagePreparedLora
import com.muyuchat.mca.LocalImageProductContractException
import com.muyuchat.mca.LocalImagePromptExecution
import com.muyuchat.mca.LocalImageResult
import com.muyuchat.mca.LocalImageRuntime
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.QnnImageEvidenceEntryPoint
import com.muyuchat.mca.executeLocalImageBatchPlan
import com.muyuchat.mca.imageSchedulerProductName
import com.muyuchat.mca.localImageApiResponseOutputEvidence
import com.muyuchat.mca.localImageExtensionResolutionFailure
import com.muyuchat.mca.localImageStructuralReadinessMessage
import com.muyuchat.mca.normalizedForPromptExecutionProfile
import com.muyuchat.mca.planLocalImageBatch
import com.muyuchat.mca.requireTextualInversionPromptTriggers
import com.muyuchat.mca.resolveLocalImageApiModel
import com.muyuchat.mca.resolveLocalImageExecutionProfile
import com.muyuchat.mca.sanitizedLocalImageApiExecution
import com.muyuchat.mca.supportsAuthenticatedLocalImageCount
import com.muyuchat.mca.toLocalImageApiDispatch
import com.muyuchat.mca.toLocalImageApiProviderExceptionOrNull
import com.muyuchat.mca.validateProductTaskSampler
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local API 生图协议适配 —— `MainViewModel.generateLocalApiImage` 的搬迁实现。
 *
 * ## 为什么放在这里
 *
 * 这段逻辑的产物就是 **协议响应**：失败时是 `ImageGenerationProviderException` 的
 * `code` / `httpStatus` / `message`，成功时是 `created` / `request_id` / `model` /
 * `prompt_processing` / `execution` / `data` 组成的 JSON 字符串。它是 Local API 的协议适配，
 * 不是界面状态机，因此归 presentation 层，而不是继续留在约 19k 行的应用 VM 内。
 *
 * ## 搬迁纪律：函数体逐字复制
 *
 * [generateLocalApiImageOperations] 的函数体是 `MainViewModel.kt` 第 17046..17455 行的逐字副本
 * （含原 8 空格缩进，便于与原文件做纯文本比对，只多一个 `port.` 前缀）。全部改动只有两类：
 *
 *  1. **VM 成员访问 -> [LocalApiImageGenerationPort] 调用**（31 处）；
 *  2. **两个 Android 平台原语 -> 端口方法**：[LocalApiImageGenerationPort.decodeImageBounds]、
 *     [LocalApiImageGenerationPort.encodeBase64NoWrap]（协议响应编码缝合点）。
 *
 * 条件、分支顺序、异常类型、HTTP 状态码、JSON 字段名、错误文案**一律未改**。
 * Local API 的租约、取消所有权、活动模型身份与 worker release barrier 已收口到唯一 owner；
 * 本函数只在 [OwnerBackedLocalApiImageGenerationPort.runLocalApiImageOperation] 的 block 内
 * 保留原有的 worker、批量和协议响应逻辑。
 */


/** 见 [LocalApiImageGenerationPort]。函数体为 `MainViewModel.kt` 17046..17455 的逐字副本。 */
internal suspend fun generateLocalApiImageOperations(
    requestId: String,
    body: String,
    port: OwnerBackedLocalApiImageGenerationPort
): String {
        val request = ImageGenerationApiContract.parseRequest(body)
        require(requestId.isNotBlank()) { "requestId must not be blank." }
        if (request.preview != null) {
            throw ImageGenerationProviderException(
                code = "unsupported_preview_transport",
                httpStatus = 422,
                message = "This synchronous Images API response cannot publish live preview frames."
            )
        }
        val requestedModel = request.model.orEmpty()
        val model = if (requestedModel.isNotBlank()) {
            val currentState = port.uiState.value
            val candidate = resolveLocalImageApiModel(
                requestedModelId = requestedModel,
                chatModelIds = currentState.models.map(ModelManifest::id),
                imageModels = currentState.localImageModels
            )
                ?: throw ImageGenerationProviderException(
                    code = "image_model_not_found",
                    httpStatus = 404,
                    message = "The requested local image model was not found."
                )
            candidate
        } else {
            port.selectedLocalImageModel()
                ?: throw ImageGenerationProviderException(
                    code = "image_runtime_unavailable",
                    httpStatus = 503,
                    message = "No configured local image model is selected."
                )
        }
        model.localImageStructuralReadinessMessage()?.let { readiness ->
            throw ImageGenerationProviderException(
                code = "image_model_not_ready",
                httpStatus = 409,
                message = "The selected local image model is not ready: $readiness " +
                    "Re-download or re-import the complete model bundle, then retry."
            )
        }
        if (!supportsAuthenticatedLocalImageCount(model.runtime, request.imageCount)) {
            throw ImageGenerationProviderException(
                code = "unsupported_image_count",
                httpStatus = 422,
                message = "The selected local image runtime does not support n=${request.imageCount}."
            )
        }
        val dispatch = request.toLocalImageApiDispatch()
        if (request.textualInversionIds.isNotEmpty() &&
            port.uiState.value.localImageTextualInversionLoading
        ) {
            throw ImageGenerationProviderException(
                code = "image_textual_inversion_library_loading",
                httpStatus = 409,
                message = "The textual inversion library is still loading. Retry this request shortly."
            )
        }
        val resolvedLoras = try {
            port.resolveCurrentLocalImageLoras(
                request.loras.map { selection -> selection.id to selection.multiplier }
            )
        } catch (error: Exception) {
            throw localImageExtensionResolutionFailure(
                error = error,
                fallbackCode = "image_lora_not_found",
                fallbackMessage = "A requested LoRA adapter is unavailable."
            )
        }
        val resolvedTextualInversionIds = try {
            port.resolveCurrentLocalImageTextualInversionIds(request.textualInversionIds).also { ids ->
                requireTextualInversionPromptTriggers(
                    prompt = request.prompt,
                    negativePrompt = dispatch.options.negativePrompt,
                    ids = ids,
                    records = port.uiState.value.localImageTextualInversions
                )
            }
        } catch (error: Exception) {
            throw localImageExtensionResolutionFailure(
                error = error,
                fallbackCode = "image_textual_inversion_not_found",
                fallbackMessage = "A requested textual inversion is unavailable."
            )
        }
        var options = dispatch.options.copy(
            loras = resolvedLoras,
            textualInversionIds = resolvedTextualInversionIds
        )
        val inputDraft = dispatch.inputDraft
        try {
            val resolvedSampler = model.validateProductTaskSampler(
                taskMode = inputDraft.taskMode,
                sampleMethod = options.sampleMethod
            )
            // Persist the task-aware effective sampler before planning/dispatch. This keeps an
            // omitted sampler usable for generic shared-QNN img2img (whose legacy profile default
            // may be PNDM) while still returning a stable 422 for an explicit PNDM request.
            options = options.copy(sampleMethod = imageSchedulerProductName(resolvedSampler))
        } catch (error: Exception) {
            throw requireNotNull(error.toLocalImageApiProviderExceptionOrNull()) {
                ImageGenerationProviderException(
                    code = "unsupported_sampler",
                    httpStatus = 422,
                    message = error.message ?: "The selected sampler is not supported for this image task."
                )
            }
        }
        val batchPlan = planLocalImageBatch(
            parentRequestId = requestId,
            runtime = model.runtime,
            requestedOptions = options
        )
        options = batchPlan.parentOptions
        return try {
            port.runLocalApiImageOperation(requestId) { operation ->
                operation.bindModel(model.id)
                var chatRuntimeLease: com.muyuchat.core.engine.EngineLifecycleLease? = null
                try {
                    currentCoroutineContext().ensureActive()
                    val textualInversionReservationError =
                        synchronized(port.localImageTextualInversionLifecycleLock) {
                            val state = port.uiState.value
                            val ids = options.textualInversionIds.toSet()
                            when {
                                ids.any(state.deletingLocalImageTextualInversionIds::contains) ->
                                    "A requested textual inversion is being deleted."
                                ids.any { requestedId ->
                                    state.localImageTextualInversions.none { it.id == requestedId }
                                } -> "A requested textual inversion is no longer installed."
                                else -> {
                                    port.updateUiState {
                                        it.copy(activeLocalImageTextualInversionIds = ids)
                                    }
                                    null
                                }
                            }
                        }
                    if (textualInversionReservationError != null) {
                        throw ImageGenerationProviderException(
                            code = "image_textual_inversion_not_found",
                            httpStatus = 404,
                            message = textualInversionReservationError
                        )
                    }
                    val leasedLoraRefreshError = synchronized(port.localImageLoraLifecycleLock) {
                        runCatching {
                            val currentLoras = port.localImageLoraStore.load()
                            options = options.copy(
                                loras = port.resolveCurrentLocalImageLoras(
                                    request.loras.map { selection -> selection.id to selection.multiplier },
                                    currentLoras
                                )
                            )
                            port.updateUiState {
                                it.copy(
                                    localImageLoras = currentLoras,
                                    activeLocalImageLoraIds = options.loras
                                        .mapTo(mutableSetOf()) { adapter -> adapter.id }
                                )
                            }
                        }.exceptionOrNull()
                    }
                    if (leasedLoraRefreshError != null) {
                        throw ImageGenerationProviderException(
                            code = "image_lora_not_found",
                            httpStatus = 404,
                            message = leasedLoraRefreshError.message
                                ?: "A requested LoRA adapter is unavailable."
                        )
                    }
                    val promptExecution = try {
                        port.prepareLocalImagePromptExecution(
                            model = model,
                            prompt = request.prompt,
                            options = options,
                            captured = null
                        ).also { execution ->
                            requireTextualInversionPromptTriggers(
                                prompt = execution.effectivePrompt,
                                negativePrompt = execution.effectiveNegativePrompt,
                                ids = options.textualInversionIds,
                                records = port.uiState.value.localImageTextualInversions
                            )
                        }
                    } catch (error: Throwable) {
                        if (error is LocalImageProductContractException &&
                            error.code == "image_textual_inversion_trigger_missing"
                        ) {
                            throw ImageGenerationProviderException(
                                code = error.code,
                                httpStatus = 422,
                                message = error.message
                            )
                        }
                        error.toLocalImageApiProviderExceptionOrNull()?.let { throw it }
                        throw error
                    }
                    val effectiveOptions = options.copy(
                        negativePrompt = promptExecution.effectiveNegativePrompt
                    )
                    chatRuntimeLease = port.prepareLocalImageRuntimeMemory()
                    currentCoroutineContext().ensureActive()
                    val childResults = try {
                        executeLocalImageBatchPlan(
                            plan = batchPlan,
                            cancellationRequested = operation::isCancellationRequested,
                            execute = { child ->
                                val result = port.localImageWorkerClient.generate(
                                    model = model,
                                    prompt = promptExecution.effectivePrompt,
                                    options = effectiveOptions.copy(
                                        batchCount = child.options.batchCount,
                                        seed = child.options.seed
                                    ),
                                    inputDraft = inputDraft,
                                    requestId = child.requestId,
                                    batchLineage = child.batchLineage
                                )
                                require(result.outputs.size == child.outputCount) {
                                    "Image batch child returned ${result.outputs.size} outputs; expected ${child.outputCount}."
                                }
                                listOf(result)
                            },
                            cleanup = { _ -> Unit }
                        )
                    } catch (error: Throwable) {
                        error.toLocalImageApiProviderExceptionOrNull()?.let { throw it }
                        if (error is IllegalArgumentException) {
                            throw ImageGenerationProviderException(
                                code = "invalid_image_input",
                                httpStatus = 422,
                                message = error.message ?: "The image input could not be prepared."
                            )
                        }
                        throw error
                    }
                    currentCoroutineContext().ensureActive()
                    return@runLocalApiImageOperation try {
                        val orderedOutputs = childResults.flatMapIndexed { childIndex, result ->
                            val child = batchPlan.requests[childIndex]
                            result.outputs.map { output ->
                                output.copy(index = child.outputOffset + output.index)
                            }
                        }
                        require(orderedOutputs.size == request.imageCount) {
                            "The local image worker returned ${orderedOutputs.size} images; ${request.imageCount} were requested."
                        }
                        val result = childResults.last()
                        val execution = runCatching {
                            val lastChild = batchPlan.requests.last()
                            sanitizedLocalImageApiExecution(port.executionWithQnnVerificationEvidence(
                                model = model,
                                result = result,
                                options = effectiveOptions.copy(
                                    batchCount = lastChild.options.batchCount,
                                    seed = lastChild.options.seed
                                ),
                                requestId = requestId,
                                attemptId = lastChild.requestId,
                                entryPoint = QnnImageEvidenceEntryPoint.AUTHENTICATED_LOCAL_API
                            ))
                        }.getOrElse { JSONObject() }
                        // Expose the resolved, non-sensitive execution contract on the authenticated
                        // API response. Paths and device-private runtime directories remain excluded;
                        // clients can now audit exactly which profile/scheduler bounds were applied.
                        runCatching {
                            val profileRoot = model.bundleRoot
                                ?.takeIf(String::isNotBlank)
                                ?.let(::File)
                                ?.takeIf(File::isDirectory)
                                ?: File(model.path).parentFile?.takeIf(File::isDirectory)
                            val profile = resolveLocalImageExecutionProfile(
                                model = model,
                                options = effectiveOptions.normalizedForPromptExecutionProfile(model.runtime),
                                bundleRoot = profileRoot
                            ).profile
                            execution.put(
                                "resolvedProfile",
                                JSONObject()
                                    .put("profileId", profile.profileId)
                                    .put("profileRevision", profile.profileRevision)
                                    .put("runtime", profile.runtime.name)
                                    .put("family", profile.family.name)
                                    .put("scheduler", JSONObject()
                                        .put("algorithm", profile.scheduler.algorithm.name)
                                        .put("predictionType", profile.scheduler.predictionType.name)
                                        .put("defaultSteps", profile.scheduler.defaultSteps)
                                        .put("minSteps", profile.scheduler.minSteps)
                                        .put("maxSteps", profile.scheduler.maxSteps))
                                    .put("defaults", JSONObject()
                                        .put("steps", profile.defaults.steps)
                                        .put("width", profile.defaults.width)
                                        .put("height", profile.defaults.height)
                                        .put("cfgScale", profile.defaults.cfgScale))
                                    .put("supportedSchedulers", JSONArray(
                                        profile.capabilities.supportedSchedulers.map { it.name }))
                            )
                        }
                        execution.put(
                            "responseOutputEvidence",
                            localImageApiResponseOutputEvidence(orderedOutputs)
                        )
                        requireNativeImageExecutionEvidence(execution, model.runtime)
                        JSONObject()
                            .put("created", System.currentTimeMillis() / 1000L)
                            .put("request_id", requestId)
                            // Echo an explicit catalog alias exactly so the API contract can bind the
                            // provider response to the caller's requested model. Legacy raw ids remain
                            // accepted and are echoed unchanged.
                            .put("model", requestedModel.ifBlank { model.id })
                            .put("prompt_processing", promptExecution.toJson())
                            .put("execution", execution)
                            .put(
                                "data",
                                JSONArray().apply {
                                    orderedOutputs.forEach { output ->
                                        currentCoroutineContext().ensureActive()
                                        val bounds = port.decodeImageBounds(output.bytes)
                                        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                                            "The local image worker returned an invalid image at index ${output.index}."
                                        }
                                        put(
                                            JSONObject()
                                                .put("index", output.index)
                                                .put("b64_json", port.encodeBase64NoWrap(output.bytes))
                                                .put("mime_type", output.mimeType)
                                                .put("width", bounds.outWidth)
                                                .put("height", bounds.outHeight)
                                                .apply { output.seed?.let { put("seed", it) } }
                                        )
                                    }
                                }
                            )
                            .toString()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: ImageGenerationProviderException) {
                        throw error
                    } catch (error: Throwable) {
                        throw ImageGenerationProviderException(
                            code = "invalid_image_worker_response",
                            httpStatus = 502,
                            message = error.message ?: "The local image worker returned invalid execution evidence."
                        )
                    }
                } finally {
                    withContext(NonCancellable) {
                        // Release the chat runtime lease while the image lease is still owned, so
                        // no new image request can enter the native-memory handoff window.
                        chatRuntimeLease?.release()
                    }
                }
            }
        } catch (busy: LocalApiImageOperationBusyException) {
            throw ImageGenerationProviderException(
                code = "image_generation_busy",
                httpStatus = 409,
                message = busy.message ?: "Another UI or Local API image request is already running."
            )
        }
}
