package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentModelMessage
import com.muyuchat.core.agent.contract.AgentModelMessageKind
import com.muyuchat.core.agent.contract.AgentTranscriptRole
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentPromptedTranscriptContext
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.AgentTranscriptSerialization
import com.muyuchat.core.agent.runtime.AgentTranscriptSerializer
import com.muyuchat.core.agent.runtime.StrictJsonCodec

/**
 * NATIVE transcript mapping (N05c send side, N05d canonical records).
 *
 * The authoritative transcript is unchanged; only the provider wire items differ:
 *
 * | authoritative record                        | native item                                  |
 * |---------------------------------------------|----------------------------------------------|
 * | system prompt                               | SYSTEM text (provider `instructions`)        |
 * | user goal                                   | USER text                                    |
 * | accepted native call (transcript v2 record) | FUNCTION_CALL(callId, toolKey, arguments)    |
 * | host result for that call (v2 output record)| FUNCTION_OUTPUT(callId, result JSON)         |
 * | legacy V1 bridge envelope (upcast)          | FUNCTION_CALL(callId, toolKey, arguments)    |
 * | prompted envelope (defensive fallback)      | ASSISTANT text                               |
 * | ask_user answer / protocol repair           | USER text                                    |
 *
 * Calls and results are read through [AgentTranscriptV2Codec]: a record that claims the v2 marker
 * but is malformed, or whose call id disagrees with the transcript entry, fails closed instead of
 * being sent as a guessed provider item. A legacy V1 prompted call without a provider id stays a
 * text observation, and a result that is not a v2 record (for example the bounded truncated-result
 * skeleton) keeps its JSON payload so the pairing and truncation marker are never lost.
 */
