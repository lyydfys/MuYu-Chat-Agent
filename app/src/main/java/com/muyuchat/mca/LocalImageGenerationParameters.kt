package com.muyuchat.mca

import org.json.JSONObject

/**
 * Requested-to-effective parameter resolution and native result control checks.
 *
 * Moved out of `LocalImageProvider.kt` verbatim: image dimensions, steps, threads, CFG/flow-shift
 * bounds, backend mode and sampler resolution for stable-diffusion.cpp and MNN Diffusion, the product
 * defaults, the smoke-pixel corruption gate, and the checks that a native result echoes the exact
 * controls it was asked for.
 *
 * Every function here is a pure `requested value -> validated value` mapping (or a pure check on the
 * returned JSON). No default, bound, message, or rejection condition changed; the provider keeps the
 * data contracts and all stateful code.
 */
private fun String.toImageDimensions(family: LocalImageModelFamily): Pair<Int, Int> {
    val parts = lowercase().split("x", "×").mapNotNull { it.trim().toIntOrNull() }
    if (parts.size >= 2) {
        return parts[0].coerceAtLeast(64) to parts[1].coerceAtLeast(64)
    }
    return when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SDXL -> 1024 to 1024
        LocalImageModelFamily.WAN -> 832 to 480
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> 512 to 512
    }
}

internal fun Pair<Int, Int>.fastLocalDimensions(family: LocalImageModelFamily): Pair<Int, Int> {
    val maxDimension = when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15 -> 512
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> 384
    }
    val largest = maxOf(first, second)
    if (largest <= maxDimension) return first.alignImageDimension() to second.alignImageDimension()
    return ((first * maxDimension) / largest).alignImageDimension() to
        ((second * maxDimension) / largest).alignImageDimension()
}

private fun Int.alignImageDimension(): Int =
    (((this.coerceAtLeast(256) + 32) / 64) * 64).coerceIn(256, 1536)

internal fun resolveMnnDiffusionDimensions(
    defaultWidth: Int,
    defaultHeight: Int,
    requestedWidth: Int?,
    requestedHeight: Int?
): Pair<Int, Int> {
    requestedWidth?.let { width ->
        require(width == defaultWidth) {
            "MNN-Diffusion 当前模型只支持 ${defaultWidth}x${defaultHeight}，不能请求 ${width}x${requestedHeight ?: defaultHeight}。"
        }
    }
    requestedHeight?.let { height ->
        require(height == defaultHeight) {
            "MNN-Diffusion 当前模型只支持 ${defaultWidth}x${defaultHeight}，不能请求 ${requestedWidth ?: defaultWidth}x${height}。"
        }
    }
    return defaultWidth to defaultHeight
}

internal fun resolveStableDiffusionDimensions(
    defaultWidth: Int,
    defaultHeight: Int,
    requestedWidth: Int?,
    requestedHeight: Int?,
    dimensionMultiple: Int = 64
): Pair<Int, Int> {
    require(dimensionMultiple == 32 || dimensionMultiple == 64)
    val width = requestedWidth ?: defaultWidth
    val height = requestedHeight ?: defaultHeight
    require(width in 256..1536 && width % dimensionMultiple == 0) {
        "stable-diffusion.cpp width must be a multiple of $dimensionMultiple between 256 and 1536."
    }
    require(height in 256..1536 && height % dimensionMultiple == 0) {
        "stable-diffusion.cpp height must be a multiple of $dimensionMultiple between 256 and 1536."
    }
    return width to height
}

internal fun resolveStableDiffusionSteps(
    family: LocalImageModelFamily,
    requestedSteps: Int?
): Int {
    val steps = requestedSteps ?: defaultStepsFor(family)
    require(steps in 1..50) { "stable-diffusion.cpp steps must be between 1 and 50." }
    return steps
}

internal fun resolveStableDiffusionThreads(requestedThreads: Int?, defaultThreads: Int): Int {
    val threads = requestedThreads ?: defaultThreads
    require(threads in 1..64) { "stable-diffusion.cpp threads must be between 1 and 64." }
    return threads
}

