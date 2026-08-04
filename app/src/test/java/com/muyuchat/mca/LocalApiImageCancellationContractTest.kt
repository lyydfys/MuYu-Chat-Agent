package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiImageCancellationContractTest {
    @Test
    fun `authenticated image cancellation owns the request job worker and lease`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val api = functionBody(source, "private suspend fun generateLocalApiImage(")
        val stopProvider = source.substring(
            source.indexOf("LocalApiRuntime.stopGenerationProvider ="),
            source.indexOf("LocalApiRuntime.loadedModelJsonProvider =")
        )
        val startServer = functionBody(source, "private fun startApiServer(")
        val stopServer = functionBody(source, "private fun stopApiServer()")

        val register = api.indexOf("registerLocalApiImageGenerationOwnership(ownership)")
        val cancellationCheck = api.indexOf("currentCoroutineContext().ensureActive()")
        val worker = api.indexOf("localImageWorkerClient.generate(")
        val workerCancel = api.indexOf("cancelOwnedLocalApiImageWorker(ownership)")
        val unregister = api.indexOf("unregisterLocalApiImageGenerationOwnership(ownership)")
        val release = api.indexOf("releaseObservedImageGenerationLease(generationLease)")

        assertTrue(stopProvider.indexOf("cancelActiveLocalApiImageGeneration(") >= 0)
        assertTrue(
            stopProvider.indexOf("cancelActiveLocalApiImageGeneration(") <
                stopProvider.indexOf("engine.stopGeneration()")
        )
        assertTrue(register >= 0)
        assertTrue(cancellationCheck > register)
        assertTrue(worker > cancellationCheck)
        assertTrue(workerCancel > worker)
        assertTrue(unregister > workerCancel)
        assertTrue(release > unregister)
        assertEquals(1, Regex("releaseObservedImageGenerationLease\\(generationLease\\)")
            .findAll(api).count())
        assertTrue(source.contains("synchronized(localApiImageGenerationLifecycleLock)"))
        assertTrue(source.contains("ownership.requestJob.cancel(CancellationException(reason))"))
        assertTrue(startServer.contains("cancelActiveLocalApiImageGeneration("))
        assertTrue(stopServer.contains("cancelActiveLocalApiImageGeneration("))
        assertFalse(api.contains("deviceProfile"))
        assertFalse(api.contains("allowlist", ignoreCase = true))
    }

    @Test
    fun `loopback server cancels image request jobs without treating request half close as abort`() {
        val server = sourceFile(
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServer.kt"
        )
        val stop = functionBody(server, "fun stop()")
        val imageHandler = functionBody(server, "private suspend fun handleImageGeneration(")
        val disconnectMonitor = functionBody(
            server,
            "private suspend fun <T> withImageClientDisconnectCancellation("
        )
        val stopRoute = server.substring(
            server.indexOf("method == \"POST\" && path == \"/v1/generate/stop\""),
            server.indexOf("method == \"POST\" && path in GENERATION_PATHS")
        )

        assertTrue(stop.contains("cancelActiveImageRequests("))
        assertTrue(imageHandler.contains("registerImageRequest(requestId, requestJob, cancellationEpoch)"))
        assertTrue(imageHandler.contains("unregisterImageRequest(requestId, requestJob)"))
        assertTrue(imageHandler.contains("withImageClientDisconnectCancellation("))
        assertTrue(imageHandler.contains("\"image_generation_cancelled\""))
        assertTrue(stopRoute.contains("LocalApiRuntime.stopGeneration()"))
        assertTrue(stopRoute.contains("cancelActiveImageRequests("))
        assertTrue(stopRoute.contains("awaitImageRequestTermination(imageRequests)"))
        assertTrue(
            stopRoute.indexOf("cancelActiveImageRequests(") <
                stopRoute.indexOf("LocalApiRuntime.stopGeneration()")
        )
        assertTrue(server.contains("imageRequestCancellationEpoch += 1L"))
        assertTrue(server.contains("imageRequestAdmissionPauseCount += 1"))
        assertTrue(server.contains("resumeImageRequestAdmission()"))
        assertTrue(disconnectMonitor.contains("catch (error: IOException)"))
        assertTrue(disconnectMonitor.contains("cancelImageRequest("))
        assertTrue(disconnectMonitor.contains("if (input.read() < 0) return@launch"))
        assertTrue(
            disconnectMonitor.indexOf("val input = socket.getInputStream()") in
                0 until disconnectMonitor.indexOf("val disconnectMonitor = launch")
        )
        assertFalse(disconnectMonitor.contains("deviceProfile"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            File(root, relativePath).takeIf(File::isFile)?.let { file ->
                return file.readText(Charsets.UTF_8)
            }
            directory = root.parentFile
        }
        error("Unable to locate $relativePath")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val open = source.indexOf('{', start)
        require(open >= 0) { "Missing function body for: $signature" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function body: $signature")
    }
}
