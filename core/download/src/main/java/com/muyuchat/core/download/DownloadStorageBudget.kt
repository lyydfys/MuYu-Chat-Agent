package com.muyuchat.core.download

import java.io.File
import java.io.IOException

/** Leave room for the model catalog, SQLite journals and Android to keep working. */
class DownloadStorageBudget(
    private val reserveBytes: Long = 128L * 1024 * 1024,
    private val availableBytes: (File) -> Long = { it.usableSpace }
) {
    fun requireAvailable(destination: File, nextBytes: Long = 0L) {
        var volume = destination
        while (!volume.exists() && volume.parentFile != null) volume = volume.parentFile!!
        val available = availableBytes(volume)
        if (available < reserveBytes || nextBytes.coerceAtLeast(0L) > available - reserveBytes) {
            throw InsufficientDownloadSpaceException()
        }
    }
}

class InsufficientDownloadSpaceException : IOException(
    "存储空间不足，已暂停并保留下载进度。请释放空间后继续下载（需额外保留 128 MB 运行空间）。"
)

fun Throwable.isDownloadStorageFailure(): Boolean = generateSequence(this) { it.cause }
    .take(16).any {
        it is InsufficientDownloadSpaceException ||
            it.message.orEmpty().let { message ->
                message.contains("ENOSPC", true) || message.contains("No space left", true)
            }
    }