internal fun resolveStableDiffusionFiniteControl(
    name: String,
    requested: Double?,
    defaultValue: Double
): Double {
    val value = requested ?: defaultValue
    require(value.isFinite()) { "stable-diffusion.cpp $name must be finite." }
    when (name) {
        "distilledGuidance" -> require(value in 0.0..30.0) {
            "stable-diffusion.cpp distilledGuidance must be in [0, 30]."
        }
        "flowShift" -> require(value == -1.0 || value in 0.0..100.0) {
            "stable-diffusion.cpp flowShift must be -1 (model default) or in [0, 100]."
        }
    }
    return value
}

internal fun resolveStableDiffusionBackendMode(requestedBackendMode: String?): String {
    val backend = requestedBackendMode?.trim()?.lowercase() ?: "cpu"
    require(backend == "cpu") {
        "stable-diffusion.cpp Android backend currently supports only backendMode=cpu."
    }
    return backend
}

internal fun resolveStableDiffusionSampleMethod(requestedSampleMethod: String?): String {
    val method = requestedSampleMethod?.trim()?.lowercase() ?: "euler"
    require(method in STABLE_DIFFUSION_SAMPLE_METHODS) {
        "Unsupported stable-diffusion.cpp sample method '$method'."
    }
    return method
}

internal fun stableDiffusionNativeSampleMethodMatches(
    scheduler: ImageSchedulerAlgorithm,
    nativeSampleMethod: String
): Boolean {
    val actual = nativeSampleMethod.trim().lowercase()
    return actual in when (scheduler) {
        ImageSchedulerAlgorithm.EULER -> setOf("euler")
        ImageSchedulerAlgorithm.EULER_A -> setOf("euler_a")
        ImageSchedulerAlgorithm.DPMPP_2M -> setOf("dpmpp_2m", "dpm++2m")
        ImageSchedulerAlgorithm.DDIM -> setOf("ddim", "ddim_trailing")
        ImageSchedulerAlgorithm.LCM -> setOf("lcm")
        ImageSchedulerAlgorithm.FLOW_MATCH -> setOf("flow_match", "euler")
        ImageSchedulerAlgorithm.PNDM_PLMS -> emptySet()
    }
}

private val STABLE_DIFFUSION_SAMPLE_METHODS = setOf(
    "euler",
    "euler_a",
    "heun",
    "dpm2",
    "dpm++2s_a",
    "dpm++2m",
    "dpm++2mv2",
    "ipndm",
    "ipndm_v",
    "lcm",
    "ddim_trailing",
    "tcd",
    "res_multistep",
    "res_2s",
    "er_sde",
    "euler_cfg_pp",
    "euler_a_cfg_pp",
    "euler_ge"
)

internal fun resolveMnnDiffusionThreads(requestedThreads: Int?, defaultThreads: Int): Int {
    val threads = requestedThreads ?: defaultThreads
    require(threads in 1..64) { "MNN-Diffusion threads must be between 1 and 64." }
    return threads
}

internal fun resolveMnnDiffusionMemoryMode(requestedMemoryMode: Int?): Int {
    val memoryMode = requestedMemoryMode ?: 0
    require(memoryMode in 0..2) { "MNN-Diffusion memoryMode must be 0, 1, or 2." }
    return memoryMode
}

internal fun mnnDiffusionControlAuditJson(params: JSONObject): JSONObject = JSONObject().apply {
    listOf(
        "family",
        "width",
        "height",
        "steps",
        "threads",
        "seed",
        "cfgScale",
        "useCfg",
        "sampleMethod",
        "runner",
        "backendMode",
        "memoryMode"
    ).forEach { key ->
        if (params.has(key)) put(key, params.get(key))
    }
}

