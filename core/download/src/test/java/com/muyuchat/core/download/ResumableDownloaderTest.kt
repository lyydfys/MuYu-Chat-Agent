package com.muyuchat.core.download

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread

class ResumableDownloaderTest {
    @Test
    fun rateEstimatorDampensSingleChunkSpikes() {
        val estimator = DownloadRateEstimator()
        val first = estimator.update(bytes = 1_000_000L, elapsedMs = 1_000L)
        val spike = estimator.update(bytes = 1_000_000L, elapsedMs = 100L)

        assertEquals(1_000_000L, first)
        assertTrue("The displayed rate should be smoothed", spike < 10_000_000L)
        assertTrue("The displayed rate should still react to faster transfer", spike > first)
    }

    @Test fun sameMillisecondReadsContributeToNextMeasuredRate() {
        val estimator = DownloadRateEstimator()
        assertEquals(0L, estimator.update(100L, 0L))
        assertEquals(0L, estimator.update(200L, 0L))
        assertEquals(1_000L, estimator.update(700L, 1_000L))
    }

    @Test fun estimatorUsesElapsedTimeRatherThanNumberOfChunks() {
        val whole = DownloadRateEstimator()
        val split = DownloadRateEstimator()
        whole.update(1000L, 1000L)
        split.update(1000L, 1000L)
        val wholeRate = whole.update(4000L, 1000L)
        repeat(4) { split.update(1000L, 250L) }
        assertTrue(kotlin.math.abs(wholeRate - split.update(0L, 0L)) <= 2L)
    }

    @Test fun etaUsesCeilingDivisionAndDoesNotOverflow() {
        assertEquals(4L, estimateDownloadRemainingSeconds(10L, 0L, 3L))
        assertEquals(Long.MAX_VALUE, estimateDownloadRemainingSeconds(Long.MAX_VALUE, 0L, 1L))
        assertEquals(1L, estimateDownloadRemainingSeconds(Long.MAX_VALUE, 1L, Long.MAX_VALUE))
        assertEquals(null, estimateDownloadRemainingSeconds(0L, 0L, 1L))
        assertEquals(null, estimateDownloadRemainingSeconds(10L, 0L, 0L))
        assertEquals(null, estimateDownloadRemainingSeconds(10L, 10L, 1L))
        assertEquals(null, estimateDownloadRemainingSeconds(10L, -1L, 1L))
    }

    @Test
    fun pendingSameMillisecondBytesAreConsumedOnceAndInvalidSamplesDoNotDiscardThem() {
        val estimator = DownloadRateEstimator(smoothingFactor = 1.0)
        assertEquals(1_000L, estimator.update(1_000L, 1_000L))
        assertEquals(1_000L, estimator.update(100L, 0L))
        assertEquals(1_000L, estimator.update(200L, -1L))
        assertEquals(1_000L, estimator.update(-20L, 1L))

        // Only the valid pending 100 bytes and the new 300 bytes count.
        assertEquals(400L, estimator.update(300L, 1_000L))
        assertEquals(400L, estimator.update(0L, 0L))
        // A later interval must not recount the consumed pending bytes.
        assertEquals(600L, estimator.update(600L, 1_000L))
    }

    @Test
    fun pendingByteAndInstantaneousRateOverflowSaturateThenRecover() {
        val estimator = DownloadRateEstimator(smoothingFactor = 1.0)
        assertEquals(0L, estimator.update(Long.MAX_VALUE, 0L))
        assertEquals(0L, estimator.update(Long.MAX_VALUE, 0L))
        assertEquals(Long.MAX_VALUE, estimator.update(1L, 1L))
        assertEquals(100L, estimator.update(100L, 1_000L))
    }

