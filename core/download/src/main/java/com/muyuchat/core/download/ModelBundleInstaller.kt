package com.muyuchat.core.download

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

fun interface BundleComponentDownloader {
    suspend fun download(
        remote: RemoteModelFile,
        tempFile: File,
        finalFile: File,
        onProgress: (DownloadTaskSnapshot) -> Unit
    ): DownloadTaskSnapshot
}

/**
 * Applies a declared product install profile to fully downloaded staging
 * content before the directory is atomically committed.
 */
fun interface ModelBundleStagedTransformer {
    fun transform(
        contentRoot: File,
        stagedFiles: Map<String, File>
    ): ModelBundleStagedTransformResult
}

data class ModelBundleStagedTransformResult(
    val transformedRelativePaths: Set<String> = emptySet()
)

data class ModelBundleDownloadTarget(
    val remote: RemoteModelFile,
    val relativePath: String,
    val tempFile: File,
    val stagedFile: File,
    val finalFile: File
)

data class ModelBundleInstallPlan(
    val bundleRoot: File,
    val workRoot: File,
    val contentRoot: File,
    val partsRoot: File,
    val backupRoot: File,
    val targets: List<ModelBundleDownloadTarget>
)

data class InstalledModelBundleFile(
    val remote: RemoteModelFile,
    val relativePath: String,
    val file: File,
    val audit: ModelBundleComponentAudit? = null
)

data class ModelBundleInstallResult(
    val bundleRoot: File,
    val files: List<InstalledModelBundleFile>,
    val auditManifest: File? = null
)

/** A local observation made only after the component has been downloaded. */
data class ModelBundleComponentAudit(
    val relativePath: String,
    val observedSizeBytes: Long,
    val observedSha256: String,
    val sourceSizeBytes: Long? = null,
    val sourceSha256: String? = null,
    val sourceMetadataStatus: ImageEngineIntegrityMetadataStatus,
    val transformed: Boolean = false
)

enum class ModelBundleComponentVerificationStatus {
    MATCHED_SOURCE_SHA256,
    MATCHED_OBSERVED_DIGEST,
    MISSING,
    SIZE_MISMATCH,
    DIGEST_MISMATCH,
    SOURCE_SHA256_MISMATCH
}

data class ModelBundleComponentVerification(
    val audit: ModelBundleComponentAudit,
    val status: ModelBundleComponentVerificationStatus
)

data class ModelBundleAuditVerificationResult(
    val auditFile: File,
    val auditReadable: Boolean,
    val components: List<ModelBundleComponentVerification>
) {
    val isVerified: Boolean
        get() = auditReadable && components.isNotEmpty() && components.all {
            it.status == ModelBundleComponentVerificationStatus.MATCHED_SOURCE_SHA256 ||
                it.status == ModelBundleComponentVerificationStatus.MATCHED_OBSERVED_DIGEST
        }
}