class AgentNativeTranscriptSerializer(
    private val codec: StrictJsonCodec = StrictAgentJsonCodec(),
    private val transcriptCodec: AgentTranscriptV2Codec = AgentTranscriptV2Codec(codec)
) : AgentTranscriptSerializer {

    override fun serialize(context: AgentPromptedTranscriptContext): AgentTranscriptSerialization {
        require(context.systemPrompt.isNotBlank()) { "system prompt must not be blank" }
        require(context.systemPrompt.toByteArray(Charsets.UTF_8).size <= MAX_SYSTEM_PROMPT_BYTES) {
            "system prompt exceeds the $MAX_SYSTEM_PROMPT_BYTES byte budget"
        }
        require(context.userGoal.isNotBlank()) { "user goal must not be blank" }
        val messages = ArrayList<AgentModelMessage>(context.entries.size + 2)
        messages += AgentModelMessage(
            messageId = "agent-msg-0",
            role = AgentTranscriptRole.SYSTEM,
            content = context.systemPrompt
        )
        messages += AgentModelMessage(
            messageId = "agent-msg-1",
            role = AgentTranscriptRole.USER,
            content = context.userGoal
        )
        var pendingCallId: String? = null
        for (entry in context.entries) {
            when (entry) {
                is AgentTranscriptEntry.AssistantEnvelope -> {
                    val callId = entry.providerCallId
                    if (callId != null) {
                        val call = requireCall(entry.rawEnvelope, callId)
                        pendingCallId = callId
                        messages += AgentModelMessage(
                            messageId = "agent-msg-${messages.size}",
                            role = AgentTranscriptRole.ASSISTANT,
                            content = encodeObservation(AgentJsonValues.canonical(call.arguments)),
                            kind = AgentModelMessageKind.FUNCTION_CALL,
                            callId = callId,
                            toolName = call.toolKey
                        )
                    } else {
                        pendingCallId = null
                        messages += AgentModelMessage(
                            messageId = "agent-msg-${messages.size}",
                            role = AgentTranscriptRole.ASSISTANT,
                            content = entry.rawEnvelope
                        )
                    }
                }
                is AgentTranscriptEntry.ToolResult -> {
                    val callId = entry.providerCallId
                    if (callId != null) {
                        require(pendingCallId == callId) {
                            "A native tool result must pair with the preceding call '$callId'."
                        }
                        pendingCallId = null
                        messages += AgentModelMessage(
                            messageId = "agent-msg-${messages.size}",
                            role = AgentTranscriptRole.USER,
                            content = outputPayload(entry, callId),
                            kind = AgentModelMessageKind.FUNCTION_OUTPUT,
                            callId = callId
                        )
                    } else {
                        messages += textObservation(
                            index = messages.size,
                            record = "tool_result",
                            fields = listOf(
                                "invocationId" to AgentJsonValue.Str(entry.invocationId),
                                "callHash" to AgentJsonValue.Str(entry.callHash),
                                "tool" to AgentJsonValue.Str(entry.toolKey),
                                "result" to entry.result
                            )
                        )
                    }
                }
                is AgentTranscriptEntry.UserAnswer -> {
                    pendingCallId = null
                    messages += textObservation(
                        index = messages.size,
                        record = "user_answer",
                        fields = listOf(
                            "questionId" to AgentJsonValue.Str(entry.questionId),
                            "text" to AgentJsonValue.Str(entry.text)
                        )
                    )
                }
                is AgentTranscriptEntry.ProtocolError -> {
                    pendingCallId = null
                    messages += textObservation(
                        index = messages.size,
                        record = "protocol_error",
                        fields = listOf(
                            "errorCode" to AgentJsonValue.Str(entry.errorCode),
                            "schemaHint" to schemaHintValue(entry.schemaHintJson)
                        )
                    )
                }
            }
        }
        return AgentTranscriptSerialization(messages = messages)
    }

    /**
     * The call record of an assistant entry, fail-closed on any malformed/unpairable input.
     *
     * A legacy V1 bridge envelope recorded by an earlier build may omit its own `callId` (the id
     * lived on the transcript entry). That exact prompted tool shape stays readable and is paired
     * with the entry's provider id; it never invents an id, and any other rejection (including a
     * malformed transcript v2 claim) still fails closed.
     */
    private fun requireCall(
        rawEnvelope: String,
        providerCallId: String
    ): AgentTranscriptV2Record.AssistantFunctionCall {
        val decoded = when (val result = transcriptCodec.decode(rawEnvelope)) {
            is AgentTranscriptV2DecodeResult.Decoded -> result.record
            is AgentTranscriptV2DecodeResult.Rejected -> {
                if (result.errorCode == AgentProtocolErrorCodes.TRANSCRIPT_V2_V1_NON_NATIVE) {
                    legacyV1Call(rawEnvelope, providerCallId)?.let { return it }
                }
                throw IllegalArgumentException(
                    "Native call '${result.errorCode}': ${result.safeMessage}"
                )
            }
        }
        require(decoded is AgentTranscriptV2Record.AssistantFunctionCall) {
            "An assistant entry with providerCallId must hold a function-call record."
        }
        require(decoded.callId == providerCallId) {
            "The function-call record id '${decoded.callId}' does not match the transcript entry " +
                "'$providerCallId'."
        }
        return decoded
    }

    /** Reads a legacy V1 prompted tool envelope, using the entry's provider id for pairing. */
    private fun legacyV1Call(
        rawEnvelope: String,
        providerCallId: String
    ): AgentTranscriptV2Record.AssistantFunctionCall? {
        val root = (codec.parse(rawEnvelope) as? AgentJsonParseResult.Parsed)
            ?.value as? AgentJsonValue.Obj ?: return null
        val fields = root.fields.toMap()
        if ((fields["protocol"] as? AgentJsonValue.Str)?.value != AgentContract.PROTOCOL_NAME) {
            return null
        }
        if ((fields["kind"] as? AgentJsonValue.Str)?.value != "tool") return null
        val call = fields["call"] as? AgentJsonValue.Obj ?: return null
        val callFields = call.fields.toMap()
        val name = (callFields["name"] as? AgentJsonValue.Str)?.value ?: return null
        val arguments = callFields["arguments"] as? AgentJsonValue.Obj ?: return null
        return AgentTranscriptV2Record.AssistantFunctionCall(
            callId = providerCallId,
            toolKey = name,
            arguments = AgentJsonValues.canonical(arguments)
        )
    }

    /**
     * The model-visible `function_call_output` payload.
     *
     * A v2 record is unwrapped to its canonical result JSON so the provider sees the tool output,
     * not MCA's transcript envelope. Anything else is a legacy/truncated observation and is sent as
     * its canonical JSON with the entry's call id.
     */
    private fun outputPayload(entry: AgentTranscriptEntry.ToolResult, providerCallId: String): String {
        if (!transcriptCodec.claimsV2(entry.result)) {
            return encodeObservation(AgentJsonValues.canonical(entry.result))
        }
        val decoded = when (val result = transcriptCodec.decodeValue(entry.result)) {
            is AgentTranscriptV2DecodeResult.Decoded -> result.record
            is AgentTranscriptV2DecodeResult.Rejected -> throw IllegalArgumentException(
                "Native tool result '${result.errorCode}': ${result.safeMessage}"
            )
        }
        require(decoded is AgentTranscriptV2Record.FunctionCallOutput) {
            "A paired tool result must hold a function-call-output record."
        }
        require(decoded.callId == providerCallId) {
            "The function-call-output record id '${decoded.callId}' does not match the transcript " +
                "entry '$providerCallId'."
        }
        return encodeObservation(AgentJsonValues.canonical(decoded.result))
    }

    private fun textObservation(
        index: Int,
        record: String,
        fields: List<Pair<String, AgentJsonValue>>
    ): AgentModelMessage = AgentModelMessage(
        messageId = "agent-msg-$index",
        role = AgentTranscriptRole.USER,
        content = encodeObservation(
            AgentJsonValues.obj(*(listOf("record" to AgentJsonValue.Str(record)) + fields).toTypedArray())
        )
    )

    private fun encodeObservation(value: AgentJsonValue): String = codec.write(AgentJsonValues.canonical(value))

    private fun schemaHintValue(schemaHintJson: String): AgentJsonValue {
        val parsed = codec.parse(schemaHintJson)
        return if (parsed is AgentJsonParseResult.Parsed) parsed.value else AgentJsonValue.Str(
            schemaHintJson.take(1024)
        )
    }

    private companion object {
        const val MAX_SYSTEM_PROMPT_BYTES = AgentPromptedTranscriptSerializer.MAX_SYSTEM_PROMPT_BYTES
    }
}