    @Test
    fun etaRetainsLongPrecisionAndRoundsOnlyIncompleteSecondsUp() {
        val halfMax = Long.MAX_VALUE / 2L
        assertEquals(3L, estimateDownloadRemainingSeconds(Long.MAX_VALUE, 0L, halfMax))
        assertEquals(2L, estimateDownloadRemainingSeconds(Long.MAX_VALUE, 1L, halfMax))
        assertEquals(2_147_483_648L,
            estimateDownloadRemainingSeconds(2_147_483_648L, 0L, 1L))
        assertEquals(1L, estimateDownloadRemainingSeconds(Long.MAX_VALUE, Long.MAX_VALUE - 1L, 1L))
    }

    @Test
    fun etaIsUnavailableForUnknownSizesInvalidRatesAndFinishedTransfers() {
        assertEquals(null, estimateDownloadRemainingSeconds(-1L, 0L, 100L))
        assertEquals(null, estimateDownloadRemainingSeconds(0L, 10L, 100L))
        assertEquals(null, estimateDownloadRemainingSeconds(100L, 20L, -1L))
        assertEquals(null, estimateDownloadRemainingSeconds(100L, 20L, 0L))
        assertEquals(null, estimateDownloadRemainingSeconds(100L, -1L, 100L))
        assertEquals(null, estimateDownloadRemainingSeconds(100L, 100L, 100L))
        assertEquals(null, estimateDownloadRemainingSeconds(100L, 101L, 100L))
    }

