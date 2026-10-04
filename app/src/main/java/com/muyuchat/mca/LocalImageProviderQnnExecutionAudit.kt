package com.muyuchat.mca

import org.json.JSONObject

internal fun qnnSharedPreviewExecutionAudit(
    nativeResult: JSONObject,
    requestedPreview: LocalImagePreviewOptions?
): JSONObject {
    require(!nativeResult.toString().contains("\"previewPath\"")) {
        "Final QNN execution metadata must not expose a transient preview path."
    }
    fun exactLong(field: String): Long {
        val number = nativeResult.opt(field) as? Number
            ?: error("Native QNN preview evidence field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Native QNN preview evidence field $field must be an exact integer."
        }
        return value
    }
    val finalVaeExecutionCount = exactLong("finalVaeExecutionCount")
    val finalVaeGraphExecutionCount = exactLong("finalVaeGraphExecutionCount")
    val vaeExecutionCount = exactLong("vaeExecutionCount")
    val vaeTileCount = exactLong("vaeTileCount")
    val vaeTiled = nativeResult.opt("vaeTiled") as? Boolean
        ?: error("Native QNN vaeTiled evidence must be boolean.")
    require(finalVaeExecutionCount == 1L) {
        "Final QNN VAE evidence must describe exactly one independent logical decode."
    }
    require(finalVaeGraphExecutionCount >= 1L &&
        vaeExecutionCount == finalVaeGraphExecutionCount &&
        vaeTileCount == finalVaeGraphExecutionCount
    ) {
        "Final QNN VAE physical graph execution evidence is inconsistent."
    }
    if (vaeTiled) {
        require(vaeTileCount > 1L) {
            "A tiled final QNN VAE decode must execute more than one concrete tile graph."
        }
    } else {
        require(vaeTileCount == 1L) {
            "A direct final QNN VAE decode must execute exactly one graph."
        }
    }

    val previewRequested = nativeResult.opt("previewRequested") as? Boolean
        ?: error("Native QNN previewRequested evidence must be boolean.")
    require(previewRequested == (requestedPreview != null)) {
        "Native QNN preview request evidence differs from the Android request."
    }
    val expectedMode = if (requestedPreview == null) "none" else "vae"
    val expectedInterval = requestedPreview?.interval?.toLong() ?: 0L
    require(requestedPreview == null || expectedInterval in 1L..10L) {
        "Shared QNN preview interval must be in [1, 10]."
    }
    require(nativeResult.opt("previewMode") is String &&
        nativeResult.getString("previewMode") == expectedMode &&
        exactLong("previewInterval") == expectedInterval
    ) { "Native QNN preview mode or interval differs from the Android request." }

    val attemptCount = exactLong("previewVaeExecutionAttemptCount")
    val executionCount = exactLong("previewVaeExecutionCount")
    val executionMsTotal = exactLong("previewVaeExecutionMsTotal")
    val publicationCount = exactLong("previewPublicationCount")
    val lastStep = exactLong("previewLastStep")
    val lastRevision = exactLong("previewLastRevision")
    require(attemptCount >= executionCount && executionCount >= publicationCount &&
        executionMsTotal >= 0L && publicationCount >= 0L &&
        lastRevision == publicationCount
    ) { "Native QNN preview counters are inconsistent." }
    val fullSteps = exactLong("steps")
    require(fullSteps > 0L) { "Native QNN preview evidence requires positive scheduler steps." }
    val previewTotalSteps = if (nativeResult.optString("taskMode") in setOf(
            LocalImageTaskMode.IMG2IMG.wireName,
            LocalImageTaskMode.INPAINT.wireName
        )
    ) {
        val effectiveSteps = exactLong("effectiveDenoiseSteps")
        require(effectiveSteps == exactLong("timetableCount") &&
            effectiveSteps in 1L..fullSteps
        ) { "Native QNN image-input preview evidence has an invalid effective scheduler tail." }
        effectiveSteps
    } else {
        fullSteps
    }
    if (publicationCount == 0L) {
        require(lastStep == 0L && lastRevision == 0L) {
            "A pathless QNN preview audit cannot claim a last frame."
        }
    } else {
        require(lastStep in 1 until previewTotalSteps && lastRevision > 0L &&
            expectedInterval > 0L && lastStep % expectedInterval == 0L &&
            lastStep == publicationCount * expectedInterval
        ) {
            "Native QNN preview last-step evidence is invalid or duplicates the final decode."
        }
    }
    val failureCode = nativeResult.opt("previewFailureCode") as? String
        ?: error("Native QNN previewFailureCode evidence must be a string.")
    val knownFailureCodes = setOf(
        "PREVIEW_STORAGE_INVALID",
        "PREVIEW_VAE_INPUT_BIND_FAILED",
        "PREVIEW_VAE_EXECUTE_FAILED",
        "PREVIEW_VAE_OUTPUT_READ_FAILED",
        "PREVIEW_REVISION_INVALID",
        "PREVIEW_PNG_WRITE_FAILED",
        "PREVIEW_PNG_INVALID",
        "PREVIEW_ATOMIC_RENAME_FAILED",
        "PREVIEW_DIRECTORY_FSYNC_FAILED",
        "PREVIEW_JOURNAL_COMMIT_FAILED"
    )
    require(failureCode.length <= 128 &&
        failureCode.all { it.isUpperCase() || it.isDigit() || it == '_' } &&
        (failureCode.isEmpty() || failureCode in knownFailureCodes)
    ) { "Native QNN preview failure code is invalid." }
    val previewDegraded = nativeResult.opt("previewDegraded") as? Boolean
        ?: error("Native QNN previewDegraded evidence must be boolean.")
    require(previewDegraded == failureCode.isNotEmpty()) {
        "Native QNN preview degradation evidence conflicts with previewFailureCode."
    }
    val expectedEligiblePreviewCount = if (requestedPreview == null) {
        0L
    } else {
        (previewTotalSteps - 1L) / expectedInterval
    }
    require(publicationCount <= expectedEligiblePreviewCount) {
        "Native QNN preview published more frames than its interval schedule allows."
    }
    if (vaeTiled && requestedPreview != null && expectedEligiblePreviewCount > 0L &&
        failureCode != "PREVIEW_STORAGE_INVALID" &&
        failureCode != "PREVIEW_JOURNAL_COMMIT_FAILED"
    ) {
        require(failureCode == "PREVIEW_VAE_INPUT_BIND_FAILED" &&
            attemptCount == 1L && executionCount == 0L && publicationCount == 0L
        ) {
            "The current shared QNN publisher cannot directly bind full latents to a tiled VAE graph."
        }
    }
    if (requestedPreview != null && failureCode.isEmpty()) {
        require(publicationCount == expectedEligiblePreviewCount &&
            attemptCount == publicationCount && executionCount == publicationCount
        ) { "Native QNN preview evidence does not match the deterministic eligible-step schedule." }
    } else if (failureCode == "PREVIEW_STORAGE_INVALID" ||
        (failureCode == "PREVIEW_JOURNAL_COMMIT_FAILED" &&
            attemptCount == 0L && executionCount == 0L && publicationCount == 0L)
    ) {
        require(attemptCount == 0L && executionCount == 0L && publicationCount == 0L) {
            "QNN preview initialization failure cannot claim a VAE attempt or publication."
        }
    } else if (failureCode.isNotEmpty()) {
        require(attemptCount == publicationCount + 1L &&
            executionCount in publicationCount..attemptCount
        ) { "QNN preview failure counters do not describe exactly one stopped attempt." }
    }
    if (requestedPreview == null) {
        require(
            attemptCount == 0L && executionCount == 0L && executionMsTotal == 0L &&
                publicationCount == 0L && lastStep == 0L && lastRevision == 0L &&
                failureCode.isEmpty() && !previewDegraded
        ) { "A QNN request without preview carried preview execution evidence." }
    }
    return JSONObject()
        .put("finalVaeExecutionCount", finalVaeExecutionCount)
        .put("finalVaeGraphExecutionCount", finalVaeGraphExecutionCount)
        .put("vaeExecutionCount", vaeExecutionCount)
        .put("vaeTileCount", vaeTileCount)
        .put("vaeTiled", vaeTiled)
        .put("previewRequested", previewRequested)
        .put("previewMode", expectedMode)
        .put("previewInterval", expectedInterval)
        .put("previewVaeExecutionAttemptCount", attemptCount)
        .put("previewVaeExecutionCount", executionCount)
        .put("previewVaeExecutionMsTotal", executionMsTotal)
        .put("previewPublicationCount", publicationCount)
        .put("previewLastStep", lastStep)
        .put("previewLastRevision", lastRevision)
        .put("previewFailureCode", failureCode)
        .put("previewDegraded", previewDegraded)
}

