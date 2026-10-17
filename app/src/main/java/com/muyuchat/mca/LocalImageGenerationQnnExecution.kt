package com.muyuchat.mca

import com.muyuchat.core.engine.SystemRuntimeMonotonicClock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal suspend fun QnnHtpExecutionContext.runQnnHtpExecution(): LocalImageResult {
    var textualInversionLease: TextualInversionSelectionLease? by textualInversionLeaseCell
            val startedAt = SystemRuntimeMonotonicClock.nowMs()
            fun progress(
                phase: String,
                message: String,
                step: Int = 0,
                totalSteps: Int = steps
            ) {
                onProgress(
                    LocalImageProgress(
                        phase = phase,
                        message = message,
                        step = step,
                        steps = totalSteps,
                        elapsedMs = (SystemRuntimeMonotonicClock.nowMs() - startedAt).coerceAtLeast(0L),
                        secondsPerStep = 0.0,
                        threads = threads,
                        width = width,
                        height = height,
                        cancelRequested = false,
                        requestOptionsJson = contract.auditJson.toString()
                    )
                )
            }

    val qnnExecutionBody: suspend () -> LocalImageResult = withContext@ {
            var splitOutputTransferred = false
            var splitRequestError: Throwable? = null

            try {
                progress(
                    "conditioning",
                    if (isSdxlQnn) {
                        "Encoding SDXL prompt conditioning for QNN image generation"
                    } else {
                        "Encoding prompt embeddings for QNN image generation"
                    }
                )
                val embeddingRaw = if (isSdxlQnn) {
                    mnnDiffusionBridge.encodeSdxlPromptConditioning(
                        conditioningRoot.absolutePath,
                        prompt.trim(),
                        executedNegativePrompt,
                        embeddingFile.absolutePath,
                        width,
                        height,
                        contract.backendMode,
                        threads,
                        useCfgForSdxlConditioning,
                        resolved.promptWeightingSupported,
                        qnnTextualInversionJson
                    )
                } else if (usesQnnClipTokenIds) {
                    encodeQnnClipPromptTokenIds(
                        bridge = mnnDiffusionBridge,
                        bundleRoot = bundleRoot,
                        prompt = prompt.trim(),
                        outputFile = embeddingFile,
                        negativePrompt = executedNegativePrompt,
                        bosId = requireNotNull(profile.tokenizer.bosId) {
                            "Resolved tokenizer profile is missing BOS id."
                        },
                        eosId = requireNotNull(profile.tokenizer.eosId) {
                            "Resolved tokenizer profile is missing EOS id."
                        },
                        padId = requireNotNull(profile.tokenizer.padId) {
                            "Resolved tokenizer profile is missing PAD id."
                        },
                        maxTokens = profile.tokenizer.maxLength,
                        promptWeightingEnabled = resolved.promptWeightingSupported,
                        nativeMultilingualEvidence = nativeMultilingualTextEncoderEvidence
                    )
                } else {
                    mnnDiffusionBridge.encodeSd15PromptEmbeddings(
                        conditioningRoot.absolutePath,
                        prompt.trim(),
                        executedNegativePrompt,
                        embeddingFile.absolutePath,
                        contract.backendMode,
                        threads,
                        conditioningOrder,
                        resolved.promptWeightingSupported,
                        qnnTextualInversionJson
                    )
                }
                val embeddingJson = JSONObject(embeddingRaw)
                if (!embeddingJson.optBoolean("ok", false)) {
                    if (embeddingJson.optBoolean("cancelled", false) || cancellationRequested.get()) {
                        throw LocalImageWorkerCancelledException()
                    }
                    port.logLocalImageNativeFailure(embeddingJson, "Failed to encode QNN prompt embeddings.")
                    throwLocalImageNativeFailure(
                        embeddingJson,
                        "Failed to encode QNN prompt embeddings."
                    )
                }
                val nativeMultilingualPromptHandoff = nativeMultilingualTextEncoderEvidence
                    ?.let { evidence ->
                        verifyQnnNativeMultilingualPromptHandoff(
                            bundleRoot = bundleRoot,
                            evidence = evidence,
                            encoded = embeddingJson
                        )
                    }
                nativeMultilingualPromptHandoff?.let { handoff ->
                    // This opaque capability can only be issued after MNN commits the exact token
                    // payload. QNN atomically consumes it while matching prompt, payload,
                    // tokenizer identity, and the signed prompt-to-encoder closure.
                    params.put("mnnPromptHandoff", handoff.opaqueHandle)
                }
                val actualConditioningFormat = embeddingJson
                    .optString("conditioningFormat")
                    .ifBlank { embeddingJson.optString("format") }
                    .trim()
                require(actualConditioningFormat.isNotEmpty()) {
                    "Prompt conditioning did not report its native file format."
                }
                val requestedConditioningFormat = params.optString("conditioningFormat").trim()
                if (requestedConditioningFormat.isEmpty()) {
                    params.put("conditioningFormat", actualConditioningFormat)
                } else {
                    require(requestedConditioningFormat == actualConditioningFormat) {
                        "Prompt conditioning format mismatch: resolved=$requestedConditioningFormat, " +
                            "encoder=$actualConditioningFormat."
                    }
                }
                ImageExecutionProfileNativeContract.nativeEvidenceOnlyFields.forEach { field ->
                    require(embeddingJson.has(field) && !embeddingJson.isNull(field)) {
                        "Prompt conditioning did not report native weighting evidence: $field"
                    }
                    params.put(field, embeddingJson.get(field))
                }
                requireNativePromptEncodingEvidence(
                    source = embeddingJson,
                    prompt = prompt.trim(),
                    negativePrompt = executedNegativePrompt
                )
                ImageExecutionProfileNativeContract.qnnPromptConditioningHandoffFields.forEach { field ->
                    params.put(field, embeddingJson.get(field))
                }
                require(embeddingFile.isFile && embeddingFile.length() > 0L) {
                    "Prompt conditioning did not produce a concrete artifact."
                }
                val conditioningArtifactSha256 = embeddingFile.sha256Contents()
                textualInversionLease?.let { lease ->
                    verifyEncodedMnnTextualInversionEvidence(
                        encoded = embeddingJson,
                        selection = lease.selection,
                        runtime = TextualInversionRuntime.QNN_HTP
                    )
                }
                if (isSdxlQnn) {
                    require(
                        embeddingJson.optString("conditioningExecutionMode") ==
                            "external_mnn_sdxl_embeddings" &&
                            embeddingJson.optString("conditioningBackend") == "MNN" &&
                            embeddingJson.optString("conditioningGraph") ==
                            SDXL_QNN_CONDITIONING_GRAPH_EVIDENCE &&
                            embeddingJson.optInt("conditioningEncoderExecutionCount", -1) ==
                            expectedSdxlConditioningExecutions &&
                            embeddingJson.optString("conditioningOrder") ==
                            sdxlConditioningOrder &&
                            embeddingJson.optJSONArray("hiddenShape")?.let { shape ->
                                shape.length() == 3 &&
                                    shape.optInt(0, -1) == expectedSdxlConditioningBranches &&
                                    shape.optInt(1, -1) == 77 &&
                                    shape.optInt(2, -1) == 2_048
                            } == true &&
                            embeddingJson.optJSONArray("pooledShape")?.let { shape ->
                                shape.length() == 2 &&
                                    shape.optInt(0, -1) == expectedSdxlConditioningBranches &&
                                    shape.optInt(1, -1) == 1_280
                            } == true &&
                            embeddingJson.optJSONArray("timeIdsShape")?.let { shape ->
                                shape.length() == 2 &&
                                    shape.optInt(0, -1) == 1 && shape.optInt(1, -1) == 6
                            } == true &&
                            embeddingJson.optString("conditioningArtifactSha256").lowercase() ==
                            conditioningArtifactSha256 &&
                            embeddingJson.optString("promptWeightFingerprint").lowercase() ==
                            conditioningArtifactSha256
                    ) {
                        "MNN SDXL conditioning did not publish complete graph and artifact evidence."
                    }
                    params.put("conditioningExecutionMode", "external_mnn_sdxl_embeddings")
                    params.put("conditioningBackend", "MNN")
                    params.put("conditioningGraph", SDXL_QNN_CONDITIONING_GRAPH_EVIDENCE)
                    params.put(
                        "conditioningGraphSha256",
                        sdxlQnnConditioningGraphSha256(conditioningRoot)
                    )
                    params.put("conditioningEncoderExecutionCount", expectedSdxlConditioningExecutions)
                    params.put("conditioningOrder", sdxlConditioningOrder)
                } else if (profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_UNET_VAE) {
                    val expectedConditioningExecutions = if (resolved.useCfg) 2 else 1
                    val conditioningGraphPath = requireNotNull(
                        profile.graph.textEncoder?.relativePath
                    ) { "Shared QNN UNet/VAE profile is missing its MNN conditioning graph path." }
                    val conditioningGraphFile = requireNotNull(
                        bundleRoot.safeDescendantOrNull(conditioningGraphPath)
                            ?.takeIf { file -> file.isFile && file.length() > 0L }
                    ) { "MNN conditioning graph is missing or empty: $conditioningGraphPath" }
                    val conditioningGraphSha256 = conditioningGraphFile.sha256Contents()
                    val conditioningShape = embeddingJson.optJSONArray("shape")
                    require(embeddingJson.optString("conditioningExecutionMode") ==
                        "external_mnn_embeddings" &&
                        embeddingJson.optString("conditioningBackend") == "MNN" &&
                        embeddingJson.optString("conditioningGraph") == "clip_v2.mnn" &&
                        embeddingJson.optInt("conditioningEncoderExecutionCount", -1) ==
                        expectedConditioningExecutions &&
                        embeddingJson.optString("conditioningOrder") == conditioningOrder &&
                        conditioningShape?.optInt(0, -1) == expectedConditioningExecutions &&
                        embeddingJson.optString("conditioningArtifactSha256").lowercase() ==
                        conditioningArtifactSha256 &&
                        embeddingJson.optString("promptWeightFingerprint").lowercase() ==
                        conditioningArtifactSha256
                    ) {
                        "MNN clip_v2.mnn conditioning did not publish complete native graph and artifact evidence."
                    }
                    params.put("conditioningExecutionMode", "external_mnn_embeddings")
                    params.put("conditioningBackend", "MNN")
                    params.put("conditioningGraph", "clip_v2.mnn")
                    params.put("conditioningGraphSha256", conditioningGraphSha256)
                    params.put("conditioningEncoderExecutionCount", expectedConditioningExecutions)
                    params.put("conditioningOrder", conditioningOrder)
                }
                params.put("conditioningArtifactSha256", conditioningArtifactSha256)
                val preparedQnnInput = if (usesQnnImg2Img) {
                    val input = requireNotNull(effectiveOptions.inputImage) {
                        "QNN img2img requires a prepared input image."
                    }
                    progress("input_preprocess", "Preparing the bounded QNN VAE-encoder input tensor")
                    val prepared = if (usesSplitQnnWorkers && effectiveOptions.ultraFix == null) {
                        SdxlInputImageArtifact.prepare(
                            input = input,
                            tensorFile = inputTensorFile,
                            targetWidth = width,
                            targetHeight = height,
                            isCancelled = cancellationRequested::get
                        )
                    } else {
                        // UltraFix preprocesses the complete target canvas before the isolated
                        // encoder worker executes its fixed-size graph once per planned tile.
                        QnnInputImageArtifact.prepare(
                            input = input,
                            tensorFile = inputTensorFile,
                            targetWidth = width,
                            targetHeight = height,
                            isCancelled = cancellationRequested::get
                        )
                    }
                    prepared.also { preparedInput ->
                        preparedInput.putNativeParams(params)
                        val schedule = resolveQnnImg2ImgSchedule(
                            steps = steps,
                            fullTimetableCount = steps,
                            strength = effectiveOptions.strength ?: 1.0
                        )
                        params.put("fullTimetableCount", schedule.fullTimetableCount)
                            .put("effectiveDenoiseSteps", schedule.effectiveSteps)
                            .put("img2imgBeginIndex", schedule.beginIndex)
                    }
                } else {
                    null
                }
                val preparedQnnInpaint = if (usesQnnInpaint) {
                    val input = requireNotNull(effectiveOptions.inputImage) {
                        "QNN inpaint requires a prepared source image."
                    }
                    val mask = requireNotNull(effectiveOptions.maskImage) {
                        "QNN inpaint requires a prepared mask image."
                    }
                    progress("input_preprocess", "Preparing bounded QNN inpaint tensors")
                    QnnInpaintInputArtifact.prepare(
                        input = input,
                        mask = mask,
                        sourceTensorFile = inputTensorFile,
                        maskTensorFile = inpaintMaskTensorFile,
                        fullMaskTensorFile = inpaintFullMaskTensorFile,
                        maskedInputTensorFile = inpaintMaskedInputTensorFile,
                        topology = profile.inspectQnnInpaintTopology(),
                        targetWidth = width,
                        targetHeight = height,
                        isCancelled = cancellationRequested::get
                    ).also { prepared ->
                        prepared.putNativeParams(params)
                        val schedule = resolveQnnInpaintSchedule(
                            steps = steps,
                            fullTimetableCount = steps,
                            strength = effectiveOptions.strength ?: 1.0
                        )
                        params
                            .put("fullTimetableCount", schedule.fullTimetableCount)
                            .put("img2imgBeginIndex", schedule.beginIndex)
                            .put("effectiveDenoiseSteps", schedule.effectiveSteps)
                    }
                } else {
                    null
                }
                if (cancellationRequested.get()) {
                    error("本地生图已停止")
                }

                progress("sampling", "正在骁龙 NPU 上运行 QNN UNet 和 VAE")
                var sharedQnnLastJournalProgress: LocalImageProgress? = null
                var sharedQnnNativeCompletedSuccessfully = false
                val raw = if (usesSplitQnnWorkers) {
                    sdxlCoordinator.generate(
                        lease = requireNotNull(splitRequestLease),
                        requestId = requestToken,
                        bundleRoot = bundleRoot,
                        runtimeDirsJson = qnnRuntimeDirsJson(appContext, bundleRoot),
                        params = params,
                        embeddingsFile = embeddingFile,
                        latentFile = latentFile,
                        metadataFile = latentMetadataFile,
                        preparedInput = preparedQnnInput,
                        preparedInpaint = preparedQnnInpaint,
                        inputTensorFile = inputTensorFile,
                        encoderLatentFile = encoderLatentFile,
                        encoderMetadataFile = encoderMetadataFile,
                        outputFile = outputFile,
                        encoderJournal = encoderJournalFile,
                        unetJournal = unetJournalFile,
                        vaeJournal = vaeJournalFile,
                        onProgress = onProgress
                    )
                } else {
                    runCatching { progressJournalFile.delete() }
                    runCatching { File(progressJournalFile.absolutePath + ".tmp").delete() }
                    val progressPoller = launch(Dispatchers.Default) {
                        while (isActive) {
                            val observed = QnnImageStageJournal.readOrPrevious(
                                file = progressJournalFile,
                                previous = sharedQnnLastJournalProgress,
                                threads = threads,
                                width = width,
                                height = height
                            )
                            if (observed != null && observed != sharedQnnLastJournalProgress) {
                                sharedQnnLastJournalProgress = observed
                                onProgress(observed)
                            }
                            delay(250)
                        }
                    }
                    var qnnRawResult: String? = null
                    try {
                        qnnBridge.runImageSemanticGenerate(
                            bundleRoot.absolutePath,
                            qnnRuntimeDirsJson(appContext, bundleRoot),
                            params.toString(),
                            embeddingFile.canonicalPath,
                            outputFile.canonicalPath
                        ).also { qnnRawResult = it }
                    } finally {
                        // The parent provider coroutine may already be cancelled here. Join in a
                        // non-cancellable cleanup region so no preview source is deleted while
                        // the poller is still reading it or completing a synchronous callback.
                        withContext(NonCancellable) {
                            progressPoller.cancelAndJoin()
                        }
                        sharedQnnNativeCompletedSuccessfully = qnnRawResult?.let { rawResult ->
                            runCatching {
                                val result = JSONObject(rawResult)
                                result.optBoolean("ok", false) &&
                                    !result.optBoolean("cancelled", false)
                            }.getOrDefault(false)
                        } == true
                    }
                }
                if (!usesSplitQnnWorkers) {
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) {
                    json.optString("errorCode")
                        .trim()
                        .takeIf(String::isNotEmpty)
                        ?.let { code ->
                            throw LocalImageProductContractException(
                                code.lowercase(),
                                json.optString("error")
                                    .ifBlank { json.optString("message") }
                                    .ifBlank { "Snapdragon NPU image generation failed." }
                            )
                        }
                    error(
                        if (json.optBoolean("cancelled", false)) {
                            "本地生图已停止"
                        } else {
                            json.optString("message").ifBlank { "Snapdragon NPU image generation failed." }
                        }
                    )
                }
                if (!usesSplitQnnWorkers) {
                    val nativeEffective = json.optJSONObject("nativeEffective")
                        ?: error("QNN image generation did not report nativeEffective evidence.")
                    require(json.optString("conditioningArtifactSha256") == conditioningArtifactSha256 &&
                        nativeEffective.optString("conditioningArtifactSha256") == conditioningArtifactSha256 &&
                        json.optBoolean("conditioningArtifactConsumed", false) &&
                        nativeEffective.optBoolean("conditioningArtifactConsumed", false) &&
                        (profile.graph.workerStrategy != ImageWorkerStrategy.SHARED_UNET_VAE ||
                            nativeEffective.optString("conditioningGraphSha256") ==
                            params.optString("conditioningGraphSha256"))
                    ) {
                        "QNN image generation did not consume the exact prepared conditioning artifact."
                    }
                }
                val qnnTextualInversionEvidence = textualInversionLease?.let { lease ->
                    promoteConsumedQnnTextualInversionEvidence(
                        result = json,
                        encoded = embeddingJson,
                        selection = lease.selection,
                        expectedConditioningSha256 = conditioningArtifactSha256,
                        splitWorkers = usesSplitQnnWorkers
                    )
                }
                ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
                val inputExecutionAudit = when (effectiveOptions.taskMode) {
                    LocalImageTaskMode.CONTROL ->
                        verifyAndSanitizeQnnProductInput(json, effectiveOptions)
                    LocalImageTaskMode.IMG2IMG -> if (effectiveOptions.ultraFix != null) {
                        verifyAndSanitizeQnnUltraFixProductInput(
                            result = json,
                            options = effectiveOptions,
                            prepared = requireNotNull(preparedQnnInput),
                            usesSplitWorkers = usesSplitQnnWorkers,
                            profile = profile,
                            expectedVaeEncoderContextSha256 = params
                                .getString("vaeEncoderContextSha256")
                                .lowercase(),
                            expectedVaeEncoderGraphName = params.getString("vaeEncoderGraphName")
                        )
                    } else if (usesSplitQnnWorkers) {
                        verifyAndSanitizeQnnImg2ImgProductInput(
                            result = json,
                            options = effectiveOptions,
                            prepared = requireNotNull(preparedQnnInput),
                            expectedVaeEncoderContextSha256 = params
                                .getString("vaeEncoderContextSha256")
                                .lowercase()
                        )
                    } else {
                        require(usesSharedQnnImg2Img) {
                            "Resolved QNN img2img execution is neither a split nor coherent shared topology."
                        }
                        verifyAndSanitizeSharedQnnImg2ImgProductInput(
                            result = json,
                            options = effectiveOptions,
                            prepared = requireNotNull(preparedQnnInput),
                            profile = profile,
                            expectedVaeEncoderContextSha256 = params
                                .getString("vaeEncoderContextSha256")
                                .lowercase(),
                            expectedVaeEncoderGraphName = params.getString("vaeEncoderGraphName")
                        )
                    }
                    LocalImageTaskMode.INPAINT -> {
                        require(usesQnnInpaint) {
                            "Resolved QNN inpaint execution has no executable topology."
                        }
                        if (usesSplitQnnWorkers) {
                            verifyAndSanitizeSplitQnnInpaintProductInput(
                                result = json,
                                options = effectiveOptions,
                                prepared = requireNotNull(preparedQnnInpaint),
                                expectedVaeEncoderContextSha256 = params
                                    .getString("vaeEncoderContextSha256")
                                    .lowercase()
                            )
                        } else {
                            verifyAndSanitizeSharedQnnInpaintProductInput(
                                result = json,
                                options = effectiveOptions,
                                prepared = requireNotNull(preparedQnnInpaint),
                                profile = profile,
                                expectedVaeEncoderContextSha256 = params
                                    .getString("vaeEncoderContextSha256")
                                    .lowercase(),
                                expectedVaeEncoderGraphName = params.getString("vaeEncoderGraphName")
                            )
                        }
                    }
                    LocalImageTaskMode.TEXT_TO_IMAGE -> {
                        verifyAndSanitizeQnnTextToImagePrivatePaths(json, effectiveOptions)
                        null
                    }
                    else -> error(
                        "QNN native product input verification is unavailable for " +
                            effectiveOptions.taskMode.wireName
                    )
                }
                if (!usesSplitQnnWorkers) {
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
                val verifiedOutput = verifyAndReadQnnImageOutput(
                    nativeResult = json,
                    expectedOutputFile = outputFile,
                    expectedWidth = width,
                    expectedHeight = height,
                    checkCancelled = {
                        coroutineContext.ensureActive()
                        if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                    }
                )
                verifyQnnUltraFixEvidence(
                    result = json,
                    request = effectiveOptions.ultraFix,
                    inputImage = effectiveOptions.inputImage,
                    requestedUseCfg = profileResolution.layers.resolved.useCfg,
                    outputBytes = verifiedOutput.bytes,
                    outputMimeType = verifiedOutput.mimeType
                )
                if (!usesSplitQnnWorkers) {
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
                val completedStepCount = if (usesSharedQnnImg2Img || usesQnnInpaint) {
                    profileResolution.layers.resolved.timetableCount
                } else {
                    steps
                }
                val qnnExecutionMetadata = sanitizeNativeExecutionJson(
                    qnnImageExecutionMetadata(
                        nativeRequestId = requestToken,
                        nativeResult = json,
                        outputBytes = verifiedOutput.outputBytes,
                        inputExecutionAudit = inputExecutionAudit,
                        requestedPreview = effectiveOptions.preview,
                        stagedRuntime = runtimeResolution.stagedRuntime
                    ).toString()
                ).takeIf(String::isNotBlank)
                    ?: error("QNN execution evidence could not be sanitized.")
                val result = LocalImageResult(
                    bytes = verifiedOutput.bytes,
                    mimeType = verifiedOutput.mimeType,
                    executionMetadataJson = JSONObject(qnnExecutionMetadata).putPromptExecutionBinding(
                        profile = profile,
                        prompt = prompt.trim(),
                        negativePrompt = executedNegativePrompt
                    ).toString(),
                    seed = contract.seed.toLong()
                )
                coroutineContext.ensureActive()
                if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                if (qnnTextualInversionEvidence != null) {
                    val lease = requireNotNull(textualInversionLease)
                    lease.verifyUnchanged()
                    lease.close()
                    textualInversionLease = null
                    textualInversionStore.commitSuccessfulBindings(
                        selection = lease.selection,
                        nativeBindingFingerprint =
                            qnnTextualInversionEvidence.bindingFingerprint,
                        nativeBindingStage = qnnTextualInversionEvidence.bindingStage
                    )
                }
                coroutineContext.ensureActive()
                if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                // The native worker can leave one committed frame between the last poll and its
                // return. Drain it only after every Java-side output/evidence/TI validation has
                // succeeded, so a product-contract failure cannot publish a new stale preview.
                if (!usesSplitQnnWorkers && sharedQnnNativeCompletedSuccessfully) {
                    QnnImageStageJournal.readOrPrevious(
                        file = progressJournalFile,
                        previous = sharedQnnLastJournalProgress,
                        threads = threads,
                        width = width,
                        height = height
                    )?.let { terminalProgress ->
                        if (terminalProgress != sharedQnnLastJournalProgress) {
                            sharedQnnLastJournalProgress = terminalProgress
                            onProgress(terminalProgress)
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                progress(
                    "completed",
                    "QNN NPU image generation completed",
                    step = completedStepCount,
                    totalSteps = completedStepCount
                )
                coroutineContext.ensureActive()
                if (usesSplitQnnWorkers) {
                    val terminal = requireNotNull(splitRequestTerminal)
                    if (cancellationRequested.get()) terminal.cancel()
                    if (!terminal.transfer()) {
                        check(terminal.current() == SplitSdxlRequestTerminalState.CANCELLED) {
                            "Split-SDXL output transfer was resolved more than once."
                        }
                        throw LocalImageWorkerCancelledException()
                    }
                } else if (cancellationRequested.get()) {
                    throw LocalImageWorkerCancelledException()
                }
                splitOutputTransferred = true
                return@withContext result
            } catch (error: Throwable) {
                splitRequestError = error
                throw error
            } finally {
                runCatching {
                    QnnImageStageJournal.cleanupRequestPreviewDirectory(progressJournalFile)
                }
                runCatching { progressJournalFile.delete() }
                runCatching { File(progressJournalFile.absolutePath + ".tmp").delete() }
                if (!usesSplitQnnWorkers) {
                    runCatching { embeddingFile.delete() }
                    runCatching { File(embeddingFile.absolutePath + ".part").delete() }
                    runCatching { outputFile.delete() }
                    runCatching { File(outputFile.absolutePath + ".part").delete() }
                    runCatching { latentFile.delete() }
                    runCatching { File(latentFile.absolutePath + ".part").delete() }
                    runCatching { latentMetadataFile.delete() }
                    runCatching { File(latentMetadataFile.absolutePath + ".part").delete() }
                    runCatching { inputTensorFile.delete() }
                    runCatching { File(inputTensorFile.absolutePath + ".part").delete() }
                    runCatching { inpaintMaskTensorFile.delete() }
                    runCatching { File(inpaintMaskTensorFile.absolutePath + ".part").delete() }
                    runCatching { inpaintFullMaskTensorFile.delete() }
                    runCatching { File(inpaintFullMaskTensorFile.absolutePath + ".part").delete() }
                    runCatching { inpaintMaskedInputTensorFile.delete() }
                    runCatching { File(inpaintMaskedInputTensorFile.absolutePath + ".part").delete() }
                    runCatching { encoderLatentFile.delete() }
                    runCatching { File(encoderLatentFile.absolutePath + ".part").delete() }
                    runCatching { encoderMetadataFile.delete() }
                    runCatching { File(encoderMetadataFile.absolutePath + ".part").delete() }
                    runCatching { encoderJournalFile.delete() }
                    runCatching { File(encoderJournalFile.absolutePath + ".tmp").delete() }
                    runCatching { unetJournalFile.delete() }
                    runCatching { File(unetJournalFile.absolutePath + ".tmp").delete() }
                    runCatching { vaeJournalFile.delete() }
                    runCatching { File(vaeJournalFile.absolutePath + ".tmp").delete() }
                }
                val splitTerminalState = splitRequestTerminal?.let { terminal ->
                    if (cancellationRequested.get()) terminal.cancel()
                    terminal.current()
                }
                try {
                    splitRequestLease?.awaitFinishAfterProviderCleanup(
                        succeeded = if (usesSplitQnnWorkers) {
                            splitTerminalState == SplitSdxlRequestTerminalState.TRANSFERRED
                        } else {
                            splitOutputTransferred
                        },
                        cancelled = if (usesSplitQnnWorkers) {
                            splitTerminalState == SplitSdxlRequestTerminalState.CANCELLED
                        } else {
                            cancellationRequested.get()
                        },
                        error = splitRequestError
                    )
                } finally {
                    splitRequestTerminal?.let { terminal ->
                        activeSplitSdxlTerminal.compareAndSet(terminal, null)
                    }
                }
            }
    }
    return qnnExecutionBody()
}