class ModelBundleInstaller(
    private val componentDownloader: BundleComponentDownloader = BundleComponentDownloader { remote, temp, final, progress ->
        ResumableDownloader().download(remote, temp, final, progress)
    }
) {
    fun plan(bundleRoot: File, components: List<RemoteModelFile>): ModelBundleInstallPlan {
        require(components.isNotEmpty()) { "A model bundle must contain at least one component." }

        val canonicalRoot = bundleRoot.canonicalFile
        val parent = canonicalRoot.parentFile
            ?: throw IllegalArgumentException("The bundle root must have a parent directory.")
        val workRoot = File(parent, ".${canonicalRoot.name}.installing").canonicalFile
        val contentRoot = File(workRoot, "content").canonicalFile
        val partsRoot = File(workRoot, "parts").canonicalFile
        val backupRoot = File(parent, ".${canonicalRoot.name}.backup").canonicalFile

        val normalizedPaths = components.map { normalizeRelativePath(it.relativePath) }
        rejectConflictingPaths(normalizedPaths)

        val targets = components.zip(normalizedPaths).map { (remote, relativePath) ->
            ModelBundleDownloadTarget(
                remote = remote,
                relativePath = relativePath,
                tempFile = safeDescendant(partsRoot, "$relativePath.part"),
                stagedFile = safeDescendant(contentRoot, relativePath),
                finalFile = safeDescendant(canonicalRoot, relativePath)
            )
        }

        return ModelBundleInstallPlan(
            bundleRoot = canonicalRoot,
            workRoot = workRoot,
            contentRoot = contentRoot,
            partsRoot = partsRoot,
            backupRoot = backupRoot,
            targets = targets
        )
    }

    suspend fun install(
        bundleRoot: File,
        components: List<RemoteModelFile>,
        stagedTransformer: ModelBundleStagedTransformer? = null,
        onProgress: (DownloadTaskSnapshot) -> Unit = {}
    ): ModelBundleInstallResult {
        val plan = plan(bundleRoot, components)
        recoverInterruptedCommit(plan)
        require(plan.workRoot.mkdirs() || plan.workRoot.isDirectory) {
            "Unable to create bundle installation workspace: ${plan.workRoot}"
        }

        plan.targets.forEach { target ->
            if (isReusableStagedFile(target)) return@forEach
            if (target.stagedFile.exists() && !target.stagedFile.delete()) {
                throw IOException("Unable to remove invalid staged file: ${target.stagedFile}")
            }
            target.tempFile.parentFile?.mkdirs()
            target.stagedFile.parentFile?.mkdirs()
            componentDownloader.download(
                target.remote,
                target.tempFile,
                target.stagedFile
            ) { snapshot ->
                onProgress(snapshot.copy(finalFile = target.finalFile))
            }
            requireDownloadedFile(target)
        }

        val transformedPaths = applyStagedTransformer(plan, stagedTransformer)
        val audits = plan.targets.map { target ->
            auditStagedFile(target, target.relativePath in transformedPaths)
        }
        writeAuditManifest(plan.contentRoot, audits)
        commit(plan)
        val auditsByPath = audits.associateBy { it.relativePath }
        return ModelBundleInstallResult(
            bundleRoot = plan.bundleRoot,
            files = plan.targets.map { target ->
                InstalledModelBundleFile(
                    remote = target.remote,
                    relativePath = target.relativePath,
                    file = target.finalFile,
                    audit = auditsByPath.getValue(target.relativePath)
                )
            },
            auditManifest = File(plan.bundleRoot, AUDIT_FILE_NAME)
        )
    }

    /**
     * Rechecks the immutable component paths recorded after installation.
     * For components without a publisher SHA-256 this detects changes made
     * after installation, but does not claim publisher-origin verification.
     */
    fun verifyInstalledBundle(bundleRoot: File): ModelBundleAuditVerificationResult {
        val root = bundleRoot.canonicalFile
        val auditFile = File(root, AUDIT_FILE_NAME).canonicalFile
        val audits = readAuditManifest(auditFile) ?: return ModelBundleAuditVerificationResult(
            auditFile = auditFile,
            auditReadable = false,
            components = emptyList()
        )
        return ModelBundleAuditVerificationResult(
            auditFile = auditFile,
            auditReadable = true,
            components = audits.map { audit ->
                ModelBundleComponentVerification(audit, verifyComponent(root, audit))
            }
        )
    }

    fun discardPartialInstall(bundleRoot: File) {
        val canonicalRoot = bundleRoot.canonicalFile
        val parent = canonicalRoot.parentFile ?: return
        File(parent, ".${canonicalRoot.name}.installing").deleteRecursively()
    }

    private fun recoverInterruptedCommit(plan: ModelBundleInstallPlan) {
        if (plan.backupRoot.exists()) {
            if (plan.bundleRoot.exists()) {
                plan.backupRoot.deleteRecursively()
            } else if (!plan.backupRoot.renameTo(plan.bundleRoot)) {
                throw IOException("Unable to restore the previous model bundle from ${plan.backupRoot}")
            }
        }

        if (plan.workRoot.exists() && !plan.contentRoot.exists() && plan.bundleRoot.exists()) {
            plan.workRoot.deleteRecursively()
        }
    }

    private fun commit(plan: ModelBundleInstallPlan) {
        require(plan.contentRoot.isDirectory) { "Bundle staging content is missing: ${plan.contentRoot}" }
        if (plan.backupRoot.exists() && !plan.backupRoot.deleteRecursively()) {
            throw IOException("Unable to remove stale bundle backup: ${plan.backupRoot}")
        }

        val hadExistingBundle = plan.bundleRoot.exists()
        if (hadExistingBundle && !plan.bundleRoot.renameTo(plan.backupRoot)) {
            throw IOException("Unable to back up the existing model bundle: ${plan.bundleRoot}")
        }

        if (!plan.contentRoot.renameTo(plan.bundleRoot)) {
            if (hadExistingBundle && !plan.bundleRoot.exists()) {
                val restored = plan.backupRoot.renameTo(plan.bundleRoot)
                if (!restored) {
                    throw IOException(
                        "Unable to commit the new bundle or restore the previous bundle. Backup: ${plan.backupRoot}"
                    )
                }
            }
            throw IOException("Unable to commit staged model bundle: ${plan.contentRoot}")
        }

        plan.workRoot.deleteRecursively()
        plan.backupRoot.deleteRecursively()
    }

    private fun isReusableStagedFile(target: ModelBundleDownloadTarget): Boolean {
        val file = target.stagedFile
        if (!file.isFile) return false
        val expectedLength = target.remote.sizeBytes
        if (expectedLength != null && expectedLength > 0L && file.length() != expectedLength) return false
        val expectedSha = target.remote.sha256?.takeIf { it.isNotBlank() } ?: return true
        return sha256(file).equals(expectedSha, ignoreCase = true)
    }

    private fun requireDownloadedFile(target: ModelBundleDownloadTarget) {
        require(target.stagedFile.isFile) {
            "Downloaded bundle component is missing: ${target.relativePath}"
        }
        val expectedLength = target.remote.sizeBytes
        if (expectedLength != null && expectedLength > 0L) {
            require(target.stagedFile.length() == expectedLength) {
                "Downloaded bundle component has an unexpected size: ${target.relativePath}"
            }
        }
        val expectedSha = target.remote.sha256?.takeIf { it.isNotBlank() }
        if (expectedSha != null) {
            require(sha256(target.stagedFile).equals(expectedSha, ignoreCase = true)) {
                "Downloaded bundle component has an unexpected SHA-256: ${target.relativePath}"
            }
        }
    }

    private fun applyStagedTransformer(
        plan: ModelBundleInstallPlan,
        transformer: ModelBundleStagedTransformer?
    ): Set<String> {
        if (transformer == null) return emptySet()
        val stagedFiles = plan.targets.associate { target -> target.relativePath to target.stagedFile }
        val transformed = transformer.transform(plan.contentRoot, stagedFiles)
            .transformedRelativePaths
            .map(::normalizeRelativePath)
            .toSet()
        require(transformed.all(stagedFiles::containsKey)) {
            "A staged transformer may only report downloaded bundle components."
        }
        plan.targets.forEach { target ->
            if (target.relativePath in transformed) {
                require(target.stagedFile.isFile && target.stagedFile.canRead() && target.stagedFile.length() > 0L) {
                    "Transformed bundle component is missing, unreadable, or empty: ${target.relativePath}"
                }
            } else {
                // A transformer must not silently mutate an unreported source
                // component. Recheck publisher size/SHA contracts before audit.
                requireDownloadedFile(target)
            }
        }
        return transformed
    }

    private fun auditStagedFile(
        target: ModelBundleDownloadTarget,
        transformed: Boolean
    ): ModelBundleComponentAudit =
        ModelBundleComponentAudit(
            relativePath = target.relativePath,
            observedSizeBytes = target.stagedFile.length(),
            observedSha256 = sha256(target.stagedFile),
            // Once product installation derives a file, its bytes are no longer
            // the publisher artifact. Keep only the local observed digest and do
            // not misrepresent source size/SHA metadata as a verified match.
            sourceSizeBytes = if (transformed) null else target.remote.sizeBytes?.takeIf { it > 0L },
            sourceSha256 = if (transformed) null else target.remote.sha256?.takeIf { it.isNotBlank() },
            sourceMetadataStatus = if (transformed) {
                ImageEngineIntegrityMetadataStatus.UNKNOWN
            } else {
                target.remote.integrityMetadataStatus
            },
            transformed = transformed
        )

    private fun writeAuditManifest(contentRoot: File, audits: List<ModelBundleComponentAudit>) {
        val auditFile = File(contentRoot, AUDIT_FILE_NAME)
        val components = JSONArray()
        audits.forEach { audit ->
            components.put(
                JSONObject()
                    .put("path", audit.relativePath)
                    .put("observedSizeBytes", audit.observedSizeBytes)
                    .put("observedSha256", audit.observedSha256)
                    .put("sourceSizeBytes", audit.sourceSizeBytes ?: JSONObject.NULL)
                    .put("sourceSha256", audit.sourceSha256 ?: JSONObject.NULL)
                    .put("sourceMetadataStatus", audit.sourceMetadataStatus.name)
                    .put("transformed", audit.transformed)
            )
        }
        auditFile.writeText(
            JSONObject()
                .put("schema", AUDIT_SCHEMA)
                .put("components", components)
                .toString(2),
            Charsets.UTF_8
        )
    }

    private fun readAuditManifest(auditFile: File): List<ModelBundleComponentAudit>? = runCatching {
        if (!auditFile.isFile) return null
        val manifest = JSONObject(auditFile.readText(Charsets.UTF_8))
        require(manifest.optString("schema") == AUDIT_SCHEMA) { "Unsupported bundle audit schema." }
        val components = manifest.optJSONArray("components") ?: return null
        require(components.length() > 0) { "Bundle audit has no components." }
        buildList {
            for (index in 0 until components.length()) {
                val component = components.getJSONObject(index)
                val relativePath = normalizeRelativePath(component.getString("path"))
                val observedSize = component.getLong("observedSizeBytes")
                val observedSha = component.getString("observedSha256")
                require(observedSize >= 0L && observedSha.matches(SHA256_HEX)) { "Invalid component audit." }
                add(
                    ModelBundleComponentAudit(
                        relativePath = relativePath,
                        observedSizeBytes = observedSize,
                        observedSha256 = observedSha,
                        sourceSizeBytes = component.optLong("sourceSizeBytes", -1L).takeIf { it > 0L },
                        sourceSha256 = component.optString("sourceSha256").takeIf { it.isNotBlank() && it != "null" },
                        sourceMetadataStatus = component.optString("sourceMetadataStatus")
                            .let { value -> ImageEngineIntegrityMetadataStatus.entries.firstOrNull { it.name == value } }
                            ?: ImageEngineIntegrityMetadataStatus.UNKNOWN,
                        transformed = component.optBoolean("transformed", false)
                    )
                )
            }
        }
    }.getOrNull()

    private fun verifyComponent(
        root: File,
        audit: ModelBundleComponentAudit
    ): ModelBundleComponentVerificationStatus {
        val file = runCatching { safeDescendant(root, audit.relativePath) }.getOrNull()
            ?: return ModelBundleComponentVerificationStatus.MISSING
        if (!file.isFile) return ModelBundleComponentVerificationStatus.MISSING
        if (file.length() != audit.observedSizeBytes) return ModelBundleComponentVerificationStatus.SIZE_MISMATCH
        val actualSha = sha256(file)
        if (!actualSha.equals(audit.observedSha256, ignoreCase = true)) {
            return ModelBundleComponentVerificationStatus.DIGEST_MISMATCH
        }
        val sourceSha = audit.sourceSha256
        return if (sourceSha != null && !actualSha.equals(sourceSha, ignoreCase = true)) {
            ModelBundleComponentVerificationStatus.SOURCE_SHA256_MISMATCH
        } else if (sourceSha != null) {
            ModelBundleComponentVerificationStatus.MATCHED_SOURCE_SHA256
        } else {
            ModelBundleComponentVerificationStatus.MATCHED_OBSERVED_DIGEST
        }
    }

    private fun normalizeRelativePath(rawPath: String): String {
        require(rawPath.isNotBlank()) { "Bundle relativePath must not be blank." }
        require('\u0000' !in rawPath) { "Bundle relativePath contains a NUL character." }
        val normalized = rawPath.replace('\\', '/')
        require(!normalized.startsWith('/')) { "Bundle relativePath must not be absolute: $rawPath" }
        require(!WINDOWS_DRIVE_PREFIX.containsMatchIn(normalized)) {
            "Bundle relativePath must not use an absolute drive path: $rawPath"
        }
        val segments = normalized.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "Bundle relativePath contains an invalid segment: $rawPath"
        }
        return segments.joinToString("/")
    }

    private fun rejectConflictingPaths(paths: List<String>) {
        val normalized = paths.map { it.lowercase(Locale.ROOT) }
        require(normalized.toSet().size == normalized.size) {
            "Duplicate bundle relativePath values are not allowed."
        }
        val pathSet = normalized.toSet()
        normalized.forEach { path ->
            val segments = path.split('/')
            for (index in 1 until segments.size) {
                val parentPath = segments.take(index).joinToString("/")
                require(parentPath !in pathSet) {
                    "Bundle relativePath conflicts with a file parent: $parentPath and $path"
                }
            }
        }
    }

    private fun safeDescendant(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val child = File(canonicalRoot, relativePath.replace('/', File.separatorChar)).canonicalFile
        require(child.toPath().startsWith(canonicalRoot.toPath()) && child != canonicalRoot) {
            "Bundle relativePath escapes its root: $relativePath"
        }
        return child
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val AUDIT_FILE_NAME = ".mca-component-audit.json"
        const val AUDIT_SCHEMA = "mca.model_bundle.audit.v1"
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:($|/)")
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    }
}
