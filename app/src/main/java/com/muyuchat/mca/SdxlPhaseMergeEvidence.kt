package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class SdxlPhaseMergeEvidence(
    val sdxlPhaseProof: JSONObject,
    val ultraFixEvidence: JSONObject?
)

internal fun buildSdxlPhaseMergeEvidence(
    contract: SdxlImageExecutionContract,
    unetNative: JSONObject,
    vaeNative: JSONObject,
    encoderNative: JSONObject?,
    encoderMetadata: SdxlEncoderLatentMetadata?,
    encoderRuntimeProfile: SdxlQnnRuntimeProfile?,
    unetProof: SdxlNativePhaseProof,
    vaeProof: SdxlNativePhaseProof,
    encoderProof: SdxlNativePhaseProof?,
    mergedNativeDetailStageMask: ULong,
    unetTransportHtpArch: Int,
    vaeTransportHtpArch: Int,
    unetRuntimeProfile: SdxlQnnRuntimeProfile,
    vaeRuntimeProfile: SdxlQnnRuntimeProfile,
    actualConditioningArtifactSha256: String,
    outputSha256: String,
    outputFile: File,
    taskMode: LocalImageTaskMode,
    ultraFix: LocalImageUltraFixOptions?
): SdxlPhaseMergeEvidence {
    val sdxlPhaseProof = JSONObject()
        .put("conditioningArtifactSha256", actualConditioningArtifactSha256)
        .put("unetTransportHtpArch", unetTransportHtpArch)
        .put("vaeTransportHtpArch", vaeTransportHtpArch)
        .put("unetRuntimeProfileSha256", unetRuntimeProfile.identitySha256())
        .put("vaeRuntimeProfileSha256", vaeRuntimeProfile.identitySha256())
        .put("unetContextLoadCount", unetNative.getInt("unetContextLoadCount"))
        .put("unetSamplingLoopCount", unetNative.getInt("unetSamplingLoopCount"))
        .put("unetSamplingStepCount", unetNative.getInt("unetSamplingStepCount"))
        .put("unetGraphExecutionCount", unetNative.getInt("unetGraphExecutionCount"))
        .put(
            "unetContextReusedAcrossSteps",
            unetNative.getBoolean("unetContextReusedAcrossSteps")
        )
        .put("unetGraphName", unetNative.getString("unetGraphName"))
        .put("vaeContextLoadCount", vaeNative.getInt("vaeContextLoadCount"))
        .put("vaeExecutionCount", vaeNative.getInt("vaeExecutionCount"))
        .put("vaeTileCount", vaeNative.getInt("vaeTileCount"))
        .put("vaeTiled", vaeNative.getBoolean("vaeTiled"))
        .put("vaeGraphName", vaeNative.getString("vaeGraphName"))
        .put("vaeSourceLatentShape", vaeNative.getJSONArray("vaeSourceLatentShape"))
        .put("vaeInputLatentShape", vaeNative.getJSONArray("vaeInputLatentShape"))
        .put("vaeOutputTileShape", vaeNative.getJSONArray("vaeOutputTileShape"))
        .put("vaeFinalOutputShape", vaeNative.getJSONArray("vaeFinalOutputShape"))
        .put("vaeDecodeSpatialScale", vaeNative.getInt("vaeDecodeSpatialScale"))
        .put("vaeScalingLocation", vaeNative.getString("vaeScalingLocation"))
        .put("vaeScalingFactor", vaeNative.getDouble("vaeScalingFactor"))
        .put("effectiveVaeHostScale", vaeNative.getDouble("effectiveVaeHostScale"))
        .put("pixelRange", vaeNative.getString("pixelRange"))
        .put("pixelRangeConversion", vaeNative.getString("pixelRangeConversion"))
        .put("pixelRangeValueCount", vaeNative.getLong("pixelRangeValueCount"))
        .put("pixelRangeClampedValueCount", vaeNative.getLong("pixelRangeClampedValueCount"))
        .put("pixelRangeObservedMin", vaeNative.getDouble("pixelRangeObservedMin"))
        .put("pixelRangeObservedMax", vaeNative.getDouble("pixelRangeObservedMax"))
        .put("outputSha256", outputSha256)
        .put(
            "unetNativeDetailStageMaskHex",
            unetProof.nativeDetailStageMask.toFixedUInt64Hex()
        )
        .put(
            "vaeNativeDetailStageMaskHex",
            vaeProof.nativeDetailStageMask.toFixedUInt64Hex()
        )
        .put(
            QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
            mergedNativeDetailStageMask.toFixedUInt64Hex()
        )
    if (encoderNative != null && encoderMetadata != null && encoderProof != null) {
        sdxlPhaseProof
            .put("encoderContextLoadCount", encoderNative.getInt("encoderContextLoadCount"))
            .put("encoderExecutionCount", encoderNative.getInt("encoderExecutionCount"))
            .put("encoderGraphName", encoderNative.getString("encoderGraphName"))
            .put("encoderInputShape", encoderNative.getJSONArray("encoderInputShape"))
            .put("encoderMeanShape", encoderNative.getJSONArray("encoderMeanShape"))
            .put("encoderStdShape", encoderNative.getJSONArray("encoderStdShape"))
            .put("encoderContextSha256", encoderMetadata.encoderContextSha256)
            .put("encoderLatentSha256", encoderMetadata.sha256)
            .put("encoderTransportHtpArch", encoderMetadata.htpArchVersion)
            .put("encoderRuntimeProfileSha256", requireNotNull(encoderRuntimeProfile).identitySha256())
            .put("inputImageSha256", encoderMetadata.inputImageSha256)
            .put("inputImageTensorSha256", encoderMetadata.inputImageTensorSha256)
            .put(
                "encoderNativeDetailStageMaskHex",
                encoderProof.nativeDetailStageMask.toFixedUInt64Hex()
            )
    }
    if (taskMode == LocalImageTaskMode.INPAINT) {
        val unetEffective = unetNative.getJSONObject("nativeEffective")
        sdxlPhaseProof
            .put("inpaintTopology", unetEffective.getString("inpaintTopology"))
            .put("maskImageTensorSha256", unetEffective.getString("maskImageTensorSha256"))
            .put("maskImageFullTensorSha256", vaeNative.getString("maskImageFullTensorSha256"))
            .put("inpaintPreserveStepCount", unetEffective.getInt("inpaintPreserveStepCount"))
            .put("inpaintLatentBlendCount", unetEffective.getInt("inpaintLatentBlendCount"))
            .put("inpaintSourceNoiseSha256", unetEffective.getString("inpaintSourceNoiseSha256"))
            .put("inpaintSourceNoiseUseCount", unetEffective.getInt("inpaintSourceNoiseUseCount"))
            .put("inpaintPreservedLatentChecksum", unetEffective.getString("inpaintPreservedLatentChecksum"))
            .put("inpaintFinalMode", vaeNative.getString("inpaintFinalMode"))
            .put("inpaintPixelBlendLevels", vaeNative.getInt("inpaintPixelBlendLevels"))
            .put("inpaintPixelBlendChecksum", vaeNative.getString("inpaintPixelBlendChecksum"))
            .put("inpaintPixelBlendApplied", vaeNative.getBoolean("inpaintPixelBlendApplied"))
    }
    if (ultraFix != null) {
        sdxlPhaseProof
            .put("ultraFixTilePlanSha256", encoderNative!!.getString("ultraFixTilePlanSha256"))
            .put("ultraFixTileCount", encoderNative.getInt("ultraFixTileCount"))
            .put(
                "ultraFixEncoderGraphExecutionCount",
                encoderNative.getInt("ultraFixEncoderGraphExecutionCount")
            )
            .put(
                "ultraFixInversionGraphExecutionCount",
                unetNative.getInt("ultraFixInversionGraphExecutionCount")
            )
            .put(
                "ultraFixRefinementPositiveGraphExecutionCount",
                unetNative.getInt("ultraFixRefinementPositiveGraphExecutionCount")
            )
            .put(
                "ultraFixRefinementNegativeGraphExecutionCount",
                unetNative.getInt("ultraFixRefinementNegativeGraphExecutionCount")
            )
            .put(
                "ultraFixDecoderGraphExecutionCount",
                vaeNative.getInt("ultraFixDecoderGraphExecutionCount")
            )
            .put("ultraFixOutputSha256", vaeNative.getString("ultraFixOutputSha256"))
    }
    val ultraFixEvidence = ultraFix?.let { request ->
        val encoder = requireNotNull(encoderNative)
        val tileCount = encoder.getInt("ultraFixTileCount")
        val tilePlanSha256 = encoder.getString("ultraFixTilePlanSha256").lowercase()
        val inversionGraphs = unetNative.getInt("ultraFixInversionGraphExecutionCount")
        val refinementPositive =
            unetNative.getInt("ultraFixRefinementPositiveGraphExecutionCount")
        val refinementNegative =
            unetNative.getInt("ultraFixRefinementNegativeGraphExecutionCount")
        val physicalUnet = unetNative.getInt("ultraFixPhysicalUnetGraphExecutionCount")
        val params = contract.paramsObject()
        val sourceWidth = params.getInt("inputImageOrientedWidth")
        val sourceHeight = params.getInt("inputImageOrientedHeight")
        require(sourceWidth > 0 && sourceHeight > 0 &&
            encoder.getInt("ultraFixSourceWidth") == sourceWidth &&
            encoder.getInt("ultraFixSourceHeight") == sourceHeight &&
            encoder.getInt("ultraFixSourceResizedWidth") >= request.targetWidth &&
            encoder.getInt("ultraFixSourceResizedHeight") >= request.targetHeight &&
            encoder.getInt("ultraFixSourceCropLeft") ==
                (encoder.getInt("ultraFixSourceResizedWidth") - request.targetWidth) / 2 &&
            encoder.getInt("ultraFixSourceCropTop") ==
                (encoder.getInt("ultraFixSourceResizedHeight") - request.targetHeight) / 2
        ) { "Split-SDXL UltraFix center-cover geometry evidence is invalid." }
        fun stage(
            invocations: Int,
            tileInvocations: Int,
            steps: Int
        ): JSONObject = JSONObject()
            .put("invocationCount", invocations)
            .put("successCount", invocations)
            .put("tileInvocationCount", tileInvocations)
            .put("tileSuccessCount", tileInvocations)
            .put("stepCount", steps)
        JSONObject()
            .put("version", 2)
            .put("generationCompleted", true)
            .put("cancelled", false)
            .put("previewPublished", false)
            .put("sourceWidth", sourceWidth)
            .put("sourceHeight", sourceHeight)
            .put("targetWidth", request.targetWidth)
            .put("targetHeight", request.targetHeight)
            .put("sourceFit", "cover_center")
            .put("sourceResizedWidth", encoder.getInt("ultraFixSourceResizedWidth"))
            .put("sourceResizedHeight", encoder.getInt("ultraFixSourceResizedHeight"))
            .put("sourceCropLeft", encoder.getInt("ultraFixSourceCropLeft"))
            .put("sourceCropTop", encoder.getInt("ultraFixSourceCropTop"))
            .put("tileSize", request.tileSize)
            .put("overlap", request.overlap)
            .put("inversionSteps", request.inversionSteps)
            .put("refinementSteps", request.refinementSteps)
            .put("denoiseStepCount", request.inversionSteps)
            .put("tileCount", tileCount)
            .put("tilePlanSha256", tilePlanSha256)
            .put("sampleMethod", unetNative.getString("ultraFixSampleMethod"))
            .put("nativeScheduler", unetNative.getString("ultraFixNativeScheduler"))
            .put("vaeEncode", stage(1, tileCount, 1))
            .put(
                "ddimInversion",
                stage(request.inversionSteps, inversionGraphs, request.inversionSteps)
            )
            .put(
                "tiledUnetRefinement",
                stage(
                    request.inversionSteps,
                    refinementPositive + refinementNegative,
                    request.inversionSteps
                )
            )
            .put("tiledVaeDecode", stage(1, tileCount, 1))
            .put("encoderGraphExecutionCount", encoder.getInt("ultraFixEncoderGraphExecutionCount"))
            .put("inversionPositiveGraphExecutionCount", inversionGraphs)
            .put("refinementPositiveGraphExecutionCount", refinementPositive)
            .put("refinementNegativeGraphExecutionCount", refinementNegative)
            .put("decoderGraphExecutionCount", vaeNative.getInt("ultraFixDecoderGraphExecutionCount"))
            .put("physicalDiffusionModelComputeCount", physicalUnet)
            .put(
                "qualityStepEvaluationCount",
                unetNative.getInt("ultraFixQualityStepEvaluationCount")
            )
            .put(
                "noiseInjectionStepCount",
                unetNative.getInt("ultraFixNoiseInjectionStepCount")
            )
            .put(
                "noiseInjectionSeedFingerprint",
                unetNative.getString("ultraFixNoiseInjectionSeedFingerprint")
            )
            .put(
                "noiseInjectionChecksum",
                unetNative.getString("ultraFixNoiseInjectionChecksum")
            )
            .put(
                "structureGuidanceStepCount",
                unetNative.getInt("ultraFixStructureGuidanceStepCount")
            )
            .put(
                "structureGuidanceChecksum",
                unetNative.getString("ultraFixStructureGuidanceChecksum")
            )
            .put(
                "trajectoryNoiseChecksum",
                unetNative.getString("ultraFixTrajectoryNoiseChecksum")
            )
            .put("outputSha256", outputSha256)
            .put("outputBytes", outputFile.length())
            .put("outputAtomicCommit", true)
    }
    return SdxlPhaseMergeEvidence(sdxlPhaseProof, ultraFixEvidence)
}

