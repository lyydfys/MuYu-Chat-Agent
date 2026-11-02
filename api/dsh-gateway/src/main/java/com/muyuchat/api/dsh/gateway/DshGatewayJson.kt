package com.muyuchat.api.dsh.gateway

import org.json.JSONArray
import org.json.JSONObject

/**
 * Small JSON helpers shared by the request mapper and the wire encoders.
 *
 * `org.json` is the platform JSON implementation used by the rest of MCA's protocol adapters.
 * The canonical writer below is deterministic (object keys sorted, stable number rendering), so
 * a tool parameter schema is serialized identically regardless of the map implementation behind
 * `JSONObject` and a catalog revision is reproducible from the same catalog.
 *
 * `org.json`'s tokener also accepts unquoted strings, so protocol boundaries that must carry
 * strict JSON (tool arguments and tool parameter schemas) use the small recursive validator
 * below rather than trusting that leniency.
 */
internal object DshGatewayJson {

    fun parseObject(text: String): JSONObject? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    fun parseArray(text: String): JSONArray? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[")) return null
        return runCatching { JSONArray(trimmed) }.getOrNull()
    }

    /** True when [text] is exactly one strict RFC 8259 JSON value. */
    fun isSingleJsonValue(text: String): Boolean = isStrictJson(text)

    /** True when [text] is one strict JSON object (tool arguments and parameter schemas). */
    fun isStrictJsonObject(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("{") && isStrictJson(trimmed)
    }

    fun isStrictJson(text: String): Boolean {
        if (text.isBlank()) return false
        return runCatching { StrictJsonValidator(text).validateDocument() }.isSuccess
    }

    /** Deterministic compact JSON for [value]; safe for tool schemas and revision hashing. */
    fun canonical(value: Any?): String = buildString { appendValue(this, value) }

    private fun appendValue(out: StringBuilder, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> out.append("null")
            is JSONObject -> appendObject(out, value)
            is JSONArray -> appendArray(out, value)
            is Map<*, *> -> appendMap(out, value)
            is Iterable<*> -> {
                out.append('[')
                var first = true
                value.forEach { item ->
                    if (!first) out.append(',')
                    first = false
                    appendValue(out, item)
                }
                out.append(']')
            }
            is Array<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    appendValue(out, item)
                }
                out.append(']')
            }
            is String -> appendQuoted(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Number -> out.append(numberText(value))
            else -> appendQuoted(out, value.toString())
        }
    }

    private fun appendObject(out: StringBuilder, value: JSONObject) {
        val keys = buildList {
            val iterator = value.keys()
            while (iterator.hasNext()) add(iterator.next())
        }.sorted()
        out.append('{')
        keys.forEachIndexed { index, key ->
            if (index > 0) out.append(',')
            appendQuoted(out, key)
            out.append(':')
            appendValue(out, value.opt(key))
        }
        out.append('}')
    }

    private fun appendMap(out: StringBuilder, value: Map<*, *>) {
        val entries = value.entries
            .map { entry -> entry.key?.toString().orEmpty() to entry.value }
            .sortedBy { it.first }
        out.append('{')
        entries.forEachIndexed { index, (key, item) ->
            if (index > 0) out.append(',')
            appendQuoted(out, key)
            out.append(':')
            appendValue(out, item)
        }
        out.append('}')
    }

    private fun appendArray(out: StringBuilder, value: JSONArray) {
        out.append('[')
        for (index in 0 until value.length()) {
            if (index > 0) out.append(',')
            appendValue(out, value.opt(index))
        }
        out.append(']')
    }

    private fun numberText(value: Number): String = when (value) {
        is Double -> {
            require(value.isFinite()) { "JSON cannot represent a non-finite double." }
            if (value == 0.0) "0" else value.toString()
        }
        is Float -> {
            require(value.isFinite()) { "JSON cannot represent a non-finite float." }
            if (value == 0.0f) "0" else value.toString()
        }
        else -> value.toString()
    }

    private fun appendQuoted(out: StringBuilder, text: String) {
        out.append('"')
        text.forEach { character ->
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (character < ' ') {
                    out.append("\\u")
                    out.append(character.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(character)
                }
            }
        }
        out.append('"')
    }
}

