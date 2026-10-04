package com.muyuchat.mca

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.muyuchat.api.local.imagePromptExecutionSha256
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.download.ImageEngineMinDeviceTier
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.engine.SystemRuntimeMonotonicClock
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.nativebridge.NativeQnnBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
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

private const val MAX_UPSCALE_SOURCE_SIDE = 2_048
private const val MAX_UPSCALE_SOURCE_PIXELS = 4_000_000L
private const val MAX_UPSCALE_NATIVE_OUTPUT_SIDE = 8_192
private const val MAX_UPSCALE_NATIVE_OUTPUT_PIXELS = 64_000_000L
private const val MAX_UPSCALE_NATIVE_PNG_BYTES = 256L * 1_024L * 1_024L
private const val MAX_UPSCALE_OUTPUT_SIDE = 4_096
private const val MAX_UPSCALE_OUTPUT_PIXELS = 16_000_000L
private const val MAX_UPSCALE_PNG_BYTES = 64L * 1_024L * 1_024L
internal const val STABLE_DIFFUSION_OUTPUT_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
internal const val MNN_DIFFUSION_OUTPUT_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
internal data class LocalImageUpscalePublicationDimensions(
    val width: Int,
    val height: Int,
    val pixels: Long
)

internal fun validatedLocalImageUpscalePublicationDimensions(
    sourceWidth: Int,
    sourceHeight: Int,
    targetScale: Int
): LocalImageUpscalePublicationDimensions {
    require(targetScale in setOf(2, 3, 4)) { "Upscale target scale must be 2, 3, or 4." }
    val sourcePixels = Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong())
    require(sourceWidth in 1..MAX_UPSCALE_SOURCE_SIDE &&
        sourceHeight in 1..MAX_UPSCALE_SOURCE_SIDE &&
        sourcePixels in 1L..MAX_UPSCALE_SOURCE_PIXELS
    ) { "Upscale source exceeds the 2048-pixel side or 4-megapixel execution limit." }
    val width = Math.multiplyExact(sourceWidth, targetScale)
    val height = Math.multiplyExact(sourceHeight, targetScale)
    val pixels = Math.multiplyExact(width.toLong(), height.toLong())
    require(width <= MAX_UPSCALE_OUTPUT_SIDE &&
        height <= MAX_UPSCALE_OUTPUT_SIDE &&
        pixels in 1L..MAX_UPSCALE_OUTPUT_PIXELS
    ) { "Requested upscale output exceeds the bounded Android publication limit." }
    return LocalImageUpscalePublicationDimensions(width, height, pixels)
}

internal data class VerifiedQnnImageOutput(
    val bytes: ByteArray,
    val mimeType: String,
    val outputBytes: Long
)


/** Preserves a concrete native product error instead of collapsing it into generation_failed. */
internal fun throwLocalImageNativeFailure(
    result: JSONObject,
    fallbackMessage: String
): Nothing {
    val message = sequenceOf(
        result.optString("error"),
        result.optString("message"),
        fallbackMessage
    ).map { it.trim() }.firstOrNull(String::isNotEmpty).orEmpty()
    val code = result.optString("errorCode").trim()
    if (code.isNotEmpty()) {
        throw LocalImageProductContractException(code.lowercase(), message)
    }
    error(message)
}

/** Logs a native failure at the Android boundary without coupling pure error mapping to Log. */
private fun logLocalImageNativeFailure(result: JSONObject, fallbackMessage: String) {
    val message = sequenceOf(
        result.optString("error"),
        result.optString("message"),
        fallbackMessage
    ).map { it.trim() }.firstOrNull(String::isNotEmpty).orEmpty()
    val code = result.optString("errorCode").trim()
    Log.e(
        "MCA-LocalImage",
        "native_failure code=${code.ifBlank { "unknown" }} " +
            "backend=${result.optString("backend").take(64)} " +
            "stage=${result.optString("executionStage").take(64)} " +
            "message=${message.take(512)}"
    )
}

