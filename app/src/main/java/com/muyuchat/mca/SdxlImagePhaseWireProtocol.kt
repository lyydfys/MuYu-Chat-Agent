package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal const val SDXL_QNN_VAE_SCALING_FACTOR = 0.13025
internal const val SDXL_ISOLATED_UNET_VAE_MODE = "isolated_unet_then_vae"
internal const val SDXL_ISOLATED_ENCODER_UNET_VAE_MODE = "isolated_encoder_then_unet_then_vae"
internal const val SDXL_ISOLATED_ULTRAFIX_MODE =
    "isolated_tiled_encoder_then_single_worker_inversion_refinement_then_tiled_vae"
internal const val QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD = "nativeDetailStageMaskHex"

internal fun ULong.toFixedUInt64Hex(): String = toString(16).padStart(16, '0')

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
        stageTrace = json.optJSONArray("stageTrace")?.toStringList().orEmpty()
    )

    private fun JSONObject.requireString(name: String): String =
        optString(name).takeIf(String::isNotBlank) ?: error("Missing $name.")
}

private fun JSONArray?.toStringList(): List<String> = buildList {
    if (this@toStringList == null) return@buildList
    for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
}

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