internal fun qnnSdxlProjectionPreviewExecutionAudit(
    nativeResult: JSONObject,
    requestedPreview: LocalImagePreviewOptions?
): JSONObject {
    require(requestedPreview == null) {
        "Split-SDXL uses isolated workers and does not support live preview."
    }
    fun exactLong(field: String): Long {
        val number = nativeResult.opt(field) as? Number
            ?: error("Split-SDXL preview evidence field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Split-SDXL preview evidence field $field must be an exact integer."
        }
        return value
    }

    val requested = nativeResult.opt("previewRequested") as? Boolean
        ?: error("Split-SDXL previewRequested evidence must be boolean.")
    require(!requested) { "Split-SDXL result claimed a forbidden live-preview request." }
    require(nativeResult.opt("previewMode") is String &&
        nativeResult.getString("previewMode") == "none" &&
        exactLong("previewInterval") == 0L
    ) { "Split-SDXL preview mode or interval must be disabled." }

    val finalVaeExecutionCount = exactLong("finalVaeExecutionCount")
    val finalVaeGraphExecutionCount = exactLong("finalVaeGraphExecutionCount")
    val vaeExecutionCount = exactLong("vaeExecutionCount")
    require(finalVaeExecutionCount == 1L && finalVaeGraphExecutionCount >= 1L &&
        vaeExecutionCount == finalVaeGraphExecutionCount
    ) {
        "Split-SDXL preview counters cannot replace the one final VAE decode."
    }
    val degraded = nativeResult.opt("previewDegraded") as? Boolean
        ?: error("Split-SDXL previewDegraded evidence must be boolean.")
    require(!degraded) { "Split-SDXL result reported a forbidden preview degradation." }
    listOf(
        "previewVaeExecutionAttemptCount",
        "previewVaeExecutionCount",
        "previewVaeExecutionMsTotal",
        "previewPublicationCount",
        "previewLastStep",
        "previewLastRevision",
        "projectionPreviewAttemptCount",
        "projectionPreviewPublicationCount",
        "projectionPreviewProjectionMsTotal",
        "projectionPreviewLastStep",
        "projectionPreviewLastRevision"
    ).forEach { field ->
        require(exactLong(field) == 0L) {
            "Split-SDXL result carried forbidden preview evidence in $field."
        }
    }
    // These fields are optional on legacy isolated-worker result wires, but any
    // occurrence would still be live-preview publication evidence and must stay disabled.
    listOf(
        "previewStep",
        "previewRevision",
        "previewWidth",
        "previewHeight",
        "previewFrameCount"
    ).forEach { field ->
        if (nativeResult.has(field)) {
            require(exactLong(field) == 0L) {
                "Split-SDXL result carried forbidden preview frame evidence in $field."
            }
        }
    }
    require(nativeResult.opt("previewFailureCode") == "" &&
        nativeResult.opt("projectionPreviewFailureCode") == "" &&
        (!nativeResult.has("previewPath") || nativeResult.opt("previewPath") == "") &&
        (!nativeResult.has("previewMimeType") || nativeResult.opt("previewMimeType") == "") &&
        (!nativeResult.has("previewNoisy") || nativeResult.opt("previewNoisy") == false)
    ) { "Split-SDXL result exposed forbidden transient preview evidence." }

    val zero = 0L
    val empty = ""
    val disabledMode = "none"
    val disabled = false
    return JSONObject()
        .put("finalVaeExecutionCount", finalVaeExecutionCount)
        .put("finalVaeGraphExecutionCount", finalVaeGraphExecutionCount)
        .put("vaeExecutionCount", vaeExecutionCount)
        .put("previewRequested", disabled)
        .put("previewMode", disabledMode)
        .put("previewInterval", zero)
        .put("previewVaeExecutionAttemptCount", zero)
        .put("previewVaeExecutionCount", zero)
        .put("previewVaeExecutionMsTotal", zero)
        .put("previewPublicationCount", zero)
        .put("previewLastStep", zero)
        .put("previewLastRevision", zero)
        .put("previewFailureCode", empty)
        .put("projectionPreviewAttemptCount", zero)
        .put("projectionPreviewPublicationCount", zero)
        .put("projectionPreviewProjectionMsTotal", zero)
        .put("projectionPreviewLastStep", zero)
        .put("projectionPreviewLastRevision", zero)
        .put("projectionPreviewFailureCode", empty)
        .put("previewDegraded", disabled)
}

