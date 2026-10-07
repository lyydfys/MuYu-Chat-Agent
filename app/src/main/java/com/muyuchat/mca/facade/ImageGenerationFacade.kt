package com.muyuchat.mca.facade

import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.runtime.CallbackRouter
import com.muyuchat.core.engine.runtime.EventConsumer
import com.muyuchat.core.engine.runtime.RequestCancellationController
import com.muyuchat.core.engine.runtime.RequestRegistry
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
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
 */
class ImageGenerationFacade(
    private val supervisor: RuntimeSupervisor
) {
    private val registry: RequestRegistry = supervisor.requestRegistry
    private val cancellation: RequestCancellationController = supervisor.cancellationController
    private val router: CallbackRouter = supervisor.callbackRouter

    /**
     * Dispatches an image generation command.
     */
    fun dispatchImage(command: ImageCommand, onEvent: EventConsumer): RequestIdentity {
        val identity = RequestIdentity(
            requestId = command.requestId,
            runtimeEpoch = supervisor.currentEpoch.value
        )

        cancellation.registerRequest(identity)
        router.subscribe(identity.requestId, onEvent)

        registry.register(identity)
        registry.transitionState(identity.requestId, RequestState.Accepted)

        return identity
    }

    /**
     * Cancels an ongoing image generation task cooperatively.
     */
    fun cancelImage(requestId: String, reason: String = "User cancelled image"): Boolean {
        return cancellation.requestCancellation(requestId, reason)
    }

    /**
     * Checks if an image job is actively running.
     */
    fun isImageActive(requestId: String): Boolean {
        return registry.isActive(requestId)
    }
}
