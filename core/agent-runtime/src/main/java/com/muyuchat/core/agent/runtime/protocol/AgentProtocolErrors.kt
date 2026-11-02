package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec

/**
 * Stable, machine-readable sub-codes of PROMPTED_V1 protocol failures.
 *
 * The run-level error family stays `MODEL_PROTOCOL_ERROR` (architecture 7.5); these codes only
 * describe the exact repair reason inside the bounded repair hint sent back to the model. They are
 * persisted inside [com.muyuchat.core.agent.runtime.AgentTranscriptEntry.ProtocolError], so do not
 * rename them without a migration.
 */
object AgentProtocolErrorCodes {
    // Syntax / framing.
    const val EMPTY = "PROTOCOL_EMPTY"
    const val TOO_LARGE = "PROTOCOL_TOO_LARGE"
    const val INVALID_JSON = "PROTOCOL_INVALID_JSON"
    const val TRUNCATED = "PROTOCOL_TRUNCATED"
    const val DUPLICATE_KEY = "PROTOCOL_DUPLICATE_KEY"
    const val DEPTH_EXCEEDED = "PROTOCOL_DEPTH_EXCEEDED"
    const val MULTIPLE_ROOTS = "PROTOCOL_MULTIPLE_ROOTS"
    const val UTF8_INVALID = "PROTOCOL_UTF8_INVALID"
    const val MARKDOWN_FENCE = "PROTOCOL_MARKDOWN_FENCE"

    // Envelope shape.
    const val NOT_OBJECT = "PROTOCOL_NOT_OBJECT"
    const val MISSING_FIELD = "PROTOCOL_MISSING_FIELD"
    const val UNKNOWN_FIELD = "PROTOCOL_UNKNOWN_FIELD"
    const val WRONG_PROTOCOL = "PROTOCOL_WRONG_PROTOCOL"
    const val FIELD_TYPE = "PROTOCOL_FIELD_TYPE"
    const val UNKNOWN_KIND = "PROTOCOL_UNKNOWN_KIND"

    // Tool message.
    const val TOOL_NOT_FOUND = "TOOL_NOT_FOUND"
    const val TOOL_ARGUMENTS_NOT_OBJECT = "PROTOCOL_TOOL_ARGUMENTS_NOT_OBJECT"

    // Final message.
    const val FINAL_EMPTY = "PROTOCOL_FINAL_EMPTY"
    const val FINAL_TEXT_TOO_LARGE = "PROTOCOL_FINAL_TEXT_TOO_LARGE"
    const val ARTIFACT_LIMIT = "PROTOCOL_ARTIFACT_LIMIT"
    const val ARTIFACT_INVALID = "PROTOCOL_ARTIFACT_INVALID"
    const val FINAL_ARTIFACT_UNKNOWN = "PROTOCOL_FINAL_ARTIFACT_UNKNOWN"

    // Ask-user message.
    const val QUESTION_EMPTY = "PROTOCOL_QUESTION_EMPTY"
    const val QUESTION_TOO_LARGE = "PROTOCOL_QUESTION_TOO_LARGE"
    const val OPTIONS_INVALID = "PROTOCOL_OPTIONS_INVALID"

    // Turn-level execution mapping (N05b).
    const val TURN_BATCH_UNSUPPORTED = "PROTOCOL_TURN_BATCH_UNSUPPORTED"
    const val TURN_NOT_EXECUTABLE = "PROTOCOL_TURN_NOT_EXECUTABLE"

    // Transcript v2 records (N05d).
    const val TRANSCRIPT_V2_UNKNOWN_RECORD = "TRANSCRIPT_V2_UNKNOWN_RECORD"
    const val TRANSCRIPT_V2_UNKNOWN_KIND = "TRANSCRIPT_V2_UNKNOWN_KIND"
    const val TRANSCRIPT_V2_MISSING_FIELD = "TRANSCRIPT_V2_MISSING_FIELD"
    const val TRANSCRIPT_V2_UNKNOWN_FIELD = "TRANSCRIPT_V2_UNKNOWN_FIELD"
    const val TRANSCRIPT_V2_FIELD_TYPE = "TRANSCRIPT_V2_FIELD_TYPE"
    const val TRANSCRIPT_V2_CALL_ID_INVALID = "TRANSCRIPT_V2_CALL_ID_INVALID"
    const val TRANSCRIPT_V2_ARGUMENTS_NOT_OBJECT = "TRANSCRIPT_V2_ARGUMENTS_NOT_OBJECT"
    const val TRANSCRIPT_V2_VERSION_UNSUPPORTED = "TRANSCRIPT_V2_VERSION_UNSUPPORTED"
    const val TRANSCRIPT_V2_TOO_LARGE = "TRANSCRIPT_V2_TOO_LARGE"

