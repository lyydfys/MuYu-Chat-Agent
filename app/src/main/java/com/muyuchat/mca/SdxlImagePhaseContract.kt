package com.muyuchat.mca

import kotlin.math.abs
import org.json.JSONObject

internal data class SdxlProjectionPreviewRequest(
    val interval: Int
) {
    init {
        require(interval in 1..10) { "Split-SDXL projection preview interval must be in [1, 10]." }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("mode", MODE)
        .put("interval", interval)

    companion object {
        const val MODE = "projection"

        fun fromJson(json: JSONObject): SdxlProjectionPreviewRequest {
            require(json.keys().asSequence().toSet() == setOf("mode", "interval")) {
                "Split-SDXL projection preview request contains unknown fields."
            }
            require(json.strictString("mode") == MODE) {
                "Split-SDXL preview supports only mode=projection."
            }
            return SdxlProjectionPreviewRequest(json.strictInt("interval"))
        }

        fun fromParamsOrNull(params: JSONObject): SdxlProjectionPreviewRequest? {
            params.requireNoSdxlProjectionPreview()
            return null
        }
    }
}

internal data class SdxlProjectionPreviewAudit(
    val requested: Boolean = false,
    val interval: Int = 0,
    val attemptCount: Int = 0,
    val publicationCount: Int = 0,
    val projectionMsTotal: Long = 0L,
    val lastStep: Int = 0,
    val lastRevision: Long = 0L,
    val failureCode: String = ""
) {
    init {
        require(interval == 0 || interval in 1..10) {
            "Split-SDXL projection preview audit interval is invalid."
        }
        require(requested == (interval > 0)) {
            "Split-SDXL projection preview request and interval evidence disagree."
        }
        require(attemptCount >= publicationCount && publicationCount >= 0 &&
            projectionMsTotal >= 0L && lastStep >= 0 && lastRevision >= 0L
        ) { "Split-SDXL projection preview counters are invalid." }
        require(lastRevision == publicationCount.toLong()) {
            "Split-SDXL projection preview revision must equal its publication count."
        }
        require(failureCode.isEmpty() || failureCode in FAILURE_CODES) {
            "Split-SDXL projection preview failure code is invalid."
        }
        if (!requested) {
            require(attemptCount == 0 && publicationCount == 0 && projectionMsTotal == 0L &&
                lastStep == 0 && lastRevision == 0L && failureCode.isEmpty()
            ) { "An unrequested split-SDXL projection preview carried execution evidence." }
        }
        if (publicationCount == 0) {
            require(lastStep == 0 && lastRevision == 0L) {
                "A split-SDXL projection audit without a frame cannot claim a last publication."
            }
        } else {
            require(lastStep > 0) { "A split-SDXL projection frame requires a positive step." }
        }
        if (failureCode.isNotEmpty()) {
            require(attemptCount == publicationCount + 1 ||
                (failureCode in PRE_ATTEMPT_FAILURE_CODES &&
                    attemptCount == 0 && publicationCount == 0)
            ) { "Split-SDXL projection failure must describe one stopped attempt." }
        }
    }

    val degraded: Boolean get() = failureCode.isNotEmpty()
    val mode: String get() = if (requested) SdxlProjectionPreviewRequest.MODE else "none"

    fun toJson(): JSONObject = JSONObject()
        .put("requested", requested)
        .put("mode", mode)
        .put("interval", interval)
        .put("attemptCount", attemptCount)
        .put("publicationCount", publicationCount)
        .put("projectionMsTotal", projectionMsTotal)
        .put("lastStep", lastStep)
        .put("lastRevision", lastRevision)
        .put("failureCode", failureCode)
        .put("degraded", degraded)

    companion object {
        val NONE = SdxlProjectionPreviewAudit()

        private val FAILURE_CODES = setOf(
            "PREVIEW_PROJECTION_STORAGE_INVALID",
            "PREVIEW_PROJECTION_LATENT_INVALID",
            "PREVIEW_PROJECTION_PNG_WRITE_FAILED",
            "PREVIEW_PROJECTION_PNG_INVALID",
            "PREVIEW_PROJECTION_ATOMIC_RENAME_FAILED",
            "PREVIEW_PROJECTION_DIRECTORY_FSYNC_FAILED",
            "PREVIEW_PROJECTION_JOURNAL_WRITE_FAILED"
        )
        private val PRE_ATTEMPT_FAILURE_CODES = setOf(
            "PREVIEW_PROJECTION_STORAGE_INVALID",
            "PREVIEW_PROJECTION_JOURNAL_WRITE_FAILED"
        )

        fun fromJson(json: JSONObject): SdxlProjectionPreviewAudit {
            val audit = SdxlProjectionPreviewAudit(
                requested = json.strictBoolean("requested"),
                interval = json.strictInt("interval"),
                attemptCount = json.strictInt("attemptCount"),
                publicationCount = json.strictInt("publicationCount"),
                projectionMsTotal = json.strictLong("projectionMsTotal"),
                lastStep = json.strictInt("lastStep"),
                lastRevision = json.strictLong("lastRevision"),
                failureCode = json.strictStringOrEmpty("failureCode")
            )
            require(json.strictString("mode") == audit.mode &&
                json.strictBoolean("degraded") == audit.degraded
            ) { "Split-SDXL projection preview derived evidence is inconsistent." }
            return audit
        }

        fun fromNativeResult(json: JSONObject): SdxlProjectionPreviewAudit =
            SdxlProjectionPreviewAudit(
                requested = json.strictBoolean("previewRequested"),
                interval = json.strictInt("previewInterval"),
                attemptCount = json.strictInt("projectionPreviewAttemptCount"),
                publicationCount = json.strictInt("projectionPreviewPublicationCount"),
                projectionMsTotal = json.strictLong("projectionPreviewProjectionMsTotal"),
                lastStep = json.strictInt("projectionPreviewLastStep"),
                lastRevision = json.strictLong("projectionPreviewLastRevision"),
                failureCode = json.strictStringOrEmpty("projectionPreviewFailureCode")
            ).also { audit ->
                require(json.strictString("previewMode") == audit.mode &&
                    json.strictBoolean("previewDegraded") == audit.degraded
                ) { "Split-SDXL native projection preview evidence is inconsistent." }
            }
    }
}

internal fun JSONObject.requireNoSdxlProjectionPreview() {
    require(!has("preview")) {
        "Split-SDXL isolated phases do not support projection preview."
    }
}

internal fun SdxlImagePhaseRequest.requireNoSdxlProjectionPreview() {
    JSONObject(paramsJson).requireNoSdxlProjectionPreview()
    require(projectionPreview == null) {
        "Split-SDXL isolated phase wire does not support projectionPreview."
    }
}

internal fun JSONObject.requireSdxlPreviewDisabledNativeResult(boundary: String) {
    fun requireOptionalZero(field: String) {
        if (!has(field)) return
        val number = opt(field) as? Number
            ?: error("$boundary $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble() && value == 0L) {
            "$boundary carried preview evidence in $field."
        }
    }

    fun requireOptionalEmpty(field: String) {
        if (!has(field)) return
        require(opt(field) == "") { "$boundary carried preview evidence in $field." }
    }

    fun requireOptionalFalse(field: String) {
        if (!has(field)) return
        require(opt(field) == false) { "$boundary carried preview evidence in $field." }
    }

    if (has("previewMode")) {
        val previewMode = opt("previewMode")
        require(previewMode == "" || previewMode == "none") {
            "$boundary carried an unsupported preview mode."
        }
    }
    requireOptionalFalse("previewRequested")
    requireOptionalFalse("previewDegraded")
    requireOptionalFalse("previewNoisy")
    listOf(
        "previewInterval",
        "previewStep",
        "previewRevision",
        "previewWidth",
        "previewHeight",
        "previewFrameCount",
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
    ).forEach(::requireOptionalZero)
    listOf(
        "previewPath",
        "previewMimeType",
        "previewFailureCode",
        "projectionPreviewFailureCode"
    ).forEach(::requireOptionalEmpty)
}

internal data class SdxlImageExecutionContract(
    val paramsJson: String,
    val expected: ImageResolvedExecution,
    val pixelRange: ImagePixelRange,
    val expectedTimetableCount: Int,
    val expectedUnetExecutionCount: Int,
    val conditioningArtifactSha256: String
) {
    val steps: Int get() = expected.steps
    val width: Int get() = expected.width
    val height: Int get() = expected.height

    fun expectedLatentShape(): List<Int> = listOf(1, 4, height / 8, width / 8)

    fun paramsObject(): JSONObject = JSONObject(paramsJson)

    fun ultraFixRequestOrNull(): LocalImageUltraFixOptions? {
        val params = paramsObject()
        if (!params.has("ultraFix")) return null
        return LocalImageUltraFixOptions.fromJson(
            params.optJSONObject("ultraFix")
                ?: error("Split-SDXL UltraFix request must be an object.")
        )
    }

    fun expectedJson(): JSONObject = expected.toSdxlResolvedExecutionJson()
        .put("pixelRange", pixelRange.name)
        .put("conditioningArtifactSha256", conditioningArtifactSha256)

    fun requireNativeEffective(
        nativeResult: JSONObject,
        phase: SdxlImagePhase
    ): ImageNativeEffectiveExecution {
        val evidence = nativeResult.optJSONObject("nativeEffective")
            ?: error("SDXL ${phase.wireName} native result is missing nativeEffective evidence.")
        val actualPixelRange = evidence.strictEnum("pixelRange", ImagePixelRange.entries)
        require(actualPixelRange == pixelRange) {
            "SDXL ${phase.wireName} nativeEffective pixelRange mismatch."
        }
        val actualConditioningSha256 = evidence.strictString("conditioningArtifactSha256").lowercase()
        require(actualConditioningSha256 == conditioningArtifactSha256) {
            "SDXL ${phase.wireName} nativeEffective conditioning artifact mismatch."
        }
        val actual = evidence.toSdxlNativeEffective()
        val validation = ImageExecutionContractValidator.validate(
            ImageExecutionLayers(
                requested = ImageRequestedExecution(),
                resolved = expected,
                nativeEffective = actual
            )
        )
        require(validation.valid) {
            val fields = validation.mismatches.joinToString { mismatch -> mismatch.field }
            "SDXL ${phase.wireName} nativeEffective mismatch: $fields"
        }
        return actual
    }

    fun validateRequestIdentity(request: SdxlImagePhaseRequest) {
        require(request.profileId == expected.profileId) { "SDXL request profileId mismatch." }
        require(request.profileRevision == expected.profileRevision) { "SDXL request profileRevision mismatch." }
        require(request.modelFingerprint.equals(expected.modelFingerprint, ignoreCase = true)) {
            "SDXL request model fingerprint mismatch."
        }
        require(request.steps == expected.steps) { "SDXL request steps mismatch." }
        require(request.width == expected.width && request.height == expected.height) {
            "SDXL request size mismatch."
        }
        require(request.conditioningArtifactSha256.equals(conditioningArtifactSha256, ignoreCase = true)) {
            "SDXL request conditioning artifact mismatch."
        }
    }

    companion object {
        fun fromParams(raw: String): SdxlImageExecutionContract {
            val json = JSONObject(raw)
            json.requireNoSdxlProjectionPreview()
            val effective = json.toSdxlResolvedExecution()
            val pixelRange = json.strictEnum("pixelRange", ImagePixelRange.entries)
            require(pixelRange != ImagePixelRange.RUNTIME_NATIVE) {
                "SDXL QNN execution requires an explicit VAE output pixel range."
            }
            val expectedTimetableCount = json.strictInt("expectedTimetableCount")
            val expectedUnetExecutionCount = json.strictInt("expectedUnetExecutionCount")
            val conditioningArtifactSha256 = json.strictString("conditioningArtifactSha256").lowercase()
            require(SDXL_SHA256.matches(conditioningArtifactSha256)) {
                "SDXL conditioningArtifactSha256 must be a lowercase SHA-256 value."
            }
            require(expectedTimetableCount == effective.timetableCount) {
                "expectedTimetableCount conflicts with timetableCount."
            }
            require(expectedUnetExecutionCount == effective.unetExecutionCount) {
                "expectedUnetExecutionCount conflicts with unetExecutionCount."
            }
            require(effective.runtime == LocalImageRuntime.QNN_HTP) {
                "SDXL isolated phases require runtime=QNN_HTP."
            }
            require(!effective.fallback) { "Resolved SDXL execution must not request fallback." }
            require(effective.steps > 0 && effective.timetableCount > 0 && effective.unetExecutionCount > 0) {
                "SDXL scheduler counts must be positive."
            }
            require(effective.width > 0 && effective.height > 0) { "SDXL size must be positive." }
            require(effective.width % 8 == 0 && effective.height % 8 == 0) {
                "SDXL size must define an exact 8x latent shape."
            }
            require(effective.useCfg == effective.unconditionalBranch) {
                "SDXL unconditionalBranch must equal useCfg."
            }
            require(effective.useCfg || abs(effective.cfgScale - 1.0) <= 1e-12) {
                "Conditional-only SDXL execution requires cfgScale=1."
            }
            require(effective.vaeScalingLocation == ImageVaeScalingLocation.HOST_BEFORE_GRAPH) {
                "SDXL isolated VAE execution requires host-before-graph latent scaling."
            }
            require(abs(effective.vaeScalingFactor - SDXL_QNN_VAE_SCALING_FACTOR) <= 1e-9) {
                "SDXL isolated VAE scaling factor must be $SDXL_QNN_VAE_SCALING_FACTOR."
            }
            val branches = if (effective.useCfg) 2 else 1
            require(effective.unetExecutionCount == effective.timetableCount * branches) {
                "SDXL UNet execution count does not match timetable and CFG branches."
            }
            val expectedConditioningTokenCount = 77 * branches
            val expectedConditioningEncoderExecutionCount = 2 * branches
            val expectedConditioningOrder = if (effective.useCfg) {
                "negative_then_positive"
            } else {
                "positive_only"
            }
            require(effective.tokenCount == expectedConditioningTokenCount) {
                "SDXL conditioning token count does not match CFG branches."
            }
            require(
                json.strictInt("conditioningEncoderExecutionCount") ==
                    expectedConditioningEncoderExecutionCount &&
                    json.strictString("conditioningOrder") == expectedConditioningOrder
            ) {
                "SDXL conditioning evidence does not match CFG branches."
            }
            if (json.has("ultraFix")) {
                val ultraFix = LocalImageUltraFixOptions.fromJson(
                    json.optJSONObject("ultraFix")
                        ?: error("Split-SDXL UltraFix request must be an object.")
                )
                require(
                    LocalImageTaskMode.fromWireName(json.strictString("taskMode")) ==
                        LocalImageTaskMode.IMG2IMG &&
                        effective.width == ultraFix.targetWidth &&
                        effective.height == ultraFix.targetHeight &&
                        effective.steps == ultraFix.refinementSteps &&
                        effective.timetableCount == ultraFix.inversionSteps &&
                        abs(json.strictDouble("strength") - ultraFix.strength) <= 1e-12 &&
                        ultraFix.tileSize == 1024 &&
                        ultraFix.targetWidth in 1024..2048 &&
                        ultraFix.targetHeight in 1024..2048 &&
                        ultraFix.targetWidth % 64 == 0 &&
                        ultraFix.targetHeight % 64 == 0
                ) {
                    "Split-SDXL UltraFix requires one 1024-pixel graph tile, a 64-pixel-aligned " +
                        "1024-2048 target, and the exact img2img denoising tail."
                }
                val tiling = json.optJSONObject("vaeTiling")
                    ?: error("Split-SDXL UltraFix requires request-local VAE tiling controls.")
                require(tiling.keys().asSequence().toSet() == setOf("tileSize", "overlap") &&
                    tiling.strictInt("tileSize") == ultraFix.tileSize &&
                    abs(tiling.strictDouble("overlap") - ultraFix.overlap) <= 1e-12
                ) {
                    "Split-SDXL UltraFix VAE tiling controls conflict with the request."
                }
            }
            return SdxlImageExecutionContract(
                paramsJson = json.toString(),
                expected = effective,
                pixelRange = pixelRange,
                expectedTimetableCount = expectedTimetableCount,
                expectedUnetExecutionCount = expectedUnetExecutionCount,
                conditioningArtifactSha256 = conditioningArtifactSha256
            )
        }
    }
}

private fun JSONObject.toSdxlNativeEffective(): ImageNativeEffectiveExecution =
    ImageExecutionProfileNativeContract.validatePromptWeightingEvidence(
        ImageNativeEffectiveExecution(
            profileId = strictString("profileId"),
            profileRevision = strictInt("profileRevision"),
            modelFingerprint = strictString("modelFingerprint"),
            runtime = strictEnum("runtime", LocalImageRuntime.entries),
            scheduler = strictEnum("scheduler", ImageSchedulerAlgorithm.entries),
            predictionType = strictEnum("predictionType", ImagePredictionType.entries),
            steps = strictInt("steps"),
            timetableCount = strictInt("timetableCount"),
            unetExecutionCount = strictInt("unetExecutionCount"),
            cfgScale = strictDouble("cfgScale"),
            useCfg = strictBoolean("useCfg"),
            unconditionalBranch = strictBoolean("unconditionalBranch"),
            tokenizerBackend = strictEnum("tokenizerBackend", ImageTokenizerBackend.entries),
            tokenCount = strictInt("tokenCount"),
            promptWeightingSupported = strictBoolean("promptWeightingSupported"),
            promptWeightingApplied = strictBoolean("promptWeightingApplied"),
            positiveWeightedTokenCount = strictInt("positiveWeightedTokenCount"),
            negativeWeightedTokenCount = strictInt("negativeWeightedTokenCount"),
            promptWeightFingerprint = strictString("promptWeightFingerprint"),
            embeddingDiskDataType = strictEnum(
                "embeddingDiskDataType",
                ImageEmbeddingDiskDataType.entries
            ),
            vaeScalingLocation = strictEnum(
                "vaeScalingLocation",
                ImageVaeScalingLocation.entries
            ),
            vaeScalingFactor = strictDouble("vaeScalingFactor"),
            width = strictInt("width"),
            height = strictInt("height"),
            seed = strictLong("seed"),
            graphName = strictString("graphName"),
            fallback = strictBoolean("fallback")
        )
    )

internal fun validateSdxlFlatNativeEffective(result: JSONObject): ImageNativeEffectiveExecution {
    val nestedJson = result.optJSONObject("nativeEffective")
        ?: error("SDXL result is missing nativeEffective.")
    val flat = result.toSdxlNativeEffective()
    val nested = nestedJson.toSdxlNativeEffective()
    require(flat == nested) { "SDXL flat execution fields conflict with nativeEffective." }
    val flatConditioningSha256 = result.strictString("conditioningArtifactSha256").lowercase()
    val nestedConditioningSha256 = nestedJson.strictString("conditioningArtifactSha256").lowercase()
    require(
        SDXL_SHA256.matches(flatConditioningSha256) &&
            flatConditioningSha256 == nestedConditioningSha256
    ) { "SDXL flat conditioning artifact proof conflicts with nativeEffective." }
    val flatPixelRange = result.strictEnum("pixelRange", ImagePixelRange.entries)
    val nestedPixelRange = nestedJson.strictEnum("pixelRange", ImagePixelRange.entries)
    require(flatPixelRange == nestedPixelRange && flatPixelRange != ImagePixelRange.RUNTIME_NATIVE) {
        "SDXL flat pixelRange conflicts with nativeEffective."
    }
    val flatPhaseProof = result.optJSONObject("sdxlPhaseProof")
        ?: error("SDXL result is missing flat phase proof.")
    val nestedPhaseProof = nestedJson.optJSONObject("sdxlPhaseProof")
        ?: error("SDXL nativeEffective is missing phase proof.")
    require(flatPhaseProof.toString() == nestedPhaseProof.toString()) {
        "SDXL flat phase proof conflicts with nativeEffective."
    }
    val runtimeSessionMode = result.strictString("runtimeSessionMode")
    require(runtimeSessionMode == nestedJson.strictString("runtimeSessionMode") &&
        runtimeSessionMode in setOf(
            SDXL_ISOLATED_UNET_VAE_MODE,
            SDXL_ISOLATED_ENCODER_UNET_VAE_MODE,
            SDXL_ISOLATED_ULTRAFIX_MODE
        )
    ) { "SDXL isolated runtime-session mode is invalid or inconsistent." }
    val unetTransportHtpArch = result.strictInt("unetTransportHtpArch")
    val vaeTransportHtpArch = result.strictInt("vaeTransportHtpArch")
    require(
        unetTransportHtpArch > 0 && vaeTransportHtpArch > 0 &&
            result.strictInt("transportHtpArch") == unetTransportHtpArch &&
            nestedJson.strictInt("transportHtpArch") == unetTransportHtpArch &&
            nestedJson.strictInt("unetTransportHtpArch") == unetTransportHtpArch &&
            nestedJson.strictInt("vaeTransportHtpArch") == vaeTransportHtpArch &&
            flatPhaseProof.strictInt("unetTransportHtpArch") == unetTransportHtpArch &&
            flatPhaseProof.strictInt("vaeTransportHtpArch") == vaeTransportHtpArch
    ) { "SDXL per-phase physical transport proof conflicts with nativeEffective." }
    require(
        SDXL_SHA256.matches(flatPhaseProof.strictString("unetRuntimeProfileSha256").lowercase()) &&
            SDXL_SHA256.matches(flatPhaseProof.strictString("vaeRuntimeProfileSha256").lowercase())
    ) { "SDXL exact UNet/VAE runtime-profile fingerprints are missing or invalid." }
    if (runtimeSessionMode in setOf(
            SDXL_ISOLATED_ENCODER_UNET_VAE_MODE,
            SDXL_ISOLATED_ULTRAFIX_MODE
        )
    ) {
        val encoderTransportHtpArch = result.strictInt("encoderTransportHtpArch")
        val flatContextSha256 = result.strictString("encoderContextSha256").lowercase()
        val nestedContextSha256 = nestedJson.strictString("encoderContextSha256").lowercase()
        require(
            encoderTransportHtpArch > 0 &&
                nestedJson.strictInt("encoderTransportHtpArch") == encoderTransportHtpArch &&
                flatPhaseProof.strictInt("encoderTransportHtpArch") == encoderTransportHtpArch &&
                SDXL_SHA256.matches(flatContextSha256) &&
                flatContextSha256 == nestedContextSha256 &&
                flatPhaseProof.strictString("encoderContextSha256").lowercase() == flatContextSha256 &&
                nestedPhaseProof.strictString("encoderContextSha256").lowercase() == flatContextSha256 &&
                SDXL_SHA256.matches(
                    flatPhaseProof.strictString("encoderRuntimeProfileSha256").lowercase()
                )
        ) { "SDXL VAE encoder transport or context proof conflicts with nativeEffective." }
    }
    return nested
}

private fun JSONObject.toSdxlResolvedExecution(): ImageResolvedExecution =
    ImageResolvedExecution(
        profileId = strictString("profileId"),
        profileRevision = strictInt("profileRevision"),
        modelFingerprint = strictString("modelFingerprint"),
        runtime = strictEnum("runtime", LocalImageRuntime.entries),
        scheduler = strictEnum("scheduler", ImageSchedulerAlgorithm.entries),
        predictionType = strictEnum("predictionType", ImagePredictionType.entries),
        steps = strictInt("steps"),
        timetableCount = strictInt("timetableCount"),
        unetExecutionCount = strictInt("unetExecutionCount"),
        cfgScale = strictDouble("cfgScale"),
        useCfg = strictBoolean("useCfg"),
        unconditionalBranch = strictBoolean("unconditionalBranch"),
        tokenizerBackend = strictEnum("tokenizerBackend", ImageTokenizerBackend.entries),
        tokenCount = strictInt("tokenCount"),
        promptWeightingSupported = strictBoolean("promptWeightingSupported"),
        embeddingDiskDataType = strictEnum("embeddingDiskDataType", ImageEmbeddingDiskDataType.entries),
        vaeScalingLocation = strictEnum("vaeScalingLocation", ImageVaeScalingLocation.entries),
        vaeScalingFactor = strictDouble("vaeScalingFactor"),
        width = strictInt("width"),
        height = strictInt("height"),
        seed = strictLong("seed"),
        graphName = strictString("graphName"),
        fallback = strictBoolean("fallback")
    )

private fun ImageResolvedExecution.toSdxlResolvedExecutionJson(): JSONObject = JSONObject()
    .put("profileId", profileId)
    .put("profileRevision", profileRevision)
    .put("modelFingerprint", modelFingerprint)
    .put("runtime", runtime.name)
    .put("scheduler", scheduler.name)
    .put("predictionType", predictionType.name)
    .put("steps", steps)
    .put("timetableCount", timetableCount)
    .put("unetExecutionCount", unetExecutionCount)
    .put("cfgScale", cfgScale)
    .put("useCfg", useCfg)
    .put("unconditionalBranch", unconditionalBranch)
    .put("tokenizerBackend", tokenizerBackend.name)
    .put("tokenCount", tokenCount)
    .put("promptWeightingSupported", promptWeightingSupported)
    .put("embeddingDiskDataType", embeddingDiskDataType.name)
    .put("vaeScalingLocation", vaeScalingLocation.name)
    .put("vaeScalingFactor", vaeScalingFactor)
    .put("width", width)
    .put("height", height)
    .put("seed", seed)
    .put("graphName", graphName)
    .put("fallback", fallback)

private fun JSONObject.strictStringOrEmpty(name: String): String =
    strictValue(name) as? String
        ?: error("SDXL execution field $name must be a string.")
