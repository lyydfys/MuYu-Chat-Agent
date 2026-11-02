package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.runtime.AgentControlDecoder
import com.muyuchat.core.agent.runtime.AgentControlMessage
import com.muyuchat.core.agent.runtime.AgentDecodeResult
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec

/**
 * PROMPTED_V1 control decoder.
 *
 * Accepts exactly one complete JSON object that matches the frozen envelope for `tool`, `final` or
 * `ask_user`. It never searches for JSON inside prose, reasoning, tool output or Markdown fences and
 * never repairs/guesses a call: every invalid input produces a typed [AgentDecodeResult.Rejected]
 * with a machine-readable repair hint, so the caller can count executor invocations as zero.
 *
 * @param availableToolKeys dynamic snapshot of the tool keys enabled for the current run. It is
 * read exactly once per [decode] call so a per-run Skills/MCP catalog cannot be frozen globally at
 * construction; a call to any key outside the snapshot is rejected as `TOOL_NOT_FOUND` before
 * validation or execution.
 */
class PromptedV1Decoder(
    availableToolKeys: () -> Set<String>,
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()
) : AgentControlDecoder {

    private val toolKeySource: () -> Set<String> = availableToolKeys

    override fun decode(visibleText: String): AgentDecodeResult {
        val toolKeys = toolKeySource().toSet()
        if (visibleText.isBlank()) {
            return reject(
                toolKeys,
                AgentProtocolErrorCodes.EMPTY,
                "The response is empty; reply with one complete JSON object."
            )
        }
        val trimmed = visibleText.trim()
        if (trimmed.startsWith("```") || trimmed.endsWith("```")) {
            return reject(
                toolKeys,
                AgentProtocolErrorCodes.MARKDOWN_FENCE,
                "Do not wrap the control object in a Markdown fence."
            )
        }
        return when (val parsed = codec.parse(visibleText)) {
            is AgentJsonParseResult.Failed -> reject(toolKeys, parsed.errorCode, parsed.message)
            is AgentJsonParseResult.Parsed -> decodeRoot(parsed.value, toolKeys)
        }
    }

    private fun decodeRoot(
        value: AgentJsonValue,
        toolKeys: Set<String>
    ): AgentDecodeResult {
        val root = value as? AgentJsonValue.Obj
            ?: return reject(
                toolKeys,
                AgentProtocolErrorCodes.NOT_OBJECT,
                "The control response must be a single JSON object."
            )
        val fields = root.fields.toMap()
        val protocol = fields["protocol"]
            ?: return reject(
                toolKeys,
                AgentProtocolErrorCodes.MISSING_FIELD,
                "Missing required field 'protocol'."
            )
        if (protocol !is AgentJsonValue.Str || protocol.value != AgentContract.PROTOCOL_NAME) {
            return reject(
                toolKeys,
                AgentProtocolErrorCodes.WRONG_PROTOCOL,
                "The 'protocol' field must be exactly '${AgentContract.PROTOCOL_NAME}'."
            )
        }
        val kind = fields["kind"]
            ?: return reject(
                toolKeys,
                AgentProtocolErrorCodes.MISSING_FIELD,
                "Missing required field 'kind'."
            )
        if (kind !is AgentJsonValue.Str) {
            return reject(toolKeys, AgentProtocolErrorCodes.FIELD_TYPE, "The 'kind' field must be a string.")
        }
        return when (kind.value) {
            "tool" -> decodeTool(root, toolKeys)
            "final" -> decodeFinal(root, toolKeys)
            "ask_user" -> decodeAskUser(root, toolKeys)
            else -> reject(
                toolKeys,
                AgentProtocolErrorCodes.UNKNOWN_KIND,
                "The 'kind' field must be one of: tool, final, ask_user."
            )
        }
    }

    private fun decodeTool(root: AgentJsonValue.Obj, toolKeys: Set<String>): AgentDecodeResult {
        val fields = root.fields.toMap()
        extraField(fields, setOf("protocol", "kind", "call"), toolKeys)?.let { return it }
        val call = fields["call"]
            ?: return reject(toolKeys, AgentProtocolErrorCodes.MISSING_FIELD, "Missing required field 'call'.")
        if (call !is AgentJsonValue.Obj) {
            return reject(toolKeys, AgentProtocolErrorCodes.FIELD_TYPE, "The 'call' field must be an object.")
        }
        val callFields = call.fields.toMap()
        extraField(callFields, setOf("name", "arguments"), toolKeys)?.let { return it }
        val name = callFields["name"]
            ?: return reject(
                toolKeys,
                AgentProtocolErrorCodes.MISSING_FIELD,
                "Missing required field 'call.name'."
            )
        if (name !is AgentJsonValue.Str) {
            return reject(
                toolKeys,
                AgentProtocolErrorCodes.FIELD_TYPE,
                "The 'call.name' field must be a string."
            )
        }
        val arguments = callFields["arguments"]
            ?: return reject(
                toolKeys,
                AgentProtocolErrorCodes.MISSING_FIELD,
                "Missing required field 'call.arguments'."
            )
        if (arguments !is AgentJsonValue.Obj) {
            return reject(
                toolKeys,
                AgentProtocolErrorCodes.TOOL_ARGUMENTS_NOT_OBJECT,
                "The 'call.arguments' field must be a JSON object."
            )
        }
        if (name.value !in toolKeys) {
            return reject(
                toolKeys,
                AgentProtocolErrorCodes.TOOL_NOT_FOUND,
                "Tool '${safeText(name.value)}' is not available in this run."
            )
        }
        val argumentsJson = codec.write(AgentJsonValues.canonical(arguments))
        return AgentDecodeResult.Accepted(
            AgentControlMessage.ToolCall(name = name.value, argumentsJson = argumentsJson)
        )
    }

    private fun decodeFinal(root: AgentJsonValue.Obj, toolKeys: Set<String>): AgentDecodeResult {
        val fields = root.fields.toMap()
        extraField(fields, setOf("protocol", "kind", "text", "artifactIds"), toolKeys)?.let { return it }
        val text = fields["text"]
            ?: return reject(toolKeys, AgentProtocolErrorCodes.MISSING_FIELD, "Missing required field 'text'.")
        if (text !is AgentJsonValue.Str) {
            return reject(toolKeys, AgentProtocolErrorCodes.FIELD_TYPE, "The 'text' field must be a string.")
        }
        if (AgentJsonValues.utf8Size(text.value) > AgentContract.MAX_FINAL_TEXT_BYTES) {
            return reject(
                toolKeys,
                AgentProtocolErrorCodes.FINAL_TEXT_TOO_LARGE,
                "The final 'text' exceeds the 32 KiB UTF-8 limit."
            )
        }
        val artifactIds = when (val raw = fields["artifactIds"]) {
            null -> emptyList()
            is AgentJsonValue.Arr -> {
                if (raw.items.size > AgentContract.MAX_FINAL_ARTIFACTS) {
                    return reject(
                        toolKeys,
                        AgentProtocolErrorCodes.ARTIFACT_LIMIT,
                        "At most ${AgentContract.MAX_FINAL_ARTIFACTS} artifact ids are allowed."
                    )
                }
                val ids = ArrayList<String>(raw.items.size)
                val seen = HashSet<String>()
                for (item in raw.items) {
                    if (item !is AgentJsonValue.Str || !AgentContract.isValidId(item.value)) {
                        return reject(
                            toolKeys,
                            AgentProtocolErrorCodes.ARTIFACT_INVALID,
                            "Every artifact id must be a stable task artifact identifier."
                        )
                    }
                    if (!seen.add(item.value)) {
                        return reject(
                            toolKeys,
                            AgentProtocolErrorCodes.ARTIFACT_INVALID,
                            "Artifact ids must not repeat."
                        )
                    }
                    ids += item.value
                }
                ids
            }
            else -> return reject(
                toolKeys,
                AgentProtocolErrorCodes.FIELD_TYPE,
                "The 'artifactIds' field must be an array of strings."
            )
        }
        if (text.value.isBlank() && artifactIds.isEmpty()) {
            return reject(
                toolKeys,
                AgentProtocolErrorCodes.FINAL_EMPTY,
                "A final answer needs non-empty visible text or at least one verified artifact id."
            )
        }
        return AgentDecodeResult.Accepted(
            AgentControlMessage.Final(text = text.value, artifactIds = artifactIds)
        )
    }

    private fun decodeAskUser(root: AgentJsonValue.Obj, toolKeys: Set<String>): AgentDecodeResult {
        val fields = root.fields.toMap()
        extraField(fields, setOf("protocol", "kind", "question", "options"), toolKeys)?.let { return it }
        val question = fields["question"]
            ?: return reject(
                toolKeys,
                AgentProtocolErrorCodes.MISSING_FIELD,
                "Missing required field 'question'."
            )
        if (question !is AgentJsonValue.Str) {
            return reject(
                toolKeys,
                AgentProtocolErrorCodes.FIELD_TYPE,
                "The 'question' field must be a string."
            )
        }
        if (question.value.isBlank()) {
            return reject(
                toolKeys,
                AgentProtocolErrorCodes.QUESTION_EMPTY,
                "The 'question' field must not be blank."
            )
        }
        if (AgentJsonValues.utf8Size(question.value) > 4 * 1024) {
            return reject(
                toolKeys,
                AgentProtocolErrorCodes.QUESTION_TOO_LARGE,
                "The 'question' field exceeds the 4 KiB UTF-8 limit."
            )
        }
        val options = when (val raw = fields["options"]) {
            null -> emptyList()
            is AgentJsonValue.Arr -> {
                if (raw.items.size !in 1..3) {
                    return reject(
                        toolKeys,
                        AgentProtocolErrorCodes.OPTIONS_INVALID,
                        "The 'options' field must contain between 1 and 3 strings when present."
                    )
                }
                val values = ArrayList<String>(raw.items.size)
                for (item in raw.items) {
                    if (item !is AgentJsonValue.Str) {
                        return reject(
                            toolKeys,
                            AgentProtocolErrorCodes.OPTIONS_INVALID,
                            "Every option must be a string."
                        )
                    }
                    if (item.value.length > 128) {
                        return reject(
                            toolKeys,
                            AgentProtocolErrorCodes.OPTIONS_INVALID,
                            "Each option must be at most 128 characters."
                        )
                    }
                    values += item.value
                }
                values
            }
            else -> return reject(
                toolKeys,
                AgentProtocolErrorCodes.OPTIONS_INVALID,
                "The 'options' field must be an array of 1 to 3 strings."
            )
        }
        return AgentDecodeResult.Accepted(
            AgentControlMessage.AskUser(question = question.value, options = options)
        )
    }

    private fun extraField(
        fields: Map<String, AgentJsonValue>,
        allowed: Set<String>,
        toolKeys: Set<String>
    ): AgentDecodeResult.Rejected? {
        val extra = fields.keys.firstOrNull { it !in allowed } ?: return null
        return reject(
            toolKeys,
            AgentProtocolErrorCodes.UNKNOWN_FIELD,
            "Unexpected control field '${safeText(extra)}'; the field set is fixed for each kind."
        )
    }

    private fun reject(
        toolKeys: Set<String>,
        code: String,
        message: String
    ): AgentDecodeResult.Rejected = AgentDecodeResult.Rejected(
        errorCode = code,
        safeMessage = message,
        repairHintJson = AgentRepairHints.build(codec, code, message, toolKeys)
    )

    private fun safeText(value: String): String = value
        .take(64)
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
}