    /** A readable V1 prompted record that carries no provider call id, so it cannot be upcast. */
    const val TRANSCRIPT_V2_V1_NON_NATIVE = "TRANSCRIPT_V2_V1_NON_NATIVE"

    // Schema / arguments (owned by the validator, reused by the loop).
    const val SCHEMA_UNSUPPORTED = "PROTOCOL_SCHEMA_UNSUPPORTED"
    const val ARGUMENTS_INVALID = "PROTOCOL_ARGUMENTS_INVALID"
}

/**
 * Builds the bounded, machine-readable repair hint attached to a rejected control response.
 *
 * The hint is itself a strict JSON object produced through the codec (never string interpolation),
 * contains no raw model input and stays well below the 4 KiB event/repair budget.
 */
object AgentRepairHints {
    const val MAX_HINT_BYTES: Int = 4 * 1024
    private const val MAX_MESSAGE_CHARS: Int = 512

    fun build(
        codec: StrictJsonCodec,
        errorCode: String,
        safeMessage: String,
        availableToolKeys: Set<String>
    ): String {
        val boundedMessage = safeMessage
            .take(MAX_MESSAGE_CHARS)
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .trim()
            .ifEmpty { "The response did not match the required protocol." }
        val hint = AgentJsonValue.Obj(
            listOf(
                "protocol" to AgentJsonValue.Str(AgentContract.PROTOCOL_NAME),
                "errorCode" to AgentJsonValue.Str(errorCode),
                "message" to AgentJsonValue.Str(boundedMessage),
                "expected" to AgentJsonValue.Str(
                    "Reply with exactly one complete JSON object. No Markdown fence, " +
                        "no prose before or after, no second root, no duplicate keys."
                ),
                "availableTools" to AgentJsonValue.Arr(
                    availableToolKeys.sorted().map { AgentJsonValue.Str(it) }
                ),
                "responseShapes" to AgentJsonValue.Obj(
                    listOf(
                        "tool" to AgentJsonValue.Obj(
                            listOf(
                                "protocol" to AgentJsonValue.Str(AgentContract.PROTOCOL_NAME),
                                "kind" to AgentJsonValue.Str("tool"),
                                "call" to AgentJsonValue.Obj(
                                    listOf(
                                        "name" to AgentJsonValue.Str("<one availableTool>"),
                                        "arguments" to AgentJsonValue.Obj(emptyList())
                                    )
                                )
                            )
                        ),
                        "final" to AgentJsonValue.Obj(
                            listOf(
                                "protocol" to AgentJsonValue.Str(AgentContract.PROTOCOL_NAME),
                                "kind" to AgentJsonValue.Str("final"),
                                "text" to AgentJsonValue.Str("<final answer>"),
                                "artifactIds" to AgentJsonValue.Arr(emptyList())
                            )
                        ),
                        "ask_user" to AgentJsonValue.Obj(
                            listOf(
                                "protocol" to AgentJsonValue.Str(AgentContract.PROTOCOL_NAME),
                                "kind" to AgentJsonValue.Str("ask_user"),
                                "question" to AgentJsonValue.Str("<one clarifying question>"),
                                "options" to AgentJsonValue.Arr(emptyList())
                            )
                        )
                    )
                )
            )
        )
        val encoded = codec.write(hint)
        return if (encoded.toByteArray(Charsets.UTF_8).size <= MAX_HINT_BYTES) {
            encoded
        } else {
            // The tool list is the only unbounded part; keep a valid bounded hint without it.
            codec.write(
                AgentJsonValue.Obj(
                    listOf(
                        "protocol" to AgentJsonValue.Str(AgentContract.PROTOCOL_NAME),
                        "errorCode" to AgentJsonValue.Str(errorCode),
                        "message" to AgentJsonValue.Str(boundedMessage)
                    )
                )
            )
        }
    }
}
