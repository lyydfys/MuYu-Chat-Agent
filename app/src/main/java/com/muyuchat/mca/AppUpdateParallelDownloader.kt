package com.muyuchat.mca

import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.DownloadTaskSnapshot
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Parallel range downloader for the 495 MB update APK.  A single API/proxy
 * connection is limited to tens or a few hundred KB/s; several CDN range
 * streams aggregate much faster while keeping the same SHA/size checks.
 */
internal class AppUpdateParallelDownloader(
    private val concurrency: Int = 8,
    private val client: OkHttpClient = defaultUpdateRangeClient()
) {
    suspend fun download(
        url: String,
        expectedBytes: Long,
        tempFile: File,
        finalFile: File,
        onProgress: (DownloadTaskSnapshot) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(expectedBytes > 0L) { "更新 APK 缺少文件大小。" }
        require(isTrustedGitHubDownloadUrl(url)) { "更新 APK 地址不是受信任的 GitHub 地址。" }
        val parent = requireNotNull(tempFile.parentFile) { "更新临时目录无效。" }
        parent.mkdirs()
        val chunkLength = (expectedBytes + concurrency - 1L) / concurrency
        val parts = (0 until concurrency).map { index ->
            val start = index * chunkLength
            val end = ((index + 1L) * chunkLength - 1L).coerceAtMost(expectedBytes - 1L)
            RangePart(index, start, end, File(parent, ".${tempFile.name}.range.$index"))
        }.filter { it.start <= it.end }
        val initial = parts.sumOf { part ->
            val actual = part.file.length()
            if (actual > part.length) {
                part.file.delete()
                0L
            } else {
                actual
            }
        }
        val progress = Progress(
            expectedBytes = expectedBytes,
            initialBytes = initial,
            tempFile = tempFile,
            finalFile = finalFile,
            onProgress = onProgress
        )
        try {
            progress.publish(force = true, status = DownloadStatus.RUNNING)
            coroutineScope {
                parts.map { part ->
                    async(Dispatchers.IO) {
                        downloadPart(url, part, expectedBytes, progress)
                    }
                }.awaitAll()
            }
            val combined = File(parent, tempFile.name)
            combined.delete()
            combined.outputStream().buffered(1024 * 1024).use { output ->
                parts.forEach { part ->
                    part.file.inputStream().buffered(1024 * 1024).use { input ->
                        input.copyTo(output, 1024 * 1024)
                    }
                }
            }
            require(combined.length() == expectedBytes) {
                "更新 APK 大小不匹配：${combined.length()} / $expectedBytes"
            }
            Files.move(
                combined.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            parts.forEach { it.file.delete() }
            progress.publish(force = true, status = DownloadStatus.DONE)
            finalFile
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is RangeUnsupported) {
                parts.forEach { it.file.delete() }
            }
            throw error
        }
    }

    /** Removes partial range files after the caller falls back to a single stream. */
    fun discard(tempFile: File) {
        val parent = tempFile.parentFile ?: return
        (0 until concurrency).forEach { index ->
            File(parent, ".${tempFile.name}.range.$index").delete()
        }
        tempFile.delete()
    }

    private suspend fun downloadPart(
        url: String,
        part: RangePart,
        totalBytes: Long,
        progress: Progress
    ) {
        var existing = part.file.length().coerceAtMost(part.length)
        if (existing == part.length) {
            progress.add(0L)
            return
        }
        var lastError: Throwable? = null
        repeat(4) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                val start = part.start + existing
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$start-${part.end}")
                    .header("Accept", "application/octet-stream,*/*")
                    .header("Accept-Encoding", "identity")
                    .header("User-Agent", "MCA-App-Update")
                    .get()
                    .build()
                val response = awaitResponse(client, request)
                response.use {
                    if (response.code == 416 && existing == part.length) return
                    if (response.code == 200) throw RangeUnsupported()
                    require(response.code == 206) { "更新分片下载失败：HTTP ${response.code}" }
                    val contentRange = response.header("Content-Range")
                    val range = contentRange?.let(CONTENT_RANGE::matchEntire)
                    require(range != null) { "更新分片缺少 Content-Range。" }
                    val responseStart = range.groupValues[1].toLongOrNull()
                    val responseEnd = range.groupValues[2].toLongOrNull()
                    val responseTotal = range.groupValues[3].toLongOrNull()
                    require(
                        responseStart == start &&
                            responseEnd == part.end &&
                            responseTotal == totalBytes
                    ) { "更新分片范围不匹配。" }
                    val body = requireNotNull(response.body) { "更新分片响应为空。" }
                    RandomAccessFile(part.file, "rw").use { output ->
                        output.seek(existing)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                existing += read
                                progress.add(read.toLong())
                            }
                        }
                    }
                }
                require(part.file.length() == part.length) {
                    "更新分片不完整：${part.file.length()} / ${part.length}"
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                if (error is RangeUnsupported) throw error
                if (attempt < 3) delay(700L * (attempt + 1))
            }
        }
        throw IOException("更新分片下载失败。", lastError)
    }

    private suspend fun awaitResponse(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    private data class RangePart(
        val index: Int,
        val start: Long,
        val end: Long,
        val file: File
    ) {
        val length: Long get() = end - start + 1L
    }

    private class RangeUnsupported : IOException("服务端不支持更新分片下载。")

    private inner class Progress(
        private val expectedBytes: Long,
        initialBytes: Long,
        private val tempFile: File,
        private val finalFile: File,
        private val onProgress: (DownloadTaskSnapshot) -> Unit
    ) {
        private val downloaded = AtomicLong(initialBytes)
        private val startedAt = System.nanoTime()
        private val lastPublishAt = AtomicLong(0L)

        fun add(bytes: Long) {
            if (bytes > 0L) downloaded.addAndGet(bytes)
            publish(force = false)
        }

        fun publish(force: Boolean, status: DownloadStatus = DownloadStatus.RUNNING) {
            val now = System.nanoTime()
            val previous = lastPublishAt.get()
            if (!force && now - previous < PUBLISH_INTERVAL_NANOS) return
            if (!lastPublishAt.compareAndSet(previous, now)) return
            val bytes = downloaded.get().coerceAtMost(expectedBytes)
            val elapsedSeconds = ((now - startedAt) / 1_000_000_000L).coerceAtLeast(1L)
            val speed = (bytes / elapsedSeconds).coerceAtLeast(0L)
            val remaining = if (speed > 0L) {
                val left = expectedBytes - bytes
                left / speed + if (left % speed == 0L) 0L else 1L
            } else null
            onProgress(
                DownloadTaskSnapshot(
                    repoId = GITHUB_RELEASE_REPOSITORY,
                    revision = finalFile.name,
                    fileName = finalFile.name,
                    url = "",
                    expectedLength = expectedBytes,
                    downloadedBytes = bytes,
                    speedBytesPerSecond = speed,
                    remainingSeconds = remaining,
                    tempFile = tempFile,
                    finalFile = finalFile,
                    status = status
                )
            )
        }
    }

    private companion object {
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        private val PUBLISH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L)
    }
}

private fun defaultUpdateRangeClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(true)
    .followSslRedirects(true)
    .retryOnConnectionFailure(true)
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.SECONDS)
    .addNetworkInterceptor { chain ->
        require(isTrustedGitHubDownloadUrl(chain.request().url.toString())) {
            "更新 APK 分片重定向到了不受信任的地址。"
        }
        chain.proceed(chain.request())
    }
    .build()
