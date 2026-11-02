package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.mediatek.MediaTekAnimaPipeline
import com.muyuchat.core.mediatek.MediaTekAnimaRequest
import com.muyuchat.core.mediatek.MediaTekAnimaTensorOps
import com.muyuchat.core.mediatek.MediaTekAotConditioningMode
import com.muyuchat.core.mediatek.MediaTekAotManifest
import com.muyuchat.core.mediatek.MediaTekAotProfile
import com.muyuchat.core.mediatek.MediaTekAotTextEncoder
import com.muyuchat.core.mediatek.MediaTekAotTextContextCache
import com.muyuchat.core.mediatek.MediaTekAotTextWeights
import com.muyuchat.core.mediatek.MediaTekAotTokenizer
import com.muyuchat.core.mediatek.MediaTekBundleAssetContract
import com.muyuchat.core.mediatek.MediaTekExecutionEvidence
import com.muyuchat.core.mediatek.MediaTekNpugCancelledException
import com.muyuchat.core.mediatek.MediaTekNpugChainPlanner
import com.muyuchat.core.mediatek.MediaTekNpugExecutionRequest
import com.muyuchat.core.mediatek.MediaTekNpugExecutor
import com.muyuchat.core.mediatek.MediaTekNpugRoute
import com.muyuchat.core.mediatek.MediaTekNpugRouteJson
import com.muyuchat.core.mediatek.MediaTekRunSummary
import com.muyuchat.core.mediatek.MediaTekRuntimeState
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.engine.SystemRuntimeMonotonicClock
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.nativebridge.NativeQnnBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
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

private const val MEDIATEK_ANIMA_UNSUPPORTED_OPTIONS = "unsupported_mediatek_anima_options"

private val mediaTekTextContextCache = MediaTekAotTextContextCache(capacity = 2)

internal fun mediaTekAnimaRequestFromOptions(
    prompt: String,
    options: LocalImageGenerationOptions,
    randomSeed: Long = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE),
    requirePrompt: Boolean = true,
): MediaTekAnimaRequest {
    val unsupported = buildList {
        if (options.taskMode != LocalImageTaskMode.TEXT_TO_IMAGE) add("taskMode")
        if (options.batchCount != 1) add("batchCount")
        if (options.threads != null) add("threads")
        if (options.inputImage != null) add("inputImage")
        if (options.maskImage != null) add("maskImage")
        if (options.controlImage != null) add("controlImage")
        if (options.strength != null) add("strength")
        if (options.controlStrength != null) add("controlStrength")
        if (options.loras.isNotEmpty()) add("loras")
        if (options.textualInversionIds.isNotEmpty()) add("textualInversionIds")
        if (options.vaeTiling != null) add("vaeTiling")
        if (options.ultraFix != null) add("ultraFix")
        if (options.preview != null) add("preview")
        if (options.clipSkip != null) add("clipSkip")
        if (options.distilledGuidance != null) add("distilledGuidance")
        if (options.flowShift != null) add("flowShift")
        if (options.sampleMethod != null) add("sampleMethod")
        if (options.backendMode != null) add("backendMode")
        if (options.tokenEmbeddingMode != null) add("tokenEmbeddingMode")
        if (options.memoryMode != null) add("memoryMode")
        if (options.runner != null) add("runner")
        if (options.useCfg == false) add("useCfg=false")
    }
    if (unsupported.isNotEmpty()) {
        throw LocalImageProductContractException(
            code = MEDIATEK_ANIMA_UNSUPPORTED_OPTIONS,
            message = "MediaTek Anima 当前仅支持单张文本生图；不支持的设置：${unsupported.joinToString(", ")}。尚未运行 NPU 图。"
        )
    }
    if (requirePrompt && prompt.isBlank()) {
        throw LocalImageProductContractException("invalid_image_prompt", "Anima 提示词不能为空。")
    }
    val seed = options.seed?.toLong() ?: randomSeed
    if (seed < 0L) {
        throw LocalImageProductContractException(
            code = "invalid_image_seed",
            message = "MediaTek Anima seed 必须为非负整数。"
        )
    }
    return MediaTekAnimaRequest(
        prompt = prompt.trim(),
        negativePrompt = options.negativePrompt.orEmpty(),
        width = options.width,
        height = options.height,
        steps = options.steps,
        cfgScale = options.cfgScale?.toFloat(),
        seed = seed
    )
}

