package com.muyuchat.core.agent.contract

/** Which provider wire produced an [AgentModelTurn]. */
enum class AgentTurnProtocol {
    /** Strict single-envelope text protocol (V1), kept for existing local models. */
    PROMPTED_V1,

    /** Structured prompted protocol with a calls array (V2, not implemented yet). */
    PROMPTED_V2,

    /** Provider-native tool calls (Responses/Anthropic/...); wire not implemented yet. */
    NATIVE
}

/** How the turn ended. Only COMPLETED/TOOL_CALL/ASK_USER are executable turn kinds for V1. */
enum class AgentTurnFinish {
    /** Final assistant text with no tool call. */
    COMPLETED,

    /** One or more complete tool calls; the provider turn is terminal for this round. */
    TOOL_CALL,

    /** The model asked the user a question. */
    ASK_USER,

    /** The provider reported truncation/refusal/error/EOF before a complete terminal proof. */
    TRUNCATED,
    REFUSED,
    ERROR,
    EOF
}

/** One complete tool call in a turn. [callId] is the provider id when the wire carries one. */
data class AgentTurnToolCall(
    val callId: String?,
    val name: String,
    val argumentsJson: String
) {
    init {
        callId?.let { AgentContract.requireId(it, "callId") }
        AgentContract.requireNotBlank(name, "tool call name")
        AgentContract.requireNotBlank(argumentsJson, "tool call arguments JSON")
    }
}

/** An explicit question turn (`ask_user` in V1). */
data class AgentTurnQuestion(
    val question: String,
    val options: List<String> = emptyList()
) {
    init {
        AgentContract.requireNotBlank(question, "question")
        require(options.size <= 8) { "a question carries at most 8 options" }
        require(options.all { it.isNotBlank() }) { "question options must not be blank" }
    }
}

/**
 * Versioned, provider-neutral result of one model round (N05b).
 *
 * The V1 loop currently drives execution from [com.muyuchat.core.agent.runtime.AgentControlMessage];
 * this turn is the contract those messages are derived from. A native adapter can fill the same
 * fields from the wire (call ids/arguments/finish reason/usage/terminal proof) without inventing a
 * second execution path. [artifactIds] is a V1 final-envelope extension; protocols without that
 * concept leave it empty. [terminalProof] is an opaque provider marker ("the provider said the turn
 * completed"); it is never user content.
 */
data class AgentModelTurn(
    val protocol: AgentTurnProtocol,
    val finish: AgentTurnFinish,
    val text: String = "",
    val calls: List<AgentTurnToolCall> = emptyList(),
    val question: AgentTurnQuestion? = null,
    val artifactIds: List<String> = emptyList(),
    val usage: AgentModelUsage? = null,
    val terminalProof: String? = null,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        require(finish != AgentTurnFinish.TOOL_CALL || calls.isNotEmpty()) {
            "a TOOL_CALL turn must carry at least one call"
        }
        require(finish == AgentTurnFinish.TOOL_CALL || calls.isEmpty()) {
            "only a TOOL_CALL turn may carry tool calls"
        }
        require(finish != AgentTurnFinish.ASK_USER || question != null) {
            "an ASK_USER turn must carry a question"
        }
        require(question == null || finish == AgentTurnFinish.ASK_USER) {
            "a question is only valid on an ASK_USER turn"
        }
        require(artifactIds == artifactIds.distinct()) { "artifact ids must be unique" }
        require(artifactIds.size <= 20) { "a turn references at most 20 artifacts" }
    }
}
