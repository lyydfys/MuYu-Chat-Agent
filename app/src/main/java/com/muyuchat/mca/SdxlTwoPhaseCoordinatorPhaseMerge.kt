package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject


internal fun mergeSdxlPhaseNativeResults(
    contract: SdxlImageExecutionContract,
    unetResult: SdxlImagePhaseResult,
    unetNative: JSONObject,
    vaeResult: SdxlImagePhaseResult,
    vaeNative: JSONObject,
    metadata: SdxlLatentMetadata,
    unetRuntimeProfile: SdxlQnnRuntimeProfile,
    vaeRuntimeProfile: SdxlQnnRuntimeProfile,
    unetTransportHtpArch: Int,
    vaeTransportHtpArch: Int,
    outputFile: File,
    stageTrace: List<String>,
    encoderResult: SdxlImagePhaseResult? = null,
    encoderNative: JSONObject? = null,
    encoderMetadata: SdxlEncoderLatentMetadata? = null,
    encoderRuntimeProfile: SdxlQnnRuntimeProfile? = null,
    preparedInpaint: QnnPreparedInpaintInput? = null
): JSONObject {
    val mergeInputs = requireSdxlPhaseMergeInputs(
        contract = contract,
        unetResult = unetResult,
        unetNative = unetNative,
        vaeResult = vaeResult,
        vaeNative = vaeNative,
        encoderResult = encoderResult,
        encoderNative = encoderNative,
        encoderMetadata = encoderMetadata,
        encoderRuntimeProfile = encoderRuntimeProfile,
        preparedInpaint = preparedInpaint
    )
    requireSdxlPhaseMergePhaseEvidence(
        contract = contract,
        unetResult = unetResult,
        unetNative = unetNative,
        vaeResult = vaeResult,
        vaeNative = vaeNative,
        metadata = metadata,
        unetRuntimeProfile = unetRuntimeProfile,
        vaeRuntimeProfile = vaeRuntimeProfile,
        unetTransportHtpArch = unetTransportHtpArch,
        vaeTransportHtpArch = vaeTransportHtpArch,
        encoderResult = encoderResult,
        encoderNative = encoderNative,
        encoderMetadata = encoderMetadata,
        encoderRuntimeProfile = encoderRuntimeProfile,
        ultraFix = mergeInputs.ultraFix
    )
    requireSdxlPhaseMergeArtifacts(
        taskMode = mergeInputs.taskMode,
        preparedInpaint = preparedInpaint,
        contract = contract,
        unetNative = unetNative,
        vaeNative = vaeNative,
        encoderNative = encoderNative,
        metadata = metadata,
        unetResult = unetResult,
        vaeResult = vaeResult,
        outputFile = outputFile
    )
    val mergeProofs = collectSdxlPhaseMergeProofs(
        unetNative = unetNative,
        vaeNative = vaeNative,
        unetResult = unetResult,
        vaeResult = vaeResult,
        metadata = metadata,
        outputFile = outputFile,
        stageTrace = stageTrace,
        encoderResult = encoderResult,
        encoderNative = encoderNative
    )
    val mergeEvidence = buildSdxlPhaseMergeEvidence(
        contract = contract,
        unetNative = unetNative,
        vaeNative = vaeNative,
        encoderNative = encoderNative,
        encoderMetadata = encoderMetadata,
        encoderRuntimeProfile = encoderRuntimeProfile,
        unetProof = mergeProofs.unetProof,
        vaeProof = mergeProofs.vaeProof,
        encoderProof = mergeProofs.encoderProof,
        mergedNativeDetailStageMask = mergeProofs.mergedNativeDetailStageMask,
        unetTransportHtpArch = unetTransportHtpArch,
        vaeTransportHtpArch = vaeTransportHtpArch,
        unetRuntimeProfile = unetRuntimeProfile,
        vaeRuntimeProfile = vaeRuntimeProfile,
        actualConditioningArtifactSha256 = mergeProofs.actualConditioningArtifactSha256,
        outputSha256 = mergeProofs.outputSha256,
        outputFile = outputFile,
        taskMode = mergeInputs.taskMode,
        ultraFix = mergeInputs.ultraFix
    )
    return assembleSdxlPhaseMergeResult(
        hasEncoder = mergeInputs.hasEncoder,
        ultraFix = mergeInputs.ultraFix,
        unetNativeEffectiveJson = mergeProofs.unetNativeEffectiveJson,
        vaeNative = vaeNative,
        actualConditioningArtifactSha256 = mergeProofs.actualConditioningArtifactSha256,
        unetTransportHtpArch = unetTransportHtpArch,
        vaeTransportHtpArch = vaeTransportHtpArch,
        mergedNativeDetailStageMask = mergeProofs.mergedNativeDetailStageMask,
        sdxlPhaseProof = mergeEvidence.sdxlPhaseProof,
        encoderMetadata = encoderMetadata,
        encoderNative = encoderNative,
        taskMode = mergeInputs.taskMode,
        ultraFixEvidence = mergeEvidence.ultraFixEvidence,
        outputSha256 = mergeProofs.outputSha256,
        outputFile = outputFile,
        unetProof = mergeProofs.unetProof,
        vaeProof = mergeProofs.vaeProof,
        encoderProof = mergeProofs.encoderProof,
        unetResult = unetResult,
        vaeResult = vaeResult,
        metadata = metadata,
        encoderResult = encoderResult,
        disabledPreviewAudit = mergeInputs.disabledPreviewAudit,
        mimeType = mergeProofs.mimeType,
        nativeStartedAt = mergeProofs.nativeStartedAt,
        stageTrace = stageTrace,
        unetNative = unetNative
    )
}
