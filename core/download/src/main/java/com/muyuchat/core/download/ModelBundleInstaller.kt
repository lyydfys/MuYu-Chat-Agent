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

    companion object {
        private val installLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
        const val AUDIT_FILE_NAME = ".mca-component-audit.json"
        const val AUDIT_SCHEMA = "mca.model_bundle.audit.v1"
        internal val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:($|/)")
        internal val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    }
}
