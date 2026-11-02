package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import java.security.MessageDigest

/** Strict JSON number grammar shared by the parser, writer and host-side AST builders. */
internal val JSON_NUMBER_LITERAL: Regex =
    Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

/**
 * Small, typed helpers for building host observations. Every non-trivial JSON value in the runtime
 * is assembled with these builders and serialized through [StrictJsonCodec]; no host path may use
 * string interpolation to create protocol JSON.
 */
object AgentJsonValues {
    fun obj(vararg fields: Pair<String, AgentJsonValue>): AgentJsonValue.Obj =
        AgentJsonValue.Obj(fields.toList())

    fun obj(fields: List<Pair<String, AgentJsonValue>>): AgentJsonValue.Obj =
        AgentJsonValue.Obj(fields)

    fun arr(vararg items: AgentJsonValue): AgentJsonValue.Arr =
        AgentJsonValue.Arr(items.toList())

    fun arr(items: List<AgentJsonValue>): AgentJsonValue.Arr = AgentJsonValue.Arr(items)

    fun str(value: String): AgentJsonValue.Str = AgentJsonValue.Str(value)

    /** A number must be provided as an already-valid JSON literal. */
    fun num(literal: String): AgentJsonValue.Num {
        require(JSON_NUMBER_LITERAL.matches(literal)) { "not a valid JSON number literal: '$literal'" }
        return AgentJsonValue.Num(literal)
    }

    fun int(value: Int): AgentJsonValue.Num = AgentJsonValue.Num(value.toString())

    fun long(value: Long): AgentJsonValue.Num = AgentJsonValue.Num(value.toString())

    fun bool(value: Boolean): AgentJsonValue.Bool = AgentJsonValue.Bool(value)

    val nullValue: AgentJsonValue = AgentJsonValue.Null

    /** Sorts object keys recursively so hashes and golden JSON stay deterministic. */
    fun canonical(value: AgentJsonValue): AgentJsonValue = when (value) {
        is AgentJsonValue.Obj -> {
            val names = value.fields.map { it.first }
            require(names.size == names.toSet().size) { "JSON object contains duplicate keys" }
            AgentJsonValue.Obj(
                value.fields
                    .sortedBy { it.first }
                    .map { (name, child) -> name to canonical(child) }
            )
        }
        is AgentJsonValue.Arr -> AgentJsonValue.Arr(value.items.map(::canonical))
        is AgentJsonValue.Str,
        is AgentJsonValue.Num,
        is AgentJsonValue.Bool,
        AgentJsonValue.Null -> value
    }

    fun encode(codec: StrictJsonCodec, value: AgentJsonValue): String = codec.write(canonical(value))

    fun encodeWith(encode: (AgentJsonValue) -> String, value: AgentJsonValue): String = encode(canonical(value))

    /** Walks a parsed AST; returns null for a different shape. */
    fun AgentJsonValue.objectOrNull(): AgentJsonValue.Obj? = this as? AgentJsonValue.Obj

    fun AgentJsonValue.entriesOrEmpty(): List<Pair<String, AgentJsonValue>> =
        (this as? AgentJsonValue.Obj)?.fields.orEmpty()

    fun AgentJsonValue.stringOrNull(): String? = (this as? AgentJsonValue.Str)?.value

    fun AgentJsonValue.arrayOrNull(): List<AgentJsonValue>? = (this as? AgentJsonValue.Arr)?.items

    /** UTF-8 byte length; used for every protocol/schema byte budget. */
    fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    /**
     * Returns the index of the first unpaired surrogate, or -1.
     *
     * A Kotlin String is UTF-16; unpaired surrogates cannot be represented as valid UTF-8 and are
     * rejected instead of being silently replaced by `?` during encoding.
     */
    fun firstUnpairedSurrogate(value: String): Int {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return i
                    i += 2
                }
                c.isLowSurrogate() -> return i
                else -> i += 1
            }
        }
        return -1
    }

    fun isBoundedDepth(value: AgentJsonValue): Boolean = depthOf(value) <= AgentContract.MAX_JSON_DEPTH

    private fun depthOf(value: AgentJsonValue): Int = when (value) {
        is AgentJsonValue.Obj -> 1 + (value.fields.maxOfOrNull { depthOf(it.second) } ?: 0)
        is AgentJsonValue.Arr -> 1 + (value.items.maxOfOrNull { depthOf(it) } ?: 0)
        else -> 1
    }
}

/** SHA-256 helpers shared by canonical schema, argument and call hashes. */
object AgentHashes {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        val hex = "0123456789abcdef"
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            out[index * 2] = hex[value ushr 4]
            out[index * 2 + 1] = hex[value and 0x0f]
        }
        return String(out)
    }

    fun sha256HexUtf8(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
}
