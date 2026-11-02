package com.muyuchat.feature.agent.workspace

import java.util.Locale

/**
 * Bounded reader for the structured part of an Agent event summary.
 *
 * P0 tool-lifecycle producers write a flat JSON object (or `key=value` lines) into the already
 * bounded `AgentEvent.summary` field. This file owns only syntax: it never interprets permissions,
 * never reads raw model text and never renders a provider document. Unknown keys stay unknown so a
 * caller can decide whether they are safe to show.
 *
 * The reader deliberately does not support nested objects/arrays. A malformed JSON-looking summary
 * is reported as [AgentSummaryDocument.malformedJson] so the UI can stay honest instead of dumping
 * an unparseable blob that might contain raw arguments.
 */

internal const val MAX_STRUCTURED_SUMMARY_CHARS = 4_096

internal data class AgentSummaryDocument(
    val fields: Map<String, List<String>>,
    val rawText: String,
    val malformedJson: Boolean = false
) {
    fun first(key: String): String? = fields[key]
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun values(key: String): List<String> = fields[key].orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun has(key: String): Boolean = fields.containsKey(key)

    val structured: Boolean get() = fields.isNotEmpty()
}

internal fun parseAgentSummaryDocument(raw: String?): AgentSummaryDocument {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return AgentSummaryDocument(emptyMap(), "")
    val bounded = if (text.length <= MAX_STRUCTURED_SUMMARY_CHARS) {
        text
    } else {
        text.take(MAX_STRUCTURED_SUMMARY_CHARS)
    }
    if (bounded.startsWith("{")) {
        val json = runCatching { FlatJsonReader(bounded).readObject() }.getOrNull()
        if (json != null) {
            return AgentSummaryDocument(
                fields = normalizeFields(json),
                rawText = bounded
            )
        }
        return AgentSummaryDocument(
            fields = emptyMap(),
            rawText = bounded,
            malformedJson = true
        )
    }
    val lines = parseKeyValueLines(bounded)
    return AgentSummaryDocument(
        fields = normalizeFields(lines),
        rawText = bounded
    )
}

private fun normalizeFields(fields: Map<String, List<String>>): Map<String, List<String>> {
    if (fields.isEmpty()) return emptyMap()
    val normalized = LinkedHashMap<String, MutableList<String>>(fields.size)
    fields.forEach { (key, values) ->
        val normalizedKey = normalizeSummaryKey(key)
        normalized.getOrPut(normalizedKey) { mutableListOf() }.addAll(values)
    }
    return normalized
}

/**
 * One canonical key vocabulary for approval requests and tool-lifecycle summaries. Keeping a
 * single normalizer prevents the approval sheet and the timeline from disagreeing about `source`.
 */
internal fun normalizeSummaryKey(rawKey: String): String {
    val key = rawKey.trim().lowercase(Locale.ROOT)
        .replace('-', '_')
        .replace(" ", "")
    return when (key) {
        "tool", "toolkey", "tool_key", "name", "call" -> "tool"
        "action", "operation", "method" -> "action"
        "read", "reads", "readtarget", "readtargets", "read_target", "read_targets" -> "read"
        "write", "writes", "writetarget", "writetargets", "write_target", "write_targets" -> "write"
        "network", "networktarget", "networktargets", "network_target", "network_targets",
        "destination", "destinations", "host", "hosts", "url", "urls", "origin",
        "origins" -> "network"
        "arg", "args", "argument", "arguments", "argumentssummary", "arguments_summary",
        "parameter", "parameters", "input" -> "args"
        "path", "file", "targetpath", "target_path" -> "path"
        "old", "oldhash", "old_hash", "beforehash", "before_hash",
        "expectedoldhash", "expected_old_hash" -> "old"
        "new", "newhash", "new_hash", "afterhash", "after_hash" -> "new"
        "source", "sourcelabel", "source_label", "tool_source", "toolsource",
        "origin_source", "provenance" -> "source"
        "state", "status", "decision", "outcome", "result_state", "resultstate" -> "state"
        "summary", "message", "result_summary", "resultsummary", "detail",
        "description" -> "summary"
        // `result` is ambiguous: it may be a state token or a bounded result sentence. Callers
        // inspect the value with a state parser before treating it as text.
        "result", "result_text", "resulttext" -> "result"
        "principal", "identity", "bound_identity", "boundidentity", "user" -> "principal"
        "invocation", "invocationid", "invocation_id", "callid", "call_id" -> "invocation"
        "approval", "approvalid", "approval_id" -> "approval"
        "expiry", "expires", "expiresat", "expires_at", "expiration" -> "expiry"
        else -> key
    }
}

private fun parseKeyValueLines(text: String): Map<String, List<String>> {
    val fields = LinkedHashMap<String, MutableList<String>>()
    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val separator = line.indexOfFirst { it == '=' || it == ':' || it == '：' }
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        fields.getOrPut(key) { mutableListOf() } += value
    }
    return fields
}

internal fun splitSummaryTargets(values: List<String>): List<String> =
    values.flatMap { value ->
        value.split('\n', ',').map { it.trim() }
    }.filter { it.isNotEmpty() }

/**
 * Minimal, bounded reader for a flat JSON object whose values are strings, string arrays or
 * scalar literals. Nested objects/arrays are rejected so a provider-shaped document cannot be
 * mistaken for a bounded UI summary.
 */
internal class FlatJsonReader(private val text: String) {
    private var index = 0

    fun readObject(): Map<String, List<String>> {
        skipWhitespace()
        expect('{')
        val result = LinkedHashMap<String, List<String>>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return result
        }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            val values = if (peek() == '[') readStringArray() else listOf(readScalar())
            result[key] = values
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return result
                }
                else -> error("unexpected token")
            }
        }
    }

    private fun readStringArray(): List<String> {
        expect('[')
        val values = mutableListOf<String>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return values
        }
        while (true) {
            values += readScalar()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return values
                }
                else -> error("unexpected token")
            }
        }
    }

    private fun readScalar(): String {
        skipWhitespace()
        return if (peek() == '"') readString() else readLiteral()
    }

    private fun readString(): String {
        expect('"')
        val builder = StringBuilder()
        while (index < text.length) {
            when (val char = text[index++]) {
                '"' -> return builder.toString()
                '\\' -> {
                    if (index >= text.length) error("truncated escape")
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> builder.append(escaped)
                        'n' -> builder.append('\n')
                        't' -> builder.append('\t')
                        'r' -> builder.append('\r')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'u' -> {
                            if (index + 4 > text.length) error("truncated unicode escape")
                            builder.append(text.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> error("unsupported escape")
                    }
                }
                else -> builder.append(char)
            }
        }
        error("unterminated string")
    }

    private fun readLiteral(): String {
        val start = index
        while (index < text.length && !text[index].isWhitespace() &&
            text[index] != ',' && text[index] != '}' && text[index] != ']'
        ) {
            index++
        }
        if (start == index) error("expected value")
        return text.substring(start, index)
    }

    private fun peek(): Char? = text.getOrNull(index)

    private fun expect(char: Char) {
        if (peek() != char) error("expected '$char'")
        index++
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
