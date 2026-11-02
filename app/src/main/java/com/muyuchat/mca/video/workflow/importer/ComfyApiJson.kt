package com.muyuchat.mca.video.workflow.importer

/**
 * Strict, dependency-free JSON reader for ComfyUI API-format imports.
 *
 * Deliberately not `org.json`: app JVM tests receive an Android stub, and the import boundary
 * needs predictable limits and exact decimal values. Numbers are kept as (unscaled, scale)
 * pairs so ids/seeds never pass through a float; scale is capped at 9 and unscaled must fit in
 * a Long. Duplicate keys, trailing content, control characters, non-finite/out-of-range numbers
 * and unterminated containers are all rejected with a path.
 */
sealed class ComfyJsonValue {
    data class ObjectValue(val entries: Map<String, ComfyJsonValue>) : ComfyJsonValue() {
        operator fun get(key: String): ComfyJsonValue? = entries[key]

        fun contains(key: String): Boolean = entries.containsKey(key)
    }

    data class ArrayValue(val values: List<ComfyJsonValue>) : ComfyJsonValue()

    data class StringValue(val value: String) : ComfyJsonValue()

    /** Exact decimal: value = unscaled * 10^(-scale); trailing zeros are stripped. */
    data class NumberValue(val unscaled: Long, val scale: Int) : ComfyJsonValue() {
        init {
            require(scale in 0..MAX_SCALE) { "decimal scale out of range: $scale" }
        }
    }

    data class BooleanValue(val value: Boolean) : ComfyJsonValue()

    data object NullValue : ComfyJsonValue()
}

const val COMFY_JSON_MAX_SCALE = 9
private const val MAX_SCALE = COMFY_JSON_MAX_SCALE

enum class ComfyJsonErrorReason {
    EMPTY,
    INPUT_TOO_LARGE,
    DEPTH_LIMIT,
    STRING_LIMIT,
    DUPLICATE_KEY,
    NUMBER_OUT_OF_RANGE,
    SYNTAX,
    TRAILING_CONTENT,
}

class ComfyJsonException(
    val reason: ComfyJsonErrorReason,
    val offset: Int,
    val path: String,
    detail: String,
) : IllegalArgumentException(detail)

object ComfyApiJson {
    const val MAX_INPUT_BYTES = 4 * 1024 * 1024
    const val MAX_DEPTH = 64
    const val MAX_STRING_BYTES = 64 * 1024

    fun parse(text: String): ComfyJsonValue {
        if (text.length > MAX_INPUT_BYTES) {
            throw ComfyJsonException(
                reason = ComfyJsonErrorReason.INPUT_TOO_LARGE,
                offset = 0,
                path = "$",
                detail = "JSON input exceeds $MAX_INPUT_BYTES bytes",
            )
        }
        val byteLength = text.toByteArray(Charsets.UTF_8).size
        if (byteLength > MAX_INPUT_BYTES) {
            throw ComfyJsonException(
                reason = ComfyJsonErrorReason.INPUT_TOO_LARGE,
                offset = 0,
                path = "$",
                detail = "JSON input is $byteLength bytes; limit is $MAX_INPUT_BYTES",
            )
        }
        if (text.isBlank()) {
            throw ComfyJsonException(
                reason = ComfyJsonErrorReason.EMPTY,
                offset = 0,
                path = "$",
                detail = "JSON input is empty",
            )
        }
        return StrictJsonParser(text).parse()
    }
}

private class StrictJsonParser(private val text: String) {

    private var index = 0
    private var depth = 0

    fun parse(): ComfyJsonValue {
        skipWhitespace()
        if (index >= text.length) fail(ComfyJsonErrorReason.EMPTY, "$", "JSON input is empty")
        val root = parseValue("$")
        skipWhitespace()
        if (index < text.length) {
            fail(ComfyJsonErrorReason.TRAILING_CONTENT, "$", "unexpected trailing content")
        }
        return root
    }

    private fun parseValue(path: String): ComfyJsonValue {
        if (index >= text.length) fail(ComfyJsonErrorReason.SYNTAX, path, "unexpected end of input")
        return when (val ch = text[index]) {
            '{' -> parseObject(path)
            '[' -> parseArray(path)
            '"' -> ComfyJsonValue.StringValue(parseString(path))
            't' -> parseKeyword("true", ComfyJsonValue.BooleanValue(true), path)
            'f' -> parseKeyword("false", ComfyJsonValue.BooleanValue(false), path)
            'n' -> parseKeyword("null", ComfyJsonValue.NullValue, path)
            '-', in '0'..'9' -> parseNumber(path)
            else -> fail(ComfyJsonErrorReason.SYNTAX, path, "unexpected character '${printable(ch)}'")
        }
    }

