package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.nativebridge.NativeQnnBridge
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

internal suspend fun CoroutineScope.runQnnHtpPreparation(
    port: LocalImageGenerationPort,
    model: LocalImageModelRecord,
    prompt: String,
    options: LocalImageGenerationOptions,
    appContext: Context,
    onProgress: (LocalImageProgress) -> Unit,
    textualInversionStoreRef: () -> TextualInversionStore,
    qnnBridgeRef: () -> NativeQnnBridge,
    mnnDiffusionBridgeRef: () -> NativeMnnDiffusionBridge,
    sdxlCoordinatorRef: () -> SdxlTwoPhaseCoordinator,
    cancellationRequested: AtomicBoolean,
    activeSplitSdxlTerminal: AtomicReference<SplitSdxlRequestTerminalArbiter?>,
    textualInversionLeaseCell: LocalImageTextualInversionLeaseCell
): QnnHtpExecutionContext {
    val textualInversionStore by lazy(LazyThreadSafetyMode.NONE) { textualInversionStoreRef() }
    val qnnBridge by lazy(LazyThreadSafetyMode.NONE) { qnnBridgeRef() }
    val mnnDiffusionBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { mnnDiffusionBridgeRef() }
    val sdxlCoordinator by lazy(LazyThreadSafetyMode.NONE) { sdxlCoordinatorRef() }
    var textualInversionLease: TextualInversionSelectionLease? by textualInversionLeaseCell
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

    return QnnHtpExecutionContext(
        scope = this,
        port = port,
        prompt = prompt,
        appContext = appContext,
        onProgress = onProgress,
        cancellationRequested = cancellationRequested,
        activeSplitSdxlTerminal = activeSplitSdxlTerminal,
        textualInversionLeaseCell = textualInversionLeaseCell,
        textualInversionStoreRef = textualInversionStoreRef,
        qnnBridgeRef = qnnBridgeRef,
        mnnDiffusionBridgeRef = mnnDiffusionBridgeRef,
        sdxlCoordinatorRef = sdxlCoordinatorRef,
        bundleRoot = bundleRoot,
        runtimeResolution = runtimeResolution,
        effectiveOptions = effectiveOptions,
        profileResolution = profileResolution,
        profile = profile,
        resolved = resolved,
        conditioningOrder = conditioningOrder,
        qnnTextualInversionJson = qnnTextualInversionJson,
        useCfgForSdxlConditioning = useCfgForSdxlConditioning,
        usesQnnClipTokenIds = usesQnnClipTokenIds,
        isSdxlQnn = isSdxlQnn,
        nativeMultilingualTextEncoderEvidence = nativeMultilingualTextEncoderEvidence,
        expectedSdxlConditioningExecutions = expectedSdxlConditioningExecutions,
        expectedSdxlConditioningBranches = expectedSdxlConditioningBranches,
        sdxlConditioningOrder = sdxlConditioningOrder,
        conditioningRoot = conditioningRoot,
        requestToken = requestToken,
        outputFile = outputFile,
        embeddingFile = embeddingFile,
        progressJournalFile = progressJournalFile,
        latentFile = latentFile,
        latentMetadataFile = latentMetadataFile,
        inputTensorFile = inputTensorFile,
        inpaintMaskTensorFile = inpaintMaskTensorFile,
        inpaintFullMaskTensorFile = inpaintFullMaskTensorFile,
        inpaintMaskedInputTensorFile = inpaintMaskedInputTensorFile,
        encoderLatentFile = encoderLatentFile,
        encoderMetadataFile = encoderMetadataFile,
        encoderJournalFile = encoderJournalFile,
        unetJournalFile = unetJournalFile,
        vaeJournalFile = vaeJournalFile,
        contract = contract,
        executedNegativePrompt = executedNegativePrompt,
        width = width,
        height = height,
        steps = steps,
        threads = threads,
        params = params,
        usesSplitQnnWorkers = usesSplitQnnWorkers,
        usesQnnImg2Img = usesQnnImg2Img,
        usesQnnInpaint = usesQnnInpaint,
        usesSharedQnnImg2Img = usesSharedQnnImg2Img,
        splitRequestLease = splitRequestLease,
        splitRequestTerminal = splitRequestTerminal
    )
}

internal class QnnHtpExecutionContext(
    scope: CoroutineScope,
    val port: LocalImageGenerationPort,
    val prompt: String,
    val appContext: Context,
    val onProgress: (LocalImageProgress) -> Unit,
    val cancellationRequested: AtomicBoolean,
    val activeSplitSdxlTerminal: AtomicReference<SplitSdxlRequestTerminalArbiter?>,
    val textualInversionLeaseCell: LocalImageTextualInversionLeaseCell,
    private val textualInversionStoreRef: () -> TextualInversionStore,
    private val qnnBridgeRef: () -> NativeQnnBridge,
    private val mnnDiffusionBridgeRef: () -> NativeMnnDiffusionBridge,
    private val sdxlCoordinatorRef: () -> SdxlTwoPhaseCoordinator,
    val bundleRoot: File,
    val runtimeResolution: QnnRuntimeDirectoryResolution,
    val effectiveOptions: LocalImageGenerationOptions,
    val profileResolution: ImageExecutionProfileResolution,
    val profile: ImageExecutionProfile,
    val resolved: ImageResolvedExecution,
    val conditioningOrder: String,
    val qnnTextualInversionJson: String,
    val useCfgForSdxlConditioning: Boolean,
    val usesQnnClipTokenIds: Boolean,
    val isSdxlQnn: Boolean,
    val nativeMultilingualTextEncoderEvidence: ImageTextEncoderLanguageEvidence?,
    val expectedSdxlConditioningExecutions: Int,
    val expectedSdxlConditioningBranches: Int,
    val sdxlConditioningOrder: String,
    val conditioningRoot: File,
    val requestToken: String,
    val outputFile: File,
    val embeddingFile: File,
    val progressJournalFile: File,
    val latentFile: File,
    val latentMetadataFile: File,
    val inputTensorFile: File,
    val inpaintMaskTensorFile: File,
    val inpaintFullMaskTensorFile: File,
    val inpaintMaskedInputTensorFile: File,
    val encoderLatentFile: File,
    val encoderMetadataFile: File,
    val encoderJournalFile: File,
    val unetJournalFile: File,
    val vaeJournalFile: File,
    val contract: QnnImageGenerationContract,
    val executedNegativePrompt: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val threads: Int,
    val params: JSONObject,
    val usesSplitQnnWorkers: Boolean,
    val usesQnnImg2Img: Boolean,
    val usesQnnInpaint: Boolean,
    val usesSharedQnnImg2Img: Boolean,
    val splitRequestLease: SdxlTwoPhaseRequestLease?,
    val splitRequestTerminal: SplitSdxlRequestTerminalArbiter?
) : CoroutineScope by scope {
    val textualInversionStore: TextualInversionStore get() = textualInversionStoreRef()
    val qnnBridge: NativeQnnBridge get() = qnnBridgeRef()
    val mnnDiffusionBridge: NativeMnnDiffusionBridge get() = mnnDiffusionBridgeRef()
    val sdxlCoordinator: SdxlTwoPhaseCoordinator get() = sdxlCoordinatorRef()
}
