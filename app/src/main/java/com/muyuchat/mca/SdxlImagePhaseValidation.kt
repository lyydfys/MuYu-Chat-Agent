package com.muyuchat.mca

import java.io.File
import kotlin.math.abs
import org.json.JSONObject

private val QNN_UINT64_HEX_PATTERN = Regex("^[0-9a-f]{16}$")

internal fun expectedSdxlUltraFixTileCount(request: LocalImageUltraFixOptions): Int {
    require(request.tileSize == 1024 && request.targetWidth in 1024..2048 &&
        request.targetHeight in 1024..2048 && request.targetWidth % 64 == 0 &&
        request.targetHeight % 64 == 0
    ) { "Split-SDXL UltraFix tile counting requires the fixed aligned graph envelope." }
    return Math.toIntExact(localQnnUltraFixTilePlanEvidence(request).tileCount)
}

internal fun JSONObject.strictUInt64Hex(
    hexField: String,
    legacyNumericField: String = hexField.removeSuffix("Hex")
): ULong {
    require(!has(legacyNumericField)) {
        "Legacy numeric $legacyNumericField is not accepted; $hexField is required."
    }
    val encoded = opt(hexField) as? String
        ?: error("$hexField must be a fixed-width hexadecimal string.")
    require(QNN_UINT64_HEX_PATTERN.matches(encoded)) {
        "$hexField must contain exactly 16 lowercase hexadecimal digits."
    }
    return encoded.toULong(16)
}

