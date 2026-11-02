package com.muyuchat.mca.agent.extensions.toolpkg

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Strict JSON value model for `mca-tool-package.json` and the private preview-store records.
 *
 * It is deliberately smaller than a general JSON library: objects preserve insertion order for
 * diagnostics, duplicate keys are rejected, numbers are integers only (the manifest and record
 * schema need no floating point), and strings are decoded strictly (valid UTF-8, escaped control
 * characters, surrogate pairs). The canonical writer sorts object keys by unsigned UTF-8 bytes so
 * the manifest hash is stable across platforms and independent of the parser's map order.
 */
internal sealed interface ToolPackageJsonValue {
    data class ObjectValue(val fields: Map<String, ToolPackageJsonValue>) : ToolPackageJsonValue

    data class ArrayValue(val items: List<ToolPackageJsonValue>) : ToolPackageJsonValue

    data class StringValue(val value: String) : ToolPackageJsonValue

    data class IntegerValue(val value: Long) : ToolPackageJsonValue

    data class BooleanValue(val value: Boolean) : ToolPackageJsonValue

    data object NullValue : ToolPackageJsonValue
}

internal object ToolPackageJson {
    private const val MAX_DEPTH = 32

    fun parse(text: String): ToolPackageJsonValue = StrictJsonReader(text).readDocument()

    fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            toolPackageFailure(
                AgentToolPackageErrorCode.MANIFEST_INVALID_UTF8,
                "manifest is not valid UTF-8"
            )
        }
        if (text.startsWith('\uFEFF')) {
            toolPackageFailure(
                AgentToolPackageErrorCode.MANIFEST_MALFORMED,
                "a UTF-8 byte-order mark is not accepted"
            )
        }
        return text
    }

    fun parseUtf8(bytes: ByteArray): ToolPackageJsonValue = parse(decodeUtf8(bytes))

    fun canonical(value: ToolPackageJsonValue): String {
        val builder = StringBuilder(256)
        appendCanonical(builder, value)
        return builder.toString()
    }

    fun canonicalHash(value: ToolPackageJsonValue): String = sha256Hex(canonical(value).toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return toHex(digest)
    }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    fun objectOf(vararg fields: Pair<String, ToolPackageJsonValue>): ToolPackageJsonValue.ObjectValue =
        ToolPackageJsonValue.ObjectValue(linkedMapOf(*fields))

    fun string(value: String): ToolPackageJsonValue.StringValue = ToolPackageJsonValue.StringValue(value)

    fun integer(value: Long): ToolPackageJsonValue.IntegerValue = ToolPackageJsonValue.IntegerValue(value)

    fun arrayOf(items: List<ToolPackageJsonValue>): ToolPackageJsonValue.ArrayValue =
        ToolPackageJsonValue.ArrayValue(items)

    private fun appendCanonical(builder: StringBuilder, value: ToolPackageJsonValue) {
        when (value) {
            is ToolPackageJsonValue.ObjectValue -> {
                val keys = value.fields.keys.sortedWith(UTF8_KEY_COMPARATOR)
                builder.append('{')
                keys.forEachIndexed { index, key ->
                    if (index > 0) builder.append(',')
                    appendQuoted(builder, key)
                    builder.append(':')
                    appendCanonical(builder, value.fields.getValue(key))
                }
                builder.append('}')
            }
            is ToolPackageJsonValue.ArrayValue -> {
                builder.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) builder.append(',')
                    appendCanonical(builder, item)
                }
                builder.append(']')
            }
            is ToolPackageJsonValue.StringValue -> appendQuoted(builder, value.value)
            is ToolPackageJsonValue.IntegerValue -> builder.append(value.value)
            is ToolPackageJsonValue.BooleanValue -> builder.append(if (value.value) "true" else "false")
            ToolPackageJsonValue.NullValue -> builder.append("null")
        }
    }

    private fun appendQuoted(builder: StringBuilder, text: String) {
        builder.append('"')
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' -> builder.append("\\\"")
                char == '\\' -> builder.append("\\\\")
                char == '\b' -> builder.append("\\b")
                char == '\u000C' -> builder.append("\\f")
                char == '\n' -> builder.append("\\n")
                char == '\r' -> builder.append("\\r")
                char == '\t' -> builder.append("\\t")
                char < ' ' -> builder
                    .append("\\u00")
                    .append(HEX[(char.code ushr 4) and 0x0F])
                    .append(HEX[char.code and 0x0F])
                char in HIGH_SURROGATE_RANGE -> {
                    val low = text.getOrNull(index + 1)
                    if (low == null || low !in LOW_SURROGATE_RANGE) {
                        throw IllegalArgumentException("canonical JSON text contains a lone surrogate")
                    }
                    builder.append(char).append(low)
                    index += 1
                }
                char in LOW_SURROGATE_RANGE ->
                    throw IllegalArgumentException("canonical JSON text contains a lone surrogate")
                else -> builder.append(char)
            }
            index += 1
        }
        builder.append('"')
    }

    private val HEX = "0123456789abcdef".toCharArray()
    private val HIGH_SURROGATE_RANGE = '\uD800'..'\uDBFF'
    private val LOW_SURROGATE_RANGE = '\uDC00'..'\uDFFF'

    private val UTF8_KEY_COMPARATOR = Comparator<String> { left, right ->
        val leftBytes = left.toByteArray(Charsets.UTF_8)
        val rightBytes = right.toByteArray(Charsets.UTF_8)
        var index = 0
        while (index < leftBytes.size && index < rightBytes.size) {
            val leftByte = leftBytes[index].toInt() and 0xFF
            val rightByte = rightBytes[index].toInt() and 0xFF
            if (leftByte != rightByte) return@Comparator leftByte - rightByte
            index += 1
        }
        leftBytes.size - rightBytes.size
    }

    private class StrictJsonReader(private val text: String) {
        private var index = 0

        fun readDocument(): ToolPackageJsonValue {
            skipWhitespace()
            if (index >= text.length) malformed("the document is empty")
            val value = readValue(depth = 0)
            skipWhitespace()
            if (index != text.length) malformed("trailing bytes after the root value")
            return value
        }

        private fun readValue(depth: Int): ToolPackageJsonValue {
            if (depth > MAX_DEPTH) malformed("JSON nesting exceeds $MAX_DEPTH")
            skipWhitespace()
            if (index >= text.length) malformed("unexpected end of the document")
            return when (val char = text[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> ToolPackageJsonValue.StringValue(readString())
                't' -> readLiteral("true", ToolPackageJsonValue.BooleanValue(true))
                'f' -> readLiteral("false", ToolPackageJsonValue.BooleanValue(false))
                'n' -> readLiteral("null", ToolPackageJsonValue.NullValue)
                else -> if (char == '-' || char in '0'..'9') {
                    ToolPackageJsonValue.IntegerValue(readInteger())
                } else {
                    malformed("unexpected character at the current position")
                }
            }
        }

        private fun readObject(depth: Int): ToolPackageJsonValue.ObjectValue {
            expect('{')
            val fields = LinkedHashMap<String, ToolPackageJsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index += 1
                return ToolPackageJsonValue.ObjectValue(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') malformed("object keys must be JSON strings")
                val key = readString()
                if (fields.containsKey(key)) {
                    malformed(
                        "duplicate object key at the current position",
                        AgentToolPackageErrorCode.MANIFEST_DUPLICATE_KEY
                    )
                }
                skipWhitespace()
                expect(':')
                val value = readValue(depth + 1)
                fields[key] = value
                skipWhitespace()
                when (val next = peek()) {
                    ',' -> index += 1
                    '}' -> {
                        index += 1
                        return ToolPackageJsonValue.ObjectValue(fields)
                    }
                    else -> malformed("expected ',' or '}' in the object")
                }
            }
        }

        private fun readArray(depth: Int): ToolPackageJsonValue.ArrayValue {
            expect('[')
            val items = ArrayList<ToolPackageJsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index += 1
                return ToolPackageJsonValue.ArrayValue(items)
            }
            while (true) {
                items += readValue(depth + 1)
                skipWhitespace()
                when (val next = peek()) {
                    ',' -> index += 1
                    ']' -> {
                        index += 1
                        return ToolPackageJsonValue.ArrayValue(items)
                    }
                    else -> malformed("expected ',' or ']' in the array")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (index >= text.length) malformed("unterminated string")
                val char = text[index]
                index += 1
                when {
                    char == '"' -> return builder.toString()
                    char == '\\' -> {
                        if (index >= text.length) malformed("unterminated string escape")
                        when (val escaped = text[index]) {
                            '"' -> {
                                index += 1
                                builder.append('"')
                            }
                            '\\' -> {
                                index += 1
                                builder.append('\\')
                            }
                            '/' -> {
                                index += 1
                                builder.append('/')
                            }
                            'b' -> {
                                index += 1
                                builder.append('\b')
                            }
                            'f' -> {
                                index += 1
                                builder.append('\u000C')
                            }
                            'n' -> {
                                index += 1
                                builder.append('\n')
                            }
                            'r' -> {
                                index += 1
                                builder.append('\r')
                            }
                            't' -> {
                                index += 1
                                builder.append('\t')
                            }
                            'u' -> {
                                index += 1
                                builder.append(readUnicodeEscape())
                            }
                            else -> malformed("unsupported string escape")
                        }
                    }
                    char < ' ' -> malformed("unescaped control character in a string")
                    char in HIGH_SURROGATE_RANGE -> {
                        // Raw UTF-16 pairs can only arrive from valid UTF-8 decoding, but the
                        // reader still refuses a lone high surrogate.
                        val low = text.getOrNull(index)
                        if (low == null || low !in LOW_SURROGATE_RANGE) {
                            malformed("string contains a lone surrogate")
                        }
                        builder.append(char).append(low)
                        index += 1
                    }
                    char in LOW_SURROGATE_RANGE -> malformed("string contains a lone surrogate")
                    else -> builder.append(char)
                }
            }
        }

        /** Reads the four hex digits after `\u`; caller already consumed `u`. */
        private fun readUnicodeEscape(): String {
            val first = readHex4()
            val firstChar = first.toChar()
            return when {
                firstChar in HIGH_SURROGATE_RANGE -> {
                    if (index + 1 >= text.length || text[index] != '\\' || text[index + 1] != 'u') {
                        malformed("high surrogate is not followed by a low surrogate")
                    }
                    index += 2
                    val secondChar = readHex4().toChar()
                    if (secondChar !in LOW_SURROGATE_RANGE) {
                        malformed("high surrogate is not followed by a low surrogate")
                    }
                    buildString(2) {
                        append(firstChar)
                        append(secondChar)
                    }
                }
                firstChar in LOW_SURROGATE_RANGE -> malformed("string contains a lone low surrogate")
                else -> firstChar.toString()
            }
        }

        private fun readHex4(): Int {
            if (index + 4 > text.length) malformed("truncated unicode escape")
            var value = 0
            repeat(4) {
                val digit = hexValue(text[index])
                    ?: malformed("invalid unicode escape")
                value = (value shl 4) or digit
                index += 1
            }
            return value
        }

        private fun hexValue(char: Char): Int? = when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> null
        }

        private fun readInteger(): Long {
            val start = index
            if (peek() == '-') index += 1
            if (index >= text.length) malformed("truncated number")
            when (val first = text[index]) {
                '0' -> {
                    index += 1
                    if (index < text.length && text[index] in '0'..'9') {
                        malformed("a number must not have a leading zero")
                    }
                }
                in '1'..'9' -> {
                    index += 1
                    while (index < text.length && text[index] in '0'..'9') index += 1
                }
                else -> malformed("invalid number")
            }
            if (index < text.length && (text[index] == '.' || text[index] == 'e' || text[index] == 'E')) {
                malformed("only integer numbers are supported in a tool package")
            }
            val raw = text.substring(start, index)
            return raw.toLongOrNull() ?: malformed("integer is outside the supported range")
        }

        private fun readLiteral(expected: String, value: ToolPackageJsonValue): ToolPackageJsonValue {
            if (index + expected.length > text.length ||
                text.regionMatches(index, expected, 0, expected.length).not()
            ) {
                malformed("invalid literal")
            }
            index += expected.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index += 1
                    else -> return
                }
            }
        }

        private fun expect(char: Char) {
            if (peek() != char) malformed("expected '$char' at the current position")
            index += 1
        }

        private fun peek(): Char = text.getOrNull(index) ?: malformed("unexpected end of the document")

        private fun malformed(
            detail: String,
            code: AgentToolPackageErrorCode = AgentToolPackageErrorCode.MANIFEST_MALFORMED
        ): Nothing {
            val line = text.take(index.coerceIn(0, text.length)).count { it == '\n' } + 1
            toolPackageFailure(code, detail, line = line)
        }
    }
}
