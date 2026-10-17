package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import com.muyuchat.mca.localImageInputNativeFloatMatches as nativeFloatMatches

private val QNN_ENCODER_GRAPH_EXECUTE_DETAIL_STAGE = 1uL shl 63
private val LOWERCASE_SHA256 = Regex("^[a-f0-9]{64}$")
internal val LOWERCASE_UINT64_HEX = Regex("^[a-f0-9]{16}$")
private val SHARED_QNN_ENCODER_INPUT_SHAPE = listOf(1, 3, 512, 512)
private val SHARED_QNN_ENCODER_OUTPUT_SHAPE = listOf(1, 4, 64, 64)
private const val SHARED_QNN_ENCODER_POSTERIOR_SAMPLE_COUNT = 1L * 4L * 64L * 64L
private const val SHARED_QNN_ENCODER_RUNTIME_MODE = "standalone_encoder_then_shared_unet_vae"

/**
 * QNN currently exposes product image inputs only for the native ControlNet path.  Verify that the
 * prepared worker file was actually consumed on every scheduler step, then remove its private path
 * before the execution object can cross back into the main process or Local API response.
 */
// P8/S41: private→internal when the QNN text-to-image path sanitizer moved out;
// callers stay in both files.
internal fun validateQnnNativeDetailStageMask(result: JSONObject): ULong {
    val detailStageMask = result.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD)
    result.optJSONObject("nativeEffective")?.let { nativeEffective ->
        if (nativeEffective.has(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ||
            nativeEffective.has("nativeDetailStageMask")
        ) {
            require(
                nativeEffective.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ==
                    detailStageMask
            ) { "QNN detail stage mask conflicts with nativeEffective." }
        }
    }
    return detailStageMask
}

internal fun verifyAndSanitizeQnnProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions
): JSONObject {
    validateQnnNativeDetailStageMask(result)
    require(options.taskMode == LocalImageTaskMode.CONTROL &&
        options.controlImage != null && options.inputImage == null && options.maskImage == null
    ) {
        "QNN image-input evidence is currently defined only for a control request."
    }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "QNN ControlNet did not report nativeEffective image input evidence."
        )
    require(result.optString("taskMode") == LocalImageTaskMode.CONTROL.wireName &&
        nativeEffective.optString("taskMode") == LocalImageTaskMode.CONTROL.wireName
    ) { "QNN ControlNet did not execute the requested taskMode." }
    require(result.optInt("batchCount", -1) == 1 &&
        nativeEffective.optInt("batchCount", -1) == 1
    ) { "QNN ControlNet must report its single concrete output." }

    fun requireExactCount(field: String, expected: Int) {
        require(result.optInt(field, -1) == expected &&
            nativeEffective.optInt(field, -1) == expected
        ) { "QNN ControlNet $field evidence differs from the request." }
    }
    requireExactCount("inputImageExecutionCount", 0)
    requireExactCount("maskImageExecutionCount", 0)
    requireExactCount("controlImageExecutionCount", 1)

    listOf("inputImagePath", "maskImagePath").forEach { field ->
        require(result.optString(field).isBlank() && nativeEffective.optString(field).isBlank()) {
            "QNN ControlNet reported an unused $field."
        }
        result.remove(field)
        nativeEffective.remove(field)
    }
    val expectedInput = requireNotNull(options.controlImage)
    val expectedPath = File(expectedInput.path).canonicalPath
    val outerPath = result.optString("controlImagePath")
    val innerPath = nativeEffective.optString("controlImagePath")
    require(outerPath.isNotBlank() && innerPath.isNotBlank() &&
        File(outerPath).canonicalPath == expectedPath &&
        File(innerPath).canonicalPath == expectedPath
    ) { "QNN ControlNet did not consume the prepared control image." }
    result.remove("controlImagePath")
    nativeEffective.remove("controlImagePath")

    require(result.optString("controlImageSha256") == expectedInput.sha256 &&
        nativeEffective.optString("controlImageSha256") == expectedInput.sha256
    ) { "QNN ControlNet control-image digest differs from the prepared input." }
    val expectedStrength = options.controlStrength ?: 1.0
    require(nativeFloatMatches(result.optDouble("controlStrength", Double.NaN), expectedStrength) &&
        nativeFloatMatches(
            nativeEffective.optDouble("controlStrength", Double.NaN),
            expectedStrength
        )
    ) { "QNN ControlNet did not execute the requested controlStrength." }
    require(result.optBoolean("controlStrengthApplied", false) &&
        nativeEffective.optBoolean("controlStrengthApplied", false)
    ) { "QNN ControlNet did not apply controlStrength." }

    val timetableCount = result.optInt("timetableCount", -1)
    val useCfg = result.optBoolean("useCfg", false)
    require(nativeEffective.optBoolean("useCfg", !useCfg) == useCfg) {
        "QNN ControlNet CFG evidence differs between the result and nativeEffective."
    }
    val expectedExecutions = timetableCount
    require(timetableCount > 0 &&
        result.optInt("controlNetExecutionCount", -1) == expectedExecutions &&
        nativeEffective.optInt("controlNetExecutionCount", -1) == expectedExecutions
    ) { "QNN ControlNet did not execute once per scheduler step." }
    val residualTensorCount = result.optInt("controlNetResidualTensorCount", -1)
    require(residualTensorCount > 0 &&
        nativeEffective.optInt("controlNetResidualTensorCount", -1) == residualTensorCount
    ) { "QNN ControlNet residual tensor evidence is invalid." }
    val expectedResidualWrites = Math.multiplyExact(expectedExecutions, residualTensorCount)
    require(result.optInt("controlNetResidualWriteCount", -1) == expectedResidualWrites &&
        nativeEffective.optInt("controlNetResidualWriteCount", -1) == expectedResidualWrites
    ) { "QNN ControlNet did not inject every residual on every scheduler step." }
    val expectedResidualReuseCount = if (useCfg) timetableCount else 0
    require(result.optInt("controlNetResidualUnetReuseCount", -1) == expectedResidualReuseCount &&
        nativeEffective.optInt("controlNetResidualUnetReuseCount", -1) == expectedResidualReuseCount
    ) { "QNN ControlNet residual reuse evidence does not match the CFG execution." }
    val expectedConditioningBranch = "positive"
    require(result.optString("controlNetConditioningBranch") == expectedConditioningBranch &&
        nativeEffective.optString("controlNetConditioningBranch") == expectedConditioningBranch
    ) { "QNN ControlNet did not execute on the required positive conditioning branch." }
    require(result.optBoolean("controlNetInputConsumed", false) &&
        nativeEffective.optBoolean("controlNetInputConsumed", false)
    ) { "QNN ControlNet did not report consuming the prepared control tensor." }

    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", 0)
        .put("maskImageExecutionCount", 0)
        .put("controlImageExecutionCount", 1)
        .put("controlNetExecutionCount", expectedExecutions)
        .put("controlNetResidualTensorCount", residualTensorCount)
        .put("controlNetResidualWriteCount", expectedResidualWrites)
        .put("controlNetResidualUnetReuseCount", expectedResidualReuseCount)
        .put("controlNetConditioningBranch", expectedConditioningBranch)
}