internal fun qnnPreviewExecutionAuditForRuntime(
    nativeResult: JSONObject,
    requestedPreview: LocalImagePreviewOptions?
): JSONObject? {
    val sharedSessionModes = setOf(
        "shared_unet_vae",
        "shared_text_unet_vae",
        "shared_unet_controlnet_vae",
        "shared_text_unet_controlnet_vae"
    )
    return when (nativeResult.optString("runtimeSessionMode")) {
        in sharedSessionModes -> qnnSharedPreviewExecutionAudit(nativeResult, requestedPreview)
        SDXL_ISOLATED_UNET_VAE_MODE,
        SDXL_ISOLATED_ENCODER_UNET_VAE_MODE,
        SDXL_ISOLATED_ULTRAFIX_MODE ->
            qnnSdxlProjectionPreviewExecutionAudit(nativeResult, requestedPreview)
        else -> {
            require(requestedPreview == null) {
                "Unknown QNN execution cannot carry a live preview request."
            }
            require(!nativeResult.toString().contains("\"previewPath\"")) {
                "Final QNN execution metadata must not expose a transient preview path."
            }
            null
        }
    }
}

internal fun qnnImageExecutionMetadata(
    nativeRequestId: String,
    nativeResult: JSONObject,
    outputBytes: Long,
    inputExecutionAudit: JSONObject? = null,
    requestedPreview: LocalImagePreviewOptions? = null,
    stagedRuntime: QnnImageStagedRuntime? = null
): JSONObject = JSONObject().also { metadata ->
    val nativeDetailStageMask =
        nativeResult.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD)
    metadata.put(
        QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
        nativeDetailStageMask.toFixedUInt64Hex()
    )
    ImageExecutionProfileNativeContract.requiredFields.forEach { field ->
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "Native QNN execution metadata is missing required field: $field"
        }
        metadata.put(field, nativeResult.get(field))
    }
    val nativeEffective = requireNotNull(nativeResult.optJSONObject("nativeEffective")) {
        "Native QNN execution metadata is missing nativeEffective."
    }
    if (nativeEffective.has(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ||
        nativeEffective.has("nativeDetailStageMask")
    ) {
        require(
            nativeEffective.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ==
                nativeDetailStageMask
        ) { "Native QNN detail stage mask conflicts with nativeEffective." }
    }
    listOf("encoder", "unet", "vae").forEach { phase ->
        val prefix = phase.replaceFirstChar(Char::uppercaseChar)
        val hexField = "${phase}NativeDetailStageMaskHex"
        val legacyField = "${phase}NativeDetailStageMask"
        if (nativeResult.has(hexField) || nativeResult.has(legacyField)) {
            val value = nativeResult.strictUInt64Hex(hexField, legacyField)
            metadata.put(hexField, value.toFixedUInt64Hex())
            nativeEffective.optJSONObject("sdxlPhaseProof")?.let { proof ->
                if (proof.has(hexField) || proof.has(legacyField)) {
                    require(proof.strictUInt64Hex(hexField, legacyField) == value) {
                        "Native QNN $prefix detail stage mask conflicts with the phase proof."
                    }
                }
            }
        }
    }
    ImageExecutionProfileNativeContract.qnnNativeEffectiveFields.forEach { field ->
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "Native QNN execution metadata is missing required field: $field"
        }
        require(nativeEffective.has(field) && !nativeEffective.isNull(field)) {
            "Native QNN nativeEffective metadata is missing required field: $field"
        }
        require(nativeResult.get(field) == nativeEffective.get(field)) {
            "Native QNN $field evidence conflicts with nativeEffective."
        }
        metadata.put(field, nativeResult.get(field))
    }
    val pixelRange = ImagePixelRange.entries.firstOrNull {
        it.name == nativeResult.getString("pixelRange")
    } ?: error("Native QNN execution reported an unknown pixelRange.")
    require(pixelRange != ImagePixelRange.RUNTIME_NATIVE) {
        "Native QNN execution must report an explicit pixelRange."
    }
    val expectedConversion =
        ImageExecutionProfileNativeContract.qnnPixelRangeConversionName(pixelRange)
    require(nativeResult.getString("pixelRangeConversion") == expectedConversion) {
        "Native QNN pixel-range conversion evidence does not match pixelRange."
    }
    fun requiredExactLong(field: String): Long {
        val number = nativeResult.opt(field) as? Number
            ?: error("Native QNN execution metadata field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Native QNN execution metadata field $field must be an exact integer."
        }
        return value
    }
    qnnPreviewExecutionAuditForRuntime(nativeResult, requestedPreview)?.let { previewAudit ->
        previewAudit.keys().forEach { field -> metadata.put(field, previewAudit.get(field)) }
    }
    val valueCount = requiredExactLong("pixelRangeValueCount")
    val clampedValueCount = requiredExactLong("pixelRangeClampedValueCount")
    val expectedValueCount = Math.multiplyExact(
        Math.multiplyExact(nativeResult.getLong("width"), nativeResult.getLong("height")),
        3L
    )
    require(valueCount == expectedValueCount) {
        "Native QNN pixel-range value count does not match the generated RGB image."
    }
    require(clampedValueCount in 0L..valueCount) {
        "Native QNN pixel-range clamp count is invalid."
    }
    val observedMin = (nativeResult.opt("pixelRangeObservedMin") as? Number)?.toDouble()
        ?: error("Native QNN pixelRangeObservedMin evidence must be numeric.")
    val observedMax = (nativeResult.opt("pixelRangeObservedMax") as? Number)?.toDouble()
        ?: error("Native QNN pixelRangeObservedMax evidence must be numeric.")
    require(observedMin.isFinite() && observedMax.isFinite() && observedMin <= observedMax) {
        "Native QNN observed pixel range is invalid."
    }
    ImageExecutionProfileNativeContract.qnnPixelRangeEvidenceFields.forEach { field ->
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "Native QNN execution metadata is missing pixel-range evidence: $field"
        }
        metadata.put(field, nativeResult.get(field))
    }
    metadata.put("nativeEffective", nativeEffective)
    nativeResult.optJSONArray("timesteps")?.let { metadata.put("timesteps", it) }
    nativeResult.optJSONArray("sigmas")?.let { metadata.put("sigmas", it) }
    inputExecutionAudit?.let { metadata.put("imageInput", it) }
    listOf(
        "taskMode",
        "batchCount",
        "inputImageExecutionCount",
        "maskImageExecutionCount",
        "controlImageExecutionCount",
        "inputImageSha256",
        "inputImageTensorSha256",
        "inputImagePreprocess",
        "inputImageSourceWidth",
        "inputImageSourceHeight",
        "inputImageOrientedWidth",
        "inputImageOrientedHeight",
        "inputImageExifOrientation",
        "inputImageTensorWidth",
        "inputImageTensorHeight",
        "inputImageTensorChannels",
        "inputImageTensorBytes",
        "inputImageTensorShape",
        "inputImageTensorDtype",
        "inputImageTensorLayout",
        "inputImageTensorRange",
        "strength",
        "fullTimetableCount",
        "effectiveDenoiseSteps",
        "img2imgBeginIndex",
        "encoderLatentSha256",
        "encoderContextSha256",
        "encoderContextLoadCount",
        "encoderExecutionCount",
        "encoderGraphName",
        "encoderInputName",
        "encoderMeanOutputName",
        "encoderStdOutputName",
        "encoderInputDtype",
        "encoderMeanDtype",
        "encoderStdDtype",
        "encoderInputShape",
        "encoderMeanShape",
        "encoderStdShape",
        "encoderInputBufferSha256",
        "encoderMeanBufferSha256",
        "encoderStdBufferSha256",
        "posteriorSampling",
        "posteriorSampleCount",
        "encoderLatentScalingFactor",
        "encoderContextReleasedBeforeSharedSession",
        "encoderRuntimeMode",
        "img2imgAddNoiseApplied",
        "img2imgAddNoiseBeginIndex",
        "img2imgAddNoiseTimestep",
        "img2imgNoiseChecksum",
        "controlImageSha256",
        "controlImagePreprocessedSha256",
        "controlImagePreprocess",
        "controlImageSourceWidth",
        "controlImageSourceHeight",
        "controlImageSourceChannels",
        "controlImageOrientedWidth",
        "controlImageOrientedHeight",
        "controlImageExifOrientation",
        "controlImageTensorWidth",
        "controlImageTensorHeight",
        "controlImageTensorChannels",
        "controlImageTensorLayout",
        "controlImageEdgePixelCount",
        "controlImageTensorChecksum",
        "controlStrength",
        "controlStrengthApplied",
        "controlNetExecutionCount",
        "controlNetResidualTensorCount",
        "controlNetResidualWriteCount",
        "controlNetResidualUnetReuseCount",
        "controlNetConditioningBranch",
        "controlNetInputConsumed",
        "controlNetInputBufferSha256",
        "controlNetScaledResidualChecksum",
        "controlNetGraph",
        "conditioningArtifactSha256"
    ).forEach { field ->
        if (nativeResult.has(field) && !nativeResult.isNull(field)) {
            metadata.put(field, nativeResult.get(field))
        }
    }
    listOf(
        "initNoiseSigma",
        "scaleModelInput",
        "textEncoderExecutionCount",
        "vaeExecutionCount",
        "effectiveVaeHostScale",
        "vaeTileCount",
        "vaeTiled",
        "outputSha256",
        "outputSizeBytes",
        "outputAtomicCommit",
        "ultraFix",
        "strengthMechanism",
        "sampleMethod",
        "nativeScheduler",
        "actualDiffusionModelComputeCount",
        "actualPositiveDiffusionModelComputeCount",
        "actualNegativeDiffusionModelComputeCount",
        "actualAuxiliaryDiffusionModelComputeCount",
        "actualSamplingStepCount",
        "actualSamplingPassCount",
        "totalUnetExecutionCount"
    ).forEach { field ->
        if (nativeResult.has(field) && !nativeResult.isNull(field)) {
            metadata.put(field, nativeResult.get(field))
        }
    }
}.apply {
    put("nativeRequestId", nativeRequestId)
    .put("backend", nativeResult.optString("backend"))
    .put("executionStage", nativeResult.optString("executionStage"))
    .put("npuActive", nativeResult.optBoolean("npuActive", false))
    .put("qnnGraphExecution", nativeResult.optBoolean("qnnGraphExecution", false))
    .put("nativeExecution", nativeResult.optBoolean("nativeExecution", false))
    .put("fallback", nativeResult.optBoolean("fallback", true))
    .put("nativeGenerationSequence", nativeResult.optLong("nativeGenerationSequence"))
    .put("nativeStartedAtMonotonicMs", nativeResult.optLong("nativeStartedAtMonotonicMs"))
    .put("nativeStageMask", nativeResult.optLong("nativeStageMask"))
    .put("runtimeSessionMode", nativeResult.optString("runtimeSessionMode"))
    .put("conditioningFormat", nativeResult.optString("conditioningFormat"))
    .put("transportHtpArch", nativeResult.optInt("transportHtpArch"))
    .put("encoderWorkerPid", nativeResult.optInt("encoderWorkerPid"))
    .put("encoderRuntimeProfile", nativeResult.optString("encoderRuntimeProfile"))
    .put("encoderTransportHtpArch", nativeResult.optInt("encoderTransportHtpArch"))
    .put("encoderProcessDeathConfirmed", nativeResult.optBoolean("encoderProcessDeathConfirmed", false))
    .put("encoderGraph", nativeResult.optString("encoderGraph"))
    .put("unetWorkerPid", nativeResult.optInt("unetWorkerPid"))
    .put("unetRuntimeProfile", nativeResult.optString("unetRuntimeProfile"))
    .put("unetTransportHtpArch", nativeResult.optInt("unetTransportHtpArch"))
    .put("unetProcessDeathConfirmed", nativeResult.optBoolean("unetProcessDeathConfirmed", false))
    .put("unetGraph", nativeResult.optString("unetGraph"))
    .put("vaeWorkerPid", nativeResult.optInt("vaeWorkerPid"))
    .put("vaeRuntimeProfile", nativeResult.optString("vaeRuntimeProfile"))
    .put("vaeTransportHtpArch", nativeResult.optInt("vaeTransportHtpArch"))
    .put("vaeProcessDeathConfirmed", nativeResult.optBoolean("vaeProcessDeathConfirmed", false))
    .put("vaeGraph", nativeResult.optString("vaeGraph"))
    .put("steps", nativeResult.optInt("steps"))
    .put("width", nativeResult.optInt("width"))
    .put("height", nativeResult.optInt("height"))
    .put("elapsedMs", nativeResult.optLong("elapsedMs"))
    .put("unetContextLoadMs", nativeResult.optLong("unetContextLoadMs"))
    .put("unetExecuteMsTotal", nativeResult.optLong("unetExecuteMsTotal"))
    .put("unetExecuteMsAvg", nativeResult.optLong("unetExecuteMsAvg"))
    .put("vaeContextLoadMs", nativeResult.optLong("vaeContextLoadMs"))
    .put("vaeExecuteMs", nativeResult.optLong("vaeExecuteMs"))
    .put("textEncoderGraph", nativeResult.optString("textEncoderGraph"))
    .put("textEncoderContextLoadMs", nativeResult.optLong("textEncoderContextLoadMs"))
    .put("textEncoderExecuteMsTotal", nativeResult.optLong("textEncoderExecuteMsTotal"))
    .put("textEncoderEmbeddingWidth", nativeResult.optLong("textEncoderEmbeddingWidth"))
    .put("latentSha256", nativeResult.optString("latentSha256"))
    .put("outputBytes", outputBytes)
    .also { metadata ->
        nativeResult.optJSONObject("runtime")?.let { runtime ->
            metadata
                .put("selectedHtpArch", runtime.optInt("htpArchVersion"))
                .put("runtimeLoadable", runtime.optBoolean("loadable", false))
                .put("qnnInterfacePresent", runtime.optBoolean("qnnInterfacePresent", false))
            runtime.optJSONObject("compile")?.let { compile ->
                metadata
                    .put("sdkHeadersPresent", compile.optBoolean("sdkHeadersPresent", false))
                    .put("typedGraphBindingsCompiled", compile.optBoolean("typedGraphBindingsCompiled", false))
            }
        }
        // A semantic QNN result reports the actual runtime under
        // `executionRuntime`; unlike the preflight `runtimeInspection`, this
        // is emitted only after a graph has executed.  Preserve just the
        // stable architecture integers in product metadata (not private
        // library paths), using the staged profile only as a last-resort hint.
        val htpTelemetry = resolveQnnHtpArchTelemetry(
            nativeResult = nativeResult,
            stagedRuntime = stagedRuntime
        )
        htpTelemetry.selectedHtpArch?.let { selected ->
            metadata.put("selectedHtpArch", selected)
        }
        htpTelemetry.transportHtpArch?.let { transport ->
            metadata.put("transportHtpArch", transport)
        }
    }
}
