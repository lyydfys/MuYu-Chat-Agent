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
                "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1)
            )
            output.write(bytes, 0, firstChunkBytes)
            output.flush()
        }

        private fun writeRemainder(client: Socket) {
            val output = client.getOutputStream()
            val remaining = bytes.size - firstChunkBytes
            output.write(
                "HTTP/1.1 206 Partial Content\r\nContent-Length: $remaining\r\nContent-Range: bytes $firstChunkBytes-${bytes.lastIndex}/${bytes.size}\r\nConnection: close\r\n\r\n"
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