/** Verifies the three-process SDXL encoder -> UNet -> decoder img2img chain. */
internal fun verifyAndSanitizeQnnImg2ImgProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions,
    prepared: SdxlPreparedInputTensor,
    expectedVaeEncoderContextSha256: String
): JSONObject {
    val detailStageMask = validateQnnNativeDetailStageMask(result)
    require(options.taskMode == LocalImageTaskMode.IMG2IMG &&
        options.inputImage != null && options.maskImage == null && options.controlImage == null
    ) { "QNN SDXL img2img verification requires exactly one prepared input image." }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "QNN SDXL img2img did not report nativeEffective image input evidence."
        )
    require(result.optString("taskMode") == LocalImageTaskMode.IMG2IMG.wireName &&
        nativeEffective.optString("taskMode") == LocalImageTaskMode.IMG2IMG.wireName &&
        result.optInt("batchCount", -1) == 1 && nativeEffective.optInt("batchCount", -1) == 1
    ) { "QNN SDXL img2img task or batch evidence differs from the request." }
    fun exactCount(field: String, expected: Int) {
        require(result.optInt(field, -1) == expected &&
            nativeEffective.optInt(field, -1) == expected
        ) { "QNN SDXL img2img $field evidence differs from the request." }
    }
    exactCount("inputImageExecutionCount", 1)
    exactCount("maskImageExecutionCount", 0)
    exactCount("controlImageExecutionCount", 0)
    val expectedInput = requireNotNull(options.inputImage)
    require(File(result.getString("inputImagePath")).canonicalFile == File(expectedInput.path).canonicalFile &&
        File(nativeEffective.getString("inputImagePath")).canonicalFile == File(expectedInput.path).canonicalFile &&
        result.getString("inputImageSha256").lowercase() == expectedInput.sha256 &&
        nativeEffective.getString("inputImageSha256").lowercase() == expectedInput.sha256
    ) { "QNN SDXL source-image preprocessing provenance differs from the worker input." }
    require(!result.optBoolean("inputImageSourceReadByNative", true) &&
        result.optString("inputImageSourceValidation") == "android_preprocess_provenance"
    ) { "QNN SDXL encoder must consume the committed tensor without reopening the source image." }
    require(File(result.getString("inputImageTensorPath")).canonicalFile == File(prepared.tensorPath).canonicalFile &&
        File(nativeEffective.getString("inputImageTensorPath")).canonicalFile == File(prepared.tensorPath).canonicalFile &&
        result.getString("inputImageTensorSha256").lowercase() == prepared.tensorSha256 &&
        nativeEffective.getString("inputImageTensorSha256").lowercase() == prepared.tensorSha256
    ) { "QNN SDXL encoder did not consume the exact Android preprocessing tensor." }
    listOf("inputImagePath", "inputImageTensorPath", "maskImagePath", "controlImagePath").forEach {
        result.remove(it)
        nativeEffective.remove(it)
    }
    require(result.optString("inputImagePreprocess") == SDXL_INPUT_TENSOR_PREPROCESS &&
        nativeEffective.optString("inputImagePreprocess") == SDXL_INPUT_TENSOR_PREPROCESS &&
        result.optString("inputImageTensorDtype") == SDXL_INPUT_TENSOR_DTYPE &&
        result.optString("inputImageTensorLayout") == SDXL_INPUT_TENSOR_LAYOUT &&
        result.optString("inputImageTensorRange") == SDXL_INPUT_TENSOR_RANGE &&
        result.optLong("inputImageTensorBytes", -1L) == prepared.tensorBytes
    ) { "QNN SDXL img2img preprocessing evidence is incomplete." }
    val expectedStrength = options.strength ?: 1.0
    require(nativeFloatMatches(result.optDouble("strength", Double.NaN), expectedStrength) &&
        nativeFloatMatches(nativeEffective.optDouble("strength", Double.NaN), expectedStrength)
    ) { "QNN SDXL UNet did not execute the requested img2img strength." }
    val schedule = resolveSdxlImg2ImgSchedule(
        steps = result.getInt("steps"),
        fullTimetableCount = result.getInt("fullTimetableCount"),
        strength = expectedStrength
    )
    require(result.getInt("timetableCount") == schedule.effectiveSteps &&
        nativeEffective.getInt("timetableCount") == schedule.effectiveSteps &&
        result.getInt("effectiveDenoiseSteps") == schedule.effectiveSteps &&
        result.getInt("img2imgBeginIndex") == schedule.beginIndex &&
        nativeEffective.getInt("img2imgBeginIndex") == schedule.beginIndex
    ) { "QNN SDXL img2img did not execute the exact strength-derived tail timetable." }
    val encoderPid = result.optInt("encoderWorkerPid", -1)
    val unetPid = result.optInt("unetWorkerPid", -1)
    val vaePid = result.optInt("vaeWorkerPid", -1)
    val phaseSequences = listOf(
        result.optLong("encoderNativeGenerationSequence", -1L),
        result.optLong("unetNativeGenerationSequence", -1L),
        result.optLong("vaeNativeGenerationSequence", -1L)
    )
    val phaseProof = result.getJSONObject("sdxlPhaseProof")
    val nativePhaseProof = nativeEffective.getJSONObject("sdxlPhaseProof")
    val expectedEncoderExecutionCount = options.ultraFix?.let { request ->
        val expectedTileCount = Math.toIntExact(localQnnUltraFixTilePlanEvidence(request).tileCount)
        require(
            phaseProof.optInt("ultraFixTileCount", -1) == expectedTileCount &&
                nativePhaseProof.optInt("ultraFixTileCount", -1) == expectedTileCount &&
                phaseProof.optInt("ultraFixEncoderGraphExecutionCount", -1) == expectedTileCount &&
                nativePhaseProof.optInt("ultraFixEncoderGraphExecutionCount", -1) == expectedTileCount
        ) { "QNN SDXL UltraFix encoder evidence differs from its deterministic tile plan." }
        expectedTileCount
    } ?: 1
    require(encoderPid > 0 && unetPid > 0 && vaePid > 0 &&
        phaseSequences.all { it > 0L } && phaseSequences.distinct().size == 3 &&
        result.optBoolean("encoderProcessDeathConfirmed") &&
        result.optBoolean("unetProcessDeathConfirmed") &&
        result.optBoolean("vaeProcessDeathConfirmed") &&
        result.optInt("encoderContextLoadCount", -1) == 1 &&
        result.optInt("encoderExecutionCount", -1) == expectedEncoderExecutionCount &&
        phaseProof.optInt("encoderExecutionCount", -1) == expectedEncoderExecutionCount &&
        nativePhaseProof.optInt("encoderExecutionCount", -1) == expectedEncoderExecutionCount
    ) { "QNN SDXL img2img did not prove three ordered disposable graph processes." }
    val phaseDetailStageMasks = listOf("encoder", "unet", "vae").map { phase ->
        val hexField = "${phase}NativeDetailStageMaskHex"
        val legacyField = "${phase}NativeDetailStageMask"
        val outer = result.strictUInt64Hex(hexField, legacyField)
        require(phaseProof.strictUInt64Hex(hexField, legacyField) == outer) {
            "QNN SDXL $phase detail stage mask conflicts with the phase proof."
        }
        outer
    }
    require(phaseDetailStageMasks.fold(0uL, ULong::or) == detailStageMask) {
        "QNN SDXL aggregate detail stage mask differs from its three phase proofs."
    }
    val encoderLatentSha256 = result.optString("encoderLatentSha256").lowercase()
    val encoderContextSha256 = result.optString("encoderContextSha256").lowercase()
    require(encoderLatentSha256.matches(Regex("[a-f0-9]{64}")) &&
        nativeEffective.optString("encoderLatentSha256").lowercase() == encoderLatentSha256
    ) { "QNN SDXL UNet encoder-latent provenance is invalid." }
    require(expectedVaeEncoderContextSha256.matches(Regex("[a-f0-9]{64}")) &&
        encoderContextSha256 == expectedVaeEncoderContextSha256 &&
        nativeEffective.optString("encoderContextSha256").lowercase() == encoderContextSha256 &&
        result.getJSONObject("sdxlPhaseProof")
            .optString("encoderContextSha256").lowercase() == encoderContextSha256 &&
        nativeEffective.getJSONObject("sdxlPhaseProof")
            .optString("encoderContextSha256").lowercase() == encoderContextSha256
    ) { "QNN SDXL VAE encoder context provenance is invalid." }
    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", 1)
        .put("maskImageExecutionCount", 0)
        .put("controlImageExecutionCount", 0)
        .put("inputImagePreprocess", SDXL_INPUT_TENSOR_PREPROCESS)
        .put("inputImageTensorSha256", prepared.tensorSha256)
        .put("encoderContextSha256", encoderContextSha256)
        .put("encoderLatentSha256", encoderLatentSha256)
        .put("fullTimetableCount", schedule.fullTimetableCount)
        .put("effectiveDenoiseSteps", schedule.effectiveSteps)
        .put("img2imgBeginIndex", schedule.beginIndex)
}