internal fun validateSdxlUnetNativeEvidence(
    contract: SdxlImageExecutionContract,
    nativeResult: JSONObject
) {
    val nativeEffective = contract.requireNativeEffective(nativeResult, SdxlImagePhase.UNET)
    val nativeEffectiveJson = nativeResult.optJSONObject("nativeEffective")
        ?: error("UNet native result is missing nativeEffective evidence.")
    val expectedParams = contract.paramsObject()
    val ultraFix = contract.ultraFixRequestOrNull()
    fun requireMatchingString(field: String, expected: String) {
        val flat = nativeResult.strictString(field)
        val nested = nativeEffectiveJson.strictString(field)
        require(flat == expected && nested == expected && flat == nested) {
            "UNet $field evidence conflicts with the resolved conditioning contract."
        }
    }
    fun requireMatchingInt(field: String, expected: Int) {
        val flat = nativeResult.strictInt(field)
        val nested = nativeEffectiveJson.strictInt(field)
        require(flat == expected && nested == expected && flat == nested) {
            "UNet $field evidence conflicts with the resolved conditioning contract."
        }
    }
    val expectedConditioningGraphSha256 = expectedParams
        .strictString("conditioningGraphSha256")
        .lowercase()
    require(SDXL_SHA256.matches(expectedConditioningGraphSha256)) {
        "Resolved SDXL conditioning graph fingerprint is invalid."
    }
    requireMatchingString(
        "conditioningExecutionMode",
        "external_mnn_sdxl_embeddings"
    )
    requireMatchingString("conditioningBackend", "MNN")
    requireMatchingString("conditioningGraph", "clip.mnn+clip_2.mnn")
    requireMatchingString("conditioningGraphSha256", expectedConditioningGraphSha256)
    val expectedConditioningOrder = if (nativeEffective.useCfg) {
        "negative_then_positive"
    } else {
        "positive_only"
    }
    val expectedConditioningEncoderExecutionCount = if (nativeEffective.useCfg) 4 else 2
    require(
        expectedParams.strictString("conditioningOrder") == expectedConditioningOrder &&
            expectedParams.strictInt("conditioningEncoderExecutionCount") ==
                expectedConditioningEncoderExecutionCount
    ) {
        "Resolved SDXL conditioning evidence conflicts with CFG branches."
    }
    requireMatchingString("conditioningOrder", expectedConditioningOrder)
    requireMatchingInt("conditioningEncoderExecutionCount", expectedConditioningEncoderExecutionCount)
    requireMatchingInt("textEncoderExecutionCount", 0)
    require(
        nativeResult.strictBoolean("conditioningArtifactConsumed") &&
            nativeEffectiveJson.strictBoolean("conditioningArtifactConsumed")
    ) {
        "UNet did not prove that it consumed the prepared conditioning artifact."
    }
    requireMatchingString("runtimeSessionMode", "isolated_unet_phase")
    require(nativeResult.strictString("phase") == SdxlImagePhase.UNET.wireName) {
        "UNet native phase identity mismatch."
    }
    require(nativeResult.strictBoolean("processExitRequired")) {
        "UNet must require disposable-process exit after latent publication."
    }
    require(!nativeResult.strictBoolean("contextReleased")) {
        "UNet phase must retain its single context until the disposable process exits."
    }
    require(nativeResult.strictInt("unetContextLoadCount") == 1) {
        "UNet must load exactly one graph context in its disposable worker."
    }
    require(nativeResult.strictInt("unetSamplingLoopCount") == 1) {
        "UNet must execute one uninterrupted scheduler loop."
    }
    if (ultraFix == null) {
        require(nativeResult.strictInt("unetSamplingStepCount") == nativeEffective.timetableCount) {
            "UNet scheduler-loop step evidence conflicts with nativeEffective."
        }
        require(nativeResult.strictInt("unetGraphExecutionCount") == nativeEffective.unetExecutionCount) {
            "UNet graph execution evidence conflicts with nativeEffective."
        }
    } else {
        val tileCount = nativeResult.strictInt("ultraFixTileCount")
        val inversionGraphs = Math.multiplyExact(tileCount, ultraFix.inversionSteps)
        val refinementPositive = Math.multiplyExact(tileCount, ultraFix.inversionSteps)
        val refinementNegative = if (nativeEffective.useCfg) refinementPositive else 0
        val physicalGraphCount = Math.addExact(
            Math.addExact(inversionGraphs, refinementPositive),
            refinementNegative
        )
        require(tileCount > 0 &&
            nativeResult.strictInt("unetSamplingStepCount") ==
                Math.addExact(ultraFix.inversionSteps, ultraFix.inversionSteps) &&
            nativeResult.strictInt("unetGraphExecutionCount") == physicalGraphCount &&
            nativeResult.strictInt("ultraFixInversionStepCount") == ultraFix.inversionSteps &&
            nativeResult.strictInt("ultraFixInversionGraphExecutionCount") == inversionGraphs &&
            nativeResult.strictInt("ultraFixInversionTileSuccessCount") == inversionGraphs &&
            nativeResult.strictInt("ultraFixRefinementStepCount") == ultraFix.inversionSteps &&
            nativeResult.strictInt("ultraFixRefinementPositiveGraphExecutionCount") ==
                refinementPositive &&
            nativeResult.strictInt("ultraFixRefinementNegativeGraphExecutionCount") ==
                refinementNegative &&
            nativeResult.strictInt("ultraFixRefinementTileSuccessCount") ==
                refinementPositive + refinementNegative &&
            nativeResult.strictInt("ultraFixPhysicalUnetGraphExecutionCount") ==
                physicalGraphCount &&
            nativeResult.strictString("ultraFixTilePlanSha256").lowercase()
                .matches(SDXL_SHA256) &&
            nativeResult.strictString("ultraFixSampleMethod") ==
                nativeResult.strictString("scheduler") &&
             nativeResult.strictString("ultraFixNativeScheduler") ==
                 nativeResult.strictString("scheduler") &&
             (!expectedParams.has("ultraFixTilePlanSha256") ||
                 nativeResult.strictString("ultraFixTilePlanSha256").lowercase() ==
                     expectedParams.strictString("ultraFixTilePlanSha256").lowercase())
         ) { "Split-SDXL UltraFix inversion/refinement evidence is incomplete." }
        val qualitySteps = nativeResult.strictInt("ultraFixQualityStepEvaluationCount")
        val noiseSteps = nativeResult.strictInt("ultraFixNoiseInjectionStepCount")
        val structureSteps = nativeResult.strictInt("ultraFixStructureGuidanceStepCount")
        val noiseSeed = nativeResult.strictString("ultraFixNoiseInjectionSeedFingerprint").lowercase()
        val noiseChecksum = nativeResult.strictString("ultraFixNoiseInjectionChecksum").lowercase()
        val structureChecksum = nativeResult.strictString("ultraFixStructureGuidanceChecksum").lowercase()
        val trajectoryChecksum = nativeResult.strictString("ultraFixTrajectoryNoiseChecksum").lowercase()
        require(
            qualitySteps == ultraFix.inversionSteps - 1 &&
                SDXL_SHA256.matches(noiseSeed) &&
                QNN_UINT64_HEX_PATTERN.matches(noiseChecksum) &&
                QNN_UINT64_HEX_PATTERN.matches(structureChecksum) &&
                QNN_UINT64_HEX_PATTERN.matches(trajectoryChecksum) &&
                if (ultraFix.inversionSteps == 1) {
                    noiseSteps == 0 && structureSteps == 0 &&
                        noiseChecksum == "0000000000000000" &&
                        structureChecksum == "0000000000000000" &&
                        trajectoryChecksum == "0000000000000000"
                } else {
                    noiseSteps > 0 && structureSteps > 0 &&
                        noiseChecksum != "0000000000000000" &&
                        structureChecksum != "0000000000000000" &&
                        trajectoryChecksum != "0000000000000000"
                }
        ) { "Split-SDXL UltraFix quality evidence is incomplete or misclassified." }
    }
    require(nativeResult.strictBoolean("unetContextReusedAcrossSteps")) {
        "UNet did not prove one context was reused across the complete timetable."
    }
    require(nativeResult.strictString("unetGraphName") == nativeEffective.graphName) {
        "UNet graph-name evidence conflicts with nativeEffective."
    }
    require(nativeResult.strictInt("timetableCount") == nativeEffective.timetableCount) {
        "UNet timetable evidence conflicts with nativeEffective."
    }
    require(nativeResult.strictInt("unetExecutionCount") == nativeEffective.unetExecutionCount) {
        "UNet execution evidence conflicts with nativeEffective."
    }
    require(
        nativeResult.strictString("conditioningArtifactSha256")
            .equals(contract.conditioningArtifactSha256, ignoreCase = true)
    ) { "UNet conditioning artifact proof conflicts with the resolved request." }
    val latentShape = nativeResult.getJSONArray("latentShape").toPositiveIntList()
    require(latentShape == contract.expectedLatentShape()) {
        "UNet latent shape does not match the resolved SDXL output size."
    }
    val latentElements = latentShape.fold(1L) { total, value ->
        Math.multiplyExact(total, value.toLong())
    }
    require(nativeResult.strictLong("latentElements") == latentElements) {
        "UNet latent element evidence does not match its shape."
    }
    require(nativeResult.strictLong("latentBytes") == Math.multiplyExact(latentElements, 4L)) {
        "UNet latent byte evidence does not match float32 storage."
    }
    val taskMode = LocalImageTaskMode.fromWireName(
        expectedParams.optString("taskMode", LocalImageTaskMode.TEXT_TO_IMAGE.wireName)
    )
    require(nativeEffectiveJson.strictString("taskMode") == taskMode.wireName) {
        "UNet nativeEffective taskMode differs from the resolved request."
    }
    if (taskMode in setOf(LocalImageTaskMode.IMG2IMG, LocalImageTaskMode.INPAINT)) {
        val expectedStrength = expectedParams.optDouble("strength", 1.0)
        val fullTimetableCount = expectedParams.strictInt("fullTimetableCount")
        val beginIndex = expectedParams.strictInt("img2imgBeginIndex")
        require(nativeEffectiveJson.strictInt("inputImageExecutionCount") == 1 &&
            nativeEffectiveJson.strictString("inputImagePath") ==
                expectedParams.strictString("inputImagePath") &&
            nativeEffectiveJson.strictString("inputImageSha256").lowercase() ==
                expectedParams.strictString("inputImageSha256").lowercase() &&
            nativeEffectiveJson.strictString("inputImageTensorPath") ==
                expectedParams.strictString("inputImageTensorPath") &&
            nativeEffectiveJson.strictString("inputImageTensorSha256").lowercase() ==
                expectedParams.strictString("inputImageTensorSha256").lowercase() &&
            nativeEffectiveJson.strictString("inputImagePreprocess") == SDXL_INPUT_TENSOR_PREPROCESS &&
            abs(nativeEffectiveJson.strictDouble("strength") - expectedStrength) <= 1e-9 &&
            nativeEffectiveJson.strictInt("fullTimetableCount") == fullTimetableCount &&
            nativeEffectiveJson.strictInt("img2imgBeginIndex") == beginIndex &&
            nativeResult.strictString("encoderLatentSha256").lowercase() ==
                nativeEffectiveJson.strictString("encoderLatentSha256").lowercase() &&
            SDXL_SHA256.matches(nativeResult.strictString("encoderLatentSha256").lowercase())
        ) { "UNet did not prove the exact encoder-backed image input and tail schedule." }
        if (ultraFix != null) {
            require(!nativeEffectiveJson.strictBoolean("img2imgAddNoiseApplied") &&
                nativeEffectiveJson.strictInt("img2imgAddNoiseBeginIndex") ==
                    expectedParams.strictInt("img2imgBeginIndex") &&
                nativeEffectiveJson.strictString("img2imgNoiseChecksum") ==
                    "0000000000000000"
            ) {
                "Split-SDXL UltraFix must use DDIM inversion instead of ordinary img2img add-noise."
            }
        }
        if (taskMode == LocalImageTaskMode.INPAINT) {
            require(nativeEffectiveJson.strictString("inpaintTopology") ==
                QnnInpaintMaskTopology.LATENT_BLEND_4.wireName &&
                nativeEffectiveJson.strictString("maskImageTensorPath") ==
                expectedParams.strictString("maskImageTensorPath") &&
                nativeEffectiveJson.strictString("maskImageTensorSha256").lowercase() ==
                expectedParams.strictString("maskImageTensorSha256").lowercase() &&
                nativeEffectiveJson.strictInt("inpaintPreserveStepCount") ==
                nativeEffective.timetableCount &&
                nativeEffectiveJson.strictInt("inpaintLatentBlendCount") ==
                nativeEffective.timetableCount &&
                nativeEffectiveJson.strictInt("inpaintSourceNoiseUseCount") ==
                nativeEffective.timetableCount
            ) { "UNet did not prove the split-SDXL latent_blend_4 inpaint schedule." }
            require(SDXL_SHA256.matches(
                nativeEffectiveJson.strictString("inpaintSourceNoiseSha256").lowercase()
            )) { "UNet inpaint source-noise identity is invalid." }
        }
    } else {
        require(nativeEffectiveJson.strictInt("inputImageExecutionCount") == 0 &&
            nativeEffectiveJson.strictValue("inputImagePath") == ""
        ) { "Text-to-image UNet reported an input-image execution." }
    }
}

