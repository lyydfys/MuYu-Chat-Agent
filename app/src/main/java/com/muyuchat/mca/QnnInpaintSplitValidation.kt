package com.muyuchat.mca

import java.io.File
import org.json.JSONObject

/**
 * T2B22-T4（b 工位）拆片：`verifyAndSanitizeSplitQnnInpaintProductInput` 全体自
 * QnnInpaintExecutionContract.kt 逐字节平移（含其 KDoc）；仅新增文件头。
 */

/** Verifies the three disposable split-SDXL phases as one product inpaint execution. */
internal fun verifyAndSanitizeSplitQnnInpaintProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions,
    prepared: QnnPreparedInpaintInput,
    expectedVaeEncoderContextSha256: String,
): JSONObject {
    require(options.taskMode == LocalImageTaskMode.INPAINT &&
        options.inputImage != null && options.maskImage != null && options.controlImage == null
    ) { "Split-SDXL inpaint requires exactly one source image and one mask." }
    require(prepared.topology == QnnInpaintMaskTopology.LATENT_BLEND_4 &&
        prepared.targetWidth == 1024 && prepared.targetHeight == 1024
    ) { "Split-SDXL inpaint accepts only the exact 1024 latent_blend_4 contract." }
    require(expectedVaeEncoderContextSha256.matches(QNN_INPAINT_SHA256)) {
        "Split-SDXL VAE encoder context SHA-256 is malformed."
    }
    require(result.optString("executionStage") == "sdxl_three_phase_inpaint_passed") {
        "Split-SDXL inpaint did not complete all three isolated phases."
    }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: error("Split-SDXL inpaint is missing nativeEffective evidence.")
    val detailStageMask = result.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD)
    require(detailStageMask and QNN_INPAINT_ENCODER_GRAPH_EXECUTE_STAGE != 0uL) {
        "Split-SDXL inpaint did not prove a real VAE encoder graphExecute call."
    }

    fun JSONObject.exactLongOrNull(field: String): Long? {
        val raw = opt(field) as? Number ?: return null
        val value = raw.toLong()
        return value.takeIf { raw.toDouble().isFinite() && raw.toDouble() == value.toDouble() }
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
    fun exactString(field: String, expected: String) {
        require(result.opt(field) is String && nativeEffective.opt(field) is String &&
            result.getString(field) == expected && nativeEffective.getString(field) == expected
        ) { "Split-SDXL inpaint $field differs from the executed request." }
    }
    fun exactLong(field: String, expected: Long) {
        require(result.exactLongOrNull(field) == expected &&
            nativeEffective.exactLongOrNull(field) == expected
        ) { "Split-SDXL inpaint $field differs from the executed request." }
    }
    fun exactBoolean(field: String, expected: Boolean) {
        require(result.opt(field) is Boolean && nativeEffective.opt(field) is Boolean &&
            result.getBoolean(field) == expected && nativeEffective.getBoolean(field) == expected
        ) { "Split-SDXL inpaint $field differs from the executed request." }
    }
    fun exactDouble(field: String, expected: Double) {
        val outer = (result.opt(field) as? Number)?.toDouble()
        val inner = (nativeEffective.opt(field) as? Number)?.toDouble()
        require(qnnInpaintNativeFloatMatches(outer, expected) &&
            qnnInpaintNativeFloatMatches(inner, expected)
        ) { "Split-SDXL inpaint $field differs from the executed request." }
    }
    fun exactSha256(field: String, expected: String? = null): String {
        val outer = (result.opt(field) as? String).orEmpty().lowercase()
        val inner = (nativeEffective.opt(field) as? String).orEmpty().lowercase()
        require(outer.matches(QNN_INPAINT_SHA256) && inner == outer &&
            (expected == null || outer == expected.lowercase())
        ) { "Split-SDXL inpaint $field is missing, malformed, or inconsistent." }
        return outer
    }
    fun exactPath(field: String, expected: File) {
        val outer = (result.opt(field) as? String).orEmpty()
        val inner = (nativeEffective.opt(field) as? String).orEmpty()
        require(outer.isNotBlank() && inner.isNotBlank() &&
            File(outer).canonicalFile == expected.canonicalFile &&
            File(inner).canonicalFile == expected.canonicalFile
        ) { "Split-SDXL inpaint did not consume the committed $field artifact." }
    }
    fun exactShape(field: String, expected: List<Int>) {
        require(result.shapeOrNull(field) == expected &&
            nativeEffective.shapeOrNull(field) == expected
        ) { "Split-SDXL inpaint $field differs from the physical tensor contract." }
    }
    fun exactUInt64Evidence(field: String): String {
        val outer = result.optString(field)
        val inner = nativeEffective.optString(field)
        require(outer.matches(QNN_INPAINT_UINT64_HEX) &&
            outer != QNN_INPAINT_ZERO_UINT64_HEX && inner == outer
        ) { "Split-SDXL inpaint $field is missing or inconsistent." }
        return outer
    }

    exactString("taskMode", LocalImageTaskMode.INPAINT.wireName)
    exactString("runtimeSessionMode", SDXL_ISOLATED_ENCODER_UNET_VAE_MODE)
    exactString("inpaintTopology", QnnInpaintMaskTopology.LATENT_BLEND_4.wireName)
    exactLong("batchCount", 1)
    exactLong("inputImageExecutionCount", 1)
    exactLong("maskImageExecutionCount", 1)
    exactLong("controlImageExecutionCount", 0)

    val input = requireNotNull(options.inputImage)
    require(File(prepared.source.sourcePath).canonicalFile == File(input.path).canonicalFile &&
        prepared.source.sourceSha256 == input.sha256 &&
        prepared.source.sourceBytes == input.sizeBytes
    ) { "Prepared split-SDXL source provenance differs from the request." }
    exactPath("inputImagePath", File(input.path))
    exactSha256("inputImageSha256", input.sha256)
    exactBoolean("inputImageSourceReadByNative", false)
    exactString("inputImageSourceValidation", "android_preprocess_provenance")
    exactPath("inputImageTensorPath", File(prepared.source.tensorPath))
    exactSha256("inputImageTensorSha256", prepared.source.tensorSha256)
    exactLong("inputImageTensorBytes", prepared.source.tensorBytes)
    exactShape("inputImageTensorShape", listOf(1, 3, 1024, 1024))
    exactString("inputImageTensorDtype", "float32-le")
    exactString("inputImageTensorLayout", "NCHW")
    exactString("inputImageTensorRange", "NEGATIVE_ONE_TO_ONE")
    exactString("inputImagePreprocess", SDXL_INPUT_TENSOR_PREPROCESS)

    val mask = requireNotNull(options.maskImage)
    require(File(prepared.maskSourcePath).canonicalFile == File(mask.path).canonicalFile &&
        prepared.maskSourceSha256 == mask.sha256 && prepared.maskSourceBytes == mask.sizeBytes
    ) { "Prepared split-SDXL mask provenance differs from the request." }
    exactPath("maskImagePath", File(mask.path))
    exactSha256("maskImageSha256", mask.sha256)
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

    exactSha256("encoderContextSha256", expectedVaeEncoderContextSha256)
    exactString("encoderGraphName", "model")
    exactLong("encoderContextLoadCount", 1)
    exactLong("encoderExecutionCount", 1)
    exactLong("inpaintSourceEncoderExecutionCount", 1)
    val encoderLatentSha256 = exactSha256("encoderLatentSha256")

    val steps = result.exactLongOrNull("steps")?.toInt() ?: -1
    require(steps > 0 && nativeEffective.exactLongOrNull("steps") == steps.toLong()) {
        "Split-SDXL inpaint did not report the full scheduler step count."
    }
    val strength = options.strength ?: 1.0
    exactDouble("strength", strength)
    exactLong("fullTimetableCount", steps.toLong())
    val schedule = resolveQnnInpaintSchedule(steps, steps, strength)
    exactLong("timetableCount", schedule.effectiveSteps.toLong())
    exactLong("effectiveDenoiseSteps", schedule.effectiveSteps.toLong())
    exactLong("img2imgBeginIndex", schedule.beginIndex.toLong())
    exactBoolean("img2imgAddNoiseApplied", true)
    exactLong("img2imgAddNoiseBeginIndex", schedule.beginIndex.toLong())
    val useCfg = result.opt("useCfg") as? Boolean
        ?: error("Split-SDXL inpaint useCfg evidence is missing.")
    exactBoolean("useCfg", useCfg)
    exactLong(
        "unetExecutionCount",
        Math.multiplyExact(schedule.effectiveSteps, if (useCfg) 2 else 1).toLong(),
    )
    val timesteps = result.optJSONArray("timesteps")
    require(timesteps != null && timesteps.length() == schedule.effectiveSteps &&
        timesteps.optDouble(0, Double.NaN).isFinite()
    ) { "Split-SDXL inpaint scheduler-tail evidence is invalid." }
    exactDouble("img2imgAddNoiseTimestep", timesteps.getDouble(0))
    val noiseChecksum = exactUInt64Evidence("img2imgNoiseChecksum")
    val sourceNoiseSha256 = exactSha256("inpaintSourceNoiseSha256")
    exactLong("inpaintMaskUnetBindCount", 0)
    exactLong("inpaintPreserveStepCount", schedule.effectiveSteps.toLong())
    exactLong("inpaintLatentBlendCount", schedule.effectiveSteps.toLong())
    exactLong("inpaintSourceNoiseUseCount", schedule.effectiveSteps.toLong())
    val preservedChecksum = exactUInt64Evidence("inpaintPreservedLatentChecksum")
    exactBoolean("inpaintMaskConsumed", true)
    exactBoolean("inpaintUnmaskedPreservationApplied", true)
    exactString(
        "inpaintFinalMode",
        "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend",
    )
    exactLong(
        "inpaintPixelBlendLevels",
        qnnInpaintLaplacianLevelCount(1024, 1024).toLong(),
    )
    exactBoolean("inpaintPixelBlendApplied", true)
    val pixelBlendChecksum = exactUInt64Evidence("inpaintPixelBlendChecksum")

    listOf(
        "inputImagePath",
        "inputImageTensorPath",
        "maskImagePath",
        "maskImageTensorPath",
        "maskImageFullTensorPath",
        "sourceLatentPath",
        "sourceMetadataPath",
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
        .put("encoderLatentSha256", encoderLatentSha256)
        .put("fullTimetableCount", schedule.fullTimetableCount)
        .put("effectiveDenoiseSteps", schedule.effectiveSteps)
        .put("img2imgBeginIndex", schedule.beginIndex)
        .put("img2imgNoiseChecksum", noiseChecksum)
        .put("inpaintSourceNoiseSha256", sourceNoiseSha256)
        .put("inpaintPreservedLatentChecksum", preservedChecksum)
        .put("inpaintFinalMode", "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend")
        .put("inpaintPixelBlendLevels", qnnInpaintLaplacianLevelCount(1024, 1024))
        .put("inpaintPixelBlendChecksum", pixelBlendChecksum)
        .put("inpaintPixelBlendApplied", true)
}
