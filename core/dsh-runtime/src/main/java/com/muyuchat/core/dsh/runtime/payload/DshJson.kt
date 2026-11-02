package com.muyuchat.core.dsh.runtime.payload

/**
 * Deliberately small JSON reader for the payload manifest and marker.
 *
 * The module must parse a machine-readable manifest on the host with no extra dependency,
 * and the same code must run in JVM unit tests (Android's `org.json` is stubbed there).
 * Supported grammar is the JSON subset the staging script emits: objects, arrays, strings
 * with `\" \\ \/ \b \f \n \r \t \uXXXX` escapes, numbers, `true`, `false`, `null`.
 * Anything else fails closed with [DshJsonException].
 */
object DshJson {

    class DshJsonException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) throw DshJsonException("trailing content at index ${reader.index}")
        return value
    }

    private class Reader(private val text: String) {
        var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw DshJsonException("unexpected end of input")
            return when (val c = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    if (c == '-' || c in '0'..'9') readNumber()
                    else throw DshJsonException("unexpected character '$c' at index $index")
                }
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, index)) throw DshJsonException("invalid literal at index $index")
            index += literal.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                out[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { index++; continue }
                    '}' -> { index++; return out }
                    else -> throw DshJsonException("expected ',' or '}' at index $index")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return out
            }
            while (true) {
                out.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> { index++; continue }
                    ']' -> { index++; return out }
                    else -> throw DshJsonException("expected ',' or ']' at index $index")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw DshJsonException("unterminated string")
                val c = text[index++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw DshJsonException("unterminated escape")
                        when (val e = text[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw DshJsonException("truncated \\u escape")
                                val hex = text.substring(index, index + 4)
                                val code = hex.toIntOrNull(16) ?: throw DshJsonException("invalid \\u escape: $hex")
                                sb.append(code.toChar())
                                index += 4
                            }
                            else -> throw DshJsonException("invalid escape '\\$e'")
                        }
                    }
                    c.code < 0x20 -> throw DshJsonException("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Number {
            val start = index
            if (peek() == '-') index++
            while (!atEnd() && text[index].isDigit()) index++
            var fractional = false
            if (!atEnd() && text[index] == '.') {
                fractional = true
                index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                fractional = true
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            if (raw.isEmpty() || raw == "-") throw DshJsonException("invalid number at index $start")
            return if (fractional) raw.toDoubleOrNull() ?: throw DshJsonException("invalid number: $raw")
            else raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw DshJsonException("invalid number: $raw")
        }

        private fun peek(): Char? = if (atEnd()) null else text[index]

        private fun expect(c: Char) {
            if (atEnd() || text[index] != c) throw DshJsonException("expected '$c' at index $index")
            index++
        }
    }

    // ------------------------------------------------------------- typed accessors

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?, what: String): Map<String, Any?> {
        if (value !is Map<*, *>) throw DshJsonException("$what must be a JSON object")
        return value as Map<String, Any?>
    }

    fun asArray(value: Any?, what: String): List<Any?> {
        if (value !is List<*>) throw DshJsonException("$what must be a JSON array")
        return value
    }

    fun asString(map: Map<String, Any?>, key: String, what: String): String {
        val v = map[key] ?: throw DshJsonException("$what is missing \"$key\"")
        return v as? String ?: throw DshJsonException("$what.$key must be a string")
    }

    fun optString(map: Map<String, Any?>, key: String): String? = map[key] as? String

    fun asLong(map: Map<String, Any?>, key: String, what: String): Long {
        val v = map[key] ?: throw DshJsonException("$what is missing \"$key\"")
        return when (v) {
            is Long -> v
            is Double -> v.toLong()
            is Int -> v.toLong()
            else -> throw DshJsonException("$what.$key must be a number")
        }
    }

    fun optLong(map: Map<String, Any?>, key: String): Long? = when (val v = map[key]) {
        is Long -> v
        is Double -> v.toLong()
        is Int -> v.toLong()
        else -> null
    }

    fun asInt(map: Map<String, Any?>, key: String, what: String): Int =
        asLong(map, key, what).let {
            if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) throw DshJsonException("$what.$key out of int range")
            it.toInt()
        }

    /** Serializes a tree produced by [parse] (objects, arrays, strings, numbers, booleans, null). */
    fun write(value: Any?): String = StringBuilder().also { writeInto(it, value) }.toString()

    private fun writeInto(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Double -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k as? String ?: k.toString())
                    sb.append(':')
                    writeInto(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                for ((i, v) in value.withIndex()) {
                    if (i > 0) sb.append(',')
                    writeInto(sb, v)
                }
                sb.append(']')
            }
            else -> throw DshJsonException("cannot serialize ${value.javaClass.name}")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