    private fun parseObject(path: String): ComfyJsonValue.ObjectValue {
        enterContainer(path)
        index++
        val entries = LinkedHashMap<String, ComfyJsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            depth--
            return ComfyJsonValue.ObjectValue(entries)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail(ComfyJsonErrorReason.SYNTAX, path, "expected an object key")
            val key = parseString(path)
            val childPath = childObjectPath(path, key)
            if (entries.containsKey(key)) {
                fail(ComfyJsonErrorReason.DUPLICATE_KEY, childPath, "duplicate object key '${preview(key)}'")
            }
            skipWhitespace()
            if (peek() != ':') fail(ComfyJsonErrorReason.SYNTAX, childPath, "expected ':'")
            index++
            skipWhitespace()
            entries[key] = parseValue(childPath)
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    index++
                    skipWhitespace()
                    if (peek() == '}') fail(ComfyJsonErrorReason.SYNTAX, path, "trailing comma in object")
                }

                '}' -> {
                    index++
                    depth--
                    return ComfyJsonValue.ObjectValue(entries)
                }

                null -> fail(ComfyJsonErrorReason.SYNTAX, path, "unterminated object")
                else -> fail(ComfyJsonErrorReason.SYNTAX, path, "expected ',' or '}'")
            }
        }
    }

    private fun parseArray(path: String): ComfyJsonValue.ArrayValue {
        enterContainer(path)
        index++
        val values = ArrayList<ComfyJsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            depth--
            return ComfyJsonValue.ArrayValue(values)
        }
        while (true) {
            skipWhitespace()
            values += parseValue("$path[${values.size}]")
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    index++
                    skipWhitespace()
                    if (peek() == ']') fail(ComfyJsonErrorReason.SYNTAX, path, "trailing comma in array")
                }

                ']' -> {
                    index++
                    depth--
                    return ComfyJsonValue.ArrayValue(values)
                }

                null -> fail(ComfyJsonErrorReason.SYNTAX, path, "unterminated array")
                else -> fail(ComfyJsonErrorReason.SYNTAX, path, "expected ',' or ']'")
            }
        }
    }

    private fun parseString(path: String): String {
        if (peek() != '"') fail(ComfyJsonErrorReason.SYNTAX, path, "expected a string")
        index++
        val builder = StringBuilder()
        while (index < text.length) {
            val ch = text[index++]
            when {
                ch == '"' -> {
                    val value = builder.toString()
                    if (value.toByteArray(Charsets.UTF_8).size > ComfyApiJson.MAX_STRING_BYTES) {
                        fail(
                            ComfyJsonErrorReason.STRING_LIMIT,
                            path,
                            "string exceeds ${ComfyApiJson.MAX_STRING_BYTES} bytes",
                        )
                    }
                    return value
                }

                ch == '\\' -> {
                    if (index >= text.length) fail(ComfyJsonErrorReason.SYNTAX, path, "unterminated escape")
                    when (val escaped = text[index++]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) {
                                fail(ComfyJsonErrorReason.SYNTAX, path, "truncated \\u escape")
                            }
                            var code = 0
                            repeat(4) {
                                val hex = text[index++]
                                val digit = when (hex) {
                                    in '0'..'9' -> hex - '0'
                                    in 'a'..'f' -> hex - 'a' + 10
                                    in 'A'..'F' -> hex - 'A' + 10
                                    else -> fail(ComfyJsonErrorReason.SYNTAX, path, "invalid \\u escape")
                                }
                                code = code * 16 + digit
                            }
                            builder.append(code.toChar())
                        }

                        else -> fail(
                            ComfyJsonErrorReason.SYNTAX,
                            path,
                            "invalid escape '\\${printable(escaped)}'",
                        )
                    }
                }

                ch < ' ' -> fail(ComfyJsonErrorReason.SYNTAX, path, "unescaped control character in string")
                else -> builder.append(ch)
            }
            if (builder.length > ComfyApiJson.MAX_STRING_BYTES) {
                fail(
                    ComfyJsonErrorReason.STRING_LIMIT,
                    path,
                    "string exceeds ${ComfyApiJson.MAX_STRING_BYTES} bytes",
                )
            }
        }
        fail(ComfyJsonErrorReason.SYNTAX, path, "unterminated string")
    }

    private fun parseNumber(path: String): ComfyJsonValue.NumberValue {
        val negative = peek() == '-'
        if (negative) index++
        if (index >= text.length || text[index] !in '0'..'9') {
            fail(ComfyJsonErrorReason.SYNTAX, path, "expected a digit")
        }

        val intStart = index
        if (text[index] == '0') {
            index++
            if (index < text.length && text[index] in '0'..'9') {
                fail(ComfyJsonErrorReason.SYNTAX, path, "leading zero in number")
            }
        } else {
            while (index < text.length && text[index] in '0'..'9') index++
        }
        val intEnd = index

        var fractionStart = index
        var fractionEnd = index
        if (index < text.length && text[index] == '.') {
            index++
            fractionStart = index
            while (index < text.length && text[index] in '0'..'9') index++
            if (index == fractionStart) fail(ComfyJsonErrorReason.SYNTAX, path, "expected fraction digits")
            fractionEnd = index
        }

        var exponent = 0
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            var exponentNegative = false
            if (index < text.length && (text[index] == '+' || text[index] == '-')) {
                exponentNegative = text[index] == '-'
                index++
            }
            val exponentStart = index
            while (index < text.length && text[index] in '0'..'9') index++
            if (index == exponentStart) fail(ComfyJsonErrorReason.SYNTAX, path, "expected exponent digits")
            val exponentText = text.substring(exponentStart, index)
            val exponentDigits = exponentText.trimStart('0').ifEmpty { "0" }
            if (exponentDigits.length > 6) {
                fail(ComfyJsonErrorReason.NUMBER_OUT_OF_RANGE, path, "number exponent is too large")
            }
            exponent = exponentDigits.toInt().let { if (exponentNegative) -it else it }
        }

        if (index < text.length && !isNumberTerminator(text[index])) {
            fail(ComfyJsonErrorReason.SYNTAX, path, "invalid character after number")
        }

        val digits = text.substring(intStart, intEnd) + text.substring(fractionStart, fractionEnd)
        val significant = digits.trimStart('0')
        if (significant.isEmpty()) return ComfyJsonValue.NumberValue(0, 0)
        var scale = (fractionEnd - fractionStart) - exponent

        if (scale < 0) {
            val zeros = -scale
            if (significant.length + zeros > 19) {
                fail(ComfyJsonErrorReason.NUMBER_OUT_OF_RANGE, path, "number is outside the Long range")
            }
            return makeNumber(significant + "0".repeat(zeros), 0, negative, path)
        }

        var normalized = significant
        while (scale > 0 && normalized.length > 1 && normalized.last() == '0') {
            normalized = normalized.dropLast(1)
            scale--
        }
        if (scale > COMFY_JSON_MAX_SCALE) {
            fail(
                ComfyJsonErrorReason.NUMBER_OUT_OF_RANGE,
                path,
                "number needs more than $COMFY_JSON_MAX_SCALE decimal places",
            )
        }
        return makeNumber(normalized, scale, negative, path)
    }

    private fun makeNumber(
        digits: String,
        scale: Int,
        negative: Boolean,
        path: String,
    ): ComfyJsonValue.NumberValue {
        if (digits.length > 19) {
            fail(ComfyJsonErrorReason.NUMBER_OUT_OF_RANGE, path, "number is outside the Long range")
        }
        val magnitude = digits.toLongOrNull()
            ?: fail(ComfyJsonErrorReason.NUMBER_OUT_OF_RANGE, path, "number is outside the Long range")
        return ComfyJsonValue.NumberValue(if (negative) -magnitude else magnitude, scale)
    }

    private fun parseKeyword(word: String, value: ComfyJsonValue, path: String): ComfyJsonValue {
        if (!text.startsWith(word, index)) {
            fail(ComfyJsonErrorReason.SYNTAX, path, "invalid literal")
        }
        index += word.length
        if (index < text.length && !isValueTerminator(text[index])) {
            fail(ComfyJsonErrorReason.SYNTAX, path, "invalid character after literal")
        }
        return value
    }

    private fun enterContainer(path: String) {
        depth++
        if (depth > ComfyApiJson.MAX_DEPTH) {
            fail(
                ComfyJsonErrorReason.DEPTH_LIMIT,
                path,
                "JSON nesting exceeds depth ${ComfyApiJson.MAX_DEPTH}",
            )
        }
    }

    private fun skipWhitespace() {
        while (index < text.length) {
            when (text[index]) {
                ' ', '\t', '\n', '\r' -> index++
                else -> return
            }
        }
    }

    private fun peek(): Char? = if (index < text.length) text[index] else null

    private fun isNumberTerminator(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == ',' || ch == '}' || ch == ']'

    private fun isValueTerminator(ch: Char): Boolean = isNumberTerminator(ch)

    private fun childObjectPath(parent: String, key: String): String {
        val simple = key.length <= 64 && key.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        return if (simple) "$parent.$key" else "$parent['${preview(key)}']"
    }

    private fun preview(value: String): String =
        if (value.length <= 64) value else value.take(64) + "..."

    private fun printable(ch: Char): String =
        if (ch.code in 0x20..0x7e) ch.toString() else "\\u${ch.code.toString(16).padStart(4, '0')}"

    private fun fail(reason: ComfyJsonErrorReason, path: String, detail: String): Nothing {
        throw ComfyJsonException(
            reason = reason,
            offset = index,
            path = path,
            detail = "$detail (offset=$index, path=$path)",
        )
    }
}
