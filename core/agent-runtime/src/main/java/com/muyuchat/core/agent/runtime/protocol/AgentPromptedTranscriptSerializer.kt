package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentModelMessage
import com.muyuchat.core.agent.contract.AgentTranscriptRole
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentPromptedTranscriptContext
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.AgentTranscriptSerialization
import com.muyuchat.core.agent.runtime.AgentTranscriptSerializer
import com.muyuchat.core.agent.runtime.StrictJsonCodec

/**
 * The single PROMPTED_V1 transcript mapping (architecture 6.5.1).
 *
 * | authoritative record        | local role / content                                         |
 * |-----------------------------|--------------------------------------------------------------|
 * | versioned system prompt     | SYSTEM, exact [AgentPromptedTranscriptContext.systemPrompt]  |
 * | user goal                   | USER  `{"record":"user_input","text":...}`                   |
 * | accepted assistant envelope | ASSISTANT, exact raw envelope text                           |
 * | host tool result            | USER  `{"record":"tool_result",...}`                         |
 * | ask_user + user answer      | ASSISTANT envelope, then USER `{"record":"user_answer",...}` |
 * | protocol repair request     | USER  `{"record":"protocol_error",...}`                      |
 *
 * `tools`/`toolExchanges` are intentionally absent: PROMPTED_V1 relies on this text mapping and
 * the adapter must not append chat history, tool exchanges or a second SYSTEM prompt.
 *
 * Message ids are deterministic for a given entry order (`agent-msg-<index>`) and distinct. Every
 * JSON value is produced by [StrictJsonCodec]; no string interpolation is used.
 */
class AgentPromptedTranscriptSerializer(
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()
) : AgentTranscriptSerializer {

    override fun serialize(context: AgentPromptedTranscriptContext): AgentTranscriptSerialization {
        require(context.systemPrompt.isNotBlank()) { "system prompt must not be blank" }
        require(context.systemPrompt.toByteArray(Charsets.UTF_8).size <= MAX_SYSTEM_PROMPT_BYTES) {
            "system prompt exceeds the $MAX_SYSTEM_PROMPT_BYTES byte budget"
        }
        require(context.userGoal.isNotBlank()) { "user goal must not be blank" }
        val messages = ArrayList<AgentModelMessage>(context.entries.size + 2)
        messages += message(
            index = 0,
            role = AgentTranscriptRole.SYSTEM,
            content = context.systemPrompt
        )
        messages += message(
            index = 1,
            role = AgentTranscriptRole.USER,
            content = encodeObservation(
                AgentJsonValues.obj(
                    "record" to AgentJsonValue.Str("user_input"),
                    "text" to AgentJsonValue.Str(context.userGoal)
                )
            )
        )
        for (entry in context.entries) {
            messages += when (entry) {
                is AgentTranscriptEntry.AssistantEnvelope -> {
                    requireAcceptedEnvelope(entry.rawEnvelope)
                    message(
                        index = messages.size,
                        role = AgentTranscriptRole.ASSISTANT,
                        content = entry.rawEnvelope
                    )
                }
                is AgentTranscriptEntry.ToolResult -> message(
                    index = messages.size,
                    role = AgentTranscriptRole.USER,
                    content = encodeObservation(
                        AgentJsonValues.obj(
                            "record" to AgentJsonValue.Str("tool_result"),
                            "invocationId" to AgentJsonValue.Str(entry.invocationId),
                            "callHash" to AgentJsonValue.Str(entry.callHash),
                            "tool" to AgentJsonValue.Str(entry.toolKey),
                            "result" to entry.result
                        )
                    )
                )
                is AgentTranscriptEntry.UserAnswer -> message(
                    index = messages.size,
                    role = AgentTranscriptRole.USER,
                    content = encodeObservation(
                        AgentJsonValues.obj(
                            "record" to AgentJsonValue.Str("user_answer"),
                            "questionId" to AgentJsonValue.Str(entry.questionId),
                            "text" to AgentJsonValue.Str(entry.text)
                        )
                    )
                )
                is AgentTranscriptEntry.ProtocolError -> message(
                    index = messages.size,
                    role = AgentTranscriptRole.USER,
                    content = encodeObservation(
                        AgentJsonValues.obj(
                            "record" to AgentJsonValue.Str("protocol_error"),
                            "errorCode" to AgentJsonValue.Str(entry.errorCode),
                            "schemaHint" to schemaHintValue(entry.schemaHintJson)
                        )
                    )
                )
            }
        }
        return AgentTranscriptSerialization(messages = messages)
    }

    private fun message(index: Int, role: AgentTranscriptRole, content: String): AgentModelMessage =
        AgentModelMessage(messageId = "agent-msg-$index", role = role, content = content)

    private fun encodeObservation(value: AgentJsonValue): String = codec.write(AgentJsonValues.canonical(value))

    private fun requireAcceptedEnvelope(raw: String) {
        require(raw.toByteArray(Charsets.UTF_8).size <= AgentContract.MAX_CONTROL_RESPONSE_BYTES) {
            "assistant envelope exceeds the control response budget"
        }
        val parsed = codec.parse(raw)
        require(parsed is AgentJsonParseResult.Parsed && parsed.value is AgentJsonValue.Obj) {
            "assistant envelope must be one complete JSON object"
        }
    }

    private fun schemaHintValue(schemaHintJson: String): AgentJsonValue {
        val parsed = codec.parse(schemaHintJson)
        return if (parsed is AgentJsonParseResult.Parsed) parsed.value else AgentJsonValue.Str(
            schemaHintJson.take(1024)
        )
    }

    companion object {
        const val MAX_SYSTEM_PROMPT_BYTES: Int = 16 * 1024
    }
}
