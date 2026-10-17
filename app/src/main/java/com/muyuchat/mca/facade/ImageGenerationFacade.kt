package com.muyuchat.mca.facade

import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.runtime.CallbackRouter
import com.muyuchat.core.engine.runtime.EventConsumer
import com.muyuchat.core.engine.runtime.RequestCancellationController
import com.muyuchat.core.engine.runtime.RequestRegistry
import com.muyuchat.core.engine.runtime.RequestSnapshot
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import com.muyuchat.core.engine.runtime.TerminalDeliveryRecord
import java.util.UUID

/**
 * Command representing an image generation task.
 */
data class ImageCommand(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfgScale: Float = 7.0f,
    val seed: Long = -1L,
    val targetOutputPath: String,
    val requestId: String = UUID.randomUUID().toString()
)

/**
 * Unified Facade for local image generation requests across UI, Local API, and Workers.
 *
 * ## Wiring status: not wired into the production path, and no execution happens here
 *
 * Audited state, recorded rather than implied:
 *
 * 1. **Admission only, no generation.** [dispatchImage] registers the request (identity, cancel
 *    record, callback subscription, `Accepted` state) and returns the identity. No image backend is
 *    asked to generate anything, so this is an identity/admission boundary.
 * 2. **No production caller at all.** The only class that holds a facade is
 *    `com.muyuchat.mca.adapter.LocalApiFacadeAdapter`, and that adapter is itself unwired: it is
 *    constructed exclusively by tests (`WorkerBoundaryTest`, `RequestRiskContractTest`), never by
 *    the application, `MainViewModel`, the Local API server, or any worker.
 */
class ImageGenerationFacade(
    private val supervisor: RuntimeSupervisor
) {
    private val registry: RequestRegistry = supervisor.requestRegistry
    private val cancellation: RequestCancellationController = supervisor.cancellationController
    private val router: CallbackRouter = supervisor.callbackRouter

    /**
     * Dispatches an image generation command.
     *
     * Admission goes through [RuntimeSupervisor.admitRequest]: lifecycle check, epoch read, a freshly
     * assigned monotonic attempt identity, the live registry entry, the cancellation handle and the
     * callback route all happen in one transaction, so a re-dispatch after a completed request never
     * reuses the previous execution identity and a shut-down runtime rejects the work instead of
     * leaving an `Accepted` request, a pending cancel record or a subscribed route behind.
     */
    fun dispatchImage(command: ImageCommand, onEvent: EventConsumer): RequestIdentity {
        val entry = supervisor.admitRequest(command.requestId, onEvent)
            ?: throw IllegalStateException(
                "Runtime supervisor is shut down; image request ${command.requestId} was rejected."
            )
        return entry.identity
    }

    /**
     * Cancels the currently tracked attempt of [requestId] cooperatively (client-facing cancel by id).
     */
    fun cancelImage(requestId: String, reason: String = "User cancelled image"): Boolean {
        return cancellation.requestCancellation(requestId, reason)
    }

    /**
     * Attempt-checked cancellation: only the exact [identity] can cancel its own request, so a late
     * action of a retired attempt cannot cancel its replacement.
     */
    fun cancelImage(identity: RequestIdentity, reason: String = "User cancelled image"): Boolean {
        return cancellation.requestCancellation(identity, reason)
    }

    /**
     * Checks if an image job is actively running.
     */
    fun isImageActive(requestId: String): Boolean {
        return registry.isActive(requestId)
    }

    /**
     * Read-only terminal/state query, mirroring [ChatExecutionFacade.snapshot]. An observer must
     * never need the live mutable registry entry, and a broken delivery channel still needs a
     * queryable terminal state. After terminal cleanup the bounded outcome record answers instead of
     * the evicted live entry, so the observable contract is unchanged.
     */
    fun snapshot(requestId: String): RequestSnapshot? {
        registry.snapshot(requestId)?.let { return it }
        val outcome = router.terminalRecord(requestId) ?: return null
        return outcome.toRequestSnapshot()
    }

    /**
     * Bounded terminal outcome of the attempt that currently owns [requestId], queryable after the
     * live registry entry, cancel handle and callback route have been released.
     */
    fun terminalOutcome(requestId: String): TerminalDeliveryRecord? = router.terminalRecord(requestId)

    /**
     * Settles an image request whose event channel could not deliver its frames: cooperative cancel,
     * exactly-once terminal settlement, and release of the attempt's cancel handle, callback route and
     * live registry entry while the bounded outcome stays queryable.
     *
     * The full [identity] is required so a late failure report cannot settle a replacement request.
     */
    fun settleStreamFailure(identity: RequestIdentity, reason: String): Boolean {
        // Attempt-checked cancel: only the exact attempt that still owns the requestId is marked.
        cancellation.requestCancellation(identity, reason)
        return supervisor.settleRequest(identity, RequestState.Failed)
    }
}
