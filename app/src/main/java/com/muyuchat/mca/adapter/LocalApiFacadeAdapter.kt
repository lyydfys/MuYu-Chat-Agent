package com.muyuchat.mca.adapter

import com.muyuchat.api.local.adapter.AuthValidationResult
import com.muyuchat.api.local.adapter.LocalApiEventBridge
import com.muyuchat.api.local.adapter.LocalApiRequestMapper
import com.muyuchat.api.local.adapter.NormalizedApiRequest
import com.muyuchat.api.local.adapter.SseFrame
import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.mca.facade.ChatCommand
import com.muyuchat.mca.facade.ChatExecutionFacade
import com.muyuchat.mca.facade.ImageCommand
import com.muyuchat.mca.facade.ImageGenerationFacade
import com.muyuchat.mca.facade.RuntimeFacade
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.util.UUID

/**
 * Result representation for Local API invocations.
 */
sealed interface LocalApiResponse {
    data class SseStream(
        val requestId: String,
        val frames: Flow<SseFrame>
    ) : LocalApiResponse

    data class JsonSuccess(
        val requestId: String,
        val payload: JSONObject
    ) : LocalApiResponse

    data class Error(
        val httpStatus: Int,
        val errorCode: String,
        val message: String,
        val requestId: String? = null
    ) : LocalApiResponse
}

/**
 * Enterprise Facade Adapter for the MCA Local Loopback API.
 *
 * Enforces boundary separation:
 * 1. Authentication and JSON schema validation are evaluated at this gate;
 *    rejected requests terminate immediately and never touch domain facades or native runtimes.
 * 2. Incoming normalized API commands are dispatched to [ChatExecutionFacade] and [ImageGenerationFacade].
 * 3. Domain [CallbackEvent] streams are translated into HTTP SSE frames via [LocalApiEventBridge],
 *    maintaining terminal exactly-once semantics and reconnect sequence filtering.
 */
class LocalApiFacadeAdapter(
    private val chatFacade: ChatExecutionFacade,
    private val imageFacade: ImageGenerationFacade,
    private val runtimeFacade: RuntimeFacade,
    private val expectedApiKey: String? = null
) {
    /**
     * Handles an incoming OpenAI-compatible Chat Completion HTTP POST request.
     */
    fun handleChatCompletion(
        bodyJson: String,
        authHeader: String?,
        resumeAfterSequence: Long = -1L,
        clientIp: String = "127.0.0.1"
    ): LocalApiResponse {
        // Gate 1: Authenticate caller
        val authResult = LocalApiRequestMapper.validateAuth(authHeader, expectedApiKey)
        if (authResult is AuthValidationResult.Failure) {
            return LocalApiResponse.Error(
                httpStatus = authResult.httpStatus,
                errorCode = authResult.errorCode,
                message = authResult.message
            )
        }

        // Gate 2: Map and validate wire schema
        val mappedRequestResult = LocalApiRequestMapper.mapChatCompletion(bodyJson, clientIp)
        val normalizedReq: NormalizedApiRequest = mappedRequestResult.getOrElse { error ->
            return LocalApiResponse.Error(
                httpStatus = 400,
                errorCode = "invalid_payload",
                message = error.message ?: "Invalid request payload"
            )
        }

        // Gate 3: Build domain command
        val requestId = if (normalizedReq.idempotencyKey.isNotBlank()) {
            "req-${normalizedReq.idempotencyKey}"
        } else {
            "chatcmpl-${UUID.randomUUID()}"
        }

        val command = ChatCommand(
            prompt = normalizedReq.prompt,
            temperature = normalizedReq.temperature,
            maxTokens = normalizedReq.maxTokens,
            requestId = requestId
        )

        // Gate 4: Bridge domain callback events into SSE stream
        val eventBridge = LocalApiEventBridge(resumeAfterSequence = resumeAfterSequence)
        val eventChannel = Channel<SseFrame>(Channel.BUFFERED)

        val identity = chatFacade.dispatchChat(command) { event ->
            val frame = eventBridge.mapEventToSse(event)
            if (frame != null) {
                eventChannel.trySend(frame)
            }
            if (eventBridge.isTerminalEmitted) {
                eventChannel.close()
            }
        }

        val sseFlow: Flow<SseFrame> = flow {
            try {
                for (frame in eventChannel) {
                    emit(frame)
                }
            } finally {
                // Cooperative cancel if the client disconnected prematurely
                if (chatFacade.isChatActive(identity.requestId)) {
                    chatFacade.cancelChat(identity.requestId, "Client connection closed")
                }
            }
        }

        return LocalApiResponse.SseStream(
            requestId = requestId,
            frames = sseFlow
        )
    }

    /**
     * Handles an incoming Image Generation HTTP POST request.
     */
    fun handleImageGeneration(
        bodyJson: String,
        authHeader: String?,
        targetOutputPath: String
    ): LocalApiResponse {
        val authResult = LocalApiRequestMapper.validateAuth(authHeader, expectedApiKey)
        if (authResult is AuthValidationResult.Failure) {
            return LocalApiResponse.Error(
                httpStatus = authResult.httpStatus,
                errorCode = authResult.errorCode,
                message = authResult.message
            )
        }

        val json = runCatching { JSONObject(bodyJson) }.getOrElse {
            return LocalApiResponse.Error(
                httpStatus = 400,
                errorCode = "invalid_json",
                message = "Malformed JSON payload"
            )
        }

        val prompt = json.optString("prompt", "").trim()
        if (prompt.isBlank()) {
            return LocalApiResponse.Error(
                httpStatus = 400,
                errorCode = "missing_prompt",
                message = "Prompt cannot be blank"
            )
        }

        val requestId = "img-${UUID.randomUUID()}"
        val command = ImageCommand(
            prompt = prompt,
            negativePrompt = json.optString("negative_prompt", ""),
            width = json.optInt("width", 512).coerceIn(128, 2048),
            height = json.optInt("height", 512).coerceIn(128, 2048),
            steps = json.optInt("steps", 20).coerceIn(1, 100),
            cfgScale = json.optDouble("cfg_scale", 7.0).toFloat().coerceIn(1.0f, 30.0f),
            seed = json.optLong("seed", -1L),
            targetOutputPath = targetOutputPath,
            requestId = requestId
        )

        val bridge = LocalApiEventBridge()
        val eventChannel = Channel<SseFrame>(Channel.BUFFERED)

        val identity = imageFacade.dispatchImage(command) { event ->
            val frame = bridge.mapEventToSse(event)
            if (frame != null) {
                eventChannel.trySend(frame)
            }
            if (bridge.isTerminalEmitted) {
                eventChannel.close()
            }
        }

        val sseFlow: Flow<SseFrame> = flow {
            try {
                for (frame in eventChannel) {
                    emit(frame)
                }
            } finally {
                if (imageFacade.isImageActive(identity.requestId)) {
                    imageFacade.cancelImage(identity.requestId, "Client connection closed")
                }
            }
        }

        return LocalApiResponse.SseStream(
            requestId = requestId,
            frames = sseFlow
        )
    }

    /**
     * Cooperative cancellation request.
     */
    fun cancelActiveRequest(requestId: String, reason: String = "Client requested abort"): Boolean {
        return chatFacade.cancelChat(requestId, reason) || imageFacade.cancelImage(requestId, reason)
    }
}
