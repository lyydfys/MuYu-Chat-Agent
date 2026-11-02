package com.muyuchat.api.dsh.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Why one gateway call stopped early. Only the first reason observed for a call is kept. */
internal enum class DshCallCancelReason {
    CLIENT_DISCONNECT,
    EXPLICIT_REQUEST,
    SERVER_STOP
}

/**
 * One admitted model call.
 *
 * The call id is the stable gateway identity `dsh:<runtimeEpoch>:<ordinal>` from the frozen
 * contract. Cancellation is per call: [requestCancel] transitions once, wakes the streaming
 * handler, cancels only the child job that collects this call's backend flow, and then dispatches
 * the adapter's exact stop signal. The engine stop must be dispatched before a cancel is
 * acknowledged, so [requestCancel] is suspend; the gateway bounds how long it can wait for the
 * adapter to return.
 */
internal class DshGatewayCall internal constructor(
    val callId: String,
    private val onCancelled: suspend (String) -> Unit
) {
    private val reason = AtomicReference<DshCallCancelReason?>(null)
    private val cancellation = CompletableDeferred<DshCallCancelReason>()

    @Volatile
    private var collector: Job? = null

    val cancellationSignal: Deferred<DshCallCancelReason> get() = cancellation

    val cancelReason: DshCallCancelReason? get() = reason.get()

    fun attachCollector(job: Job) {
        collector = job
        if (reason.get() != null) {
            job.cancel(CancellationException("DshGateway call was already cancelled."))
        }
    }

    /** Returns true only for the transition that actually cancelled this call. */
    suspend fun requestCancel(reason: DshCallCancelReason): Boolean {
        if (!this.reason.compareAndSet(null, reason)) return false
        cancellation.complete(reason)
        // Stop writing deltas first: the streaming handler wakes on the signal above and no
        // further backend output can reach the peer after a cancel decision.
        collector?.cancel(CancellationException("DshGateway call cancelled: ${reason.name}."))
        // The out-of-band backend signal fires exactly once, whichever path (peer disconnect,
        // explicit cancel route or server stop) observed the transition first. A backend failure
        // is contained here: the request is already cancelled and the terminal is settled by the
        // streaming handler, so a failing native stop must not turn it into a second outcome.
        try {
            onCancelled(callId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Contained on purpose; the cancellation transition itself already succeeded.
        }
        return true
    }
}

/**
 * Bounded registry of in-flight calls.
 *
 * The ordinal is monotonic for the lifetime of the server instance (it is not reset by stop and
 * start), so a call id is never reused while the runtime epoch stays the same. The host must use a
 * new runtime epoch for every new DSH host generation.
 */
internal class DshGatewayCallRegistry(
    private val runtimeEpoch: Long,
    private val maxConcurrentCalls: Int,
    private val onCancel: suspend (String) -> Unit
) {
    private val lock = Any()
    private val active = LinkedHashMap<String, DshGatewayCall>()
    private val ordinal = AtomicLong(0L)

    /** Admits one call or returns null when the concurrency limit is reached. */
    fun admit(): DshGatewayCall? = synchronized(lock) {
        if (active.size >= maxConcurrentCalls) return null
        val call = DshGatewayCall("dsh:$runtimeEpoch:${ordinal.incrementAndGet()}") { callId ->
            onCancel(callId)
        }
        active[call.callId] = call
        return call
    }

    fun release(call: DshGatewayCall) {
        synchronized(lock) {
            if (active[call.callId] === call) active.remove(call.callId)
        }
    }

    fun activeCount(): Int = synchronized(lock) { active.size }

    /**
     * Cancels one live call.
     *
     * Returns true when the call is (or already was) cancelled, i.e. also for a repeated cancel of
     * a call that is still in flight but whose first cancel transition was already caused by
     * another path (the common case is: the peer disconnected, the stop signal was dispatched, and
     * the backend collector is still unwinding when the explicit cancel route arrives). Returning
     * false - and therefore HTTP 404 `call_not_found` - is reserved for an id that is not live
     * here at all; a live cancelled call is acknowledged idempotently. The backend cancel hook is
     * still invoked at most once.
     */
    suspend fun cancel(callId: String, reason: DshCallCancelReason): Boolean {
        val call = synchronized(lock) { active[callId] } ?: return false
        return cancelCall(call, reason) || call.cancelReason != null
    }

    suspend fun cancelAll(reason: DshCallCancelReason) {
        val snapshot = synchronized(lock) { active.values.toList() }
        snapshot.forEach { call -> cancelCall(call, reason) }
    }

    private suspend fun cancelCall(call: DshGatewayCall, reason: DshCallCancelReason): Boolean =
        // requestCancel invokes the out-of-band backend hook exactly once for the transition.
        call.requestCancel(reason)
}
