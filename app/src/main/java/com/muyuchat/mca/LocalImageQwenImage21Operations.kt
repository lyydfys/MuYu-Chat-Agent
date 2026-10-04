package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.SystemRuntimeMonotonicClock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Qwen-Image-2.1 worker-generation owner (P5/L10). LocalImageProvider.generateQwenImage21's body
 * moved here verbatim as runQwenImage21Generation(port, ...): request-id publication, resident-handle
 * latching, cancellation cleanup and the unconditional disconnect at the boundary still run in the
 * same order on the same AtomicBoolean/AtomicReference instances supplied through
 * LocalImageQwenImage21Port. qwenImage21QnnRuntimeDirectory keeps the advisory-only runtime discovery;
 * native loading remains the compatibility authority.
 */

internal interface LocalImageQwenImage21Port {
    val appContext: Context
    val qwenImage21Worker: QwenImage21WorkerClient
    val qwenImage21RuntimeResident: AtomicBoolean
    val activeQwenImage21RequestId: AtomicReference<String?>
    val cancellationRequested: AtomicBoolean
}

internal suspend fun runQwenImage21Generation(
    port: LocalImageQwenImage21Port,
    model: LocalImageModelRecord,
    prompt: String,
    options: LocalImageGenerationOptions,
    onProgress: (LocalImageProgress) -> Unit
): LocalImageResult {
    val appContext = port.appContext
    val qwenImage21Worker = port.qwenImage21Worker
    val qwenImage21RuntimeResident = port.qwenImage21RuntimeResident
    val activeQwenImage21RequestId = port.activeQwenImage21RequestId
    val cancellationRequested = port.cancellationRequested
    val generationContext = coroutineContext
    val bundleRoot = model.bundleRoot
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.takeIf(File::isDirectory)
        ?.canonicalFile
        ?: File(model.path).canonicalFile.parentFile
        ?: error("Qwen-Image-2.1 requires its complete MNN model bundle directory.")
    val primary = File(model.path).canonicalFile
    val expectedPrimary = File(bundleRoot, "dit.mnn").canonicalFile
    require(primary == expectedPrimary) {
        "Qwen-Image-2.1 primary model must be dit.mnn directly inside its installed bundle."
    }
    LocalImageBundleContract.inspectQwenImage21Bundle(bundleRoot, primary)
        .readinessMessage()
        ?.let(::error)

    val seed = options.seed ?: (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
    val effectiveOptions = options.copy(seed = seed)
    val profileResolution = resolveLocalImageExecutionProfile(
        model = model,
        options = effectiveOptions,
        bundleRoot = bundleRoot
    )
    val profile = profileResolution.profile
    require(profile.variant == ImageModelVariant.QWEN_IMAGE_21 &&
        profile.profileId == "mnn.qwen-image-2.1.opencl"
    ) { "The selected bundle did not resolve to the Qwen-Image-2.1 MNN profile." }
    validateLocalImageProfileProductOptions(profile, effectiveOptions)
    require(options.taskMode == LocalImageTaskMode.TEXT_TO_IMAGE &&
        options.inputImage == null && options.maskImage == null && options.controlImage == null
    ) { "Qwen-Image-2.1 currently supports text-to-image requests only." }
    require(options.negativePrompt.isNullOrBlank()) {
        "Qwen-Image-2.1 does not support a separate negative prompt. Clear it and retry."
    }
    require(options.batchCount == 1 && options.loras.isEmpty() &&
        options.textualInversionIds.isEmpty() && options.vaeTiling == null &&
        options.ultraFix == null && options.preview == null
    ) { "Qwen-Image-2.1 currently supports one image per request without LoRA or editing controls." }
    require(options.cfgScale == null || kotlin.math.abs(options.cfgScale - 1.0) <= 1e-9) {
        "Qwen-Image-2.1 uses its model-native guidance value; reset CFG to 1.0 and retry."
    }
    require(options.useCfg == null || !options.useCfg) {
        "Qwen-Image-2.1 does not support classifier-free guidance. Disable CFG and retry."
    }
    require(options.distilledGuidance == null && options.flowShift == null) {
        "Qwen-Image-2.1 uses its model-native flow schedule; clear the custom guidance/flow shift and retry."
    }
    val width = options.width ?: profile.defaults.width
    val height = options.height ?: profile.defaults.height
    val steps = options.steps ?: profile.scheduler.defaultSteps
    require(QwenImage21SizeContract.isSupported(width, height)) {
        QwenImage21SizeContract.unsupportedSizeMessage(width, height)
    }
    require(steps in profile.scheduler.minSteps..profile.scheduler.maxSteps) {
        "Qwen-Image-2.1 steps must be between ${profile.scheduler.minSteps} and ${profile.scheduler.maxSteps}."
    }
    require(profile.scheduler.algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) {
        "Qwen-Image-2.1 requires its built-in flow-matching sampler."
    }
    requireLocalImagePromptLanguageAdmission(
        profile = profile,
        prompt = prompt.trim(),
        executedNegativePrompt = ""
    )

    val requestedBackend = options.backendMode?.trim()?.lowercase().orEmpty()
    val backend = when (requestedBackend) {
        "", "auto", "fastest", "gpu", "opencl", "mnn_opencl" -> QwenImage21Backend.OPENCL
        "cpu", "mnn_cpu" -> QwenImage21Backend.CPU
        // QNN is an explicit opt-in for the experimental MNN DiT path. The
        // text encoder and VAE remain CPU, matching the native contract.
        "qnn", "mnn_qnn", "qnn_htp", "npu", "htp" -> QwenImage21Backend.QNN
        else -> error(
            "Qwen-Image-2.1 supports MNN OpenCL, MNN QNN/HTP, or CPU fallback."
        )
    }
    val useGpu = backend.useGpu
    val qnnRuntimePath = if (backend == QwenImage21Backend.QNN) {
        qwenImage21QnnRuntimeDirectory(appContext, bundleRoot)
    } else {
        null
    }
    val threads = options.threads
        ?: Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    require(threads in 1..16) { "Qwen-Image-2.1 CPU thread count must be between 1 and 16." }
    val backendLabel = when (backend) {
        QwenImage21Backend.CPU -> "MNN CPU"
        QwenImage21Backend.OPENCL -> "MNN OpenCL"
        QwenImage21Backend.QNN -> "MNN QNN/HTP"
    }
    val operationStartedAt = SystemRuntimeMonotonicClock.nowMs()
    fun publishProgress(phase: String, message: String, step: Int) {
        val elapsed = (SystemRuntimeMonotonicClock.nowMs() - operationStartedAt).coerceAtLeast(0L)
        runCatching {
            onProgress(
                LocalImageProgress(
                    phase = phase,
                    message = message,
                    step = step.coerceIn(0, steps),
                    steps = steps,
                    elapsedMs = elapsed,
                    secondsPerStep = if (step > 0) elapsed / 1_000.0 / step else 0.0,
                    threads = threads,
                    width = width,
                    height = height,
                    cancelRequested = cancellationRequested.get(),
                    requestOptionsJson = effectiveOptions.toJson().toString()
                )
            )
        }
    }

    val outputDirectory = canonicalLocalImageOutputDirectory(
        appContext.cacheDir,
        "qwen_image_21_outputs"
    )
    pruneStaleQwenImage21Outputs(outputDirectory)
    val outputFile = File(outputDirectory, "qwen-image-2.1-${UUID.randomUUID()}.png").canonicalFile
    require(outputFile.parentFile == outputDirectory) {
        "Qwen-Image-2.1 output path must remain in the app cache directory."
    }
    var activeRequestId: String? = null
    try {
        val loadRequestId = UUID.randomUUID().toString()
        activeRequestId = loadRequestId
        activeQwenImage21RequestId.set(loadRequestId)
        publishProgress("model_loading", "正在校验并加载 Qwen-Image-2.1（${backendLabel}）", 0)
        val loaded = qwenImage21Worker.load(
            bundleRoot = bundleRoot.absolutePath,
            useGpu = useGpu,
            backend = backend,
            qnnRuntimePath = qnnRuntimePath,
            threads = threads,
            bundleFingerprint = profile.bindingFingerprint,
            requestId = loadRequestId,
            onProgress = { progress ->
                val percent = progress.progressPercent ?: 0
                publishProgress(
                    "model_loading",
                    "正在加载 Qwen-Image-2.1（${backendLabel}，${percent}%）",
                    0
                )
            }
        )
        require(loaded.loaded && loaded.state == "READY") {
            "Qwen-Image-2.1 worker did not confirm that the model is ready."
        }
        require(loaded.backendConfigured == backend.nativeAuditName) {
            "Qwen-Image-2.1 worker did not configure the requested ${backendLabel} backend."
        }
        require(loaded.textEncoderOnCpu && loaded.vaeOnCpu) {
            "Qwen-Image-2.1 did not keep its text encoder and VAE on CPU as required by this profile."
        }
        qwenImage21RuntimeResident.set(true)
        generationContext.ensureActive()
        if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()

        val generationRequestId = UUID.randomUUID().toString()
        activeRequestId = generationRequestId
        activeQwenImage21RequestId.set(generationRequestId)
        publishProgress("denoising", "正在生成图片（0%）", 0)
        val generated = qwenImage21Worker.generate(
            requestId = generationRequestId,
            bundleRoot = bundleRoot.absolutePath,
            prompt = prompt.trim(),
            inputImagePath = null,
            outputPath = outputFile.absolutePath,
            width = width,
            height = height,
            steps = steps,
            seed = seed,
            threads = threads,
            useGpu = useGpu,
            backend = backend,
            qnnRuntimePath = qnnRuntimePath,
            onProgress = { percent ->
                publishProgress(
                    "denoising",
                    "正在生成图片（${percent}%）",
                    (steps * percent / 100.0).toInt()
                )
            },
            negativePrompt = "",
            bundleFingerprint = profile.bindingFingerprint
        )
        generationContext.ensureActive()
        if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
        require(generated.requestId == generationRequestId &&
            generated.width == width && generated.height == height &&
            generated.steps == steps && generated.seed == seed
        ) { "Qwen-Image-2.1 worker result does not match the submitted generation request." }
        require(File(generated.outputPath).canonicalFile == outputFile &&
            generated.outputBytes == outputFile.length() &&
            generated.outputBytes in MIN_QNN_OUTPUT_PNG_BYTES..MAX_QNN_OUTPUT_PNG_BYTES
        ) { "Qwen-Image-2.1 returned an invalid or out-of-bounds PNG path/size." }
        val outputSha256 = outputFile.sha256Contents()
        val verifiedOutput = verifyAndReadQwenImageOutput(
            nativeResult = JSONObject()
                .put("width", width)
                .put("height", height)
                .put("mimeType", "image/png")
                .put("outputPath", outputFile.absolutePath)
                .put("outputBytes", generated.outputBytes)
                .put("outputSha256", outputSha256),
            expectedOutputFile = outputFile,
            expectedWidth = width,
            expectedHeight = height,
            checkCancelled = {
                generationContext.ensureActive()
                if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            }
        )
        val executionMetadata = bindQwenImage21ExecutionEvidence(
            execution = JSONObject(generated.executionAudit.toString())
            .put("bundleId", model.recommendationId ?: QWEN_IMAGE_21_RECOMMENDATION_ID)
            .put("profileId", profile.profileId)
            .put("profileBindingFingerprint", profile.bindingFingerprint)
            .put("backendConfigured", loaded.backendConfigured)
            .put(
                "backendExecutionConfirmedByNative",
                generated.executionAudit.optBoolean("backendExecutionConfirmedByNative", false)
            )
            .put("textEncoderOnCpu", loaded.textEncoderOnCpu)
            .put("vaeOnCpu", loaded.vaeOnCpu)
            .put("outputSha256", outputSha256)
            .putPromptExecutionBinding(
                profile = profile,
                prompt = prompt.trim(),
                negativePrompt = ""
            ),
            profileId = profile.profileId,
            profileRevision = profile.profileRevision,
            modelFingerprint = profile.modelFingerprint,
            profileBindingFingerprint = profile.bindingFingerprint,
            promptLanguageBindingFingerprint = profile.promptLanguageBindingFingerprint,
            runtime = profile.runtime.name,
            width = width,
            height = height,
            steps = steps,
            seed = seed,
            outputBytes = verifiedOutput.outputBytes,
            outputSha256 = outputSha256
        ).toString()
        verifyQwenNativeBackendExecutionEvidence(
            audit = generated.executionAudit,
            expectedBackend = loaded.backendConfigured
        )
        val sanitizedExecutionMetadata = sanitizeNativeExecutionJson(executionMetadata)
            .takeIf(String::isNotBlank)
            ?: error("Qwen-Image-2.1 execution evidence could not be sanitized.")
        generationContext.ensureActive()
        if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
        publishProgress("complete", "图片生成完成（${backendLabel}）", steps)
        return LocalImageResult(
            bytes = verifiedOutput.bytes,
            mimeType = verifiedOutput.mimeType,
            executionMetadataJson = sanitizedExecutionMetadata,
            seed = seed.toLong()
        )
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        qwenImage21RuntimeResident.set(false)
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { qwenImage21Worker.disconnect() }
        }
        throw cancelled
    } catch (error: Throwable) {
        if (error is LocalImageWorkerCancelledException || cancellationRequested.get()) {
            qwenImage21RuntimeResident.set(false)
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { qwenImage21Worker.disconnect() }
            }
            throw LocalImageWorkerCancelledException()
        }
        if (error is QwenImage21WorkerException || error is OutOfMemoryError) {
            qwenImage21RuntimeResident.set(false)
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { qwenImage21Worker.unload() }
                    .onFailure { runCatching { qwenImage21Worker.disconnect() } }
            }
        }
        throw error
    } finally {
        activeRequestId?.let { requestId ->
            activeQwenImage21RequestId.compareAndSet(requestId, null)
        }
        // Qwen-Image-2.1 keeps nearly 10 GB of native state resident after a successful
        // generation. Release it before the disposable image worker returns so the chat
        // runtime can be loaded without waiting for a later image request.
        val releaseResidentRuntime = qwenImage21RuntimeResident.compareAndSet(true, false)
        withContext(NonCancellable + Dispatchers.IO) {
            if (releaseResidentRuntime) {
                runCatching { qwenImage21Worker.unload() }
                    .onFailure { runCatching { qwenImage21Worker.disconnect() } }
            } else {
                // A failed load can still leave the Binder connection alive even though no
                // resident handle was published; always drop that connection at the boundary.
                runCatching { qwenImage21Worker.disconnect() }
            }
        }
        runCatching { outputFile.delete() }
    }
}

