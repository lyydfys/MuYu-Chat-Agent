package com.muyuchat.mca

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

data class QnnImageDeviceIdentity(
    val soc: String,
    val abi: String,
    val buildFingerprint: String
)

data class QnnImageRuntimeIdentity(
    val app: String,
    val nativeRuntime: String,
    val apkSha256: String? = null
) {
    init {
        require(apkSha256 == null || QNN_EVIDENCE_SHA256.matches(apkSha256))
    }
}

enum class QnnImageEvidenceLevel {
    LEGACY_UNSCOPED,
    GRAPH_SMOKE,
    PRODUCT_IMAGE_SMOKE,
    FULL_IMAGE_REGRESSION
}

enum class QnnImageEvidenceEntryPoint {
    MAIN_ACTIVITY,
    AUTHENTICATED_LOCAL_API,
    DIAGNOSTIC
}

data class QnnImageRunParameters(val width: Int, val height: Int, val steps: Int, val seed: Long) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
        require(steps > 0 && seed >= 0L)
    }

    fun toJson(): JSONObject = JSONObject().put("width", width).put("height", height)
        .put("steps", steps).put("seed", seed)

    companion object {
        fun fromJson(json: JSONObject): QnnImageRunParameters = QnnImageRunParameters(
            json.exactEvidenceInt("width"), json.exactEvidenceInt("height"),
            json.exactEvidenceInt("steps"), json.exactEvidenceLong("seed")
        )
    }
}

