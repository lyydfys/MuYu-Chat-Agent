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
    if (taskMode == LocalImageTaskMode.INPAINT) {
        val prepared = requireNotNull(preparedInpaint)
        val params = contract.paramsObject()
        val unetEffectiveJson = unetNative.getJSONObject("nativeEffective")
        require(encoderNative?.getString("taskMode") == taskMode.wireName &&
            unetEffectiveJson.getString("taskMode") == taskMode.wireName &&
            vaeNative.getString("taskMode") == taskMode.wireName
        ) { "Split-SDXL inpaint task identity changed across isolated phases." }
        require(unetEffectiveJson.getString("inpaintTopology") ==
            QnnInpaintMaskTopology.LATENT_BLEND_4.wireName &&
            File(unetEffectiveJson.getString("maskImageTensorPath")).canonicalFile ==
                File(prepared.maskTensorPath).canonicalFile &&
            unetEffectiveJson.getString("maskImageTensorSha256").lowercase() ==
                prepared.maskTensorSha256 &&
            unetEffectiveJson.getLong("maskImageTensorBytes") == prepared.maskTensorBytes &&
            unetEffectiveJson.getInt("inpaintPreserveStepCount") ==
                contract.expectedTimetableCount &&
            unetEffectiveJson.getInt("inpaintLatentBlendCount") ==
                contract.expectedTimetableCount &&
            unetEffectiveJson.getInt("inpaintSourceNoiseUseCount") ==
                contract.expectedTimetableCount &&
            unetEffectiveJson.getString("inpaintSourceNoiseSha256")
                .matches(Regex("^[a-f0-9]{64}$")) &&
            unetEffectiveJson.getString("inpaintPreservedLatentChecksum")
                .matches(Regex("^(?!0{16}$)[a-f0-9]{16}$"))
        ) { "Split-SDXL UNet inpaint preservation evidence is incomplete." }
        require(File(vaeNative.getString("inputImageTensorPath")).canonicalFile ==
            File(prepared.source.tensorPath).canonicalFile &&
            vaeNative.getString("inputImageTensorSha256").lowercase() ==
                prepared.source.tensorSha256 &&
            File(vaeNative.getString("maskImageFullTensorPath")).canonicalFile ==
                File(prepared.fullMaskTensorPath).canonicalFile &&
            vaeNative.getString("maskImageFullTensorSha256").lowercase() ==
                prepared.fullMaskTensorSha256 &&
            vaeNative.getLong("maskImageFullTensorBytes") == prepared.fullMaskTensorBytes &&
            vaeNative.getJSONArray("maskImageFullTensorShape").toString() ==
                JSONArray(prepared.fullMaskTensorShape).toString() &&
            vaeNative.getString("inpaintFinalMode") ==
                "per_step_source_latent_blend_then_final_vae_laplacian_pixel_blend" &&
            vaeNative.getBoolean("inpaintPixelBlendApplied") &&
            vaeNative.getInt("inpaintPixelBlendLevels") ==
                qnnInpaintLaplacianLevelCount(prepared.targetWidth, prepared.targetHeight) &&
            vaeNative.getString("inpaintPixelBlendChecksum")
                .matches(Regex("^(?!0{16}$)[a-f0-9]{16}$")) &&
            params.getString("maskImageTensorSha256").lowercase() == prepared.maskTensorSha256 &&
            params.getString("maskImageFullTensorSha256").lowercase() ==
                prepared.fullMaskTensorSha256
        ) { "Split-SDXL VAE inpaint pixel-blend evidence is incomplete." }
    }
    require(metadata.unetNativeGenerationSequence == unetResult.nativeGenerationSequence) {
        "Committed latent sequence mismatch."
    }
    require(File(unetResult.artifactPath).canonicalFile == File(metadata.latentPath).canonicalFile) {
        "UNet artifact path does not match committed latent metadata."
    }
    require(outputFile.isFile && outputFile.length() > 0L) { "SDXL output is missing." }
    require(File(vaeResult.artifactPath).canonicalFile == outputFile.canonicalFile) {
        "VAE protocol artifact path mismatch."
    }

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