internal fun verifyStableDiffusionResultControl(
    result: JSONObject,
    key: String,
    expected: Double
) {
    require(result.has(key) && !result.isNull(key)) {
        "stable-diffusion.cpp native result did not report $key."
    }
    val actual = result.get(key)
    require(actual is Number && kotlin.math.abs(actual.toDouble() - expected) <= 1e-6) {
        "stable-diffusion.cpp native result reported $key=$actual, expected $expected."
    }
}

internal fun verifyStableDiffusionDistilledGuidanceResult(
    result: JSONObject,
    requested: Double,
    specified: Boolean
) {
    verifyStableDiffusionResultControl(result, "requestedDistilledGuidance", requested)
    require(
        result.has("distilledGuidanceSpecified") &&
            result.get("distilledGuidanceSpecified") is Boolean &&
            result.getBoolean("distilledGuidanceSpecified") == specified
    ) {
        "stable-diffusion.cpp did not preserve whether distilled guidance was explicitly requested."
    }
    require(
        result.has("distilledGuidanceApplied") &&
            result.get("distilledGuidanceApplied") is Boolean
    ) {
        "stable-diffusion.cpp did not report distilled-guidance applicability."
    }
    if (result.getBoolean("distilledGuidanceApplied")) {
        verifyStableDiffusionResultControl(result, "distilledGuidance", requested)
    } else {
        require(result.has("distilledGuidance") && result.isNull("distilledGuidance")) {
            "stable-diffusion.cpp claimed an inert distilled-guidance value as effective."
        }
    }
}

internal fun verifyStableDiffusionFlowShiftResult(
    result: JSONObject,
    requested: Double,
    specified: Boolean,
    expectApplied: Boolean
) {
    verifyStableDiffusionResultControl(result, "requestedFlowShift", requested)
    require(
        result.has("flowShiftSpecified") &&
            result.get("flowShiftSpecified") is Boolean &&
            result.getBoolean("flowShiftSpecified") == specified
    ) {
        "stable-diffusion.cpp did not preserve whether flow shift was explicitly requested."
    }
    require(result.has("flowShiftApplied") && result.get("flowShiftApplied") is Boolean) {
        "stable-diffusion.cpp did not report flow-shift applicability."
    }
    val applied = result.getBoolean("flowShiftApplied")
    require(applied == expectApplied) {
        "stable-diffusion.cpp flow-shift applicability conflicts with the resolved prediction mode."
    }
    require(result.has("dynamicFlowShift") && result.get("dynamicFlowShift") is Boolean) {
        "stable-diffusion.cpp did not report whether flow shift was dynamically resolved."
    }
    val dynamic = result.getBoolean("dynamicFlowShift")
    if (!applied) {
        require(!dynamic && result.has("flowShift") && result.isNull("flowShift")) {
            "stable-diffusion.cpp claimed an inert flow shift as effective."
        }
        return
    }
    require(result.has("flowShift") && !result.isNull("flowShift")) {
        "stable-diffusion.cpp did not report the effective flow shift."
    }
    val effective = result.get("flowShift")
    require(effective is Number && effective.toDouble().isFinite() && effective.toDouble() >= 0.0) {
        "stable-diffusion.cpp reported an invalid effective flow shift."
    }
    if (dynamic) {
        require(requested < 0.0) {
            "stable-diffusion.cpp dynamically replaced an explicit flow-shift override."
        }
    } else if (requested >= 0.0) {
        require(kotlin.math.abs(effective.toDouble() - requested) <= 1e-6) {
            "stable-diffusion.cpp effective flow shift differs from the configured value."
        }
    }
}

internal data class LocalImageSmokePixelQuality(
    val passed: Boolean,
    val message: String,
    val lumaDynamicRange: Int,
    val distinctLumaBins: Int,
    val meanHorizontalDelta: Double,
    val meanVerticalDelta: Double
)

/**
 * Cheap deterministic corruption gate for product smoke tests.  This proves
 * that a decoder returned a non-trivial image, not that the prompt semantics
 * are correct; semantic/default certification deliberately remains separate.
 */
