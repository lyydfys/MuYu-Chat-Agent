package com.muyuchat.core.dsh.vscreen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lifecycle, close/skip races, frame policy and privileged action planning. No Android code. */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualScreenOwnerTest {

    private fun owner(
        display: FakeDisplayPort = FakeDisplayPort(),
        privileged: FakePrivilegedPort = FakePrivilegedPort(),
        frameSink: FakeFrameSink = FakeFrameSink(),
        preview: DshVscreenPreviewPort = DshVscreenPreviewPort.None,
        policy: DshVscreenFramePolicy = DshVscreenFramePolicy(),
        clock: FakeClock = FakeClock(),
        startTimeoutMillis: Long = 5_000L,
        previewScope: kotlinx.coroutines.CoroutineScope? = null,
        previewPumpIntervalMillis: Long? = null
    ): VirtualScreenOwner = VirtualScreenOwner(
        displayPort = display,
        privilegedPort = privileged,
        frameSink = frameSink,
        previewPort = preview,
        framePolicy = policy,
        startTimeoutMillis = startTimeoutMillis,
        clock = { clock.now },
        previewScope = previewScope,
        previewPumpIntervalMillis = previewPumpIntervalMillis
    )

    private suspend fun stateOf(owner: VirtualScreenOwner): DshVscreenState =
        successOf(owner.status()).state

    private fun <T> successOf(result: DshVscreenResult<T>): T {
        assertTrue("expected success but was $result", result is DshVscreenResult.Success)
        return (result as DshVscreenResult.Success<T>).value
    }

    private fun createInfo(result: DshVscreenResult<DshVscreenCreateInfo>): DshVscreenCreateInfo =
        successOf(result)

    private fun failureOf(result: DshVscreenResult<*>): DshVscreenResult.Failure {
        assertTrue("expected failure but was $result", result is DshVscreenResult.Failure)
        return result as DshVscreenResult.Failure
    }

    @Test
    fun createStartsOneDisplayAndReportsAnHonestHint() = runTest {
        val display = FakeDisplayPort()
        val privileged = FakePrivilegedPort()
        val screen = owner(display, privileged)

        val info = createInfo(screen.create(createRequest()))
        assertEquals(7, info.displayId)
        assertEquals(1L, info.lease.id)
        assertFalse(info.reused)
        assertNotNull(info.hint)
        assertTrue("root-only hint must state the unverified display path", info.hint!!.contains("unverified"))

        val status = successOf(screen.status())
        assertEquals(DshVscreenState.RUNNING, status.state)
        assertTrue(status.running)
        assertFalse(status.hasFrame)
        assertEquals(7, status.displayId)
        assertEquals(1, display.startCalls)
        assertEquals(1, privileged.probeCalls)
    }

    @Test
    fun secondCreateWhileRunningIsRejectedAndDoesNotStartAnotherDisplay() = runTest {
        val display = FakeDisplayPort()
        val screen = owner(display)
        createInfo(screen.create(createRequest()))

        val second = failureOf(screen.create(createRequest()))
        assertEquals(DshVscreenErrorCode.ALREADY_ACTIVE, second.code)
        assertEquals(1, display.startCalls)
        assertEquals(DshVscreenState.RUNNING, stateOf(screen))
        assertEquals(0, display.handles.single().releaseCount)
    }

    @Test
    fun createWithoutAnyCapturePathFailsHonestlyAndStartsNoDisplay() = runTest {
        val display = FakeDisplayPort()
        val privileged = FakePrivilegedPort(
            report = probeReport(capture = captureEvidence(DshVscreenProof.UNSUPPORTED))
        )
        val screen = owner(display, privileged)

        val failure = failureOf(screen.create(createRequest()))
        assertEquals(DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE, failure.code)
        assertEquals(0, display.startCalls)
        assertEquals(DshVscreenState.FAILED, stateOf(screen))
    }

    @Test
    fun startFailureMovesToFailedAndLeavesNoHandle() = runTest {
        val display = FakeDisplayPort(
            failure = DshVscreenDisplayStartException(
                DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE,
                "createVirtualDisplay rejected"
            )
        )
        val screen = owner(display)

        val failure = failureOf(screen.create(createRequest()))
        assertEquals(DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE, failure.code)
        assertEquals(DshVscreenState.FAILED, stateOf(screen))
        assertTrue(display.handles.isEmpty())

        assertTrue(screen.close() is DshVscreenResult.Success)
        assertEquals(DshVscreenState.CLOSED, stateOf(screen))
    }

    @Test
    fun startTimeoutMovesToFailed() = runTest {
        val gate = CompletableDeferred<Unit>()
        val display = FakeDisplayPort(gate = gate)
        val screen = owner(display, startTimeoutMillis = 100L)
        var result: DshVscreenResult<DshVscreenCreateInfo>? = null

        launch { result = screen.create(createRequest()) }
        runCurrent()
        assertEquals(DshVscreenState.STARTING, stateOf(screen))
        advanceTimeBy(101L)
        advanceUntilIdle()

        assertEquals(DshVscreenErrorCode.START_TIMEOUT, failureOf(requireNotNull(result)).code)
        assertEquals(DshVscreenState.FAILED, stateOf(screen))
        assertTrue(display.handles.isEmpty())
    }

    @Test
    fun closeDuringStartReleasesTheLateHandleAndReturnsCancelled() = runTest {
        val gate = CompletableDeferred<Unit>()
        val display = FakeDisplayPort(gate = gate)
        val screen = owner(display)
        var result: DshVscreenResult<DshVscreenCreateInfo>? = null

        val job = launch { result = screen.create(createRequest()) }
        runCurrent()
        assertEquals(DshVscreenState.STARTING, stateOf(screen))

        assertTrue(screen.close() is DshVscreenResult.Success)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(DshVscreenErrorCode.CANCELLED, failureOf(requireNotNull(result)).code)
        assertEquals(DshVscreenState.CLOSED, stateOf(screen))
        assertEquals(1, display.handles.size)
        assertEquals(1, display.handles.single().releaseCount)
        assertTrue(job.isCompleted)
    }

    @Test
    fun callerCancellationDoesNotStrandOwnerInStarting() = runTest {
        val gate = CompletableDeferred<Unit>()
        val display = FakeDisplayPort(gate = gate)
        val screen = owner(display)
        val job = launch { screen.create(createRequest()) }
        runCurrent()
        assertEquals(DshVscreenState.STARTING, stateOf(screen))

        job.cancel()
        advanceUntilIdle()

        assertEquals(DshVscreenState.FAILED, stateOf(screen))
        assertTrue(display.handles.isEmpty())
    }

    @Test
    fun closeIsIdempotentAndReleasesTheDisplayExactlyOnce() = runTest {
        val display = FakeDisplayPort()
        val preview = FakePreviewPort()
        val screen = owner(display, preview = preview)
        createInfo(screen.create(createRequest()))

        assertTrue(screen.close() is DshVscreenResult.Success)
        assertTrue(screen.close() is DshVscreenResult.Success)

        assertEquals(1, display.handles.single().releaseCount)
        assertEquals(listOf(7), preview.stoppedDisplayIds)
        assertEquals(DshVscreenState.CLOSED, stateOf(screen))
    }

    @Test
    fun staleLeaseCannotCloseANewerDisplayGeneration() = runTest {
        val display = FakeDisplayPort()
        val screen = owner(display)
        val firstLease = createInfo(screen.create(createRequest())).lease
        assertTrue(screen.close(firstLease) is DshVscreenResult.Success)

        val secondLease = createInfo(screen.create(createRequest())).lease
        assertEquals(secondLease.id, firstLease.id + 1L)

        val stale = failureOf(screen.close(firstLease))
        assertEquals(DshVscreenErrorCode.STALE_LEASE, stale.code)
        assertEquals(DshVscreenState.RUNNING, stateOf(screen))
        assertEquals(0, display.handles[1].releaseCount)

        assertTrue(screen.close(secondLease) is DshVscreenResult.Success)
        assertEquals(1, display.handles[1].releaseCount)
        assertEquals(DshVscreenState.CLOSED, stateOf(screen))
    }

    @Test
    fun seeBeforeCreateReturnsNoDisplay() = runTest {
        val failure = failureOf(owner().see(DshVscreenSeeRequest(0L, false)))
        assertEquals(DshVscreenErrorCode.NO_DISPLAY, failure.code)
        assertEquals(-1, failure.displayId)
        assertEquals(false, failure.hasFrame)
    }

    @Test
    fun seeBeforeFirstFrameReturnsNotRendered() = runTest {
        val display = FakeDisplayPort()
        val screen = owner(display)
        createInfo(screen.create(createRequest()))

        val failure = failureOf(screen.see(DshVscreenSeeRequest(0L, false)))
        assertEquals(DshVscreenErrorCode.NOT_RENDERED, failure.code)
        assertEquals(7, failure.displayId)
        assertEquals(false, failure.hasFrame)
        assertEquals(1, failure.attempts)
    }

    @Test
    fun seeWithFramePersistsAndReportsScale() = runTest {
        val display = FakeDisplayPort()
        val frameSink = FakeFrameSink()
        val preview = FakePreviewPort()
        val screen = owner(display, frameSink = frameSink, preview = preview)
        createInfo(screen.create(createRequest()))
        display.handles.single().pendingFrames.add(testFrame(width = 100, height = 200))

        val info = successOf(screen.see(DshVscreenSeeRequest(0L, false)))
        assertEquals("/tmp/vscreen/frame-1.png", info.path)
        assertEquals(7, info.displayId)
        assertEquals(1008, info.screenWidth)
        assertEquals(1792, info.screenHeight)
        assertEquals(10.08, info.scaleX, 0.0001)
        assertEquals(8.96, info.scaleY, 0.0001)
        assertEquals(1, frameSink.persisted)
        assertEquals(1, preview.delivered.size)

        val status = successOf(screen.status())
        assertTrue(status.hasFrame)
    }

    @Test
    fun rapidSecondSeeServesTheCachedFrameInsteadOfReReading() = runTest {
        val display = FakeDisplayPort()
        val sink = FakeFrameSink()
        val clock = FakeClock()
        val screen = owner(display, frameSink = sink, clock = clock)
        createInfo(screen.create(createRequest()))
        val handle = display.handles.single()
        handle.pendingFrames.add(testFrame())

        assertTrue(screen.see(DshVscreenSeeRequest(0L, false)) is DshVscreenResult.Success)
        clock.advance(10L) // below the 34 ms interval of 30 fps

        assertTrue(screen.see(DshVscreenSeeRequest(0L, false)) is DshVscreenResult.Success)
        assertEquals(2, sink.persisted)
        assertEquals(2, handle.latestFrameCalls)
    }

    @Test
    fun seeAfterFramesStopAndCacheExpiresReturnsNoFrame() = runTest {
        val display = FakeDisplayPort()
        val clock = FakeClock()
        val screen = owner(display, clock = clock)
        createInfo(screen.create(createRequest()))
        display.handles.single().pendingFrames.add(testFrame())
        assertTrue(screen.see(DshVscreenSeeRequest(0L, false)) is DshVscreenResult.Success)

        clock.advance(DshVscreenLimits.MAX_STALE_FRAME_AGE_MILLIS + 1L)
        val failure = failureOf(screen.see(DshVscreenSeeRequest(0L, false)))
        assertEquals(DshVscreenErrorCode.NO_FRAME, failure.code)
        assertEquals(true, failure.hasFrame)
    }

    @Test
    fun oversizedFrameIsRejectedBeforeItIsPersisted() = runTest {
        val display = FakeDisplayPort()
        val sink = FakeFrameSink()
        val screen = owner(display, frameSink = sink, policy = DshVscreenFramePolicy(maxFrameBytes = 8))
        createInfo(screen.create(createRequest()))
        display.handles.single().pendingFrames.add(testFrame(sizeBytes = 9))

        val failure = failureOf(screen.see(DshVscreenSeeRequest(0L, false)))
        assertEquals(DshVscreenErrorCode.FRAME_TOO_LARGE, failure.code)
        assertEquals(0, sink.persisted)
    }

    @Test
    fun frameSinkFailureIsReportedAsInternal() = runTest {
        val display = FakeDisplayPort()
        val sink = FakeFrameSink()
        sink.failWith = IllegalStateException("disk full")
        val screen = owner(display, frameSink = sink)
        createInfo(screen.create(createRequest()))
        display.handles.single().pendingFrames.add(testFrame())

        assertEquals(DshVscreenErrorCode.INTERNAL, failureOf(screen.see(DshVscreenSeeRequest(0L, false))).code)
    }

    @Test
    fun tapSwipeKeyAndLaunchUseTheDisplayIdAndRejectOutOfBounds() = runTest {
        val display = FakeDisplayPort()
        val privileged = FakePrivilegedPort()
        val screen = owner(display, privileged)
        createInfo(screen.create(createRequest()))

        assertTrue(screen.tap(DshVscreenTapRequest(10, 20)) is DshVscreenResult.Success)
        assertEquals("input -d 7 tap 10 20", privileged.commands.last().argv.last())
        assertEquals(listOf("su", "-c", "input -d 7 tap 10 20"), privileged.commands.last().argv)

        assertEquals(
            DshVscreenErrorCode.INVALID_COORDINATE,
            failureOf(screen.tap(DshVscreenTapRequest(1008, 20))).code
        )
        assertEquals(1, privileged.commands.size)

        val swipe = DshVscreenSwipeRequest(0, 0, 10, 10, 300)
        assertTrue(screen.swipe(swipe) is DshVscreenResult.Success)
        assertEquals("input -d 7 swipe 0 0 10 10 300", privileged.commands.last().argv.last())

        assertTrue(screen.key(DshVscreenKeyRequest(DshVscreenKey.BACK)) is DshVscreenResult.Success)
        assertEquals("input -d 7 keyevent 4", privileged.commands.last().argv.last())

        val launch = successOf(screen.launch(DshVscreenLaunchRequest("com.example.app")))
        assertEquals(7, launch.displayId)
        assertEquals("com.example.app", launch.packageName)
        assertTrue(privileged.commands.last().argv.last().contains("am start --display 7"))
        assertTrue(privileged.commands.last().argv.last().contains("com.example.app"))
    }

    @Test
    fun privilegedCommandFailureAndTimeoutAreTyped() = runTest {
        val display = FakeDisplayPort()
        val privileged = FakePrivilegedPort()
        val screen = owner(display, privileged)
        createInfo(screen.create(createRequest()))

        privileged.commandResult = DshVscreenCommandResult(1, "", "denied", false)
        assertEquals(DshVscreenErrorCode.COMMAND_FAILED, failureOf(screen.key(DshVscreenKeyRequest(DshVscreenKey.HOME))).code)

        privileged.commandResult = DshVscreenCommandResult(-1, "", "", true)
        assertEquals(DshVscreenErrorCode.TIMEOUT, failureOf(screen.tap(DshVscreenTapRequest(1, 2))).code)
    }

    @Test
    fun actionsWithoutDisplayAreRejected() = runTest {
        val screen = owner()
        assertEquals(DshVscreenErrorCode.NO_DISPLAY, failureOf(screen.tap(DshVscreenTapRequest(1, 2))).code)
        assertEquals(DshVscreenErrorCode.NO_DISPLAY, failureOf(screen.key(DshVscreenKeyRequest(DshVscreenKey.BACK))).code)
        assertEquals(DshVscreenErrorCode.NO_DISPLAY, failureOf(screen.launch(DshVscreenLaunchRequest("com.example"))).code)
        assertEquals(
            DshVscreenErrorCode.NO_DISPLAY,
            failureOf(screen.swipe(DshVscreenSwipeRequest(0, 0, 1, 1, 300))).code
        )
    }

    @Test
    fun previewPumpDeliversFramesForTheRunningDisplayAndStopsOnClose() = runTest {
        val display = FakeDisplayPort()
        val preview = FakePreviewPort()
        val screen = owner(
            display = display,
            preview = preview,
            previewScope = backgroundScope,
            previewPumpIntervalMillis = DshVscreenLimits.PREVIEW_PUMP_INTERVAL_MILLIS
        )
        createInfo(screen.create(createRequest()))
        display.handles.single().pendingFrames.add(testFrame())

        advanceTimeBy(DshVscreenLimits.PREVIEW_PUMP_INTERVAL_MILLIS + 50L)
        runCurrent()

        assertEquals(1, preview.delivered.size)
        assertTrue("a pumped frame must be visible to status", successOf(screen.status()).hasFrame)

        assertTrue(screen.close() is DshVscreenResult.Success)
        assertEquals(listOf(7), preview.stoppedDisplayIds)

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals("a closed display must never keep previewing", 1, preview.delivered.size)
    }

    @Test
    fun aPumpedFrameIsServedToSeeFromTheCache() = runTest {
        val display = FakeDisplayPort()
        val preview = FakePreviewPort()
        val sink = FakeFrameSink()
        val screen = owner(
            display = display,
            preview = preview,
            frameSink = sink,
            previewScope = backgroundScope,
            previewPumpIntervalMillis = DshVscreenLimits.PREVIEW_PUMP_INTERVAL_MILLIS
        )
        createInfo(screen.create(createRequest()))
        val handle = display.handles.single()
        handle.pendingFrames.add(testFrame())

        advanceTimeBy(DshVscreenLimits.PREVIEW_PUMP_INTERVAL_MILLIS + 50L)
        runCurrent()
        assertEquals(1, preview.delivered.size)

        // No new frame is pushed by the display; `see` must use the pumped one.
        val info = successOf(screen.see(DshVscreenSeeRequest(0L, false)))
        assertEquals(7, info.displayId)
        assertEquals(1, sink.persisted)
    }

    @Test
    fun aDeadShizukuChannelIsReportedAsChannelLost() = runTest {
        val display = FakeDisplayPort()
        val privileged = FakePrivilegedPort()
        val screen = owner(display, privileged)
        createInfo(screen.create(createRequest()))

        privileged.commandResult = DshVscreenCommandResult(
            exitCode = -1,
            stdout = "",
            stderr = "",
            channelState = DshPrivilegedChannelState.SERVER_NOT_RUNNING
        )
        assertEquals(
            DshVscreenErrorCode.CHANNEL_LOST,
            failureOf(screen.key(DshVscreenKeyRequest(DshVscreenKey.BACK))).code
        )
    }
}