internal fun executeMediaTekAnimaGeneration(
    context: Context,
    model: LocalImageModelRecord,
    prompt: String,
    options: LocalImageGenerationOptions,
    isCancellationRequested: () -> Boolean,
    onProgress: (LocalImageProgress) -> Unit
): LocalImageResult {
    val request = mediaTekAnimaRequestFromOptions(prompt, options)
    val bundleRoot = model.bundleRoot?.takeIf(String::isNotBlank)?.let(::File)
        ?: File(model.path).parentFile
        ?: throw LocalImageProductContractException(
            "mediatek_bundle_invalid",
            "MediaTek Anima bundle directory is unavailable."
        )
    if (!bundleRoot.isDirectory || !bundleRoot.canRead()) {
        throw LocalImageProductContractException(
            "mediatek_bundle_invalid",
            "MediaTek Anima bundle directory is unavailable or unreadable."
        )
    }
    val envelope = when (val inspection = inspectLocalImageBundleManifestFromRoot(bundleRoot)) {
        LocalImageBundleManifestInspection.Undeclared -> throw LocalImageProductContractException(
            "mediatek_bundle_invalid",
            "MediaTek Anima bundle is missing its MCA v1 manifest envelope."
        )
        is LocalImageBundleManifestInspection.Invalid -> throw LocalImageProductContractException(
            "mediatek_bundle_invalid",
            "MediaTek Anima bundle contract is invalid: ${inspection.message}"
        )
        is LocalImageBundleManifestInspection.Ready -> inspection.manifest
    }
    if (envelope.runtime != LocalImageRuntime.MEDIATEK_NEURON ||
        envelope.family != LocalImageModelFamily.ANIMA || envelope.task != "TEXT_TO_IMAGE"
    ) {
        throw LocalImageProductContractException(
            "mediatek_bundle_invalid",
            "Installed bundle metadata does not describe MediaTek Anima text-to-image."
        )
    }
    envelope.mediatekAotManifest?.let { aotManifest ->
        if (envelope.mediatekAnimaManifest == null) {
            return executeMediaTekAotGeneration(
                bundleRoot = bundleRoot,
                manifest = aotManifest,
                prompt = prompt,
                options = options,
                isCancellationRequested = isCancellationRequested,
                onProgress = onProgress,
            )
        }
    }
    val manifest = envelope.mediatekAnimaManifest ?: throw LocalImageProductContractException(
        "mediatek_bundle_invalid",
        "MediaTek Anima v3 manifest was not parsed from the checked MCA envelope."
    )
    val profile = manifest.sampling.generationProfiles.firstOrNull { candidate ->
        (request.width == null || request.width == candidate.widthPixels) &&
            (request.height == null || request.height == candidate.heightPixels) &&
            (request.steps == null || request.steps in candidate.sigmaSchedules)
    } ?: throw LocalImageProductContractException(
        "mediatek_anima_profile_unsupported",
        "Requested resolution or step count is not supported by this MediaTek Anima bundle."
    )
    if (isCancellationRequested()) throw LocalImageWorkerCancelledException()

    val startedAtNanos = System.nanoTime()
    val diagnosticCallback = if (BuildConfig.BUILD_TYPE.equals("diagnostic", ignoreCase = true)) {
        MediaTekAnimaDiagnosticLog.recordRunStart(profile.widthPixels, profile.heightPixels, request.steps ?: profile.defaultSteps)
        MediaTekAnimaDiagnosticLog::record
    } else {
        null
    }
    val pipelineResult = try {
        MediaTekAnimaPipeline(context.applicationContext, MediaTekAnimaNativeTokenizer()).generate(
            bundleRoot = bundleRoot.canonicalFile,
            manifest = manifest,
            request = request,
            isCancellationRequested = isCancellationRequested,
            onDiagnostic = diagnosticCallback,
            onProgress = { progress ->
                val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
                val denoise = progress.stage == "denoise"
                val currentSteps = request.steps ?: profile.defaultSteps
                onProgress(
                    LocalImageProgress(
                        phase = "mediatek_${progress.stage}",
                        message = when (progress.stage) {
                            "text_conditioning" -> "正在编码正向和负向提示词。"
                            "denoise" -> "正在执行 Anima 去噪步骤 ${progress.completed}/$currentSteps（已请求 NPU，实际分区待确认）。"
                            "complete" -> "Anima 图像已生成，NPU 分区证据待设备侧确认。"
                            else -> "正在执行 MediaTek Anima。"
                        },
                        step = progress.completed,
                        steps = progress.total,
                        elapsedMs = elapsedMs,
                        secondsPerStep = if (denoise && progress.completed > 0) {
                            elapsedMs.toDouble() / progress.completed / 1_000.0
                        } else {
                            0.0
                        },
                        threads = 1,
                        width = profile.widthPixels,
                        height = profile.heightPixels,
                        cancelRequested = isCancellationRequested()
                    )
                )
            }
        )
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath || error is kotlinx.coroutines.CancellationException) {
            throw error
        }
        if (isCancellationRequested()) throw LocalImageWorkerCancelledException()
        if (error is LocalImageProductContractException) throw error
        val detail = error.message?.take(600).orEmpty().ifBlank { error.javaClass.simpleName }
        throw LocalImageProductContractException(
            code = "mediatek_anima_execution_failed",
            message = "MediaTek Anima 执行失败，未切换到其他 runtime：$detail"
        )
    }
    if (isCancellationRequested()) throw LocalImageWorkerCancelledException()
    verifyMediaTekAnimaPipelineResult(
        pipelineResult.pngBytes,
        pipelineResult.evidence,
        pipelineResult.summary,
        profile.widthPixels,
        profile.heightPixels
    )
    val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
    return LocalImageResult(
        bytes = pipelineResult.pngBytes,
        mimeType = "image/png",
        executionMetadataJson = mediaTekExecutionMetadataJson(
            pipelineResult.evidence,
            graphRuns = pipelineResult.graphRuns,
            elapsedMs = elapsedMs,
            width = profile.widthPixels,
            height = profile.heightPixels,
            seed = pipelineResult.seed,
            steps = pipelineResult.steps,
            summary = pipelineResult.summary
        ),
        seed = pipelineResult.seed
    )
}