internal fun evaluateLocalImageSmokePixels(
    width: Int,
    height: Int,
    pixels: IntArray
): LocalImageSmokePixelQuality {
    require(width > 1 && height > 1 && pixels.size == width * height) {
        "Image pixels do not match the declared dimensions."
    }
    val stride = (pixels.size / 65_536).coerceAtLeast(1)
    val lumas = ArrayList<Int>((pixels.size + stride - 1) / stride)
    val occupiedBins = BooleanArray(64)
    var index = 0
    while (index < pixels.size) {
        val pixel = pixels[index]
        val red = pixel ushr 16 and 0xff
        val green = pixel ushr 8 and 0xff
        val blue = pixel and 0xff
        val luma = (red * 54 + green * 183 + blue * 19) ushr 8
        lumas += luma
        occupiedBins[(luma ushr 2).coerceIn(0, occupiedBins.lastIndex)] = true
        index += stride
    }
    lumas.sort()
    val p02 = lumas[((lumas.size - 1) * 2) / 100]
    val p98 = lumas[((lumas.size - 1) * 98) / 100]
    val dynamicRange = p98 - p02
    val distinctBins = occupiedBins.count { it }

    fun luma(pixel: Int): Int {
        val red = pixel ushr 16 and 0xff
        val green = pixel ushr 8 and 0xff
        val blue = pixel and 0xff
        return (red * 54 + green * 183 + blue * 19) ushr 8
    }

    var horizontalDelta = 0L
    var horizontalCount = 0L
    var verticalDelta = 0L
    var verticalCount = 0L
    for (y in 0 until height) {
        val row = y * width
        for (x in 1 until width) {
            horizontalDelta += kotlin.math.abs(luma(pixels[row + x]) - luma(pixels[row + x - 1]))
            horizontalCount += 1
        }
    }
    for (y in 1 until height) {
        val row = y * width
        val previous = row - width
        for (x in 0 until width) {
            verticalDelta += kotlin.math.abs(luma(pixels[row + x]) - luma(pixels[previous + x]))
            verticalCount += 1
        }
    }
    val meanHorizontal = horizontalDelta.toDouble() / horizontalCount.coerceAtLeast(1)
    val meanVertical = verticalDelta.toDouble() / verticalCount.coerceAtLeast(1)
    val reasons = buildList {
        if (dynamicRange < 24) add("luma dynamic range is too low ($dynamicRange)")
        if (distinctBins < 10) add("too few occupied luma bins ($distinctBins)")
        if (meanVertical > 12.0 && meanVertical > meanHorizontal * 3.5) {
            add("strong horizontal stripe pattern detected")
        }
    }
    return LocalImageSmokePixelQuality(
        passed = reasons.isEmpty(),
        message = if (reasons.isEmpty()) {
            "PNG pixel quality smoke passed."
        } else {
            reasons.joinToString("; ")
        },
        lumaDynamicRange = dynamicRange,
        distinctLumaBins = distinctBins,
        meanHorizontalDelta = meanHorizontal,
        meanVerticalDelta = meanVertical
    )
}

internal fun resolveMnnDiffusionBackendMode(requestedBackendMode: String?): String {
    // Default to CPU: MNN 3.6.0 OpenCL can hang inside a blocking native UNet
    // call on some Adreno devices (clFinish never returns), which would stall
    // the user's image request forever. Explicit OpenCL stays available as an
    // opt-in until the runtime hang is resolved.
    if (requestedBackendMode == null) return "cpu"
    return when (requestedBackendMode.trim().lowercase()) {
        // `auto` is a user-facing selection mode.  Start on the conservative
        // CPU graph; the caller may retry OpenCL only after native CPU
        // execution proves the fused UNet is unsupported.
        "auto" -> "cpu"
        "opencl", "gpu" -> "opencl"
        "cpu" -> "cpu"
        else -> throw IllegalArgumentException(
            "Unsupported MNN-Diffusion backend '$requestedBackendMode'. Supported values are cpu and opencl (gpu is an alias for opencl)."
        )
    }
}

