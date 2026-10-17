package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class SdxlPhaseMergeProofs(
    val unetProof: SdxlNativePhaseProof,
    val vaeProof: SdxlNativePhaseProof,
    val encoderProof: SdxlNativePhaseProof?,
    val mergedNativeDetailStageMask: ULong,
    val nativeStartedAt: Any?,
    val outputSha256: String,
    val mimeType: String,
    val unetNativeEffectiveJson: JSONObject,
    val actualConditioningArtifactSha256: String
)

internal fun collectSdxlPhaseMergeProofs(
    unetNative: JSONObject,
    vaeNative: JSONObject,
    unetResult: SdxlImagePhaseResult,
    vaeResult: SdxlImagePhaseResult,
    metadata: SdxlLatentMetadata,
    outputFile: File,
    stageTrace: List<String>,
    encoderResult: SdxlImagePhaseResult?,
    encoderNative: JSONObject?
): SdxlPhaseMergeProofs {
    val unetProof = SdxlNativePhaseProof.fromNativeResult(unetNative, SdxlImagePhase.UNET)
    val vaeProof = SdxlNativePhaseProof.fromNativeResult(vaeNative, SdxlImagePhase.VAE)
    val encoderProof = encoderNative?.let {
        SdxlNativePhaseProof.fromNativeResult(it, SdxlImagePhase.ENCODER)
    }
    val mergedNativeDetailStageMask =
        unetProof.nativeDetailStageMask or vaeProof.nativeDetailStageMask or
            (encoderProof?.nativeDetailStageMask ?: 0uL)
    val phaseSequences = buildList {
        encoderProof?.nativeGenerationSequence?.let(::add)
        add(unetProof.nativeGenerationSequence)
        add(vaeProof.nativeGenerationSequence)
    }
    require(phaseSequences.distinct().size == phaseSequences.size) {
        "SDXL disposable phase instances reused one native generation identity."
    }
    val processExitBoundaries = buildList {
        if (encoderResult != null) {
            add(
                "${SdxlImagePhase.ENCODER.wireName}[pid=${encoderResult.workerPid}," +
                    "profile=${encoderResult.runtimeProfile}]:process_exit_confirmed"
            )
        }
        add(
            "${SdxlImagePhase.UNET.wireName}[pid=${unetResult.workerPid}," +
                "profile=${unetResult.runtimeProfile}]:process_exit_confirmed"
        )
        add(
            "${SdxlImagePhase.VAE.wireName}[pid=${vaeResult.workerPid}," +
                "profile=${vaeResult.runtimeProfile}]:process_exit_confirmed"
        )
    }
    val processExitIndices = processExitBoundaries.map(stageTrace::indexOf)
    require(processExitIndices.all { it >= 0 } &&
        processExitIndices.zipWithNext().all { (previous, next) -> previous < next }
    ) { "SDXL disposable process exits are missing or out of phase order." }
    require(unetProof.nativeGenerationSequence == unetResult.nativeGenerationSequence) {
        "UNet protocol sequence mismatch."
    }
    val nativeStartedAt = unetNative.opt("nativeStartedAtMonotonicMs")
    require((nativeStartedAt is Int || nativeStartedAt is Long) &&
        (nativeStartedAt as Number).toLong() > 0L
    ) { "UNet native monotonic start evidence is missing or invalid." }
    require(unetProof.nativeStageMask == unetResult.nativeStageMask &&
        unetProof.nativeDetailStageMask == unetResult.nativeDetailStageMask
    ) { "UNet protocol stage proof mismatch." }
    require(vaeProof.nativeGenerationSequence == vaeResult.nativeGenerationSequence) {
        "VAE protocol sequence mismatch."
    }
    require(vaeProof.nativeStageMask == vaeResult.nativeStageMask &&
        vaeProof.nativeDetailStageMask == vaeResult.nativeDetailStageMask
    ) { "VAE protocol stage proof mismatch." }
    val nativeOutput = File(vaeNative.getString("outputPath")).canonicalFile
    require(nativeOutput == outputFile.canonicalFile) { "VAE output path proof mismatch." }
    require(vaeNative.getLong("outputBytes") == outputFile.length()) { "VAE output byte proof mismatch." }
    require(vaeNative.opt("outputAtomicCommit") == true) {
        "VAE output is missing native atomic publication proof."
    }
    val outputSha256 = vaeNative.getString("outputSha256").lowercase()
    require(outputSha256 == sdxlArtifactSha256(outputFile)) { "VAE output SHA-256 proof mismatch." }
    val mimeType = vaeNative.getString("mimeType")
    require(mimeType == "image/png") { "VAE output MIME proof mismatch." }

    val unetNativeEffectiveJson = unetNative.optJSONObject("nativeEffective")
        ?: error("UNet native result is missing nativeEffective evidence.")
    val actualConditioningArtifactSha256 = unetNativeEffectiveJson
        .getString("conditioningArtifactSha256")
        .lowercase()
    require(actualConditioningArtifactSha256 == metadata.conditioningArtifactSha256) {
        "Committed latent conditioning evidence differs from the UNet result."
    }
    val metadataNativeEffectiveJson = JSONObject(metadata.nativeEffectiveJson)
    require(
        metadataNativeEffectiveJson.getString("conditioningArtifactSha256").lowercase() ==
            actualConditioningArtifactSha256
    ) { "Committed latent nativeEffective conditioning evidence was changed." }

    return SdxlPhaseMergeProofs(unetProof, vaeProof, encoderProof, mergedNativeDetailStageMask,
        nativeStartedAt, outputSha256, mimeType, unetNativeEffectiveJson, actualConditioningArtifactSha256)
}

