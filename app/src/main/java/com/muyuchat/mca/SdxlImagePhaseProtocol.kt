package com.muyuchat.mca

import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

internal const val SDXL_QNN_VAE_SCALING_FACTOR = 0.13025
internal const val SDXL_ISOLATED_UNET_VAE_MODE = "isolated_unet_then_vae"
internal const val SDXL_ISOLATED_ENCODER_UNET_VAE_MODE = "isolated_encoder_then_unet_then_vae"
internal const val SDXL_ISOLATED_ULTRAFIX_MODE =
    "isolated_tiled_encoder_then_single_worker_inversion_refinement_then_tiled_vae"
internal const val QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD = "nativeDetailStageMaskHex"
private val QNN_UINT64_HEX_PATTERN = Regex("^[0-9a-f]{16}$")

internal fun ULong.toFixedUInt64Hex(): String = toString(16).padStart(16, '0')

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

internal enum class SdxlImagePhase(val wireName: String) {
    ENCODER("encoder"),
    UNET("unet"),
    VAE("vae");

    companion object {
        fun fromWire(value: String): SdxlImagePhase =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown SDXL image phase: $value")
    }
}

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

internal data class SdxlQnnRuntimeProfile(
    val hostDirectory: String,
    val dspDirectory: String,
    val htpArchVersion: Int
) {
    init {
        require(hostDirectory.isNotBlank()) { "SDXL QNN hostDirectory must not be blank." }
        require(dspDirectory.isNotBlank()) { "SDXL QNN dspDirectory must not be blank." }
        require(htpArchVersion > 0) { "SDXL QNN htpArchVersion must be positive." }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("hostDirectory", hostDirectory)
        .put("dspDirectory", dspDirectory)
        .put("htpArchVersion", htpArchVersion)

    fun identitySha256(): String {
        val canonicalHost = File(hostDirectory).canonicalPath.replace('\\', '/')
        val canonicalDsp = File(dspDirectory).canonicalPath.replace('\\', '/')
        val identity = buildString {
            append("sdxl-qnn-runtime-profile-v1\n")
            append(canonicalHost.length).append(':').append(canonicalHost).append('\n')
            append(canonicalDsp.length).append(':').append(canonicalDsp).append('\n')
            append(htpArchVersion)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        private const val VERSION = 1
        private val FIELDS = setOf(
            "version",
            "hostDirectory",
            "dspDirectory",
            "htpArchVersion"
        )

        fun fromJson(json: JSONObject): SdxlQnnRuntimeProfile {
            require(json.keys().asSequence().toSet() == FIELDS) {
                "SDXL QNN runtime profile must contain only explicit host, DSP, and HTP fields."
            }
            require(json.strictInt("version") == VERSION) {
                "Unsupported SDXL QNN runtime profile version."
            }
            return SdxlQnnRuntimeProfile(
                hostDirectory = json.strictString("hostDirectory"),
                dspDirectory = json.strictString("dspDirectory"),
                htpArchVersion = json.strictInt("htpArchVersion")
            )
        }
    }
}

internal data class SdxlImagePhaseRequest(
    val requestId: String,
    val phase: SdxlImagePhase,
    /** Exact host/DSP transport requested for this disposable phase only. */
    val runtimeProfile: SdxlQnnRuntimeProfile,
    /** Physical transport that produced the source artifact; provenance only, never phase admission. */
    val sourceArtifactProducerHtpArch: Int = 0,
    val profileId: String,
    val profileRevision: Int,
    val modelFingerprint: String,
    val steps: Int,
    val width: Int,
    val height: Int,
    val bundleRoot: String,
    val paramsJson: String,
    val embeddingsPath: String,
    val latentPath: String,
    val metadataPath: String,
    val outputPath: String,
    val journalPath: String,
    val conditioningArtifactSha256: String,
    val expectedVaeEncoderContextSha256: String = "",
    val inputTensorPath: String = "",
    val maskTensorPath: String = "",
    val fullMaskTensorPath: String = "",
    val sourceLatentPath: String = "",
    val sourceMetadataPath: String = "",
    val projectionPreview: SdxlProjectionPreviewRequest? = null
) {
    val phaseHtpArch: Int get() = runtimeProfile.htpArchVersion
}

private fun JSONObject.requireNoSdxlProjectionPreview() {
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

internal fun LocalImageProgress.withSdxlPreviewDisabled(): LocalImageProgress = copy(
    previewPath = "",
    previewMimeType = "",
    previewMode = "",
    previewStep = 0,
    previewRevision = 0L,
    previewWidth = 0,
    previewHeight = 0,
    previewFrameCount = 0,
    previewNoisy = false,
    previewVaeExecutionAttemptCount = 0,
    previewVaeExecutionCount = 0,
    previewVaeExecutionMsTotal = 0L,
    previewPublicationCount = 0,
    previewLastStep = 0,
    previewLastRevision = 0L,
    previewFailureCode = ""
)

internal fun LocalImageProgress.requireSdxlPreviewDisabled(boundary: String) {
    require(
        previewPath.isEmpty() && previewMimeType.isEmpty() && previewMode.isEmpty() &&
            previewStep == 0 && previewRevision == 0L &&
            previewWidth == 0 && previewHeight == 0 && previewFrameCount == 0 &&
            !previewNoisy && previewVaeExecutionAttemptCount == 0 &&
            previewVaeExecutionCount == 0 && previewVaeExecutionMsTotal == 0L &&
            previewPublicationCount == 0 && previewLastStep == 0 && previewLastRevision == 0L &&
            previewFailureCode.isEmpty()
    ) { "$boundary carried unsupported split-SDXL preview progress." }
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

internal data class SdxlImagePhaseProgress(
    val requestId: String,
    val phase: SdxlImagePhase,
    val workerPid: Int,
    val runtimeProfile: String,
    val progress: LocalImageProgress,
    val projectionPreviewAudit: SdxlProjectionPreviewAudit = SdxlProjectionPreviewAudit.NONE
)

internal data class SdxlImagePhaseResult(
    val requestId: String,
    val phase: SdxlImagePhase,
    val workerPid: Int,
    val runtimeProfile: String,
    val artifactPath: String,
    val metadataPath: String,
    val nativeGenerationSequence: Long,
    val nativeStageMask: Long,
    val nativeDetailStageMask: ULong,
    val conditioningArtifactSha256: String,
    val nativeResultJson: String,
    val projectionPreviewAudit: SdxlProjectionPreviewAudit = SdxlProjectionPreviewAudit.NONE
)

internal data class SdxlNativePhaseProof(
    val nativeGenerationSequence: Long,
    val nativeStageMask: Long,
    val nativeDetailStageMask: ULong
) {
    companion object {
        fun fromNativeResult(result: JSONObject, phase: SdxlImagePhase): SdxlNativePhaseProof {
            val sequence = result.strictLong("nativeGenerationSequence")
            val stageMask = result.strictLong("nativeStageMask")
            val detailStageMask = result.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD)
            require(sequence > 0L) { "SDXL ${phase.wireName} native sequence must be positive." }
            require(stageMask > 0L) { "SDXL ${phase.wireName} native stage mask must be positive." }
            return SdxlNativePhaseProof(sequence, stageMask, detailStageMask)
        }
    }
}

/** Immutable resolved execution fields carried through both isolated phase workers. */
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

internal data class SdxlImagePhaseError(
    val requestId: String,
    val phase: SdxlImagePhase,
    val workerPid: Int,
    val code: String,
    val message: String
)

internal object SdxlImagePhaseProtocol {
    private const val VERSION = 10

    fun request(value: SdxlImagePhaseRequest): String {
        value.requireNoSdxlProjectionPreview()
        return JSONObject()
            .put("version", VERSION)
            .put("requestId", value.requestId)
            .put("phase", value.phase.wireName)
            .put("runtimeProfile", value.runtimeProfile.toJson())
            .put("sourceArtifactProducerHtpArch", value.sourceArtifactProducerHtpArch)
            .put("profileId", value.profileId)
            .put("profileRevision", value.profileRevision)
            .put("modelFingerprint", value.modelFingerprint)
            .put("steps", value.steps)
            .put("width", value.width)
            .put("height", value.height)
            .put("bundleRoot", value.bundleRoot)
            .put("paramsJson", value.paramsJson)
            .put("embeddingsPath", value.embeddingsPath)
            .put("latentPath", value.latentPath)
            .put("metadataPath", value.metadataPath)
            .put("outputPath", value.outputPath)
            .put("journalPath", value.journalPath)
            .put("conditioningArtifactSha256", value.conditioningArtifactSha256)
            .put("expectedVaeEncoderContextSha256", value.expectedVaeEncoderContextSha256)
            .put("inputTensorPath", value.inputTensorPath)
            .put("maskTensorPath", value.maskTensorPath)
            .put("fullMaskTensorPath", value.fullMaskTensorPath)
            .put("sourceLatentPath", value.sourceLatentPath)
            .put("sourceMetadataPath", value.sourceMetadataPath)
            // Keep the v10 field so pre-existing phase clients remain wire-compatible.
            .put("projectionPreview", JSONObject.NULL)
            .toString()
    }

    fun parseRequest(raw: String): SdxlImagePhaseRequest {
        val json = JSONObject(raw)
        require(json.strictInt("version") == VERSION) { "Unsupported SDXL phase protocol version." }
        val phase = SdxlImagePhase.fromWire(json.requireString("phase"))
        val paramsJson = json.requireString("paramsJson")
        val contract = SdxlImageExecutionContract.fromParams(paramsJson)
        val wireProjectionPreview = json.opt("projectionPreview")
        require(wireProjectionPreview == null || wireProjectionPreview === JSONObject.NULL) {
            "Split-SDXL isolated phase wire does not support projectionPreview."
        }
        require(!json.has("expectedHtpArch")) {
            "Legacy ambiguous expectedHtpArch is not accepted by SDXL phase protocol v$VERSION."
        }
        require(!json.has("runtimeDirsJson") && !json.has("phaseHtpArch")) {
            "Legacy untyped runtime directories are not accepted by SDXL phase protocol v$VERSION."
        }
        val runtimeProfile = SdxlQnnRuntimeProfile.fromJson(
            json.optJSONObject("runtimeProfile")
                ?: error("SDXL phase request requires one explicit QNN runtime profile.")
        )
        val sourceArtifactProducerHtpArch = json.strictInt(
            "sourceArtifactProducerHtpArch"
        ).also { arch ->
            require(arch >= 0) { "sourceArtifactProducerHtpArch must be non-negative." }
        }
        return SdxlImagePhaseRequest(
            requestId = json.requireString("requestId"),
            phase = phase,
            runtimeProfile = runtimeProfile,
            sourceArtifactProducerHtpArch = sourceArtifactProducerHtpArch,
            profileId = json.requireString("profileId"),
            profileRevision = json.strictInt("profileRevision"),
            modelFingerprint = json.requireString("modelFingerprint"),
            steps = json.strictInt("steps"),
            width = json.strictInt("width"),
            height = json.strictInt("height"),
            bundleRoot = json.requireString("bundleRoot"),
            paramsJson = paramsJson,
            embeddingsPath = json.optString("embeddingsPath"),
            latentPath = json.requireString("latentPath"),
            metadataPath = json.requireString("metadataPath"),
            outputPath = json.optString("outputPath"),
            journalPath = json.requireString("journalPath"),
            conditioningArtifactSha256 = json.requireString("conditioningArtifactSha256"),
            expectedVaeEncoderContextSha256 = json.optString("expectedVaeEncoderContextSha256")
                .lowercase(),
            inputTensorPath = json.optString("inputTensorPath"),
            maskTensorPath = json.optString("maskTensorPath"),
            fullMaskTensorPath = json.optString("fullMaskTensorPath"),
            sourceLatentPath = json.optString("sourceLatentPath"),
            sourceMetadataPath = json.optString("sourceMetadataPath"),
            projectionPreview = null
        ).also { request ->
            contract.validateRequestIdentity(request)
            request.requireNoSdxlProjectionPreview()
            val taskMode = LocalImageTaskMode.fromWireName(
                contract.paramsObject().optString(
                    "taskMode",
                    LocalImageTaskMode.TEXT_TO_IMAGE.wireName
                )
            )
            when (phase) {
                SdxlImagePhase.ENCODER -> {
                    val expectedContextSha256 = contract.paramsObject()
                        .strictString("vaeEncoderContextSha256")
                        .lowercase()
                    require(
                        taskMode in setOf(LocalImageTaskMode.IMG2IMG, LocalImageTaskMode.INPAINT) &&
                            request.inputTensorPath.isNotBlank() &&
                            request.inputTensorPath == contract.paramsObject()
                                .strictString("inputImageTensorPath") &&
                            request.maskTensorPath.isBlank() && request.fullMaskTensorPath.isBlank() &&
                            request.sourceLatentPath.isBlank() && request.sourceMetadataPath.isBlank()
                    ) { "SDXL encoder phase requires exactly one prepared input tensor." }
                    require(request.sourceArtifactProducerHtpArch == 0) {
                        "SDXL encoder phase cannot declare an upstream artifact producer."
                    }
                    require(
                        SDXL_SHA256.matches(request.expectedVaeEncoderContextSha256) &&
                            request.expectedVaeEncoderContextSha256 == expectedContextSha256
                    ) { "SDXL encoder phase requires the resolved VAE encoder context SHA-256." }
                }
                SdxlImagePhase.UNET -> {
                    require(request.expectedVaeEncoderContextSha256.isBlank()) {
                        "SDXL UNet phase received an unrelated VAE encoder context digest."
                    }
                    val hasSourceArtifact = request.sourceLatentPath.isNotBlank() &&
                        request.sourceMetadataPath.isNotBlank()
                    require(
                        (taskMode in setOf(LocalImageTaskMode.IMG2IMG, LocalImageTaskMode.INPAINT)) ==
                            hasSourceArtifact
                    ) { "SDXL UNet source latent does not match taskMode." }
                    require(request.inputTensorPath.isBlank() && request.fullMaskTensorPath.isBlank()) {
                        "SDXL UNet phase received an unrelated full-resolution input artifact."
                    }
                    if (taskMode == LocalImageTaskMode.INPAINT) {
                        require(request.maskTensorPath.isNotBlank() &&
                            request.maskTensorPath == contract.paramsObject()
                                .strictString("maskImageTensorPath")
                        ) { "SDXL inpaint UNet requires the exact prepared latent mask." }
                    } else {
                        require(request.maskTensorPath.isBlank()) {
                            "A non-inpaint SDXL UNet phase received a mask tensor."
                        }
                    }
                    require(
                        if (hasSourceArtifact) {
                            request.sourceArtifactProducerHtpArch > 0
                        } else {
                            request.sourceArtifactProducerHtpArch == 0
                        }
                    ) { "SDXL UNet artifact producer provenance does not match its source latent." }
                }
                SdxlImagePhase.VAE -> {
                    require(
                        request.expectedVaeEncoderContextSha256.isBlank() &&
                            request.maskTensorPath.isBlank() && request.sourceLatentPath.isBlank() &&
                            request.sourceMetadataPath.isBlank()
                    ) { "SDXL VAE decoder phase received an unrelated private input path or encoder digest." }
                    if (taskMode == LocalImageTaskMode.INPAINT) {
                        require(request.inputTensorPath.isNotBlank() &&
                            request.inputTensorPath == contract.paramsObject()
                                .strictString("inputImageTensorPath") &&
                            request.fullMaskTensorPath.isNotBlank() &&
                            request.fullMaskTensorPath == contract.paramsObject()
                                .strictString("maskImageFullTensorPath")
                        ) { "SDXL inpaint VAE requires the exact source RGB and full mask tensors." }
                    } else {
                        require(request.inputTensorPath.isBlank() && request.fullMaskTensorPath.isBlank()) {
                            "A non-inpaint SDXL VAE phase received pixel-blend artifacts."
                        }
                    }
                    require(request.sourceArtifactProducerHtpArch > 0) {
                        "SDXL VAE phase requires UNet artifact producer provenance."
                    }
                }
            }
        }
    }

    fun progress(value: SdxlImagePhaseProgress): String {
        require(value.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE) {
            "Split-SDXL phase progress cannot carry projection preview evidence."
        }
        value.progress.requireSdxlPreviewDisabled("Split-SDXL phase progress")
        return JSONObject()
            .put("version", VERSION)
            .put("requestId", value.requestId)
            .put("phase", value.phase.wireName)
            .put("workerPid", value.workerPid)
            .put("runtimeProfile", value.runtimeProfile)
            .put("progress", progressJson(value.progress))
            .put("projectionPreviewAudit", SdxlProjectionPreviewAudit.NONE.toJson())
            .toString()
    }

    fun parseProgress(raw: String): SdxlImagePhaseProgress {
        val json = JSONObject(raw)
        require(json.strictInt("version") == VERSION) { "Unsupported SDXL phase protocol version." }
        val projectionPreviewAudit = SdxlProjectionPreviewAudit.fromJson(
            json.getJSONObject("projectionPreviewAudit")
        )
        require(projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE) {
            "Split-SDXL phase progress carried unsupported projection preview evidence."
        }
        val progress = parseProgressJson(json.getJSONObject("progress"))
        progress.requireSdxlPreviewDisabled("Split-SDXL phase progress")
        return SdxlImagePhaseProgress(
            requestId = json.requireString("requestId"),
            phase = SdxlImagePhase.fromWire(json.requireString("phase")),
            workerPid = json.optInt("workerPid", -1),
            runtimeProfile = json.requireString("runtimeProfile"),
            progress = progress,
            projectionPreviewAudit = SdxlProjectionPreviewAudit.NONE
        )
    }

    fun result(value: SdxlImagePhaseResult): String {
        require(value.projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE) {
            "Split-SDXL phase result cannot carry projection preview evidence."
        }
        JSONObject(value.nativeResultJson).requireSdxlPreviewDisabledNativeResult(
            "Split-SDXL phase result"
        )
        return JSONObject()
            .put("version", VERSION)
            .put("requestId", value.requestId)
            .put("phase", value.phase.wireName)
            .put("workerPid", value.workerPid)
            .put("runtimeProfile", value.runtimeProfile)
            .put("artifactPath", value.artifactPath)
            .put("metadataPath", value.metadataPath)
            .put("nativeGenerationSequence", value.nativeGenerationSequence)
            .put("nativeStageMask", value.nativeStageMask)
            .put(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD, value.nativeDetailStageMask.toFixedUInt64Hex())
            .put("conditioningArtifactSha256", value.conditioningArtifactSha256)
            .put("nativeResultJson", value.nativeResultJson)
            .put("projectionPreviewAudit", SdxlProjectionPreviewAudit.NONE.toJson())
            .toString()
    }

    fun parseResult(raw: String): SdxlImagePhaseResult {
        val json = JSONObject(raw)
        require(json.strictInt("version") == VERSION) { "Unsupported SDXL phase protocol version." }
        val projectionPreviewAudit = SdxlProjectionPreviewAudit.fromJson(
            json.getJSONObject("projectionPreviewAudit")
        )
        require(projectionPreviewAudit == SdxlProjectionPreviewAudit.NONE) {
            "Split-SDXL phase result carried unsupported projection preview evidence."
        }
        val nativeResultJson = json.requireString("nativeResultJson")
        JSONObject(nativeResultJson).requireSdxlPreviewDisabledNativeResult(
            "Split-SDXL phase result"
        )
        return SdxlImagePhaseResult(
            requestId = json.requireString("requestId"),
            phase = SdxlImagePhase.fromWire(json.requireString("phase")),
            workerPid = json.optInt("workerPid", -1),
            runtimeProfile = json.requireString("runtimeProfile"),
            artifactPath = json.requireString("artifactPath"),
            metadataPath = json.optString("metadataPath"),
            nativeGenerationSequence = json.strictLong("nativeGenerationSequence"),
            nativeStageMask = json.strictLong("nativeStageMask"),
            nativeDetailStageMask = json.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD),
            conditioningArtifactSha256 = json.requireString("conditioningArtifactSha256"),
            nativeResultJson = nativeResultJson,
            projectionPreviewAudit = SdxlProjectionPreviewAudit.NONE
        )
    }

    fun error(value: SdxlImagePhaseError): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", value.requestId)
        .put("phase", value.phase.wireName)
        .put("workerPid", value.workerPid)
        .put("code", value.code)
        .put("message", value.message)
        .toString()

    fun parseError(raw: String): SdxlImagePhaseError {
        val json = JSONObject(raw)
        require(json.strictInt("version") == VERSION) { "Unsupported SDXL phase protocol version." }
        return SdxlImagePhaseError(
            requestId = json.optString("requestId"),
            phase = SdxlImagePhase.fromWire(json.requireString("phase")),
            workerPid = json.optInt("workerPid", -1),
            code = json.optString("code", "sdxl_phase_failed"),
            message = json.optString("message").ifBlank { "SDXL phase failed." }
        )
    }

    private fun progressJson(value: LocalImageProgress): JSONObject = JSONObject()
        .put("phase", value.phase)
        .put("message", value.message)
        .put("step", value.step)
        .put("steps", value.steps)
        .put("elapsedMs", value.elapsedMs)
        .put("secondsPerStep", value.secondsPerStep)
        .put("threads", value.threads)
        .put("width", value.width)
        .put("height", value.height)
        .put("cancelRequested", value.cancelRequested)
        .put("requestOptionsJson", value.requestOptionsJson)
        .put("componentSelectionJson", value.componentSelectionJson)
        .put("previewPath", value.previewPath)
        .put("previewMimeType", value.previewMimeType)
        .put("previewMode", value.previewMode)
        .put("previewStep", value.previewStep)
        .put("previewRevision", value.previewRevision)
        .put("previewWidth", value.previewWidth)
        .put("previewHeight", value.previewHeight)
        .put("previewFrameCount", value.previewFrameCount)
        .put("previewNoisy", value.previewNoisy)
        .put("previewVaeExecutionAttemptCount", value.previewVaeExecutionAttemptCount)
        .put("previewVaeExecutionCount", value.previewVaeExecutionCount)
        .put("previewVaeExecutionMsTotal", value.previewVaeExecutionMsTotal)
        .put("previewPublicationCount", value.previewPublicationCount)
        .put("previewLastStep", value.previewLastStep)
        .put("previewLastRevision", value.previewLastRevision)
        .put("previewFailureCode", value.previewFailureCode)
        .put("stageTrace", JSONArray(value.stageTrace))

    private fun parseProgressJson(json: JSONObject): LocalImageProgress = LocalImageProgress(
        phase = json.optString("phase"),
        message = json.optString("message"),
        step = json.optInt("step"),
        steps = json.optInt("steps"),
        elapsedMs = json.optLong("elapsedMs"),
        secondsPerStep = json.optDouble("secondsPerStep"),
        threads = json.optInt("threads"),
        width = json.optInt("width"),
        height = json.optInt("height"),
        cancelRequested = json.optBoolean("cancelRequested"),
        requestOptionsJson = json.optString("requestOptionsJson"),
        componentSelectionJson = json.optString("componentSelectionJson"),
        previewPath = json.optString("previewPath"),
        previewMimeType = json.optString("previewMimeType"),
        previewMode = json.optString("previewMode"),
        previewStep = json.optInt("previewStep"),
        previewRevision = json.optLong("previewRevision"),
        previewWidth = json.optInt("previewWidth"),
        previewHeight = json.optInt("previewHeight"),
        previewFrameCount = json.optInt("previewFrameCount"),
        previewNoisy = json.optBoolean("previewNoisy"),
        previewVaeExecutionAttemptCount = json.optInt("previewVaeExecutionAttemptCount"),
        previewVaeExecutionCount = json.optInt("previewVaeExecutionCount"),
        previewVaeExecutionMsTotal = json.optLong("previewVaeExecutionMsTotal"),
        previewPublicationCount = json.optInt("previewPublicationCount"),
        previewLastStep = json.optInt("previewLastStep"),
        previewLastRevision = json.optLong("previewLastRevision"),
        previewFailureCode = json.optString("previewFailureCode"),
        stageTrace = json.optJSONArray("stageTrace").toStringList()
    )

    private fun JSONObject.requireString(name: String): String =
        optString(name).takeIf(String::isNotBlank) ?: error("Missing $name.")
}

internal fun sdxlTransportProfile(htpArchVersion: Int): String {
    require(htpArchVersion > 0) { "SDXL transport profile requires a physical HTP architecture." }
    return "V$htpArchVersion"
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

internal data class SdxlEncoderLatentMetadata(
    val requestId: String,
    val producerPid: Int,
    val runtimeProfile: String,
    val htpArchVersion: Int,
    val latentPath: String,
    val dtype: String,
    val shape: List<Int>,
    val byteSize: Long,
    val sha256: String,
    val inputImageSha256: String,
    val inputImageTensorSha256: String,
    val encoderContextSha256: String,
    val encoderNativeGenerationSequence: Long,
    val encoderNativeStageMask: Long,
    val encoderNativeDetailStageMask: ULong
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 2)
        .put("committed", true)
        .put("requestId", requestId)
        .put("phase", SdxlImagePhase.ENCODER.wireName)
        .put("producerPid", producerPid)
        .put("runtimeProfile", runtimeProfile)
        .put("htpArchVersion", htpArchVersion)
        .put("latentPath", latentPath)
        .put("dtype", dtype)
        .put("shape", JSONArray(shape))
        .put("byteSize", byteSize)
        .put("sha256", sha256)
        .put("inputImageSha256", inputImageSha256)
        .put("inputImageTensorSha256", inputImageTensorSha256)
        .put("encoderContextSha256", encoderContextSha256)
        .put("encoderNativeGenerationSequence", encoderNativeGenerationSequence)
        .put("encoderNativeStageMask", encoderNativeStageMask)
        .put(
            "encoderNativeDetailStageMaskHex",
            encoderNativeDetailStageMask.toFixedUInt64Hex()
        )

    companion object {
        fun fromJson(json: JSONObject): SdxlEncoderLatentMetadata {
            require(json.strictInt("version") == 2 && json.strictBoolean("committed")) {
                "Unsupported or uncommitted SDXL encoder metadata."
            }
            return SdxlEncoderLatentMetadata(
                requestId = json.strictString("requestId"),
                producerPid = json.strictInt("producerPid"),
                runtimeProfile = json.strictString("runtimeProfile"),
                htpArchVersion = json.strictInt("htpArchVersion"),
                latentPath = json.strictString("latentPath"),
                dtype = json.strictString("dtype"),
                shape = json.getJSONArray("shape").toPositiveIntList(),
                byteSize = json.strictLong("byteSize"),
                sha256 = json.strictString("sha256").lowercase(),
                inputImageSha256 = json.strictString("inputImageSha256").lowercase(),
                inputImageTensorSha256 = json.strictString("inputImageTensorSha256").lowercase(),
                encoderContextSha256 = json.strictString("encoderContextSha256").lowercase(),
                encoderNativeGenerationSequence = json.strictLong("encoderNativeGenerationSequence"),
                encoderNativeStageMask = json.strictLong("encoderNativeStageMask"),
                encoderNativeDetailStageMask = json.strictUInt64Hex(
                    "encoderNativeDetailStageMaskHex",
                    "encoderNativeDetailStageMask"
                )
            )
        }
    }
}

internal object SdxlEncoderLatentArtifact {
    fun publishMetadata(
        requestId: String,
        producerPid: Int,
        contract: SdxlImageExecutionContract,
        proof: SdxlNativePhaseProof,
        nativeResult: JSONObject,
        latentFile: File,
        metadataFile: File
    ): SdxlEncoderLatentMetadata {
        validateSdxlEncoderNativeEvidence(contract, nativeResult)
        val producerHtpArch = nativeResult.strictInt("htpArchVersion")
        val runtimeProfile = nativeResult.strictString("runtimeProfile")
        require(producerHtpArch > 0 && runtimeProfile == sdxlTransportProfile(producerHtpArch)) {
            "VAE encoder producer transport evidence is invalid."
        }
        val shape = nativeResult.getJSONArray("latentShape").toPositiveIntList()
        require(nativeResult.strictString("latentDtype") == "float32-le") {
            "Unsupported VAE encoder latent dtype."
        }
        val expectedBytes = shape.fold(1L) { total, value ->
            Math.multiplyExact(total, value.toLong())
        } * 4L
        require(latentFile.isFile && latentFile.length() == expectedBytes) {
            "VAE encoder latent file does not match its declared shape."
        }
        val metadata = SdxlEncoderLatentMetadata(
            requestId = requestId,
            producerPid = producerPid,
            runtimeProfile = runtimeProfile,
            htpArchVersion = producerHtpArch,
            latentPath = latentFile.canonicalPath,
            dtype = "float32-le",
            shape = shape,
            byteSize = latentFile.length(),
            sha256 = sdxlArtifactSha256(latentFile),
            inputImageSha256 = nativeResult.strictString("inputImageSha256").lowercase(),
            inputImageTensorSha256 = nativeResult.strictString("inputImageTensorSha256").lowercase(),
            encoderContextSha256 = nativeResult.strictString("encoderContextSha256").lowercase(),
            encoderNativeGenerationSequence = proof.nativeGenerationSequence,
            encoderNativeStageMask = proof.nativeStageMask,
            encoderNativeDetailStageMask = proof.nativeDetailStageMask
        )
        sdxlAtomicWriteJson(metadataFile, metadata.toJson())
        return metadata
    }

    fun validate(
        requestId: String,
        latentFile: File,
        metadataFile: File,
        expectedProducerArch: Int,
        contract: SdxlImageExecutionContract
    ): SdxlEncoderLatentMetadata {
        require(metadataFile.isFile) { "VAE encoder latent metadata is missing." }
        val metadata = SdxlEncoderLatentMetadata.fromJson(JSONObject(metadataFile.readText()))
        val params = contract.paramsObject()
        require(metadata.requestId == requestId && metadata.producerPid > 0) {
            "VAE encoder latent request identity is invalid."
        }
        require(expectedProducerArch > 0 && metadata.htpArchVersion == expectedProducerArch &&
            metadata.runtimeProfile == sdxlTransportProfile(metadata.htpArchVersion)
        ) {
            "VAE encoder latent producer transport provenance mismatch."
        }
        require(metadata.dtype == "float32-le" && metadata.shape == contract.expectedLatentShape()) {
            "VAE encoder latent type or shape mismatch."
        }
        require(File(metadata.latentPath).canonicalFile == latentFile.canonicalFile &&
            latentFile.isFile && latentFile.length() == metadata.byteSize &&
            sdxlArtifactSha256(latentFile) == metadata.sha256
        ) { "VAE encoder latent artifact changed after publication." }
        require(metadata.inputImageSha256 == params.strictString("inputImageSha256").lowercase() &&
            metadata.inputImageTensorSha256 == params.strictString("inputImageTensorSha256").lowercase() &&
            metadata.encoderContextSha256 == params.strictString("vaeEncoderContextSha256").lowercase() &&
            SDXL_SHA256.matches(metadata.encoderContextSha256)
        ) { "VAE encoder latent input provenance mismatch." }
        require(metadata.encoderNativeGenerationSequence > 0L &&
            metadata.encoderNativeStageMask > 0L
        ) { "VAE encoder native proof is invalid." }
        return metadata
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

internal data class SdxlLatentMetadata(
    val requestId: String,
    val producerPid: Int,
    val runtimeProfile: String,
    val htpArchVersion: Int,
    val latentPath: String,
    val dtype: String,
    val shape: List<Int>,
    val byteSize: Long,
    val sha256: String,
    val conditioningArtifactSha256: String,
    val nativeEffectiveJson: String,
    val unetNativeGenerationSequence: Long,
    val unetNativeStageMask: Long,
    val unetNativeDetailStageMask: ULong
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 3)
        .put("committed", true)
        .put("requestId", requestId)
        .put("phase", SdxlImagePhase.UNET.wireName)
        .put("producerPid", producerPid)
        .put("runtimeProfile", runtimeProfile)
        .put("htpArchVersion", htpArchVersion)
        .put("latentPath", latentPath)
        .put("dtype", dtype)
        .put("shape", JSONArray(shape))
        .put("byteSize", byteSize)
        .put("sha256", sha256)
        .put("conditioningArtifactSha256", conditioningArtifactSha256)
        .put("nativeEffective", JSONObject(nativeEffectiveJson))
        .put("unetNativeGenerationSequence", unetNativeGenerationSequence)
        .put("unetNativeStageMask", unetNativeStageMask)
        .put("unetNativeDetailStageMaskHex", unetNativeDetailStageMask.toFixedUInt64Hex())

    companion object {
        fun fromJson(json: JSONObject): SdxlLatentMetadata {
            require(json.strictInt("version") == 3) { "Unsupported SDXL latent metadata version." }
            require(json.optBoolean("committed")) { "Latent metadata is not committed." }
            val shape = json.getJSONArray("shape").toPositiveIntList()
            require(shape.isNotEmpty()) { "Latent shape is empty." }
            return SdxlLatentMetadata(
                requestId = json.getString("requestId"),
                producerPid = json.getInt("producerPid"),
                runtimeProfile = json.getString("runtimeProfile"),
                htpArchVersion = json.getInt("htpArchVersion"),
                latentPath = json.getString("latentPath"),
                dtype = json.getString("dtype"),
                shape = shape,
                byteSize = json.getLong("byteSize"),
                sha256 = json.getString("sha256"),
                conditioningArtifactSha256 = json.strictString("conditioningArtifactSha256").lowercase(),
                nativeEffectiveJson = json.getJSONObject("nativeEffective").toString(),
                unetNativeGenerationSequence = json.strictLong("unetNativeGenerationSequence"),
                unetNativeStageMask = json.strictLong("unetNativeStageMask"),
                unetNativeDetailStageMask = json.strictUInt64Hex(
                    "unetNativeDetailStageMaskHex",
                    "unetNativeDetailStageMask"
                )
            )
        }
    }
}

internal object SdxlLatentArtifact {
    fun publishMetadata(
        requestId: String,
        producerPid: Int,
        contract: SdxlImageExecutionContract,
        proof: SdxlNativePhaseProof,
        nativeResult: JSONObject,
        latentFile: File,
        metadataFile: File
    ): SdxlLatentMetadata {
        require(latentFile.isFile && latentFile.length() > 0L) { "UNet did not publish a latent file." }
        val producerHtpArch = nativeResult.strictInt("htpArchVersion")
        val runtimeProfile = nativeResult.strictString("runtimeProfile")
        require(producerHtpArch > 0 && runtimeProfile == sdxlTransportProfile(producerHtpArch)) {
            "UNet latent producer transport evidence is invalid."
        }
        val shape = nativeResult.getJSONArray("latentShape").toPositiveIntList()
        val dtype = nativeResult.getString("latentDtype")
        require(dtype == "float32-le") { "Unsupported latent dtype: $dtype" }
        require(shape == contract.expectedLatentShape()) {
            "UNet latent shape does not match the resolved SDXL output size."
        }
        require(shape.fold(1L) { total, value -> Math.multiplyExact(total, value.toLong()) } * 4L == latentFile.length()) {
            "Latent shape does not match byte size."
        }
        validateSdxlUnetNativeEvidence(contract, nativeResult)
        contract.requireNativeEffective(nativeResult, SdxlImagePhase.UNET)
        val nativeEffectiveJson = nativeResult.optJSONObject("nativeEffective")
            ?: error("UNet native result is missing nativeEffective evidence.")
        val actualConditioningArtifactSha256 = nativeEffectiveJson
            .strictString("conditioningArtifactSha256")
            .lowercase()
        require(actualConditioningArtifactSha256 == contract.conditioningArtifactSha256) {
            "UNet nativeEffective conditioning artifact mismatch."
        }
        val metadata = SdxlLatentMetadata(
            requestId = requestId,
            producerPid = producerPid,
            runtimeProfile = runtimeProfile,
            htpArchVersion = producerHtpArch,
            latentPath = latentFile.canonicalPath,
            dtype = dtype,
            shape = shape,
            byteSize = latentFile.length(),
            sha256 = sha256(latentFile),
            conditioningArtifactSha256 = actualConditioningArtifactSha256,
            nativeEffectiveJson = JSONObject(nativeEffectiveJson.toString()).toString(),
            unetNativeGenerationSequence = proof.nativeGenerationSequence,
            unetNativeStageMask = proof.nativeStageMask,
            unetNativeDetailStageMask = proof.nativeDetailStageMask
        )
        atomicWrite(metadataFile, strictJsonForPersistence(metadata.toJson()))
        return metadata
    }

    fun validate(
        requestId: String,
        latentFile: File,
        metadataFile: File,
        expectedProducerArch: Int,
        contract: SdxlImageExecutionContract
    ): SdxlLatentMetadata {
        require(metadataFile.isFile) { "Latent metadata is missing." }
        val metadata = SdxlLatentMetadata.fromJson(JSONObject(metadataFile.readText()))
        require(metadata.requestId == requestId) { "Latent request id mismatch." }
        require(expectedProducerArch > 0 && metadata.htpArchVersion == expectedProducerArch &&
            metadata.runtimeProfile == sdxlTransportProfile(metadata.htpArchVersion)
        ) { "Latent producer transport provenance mismatch." }
        require(metadata.dtype == "float32-le") { "Latent dtype mismatch." }
        require(metadata.shape == contract.expectedLatentShape()) {
            "Latent metadata shape does not match the resolved SDXL output size."
        }
        require(File(metadata.latentPath).canonicalFile == latentFile.canonicalFile) { "Latent path mismatch." }
        require(latentFile.isFile && latentFile.length() == metadata.byteSize) { "Latent byte size mismatch." }
        val elements = metadata.shape.fold(1L) { total, value -> Math.multiplyExact(total, value.toLong()) }
        require(elements * 4L == metadata.byteSize) { "Latent shape metadata is invalid." }
        require(sha256(latentFile).equals(metadata.sha256, ignoreCase = true)) { "Latent SHA-256 mismatch." }
        require(metadata.conditioningArtifactSha256 == contract.conditioningArtifactSha256) {
            "Latent conditioning artifact SHA-256 mismatch."
        }
        contract.requireNativeEffective(
            JSONObject().put("nativeEffective", JSONObject(metadata.nativeEffectiveJson)),
            SdxlImagePhase.UNET
        )
        require(metadata.unetNativeGenerationSequence > 0L) { "Latent native sequence is invalid." }
        require(metadata.unetNativeStageMask > 0L) { "Latent native stage mask is invalid." }
        return metadata
    }

    private fun atomicWrite(file: File, value: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".part")
        runCatching { temporary.delete() }
        FileOutputStream(temporary).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String = sdxlArtifactSha256(file)
}

internal fun sdxlArtifactSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun sdxlAtomicWriteJson(file: File, value: JSONObject) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, file.name + ".part")
    runCatching { temporary.delete() }
    FileOutputStream(temporary).use { output ->
        output.write(strictJsonForPersistence(value).toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
    try {
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun JSONArray?.toStringList(): List<String> = buildList {
    if (this@toStringList == null) return@buildList
    for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
}

private fun JSONArray.toPositiveIntList(): List<Int> = buildList {
    for (index in 0 until length()) {
        val value = getInt(index)
        require(value > 0) { "Latent shape dimensions must be positive." }
        add(value)
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

internal fun ImageNativeEffectiveExecution.toSdxlNativeEffectiveJson(): JSONObject = JSONObject()
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
    .put("promptWeightingApplied", promptWeightingApplied)
    .put("positiveWeightedTokenCount", positiveWeightedTokenCount)
    .put("negativeWeightedTokenCount", negativeWeightedTokenCount)
    .put("promptWeightFingerprint", promptWeightFingerprint)
    .put("embeddingDiskDataType", embeddingDiskDataType.name)
    .put("vaeScalingLocation", vaeScalingLocation.name)
    .put("vaeScalingFactor", vaeScalingFactor)
    .put("width", width)
    .put("height", height)
    .put("seed", seed)
    .put("graphName", graphName)
    .put("fallback", fallback)

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

private val SDXL_SHA256 = Regex("^[0-9a-f]{64}$")

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

private fun JSONObject.strictValue(name: String): Any {
    require(has(name) && !isNull(name)) { "Missing required SDXL execution field: $name" }
    return get(name)
}

private fun JSONObject.strictString(name: String): String =
    (strictValue(name) as? String)?.takeIf(String::isNotBlank)
        ?: error("SDXL execution field $name must be a non-blank string.")

private fun JSONObject.strictStringOrEmpty(name: String): String =
    strictValue(name) as? String
        ?: error("SDXL execution field $name must be a string.")

private fun JSONObject.strictBoolean(name: String): Boolean =
    strictValue(name) as? Boolean
        ?: error("SDXL execution field $name must be a boolean.")

private fun JSONObject.strictInt(name: String): Int = try {
    BigDecimal((strictValue(name) as? Number)?.toString()
        ?: error("SDXL execution field $name must be numeric.")).intValueExact()
} catch (error: ArithmeticException) {
    throw IllegalArgumentException("SDXL execution field $name must be an exact int32.", error)
} catch (error: NumberFormatException) {
    throw IllegalArgumentException("SDXL execution field $name must be an exact int32.", error)
}

private fun JSONObject.strictLong(name: String): Long = try {
    BigDecimal((strictValue(name) as? Number)?.toString()
        ?: error("SDXL execution field $name must be numeric.")).longValueExact()
} catch (error: ArithmeticException) {
    throw IllegalArgumentException("SDXL execution field $name must be an exact int64.", error)
} catch (error: NumberFormatException) {
    throw IllegalArgumentException("SDXL execution field $name must be an exact int64.", error)
}

private fun JSONObject.strictDouble(name: String): Double =
    (strictValue(name) as? Number)?.toDouble()?.takeIf(Double::isFinite)
        ?: error("SDXL execution field $name must be finite numeric.")

private fun <T : Enum<T>> JSONObject.strictEnum(name: String, entries: List<T>): T {
    val value = strictString(name)
    return entries.firstOrNull { it.name == value }
        ?: error("Unknown SDXL execution enum $name=$value")
}
