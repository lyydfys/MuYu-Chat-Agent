package com.muyuchat.core.dsh.contract.capability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM behavior tests for the in-memory capability fake. No Android code is run. */
class FakeDshCapabilityPortTest {

    @Test
    fun fakeImplementsAllFiveOperationsInMemory() = runBlocking {
        val fake = FakeDshCapabilityPort()

        assertEquals(DshCapabilityState.GRANTED, fake.status(DshCapability.FILE_PICK).state)
        fake.setStatus(DshCapability.NOTIFICATION, DshCapabilityState.NOT_ENABLED)
        assertEquals(
            DshCapabilityState.NOT_ENABLED,
            fake.status(DshCapability.NOTIFICATION).state
        )

        fake.pickResult = DshPickFilesResult.Picked(listOf(stagedFile()))
        assertEquals(fake.pickResult, fake.pickFiles(pickRequest()))

        fake.saveResult = DshSaveFileResult.Saved(
            document = DshDocumentRef("doc_0123456789abcdef"),
            displayName = "report.txt"
        )
        assertEquals(fake.saveResult, fake.saveFile(saveRequest()))

        fake.notifyResult = DshNotifyResult.Posted(42)
        assertEquals(fake.notifyResult, fake.notify(notifyRequest()))

        fake.deviceResult = DshDeviceActionResult.Completed("screen text")
        assertEquals(fake.deviceResult, fake.performDeviceAction(deviceRequest()))
    }

    @Test
    fun terminalResultIsReplayedWithoutReExecution() = runBlocking {
        val fake = FakeDshCapabilityPort()
        val request = pickRequest()
        fake.pickResult = DshPickFilesResult.Picked(listOf(stagedFile()))

        assertEquals(fake.pickResult, fake.pickFiles(request))
        assertEquals(fake.pickResult, fake.pickFiles(request))
        assertEquals(1, fake.executionsOf(request.requestId))
    }

    @Test
    fun pendingDuplicateIsRejectedWithRequestInProgress() = runBlocking {
        val fake = FakeDshCapabilityPort(autoRelease = false)
        val request = pickRequest()
        val pendingResult = async(Dispatchers.Default) { fake.pickFiles(request) }
        fake.awaitPending(request.requestId)

        val duplicate = fake.pickFiles(request)
        assertTrue(duplicate is DshPickFilesResult.Rejected)
        val failure = (duplicate as DshPickFilesResult.Rejected).failure
        assertEquals(DshCapabilityErrorCode.REQUEST_IN_PROGRESS, failure.code)
        assertTrue(failure.retryable)

        fake.release(request.requestId)
        assertTrue(withTimeout(2_000L) { pendingResult.await() } is DshPickFilesResult.Picked)
        assertEquals(1, fake.executionsOf(request.requestId))
    }

    @Test
    fun cancelledPickReturnsCancelledWithoutExecution() = runBlocking {
        val fake = FakeDshCapabilityPort(autoRelease = false)
        val request = pickRequest()
        val pendingResult = async(Dispatchers.Default) { fake.pickFiles(request) }
        fake.awaitPending(request.requestId)

        assertEquals(
            DshCapabilityCancelResult.Cancelled,
            fake.cancel(DshCapabilityCancelRequest(request.requestId, request.generation))
        )
        assertEquals(DshPickFilesResult.Cancelled, withTimeout(2_000L) { pendingResult.await() })
        assertEquals(0, fake.executionsOf(request.requestId))
        assertEquals(
            DshCapabilityCancelResult.AlreadyTerminal,
            fake.cancel(DshCapabilityCancelRequest(request.requestId, request.generation))
        )
    }

    @Test
    fun cancelledDeviceActionReturnsCancelled() = runBlocking {
        val fake = FakeDshCapabilityPort(autoRelease = false)
        val request = deviceRequest()
        val pendingResult = async(Dispatchers.Default) { fake.performDeviceAction(request) }
        fake.awaitPending(request.requestId)

        assertEquals(
            DshCapabilityCancelResult.Cancelled,
            fake.cancel(DshCapabilityCancelRequest(request.requestId, request.generation))
        )
        assertEquals(
            DshDeviceActionResult.Cancelled,
            withTimeout(2_000L) { pendingResult.await() }
        )
        assertEquals(0, fake.executionsOf(request.requestId))
    }

    @Test
    fun staleGenerationIsRejectedBeforeExecution() = runBlocking {
        val fake = FakeDshCapabilityPort(generation = 2L)
        val request = pickRequest(generation = 1L)

        val result = fake.pickFiles(request)
        assertTrue(result is DshPickFilesResult.Rejected)
        assertEquals(
            DshCapabilityErrorCode.STALE_GENERATION,
            (result as DshPickFilesResult.Rejected).failure.code
        )
        assertEquals(0, fake.executionsOf(request.requestId))
    }

    @Test
    fun cancelOfUnknownOrStaleRequestIsFailClosed() = runBlocking {
        val fake = FakeDshCapabilityPort()
        val requestId = DshCapabilityRequestId("cancel-request-0001")

        assertEquals(
            DshCapabilityCancelResult.NotFound,
            fake.cancel(DshCapabilityCancelRequest(requestId, 1L))
        )
        assertEquals(
            DshCapabilityCancelResult.StaleGeneration,
            fake.cancel(DshCapabilityCancelRequest(requestId, 2L))
        )
    }