internal fun executeMediaTekAotGeneration(
    bundleRoot: File,
    manifest: MediaTekAotManifest,
    prompt: String,
    options: LocalImageGenerationOptions,
    isCancellationRequested: () -> Boolean,
    onProgress: (LocalImageProgress) -> Unit,
    onFinalLatent: (FloatArray) -> Unit = {},
): LocalImageResult {
    val request = mediaTekAnimaRequestFromOptions(prompt, options, requirePrompt = false)
    val blockers = manifest.executionBlockers()
    if (blockers.isNotEmpty()) {
        throw LocalImageProductContractException(
            "mediatek_aot_incomplete",
            "MediaTek NPUG/AOT bundle is incomplete: ${blockers.joinToString("; ")}."
        )
    }
    val profile = manifest.profiles.firstOrNull { candidate ->
        (request.width == null || request.width == candidate.widthPixels) &&
            (request.height == null || request.height == candidate.heightPixels) &&
            (request.steps == null || request.steps in candidate.supportedSteps)
    } ?: throw LocalImageProductContractException(
        "mediatek_aot_profile_unsupported",
        "Requested resolution or step count is not supported by this MediaTek NPUG/AOT bundle."
    )
    // The reference app falls back to its fixed ctx.bin when text assets are absent and
    // records that the prompt is not applied. Follow that documented behavior instead of
    // rejecting every product request: a non-blank prompt is accepted, but never claimed
    // to be effective (progress + metadata say so).
    val promptApplied = manifest.conditioning.mode == MediaTekAotConditioningMode.DYNAMIC_TEXT
    val rejections = manifest.requestRejections(
        profileId = profile.id,
        prompt = if (promptApplied) request.prompt else "",
        negativePrompt = request.negativePrompt,
        cfgScale = request.cfgScale,
        steps = request.steps,
    )
    if (rejections.isNotEmpty()) {
        throw LocalImageProductContractException(
            "mediatek_aot_unsupported_request",
            "MediaTek NPUG/AOT bundle cannot honour this request: ${rejections.joinToString("; ")}."
        )
    }
    val promptSuppressed = !promptApplied && request.prompt.isNotBlank()
    if (isCancellationRequested()) throw LocalImageWorkerCancelledException()

    val resolvedAssets = try {
        manifest.assets()
            .distinctBy { it.file.replace('\\', '/') }
            .associateBy({ it.file }, { asset -> resolvePinnedMediaTekAsset(bundleRoot, asset, "NPUG asset '${asset.file}'") })
    } catch (error: IllegalArgumentException) {
        throw LocalImageProductContractException(
            "mediatek_aot_asset_invalid",
            "MediaTek NPUG/AOT asset is missing or unpinned: ${error.message}"
        )
    }
    val routes = LinkedHashMap<String, MediaTekNpugRoute>()
    for (variant in manifest.variants) {
        val routeFile = resolvedAssets.getValue(variant.route.asset.file)
        val route = try {
            MediaTekNpugRouteJson.parse(routeFile.readText())
        } catch (error: IllegalArgumentException) {
            throw LocalImageProductContractException(
                "mediatek_aot_route_invalid",
                "MediaTek NPUG/AOT route '${variant.route.asset.file}' is invalid: ${error.message}"
            )
        }
        val problems = route.verifyAgainst(variant.route, variant.topology, variant.compiledNetwork)
        if (problems.isNotEmpty()) {
            throw LocalImageProductContractException(
                "mediatek_aot_route_invalid",
                "MediaTek NPUG/AOT route '${variant.route.asset.file}' does not match its variant: ${problems.joinToString("; ")}"
            )
        }
        routes[variant.id] = route
    }
    val contextTokens = routes.values.firstOrNull()?.inputs?.getOrNull(1)?.dims?.getOrNull(1)
    if (contextTokens == null || contextTokens !in 2..1024) {
        throw LocalImageProductContractException(
            "mediatek_aot_chain_invalid",
            "NPUG route does not declare a valid ACTX context shape."
        )
    }
    val steps = request.steps ?: profile.defaultSteps
    val plan = try {
        MediaTekNpugChainPlanner.plan(manifest, profile.id, steps, routes)
    } catch (error: IllegalArgumentException) {
        throw LocalImageProductContractException(
            "mediatek_aot_chain_invalid",
            "MediaTek NPUG/AOT chain contract is invalid: ${error.message}"
        )
    }
    val textEncoding = if (manifest.conditioning.mode == MediaTekAotConditioningMode.DYNAMIC_TEXT) {
        encodeMediaTekAotText(
            assets = manifest.conditioning.textAssets.map { asset -> asset to resolvedAssets.getValue(asset.file) },
            prompt = request.prompt,
            lk = contextTokens,
            profile = profile,
            steps = steps,
            isCancellationRequested = isCancellationRequested,
            onProgress = onProgress,
        )
    } else {
        null
    }
    if (promptSuppressed) {
        onProgress(
            LocalImageProgress(
                phase = "mediatek_npug_context",
                message = "该 NPUG 包使用固定 ACTX 上下文，提示词不会生效。",
                step = 0,
                steps = steps,
                elapsedMs = 0,
                secondsPerStep = 0.0,
                threads = 1,
                width = profile.widthPixels,
                height = profile.heightPixels,
                cancelRequested = isCancellationRequested(),
            )
        )
    }
    val latent = MediaTekAnimaTensorOps.standardNormalLikeTorchMt19937(
        request.seed,
        Math.multiplyExact(plan.latentChannels * plan.height, plan.width)
    )
    val startedAtNanos = System.nanoTime()
    val executor = MediaTekNpugExecutor()
    val result = try {
        executor.execute(
            MediaTekNpugExecutionRequest(
                plan = plan,
                manifest = manifest,
                routes = routes,
                resolveAsset = { asset -> resolvedAssets.getValue(asset.file) },
                contextValues = textEncoding?.encoding?.context,
                latent = latent,
                isCancelled = isCancellationRequested,
                onProgress = { completed, total ->
                    val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
                    onProgress(
                        LocalImageProgress(
                            phase = "mediatek_npug_denoise",
                            message = "正在执行 NPUG 去噪步骤 $completed/$total（Neuron DLA 真机分区待确认）。",
                            step = completed,
                            steps = total,
                            elapsedMs = elapsedMs,
                            secondsPerStep = if (completed > 0) {
                                elapsedMs.toDouble() / completed / 1_000.0
                            } else {
                                0.0
                            },
                            threads = 1,
                            width = profile.widthPixels,
                            height = profile.heightPixels,
                            cancelRequested = isCancellationRequested(),
                        )
                    )
                },
            )
        )
    } catch (error: MediaTekNpugCancelledException) {
        throw LocalImageWorkerCancelledException()
    } catch (error: VirtualMachineError) {
        throw error
    } catch (error: ThreadDeath) {
        throw error
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Throwable) {
        if (isCancellationRequested()) throw LocalImageWorkerCancelledException()
        val detail = error.message?.take(600).orEmpty().ifBlank { error.javaClass.simpleName }
        throw LocalImageProductContractException(
            "mediatek_aot_execution_failed",
            "MediaTek NPUG/AOT 执行失败，未切换到其他 runtime：$detail"
        )
    }
    if (isCancellationRequested()) throw LocalImageWorkerCancelledException()
    onFinalLatent(result.finalLatent)
    val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
    val outputSha256 = MessageDigest.getInstance("SHA-256").digest(result.pngBytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    val metadata = JSONObject()
        .put("schema", manifest.schema)
        .put("modelSha256", manifest.modelIdentity.modelSha256)
        .put("backend", "MEDIATEK_NEURON")
        .put("transport", "NEURON_COMPILED_NETWORK")
        .put("profileId", profile.id)
        .put("width", profile.widthPixels)
        .put("height", profile.heightPixels)
        .put("steps", result.steps)
        .put("seed", request.seed)
        .put("sigmas", JSONArray(result.sigmas))
        .put("nativeExecution", true)
        .put("fallback", false)
        .put("outputSha256", outputSha256)
        .put("promptApplied", promptApplied)
        .put("promptSuppressed", promptSuppressed)
        .put("negativePromptSupported", manifest.parameters.supportsNegativePrompt)
        .put("cfgMode", manifest.parameters.cfgMode.name)
        .put("latentInitialization", "TORCH_MT19937_STANDARD_NORMAL")
        .put("runtimeMessage", result.runtimeMessage)
        .put("elapsedMs", elapsedMs)
        .put("deviceVerification", "NOT_RUN")
        .put("executionStatus", "NPUG_CHAIN_AND_VAE_COMPLETED")
        .put("qualityStatus", "NOT_VERIFIED_NO_REFERENCE_BASELINE")
        .put("acceptanceStatus", "NOT_RUN")
        .put(
            "chunks",
            JSONArray(result.chunkRuns.map { run ->
                JSONObject()
                    .put("step", run.step)
                    .put("variantId", run.variantId)
                    .put("bytes", run.bytes)
                    .put("sha256", run.sha256)
                    .put("min", run.minimum)
                    .put("max", run.maximum)
                    .put("mean", run.mean)
                    .put("std", run.standardDeviation)
            })
        )
    metadata.put("conditioningMode", manifest.conditioning.mode.name)
    textEncoding?.let { encoded ->
        metadata.put(
            "textEncoding",
            JSONObject()
                .put("backend", "QWEN3_0_6B_PLUS_ADAPTER_CPU")
                .put("lk", encoded.encoding.lk)
                .put("qwenTokens", JSONArray(encoded.encoding.qwenTokens.toList()))
                .put("t5Tokens", JSONArray(encoded.encoding.t5Tokens.toList()))
                .put("contextSha256", encoded.encoding.contextSha256)
                .put("elapsedMs", encoded.encoding.elapsedMs)
                .put("minimum", encoded.encoding.minimum.toDouble())
                .put("maximum", encoded.encoding.maximum.toDouble())
                .put("mean", encoded.encoding.mean.toDouble())
                .put("standardDeviation", encoded.encoding.standardDeviation.toDouble())
                .put("weightsSha256", encoded.weightsSha256)
                .put("tokenizerSha256", encoded.tokenizerSha256)
                .put("truncatedQwen", encoded.truncatedQwen)
                .put("truncatedT5", encoded.truncatedT5)
                .put("cacheHit", encoded.cacheHit),
        )
    }
    return LocalImageResult(
        bytes = result.pngBytes,
        mimeType = "image/png",
        executionMetadataJson = metadata.toString(),
        seed = request.seed,
    )
}

private data class MediaTekTextEncodingResult(
    val encoding: MediaTekAotTextEncoder.Encoding,
    val weightsSha256: String,
    val tokenizerSha256: String,
    val truncatedQwen: Boolean,
    val truncatedT5: Boolean,
    val cacheHit: Boolean,
)

private fun encodeMediaTekAotText(
    assets: List<Pair<MediaTekBundleAssetContract, File>>,
    prompt: String,
    lk: Int,
    profile: MediaTekAotProfile,
    steps: Int,
    isCancellationRequested: () -> Boolean,
    onProgress: (LocalImageProgress) -> Unit,
): MediaTekTextEncodingResult {
    val weights = assets.singleOrNull { (_, file) -> file.leadingMagic() == "ATXW" }
        ?: throw LocalImageProductContractException(
            "mediatek_aot_text_invalid",
            "DYNAMIC_TEXT requires exactly one ATXW text weight asset."
        )
    val tokenizer = assets.singleOrNull { (_, file) -> file.leadingMagic() == "ATOK" }
        ?: throw LocalImageProductContractException(
            "mediatek_aot_text_invalid",
            "DYNAMIC_TEXT requires exactly one ATOK tokenizer asset."
        )
    val cacheKey = "${weights.first.sha256}|${tokenizer.first.sha256}|$lk|$prompt"
    mediaTekTextContextCache.get(cacheKey)?.let { cached ->
        if (isCancellationRequested()) throw LocalImageWorkerCancelledException()
        onProgress(
            LocalImageProgress(
                phase = "mediatek_text_encode",
                message = "文本编码命中缓存（lk=$lk）。",
                step = 0,
                steps = steps,
                elapsedMs = cached.encoding.elapsedMs,
                secondsPerStep = 0.0,
                threads = 1,
                width = profile.widthPixels,
                height = profile.heightPixels,
                cancelRequested = isCancellationRequested(),
            )
        )
        return MediaTekTextEncodingResult(
            encoding = cached.encoding,
            weightsSha256 = weights.first.sha256,
            tokenizerSha256 = tokenizer.first.sha256,
            truncatedQwen = cached.truncatedQwen,
            truncatedT5 = cached.truncatedT5,
            cacheHit = true,
        )
    }
    onProgress(
        LocalImageProgress(
            phase = "mediatek_text_encode",
            message = "正在运行 Qwen3 端上文本编码（CPU），首次运行需要读取 1.4 GB 文本权重。",
            step = 0,
            steps = steps,
            elapsedMs = 0,
            secondsPerStep = 0.0,
            threads = 1,
            width = profile.widthPixels,
            height = profile.heightPixels,
            cancelRequested = isCancellationRequested(),
        )
    )
    if (isCancellationRequested()) throw LocalImageWorkerCancelledException()
    return try {
        MediaTekAotTextWeights.open(weights.second).use { store ->
            val tokenization = MediaTekAotTokenizer.parse(tokenizer.second)
            val rawQwen = tokenization.encodeQwen(prompt).size
            val rawT5 = tokenization.encodeT5(prompt).size
            val encoder = MediaTekAotTextEncoder(store, tokenization)
            val encoding = encoder.encode(prompt, lk) { isCancellationRequested() }
            if (isCancellationRequested()) throw LocalImageWorkerCancelledException()
            val truncatedQwen = rawQwen > lk
            val truncatedT5 = rawT5 >= lk
            if (truncatedQwen || truncatedT5) {
                onProgress(
                    LocalImageProgress(
                        phase = "mediatek_text_encode",
                        message = "提示词超长：Qwen $rawQwen/$lk，T5 $rawT5/$lk，超出部分未进入模型。",
                        step = 0,
                        steps = steps,
                        elapsedMs = encoding.elapsedMs,
                        secondsPerStep = 0.0,
                        threads = 1,
                        width = profile.widthPixels,
                        height = profile.heightPixels,
                        cancelRequested = isCancellationRequested(),
                    )
                )
            }
            MediaTekTextEncodingResult(
                encoding = encoding,
                weightsSha256 = weights.first.sha256,
                tokenizerSha256 = tokenizer.first.sha256,
                truncatedQwen = truncatedQwen,
                truncatedT5 = truncatedT5,
                cacheHit = false,
            ).also { result ->
                mediaTekTextContextCache.put(
                    cacheKey,
                    MediaTekAotTextContextCache.Entry(encoding, truncatedQwen, truncatedT5),
                )
                result
            }
        }
    } catch (error: LocalImageWorkerCancelledException) {
        throw error
    } catch (error: MediaTekNpugCancelledException) {
        throw LocalImageWorkerCancelledException()
    } catch (error: VirtualMachineError) {
        throw error
    } catch (error: ThreadDeath) {
        throw error
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Throwable) {
        if (isCancellationRequested()) throw LocalImageWorkerCancelledException()
        val detail = error.message?.take(600).orEmpty().ifBlank { error.javaClass.simpleName }
        throw LocalImageProductContractException(
            "mediatek_aot_text_encoding_failed",
            "MediaTek 文本编码失败，未回退到固定 ctx：$detail"
        )
    }
}

private fun File.leadingMagic(): String = try {
    java.io.RandomAccessFile(this, "r").use { file ->
        val bytes = ByteArray(4)
        file.readFully(bytes)
        String(bytes, Charsets.US_ASCII)
    }
} catch (error: Throwable) {
    ""
}

private fun verifyMediaTekAnimaPipelineResult(
    png: ByteArray,
    evidence: MediaTekExecutionEvidence,
    summary: MediaTekRunSummary,
    width: Int,
    height: Int
) {
    if (!summary.accepted) {
        throw LocalImageProductContractException(
            "mediatek_anima_evidence_invalid",
            "MediaTek Anima run is not acceptable: incompleteGraphs=${summary.tally.incompleteGraphs}, " +
                "quality=${summary.quality.status}, outputValid=${summary.outputValid}."
        )
    }
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    if (png.size < 57 || png.size.toLong() > MAX_STABLE_DIFFUSION_OUTPUT_PNG_BYTES ||
        !png.copyOfRange(0, signature.size).contentEquals(signature)
    ) {
        throw LocalImageProductContractException(
            "mediatek_anima_output_invalid",
            "MediaTek Anima pipeline returned an invalid or out-of-bounds PNG."
        )
    }
    val header = ByteBuffer.wrap(png, 16, 8).order(ByteOrder.BIG_ENDIAN)
    if (header.int != width || header.int != height) {
        throw LocalImageProductContractException(
            "mediatek_anima_output_invalid",
            "MediaTek Anima PNG dimensions do not match the selected bundle profile."
        )
    }
    val outputSha256 = MessageDigest.getInstance("SHA-256").digest(png).toHexString()
    if (evidence.state != MediaTekRuntimeState.UNVERIFIED || !evidence.semanticGeneration ||
        evidence.outputMime != "image/png" || evidence.outputSha256 != outputSha256 || evidence.provesNpuExecution
    ) {
        throw LocalImageProductContractException(
            "mediatek_anima_evidence_invalid",
            "MediaTek Anima execution evidence is incomplete or inconsistent; NPU execution is not proven."
        )
    }
}

internal fun mediaTekExecutionMetadataJson(
    evidence: MediaTekExecutionEvidence,
    graphRuns: List<com.muyuchat.core.mediatek.MediaTekGraphRunEvidence>,
    elapsedMs: Long,
    width: Int,
    height: Int,
    seed: Long,
    steps: Int,
    summary: MediaTekRunSummary? = null
): String = JSONObject()
    .put("backend", "MEDIATEK_NEURON")
    .put("runtime", "MEDIATEK_NEURON")
    .put("state", evidence.state.name)
    .put("requestedRuntime", evidence.requestedRuntime)
    .put("requestedAccelerator", "MEDIATEK_NPU")
    .put("delegateRequest", evidence.actualDelegate ?: "LiteRT_NPU_REQUESTED")
    .put("actualDelegate", JSONObject.NULL)
    .put("actualRuntimeVersion", JSONObject.NULL)
    .put("npuPartitionCountAvailable", false)
    .put("npuPartitions", JSONObject.NULL)
    .put("cpuFallbackCountAvailable", false)
    .put("cpuFallbackOps", JSONObject.NULL)
    .put("provesNpuExecution", evidence.provesNpuExecution)
    .put("runSummary", summary?.let { run ->
        JSONObject()
            .put("plannedCpu", run.tally.plannedCpu)
            .put("rangeCpu", run.tally.rangeCpu)
            .put("npuSucceeded", run.tally.npuSucceeded)
            .put("npuFailedThenCpu", run.tally.npuFailedThenCpu)
            .put("supersededFailures", run.tally.supersededFailures)
            .put("incompleteGraphs", JSONArray().apply { run.tally.incompleteGraphs.forEach { put(it) } })
            .put("qualityStatus", run.quality.status.name)
            .put("qualityVerified", run.qualityVerified)
            .put("npuAttribution", run.npuAttribution.name)
            .put("accepted", run.accepted)
    } ?: JSONObject.NULL)
    .put("semanticGeneration", evidence.semanticGeneration)
    .put("stages", JSONArray().apply { evidence.stages.forEach { put(it.name) } })
    .put("outputMime", evidence.outputMime)
    .put("outputSha256", evidence.outputSha256)
    .put("graphRunCount", graphRuns.size)
    .put("graphElapsedMs", graphRuns.sumOf { it.elapsedMs })
    .put("graphRuns", JSONArray().apply {
        graphRuns.forEach { graph ->
            put(
                JSONObject()
                    .put("graph", graph.graphFile)
                    .put("stage", graph.graphStage.name)
                    .put("requestedAccelerator", graph.requestedAccelerator)
                    .put("compiled", graph.graphCompiled)
                    .put("ran", graph.graphRan)
                    .put("npuPartitions", graph.npuPartitions ?: JSONObject.NULL)
                    .put("cpuFallbackOps", graph.cpuFallbackOps ?: JSONObject.NULL)
                    .put("availableAccelerators", JSONArray().apply {
                        graph.availableAccelerators.sorted().forEach { put(it) }
                    })
                    .put("providerDeviceSupportedAdvisory", graph.providerDeviceSupportedAdvisory)
                    .put("providerLibraryReadyAdvisory", graph.providerLibraryReadyAdvisory)
                    .put("outputSha256", graph.outputSha256)
                    .put("elapsedMs", graph.elapsedMs)
                    .put("state", graph.state.name)
                    .put("message", graph.message)
            )
        }
    })
    .put("elapsedMs", elapsedMs)
    .put("width", width)
    .put("height", height)
    .put("seed", seed)
    .put("steps", steps)
    .put("message", evidence.message)
    .toString()

private fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHexString) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}

