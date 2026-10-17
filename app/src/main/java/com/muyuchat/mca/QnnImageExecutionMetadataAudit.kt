package com.muyuchat.mca

import org.json.JSONObject

/**
 * T2B22-T4（b 工位）拆片：`qnnImageExecutionMetadata` 自
 * LocalImageProviderQnnExecutionAudit.kt 逐字节平移；函数原为 internal 顶层，零改写。
 */

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
