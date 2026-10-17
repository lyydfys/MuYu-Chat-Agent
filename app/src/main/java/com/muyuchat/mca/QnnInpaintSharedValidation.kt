package com.muyuchat.mca

import java.io.File
import org.json.JSONObject

/**
 * T2B22-T4（b 工位）拆片：`verifyAndSanitizeSharedQnnInpaintProductInput` 全体自
 * QnnInpaintExecutionContract.kt 逐字节平移（含其 KDoc）。壳保留调度入口
 * （QnnInpaintSchedule / resolveQnnInpaintSchedule / withQnnInpaintProductSchedule）与共享
 * 常量；本文件仅新增 package/import/本说明头。
 */

/** Verifies native inpaint consumption before any worker-private path can escape the worker. */
internal fun verifyAndSanitizeSharedQnnInpaintProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions,
    prepared: QnnPreparedInpaintInput,
    profile: ImageExecutionProfile,
    expectedVaeEncoderContextSha256: String,
    expectedVaeEncoderGraphName: String,
): JSONObject {
    require(profile.hasExecutableQnnInpaintTopology()) {
        "Shared-QNN inpaint verification requires a coherent encoder/UNet/decoder topology."
    }
    val inspectedTopology = profile.inspectQnnInpaintTopology()
    require(inspectedTopology.supported && inspectedTopology.topology == prepared.topology &&
        inspectedTopology.width == prepared.targetWidth / 8 &&
        inspectedTopology.height == prepared.targetHeight / 8
    ) { "Prepared QNN inpaint artifacts differ from the resolved UNet topology." }
    require(
        options.taskMode == LocalImageTaskMode.INPAINT &&
            options.inputImage != null && options.maskImage != null && options.controlImage == null
    ) { "Shared-QNN inpaint verification requires exactly one source image and one mask." }
    require(expectedVaeEncoderContextSha256.matches(QNN_INPAINT_SHA256)) {
        "Resolved shared-QNN VAE encoder context SHA-256 is malformed."
    }
    require(expectedVaeEncoderGraphName.isNotBlank()) {
        "Resolved shared-QNN VAE encoder graph name is missing."
    }

    val detailStageMask = result.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD)
    require(detailStageMask and QNN_INPAINT_ENCODER_GRAPH_EXECUTE_STAGE != 0uL) {
        "Shared-QNN inpaint did not prove a real VAE encoder graphExecute call."
    }
    val nativeEffective = requireNotNull(result.optJSONObject("nativeEffective")) {
        "Shared-QNN inpaint did not report nativeEffective evidence."
    }
    if (nativeEffective.has(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ||
        nativeEffective.has("nativeDetailStageMask")
    ) {
        require(
            nativeEffective.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ==
                detailStageMask
        ) { "Shared-QNN inpaint detail-stage evidence conflicts with nativeEffective." }
    }

    fun exactString(field: String, expected: String) {
        require(result.opt(field) is String && nativeEffective.opt(field) is String &&
            result.getString(field) == expected && nativeEffective.getString(field) == expected
        ) { "Shared-QNN inpaint $field differs from the executed request." }
    }
    fun JSONObject.exactLongOrNull(field: String): Long? {
        val raw = opt(field) as? Number ?: return null
        val value = raw.toLong()
        return value.takeIf { raw.toDouble().isFinite() && raw.toDouble() == value.toDouble() }
    }
    fun exactLong(field: String, expected: Long) {
        require(result.exactLongOrNull(field) == expected &&
            nativeEffective.exactLongOrNull(field) == expected
        ) { "Shared-QNN inpaint $field differs from the executed request." }
    }
    fun exactBoolean(field: String, expected: Boolean) {
        require(result.opt(field) is Boolean && nativeEffective.opt(field) is Boolean &&
            result.getBoolean(field) == expected && nativeEffective.getBoolean(field) == expected
        ) { "Shared-QNN inpaint $field differs from the executed request." }
    }
    fun exactDouble(field: String, expected: Double) {
        fun JSONObject.value(): Double? = (opt(field) as? Number)
            ?.toDouble()
            ?.takeIf { it.isFinite() }
        require(qnnInpaintNativeFloatMatches(result.value(), expected) &&
            qnnInpaintNativeFloatMatches(nativeEffective.value(), expected)
        ) { "Shared-QNN inpaint $field differs from the executed request." }
    }
    fun exactSha256(field: String, expected: String? = null): String {
        val outer = (result.opt(field) as? String).orEmpty()
        val inner = (nativeEffective.opt(field) as? String).orEmpty()
        require(outer.matches(QNN_INPAINT_SHA256) && inner == outer &&
            (expected == null || outer == expected)
        ) { "Shared-QNN inpaint $field is missing, malformed, or inconsistent." }
        return outer
    }
    fun JSONObject.shapeOrNull(field: String): List<Int>? {
        val array = optJSONArray(field) ?: return null
        return List(array.length()) { index ->
            val raw = array.opt(index) as? Number ?: return null
            val value = raw.toInt()
            if (raw.toDouble() != value.toDouble()) return null
            value
        }
    }
    fun exactShape(field: String, expected: List<Int>) {
        require(result.shapeOrNull(field) == expected && nativeEffective.shapeOrNull(field) == expected) {
            "Shared-QNN inpaint $field differs from the physical tensor contract."
        }
    }
    fun exactPath(field: String, expected: File) {
        val outer = (result.opt(field) as? String).orEmpty()
        val inner = (nativeEffective.opt(field) as? String).orEmpty()
        require(outer.isNotBlank() && inner.isNotBlank() &&
            File(outer).canonicalFile == expected.canonicalFile &&
            File(inner).canonicalFile == expected.canonicalFile
        ) { "Shared-QNN inpaint did not consume the committed $field artifact." }
    }
    fun exactEmptyString(field: String) {
        require(result.opt(field) is String && nativeEffective.opt(field) is String &&
            result.getString(field).isEmpty() && nativeEffective.getString(field).isEmpty()
        ) { "Shared-QNN inpaint reported an unexpected $field value." }
    }

    exactString("taskMode", LocalImageTaskMode.INPAINT.wireName)
    exactLong("batchCount", 1)
    exactLong("inputImageExecutionCount", 1)
    exactLong("maskImageExecutionCount", 1)
    exactLong("controlImageExecutionCount", 0)
    exactEmptyString("controlImagePath")

    val expectedInput = requireNotNull(options.inputImage)
    require(File(prepared.source.sourcePath).canonicalFile == File(expectedInput.path).canonicalFile &&
        prepared.source.sourceSha256 == expectedInput.sha256 &&
        prepared.source.sourceBytes == expectedInput.sizeBytes
    ) { "Prepared shared-QNN source provenance differs from the request." }
    exactPath("inputImagePath", File(expectedInput.path))
    exactSha256("inputImageSha256", expectedInput.sha256)
    exactBoolean("inputImageSourceReadByNative", false)
    exactString("inputImageSourceValidation", "android_preprocess_provenance")
    exactPath("inputImageTensorPath", File(prepared.source.tensorPath))
    exactSha256("inputImageTensorSha256", prepared.source.tensorSha256)
    exactString("inputImagePreprocess", QNN_INPUT_TENSOR_PREPROCESS)
    exactLong("inputImageSourceWidth", prepared.source.sourceWidth.toLong())
    exactLong("inputImageSourceHeight", prepared.source.sourceHeight.toLong())
    exactLong("inputImageOrientedWidth", prepared.source.orientedWidth.toLong())
    exactLong("inputImageOrientedHeight", prepared.source.orientedHeight.toLong())
    exactLong("inputImageExifOrientation", prepared.source.exifOrientation.toLong())
    exactLong("inputImageTensorWidth", prepared.targetWidth.toLong())
    exactLong("inputImageTensorHeight", prepared.targetHeight.toLong())
    exactLong("inputImageTensorChannels", 3)
    exactLong("inputImageTensorBytes", prepared.source.tensorBytes)
    exactShape("inputImageTensorShape", listOf(1, 3, prepared.targetHeight, prepared.targetWidth))
    exactString("inputImageTensorDtype", QNN_INPUT_TENSOR_DTYPE)
    exactString("inputImageTensorLayout", QNN_INPUT_TENSOR_LAYOUT)
    exactString("inputImageTensorRange", QNN_INPUT_TENSOR_RANGE)

    val expectedMask = requireNotNull(options.maskImage)
    require(File(prepared.maskSourcePath).canonicalFile == File(expectedMask.path).canonicalFile &&
        prepared.maskSourceSha256 == expectedMask.sha256 &&
        prepared.maskSourceBytes == expectedMask.sizeBytes
    ) { "Prepared shared-QNN mask provenance differs from the request." }
    exactPath("maskImagePath", File(expectedMask.path))
    exactSha256("maskImageSha256", expectedMask.sha256)
    exactLong("maskImageSizeBytes", prepared.maskSourceBytes)
    exactBoolean("maskImageSourceReadByNative", false)
    exactString("maskImageSourceValidation", "android_preprocess_provenance")
    exactLong("maskImageSourceWidth", prepared.maskSourceWidth.toLong())
    exactLong("maskImageSourceHeight", prepared.maskSourceHeight.toLong())
    exactLong("maskImageOrientedWidth", prepared.maskOrientedWidth.toLong())
    exactLong("maskImageOrientedHeight", prepared.maskOrientedHeight.toLong())
    exactLong("maskImageExifOrientation", prepared.maskExifOrientation.toLong())
    exactPath("maskImageTensorPath", File(prepared.maskTensorPath))
    exactSha256("maskImageTensorSha256", prepared.maskTensorSha256)
    exactLong("maskImageTensorBytes", prepared.maskTensorBytes)
    exactShape("maskImageTensorShape", prepared.maskTensorShape)
    exactString("maskImageTensorDtype", "float32-le")
    exactString("maskImageTensorLayout", "NCHW")
    exactString("maskImageTensorRange", "ZERO_TO_ONE")
    exactString("maskImageTensorPreprocess", QNN_INPAINT_MASK_PREPROCESS)
    exactPath("maskImageFullTensorPath", File(prepared.fullMaskTensorPath))
    exactSha256("maskImageFullTensorSha256", prepared.fullMaskTensorSha256)
    exactLong("maskImageFullTensorBytes", prepared.fullMaskTensorBytes)
    exactShape("maskImageFullTensorShape", prepared.fullMaskTensorShape)
    exactString("maskImageFullTensorDtype", "float32-le")
    exactString("maskImageFullTensorLayout", "NCHW")
    exactString("maskImageFullTensorRange", "ZERO_TO_ONE")
    exactString("maskImageFullTensorPreprocess", QNN_INPAINT_FULL_MASK_PREPROCESS)
    exactLong("maskImageRepaintPixelCount", prepared.repaintPixelCount)
    exactLong("maskImageLatentRepaintPixelCount", prepared.latentRepaintPixelCount)

    exactString("inpaintTopology", prepared.topology.wireName)
    exactSha256("encoderContextSha256", expectedVaeEncoderContextSha256)
    exactString("encoderGraphName", expectedVaeEncoderGraphName)
    exactLong("encoderContextLoadCount", 1)
    exactLong("inpaintSourceEncoderExecutionCount", 1)
    exactString("encoderInputName", "input")
    exactString("encoderMeanOutputName", "mean")
    exactString("encoderStdOutputName", "std")
    exactString("encoderInputDtype", "uint16")
    exactString("encoderMeanDtype", "uint16")
    exactString("encoderStdDtype", "uint16")
    exactShape("encoderInputShape", listOf(1, 3, prepared.targetHeight, prepared.targetWidth))
    val latentShape = listOf(1, 4, prepared.targetHeight / 8, prepared.targetWidth / 8)
    exactShape("encoderMeanShape", latentShape)
    exactShape("encoderStdShape", latentShape)
    val encoderInputBufferSha256 = exactSha256("encoderInputBufferSha256")
    val encoderMeanBufferSha256 = exactSha256("encoderMeanBufferSha256")
    val encoderStdBufferSha256 = exactSha256("encoderStdBufferSha256")
    val encoderLatentSha256 = exactSha256("encoderLatentSha256")
    exactString("posteriorSampling", "mean_plus_std_times_normal_mt19937_domain_v1")
    val latentElementCount = Math.multiplyExact(
        4L,
        Math.multiplyExact((prepared.targetHeight / 8).toLong(), (prepared.targetWidth / 8).toLong()),
    )
    exactLong("posteriorSampleCount", latentElementCount)
    exactDouble("encoderLatentScalingFactor", profile.vae.scalingFactor)
    exactBoolean("encoderContextReleasedBeforeSharedSession", true)

    val requiresMaskedLatent = prepared.topology.requiresMaskedImageLatent
    exactLong("encoderExecutionCount", if (requiresMaskedLatent) 2 else 1)
    exactString(
        "encoderRuntimeMode",
        if (requiresMaskedLatent) {
            "standalone_dual_encode_then_shared_unet_vae"
        } else {
            "standalone_encoder_then_shared_unet_vae"
        },
    )
    if (requiresMaskedLatent) {
        exactPath("maskedInputImageTensorPath", File(requireNotNull(prepared.maskedInputTensorPath)))
        exactSha256("maskedInputImageTensorSha256", requireNotNull(prepared.maskedInputTensorSha256))
        exactLong("maskedInputImageTensorBytes", prepared.maskedInputTensorBytes)
        exactShape("maskedInputImageTensorShape", prepared.maskedInputTensorShape)
        exactString("maskedInputImageTensorDtype", "float32-le")
        exactString("maskedInputImageTensorLayout", "NCHW")
        exactString("maskedInputImageTensorRange", "NEGATIVE_ONE_TO_ONE")
        exactString("maskedInputImageTensorPreprocess", QNN_INPAINT_MASKED_RGB_PREPROCESS)
        exactSha256("maskedInputBufferSha256")
        exactSha256("maskedInputMeanBufferSha256")
        exactSha256("maskedInputStdBufferSha256")
        exactSha256("maskedInputLatentSha256")
        exactShape("maskedInputLatentShape", latentShape)
        exactLong("maskedInputEncoderExecutionCount", 1)
        exactLong("maskedInputPosteriorSampleCount", latentElementCount)
    } else {
        exactEmptyString("maskedInputImageTensorPath")
        exactEmptyString("maskedInputImageTensorSha256")
        exactLong("maskedInputImageTensorBytes", 0)
        exactShape("maskedInputImageTensorShape", listOf(1, 3, 0, 0))
        listOf(
            "maskedInputImageTensorDtype",
            "maskedInputImageTensorLayout",
            "maskedInputImageTensorRange",
            "maskedInputImageTensorPreprocess",
            "maskedInputBufferSha256",
            "maskedInputMeanBufferSha256",
            "maskedInputStdBufferSha256",
            "maskedInputLatentSha256",
        ).forEach(::exactEmptyString)
        exactShape("maskedInputLatentShape", listOf(1, 4, 0, 0))
        exactLong("maskedInputEncoderExecutionCount", 0)
        exactLong("maskedInputPosteriorSampleCount", 0)
    }

    val steps = result.exactLongOrNull("steps")?.toInt() ?: -1
    require(steps > 0 && nativeEffective.exactLongOrNull("steps") == steps.toLong()) {
        "Shared-QNN inpaint did not report the full scheduler step count."
    }
    val expectedStrength = options.strength ?: 1.0
    exactDouble("strength", expectedStrength)
    exactLong("fullTimetableCount", steps.toLong())
    val schedule = resolveQnnInpaintSchedule(
        steps = steps,
        fullTimetableCount = steps,
        strength = expectedStrength,
    )
    exactLong("timetableCount", schedule.effectiveSteps.toLong())
    exactLong("effectiveDenoiseSteps", schedule.effectiveSteps.toLong())
    exactLong("img2imgBeginIndex", schedule.beginIndex.toLong())
    val useCfg = result.opt("useCfg") as? Boolean
        ?: error("Shared-QNN inpaint useCfg evidence is missing.")
    exactBoolean("useCfg", useCfg)
    val expectedUnetExecutions = Math.multiplyExact(
        schedule.effectiveSteps,
        if (useCfg) 2 else 1,
    )
    exactLong("unetExecutionCount", expectedUnetExecutions.toLong())
    val tailTimesteps = result.optJSONArray("timesteps")
    require(tailTimesteps != null && tailTimesteps.length() == schedule.effectiveSteps) {
        "Shared-QNN inpaint did not report the exact executed scheduler tail."
    }
    val addNoiseTimestep = tailTimesteps.optDouble(0, Double.NaN)
    require(addNoiseTimestep.isFinite()) { "Shared-QNN inpaint add-noise timestep is invalid." }
    exactBoolean("img2imgAddNoiseApplied", true)
    exactLong("img2imgAddNoiseBeginIndex", schedule.beginIndex.toLong())
    exactDouble("img2imgAddNoiseTimestep", addNoiseTimestep)
    val img2imgNoiseChecksum = (result.opt("img2imgNoiseChecksum") as? String).orEmpty()
    require(img2imgNoiseChecksum.matches(QNN_INPAINT_UINT64_HEX) &&
        img2imgNoiseChecksum != QNN_INPAINT_ZERO_UINT64_HEX &&
        nativeEffective.optString("img2imgNoiseChecksum") == img2imgNoiseChecksum
    ) { "Shared-QNN inpaint add-noise evidence is missing or inconsistent." }

    val expectedMaskBindCount = if (prepared.topology.requiresNativeMaskBinding) {
        expectedUnetExecutions
    } else {
        0
    }
    exactLong("inpaintMaskUnetBindCount", expectedMaskBindCount.toLong())
    exactLong("inpaintPreserveStepCount", schedule.effectiveSteps.toLong())
    exactLong("inpaintLatentBlendCount", schedule.effectiveSteps.toLong())
    exactSha256("inpaintSourceNoiseSha256")
    exactLong("inpaintSourceNoiseUseCount", schedule.effectiveSteps.toLong())
    exactString(
        "inpaintFinalMode",
        "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend",
    )
    exactLong(
        "inpaintPixelBlendLevels",
        qnnInpaintLaplacianLevelCount(prepared.targetWidth, prepared.targetHeight).toLong(),
    )
    exactBoolean("inpaintPixelBlendApplied", true)
    val pixelBlendChecksum = result.optString("inpaintPixelBlendChecksum")
    require(pixelBlendChecksum.matches(QNN_INPAINT_UINT64_HEX) &&
        pixelBlendChecksum != QNN_INPAINT_ZERO_UINT64_HEX &&
        nativeEffective.optString("inpaintPixelBlendChecksum") == pixelBlendChecksum
    ) { "Shared-QNN inpaint Laplacian pixel-blend evidence is invalid." }
    exactBoolean("inpaintMaskConsumed", true)
    exactBoolean("inpaintUnmaskedPreservationApplied", true)
    val preservedChecksum = result.optString("inpaintPreservedLatentChecksum")
    require(preservedChecksum.matches(QNN_INPAINT_UINT64_HEX) &&
        preservedChecksum != QNN_INPAINT_ZERO_UINT64_HEX &&
        nativeEffective.optString("inpaintPreservedLatentChecksum") == preservedChecksum
    ) { "Shared-QNN inpaint preserved-latent evidence is invalid." }

    listOf(
        "inputImagePath",
        "inputImageTensorPath",
        "maskImagePath",
        "maskImageTensorPath",
        "maskImageFullTensorPath",
        "maskedInputImageTensorPath",
        "controlImagePath",
    ).forEach { field ->
        result.remove(field)
        nativeEffective.remove(field)
    }
    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", 1)
        .put("maskImageExecutionCount", 1)
        .put("controlImageExecutionCount", 0)
        .put("inpaintTopology", prepared.topology.wireName)
        .put("maskImageTensorSha256", prepared.maskTensorSha256)
        .put("maskImageFullTensorSha256", prepared.fullMaskTensorSha256)
        .put("encoderContextSha256", expectedVaeEncoderContextSha256)
        .put("encoderGraphName", expectedVaeEncoderGraphName)
        .put("encoderInputBufferSha256", encoderInputBufferSha256)
        .put("encoderMeanBufferSha256", encoderMeanBufferSha256)
        .put("encoderStdBufferSha256", encoderStdBufferSha256)
        .put("encoderLatentSha256", encoderLatentSha256)
        .put("fullTimetableCount", schedule.fullTimetableCount)
        .put("effectiveDenoiseSteps", schedule.effectiveSteps)
        .put("img2imgBeginIndex", schedule.beginIndex)
        .put("img2imgAddNoiseTimestep", addNoiseTimestep)
        .put("img2imgNoiseChecksum", img2imgNoiseChecksum)
        .put("inpaintLatentBlendCount", schedule.effectiveSteps)
        .put("inpaintFinalMode", "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend")
        .put("inpaintPixelBlendLevels", qnnInpaintLaplacianLevelCount(
            prepared.targetWidth,
            prepared.targetHeight,
        ))
        .put("inpaintPixelBlendChecksum", pixelBlendChecksum)
        .put("inpaintPixelBlendApplied", true)
}
