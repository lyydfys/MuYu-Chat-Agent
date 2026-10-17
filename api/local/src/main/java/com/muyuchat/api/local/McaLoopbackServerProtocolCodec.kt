package com.muyuchat.api.local

import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.localContextWindowBudget
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket

    internal fun McaLoopbackServer.prepareGenerationRequest(
        route: String,
        body: String,
        streaming: Boolean
    ): PreparedGenerationRequest {
        val parsed = runCatching {
            OpenAiApiCompat.parseChatRequestChecked(
                body = body,
                baseParams = LocalApiRuntime.generationParamsProvider(),
                // OpenAI-compatible generation routes are strict JSON APIs.
                // Keep the historical /completion text compatibility route
                // permissive for existing local clients, while the standard
                // chat/completions and completions endpoints reject malformed
                // bodies and missing model ids before touching the engine.
                requireJsonObject = route != "/completion",
                requireModel = route != "/completion",
                requireNonEmptyMessages = route == "/v1/chat/completions" || route == "/chat/completions"
            )
        }.getOrElse { error ->
            return PreparedGenerationRequest.Rejected(
                httpStatus = 500,
                code = "preflight_failed",
                message = error.message ?: "Local API request preflight failed."
            )
        }
        val request = when (parsed) {
            is OpenAiChatParseResult.Success -> parsed.request
            is OpenAiChatParseResult.Rejected -> {
                return PreparedGenerationRequest.Rejected(
                    httpStatus = parsed.rejection.httpStatus,
                    code = parsed.rejection.code,
                    message = parsed.rejection.message,
                    detailsJson = parsed.rejection.detailsJson
                )
            }
        }
        request.localApiVisionRejection()?.let { rejection ->
            return PreparedGenerationRequest.Rejected(
                httpStatus = 409,
                code = rejection.code,
                message = rejection.message
            )
        }
        request.contextLengthRejection()?.let { return it }
        return admitGenerationRequest(
            route = route,
            streaming = streaming,
            requestedModel = OpenAiApiCompat.requestedModel(body),
            request = request
        )
    }

    internal fun McaLoopbackServer.prepareResponsesRequest(
        body: String,
        streaming: Boolean
    ): PreparedResponsesRequest {
        val parsed = LocalResponsesCompat.parseRequest(
            body = body,
            baseParams = LocalApiRuntime.generationParamsProvider(),
            streaming = streaming
        )
        if (parsed is LocalResponsesParseResult.Rejected) {
            return PreparedResponsesRequest.Rejected(parsed.rejection)
        }
        val request = (parsed as LocalResponsesParseResult.Success).request
        request.chatRequest.localApiVisionRejection()?.let { rejection ->
            return PreparedResponsesRequest.Rejected(
                OpenAiRequestRejection(
                    code = rejection.code,
                    message = rejection.message,
                    httpStatus = 409
                )
            )
        }
        request.chatRequest.contextLengthRejection()?.let { rejection ->
            return PreparedResponsesRequest.Rejected(
                OpenAiRequestRejection(
                    code = rejection.code,
                    message = rejection.message,
                    detailsJson = rejection.detailsJson,
                    httpStatus = rejection.httpStatus
                )
            )
        }
        return when (
            val admitted = admitGenerationRequest(
                route = "/v1/responses",
                streaming = streaming,
                requestedModel = request.model,
                request = request.chatRequest
            )
        ) {
            is PreparedGenerationRequest.Ready -> PreparedResponsesRequest.Ready(request, admitted.lease)
            is PreparedGenerationRequest.Rejected -> PreparedResponsesRequest.Rejected(
                OpenAiRequestRejection(
                    code = admitted.code,
                    message = admitted.message,
                    detailsJson = admitted.detailsJson,
                    httpStatus = admitted.httpStatus
                )
            )
        }
    }

    private fun McaLoopbackServer.admitGenerationRequest(
        route: String,
        streaming: Boolean,
        requestedModel: String?,
        request: ChatRequest
    ): PreparedGenerationRequest {
        val preflightRequest = LocalApiPreflightRequest(
            route = route,
            streaming = streaming,
            requestedModel = requestedModel,
            chatRequest = request
        )
        return synchronized(generationLeaseLock) {
            if (!generationAdmissionOpen) {
                return@synchronized PreparedGenerationRequest.Rejected(
                    httpStatus = 503,
                    code = "server_stopping",
                    message = "The Local API server is stopping and is not accepting generation requests."
                )
            }
            if (activeGenerationLease != null) {
                return@synchronized PreparedGenerationRequest.Rejected(
                    httpStatus = 409,
                    code = "generation_in_progress",
                    message = "Another Local API generation request is already active.",
                    retryAfterMs = 1_000L
                )
            }
            val coordinatorResult = runCatching {
                LocalApiRuntime.preflight(preflightRequest)
            }.getOrElse { error ->
                return@synchronized PreparedGenerationRequest.Rejected(
                    httpStatus = 503,
                    code = "coordinator_preflight_failed",
                    message = error.message ?: "The runtime coordinator could not complete preflight."
                )
            }
            when (coordinatorResult) {
                LocalApiPreflightResult.Ready -> {
                    val lease = GenerationLease()
                    activeGenerationLease = lease
                    PreparedGenerationRequest.Ready(request, lease)
                }
                is LocalApiPreflightResult.Rejected -> {
                    val contextExceeded = coordinatorResult.isContextLengthExceeded()
                    PreparedGenerationRequest.Rejected(
                        httpStatus = if (contextExceeded) 413 else coordinatorResult.httpStatus.coerceIn(400, 599),
                        code = if (contextExceeded) CONTEXT_LENGTH_EXCEEDED_CODE else coordinatorResult.code,
                        message = coordinatorResult.message,
                        retryAfterMs = coordinatorResult.retryAfterMs,
                        detailsJson = coordinatorResult.detailsJson
                    )
                }
            }
        }
    }

    internal fun McaLoopbackServer.releaseGenerationLease(lease: GenerationLease) {
        val requestIdToStop = synchronized(generationLeaseLock) {
            if (activeGenerationLease !== lease) {
                return
            }
            // A cancelled request can leave the native runner generating for a short period
            // after the HTTP coroutine has unwound. Keep the lease until the exact request has
            // been stopped; otherwise the next client observes generation_in_progress.
            val cancelled = lease.job?.isCancelled == true
            if (!cancelled || lease.requestId.isBlank()) {
                activeGenerationLease = null
                return
            }
            lease.requestId
        }
        scope.launch {
            runCatching { LocalApiRuntime.stopGenerationIfRequestActive(requestIdToStop) }
            synchronized(generationLeaseLock) {
                if (activeGenerationLease === lease) {
                    activeGenerationLease = null
                }
            }
        }
    }

    private fun ChatRequest.contextLengthRejection(): PreparedGenerationRequest.Rejected? {
        val contextLength = params.nCtx.takeIf { it > 0 } ?: return null
        val budget = localContextWindowBudget(contextLength)
        val promptBudget = budget.promptBudgetTokens
        val systemTokens = messages.asSequence()
            .filter { it.role == Role.SYSTEM }
            .sumOf { estimateLocalApiTokens(it.content) } +
            estimateLocalApiTokens(params.systemPrompt) +
            estimateLocalApiTokens(runtimeSystemContext) +
            REASONING_INSTRUCTION_ESTIMATE_TOKENS
        val latestTurn = messages.lastOrNull { it.role != Role.SYSTEM }
        val latestTurnTokens = latestTurn?.let { estimateLocalApiTokens(it.content) } ?: 0L
        val requiredPromptTokens = systemTokens + latestTurnTokens
        if (
            promptBudget >= budget.minimumPromptBudgetTokens &&
            requiredPromptTokens <= promptBudget.toLong()
        ) {
            return null
        }
        val details = JSONObject()
            .put("n_ctx", contextLength)
            .put("context_length", contextLength)
            .put("max_context_length", contextLength)
            .put("estimated_prompt_tokens", requiredPromptTokens)
            .put("prompt_token_budget", promptBudget.coerceAtLeast(0))
        return PreparedGenerationRequest.Rejected(
            httpStatus = 413,
            code = CONTEXT_LENGTH_EXCEEDED_CODE,
            message = "The request exceeds the active local model context length. Shorten the latest input or reload the model with a larger n_ctx.",
            detailsJson = details.toString()
        )
    }

    /** Mirrors the engine's conservative fallback estimate before a tokenizer is available. */
    private fun estimateLocalApiTokens(text: String): Long {
        if (text.isEmpty()) return 1L
        var denseTokens = 0L
        var sparseCharacters = 0L
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when {
                codePoint in 0x3400..0x9FFF ||
                    codePoint in 0xF900..0xFAFF ||
                    codePoint in 0x3040..0x30FF ||
                    codePoint in 0xAC00..0xD7AF -> denseTokens += 1L
                Character.isSupplementaryCodePoint(codePoint) -> denseTokens += 2L
                else -> sparseCharacters += 1L
            }
            index += Character.charCount(codePoint)
        }
        return (denseTokens + (sparseCharacters + 2L) / 3L).coerceAtLeast(1L)
    }

    internal fun McaLoopbackServer.writePreflightRejection(socket: Socket, rejection: PreparedGenerationRequest.Rejected) {
        writeError(
            socket = socket,
            status = rejection.httpStatus.toHttpStatus(),
            code = rejection.code,
            message = rejection.message,
            detailsJson = rejection.detailsJson,
            retryAfterMs = rejection.retryAfterMs
        )
    }

    internal fun McaLoopbackServer.writePreflightRejection(socket: Socket, rejection: OpenAiRequestRejection) {
        writeError(
            socket = socket,
            status = rejection.httpStatus.toHttpStatus(),
            code = rejection.code,
            message = rejection.message,
            detailsJson = rejection.detailsJson
        )
    }

    internal fun Int.toHttpStatus(): String = when (this) {
        200 -> "200 OK"
        201 -> "201 Created"
        202 -> "202 Accepted"
        204 -> "204 No Content"
        400 -> "400 Bad Request"
        401 -> "401 Unauthorized"
        403 -> "403 Forbidden"
        404 -> "404 Not Found"
        408 -> "408 Request Timeout"
        409 -> "409 Conflict"
        413 -> "413 Payload Too Large"
        422 -> "422 Unprocessable Entity"
        429 -> "429 Too Many Requests"
        501 -> "501 Not Implemented"
        500 -> "500 Internal Server Error"
        502 -> "502 Bad Gateway"
        503 -> "503 Service Unavailable"
        504 -> "504 Gateway Timeout"
        else -> if (this in 200..299) "$this Success" else "$this Error"
    }

    internal fun localApiRuntimeStateJson(): String {
        val busy = runCatching { LocalApiRuntime.busyState() }.getOrDefault(LocalApiBusyState.IDLE)
        return JSONObject()
            .put("busy", busy.busy)
            .put("code", busy.code)
            .put("message", LocalApiRuntime.publicMessage(busy.message))
            .put("retry_after_ms", busy.retryAfterMs.coerceAtLeast(0L))
            .put("details", LocalApiRuntime.publicJson(busy.detailsJson).toJsonValue())
            .put("trace", LocalApiRuntime.traceJson().toJsonValue())
            .put("profile", LocalApiRuntime.publicProfileJson().toJsonValue())
            .put("tuning", LocalApiRuntime.publicTuningJson().toJsonValue())
            .toString()
    }

    private fun String.toJsonValue(): Any =
        runCatching { JSONObject(this) }.getOrNull()
            ?: runCatching { JSONArray(this) }.getOrNull()
            ?: this

    internal fun String.isStreamingRequest(headers: Map<String, String>): Boolean {
        val accept = headers["accept"].orEmpty()
        return OpenAiApiCompat.isStreamingRequest(this) ||
            STREAM_TRUE_PATTERN.containsMatchIn(this) ||
            accept.contains("text/event-stream", ignoreCase = true)
    }

    internal fun String.toSseJson(
        requestId: String,
        created: Long,
        generationSequence: Long? = null,
        reasoning: Boolean = false
    ): String = JSONObject()
        .put("id", requestId)
        .put("object", "chat.completion.chunk")
        .put("created", created)
        .put("model", currentModelName())
        .put("mca_trace", generationTraceJson(requestId, generationSequence))
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put(
                        "delta",
                        if (reasoning) {
                            JSONObject().put("reasoning_content", this)
                        } else {
                            JSONObject().put("content", this)
                        }
                    )
                    .put("finish_reason", JSONObject.NULL)
            )
        )
        .toString()

    internal fun roleSseJson(requestId: String, created: Long): String = JSONObject()
        .put("id", requestId)
        .put("object", "chat.completion.chunk")
        .put("created", created)
        .put("model", currentModelName())
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("delta", JSONObject().put("role", "assistant"))
                    .put("finish_reason", JSONObject.NULL)
            )
        )
        .toString()

    internal fun finishSseJson(
        requestId: String,
        created: Long,
        generationSequence: Long?
    ): String = JSONObject()
        .put("id", requestId)
        .put("object", "chat.completion.chunk")
        .put("created", created)
        .put("model", currentModelName())
        .put("mca_trace", generationTraceJson(requestId, generationSequence))
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("delta", JSONObject())
                    .put("finish_reason", "stop")
            )
        )
        .toString()

    internal fun generationTraceJson(requestId: String, generationSequence: Long?): JSONObject =
        JSONObject()
            .put("requestId", requestId)
            .apply {
                generationSequence?.let { put("generationSequence", it) }
            }

    internal fun generationErrorDetails(
        error: GenerateEvent.Error,
        requestId: String,
        generationSequence: Long?
    ): JSONObject = generationTraceJson(requestId, generationSequence).apply {
        error.action?.let { put("action", it) }
        if (error.changedFields.isNotEmpty()) {
            put("changedFields", JSONArray(error.changedFields.sorted()))
        }
    }

    internal fun currentModelName(): String =
        runCatching {
            val json = JSONObject(LocalApiRuntime.loadedModelJsonProvider())
            val internalId = json.optString("id").trim()
            val displayName = json.optString("displayName")
                .ifBlank { json.optString("name") }
            localApiPublicModelId(displayName, internalId)
                .ifBlank { "mca-local-model" }
        }.getOrDefault("mca-local-model")

    internal fun tokenOrNull(value: Int?): Any =
        value?.takeIf { it > 0 } ?: JSONObject.NULL

    internal fun generationErrorStatus(error: GenerateEvent.Error): String {
        if (error.isContextLengthExceeded()) return "413 Payload Too Large"
        if (error.code in setOf(
                "model_mismatch",
                "active_profile_drift",
                "model_reload_required",
                "model_reload_required_authorized",
                "execution_override_forbidden",
                "model_behavior_override_forbidden"
            )
        ) {
            return "409 Conflict"
        }
        val message = error.message
        val normalized = message.lowercase()
        return if (
            "请先在模型页加载" in message ||
            "no gguf model is loaded" in normalized ||
            "engine is not attached" in normalized ||
            "no backends are loaded" in normalized
        ) {
            "503 Service Unavailable"
        } else {
            "500 Internal Server Error"
        }
    }

    internal fun generationErrorCode(error: GenerateEvent.Error): String =
        if (error.isContextLengthExceeded()) CONTEXT_LENGTH_EXCEEDED_CODE
        else error.code ?: "generation_failed"

    private fun GenerateEvent.Error.isContextLengthExceeded(): Boolean =
        code.equals(CONTEXT_LENGTH_EXCEEDED_CODE, ignoreCase = true) ||
            message.isContextLengthExceededMessage()

    private fun LocalApiPreflightResult.Rejected.isContextLengthExceeded(): Boolean =
        code.equals(CONTEXT_LENGTH_EXCEEDED_CODE, ignoreCase = true) ||
            message.isContextLengthExceededMessage()

    private fun String.isContextLengthExceededMessage(): Boolean {
        val normalized = lowercase()
        return "上下文预算过小" in this ||
            "超过本机安全上下文预算" in this ||
            "历史上下文过长" in this ||
            "超出上下文" in this ||
            "超过上下文" in this ||
            "context_length_exceeded" in normalized ||
            "context length exceeded" in normalized ||
            "maximum context length" in normalized ||
            ("context window" in normalized && ("exceed" in normalized || "too long" in normalized)) ||
            ("prompt" in normalized && "too long" in normalized) ||
            "too many tokens" in normalized
    }

    internal sealed interface PreparedGenerationRequest {
        data class Ready(
            val request: ChatRequest,
            val lease: GenerationLease
        ) : PreparedGenerationRequest

        data class Rejected(
            val httpStatus: Int,
            val code: String,
            val message: String,
            val retryAfterMs: Long = 0L,
            val detailsJson: String = "{}"
        ) : PreparedGenerationRequest
    }

    internal sealed interface PreparedResponsesRequest {
        data class Ready(
            val request: LocalResponsesRequest,
            val lease: GenerationLease
        ) : PreparedResponsesRequest

        data class Rejected(
            val rejection: OpenAiRequestRejection
        ) : PreparedResponsesRequest
    }

    internal class GenerationLease {
        @Volatile
        var job: Job? = null

        @Volatile
        var requestId: String = ""

        @Volatile
        var clientRequestId: String = ""

        @Volatile
        var stopRequested: Boolean = false
    }

        private const val CONTEXT_LENGTH_EXCEEDED_CODE = "context_length_exceeded"
        private const val REASONING_INSTRUCTION_ESTIMATE_TOKENS = 96L

        private val STREAM_TRUE_PATTERN = Regex(""""stream"\s*:\s*(true|1|"true")""", RegexOption.IGNORE_CASE)
