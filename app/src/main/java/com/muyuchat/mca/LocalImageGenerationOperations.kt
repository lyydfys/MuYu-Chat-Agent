package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.engine.SystemRuntimeMonotonicClock
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.nativebridge.NativeQnnBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The local-image generation owner (P5/L8). LocalImageProvider.generate's body moved here verbatim
 * as runLocalImageGeneration(port, ...): the provider keeps begin/cancel/generateQwenImage21/upscale/
 * nativeConfig and supplies state through LocalImageGenerationPort implemented by a private port
 * object. The SplitSdxl request-terminal arbiter pair moved with the body (private -> internal,
 * recorded: the port exposes the same AtomicReference instance, so terminal semantics are unchanged).
 * activeRuntime stays a live volatile read through the port on purpose (begin() updates it mid-call).
 */

internal suspend fun runLocalImageGeneration(
    port: LocalImageGenerationPort,
    model: LocalImageModelRecord,
    prompt: String,
    options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
    onProgress: (LocalImageProgress) -> Unit = {}
): LocalImageResult {
    val appContext = port.appContext
    val textualInversionStore = port.textualInversionStore
    val bridge = port.bridge
    val mnnDiffusionBridge = port.mnnDiffusionBridge
    val qnnBridge = port.qnnBridge
    val qwenImage21Worker = port.qwenImage21Worker
    val qwenImage21RuntimeResident = port.qwenImage21RuntimeResident
    val sdxlCoordinator = port.sdxlCoordinator
    val cancellationRequested = port.cancellationRequested
    val activeSplitSdxlTerminal = port.activeSplitSdxlTerminal
    return withContext(Dispatchers.IO) {
        val options = options.withCanonicalUltraFixControls()
        var textualInversionLease: TextualInversionSelectionLease? = null
        try {
            model.localImageStructuralReadinessMessage()?.let { message -> error(message) }
            require(prompt.isNotBlank()) { "请输入图片描述。" }
            require(!cancellationRequested.get()) { "本地生图已停止" }
            options.validateProductInputContract()
            validateLocalImageRuntimeProductOptions(model.runtime, options)
            if (port.activeRuntime != model.runtime) port.begin(model.runtime)
            if (model.isQwenImage21MnnModel()) {
                return@withContext port.generateQwenImage21(
                    model = model,
                    prompt = prompt,
                    options = options,
                    onProgress = onProgress
                )
            }
            if (qwenImage21RuntimeResident.get()) {
                // The Qwen model is close to 10 GB. Release its isolated MNN context before
                // another image backend is allowed to allocate its own working set.
                try {
                    qwenImage21Worker.unload()
                } finally {
                    qwenImage21RuntimeResident.set(false)
                }
            }
            if (model.runtime == LocalImageRuntime.QNN_HTP) {
            require(NativeQnnBridge.isAvailable) {
                val reason = NativeQnnBridge.loadError?.message.orEmpty()
                "Snapdragon NPU image backend failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(NativeQnnBridge.runnerReady) {
                JSONObject(qnnBridge.getRuntimeStatsJson()).optString("lastError")
                    .ifBlank {
                        "QNN typed graph bindings are unavailable in this APK; rebuild with the QAIRT/QNN SDK headers."
                    }
            }
            require(NativeMnnDiffusionBridge.isAvailable) {
                val reason = NativeMnnDiffusionBridge.loadError?.message.orEmpty()
                "MNN prompt encoder failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(NativeMnnDiffusionBridge.runnerReady) {
                JSONObject(mnnDiffusionBridge.getRuntimeStatsJson()).optString("lastError")
                    .ifBlank { "MNN prompt encoder is not packaged in this APK." }
            }
            model.localImageReadinessMessage()?.let { message -> error(message) }
            val bundleRoot = model.bundleRoot
                ?.let(::File)
                ?.takeIf { it.isDirectory }
                ?: File(model.path).parentFile?.takeIf { it.isDirectory }
                ?: error("QNN image engine requires a complete QNN bundle directory.")
            val runtimeResolution = qnnRuntimeDirectoryResolutionFor(appContext, bundleRoot)
            val qnnHealth = QnnHtpImageRunner(context = appContext).health(
                device = DeviceProfileReader(appContext).read(),
                bundleRoot = bundleRoot
            )
            require(qnnHealth.state == LocalImageQnnState.SMOKE_REQUIRED) {
                qnnHealth.message
            }
            val fallbackSeed = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
            val effectiveOptions = if (options.seed == null) options.copy(seed = fallbackSeed) else options
            val profileResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = effectiveOptions,
                bundleRoot = bundleRoot,
                captureTextualInversionExecutionAssets = true
            ).withQnnProductSchedule(effectiveOptions)
                .withQnnInpaintProductSchedule(effectiveOptions)
            val profile = profileResolution.profile
            validateLocalImageProfileProductOptions(profile, effectiveOptions)
            textualInversionLease = effectiveOptions.textualInversionIds
                .takeIf(List<String>::isNotEmpty)
                ?.let { ids ->
                    require(profile.capabilities.supportsTextualInversion &&
                        profile.tokenizer.supportsTextualInversion &&
                        profile.hasHostWritableClipTextualInversionTopology()
                    ) {
                        "The resolved QNN profile has no host-writable CLIP textual-inversion topology."
                    }
                    val executionAssets = requireNotNull(profile.textualInversionExecutionAssets) {
                        "QNN textual inversion is missing its exact execution-asset snapshot."
                    }
                    textualInversionStore.acquireSelectionLease(
                        ids = ids,
                        modelFingerprint = profile.modelFingerprint,
                        tokenizerFingerprint = executionAssets.compositeSha256,
                        profileId = profile.profileId,
                        profileRevision = profile.profileRevision,
                        runtime = TextualInversionRuntime.QNN_HTP,
                        executionAssetBinding = executionAssets
                    ).also { lease ->
                        TextualInversionContract.validateNativeCapability(
                            runtime = LocalImageRuntime.QNN_HTP.name,
                            graphSupportsTextualInversion =
                                profile.hasHostWritableClipTextualInversionTopology(),
                            selection = lease.selection
                        )
                    }
                }
            val qnnTextualInversionJson = textualInversionLease
                ?.let { lease -> lease.selection.toNativeJson(lease.rootPath).toString() }
                .orEmpty()
            val resolved = profileResolution.layers.resolved
            val conditioningOrder = if (resolved.useCfg) {
                "negative_then_positive"
            } else {
                "positive_only"
            }
            val resolvedFinalNegativePrompt = resolveLocalImageFinalNegativePrompt(
                userNegativePrompt = effectiveOptions.negativePrompt,
                modelDefaultNegativePrompt = profile.defaults.defaultNegativePrompt
            )
            val effectiveFamily = profile.family
            val isSdxlQnn = effectiveFamily == LocalImageModelFamily.SDXL
            val usesSplitQnnWorkers =
                profile.graph.workerStrategy == ImageWorkerStrategy.SPLIT_UNET_VAE
            // Only isolated SDXL workers serialize a conditional-only payload.
            // Shared QNN topologies retain the fixed dual-branch layout.
            val useCfgForSdxlConditioning = !usesSplitQnnWorkers || resolved.useCfg
            val sdxlConditioningOrder = if (useCfgForSdxlConditioning) {
                "negative_then_positive"
            } else {
                "positive_only"
            }
            val expectedSdxlConditioningBranches = if (useCfgForSdxlConditioning) 2 else 1
            val expectedSdxlConditioningExecutions = if (useCfgForSdxlConditioning) 4 else 2
            require(!usesSplitQnnWorkers || effectiveOptions.preview == null) {
                "Split-SDXL uses isolated workers and does not support live preview."
            }
            val usesQnnImg2Img =
                effectiveOptions.taskMode == LocalImageTaskMode.IMG2IMG &&
                    profile.hasExecutableQnnImg2ImgTopology()
            val usesSharedQnnImg2Img = usesQnnImg2Img && profile.hasSharedQnnImg2ImgTopology()
            val usesQnnInpaint =
                effectiveOptions.taskMode == LocalImageTaskMode.INPAINT &&
                    profile.hasExecutableQnnInpaintTopology()
            // Qualcomm's Gen5 archives include a native QNN CLIP graph. Keep
            // legacy QNN archives on their existing MNN-embedding path, while
            // shared-text topologies bind the exact declared .bin artifact.
            val qnnTextEncoderContextPath = when (profile.graph.workerStrategy) {
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE ->
                    qnnDeclaredTextEncoderContextPath(bundleRoot, profile.graph.textEncoder)
                else -> qnnNativeTextEncoderContextPath(bundleRoot)
            }
            val usesQnnClipTokenIds = !isSdxlQnn && qnnTextEncoderContextPath != null
            when (profile.graph.workerStrategy) {
                ImageWorkerStrategy.SHARED_UNET_VAE -> {
                    if (!isSdxlQnn) {
                        require(!usesQnnClipTokenIds &&
                            profile.graph.textEncoder
                                ?.relativePath
                                ?.substringAfterLast('/')
                                ?.substringAfterLast('\\') == "clip_v2.mnn"
                        ) {
                            "Resolved shared QNN UNet/VAE profile must use the installed MNN clip_v2.mnn conditioner."
                        }
                    }
                }
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE -> {
                    require(usesQnnClipTokenIds &&
                        profile.graph.textEncoder?.relativePath?.endsWith(".bin", ignoreCase = true) == true
                    ) {
                        "Resolved shared QNN text/UNet/VAE profile requires a real text-encoder context."
                    }
                }
                else -> Unit
            }
            val conditioningRoot = when {
                isSdxlQnn -> resolveSdxlQnnConditioningRoot(bundleRoot)
                profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_UNET_VAE -> {
                    val relativePath = requireNotNull(profile.graph.textEncoder?.relativePath) {
                        "Shared QNN UNet/VAE profile is missing its MNN conditioning graph path."
                    }
                    requireNotNull(
                        bundleRoot.safeDescendantOrNull(relativePath)
                            ?.takeIf { file -> file.isFile && file.length() > 0L }
                            ?.parentFile
                    ) { "MNN conditioning graph is missing or empty: $relativePath" }
                }
                else -> bundleRoot
            }
            val outputDir = canonicalLocalImageOutputDirectory(
                appContext.cacheDir,
                if (usesSplitQnnWorkers) SDXL_TWO_PHASE_DIRECTORY else "local_image_outputs"
            )
            if (!usesSplitQnnWorkers) {
                runCatching { QnnImageStageJournal.sweepStalePreviewDirectories(outputDir) }
                runCatching { QnnInputImageArtifact.cleanupStaleSharedArtifacts(appContext.cacheDir) }
                runCatching { QnnInpaintInputArtifact.cleanupStaleArtifacts(appContext.cacheDir) }
            }
            val requestToken = "qnn-htp-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            val outputFile = File(outputDir, "$requestToken.png").canonicalFile
            val embeddingFile = File(
                outputDir,
                if (isSdxlQnn) {
                    "${outputFile.nameWithoutExtension}.sdxl-conditioning.f32"
                } else if (usesQnnClipTokenIds) {
                    if (resolved.promptWeightingSupported) {
                        "${outputFile.nameWithoutExtension}.qnn-clip-conditioning.bin"
                    } else {
                        "${outputFile.nameWithoutExtension}.qnn-clip-token-ids.i32"
                    }
                } else {
                    "${outputFile.nameWithoutExtension}.sd15-embeddings.f32"
                }
            )
            val progressJournalFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.qnn-stage.json"
            )
            val latentFile = File(outputDir, "${outputFile.nameWithoutExtension}.latent.f32")
            val latentMetadataFile = File(outputDir, "${outputFile.nameWithoutExtension}.latent.json")
            val inputTensorFile = File(outputDir, "${outputFile.nameWithoutExtension}.input-rgb-nchw.f32")
            val inpaintMaskTensorFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.inpaint-mask-latent.f32"
            )
            val inpaintFullMaskTensorFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.inpaint-mask-full.f32"
            )
            val inpaintMaskedInputTensorFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.inpaint-masked-rgb-nchw.f32"
            )
            val encoderLatentFile = File(outputDir, "${outputFile.nameWithoutExtension}.encoder-latent.f32")
            val encoderMetadataFile = File(outputDir, "${outputFile.nameWithoutExtension}.encoder-latent.json")
            val encoderJournalFile = File(outputDir, "${outputFile.nameWithoutExtension}.encoder-stage.json")
            val unetJournalFile = File(outputDir, "${outputFile.nameWithoutExtension}.unet-stage.json")
            val vaeJournalFile = File(outputDir, "${outputFile.nameWithoutExtension}.vae-stage.json")
            val contract = resolveQnnImageGenerationContract(
                resolution = profileResolution,
                defaultThreads = defaultLocalImageThreads(),
                options = effectiveOptions
            )
            val finalNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(
                finalNegativePrompt = resolvedFinalNegativePrompt,
                useCfg = contract.useCfg
            )
            val executedNegativePrompt = resolveQnnExecutedNegativePrompt(
                useCfg = contract.useCfg,
                effectiveNegativePrompt = finalNegativePrompt.value
            )
            // QNN may discard a model-default negative prompt when CFG is disabled. Admit the
            // final native pair, not the request's pre-resolution negative-prompt value.
            requireLocalImagePromptLanguageAdmission(
                profile = profile,
                prompt = prompt.trim(),
                executedNegativePrompt = executedNegativePrompt
            )
            requireNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)
            val width = contract.width
            val height = contract.height
            val steps = contract.steps
            val threads = contract.threads
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

            val params = ImageExecutionProfileNativeContract.toNativeParamsJson(profileResolution)
                .put("prompt", prompt.trim())
                .put("negativePrompt", executedNegativePrompt)
                .put("family", effectiveFamily.name)
                .put("variant", profile.variant.name)
                .put("width", width)
                .put("height", height)
                .put("steps", steps)
                .put("threads", threads)
                .put("seed", contract.seed)
                .put("cfgScale", contract.cfgScale)
                .put("sampleMethod", contract.sampleMethod)
                .put("backendMode", contract.backendMode)
                .put("tokenEmbeddingMode", contract.tokenEmbeddingMode)
                .put("memoryMode", contract.memoryMode)
                .put("useCfg", contract.useCfg)
                .put("workerStrategy", profile.graph.workerStrategy.name)
                .put("progressJournalPath", progressJournalFile.absolutePath)
                .putQnnSemanticDefaults(
                    bundleRoot = bundleRoot,
                    profile = profile,
                    includeVaeEncoderEvidence = usesQnnImg2Img || usesQnnInpaint
                )
                .putQnnNativeMultilingualTextEncoderEvidence(
                    bundleRoot = bundleRoot,
                    profile = profile
                )
            if (profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_UNET_VAE) {
                params.put("conditioningContractMode", "shared_unet_vae")
            }
            effectiveOptions.putProductInputNativeParams(params)
            if (usesSplitQnnWorkers) {
                require(!params.has("preview")) {
                    "Split-SDXL params must not carry a live-preview request."
                }
            }
            val nativeMultilingualTextEncoderEvidence = profile
                .textEncoderLanguage
                ?.evidence
                ?.takeIf { profile.hasVerifiedNativeSimplifiedChineseTextEncoder() }
            val requiresQnnVersionedTokenPayload =
                resolved.promptWeightingSupported || nativeMultilingualTextEncoderEvidence != null
            if (isSdxlQnn) {
                params.put("conditioningFormat", "sdxl_qnn_conditioning")
            } else if (usesQnnClipTokenIds) {
                params.put(
                    "conditioningFormat",
                    if (requiresQnnVersionedTokenPayload) {
                        "qnn_clip_token_ids_weights_v1"
                    } else {
                        "qnn_clip_token_ids_i32"
                    }
                )
            }
            val splitRequestLease = if (usesSplitQnnWorkers) {
                sdxlCoordinator.prepareLease(
                    requestId = requestToken,
                    params = params,
                    embeddingsFile = embeddingFile,
                    latentFile = latentFile,
                    metadataFile = latentMetadataFile,
                    inputTensorFile = inputTensorFile,
                    encoderLatentFile = encoderLatentFile,
                    encoderMetadataFile = encoderMetadataFile,
                    outputFile = outputFile,
                    encoderJournal = encoderJournalFile,
                    unetJournal = unetJournalFile,
                    vaeJournal = vaeJournalFile,
                    maskTensorFile = inpaintMaskTensorFile.takeIf { usesQnnInpaint },
                    fullMaskTensorFile = inpaintFullMaskTensorFile.takeIf { usesQnnInpaint }
                )
            } else {
                null
            }
            val splitRequestTerminal = if (usesSplitQnnWorkers) {
                SplitSdxlRequestTerminalArbiter().also { terminal ->
                    activeSplitSdxlTerminal.set(terminal)
                    if (cancellationRequested.get()) terminal.cancel()
                }
            } else {
                null
            }
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
        require(model.runtime != LocalImageRuntime.QNN_HTP) {
            "QNN/QAIRT 本地生图 runner 尚未打包。该引擎包需要骁龙 NPU runtime、完整 QNN context/bin 组件，并通过 1-step smoke test 后才能生成。"
        }
        if (model.runtime == LocalImageRuntime.MNN_DIFFUSION) {
            require(NativeMnnDiffusionBridge.isAvailable) {
                val reason = NativeMnnDiffusionBridge.loadError?.message.orEmpty()
                "MNN-Diffusion image backend failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(NativeMnnDiffusionBridge.runnerReady) {
                JSONObject(mnnDiffusionBridge.getRuntimeStatsJson()).optString("lastError")
                    .ifBlank { "MNN-Diffusion native runner is not packaged in this APK." }
            }
            val bundleRoot = model.bundleRoot
                ?.let(::File)
                ?.takeIf(File::isDirectory)
                ?: File(model.path).parentFile?.takeIf(File::isDirectory)
                ?: error("MNN-Diffusion requires a complete model bundle directory.")
            prepareMnnDiffusionTokenizerIfPossible(bundleRoot)
            model.localImageStructuralReadinessMessage()?.let { message -> error(message) }
            val outputDir = canonicalLocalImageOutputDirectory(
                appContext.cacheDir,
                "local_image_outputs"
            )
            pruneStaleMnnDiffusionOutputs(outputDir)
            val outputFile = File(
                outputDir,
                "mnn-diffusion-${UUID.randomUUID()}.png"
            ).canonicalFile
            try {
            val fallbackSeed = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
            val effectiveOptions = if (options.seed == null) options.copy(seed = fallbackSeed) else options
            val profileResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = effectiveOptions,
                bundleRoot = bundleRoot,
                captureTextualInversionExecutionAssets = true
            )
            val profile = profileResolution.profile
            validateLocalImageProfileProductOptions(profile, effectiveOptions)
            textualInversionLease = effectiveOptions.textualInversionIds
                .takeIf(List<String>::isNotEmpty)
                ?.let { ids ->
                    require(profile.capabilities.supportsTextualInversion &&
                        profile.tokenizer.supportsTextualInversion &&
                        profile.hasHostWritableClipTextualInversionTopology()
                    ) {
                        "The resolved MNN profile has no host-writable CLIP textual-inversion topology."
                    }
                    val executionAssets = requireNotNull(profile.textualInversionExecutionAssets) {
                        "MNN textual inversion is missing its exact execution-asset snapshot."
                    }
                    textualInversionStore.acquireSelectionLease(
                        ids = ids,
                        modelFingerprint = profile.modelFingerprint,
                        tokenizerFingerprint = executionAssets.compositeSha256,
                        profileId = profile.profileId,
                        profileRevision = profile.profileRevision,
                        runtime = TextualInversionRuntime.MNN_DIFFUSION,
                        executionAssetBinding = executionAssets
                    ).also { lease ->
                        TextualInversionContract.validateNativeCapability(
                            runtime = LocalImageRuntime.MNN_DIFFUSION.name,
                            graphSupportsTextualInversion =
                                profile.hasHostWritableClipTextualInversionTopology(),
                            selection = lease.selection
                        )
                    }
                }
            val resolved = profileResolution.layers.resolved
            val effectiveNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(
                finalNegativePrompt = resolveLocalImageFinalNegativePrompt(
                    userNegativePrompt = effectiveOptions.negativePrompt,
                    modelDefaultNegativePrompt = profile.defaults.defaultNegativePrompt
                ),
                useCfg = resolved.useCfg
            ).value
            requireLocalImagePromptLanguageAdmission(
                profile = profile,
                prompt = prompt.trim(),
                executedNegativePrompt = effectiveNegativePrompt
            )
            requireNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)
            val nativeTextEncoderBinding = captureMnnNativeTextEncoderBinding(bundleRoot, profile)
            val runner = resolveMnnDiffusionProfileRunner(profile, effectiveOptions.runner)
            val (defaultWidth, defaultHeight) = resolved.width to resolved.height
            // The direct SD1.5 interpreter has a fixed 64x64 latent today.
            // Accepting a mismatched requested size would produce a misleading
            // result, so reject it rather than silently rendering another size.
            val (width, height) = resolveMnnDiffusionDimensions(
                defaultWidth = defaultWidth,
                defaultHeight = defaultHeight,
                requestedWidth = resolved.width,
                requestedHeight = resolved.height
            )
            val steps = resolved.steps
            val threads = resolveMnnDiffusionThreads(effectiveOptions.threads, defaultLocalImageThreads())
            // Keep the distinction between an omitted backend (automatic compatibility
            // selection) and an explicit user choice.  The shipped SD1.5 MNN graph is
            // OpenCL-capable but its fused UNet operators are not registered by our CPU
            // build, so only the automatic path may retry on OpenCL after a proven CPU
            // NOT_SUPPORT result.
            val requestedBackendMode = requestedMnnDiffusionBackendMode(effectiveOptions.backendMode)
            var backendMode = resolveMnnDiffusionBackendMode(effectiveOptions.backendMode)
            val backendAttempts = mutableListOf<MnnDiffusionBackendAttempt>()
            var backendFallbackApplied = false
            var backendFallbackReason: String? = null
            val sampleMethod = imageSchedulerProductName(resolved.scheduler)
            require(effectiveOptions.tokenEmbeddingMode == null) {
                "MNN-Diffusion no longer accepts tokenEmbeddingMode; token table precision is " +
                    "determined from the package's exact byte contract."
            }
            val memoryMode = resolveMnnDiffusionMemoryMode(effectiveOptions.memoryMode)
            require(runner != "direct" || memoryMode == 0) {
                "MNN-Diffusion direct runner requires memoryMode=0."
            }
            val seed = resolved.seed.toInt()
            val cfgScale = resolved.cfgScale
            val useCfg = resolved.useCfg
            // Rebuilt per attempt so an OpenCL failure can retry once on CPU.
            fun mnnNativeParams(backend: String): JSONObject {
                val p = ImageExecutionProfileNativeContract.toNativeParamsJson(profileResolution)
                    .put("prompt", prompt.trim())
                    .put("negativePrompt", effectiveNegativePrompt)
                    .put("family", profile.family.name)
                    .put("variant", profile.variant.name)
                    .put("width", width)
                    .put("height", height)
                    .put("steps", steps)
                    .put("threads", threads)
                    .put("seed", seed)
                    .put("randomSeed", seed)
                    .put("cfgScale", cfgScale)
                    .put("useCfg", useCfg)
                    .put("sampleMethod", sampleMethod)
                    .put("runner", runner)
                    .put("backendMode", backend)
                    // MNN's direct VAE writer needs the profile-declared
                    // output range just like QNN. Keep this field local to
                    // the MNN execution payload so legacy shared contract
                    // consumers remain backward compatible.
                    .put("pixelRange", profile.vae.outputRange.name)
                    .put("memoryMode", memoryMode)
                effectiveOptions.putProductInputNativeParams(p)
                nativeTextEncoderBinding?.let { binding ->
                    p.put("nativeTextEncoderEvidence", binding.toNativeJson())
                }
                textualInversionLease?.let { lease ->
                    val nativeSelection = lease.selection.toNativeJson(lease.rootPath)
                    nativeSelection.keys().forEach { key ->
                        p.put(key, nativeSelection.get(key))
                    }
                }
                return p
            }
            val initialParams = mnnNativeParams(backendMode)
            onProgress(
                LocalImageProgress(
                    phase = "request_validated",
                    message = "MNN-Diffusion request controls validated; native execution has not started.",
                    step = 0,
                    steps = steps,
                    elapsedMs = 0L,
                    secondsPerStep = 0.0,
                    threads = threads,
                    width = width,
                    height = height,
                    cancelRequested = false,
                    requestOptionsJson = mnnDiffusionControlAuditJson(initialParams).toString()
                )
            )
            val progressPoller = launch {
                while (isActive) {
                    mnnDiffusionBridge.currentProgressOrNull()?.let(onProgress)
                    delay(500)
                }
            }
            fun mnnGenerate(backend: String): JSONObject {
                val result = JSONObject(
                    mnnDiffusionBridge.generate(
                        bundleRoot.absolutePath,
                        mnnNativeParams(backend).toString(),
                        outputFile.canonicalPath
                    )
                )
                backendAttempts += MnnDiffusionBackendAttempt(
                    backend = backend,
                    ok = result.optBoolean("ok", false),
                    errorCode = result.optString("errorCode").trim().ifBlank { null },
                    error = result.optString("error").trim().ifBlank { null }
                )
                return result
            }
            var json: JSONObject
            try {
                json = mnnGenerate(backendMode)
                if (!json.optBoolean("ok", false) &&
                    !json.optBoolean("cancelled", false) &&
                    !cancellationRequested.get() &&
                    requestedBackendMode == "auto" &&
                    backendMode == "cpu" &&
                    isMnnCpuBackendUnsupported(json)
                ) {
                    // This is a graph-proven compatibility fallback, not a chipset
                    // allowlist.  Preserve the complete CPU failure in backendAttempts.
                    backendFallbackApplied = true
                    backendFallbackReason = json.optString("error").trim().ifBlank {
                        json.optString("errorCode").trim().ifBlank {
                            "CPU UNet returned NOT_SUPPORT during graph initialization."
                        }
                    }
                    backendMode = "opencl"
                    json = mnnGenerate(backendMode)
                }
                // Keep a narrow automatic fallback for a future package whose preferred
                // backend is OpenCL but whose device reports an OpenCL execution failure.
                // Explicit OpenCL requests remain explicit and never silently change backend.
                if (!json.optBoolean("ok", false) &&
                    !json.optBoolean("cancelled", false) &&
                    !cancellationRequested.get() &&
                    requestedBackendMode == "auto" &&
                    backendMode == "opencl" &&
                    backendAttempts.none { it.backend == "cpu" }
                ) {
                    val openClFailure = json
                    backendFallbackApplied = true
                    backendFallbackReason = openClFailure.optString("error").trim().ifBlank {
                        openClFailure.optString("errorCode").trim().ifBlank {
                            "OpenCL execution failed."
                        }
                    }
                    backendMode = "cpu"
                    json = mnnGenerate(backendMode)
                }
            } finally {
                progressPoller.cancelAndJoin()
                mnnDiffusionBridge.currentProgressOrNull()?.let(onProgress)
            }
            if (!json.optBoolean("ok", false)) {
                if (json.optBoolean("cancelled", false) || cancellationRequested.get()) {
                    throw LocalImageWorkerCancelledException()
                }
                if (requestedBackendMode == "cpu" && isMnnCpuBackendUnsupported(json)) {
                    json.put("errorCode", MNN_UNET_BACKEND_UNSUPPORTED_ERROR_CODE)
                    json.put(
                        "error",
                        "MNN SD1.5 CPU backend does not support the fused UNet operators " +
                            "in this bundle (GroupNorm/FmhaV2/SplitGeLU). " +
                            json.optString("error").trim()
                    )
                }
                json.withMnnBackendAudit(
                    requested = requestedBackendMode,
                    effective = backendMode,
                    attempts = backendAttempts,
                    fallback = backendFallbackApplied,
                    fallbackReason = backendFallbackReason
                )
                port.logLocalImageNativeFailure(json, "MNN-Diffusion image generation failed.")
                throwLocalImageNativeFailure(json, "MNN-Diffusion image generation failed.")
            }
            json.withMnnBackendAudit(
                requested = requestedBackendMode,
                effective = backendMode,
                attempts = backendAttempts,
                fallback = backendFallbackApplied,
                fallbackReason = backendFallbackReason
            )
            ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
            nativeTextEncoderBinding?.verifyNativeReceipt(json)
            require(mnnDiffusionBackendMatches(backendMode, json.getString("backendMode"))) {
                "MNN-Diffusion did not execute on the resolved $backendMode backend."
            }
            require(json.getString("runner").trim().lowercase() == runner) {
                "MNN-Diffusion did not execute with the resolved $runner runner."
            }
            require(json.getString("sampleMethod") == sampleMethod) {
                "MNN-Diffusion did not execute the resolved scheduler."
            }
            require(json.getInt("memoryMode") == memoryMode) {
                "MNN-Diffusion did not execute the resolved memory mode."
            }
            val inputExecutionAudit = verifyAndSanitizeMnnProductInput(json, effectiveOptions)
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
            verifyMnnPublishedOutputEvidence(
                nativeResult = json,
                expectedOutputFile = outputFile,
                verifiedOutput = verifiedOutput
            )
            val textualInversionEvidence = textualInversionLease?.let { lease ->
                verifyStableDiffusionTextualInversionEvidence(
                    result = json,
                    selection = lease.selection,
                    expectedNativeMode = TextualInversionRuntime.MNN_DIFFUSION.nativeMode
                )
            }
            val executionMetadataJson = sanitizeNativeExecutionJson(
                json
                    .put("imageInput", inputExecutionAudit)
                    .putPromptExecutionBinding(
                        profile = profile,
                        prompt = prompt.trim(),
                        negativePrompt = effectiveNegativePrompt
                    )
                    .toString()
            ).takeIf(String::isNotBlank)
                ?: error("MNN-Diffusion execution evidence could not be sanitized.")
            val result = LocalImageResult(
                bytes = verifiedOutput.bytes,
                mimeType = verifiedOutput.mimeType,
                executionMetadataJson = executionMetadataJson,
                seed = seed.toLong()
            )
            coroutineContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            if (textualInversionEvidence != null) {
                val lease = requireNotNull(textualInversionLease)
                lease.verifyUnchanged()
                lease.close()
                textualInversionLease = null
                textualInversionStore.commitSuccessfulBindings(
                    selection = lease.selection,
                    nativeBindingFingerprint = textualInversionEvidence.bindingFingerprint,
                    nativeBindingStage = textualInversionEvidence.bindingStage
                )
            }
            return@withContext result
            } finally {
                cleanupMnnDiffusionRequestOutputs(outputFile)
            }
        }
        require(model.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            "当前仅支持 stable-diffusion.cpp 或 MNN-Diffusion 本地图像引擎。"
        }
        require(NativeStableDiffusionBridge.isAvailable) {
            val reason = NativeStableDiffusionBridge.loadError?.message.orEmpty()
            "stable-diffusion.cpp 本地后端加载失败${if (reason.isBlank()) "" else "：$reason"}"
        }
        model.localImageStructuralReadinessMessage()?.let { message -> error(message) }
        val componentSelection = resolveStableDiffusionComponentSelection(model)
        val effectiveFamily = componentSelection.family
        val bundleRoot = File(componentSelection.bundleRoot).takeIf(File::isDirectory)
            ?: error("stable-diffusion.cpp requires a complete model bundle directory.")
        val fallbackSeed = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
        var effectiveOptions = options.copy(seed = options.seed ?: fallbackSeed)
            .normalizedForPromptExecutionProfile(LocalImageRuntime.STABLE_DIFFUSION_CPP)
        var profileResolution = resolveLocalImageExecutionProfile(
            model = model,
            options = effectiveOptions,
            bundleRoot = bundleRoot,
            familyOverride = effectiveFamily,
            captureTextualInversionExecutionAssets = true
        )
        // Only the resolved 2.1 GGUF graph may use Viggle's distilled timetable. The
        // optional adapter changes recommended defaults, never an explicit step choice.
        val selectedViggle = profileResolution.profile.profileId == "sdcpp.qwen-image-2.1" &&
            options.loras.size == 1 &&
            QwenImage21TurboSchedule.isOfficialV021(options.loras.single())
        val viggleSteps = if (selectedViggle) options.steps ?: 6 else options.steps
        val useViggleTimetable = selectedViggle &&
            viggleSteps?.let { it in 4..8 } == true &&
            QwenImage21TurboSchedule.acceptsControls(options)
        if (useViggleTimetable) {
            effectiveOptions = options.copy(
                seed = effectiveOptions.seed,
                steps = viggleSteps,
                cfgScale = options.cfgScale ?: 1.0,
                useCfg = options.useCfg ?: false,
                negativePrompt = options.negativePrompt ?: ""
            ).normalizedForPromptExecutionProfile(LocalImageRuntime.STABLE_DIFFUSION_CPP)
            profileResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = effectiveOptions,
                bundleRoot = bundleRoot,
                familyOverride = effectiveFamily,
                captureTextualInversionExecutionAssets = true
            )
        }
        val profile = profileResolution.profile
        validateLocalImageProfileProductOptions(profile, effectiveOptions)
        textualInversionLease = effectiveOptions.textualInversionIds
            .takeIf(List<String>::isNotEmpty)
            ?.let { ids ->
                require(profile.capabilities.supportsTextualInversion &&
                    profile.tokenizer.supportsTextualInversion
                ) {
                    "The resolved stable-diffusion.cpp profile does not expose textual inversion."
                }
                val executionAssets = requireNotNull(profile.textualInversionExecutionAssets) {
                    "stable-diffusion.cpp textual inversion is missing its exact execution-asset snapshot."
                }
                textualInversionStore.acquireSelectionLease(
                    ids = ids,
                    modelFingerprint = profile.modelFingerprint,
                    tokenizerFingerprint = executionAssets.compositeSha256,
                    profileId = profile.profileId,
                    profileRevision = profile.profileRevision,
                    executionAssetBinding = executionAssets
                ).also { lease ->
                    TextualInversionContract.validateNativeCapability(
                        runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP.name,
                        graphSupportsTextualInversion =
                            profile.capabilities.supportsTextualInversion &&
                                profile.tokenizer.supportsTextualInversion,
                        selection = lease.selection
                    )
                }
            }
        val nativeComponentSelection = componentSelection
            .withControlNetPath(resolveProfileControlNetPath(bundleRoot, profile))
            .let { selection ->
                if (profile.textEncoderLanguageCapability() ==
                    LocalImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL
                ) {
                    selection.requireTextEncoderPath(
                        requireNotNull(resolveProfileTextEncoderPath(bundleRoot, profile)) {
                            "Native multilingual profile is missing its evidence-bound text encoder."
                        }
                    )
                } else {
                    selection
                }
            }
        val resolved = profileResolution.layers.resolved

        val outputDir = canonicalLocalImageOutputDirectory(
            appContext.cacheDir,
            "local_image_outputs"
        )
        pruneStaleStableDiffusionOutputs(outputDir)
        val outputFile = File(
            outputDir,
            "sdcpp-${UUID.randomUUID()}.png"
        ).canonicalFile
        try {
        val (width, height) = resolveStableDiffusionDimensions(
            defaultWidth = resolved.width,
            defaultHeight = resolved.height,
            requestedWidth = resolved.width,
            requestedHeight = resolved.height,
            dimensionMultiple = if (profile.variant == ImageModelVariant.QWEN_IMAGE_21) 32 else 64
        )
        val steps = resolved.steps
        val threads = resolveStableDiffusionThreads(effectiveOptions.threads, defaultLocalImageThreads())
        val seed = resolved.seed
        val cfgScale = resolved.cfgScale
        val distilledGuidance = resolveStableDiffusionFiniteControl(
            name = "distilledGuidance",
            requested = effectiveOptions.distilledGuidance,
            defaultValue = 3.5
        )
        val flowShift = resolveStableDiffusionFiniteControl(
            name = "flowShift",
            requested = effectiveOptions.flowShift,
            defaultValue = defaultStableDiffusionFlowShiftFor(profile)
        )
        val sampleMethod = imageSchedulerProductName(resolved.scheduler)
        val backendMode = resolveStableDiffusionBackendMode(effectiveOptions.backendMode)
        val effectiveNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(
            finalNegativePrompt = resolveLocalImageFinalNegativePrompt(
                userNegativePrompt = effectiveOptions.negativePrompt,
                modelDefaultNegativePrompt = profile.defaults.defaultNegativePrompt
            ),
            useCfg = resolved.useCfg
        ).value
        requireLocalImagePromptLanguageAdmission(
            profile = profile,
            prompt = prompt.trim(),
            executedNegativePrompt = effectiveNegativePrompt
        )
        requireNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)
        require(effectiveOptions.runner == null) {
            "stable-diffusion.cpp does not support the MNN runner option."
        }
        require(effectiveOptions.tokenEmbeddingMode == null) {
            "stable-diffusion.cpp no longer accepts tokenEmbeddingMode; its native tokenizer owns conditioning storage."
        }
        require(effectiveOptions.memoryMode == null || effectiveOptions.memoryMode == 0) {
            "stable-diffusion.cpp supports only memoryMode=0."
        }
        val params = ImageExecutionProfileNativeContract.toNativeParamsJson(profileResolution)
            .put("prompt", prompt.trim())
            .put("negativePrompt", effectiveNegativePrompt)
            .put("family", effectiveFamily.name)
            .put("variant", profile.variant.name)
            .put("width", width)
            .put("height", height)
            .put("steps", steps)
            .put("threads", threads)
            .put("seed", seed)
            .put("cfgScale", cfgScale)
            .put("distilledGuidance", distilledGuidance)
            .put("distilledGuidanceSpecified", effectiveOptions.distilledGuidance != null)
            .put("flowShift", flowShift)
            .put("flowShiftSpecified", effectiveOptions.flowShift != null)
            .put("sampleMethod", sampleMethod)
            .put("backendMode", backendMode)
        effectiveOptions.putProductInputNativeParams(params)
        val qwenTurboSchedule = if (useViggleTimetable) {
            QwenImage21TurboSchedule.selectedFor(profile, effectiveOptions.loras, steps)
        } else {
            null
        }
        qwenTurboSchedule?.let { params.put("qwenTurboSchedule", it) }
        textualInversionLease?.let { lease ->
            val nativeTextualInversionParams = TextualInversionContract.run {
                lease.selection.toNativeJson(rootPath = lease.rootPath)
            }
            nativeTextualInversionParams.keys().forEach { key ->
                params.put(key, nativeTextualInversionParams.get(key))
            }
        }
        nativeComponentSelection.putIntoNativeParams(params)

        val progressPoller = launch {
            while (isActive) {
                bridge.currentProgressOrNull()?.let(onProgress)
                delay(500)
            }
        }
        val raw = try {
            bridge.generate(
                componentSelection.primaryPath,
                componentSelection.bundleRoot,
                params.toString(),
                outputFile.canonicalPath
            )
        } finally {
            progressPoller.cancelAndJoin()
            bridge.currentProgressOrNull()?.let(onProgress)
            // stable-diffusion.cpp keeps a process-global context for reuse.
            // The product worker is disposable and must return the model's
            // multi-gigabyte mappings after every terminal outcome instead.
            runCatching { bridge.shutdown() }
            stableDiffusionPreviewCandidates(outputFile).forEach { preview ->
                runCatching { preview.delete() }
            }
        }
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) {
            if (json.optBoolean("cancelled", false) || cancellationRequested.get()) {
                throw LocalImageWorkerCancelledException()
            }
            port.logLocalImageNativeFailure(json, "stable-diffusion.cpp image generation failed.")
            throwLocalImageNativeFailure(json, "stable-diffusion.cpp image generation failed.")
        }
        ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
        if (profile.variant == ImageModelVariant.QWEN_IMAGE_21) {
            requireQwenImage21GgufExecutionEvidence(json)
            QwenImage21TurboSchedule.verifyNativeEcho(json, qwenTurboSchedule)
        }
        require(json.optInt("width", -1) == width && json.optInt("height", -1) == height) {
            "stable-diffusion.cpp did not execute the requested ${width}x${height} dimensions."
        }
        require(json.optInt("steps", -1) == steps) {
            "stable-diffusion.cpp did not execute the requested $steps steps."
        }
        require(json.optInt("threads", -1) == threads) {
            "stable-diffusion.cpp did not execute the requested $threads threads."
        }
        require(json.has("seed") && json.getLong("seed") == seed) {
            "stable-diffusion.cpp did not execute the requested seed."
        }
        verifyStableDiffusionResultControl(json, "cfgScale", cfgScale)
        verifyStableDiffusionDistilledGuidanceResult(
            result = json,
            requested = distilledGuidance,
            specified = effectiveOptions.distilledGuidance != null
        )
        verifyStableDiffusionFlowShiftResult(
            result = json,
            requested = flowShift,
            specified = effectiveOptions.flowShift != null,
            expectApplied = resolved.predictionType == ImagePredictionType.FLOW
        )
        require(stableDiffusionNativeSampleMethodMatches(resolved.scheduler, json.optString("sampleMethod"))) {
            "stable-diffusion.cpp did not execute the resolved ${resolved.scheduler} sampler."
        }
        require(
            json.has("negativePrompt") &&
                json.getString("negativePrompt") == effectiveNegativePrompt
        ) {
            "stable-diffusion.cpp did not execute the resolved negative prompt."
        }
        require(json.optString("backendMode") == backendMode) {
            "stable-diffusion.cpp did not execute on backendMode=$backendMode."
        }
        require(json.optBoolean("contextReleased", false)) {
            "stable-diffusion.cpp did not confirm native context release."
        }
        val textualInversionEvidence = verifyStableDiffusionTextualInversionEvidence(
            result = json,
            selection = textualInversionLease?.selection
        )
        val componentSelectionAudit = nativeComponentSelection.verifyNativeEcho(json)
        val inputExecutionAudit = verifyAndSanitizeStableDiffusionProductInput(json, effectiveOptions)
        val outputs = consumeStableDiffusionOutputs(
            result = json,
            expectedCount = effectiveOptions.batchCount,
            expectedSeed = seed,
            legacyOutputFile = outputFile,
            requireCommittedEvidence = effectiveOptions.ultraFix != null
        )
        verifyStableDiffusionUltraFixEvidence(
            result = json,
            request = effectiveOptions.ultraFix,
            inputImage = effectiveOptions.inputImage,
            requestedUseCfg = resolved.useCfg,
            outputs = outputs
        )
        val executionMetadataJson = sanitizeNativeExecutionJson(
            json
                .put("componentSelection", componentSelectionAudit)
                .put("imageInput", inputExecutionAudit)
                .putPromptExecutionBinding(
                    profile = profile,
                    prompt = prompt.trim(),
                    negativePrompt = effectiveNegativePrompt
                )
                .toString()
        ).takeIf(String::isNotBlank)
            ?: error("stable-diffusion.cpp execution evidence could not be sanitized.")
        val first = outputs.first()
        val result = LocalImageResult(
            bytes = first.bytes,
            mimeType = first.mimeType,
            executionMetadataJson = executionMetadataJson,
            seed = first.seed,
            outputs = outputs
        )
        coroutineContext.ensureActive()
        if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
        textualInversionLease?.let { lease ->
            lease.verifyUnchanged()
            lease.close()
            textualInversionLease = null
            textualInversionStore.commitSuccessfulBindings(
                selection = lease.selection,
                nativeBindingFingerprint = textualInversionEvidence.bindingFingerprint,
                nativeBindingStage = textualInversionEvidence.bindingStage
            )
        }
        result
        } finally {
            cleanupStableDiffusionRequestOutputs(outputFile)
        }
        } finally {
            textualInversionLease?.close()
            textualInversionLease = null
            if (port.activeRuntime == model.runtime) {
                port.activeRuntime = null
                cancellationRequested.set(false)
            }
        }
    }
}