    @Test
    fun unknownTerminalOutcomeIsNotReplayed() = runBlocking {
        val fake = FakeDshCapabilityPort()
        val request = pickRequest()

        assertEquals(DshPickFilesResult.Picked(emptyList()), fake.pickFiles(request))
        assertEquals(1, fake.executionsOf(request.requestId))
        assertTrue(fake.markOutcomeUnknown(request.requestId))

        val retry = fake.pickFiles(request)
        assertTrue(retry is DshPickFilesResult.Rejected)
        assertEquals(
            DshCapabilityErrorCode.OUTCOME_UNKNOWN,
            (retry as DshPickFilesResult.Rejected).failure.code
        )
        assertEquals(1, fake.executionsOf(request.requestId))
    }

    @Test
    fun unknownPendingOutcomeIsNotReplayed() = runBlocking {
        val fake = FakeDshCapabilityPort(autoRelease = false)
        val request = pickRequest()
        val pendingResult = async(Dispatchers.Default) { fake.pickFiles(request) }
        fake.awaitPending(request.requestId)

        assertTrue(fake.markOutcomeUnknown(request.requestId))
        val result = withTimeout(2_000L) { pendingResult.await() }
        assertTrue(result is DshPickFilesResult.Rejected)
        assertEquals(
            DshCapabilityErrorCode.OUTCOME_UNKNOWN,
            (result as DshPickFilesResult.Rejected).failure.code
        )
        assertEquals(0, fake.executionsOf(request.requestId))

        val retry = fake.pickFiles(request)
        assertTrue(retry is DshPickFilesResult.Rejected)
        assertEquals(
            DshCapabilityErrorCode.OUTCOME_UNKNOWN,
            (retry as DshPickFilesResult.Rejected).failure.code
        )
    }

    @Test
    fun revocationReleasesPendingCallsAndFailsClosed() = runBlocking {
        val fake = FakeDshCapabilityPort(generation = 7L, autoRelease = false)
        val request = pickRequest(generation = 7L)
        val pendingResult = async(Dispatchers.Default) { fake.pickFiles(request) }
        fake.awaitPending(request.requestId)

        fake.revoke()
        val result = withTimeout(2_000L) { pendingResult.await() }
        assertTrue(result is DshPickFilesResult.Rejected)
        assertEquals(
            DshCapabilityErrorCode.STALE_GENERATION,
            (result as DshPickFilesResult.Rejected).failure.code
        )
        assertEquals(0, fake.executionsOf(request.requestId))
        assertEquals(DshCapabilityState.DENIED, fake.status(DshCapability.FILE_PICK).state)
    }

    @Test
    fun requestIdCannotBeReusedForAnotherCapability() = runBlocking {
        val fake = FakeDshCapabilityPort()
        val sharedId = "shared-request-0001"

        assertEquals(DshPickFilesResult.Picked(emptyList()), fake.pickFiles(pickRequest(id = sharedId)))
        val reuse = fake.saveFile(saveRequest(id = sharedId))
        assertTrue(reuse is DshSaveFileResult.Rejected)
        assertEquals(
            DshCapabilityErrorCode.INVALID_REQUEST,
            (reuse as DshSaveFileResult.Rejected).failure.code
        )
        assertEquals(1, fake.executionsOf(DshCapabilityRequestId(sharedId)))
    }

    private fun pickRequest(
        id: String = "pick-request-0001",
        generation: Long = 1L
    ) = DshPickFilesRequest(
        requestId = DshCapabilityRequestId(id),
        generation = generation,
        reason = "read a user document",
        acceptMimeTypes = listOf("text/plain"),
        allowMultiple = false
    )

    private fun saveRequest(
        id: String = "save-request-0001",
        generation: Long = 1L
    ) = DshSaveFileRequest(
        requestId = DshCapabilityRequestId(id),
        generation = generation,
        reason = "save the generated report",
        source = DshSaveSource.WorkspaceFile(
            DshWorkspaceRelativePath.of("reports/report.txt")
        ),
        suggestedName = "report.txt",
        mimeType = "text/plain"
    )

    private fun notifyRequest(
        id: String = "notify-request-0001",
        generation: Long = 1L
    ) = DshNotifyRequest(
        requestId = DshCapabilityRequestId(id),
        generation = generation,
        reason = "report task completion",
        title = "DSH task finished",
        body = "The report is ready.",
        deepLinkPath = "/sessions/1"
    )

    private fun deviceRequest(
        id: String = "device-request-0001",
        generation: Long = 1L
    ) = DshDeviceActionRequest(
        requestId = DshCapabilityRequestId(id),
        generation = generation,
        reason = "read the current screen",
        action = DshDeviceAction.ReadScreen
    )

    private fun stagedFile(
        requestId: String = "pick-request-0001"
    ) = DshStagedFile(
        document = DshDocumentRef("doc_0123456789abcdef"),
        workspaceRelativePath = DshWorkspacePaths.stagingPath(
            generation = 1L,
            requestId = DshCapabilityRequestId(requestId),
            displayName = "report.txt"
        ),
        displayName = "report.txt",
        mimeType = "text/plain",
        sizeBytes = 12L
    )
}
