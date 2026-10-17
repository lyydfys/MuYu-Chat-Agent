package com.muyuchat.core.modelstore

import java.io.File
import java.util.UUID
import com.muyuchat.core.modelstore.ModelStoreRepository.Companion.MODEL_IMPORT_LOCK

    internal fun ModelStoreRepository.deleteModelImpl(id: String): Boolean = synchronized(MODEL_IMPORT_LOCK) {
        val models = listModels()
        val target = models.firstOrNull { it.id == id || id in it.aliases } ?: return@synchronized false
        val modelPath = File(target.path)
        val deletionTarget = modelPath.takeIf { it.isDirectory }
            ?: modelPath.parentFile?.takeIf { it.isMcaManagedBundleDir() }
            ?: modelPath
        val ownedProjector = target.visionProjectorPath
            ?.let(::File)
            ?.takeIf { projector ->
                projector.parentFile?.absolutePath == managedModelDir.absolutePath
            }

        deleteModelArtifactsBeforeManifestUpdate(
            modelPath = modelPath,
            mainDeletionTarget = deletionTarget,
            ownedProjectorPath = ownedProjector
        ) {
            save(models.filterNot { it.id == target.id })
        }
        true
    }

internal fun File.isManagedImportStagingDirectory(): Boolean =
    CURRENT_IMPORT_DIRECTORY.matches(name) || LEGACY_NAMED_IMPORT_ARTIFACT.matches(name) ||
        name.startsWith(".mca-import-resume-")

/**
 * Deletes repository-owned artifacts before committing the manifest update.
 *
 * The standalone projector is removed first so a projector failure leaves the
 * primary text model intact. The main model/bundle is removed next, and every
 * owned path is checked again before [updateManifest] is allowed to run.
 */
internal fun deleteModelArtifactsBeforeManifestUpdate(
    modelPath: File,
    mainDeletionTarget: File,
    ownedProjectorPath: File?,
    deleteFile: (File) -> Boolean = { path -> path.delete() },
    deleteRecursively: (File) -> Boolean = { path -> path.deleteRecursively() },
    updateManifest: () -> Unit
) {
    val projectorCoveredByMainDeletion = ownedProjectorPath?.let { projector ->
        projector.isSameAsOrUnder(mainDeletionTarget)
    } == true

    if (ownedProjectorPath != null && !projectorCoveredByMainDeletion) {
        deleteRepositoryPathOrThrow(
            path = ownedProjectorPath,
            recursive = false,
            description = "视觉投影器",
            deleteFile = deleteFile,
            deleteRecursively = deleteRecursively
        )
    }
    deleteRepositoryPathOrThrow(
        path = mainDeletionTarget,
        recursive = mainDeletionTarget.isDirectory,
        description = if (mainDeletionTarget.isDirectory) "模型目录" else "模型文件",
        deleteFile = deleteFile,
        deleteRecursively = deleteRecursively
    )

    requireRepositoryPathAbsent(modelPath, "模型路径")
    ownedProjectorPath?.let { requireRepositoryPathAbsent(it, "视觉投影器路径") }
    updateManifest()
}

private fun deleteRepositoryPathOrThrow(
    path: File,
    recursive: Boolean,
    description: String,
    deleteFile: (File) -> Boolean,
    deleteRecursively: (File) -> Boolean
) {
    if (!path.exists()) return
    val operation = if (recursive) "deleteRecursively()" else "delete()"
    val deleted = try {
        if (recursive) deleteRecursively(path) else deleteFile(path)
    } catch (error: Exception) {
        throw IllegalStateException(
            "删除${description}失败：$operation 抛出 ${error.javaClass.simpleName}" +
                error.message?.let { "：$it" }.orEmpty() +
                "；路径=${path.absolutePath}",
            error
        )
    }
    if (!deleted) {
        val state = if (path.exists()) "路径仍存在" else "路径随后不存在"
        throw IllegalStateException(
            "删除${description}失败：$operation 返回 false，$state；路径=${path.absolutePath}"
        )
    }
    requireRepositoryPathAbsent(path, description)
}

private fun requireRepositoryPathAbsent(path: File, description: String) {
    check(!path.exists()) {
        "删除${description}失败：删除操作返回成功，但路径仍存在；路径=${path.absolutePath}"
    }
}

