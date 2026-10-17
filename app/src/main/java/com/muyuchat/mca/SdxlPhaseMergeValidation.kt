package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private fun requireSplitSdxlPreviewDisabledEvidence(
    phase: SdxlImagePhase,
    requestedPreview: SdxlProjectionPreviewRequest?,
    nativeResult: JSONObject
) {
    require(requestedPreview == null) {
        "Split-SDXL ${phase.wireName} result carried a forbidden live-preview request."
    }
    require(SdxlProjectionPreviewAudit.fromNativeResult(nativeResult) ==
        SdxlProjectionPreviewAudit.NONE
    ) { "Split-SDXL ${phase.wireName} result carried legacy projection-preview evidence." }

    fun requireExactZero(field: String) {
        val number = nativeResult.opt(field) as? Number
            ?: error("Split-SDXL $field evidence must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble() && value == 0L) {
            "Split-SDXL ${phase.wireName} result carried preview evidence in $field."
        }
    }

    listOf(
        "previewVaeExecutionAttemptCount",
        "previewVaeExecutionCount",
        "previewVaeExecutionMsTotal",
        "previewPublicationCount",
        "previewLastStep",
        "previewLastRevision"
    ).forEach(::requireExactZero)
    listOf(
        "previewStep",
        "previewRevision",
        "previewWidth",
        "previewHeight",
        "previewFrameCount"
    ).forEach { field ->
        if (nativeResult.has(field)) requireExactZero(field)
    }
    require(nativeResult.opt("previewFailureCode") == "" &&
        (!nativeResult.has("previewPath") || nativeResult.opt("previewPath") == "") &&
        (!nativeResult.has("previewMimeType") || nativeResult.opt("previewMimeType") == "") &&
        (!nativeResult.has("previewNoisy") || nativeResult.opt("previewNoisy") == false)
    ) { "Split-SDXL ${phase.wireName} result exposed transient preview evidence." }
}

internal data class SdxlPhaseMergeInputs(
    val hasEncoder: Boolean,
    val taskMode: LocalImageTaskMode,
    val ultraFix: LocalImageUltraFixOptions?,
    val disabledPreviewAudit: SdxlProjectionPreviewAudit
)

internal fun requireSdxlPhaseMergeInputs(
    contract: SdxlImageExecutionContract,
    unetResult: SdxlImagePhaseResult,
    unetNative: JSONObject,
    vaeResult: SdxlImagePhaseResult,
    vaeNative: JSONObject,
    encoderResult: SdxlImagePhaseResult?,
    encoderNative: JSONObject?,
    encoderMetadata: SdxlEncoderLatentMetadata?,
    encoderRuntimeProfile: SdxlQnnRuntimeProfile?,
    preparedInpaint: QnnPreparedInpaintInput?
): SdxlPhaseMergeInputs {
    require(listOf(encoderResult, encoderNative, encoderMetadata, encoderRuntimeProfile)
        .all { it == null } ||
        listOf(encoderResult, encoderNative, encoderMetadata, encoderRuntimeProfile)
            .all { it != null }
    ) { "SDXL encoder result, native proof, metadata, and exact runtime profile must be present together." }
    val hasEncoder = encoderResult != null
    val taskMode = LocalImageTaskMode.fromWireName(
        contract.paramsObject().getString("taskMode")
    )
    val ultraFix = contract.ultraFixRequestOrNull()
    require(
        when (taskMode) {
            LocalImageTaskMode.TEXT_TO_IMAGE -> !hasEncoder && preparedInpaint == null
            LocalImageTaskMode.IMG2IMG -> hasEncoder && preparedInpaint == null
            LocalImageTaskMode.INPAINT -> hasEncoder && preparedInpaint != null
            else -> false
        }
    ) { "SDXL phase topology does not match the resolved product task mode." }
    preparedInpaint?.let { prepared ->
        require(prepared.topology == QnnInpaintMaskTopology.LATENT_BLEND_4 &&
            prepared.targetWidth == contract.width && prepared.targetHeight == contract.height
        ) { "Split-SDXL inpaint merge requires exact latent_blend_4 artifacts." }
    }
    require(ultraFix == null ||
        (taskMode == LocalImageTaskMode.IMG2IMG && hasEncoder && preparedInpaint == null)
    ) { "Split-SDXL UltraFix requires exactly the three encoder/UNet/VAE phases." }
    require(unetResult.phase == SdxlImagePhase.UNET) { "Expected UNet phase result." }
    require(vaeResult.phase == SdxlImagePhase.VAE) { "Expected VAE phase result." }
    val requestedPreview = SdxlProjectionPreviewRequest.fromParamsOrNull(contract.paramsObject())
    require(requestedPreview == null &&
        unetResult.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE &&
        vaeResult.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE &&
        (encoderResult == null || encoderResult.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE)
    ) { "Split-SDXL phase results cannot carry live-preview evidence." }
    requireSplitSdxlPreviewDisabledEvidence(
        phase = SdxlImagePhase.UNET,
        requestedPreview = requestedPreview,
        nativeResult = unetNative
    )
    requireSplitSdxlPreviewDisabledEvidence(
        phase = SdxlImagePhase.VAE,
        requestedPreview = requestedPreview,
        nativeResult = vaeNative
    )
    encoderNative?.let { native ->
        requireSplitSdxlPreviewDisabledEvidence(
            phase = SdxlImagePhase.ENCODER,
            requestedPreview = requestedPreview,
            nativeResult = native
        )
    }
    val disabledPreviewAudit = SdxlProjectionPreviewAudit.NONE
    require(encoderResult == null || encoderResult.phase == SdxlImagePhase.ENCODER) {
        "Expected VAE encoder phase result."
    }
    require(unetResult.requestId == vaeResult.requestId) { "SDXL phase request identity mismatch." }
    return SdxlPhaseMergeInputs(hasEncoder, taskMode, ultraFix, disabledPreviewAudit)
}

