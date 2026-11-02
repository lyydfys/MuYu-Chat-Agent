package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import java.security.SecureRandom

/**
 * Bounded EXTRACT node implementation (WF-07).
 *
 * Config shape: `{"op":"REGEX|JSON|SUB|CONCAT|RANDOM_INT|RANDOM_STRING","source":"...","path":"..."}`.
 * Every operation has explicit input/output limits; invalid input or an invalid pattern is a typed
 * error, never a silent empty value. Random values are produced once and stored in the node output,
 * which the checkpoint persists, so a resume never re-rolls them.
 */
sealed interface WorkflowExtractResult {
    data class Value(val value: AgentJsonValue) : WorkflowExtractResult
    data class Error(val code: String, val safeMessage: String) : WorkflowExtractResult
}

object WorkflowExtract {

    private val codec = StrictAgentJsonCodec()
    private val random = SecureRandom()

    fun execute(
        configJson: String,
        inputs: List<AgentJsonValue> = emptyList()
    ): WorkflowExtractResult {
        val parsed = codec.parse(configJson) as? AgentJsonParseResult.Parsed
            ?: return error("WF_EXTRACT_CONFIG", "Extract config is not valid JSON.")
        val fields = (parsed.value as? AgentJsonValue.Obj)?.fields?.toMap()
            ?: return error("WF_EXTRACT_CONFIG", "Extract config must be an object.")
        val op = (fields["op"] as? AgentJsonValue.Str)?.value
        val path = (fields["path"] as? AgentJsonValue.Str)?.value
            ?: return error("WF_EXTRACT_CONFIG", "Extract config needs path.")
        if (op == null) {
            // Legacy app contract: dot-path lookup over exactly one committed predecessor value.
            if (inputs.isEmpty()) return error("WF_EXTRACT_INPUT_MISSING", "Extract needs an input value.")
            if (inputs.size > 1) return error("WF_EXTRACT_INPUT_AMBIGUOUS", "Extract needs exactly one input.")
            return json(inputs.single(), path, legacy = true)
        }
        val source = (fields["source"] as? AgentJsonValue.Str)?.value
            ?: return error("WF_EXTRACT_CONFIG", "Extract config needs source.")
        if (source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) {
            return error("WF_EXTRACT_LIMIT", "Extract source is too large.")
        }
        return when (op) {
            "REGEX" -> regex(source, path)
            "JSON" -> json(source, path)
            "SUB" -> substring(source, path)
            "CONCAT" -> concatenate(source, path)
            "RANDOM_INT" -> randomInt(path)
            "RANDOM_STRING" -> randomString(path)
            else -> error("WF_EXTRACT_OP", "Unsupported extract op '$op'.")
        }
    }

    private fun regex(source: String, pattern: String): WorkflowExtractResult {
        if (pattern.isBlank() || pattern.length > MAX_PATTERN_CHARS) {
            return error("WF_EXTRACT_PATTERN", "Regex pattern is blank or too long.")
        }
        if (source.length > MAX_REGEX_SOURCE_CHARS) {
            return error("WF_EXTRACT_LIMIT", "Regex source is too large.")
        }
        // Kotlin Regex has no deterministic timeout, so reject nested quantified groups that are the
        // common catastrophic-backtracking shape; combined with the bounded source this caps work.
        if (RISKY_BACKTRACKING.containsMatchIn(pattern)) {
            return error("WF_EXTRACT_PATTERN", "Regex pattern uses unsafe nested quantifiers.")
        }
        val compiled = runCatching { Regex(pattern) }.getOrNull()
            ?: return error("WF_EXTRACT_PATTERN", "Regex pattern is invalid.")
        val match = compiled.find(source)
            ?: return error("WF_EXTRACT_NO_MATCH", "Regex did not match the source.")
        return valueString(match.value)
    }

    private fun json(source: String, pointer: String, legacy: Boolean = false): WorkflowExtractResult =
        jsonValue(codec.parse(source) as? AgentJsonParseResult.Parsed, pointer, legacy)

    private fun json(value: AgentJsonValue, pointer: String, legacy: Boolean): WorkflowExtractResult =
        jsonValue(AgentJsonParseResult.Parsed(value), pointer, legacy)