internal fun validateSdxlVaeNativeEvidence(
    contract: SdxlImageExecutionContract,
    nativeResult: JSONObject
) {
    val ultraFix = contract.ultraFixRequestOrNull()
    require(nativeResult.strictString("phase") == SdxlImagePhase.VAE.wireName) {
        "VAE native phase identity mismatch."
    }
    require(nativeResult.strictBoolean("processExitRequired")) {
        "VAE must require disposable-process exit after PNG publication."
    }
    require(!nativeResult.strictBoolean("contextReleased")) {
        "VAE phase must retain its single context until the disposable process exits."
    }
    val location = nativeResult.strictEnum(
        "vaeScalingLocation",
        ImageVaeScalingLocation.entries
    )
    val factor = nativeResult.strictDouble("vaeScalingFactor")
    val effectiveHostScale = nativeResult.strictDouble("effectiveVaeHostScale")
    require(location == contract.expected.vaeScalingLocation) { "VAE scaling location mismatch." }
    require(abs(factor - contract.expected.vaeScalingFactor) <= 1e-6) { "VAE scaling factor mismatch." }
    val expectedHostScale = when (location) {
        ImageVaeScalingLocation.HOST_BEFORE_GRAPH -> 1.0 / factor
        ImageVaeScalingLocation.GRAPH_INTERNAL,
        ImageVaeScalingLocation.NONE,
        ImageVaeScalingLocation.RUNTIME_NATIVE -> 1.0
    }
    require(abs(effectiveHostScale - expectedHostScale) <= 1e-6) {
        "VAE effective host scaling mismatch."
    }
    require(location == ImageVaeScalingLocation.HOST_BEFORE_GRAPH) {
        "SDXL VAE scaling must be applied on host before every graph execution."
    }
    require(abs(factor - SDXL_QNN_VAE_SCALING_FACTOR) <= 1e-9) {
        "SDXL VAE scaling factor must be $SDXL_QNN_VAE_SCALING_FACTOR."
    }
    require(nativeResult.strictInt("vaeContextLoadCount") == 1) {
        "VAE must load exactly one graph context in its disposable worker."
    }
    require(nativeResult.strictString("vaeGraphName").isNotBlank()) {
        "VAE graph-name evidence is missing."
    }
    val vaeExecutionCount = nativeResult.strictInt("vaeExecutionCount")
    require(vaeExecutionCount > 0) { "VAE must execute at least once." }
    val vaeTileCount = nativeResult.strictInt("vaeTileCount")
    require(vaeTileCount == vaeExecutionCount) {
        "VAE tile count must equal its graph execution count."
    }
    val sourceShape = nativeResult.getJSONArray("vaeSourceLatentShape").toPositiveIntList()
    val inputShape = nativeResult.getJSONArray("vaeInputLatentShape").toPositiveIntList()
    val outputTileShape = nativeResult.getJSONArray("vaeOutputTileShape").toPositiveIntList()
    val finalOutputShape = nativeResult.getJSONArray("vaeFinalOutputShape").toPositiveIntList()
    require(sourceShape == contract.expectedLatentShape()) {
        "VAE source latent shape does not match the committed UNet artifact."
    }
    require(inputShape.size == 4 && inputShape[0] == 1 && inputShape[1] == 4) {
        "VAE input latent shape must be canonical NCHW with four channels."
    }
    require(outputTileShape.size == 4 && outputTileShape[0] == 1 && outputTileShape[1] == 3) {
        "VAE output tile shape must be canonical NCHW RGB."
    }
    require(finalOutputShape == listOf(1, 3, contract.height, contract.width)) {
        "VAE final output shape does not match the resolved SDXL size."
    }
    require(inputShape[2] <= sourceShape[2] && inputShape[3] <= sourceShape[3]) {
        "VAE latent tile exceeds the denoised latent."
    }
    require(outputTileShape[2] % inputShape[2] == 0 && outputTileShape[3] % inputShape[3] == 0) {
        "VAE tile shapes do not define an integer spatial decode scale."
    }
    val heightScale = outputTileShape[2] / inputShape[2]
    val widthScale = outputTileShape[3] / inputShape[3]
    val decodeScale = nativeResult.strictInt("vaeDecodeSpatialScale")
    require(heightScale == widthScale && decodeScale == widthScale && decodeScale == 8) {
        "VAE spatial decode scale must be exactly 8 on both axes."
    }
    val expectedTileCount = if (ultraFix == null) {
        expectedSdxlVaeTileCount(
            sourceHeight = sourceShape[2],
            sourceWidth = sourceShape[3],
            tileHeight = inputShape[2],
            tileWidth = inputShape[3]
        )
    } else {
        nativeResult.strictInt("ultraFixTileCount").also { tileCount ->
            require(tileCount > 0 &&
                inputShape == listOf(1, 4, 128, 128) &&
                outputTileShape == listOf(1, 3, 1024, 1024) &&
                nativeResult.strictInt("ultraFixDecoderGraphExecutionCount") == tileCount &&
                nativeResult.strictInt("ultraFixDecoderTileSuccessCount") == tileCount &&
                nativeResult.strictString("ultraFixTilePlanSha256").lowercase()
                    .matches(SDXL_SHA256) &&
                nativeResult.strictBoolean("ultraFixOutputAtomicCommit") &&
                nativeResult.strictString("ultraFixOutputSha256").lowercase() ==
                    nativeResult.strictString("outputSha256").lowercase() &&
                nativeResult.strictLong("ultraFixOutputBytes") ==
                    nativeResult.strictLong("outputBytes") &&
                (!contract.paramsObject().has("ultraFixTilePlanSha256") ||
                    nativeResult.strictString("ultraFixTilePlanSha256").lowercase() ==
                        contract.paramsObject().strictString("ultraFixTilePlanSha256").lowercase())
            ) { "Split-SDXL UltraFix tiled VAE evidence is incomplete." }
        }
    }
    require(vaeTileCount == expectedTileCount) {
        "VAE tile count does not match the resolved source and graph input shapes."
    }
    require(nativeResult.strictBoolean("vaeTiled") == (expectedTileCount > 1)) {
        "VAE tiled evidence does not match its decode plan."
    }
    require(nativeResult.strictInt("width") == contract.width) { "VAE output width mismatch." }
    require(nativeResult.strictInt("height") == contract.height) { "VAE output height mismatch." }
    val pixelRange = nativeResult.strictEnum("pixelRange", ImagePixelRange.entries)
    require(pixelRange == contract.pixelRange) { "VAE output pixel range mismatch." }
    require(pixelRange != ImagePixelRange.RUNTIME_NATIVE) {
        "VAE output pixel range must be explicit."
    }
    require(
        nativeResult.strictString("pixelRangeConversion") ==
            ImageExecutionProfileNativeContract.qnnPixelRangeConversionName(pixelRange)
    ) { "VAE pixel-range conversion evidence mismatch." }
    val valueCount = nativeResult.strictLong("pixelRangeValueCount")
    val clampedValueCount = nativeResult.strictLong("pixelRangeClampedValueCount")
    val expectedValueCount = Math.multiplyExact(
        Math.multiplyExact(contract.width.toLong(), contract.height.toLong()),
        3L
    )
    require(valueCount == expectedValueCount) { "VAE pixel-range value count mismatch." }
    require(clampedValueCount in 0L..valueCount) { "VAE pixel-range clamp count is invalid." }
    val observedMin = nativeResult.strictDouble("pixelRangeObservedMin")
    val observedMax = nativeResult.strictDouble("pixelRangeObservedMax")
    require(observedMin <= observedMax) { "VAE observed pixel range is invalid." }
    val outputSha256 = nativeResult.strictString("outputSha256").lowercase()
    require(SDXL_SHA256.matches(outputSha256)) {
        "VAE output SHA-256 evidence is invalid."
    }
}