internal fun requireSdxlPhaseMergePhaseEvidence(
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
    encoderResult: SdxlImagePhaseResult?,
    encoderNative: JSONObject?,
    encoderMetadata: SdxlEncoderLatentMetadata?,
    encoderRuntimeProfile: SdxlQnnRuntimeProfile?,
    ultraFix: LocalImageUltraFixOptions?
) {
    require(
        unetResult.conditioningArtifactSha256 == contract.conditioningArtifactSha256 &&
            vaeResult.conditioningArtifactSha256 == contract.conditioningArtifactSha256
    ) { "SDXL phase conditioning artifact identity mismatch." }
    require(unetResult.workerPid > 0 && vaeResult.workerPid > 0 &&
        (encoderResult == null || encoderResult.workerPid > 0)
    ) {
        "SDXL phase worker PID proof is missing."
    }
    require(unetNative.optBoolean("ok") && vaeNative.optBoolean("ok")) {
        "SDXL phase native success proof is missing."
    }
    require(unetTransportHtpArch > 0 && vaeTransportHtpArch > 0) {
        "SDXL phase physical transport proof is missing."
    }
    require(unetRuntimeProfile.htpArchVersion == unetTransportHtpArch &&
        vaeRuntimeProfile.htpArchVersion == vaeTransportHtpArch
    ) { "SDXL winning exact runtime profiles differ from the physical transport proof." }
    require(unetResult.runtimeProfile == sdxlTransportProfile(unetTransportHtpArch) &&
        vaeResult.runtimeProfile == sdxlTransportProfile(vaeTransportHtpArch)
    ) { "SDXL phase protocol runtime profile differs from native transport evidence." }
    if (encoderNative != null && encoderMetadata != null && encoderResult != null) {
        require(encoderRuntimeProfile?.htpArchVersion == encoderMetadata.htpArchVersion) {
            "SDXL encoder winning exact runtime profile differs from producer transport proof."
        }
        val expectedEncoderContextSha256 = contract.paramsObject()
            .getString("vaeEncoderContextSha256")
            .lowercase()
        require(encoderResult.requestId == unetResult.requestId &&
            encoderResult.conditioningArtifactSha256 == contract.conditioningArtifactSha256 &&
            encoderNative.optBoolean("ok") &&
            encoderNative.getInt("htpArchVersion") > 0 &&
            encoderMetadata.htpArchVersion == encoderNative.getInt("htpArchVersion") &&
            encoderResult.runtimeProfile == sdxlTransportProfile(encoderMetadata.htpArchVersion) &&
            encoderMetadata.runtimeProfile == encoderResult.runtimeProfile &&
            encoderNative.getString("encoderContextSha256").lowercase() ==
                expectedEncoderContextSha256 &&
            encoderMetadata.encoderContextSha256 == expectedEncoderContextSha256
        ) { "SDXL encoder identity or producer transport proof mismatch." }
        validateSdxlEncoderNativeEvidence(contract, encoderNative)
        val encoderProof = SdxlNativePhaseProof.fromNativeResult(
            encoderNative,
            SdxlImagePhase.ENCODER
        )
        require(encoderProof.nativeGenerationSequence == encoderResult.nativeGenerationSequence &&
            encoderProof.nativeStageMask == encoderResult.nativeStageMask &&
            encoderProof.nativeDetailStageMask == encoderResult.nativeDetailStageMask &&
            encoderMetadata.encoderNativeGenerationSequence == encoderProof.nativeGenerationSequence &&
            encoderMetadata.encoderNativeStageMask == encoderProof.nativeStageMask &&
            encoderMetadata.encoderNativeDetailStageMask == encoderProof.nativeDetailStageMask &&
            File(encoderResult.artifactPath).canonicalFile == File(encoderMetadata.latentPath).canonicalFile
        ) { "SDXL encoder protocol or committed artifact proof mismatch." }
    }
    require(unetNative.getInt("htpArchVersion") == unetTransportHtpArch) { "UNet transport proof mismatch." }
    require(metadata.htpArchVersion == unetTransportHtpArch &&
        metadata.runtimeProfile == unetResult.runtimeProfile
    ) {
        "Committed latent UNet producer transport proof mismatch."
    }
    require(vaeNative.getInt("htpArchVersion") == vaeTransportHtpArch) { "VAE transport proof mismatch." }
    validateSdxlUnetNativeEvidence(contract, unetNative)
    val unetEffective = contract.requireNativeEffective(unetNative, SdxlImagePhase.UNET)
    if (encoderMetadata != null) {
        require(
            unetNative.getString("encoderLatentSha256").lowercase() == encoderMetadata.sha256 &&
                unetNative.getJSONObject("nativeEffective")
                    .getString("encoderLatentSha256").lowercase() == encoderMetadata.sha256
        ) { "UNet did not consume the exact committed VAE encoder latent." }
    }
    val metadataEffective = contract.requireNativeEffective(
        JSONObject().put("nativeEffective", JSONObject(metadata.nativeEffectiveJson)),
        SdxlImagePhase.UNET
    )
    require(metadataEffective == unetEffective) {
        "Committed latent native execution evidence differs from the UNet result."
    }
    validateSdxlVaeNativeEvidence(contract, vaeNative)
    ultraFix?.let { request ->
        val encoder = requireNotNull(encoderNative)
        val encoderPlan = encoder.getString("ultraFixTilePlanSha256").lowercase()
        val unetPlan = unetNative.getString("ultraFixTilePlanSha256").lowercase()
        val vaePlan = vaeNative.getString("ultraFixTilePlanSha256").lowercase()
        val tileCount = encoder.getInt("ultraFixTileCount")
        require(encoderPlan.matches(Regex("^[a-f0-9]{64}$")) &&
            encoderPlan == unetPlan && encoderPlan == vaePlan &&
            tileCount > 0 && unetNative.getInt("ultraFixTileCount") == tileCount &&
            vaeNative.getInt("ultraFixTileCount") == tileCount
        ) { "Split-SDXL UltraFix phase tile-plan identities conflict." }
        val inversionGraphs = Math.multiplyExact(tileCount, request.inversionSteps)
        val refinementPositive = inversionGraphs
        val refinementNegative = if (contract.expected.useCfg) refinementPositive else 0
        val physicalUnet = Math.addExact(
            Math.addExact(inversionGraphs, refinementPositive),
            refinementNegative
        )
        require(encoder.getInt("ultraFixEncoderGraphExecutionCount") == tileCount &&
            encoder.getInt("ultraFixEncoderTileSuccessCount") == tileCount &&
            unetNative.getInt("ultraFixInversionGraphExecutionCount") == inversionGraphs &&
            unetNative.getInt("ultraFixInversionTileSuccessCount") == inversionGraphs &&
            unetNative.getInt("ultraFixRefinementPositiveGraphExecutionCount") ==
                refinementPositive &&
            unetNative.getInt("ultraFixRefinementNegativeGraphExecutionCount") ==
                refinementNegative &&
            unetNative.getInt("ultraFixRefinementTileSuccessCount") ==
                refinementPositive + refinementNegative &&
            unetNative.getInt("ultraFixPhysicalUnetGraphExecutionCount") == physicalUnet &&
            vaeNative.getInt("ultraFixDecoderGraphExecutionCount") == tileCount &&
            vaeNative.getInt("ultraFixDecoderTileSuccessCount") == tileCount &&
            vaeNative.getBoolean("ultraFixOutputAtomicCommit")
        ) { "Split-SDXL UltraFix physical phase counts are incomplete." }
        val qualityStepCount = unetNative.getInt("ultraFixQualityStepEvaluationCount")
        val noiseStepCount = unetNative.getInt("ultraFixNoiseInjectionStepCount")
        val structureStepCount = unetNative.getInt("ultraFixStructureGuidanceStepCount")
        val seedFingerprint = unetNative.getString("ultraFixNoiseInjectionSeedFingerprint").lowercase()
        val noiseChecksum = unetNative.getString("ultraFixNoiseInjectionChecksum").lowercase()
        val structureChecksum = unetNative.getString("ultraFixStructureGuidanceChecksum").lowercase()
        val trajectoryChecksum = unetNative.getString("ultraFixTrajectoryNoiseChecksum").lowercase()
        require(
            qualityStepCount == request.inversionSteps - 1 &&
                seedFingerprint.matches(Regex("^[a-f0-9]{64}$")) &&
                noiseChecksum.matches(Regex("^[a-f0-9]{16}$")) &&
                structureChecksum.matches(Regex("^[a-f0-9]{16}$")) &&
                trajectoryChecksum.matches(Regex("^[a-f0-9]{16}$")) &&
                if (request.inversionSteps == 1) {
                    noiseStepCount == 0 && structureStepCount == 0 &&
                        noiseChecksum == "0000000000000000" &&
                        structureChecksum == "0000000000000000" &&
                        trajectoryChecksum == "0000000000000000"
                } else {
                    noiseStepCount > 0 && structureStepCount > 0 &&
                        noiseChecksum != "0000000000000000" &&
                        structureChecksum != "0000000000000000" &&
                        trajectoryChecksum != "0000000000000000"
                }
        ) { "Split-SDXL UltraFix quality execution evidence is incomplete." }
    }
}

