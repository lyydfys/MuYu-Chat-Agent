package com.muyuchat.core.download

import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ResumableDownloadObjectIdentityTest {
    @Test fun changedSameLengthObjectRestartsInsteadOfJoiningVersions() = runBlocking {
        SequenceServer(listOf(
            reply(200, "oldoldold0", "v1", sendBytes = 5),
            reply(200, "newnewnew0", "v2")
        )).use { server ->
            val root = Files.createTempDirectory("mca-object-change").toFile()
            try {
                val result = ResumableDownloader(maxRetries = 1, retryDelayMs = 1).download(
                    remote(server.url), File(root, "part"), File(root, "model"))
                assertEquals("newnewnew0", result.finalFile.readText())
                assertEquals("bytes=5-", server.requests[1]["range"])
                assertEquals("\"v1\"", server.requests[1]["if-range"])
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun invalidPartialRangeIsDiscardedBeforeRecovery() = runBlocking {
        SequenceServer(listOf(
            reply(200, "0123456789", "v1", sendBytes = 5),
            reply(206, "BADBAD", "v1", range = "bytes 4-9/10"),
            reply(200, "abcdefghij", "v2")
        )).use { server ->
            val root = Files.createTempDirectory("mca-invalid-range").toFile()
            try {
                val result = ResumableDownloader(maxRetries = 2, retryDelayMs = 1).download(
                    remote(server.url), File(root, "part"), File(root, "model"))
                assertEquals("abcdefghij", result.finalFile.readText())
                assertNull(server.requests[2]["range"])
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun legacyPartialWithoutPublisherDigestOrValidatorIsNotTrusted() = runBlocking {
        SequenceServer(listOf(reply(200, "abcdefghij", "v2"))).use { server ->
            val root = Files.createTempDirectory("mca-legacy-range").toFile()
            try {
                val part = File(root, "part").apply { writeText("OLDOL") }
                val result = ResumableDownloader(maxRetries = 0).download(remote(server.url), part, File(root, "model"))
                assertEquals("abcdefghij", result.finalFile.readText())
                assertNull(server.requests.single()["range"])
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun changedValidatorOn206NeverCommitsMixedBytes() = runBlocking {
        SequenceServer(listOf(
            reply(200, "0123456789", "v1", sendBytes = 5),
            reply(206, "abcde", "v2", range = "bytes 5-9/10")
        )).use { server ->
            val root = Files.createTempDirectory("mca-validator-change").toFile()
            try {
                val final = File(root, "model").apply { writeText("previous-good-model") }
                val error = runCatching { ResumableDownloader(maxRetries = 1, retryDelayMs = 1)
                    .download(remote(server.url), File(root, "part"), final) }.exceptionOrNull()
                assertNotNull(error)
                assertEquals("previous-good-model", final.readText())
                assertFalse(File(root, "part").exists())
            } finally { root.deleteRecursively() }
        }
    }

    private fun remote(url: String) = RemoteModelFile("owner/model", "main", "nested/model.bin", "model.bin", 10, downloadUrl = url)
    private data class Reply(val header: String, val bytes: ByteArray)
    private fun reply(code: Int, content: String, etag: String, sendBytes: Int = content.length, range: String? = null): Reply =
        Reply("HTTP/1.1 $code OK\r\nETag: \"$etag\"\r\nContent-Length: "+content.length+"\r\n"+
            (range?.let { "Content-Range: $it\r\n" } ?: "")+"Connection: close\r\n\r\n", content.toByteArray().take(sendBytes).toByteArray())
    private class SequenceServer(private val replies: List<Reply>) : Closeable {
        private val socket = ServerSocket(0)
        private val executor = Executors.newSingleThreadExecutor()
        val url = "http://127.0.0.1:"+socket.localPort+"/file"
        val requests: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
        init { executor.submit {
            for(reply in replies) {
                if(socket.isClosed) break
                socket.accept().use { connection ->
                    val reader = connection.getInputStream().bufferedReader()
                    reader.readLine()
                    val headers = mutableMapOf<String, String>()
                    while(true) { val line = reader.readLine() ?: break; if(line.isEmpty()) break
                        val split = line.indexOf(':'); if(split > 0) headers[line.substring(0,split).lowercase()] = line.substring(split+1).trim()
                    }
                    requests += headers
                    connection.getOutputStream().apply { write(reply.header.toByteArray()); write(reply.bytes); flush() }
                }
            }
        } }
        override fun close() { socket.close(); executor.shutdownNow() }
    }
}