private fun expectedSdxlVaeTileCount(
    sourceHeight: Int,
    sourceWidth: Int,
    tileHeight: Int,
    tileWidth: Int
): Int = Math.multiplyExact(
    expectedSdxlVaeAxisTileCount(sourceHeight, tileHeight),
    expectedSdxlVaeAxisTileCount(sourceWidth, tileWidth)
)

private fun expectedSdxlVaeAxisTileCount(source: Int, tile: Int): Int {
    require(source > 0 && tile > 0 && source >= tile) { "Invalid SDXL VAE tile axis." }
    if (source == tile) return 1
    val minimumOverlap = maxOf(1, tile / 4)
    val maximumStride = tile - minimumOverlap
    require(maximumStride > 0) { "SDXL VAE tile stride is invalid." }
    val remaining = source - tile
    val intervalCount = (remaining + maximumStride - 1) / maximumStride
    return intervalCount + 1
}

internal fun validateSdxlEncoderNativeEvidence(
    contract: SdxlImageExecutionContract,
    nativeResult: JSONObject
) {
    val params = contract.paramsObject()
    val ultraFix = contract.ultraFixRequestOrNull()
    val expectedContextSha256 = params.strictString("vaeEncoderContextSha256").lowercase()
    require(SDXL_SHA256.matches(expectedContextSha256) &&
        nativeResult.strictString("encoderContextSha256").lowercase() == expectedContextSha256
    ) { "VAE encoder context digest differs from the resolved installed graph." }
    require(nativeResult.strictString("phase") == SdxlImagePhase.ENCODER.wireName) {
        "VAE encoder native phase identity mismatch."
    }
    val taskMode = LocalImageTaskMode.fromWireName(params.strictString("taskMode"))
    require(taskMode in setOf(LocalImageTaskMode.IMG2IMG, LocalImageTaskMode.INPAINT) &&
        nativeResult.strictString("taskMode") == taskMode.wireName
    ) {
        "VAE encoder must execute the resolved img2img or inpaint request."
    }
    require(nativeResult.strictBoolean("processExitRequired") &&
        !nativeResult.strictBoolean("contextReleased")
    ) { "VAE encoder must retain its context until the disposable process exits." }
    val encoderExecutionCount = nativeResult.strictInt("encoderExecutionCount")
    require(nativeResult.strictInt("encoderContextLoadCount") == 1 &&
        encoderExecutionCount == (ultraFix?.let {
            nativeResult.strictInt("ultraFixTileCount")
        } ?: 1)
    ) { "VAE encoder context or physical graph execution count is invalid." }
    require(nativeResult.strictString("encoderGraphName") == "model") {
        "VAE encoder graph name must match the inspected archive contract."
    }
    require(nativeResult.strictString("encoderInputName") == "input" &&
        nativeResult.strictString("encoderMeanOutputName") == "mean" &&
        nativeResult.strictString("encoderStdOutputName") == "std"
    ) { "VAE encoder tensor names differ from the inspected archive contract." }
    require(nativeResult.strictString("encoderInputDtype") == "float32" &&
        nativeResult.strictString("encoderMeanDtype") == "float32" &&
        nativeResult.strictString("encoderStdDtype") == "float32"
    ) { "VAE encoder tensors must use float32." }
    require(nativeResult.getJSONArray("encoderInputShape").toPositiveIntList() == listOf(1, 3, 1024, 1024)) {
        "VAE encoder input shape must be [1,3,1024,1024]."
    }
    val expectedLatentShape = contract.expectedLatentShape()
    val graphLatentShape = listOf(1, 4, 128, 128)
    require(nativeResult.getJSONArray("encoderMeanShape").toPositiveIntList() == graphLatentShape &&
        nativeResult.getJSONArray("encoderStdShape").toPositiveIntList() == graphLatentShape &&
        nativeResult.getJSONArray("latentShape").toPositiveIntList() == expectedLatentShape
    ) { "VAE encoder graph tiles or published full latent do not match split-SDXL topology." }
    val latentElements = expectedLatentShape.fold(1L) { total, value ->
        Math.multiplyExact(total, value.toLong())
    }
    require(nativeResult.strictLong("posteriorSampleCount") == latentElements &&
        nativeResult.strictLong("latentElements") == latentElements &&
        nativeResult.strictLong("latentBytes") == Math.multiplyExact(latentElements, 4L)
    ) { "VAE encoder posterior or latent byte evidence is invalid." }
    require(nativeResult.strictString("posteriorSampling") == "mean_plus_std_times_normal_mt19937_domain_v1") {
        "VAE encoder posterior sampling contract is missing."
    }
    require(abs(nativeResult.strictDouble("encoderLatentScalingFactor") - SDXL_QNN_VAE_SCALING_FACTOR) <= 1e-9) {
        "VAE encoder latent scaling factor must be $SDXL_QNN_VAE_SCALING_FACTOR."
    }
    require(nativeResult.strictString("inputImageSha256").lowercase() ==
        params.strictString("inputImageSha256").lowercase()
    ) { "VAE encoder source-image digest differs from the prepared input." }
    require(!nativeResult.strictBoolean("inputImageSourceReadByNative") &&
        nativeResult.strictString("inputImageSourceValidation") == "android_preprocess_provenance"
    ) { "VAE encoder must consume only the committed tensor, not reopen the worker source image." }
    require(nativeResult.strictString("inputImageTensorSha256").lowercase() ==
        params.strictString("inputImageTensorSha256").lowercase()
    ) { "VAE encoder tensor digest differs from the Android preprocessing artifact." }
    require(nativeResult.strictString("inputImagePreprocess") == SDXL_INPUT_TENSOR_PREPROCESS &&
        nativeResult.strictString("inputImageTensorDtype") == SDXL_INPUT_TENSOR_DTYPE &&
        nativeResult.strictString("inputImageTensorLayout") == SDXL_INPUT_TENSOR_LAYOUT &&
        nativeResult.strictString("inputImageTensorRange") == SDXL_INPUT_TENSOR_RANGE
    ) { "VAE encoder input preprocessing contract is incomplete." }
    val expectedInputBytes = Math.multiplyExact(
        Math.multiplyExact(contract.width.toLong(), contract.height.toLong()),
        3L * Float.SIZE_BYTES
    )
    require(nativeResult.strictLong("inputImageTensorBytes") == expectedInputBytes &&
        nativeResult.getJSONArray("inputImageTensorShape").toPositiveIntList() ==
            listOf(1, 3, contract.height, contract.width)
    ) {
        "VAE encoder input tensor byte size is invalid."
    }
    ultraFix?.let { request ->
        val tileCount = nativeResult.strictInt("ultraFixTileCount")
        require(tileCount > 0 && encoderExecutionCount == tileCount &&
            nativeResult.strictInt("ultraFixEncoderGraphExecutionCount") == tileCount &&
            nativeResult.strictInt("ultraFixEncoderTileSuccessCount") == tileCount &&
            nativeResult.strictInt("ultraFixTileSize") == request.tileSize &&
            abs(nativeResult.strictDouble("ultraFixOverlap") - request.overlap) <= 1e-12 &&
            nativeResult.strictString("ultraFixTilePlanSha256").lowercase()
                .matches(SDXL_SHA256) &&
            nativeResult.strictString("ultraFixEncoderInputProofSha256").lowercase()
                .matches(SDXL_SHA256) &&
            nativeResult.strictString("ultraFixEncoderMeanProofSha256").lowercase()
                .matches(SDXL_SHA256) &&
            nativeResult.strictString("ultraFixEncoderStdProofSha256").lowercase()
                .matches(SDXL_SHA256)
        ) { "Split-SDXL UltraFix tiled encoder evidence is incomplete." }
    }
}

