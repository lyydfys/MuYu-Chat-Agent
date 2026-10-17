package com.muyuchat.core.engine

import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.runtime.EventConsumer
import com.muyuchat.core.engine.runtime.NativeErrorTranslator
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform

/**
 * The real execution body behind [ChatExecutionOwner]: the engine call that actually produces the
 * chat event stream, plus the two stop entry points, kept exactly as the pre-owner callers used
 * them (`McaInferenceService.streamChat` / `stopGeneration` / `stopGenerationIfActive`).
 *
 * It exists as an interface so the owner can be exercised deterministically in host tests without
 * constructing the full engine, and so the engine stays the single implementation of the body.
 */
interface ChatExecutionBody {
    fun stream(request: ChatRequest, context: LocalChatExecutionContext): Flow<GenerateEvent>
    suspend fun stopGeneration()
    suspend fun stopGenerationIfActive(requestId: String): Boolean
}

/**
 * The single chat execution owner.
 *
 * Every UI / Local API / worker chat turn goes through [stream]: the supervisor admits the request
 * exactly once (attempt identity, live registry entry, cancellation handle, attempt route for the
 * bounded terminal record), the engine body produces the event flow unchanged, and the attempt is
 * settled exactly once when that flow ends - success, failure or cancellation.
 *
 * ## Identity and terminal mapping
 *
 * Admission assigns a monotonic attempt for the request id the caller already uses today (`ui-...`
 * for UI turns, `chatcmpl-...` for Local API turns). The state machine is driven along its legal
 * path (`Accepted -> Preparing -> Running`, and `Running -> CancelRequested -> Cancelled` for a
 * cancel), so terminal settlement never bypasses the contract:
 *
 * - normal end, last event not an error -> `Succeeded`
 * - last event is `GenerateEvent.Error` with the cancellation code -> `Cancelled`
 * - last event is any other error event, or the flow threw -> `Failed`
 * - collector cancellation (`CancellationException`) -> `Cancelled`
 *
 * A duplicate live request id is an explicit conflict (the supervisor rejects it) instead of two
 * attempts silently sharing one id; no production channel reuses ids while a turn is live.
 *
 * The route consumer admitted here deliberately does not re-deliver the stream (the caller keeps
 * its `Flow<GenerateEvent>`); it exists so the bounded terminal outcome stays archivable and
 * queryable after the live entry is evicted. Routing chat events through the callback router is a
 * separate migration slice.
 */
