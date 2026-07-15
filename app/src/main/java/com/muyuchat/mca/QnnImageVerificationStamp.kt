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
    val nativeRuntime: String
)

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
                        lastModified = file.lastModified()
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
    val bundle: QnnImageBundleIdentity
) {
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
            )
            .put(
                "bundle",
                JSONObject()
                    .put("status", bundle.status.name)
                    .also { json -> bundle.sha256?.let { json.put("sha256", it) } }
            )

    fun toJsonString(): String = toJson().toString()

    companion object {
        const val SCHEMA = "mca.qnn.image.verification_stamp"
        const val VERSION = 2

        fun create(
            device: QnnImageDeviceIdentity,
            runtime: QnnImageRuntimeIdentity,
            bundleDirectory: File?
        ): QnnImageVerificationStamp =
            QnnImageVerificationStamp(
                device = device,
                runtime = runtime,
                bundle = QnnImageBundleIdentity.fromDirectory(bundleDirectory)
            )

        fun fromJson(json: JSONObject): QnnImageVerificationStamp {
            val device = json.getJSONObject("device")
            val runtime = json.getJSONObject("runtime")
            val bundle = json.getJSONObject("bundle")
            return QnnImageVerificationStamp(
                schema = json.getString("schema"),
                version = json.getInt("version"),
                device = QnnImageDeviceIdentity(
                    soc = device.getString("soc"),
                    abi = device.getString("abi"),
                    buildFingerprint = device.getString("buildFingerprint")
                ),
                runtime = QnnImageRuntimeIdentity(
                    app = runtime.getString("app"),
                    nativeRuntime = runtime.getString("nativeRuntime")
                ),
                bundle = QnnImageBundleIdentity(
                    status = QnnImageBundleIdentityStatus.valueOf(bundle.getString("status")),
                    sha256 = bundle.optionalString("sha256")
                )
            )
        }

        fun fromJson(raw: String): QnnImageVerificationStamp = fromJson(JSONObject(raw))
    }
}

private data class BundleFileMetadata(
    val relativePath: String,
    val length: Long,
    val lastModified: Long
)

private fun JSONObject.optionalString(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null

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

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
