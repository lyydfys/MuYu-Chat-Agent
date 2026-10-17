package com.muyuchat.mca.facade

import com.muyuchat.core.engine.ChatExecutionBody
import com.muyuchat.core.engine.ChatExecutionOwner
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.ErrorEnvelope
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
import kotlinx.coroutines.flow.Flow

/**
 * Command representing an incoming chat request from UI, Local API, or Worker.
 */
data class ChatCommand(
    val prompt: String,
    val sessionId: String = "default-session",
    val systemPrompt: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val requestId: String = UUID.randomUUID().toString()
)

/**
 * Unified Facade for chat interactions.
 * Single entry point handling request identity generation, event subscription,
 * cooperative cancellation, and terminal states across all client channels.
 *
 * ## Wiring status: the production single chat entry (E06)
 *
 * 1. **Real execution.** [streamChat] admits the request through the shared [RuntimeSupervisor]
 *    ([ChatExecutionOwner]) and returns the engine's event flow. The production composition
 *    (`McaAppContainer.createChatLocalApiProviderPort`) attaches the execution body, and the Local
 *    API chat provider installs it for every channel (`LocalApiRuntime.streamChatWithContextProvider`),
 *    which is also the funnel the UI turn executor already calls. There is no second production
 *    execution body.
 * 2. **Stop entry points.** [stopGeneration] / [stopGenerationIfActive] replace the direct engine
 *    stops in the provider while keeping their return semantics.
 * 3. **Admission boundary retained.** [dispatchChat] is still the identity-only admission entry used
 *    by the worker-boundary and request-risk contract tests; it is not a chat execution path.
 * 4. **LocalApiFacadeAdapter** remains test-only; it is not part of the production chat path.
 */
