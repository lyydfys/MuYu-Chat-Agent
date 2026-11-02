package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec

/**
 * Canonical, reversible transcript records (transcript schema v2, N05d).
 *
 * A native/PROMPTED_V2 tool exchange has two records:
 *
 * | record                       | wire meaning                                        |
 * |------------------------------|-----------------------------------------------------|
 * | `assistant_function_call`    | one complete provider call (id, tool, arguments)     |
 * | `user_function_call_output`  | the host result paired with that exact provider id   |
 *
 * The authoritative transcript still stores [com.muyuchat.core.agent.runtime.AgentTranscriptEntry];
 * these records are the canonical JSON form recorded inside it, so a later reader can reconstruct
 * the exact call/result pair without string interpolation or provider quirks. Encoding is canonical
 * (sorted keys, re-serialized arguments/result), therefore `encode(decode(x)) == x` for any record
 * this codec accepted.
 *
 * Compatibility rules:
 *  * V1 prompted envelopes stay readable through a one-way upcast: a legacy bridge envelope
 *    (`{"protocol":"mca.agent.v1","kind":"tool","call":{...},"callId":"..."}`) decodes to the same
 *    call record with [AgentTranscriptV2Origin.V1_UPCAST].
 *  * A legacy V1 record without a provider call id (final text, ask_user, `tool_result` observation)
 *    is *readable as prompted text but never converted*: there is no provider id to pair, so the
 *    decoder returns a typed rejection instead of inventing one (TOOL-01).
 *  * Anything that claims the v2 marker but is malformed is fail-closed: unknown `record`/`kind`,
 *    missing or extra fields, wrong version, invalid call id, non-object arguments.
 */
object AgentTranscriptV2Schema {
    /** Marker of a transcript v2 record; every other shape is either V1 or not a record at all. */
    const val RECORD_MARKER: String = "mca.agent.transcript.v2"

    /** The only accepted transcript schema version of this codec. */
    const val SCHEMA_VERSION: Int = 2

    /** Bound for one encoded record; arguments/result stay inside the persist budget. */
    const val MAX_RECORD_BYTES: Int = AgentContract.MAX_TOOL_RESULT_PERSIST_BYTES
}

/** Serialized kinds of a transcript v2 record. Renaming a wire name needs a migration. */
enum class AgentTranscriptV2Kind(val wireName: String) {
    ASSISTANT_FUNCTION_CALL("assistant_function_call"),
    USER_FUNCTION_CALL_OUTPUT("user_function_call_output");

    companion object {
        fun fromWireName(name: String): AgentTranscriptV2Kind? =
            entries.firstOrNull { it.wireName == name }
    }
}

/** Where a decoded record came from: a native v2 record or an upcast legacy V1 envelope. */
enum class AgentTranscriptV2Origin { V2, V1_UPCAST }

/**
 * One reversible transcript record.
 *
 * Both variants validate their own identity at construction: a record can never be built without a
 * stable provider call id, and call arguments are a JSON object (the single supported call shape).
 */
sealed interface AgentTranscriptV2Record {
    val callId: String
    val kind: AgentTranscriptV2Kind

    data class AssistantFunctionCall(
        override val callId: String,
        val toolKey: String,
        val arguments: AgentJsonValue
    ) : AgentTranscriptV2Record {
        init {
            AgentContract.requireId(callId, "transcript v2 callId")
            AgentContract.requireNotBlank(toolKey, "transcript v2 tool key")
            require(toolKey.length <= MAX_TOOL_KEY_CHARS) {
                "transcript v2 tool key exceeds $MAX_TOOL_KEY_CHARS characters"
            }
            require(arguments is AgentJsonValue.Obj) {
                "transcript v2 call arguments must be one JSON object"
            }
        }

        override val kind: AgentTranscriptV2Kind
            get() = AgentTranscriptV2Kind.ASSISTANT_FUNCTION_CALL
    }

