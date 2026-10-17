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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit outcome of one Local API SSE stream. A bounded stream that could not deliver every frame
 * must report *why* it ended instead of ending as if the terminal had been reached.
 */
sealed interface SseStreamOutcome {
    val requestId: String

    data class Completed(
        override val requestId: String,
        val terminalEvent: String
    ) : SseStreamOutcome

    data class Overflowed(
        override val requestId: String,
        val droppedSequence: Long,
        val droppedTerminal: Boolean,
        val deliveredFrames: Int
    ) : SseStreamOutcome

    data class Disconnected(override val requestId: String) : SseStreamOutcome
}

/**
 * Raised to the SSE collector when the bounded frame buffer refused a frame. The message states
 * whether the refused frame was the terminal, because a dropped terminal must never be silent.
 */
class SseStreamOverflowException(
    override val message: String,
    val requestId: String,
    val droppedSequence: Long,
    val droppedTerminal: Boolean
) : IllegalStateException(message)

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
    private val expectedApiKey: String? = null,
    private val sseBufferCapacity: Int = DEFAULT_SSE_BUFFER_CAPACITY
) {
    private val sseOutcomes = ConcurrentHashMap<String, SseStreamOutcome>()

    /**
     * Explicit stream outcome for a request, or null while the stream is still open. Distinguishes
     * "delivered the terminal" from "the bounded buffer overflowed" and "the client disconnected",
     * none of which may be inferred from a silently ended flow.
     */
    fun sseOutcome(requestId: String): SseStreamOutcome? = sseOutcomes[requestId]

    companion object {
        /** Bounded per-stream frame buffer: frames beyond this are refused, never silently dropped. */
        const val DEFAULT_SSE_BUFFER_CAPACITY = 64

        private const val SSE_BACKPRESSURE_REASON = "SSE stream backpressure overflow"
    }

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

        // Gate 4: Bridge domain callback events into a bounded SSE stream. A frame that the buffer
        // refuses is never dropped silently: it closes the stream with an explicit, queryable
        // overflow outcome (and settles the request) instead of ending as if it were complete.
        val eventBridge = LocalApiEventBridge(resumeAfterSequence = resumeAfterSequence)
        val dispatchedIdentity = AtomicReference<RequestIdentity?>(null)
        val stream = SseFrameStream(
            requestId = requestId,
            capacity = sseBufferCapacity,
            onOverflow = { failure ->
                sseOutcomes[requestId] = failure.outcome
                // Settlement is identity-carrying; the identity is published as soon as dispatch
                // returns, and the post-dispatch check below closes the (unreachable) window in
                // which an overflow could be observed earlier.
                dispatchedIdentity.get()?.let { dispatched ->
                    chatFacade.settleStreamFailure(dispatched, SSE_BACKPRESSURE_REASON)
                }
            }
        )

        val identity = chatFacade.dispatchChat(command) { event ->
            val frame = eventBridge.mapEventToSse(event)
            if (frame != null) {
                stream.publish(frame, event.sequence, event.isTerminal)
            }
        }
        dispatchedIdentity.set(identity)
        stream.awaitFailure()?.let { chatFacade.settleStreamFailure(identity, SSE_BACKPRESSURE_REASON) }

        val sseFlow: Flow<SseFrame> = flow {
            var completed = false
            try {
                for (frame in stream.channel) {
                    emit(frame)
                }
                stream.awaitFailure()?.let { throw it.toException() }
                sseOutcomes[requestId] = SseStreamOutcome.Completed(
                    requestId = requestId,
                    terminalEvent = stream.terminalEvent
                )
                completed = true
            } finally {
                // A collector that left before the terminal arrived is a disconnect, and the request
                // must be cancelled cooperatively with an outcome that says so. The cancel is
                // attempt-checked: this stream may only cancel the exact attempt it dispatched.
                if (!completed && chatFacade.isChatActive(identity.requestId)) {
                    sseOutcomes[requestId] = SseStreamOutcome.Disconnected(identity.requestId)
                    chatFacade.cancelChat(identity, "Client connection closed")
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
        val dispatchedIdentity = AtomicReference<RequestIdentity?>(null)
        val stream = SseFrameStream(
            requestId = requestId,
            capacity = sseBufferCapacity,
            onOverflow = { failure ->
                sseOutcomes[requestId] = failure.outcome
                dispatchedIdentity.get()?.let { dispatched ->
                    imageFacade.settleStreamFailure(dispatched, SSE_BACKPRESSURE_REASON)
                }
            }
        )

        val identity = imageFacade.dispatchImage(command) { event ->
            val frame = bridge.mapEventToSse(event)
            if (frame != null) {
                stream.publish(frame, event.sequence, event.isTerminal)
            }
        }
        dispatchedIdentity.set(identity)
        stream.awaitFailure()?.let { imageFacade.settleStreamFailure(identity, SSE_BACKPRESSURE_REASON) }

        val sseFlow: Flow<SseFrame> = flow {
            var completed = false
            try {
                for (frame in stream.channel) {
                    emit(frame)
                }
                stream.awaitFailure()?.let { throw it.toException() }
                sseOutcomes[requestId] = SseStreamOutcome.Completed(
                    requestId = requestId,
                    terminalEvent = stream.terminalEvent
                )
                completed = true
            } finally {
                // Attempt-checked cancel: this stream may only cancel the exact attempt it dispatched.
                if (!completed && imageFacade.isImageActive(identity.requestId)) {
                    sseOutcomes[requestId] = SseStreamOutcome.Disconnected(identity.requestId)
                    imageFacade.cancelImage(identity, "Client connection closed")
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

    /**
     * Bounded frame buffer for one SSE stream.
     *
     * The buffer has a hard capacity: a frame that does not fit is *refused*, the stream is closed
     * with an explicit overflow failure (including whether the refused frame was the terminal), and
     * the request owner is told to settle it. Nothing is dropped without a record, and the terminal
     * can never disappear silently.
     */
    private class SseFrameStream(
        private val requestId: String,
        capacity: Int,
        private val onOverflow: (SseOverflow) -> Unit
    ) {
        /** One reserved slot is always left for the terminal, so a full buffer cannot hide it. */
        val channel = Channel<SseFrame>(capacity.coerceAtLeast(2))
        private val failure = AtomicReference<SseOverflow?>(null)

        @Volatile private var closed = false

        @Volatile var terminalEvent: String = ""

            private set

        private var deliveredFrames = 0

        fun publish(frame: SseFrame, sequence: Long, isTerminal: Boolean) {
            if (closed) return
            val result = channel.trySend(frame)
            if (result.isSuccess) {
                deliveredFrames++
                if (isTerminal) {
                    terminalEvent = frame.event.orEmpty()
                    closed = true
                    channel.close()
                }
                return
            }
            // Bounded backpressure: refuse rather than silently drop, and report exactly what was
            // refused. The channel is closed so the collector drains what was accepted and then
            // observes the explicit failure.
            val overflow = SseOverflow(
                requestId = requestId,
                droppedSequence = sequence,
                droppedTerminal = isTerminal,
                deliveredFrames = deliveredFrames
            )
            if (failure.compareAndSet(null, overflow)) {
                closed = true
                channel.close()
                onOverflow(overflow)
            }
        }

        fun awaitFailure(): SseOverflow? = failure.get()
    }

    private data class SseOverflow(
        val requestId: String,
        val droppedSequence: Long,
        val droppedTerminal: Boolean,
        val deliveredFrames: Int
    ) {
        val outcome: SseStreamOutcome
            get() = SseStreamOutcome.Overflowed(
                requestId = requestId,
                droppedSequence = droppedSequence,
                droppedTerminal = droppedTerminal,
                deliveredFrames = deliveredFrames
            )

        fun toException(): SseStreamOverflowException = SseStreamOverflowException(
            message = "Local API SSE buffer overflow for $requestId at sequence $droppedSequence" +
                if (droppedTerminal) " (the terminal frame was refused)." else ".",
            requestId = requestId,
            droppedSequence = droppedSequence,
            droppedTerminal = droppedTerminal
        )
    }
}