class LocalImageProvider(context: Context) {
    private val appContext = context.applicationContext
    private val textualInversionStore by lazy { TextualInversionStore(appContext) }
    private val bridge by lazy { NativeStableDiffusionBridge() }
    private val mnnDiffusionBridge by lazy { NativeMnnDiffusionBridge() }
    private val qnnBridge by lazy { NativeQnnBridge() }
    private val qwenImage21Worker by lazy { QwenImage21WorkerClient(appContext) }
    private val qwenImage21RuntimeResident = AtomicBoolean(false)
    private val activeQwenImage21RequestId = AtomicReference<String?>(null)
    private val sdxlCoordinator by lazy { SdxlTwoPhaseCoordinator(appContext) }
    private val cancellationRequested = AtomicBoolean(false)
    private val activeSplitSdxlTerminal =
        AtomicReference<SplitSdxlRequestTerminalArbiter?>(null)

    @Volatile
    private var activeRuntime: LocalImageRuntime? = null

    private val localImageGenerationPort: LocalImageGenerationPort =
        object : LocalImageGenerationPort {
            override val appContext get() = this@LocalImageProvider.appContext
            override val textualInversionStore get() = this@LocalImageProvider.textualInversionStore
            override val bridge get() = this@LocalImageProvider.bridge
            override val mnnDiffusionBridge get() = this@LocalImageProvider.mnnDiffusionBridge
            override val qnnBridge get() = this@LocalImageProvider.qnnBridge
            override val qwenImage21Worker get() = this@LocalImageProvider.qwenImage21Worker
            override val qwenImage21RuntimeResident get() = this@LocalImageProvider.qwenImage21RuntimeResident
            override val sdxlCoordinator get() = this@LocalImageProvider.sdxlCoordinator
            override val cancellationRequested get() = this@LocalImageProvider.cancellationRequested
            override val activeSplitSdxlTerminal get() = this@LocalImageProvider.activeSplitSdxlTerminal
            override var activeRuntime: LocalImageRuntime?
                get() = this@LocalImageProvider.activeRuntime
                set(value) {
                    this@LocalImageProvider.activeRuntime = value
                }

            override fun begin(runtime: LocalImageRuntime) {
                this@LocalImageProvider.begin(runtime)
            }

            override fun logLocalImageNativeFailure(result: JSONObject, fallbackMessage: String) {
                logLocalImageNativeFailure(result, fallbackMessage)
            }

            override suspend fun generateQwenImage21(
                model: LocalImageModelRecord,
                prompt: String,
                options: LocalImageGenerationOptions,
                onProgress: (LocalImageProgress) -> Unit
            ): LocalImageResult = this@LocalImageProvider.generateQwenImage21(model, prompt, options, onProgress)
        }

    fun begin(runtime: LocalImageRuntime) {
        cancellationRequested.set(false)
        if (runtime in setOf(LocalImageRuntime.QNN_HTP, LocalImageRuntime.MNN_DIFFUSION) &&
            NativeMnnDiffusionBridge.isAvailable
        ) {
            runCatching { mnnDiffusionBridge.resetImageCancellation() }
        }
        if (runtime == LocalImageRuntime.QNN_HTP) {
            sdxlCoordinator.begin()
        }
        activeRuntime = runtime
    }

    fun cancel(): Boolean {
        return when (activeRuntime ?: return false) {
            LocalImageRuntime.QNN_HTP -> {
                // The split request state is the linearization point against final output transfer.
                activeSplitSdxlTerminal.get()?.cancel()
                cancellationRequested.set(true)
                activeSplitSdxlTerminal.get()?.cancel()
                runCatching { sdxlCoordinator.cancel() }
                if (NativeQnnBridge.isAvailable) {
                    runCatching { qnnBridge.cancelImageGeneration() }
                }
                if (NativeMnnDiffusionBridge.isAvailable) {
                    runCatching { mnnDiffusionBridge.cancel() }
                }
                true
            }
            LocalImageRuntime.MNN_DIFFUSION -> {
                cancellationRequested.set(true)
                activeQwenImage21RequestId.get()?.let { requestId ->
                    runCatching { qwenImage21Worker.cancelImmediately(requestId) }
                    qwenImage21RuntimeResident.set(false)
                    return true
                }
                if (NativeMnnDiffusionBridge.isAvailable) {
                    runCatching { mnnDiffusionBridge.cancel() }
                }
                true
            }
            LocalImageRuntime.STABLE_DIFFUSION_CPP -> {
                cancellationRequested.set(true)
                if (NativeStableDiffusionBridge.isAvailable) {
                    runCatching { bridge.cancel() }
                }
                true
            }
            else -> false
        }
    }

