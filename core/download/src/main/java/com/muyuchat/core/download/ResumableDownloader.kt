package com.muyuchat.core.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ResumableDownloader(
    private val client: OkHttpClient = defaultClient(),
    private val maxRetries: Int = 8,
    private val retryDelayMs: Long = 1_200L
) {
    suspend fun download(
        remote: RemoteModelFile,
        tempFile: File,
        finalFile: File,
        onProgress: (DownloadTaskSnapshot) -> Unit = {}
    ): DownloadTaskSnapshot = withContext(Dispatchers.IO) {
        tempFile.parentFile?.mkdirs()
        finalFile.parentFile?.mkdirs()

        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= maxRetries) {
            currentCoroutineContext().ensureActive()
            try {
                return@withContext downloadAttempt(remote, tempFile, finalFile, onProgress)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
                val willRetry = attempt < maxRetries
                val progressMessage = retryMessage(
                    error = error,
                    tempFile = tempFile,
                    attempt = attempt + 1,
                    totalAttempts = maxRetries + 1,
                    willRetry = willRetry
                )
                onProgress(
                    snapshot(
                        remote = remote,
                        tempFile = tempFile,
                        finalFile = finalFile,
                        expectedLength = remote.sizeBytes ?: 0L,
                        downloaded = tempFile.length().coerceAtLeast(0L),
                        status = DownloadStatus.FAILED,
                        errorMessage = progressMessage
                    )
                )
                if (!willRetry) break
                delay((retryDelayMs * (attempt + 1)).coerceAtMost(12_000L))
                attempt += 1
            }
        }

        throw IOException(
            "下载未完成，已保留临时文件（${formatBytes(tempFile.length())}）。重新点击下载会从已下载位置续传。${friendlyError(lastError)}",
            lastError
        )
    }

    private fun downloadAttempt(
        remote: RemoteModelFile,
        tempFile: File,
        finalFile: File,
        onProgress: (DownloadTaskSnapshot) -> Unit
    ): DownloadTaskSnapshot {
        var downloaded = tempFile.takeIf { it.exists() }?.length() ?: 0L
        val request = request(remote.downloadUrl, downloaded)
        val startedAt = System.currentTimeMillis()
        var lastProgressAt = startedAt

        client.newCall(request).execute().use { response ->
            val contentRangeTotal = response.header("Content-Range")?.contentRangeTotal()
            val knownLength = remote.sizeBytes ?: contentRangeTotal ?: 0L

            if (response.code == 416 && knownLength > 0L && downloaded >= knownLength) {
                return finalizeDownload(remote, tempFile, finalFile, knownLength, onProgress)
            }

            require(response.isSuccessful || response.code == 206) {
                "模型下载失败：HTTP ${response.code}"
            }

            // Append only when the server explicitly honored the requested range.
            // A 200 response is a fresh full body; appending to stale bytes corrupts it.
            val append = downloaded > 0L && response.code == 206 &&
                response.header("Content-Range")?.startsWith("bytes $downloaded-") == true
            if (!append) {
                downloaded = 0L
                if (tempFile.exists()) tempFile.delete()
            }

            val expectedLength = remote.sizeBytes
                ?: response.header("Content-Range")?.contentRangeTotal()
                ?: response.header("Content-Length")?.toLongOrNull()?.plus(if (append) downloaded else 0L)
                ?: 0L

            onProgress(snapshot(remote, tempFile, finalFile, expectedLength, downloaded, DownloadStatus.RUNNING))

            val body = requireNotNull(response.body) { "下载响应为空。" }
            RandomAccessFile(tempFile, "rw").use { output ->
                output.seek(downloaded)
                if (!append) output.setLength(0L)
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastProgressBytes = downloaded
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        currentThreadInterruptedCheck()
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastProgressBytes >= PROGRESS_STEP_BYTES) {
                            val now = System.currentTimeMillis()
                            val speed = speedBytes(downloaded - lastProgressBytes, now - lastProgressAt)
                            lastProgressBytes = downloaded
                            lastProgressAt = now
                            onProgress(
                                snapshot(
                                    remote = remote,
                                    tempFile = tempFile,
                                    finalFile = finalFile,
                                    expectedLength = expectedLength,
                                    downloaded = downloaded,
                                    status = DownloadStatus.RUNNING,
                                    speedBytesPerSecond = speed
                                )
                            )
                        }
                    }
                }
            }

            return finalizeDownload(remote, tempFile, finalFile, expectedLength, onProgress)
        }
    }

    private fun finalizeDownload(
        remote: RemoteModelFile,
        tempFile: File,
        finalFile: File,
        expectedLength: Long,
        onProgress: (DownloadTaskSnapshot) -> Unit
    ): DownloadTaskSnapshot {
        if (expectedLength > 0L) {
            val actualLength = tempFile.length()
            if (actualLength != expectedLength) {
                if (actualLength > expectedLength) tempFile.delete()
                error("下载大小不匹配：$actualLength / $expectedLength。${if (actualLength > expectedLength) "已删除异常临时文件，请重新下载。" else "已保留临时文件，下次会继续续传。"}")
            }
        }
        if (!remote.sha256.isNullOrBlank()) {
            val actual = sha256(tempFile)
            if (!actual.equals(remote.sha256, ignoreCase = true)) {
                tempFile.delete()
                error("SHA-256 校验失败，已删除损坏临时文件，请重新下载。实际：$actual")
            }
        }

        if (finalFile.exists()) finalFile.delete()
        require(tempFile.renameTo(finalFile)) { "模型文件重命名失败。" }
        val done = snapshot(remote, tempFile, finalFile, finalFile.length(), finalFile.length(), DownloadStatus.DONE)
        onProgress(done)
        return done
    }

    private fun request(url: String, downloaded: Long): Request {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/octet-stream,*/*")
            .header("Accept-Encoding", "identity")
        if (downloaded > 0L) builder.header("Range", "bytes=$downloaded-")
        return builder.build()
    }

    private fun snapshot(
        remote: RemoteModelFile,
        tempFile: File,
        finalFile: File,
        expectedLength: Long,
        downloaded: Long,
        status: DownloadStatus,
        speedBytesPerSecond: Long = 0L,
        errorMessage: String? = null
    ): DownloadTaskSnapshot = DownloadTaskSnapshot(
        repoId = remote.repoId,
        revision = remote.revision,
        fileName = remote.name,
        url = remote.downloadUrl,
        expectedLength = expectedLength,
        downloadedBytes = downloaded,
        speedBytesPerSecond = speedBytesPerSecond,
        remainingSeconds = remainingSeconds(expectedLength, downloaded, speedBytesPerSecond),
        errorMessage = errorMessage,
        tempFile = tempFile,
        finalFile = finalFile,
        status = status
    )

    private fun speedBytes(bytes: Long, elapsedMs: Long): Long {
        if (bytes <= 0L || elapsedMs <= 0L) return 0L
        return (bytes * 1000L / elapsedMs).coerceAtLeast(0L)
    }

    private fun remainingSeconds(expectedLength: Long, downloaded: Long, speedBytesPerSecond: Long): Long? {
        if (expectedLength <= 0L || speedBytesPerSecond <= 0L || downloaded >= expectedLength) return null
        return ((expectedLength - downloaded) / speedBytesPerSecond).coerceAtLeast(0L)
    }

    private fun retryMessage(
        error: Throwable,
        tempFile: File,
        attempt: Int,
        totalAttempts: Int,
        willRetry: Boolean
    ): String {
        val prefix = if (willRetry) {
            "下载连接中断，正在重试 $attempt/$totalAttempts。"
        } else {
            "下载多次中断，已暂停。"
        }
        val resume = "已保留 ${formatBytes(tempFile.length())} 临时文件，重新点击下载会尝试续传。"
        return "$prefix$resume${friendlyError(error)}"
    }

    private fun friendlyError(error: Throwable?): String {
        val raw = error?.message.orEmpty()
        val lower = raw.lowercase()
        return when {
            raw.isBlank() -> "如网络不稳定，请切换 Wi-Fi 或保持屏幕常亮后重试。"
            "software caused connection abort" in lower ||
                "unexpected end of stream" in lower ||
                "socket closed" in lower ||
                "connection reset" in lower ->
                "原因：网络连接被系统或远端中断，通常可续传。"
            "timeout" in lower || "timed out" in lower ->
                "原因：网络超时。建议切换更稳定的 Wi-Fi 后继续下载。"
            "http 403" in lower ->
                "原因：远端拒绝访问。请确认模型权限、ModelScope 登录状态或访问令牌。"
            "http 404" in lower ->
                "原因：文件地址不存在或仓库 revision 已变化。请刷新模型列表后重试。"
            "http 5" in lower ->
                "原因：ModelScope 服务端临时异常。稍后重试通常可恢复。"
            "sha-256" in lower ->
                "原因：校验失败，损坏临时文件会被删除，请重新下载。"
            "大小不匹配" in raw ->
                "原因：文件长度不一致。MCA 会保留未完成临时文件并优先续传。"
            "no space" in lower || "enospc" in lower || "空间" in raw ->
                "原因：存储空间不足。请清理空间后继续。"
            else -> "最后错误：$raw"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        val mb = bytes / 1024.0 / 1024.0
        return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(mb)
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

    private fun String.contentRangeTotal(): Long? =
        substringAfterLast('/', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() && it != "*" }
            ?.toLongOrNull()

    private fun currentThreadInterruptedCheck() {
        if (Thread.currentThread().isInterrupted) throw IOException("下载线程已中断。")
    }

    companion object {
        private const val USER_AGENT = "MCA/0.1 ModelScopeDownloader"
        private const val PROGRESS_STEP_BYTES = 1L * 1024L * 1024L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
