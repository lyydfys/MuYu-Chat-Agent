package com.muyuchat.mca.agent.security

import java.io.ByteArrayOutputStream

/**
 * Strict, canonical JSON used only for the frozen-command bytes.
 *
 * V1 tool arguments are a flat object of string/integer/boolean/null values. This parser exists so
 * the frozen bytes are produced by K07 itself (deterministic across platforms) instead of depending
 * on a platform JSON writer's map ordering. Nested arrays/objects are rejected here because no V1
 * tool schema accepts them; K02's schema validator rejects them for the model earlier. The writer is
 * canonical: keys sorted by unsigned UTF-8 bytes, no insignificant whitespace, shortest deterministic
 * escapes. The exact bytes are hashed once and never produced again.
 */
internal sealed interface AgentArgValue {
    data class Text(val value: String) : AgentArgValue

    data class Integer(val value: Long) : AgentArgValue

    data class Bool(val value: Boolean) : AgentArgValue

    data object Null : AgentArgValue

    data class ListValue(val items: kotlin.collections.List<AgentArgValue>) : AgentArgValue
}

internal sealed interface AgentArgumentsParse {
    data class Parsed(val fields: Map<String, AgentArgValue>) : AgentArgumentsParse

    data class Rejected(val error: AgentSecurityError) : AgentArgumentsParse
}

internal object AgentCanonicalJson {
    const val MAX_ARGUMENT_BYTES: Int = 64 * 1024

    fun parseArguments(text: String): AgentArgumentsParse {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_ARGUMENT_BYTES) {
            return AgentArgumentsParse.Rejected(
                AgentSecurityErrors.invalidArguments("Tool arguments exceed the maximum control-response size.")
            )
        }
        return try {
            AgentArgumentsParse.Parsed(ArgumentsParser(text).parseRootObject())
        } catch (failure: InvalidArguments) {
            AgentArgumentsParse.Rejected(AgentSecurityErrors.invalidArguments(failure.detail))
        }
    }

    /** Canonical JSON object text for the frozen arguments; the map is copied and sorted here. */
    fun writeObject(fields: Map<String, AgentArgValue>): String {
        val builder = StringBuilder()
        writeObject(builder, fields)
        return builder.toString()
    }

    fun preconditionJson(
        taskRoot: String,
        sortedLogicalPaths: kotlin.collections.List<String>,
        expectedOldHash: String?
    ): String = writeObject(
        linkedMapOf(
            "expectedOldHash" to (expectedOldHash?.let(AgentArgValue::Text) ?: AgentArgValue.Null),
            "logicalPaths" to AgentArgValue.ListValue(sortedLogicalPaths.map(AgentArgValue::Text)),
            "taskRoot" to AgentArgValue.Text(taskRoot)
        )
    )

    fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return toHex(digest)
    }

    fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    /** 8-byte big-endian unsigned length prefix, then the UTF-8 bytes (architecture 8.2). */
    fun appendLengthPrefixed(target: ByteArrayOutputStream, bytes: ByteArray) {
        val length = bytes.size.toLong()
        var shift = 56
        while (shift >= 0) {
            target.write(((length ushr shift) and 0xFF).toInt())
            shift -= 8
        }
        target.write(bytes)
    }

    fun appendLengthPrefixed(target: ByteArrayOutputStream, text: String) =
        appendLengthPrefixed(target, text.toByteArray(Charsets.UTF_8))

    private fun writeObject(builder: StringBuilder, fields: Map<String, AgentArgValue>) {
        val sortedKeys = fields.keys.sortedWith(UTF8_KEY_COMPARATOR)
        builder.append('{')
        sortedKeys.forEachIndexed { index, key ->
            if (index > 0) builder.append(',')
            builder.append(quote(key)).append(':')
            writeValue(builder, fields.getValue(key))
        }
        builder.append('}')
    }

    private fun writeValue(builder: StringBuilder, value: AgentArgValue) {
        when (value) {
            is AgentArgValue.Text -> builder.append(quote(value.value))
            is AgentArgValue.Integer -> builder.append(value.value)
            is AgentArgValue.Bool -> builder.append(if (value.value) "true" else "false")
            AgentArgValue.Null -> builder.append("null")
            is AgentArgValue.ListValue -> {
                builder.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) builder.append(',')
                    writeValue(builder, item)
                }
                builder.append(']')
            }
        }
    }

    fun quote(text: String): String {
        val builder = StringBuilder(text.length + 8)
        builder.append('"')
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> when {
                    char < ' ' -> builder.append("\\u00").append(HEX[(char.code ushr 4) and 0x0F]).append(HEX[char.code and 0x0F])
                    char.isHighSurrogate() || char.isLowSurrogate() -> {
                        // Raw surrogates are only valid as a pair; the parser guarantees pairs, but
                        // the writer is defensive and never emits a lone surrogate.
                        val next = text.getOrNull(index + 1)
                        if (char.isHighSurrogate() && next != null && next.isLowSurrogate()) {
                            builder.append(char).append(next)
                            index++
                        } else {
                            throw IllegalArgumentException("lone surrogate in canonical JSON text")
                        }
                    }
                    else -> builder.append(char)
                }
            }
            index++
        }
        builder.append('"')
        return builder.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private val UTF8_KEY_COMPARATOR = Comparator<String> { left, right ->
        val leftBytes = left.toByteArray(Charsets.UTF_8)
        val rightBytes = right.toByteArray(Charsets.UTF_8)
        var index = 0
        while (index < leftBytes.size && index < rightBytes.size) {
            val leftByte = leftBytes[index].toInt() and 0xFF
            val rightByte = rightBytes[index].toInt() and 0xFF
            if (leftByte != rightByte) return@Comparator leftByte - rightByte
            index++
        }
        leftBytes.size - rightBytes.size
    }
}
