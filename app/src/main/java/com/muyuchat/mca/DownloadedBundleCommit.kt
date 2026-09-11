package com.muyuchat.mca

import java.io.File
import java.io.IOException
import java.util.UUID

internal fun promoteImageBundleCandidate(candidateDir: File, bundleDir: File): File? {
    val candidate = candidateDir.canonicalFile
    val destination = bundleDir.canonicalFile
    val parent = requireNotNull(destination.parentFile)
    require(candidate.isDirectory && candidate.parentFile == parent && candidate != destination) {
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
    val candidate = File(destination.parentFile, ".${destination.name}.candidate")
    if (destination.exists()) {
        val retained = if (!candidate.exists()) candidate
            else File(destination.parentFile, ".${destination.name}.recovery-${UUID.randomUUID()}")
        check(destination.renameTo(retained)) { "无法保留待重试的模型文件：$destination" }
    }
    if (backup != null && backup.exists() && !backup.renameTo(destination)) {
        throw IOException("无法恢复旧模型，文件仍保留在：$backup")
    }
}