internal class LocalImageTextualInversionLeaseCell {
    var value: TextualInversionSelectionLease? = null

    operator fun getValue(
        thisRef: Any?,
        property: kotlin.reflect.KProperty<*>
    ): TextualInversionSelectionLease? = value

    operator fun setValue(
        thisRef: Any?,
        property: kotlin.reflect.KProperty<*>,
        newValue: TextualInversionSelectionLease?
    ) {
        value = newValue
    }
}

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
    // The provider owns lazy runtime resources. Keep accesses inside the selected IO
    // branch, matching the original provider rather than loading every backend at entry.
    val textualInversionStore by lazy(LazyThreadSafetyMode.NONE) { port.textualInversionStore }
    val bridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { port.bridge }
    val mnnDiffusionBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { port.mnnDiffusionBridge }
    val qnnBridge by lazy(LazyThreadSafetyMode.NONE) { port.qnnBridge }
    val qwenImage21Worker by lazy(LazyThreadSafetyMode.NONE) { port.qwenImage21Worker }
    val qwenImage21RuntimeResident = port.qwenImage21RuntimeResident
    val sdxlCoordinator by lazy(LazyThreadSafetyMode.NONE) { port.sdxlCoordinator }
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
                // The Qwen model is close to 10 GB. Release it before another image backend allocates.
                try {
                    qwenImage21Worker.unload()
                } finally {
                    qwenImage21RuntimeResident.set(false)
                }
            }
            if (model.runtime == LocalImageRuntime.MEDIATEK_NEURON) {
                return@withContext port.generateMediaTekAnima(
                    model = model,
                    prompt = prompt,
                    options = options,
                    isCancellationRequested = cancellationRequested::get,
                    onProgress = onProgress
                )
            }
            if (model.runtime == LocalImageRuntime.QNN_HTP) {
            val qnnLeaseCell = LocalImageTextualInversionLeaseCell()
            return@withContext try {
                val qnnExecution = runQnnHtpPreparation(
                    port = port,
                    model = model,
                    prompt = prompt,
                    options = options,
                    appContext = appContext,
                    onProgress = onProgress,
                    textualInversionStoreRef = { textualInversionStore },
                    qnnBridgeRef = { qnnBridge },
                    mnnDiffusionBridgeRef = { mnnDiffusionBridge },
                    sdxlCoordinatorRef = { sdxlCoordinator },
                    cancellationRequested = cancellationRequested,
                    activeSplitSdxlTerminal = activeSplitSdxlTerminal,
                    textualInversionLeaseCell = qnnLeaseCell
                )
                qnnExecution.runQnnHtpExecution()
            } finally {
                textualInversionLease = qnnLeaseCell.value
            }
        }
        require(model.runtime != LocalImageRuntime.QNN_HTP) {
            "QNN/QAIRT 本地生图 runner 尚未打包。该引擎包需要骁龙 NPU runtime、完整 QNN context/bin 组件，并通过 1-step smoke test 后才能生成。"
        }
        if (model.runtime == LocalImageRuntime.MNN_DIFFUSION) {
            val mnnLeaseCell = LocalImageTextualInversionLeaseCell()
            return@withContext try {
                runMnnDiffusionGeneration(
                    port = port,
                    model = model,
                    prompt = prompt,
                    options = options,
                    appContext = appContext,
                    onProgress = onProgress,
                    mnnDiffusionBridgeRef = { mnnDiffusionBridge },
                    textualInversionStoreRef = { textualInversionStore },
                    cancellationRequested = cancellationRequested,
                    textualInversionLeaseCell = mnnLeaseCell
                )
            } finally {
                textualInversionLease = mnnLeaseCell.value
            }
        }
        require(model.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            "当前仅支持 stable-diffusion.cpp 或 MNN-Diffusion 本地图像引擎。"
        }
        val sdcppLeaseCell = LocalImageTextualInversionLeaseCell()
        return@withContext try {
            runStableDiffusionCppGeneration(
                port = port,
                model = model,
                prompt = prompt,
                options = options,
                appContext = appContext,
                onProgress = onProgress,
                bridgeRef = { bridge },
                textualInversionStoreRef = { textualInversionStore },
                cancellationRequested = cancellationRequested,
                textualInversionLeaseCell = sdcppLeaseCell
            )
        } finally {
            textualInversionLease = sdcppLeaseCell.value
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
