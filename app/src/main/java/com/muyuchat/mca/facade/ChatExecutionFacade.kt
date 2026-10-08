package com.muyuchat.mca.facade

import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.runtime.CallbackRouter
import com.muyuchat.core.engine.runtime.EventConsumer
import com.muyuchat.core.engine.runtime.RequestCancellationController
import com.muyuchat.core.engine.runtime.RequestRegistry
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import java.util.UUID

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
 */
class ChatExecutionFacade(
    private val supervisor: RuntimeSupervisor
) {
    private val registry: RequestRegistry = supervisor.requestRegistry
    private val cancellation: RequestCancellationController = supervisor.cancellationController
    private val router: CallbackRouter = supervisor.callbackRouter

    /**
     * Dispatches a chat command, returning its validated RequestIdentity.
     */
    fun dispatchChat(command: ChatCommand, onEvent: EventConsumer): RequestIdentity {
        val identity = RequestIdentity(
            requestId = command.requestId,
            runtimeEpoch = supervisor.currentEpoch.value
        )

        // 1. Register for cancellation tracking and callback routing
        cancellation.registerRequest(identity)
        router.subscribe(identity, onEvent)

        // 2. Register with Exactly-Once state machine
        registry.register(identity)
        registry.transitionState(identity.requestId, RequestState.Accepted)

        return identity
    }

    /**
     * Requests cancellation for an active chat command.
     */
    fun cancelChat(requestId: String, reason: String = "User cancelled"): Boolean {
        return cancellation.requestCancellation(requestId, reason)
    }

    /**
     * Checks if a chat request is actively processing.
     */
    fun isChatActive(requestId: String): Boolean {
        return registry.isActive(requestId)
    }
}
