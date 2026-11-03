package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateParallelDownloaderTest {
    @Test
    fun assemblesAllRangesAndRemovesPartFiles() = runBlocking {
        val payload = ByteArray(16 * 1024) { index -> (index % 251).toByte() }
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val range = requireNotNull(chain.request().header("Range")) { "range missing" }
                val match = requireNotNull(Regex("bytes[ =](\\d+)-(\\d+)").matchEntire(range))
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toInt()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes $start-$end/${payload.size}")
                    .body(payload.copyOfRange(start, end + 1).toResponseBody())
                    .build()
            })
            .build()
        val directory = Files.createTempDirectory("mca-update-parallel").toFile()
        try {
            val temp = File(directory, ".update.apk.part")
            val finalFile = File(directory, "update.apk")
            val downloader = AppUpdateParallelDownloader(concurrency = 4, client = client)

            downloader.download(
                url = "https://release-assets.githubusercontent.com/test/update.apk",
                expectedBytes = payload.size.toLong(),
                tempFile = temp,
                finalFile = finalFile,
                onProgress = {}
            )

            assertTrue(finalFile.isFile)
            assertArrayEquals(payload, finalFile.readBytes())
            assertEquals(payload.size.toLong(), finalFile.length())
            assertEquals(emptyList<File>(), directory.listFiles { file -> file.name.contains(".range.") }?.toList())
        } finally {
            directory.deleteRecursively()
        }
    }
}