internal fun validateSdxlNativeTransport(
    phase: SdxlImagePhase,
    expectedRuntimeProfile: SdxlQnnRuntimeProfile,
    nativeResult: JSONObject
): Int {
    val selectedHtpArch = nativeResult.optInt("htpArchVersion")
    require(selectedHtpArch > 0) {
        "SDXL ${phase.wireName} native runtime did not report a physical HTP transport."
    }
    require(selectedHtpArch == expectedRuntimeProfile.htpArchVersion) {
        "SDXL ${phase.wireName} selected HTP V$selectedHtpArch but this phase requires " +
            "V${expectedRuntimeProfile.htpArchVersion}."
    }
    val evidence = nativeResult.optJSONObject("runtimeEvidence")
        ?: error("SDXL ${phase.wireName} native result is missing exact runtime evidence.")
    require(evidence.strictBoolean("exactRoleBinding")) {
        "SDXL ${phase.wireName} native runtime did not preserve explicit host/DSP roles."
    }
    val actualHost = File(evidence.strictString("hostRuntimeDirectory")).canonicalFile
    val actualDsp = File(evidence.strictString("dspRuntimeDirectory")).canonicalFile
    require(actualHost == File(expectedRuntimeProfile.hostDirectory).canonicalFile &&
        actualDsp == File(expectedRuntimeProfile.dspDirectory).canonicalFile &&
        evidence.strictInt("htpArchVersion") == expectedRuntimeProfile.htpArchVersion
    ) { "SDXL ${phase.wireName} native runtime profile differs from the requested exact tuple." }
    return selectedHtpArch
}
