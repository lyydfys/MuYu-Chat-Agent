package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageWorkerClientLifecycleTest {
    @Test
    fun upscaleStartsBoundedWatchdogAfterAcceptanceAndPreservesTimeoutTermination() {
        val source = localImageWorkerClientSource()
        val upscale = functionBody(source, "suspend fun upscale(")
        val accepted = upscale.indexOf("request.handshake.completeRemoteStart(accepted)")
        val watchdog = upscale.indexOf("startUpscaleWatchdog(request)", accepted)
        val awaitTerminal = upscale.indexOf("request.completion.await()", watchdog)

        assertTrue(accepted >= 0)
        assertTrue(watchdog > accepted)
        assertTrue(awaitTerminal > watchdog)
        assertTrue(upscale.contains("if (!request.watchdogTimedOut) request.watchdogJob?.cancel()"))
        assertEquals("esrgan_worker_timeout", LOCAL_IMAGE_UPSCALE_WATCHDOG_TIMEOUT_CODE)
        assertTrue(LOCAL_IMAGE_UPSCALE_MAX_RUNTIME_MS > localImageUpscaleHeartbeatTimeoutMs("upscaling"))
    }

    @Test
    fun upscaleWatchdogUsesWorkerCallbacksAsHeartbeatWithoutDeviceAdmission() {
        val source = localImageWorkerClientSource()
        val callback = functionBody(source, "private fun callbackFor(")
        val watchdog = functionBody(source, "private fun startUpscaleWatchdog(")

        assertTrue(callback.contains("request.lastWorkerCallbackAtMs = SystemClock.elapsedRealtime()"))
        assertTrue(watchdog.contains("localImageUpscaleHeartbeatTimeoutMs("))
        assertTrue(watchdog.contains("LOCAL_IMAGE_UPSCALE_MAX_RUNTIME_MS"))
        assertTrue(watchdog.contains("cancelRemote(request)"))
        assertTrue(watchdog.contains("Process.killProcess(workerPid)"))
        assertFalse(watchdog.contains("deviceProfile"))
        assertFalse(watchdog.contains("chipset"))
        assertTrue(
            localImageUpscaleHeartbeatTimeoutMs("worker_preparing") >
                localImageUpscaleHeartbeatTimeoutMs("upscaling")
        )
        assertTrue(
            localImageUpscaleHeartbeatTimeoutMs("waiting_for_native_lease") >
                localImageUpscaleHeartbeatTimeoutMs("worker_preparing")
        )
    }

    @Test
    fun upscaleWorkerPublishesPreparationHeartbeatAndClosesModelLease() {
        val service = functionBody(localImageWorkerServiceSource(), "private fun acceptUpscale(")
        val heartbeat = service.indexOf("phase = \"worker_preparing\"")
        val materialize = service.indexOf("workerInputStore.materializeUpscale(")
        val closeLease = service.indexOf("workerInputs?.close()")

        assertTrue(heartbeat >= 0)
        assertTrue(materialize > heartbeat)
        assertTrue(service.contains("isCancelled = {"))
        assertTrue(service.contains("preparedInputs.close()"))
        assertTrue(closeLease > materialize)
    }

    @Test
    fun terminalOutcomesReleaseTheBindingAndAllowTheNextBind() {
        listOf("success", "failure", "cancelled").forEach { outcome ->
            val lifecycle = LocalImageWorkerBindingLifecycle()
            val first = requireNotNull(lifecycle.issueBind())

            assertTrue("$outcome must issue the first bind", lifecycle.isCurrent(first))
            assertTrue("$outcome must not duplicate an active bind", lifecycle.issueBind() == null)
            assertTrue("$outcome must unbind its worker", lifecycle.release(first))
            assertFalse("$outcome must leave no active binding", lifecycle.bindIssued)
            assertTrue(
                "$outcome must allow the next generation to rebind",
                lifecycle.issueBind() != null
            )
        }
    }

    @Test
    fun terminalReleaseIsIdempotent() {
        val lifecycle = LocalImageWorkerBindingLifecycle()
        val session = requireNotNull(lifecycle.issueBind())
        assertTrue(lifecycle.release(session))
        assertFalse(lifecycle.release(session))
    }

    @Test
    fun staleBindingSessionCannotReleaseOrInvalidateItsReplacement() {
        val lifecycle = LocalImageWorkerBindingLifecycle()
        val first = requireNotNull(lifecycle.issueBind())
        assertTrue(lifecycle.release(first))

        val second = requireNotNull(lifecycle.issueBind())
        assertTrue(second.epoch > first.epoch)
        assertFalse(lifecycle.isCurrent(first))
        assertTrue(lifecycle.isCurrent(second))

        listOf(
            "late onServiceDisconnected",
            "late onBindingDied",
            "late onNullBinding",
            "late binder death"
        ).forEach { callback ->
            assertFalse("$callback must not release the replacement", lifecycle.release(first))
        }
        assertTrue(lifecycle.bindIssued)
        assertTrue(lifecycle.isCurrent(second))
    }

    @Test
    fun eachBindingEpochOwnsItsConnectionAndAllTerminalCallbacksCarryIt() {
        val source = localImageWorkerClientSource()
        val connectionClass = functionBody(source, "private inner class WorkerServiceConnection(")
        val awaitService = functionBody(source, "): BoundWorker {")
        val connectionLoss = functionBody(source, "private fun handleConnectionLoss(")

        assertTrue(connectionClass.contains("val lease: LocalImageWorkerBindingLifecycle.Session"))
        assertTrue(connectionClass.contains("handleServiceConnected(this, binder)"))
        assertTrue(connectionClass.contains("override fun onServiceDisconnected("))
        assertTrue(connectionClass.contains("override fun onBindingDied("))
        assertTrue(connectionClass.contains("override fun onNullBinding("))
        assertTrue(
            "every terminal ServiceConnection callback must identify its own bind epoch",
            connectionClass.split("handleConnectionLoss(this,").size - 1 == 3
        )
        assertTrue(awaitService.contains("val connection = WorkerServiceConnection(lease)"))
        assertTrue(connectionLoss.contains("current.connection !== connection"))
        assertTrue(connectionLoss.contains("!bindingLifecycle.isCurrent(connection.lease)"))
    }

    @Test
    fun bindingOwnerPublicationIsAtomicAndStaleBindCompletionRetiresItself() {
        val source = localImageWorkerClientSource()
        val awaitService = functionBody(source, "): BoundWorker {")
        val selectionLock = functionBody(awaitService, "synchronized(stateLock)")

        assertTrue(selectionLock.contains("onSessionSelected(session)"))
        assertTrue(awaitService.contains("else if (!isCurrentBindingSession(session))"))
        assertTrue(
            awaitService.contains("appContext.unbindService(session.connection)")
        )
        assertTrue(
            awaitService.contains(
                "session.deferred.completeExceptionally("
            )
        )
    }

    @Test
    fun cancelledPreparationReleasesOnlyItsExactUnclaimedBindingEpoch() {
        val source = localImageWorkerClientSource()
        val begin = functionBody(source, "fun begin(")
        val release = functionBody(source, "private fun releaseBindingAfterPreparationFailure(")

        assertTrue(begin.contains("releaseBindingAfterPreparationFailure(next, error)"))
        assertTrue(release.contains("candidate.bindingSession"))
        assertTrue(release.contains("?.takeIf { it !== candidate }"))
        assertTrue(release.contains("?.bindingSession === expectedSession"))
        assertTrue(release.contains("activeRequest?.bindingSession === expectedSession"))
        assertTrue(release.contains("bindingSession !== expectedSession"))
        assertTrue(release.contains("bindingLifecycle.release(expectedSession.lease)"))
        assertTrue(release.contains("appContext.unbindService(releasedSession.connection)"))
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
    fun cancellationTerminalIsPublishedExactlyOnceAndExcludesOtherTerminals() {
        val gate = LocalImageWorkerPublicationGate()

        assertTrue(gate.tryRequestCancellation())
        assertTrue(gate.tryBeginCancellationTerminalPublication())
        assertFalse(gate.tryBeginCancellationTerminalPublication())
        assertFalse(gate.tryBeginResultPublication())
        assertFalse(gate.tryBeginErrorPublication())
        assertTrue(gate.cancelRequested)
    }

    @Test
    fun normalErrorTerminalExcludesCancellationAndResultPublication() {
        val gate = LocalImageWorkerPublicationGate()

        assertTrue(gate.tryBeginErrorPublication())
        assertFalse(gate.tryBeginErrorPublication())
        assertFalse(gate.tryRequestCancellation())
        assertFalse(gate.tryBeginCancellationTerminalPublication())
        assertFalse(gate.tryBeginResultPublication())
        assertFalse(gate.cancelRequested)
    }

    @Test
    fun normalFailureClaimsTerminalBeforeWritingFailedJournal() {
        val job = functionBody(localImageWorkerServiceSource(), "val job = scope.launch {")
        val catchStart = job.indexOf("} catch (error: Throwable) {")
        require(catchStart >= 0) { "Missing worker failure handler" }
        val failureHandler = job.substring(catchStart)
        val claim = failureHandler.indexOf("active.tryBeginErrorPublication()")
        val failedJournal = failureHandler.indexOf("ImageExecutionPhase.FAILED")
        val cancelledJournal = failureHandler.indexOf("finishJournalCancelled(")

        assertTrue(claim >= 0)
        assertTrue(failedJournal > claim)
        assertTrue(cancelledJournal > failedJournal)
    }

    @Test
    fun cancellationBeforeRegistrationCompletesTheClientDeferredLocally() {
        val cancel = functionBody(localImageWorkerClientSource(), "fun cancel(): Boolean")
        val localAction = cancel.indexOf("LocalImageStartHandshake.CancelAction.COMPLETE_LOCALLY")
        val completion = cancel.indexOf("request.completion.completeExceptionally(cancellation)")

        assertTrue(localAction >= 0)
        assertTrue(completion > localAction)
    }

    @Test
    fun cancellationDuringRegistrationIsSentAsSoonAsTheWorkerAccepts() {
        val generate = functionBody(localImageWorkerClientSource(), "suspend fun generate(")
        val registration = generate.indexOf(
            "val cancelAfterRegistration = request.handshake.completeRemoteStart(accepted)"
        )
        val deferredBranch = generate.indexOf("else if (cancelAfterRegistration)", registration)
        val remoteCancel = generate.indexOf("cancelRemote(request)", deferredBranch)
        val awaitTerminal = generate.indexOf("val result = request.completion.await()", remoteCancel)

        assertTrue(registration >= 0)
        assertTrue(deferredBranch > registration)
        assertTrue(remoteCancel > deferredBranch)
        assertTrue(awaitTerminal > remoteCancel)
    }

    @Test
    fun cancellationAfterRegistrationPublishesAWorkerTerminalThatCompletesTheClientDeferred() {
        val clientSource = localImageWorkerClientSource()
        val serviceSource = localImageWorkerServiceSource()
        val cancel = functionBody(clientSource, "fun cancel(): Boolean")
        val cancelRemote = functionBody(clientSource, "private fun cancelRemote(")
        val serviceCancel = functionBody(serviceSource, "override fun cancel(")
        val cancellationTerminal = functionBody(serviceSource, "private fun publishCancellationTerminal(")
        val onError = functionBody(clientSource, "override fun onError(")

        assertTrue(
            cancel.contains(
                "LocalImageStartHandshake.CancelAction.CANCEL_REMOTE -> cancelRemote(request)"
            )
        )
        assertTrue(serviceCancel.contains("publishCancellationTerminal(active,"))
        assertTrue(cancellationTerminal.contains("tryBeginCancellationTerminalPublication()"))
        assertTrue(cancellationTerminal.contains("code = LOCAL_IMAGE_GENERATION_CANCELLED_CODE"))
        assertTrue(onError.contains("LOCAL_IMAGE_GENERATION_CANCELLED_CODE"))
        assertTrue(onError.contains("LocalImageWorkerCancelledException()"))
        assertTrue(onError.contains("request.completion.completeExceptionally("))
        assertTrue(cancelRemote.contains("if (cancelled)"))
        assertTrue(
            cancelRemote.contains(
                "request.completion.completeExceptionally(LocalImageWorkerCancelledException())"
            )
        )
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
        assertTrue(release.contains("bindingLifecycle.release(expectedSession.lease)"))
        assertTrue(release.contains("unlinkRemoteDeathRecipientLocked()"))
        assertTrue(release.contains("remote = null"))
        assertTrue(release.contains("remoteBinder = null"))
        assertTrue(release.contains("appContext.unbindService(releasedSession.connection)"))
        assertTrue(
            "unbind must finish before a new request can observe activeRequest=null",
            release.indexOf("appContext.unbindService(releasedSession.connection)") <
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
        val cleanupBeforeFailure = job.indexOf("transferredOutputs.deleteWorkerOutputs()")
        val onDestroy = functionBody(source, "override fun onDestroy()")
        val writeResult = functionBody(source, "private fun writeResultFile(")

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
        assertTrue(
            "undelivered outputs must be removed before the FAILED journal terminal",
            cleanupBeforeFailure in (completed + 1) until deliveryFailed
        )
        assertTrue(writeResult.contains("output.fd.sync()"))
        assertTrue(writeResult.contains("durableMoveWithinParent("))
        assertTrue(writeResult.contains("target.partial.delete()"))
    }

    @Test
    fun unfinishedBinderDeathStillFailsTheClientAndStopsTheWorker() {
        val clientSource = localImageWorkerClientSource()
        val serviceSource = localImageWorkerServiceSource()
        val connected = functionBody(clientSource, "override fun onServiceConnected(")
        val serviceConnected = functionBody(clientSource, "private fun handleServiceConnected(")
        val connectionLoss = functionBody(clientSource, "private fun handleConnectionLoss(")
        val generate = functionBody(serviceSource, "override fun generate(")
        val deadClient = functionBody(serviceSource, "private fun cancelForDeadClient(")

        assertTrue(connected.contains("handleServiceConnected(this, binder)"))
        assertTrue(serviceConnected.contains("handleConnectionLoss("))
        assertTrue(serviceConnected.contains("connection,"))
        assertTrue(connectionLoss.contains("current.connection !== connection"))
        assertTrue(connectionLoss.contains("!bindingLifecycle.isCurrent(connection.lease)"))
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
        assertTrue(serviceCancel.contains("runCatching { provider.cancel() }"))
        assertTrue(serviceCancel.contains("active.job?.cancel("))
        assertTrue(serviceCancel.contains("return true"))
    }

    @Test
    fun closingOneClientDoesNotDeleteAnotherRequestsPublishedResults() {
        val source = localImageWorkerClientSource()
        val close = functionBody(source, "override fun close()")

        assertFalse(close.contains("cleanupWorkerResults()"))
        assertFalse(source.contains("private fun cleanupWorkerResults()"))
    }

    @Test
    fun randomSeedSentinelIsResolvedBeforeNativeWorkerExecution() {
        val generate = functionBody(localImageWorkerServiceSource(), "override fun generate(")

        assertTrue(generate.contains("request.options.seed == null || request.options.seed == -1"))
        assertTrue(generate.contains("val workerOptions = workerInputs.options"))
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
        val openingBrace = if ('{' in signature) {
            source.indexOf('{', start)
        } else {
            val openingParenthesis = source.indexOf('(', start)
            require(openingParenthesis >= 0) { "Missing function parameter list: $signature" }
            var parenthesisDepth = 0
            var closingParenthesis = -1
            for (index in openingParenthesis until source.length) {
                when (source[index]) {
                    '(' -> parenthesisDepth += 1
                    ')' -> {
                        parenthesisDepth -= 1
                        if (parenthesisDepth == 0) {
                            closingParenthesis = index
                            break
                        }
                    }
                }
            }
            require(closingParenthesis >= 0) { "Unterminated function parameter list: $signature" }
            source.indexOf('{', closingParenthesis)
        }
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
