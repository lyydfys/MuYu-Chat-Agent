package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal fun assembleSdxlPhaseMergeResult(
    hasEncoder: Boolean,
    ultraFix: LocalImageUltraFixOptions?,
    unetNativeEffectiveJson: JSONObject,
    vaeNative: JSONObject,
    actualConditioningArtifactSha256: String,
    unetTransportHtpArch: Int,
    vaeTransportHtpArch: Int,
    mergedNativeDetailStageMask: ULong,
    sdxlPhaseProof: JSONObject,
    encoderMetadata: SdxlEncoderLatentMetadata?,
    encoderNative: JSONObject?,
    taskMode: LocalImageTaskMode,
    ultraFixEvidence: JSONObject?,
    outputSha256: String,
    outputFile: File,
    unetProof: SdxlNativePhaseProof,
    vaeProof: SdxlNativePhaseProof,
    encoderProof: SdxlNativePhaseProof?,
    unetResult: SdxlImagePhaseResult,
    vaeResult: SdxlImagePhaseResult,
    metadata: SdxlLatentMetadata,
    encoderResult: SdxlImagePhaseResult?,
    disabledPreviewAudit: SdxlProjectionPreviewAudit,
    mimeType: String,
    nativeStartedAt: Any?,
    stageTrace: List<String>,
    unetNative: JSONObject
): JSONObject {
    val runtimeSessionMode = if (ultraFix != null) {
        SDXL_ISOLATED_ULTRAFIX_MODE
    } else if (hasEncoder) {
        SDXL_ISOLATED_ENCODER_UNET_VAE_MODE
    } else {
        SDXL_ISOLATED_UNET_VAE_MODE
    }
    val nativeEffectiveJson = JSONObject(unetNativeEffectiveJson.toString())
        .put("vaeScalingLocation", vaeNative.getString("vaeScalingLocation"))
        .put("vaeScalingFactor", vaeNative.getDouble("vaeScalingFactor"))
        .put("pixelRange", vaeNative.getString("pixelRange"))
        .put("conditioningArtifactSha256", actualConditioningArtifactSha256)
        .put("runtimeSessionMode", runtimeSessionMode)
        // transportHtpArch remains the compatibility alias for the primary UNet
        // sampling phase. Explicit per-phase fields carry the truthful topology.
        .put("transportHtpArch", unetTransportHtpArch)
        .put("unetTransportHtpArch", unetTransportHtpArch)
        .put("vaeTransportHtpArch", vaeTransportHtpArch)
        .put(
            QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
            mergedNativeDetailStageMask.toFixedUInt64Hex()
        )
        .put("sdxlPhaseProof", sdxlPhaseProof)
    if (encoderMetadata != null) {
        nativeEffectiveJson
            .put("encoderContextSha256", encoderMetadata.encoderContextSha256)
            .put("encoderTransportHtpArch", encoderMetadata.htpArchVersion)
    }
    if (taskMode == LocalImageTaskMode.INPAINT) {
        listOf(
            "inputImageTensorPath",
            "inputImageTensorSha256",
            "inputImageTensorBytes",
            "maskImageFullTensorPath",
            "maskImageFullTensorSha256",
            "maskImageFullTensorBytes",
            "maskImageFullTensorShape",
            "maskImageFullTensorDtype",
            "maskImageFullTensorLayout",
            "maskImageFullTensorRange",
            "maskImageFullTensorPreprocess",
            "inpaintFinalMode",
            "inpaintPixelBlendLevels",
            "inpaintPixelBlendChecksum",
            "inpaintPixelBlendApplied",
        ).forEach { field -> nativeEffectiveJson.put(field, vaeNative.get(field)) }
        nativeEffectiveJson
            .put("inpaintMaskConsumed", true)
            .put("inpaintUnmaskedPreservationApplied", true)
            .put("encoderContextSha256", requireNotNull(encoderMetadata).encoderContextSha256)
            .put("encoderGraphName", requireNotNull(encoderNative).getString("encoderGraphName"))
            .put("encoderExecutionCount", 1)
            .put("encoderContextLoadCount", 1)
            .put("inpaintSourceEncoderExecutionCount", 1)
    }
    if (ultraFixEvidence != null && ultraFix != null) {
        val positiveCompute = Math.addExact(
            unetNative.getInt("ultraFixInversionGraphExecutionCount"),
            unetNative.getInt("ultraFixRefinementPositiveGraphExecutionCount")
        )
        val negativeCompute =
            unetNative.getInt("ultraFixRefinementNegativeGraphExecutionCount")
        val physicalCompute = Math.addExact(positiveCompute, negativeCompute)
        nativeEffectiveJson
            .put("ultraFix", JSONObject(ultraFixEvidence.toString()))
            .put("strengthMechanism", "ddim_inversion")
            .put("outputSha256", outputSha256)
            .put("outputSizeBytes", outputFile.length())
            .put("outputAtomicCommit", true)
            .put("positiveDiffusionModelComputeCount", positiveCompute)
            .put("negativeDiffusionModelComputeCount", negativeCompute)
            .put("auxiliaryDiffusionModelComputeCount", 0)
            .put("samplingPassCount", 1)
            .put("totalUnetExecutionCount", physicalCompute)
    }
    val finalResult = JSONObject(nativeEffectiveJson.toString())
    finalResult.put("nativeEffective", nativeEffectiveJson)
        .put("ok", true)
        .put("backend", "qnn_htp")
        .put("npuActive", true)
        .put("qnnGraphExecution", true)
        .put("nativeExecution", true)
        .put(
            "executionStage",
            if (ultraFix != null) {
                "sdxl_three_phase_ultrafix_passed"
            } else {
                when (taskMode) {
                    LocalImageTaskMode.INPAINT -> "sdxl_three_phase_inpaint_passed"
                    LocalImageTaskMode.IMG2IMG -> "sdxl_three_phase_img2img_passed"
                    else -> "sdxl_two_phase_passed"
                }
            }
        )
        .put("runtimeSessionMode", runtimeSessionMode)
        .put("transportHtpArch", unetTransportHtpArch)
        .put("unetTransportHtpArch", unetTransportHtpArch)
        .put("unetWorkerPid", unetResult.workerPid)
        .put("unetRuntimeProfile", unetResult.runtimeProfile)
        .put("unetGraph", unetNative.getString("unetGraphName"))
        .put("unetProcessDeathConfirmed", true)
        .put("unetNativeGenerationSequence", unetProof.nativeGenerationSequence)
        .put("unetNativeStageMask", unetProof.nativeStageMask)
        .put(
            "unetNativeDetailStageMaskHex",
            unetProof.nativeDetailStageMask.toFixedUInt64Hex()
        )
        .put("vaeWorkerPid", vaeResult.workerPid)
        .put("vaeRuntimeProfile", vaeResult.runtimeProfile)
        .put("vaeGraph", vaeNative.getString("vaeGraphName"))
        .put("vaeTransportHtpArch", vaeTransportHtpArch)
        .put("vaeProcessDeathConfirmed", true)
        .put("vaeNativeGenerationSequence", vaeProof.nativeGenerationSequence)
        .put("vaeNativeStageMask", vaeProof.nativeStageMask)
        .put(
            "vaeNativeDetailStageMaskHex",
            vaeProof.nativeDetailStageMask.toFixedUInt64Hex()
        )
        .put("nativeGenerationSequence", unetProof.nativeGenerationSequence)
        .put("nativeStartedAtMonotonicMs", (nativeStartedAt as Number).toLong())
        .put(
            "nativeStageMask",
            unetProof.nativeStageMask or vaeProof.nativeStageMask or
                (encoderProof?.nativeStageMask ?: 0L)
        )
        .put(
            QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
            mergedNativeDetailStageMask.toFixedUInt64Hex()
        )
        .put("vaeScalingLocation", vaeNative.getString("vaeScalingLocation"))
        .put("vaeScalingFactor", vaeNative.getDouble("vaeScalingFactor"))
        .put("pixelRange", vaeNative.getString("pixelRange"))
        .put("effectiveVaeHostScale", vaeNative.getDouble("effectiveVaeHostScale"))
        .put("vaeExecutionCount", vaeNative.getInt("vaeExecutionCount"))
        .put("finalVaeExecutionCount", 1)
        .put("finalVaeGraphExecutionCount", vaeNative.getInt("vaeExecutionCount"))
        .put("previewRequested", disabledPreviewAudit.requested)
        .put("previewMode", disabledPreviewAudit.mode)
        .put("previewInterval", disabledPreviewAudit.interval)
        .put("previewVaeExecutionAttemptCount", 0)
        .put("previewVaeExecutionCount", 0)
        .put("previewVaeExecutionMsTotal", 0)
        .put("previewPublicationCount", disabledPreviewAudit.publicationCount)
        .put("previewLastStep", disabledPreviewAudit.lastStep)
        .put("previewLastRevision", disabledPreviewAudit.lastRevision)
        .put("previewFailureCode", disabledPreviewAudit.failureCode)
        .put("projectionPreviewAttemptCount", disabledPreviewAudit.attemptCount)
        .put("projectionPreviewPublicationCount", disabledPreviewAudit.publicationCount)
        .put("projectionPreviewProjectionMsTotal", disabledPreviewAudit.projectionMsTotal)
        .put("projectionPreviewLastStep", disabledPreviewAudit.lastStep)
        .put("projectionPreviewLastRevision", disabledPreviewAudit.lastRevision)
        .put("projectionPreviewFailureCode", disabledPreviewAudit.failureCode)
        .put("previewDegraded", disabledPreviewAudit.degraded)
        .put("outputPath", outputFile.canonicalPath)
        .put("mimeType", mimeType)
        .put("outputBytes", outputFile.length())
        .put("outputSha256", outputSha256)
        .put("outputAtomicCommit", vaeNative.getBoolean("outputAtomicCommit"))
        .put("latentSha256", metadata.sha256)
        .put("stageTrace", JSONArray(stageTrace))

    if (ultraFixEvidence != null && ultraFix != null) {
        val positiveCompute = Math.addExact(
            unetNative.getInt("ultraFixInversionGraphExecutionCount"),
            unetNative.getInt("ultraFixRefinementPositiveGraphExecutionCount")
        )
        val negativeCompute =
            unetNative.getInt("ultraFixRefinementNegativeGraphExecutionCount")
        val physicalCompute = Math.addExact(positiveCompute, negativeCompute)
        finalResult
            .put("ultraFix", JSONObject(ultraFixEvidence.toString()))
            .put("strengthMechanism", "ddim_inversion")
            .put("sampleMethod", ultraFixEvidence.getString("sampleMethod"))
            .put("nativeScheduler", ultraFixEvidence.getString("nativeScheduler"))
            .put("actualDiffusionModelComputeCount", physicalCompute)
            .put("actualPositiveDiffusionModelComputeCount", positiveCompute)
            .put("actualNegativeDiffusionModelComputeCount", negativeCompute)
            .put("actualAuxiliaryDiffusionModelComputeCount", 0)
            .put("actualSamplingStepCount", ultraFix.inversionSteps)
            .put("actualSamplingPassCount", 1)
            .put("totalUnetExecutionCount", physicalCompute)
            .put("outputSizeBytes", outputFile.length())
            .put("outputAtomicCommit", true)
    }

    if (encoderResult != null && encoderNative != null && encoderMetadata != null && encoderProof != null) {
        finalResult
            .put("encoderWorkerPid", encoderResult.workerPid)
            .put("encoderRuntimeProfile", encoderResult.runtimeProfile)
            .put("encoderGraph", encoderNative.getString("encoderGraphName"))
            .put("encoderTransportHtpArch", encoderMetadata.htpArchVersion)
            .put("encoderProcessDeathConfirmed", true)
            .put("encoderNativeGenerationSequence", encoderProof.nativeGenerationSequence)
            .put("encoderNativeStageMask", encoderProof.nativeStageMask)
            .put(
                "encoderNativeDetailStageMaskHex",
                encoderProof.nativeDetailStageMask.toFixedUInt64Hex()
            )
            .put("encoderContextSha256", encoderMetadata.encoderContextSha256)
            .put("encoderLatentSha256", encoderMetadata.sha256)
        listOf(
            "encoderContextLoadCount",
            "encoderExecutionCount",
            "encoderGraphName",
            "encoderContextSha256",
            "encoderContextLoadMs",
            "encoderExecuteMs",
            "encoderInputName",
            "encoderMeanOutputName",
            "encoderStdOutputName",
            "encoderInputDtype",
            "encoderMeanDtype",
            "encoderStdDtype",
            "encoderInputShape",
            "encoderMeanShape",
            "encoderStdShape",
            "posteriorSampling",
            "posteriorSampleCount",
            "encoderLatentScalingFactor",
            "inputImagePath",
            "inputImageSha256",
            "inputImageSizeBytes",
            "inputImageSourceReadByNative",
            "inputImageSourceValidation",
            "inputImageTensorPath",
            "inputImageTensorSha256",
            "inputImageTensorBytes",
            "inputImageTensorShape",
            "inputImageTensorDtype",
            "inputImageTensorLayout",
            "inputImageTensorRange",
            "inputImagePreprocess"
        ).forEach { field ->
            if (encoderNative.has(field) && !encoderNative.isNull(field)) {
                finalResult.put(field, encoderNative.get(field))
            }
        }
    }

    listOf(
        "timesteps",
        "sigmas",
        "initNoiseSigma",
        "scaleModelInput",
        "unetContextLoadCount",
        "unetSamplingLoopCount",
        "unetSamplingStepCount",
        "unetGraphExecutionCount",
        "unetContextReusedAcrossSteps",
        "unetContextLoadMs",
        "unetExecuteMsTotal"
    ).forEach { field ->
        if (unetNative.has(field) && !unetNative.isNull(field)) {
            finalResult.put(field, unetNative.get(field))
        }
    }
    listOf(
        "vaeContextLoadMs",
        "vaeExecuteMs",
        "vaeTileCount",
        "vaeTiled",
        "vaeContextLoadCount",
        "vaeSourceLatentShape",
        "vaeInputLatentShape",
        "vaeOutputTileShape",
        "vaeFinalOutputShape",
        "vaeDecodeSpatialScale",
        "pixelChecksum",
        "pixelRangeConversion",
        "pixelRangeValueCount",
        "pixelRangeClampedValueCount",
        "pixelRangeObservedMin",
        "pixelRangeObservedMax"
    ).forEach { field ->
        if (vaeNative.has(field) && !vaeNative.isNull(field)) {
            finalResult.put(field, vaeNative.get(field))
        }
    }
    (vaeNative.optJSONObject("runtimeEvidence")
        ?: vaeNative.optJSONObject("runtime"))?.let { finalResult.put("runtimeEvidence", it) }
    validateSdxlFlatNativeEffective(finalResult)
    return finalResult
}