/**
 * Verifies one standalone encoder followed by one coherent shared-QNN UNet/VAE session. This is
 * intentionally separate from the split-SDXL verifier, whose three disposable-process proof must
 * remain intact.
 */
internal fun verifyAndSanitizeQnnUltraFixProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions,
    prepared: Any,
    usesSplitWorkers: Boolean,
    profile: ImageExecutionProfile,
    expectedVaeEncoderContextSha256: String,
    expectedVaeEncoderGraphName: String
): JSONObject {
    require(options.ultraFix != null) {
        "QNN UltraFix input verification requires a structured UltraFix request."
    }
    return if (usesSplitWorkers) {
        verifyAndSanitizeQnnImg2ImgProductInput(
            result = result,
            options = options,
            prepared = prepared as? SdxlPreparedInputTensor
                ?: error("QNN UltraFix split input tensor has the wrong topology."),
            expectedVaeEncoderContextSha256 = expectedVaeEncoderContextSha256
        )
    } else {
        verifyAndSanitizeSharedQnnImg2ImgProductInput(
            result = result,
            options = options,
            prepared = prepared as? QnnPreparedInputTensor
                ?: error("QNN UltraFix shared input tensor has the wrong topology."),
            profile = profile,
            expectedVaeEncoderContextSha256 = expectedVaeEncoderContextSha256,
            expectedVaeEncoderGraphName = expectedVaeEncoderGraphName
        )
    }
}

