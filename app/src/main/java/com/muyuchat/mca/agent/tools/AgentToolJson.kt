package com.muyuchat.mca.agent.tools

import com.muyuchat.mca.agent.security.AgentCanonicalJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical JSON writer for the K08 built-in tool layer.
 *
 * Parsing of tool arguments is deliberately *not* duplicated here: the only argument text an
 * executor sees is [com.muyuchat.core.agent.contract.FrozenToolCommand.normalizedArgumentsJson],
 * already parsed and canonicalised by the K07 strict reader (`AgentCanonicalJson`). This object
 * therefore only writes JSON and hashes it. Object keys are sorted by UTF-8 bytes so a descriptor
 * schema hash and a result document do not depend on a platform `JSONObject` map order
 * (architecture 8.2 / 9.1).
 */
internal object AgentToolJson {
    fun sha256Hex(bytes: ByteArray): String = AgentCanonicalJson.sha256Hex(bytes)

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    fun utf8Size(value: JSONObject): Int = utf8Size(canonical(value))

    /** RFC 8259 text with sorted object keys and no insignificant whitespace. */
    fun canonical(value: Any?): String {
        val builder = StringBuilder(256)
        append(builder, value)
        return builder.toString()
    }

    private fun append(builder: StringBuilder, value: Any?) {
        if (value == null || value == JSONObject.NULL) {
            builder.append("null")
            return
        }
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList().sorted()
                builder.append('{')
                keys.forEachIndexed { index, key ->
                    if (index > 0) builder.append(',')
                    builder.append(AgentCanonicalJson.quote(key))
                    builder.append(':')
                    append(builder, value.opt(key))
                }
                builder.append('}')
            }
            is JSONArray -> {
                builder.append('[')
                for (index in 0 until value.length()) {
                    if (index > 0) builder.append(',')
                    append(builder, value.opt(index))
                }
                builder.append(']')
            }
            is String -> builder.append(AgentCanonicalJson.quote(value))
            is Boolean -> builder.append(if (value) "true" else "false")
            is Int -> builder.append(value)
            is Long -> builder.append(value)
            is Double -> {
                require(value.isFinite()) { "JSON result contains a non-finite double" }
                builder.append(value)
            }
            is Float -> {
                require(value.isFinite()) { "JSON result contains a non-finite float" }
                builder.append(value)
            }
            is Number -> builder.append(value.toString())
            else -> throw IllegalArgumentException("unsupported JSON value type: ${value::class.java.name}")
        }
    }
}
