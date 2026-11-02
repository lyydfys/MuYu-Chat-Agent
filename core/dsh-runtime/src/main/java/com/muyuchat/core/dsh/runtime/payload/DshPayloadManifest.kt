package com.muyuchat.core.dsh.runtime.payload

/**
 * The signed (sha256-pinned) description of the official DSH payload packaged in the APK.
 *
 * The manifest is produced by `scripts/dsh/stage.ps1` from the reference release APK and is
 * packaged next to `dsh/payload.zip`. The runtime verifies the archive against
 * [archiveSha256] while extracting, then verifies [criticalFiles] inside the extracted tree.
 * A mismatch fails closed; the manifest is never trusted without a hash match.
 */
data class DshPayloadManifest(
    val manifestVersion: Int,
    val payloadVersion: String,
    val archiveAsset: String,
    val archiveSize: Long,
    val archiveSha256: String,
    val nodeLibraryName: String,
    val nodeSize: Long,
    val nodeSha256: String,
    val kernelEntry: String,
    val runtimeLibraryDir: String,
    val fileCount: Long,
    val criticalFiles: List<DshPayloadCriticalFile>,
    val source: DshPayloadSourceInfo,
    val licenses: List<DshPayloadLicense>
) {
    init {
        require(manifestVersion == MANIFEST_VERSION) {
            "dsh manifest: unsupported manifestVersion $manifestVersion"
        }
        require(payloadVersion.isNotBlank()) { "dsh manifest: payloadVersion is blank" }
        require(archiveAsset.isNotBlank()) { "dsh manifest: archiveAsset is blank" }
        require(archiveSize > 0) { "dsh manifest: archiveSize must be positive" }
        require(archiveSha256.isSha256()) { "dsh manifest: archiveSha256 is not a sha256" }
        require(nodeLibraryName.startsWith("lib") && nodeLibraryName.endsWith(".so")) {
            "dsh manifest: nodeLibraryName must be lib*.so"
        }
        require(nodeSize > 0) { "dsh manifest: nodeSize must be positive" }
        require(nodeSha256.isSha256()) { "dsh manifest: nodeSha256 is not a sha256" }
        require(kernelEntry.endsWith(".js")) { "dsh manifest: kernelEntry must be a js entry" }
        require(runtimeLibraryDir.isNotBlank()) { "dsh manifest: runtimeLibraryDir is blank" }
        require(fileCount > 0) { "dsh manifest: fileCount must be positive" }
        require(criticalFiles.isNotEmpty()) { "dsh manifest: criticalFiles must not be empty" }
        criticalFiles.forEach { it.validate() }
    }

    fun toJson(): String = DshJson.write(toTree())

    fun toTree(): Map<String, Any?> = linkedMapOf(
        "manifestVersion" to manifestVersion,
        "payloadVersion" to payloadVersion,
        "archive" to linkedMapOf(
            "asset" to archiveAsset,
            "size" to archiveSize,
            "sha256" to archiveSha256
        ),
        "node" to linkedMapOf(
            "jniLib" to nodeLibraryName,
            "size" to nodeSize,
            "sha256" to nodeSha256
        ),
        "kernelEntry" to kernelEntry,
        "runtimeLibraryDir" to runtimeLibraryDir,
        "fileCount" to fileCount,
        "criticalFiles" to criticalFiles.map { it.toTree() },
        "source" to source.toTree(),
        "licenses" to licenses.map { it.toTree() }
    )

    companion object {
        const val MANIFEST_VERSION = 1
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        fun String.isSha256(): Boolean = SHA256.matches(this)

        fun parse(json: String): DshPayloadManifest {
            val root = DshJson.asObject(DshJson.parse(json), "manifest")
            val archive = DshJson.asObject(root["archive"], "manifest.archive")
            val node = DshJson.asObject(root["node"], "manifest.node")
            val source = DshJson.asObject(root["source"], "manifest.source")
            val critical = DshJson.asArray(root["criticalFiles"], "manifest.criticalFiles").map { item ->
                val obj = DshJson.asObject(item, "manifest.criticalFiles[]")
                DshPayloadCriticalFile(
                    path = DshJson.asString(obj, "path", "criticalFile"),
                    size = DshJson.asLong(obj, "size", "criticalFile"),
                    sha256 = DshJson.asString(obj, "sha256", "criticalFile")
                )
            }
            val licenses = DshJson.asArray(root["licenses"], "manifest.licenses").map { item ->
                val obj = DshJson.asObject(item, "manifest.licenses[]")
                DshPayloadLicense(
                    name = DshJson.asString(obj, "name", "license"),
                    license = DshJson.asString(obj, "license", "license"),
                    source = DshJson.optString(obj, "source")
                )
            }
            return DshPayloadManifest(
                manifestVersion = DshJson.asInt(root, "manifestVersion", "manifest"),
                payloadVersion = DshJson.asString(root, "payloadVersion", "manifest"),
                archiveAsset = DshJson.asString(archive, "asset", "manifest.archive"),
                archiveSize = DshJson.asLong(archive, "size", "manifest.archive"),
                archiveSha256 = DshJson.asString(archive, "sha256", "manifest.archive"),
                nodeLibraryName = DshJson.asString(node, "jniLib", "manifest.node"),
                nodeSize = DshJson.asLong(node, "size", "manifest.node"),
                nodeSha256 = DshJson.asString(node, "sha256", "manifest.node"),
                kernelEntry = DshJson.asString(root, "kernelEntry", "manifest"),
                runtimeLibraryDir = DshJson.asString(root, "runtimeLibraryDir", "manifest"),
                fileCount = DshJson.asLong(root, "fileCount", "manifest"),
                criticalFiles = critical,
                source = DshPayloadSourceInfo(
                    officialCommit = DshJson.optString(source, "officialCommit"),
                    referenceRepo = DshJson.optString(source, "referenceRepo"),
                    referenceVersion = DshJson.optString(source, "referenceVersion"),
                    referenceCommit = DshJson.optString(source, "referenceCommit")
                ),
                licenses = licenses
            )
        }
    }
}

