package com.muyuchat.core.dsh.contract

/**
 * Protocol-neutral request handed by the internal DSH model gateway to the MCA inference
 * adapter. The gateway owns HTTP/SSE and OpenAI field fidelity; the adapter owns the mapping
 * to MCA's model execution owner. The gateway never executes DSH tools itself.
 */
data class DshModelMessage(
    val role: DshModelRole,
    /** Plain text content; null is legal for an assistant message that only carries tool calls. */
    val content: String?,
    val toolCallId: String? = null,
    val toolCalls: List<DshModelToolCall> = emptyList()
)

enum class DshModelRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

data class DshModelToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class DshModelToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String
)

enum class DshToolChoice {
    AUTO,
    NONE,
    REQUIRED
}

data class DshModelRequest(
    /** Stable gateway call identity `dsh:<runtimeEpoch>:<requestOrdinal>`. */
    val callId: String,
    val modelId: String,
    val messages: List<DshModelMessage>,
    val tools: List<DshModelToolDefinition> = emptyList(),
    val toolChoice: DshToolChoice = DshToolChoice.AUTO,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val stream: Boolean = true
)

/** One normalized streaming event produced by the MCA inference adapter. */
sealed interface DshModelEvent {
    data class TextDelta(val text: String) : DshModelEvent
    data class ReasoningDelta(val text: String) : DshModelEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsFragment: String?
    ) : DshModelEvent

    /** Terminal event; exactly one must end every stream. */
    data class Completed(
        val finishReason: DshFinishReason,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null
    ) : DshModelEvent

    data class Failed(
        val code: String,
        val message: String,
        val retryable: Boolean = false
    ) : DshModelEvent
}

enum class DshFinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CANCELLED,
    ERROR
}

/** Capability description exposed through the gateway's `/v1/models`. */
data class DshGatewayModel(
    val id: String,
    val displayName: String,
    val contextWindow: Int?,
    val maxOutputTokens: Int?,
    val supportsTools: Boolean,
    /** True only for a native tool/template path; false for the labelled prompt-compat mode. */
    val nativeToolCalling: Boolean,
    val toolProtocolLabel: String? = null
)
