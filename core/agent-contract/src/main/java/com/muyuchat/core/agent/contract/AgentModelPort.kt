package com.muyuchat.core.agent.contract

/** Unique, never-reused identity of one model generation attempt. */
data class AgentModelAttemptRef(
    val attemptId: String,
    val runId: String,
    val stepIndex: Int,
    val ordinal: Int
) {
    init {
        AgentContract.requireId(attemptId, "attemptId")
        AgentContract.requireId(runId, "runId")
        require(stepIndex >= 0) { "stepIndex must not be negative" }
        require(ordinal >= 1) { "attempt ordinal must be >= 1" }
    }
}

/** The three roles the local MCA transcript exposes; no TOOL role is added in V1. */
enum class AgentTranscriptRole { SYSTEM, USER, ASSISTANT }

/**
 * What one serialized message represents.
 *
 * [FUNCTION_CALL] and [FUNCTION_OUTPUT] exist for native providers (N05c send side): a native
 * adapter maps them to provider items (`function_call` / `function_call_output`) instead of chat
 * text. A prompted serializer never emits them.
 */
enum class AgentModelMessageKind { TEXT, FUNCTION_CALL, FUNCTION_OUTPUT }

data class AgentModelMessage(
    val messageId: String,
    val role: AgentTranscriptRole,
    val content: String,
    val kind: AgentModelMessageKind = AgentModelMessageKind.TEXT,
    val callId: String? = null,
    val toolName: String? = null
) {
    init {
        AgentContract.requireId(messageId, "messageId")
        when (kind) {
            AgentModelMessageKind.TEXT -> Unit

            AgentModelMessageKind.FUNCTION_CALL -> {
                AgentContract.requireNotBlank(callId.orEmpty(), "function call id")
                AgentContract.requireNotBlank(toolName.orEmpty(), "function tool name")
                AgentContract.requireNotBlank(content, "function arguments")
            }

            AgentModelMessageKind.FUNCTION_OUTPUT -> {
                AgentContract.requireNotBlank(callId.orEmpty(), "function output call id")
                AgentContract.requireNotBlank(content, "function output")
            }
        }
    }
}

/**
 * One fully paired model request. [messages] is the authoritative Agent transcript, already
 * serialized by the runtime; adapters must not append chat history or page state.
 *
 * [tools] is the frozen run catalog surface (N05c). A prompted adapter ignores it; a native adapter
 * must serialize exactly these descriptors and never a tool map of its own. The list is bounded and
 * key-unique so a misassembled catalog fails before any wire call.
 */
data class AgentModelRequest(
    val attempt: AgentModelAttemptRef,
    val binding: AgentModelBinding,
    val systemPrompt: String,
    val messages: List<AgentModelMessage>,
    val maxOutputTokens: Int,
    val samplingJson: String = "{}",
    val callOrdinal: Int = 0,
    val tools: List<AgentToolDescriptor> = emptyList()
) {
    init {
        require(messages.isNotEmpty()) { "Agent model request needs at least one message" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(callOrdinal >= 0) { "callOrdinal must not be negative" }
        require(tools.size <= MAX_TOOLS) { "a request declares at most $MAX_TOOLS tools" }
        require(tools.map { it.toolKey }.distinct().size == tools.size) {
            "a request must not declare duplicate tool keys"
        }
    }

    /**
     * Transcript turns without the serialized SYSTEM echo.
     *
     * Both transcript serializers open with a SYSTEM message whose content is exactly
     * [systemPrompt], because the serialized form is also the hashed/estimated representation.
     * An adapter that sends [systemPrompt] as its own SYSTEM message must call this instead of
     * [messages]; echoing both doubled every request, and on an 8192-token local model it pushed
     * every new Agent task past the prompt budget before a single token was generated.
     */
    val conversationalMessages: List<AgentModelMessage>
        get() {
            val first = messages.first()
            return if (first.role == AgentTranscriptRole.SYSTEM && first.content == systemPrompt) {
                messages.drop(1)
            } else {
                messages
            }
        }

    private companion object {
        const val MAX_TOOLS = 64
    }
}

data class AgentModelUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val estimated: Boolean = true
)

sealed interface AgentModelResult {
    /**
     * Complete visible text of a successfully terminated response, before protocol decoding.
     *
     * [turn] is set by a native adapter that already reconstructed provider calls/terminal state
     * (N05c). When present the loop uses it instead of decoding [visibleText] with the prompted
     * protocol; prompted adapters leave it null.
     */
    data class Success(
        val visibleText: String,
        val usage: AgentModelUsage? = null,
        val turn: AgentModelTurn? = null
    ) : AgentModelResult

    data class Failure(val error: AgentError) : AgentModelResult

    data class Cancelled(val error: AgentError? = null) : AgentModelResult
}

/** UI-only progress. It never carries a control decision and never executes partial arguments. */
sealed interface AgentModelProgress {
    data class Phase(val label: String) : AgentModelProgress
    data class TextDelta(val text: String) : AgentModelProgress
    data class ResourceWait(val waitedMillis: Long) : AgentModelProgress
}

fun interface AgentModelProgressSink {
    fun onProgress(progress: AgentModelProgress)
}

data class AgentStopDispatch(
    val accepted: Boolean,
    val detail: String? = null
)

/**
 * The only model surface the Agent runtime sees. The app adapter owns the real engine/facade
 * identity, lease and stop proof; the runtime never holds a native handle.
 */
interface AgentModelPort {
    suspend fun infer(
        request: AgentModelRequest,
        progress: AgentModelProgressSink
    ): AgentModelResult

    /** Stops exactly this attempt. A missing/stale attempt must return accepted=false. */
    suspend fun requestStop(attempt: AgentModelAttemptRef): AgentStopDispatch
}
