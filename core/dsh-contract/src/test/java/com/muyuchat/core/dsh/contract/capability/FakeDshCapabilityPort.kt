package com.muyuchat.core.dsh.contract.capability

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * In-memory fake for JVM contract tests. It touches no Android API, no file system and no network.
 *
 * The fake models the semantics a real capability owner must preserve:
 * - one request id is executed at most once per generation;
 * - a terminal result is replayed for a duplicate id without re-execution;
 * - a pending duplicate is rejected with `REQUEST_IN_PROGRESS`;
 * - an unknown outcome is rejected with `OUTCOME_UNKNOWN` and is never replayed;
 * - [revoke] makes later calls fail closed and releases pending calls.
 *
 * It is deliberately not a production adapter. The real adapter still owns SAF, notifications,
 * accessibility and workspace staging.
 */
class FakeDshCapabilityPort(
    var generation: Long = 1L,
    var autoRelease: Boolean = true
) : DshCapabilityPort, DshCapabilityCancellationPort {

    private val lock = Any()
    private var revoked = false
    private val statuses = linkedMapOf<DshCapability, DshCapabilityState>()
    private val calls = mutableListOf<DshCapabilityRequest>()
    private val owners = linkedMapOf<DshCapabilityRequestId, DshCapability>()
    private val pending = linkedMapOf<DshCapabilityRequestId, PendingCall>()
    private val executionCounts = linkedMapOf<DshCapabilityRequestId, Int>()

    private val pickStates = linkedMapOf<DshCapabilityRequestId, CallState>()
    private val saveStates = linkedMapOf<DshCapabilityRequestId, CallState>()
    private val notifyStates = linkedMapOf<DshCapabilityRequestId, CallState>()
    private val deviceStates = linkedMapOf<DshCapabilityRequestId, CallState>()

    var pickResult: DshPickFilesResult = DshPickFilesResult.Picked(emptyList())
    var saveResult: DshSaveFileResult = DshSaveFileResult.Cancelled
    var notifyResult: DshNotifyResult = DshNotifyResult.Posted(1)
    var deviceResult: DshDeviceActionResult = DshDeviceActionResult.Completed()

    fun setStatus(capability: DshCapability, state: DshCapabilityState) {
        synchronized(lock) {
            statuses[capability] = state
        }
    }

    fun callCount(): Int = synchronized(lock) { calls.size }

    fun executionsOf(requestId: DshCapabilityRequestId): Int =
        synchronized(lock) { executionCounts[requestId] ?: 0 }

    /** Fails every subsequent call closed and releases pending calls without executing them. */
    fun revoke() {
        val released = synchronized(lock) {
            revoked = true
            val snapshot = pending.values.toList()
            pending.clear()
            snapshot
        }
        released.forEach { it.gate.complete(Gate.REVOKED) }
    }

    /** Lets a pending call proceed when [autoRelease] is false. */
    fun release(requestId: DshCapabilityRequestId): Boolean {
        val call = synchronized(lock) { pending[requestId] } ?: return false
        return call.gate.complete(Gate.OPEN)
    }

    suspend fun awaitPending(requestId: DshCapabilityRequestId, timeoutMillis: Long = 2_000L) {
        withTimeout(timeoutMillis) {
            while (!synchronized(lock) { pending.containsKey(requestId) }) {
                delay(1L)
            }
        }
    }

    /**
     * Marks a pending or terminal outcome as unknown. This models a timed-out call or a lost
     * process result: a later duplicate must fail closed instead of re-executing the request.
     */
    fun markOutcomeUnknown(requestId: DshCapabilityRequestId): Boolean {
        val pendingCall = synchronized(lock) { pending.remove(requestId) }
        if (pendingCall != null) {
            return pendingCall.gate.complete(Gate.UNKNOWN)
        }
        return synchronized(lock) {
            var changed = markUnknownIn(pickStates, requestId)
            changed = markUnknownIn(saveStates, requestId) || changed
            changed = markUnknownIn(notifyStates, requestId) || changed
            changed = markUnknownIn(deviceStates, requestId) || changed
            changed
        }
    }

    override suspend fun status(capability: DshCapability): DshCapabilityStatus =
        synchronized(lock) {
            val state = if (revoked) {
                DshCapabilityState.DENIED
            } else {
                statuses[capability] ?: DshCapabilityState.GRANTED
            }
            DshCapabilityStatus(
                capability = capability,
                state = state,
                detail = if (revoked) "capability generation was revoked" else null
            )
        }

    override suspend fun pickFiles(request: DshPickFilesRequest): DshPickFilesResult =
        awaitOutcome(
            registry = pickStates,
            request = request,
            replay = { it as? DshPickFilesResult },
            rejected = { DshPickFilesResult.Rejected(it) },
            cancelled = { DshPickFilesResult.Cancelled },
            execute = { pickResult }
        )

    override suspend fun saveFile(request: DshSaveFileRequest): DshSaveFileResult =
        awaitOutcome(
            registry = saveStates,
            request = request,
            replay = { it as? DshSaveFileResult },
            rejected = { DshSaveFileResult.Rejected(it) },
            cancelled = { DshSaveFileResult.Cancelled },
            execute = { saveResult }
        )

    override suspend fun notify(request: DshNotifyRequest): DshNotifyResult =
        awaitOutcome(
            registry = notifyStates,
            request = request,
            replay = { it as? DshNotifyResult },
            rejected = { DshNotifyResult.Rejected(it) },
            cancelled = { DshNotifyResult.Cancelled },
            execute = { notifyResult }
        )

    override suspend fun performDeviceAction(request: DshDeviceActionRequest): DshDeviceActionResult =
        awaitOutcome(
            registry = deviceStates,
            request = request,
            replay = { it as? DshDeviceActionResult },
            rejected = { DshDeviceActionResult.Rejected(it) },
            cancelled = { DshDeviceActionResult.Cancelled },
            execute = { deviceResult }
        )

    override suspend fun cancel(request: DshCapabilityCancelRequest): DshCapabilityCancelResult =
        synchronized(lock) {
            if (revoked) return@synchronized DshCapabilityCancelResult.StaleGeneration
            if (request.generation != generation) {
                return@synchronized DshCapabilityCancelResult.StaleGeneration
            }
            val call = pending.remove(request.requestId)
            if (call != null) {
                val cancelled = call.gate.complete(Gate.CANCELLED)
                val result = if (cancelled) {
                    DshCapabilityCancelResult.Cancelled
                } else {
                    DshCapabilityCancelResult.AlreadyTerminal
                }
                return@synchronized result
            }
            val terminal = pickStates[request.requestId] is CallState.Terminal ||
                saveStates[request.requestId] is CallState.Terminal ||
                notifyStates[request.requestId] is CallState.Terminal ||
                deviceStates[request.requestId] is CallState.Terminal
            if (terminal) DshCapabilityCancelResult.AlreadyTerminal else DshCapabilityCancelResult.NotFound
        }

    private suspend fun <T : DshCapabilityResult> awaitOutcome(
        registry: MutableMap<DshCapabilityRequestId, CallState>,
        request: DshCapabilityRequest,
        replay: (DshCapabilityResult) -> T?,
        rejected: (DshCapabilityFailure) -> T,
        cancelled: () -> T,
        execute: () -> T
    ): T {
        var gateCall: PendingCall? = null
        synchronized(lock) {
            calls += request
            if (revoked) {
                return rejected(
                    failure(
                        DshCapabilityErrorCode.STALE_GENERATION,
                        request.capability,
                        "capability generation was revoked"
                    )
                )
            }
            if (request.generation != generation) {
                return rejected(
                    failure(
                        DshCapabilityErrorCode.STALE_GENERATION,
                        request.capability,
                        "capability generation is stale"
                    )
                )
            }
            val owner = owners[request.requestId]
            if (owner != null && owner != request.capability) {
                return rejected(
                    failure(
                        DshCapabilityErrorCode.INVALID_REQUEST,
                        request.capability,
                        "request id is already bound to another capability"
                    )
                )
            }
            when (val state = registry[request.requestId]) {
                is CallState.Pending -> return rejected(
                    failure(
                        DshCapabilityErrorCode.REQUEST_IN_PROGRESS,
                        request.capability,
                        "request is still pending",
                        retryable = true
                    )
                )
                is CallState.Terminal -> {
                    val replayed = replay(state.result)
                    return if (replayed != null) {
                        replayed
                    } else {
                        rejected(
                            failure(
                                DshCapabilityErrorCode.INVALID_REQUEST,
                                request.capability,
                                "request id belongs to another capability"
                            )
                        )
                    }
                }
                CallState.Unknown -> return rejected(
                    failure(
                        DshCapabilityErrorCode.OUTCOME_UNKNOWN,
                        request.capability,
                        "outcome is unknown; the request is not replayed"
                    )
                )
                null -> {
                    owners[request.requestId] = request.capability
                    val call = PendingCall()
                    registry[request.requestId] = CallState.Pending(call)
                    pending[request.requestId] = call
                    gateCall = call
                }
            }
        }

        val call = gateCall ?: error("pending capability call was not registered")
        if (autoRelease) call.gate.complete(Gate.OPEN)
        val gate = try {
            call.gate.await()
        } catch (cancelledByCoroutine: kotlinx.coroutines.CancellationException) {
            synchronized(lock) {
                pending.remove(request.requestId)
                registry[request.requestId] = CallState.Unknown
            }
            throw cancelledByCoroutine
        }

        synchronized(lock) {
            pending.remove(request.requestId)
            return when (gate) {
                Gate.OPEN -> {
                    if (revoked || request.generation != generation) {
                        registry[request.requestId] = CallState.Unknown
                        rejected(
                            failure(
                                DshCapabilityErrorCode.STALE_GENERATION,
                                request.capability,
                                "capability generation changed before execution"
                            )
                        )
                    } else {
                        executionCounts[request.requestId] =
                            (executionCounts[request.requestId] ?: 0) + 1
                        val result = execute()
                        registry[request.requestId] = CallState.Terminal(result)
                        result
                    }
                }
                Gate.CANCELLED -> {
                    val result = cancelled()
                    registry[request.requestId] = CallState.Terminal(result)
                    result
                }
                Gate.REVOKED -> {
                    registry[request.requestId] = CallState.Unknown
                    rejected(
                        failure(
                            DshCapabilityErrorCode.STALE_GENERATION,
                            request.capability,
                            "capability generation was revoked"
                        )
                    )
                }
                Gate.UNKNOWN -> {
                    registry[request.requestId] = CallState.Unknown
                    rejected(
                        failure(
                            DshCapabilityErrorCode.OUTCOME_UNKNOWN,
                            request.capability,
                            "outcome is unknown; the request is not replayed"
                        )
                    )
                }
            }
        }
    }

    private fun markUnknownIn(
        registry: MutableMap<DshCapabilityRequestId, CallState>,
        requestId: DshCapabilityRequestId
    ): Boolean {
        if (registry[requestId] is CallState.Terminal) {
            registry[requestId] = CallState.Unknown
            return true
        }
        return false
    }

    private fun failure(
        code: DshCapabilityErrorCode,
        capability: DshCapability,
        message: String,
        retryable: Boolean = false
    ): DshCapabilityFailure = DshCapabilityFailure(
        code = code,
        capability = capability,
        message = message,
        retryable = retryable
    )

    private sealed interface CallState {
        data class Pending(val call: PendingCall) : CallState
        data class Terminal(val result: DshCapabilityResult) : CallState
        data object Unknown : CallState
    }

    private class PendingCall {
        val gate: CompletableDeferred<Gate> = CompletableDeferred()
    }

    private enum class Gate {
        OPEN,
        CANCELLED,
        REVOKED,
        UNKNOWN
    }
}