    private suspend fun generateQwenImage21(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        onProgress: (LocalImageProgress) -> Unit
    ): LocalImageResult {
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
            qwenQnnRuntimeDirectory(bundleRoot)
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
    private fun qwenQnnRuntimeDirectory(bundleRoot: File): String? {
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

    fun nativeConfig(): JSONObject? =
        if (NativeStableDiffusionBridge.isAvailable) {
            runCatching { JSONObject(bridge.getNativeConfig()) }.getOrNull()
        } else {
            null
        }

    suspend fun upscale(
        input: LocalImagePreparedInput,
        upscaler: LocalImagePreparedUpscaler,
        targetScale: Int,
        tileSize: Int = 128,
        threads: Int = defaultLocalImageThreads(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult = withContext(Dispatchers.IO) {
        if (activeRuntime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            begin(LocalImageRuntime.STABLE_DIFFUSION_CPP)
        }
        val nativeOutput = File(
            appContext.cacheDir,
            "local-image-upscale-native-${UUID.randomUUID()}.png"
        ).canonicalFile
        val postprocessedOutput = File(
            appContext.cacheDir,
            "local-image-upscale-final-${UUID.randomUUID()}.png"
        ).canonicalFile
        require(nativeOutput.parentFile == appContext.cacheDir.canonicalFile &&
            postprocessedOutput.parentFile == appContext.cacheDir.canonicalFile
        ) {
            "Upscale output escaped the app cache directory."
        }
        try {
            coroutineContext.ensureActive()
            require(NativeStableDiffusionBridge.isAvailable) {
                val reason = NativeStableDiffusionBridge.loadError?.message.orEmpty()
                "stable-diffusion.cpp upscaler failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(targetScale in setOf(2, 3, 4)) { "Upscale target scale must be 2, 3, or 4." }
            validatedLocalImageUpscalePublicationDimensions(input.width, input.height, targetScale)
            require(tileSize in 32..1_024 && tileSize % 8 == 0) {
                "Upscale tile size must be a multiple of 8 in 32-1024."
            }
            require(threads in 1..64) { "Upscale thread count must be in 1-64." }
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            val upscalerRoot = File(upscaler.path).canonicalFile.parentFile
                ?: error("Upscaler model has no app-private root.")
            val params = JSONObject()
                .put("upscalerId", upscaler.id)
                .put("upscalerSha256", upscaler.sha256)
                .put("upscalerSizeBytes", upscaler.sizeBytes)
                .put("inputImageSha256", input.sha256)
                .put("targetScale", targetScale)
                .put("tileSize", tileSize)
                .put("threads", threads)
            val progressPoller = launch {
                while (isActive) {
                    bridge.currentProgressOrNull()?.let(onProgress)
                    delay(500)
                }
            }
            val raw = try {
                bridge.upscale(
                    upscalerPath = upscaler.path,
                    upscalerRoot = upscalerRoot.canonicalPath,
                    inputPath = input.path,
                    paramsJson = params.toString(),
                    outputPath = nativeOutput.path
                )
            } finally {
                progressPoller.cancelAndJoin()
                bridge.currentProgressOrNull()?.let(onProgress)
            }
            coroutineContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            val json = JSONObject(raw)
            if (!json.optBoolean("ok", false)) {
                if (json.optBoolean("cancelled", false)) throw LocalImageWorkerCancelledException()
                logLocalImageNativeFailure(json, "stable-diffusion.cpp ESRGAN upscale failed.")
                throwLocalImageNativeFailure(json, "stable-diffusion.cpp ESRGAN upscale failed.")
            }
            val nativeEffective = json.optJSONObject("nativeEffective")
                ?: error("ESRGAN upscale did not report nativeEffective execution evidence.")
            require(json.optBoolean("nativeExecution", false) &&
                !nativeEffective.optBoolean("fallback", true) &&
                nativeEffective.optString("operation") == "ESRGAN_UPSCALE" &&
                nativeEffective.optString("runtime") == LocalImageRuntime.STABLE_DIFFUSION_CPP.name &&
                nativeEffective.optString("backendMode") == "cpu"
            ) { "ESRGAN upscale did not prove direct stable-diffusion.cpp execution." }
            require(nativeEffective.optString("upscalerId") == upscaler.id &&
                nativeEffective.optString("upscalerFileName") == File(upscaler.path).name &&
                nativeEffective.optString("upscalerSha256").lowercase() == upscaler.sha256 &&
                nativeEffective.optLong("upscalerSizeBytes", -1L) == upscaler.sizeBytes &&
                nativeEffective.optBoolean("modelHashVerified", false) &&
                nativeEffective.optBoolean("modelFileIdentityStable", false) &&
                nativeEffective.optString("inputImageSha256").lowercase() == input.sha256
            ) { "ESRGAN upscale identity or input digest evidence does not match the request." }
            val sourceWidth = nativeEffective.optInt("sourceWidth", -1)
            val sourceHeight = nativeEffective.optInt("sourceHeight", -1)
            val nativeScale = nativeEffective.optInt("nativeScale", -1)
            val nativeWidth = nativeEffective.optInt("width", -1)
            val nativeHeight = nativeEffective.optInt("height", -1)
            val nativePixels = runCatching {
                Math.multiplyExact(nativeWidth.toLong(), nativeHeight.toLong())
            }.getOrDefault(-1L)
            val sourcePixels = runCatching {
                Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong())
            }.getOrDefault(-1L)
            require(sourceWidth in 1..MAX_UPSCALE_SOURCE_SIDE &&
                sourceHeight in 1..MAX_UPSCALE_SOURCE_SIDE &&
                sourcePixels in 1L..MAX_UPSCALE_SOURCE_PIXELS &&
                nativeScale in 2..8 &&
                nativeScale >= targetScale &&
                nativeWidth.toLong() == sourceWidth.toLong() * nativeScale.toLong() &&
                nativeHeight.toLong() == sourceHeight.toLong() * nativeScale.toLong() &&
                nativeWidth <= MAX_UPSCALE_NATIVE_OUTPUT_SIDE &&
                nativeHeight <= MAX_UPSCALE_NATIVE_OUTPUT_SIDE &&
                nativePixels in 1L..MAX_UPSCALE_NATIVE_OUTPUT_PIXELS &&
                nativeEffective.optInt("requestedTargetScale", -1) == targetScale &&
                nativeEffective.optInt("tileSize", -1) == tileSize &&
                nativeEffective.optInt("threads", -1) == threads
            ) { "ESRGAN upscale dimensions or controls do not match the request." }
            val physicalComputeCount = nativeEffective.optLong("physicalComputeCount", -1L)
            val physicalComputeSuccessCount =
                nativeEffective.optLong("physicalComputeSuccessCount", -1L)
            val physicalTileComputeCount =
                nativeEffective.optLong("physicalTileComputeCount", -1L)
            val physicalTileComputeSuccessCount =
                nativeEffective.optLong("physicalTileComputeSuccessCount", -1L)
            val expectedTiled = sourceWidth > tileSize || sourceHeight > tileSize
            require(nativeEffective.optBoolean("executionCompleted", false) &&
                physicalComputeCount > 0L &&
                physicalComputeSuccessCount == physicalComputeCount &&
                nativeEffective.optBoolean("tiledExecution", false) == expectedTiled &&
                (if (expectedTiled) {
                    physicalTileComputeCount == physicalComputeCount &&
                        physicalTileComputeSuccessCount == physicalComputeSuccessCount
                } else {
                    physicalComputeCount == 1L &&
                        physicalTileComputeCount == 0L &&
                        physicalTileComputeSuccessCount == 0L
                })
            ) { "ESRGAN upscale did not prove its physical model compute invocations." }
            require(json.optBoolean("contextReleased", false) &&
                json.optLong("nativeGenerationSequence", 0L) > 0L
            ) { "ESRGAN upscale did not release its native context or publish a sequence." }
            val requiredStages = 255L
            listOf("nativeStageMask", "nativeDetailStageMask").forEach { field ->
                val mask = json.optLong(field, -1L)
                require(mask >= 0L && mask and requiredStages == requiredStages) {
                    "ESRGAN upscale $field is missing required execution stages."
                }
            }
            require(nativeOutput.isFile &&
                nativeOutput.length() in 1L..MAX_UPSCALE_NATIVE_PNG_BYTES
            ) {
                "ESRGAN upscale did not publish a bounded non-empty PNG."
            }
            val nativeBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(nativeOutput.path, nativeBounds)
            require(nativeBounds.outWidth == nativeWidth && nativeBounds.outHeight == nativeHeight) {
                "ESRGAN native PNG dimensions differ from native execution evidence."
            }
            val publicationDimensions = validatedLocalImageUpscalePublicationDimensions(
                sourceWidth,
                sourceHeight,
                targetScale
            )
            val finalWidth = publicationDimensions.width
            val finalHeight = publicationDimensions.height
            var decodeSampleSize = 1
            val finalFile = if (targetScale == nativeScale) {
                nativeOutput
            } else {
                coroutineContext.ensureActive()
                decodeSampleSize = (nativeScale / targetScale).takeIf { ratio ->
                    nativeScale % targetScale == 0 && ratio >= 2 &&
                        (ratio and (ratio - 1)) == 0
                } ?: 1
                val nativeBitmap = BitmapFactory.decodeFile(
                    nativeOutput.path,
                    BitmapFactory.Options().apply { inSampleSize = decodeSampleSize }
                )
                    ?: error("ESRGAN native PNG could not be decoded for target-scale resize.")
                try {
                    require(nativeBitmap.width > 0 && nativeBitmap.height > 0 &&
                        nativeBitmap.width <= nativeWidth && nativeBitmap.height <= nativeHeight
                    ) {
                        "ESRGAN native PNG downsample could not be decoded safely."
                    }
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                    val resized = Bitmap.createScaledBitmap(nativeBitmap, finalWidth, finalHeight, true)
                    try {
                        coroutineContext.ensureActive()
                        FileOutputStream(postprocessedOutput).use { output ->
                            check(resized.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                "Unable to encode target-scale upscale output."
                            }
                            output.fd.sync()
                        }
                    } finally {
                        if (resized !== nativeBitmap) resized.recycle()
                    }
                } finally {
                    nativeBitmap.recycle()
                }
                postprocessedOutput
            }
            coroutineContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            require(finalFile.isFile && finalFile.length() in 1L..MAX_UPSCALE_PNG_BYTES) {
                "Final upscale PNG exceeds the bounded worker publication limit."
            }
            val finalBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(finalFile.path, finalBounds)
            require(finalBounds.outWidth == finalWidth && finalBounds.outHeight == finalHeight) {
                "Final upscale output dimensions do not match the requested scale."
            }
            coroutineContext.ensureActive()
            val finalBytes = finalFile.readBytes()
            val executionMetadata = json
                .put(
                    "productOutput",
                    JSONObject()
                        .put("targetScale", targetScale)
                        .put("nativeFixedScale", nativeScale)
                        .put("postResizeApplied", targetScale != nativeScale)
                        .put(
                            "postResizeMethod",
                            if (targetScale == nativeScale) "none" else "android_bitmap_filtered"
                        )
                        .put("sourceWidth", sourceWidth)
                        .put("sourceHeight", sourceHeight)
                        .put("nativeWidth", nativeWidth)
                        .put("nativeHeight", nativeHeight)
                        .put("decodeSampleSize", decodeSampleSize)
                        .put("width", finalWidth)
                        .put("height", finalHeight)
                        .put("mimeType", "image/png")
                )
                .toString()
            LocalImageResult(
                bytes = finalBytes,
                mimeType = "image/png",
                executionMetadataJson = executionMetadata,
                seed = null
            )
        } finally {
            runCatching { nativeOutput.delete() }
            runCatching { postprocessedOutput.delete() }
            runCatching { bridge.shutdown() }
            if (activeRuntime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
                activeRuntime = null
                cancellationRequested.set(false)
            }
        }
    }

    suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult =
        runLocalImageGeneration(localImageGenerationPort, model, prompt, options, onProgress)

}