    private fun jsonValue(
        parsed: AgentJsonParseResult.Parsed?,
        pointer: String,
        legacy: Boolean
    ): WorkflowExtractResult {
        if (parsed == null) {
            return error(
                if (legacy) "WF_EXTRACT_PATH_INVALID" else "WF_EXTRACT_JSON",
                "JSON source is invalid."
            )
        }
        var current: AgentJsonValue = parsed.value
        for (segment in pointer.split('.').filter { it.isNotBlank() }) {
            current = when (current) {
                is AgentJsonValue.Obj -> current.fields.toMap()[segment]
                    ?: return error(
                        if (legacy) "WF_EXTRACT_PATH_MISSING" else "WF_EXTRACT_JSON_PATH",
                        "JSON path '$pointer' is missing."
                    )
                is AgentJsonValue.Arr -> {
                    val index = segment.toIntOrNull()
                        ?: return error("WF_EXTRACT_JSON_PATH", "JSON array segment must be an index.")
                    current.items.getOrNull(index)
                        ?: return error("WF_EXTRACT_JSON_PATH", "JSON array index is out of range.")
                }
                else -> return error(
                    if (legacy) "WF_EXTRACT_PATH_INVALID" else "WF_EXTRACT_JSON_PATH",
                    "JSON path enters a scalar."
                )
            }
        }
        return when (current) {
            is AgentJsonValue.Str -> valueString(current.value)
            is AgentJsonValue.Num, is AgentJsonValue.Bool -> WorkflowExtractResult.Value(current)
            else -> error("WF_EXTRACT_JSON_PATH", "JSON path must resolve to a scalar.")
        }
    }

    private fun substring(source: String, range: String): WorkflowExtractResult {
        val parts = range.split(',').map { it.trim().toIntOrNull() }
        if (parts.size != 2 || parts.any { it == null }) {
            return error("WF_EXTRACT_RANGE", "SUB path must be 'start,length'.")
        }
        val start = parts[0]!!
        val length = parts[1]!!
        if (start < 0 || length < 0 || start > source.length || start + length > source.length) {
            return error("WF_EXTRACT_RANGE", "SUB range is outside the source.")
        }
        return valueString(source.substring(start, start + length))
    }

    private fun concatenate(source: String, suffix: String): WorkflowExtractResult {
        val combined = source + suffix
        if (combined.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_BYTES) {
            return error("WF_EXTRACT_LIMIT", "Concatenated output is too large.")
        }
        return WorkflowExtractResult.Value(AgentJsonValue.Str(combined))
    }

    private fun randomInt(range: String): WorkflowExtractResult {
        val parts = range.split(',').map { it.trim().toIntOrNull() }
        if (parts.size != 2 || parts.any { it == null }) {
            return error("WF_EXTRACT_RANGE", "RANDOM_INT path must be 'min,max'.")
        }
        val min = parts[0]!!
        val max = parts[1]!!
        if (max < min || (max.toLong() - min.toLong()) > MAX_RANDOM_SPAN) {
            return error("WF_EXTRACT_RANGE", "RANDOM_INT range is invalid or too wide.")
        }
        val value = if (max == min) min else min + random.nextInt(max - min + 1)
        return WorkflowExtractResult.Value(AgentJsonValue.Num(value.toString()))
    }

    private fun randomString(lengthText: String): WorkflowExtractResult {
        val length = lengthText.trim().toIntOrNull()
            ?: return error("WF_EXTRACT_RANGE", "RANDOM_STRING path must be a length.")
        if (length !in 1..MAX_RANDOM_STRING_CHARS) {
            return error("WF_EXTRACT_RANGE", "RANDOM_STRING length is out of range.")
        }
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val builder = StringBuilder(length)
        repeat(length) { builder.append(alphabet[random.nextInt(alphabet.length)]) }
        return WorkflowExtractResult.Value(AgentJsonValue.Str(builder.toString()))
    }

    private fun valueString(value: String): WorkflowExtractResult {
        if (value.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_BYTES) {
            return error("WF_EXTRACT_LIMIT", "Extract output is too large.")
        }
        return WorkflowExtractResult.Value(AgentJsonValue.Str(value))
    }

    private fun error(code: String, message: String): WorkflowExtractResult =
        WorkflowExtractResult.Error(code, message)

    const val MAX_SOURCE_BYTES: Int = 64 * 1024
    const val MAX_OUTPUT_BYTES: Int = 8 * 1024
    const val MAX_PATTERN_CHARS: Int = 512
    const val MAX_REGEX_SOURCE_CHARS: Int = 4_096
    private val RISKY_BACKTRACKING = Regex("""\([^)]*[+*][^)]*\)[+*{]""")
    const val MAX_RANDOM_STRING_CHARS: Int = 256
    const val MAX_RANDOM_SPAN: Long = 1_000_000
}