class ChatExecutionOwner(
    private val supervisor: RuntimeSupervisor,
    private val body: ChatExecutionBody
) {
    private val registry = supervisor.requestRegistry

    /**
     * Starts one real chat turn under supervisor admission and returns the engine's event flow.
     *
     * Throws [IllegalStateException] when the runtime is shut down or the request id is already
     * live: both are concrete runtime conflicts, not device or profile decisions.
     */
    fun stream(request: ChatRequest, context: LocalChatExecutionContext): Flow<GenerateEvent> {
        val entry = supervisor.admitRequest(context.requestId, TERMINAL_ROUTE_CONSUMER)
            ?: throw IllegalStateException(
                "Runtime supervisor is shut down; chat request ${context.requestId} was rejected."
            )
        val identity = entry.identity
        // Admission may carry an explicit trace; legacy contexts only supply requestId, which is
        // still a stable correlation key for the terminal error.
        val correlationTraceId = identity.traceId.trim().ifBlank { context.requestId }
        var lastEvent: GenerateEvent? = null
        var advancedToRunning = false
        return body.stream(request, context)
            .transform { event ->
                if (!advancedToRunning) {
                    advancedToRunning = true
                    advanceToRunning(identity)
                }
                lastEvent = event
                emit(event)
            }
            .onCompletion { cause ->
                settleAttempt(identity, cause, lastEvent, correlationTraceId)
            }
    }

    /**
     * Stops whatever is currently generating: the supervisor marks every live attempt cancelled and
     * the body performs the engine's existing unconditional stop.
     */
    suspend fun stopGeneration() {
        supervisor.cancellationController.cancelAllActive("Chat generation was stopped.")
        body.stopGeneration()
    }

    /**
     * Attempt-checked stop by request id. The return value keeps the engine's historical meaning
     * ("a stop owned by this id was dispatched"), so Local API responses are unchanged.
     */
    suspend fun stopGenerationIfActive(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        supervisor.cancellationController.requestCancellation(
            requestId,
            "Chat generation was stopped by the client."
        )
        return body.stopGenerationIfActive(requestId)
    }

    private fun advanceToRunning(identity: RequestIdentity) {
        when (registry.snapshot(identity)?.state) {
            RequestState.Accepted -> {
                registry.transitionState(identity, RequestState.Preparing)
                registry.transitionState(identity, RequestState.Running)
            }
            RequestState.Preparing -> registry.transitionState(identity, RequestState.Running)
            else -> Unit
        }
    }

    private fun settleAttempt(
        identity: RequestIdentity,
        cause: Throwable?,
        lastEvent: GenerateEvent?,
        correlationTraceId: String
    ) {
        val errorEvent = lastEvent as? GenerateEvent.Error
        val terminal = when {
            cause is CancellationException -> RequestState.Cancelled
            cause != null -> RequestState.Failed
            errorEvent?.isCancellation() == true -> RequestState.Cancelled
            errorEvent != null -> RequestState.Failed
            else -> RequestState.Succeeded
        }
        if (terminal == RequestState.Cancelled) {
            registry.transitionState(identity, RequestState.CancelRequested)
        } else {
            // The success/failure terminal transitions are only legal from Running; a flow that
            // produced no event (or was still Preparing) must still settle along a legal path.
            advanceToRunning(identity)
        }

        val typedEventError = errorEvent?.errorEnvelope?.let { error ->
            NativeErrorTranslator.attachContext(error, identity, correlationTraceId)
        }
        val translatedEventError = errorEvent?.let { event ->
            NativeErrorTranslator.fromChatError(
                message = event.message,
                code = event.code,
                identity = identity,
                traceId = correlationTraceId
            )
        }
        val error = when (terminal) {
            RequestState.Failed -> typedEventError
                ?: cause?.let { failure ->
                    NativeErrorTranslator.fromThrowable(
                        failure,
                        identity = identity,
                        traceId = correlationTraceId
                    )
                }
                ?: translatedEventError
            RequestState.Cancelled -> typedEventError?.takeIf { it.category == ErrorCategory.Cancel }
                ?: translatedEventError?.takeIf { it.category == ErrorCategory.Cancel }
                ?: NativeErrorTranslator.cancelled(
                    identity = identity,
                    reason = (cause as? CancellationException)?.message
                        ?: errorEvent?.message
                        ?: "Request cancelled by its owner.",
                    traceId = correlationTraceId
                )
            else -> null
        }
        settleWithError(identity, terminal, error)
    }

    /**
     * Settles the registry and publishes the same typed envelope into the bounded terminal record
     * before releasing the attempt resources. Publishing before cleanup is what keeps the real error
     * queryable from both `terminalOutcome` and `snapshot` after the live entry is evicted.
     */
    private fun settleWithError(
        identity: RequestIdentity,
        terminalState: RequestState,
        error: ErrorEnvelope?
    ) {
        val settled = registry.settle(identity, terminalState, error)
        try {
            if (settled != null) {
                supervisor.callbackRouter.recordOutcome(
                    identity = identity,
                    finalState = terminalState,
                    terminalError = error
                )
            }
        } finally {
            supervisor.releaseRequestResources(identity)
        }
    }

    private fun GenerateEvent.Error.isCancellation(): Boolean {
        val normalizedCode = code?.trim()?.uppercase()
        return errorEnvelope?.category == ErrorCategory.Cancel ||
            (normalizedCode != null && normalizedCode in CANCELLATION_CODES)
    }

    private companion object {
        /** Stable cancellation code the engine emits for a client-requested stop. */
        const val CANCELLATION_CODE = "REQUEST_CANCELLED"

        val CANCELLATION_CODES = setOf(
            CANCELLATION_CODE,
            "CANCELLED",
            "CANCELED",
            "CNC_USER_CANCELLED",
            "CNC_ALREADY_CANCELLED"
        )

        /** Attempt route for the bounded terminal record; the caller keeps the event stream. */
        val TERMINAL_ROUTE_CONSUMER = EventConsumer { }
    }
}
