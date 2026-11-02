package com.muyuchat.core.dsh.vscreen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Low-rate preview pump for the running display.
 *
 * The display only pushes frames when its content changes, so a pull-only preview would freeze on a
 * static app. The pump reads the newest available frame on a bounded cadence and hands it to the
 * owner, which admits it through the same [DshVscreenFramePolicy] used by `see` and then forwards it
 * to the preview port. Every frame it admits also refreshes the owner's frame cache, so a later
 * `see` on a static display is served from cache instead of reporting `no_frame`.
 *
 * Ownership: the pump runs in the scope supplied by the app composition and is cancelled by
 * [VirtualScreenOwner] on every terminal transition. It never creates its own scope, and it never
 * delivers a frame after the owner stopped owning the display ([stillOwned]).
 */
class DshVscreenPreviewPump(
    private val framePolicy: DshVscreenFramePolicy,
    private val intervalMillis: Long = DshVscreenLimits.PREVIEW_PUMP_INTERVAL_MILLIS,
    private val readTimeoutMillis: Long = DshVscreenLimits.FRAME_READ_TIMEOUT_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    init {
        require(intervalMillis in DshVscreenLimits.MIN_PREVIEW_PUMP_INTERVAL_MILLIS..DshVscreenLimits.MAX_PREVIEW_PUMP_INTERVAL_MILLIS) {
            "preview pump interval is out of range"
        }
        require(readTimeoutMillis in 1L..DshVscreenLimits.FRAME_READ_TIMEOUT_MILLIS) {
            "preview read timeout is out of range"
        }
    }

    fun start(
        scope: CoroutineScope,
        handle: DshVscreenDisplayHandle,
        stillOwned: suspend () -> Boolean,
        onFrame: suspend (DshVscreenFrame, Long) -> Unit
    ): Job = scope.launch {
        while (isActive) {
            delay(intervalMillis)
            if (!isActive || !stillOwned()) return@launch
            val frame = withTimeoutOrNull(readTimeoutMillis) {
                try {
                    handle.latestFrame(readTimeoutMillis)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }
            } ?: continue
            val admittedAt = clock()
            when (framePolicy.admit(frame, admittedAt, null)) {
                DshVscreenFrameAdmission.Deliver -> onFrame(frame, admittedAt)
                is DshVscreenFrameAdmission.Drop -> Unit
                is DshVscreenFrameAdmission.Reject -> Unit
            }
        }
    }
}
