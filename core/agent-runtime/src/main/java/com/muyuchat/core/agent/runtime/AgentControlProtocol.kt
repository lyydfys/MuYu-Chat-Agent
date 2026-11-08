package com.muyuchat.core.agent.runtime

import com.muyuchat.core.agent.contract.AgentModelMessage
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentModelTurn
import com.muyuchat.core.agent.contract.AgentToolDescriptor

/**
 * Frozen interfaces between the strict protocol implementation (K02) and the deterministic loop
 * (K03). Both sides compile against these types; neither duplicates the other's rules.
 *
 * Only this file is the shared seam. The decoder/validator/serializer implementations live in the
 * protocol package and are injected into the loop, never constructed inside it.
 */

/** The only three executable control messages of PROMPTED_V1. */
sealed interface AgentControlMessage {
    data class ToolCall(
        val name: String,
        val argumentsJson: String
    ) : AgentControlMessage

    data class Final(
        val text: String,
        val artifactIds: List<String>
    ) : AgentControlMessage

    data class AskUser(
        val question: String,
        val options: List<String>
    ) : AgentControlMessage
}

sealed interface AgentDecodeResult {
    data class Accepted(val message: AgentControlMessage) : AgentDecodeResult

    /** [repairHintJson] is machine-readable and safe to send back to the model. */
    data class Rejected(
        val errorCode: String,
        val safeMessage: String,
        val repairHintJson: String
    ) : AgentDecodeResult
}

/**
 * Decodes one complete visible assistant response. Protocol failures never throw for model text:
 * they return [AgentDecodeResult.Rejected] so the run can perform exactly one bounded repair.
 */
interface AgentControlDecoder {
    fun decode(visibleText: String): AgentDecodeResult
}

/** Result of decoding one provider round into a canonical [AgentModelTurn]. */
sealed interface AgentTurnDecodeResult {
    data class Accepted(val turn: AgentModelTurn) : AgentTurnDecodeResult

    /** [repairHintJson] is machine-readable and safe to send back to the model. */
    data class Rejected(
        val errorCode: String,
        val safeMessage: String,
        val repairHintJson: String
    ) : AgentTurnDecodeResult
}

/**
 * Provider-neutral turn decoder (N05b).
 *
 * The V1 adapter wraps [AgentControlDecoder]; native adapters will build the same [AgentModelTurn]
 * from their wire (call ids, finish reason, usage, terminal proof) without touching the loop. A
 * rejected turn is a protocol failure the run may repair exactly once.
 */
interface AgentTurnDecoder {
    fun decode(visibleText: String): AgentTurnDecodeResult
}

/** Canonical JSON AST used to build host observations without string interpolation. */
sealed interface AgentJsonValue {
    data class Obj(val fields: List<Pair<String, AgentJsonValue>>) : AgentJsonValue
    data class Arr(val items: List<AgentJsonValue>) : AgentJsonValue
    data class Str(val value: String) : AgentJsonValue
    /** A pre-validated JSON number literal; callers must not substitute free-form strings. */
    data class Num(val literal: String) : AgentJsonValue
    data class Bool(val value: Boolean) : AgentJsonValue
    data object Null : AgentJsonValue
}

sealed interface AgentJsonParseResult {
    data class Parsed(val value: AgentJsonValue) : AgentJsonParseResult
    data class Failed(val errorCode: String, val message: String) : AgentJsonParseResult
}

/** Strict JSON writer/parser (Jackson Core streaming, no databind, no lenient fallback). */
interface StrictJsonCodec {
    fun write(value: AgentJsonValue): String

    fun parse(text: String): AgentJsonParseResult
}

sealed interface AgentSchemaValidation {
    data class Supported(
        val canonicalSchemaJson: String,
        val schemaHash: String
    ) : AgentSchemaValidation

    data class Unsupported(
        val errorCode: String,
        val safeMessage: String
    ) : AgentSchemaValidation
}

sealed interface AgentArgumentValidation {
    data class Valid(
        val normalizedArgumentsJson: String,
        val argumentsHash: String
    ) : AgentArgumentValidation

    data class Invalid(
        val errorCode: String,
        val safeMessage: String
    ) : AgentArgumentValidation
}

/**
 * Validates the supported JSON Schema subset and tool arguments. Unsupported schema keywords are
 * reported (never silently ignored); unsupported remote tools stay disabled.
 */
interface AgentToolSchemaValidator {
    fun validateToolSchema(schemaJson: String): AgentSchemaValidation

    fun validateArguments(
        descriptor: AgentToolDescriptor,
        argumentsJson: String
    ): AgentArgumentValidation
}

/** One entry of the authoritative transcript, in exact conversation order. */
sealed interface AgentTranscriptEntry {
    /**
     * The exact accepted assistant envelope text; never replaced by a tool-success description.
     *
     * [providerCallId] is set only for a native provider call (N05c): the serializer then emits a
     * provider `function_call` item instead of the declarative envelope text, and the paired
     * [ToolResult] carries the same id for `function_call_output`.
     */
    data class AssistantEnvelope(
        val rawEnvelope: String,
        val providerCallId: String? = null
    ) : AgentTranscriptEntry

    data class ToolResult(
        val invocationId: String,
        val callHash: String,
        val toolKey: String,
        val result: AgentJsonValue,
        val providerCallId: String? = null
    ) : AgentTranscriptEntry

    data class UserAnswer(
        val questionId: String,
        val text: String
    ) : AgentTranscriptEntry

    data class ProtocolError(
        val errorCode: String,
        val schemaHintJson: String
    ) : AgentTranscriptEntry

    /**
     * Text the user typed **while the run was in flight**, delivered at a step boundary.
     *
     * It is a first-class transcript entry, not a tool observation: it survives a park/resume, it is
     * validated by the pairing rules, and every serializer renders it as one user-role record. It is
     * drained from [com.muyuchat.core.agent.runtime.run.AgentInterjectionQueue] exactly once per step.
     */
    data class UserInterjection(
        val text: String,
        val receivedAtMillis: Long
    ) : AgentTranscriptEntry
}

data class AgentPromptedTranscriptContext(
    val systemPrompt: String,
    val userGoal: String,
    val entries: List<AgentTranscriptEntry>
)

data class AgentTranscriptSerialization(
    val messages: List<AgentModelMessage>
)

/**
 * Maps the authoritative transcript to the three local roles exactly per architecture 6.5.1.
 * tools/toolExchanges stay empty for PROMPTED_V1; this serializer is the only mapping.
 */
interface AgentTranscriptSerializer {
    fun serialize(context: AgentPromptedTranscriptContext): AgentTranscriptSerialization
}

/** Builds the single explicit SYSTEM prompt; version/hash are recorded by the run. */
interface AgentSystemPromptBuilder {
    /**
     * [operationKnowledge] is the already-resolved fragment from
     * [com.muyuchat.core.agent.contract.AgentKnowledgePort] (empty when the feature is unwired or
     * nothing matched). [userPreferences] is the already-resolved fragment from
     * [com.muyuchat.core.agent.contract.AgentPreferencePort]. Both default to empty so every existing
     * caller — and every stored prompt hash — keeps producing the identical string.
     */
    fun build(
        tools: List<AgentToolDescriptor>,
        policyVersion: Long,
        budgetSummaryJson: String,
        protocolMode: AgentProtocolMode = AgentProtocolMode.PROMPTED_V1,
        operationKnowledge: String = "",
        userPreferences: String = ""
    ): String
}
