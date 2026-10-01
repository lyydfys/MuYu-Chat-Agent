package com.muyuchat.mca

import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex

/** Import, download and recovery must never promote different packages concurrently. */
internal object OfflinePromptTranslationPackageActivation {
    val gate = Mutex()
}

/**
 * Stage and destination are sibling directories on app-private storage. Only a fully verified
 * stage is renamed into the active path; any failure restores the previous active package.
 * The caller owns [OfflinePromptTranslationPackageActivation.gate].
 */
internal fun <T> activateOfflinePromptTranslationPackage(
    stageRoot: File,
    activeRoot: File,
    verify: (File) -> T
): T {
    require(stageRoot.canonicalFile.parentFile == activeRoot.canonicalFile.parentFile) {
        "离线翻译暂存包与安装目录不在同一存储位置。"
    }
    require(stageRoot.canonicalFile != activeRoot.canonicalFile) { "离线翻译暂存目录不能是已安装目录。" }
    verify(stageRoot)
    val parent = requireNotNull(activeRoot.parentFile)
    val preserved = if (activeRoot.exists()) {
        File(parent, "${activeRoot.name}.invalid-${UUID.randomUUID()}").also {
            check(activeRoot.renameTo(it)) { "无法保留原有离线翻译包。" }
        }
    } else null
    try {
        check(stageRoot.renameTo(activeRoot)) { "无法启用已校验的离线翻译包。" }
        return verify(activeRoot)
    } catch (error: Throwable) {
        if (activeRoot.exists()) {
            val recoverable = File(parent, "${activeRoot.name}.recovery-${UUID.randomUUID()}")
            check(activeRoot.renameTo(recoverable)) { "离线翻译安装失败；候选包仍保留在安装目录。" }
        }
        if (preserved != null) check(preserved.renameTo(activeRoot)) {
            "离线翻译安装失败；原有模型文件仍保留在备份目录。"
        }
        throw error
    }
}
