package com.muyuchat.core.download

import java.io.File

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
