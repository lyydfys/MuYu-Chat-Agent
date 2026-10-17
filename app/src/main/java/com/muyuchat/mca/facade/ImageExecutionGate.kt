package com.muyuchat.mca.facade

import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.runtime.EventConsumer
import com.muyuchat.core.engine.runtime.NativeErrorTranslator
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import com.muyuchat.mca.LocalImageWorkerCancelledException
import kotlinx.coroutines.CancellationException

/**
 * The single execution gate every local image operation passes through
 * (`LocalImageWorkerClient.generate` / `upscale`, which both the UI asset pipeline and the
 * authenticated Local API call).
 *
 * It exists so one process-wide [RuntimeSupervisor] owns the request identity, the live registry
 * entry, the cancellation handle and the exactly-once terminal record of every image operation,
 * while the worker client stays a transport.
 *
 * [PASSTHROUGH] keeps transport-only embeddings (focused tests, tools that talk to a real worker
 * without a runtime host) working unchanged; the production container always installs
 * [ImageExecutionOwner].
 */
interface ImageExecutionGate {

    /**
     * Runs one image operation under supervisor admission and settles its terminal state exactly
     * once. The block's result or exception is propagated unchanged.
     */
    suspend fun <T> run(requestId: String, operation: String, block: suspend () -> T): T

    /** Marks a matched cancel in the supervisor's cancellation bookkeeping. */
    fun cancel(requestId: String)

    /** Marks every live attempt cancelled (unscoped lifecycle cancel). */
    fun cancelAll(reason: String)

    companion object {
        val PASSTHROUGH: ImageExecutionGate = object : ImageExecutionGate {
            override suspend fun <T> run(requestId: String, operation: String, block: suspend () -> T): T =
                block()

            override fun cancel(requestId: String) = Unit

            override fun cancelAll(reason: String) = Unit
        }
    }
}

/**
 * Real image execution owner behind [ImageExecutionGate].
 *
 * Terminal mapping (mirrors the worker's own contract):
 * - block returns -> `Succeeded`
 * - block throws [CancellationException] or the worker's [LocalImageWorkerCancelledException] ->
 *   `Cancelled` (via the legal `Running -> CancelRequested -> Cancelled` path)
 * - any other throwable -> `Failed` with a typed error envelope
 *
 * A duplicate live request id is an explicit conflict (the supervisor rejects it) instead of two
 * attempts sharing one id; both production channels allocate unique ids per operation.
 */
class ImageExecutionOwner(
    private val supervisor: RuntimeSupervisor
) : ImageExecutionGate {

    private val registry = supervisor.requestRegistry

    override suspend fun <T> run(requestId: String, operation: String, block: suspend () -> T): T {
        val entry = supervisor.admitRequest(requestId, TERMINAL_ROUTE_CONSUMER)
            ?: throw IllegalStateException(
                "Runtime supervisor is shut down; image $operation request $requestId was rejected."
            )
        val identity = entry.identity
        registry.transitionState(identity, RequestState.Preparing)
        registry.transitionState(identity, RequestState.Running)
        return try {
            val value = block()
            supervisor.settleRequest(identity, RequestState.Succeeded)
            value
        } catch (cancelled: CancellationException) {
            settleCancelled(identity)
            throw cancelled
        } catch (workerCancelled: LocalImageWorkerCancelledException) {
            settleCancelled(identity)
            throw workerCancelled
        } catch (failure: Throwable) {
            supervisor.settleRequest(
                identity,
                RequestState.Failed,
                NativeErrorTranslator.fromThrowable(failure)
            )
            throw failure
        }
    }

    override fun cancel(requestId: String) {
        if (requestId.isBlank()) return
        supervisor.cancellationController.requestCancellation(
            requestId,
            "Image operation was cancelled."
        )
    }

    override fun cancelAll(reason: String) {
        supervisor.cancellationController.cancelAllActive(reason)
    }

    private fun settleCancelled(identity: RequestIdentity) {
        registry.transitionState(identity, RequestState.CancelRequested)
        supervisor.settleRequest(identity, RequestState.Cancelled)
    }

    private companion object {
        /** Attempt route for the bounded terminal record; callers keep their own result channel. */
        val TERMINAL_ROUTE_CONSUMER = EventConsumer { }
    }
}