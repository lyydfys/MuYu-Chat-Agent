package com.muyuchat.core.download

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DownloadRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun lowSpaceDoesNotRetryOrReplaceUsableModel() = runBlocking {
        val calls = AtomicInteger()
        val bytes = "replacement".toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(okhttp3.ResponseBody.create(null, bytes)).build()
        }.build()
        val final = temp.newFile("model.gguf").apply { writeText("usable previous model") }
        val partial = temp.newFile("model.part").apply { writeText("old partial") }
        val error = runCatching {
            ResumableDownloader(client, retryDelayMs = 1,
                storageBudget = DownloadStorageBudget(128) { 127 }).download(remote(), partial, final)
        }.exceptionOrNull()
        assertTrue(error?.isDownloadStorageFailure() == true)
        assertEquals(1, calls.get())
        assertEquals("usable previous model", final.readText())
    }

    @Test fun cancellationClosesSocketAndDoesNotPublishPartialModel() = runBlocking {
        val server = ServerSocket(0)
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val serving = thread(isDaemon = true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrBlank()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\na".toByteArray()); flush()
                }
                connected.countDown()
                while (socket.getInputStream().read() >= 0) { }
                disconnected.countDown()
            }
        }
        try {
            val partial = File(temp.root, "cancel.part")
            val final = File(temp.root, "cancel.gguf")
            val job = launch(Dispatchers.IO) {
                ResumableDownloader().download(remote().copy(downloadUrl = "http://127.0.0.1:${server.localPort}/model"), partial, final)
            }
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { job.cancelAndJoin() }
            assertTrue(disconnected.await(5, TimeUnit.SECONDS))
            assertFalse(final.exists())
        } finally { server.close(); serving.join(1000) }
    }

    @Test fun extractionResumesCompletedEntriesAfterSpaceFailure() = runBlocking {
        val archive = archive("a.bin" to "first", "b.bin" to "second")
        val destination = temp.newFolder("candidate")
        val lowOnSecond = DownloadStorageBudget(0) { if (File(destination, "a.bin").exists()) 0 else 10000000 }
        assertTrue(runCatching { extractResumableModelZip(archive, destination, lowOnSecond) }.isFailure)
        val completed = File(destination, "a.bin")
        assertEquals("first", completed.readText())
        completed.setLastModified(100000)
        extractResumableModelZip(archive, destination, DownloadStorageBudget(0) { 10000000 })
        assertEquals(100000, completed.lastModified())
        assertEquals("second", File(destination, "b.bin").readText())
    }

    @Test fun zipTraversalAndExpansionLimitAreRejected() = runBlocking {
        val destination = temp.newFolder("bounded")
        assertTrue(runCatching { extractResumableModelZip(archive("../escaped" to "bad"), destination) }.isFailure)
        assertFalse(File(temp.root, "escaped").exists())
        assertTrue(runCatching { extractResumableModelZip(archive("huge.bin" to "123456"), destination,
            maxExpandedBytes = 5) }.isFailure)
        assertFalse(File(destination, "huge.bin").exists())
    }

    @Test fun resumedDownloadOnlyReservesRemainingBytes() {
        val budget = DownloadStorageBudget(128) { 200 }
        budget.requireAvailable(temp.root, 72)
        assertTrue(runCatching { budget.requireAvailable(temp.root, 73) }.isFailure)
    }

    @Test fun completedFileCanResumeRegistrationButChangedBytesCannot() = runBlocking {
        val file = temp.newFile("complete.gguf").apply { writeText("downloaded content") }
        val receipt = CompletedDownloadReceipt(file, "request-1")
        assertFalse(receipt.matches(remote()))
        receipt.record()
        assertTrue(CompletedDownloadReceipt(file, "request-1").matches(remote()))
        assertFalse(CompletedDownloadReceipt(file, "request-2").matches(remote()))
        file.writeText("corrupted content!")
        assertFalse(receipt.matches(remote()))
    }

    @Test fun emptyCompletedDownloadCannotPassOrCreateAReceipt() = runBlocking {
        val file = temp.newFile("empty.gguf")
        val receipt = CompletedDownloadReceipt(file, "empty-request")

        assertFalse(receipt.matches(remote()))
        assertTrue(runCatching { receipt.record() }.isFailure)

        file.writeText("complete")
        receipt.record()
        assertTrue(receipt.matches(remote()))
    }

    private fun archive(vararg entries: Pair<String, String>): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }
    private fun remote() = RemoteModelFile("owner/model", "main", "model.gguf", "model.gguf",
        downloadUrl = "https://example.invalid/model.gguf")
}