/**
 * Minimal strict RFC 8259 validator. It only decides validity; values are still parsed by
 * `org.json` after validation, so no second JSON object model is introduced.
 */
private class StrictJsonValidator(private val text: String) {

    private var position = 0

    fun validateDocument() {
        skipWhitespace()
        parseValue(depth = 0)
        skipWhitespace()
        check(position == text.length) { "Trailing characters after the JSON value." }
    }

    private fun parseValue(depth: Int) {
        check(depth <= MAX_DEPTH) { "JSON nesting is too deep." }
        skipWhitespace()
        check(position < text.length) { "Missing JSON value." }
        when (text[position]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> parseString()
            't' -> expectLiteral("true")
            'f' -> expectLiteral("false")
            'n' -> expectLiteral("null")
            else -> {
                val character = text[position]
                check(character == '-' || character in '0'..'9') { "Invalid JSON value." }
                parseNumber()
            }
        }
    }

    private fun parseObject(depth: Int) {
        position++ // '{'
        skipWhitespace()
        if (peek() == '}') {
            position++
            return
        }
        while (true) {
            skipWhitespace()
            check(peek() == '"') { "Object keys must be strings." }
            parseString()
            skipWhitespace()
            expect(':')
            parseValue(depth)
            skipWhitespace()
            when (peek()) {
                ',' -> position++
                '}' -> {
                    position++
                    return
                }
                else -> error("Expected ',' or '}'.")
            }
        }
    }

    private fun parseArray(depth: Int) {
        position++ // '['
        skipWhitespace()
        if (peek() == ']') {
            position++
            return
        }
        while (true) {
            parseValue(depth)
            skipWhitespace()
            when (peek()) {
                ',' -> position++
                ']' -> {
                    position++
                    return
                }
                else -> error("Expected ',' or ']'.")
            }
        }
    }

    private fun parseString() {
        expect('"')
        while (true) {
            check(position < text.length) { "Unterminated JSON string." }
            val character = text[position++]
            when {
                character == '"' -> return
                character == '\\' -> {
                    check(position < text.length) { "Unterminated JSON escape." }
                    when (val escape = text[position++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            check(position < text.length) { "Unterminated unicode escape." }
                            check(text[position++].isHexDigit()) { "Invalid unicode escape." }
                        }
                        else -> error("Invalid JSON escape: $escape")
                    }
                }
                character < ' ' -> error("Unescaped control character in JSON string.")
                else -> Unit
            }
        }
    }

    private fun parseNumber() {
        if (peek() == '-') position++
        check(position < text.length) { "Missing JSON number." }
        if (peek() == '0') {
            position++
        } else {
            val first = peek()
            check(first != null && first in '1'..'9') { "Invalid JSON number." }
            while (position < text.length && text[position] in '0'..'9') position++
        }
        if (position < text.length && text[position] == '.') {
            position++
            check(position < text.length && text[position] in '0'..'9') { "Invalid JSON fraction." }
            while (position < text.length && text[position] in '0'..'9') position++
        }
        if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
            position++
            if (position < text.length && (text[position] == '+' || text[position] == '-')) {
                position++
            }
            check(position < text.length && text[position] in '0'..'9') { "Invalid JSON exponent." }
            while (position < text.length && text[position] in '0'..'9') position++
        }
    }

    private fun expectLiteral(literal: String) {
        check(text.startsWith(literal, position)) { "Invalid JSON literal." }
        position += literal.length
    }

    private fun expect(character: Char) {
        check(peek() == character) { "Expected '$character'." }
        position++
    }

    private fun peek(): Char? = if (position < text.length) text[position] else null

    private fun skipWhitespace() {
        while (position < text.length) {
            when (text[position]) {
                ' ', '\t', '\n', '\r' -> position++
                else -> return
            }
        }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private companion object {
        const val MAX_DEPTH = 128
    }
}