internal fun resolveMnnDiffusionRunner(
    family: LocalImageModelFamily,
    requestedRunner: String?
): String {
    val normalized = requestedRunner?.trim()?.lowercase()
    return if (family == LocalImageModelFamily.SANA) {
        when (normalized) {
            null, "sana", "sana_varp", "module" -> "sana_varp"
            else -> throw IllegalArgumentException(
                "Unsupported MNN Sana runner '$requestedRunner'. Supported values are sana_varp, sana, and module."
            )
        }
    } else {
        when (normalized) {
            null -> "direct"
            "direct", "module" -> normalized
            else -> throw IllegalArgumentException(
                "Unsupported MNN Stable Diffusion runner '$requestedRunner'. Supported values are direct and module."
            )
        }
    }
}

internal fun mnnDiffusionBackendMatches(requestedBackend: String, actualBackend: String): Boolean =
    when (requestedBackend) {
        "opencl" -> actualBackend.trim().lowercase() in setOf("opencl", "gpu")
        "cpu" -> actualBackend.trim().lowercase() == "cpu"
        else -> false
    }

internal fun defaultStepsFor(family: LocalImageModelFamily): Int =
    when (family) {
        LocalImageModelFamily.Z_IMAGE -> 4
        LocalImageModelFamily.FLUX -> 4
        LocalImageModelFamily.SD_TURBO -> 1
        LocalImageModelFamily.QWEN_IMAGE -> 6
        LocalImageModelFamily.GLM_IMAGE -> 6
        LocalImageModelFamily.LONGCAT_IMAGE -> 6
        LocalImageModelFamily.DREAMLITE -> 6
        LocalImageModelFamily.SANA -> 5
        LocalImageModelFamily.SDXL -> 8
        LocalImageModelFamily.SD21 -> 8
        LocalImageModelFamily.SD15 -> 8
        LocalImageModelFamily.WAN -> 6
        LocalImageModelFamily.CUSTOM -> 6
    }

internal fun defaultCfgFor(family: LocalImageModelFamily): Double =
    when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO -> 1.0
        LocalImageModelFamily.QWEN_IMAGE -> 2.5
        LocalImageModelFamily.SANA -> 4.5
        LocalImageModelFamily.LONGCAT_IMAGE -> 5.0
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> 7.0
    }

internal fun requireQwenImage21GgufExecutionEvidence(result: JSONObject) {
    val native = result.optJSONObject("nativeEffective")
        ?: error("Qwen-Image-2.1 GGUF did not report native execution metadata.")
    require(native.optString("actualModelVariant") == "QWEN_IMAGE_21" &&
        native.optString("nativeScheduler") == "flux") {
        "Qwen-Image-2.1 GGUF requires its detected 2.1 architecture and resolution-aware flow schedule."
    }
    require(native.optBoolean("qwenTwoStage") &&
        native.optBoolean("qwenTextEncoderPhase") &&
        native.optBoolean("qwenTextConditionEncoded") &&
        native.optBoolean("qwenTextEncoderReleasedBeforeDiffusion") &&
        native.optBoolean("qwenDiffusionVaePhase")) {
        "Qwen-Image-2.1 GGUF did not complete text encoding and diffusion in separate native contexts."
    }
}

internal fun defaultStableDiffusionFlowShiftFor(profile: ImageExecutionProfile): Double {
    if (profile.scheduler.predictionType != ImagePredictionType.FLOW) return -1.0
    if (profile.variant == ImageModelVariant.QWEN_IMAGE_21) return -1.0
    return when (profile.family) {
        LocalImageModelFamily.FLUX -> -1.0
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.WAN -> 3.0
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> -1.0
    }
}

internal fun defaultImageSizeFor(fileName: String): String =
    when (LocalImageModelFamily.infer(fileName)) {
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE -> "768x768"
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SDXL -> "512x512"
        LocalImageModelFamily.SD_TURBO -> if ("384" in fileName) "384x384" else "512x512"
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15 -> if ("384" in fileName) "384x384" else "512x512"
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> "512x512"
    }
