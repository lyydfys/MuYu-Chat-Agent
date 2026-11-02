package com.muyuchat.mca.agent.extensions.toolpkg

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolPackageBinding
import com.muyuchat.core.agent.contract.AgentToolPackageToolRef
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import java.io.InputStream
import java.security.MessageDigest
/**
 * Read-only preview of a validated package.
 *
 * [previewOnly] is a computed `true`: P0 has no install/enable/run action and this model carries no
 * executor, callback or mutable activation flag. The [bindings] are frozen metadata (identity,
 * host operation, preset JSON, timeout, side effect/data class); they are not registered in any
 * production [com.muyuchat.core.agent.contract.AgentToolCatalog].
 */
data class AgentToolPackagePreview(
    val packageId: String,
    val packageVersion: String,
    val displayName: String,
    val description: String,
    val schemaVersion: Int,
    val apiVersion: Int,
    val source: AgentToolPackageSource,
    val manifestSha256: String,
    val contentSha256: String,
    val dependencies: List<AgentToolPackageDependency>,
    val tools: List<AgentToolPackagePreviewTool>,
    val resources: List<AgentToolPackagePreviewResource>,
    val totalExpandedBytes: Long,
    val bindings: List<AgentToolPackageBinding>
) {
    init {
        require(AgentToolPackageToolRef.isValidPackageId(packageId)) { "preview package id is invalid" }
        require(AgentToolPackageToolRef.isValidSha256(manifestSha256)) { "preview manifest hash is invalid" }
        require(AgentToolPackageToolRef.isValidSha256(contentSha256)) { "preview content hash is invalid" }
        require(totalExpandedBytes >= 0L) { "preview expanded size must not be negative" }
        require(tools.size == bindings.size) { "preview tools and bindings must be paired" }
        require(bindings.all { it.packageContentHash == contentSha256 }) {
            "preview bindings must be frozen to the preview content hash"
        }
    }

    /** P0 invariant: a preview can never be treated as an enabled/installed tool source. */
    val previewOnly: Boolean get() = true

    val versionIdentity: String get() = packageVersion + "+" + contentSha256.take(16)

    fun toolByName(toolName: String): AgentToolPackagePreviewTool? = tools.firstOrNull { it.ref.toolName == toolName }
}

/** One declared tool as shown to the user/admin preview; no executor is exposed. */
data class AgentToolPackagePreviewTool(
    val ref: AgentToolPackageToolRef,
    val displayName: String,
    val description: String,
    val hostOperation: String,
    val sideEffect: AgentToolSideEffect,
    val dataClass: AgentDataClass,
    val presetKeys: List<String>,
    val exposedParameters: List<String>,
    val timeoutMillis: Long,
    val bindingVersionIdentity: String
)

/** One declared text/metadata resource whose bytes were hashed before the preview was accepted. */
data class AgentToolPackagePreviewResource(
    val path: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String
)

/** A package that has a validated, private, read-only preview snapshot in the store. */
data class AgentToolPackageStoredPackage(
    val preview: AgentToolPackagePreview,
    val snapshotId: String,
    val storedAtMillis: Long
)

/** Lightweight index row; [AgentToolPackageFileStore.verify] is the content-checking call. */
data class AgentToolPackageSummary(
    val packageId: String,
    val packageVersion: String,
    val displayName: String,
    val manifestSha256: String,
    val contentSha256: String,
    val archiveSha256: String,
    val toolCount: Int,
    val resourceCount: Int,
    val storedAtMillis: Long
) {
    val versionIdentity: String get() = packageVersion + "+" + contentSha256.take(16)
}

internal data class AgentToolPackageStoredResource(
    val path: String,
    val sizeBytes: Long,
    val sha256: String
) {
    init {
        require(AgentToolPackagePathPolicy.isNormalizedRelativeFile(path)) {
            "stored resource path is not a normalized relative file"
        }
        require(sizeBytes >= 0L) { "stored resource size must not be negative" }
        require(AgentToolPackageToolRef.isValidSha256(sha256)) { "stored resource hash must be SHA-256" }
    }
}