private fun File.isSameAsOrUnder(root: File): Boolean = runCatching {
    val candidatePath = absoluteFile.toPath().normalize()
    val rootPath = root.absoluteFile.toPath().normalize()
    candidatePath == rootPath || candidatePath.startsWith(rootPath)
}.getOrDefault(false)

/**
 * Keeps the exact destination basename and extension visible while a
 * user-supplied model is copied atomically. GGUF compatibility checks
 * intentionally inspect both the file suffix and basename (for
 * mmproj/MTP/split-part protection), so only the parent directory may carry
 * the transaction marker.
 */
internal fun atomicImportStagingFile(
    target: File,
    transactionId: String = UUID.randomUUID().toString()
): File {
    require(SAFE_IMPORT_TRANSACTION_ID.matches(transactionId)) {
        "Import transaction id contains unsupported characters."
    }
    return File(File(target.parentFile, ".importing-$transactionId"), target.name)
}

/**
 * The repository serializes imports in-process; this final existence check
 * additionally makes a failed commit non-destructive if another producer has
 * materialized the chosen destination since it was selected.
 */

internal fun commitAtomicImportPath(staging: File, target: File): Boolean {
    if (target.exists()) return false
    return staging.renameTo(target)
}

internal fun cleanupAtomicImportStagingFile(staging: File, expectedParent: File): Boolean {
    val root = runCatching { expectedParent.canonicalFile }.getOrNull() ?: return false
    val requestedStagingDirectory = staging.parentFile ?: return false
    val stagingDirectory = runCatching { requestedStagingDirectory.canonicalFile }.getOrNull() ?: return false
    if (requestedStagingDirectory.absolutePath != stagingDirectory.absolutePath) return false
    if (stagingDirectory.parentFile?.canonicalFile != root) return false
    if (!stagingDirectory.name.startsWith(".importing-")) return false
    return stagingDirectory.deleteRecursively()
}

internal fun cleanupStaleAtomicImports(
    managedRoot: File,
    nowMillis: Long = System.currentTimeMillis(),
    minimumAgeMillis: Long = 24L * 60L * 60L * 1000L
): Int {
    val root = runCatching { managedRoot.canonicalFile }.getOrNull()?.takeIf { it.isDirectory }
        ?: return 0
    return root.listFiles().orEmpty().count { candidate ->
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@count false
        if (candidate.absolutePath != canonical.absolutePath) return@count false
        if (canonical.parentFile?.canonicalFile != root) return@count false
        val isStagingArtifact = when {
            canonical.isDirectory -> CURRENT_IMPORT_DIRECTORY.matches(canonical.name) ||
                LEGACY_NAMED_IMPORT_ARTIFACT.matches(canonical.name)
            canonical.isFile -> LEGACY_NAMED_IMPORT_ARTIFACT.matches(canonical.name)
            else -> false
        }
        if (!isStagingArtifact) return@count false
        val modifiedAt = canonical.lastModified().takeIf { it > 0L } ?: return@count false
        if (nowMillis - modifiedAt < minimumAgeMillis) return@count false
        candidate.deleteRecursively()
    }
}

/**
 * `OpenableColumns.SIZE` is advisory SAF metadata. Cloud drives and OEM file
 * providers may return an estimate, a stale value, or the encoded object size;
 * none proves whether the stream reached EOF. Import completeness is therefore
 * established by reading to EOF, hashing the copied bytes, and validating the
 * resulting GGUF/MNN payload. A provider-size mismatch must never reject an
 * otherwise valid import.
 */

internal fun verifyProviderCopyAgainstAdvisorySize(
    providerReportedSize: Long?,
    copiedSize: Long
) {
    require(copiedSize >= 0L) { "Copied byte count cannot be negative." }
    @Suppress("UNUSED_VARIABLE")
    val diagnosticOnly = providerReportedSize?.takeIf { it >= 0L }
}

private val SAFE_IMPORT_TRANSACTION_ID = Regex("[A-Za-z0-9._-]{1,128}")

private const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

private val CURRENT_IMPORT_DIRECTORY = Regex("^\\.importing-$UUID_PATTERN$")

private val LEGACY_NAMED_IMPORT_ARTIFACT = Regex("^\\..+\\.importing-$UUID_PATTERN$")
