package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes diagnostic payloads as RFC 8259 JSON.
 *
 * Native diagnostics can legitimately contain non-finite floating point values
 * while investigating a failed graph. JSON has no representation for those
 * values, so persist them as null instead of leaking NaN/Infinity tokens into
 * a smoke artifact that host tooling cannot parse.
 */
internal fun strictJsonForPersistence(value: Any?, indentSpaces: Int = 0): String =
    strictJsonValue(value, indentSpaces.coerceAtLeast(0), 0)

private fun strictJsonValue(value: Any?, indentSpaces: Int, depth: Int): String =
    when (value) {
        null,
        JSONObject.NULL -> "null"
        is JSONObject -> strictJsonObject(value, indentSpaces, depth)
        is JSONArray -> strictJsonArray(value, indentSpaces, depth)
        is Map<*, *> -> strictJsonMap(value, indentSpaces, depth)
        is Iterable<*> -> strictJsonIterable(value.asSequence(), indentSpaces, depth)
        is Array<*> -> strictJsonIterable(value.asSequence(), indentSpaces, depth)
        is Boolean -> value.toString()
        is Number -> strictJsonNumber(value)
        else -> JSONObject.quote(value.toString())
    }

private fun strictJsonObject(value: JSONObject, indentSpaces: Int, depth: Int): String {
    val entries = buildList {
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            add(JSONObject.quote(key) to strictJsonValue(value.opt(key), indentSpaces, depth + 1))
        }
    }
    return strictJsonEntries(entries, indentSpaces, depth)
}

private fun strictJsonMap(value: Map<*, *>, indentSpaces: Int, depth: Int): String =
    strictJsonEntries(
        value.entries.map { entry ->
            JSONObject.quote(entry.key?.toString().orEmpty()) to
                strictJsonValue(entry.value, indentSpaces, depth + 1)
        },
        indentSpaces,
        depth
    )

private fun strictJsonEntries(
    entries: List<Pair<String, String>>,
    indentSpaces: Int,
    depth: Int
): String {
    if (entries.isEmpty()) return "{}"
    if (indentSpaces == 0) {
        return entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, encoded) ->
            "$key:$encoded"
        }
    }
    val childIndent = " ".repeat((depth + 1) * indentSpaces)
    val currentIndent = " ".repeat(depth * indentSpaces)
    return entries.joinToString(
        prefix = "{\n$childIndent",
        postfix = "\n$currentIndent}",
        separator = ",\n$childIndent"
    ) { (key, encoded) -> "$key: $encoded" }
}

private fun strictJsonArray(value: JSONArray, indentSpaces: Int, depth: Int): String =
    strictJsonIterable(
        (0 until value.length()).asSequence().map(value::opt),
        indentSpaces,
        depth
    )

private fun strictJsonIterable(
    values: Sequence<*>,
    indentSpaces: Int,
    depth: Int
): String {
    val encoded = values.map { strictJsonValue(it, indentSpaces, depth + 1) }.toList()
    if (encoded.isEmpty()) return "[]"
    if (indentSpaces == 0) return encoded.joinToString(prefix = "[", postfix = "]", separator = ",")
    val childIndent = " ".repeat((depth + 1) * indentSpaces)
    val currentIndent = " ".repeat(depth * indentSpaces)
    return encoded.joinToString(
        prefix = "[\n$childIndent",
        postfix = "\n$currentIndent]",
        separator = ",\n$childIndent"
    )
}

private fun strictJsonNumber(value: Number): String =
    when (value) {
        is Double -> value.takeIf(Double::isFinite)?.toString() ?: "null"
        is Float -> value.takeIf(Float::isFinite)?.toString() ?: "null"
        else -> value.toString()
    }