/** Deterministic package identity over the canonical manifest and every declared resource. */
internal object AgentToolPackageContentHash {
    fun compute(manifestSha256: String, resources: List<AgentToolPackageStoredResource>): String {
        if (!AgentToolPackageToolRef.isValidSha256(manifestSha256)) {
            toolPackageFailure(AgentToolPackageErrorCode.PACKAGE_CORRUPT, "manifest hash is not SHA-256")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN.toByteArray(Charsets.UTF_8))
        digest.update(manifestSha256.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        resources.sortedBy { it.path }.forEach { resource ->
            digest.update(resource.path.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(le64(resource.sizeBytes))
            digest.update(resource.sha256.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return ToolPackageJson.toHex(digest.digest())
    }

    private fun le64(value: Long): ByteArray = ByteArray(8) { index ->
        ((value ushr (8 * index)) and 0xFFL).toByte()
    }

    private const val DOMAIN = "MCA-TOOL-PACKAGE-CONTENT\n"
}

/** One bounded content stream exposed to the validator (ZIP entries today, a directory later). */
internal data class AgentToolPackageContentFile(
    val path: String,
    val sizeBytes: Long
)

internal interface AgentToolPackageContentSource {
    val files: List<AgentToolPackageContentFile>

    /** Must return a fresh stream positioned at the start of the file. */
    fun open(file: AgentToolPackageContentFile): InputStream
}

internal class AgentToolPackageZipContentSource(
    private val archive: AgentToolPackageZipArchive
) : AgentToolPackageContentSource {
    private val entriesByPath: Map<String, AgentToolPackageZipEntry>

    override val files: List<AgentToolPackageContentFile>

    init {
        val entries = archive.entries().filterNot { it.isDirectory }
        entriesByPath = entries.associateBy { it.path }
        require(entriesByPath.size == entries.size) { "archive contains duplicate normalized paths" }
        files = entries.map { AgentToolPackageContentFile(it.path, it.uncompressedSize) }
    }

    override fun open(file: AgentToolPackageContentFile): InputStream =
        archive.openStream(entriesByPath.getValue(file.path))
}

/** Fully validated package content before it is committed to the private preview store. */
internal data class AgentToolPackageValidated(
    val manifest: AgentToolPackageManifest,
    val resources: List<AgentToolPackageStoredResource>,
    val contentSha256: String,
    val bindings: List<AgentToolPackageBinding>,
    val preview: AgentToolPackagePreview
)

/**
 * Validates package content against its manifest and the trusted host catalog.
 *
 * The manifest is parsed first, every declared resource is streamed/hashed, and undeclared/missing
 * files reject the package as a whole. The derived bindings are metadata only; no
 * [com.muyuchat.core.agent.contract.AgentToolExecutor] is created here or anywhere in P0.
 */
internal class AgentToolPackageValidator(
    private val hostCatalog: AgentToolPackageHostCatalog
) {
    private val parser = AgentToolPackageManifestParser(hostCatalog)

    fun validate(
        source: AgentToolPackageSource,
        content: AgentToolPackageContentSource
    ): AgentToolPackageValidated {
        val filesByPath = LinkedHashMap<String, AgentToolPackageContentFile>()
        for (file in content.files) {
            if (filesByPath.put(file.path, file) != null) {
                toolPackageFailure(AgentToolPackageErrorCode.MANIFEST_DUPLICATE_RESOURCE, "content repeats a path")
            }
        }
        val manifestFile = filesByPath[AgentToolPackageLimits.MANIFEST_FILE_NAME]
            ?: toolPackageFailure(
                AgentToolPackageErrorCode.MISSING_MANIFEST,
                "the package root must contain ${AgentToolPackageLimits.MANIFEST_FILE_NAME}"
            )
        if (manifestFile.sizeBytes > AgentToolPackageLimits.MAX_MANIFEST_BYTES) {
            toolPackageFailure(
                AgentToolPackageErrorCode.MANIFEST_TOO_LARGE,
                "manifest exceeds ${AgentToolPackageLimits.MAX_MANIFEST_BYTES} bytes"
            )
        }
        val manifestBytes = try {
            content.open(manifestFile).use { input ->
                readBounded(input, AgentToolPackageLimits.MAX_MANIFEST_BYTES, AgentToolPackageErrorCode.MANIFEST_TOO_LARGE)
            }
        } catch (failure: AgentToolPackageException) {
            throw failure
        } catch (_: Exception) {
            toolPackageFailure(AgentToolPackageErrorCode.MALFORMED_ZIP, "manifest entry could not be read")
        }
        val manifest = parser.parse(manifestBytes)

        val declared = LinkedHashMap<String, AgentToolPackageResource>()
        manifest.resources.forEach { resource -> declared[resource.path] = resource }
        val stored = ArrayList<AgentToolPackageStoredResource>(declared.size)
        var totalExpanded = manifestBytes.size.toLong()

        for ((path, file) in filesByPath) {
            if (path == AgentToolPackageLimits.MANIFEST_FILE_NAME) continue
            if (!declared.containsKey(path)) {
                toolPackageFailure(
                    AgentToolPackageErrorCode.MANIFEST_RESOURCE_UNLISTED,
                    "the package contains a file that the manifest does not declare",
                    path
                )
            }
            val declaredResource = declared.getValue(path)
            val (actualSize, actualSha256) = try {
                content.open(file).use { input ->
                    hashBounded(input, AgentToolPackageLimits.MAX_SINGLE_ENTRY_BYTES, path)
                }
            } catch (failure: AgentToolPackageException) {
                throw failure
            } catch (_: Exception) {
                toolPackageFailure(AgentToolPackageErrorCode.MALFORMED_ZIP, "resource entry could not be read", path)
            }
            if (actualSha256 != declaredResource.sha256) {
                toolPackageFailure(
                    AgentToolPackageErrorCode.MANIFEST_RESOURCE_HASH_MISMATCH,
                    "resource does not match its declared SHA-256",
                    path
                )
            }
            totalExpanded += actualSize
            if (totalExpanded > AgentToolPackageLimits.MAX_EXPANDED_BYTES) {
                toolPackageFailure(
                    AgentToolPackageErrorCode.EXPANDED_SIZE_EXCEEDED,
                    "expanded package exceeds ${AgentToolPackageLimits.MAX_EXPANDED_BYTES} bytes",
                    path
                )
            }
            stored += AgentToolPackageStoredResource(path, actualSize, actualSha256)
        }
        for (declaredResource in declared.values) {
            if (!filesByPath.containsKey(declaredResource.path)) {
                toolPackageFailure(
                    AgentToolPackageErrorCode.MANIFEST_RESOURCE_MISSING,
                    "the manifest declares a resource that is not in the package",
                    declaredResource.path
                )
            }
        }
        stored.sortBy { it.path }

        val contentSha256 = AgentToolPackageContentHash.compute(manifest.manifestSha256, stored)
        val bindings = bindings(manifest, contentSha256)
        val preview = preview(source, manifest, contentSha256, stored, bindings, totalExpanded)
        return AgentToolPackageValidated(
            manifest = manifest,
            resources = stored.toList(),
            contentSha256 = contentSha256,
            bindings = bindings,
            preview = preview
        )
    }

    fun bindings(
        manifest: AgentToolPackageManifest,
        contentSha256: String
    ): List<AgentToolPackageBinding> = manifest.tools.map { tool ->
        val operation = hostCatalog.find(tool.hostOperation)
            ?: toolPackageFailure(
                AgentToolPackageErrorCode.MANIFEST_HOST_OPERATION_NOT_ALLOWED,
                "tool host operation is no longer allowlisted",
                tool.name
            )
        if (operation.sideEffect != AgentToolSideEffect.READ_ONLY) {
            toolPackageFailure(
                AgentToolPackageErrorCode.MANIFEST_HOST_OPERATION_NOT_ALLOWED,
                "tool host operation is not read-only",
                tool.name
            )
        }
        try {
            AgentToolPackageBinding(
                ref = tool.ref(manifest.packageId),
                packageVersion = manifest.packageVersion,
                hostOperation = operation.operationId,
                presetCanonicalJson = tool.presetJson,
                exposedParameters = tool.exposedParameters,
                timeoutMillis = tool.timeoutMillis,
                packageContentHash = contentSha256,
                sideEffect = operation.sideEffect,
                dataClass = operation.dataClass
            )
        } catch (_: IllegalArgumentException) {
            toolPackageFailure(
                AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID,
                "tool binding metadata is invalid",
                tool.name
            )
        }
    }

    fun preview(
        source: AgentToolPackageSource,
        manifest: AgentToolPackageManifest,
        contentSha256: String,
        resources: List<AgentToolPackageStoredResource>,
        bindings: List<AgentToolPackageBinding>,
        totalExpandedBytes: Long
    ): AgentToolPackagePreview {
        val mediaTypes = manifest.resources.associate { it.path to it.mediaType }
        val tools = manifest.tools.mapIndexed { index, tool ->
            val binding = bindings.getOrNull(index)
                ?: toolPackageFailure(AgentToolPackageErrorCode.PACKAGE_CORRUPT, "tool binding is missing")
            AgentToolPackagePreviewTool(
                ref = binding.ref,
                displayName = tool.displayName,
                description = tool.description,
                hostOperation = binding.hostOperation,
                sideEffect = binding.sideEffect,
                dataClass = binding.dataClass,
                presetKeys = tool.presetKeys,
                exposedParameters = tool.exposedParameters,
                timeoutMillis = binding.timeoutMillis,
                bindingVersionIdentity = binding.versionIdentity
            )
        }
        val previewResources = resources.map { resource ->
            val mediaType = mediaTypes[resource.path]
                ?: toolPackageFailure(
                    AgentToolPackageErrorCode.PACKAGE_CORRUPT,
                    "validated resource is missing its manifest media type",
                    resource.path
                )
            AgentToolPackagePreviewResource(
                path = resource.path,
                mediaType = mediaType,
                sizeBytes = resource.sizeBytes,
                sha256 = resource.sha256
            )
        }
        return AgentToolPackagePreview(
            packageId = manifest.packageId,
            packageVersion = manifest.packageVersion,
            displayName = manifest.displayName,
            description = manifest.description,
            schemaVersion = manifest.schemaVersion,
            apiVersion = manifest.apiVersion,
            source = source,
            manifestSha256 = manifest.manifestSha256,
            contentSha256 = contentSha256,
            dependencies = manifest.requires,
            tools = tools,
            resources = previewResources,
            totalExpandedBytes = totalExpandedBytes,
            bindings = bindings
        )
    }

    private fun readBounded(
        input: InputStream,
        limit: Long,
        oversizeCode: AgentToolPackageErrorCode
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                toolPackageFailure(oversizeCode, "entry exceeds its byte limit")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun hashBounded(input: InputStream, limit: Long, path: String): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                toolPackageFailure(
                    AgentToolPackageErrorCode.ENTRY_SIZE_EXCEEDED,
                    "resource exceeds ${AgentToolPackageLimits.MAX_SINGLE_ENTRY_BYTES} bytes",
                    path
                )
            }
            digest.update(buffer, 0, read)
        }
        return total to ToolPackageJson.toHex(digest.digest())
    }

    private companion object {
        const val COPY_BUFFER = 16 * 1024
    }
}
