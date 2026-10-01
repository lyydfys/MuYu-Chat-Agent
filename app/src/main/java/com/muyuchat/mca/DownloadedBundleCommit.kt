package com.muyuchat.mca

import java.io.File
import java.io.IOException
import java.util.UUID

internal fun imageBundleCandidateDirectory(bundleDir: File): File {
    val destination = bundleDir.canonicalFile
    val parent = requireNotNull(destination.parentFile) { "模型目录必须有父目录。" }
    return File(parent, ".${destination.name}.candidate")
}

internal fun promoteImageBundleCandidate(candidateDir: File, bundleDir: File): File? {
    val candidate = candidateDir.canonicalFile
    val destination = bundleDir.canonicalFile
    val parent = requireNotNull(destination.parentFile)
    require(candidate.isDirectory && candidate == imageBundleCandidateDirectory(destination).canonicalFile) {
        "模型候选目录不存在或不在目标目录旁。"
    }
    val backup = File(parent, ".${destination.name}.backup")
    if (backup.exists()) {
        if (!destination.exists()) {
            check(backup.renameTo(destination)) { "无法恢复上次中断前的模型：$backup" }
        } else {
            // An interrupted older transaction may own this backup. Keep it until
            // the user can inspect it; never destroy the only known previous copy.
            val retained = File(parent, ".${destination.name}.recovery-${UUID.randomUUID()}")
            check(backup.renameTo(retained)) { "无法保留上次模型备份：$backup" }
        }
    }
    val hadDestination = destination.exists()
    if (hadDestination && !destination.renameTo(backup)) throw IOException("无法备份现有模型：$destination")
    if (!candidate.renameTo(destination)) {
        if (hadDestination && !backup.renameTo(destination)) throw IOException("提交和恢复均未完成，原模型保留在：$backup")
        throw IOException("无法提交模型候选目录：$candidate")
    }
    return backup.takeIf { hadDestination }
}

internal fun restoreImageBundleBackup(bundleDir: File, backup: File?) {
    val destination = bundleDir.canonicalFile
    val candidate = imageBundleCandidateDirectory(destination)
    if (destination.exists()) {
        val retained = if (!candidate.exists()) candidate
            else File(destination.parentFile, ".${destination.name}.recovery-${UUID.randomUUID()}")
        check(destination.renameTo(retained)) { "无法保留待重试的模型文件：$destination" }
    }
    if (backup != null && backup.exists() && !backup.renameTo(destination)) {
        throw IOException("无法恢复旧模型，文件仍保留在：$backup")
    }
}

/**
 * Publish validated files and their catalog entry as one recoverable operation.
 * Call inside the owning store's catalog transaction. Once publish returns, cleanup
 * must never turn a committed install into a rollback or discard its previous copy.
 */
internal fun <T> publishDownloadedBundleCandidate(
    candidateDir: File,
    bundleDir: File,
    onCleanupFailure: (Throwable) -> Unit = {},
    publish: () -> T
): T {
    val backup = promoteImageBundleCandidate(candidateDir, bundleDir)
    val result = try {
        publish()
    } catch (error: Throwable) {
        runCatching { restoreImageBundleBackup(bundleDir, backup) }
            .exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }
    if (backup != null) {
        runCatching {
            check(backup.deleteRecursively()) { "安装已提交，但旧模型备份清理失败：$backup" }
        }.exceptionOrNull()?.let { error -> runCatching { onCleanupFailure(error) } }
    }
    return result
}