internal fun verifyAndSanitizeSharedQnnImg2ImgProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions,
    prepared: QnnPreparedInputTensor,
    profile: ImageExecutionProfile,
    expectedVaeEncoderContextSha256: String,
    expectedVaeEncoderGraphName: String
): JSONObject {
    require(profile.hasSharedQnnImg2ImgTopology()) {
        "Shared-QNN img2img verification requires a coherent QNN UNet/VAE topology with a VAE encoder."
    }
    val ultraFixRequest = options.ultraFix
    require(if (ultraFixRequest == null) {
        prepared.tensorWidth == 512 && prepared.tensorHeight == 512
    } else {
        prepared.tensorWidth == ultraFixRequest.targetWidth &&
            prepared.tensorHeight == ultraFixRequest.targetHeight
    }) {
        "Shared-QNN img2img verification requires the committed tensor dimensions."
    }
    require(expectedVaeEncoderContextSha256.matches(LOWERCASE_SHA256)) {
        "Resolved shared-QNN VAE encoder context SHA-256 is malformed."
    }
    require(expectedVaeEncoderGraphName.isNotBlank()) {
        "Resolved shared-QNN VAE encoder graph name is missing."
    }
    require(
        options.taskMode == LocalImageTaskMode.IMG2IMG &&
            options.inputImage != null && options.maskImage == null && options.controlImage == null
    ) { "Shared-QNN img2img verification requires exactly one prepared input image." }

    val detailStageMask = validateQnnNativeDetailStageMask(result)
    require(detailStageMask and QNN_ENCODER_GRAPH_EXECUTE_DETAIL_STAGE != 0uL) {
        "Shared-QNN img2img did not prove a real VAE encoder graphExecute call."
    }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "Shared-QNN img2img did not report nativeEffective image input evidence."
        )

    fun exactString(field: String, expected: String) {
        require(result.optString(field) == expected && nativeEffective.optString(field) == expected) {
            "Shared-QNN img2img $field evidence differs from the executed request."
        }
    }
    fun exactInt(field: String, expected: Int) {
        require(result.optInt(field, Int.MIN_VALUE) == expected &&
            nativeEffective.optInt(field, Int.MIN_VALUE) == expected
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactLong(field: String, expected: Long) {
        require(result.optLong(field, Long.MIN_VALUE) == expected &&
            nativeEffective.optLong(field, Long.MIN_VALUE) == expected
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactBoolean(field: String, expected: Boolean) {
        require(result.has(field) && nativeEffective.has(field) &&
            result.optBoolean(field, !expected) == expected &&
            nativeEffective.optBoolean(field, !expected) == expected
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactDouble(field: String, expected: Double) {
        require(nativeFloatMatches(result.optDouble(field, Double.NaN), expected) &&
            nativeFloatMatches(nativeEffective.optDouble(field, Double.NaN), expected)
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactSha256(field: String, expected: String? = null): String {
        val outer = result.optString(field)
        val inner = nativeEffective.optString(field)
        require(outer.matches(LOWERCASE_SHA256) && inner == outer &&
            (expected == null || outer == expected)
        ) { "Shared-QNN img2img $field evidence is missing, malformed, or inconsistent." }
        return outer
    }
    fun exactShape(field: String, expected: List<Int>) {
        fun JSONObject.shapeOrNull(): List<Int>? {
            val shape = optJSONArray(field) ?: return null
            if (shape.length() != expected.size) return null
            return (0 until shape.length()).map { index -> shape.optInt(index, Int.MIN_VALUE) }
        }
        require(result.shapeOrNull() == expected && nativeEffective.shapeOrNull() == expected) {
            "Shared-QNN img2img $field differs from the physical encoder tensor contract."
        }
    }

    exactString("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
    exactInt("batchCount", 1)
    exactInt("inputImageExecutionCount", 1)
    exactInt("maskImageExecutionCount", 0)
    exactInt("controlImageExecutionCount", 0)
    listOf("maskImagePath", "controlImagePath").forEach { field ->
        require(result.has(field) && nativeEffective.has(field) &&
            result.optString(field).isBlank() && nativeEffective.optString(field).isBlank()
        ) { "Shared-QNN img2img reported an unexpected private $field slot." }
    }

    val expectedInput = requireNotNull(options.inputImage)
    val ultraFixPlan = ultraFixRequest?.let(::localQnnUltraFixTilePlanEvidence)
    val expectedInputTensorShape = ultraFixRequest?.let { request ->
        listOf(1, 3, request.targetHeight, request.targetWidth)
    } ?: SHARED_QNN_ENCODER_INPUT_SHAPE
    val expectedEncoderGraphInputShape = SHARED_QNN_ENCODER_INPUT_SHAPE
    val expectedEncoderGraphOutputShape = SHARED_QNN_ENCODER_OUTPUT_SHAPE
    val expectedEncoderExecutionCount = ultraFixPlan?.tileCount ?: 1L
    val expectedPosteriorSampleCount = ultraFixRequest?.let { request ->
        Math.multiplyExact(
            Math.multiplyExact(request.targetWidth.toLong() / 8L, request.targetHeight.toLong() / 8L),
            4L
        )
    } ?: SHARED_QNN_ENCODER_POSTERIOR_SAMPLE_COUNT
    val expectedEncoderRuntimeMode = if (ultraFixRequest != null) {
        "standalone_tiled_encoder_then_shared_tiled_unet_vae"
    } else {
        SHARED_QNN_ENCODER_RUNTIME_MODE
    }
    val expectedSource = File(expectedInput.path).canonicalFile
    require(
        File(result.optString("inputImagePath")).canonicalFile == expectedSource &&
            File(nativeEffective.optString("inputImagePath")).canonicalFile == expectedSource
    ) { "Shared-QNN source-image preprocessing provenance differs from the worker input." }
    exactSha256("inputImageSha256", expectedInput.sha256)
    exactBoolean("inputImageSourceReadByNative", false)
    exactString("inputImageSourceValidation", "android_preprocess_provenance")

    val expectedTensor = File(prepared.tensorPath).canonicalFile
    require(
        File(result.optString("inputImageTensorPath")).canonicalFile == expectedTensor &&
            File(nativeEffective.optString("inputImageTensorPath")).canonicalFile == expectedTensor
    ) { "Shared-QNN encoder did not consume the exact committed Android preprocessing tensor." }
    exactSha256("inputImageTensorSha256", prepared.tensorSha256)
    exactString("inputImagePreprocess", QNN_INPUT_TENSOR_PREPROCESS)
    exactInt("inputImageSourceWidth", prepared.sourceWidth)
    exactInt("inputImageSourceHeight", prepared.sourceHeight)
    exactInt("inputImageOrientedWidth", prepared.orientedWidth)
    exactInt("inputImageOrientedHeight", prepared.orientedHeight)
    exactInt("inputImageExifOrientation", prepared.exifOrientation)
    exactInt("inputImageTensorWidth", prepared.tensorWidth)
    exactInt("inputImageTensorHeight", prepared.tensorHeight)
    exactInt("inputImageTensorChannels", 3)
    exactLong("inputImageTensorBytes", prepared.tensorBytes)
    exactShape("inputImageTensorShape", expectedInputTensorShape)
    exactString("inputImageTensorDtype", QNN_INPUT_TENSOR_DTYPE)
    exactString("inputImageTensorLayout", QNN_INPUT_TENSOR_LAYOUT)
    exactString("inputImageTensorRange", QNN_INPUT_TENSOR_RANGE)

    exactSha256("encoderContextSha256", expectedVaeEncoderContextSha256)
    exactInt("encoderContextLoadCount", 1)
    exactInt("encoderExecutionCount", expectedEncoderExecutionCount.toInt())
    exactString("encoderGraphName", expectedVaeEncoderGraphName)
    exactString("encoderInputName", "input")
    exactString("encoderMeanOutputName", "mean")
    exactString("encoderStdOutputName", "std")
    exactString("encoderInputDtype", "uint16")
    exactString("encoderMeanDtype", "uint16")
    exactString("encoderStdDtype", "uint16")
    exactShape("encoderInputShape", expectedEncoderGraphInputShape)
    exactShape("encoderMeanShape", expectedEncoderGraphOutputShape)
    exactShape("encoderStdShape", expectedEncoderGraphOutputShape)
    val encoderInputBufferSha256 = exactSha256("encoderInputBufferSha256")
    val encoderMeanBufferSha256 = exactSha256("encoderMeanBufferSha256")
    val encoderStdBufferSha256 = exactSha256("encoderStdBufferSha256")
    val encoderLatentSha256 = exactSha256("encoderLatentSha256")
    exactString("posteriorSampling", "mean_plus_std_times_normal_mt19937_domain_v1")
    exactLong("posteriorSampleCount", expectedPosteriorSampleCount)
    exactDouble("encoderLatentScalingFactor", profile.vae.scalingFactor)
    exactBoolean("encoderContextReleasedBeforeSharedSession", true)
    exactString("encoderRuntimeMode", expectedEncoderRuntimeMode)
    exactString(
        "runtimeSessionMode",
        if (profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_TEXT_UNET_VAE) {
            "shared_text_unet_vae"
        } else {
            "shared_unet_vae"
        }
    )

    val steps = result.optInt("steps", -1)
    require(steps > 0 && nativeEffective.optInt("steps", -1) == steps) {
        "Shared-QNN img2img did not report the full scheduler step count."
    }
    val expectedStrength = options.strength ?: 1.0
    exactDouble("strength", expectedStrength)
    val fullTimetableCount = result.optInt("fullTimetableCount", -1)
    require(fullTimetableCount == steps &&
        nativeEffective.optInt("fullTimetableCount", -1) == fullTimetableCount
    ) { "Shared-QNN img2img full timetable evidence is invalid." }
    val schedule = resolveQnnImg2ImgSchedule(
        steps = steps,
        fullTimetableCount = fullTimetableCount,
        strength = expectedStrength
    )
    exactInt("timetableCount", schedule.effectiveSteps)
    exactInt("effectiveDenoiseSteps", schedule.effectiveSteps)
    exactInt("img2imgBeginIndex", schedule.beginIndex)
    val useCfg = result.optBoolean("useCfg", false)
    require(nativeEffective.has("useCfg") && nativeEffective.optBoolean("useCfg", !useCfg) == useCfg) {
        "Shared-QNN img2img CFG evidence is inconsistent."
    }
    exactInt(
        "unetExecutionCount",
        Math.multiplyExact(schedule.effectiveSteps, if (useCfg) 2 else 1)
    )

    val tailTimesteps = result.optJSONArray("timesteps")
    require(tailTimesteps != null && tailTimesteps.length() == schedule.effectiveSteps) {
        "Shared-QNN img2img did not report the exact executed scheduler tail."
    }
    val addNoiseTimestep = tailTimesteps.optDouble(0, Double.NaN)
    require(addNoiseTimestep.isFinite()) {
        "Shared-QNN img2img first executed timestep is missing or non-finite."
    }
    val ultraFix = options.ultraFix != null
    if (ultraFix) {
        exactBoolean("img2imgAddNoiseApplied", false)
        exactInt("img2imgAddNoiseBeginIndex", schedule.beginIndex)
        exactDouble("img2imgAddNoiseTimestep", 0.0)
    } else {
        exactBoolean("img2imgAddNoiseApplied", true)
        exactInt("img2imgAddNoiseBeginIndex", schedule.beginIndex)
        exactDouble("img2imgAddNoiseTimestep", addNoiseTimestep)
    }
    val noiseChecksum = result.optString("img2imgNoiseChecksum")
    if (ultraFix) {
        require(noiseChecksum == "0000000000000000" &&
            nativeEffective.optString("img2imgNoiseChecksum") == noiseChecksum
        ) { "Shared-QNN UltraFix did not prove addNoise=false." }
    } else {
        require(noiseChecksum.matches(LOWERCASE_UINT64_HEX) &&
            noiseChecksum != "0000000000000000" &&
            nativeEffective.optString("img2imgNoiseChecksum") == noiseChecksum
        ) {
            "Shared-QNN img2img did not prove non-empty scheduler add-noise output."
        }
    }

    listOf("inputImagePath", "inputImageTensorPath", "maskImagePath", "controlImagePath").forEach {
        result.remove(it)
        nativeEffective.remove(it)
    }
    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", 1)
        .put("maskImageExecutionCount", 0)
        .put("controlImageExecutionCount", 0)
        .put("inputImagePreprocess", QNN_INPUT_TENSOR_PREPROCESS)
        .put("inputImageTensorSha256", prepared.tensorSha256)
        .put("encoderContextSha256", expectedVaeEncoderContextSha256)
        .put("encoderGraphName", expectedVaeEncoderGraphName)
        .put("encoderInputBufferSha256", encoderInputBufferSha256)
        .put("encoderMeanBufferSha256", encoderMeanBufferSha256)
        .put("encoderStdBufferSha256", encoderStdBufferSha256)
        .put("encoderLatentSha256", encoderLatentSha256)
        .put("encoderRuntimeMode", expectedEncoderRuntimeMode)
        .put("fullTimetableCount", schedule.fullTimetableCount)
        .put("effectiveDenoiseSteps", schedule.effectiveSteps)
        .put("img2imgBeginIndex", schedule.beginIndex)
        .put("img2imgAddNoiseTimestep", if (ultraFix) 0.0 else addNoiseTimestep)
        .put("img2imgNoiseChecksum", noiseChecksum)
}
