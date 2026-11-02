package com.muyuchat.mca.agent.security

/** Strict parser for the flat V1 tool-argument object (split out of AgentCanonicalJson). */
internal class InvalidArguments(val detail: String) : Exception(detail)

internal class ArgumentsParser(private val text: String) {
        private var index = 0

        fun parseRootObject(): Map<String, AgentArgValue> {
            skipWhitespace()
            expect('{', "Tool arguments must be a JSON object.")
            val fields = LinkedHashMap<String, AgentArgValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                finish()
                return fields
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                if (fields.containsKey(key)) fail("Tool arguments must not contain duplicate keys.")
                skipWhitespace()
                expect(':', "Tool arguments must use ':' between key and value.")
                skipWhitespace()
                fields[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        finish()
                        return fields
                    }
                    else -> fail("Tool arguments object is not terminated correctly.")
                }
            }
        }

        private fun parseValue(): AgentArgValue = when (val char = peek()) {
            '"' -> AgentArgValue.Text(parseString())
            '{', '[' -> fail("Nested JSON values are not valid V1 tool arguments.")
            't' -> {
                expectLiteral("true")
                AgentArgValue.Bool(true)
            }
            'f' -> {
                expectLiteral("false")
                AgentArgValue.Bool(false)
            }
            'n' -> {
                expectLiteral("null")
                AgentArgValue.Null
            }
            '-', in '0'..'9' -> AgentArgValue.Integer(parseInteger())
            else -> fail("Unsupported JSON value in tool arguments.")
        }

        private fun parseString(): String {
            expect('"', "Tool arguments must use JSON strings.")
            val builder = StringBuilder()
            while (index < text.length) {
                val char = text[index]
                when {
                    char == '"' -> {
                        index++
                        return checkedString(builder)
                    }
                    char == '\\' -> {
                        index++
                        if (index >= text.length) fail("Tool argument string escape is truncated.")
                        when (val escape = text[index]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                index++
                                builder.append(parseUnicodeEscape())
                                continue
                            }
                            else -> fail("Tool argument string uses an unsupported escape.")
                        }
                        index++
                    }
                    char < ' ' -> fail("Tool argument string contains an unescaped control character.")
                    char.isHighSurrogate() -> {
                        val next = text.getOrNull(index + 1)
                        if (next == null || !next.isLowSurrogate()) fail("Tool argument string contains an unpaired surrogate.")
                        builder.append(char).append(next)
                        index += 2
                    }
                    char.isLowSurrogate() -> fail("Tool argument string contains an unpaired surrogate.")
                    else -> {
                        builder.append(char)
                        index++
                    }
                }
            }
            fail("Tool argument string is not terminated.")
        }

        private fun checkedString(builder: StringBuilder): String {
            val value = builder.toString()
            var position = 0
            while (position < value.length) {
                val char = value[position]
                when {
                    char.isHighSurrogate() -> {
                        val next = value.getOrNull(position + 1)
                        if (next == null || !next.isLowSurrogate()) fail("Tool argument string contains an unpaired surrogate.")
                        position += 2
                    }
                    char.isLowSurrogate() -> fail("Tool argument string contains an unpaired surrogate.")
                    else -> position++
                }
            }
            return value
        }

        private fun parseUnicodeEscape(): Char {
            if (index + 4 > text.length) fail("Tool argument string unicode escape is truncated.")
            var value = 0
            repeat(4) {
                val digit = Character.digit(text[index], 16)
                if (digit < 0) fail("Tool argument string unicode escape is invalid.")
                value = (value shl 4) or digit
                index++
            }
            return value.toChar()
        }

        private fun parseInteger(): Long {
            val start = index
            if (peek() == '-') index++
            if (index >= text.length || text[index] !in '0'..'9') fail("Tool argument number is not an integer.")
            if (text[index] == '0') {
                index++
            } else {
                while (index < text.length && text[index] in '0'..'9') index++
            }
            val literal = text.substring(start, index)
            val parsed = literal.toLongOrNull() ?: fail("Tool argument integer is out of range.")
            if (index < text.length && text[index] !in charArrayOf(',', '}', ' ', '\t', '\n', '\r')) {
                fail("Tool argument values must be integers; fractions and exponents are not supported.")
            }
            return parsed
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, index)) fail("Tool argument literal is invalid.")
            index += literal.length
        }

        private fun finish() {
            skipWhitespace()
            if (index != text.length) fail("Tool arguments must contain exactly one JSON object.")
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in charArrayOf(' ', '\t', '\n', '\r')) index++
        }

        private fun expect(char: Char, detail: String) {
            if (peek() != char) fail(detail)
            index++
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun fail(detail: String): Nothing = throw InvalidArguments(detail)
    }
