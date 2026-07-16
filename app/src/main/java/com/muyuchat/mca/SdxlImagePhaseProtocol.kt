package com.muyuchat.mca

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
    val nativeResultJson: String
)

internal data class SdxlImagePhaseError(
    val requestId: String,
    val phase: SdxlImagePhase,
    val workerPid: Int,
    val code: String,
    val message: String
)

internal object SdxlImagePhaseProtocol {
    private const val VERSION = 1

    fun request(value: SdxlImagePhaseRequest): String = JSONObject()
        .put("version", VERSION)
        .put("requestId", value.requestId)
        .put("phase", value.phase.wireName)
        .put("expectedHtpArch", value.expectedHtpArch)
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
        val phase = SdxlImagePhase.fromWire(json.requireString("phase"))
        val expectedHtpArch = json.getInt("expectedHtpArch").also { arch ->
            require(arch >= 0) { "expectedHtpArch must be non-negative." }
            require(phase == SdxlImagePhase.UNET || arch > 0) {
                "VAE expectedHtpArch must bind the UNet transport profile."
            }
        }
        return SdxlImagePhaseRequest(
            requestId = json.requireString("requestId"),
            phase = phase,
            expectedHtpArch = expectedHtpArch,
            bundleRoot = json.requireString("bundleRoot"),
            runtimeDirsJson = json.requireString("runtimeDirsJson"),
            paramsJson = json.requireString("paramsJson"),
            embeddingsPath = json.optString("embeddingsPath"),
            latentPath = json.requireString("latentPath"),
            metadataPath = json.requireString("metadataPath"),
            outputPath = json.optString("outputPath"),
            journalPath = json.requireString("journalPath")
        )
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
        .put("nativeResultJson", value.nativeResultJson)
        .toString()

    fun parseResult(raw: String): SdxlImagePhaseResult {
        val json = JSONObject(raw)
        return SdxlImagePhaseResult(
            requestId = json.requireString("requestId"),
            phase = SdxlImagePhase.fromWire(json.requireString("phase")),
            workerPid = json.optInt("workerPid", -1),
            runtimeProfile = json.requireString("runtimeProfile"),
            artifactPath = json.requireString("artifactPath"),
            metadataPath = json.optString("metadataPath"),
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
    val sha256: String
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
                sha256 = json.getString("sha256")
            )
        }
    }
}

internal object SdxlLatentArtifact {
    fun publishMetadata(
        requestId: String,
        producerPid: Int,
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
        val metadata = SdxlLatentMetadata(
            requestId = requestId,
            producerPid = producerPid,
            runtimeProfile = nativeResult.getString("runtimeProfile"),
            htpArchVersion = nativeResult.getInt("htpArchVersion"),
            latentPath = latentFile.canonicalPath,
            dtype = dtype,
            shape = shape,
            byteSize = latentFile.length(),
            sha256 = sha256(latentFile)
        )
        atomicWrite(metadataFile, strictJsonForPersistence(metadata.toJson()))
        return metadata
    }

    fun validate(
        requestId: String,
        latentFile: File,
        metadataFile: File,
        expectedProducerArch: Int
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
        check(temporary.renameTo(file)) { "Unable to atomically publish latent metadata." }
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