    data class FunctionCallOutput(
        override val callId: String,
        val result: AgentJsonValue
    ) : AgentTranscriptV2Record {
        init {
            AgentContract.requireId(callId, "transcript v2 callId")
        }

        override val kind: AgentTranscriptV2Kind
            get() = AgentTranscriptV2Kind.USER_FUNCTION_CALL_OUTPUT
    }

    companion object {
        private const val MAX_TOOL_KEY_CHARS = 256
    }
}

sealed interface AgentTranscriptV2DecodeResult {
    data class Decoded(
        val record: AgentTranscriptV2Record,
        val origin: AgentTranscriptV2Origin
    ) : AgentTranscriptV2DecodeResult

    /**
     * The input was readable but is not a native transcript record, or it claimed the v2 marker and
     * failed validation. Callers must not execute anything from a rejected record.
     */
    data class Rejected(
        val errorCode: String,
        val safeMessage: String
    ) : AgentTranscriptV2DecodeResult
}

/**
 * Strict encoder/decoder for [AgentTranscriptV2Record].
 *
 * No lenient parsing: prose, Markdown fences, partial JSON, duplicate keys, provider-specific
 * envelopes and unknown fields are rejected. Legacy V1 bridge envelopes are the only upcast input.
 */
class AgentTranscriptV2Codec(
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()
) {

    /** Canonical JSON of [record]; deterministic for the same record. */
    fun encode(record: AgentTranscriptV2Record): String = codec.write(recordValue(record))

    /** Canonical JSON AST of [record] for callers that store an AST (the transcript does). */
    fun recordValue(record: AgentTranscriptV2Record): AgentJsonValue.Obj {
        val fields = ArrayList<Pair<String, AgentJsonValue>>(6)
        fields += "record" to AgentJsonValue.Str(AgentTranscriptV2Schema.RECORD_MARKER)
        fields += "schemaVersion" to
            AgentJsonValue.Num(AgentTranscriptV2Schema.SCHEMA_VERSION.toString())
        fields += "kind" to AgentJsonValue.Str(record.kind.wireName)
        fields += "callId" to AgentJsonValue.Str(record.callId)
        when (record) {
            is AgentTranscriptV2Record.AssistantFunctionCall -> {
                fields += "tool" to AgentJsonValue.Str(record.toolKey)
                fields += "arguments" to record.arguments
            }
            is AgentTranscriptV2Record.FunctionCallOutput -> {
                fields += "result" to record.result
            }
        }
        return AgentJsonValues.canonical(AgentJsonValue.Obj(fields)) as AgentJsonValue.Obj
    }

    /** True when [value] claims to be a transcript v2 record (then it must also decode strictly). */
    fun claimsV2(value: AgentJsonValue): Boolean {
        val root = value as? AgentJsonValue.Obj ?: return false
        val marker = root.fields.firstOrNull { it.first == "record" }?.second
        return (marker as? AgentJsonValue.Str)?.value == AgentTranscriptV2Schema.RECORD_MARKER
    }

    /** True when [json] is a complete JSON object that claims the transcript v2 marker. */
    fun claimsV2(json: String): Boolean {
        val parsed = codec.parse(json) as? AgentJsonParseResult.Parsed ?: return false
        return claimsV2(parsed.value)
    }

    /**
     * Decodes exactly one transcript record.
     *
     * @return [AgentTranscriptV2DecodeResult.Decoded] with [AgentTranscriptV2Origin.V2] for a native
     * record, [AgentTranscriptV2Origin.V1_UPCAST] for a legacy bridge envelope, or a typed rejection.
     */
    fun decode(json: String): AgentTranscriptV2DecodeResult {
        if (json.isBlank()) return reject(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
            "The transcript record is empty."
        )
        if (AgentJsonValues.utf8Size(json) > AgentTranscriptV2Schema.MAX_RECORD_BYTES) {
            return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_TOO_LARGE,
                "The transcript record exceeds ${AgentTranscriptV2Schema.MAX_RECORD_BYTES} bytes."
            )
        }
        val parsed = codec.parse(json) as? AgentJsonParseResult.Parsed
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_RECORD,
                "The transcript record must be one complete JSON object."
            )
        return decodeValue(parsed.value)
    }

    fun decodeValue(value: AgentJsonValue): AgentTranscriptV2DecodeResult {
        val root = value as? AgentJsonValue.Obj
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_RECORD,
                "The transcript record must be one JSON object."
            )
        val fields = root.fields
        val marker = fields.firstOrNull { it.first == "record" }?.second as? AgentJsonValue.Str
        val protocol = fields.firstOrNull { it.first == "protocol" }?.second as? AgentJsonValue.Str
        return when {
            marker?.value == AgentTranscriptV2Schema.RECORD_MARKER -> decodeV2Fields(fields)
            // A V1 prompt envelope has no `record` marker; only its native bridge shape is upcastable.
            marker == null && protocol?.value == AgentContract.PROTOCOL_NAME ->
                upcastV1Envelope(fields)
            marker != null -> reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_RECORD,
                "Unknown transcript record marker '${safeText(marker.value)}'."
            )
            else -> reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_RECORD,
                "The record has no 'record' marker, so it is not a transcript record."
            )
        }
    }

    private fun decodeV2Fields(
        fields: List<Pair<String, AgentJsonValue>>
    ): AgentTranscriptV2DecodeResult {
        val map = fields.toMap()
        if (map.size != fields.size) {
            return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_FIELD,
                "The transcript record repeats a field."
            )
        }
        val version = map["schemaVersion"]
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
                "Missing required field 'schemaVersion'."
            )
        if (version !is AgentJsonValue.Num) {
            return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_FIELD_TYPE,
                "The 'schemaVersion' field must be a number."
            )
        }
        if (version.literal != AgentTranscriptV2Schema.SCHEMA_VERSION.toString()) {
            return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_VERSION_UNSUPPORTED,
                "Unsupported transcript schema version '${safeText(version.literal)}'."
            )
        }
        val kindValue = map["kind"]
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
                "Missing required field 'kind'."
            )
        val kindName = (kindValue as? AgentJsonValue.Str)?.value
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_FIELD_TYPE,
                "The 'kind' field must be a string."
            )
        val kind = AgentTranscriptV2Kind.fromWireName(kindName)
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_KIND,
                "Unknown transcript record kind '${safeText(kindName)}'."
            )
        val callId = (map["callId"] as? AgentJsonValue.Str)?.value
        if (callId == null) {
            return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
                "Missing required field 'callId'."
            )
        }
        if (!AgentContract.isValidId(callId)) {
            return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_CALL_ID_INVALID,
                "The 'callId' field must be a stable provider call id."
            )
        }
        return when (kind) {
            AgentTranscriptV2Kind.ASSISTANT_FUNCTION_CALL -> decodeCall(map, callId)
            AgentTranscriptV2Kind.USER_FUNCTION_CALL_OUTPUT -> decodeOutput(map, callId)
        }
    }

    private fun decodeCall(
        fields: Map<String, AgentJsonValue>,
        callId: String
    ): AgentTranscriptV2DecodeResult {
        val allowed = setOf("record", "schemaVersion", "kind", "callId", "tool", "arguments")
        extraField(fields, allowed)?.let { return it }
        val tool = (fields["tool"] as? AgentJsonValue.Str)?.value
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
                "Missing required field 'tool'."
            )
        val arguments = fields["arguments"]
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
                "Missing required field 'arguments'."
            )
        if (arguments !is AgentJsonValue.Obj) {
            return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_ARGUMENTS_NOT_OBJECT,
                "The call 'arguments' field must be one JSON object."
            )
        }
        if (tool.isBlank() || tool.length > MAX_TOOL_KEY_CHARS) {
            return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_FIELD_TYPE,
                "The 'tool' field must be a bounded non-blank tool key."
            )
        }
        val canonicalArguments = AgentJsonValues.canonical(arguments)
        if (!AgentJsonValues.isBoundedDepth(canonicalArguments)) {
            return reject(
                AgentProtocolErrorCodes.DEPTH_EXCEEDED,
                "The call arguments exceed the JSON depth limit."
            )
        }
        return AgentTranscriptV2DecodeResult.Decoded(
            record = AgentTranscriptV2Record.AssistantFunctionCall(
                callId = callId,
                toolKey = tool,
                arguments = canonicalArguments
            ),
            origin = AgentTranscriptV2Origin.V2
        )
    }

    private fun decodeOutput(
        fields: Map<String, AgentJsonValue>,
        callId: String
    ): AgentTranscriptV2DecodeResult {
        extraField(fields, setOf("record", "schemaVersion", "kind", "callId", "result"))?.let {
            return it
        }
        val result = fields["result"]
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
                "Missing required field 'result'."
            )
        val canonical = AgentJsonValues.canonical(result)
        if (!AgentJsonValues.isBoundedDepth(canonical)) {
            return reject(
                AgentProtocolErrorCodes.DEPTH_EXCEEDED,
                "The call result exceeds the JSON depth limit."
            )
        }
        return AgentTranscriptV2DecodeResult.Decoded(
            record = AgentTranscriptV2Record.FunctionCallOutput(callId = callId, result = canonical),
            origin = AgentTranscriptV2Origin.V2
        )
    }

    /**
     * One-way V1 upcast: the legacy native bridge envelope is the only V1 shape that carries a
     * provider call id. Every other V1 record is a prompted text observation and is rejected here.
     */
    private fun upcastV1Envelope(
        fields: List<Pair<String, AgentJsonValue>>
    ): AgentTranscriptV2DecodeResult {
        val map = fields.toMap()
        val kind = (map["kind"] as? AgentJsonValue.Str)?.value
        if (kind != "tool") {
            return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_V1_NON_NATIVE,
                "A V1 prompted '${safeText(kind.orEmpty())}' record has no provider call id."
            )
        }
        val call = map["call"] as? AgentJsonValue.Obj
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
                "Missing required field 'call'."
            )
        val callFields = call.fields.toMap()
        val name = (callFields["name"] as? AgentJsonValue.Str)?.value
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
                "Missing required field 'call.name'."
            )
        val arguments = callFields["arguments"]
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_MISSING_FIELD,
                "Missing required field 'call.arguments'."
            )
        if (arguments !is AgentJsonValue.Obj) {
            return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_ARGUMENTS_NOT_OBJECT,
                "The V1 call 'arguments' field must be one JSON object."
            )
        }
        val callId = (map["callId"] as? AgentJsonValue.Str)?.value
            ?: return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_V1_NON_NATIVE,
                "A V1 prompted call without a provider call id cannot become a native record."
            )
        if (!AgentContract.isValidId(callId)) {
            return reject(
                AgentProtocolErrorCodes.TRANSCRIPT_V2_CALL_ID_INVALID,
                "The V1 'callId' field is not a stable provider call id."
            )
        }
        return AgentTranscriptV2DecodeResult.Decoded(
            record = AgentTranscriptV2Record.AssistantFunctionCall(
                callId = callId,
                toolKey = name,
                arguments = AgentJsonValues.canonical(arguments)
            ),
            origin = AgentTranscriptV2Origin.V1_UPCAST
        )
    }

    private fun extraField(
        fields: Map<String, AgentJsonValue>,
        allowed: Set<String>
    ): AgentTranscriptV2DecodeResult.Rejected? {
        val extra = fields.keys.firstOrNull { it !in allowed } ?: return null
        return reject(
            AgentProtocolErrorCodes.TRANSCRIPT_V2_UNKNOWN_FIELD,
            "Unexpected transcript field '${safeText(extra)}'; the field set is fixed."
        )
    }

    private fun reject(code: String, message: String): AgentTranscriptV2DecodeResult.Rejected =
        AgentTranscriptV2DecodeResult.Rejected(errorCode = code, safeMessage = message)

    private fun safeText(value: String): String = value
        .take(64)
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")

    private companion object {
        const val MAX_TOOL_KEY_CHARS = 256
    }
}