/**
 * Returns one coherent app-private or platform QNN host directory for the
 * Qwen worker. Runtime discovery is advisory: an empty result is passed as
 * null so the native loader can use its normal platform search path.
 */
internal fun qwenImage21QnnRuntimeDirectory(appContext: Context, bundleRoot: File): String? {
    val resolution = runCatching {
        qnnRuntimeDirectoryResolutionFor(appContext, bundleRoot)
    }.getOrNull()
    val discovered = resolution?.directories.orEmpty().asSequence()
        .map { path -> runCatching { File(path).canonicalFile }.getOrNull() }
        .filterNotNull()
        .firstOrNull { directory ->
            directory.isDirectory &&
                File(directory, "libQnnSystem.so").isFile &&
                File(directory, "libQnnHtp.so").isFile
        }
        ?.absolutePath
    if (discovered != null) return discovered

    // Qwen's MNN bundle predates the image-bundle QNN manifest, so it may
    // have no bundle-specific runtime to stage. Reuse the APK's coherent
    // Qualcomm runtime as an advisory candidate when it is packaged; an
    // unknown device simply returns null and native loading remains the
    // compatibility authority.
    return runCatching {
        LiteRtQualcommRuntimeStager.stage(appContext)?.directory?.canonicalPath
    }.getOrNull()
}
