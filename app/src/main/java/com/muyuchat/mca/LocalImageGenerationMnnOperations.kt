package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
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

internal suspend fun CoroutineScope.runMnnDiffusionGeneration(
    port: LocalImageGenerationPort,
    model: LocalImageModelRecord,
    prompt: String,
    options: LocalImageGenerationOptions,
    appContext: Context,
    onProgress: (LocalImageProgress) -> Unit,
    mnnDiffusionBridgeRef: () -> NativeMnnDiffusionBridge,
    textualInversionStoreRef: () -> TextualInversionStore,
    cancellationRequested: AtomicBoolean,
    textualInversionLeaseCell: LocalImageTextualInversionLeaseCell
): LocalImageResult {
    val mnnDiffusionBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { mnnDiffusionBridgeRef() }
    val textualInversionStore by lazy(LazyThreadSafetyMode.NONE) { textualInversionStoreRef() }
    var textualInversionLease: TextualInversionSelectionLease? by textualInversionLeaseCell
    val mnnBody: suspend () -> LocalImageResult = withContext@ {
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
    return mnnBody()
}
