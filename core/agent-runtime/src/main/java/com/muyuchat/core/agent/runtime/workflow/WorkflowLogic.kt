package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec

/**
 * Bounded LOGIC node implementation (N18 MCA enhancement).
 *
 * The published config is a closed operation set, never an expression language or eval:
 *
 * ```
 * {"operation":"SET","value":<json>}
 * {"operation":"PICK","path":"a.b"}
 * {"operation":"DEFAULT","value":<json>}
 * {"operation":"CONCAT","suffix":"..."}
 * {"operation":"AND","operands":[{"input":0},{"input":1},true]}
 * {"operation":"OR","operands":[{"input":0},false]}
 * ```
 *
 * Inputs are the committed outputs of active direct predecessors. PICK/CONCAT require exactly one
 * input, DEFAULT returns the one input or the configured value, and every output is bounded. Missing
 * paths and type mismatches are typed errors and can never become a silent empty value.
 *
 * AND/OR read operands left to right and stop as soon as the result is decided, so a later operand
 * that is absent or malformed cannot fail a branch whose value is already known. An operand that
 * *does* decide the result must be a real boolean: a missing input is `WF_LOGIC_INPUT_MISSING`, never
 * an implicit `false`.
 */
sealed interface WorkflowLogicResult {
    data class Value(val value: AgentJsonValue) : WorkflowLogicResult

    data class Error(val code: String, val safeMessage: String) : WorkflowLogicResult
}

object WorkflowLogic {

    private val codec: StrictJsonCodec = StrictAgentJsonCodec()

    fun execute(
        configJson: String,
        inputs: List<AgentJsonValue> = emptyList()
    ): WorkflowLogicResult {
        if (configJson.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES) {
            return error("WF_LOGIC_CONFIG", "Logic config exceeds its byte limit.")
        }
        val parsed = codec.parse(configJson) as? AgentJsonParseResult.Parsed
            ?: return error("WF_LOGIC_CONFIG", "Logic config is not valid JSON.")
        val fields = (parsed.value as? AgentJsonValue.Obj)?.fields?.toMap()
            ?: return error("WF_LOGIC_CONFIG", "Logic config must be an object.")
        val operation = (fields["operation"] as? AgentJsonValue.Str)?.value
            ?: return error("WF_LOGIC_CONFIG", "Logic config needs an operation.")
        return when (operation) {
            "SET" -> bounded(fields["value"] ?: return error("WF_LOGIC_CONFIG", "SET needs value."))
            "DEFAULT" -> when {
                inputs.size > 1 -> error("WF_LOGIC_INPUT_AMBIGUOUS", "DEFAULT accepts at most one input.")
                inputs.size == 1 -> bounded(inputs.single())
                else -> bounded(
                    fields["value"] ?: return error("WF_LOGIC_CONFIG", "DEFAULT needs a fallback value.")
                )
            }
            "PICK" -> {
                val input = singleInput(inputs) ?: return error(
                    "WF_LOGIC_INPUT_MISSING",
                    "PICK needs exactly one input."
                )
                val path = (fields["path"] as? AgentJsonValue.Str)?.value
                    ?: return error("WF_LOGIC_CONFIG", "PICK needs a path.")
                pick(input, path)
            }
            "CONCAT" -> {
                val input = singleInput(inputs)
                if (input == null) return error("WF_LOGIC_INPUT_MISSING", "CONCAT needs exactly one input.")
                val text = (input as? AgentJsonValue.Str)?.value
                    ?: return error("WF_LOGIC_TYPE", "CONCAT input must be a string.")
                val suffix = (fields["suffix"] as? AgentJsonValue.Str)?.value
                    ?: return error("WF_LOGIC_CONFIG", "CONCAT needs a suffix.")
                val combined = text + suffix
                if (combined.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_BYTES) {
                    return error("WF_LOGIC_LIMIT", "Concatenated output exceeds its byte limit.")
                }
                WorkflowLogicResult.Value(AgentJsonValue.Str(combined))
            }
            "AND", "OR" -> andOr(operation, fields["operands"], inputs)
            else -> error("WF_LOGIC_OP", "Unsupported logic operation '$operation'.")
        }
    }

