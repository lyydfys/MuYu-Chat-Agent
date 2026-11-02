package com.muyuchat.core.dsh.vscreen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lifecycle owner of the single MCA virtual display.
 *
 * This is deliberately pure Kotlin: it never imports an Android type. The Android adapter supplies
 * [DshVscreenDisplayPort], [DshVscreenPrivilegedPort] and [DshVscreenFrameSink]; unit tests supply
 * fakes and a virtual clock. The owner is the only writer of [state], [handle] and the lease
 * generation, and it releases the display handle on every terminal path:
 *
 * - successful start -> later close releases it;
 * - failed start -> the port owns the failed resources (contract of [DshVscreenDisplayPort.start]);
 * - close while STARTING -> the late handle is released when start completes;
 * - caller cancellation -> the start is cancelled, and a late handle is released defensively.
 *
 * A caller may pass an app-owned [previewScope]. When it does, a [DshVscreenPreviewPump] delivers
 * the newest frame to [previewPort] at a bounded cadence for the lifetime of the RUNNING state; the
 * pump is a child job of that scope and is cancelled on every terminal transition. Without a scope
 * the owner is exactly the pull-only owner it always was — a preview is optional, not fake.
 */
class VirtualScreenOwner(
    private val displayPort: DshVscreenDisplayPort,
    private val privilegedPort: DshVscreenPrivilegedPort,
    private val frameSink: DshVscreenFrameSink,
    private val previewPort: DshVscreenPreviewPort = DshVscreenPreviewPort.None,
    private val framePolicy: DshVscreenFramePolicy = DshVscreenFramePolicy(),
    private val startTimeoutMillis: Long = DshVscreenLimits.START_TIMEOUT_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val previewScope: CoroutineScope? = null,
    private val previewPumpIntervalMillis: Long? = null
) : DshVscreenControl {

    init {
        require(
            startTimeoutMillis in 1L..DshVscreenLimits.MAX_COMMAND_TIMEOUT_MILLIS
        ) { "start timeout is out of range" }
        require(
            previewPumpIntervalMillis == null ||
                previewPumpIntervalMillis in
                DshVscreenLimits.MIN_PREVIEW_PUMP_INTERVAL_MILLIS..DshVscreenLimits.MAX_PREVIEW_PUMP_INTERVAL_MILLIS
        ) { "preview pump interval is out of range" }
    }

    private val mutex = Mutex()
    private val previewPump: DshVscreenPreviewPump? =
        if (previewScope != null && previewPumpIntervalMillis != null) {
            DshVscreenPreviewPump(
                framePolicy = framePolicy,
                intervalMillis = previewPumpIntervalMillis,
                clock = clock
            )
        } else {
            null
        }

    private var previewJob: Job? = null
    private var state: DshVscreenState = DshVscreenState.CREATED
    private var generation: Long = 0L
    private var handle: DshVscreenDisplayHandle? = null
    private var geometry: DshVscreenGeometry? = null
    private var transportSelection: DshVscreenTransportSelection? = null
    private var hasFrame: Boolean = false
    private var lastFrameAtMillis: Long? = null
    private val frameCache = DshVscreenFrameCache()

    override suspend fun create(request: DshVscreenCreateRequest): DshVscreenResult<DshVscreenCreateInfo> {
        val myGeneration: Long = mutex.withLock {
            when (state) {
                DshVscreenState.STARTING,
                DshVscreenState.RUNNING,
                DshVscreenState.CLOSING -> return failure(
                    DshVscreenErrorCode.ALREADY_ACTIVE,
                    "a virtual display is already active",
                    hint = "close the active display before creating another one"
                )

                DshVscreenState.CREATED,
                DshVscreenState.CLOSED,
                DshVscreenState.FAILED -> Unit
            }
            generation += 1L
            state = DshVscreenState.STARTING
            geometry = request.geometry
            hasFrame = false
            lastFrameAtMillis = null
            handle = null
            frameCache.clear()
            generation
        }

        val report = try {
            privilegedPort.probe()
        } catch (cancellation: CancellationException) {
            markStartAbortedByCancellation(myGeneration)
            throw cancellation
        } catch (throwable: Exception) {
            null
        }
        val selection = report?.let { DshVscreenTransportSelector.select(it) }
            ?: DshVscreenTransportSelection.unavailable
        if (!selection.canCreateDisplay) {
            return recordStartFailure(
                generationAtStart = myGeneration,
                result = failure(
                    DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE,
                    "no privileged transport can create a virtual display",
                    hint = selection.warning
                )
            )
        }

        // A close may have raced the probe. Do not start a display nobody is waiting for.
        val cancelledAfterProbe = mutex.withLock {
            state != DshVscreenState.STARTING || generation != myGeneration
        }
        if (cancelledAfterProbe) {
            return finishCancelledStart(myGeneration)
        }

        var started: DshVscreenDisplayHandle? = null
        val startFailure: DshVscreenResult.Failure? = try {
            started = withTimeout(startTimeoutMillis) { displayPort.start(request.geometry) }
            null
        } catch (timeout: TimeoutCancellationException) {
            failure(
                DshVscreenErrorCode.START_TIMEOUT,
                "virtual display creation timed out",
                hint = "the privileged display path did not answer within the bounded start timeout"
            )
        } catch (startError: DshVscreenDisplayStartException) {
            failure(
                startError.code,
                startError.message?.takeIf { it.isNotBlank() } ?: "virtual display creation failed"
            )
        } catch (cancellation: CancellationException) {
            markStartAbortedByCancellation(myGeneration)
            throw cancellation
        } catch (unexpected: Exception) {
            failure(
                DshVscreenErrorCode.START_FAILED,
                "virtual display creation failed",
                hint = "the display adapter rejected the request"
            )
        }

        val lateHandle = started
        var pumpTarget: DshVscreenDisplayHandle? = null
        val result = mutex.withLock {
            if (state != DshVscreenState.STARTING || generation != myGeneration) {
                if (lateHandle != null) {
                    try {
                        lateHandle.release()
                    } catch (releaseError: Exception) {
                        // The display is being torn down anyway; a release failure here must not
                        // resurrect the start or leak it into a later generation.
                    }
                }
                if (state == DshVscreenState.CLOSING) state = DshVscreenState.CLOSED
                return@withLock failure(
                    DshVscreenErrorCode.CANCELLED,
                    "virtual display creation was cancelled by a concurrent close",
                    hint = "the late handle was released; no display is left running"
                )
            }
            if (startFailure != null || lateHandle == null) {
                state = DshVscreenState.FAILED
                stopPreviewPump()
                geometry = null
                hasFrame = false
                handle = null
                lastFrameAtMillis = null
                frameCache.clear()
                return@withLock startFailure ?: failure(
                    DshVscreenErrorCode.START_FAILED,
                    "virtual display creation returned no handle"
                )
            }
            handle = lateHandle
            state = DshVscreenState.RUNNING
            transportSelection = selection
            hasFrame = false
            lastFrameAtMillis = null
            frameCache.clear()
            // Started after the mutex is released so the pump cannot contend with the create path.
            pumpTarget = lateHandle
            val hint = buildString {
                append("virtual display ready; next: android_vscreen_launch or android_vscreen_see")
                selection.warning?.let { append("; ").append(it) }
            }
            DshVscreenResult.Success(
                DshVscreenCreateInfo(
                    displayId = lateHandle.displayId,
                    geometry = request.geometry,
                    reused = false,
                    lease = DshVscreenLease(myGeneration, lateHandle.displayId),
                    hint = hint.take(DshVscreenLimits.MAX_MESSAGE_CHARS)
                )
            )
        }
        pumpTarget?.let { active -> startPreviewPump(active, myGeneration) }
        return result
    }

    override suspend fun status(): DshVscreenResult<DshVscreenStatusInfo> = mutex.withLock {
        val running = state == DshVscreenState.RUNNING
        val current = handle
        DshVscreenResult.Success(
            DshVscreenStatusInfo(
                state = state,
                displayId = if (running) current?.displayId ?: -1 else -1,
                geometry = if (running) geometry else null,
                running = running,
                hasFrame = running && hasFrame,
                lease = if (running && current != null) DshVscreenLease(generation, current.displayId) else null,
                transport = transportSelection
            )
        )
    }

    override suspend fun see(request: DshVscreenSeeRequest): DshVscreenResult<DshVscreenFrameInfo> {
        val startedAt = clock()
        var attempts = 0
        while (true) {
            val active: DshVscreenDisplayHandle = mutex.withLock {
                if (state != DshVscreenState.RUNNING) {
                    return failure(
                        DshVscreenErrorCode.NO_DISPLAY,
                        "no virtual display is running",
                        displayId = -1,
                        hasFrame = false,
                        waitedMs = clock() - startedAt,
                        attempts = attempts,
                        hint = "call android_vscreen_create first"
                    )
                }
                handle ?: return failure(
                    DshVscreenErrorCode.NO_DISPLAY,
                    "no virtual display is running",
                    displayId = -1,
                    hasFrame = false,
                    waitedMs = clock() - startedAt,
                    attempts = attempts,
                    hint = "call android_vscreen_create first"
                )
            }
            attempts += 1
            val frame = try {
                withTimeoutOrNull(DshVscreenLimits.FRAME_READ_TIMEOUT_MILLIS) {
                    active.latestFrame(DshVscreenLimits.FRAME_READ_TIMEOUT_MILLIS)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Exception) {
                null
            }
            if (frame != null) {
                when (val admission = framePolicy.admit(frame, clock(), lastFrameAtMillis)) {
                    DshVscreenFrameAdmission.Deliver -> {
                        val persisted = try {
                            frameSink.persist(frame, active.displayId)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (unexpected: Exception) {
                            return failure(
                                DshVscreenErrorCode.INTERNAL,
                                "the captured frame could not be persisted",
                                displayId = active.displayId,
                                hasFrame = mutex.withLock { hasFrame },
                                waitedMs = clock() - startedAt,
                                attempts = attempts,
                                hint = "the frame was captured but not written to the cache directory"
                            )
                        }
                        val admittedAt = clock()
                        mutex.withLock {
                            if (state == DshVscreenState.RUNNING && handle === active) {
                                hasFrame = true
                                lastFrameAtMillis = admittedAt
                            }
                        }
                        frameCache.store(frame, admittedAt)
                        previewPort.onFrame(active.displayId, frame, admittedAt)
                        return DshVscreenResult.Success(
                            DshVscreenFrameInfo(
                                path = persisted.path,
                                displayId = active.displayId,
                                screenWidth = active.geometry.width,
                                screenHeight = active.geometry.height,
                                imageWidth = persisted.imageWidth,
                                imageHeight = persisted.imageHeight
                            )
                        )
                    }

                    is DshVscreenFrameAdmission.Drop -> Unit

                    is DshVscreenFrameAdmission.Reject -> return failure(
                        admission.code,
                        admission.message,
                        displayId = active.displayId,
                        hasFrame = mutex.withLock { hasFrame },
                        waitedMs = clock() - startedAt,
                        attempts = attempts,
                        hint = "lower the android_vscreen_see frequency or raise the frame budget"
                    )
                }
            }
            val now = clock()
            if (attempts >= DshVscreenLimits.MAX_FRAME_ATTEMPTS ||
                now + DshVscreenLimits.FRAME_POLL_INTERVAL_MILLIS > startedAt + request.waitMs
            ) {
                break
            }
            delay(DshVscreenLimits.FRAME_POLL_INTERVAL_MILLIS)
        }

        val snapshot = mutex.withLock {
            RuntimeSnapshot(
                state = state,
                displayId = handle?.displayId ?: -1,
                hasFrame = hasFrame,
                geometry = geometry
            )
        }
        val cached = frameCache.last(clock())
        if (snapshot.state == DshVscreenState.RUNNING &&
            cached != null &&
            cached.ageMillis <= DshVscreenLimits.MAX_STALE_FRAME_AGE_MILLIS
        ) {
            val persisted = try {
                frameSink.persist(cached.frame, snapshot.displayId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Exception) {
                null
            }
            if (persisted != null) {
                return DshVscreenResult.Success(
                    DshVscreenFrameInfo(
                        path = persisted.path,
                        displayId = snapshot.displayId,
                        screenWidth = snapshot.geometry?.width ?: cached.frame.width,
                        screenHeight = snapshot.geometry?.height ?: cached.frame.height,
                        imageWidth = persisted.imageWidth,
                        imageHeight = persisted.imageHeight
                    )
                )
            }
        }
        return when {
            snapshot.state != DshVscreenState.RUNNING -> failure(
                DshVscreenErrorCode.NO_DISPLAY,
                "no virtual display is running",
                displayId = -1,
                hasFrame = false,
                waitedMs = clock() - startedAt,
                attempts = attempts,
                hint = "call android_vscreen_create first"
            )

            !snapshot.hasFrame -> failure(
                DshVscreenErrorCode.NOT_RENDERED,
                "the virtual display has not produced a first frame yet",
                displayId = snapshot.displayId,
                hasFrame = false,
                waitedMs = clock() - startedAt,
                attempts = attempts,
                hint = "wait for the target app to draw, or retry android_vscreen_see with waitMs up to 5000"
            )

            else -> failure(
                DshVscreenErrorCode.NO_FRAME,
                "the display produced frames earlier but none is available now",
                displayId = snapshot.displayId,
                hasFrame = true,
                waitedMs = clock() - startedAt,
                attempts = attempts,
                hint = "the target app may have exited; relaunch it with android_vscreen_launch"
            )
        }
    }

    override suspend fun tap(request: DshVscreenTapRequest): DshVscreenResult<Unit> {
        val active = activeForAction() ?: return noDisplay()
        val display = active.first
        val selection = active.second
        if (request.x >= display.geometry.width || request.y >= display.geometry.height) {
            return failure(
                DshVscreenErrorCode.INVALID_COORDINATE,
                "tap coordinates are outside the virtual display",
                displayId = display.displayId,
                hint = "coordinates are in display pixels; see returns scaleX/scaleY"
            )
        }
        if (!selection.privileged) {
            return failure(
                DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE,
                "no privileged transport is available for input injection",
                hint = selection.warning
            )
        }
        return execute(
            DshVscreenCommandFactory.inputTap(
                transport = selection.shell,
                targets = privilegedPort.targets(),
                displayId = display.displayId,
                x = request.x,
                y = request.y
            )
        )
    }

    override suspend fun swipe(request: DshVscreenSwipeRequest): DshVscreenResult<Unit> {
        val active = activeForAction() ?: return noDisplay()
        val display = active.first
        val selection = active.second
        if (!withinDisplay(request, display.geometry)) {
            return failure(
                DshVscreenErrorCode.INVALID_COORDINATE,
                "swipe coordinates are outside the virtual display",
                displayId = display.displayId,
                hint = "coordinates are in display pixels; see returns scaleX/scaleY"
            )
        }
        if (!selection.privileged) {
            return failure(
                DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE,
                "no privileged transport is available for input injection",
                hint = selection.warning
            )
        }
        return execute(
            DshVscreenCommandFactory.inputSwipe(
                transport = selection.shell,
                targets = privilegedPort.targets(),
                displayId = display.displayId,
                request = request
            )
        )
    }

    override suspend fun key(request: DshVscreenKeyRequest): DshVscreenResult<Unit> {
        val active = activeForAction() ?: return noDisplay()
        val selection = active.second
        if (!selection.privileged) {
            return failure(
                DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE,
                "no privileged transport is available for key injection",
                hint = selection.warning
            )
        }
        return execute(
            DshVscreenCommandFactory.inputKey(
                transport = selection.shell,
                targets = privilegedPort.targets(),
                displayId = active.first.displayId,
                key = request.key
            )
        )
    }

    override suspend fun launch(request: DshVscreenLaunchRequest): DshVscreenResult<DshVscreenLaunchInfo> {
        val active = activeForAction() ?: return noDisplay()
        val selection = active.second
        if (!selection.privileged) {
            return failure(
                DshVscreenErrorCode.PRIVILEGE_UNAVAILABLE,
                "no privileged transport is available to launch an app on the display",
                hint = selection.warning
            )
        }
        val command = DshVscreenCommandFactory.startApp(
            transport = selection.shell,
            targets = privilegedPort.targets(),
            displayId = active.first.displayId,
            packageName = request.packageName
        )
        return when (val outcome = execute(command)) {
            is DshVscreenResult.Failure -> outcome
            is DshVscreenResult.Success -> DshVscreenResult.Success(
                DshVscreenLaunchInfo(
                    packageName = request.packageName,
                    component = null,
                    displayId = active.first.displayId,
                    hint = "launch dispatched; confirm the frame with android_vscreen_see"
                )
            )
        }
    }

    override suspend fun close(): DshVscreenResult<Unit> = close(null)

    /**
     * Lease-guarded close. A lease from an older generation is rejected instead of closing the
     * display that replaced it; passing null is the route-level close and always targets the
     * current owner generation.
     */
    suspend fun close(lease: DshVscreenLease?): DshVscreenResult<Unit> = mutex.withLock {
        if (lease != null && lease.id != generation) {
            return failure(
                DshVscreenErrorCode.STALE_LEASE,
                "the lease belongs to a previous display generation",
                hint = "only the active lease may close the current display"
            )
        }
        when (state) {
            DshVscreenState.STARTING -> {
                state = DshVscreenState.CLOSING
                stopPreviewPump()
                val late = handle
                handle = null
                clearRuntimeState()
                if (late != null) {
                    try {
                        late.release()
                    } catch (releaseError: Exception) {
                        // Close still reaches CLOSED; a stuck release must not revive the start.
                    }
                }
                state = DshVscreenState.CLOSED
                DshVscreenResult.Success(Unit)
            }

            DshVscreenState.RUNNING -> {
                state = DshVscreenState.CLOSING
                stopPreviewPump()
                val current = handle
                val currentId = current?.displayId ?: -1
                handle = null
                clearRuntimeState()
                var result: DshVscreenResult<Unit> = DshVscreenResult.Success(Unit)
                if (current != null) {
                    try {
                        current.release()
                    } catch (releaseError: Exception) {
                        result = failure(
                            DshVscreenErrorCode.INTERNAL,
                            "the virtual display did not release cleanly",
                            hint = "the owner is closed; a leaked native resource may remain until process exit"
                        )
                    }
                }
                if (currentId >= 0) previewPort.onDisplayStopped(currentId, "closed")
                state = DshVscreenState.CLOSED
                result
            }

            DshVscreenState.CLOSING -> {
                stopPreviewPump()
                handle = null
                clearRuntimeState()
                state = DshVscreenState.CLOSED
                DshVscreenResult.Success(Unit)
            }

            DshVscreenState.CREATED,
            DshVscreenState.CLOSED,
            DshVscreenState.FAILED -> {
                stopPreviewPump()
                clearRuntimeState()
                state = DshVscreenState.CLOSED
                DshVscreenResult.Success(Unit)
            }
        }
    }

    private fun startPreviewPump(handle: DshVscreenDisplayHandle, generationAtStart: Long) {
        val pump = previewPump ?: return
        val scope = previewScope ?: return
        previewJob?.cancel()
        previewJob = pump.start(
            scope = scope,
            handle = handle,
            stillOwned = {
                mutex.withLock {
                    state == DshVscreenState.RUNNING && generation == generationAtStart && this.handle === handle
                }
            },
            onFrame = { frame, admittedAt -> admitPreviewFrame(handle, generationAtStart, frame, admittedAt) }
        )
    }

    /**
     * Admits one pumped frame. It refreshes the same cache `see` uses, so a static display can still
     * be observed, and it never reports a frame for a display the owner no longer owns.
     */
    private suspend fun admitPreviewFrame(
        handle: DshVscreenDisplayHandle,
        generationAtStart: Long,
        frame: DshVscreenFrame,
        admittedAtMillis: Long
    ) {
        val owned = mutex.withLock {
            val current =
                state == DshVscreenState.RUNNING && generation == generationAtStart && this.handle === handle
            if (current) {
                hasFrame = true
                lastFrameAtMillis = admittedAtMillis
                frameCache.store(frame, admittedAtMillis)
            }
            current
        }
        if (owned) previewPort.onFrame(handle.displayId, frame, admittedAtMillis)
    }

    private fun stopPreviewPump() {
        previewJob?.cancel()
        previewJob = null
    }

    private suspend fun activeForAction(): Pair<DshVscreenDisplayHandle, DshVscreenTransportSelection>? =
        mutex.withLock {
            if (state != DshVscreenState.RUNNING) return@withLock null
            val current = handle ?: return@withLock null
            Pair(current, transportSelection ?: DshVscreenTransportSelection.unavailable)
        }

    private suspend fun recordStartFailure(
        generationAtStart: Long,
        result: DshVscreenResult.Failure
    ): DshVscreenResult.Failure = mutex.withLock {
        if (generation == generationAtStart && state == DshVscreenState.STARTING) {
            state = DshVscreenState.FAILED
            stopPreviewPump()
            geometry = null
            hasFrame = false
            lastFrameAtMillis = null
            handle = null
            frameCache.clear()
        }
        result
    }

    /** Caller cancellation must not strand the owner in STARTING. */
    private suspend fun markStartAbortedByCancellation(generationAtStart: Long) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (generation == generationAtStart &&
                    (state == DshVscreenState.STARTING || state == DshVscreenState.CLOSING)
                ) {
                    state = DshVscreenState.FAILED
                    stopPreviewPump()
                    geometry = null
                    hasFrame = false
                    lastFrameAtMillis = null
                    handle = null
                    frameCache.clear()
                }
            }
        }
    }

    private suspend fun finishCancelledStart(generationAtStart: Long): DshVscreenResult.Failure = mutex.withLock {
        if (generation == generationAtStart && state == DshVscreenState.STARTING) {
            state = DshVscreenState.CLOSED
        }
        if (state == DshVscreenState.CLOSING) state = DshVscreenState.CLOSED
        stopPreviewPump()
        clearRuntimeState()
        failure(
            DshVscreenErrorCode.CANCELLED,
            "virtual display creation was cancelled by a concurrent close",
            hint = "no display was started"
        )
    }

    private fun clearRuntimeState() {
        geometry = null
        hasFrame = false
        lastFrameAtMillis = null
        frameCache.clear()
    }

    private fun noDisplay(): DshVscreenResult.Failure = failure(
        DshVscreenErrorCode.NO_DISPLAY,
        "no virtual display is running",
        hint = "call android_vscreen_create first"
    )

    private fun withinDisplay(request: DshVscreenSwipeRequest, geometry: DshVscreenGeometry): Boolean =
        request.x1 < geometry.width && request.y1 < geometry.height &&
            request.x2 < geometry.width && request.y2 < geometry.height

    private suspend fun execute(command: DshVscreenPrivilegedCommand): DshVscreenResult<Unit> {
        val result = try {
            privilegedPort.execute(command)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Exception) {
            return failure(
                DshVscreenErrorCode.COMMAND_FAILED,
                "the privileged command could not be started",
                hint = "check that the selected transport is still alive"
            )
        }
        if (result.timedOut) {
            return failure(
                DshVscreenErrorCode.TIMEOUT,
                "the privileged command timed out",
                hint = "the transport did not answer within the bounded command timeout"
            )
        }
        if (result.channelLost) {
            return failure(
                DshVscreenErrorCode.CHANNEL_LOST,
                "the privileged channel stopped working",
                hint = "re-grant root or start Shizuku and retry; nothing was injected"
            )
        }
        if (result.exitCode != 0) {
            return failure(
                DshVscreenErrorCode.COMMAND_FAILED,
                "the privileged command failed with exit code ${result.exitCode}",
                hint = "the shell channel exists but the platform rejected the command"
            )
        }
        return DshVscreenResult.Success(Unit)
    }

    private data class RuntimeSnapshot(
        val state: DshVscreenState,
        val displayId: Int,
        val hasFrame: Boolean,
        val geometry: DshVscreenGeometry?
    )
}