/** One payload file whose content is verified before the host may start Node. */
data class DshPayloadCriticalFile(
    val path: String,
    val size: Long,
    val sha256: String
) {
    fun validate() {
        require(path.isNotBlank() && !path.startsWith("/") && !path.contains("..")) {
            "dsh manifest: critical file path must be a relative safe path: $path"
        }
        require(size >= 0) { "dsh manifest: critical file size negative: $path" }
        require(SHA256.matches(sha256)) {
            "dsh manifest: critical file sha256 invalid: $path"
        }
    }

    fun toTree(): Map<String, Any?> = linkedMapOf("path" to path, "size" to size, "sha256" to sha256)

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/** Upstream provenance carried into diagnostics and the third_party notice. */
data class DshPayloadSourceInfo(
    val officialCommit: String?,
    val referenceRepo: String?,
    val referenceVersion: String?,
    val referenceCommit: String? = null
) {
    fun toTree(): Map<String, Any?> = linkedMapOf(
        "officialCommit" to officialCommit,
        "referenceRepo" to referenceRepo,
        "referenceVersion" to referenceVersion,
        "referenceCommit" to referenceCommit
    )
}

/** One license row for the payload dependency closure. */
data class DshPayloadLicense(
    val name: String,
    val license: String,
    val source: String? = null
) {
    fun toTree(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "license" to license,
        "source" to source
    )
}

/**
 * Version+hash marker written beside the verified payload tree. It proves which manifest and
 * archive produced the bytes currently on disk without re-extracting a ~hundreds-of-MB tree
 * on every start. A missing or mismatched marker triggers re-installation.
 */
data class DshPayloadMarker(
    val manifestVersion: Int,
    val payloadVersion: String,
    val archiveSha256: String,
    val fileCount: Long,
    val installedAtMillis: Long
) {
    fun matches(manifest: DshPayloadManifest): Boolean =
        manifestVersion == manifest.manifestVersion &&
            payloadVersion == manifest.payloadVersion &&
            archiveSha256 == manifest.archiveSha256 &&
            fileCount == manifest.fileCount

    fun toJson(): String = DshJson.write(
        linkedMapOf(
            "manifestVersion" to manifestVersion,
            "payloadVersion" to payloadVersion,
            "archiveSha256" to archiveSha256,
            "fileCount" to fileCount,
            "installedAtMillis" to installedAtMillis
        )
    )

    companion object {
        fun parse(json: String): DshPayloadMarker {
            val root = DshJson.asObject(DshJson.parse(json), "marker")
            return DshPayloadMarker(
                manifestVersion = DshJson.asInt(root, "manifestVersion", "marker"),
                payloadVersion = DshJson.asString(root, "payloadVersion", "marker"),
                archiveSha256 = DshJson.asString(root, "archiveSha256", "marker"),
                fileCount = DshJson.asLong(root, "fileCount", "marker"),
                installedAtMillis = DshJson.asLong(root, "installedAtMillis", "marker")
            )
        }
    }
}
