package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiImageCancellationContractTest {
    @Test
    fun `authenticated image cancellation owns the request job worker and lease through ImageGenerationOwner`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val api = functionBody(
            sourceFile("app/src/main/java/com/muyuchat/mca/presentation/LocalApiImageGenerationOperations.kt"),
            "internal suspend fun generateLocalApiImageOperations("
        )
        val owner = sourceFile("app/src/main/java/com/muyuchat/mca/ImageGenerationOwner.kt")
        val operationRun = functionBody(owner, "suspend fun <T> run(")
        val cancelActive = functionBody(owner, "fun cancelActive(")
        // P3 step 2 moved the stop-provider assignment to ChatLocalApiProviderOperations; the
        // ordering contract is unchanged -- it is read where it now lives.
        val installSource = sourceFile("app/src/main/java/com/muyuchat/mca/ChatLocalApiProviderOperations.kt")
        val stopProvider = installSource.substring(
            installSource.indexOf("LocalApiRuntime.stopGenerationProvider =")
        )
        val coordinator = sourceFile("app/src/main/java/com/muyuchat/mca/LocalApiServerCoordinator.kt")
        val startServer = functionBody(coordinator, "fun startApiServer(")
        val stopServer = functionBody(coordinator, "fun stopApiServer()")

        val ownerEntry = api.indexOf("port.runLocalApiImageOperation(requestId)")
        val cancellationCheck = api.indexOf("currentCoroutineContext().ensureActive()")
        val worker = api.indexOf("localImageWorkerClient.generate(")
        val ownerAdmission = operationRun.indexOf("tryAcquireLease(requestId)")
        val ownership = operationRun.indexOf("activeOwnership = ownership")
        val workerCancel = operationRun.indexOf("cancelOwnedWorker(ownership)")
        val releaseBarrier = operationRun.indexOf("awaitWorkerRelease()")
        val unregister = operationRun.indexOf("unregisterOwnership(ownership)")
        val clearModel = operationRun.indexOf("setActiveModelId(null)")
        val release = operationRun.indexOf("releaseLease(lease)")

        assertTrue(stopProvider.indexOf("cancelActiveLocalApiImageGeneration(") >= 0)
        assertTrue(
            stopProvider.indexOf("cancelActiveLocalApiImageGeneration(") <
                stopProvider.indexOf("port.chatExecutionFacade.stopGeneration()")
        )
        assertTrue(ownerEntry >= 0)
        assertTrue(cancellationCheck > ownerEntry)
        assertTrue(worker > cancellationCheck)
        assertTrue(ownerAdmission >= 0 && ownership > ownerAdmission)
        assertTrue(workerCancel >= 0)
        assertTrue(releaseBarrier > workerCancel)
        assertTrue(unregister > releaseBarrier)
        assertTrue(clearModel > unregister)
        assertTrue(release > clearModel)
        assertTrue(cancelActive.contains("cancelWorker(ownership.requestId)"))
        assertTrue(cancelActive.contains("ownership.requestJob.cancel("))
        assertFalse(api.contains("registerLocalApiImageGenerationOwnership"))
        assertFalse(api.contains("unregisterLocalApiImageGenerationOwnership"))
        assertFalse(api.contains("cancelOwnedLocalApiImageWorker"))
        assertFalse(api.contains("tryAcquireObservedImageGenerationLease"))
        assertFalse(api.contains("releaseObservedImageGenerationLease"))
        assertTrue(source.contains("imageGenerationOwner.cancelActiveLocalApiImageGeneration(reason)"))
        assertFalse(source.contains("activeLocalApiImageGenerationOwnership"))
        assertTrue(startServer.contains("cancelActiveLocalApiImageGeneration("))
        assertTrue(stopServer.contains("cancelActiveLocalApiImageGeneration("))
        assertFalse(api.contains("deviceProfile"))
        assertFalse(api.contains("allowlist", ignoreCase = true))
    }

    @Test
    fun `loopback server cancels image request jobs without treating request half close as abort`() {
        // T2B8/R7-B3: the loopback server is a shell + six same-package successor files;
        // the union read keeps every extracted window byte-identical from the function name on.
        val server = LOOPBACK_SERVER_SOURCE_FILES.joinToString("\n") { sourceFile(it) }
        val stop = functionBody(server, "fun stop()")
        val imageHandler = functionBody(
            server,
            "handleImageGeneration(socket: Socket, body: String, clientRequestId: String?)"
        )
        val disconnectMonitor = functionBody(
            server,
            "withImageClientDisconnectCancellation(\n        socket: Socket,"
        )
        val stopRoute = functionBody(server, "handleGenerationStop(socket: Socket, body: String)")

        assertTrue(stop.contains("cancelActiveImageRequests("))
        assertTrue(imageHandler.contains("registerImageRequest(requestId, requestJob, cancellationEpoch)"))
        assertTrue(imageHandler.contains("unregisterImageRequest(requestId, requestJob)"))
        assertTrue(imageHandler.contains("withImageClientDisconnectCancellation("))
        assertTrue(imageHandler.contains("\"image_generation_cancelled\""))
        assertTrue(stopRoute.contains("LocalApiRuntime.stopGeneration()"))
        assertTrue(stopRoute.contains("LocalApiRuntime.stopGenerationIfRequestActive(chatRequestId)"))
        assertTrue(stopRoute.contains("imageJobs.forEach"))
        assertTrue(stopRoute.contains("imageJobs.map { it.second }.joinAll()"))
        assertTrue(
            stopRoute.indexOf("activeImageRequestJobs.filterKeys") <
                stopRoute.indexOf("LocalApiRuntime.stopGeneration()")
        )
        assertTrue(server.contains("imageRequestCancellationEpoch += 1L"))
        assertTrue(server.contains("imageRequestAdmissionPauseCount += 1"))
        assertTrue(server.contains("imageRequestAdmissionPauseCount = 0"))
        assertTrue(disconnectMonitor.contains("catch (error: IOException)"))
        assertTrue(disconnectMonitor.contains("cancelImageRequest("))
        assertTrue(disconnectMonitor.contains("if (input.read() < 0) return@launch"))
        assertTrue(
            disconnectMonitor.indexOf("val input = socket.getInputStream()") in
                0 until disconnectMonitor.indexOf("val disconnectMonitor = launch")
        )
        assertFalse(disconnectMonitor.contains("deviceProfile"))
    }

    // T2B8/R7-B3: router source list for the union read (shell + six successors).
    private val LOOPBACK_SERVER_SOURCE_FILES = listOf(
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServer.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerHttpTransport.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerChatSse.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerResponsesSse.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerImageGeneration.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerTuningControl.kt",
            "api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServerProtocolCodec.kt"
        )

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
