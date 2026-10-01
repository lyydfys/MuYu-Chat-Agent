package com.muyuchat.core.download

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
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
    val transformed: Boolean = false,
    val sourceIdentity: String? = null
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
    ): ModelBundleInstallResult = installLocks.computeIfAbsent(bundleRoot.canonicalPath) {
        kotlinx.coroutines.sync.Mutex()
    }.withLock {
        installLocked(bundleRoot, components, stagedTransformer, onProgress)
    }

    private suspend fun installLocked(
        bundleRoot: File,
        components: List<RemoteModelFile>,
        stagedTransformer: ModelBundleStagedTransformer? = null,
        onProgress: (DownloadTaskSnapshot) -> Unit = {}
    ): ModelBundleInstallResult {
        currentCoroutineContext().ensureActive()
        val plan = plan(bundleRoot, components)
        recoverInterruptedCommit(plan)
        currentCoroutineContext().ensureActive()

        // A completed managed bundle is already the canonical, atomically
        // committed result.  Re-running the installer used to download every
        // component again whenever the app was restarted or the user tapped
        // the recommendation card a second time.  Reuse it only when there
        // is no interrupted transaction and the immutable audit still covers
        // exactly the current catalog component set.  A staged transformer
        // is allowed: its audit marks derived files and we only compare the
        // publisher size/SHA for untouched files.
        if (!plan.workRoot.exists()) {
            reusableInstalledBundle(plan)?.let { existing ->
                currentCoroutineContext().ensureActive()
                onProgress(
                    DownloadTaskSnapshot(
                        repoId = components.first().repoId,
                        revision = components.first().revision,
                        fileName = components.last().name,
                        url = components.last().downloadUrl,
                        expectedLength = components.sumOf { it.sizeBytes ?: 0L },
                        downloadedBytes = components.sumOf { it.sizeBytes ?: 0L },
                        status = DownloadStatus.DONE,
                        tempFile = plan.partsRoot,
                        finalFile = plan.bundleRoot
                    )
                )
                return existing
            }
        }
        require(plan.workRoot.mkdirs() || plan.workRoot.isDirectory) {
            "Unable to create bundle installation workspace: ${plan.workRoot}"
        }

        plan.targets.forEach { target ->
            currentCoroutineContext().ensureActive()
            if (reusableStagedDigest(target) != null) return@forEach
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
            currentCoroutineContext().ensureActive()
            requireDownloadedFile(target)
            recordStagedReceipt(target)
        }

        currentCoroutineContext().ensureActive()
        val transformedPaths = applyStagedTransformer(plan, stagedTransformer)
        currentCoroutineContext().ensureActive()
        if (stagedTransformer != null) {
            // A transformer is allowed to mutate only the paths it reports.
            // Recheck the untouched files after the transform so a buggy or
            // malicious transformer cannot silently remove or resize a source
            // artifact. The final audit hash below also validates its bytes.
            plan.targets.forEach { target ->
                currentCoroutineContext().ensureActive()
                if (target.relativePath in transformedPaths) {
                    require(target.stagedFile.isFile && target.stagedFile.canRead() && target.stagedFile.length() > 0L) {
                        "Transformed bundle component is missing, unreadable, or empty: ${target.relativePath}"
                    }
                } else {
                    requireDownloadedFile(target)
                }
            }
        }
        val audits = mutableListOf<ModelBundleComponentAudit>()
        for (target in plan.targets) {
            currentCoroutineContext().ensureActive()
            audits += auditStagedFile(
                target = target,
                transformed = target.relativePath in transformedPaths
            )
        }
        currentCoroutineContext().ensureActive()
        writeAuditManifest(plan.contentRoot, audits)
        currentCoroutineContext().ensureActive()
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

    private fun reusableInstalledBundle(plan: ModelBundleInstallPlan): ModelBundleInstallResult? {
        if (!plan.bundleRoot.isDirectory) return null
        val audit = verifyInstalledBundle(plan.bundleRoot)
        if (!audit.isVerified) return null
        val byPath = audit.components.associateBy { it.audit.relativePath }
        if (byPath.keys != plan.targets.mapTo(mutableSetOf()) { it.relativePath }) return null
        val files = plan.targets.map { target ->
            val verification = byPath[target.relativePath] ?: return null
            val file = target.finalFile
            if (!file.isFile || file.length() <= 0L) return null
            val record = verification.audit
            if (record.sourceIdentity != target.remote.sourceIdentity()) {
                // Legacy audits without provenance may be reused only if immutable source bytes match.
                val expectedSha = normalizedRemoteSha256OrNull(target.remote.sha256) ?: return null
                if (record.transformed || !record.observedSha256.equals(expectedSha, ignoreCase = true)) return null
            }
            // Derived files (for example a text-only config) intentionally no
            // longer match the publisher artifact.  Untouched files must
            // still match the catalog's immutable size/SHA declaration.
            if (!record.transformed) {
                target.remote.sizeBytes?.takeIf { it > 0L }?.let { expected ->
                    if (file.length() != expected) return null
                }
                target.remote.sha256?.takeIf { it.matches(SHA256_HEX) }?.let { expected ->
                    if (!record.observedSha256.equals(expected, ignoreCase = true)) return null
                }
            }
            InstalledModelBundleFile(
                remote = target.remote,
                relativePath = target.relativePath,
                file = file,
                audit = record
            )
        }
        return ModelBundleInstallResult(
            bundleRoot = plan.bundleRoot,
            files = files,
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
                val currentIntegrity = inspectRecoveryBundle(plan.bundleRoot)
                val backupIntegrity = inspectRecoveryBundle(plan.backupRoot)
                when {
                    currentIntegrity == RecoveryBundleIntegrity.VERIFIED -> {
                        // A completed commit leaves both directories briefly visible. Keep the
                        // previous copy in a uniquely named recovery directory instead of
                        // deleting it before the new bundle has been verified.
                        retainRecoveryDirectory(plan.backupRoot, plan)
                    }
                    backupIntegrity == RecoveryBundleIntegrity.VERIFIED -> {
                        // The destination is damaged but the previous bundle is verified. Move
                        // the damaged destination aside first, then restore the known-good copy.
                        retainRecoveryDirectory(plan.bundleRoot, plan)
                        if (!plan.backupRoot.renameTo(plan.bundleRoot)) {
                            throw IOException(
                                "Unable to restore the verified previous model bundle from ${plan.backupRoot}; " +
                                    "the damaged bundle was retained beside it."
                            )
                        }
                    }
                    else -> {
                        // Neither directory is verified. Do not destroy either copy; an explicit
                        // error lets the caller surface both paths for manual recovery.
                        throw IOException(
                            "Unable to recover model bundle safely: neither ${plan.bundleRoot} nor " +
                                "${plan.backupRoot} passed integrity verification. Both copies were preserved."
                        )
                    }
                }
            } else {
                when (inspectRecoveryBundle(plan.backupRoot)) {
                    RecoveryBundleIntegrity.VERIFIED,
                    RecoveryBundleIntegrity.UNKNOWN -> {
                        if (!plan.backupRoot.renameTo(plan.bundleRoot)) {
                            throw IOException("Unable to restore the previous model bundle from ${plan.backupRoot}")
                        }
                    }
                    RecoveryBundleIntegrity.INVALID -> {
                        // Keep a corrupt/partial backup out of the commit path. The new install
                        // may proceed, but the old bytes remain available for diagnosis.
                        retainRecoveryDirectory(plan.backupRoot, plan)
                    }
                }
            }
        }

        if (plan.workRoot.exists() && !plan.contentRoot.exists() && plan.bundleRoot.exists()) {
            plan.workRoot.deleteRecursively()
        }
    }

    private enum class RecoveryBundleIntegrity {
        VERIFIED,
        UNKNOWN,
        INVALID
    }

    /**
     * Recovery is intentionally conservative. A managed bundle is VERIFIED only when its audit
     * manifest validates every recorded component. Legacy bundles without an audit are UNKNOWN:
     * they may still be usable, so they are eligible for restoration when the destination is
     * damaged, but are never silently deleted.
     */
    private fun inspectRecoveryBundle(root: File): RecoveryBundleIntegrity {
        if (!root.isDirectory) return RecoveryBundleIntegrity.INVALID
        val verification = runCatching { verifyInstalledBundle(root) }.getOrNull()
            ?: return RecoveryBundleIntegrity.INVALID
        if (verification.auditReadable) {
            return if (verification.isVerified) {
                RecoveryBundleIntegrity.VERIFIED
            } else {
                RecoveryBundleIntegrity.INVALID
            }
        }
        val hasReadableContent = root.walkTopDown().any { file ->
            file.isFile && file.canRead() && file.length() > 0L
        }
        return if (hasReadableContent) RecoveryBundleIntegrity.UNKNOWN else RecoveryBundleIntegrity.INVALID
    }

    private fun retainRecoveryDirectory(directory: File, plan: ModelBundleInstallPlan) {
        val parent = requireNotNull(directory.parentFile)
        val retained = File(
            parent,
            ".${plan.bundleRoot.name}.recovery-${UUID.randomUUID()}"
        )
        if (!directory.renameTo(retained)) {
            throw IOException("Unable to preserve model bundle copy at ${directory.absolutePath}")
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

    private suspend fun reusableStagedDigest(target: ModelBundleDownloadTarget): String? {
        val file = target.stagedFile
        if (!file.isFile || !file.canRead() || file.length() <= 0L) return null
        val expectedLength = target.remote.sizeBytes
        if (expectedLength != null && expectedLength > 0L && file.length() != expectedLength) return null
        val digest = cancellableSha256(file)
        val expectedSha = normalizedRemoteSha256OrNull(target.remote.sha256)
        if (expectedSha != null) return digest.takeIf { it.equals(expectedSha, ignoreCase = true) }
        val receipt = runCatching { JSONObject(stagedReceiptFile(target).readText()) }.getOrNull() ?: return null
        return digest.takeIf {
            receipt.optString("sourceIdentity") == target.remote.sourceIdentity() &&
                receipt.optLong("sizeBytes", -1L) == file.length() &&
                receipt.optString("sha256").equals(digest, ignoreCase = true)
        }
    }

    private fun stagedReceiptFile(target: ModelBundleDownloadTarget): File =
        File(target.tempFile.parentFile, target.tempFile.name + ".completed.json")

    private suspend fun recordStagedReceipt(target: ModelBundleDownloadTarget) {
        val digest = cancellableSha256(target.stagedFile)
        val expectedSha = normalizedRemoteSha256OrNull(target.remote.sha256)
        require(expectedSha == null || digest.equals(expectedSha, ignoreCase = true)) {
            "Downloaded bundle component has an unexpected SHA-256: "+target.relativePath
        }
        val receipt = stagedReceiptFile(target)
        val pending = File(receipt.parentFile, receipt.name + ".writing")
        pending.writeText(JSONObject().put("sourceIdentity", target.remote.sourceIdentity())
            .put("sizeBytes", target.stagedFile.length()).put("sha256", digest).toString())
        java.nio.file.Files.move(pending.toPath(), receipt.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun requireDownloadedFile(target: ModelBundleDownloadTarget) {
        require(target.stagedFile.isFile && target.stagedFile.canRead()) {
            "Downloaded bundle component is missing or unreadable: ${target.relativePath}"
        }
        require(target.stagedFile.length() > 0L) {
            "Downloaded bundle component is empty: ${target.relativePath}"
        }
        val expectedLength = target.remote.sizeBytes
        if (expectedLength != null && expectedLength > 0L) {
            require(target.stagedFile.length() == expectedLength) {
                "Downloaded bundle component has an unexpected size: ${target.relativePath}"
            }
        }
    }

    private suspend fun applyStagedTransformer(
        plan: ModelBundleInstallPlan,
        transformer: ModelBundleStagedTransformer?
    ): Set<String> {
        if (transformer == null) return emptySet()
        currentCoroutineContext().ensureActive()
        // A transformer derives new bytes whose final audit intentionally
        // omits the publisher digest. Verify the original bytes before that
        // boundary, including files supplied by an injected downloader or
        // resumed staging content. Never let transformation hide corruption.
        for (target in plan.targets) {
            currentCoroutineContext().ensureActive()
            requireDownloadedFile(target)
            val expectedSha = normalizedRemoteSha256OrNull(target.remote.sha256)
            if (expectedSha != null) {
                require(cancellableSha256(target.stagedFile).equals(expectedSha, ignoreCase = true)) {
                    "Downloaded bundle component has an unexpected SHA-256: ${target.relativePath}"
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val stagedFiles = plan.targets.associate { target -> target.relativePath to target.stagedFile }
        val transformed = transformer.transform(plan.contentRoot, stagedFiles)
            .transformedRelativePaths
            .map(::normalizeRelativePath)
            .toSet()
        require(transformed.all(stagedFiles::containsKey)) {
            "A staged transformer may only report downloaded bundle components."
        }
        plan.targets.forEach { target ->
            currentCoroutineContext().ensureActive()
            if (target.relativePath in transformed) {
                require(target.stagedFile.isFile && target.stagedFile.canRead() && target.stagedFile.length() > 0L) {
                    "Transformed bundle component is missing, unreadable, or empty: ${target.relativePath}"
                }
            }
        }
        return transformed
    }

    private suspend fun auditStagedFile(
        target: ModelBundleDownloadTarget,
        transformed: Boolean
    ): ModelBundleComponentAudit {
        val observedSha256 = cancellableSha256(target.stagedFile)
        val expectedSha = normalizedRemoteSha256OrNull(target.remote.sha256)
        require(transformed || expectedSha == null || observedSha256.equals(expectedSha, ignoreCase = true)) {
            "Downloaded bundle component has an unexpected SHA-256: ${target.relativePath}"
        }
        return ModelBundleComponentAudit(
            relativePath = target.relativePath,
            observedSizeBytes = target.stagedFile.length(),
            observedSha256 = observedSha256,
            // Once product installation derives a file, its bytes are no longer
            // the publisher artifact. Keep only the local observed digest and do
            // not misrepresent source size/SHA metadata as a verified match.
            sourceSizeBytes = if (transformed) null else target.remote.sizeBytes?.takeIf { it > 0L },
            sourceSha256 = if (transformed) null else normalizedRemoteSha256OrNull(target.remote.sha256),
            sourceMetadataStatus = if (transformed) {
                ImageEngineIntegrityMetadataStatus.UNKNOWN
            } else {
                target.remote.integrityMetadataStatus
            },
            transformed = transformed,
            sourceIdentity = target.remote.sourceIdentity()
        )
    }

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
                    .put("sourceIdentity", audit.sourceIdentity ?: JSONObject.NULL)
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
                        transformed = component.optBoolean("transformed", false),
                        sourceIdentity = component.optString("sourceIdentity").takeIf { it.matches(SHA256_HEX) }
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
        if (file.length() <= 0L) return ModelBundleComponentVerificationStatus.MISSING
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

    private suspend fun cancellableSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
        private val installLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
        const val AUDIT_FILE_NAME = ".mca-component-audit.json"
        const val AUDIT_SCHEMA = "mca.model_bundle.audit.v1"
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:($|/)")
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    }
}
