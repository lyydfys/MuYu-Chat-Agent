package com.muyuchat.mca

import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal fun sdxlTransportProfile(htpArchVersion: Int): String {
    require(htpArchVersion > 0) { "SDXL transport profile requires a physical HTP architecture." }
    return "V$htpArchVersion"
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

internal fun JSONArray.toPositiveIntList(): List<Int> = buildList {
    for (index in 0 until length()) {
        val value = getInt(index)
        require(value > 0) { "Latent shape dimensions must be positive." }
        add(value)
    }
}

internal val SDXL_SHA256 = Regex("^[0-9a-f]{64}$")

internal fun JSONObject.strictValue(name: String): Any {
    require(has(name) && !isNull(name)) { "Missing required SDXL execution field: $name" }
    return get(name)
}

internal fun JSONObject.strictString(name: String): String =
    (strictValue(name) as? String)?.takeIf(String::isNotBlank)
        ?: error("SDXL execution field $name must be a non-blank string.")

internal fun JSONObject.strictBoolean(name: String): Boolean =
    strictValue(name) as? Boolean
        ?: error("SDXL execution field $name must be a boolean.")

internal fun JSONObject.strictInt(name: String): Int = try {
    BigDecimal((strictValue(name) as? Number)?.toString()
        ?: error("SDXL execution field $name must be numeric.")).intValueExact()
} catch (error: ArithmeticException) {
    throw IllegalArgumentException("SDXL execution field $name must be an exact int32.", error)
} catch (error: NumberFormatException) {
    throw IllegalArgumentException("SDXL execution field $name must be an exact int32.", error)
}

internal fun JSONObject.strictLong(name: String): Long = try {
    BigDecimal((strictValue(name) as? Number)?.toString()
        ?: error("SDXL execution field $name must be numeric.")).longValueExact()
} catch (error: ArithmeticException) {
    throw IllegalArgumentException("SDXL execution field $name must be an exact int64.", error)
} catch (error: NumberFormatException) {
    throw IllegalArgumentException("SDXL execution field $name must be an exact int64.", error)
}

internal fun JSONObject.strictDouble(name: String): Double =
    (strictValue(name) as? Number)?.toDouble()?.takeIf(Double::isFinite)
        ?: error("SDXL execution field $name must be finite numeric.")

internal fun <T : Enum<T>> JSONObject.strictEnum(name: String, entries: List<T>): T {
    val value = strictString(name)
    return entries.firstOrNull { it.name == value }
        ?: error("Unknown SDXL execution enum $name=$value")
}