    @Test
    fun progressSpeedUsesMonotonicElapsedTime() = runBlocking {
        val bytes = ByteArray(1024 * 1024 + 1024) { 0x41 }
        FixedContentServer(bytes).use { server ->
            val tempDir = Files.createTempDirectory("mca-download-speed-test").toFile()
            try {
                val remote = RemoteModelFile(
                    repoId = "owner/model",
                    revision = "main",
                    path = "model.gguf",
                    name = "model.gguf",
                    sizeBytes = bytes.size.toLong(),
                    downloadUrl = server.url
                )
                var monotonicReads = 0
                val speeds = mutableListOf<Long>()
                ResumableDownloader(
                    maxRetries = 0,
                    monotonicNanos = { if (monotonicReads++ == 0) 0L else 1_000_000_000L }
                ).download(remote, File(tempDir, "model.gguf.part"), File(tempDir, "model.gguf")) { snapshot ->
                    if (snapshot.status == DownloadStatus.RUNNING && snapshot.speedBytesPerSecond > 0L) {
                        speeds += snapshot.speedBytesPerSecond
                    }
                }

                assertEquals(1, speeds.size)
                // The HTTP test server may satisfy the read with either the
                // 1 MiB progress boundary or the full 1 MiB + 1 KiB body.
                assertTrue(speeds.single() >= 1_048_576L)
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    @Test
    fun resumesFromPartialTempFileAfterConnectionAbort() = runBlocking {
        val bytes = "0123456789".toByteArray()
        PartialContentServer(bytes, firstChunkBytes = 5).use { server ->
            val tempDir = Files.createTempDirectory("mca-download-test").toFile()
            try {
                val temp = File(tempDir, "model.gguf.part")
                val final = File(tempDir, "model.gguf")
                val remote = RemoteModelFile(
                    repoId = "owner/model",
                    revision = "master",
                    path = "model.gguf",
                    name = "model.gguf",
                    sizeBytes = bytes.size.toLong(),
                    downloadUrl = server.url
                )

                val snapshot = ResumableDownloader(maxRetries = 1, retryDelayMs = 1L)
                    .download(remote, temp, final)

                assertEquals(DownloadStatus.DONE, snapshot.status)
                assertEquals(bytes.decodeToString(), final.readBytes().decodeToString())
                assertTrue(server.rangeHeaders.any { it == "bytes=5-" })
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    @Test
    fun checksumMismatchDeletesTempFileAndExplainsRetry() = runBlocking {
        val bytes = "abc".toByteArray()
        FixedContentServer(bytes).use { server ->
            val tempDir = Files.createTempDirectory("mca-download-sha-test").toFile()
            try {
                val temp = File(tempDir, "model.gguf.part")
                val final = File(tempDir, "model.gguf")
                val remote = RemoteModelFile(
                    repoId = "owner/model",
                    revision = "master",
                    path = "model.gguf",
                    name = "model.gguf",
                    sizeBytes = bytes.size.toLong(),
                    sha256 = "deadbeef",
                    downloadUrl = server.url
                )

                val error = runCatching {
                    ResumableDownloader(maxRetries = 0, retryDelayMs = 1L)
                        .download(remote, temp, final)
                }.exceptionOrNull()

                assertTrue(error?.message.orEmpty().contains("校验失败"))
                assertFalse(temp.exists())
                assertFalse(final.exists())
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    @Test
    fun zeroByteResponseIsRejectedWithoutPublishingAnEmptyFinalFile() = runBlocking {
        FixedContentServer(byteArrayOf()).use { server ->
            val tempDir = Files.createTempDirectory("mca-empty-download-test").toFile()
            try {
                val temp = File(tempDir, "model.gguf.part")
                val final = File(tempDir, "model.gguf")
                val remote = RemoteModelFile(
                    repoId = "owner/model",
                    revision = "main",
                    path = "model.gguf",
                    name = "model.gguf",
                    sizeBytes = null,
                    sha256 = null,
                    downloadUrl = server.url
                )

                val error = runCatching {
                    ResumableDownloader(maxRetries = 0)
                        .download(remote, temp, final)
                }.exceptionOrNull()

                assertTrue(error?.message.orEmpty().contains("内容为空"))
                assertFalse("An empty response must never become a completed model", final.exists())
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    private class PartialContentServer(
        private val bytes: ByteArray,
        private val firstChunkBytes: Int
    ) : Closeable {
        private val socket = ServerSocket(0)
        private val worker = thread(start = true, isDaemon = true) { serve() }
        val rangeHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val url: String = "http://127.0.0.1:${socket.localPort}/model.gguf"

        private fun serve() {
            repeat(2) { index ->
                runCatching {
                    socket.accept().use { client ->
                        val headers = readHeaders(client)
                        rangeHeaders += headers["range"]
                        if (index == 0) {
                            writeFirstPartial(client)
                        } else {
                            writeRemainder(client)
                        }
                    }
                }
            }
        }

        private fun readHeaders(client: Socket): Map<String, String> {
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) headers[parts[0].trim().lowercase()] = parts[1].trim()
            }
            return headers
        }

        private fun writeFirstPartial(client: Socket) {
            val output = client.getOutputStream()
            output.write(
                "HTTP/1.1 200 OK\r\nETag: \"stable-object\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1)
            )
            output.write(bytes, 0, firstChunkBytes)
            output.flush()
        }

        private fun writeRemainder(client: Socket) {
            val output = client.getOutputStream()
            val remaining = bytes.size - firstChunkBytes
            output.write(
                "HTTP/1.1 206 Partial Content\r\nETag: \"stable-object\"\r\nContent-Length: $remaining\r\nContent-Range: bytes $firstChunkBytes-${bytes.lastIndex}/${bytes.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1)
            )
            output.write(bytes, firstChunkBytes, remaining)
            output.flush()
        }

        override fun close() {
            socket.close()
            worker.join(1_000L)
        }
    }

    private class FixedContentServer(private val bytes: ByteArray) : Closeable {
        private val socket = ServerSocket(0)
        private val worker = thread(start = true, isDaemon = true) { serve() }
        val url: String = "http://127.0.0.1:${socket.localPort}/model.gguf"

        private fun serve() {
            runCatching {
                socket.accept().use { client ->
                    readHeaders(client)
                    val output = client.getOutputStream()
                    output.write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    output.write(bytes)
                    output.flush()
                }
            }
        }

        private fun readHeaders(client: Socket) {
            val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
            }
        }

        override fun close() {
            socket.close()
            worker.join(1_000L)
        }
    }
}
