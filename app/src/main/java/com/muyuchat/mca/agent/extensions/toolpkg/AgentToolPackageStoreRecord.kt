package com.muyuchat.mca.agent.extensions.toolpkg

import com.muyuchat.core.agent.contract.AgentToolPackageToolRef

/**
 * Durable index record for one previewed package.
 *
 * The record is written twice: in the atomically-replaced `index.json` and in the snapshot's
 * `record.json`. Both are canonical JSON so `verify()` can compare them without depending on map
 * order. A record contains no manifest values beyond bounded metadata and resource hashes; package
 * content itself lives only in the private snapshot.
 */
internal data class AgentToolPackageStoreRecord(
    val packageId: String,
    val packageVersion: String,
    val displayName: String,
    val manifestSha256: String,
    val contentSha256: String,
    val archiveSha256: String,
    val sourceUri: String,
    val archiveSizeBytes: Long,
    val storedAtMillis: Long,
    val snapshotId: String,
    val resources: List<AgentToolPackageStoredResource>
)

internal fun toolPackageSnapshotId(packageId: String, contentSha256: String): String =
    "$packageId-${contentSha256.take(16)}"

/** Canonical JSON codec for the preview-store index and per-snapshot records. */
internal object AgentToolPackageStoreCodec {
    const val STORE_SCHEMA_VERSION: Long = 1L

