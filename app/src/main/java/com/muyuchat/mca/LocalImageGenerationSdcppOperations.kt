package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal suspend fun CoroutineScope.runStableDiffusionCppGeneration(
    port: LocalImageGenerationPort,
    model: LocalImageModelRecord,
    prompt: String,
    options: LocalImageGenerationOptions,
    appContext: Context,
    onProgress: (LocalImageProgress) -> Unit,
    bridgeRef: () -> NativeStableDiffusionBridge,
    textualInversionStoreRef: () -> TextualInversionStore,
    cancellationRequested: AtomicBoolean,
    textualInversionLeaseCell: LocalImageTextualInversionLeaseCell
): LocalImageResult {
    val bridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { bridgeRef() }
    val textualInversionStore by lazy(LazyThreadSafetyMode.NONE) { textualInversionStoreRef() }
    var textualInversionLease: TextualInversionSelectionLease? by textualInversionLeaseCell
    val sdcppBody: suspend () -> LocalImageResult = withContext@ {
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
    }
    return sdcppBody()
}
