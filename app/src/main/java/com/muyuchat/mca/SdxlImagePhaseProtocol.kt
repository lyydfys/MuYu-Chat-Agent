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

internal enum class SdxlImagePhase(val wireName: String) {
    UNET("unet"),
    VAE("vae");

    companion object {
        fun fromWire(value: String): SdxlImagePhase =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown SDXL image phase: $value")
    }
}

internal data class SdxlImagePhaseRequest(
    val requestId: String,
    val phase: SdxlImagePhase,
    val expectedHtpArch: Int,
    val profileId: String,
    val profileRevision: Int,
    val modelFingerprint: String,
    val steps: Int,
    val width: Int,
    val height: Int,
    val bundleRoot: String,
    val runtimeDirsJson: String,
    val paramsJson: String,
    val embeddingsPath: String,
    val latentPath: String,
    val metadataPath: String,
    val outputPath: String,
    val journalPath: String
)

internal data class SdxlImagePhaseProgress(
    val requestId: String,
    val phase: SdxlImagePhase,
    val workerPid: Int,
    val runtimeProfile: String,
    val progress: LocalImageProgress
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
    val nativeDetailStageMask: Long,
    val nativeResultJson: String
)

internal data class SdxlNativePhaseProof(
    val nativeGenerationSequence: Long,
    val nativeStageMask: Long,
    val nativeDetailStageMask: Long
) {
    companion object {
        fun fromNativeResult(result: JSONObject, phase: SdxlImagePhase): SdxlNativePhaseProof {
            val sequence = result.strictLong("nativeGenerationSequence")
            val stageMask = result.strictLong("nativeStageMask")
            val detailStageMask = result.strictLong("nativeDetailStageMask")
            require(sequence > 0L) { "SDXL ${phase.wireName} native sequence must be positive." }
            require(stageMask > 0L) { "SDXL ${phase.wireName} native stage mask must be positive." }
            require(detailStageMask >= 0L) { "SDXL ${phase.wireName} native detail stage mask is invalid." }
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
    val expectedUnetExecutionCount: Int
) {
    val steps: Int get() = expected.steps
    val width: Int get() = expected.width
    val height: Int get() = expected.height

    fun paramsObject(): JSONObject = JSONObject(paramsJson)

    fun expectedJson(): JSONObject = expected.toSdxlResolvedExecutionJson()
        .put("pixelRange", pixelRange.name)

    fun requireNativeEffective(
        nativeResult: JSONObject,
        phase: SdxlImagePhase
    ): ImageNativeEffectiveExecution {
        val evidence = nativeResult.optJSONObject("nativeEffective") ?: nativeResult
        val actualPixelRange = evidence.strictEnum("pixelRange", ImagePixelRange.entries)
        require(actualPixelRange == pixelRange) {
            "SDXL ${phase.wireName} nativeEffective pixelRange mismatch."
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
    }

    companion object {
        fun fromParams(raw: String): SdxlImageExecutionContract {
            val json = JSONObject(raw)
            val effective = json.toSdxlResolvedExecution()
            val pixelRange = json.strictEnum("pixelRange", ImagePixelRange.entries)
            require(pixelRange != ImagePixelRange.RUNTIME_NATIVE) {
                "SDXL QNN execution requires an explicit VAE output pixel range."
            }
            val expectedTimetableCount = json.strictInt("expectedTimetableCount")
            val expectedUnetExecutionCount = json.strictInt("expectedUnetExecutionCount")
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
            require(effective.useCfg == effective.unconditionalBranch) {
                "SDXL unconditionalBranch must equal useCfg."
            }
            val branches = if (effective.useCfg) 2 else 1
            require(effective.unetExecutionCount == effective.timetableCount * branches) {
                "SDXL UNet execution count does not match timetable and CFG branches."
            }
            return SdxlImageExecutionContract(
                paramsJson = json.toString(),
                expected = effective,
                pixelRange = pixelRange,
                expectedTimetableCount = expectedTimetableCount,
                expectedUnetExecutionCount = expectedUnetExecutionCount
            )
        }
    }
}

internal fun validateSdxlVaeNativeEvidence(
    contract: SdxlImageExecutionContract,
    nativeResult: JSONObject
) {
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
    require(nativeResult.strictInt("vaeExecutionCount") == 1) { "VAE must execute exactly once." }
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
}

internal data class SdxlImagePhaseError(
    val requestId: String,
    val phase: SdxlImagePhase,
    val workerPid: Int,
    val code: String,
    val message: String
)

internal object SdxlImagePhaseProtocol {
    private const val VERSION = 2

    fun request(value: SdxlImagePhaseRequest): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", value.requestId)
        .put("phase", value.phase.wireName)
        .put("expectedHtpArch", value.expectedHtpArch)
        .put("profileId", value.profileId)
        .put("profileRevision", value.profileRevision)
        .put("modelFingerprint", value.modelFingerprint)
        .put("steps", value.steps)
        .put("width", value.width)
        .put("height", value.height)
        .put("bundleRoot", value.bundleRoot)
        .put("runtimeDirsJson", value.runtimeDirsJson)
        .put("paramsJson", value.paramsJson)
        .put("embeddingsPath", value.embeddingsPath)
        .put("latentPath", value.latentPath)
        .put("metadataPath", value.metadataPath)
        .put("outputPath", value.outputPath)
        .put("journalPath", value.journalPath)
        .toString()

    fun parseRequest(raw: String): SdxlImagePhaseRequest {
        val json = JSONObject(raw)
        require(json.strictInt("version") == VERSION) { "Unsupported SDXL phase protocol version." }
        val phase = SdxlImagePhase.fromWire(json.requireString("phase"))
        val paramsJson = json.requireString("paramsJson")
        val contract = SdxlImageExecutionContract.fromParams(paramsJson)
        val expectedHtpArch = json.strictInt("expectedHtpArch").also { arch ->
            require(arch >= 0) { "expectedHtpArch must be non-negative." }
            require(phase == SdxlImagePhase.UNET || arch > 0) {
                "VAE expectedHtpArch must bind the UNet transport profile."
            }
        }
        return SdxlImagePhaseRequest(
            requestId = json.requireString("requestId"),
            phase = phase,
            expectedHtpArch = expectedHtpArch,
            profileId = json.requireString("profileId"),
            profileRevision = json.strictInt("profileRevision"),
            modelFingerprint = json.requireString("modelFingerprint"),
            steps = json.strictInt("steps"),
            width = json.strictInt("width"),
            height = json.strictInt("height"),
            bundleRoot = json.requireString("bundleRoot"),
            runtimeDirsJson = json.requireString("runtimeDirsJson"),
            paramsJson = paramsJson,
            embeddingsPath = json.optString("embeddingsPath"),
            latentPath = json.requireString("latentPath"),
            metadataPath = json.requireString("metadataPath"),
            outputPath = json.optString("outputPath"),
            journalPath = json.requireString("journalPath")
        ).also(contract::validateRequestIdentity)
    }

    fun progress(value: SdxlImagePhaseProgress): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", value.requestId)
        .put("phase", value.phase.wireName)
        .put("workerPid", value.workerPid)
        .put("runtimeProfile", value.runtimeProfile)
        .put("progress", progressJson(value.progress))
        .toString()

    fun parseProgress(raw: String): SdxlImagePhaseProgress {
        val json = JSONObject(raw)
        require(json.strictInt("version") == VERSION) { "Unsupported SDXL phase protocol version." }
        return SdxlImagePhaseProgress(
            requestId = json.requireString("requestId"),
            phase = SdxlImagePhase.fromWire(json.requireString("phase")),
            workerPid = json.optInt("workerPid", -1),
            runtimeProfile = json.requireString("runtimeProfile"),
            progress = parseProgressJson(json.getJSONObject("progress"))
        )
    }

    fun result(value: SdxlImagePhaseResult): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", value.requestId)
        .put("phase", value.phase.wireName)
        .put("workerPid", value.workerPid)
        .put("runtimeProfile", value.runtimeProfile)
        .put("artifactPath", value.artifactPath)
        .put("metadataPath", value.metadataPath)
        .put("nativeGenerationSequence", value.nativeGenerationSequence)
        .put("nativeStageMask", value.nativeStageMask)
        .put("nativeDetailStageMask", value.nativeDetailStageMask)
        .put("nativeResultJson", value.nativeResultJson)
        .toString()

    fun parseResult(raw: String): SdxlImagePhaseResult {
        val json = JSONObject(raw)
        require(json.strictInt("version") == VERSION) { "Unsupported SDXL phase protocol version." }
        return SdxlImagePhaseResult(
            requestId = json.requireString("requestId"),
            phase = SdxlImagePhase.fromWire(json.requireString("phase")),
            workerPid = json.optInt("workerPid", -1),
            runtimeProfile = json.requireString("runtimeProfile"),
            artifactPath = json.requireString("artifactPath"),
            metadataPath = json.optString("metadataPath"),
            nativeGenerationSequence = json.strictLong("nativeGenerationSequence"),
            nativeStageMask = json.strictLong("nativeStageMask"),
            nativeDetailStageMask = json.strictLong("nativeDetailStageMask"),
            nativeResultJson = json.requireString("nativeResultJson")
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
        stageTrace = json.optJSONArray("stageTrace").toStringList()
    )

    private fun JSONObject.requireString(name: String): String =
        optString(name).takeIf(String::isNotBlank) ?: error("Missing $name.")
}

internal fun sdxlTransportProfile(htpArchVersion: Int): String =
    if (htpArchVersion > 0) "V$htpArchVersion" else "AUTO"

internal fun validateSdxlNativeTransport(
    phase: SdxlImagePhase,
    expectedHtpArch: Int,
    nativeResult: JSONObject
): Int {
    val selectedHtpArch = nativeResult.optInt("htpArchVersion")
    require(selectedHtpArch > 0) {
        "SDXL ${phase.wireName} native runtime did not report a physical HTP transport."
    }
    require(expectedHtpArch <= 0 || selectedHtpArch == expectedHtpArch) {
        "SDXL ${phase.wireName} selected HTP V$selectedHtpArch but expected transport V$expectedHtpArch."
    }
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
    val nativeEffectiveJson: String,
    val unetNativeGenerationSequence: Long,
    val unetNativeStageMask: Long,
    val unetNativeDetailStageMask: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
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
        .put("nativeEffective", JSONObject(nativeEffectiveJson))
        .put("unetNativeGenerationSequence", unetNativeGenerationSequence)
        .put("unetNativeStageMask", unetNativeStageMask)
        .put("unetNativeDetailStageMask", unetNativeDetailStageMask)

    companion object {
        fun fromJson(json: JSONObject): SdxlLatentMetadata {
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
                nativeEffectiveJson = json.getJSONObject("nativeEffective").toString(),
                unetNativeGenerationSequence = json.strictLong("unetNativeGenerationSequence"),
                unetNativeStageMask = json.strictLong("unetNativeStageMask"),
                unetNativeDetailStageMask = json.strictLong("unetNativeDetailStageMask")
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
        val shape = nativeResult.getJSONArray("latentShape").toPositiveIntList()
        val dtype = nativeResult.getString("latentDtype")
        require(dtype == "float32-le") { "Unsupported latent dtype: $dtype" }
        require(shape.fold(1L) { total, value -> Math.multiplyExact(total, value.toLong()) } * 4L == latentFile.length()) {
            "Latent shape does not match byte size."
        }
        val nativeEffective = contract.requireNativeEffective(nativeResult, SdxlImagePhase.UNET)
        require(nativeResult.strictInt("timetableCount") == nativeEffective.timetableCount) {
            "UNet timetable evidence conflicts with nativeEffective."
        }
        require(nativeResult.strictInt("unetExecutionCount") == nativeEffective.unetExecutionCount) {
            "UNet execution evidence conflicts with nativeEffective."
        }
        val metadata = SdxlLatentMetadata(
            requestId = requestId,
            producerPid = producerPid,
            runtimeProfile = nativeResult.getString("runtimeProfile"),
            htpArchVersion = nativeResult.getInt("htpArchVersion"),
            latentPath = latentFile.canonicalPath,
            dtype = dtype,
            shape = shape,
            byteSize = latentFile.length(),
            sha256 = sha256(latentFile),
            nativeEffectiveJson = nativeEffective.toSdxlNativeEffectiveJson()
                .put("pixelRange", contract.pixelRange.name)
                .toString(),
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
        require(metadata.htpArchVersion == expectedProducerArch) { "Latent producer profile mismatch." }
        require(metadata.dtype == "float32-le") { "Latent dtype mismatch." }
        require(File(metadata.latentPath).canonicalFile == latentFile.canonicalFile) { "Latent path mismatch." }
        require(latentFile.isFile && latentFile.length() == metadata.byteSize) { "Latent byte size mismatch." }
        val elements = metadata.shape.fold(1L) { total, value -> Math.multiplyExact(total, value.toLong()) }
        require(elements * 4L == metadata.byteSize) { "Latent shape metadata is invalid." }
        require(sha256(latentFile).equals(metadata.sha256, ignoreCase = true)) { "Latent SHA-256 mismatch." }
        contract.requireNativeEffective(
            JSONObject().put("nativeEffective", JSONObject(metadata.nativeEffectiveJson)),
            SdxlImagePhase.UNET
        )
        require(metadata.unetNativeGenerationSequence > 0L) { "Latent native sequence is invalid." }
        require(metadata.unetNativeStageMask > 0L) { "Latent native stage mask is invalid." }
        require(metadata.unetNativeDetailStageMask >= 0L) { "Latent native detail stage mask is invalid." }
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
