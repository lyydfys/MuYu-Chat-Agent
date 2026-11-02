package com.muyuchat.feature.dsh.workspace

/**
 * App-process edge that reports the first visible frame of one DSH host generation to the DSH
 * runtime owner.
 *
 * The workspace invokes it at most once per epoch and only while the frozen
 * `DshRuntimeSnapshot` is READY for exactly that epoch. READY is published by the runtime owner
 * only after its readiness gate verified the authenticated index and the client plugin asset, so
 * this edge adds the app-process observation the host cannot make itself; it can never make the
 * runtime READY, start/stop it or change any phase.
 *
 * The app composition root wires the concrete runtime client, for example:
 * `DshFirstFrameReporter { epoch -> runtimeClient.reportFirstFrameRendered(epoch) }`.
 * When nothing is wired, [None] keeps the page functional and simply does not record the stage.
 * The route caches its ViewModel, so pass a stable instance owned by the composition root rather
 * than a lambda whose captured dependencies change between recompositions.
 *
 * Implementations must be non-blocking and must not throw: the call is an observation from a
 * WebView callback on the Android main thread, not a lifecycle command.
 */
fun interface DshFirstFrameReporter {

    /** Reports the first visible frame of the READY runtime generation [epoch]. */
    fun onFirstFrameRendered(epoch: Long)

    companion object {
        /** No-op reporter for hosts that do not expose runtime startup observability. */
        val None: DshFirstFrameReporter = object : DshFirstFrameReporter {
            override fun onFirstFrameRendered(epoch: Long) = Unit
        }
    }
}
