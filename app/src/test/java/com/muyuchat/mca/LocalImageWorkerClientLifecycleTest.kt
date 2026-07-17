package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageWorkerClientLifecycleTest {
    @Test
    fun terminalOutcomesReleaseTheBindingAndAllowTheNextBind() {
        listOf("success", "failure", "cancelled").forEach { outcome ->
            val lifecycle = LocalImageWorkerBindingLifecycle()

            assertTrue("$outcome must issue the first bind", lifecycle.issueBind())
            assertFalse("$outcome must not duplicate an active bind", lifecycle.issueBind())
            assertTrue("$outcome must unbind its worker", lifecycle.release())
            assertFalse("$outcome must leave no active binding", lifecycle.bindIssued)
            assertTrue("$outcome must allow the next generation to rebind", lifecycle.issueBind())
        }
    }

    @Test
    fun terminalReleaseIsIdempotent() {
        val lifecycle = LocalImageWorkerBindingLifecycle()
        assertFalse(lifecycle.release())
        assertTrue(lifecycle.issueBind())
        assertTrue(lifecycle.release())
        assertFalse(lifecycle.release())
    }

    @Test
    fun resultPublicationWinsBeforeCancellation() {
        val gate = LocalImageWorkerPublicationGate()

        assertTrue(gate.tryBeginResultPublication())
        assertFalse(gate.tryRequestCancellation())
        assertFalse(gate.cancelRequested)
    }

    @Test
    fun cancellationWinsBeforeResultPublication() {
        val gate = LocalImageWorkerPublicationGate()

        assertTrue(gate.tryRequestCancellation())
        assertFalse(gate.tryBeginResultPublication())
        assertTrue(gate.cancelRequested)
    }

    @Test
    fun publicationAndCancellationGatesAreIdempotent() {
        val publicationGate = LocalImageWorkerPublicationGate()
        assertTrue(publicationGate.tryBeginResultPublication())
        assertFalse(publicationGate.tryBeginResultPublication())
        assertFalse(publicationGate.tryRequestCancellation())
        assertFalse(publicationGate.cancelRequested)

        val cancellationGate = LocalImageWorkerPublicationGate()
        assertTrue(cancellationGate.tryRequestCancellation())
        assertFalse(cancellationGate.tryRequestCancellation())
        assertFalse(cancellationGate.tryBeginResultPublication())
        assertTrue(cancellationGate.cancelRequested)
    }

    @Test
    fun generateFinallyReleasesBinderStateBeforeClearingTheActiveRequest() {
        val source = localImageWorkerClientSource()
        val generateStart = source.indexOf("suspend fun generate(")
        require(generateStart >= 0) { "Missing generate()" }
        val generateEnd = source.indexOf("override fun close()", generateStart)
        require(generateEnd > generateStart) { "Missing close() after generate()" }
        val generate = source.substring(generateStart, generateEnd)
        val release = functionBody(source, "private fun releaseBindingAfterRequest(")

        val finallyBlock = generate.substring(generate.indexOf("finally {") + "finally {".length)
        assertTrue(finallyBlock.contains("releaseBindingAfterRequest(request, model.runtime)"))
        assertTrue(release.contains("bindingLifecycle.release()"))
        assertTrue(release.contains("unlinkRemoteDeathRecipientLocked()"))
        assertTrue(release.contains("remote = null"))
        assertTrue(release.contains("remoteBinder = null"))
        assertTrue(release.contains("appContext.unbindService(connection)"))
        assertTrue(
            "unbind must finish before a new request can observe activeRequest=null",
            release.indexOf("appContext.unbindService(connection)") <
                release.lastIndexOf("activeRequest === request")
        )
    }

    @Test
    fun successfulDeliveryCommitsPublicationBeforeClientUnbindCanDestroyTheService() {
        val source = localImageWorkerServiceSource()
        val job = functionBody(source, "val job = scope.launch {")
        val beginPublication = job.indexOf("active.tryBeginResultPublication()")
        val deliver = job.indexOf("val delivered = sendComplete(")
        val completed = job.indexOf(
            "markJournalTerminal(request.requestId, ImageExecutionPhase.COMPLETED)"
        )
        val deliveryFailed = job.indexOf("errorCode = \"RESULT_DELIVERY_FAILED\"")
        val onDestroy = functionBody(source, "override fun onDestroy()")

        assertTrue("worker job must contain its result callback", deliver >= 0)
        assertTrue(
            "a successful callback can resume the client and unbind synchronously; " +
                "publication must commit before invoking that callback",
            beginPublication in 0 until deliver
        )
        assertTrue(onDestroy.contains("if (active.tryRequestCancellation())"))
        assertTrue(
            "COMPLETED is committed only after the callback confirms delivery",
            completed > deliver
        )
        assertTrue(
            "a callback delivery failure must remain FAILED rather than COMPLETED",
            deliveryFailed > completed
        )
    }

    @Test
    fun unfinishedBinderDeathStillFailsTheClientAndStopsTheWorker() {
        val clientSource = localImageWorkerClientSource()
        val serviceSource = localImageWorkerServiceSource()
        val connected = functionBody(clientSource, "override fun onServiceConnected(")
        val connectionLoss = functionBody(clientSource, "private fun handleConnectionLoss(")
        val generate = functionBody(serviceSource, "override fun generate(")
        val deadClient = functionBody(serviceSource, "private fun cancelForDeadClient(")

        assertTrue(connected.contains("handleConnectionLoss("))
        assertTrue(connectionLoss.contains("active?.completion?.completeExceptionally(failure)"))
        assertTrue(generate.contains("cancelForDeadClient(active)"))
        assertTrue(deadClient.contains("if (!isCurrent) return"))
        assertTrue(deadClient.contains("if (!active.tryRequestCancellation()) return"))
        assertTrue(deadClient.contains("requestJournalCancellation(active.requestId)"))
        assertTrue(deadClient.contains("provider.cancel()"))
        assertTrue(deadClient.contains("active.job?.cancel("))
    }

    @Test
    fun explicitCancellationStillCancelsTheRegisteredRemoteRequest() {
        val clientSource = localImageWorkerClientSource()
        val serviceSource = localImageWorkerServiceSource()
        val serviceCancel = functionBody(serviceSource, "override fun cancel(")

        assertTrue(clientSource.contains("request.handshake.requestCancel()"))
        assertTrue(clientSource.contains("cancelRemote(request)"))
        assertTrue(serviceCancel.contains("if (!active.tryRequestCancellation()) return false"))
        assertTrue(serviceCancel.contains("requestJournalCancellation(active.requestId)"))
        assertTrue(serviceCancel.contains("return provider.cancel()"))
    }

    private fun localImageWorkerClientSource(): String {
        return localImageWorkerSource("LocalImageWorkerClient.kt")
    }

    private fun localImageWorkerServiceSource(): String {
        return localImageWorkerSource("LocalImageWorkerService.kt")
    }

    private fun localImageWorkerSource(fileName: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(6) {
            listOf(
                File(root, "src/main/java/com/muyuchat/mca/$fileName"),
                File(root, "app/src/main/java/com/muyuchat/mca/$fileName")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate $fileName")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingBrace = source.indexOf('{', start)
        require(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
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