/** A record of one completed invocation. It is never a device admission decision. */
data class QnnImageExecutionEvidence(
    val level: QnnImageEvidenceLevel,
    val entryPoint: QnnImageEvidenceEntryPoint,
    val requestId: String,
    val attemptId: String,
    val nativeRequestId: String,
    val nativeGenerationSequence: Long,
    val nativeStartedAtMonotonicMs: Long,
    val recordedAtEpochMs: Long,
    val parameters: QnnImageRunParameters? = null,
    val outputSha256: String? = null,
    val outputBytes: Long? = null,
    val outputAtomicCommit: Boolean = false
) {
    init {
        require(level != QnnImageEvidenceLevel.LEGACY_UNSCOPED)
        require(listOf(requestId, attemptId, nativeRequestId).all { it.isNotBlank() && it.length <= 256 })
        require(nativeGenerationSequence > 0L && nativeStartedAtMonotonicMs > 0L && recordedAtEpochMs > 0L)
        if (level == QnnImageEvidenceLevel.GRAPH_SMOKE) {
            require(parameters == null && outputSha256 == null && outputBytes == null && !outputAtomicCommit)
        } else {
            require(parameters != null && outputSha256 != null && QNN_EVIDENCE_SHA256.matches(outputSha256))
            require(outputBytes != null && outputBytes > 0L && outputAtomicCommit)
        }
        if (level == QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION) {
            require(entryPoint != QnnImageEvidenceEntryPoint.DIAGNOSTIC)
            val executed = requireNotNull(parameters)
            require(executed.width == 512 && executed.height == 512 && executed.steps == 20)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("level", level.name).put("entryPoint", entryPoint.name)
        .put("requestId", requestId).put("attemptId", attemptId).put("nativeRequestId", nativeRequestId)
        .put("nativeGenerationSequence", nativeGenerationSequence)
        .put("nativeStartedAtMonotonicMs", nativeStartedAtMonotonicMs)
        .put("recordedAtEpochMs", recordedAtEpochMs)
        .put("parameters", parameters?.toJson() ?: JSONObject.NULL)
        .put("outputSha256", outputSha256 ?: JSONObject.NULL)
        .put("outputBytes", outputBytes ?: JSONObject.NULL)
        .put("outputAtomicCommit", outputAtomicCommit)

    companion object {
        fun fromJson(json: JSONObject): QnnImageExecutionEvidence = QnnImageExecutionEvidence(
            level = QnnImageEvidenceLevel.valueOf(json.getString("level")),
            entryPoint = QnnImageEvidenceEntryPoint.valueOf(json.getString("entryPoint")),
            requestId = json.getString("requestId"), attemptId = json.getString("attemptId"),
            nativeRequestId = json.getString("nativeRequestId"),
            nativeGenerationSequence = json.exactEvidenceLong("nativeGenerationSequence"),
            nativeStartedAtMonotonicMs = json.exactEvidenceLong("nativeStartedAtMonotonicMs"),
            recordedAtEpochMs = json.exactEvidenceLong("recordedAtEpochMs"),
            parameters = json.optJSONObject("parameters")?.let(QnnImageRunParameters::fromJson),
            outputSha256 = json.optionalString("outputSha256"),
            outputBytes = if (json.has("outputBytes") && !json.isNull("outputBytes")) json.exactEvidenceLong("outputBytes") else null,
            outputAtomicCommit = json.opt("outputAtomicCommit") == true
        )

        fun fromNativeImageExecution(
            level: QnnImageEvidenceLevel,
            entryPoint: QnnImageEvidenceEntryPoint,
            requestId: String,
            attemptId: String,
            expected: QnnImageRunParameters,
            executionMetadataJson: String,
            output: ByteArray,
            recordedAtEpochMs: Long = System.currentTimeMillis()
        ): QnnImageExecutionEvidence {
            require(level == QnnImageEvidenceLevel.PRODUCT_IMAGE_SMOKE || level == QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION)
            val execution = JSONObject(executionMetadataJson)
            require(execution.opt("cancelled") != true && execution.opt("cancelRequested") != true) {
                "Cancelled QNN execution cannot become completed image evidence."
            }
            require(execution.opt("npuActive") == true && execution.opt("qnnGraphExecution") == true &&
                execution.opt("nativeExecution") == true && execution.opt("fallback") == false) {
                "QNN evidence requires actual native HTP graph execution without fallback."
            }
            require(QnnImageRunParameters.fromJson(execution) == expected) {
                "QNN executed parameters differ from the frozen verification request."
            }
            val actualHash = MessageDigest.getInstance("SHA-256").digest(output).toHexString()
            require(output.isNotEmpty() && execution.getString("outputSha256") == actualHash &&
                execution.exactEvidenceLong("outputBytes") == output.size.toLong() &&
                execution.opt("outputAtomicCommit") == true) {
                "QNN evidence does not match the atomically committed native output."
            }
            return QnnImageExecutionEvidence(
                level = level, entryPoint = entryPoint, requestId = requestId, attemptId = attemptId,
                nativeRequestId = execution.getString("nativeRequestId"),
                nativeGenerationSequence = execution.exactEvidenceLong("nativeGenerationSequence"),
                nativeStartedAtMonotonicMs = execution.exactEvidenceLong("nativeStartedAtMonotonicMs"),
                recordedAtEpochMs = recordedAtEpochMs, parameters = expected,
                outputSha256 = actualHash, outputBytes = output.size.toLong(), outputAtomicCommit = true
            )
        }
    }
}

private val QNN_EVIDENCE_SHA256 = Regex("[0-9a-f]{64}")

internal fun qnnImageNativeRuntimeFingerprint(runtimeIdentityJson: String): String =
    "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(runtimeIdentityJson.toByteArray(StandardCharsets.UTF_8)).toHexString()

enum class QnnImageBundleIdentityStatus {
    AVAILABLE,
    MISSING,
    NOT_DIRECTORY
}

data class QnnImageBundleIdentity(
    val status: QnnImageBundleIdentityStatus,
    val sha256: String? = null
) {
    init {
        if (status == QnnImageBundleIdentityStatus.AVAILABLE) {
            require(!sha256.isNullOrBlank()) {
                "An available bundle identity requires a SHA-256."
            }
        } else {
            require(sha256 == null) {
                "An unavailable bundle identity must not have a SHA-256."
            }
        }
    }

    companion object {
        fun fromDirectory(directory: File?): QnnImageBundleIdentity {
            when {
                directory == null || !directory.exists() -> {
                    return QnnImageBundleIdentity(QnnImageBundleIdentityStatus.MISSING)
                }
                !directory.isDirectory -> {
                    return QnnImageBundleIdentity(QnnImageBundleIdentityStatus.NOT_DIRECTORY)
                }
            }

            val rootPath = directory.toPath().toAbsolutePath().normalize()
            val entries = directory.walkTopDown()
                .filter { it.isFile }
                .map { file ->
                    BundleFileMetadata(
                        relativePath = rootPath.relativize(file.toPath().toAbsolutePath().normalize())
                            .toString()
                            .replace(File.separatorChar, '/'),
                        length = file.length(),
                        lastModified = file.lastModified(),
                        sha256 = runCatching { file.sha256Contents() }.getOrNull()
                    )
                }
                .sortedBy(BundleFileMetadata::relativePath)
                .toList()

            val digest = MessageDigest.getInstance("SHA-256")
            digest.updateField("mca.qnn.image.bundle.metadata.v1")
            entries.forEach { entry ->
                digest.updateField(entry.relativePath)
                digest.updateLong(entry.length)
                digest.updateLong(entry.lastModified)
                digest.updateField(entry.sha256.orEmpty())
            }
            return QnnImageBundleIdentity(
                status = QnnImageBundleIdentityStatus.AVAILABLE,
                sha256 = digest.digest().toHexString()
            )
        }
    }
}

data class QnnImageVerificationStamp(
    val schema: String = SCHEMA,
    val version: Int = VERSION,
    val device: QnnImageDeviceIdentity,
    val runtime: QnnImageRuntimeIdentity,
    val bundle: QnnImageBundleIdentity,
    val execution: QnnImageExecutionEvidence? = null
) {
    init {
        if (execution?.level == QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION) {
            require(schema == SCHEMA && version == VERSION && runtime.apkSha256 != null &&
                bundle.status == QnnImageBundleIdentityStatus.AVAILABLE) {
                "Full QNN regression evidence requires the current schema, APK SHA-256 and available bundle identity."
            }
        }
    }

    val evidenceLevel: QnnImageEvidenceLevel
        get() = if (schema == SCHEMA && version == VERSION) {
            execution?.level ?: QnnImageEvidenceLevel.LEGACY_UNSCOPED
        } else QnnImageEvidenceLevel.LEGACY_UNSCOPED

    fun matchesCurrent(
        device: QnnImageDeviceIdentity,
        runtime: QnnImageRuntimeIdentity,
        bundleDirectory: File?
    ): Boolean =
        schema == SCHEMA &&
            version == VERSION &&
            this.device == device &&
            this.runtime == runtime &&
            bundle == QnnImageBundleIdentity.fromDirectory(bundleDirectory)

    fun toJson(): JSONObject =
        JSONObject()
            .put("schema", schema)
            .put("version", version)
            .put(
                "device",
                JSONObject()
                    .put("soc", device.soc)
                    .put("abi", device.abi)
                    .put("buildFingerprint", device.buildFingerprint)
            )
            .put(
                "runtime",
                JSONObject()
                    .put("app", runtime.app)
                    .put("nativeRuntime", runtime.nativeRuntime)
                    .put("apkSha256", runtime.apkSha256 ?: JSONObject.NULL)
            )
            .put("execution", execution?.toJson() ?: JSONObject.NULL)
            .put(
                "bundle",
                JSONObject()
                    .put("status", bundle.status.name)
                    .also { json -> bundle.sha256?.let { json.put("sha256", it) } }
            )

    fun toJsonString(): String = toJson().toString()

    companion object {
        const val SCHEMA = "mca.qnn.image.verification_stamp"
        const val VERSION = 3

        fun create(
            device: QnnImageDeviceIdentity,
            runtime: QnnImageRuntimeIdentity,
            bundleDirectory: File?,
            execution: QnnImageExecutionEvidence? = null
        ): QnnImageVerificationStamp =
            QnnImageVerificationStamp(
                device = device,
                runtime = runtime,
                bundle = QnnImageBundleIdentity.fromDirectory(bundleDirectory),
                execution = execution
            )

        fun fromJson(json: JSONObject): QnnImageVerificationStamp {
            val device = json.getJSONObject("device")
            val runtime = json.getJSONObject("runtime")
            val bundle = json.getJSONObject("bundle")
            val version = json.getInt("version")
            return QnnImageVerificationStamp(
                schema = json.getString("schema"),
                version = version,
                device = QnnImageDeviceIdentity(
                    soc = device.getString("soc"),
                    abi = device.getString("abi"),
                    buildFingerprint = device.getString("buildFingerprint")
                ),
                runtime = QnnImageRuntimeIdentity(
                    app = runtime.getString("app"),
                    nativeRuntime = runtime.getString("nativeRuntime"),
                    apkSha256 = runtime.optionalString("apkSha256")
                ),
                bundle = QnnImageBundleIdentity(
                    status = QnnImageBundleIdentityStatus.valueOf(bundle.getString("status")),
                    sha256 = bundle.optionalString("sha256")
                ),
                execution = if (version == VERSION) json.optJSONObject("execution")?.let(QnnImageExecutionEvidence::fromJson) else null
            )
        }

        fun fromJson(raw: String): QnnImageVerificationStamp = fromJson(JSONObject(raw))
    }
}

private data class BundleFileMetadata(
    val relativePath: String,
    val length: Long,
    val lastModified: Long,
    val sha256: String?
)

private fun JSONObject.optionalString(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null

private fun JSONObject.exactEvidenceLong(name: String): Long {
    val value = opt(name)
    require(value is Byte || value is Short || value is Int || value is Long) {
        "QNN evidence field $name must be an integer."
    }
    return (value as Number).toLong()
}

private fun JSONObject.exactEvidenceInt(name: String): Int = exactEvidenceLong(name).also {
    require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "QNN evidence field $name overflows an integer." }
}.toInt()

private fun MessageDigest.updateField(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    updateLong(bytes.size.toLong())
    update(bytes)
}

private fun MessageDigest.updateLong(value: Long) {
    for (shift in 56 downTo 0 step 8) {
        update((value ushr shift).toByte())
    }
}

private fun File.sha256Contents(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