    fun encodeIndex(records: Collection<AgentToolPackageStoreRecord>): ByteArray {
        val packages = records.sortedBy { it.packageId }.map { encodeRecord(it) }
        val root = ToolPackageJson.objectOf(
            "storeSchemaVersion" to ToolPackageJson.integer(STORE_SCHEMA_VERSION),
            "packages" to ToolPackageJson.arrayOf(packages)
        )
        val bytes = ToolPackageJson.canonical(root).toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > AgentToolPackageLimits.MAX_STORE_INDEX_BYTES) {
            failStore("the preview store index would exceed its size limit")
        }
        return bytes
    }

    fun parseIndex(bytes: ByteArray): List<AgentToolPackageStoreRecord> {
        if (bytes.size.toLong() > AgentToolPackageLimits.MAX_STORE_INDEX_BYTES) {
            failStore("the preview store index exceeds its size limit")
        }
        val rootValue = try {
            ToolPackageJson.parseUtf8(bytes)
        } catch (_: Exception) {
            failStore("the preview store index is corrupt")
        }
        val root = rootValue as? ToolPackageJsonValue.ObjectValue
            ?: failStore("the preview store index is corrupt")
        checkKeys(root, INDEX_FIELDS)
        if (root.requireInteger("storeSchemaVersion") != STORE_SCHEMA_VERSION) {
            failStore("the preview store index schema version is unsupported")
        }
        val packages = root.fields["packages"] as? ToolPackageJsonValue.ArrayValue
            ?: failStore("the preview store index is missing its package list")
        val loaded = LinkedHashMap<String, AgentToolPackageStoreRecord>()
        for (item in packages.items) {
            val record = try {
                parseRecord(item)
            } catch (_: Exception) {
                failStore("a preview store index entry is corrupt")
            }
            if (loaded.put(record.packageId, record) != null) {
                failStore("the preview store index repeats a package id")
            }
        }
        return loaded.values.toList()
    }

    fun encodeRecord(record: AgentToolPackageStoreRecord): ToolPackageJsonValue.ObjectValue =
        ToolPackageJson.objectOf(
            "packageId" to ToolPackageJson.string(record.packageId),
            "packageVersion" to ToolPackageJson.string(record.packageVersion),
            "displayName" to ToolPackageJson.string(record.displayName),
            "manifestSha256" to ToolPackageJson.string(record.manifestSha256),
            "contentSha256" to ToolPackageJson.string(record.contentSha256),
            "archiveSha256" to ToolPackageJson.string(record.archiveSha256),
            "sourceUri" to ToolPackageJson.string(record.sourceUri),
            "archiveSizeBytes" to ToolPackageJson.integer(record.archiveSizeBytes),
            "storedAtMillis" to ToolPackageJson.integer(record.storedAtMillis),
            "snapshotId" to ToolPackageJson.string(record.snapshotId),
            "resources" to ToolPackageJson.arrayOf(
                record.resources.map { resource ->
                    ToolPackageJson.objectOf(
                        "path" to ToolPackageJson.string(resource.path),
                        "sizeBytes" to ToolPackageJson.integer(resource.sizeBytes),
                        "sha256" to ToolPackageJson.string(resource.sha256)
                    )
                }
            )
        )

    fun parseRecord(value: ToolPackageJsonValue): AgentToolPackageStoreRecord {
        val obj = value as? ToolPackageJsonValue.ObjectValue
            ?: failStore("a store record is not an object")
        checkKeys(obj, RECORD_FIELDS)
        val packageId = obj.requireString("packageId")
        if (!AgentToolPackageToolRef.isValidPackageId(packageId)) {
            failStore("a store record has an invalid package id")
        }
        val packageVersion = obj.requireString("packageVersion")
        if (ToolPackageVersion.parseOrNull(packageVersion) == null) {
            failStore("a store record has an invalid package version")
        }
        val manifestSha256 = obj.requireString("manifestSha256")
        val contentSha256 = obj.requireString("contentSha256")
        val archiveSha256 = obj.requireString("archiveSha256")
        if (!AgentToolPackageToolRef.isValidSha256(manifestSha256) ||
            !AgentToolPackageToolRef.isValidSha256(contentSha256) ||
            !AgentToolPackageToolRef.isValidSha256(archiveSha256)
        ) {
            failStore("a store record has an invalid hash")
        }
        val sourceUri = obj.requireString("sourceUri")
        if (sourceUri.isBlank() || sourceUri.length > AgentToolPackageLimits.MAX_SOURCE_URI_CHARS ||
            sourceUri.any { it.isISOControl() }
        ) {
            failStore("a store record has an invalid source URI")
        }
        val archiveSizeBytes = obj.requireInteger("archiveSizeBytes")
        if (archiveSizeBytes !in 0L..AgentToolPackageLimits.MAX_ARCHIVE_BYTES) {
            failStore("a store record has an invalid archive size")
        }
        val storedAtMillis = obj.requireInteger("storedAtMillis")
        if (storedAtMillis < 0L) failStore("a store record has an invalid timestamp")
        val snapshotId = obj.requireString("snapshotId")
        val displayName = obj.requireString("displayName")
        if (displayName.isBlank() || displayName.length > AgentToolPackageLimits.MAX_DISPLAY_NAME_CHARS) {
            failStore("a store record has an invalid display name")
        }
        val resourcesValue = obj.fields["resources"] as? ToolPackageJsonValue.ArrayValue
            ?: failStore("a store record is missing its resource list")
        if (resourcesValue.items.size > AgentToolPackageLimits.MAX_RESOURCES) {
            failStore("a store record declares too many resources")
        }
        val resources = resourcesValue.items.map { item ->
            val resourceObject = item as? ToolPackageJsonValue.ObjectValue
                ?: failStore("a stored resource is not an object")
            checkKeys(resourceObject, RESOURCE_RECORD_FIELDS)
            val path = resourceObject.requireString("path")
            if (!AgentToolPackagePathPolicy.isNormalizedRelativeFile(path) ||
                path in AgentToolPackageLimits.RESERVED_RESOURCE_PATHS
            ) {
                failStore("a stored resource path is invalid")
            }
            val sizeBytes = resourceObject.requireInteger("sizeBytes")
            if (sizeBytes !in 0L..AgentToolPackageLimits.MAX_SINGLE_ENTRY_BYTES) {
                failStore("a stored resource has an invalid size")
            }
            val sha256 = resourceObject.requireString("sha256")
            if (!AgentToolPackageToolRef.isValidSha256(sha256)) {
                failStore("a stored resource has an invalid hash")
            }
            AgentToolPackageStoredResource(path, sizeBytes, sha256)
        }.sortedBy { it.path }
        if (resources.map { it.path }.toSet().size != resources.size) {
            failStore("a store record repeats a resource path")
        }
        if (snapshotId != toolPackageSnapshotId(packageId, contentSha256)) {
            failStore("a store record has an inconsistent snapshot id")
        }
        return AgentToolPackageStoreRecord(
            packageId = packageId,
            packageVersion = packageVersion,
            displayName = displayName,
            manifestSha256 = manifestSha256,
            contentSha256 = contentSha256,
            archiveSha256 = archiveSha256,
            sourceUri = sourceUri,
            archiveSizeBytes = archiveSizeBytes,
            storedAtMillis = storedAtMillis,
            snapshotId = snapshotId,
            resources = resources
        )
    }

    private fun checkKeys(obj: ToolPackageJsonValue.ObjectValue, allowed: Set<String>) {
        if (obj.fields.keys.any { it !in allowed }) {
            failStore("a preview store record contains an unsupported field")
        }
    }

    private fun ToolPackageJsonValue.ObjectValue.requireString(key: String): String =
        (fields[key] as? ToolPackageJsonValue.StringValue)?.value
            ?: failStore("a preview store record is missing '$key'")

    private fun ToolPackageJsonValue.ObjectValue.requireInteger(key: String): Long =
        (fields[key] as? ToolPackageJsonValue.IntegerValue)?.value
            ?: failStore("a preview store record is missing '$key'")

    private fun failStore(message: String): Nothing =
        toolPackageFailure(AgentToolPackageErrorCode.STORE_FAILURE, message)

    private val INDEX_FIELDS = setOf("storeSchemaVersion", "packages")
    private val RECORD_FIELDS = setOf(
        "packageId", "packageVersion", "displayName", "manifestSha256", "contentSha256",
        "archiveSha256", "sourceUri", "archiveSizeBytes", "storedAtMillis", "snapshotId",
        "resources"
    )
    private val RESOURCE_RECORD_FIELDS = setOf("path", "sizeBytes", "sha256")
}