class ChatExecutionFacade(
    val supervisor: RuntimeSupervisor,
    executionBody: ChatExecutionBody
) {
    /** The single chat execution owner for this supervisor. */
    private val owner: ChatExecutionOwner = ChatExecutionOwner(supervisor, executionBody)

    private val registry: RequestRegistry = supervisor.requestRegistry
    private val cancellation: RequestCancellationController = supervisor.cancellationController
    private val router: CallbackRouter = supervisor.callbackRouter

    /**
     * Dispatches a chat command, returning its validated RequestIdentity.
     *
     * Admission is delegated to [RuntimeSupervisor.admitRequest], the single owner that atomically
     * checks the lifecycle, reads the current epoch, assigns a monotonic attempt identity, reserves
     * the live registry entry, registers the cancellation handle and subscribes the callback route.
     * That gives every dispatch its own execution identity (so a retired attempt can never settle a
     * replacement that reused the requestId), rejects new work after shutdown, and keeps a late
     * dispatch from registering itself under an epoch an advance is concurrently superseding.
     *
     * Registration used to happen here, after admission had already returned, which left a window in
     * which a shutdown could close admission and clear everything before this method subscribed its
     * route and published its cancel handle; the dispatch then owned resources on a shut-down
     * runtime. The reservation now belongs to the admission transaction and is rolled back there, so
     * this method has nothing left to clean up on failure.
     */
    fun dispatchChat(command: ChatCommand, onEvent: EventConsumer): RequestIdentity {
        val entry = supervisor.admitRequest(command.requestId, onEvent)
            ?: throw IllegalStateException(
                "Runtime supervisor is shut down; chat request ${command.requestId} was rejected."
            )
        return entry.identity
    }

    /**
     * Single production chat entry: admits one turn in the shared supervisor and returns the
     * engine's event flow (see the class KDoc). Throws [IllegalStateException] on a concrete
     * runtime conflict (runtime shut down, or the id already live).
     */
    fun streamChat(request: ChatRequest, context: LocalChatExecutionContext): Flow<GenerateEvent> =
        owner.stream(request, context)

    /**
     * Stops the current generation through the shared owner: supervisor cancellation bookkeeping
     * plus the engine's existing unconditional stop.
     */
    suspend fun stopGeneration() {
        owner.stopGeneration()
    }

    /**
     * Attempt-checked stop by request id; returns the engine's historical "stop dispatched" answer
     * so Local API responses stay unchanged.
     */
    suspend fun stopGenerationIfActive(requestId: String): Boolean =
        owner.stopGenerationIfActive(requestId) ?: false

    /**
     * Requests cancellation for the currently tracked attempt of [requestId] (client-facing
     * "cancel by id": the client protocol only knows the id, so it targets whichever attempt holds it).
     */
    fun cancelChat(requestId: String, reason: String = "User cancelled"): Boolean {
        return cancellation.requestCancellation(requestId, reason)
    }

    /**
     * Attempt-checked cancellation: only the exact [identity] can cancel its own request. Attempt-owned
     * actions (stream failure, disconnect cleanup, timeouts) must use this form so a late action of a
     * retired attempt cannot cancel its replacement.
     */
    fun cancelChat(identity: RequestIdentity, reason: String = "User cancelled"): Boolean {
        return cancellation.requestCancellation(identity, reason)
    }

    /**
     * Checks if a chat request is actively processing.
     */
    fun isChatActive(requestId: String): Boolean {
        return registry.isActive(requestId)
    }

    /**
     * Read-only terminal/state query. A caller that only observes a request must not hold the live
     * registry entry, and a broken delivery channel still needs a way to report what happened.
     *
     * Once an attempt is settled its live registry entry is evicted (terminal cleanup), so this
     * falls back to the bounded outcome record. The observable contract is unchanged -- a caller that
     * queried a terminal snapshot before cleanup still gets one afterwards -- while the live index
     * stops retaining finished requests.
     */
    fun snapshot(requestId: String): RequestSnapshot? {
        registry.snapshot(requestId)?.let { return it }
        val outcome = router.terminalRecord(requestId) ?: return null
        return outcome.toRequestSnapshot()
    }

    /**
     * Bounded terminal outcome of the attempt that currently owns [requestId]. This is the query that
     * still answers after [settleStreamFailure] or a normal completion has released the live registry
     * entry, cancel handle and callback route, so "which terminal state did it reach, and was it
     * delivered" stays observable without holding a live request.
     */
    fun terminalOutcome(requestId: String): TerminalDeliveryRecord? = router.terminalRecord(requestId)

    /**
     * Settles a request whose event channel could not deliver its frames (bounded SSE backpressure
     * overflow): the request is cancelled cooperatively, its terminal state is settled exactly once,
     * and the attempt's cancel handle, callback route and live registry entry are released while the
     * bounded outcome above stays queryable.
     *
     * The full [identity] is required: a stream failure belongs to the exact attempt that dispatched
     * it, so a late failure report cannot settle a replacement request that reused the same id.
     */
    fun settleStreamFailure(identity: RequestIdentity, reason: String): Boolean {
        // Attempt-checked cancel first: only the exact attempt that still owns the requestId is
        // marked, so a late stream failure cannot cancel the replacement.
        cancellation.requestCancellation(identity, reason)
        return supervisor.settleRequest(identity, RequestState.Failed)
    }

    companion object {
        /** Admission/cancellation-only fixture for contract tests; it cannot stream chat. */
        internal fun admissionOnly(supervisor: RuntimeSupervisor = RuntimeSupervisor()): ChatExecutionFacade =
            ChatExecutionFacade(
                supervisor,
                object : ChatExecutionBody {
                    override fun stream(
                        request: ChatRequest,
                        context: LocalChatExecutionContext
                    ): Flow<GenerateEvent> = kotlinx.coroutines.flow.emptyFlow()

                    override suspend fun stopGeneration() = Unit

                    override suspend fun stopGenerationIfActive(requestId: String): Boolean = false
                }
            )
    }

}