    /**
     * Short-circuit boolean fold. An operand is either a boolean literal or `{"input":<index>}`
     * pointing at a committed predecessor output; anything else that would decide the result is a
     * typed error.
     */
    private fun andOr(
        operation: String,
        rawOperands: AgentJsonValue?,
        inputs: List<AgentJsonValue>
    ): WorkflowLogicResult {
        val operands = (rawOperands as? AgentJsonValue.Arr)?.items
            ?: return error("WF_LOGIC_CONFIG", "\$operation needs an operands array.")
        if (operands.isEmpty()) return error("WF_LOGIC_CONFIG", "\$operation needs at least one operand.")
        if (operands.size > MAX_OPERANDS) return error("WF_LOGIC_LIMIT", "\$operation has too many operands.")
        val decisive = operation == "OR"
        for (operand in operands) {
            val value = when (operand) {
                is AgentJsonValue.Bool -> operand.value
                is AgentJsonValue.Obj -> {
                    val fields = operand.fields.toMap()
                    val index = (fields["input"] as? AgentJsonValue.Num)?.literal?.toIntOrNull()
                        ?: return error("WF_LOGIC_CONFIG", "An operand must be a boolean or {\"input\":<index>}.")
                    val input = inputs.getOrNull(index)
                        ?: return error("WF_LOGIC_INPUT_MISSING", "Operand input \$index is not committed.")
                    (input as? AgentJsonValue.Bool)?.value
                        ?: return error("WF_LOGIC_TYPE", "Operand input \$index must be a boolean.")
                }
                else -> return error("WF_LOGIC_CONFIG", "An operand must be a boolean or {\"input\":<index>}.")
            }
            if (value == decisive) return WorkflowLogicResult.Value(AgentJsonValue.Bool(decisive))
        }
        return WorkflowLogicResult.Value(AgentJsonValue.Bool(!decisive))
    }

    private fun singleInput(inputs: List<AgentJsonValue>): AgentJsonValue? =
        if (inputs.size == 1) inputs.single() else null

    private fun pick(root: AgentJsonValue, path: String): WorkflowLogicResult {
        if (path.isBlank()) return error("WF_LOGIC_PATH_INVALID", "PICK path is blank.")
        if (path == WHOLE_VALUE) return bounded(root)
        val segments = path.split('.')
        if (segments.size > MAX_PATH_SEGMENTS || segments.any { it.isBlank() || it.length > MAX_SEGMENT_CHARS }) {
            return error("WF_LOGIC_PATH_INVALID", "PICK path is empty, too deep or has an over-long segment.")
        }
        var current: AgentJsonValue = root
        for (segment in segments) {
            current = when (current) {
                is AgentJsonValue.Obj -> current.fields.firstOrNull { it.first == segment }?.second
                    ?: return error("WF_LOGIC_PATH_MISSING", "PICK path does not exist.")
                is AgentJsonValue.Arr -> {
                    val index = segment.toIntOrNull()
                        ?: return error("WF_LOGIC_PATH_INVALID", "PICK array segment must be an index.")
                    current.items.getOrNull(index)
                        ?: return error("WF_LOGIC_PATH_MISSING", "PICK array index is out of range.")
                }
                else -> return error("WF_LOGIC_PATH_INVALID", "PICK path enters a scalar.")
            }
        }
        return bounded(current)
    }

    private fun bounded(value: AgentJsonValue): WorkflowLogicResult {
        val encoded = codec.write(value)
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_BYTES) {
            return error("WF_LOGIC_LIMIT", "Logic output exceeds its byte limit.")
        }
        return WorkflowLogicResult.Value(value)
    }

    private fun error(code: String, message: String): WorkflowLogicResult.Error =
        WorkflowLogicResult.Error(code, message)

    private const val MAX_CONFIG_BYTES = 8 * 1024
    private const val MAX_OUTPUT_BYTES = 8 * 1024
    private const val MAX_PATH_SEGMENTS = 8
    private const val MAX_SEGMENT_CHARS = 64
    private const val MAX_OPERANDS = 16
    private const val WHOLE_VALUE = "\$"
}
