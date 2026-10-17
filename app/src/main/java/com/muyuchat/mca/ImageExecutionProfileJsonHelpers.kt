package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject

private val IMAGE_SIZE = Regex("""^(\d+)[xX×](\d+)$""")

internal fun formatError(field: String, message: String, code: String = "PROFILE_FORMAT_INVALID") =
    ImageExecutionProfileJsonException(code, field, message)

internal inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
    enumValues<T>().firstOrNull { candidate -> candidate.name.equals(value.trim(), ignoreCase = true) }
        ?: throw formatError(field, "Unsupported ${T::class.java.simpleName}: $value")

internal inline fun <reified T : Enum<T>> JSONArray.strictEnumList(field: String): List<T> =
    strictStringList(field).mapIndexed { index, value -> enumValue(value, "$field[$index]") }

internal fun JSONObject.requiredStrictString(name: String): String =
    optionalStrictString(name) ?: throw formatError(name, "$name is required and must be a non-blank string.")

internal fun JSONObject.optionalStrictString(name: String, preserveBlank: Boolean = false): String? {
    if (!has(name) || isNull(name)) return null
    val value = get(name)
    if (value !is String) throw formatError(name, "$name must be a string.")
    val normalized = value.trim()
    if (!preserveBlank && normalized.isBlank()) throw formatError(name, "$name must not be blank.")
    return normalized
}

internal fun JSONObject.optionalStrictStringAlias(
    fieldPrefix: String,
    names: List<String>,
    preserveBlank: Boolean = false
): String? {
    val name = firstPresentAlias(names) ?: return null
    val value = get(name)
    if (value !is String) throw formatError("$fieldPrefix.$name", "$name must be a string.")
    val normalized = value.trim()
    if (!preserveBlank && normalized.isBlank()) {
        throw formatError("$fieldPrefix.$name", "$name must not be blank.")
    }
    return normalized
}

internal fun JSONObject.requiredStrictInt(name: String): Int =
    optionalStrictInt(name) ?: throw formatError(name, "$name is required and must be an integer.")

internal fun JSONObject.optionalStrictInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    val value = get(name)
    if (value !is Number) throw formatError(name, "$name must be an integer.")
    val number = value.toDouble()
    if (!number.isFinite() || number % 1.0 != 0.0 || number !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
        throw formatError(name, "$name must be a finite 32-bit integer.")
    }
    return number.toInt()
}

/** A proof envelope is advisory until signature verification; malformed values fail closed. */
internal fun JSONObject.optStrictIntOrZero(name: String): Int {
    val value = opt(name) as? Number ?: return 0
    val number = value.toDouble()
    return if (
        number.isFinite() &&
        number % 1.0 == 0.0 &&
        number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
    ) {
        number.toInt()
    } else {
        0
    }
}

internal fun JSONObject.optionalStrictIntAlias(fieldPrefix: String, names: List<String>): Int? {
    val name = firstPresentAlias(names) ?: return null
    val value = get(name)
    if (value !is Number) throw formatError("$fieldPrefix.$name", "$name must be an integer.")
    val number = value.toDouble()
    if (!number.isFinite() || number % 1.0 != 0.0 || number !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
        throw formatError("$fieldPrefix.$name", "$name must be a finite 32-bit integer.")
    }
    return number.toInt()
}

internal fun JSONObject.requiredStrictLong(name: String): Long =
    optionalStrictLong(name) ?: throw formatError(name, "$name is required and must be an integer.")

internal fun JSONObject.optionalStrictLong(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    val value = get(name)
    if (value !is Number) throw formatError(name, "$name must be an integer.")
    val number = value.toDouble()
    if (!number.isFinite() || number % 1.0 != 0.0 || number !in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
        throw formatError(name, "$name must be a finite integer.")
    }
    return value.toLong()
}

internal fun JSONObject.requiredStrictDouble(name: String): Double =
    optionalStrictDouble(name) ?: throw formatError(name, "$name is required and must be numeric.")

internal fun JSONObject.optionalStrictDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    val value = get(name)
    if (value !is Number) throw formatError(name, "$name must be numeric.")
    return value.toDouble().also { number ->
        if (!number.isFinite()) throw formatError(name, "$name must be finite.")
    }
}

internal fun JSONObject.optionalStrictDoubleAlias(fieldPrefix: String, names: List<String>): Double? {
    val name = firstPresentAlias(names) ?: return null
    val value = get(name)
    if (value !is Number) throw formatError("$fieldPrefix.$name", "$name must be numeric.")
    return value.toDouble().also { number ->
        if (!number.isFinite()) throw formatError("$fieldPrefix.$name", "$name must be finite.")
    }
}

internal fun JSONObject.requiredStrictBoolean(name: String): Boolean =
    optionalStrictBoolean(name) ?: throw formatError(name, "$name is required and must be boolean.")

internal fun JSONObject.optionalStrictBoolean(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    val value = get(name)
    if (value !is Boolean) throw formatError(name, "$name must be boolean.")
    return value
}

internal fun JSONObject.optionalStrictBooleanAlias(fieldPrefix: String, names: List<String>): Boolean? {
    val name = firstPresentAlias(names) ?: return null
    val value = get(name)
    if (value !is Boolean) throw formatError("$fieldPrefix.$name", "$name must be boolean.")
    return value
}

internal fun JSONObject.optionalImageSizeAlias(
    fieldPrefix: String,
    names: List<String>
): Pair<Int, Int>? {
    val name = firstPresentAlias(names) ?: return null
    val field = "$fieldPrefix.$name"
    val value = get(name)
    val dimensions = when (value) {
        is Number -> {
            val number = value.toDouble()
            if (!number.isFinite() || number % 1.0 != 0.0 || number !in 1.0..Int.MAX_VALUE.toDouble()) {
                throw formatError(field, "$name must be a positive integer or WIDTHxHEIGHT string.")
            }
            number.toInt() to number.toInt()
        }
        is String -> {
            val normalized = value.replace(" ", "")
            val match = IMAGE_SIZE.matchEntire(normalized)
            if (match != null) {
                val width = match.groupValues[1].toIntOrNull()
                val height = match.groupValues[2].toIntOrNull()
                if (width == null || height == null || width <= 0 || height <= 0) {
                    throw formatError(field, "$name dimensions must be positive 32-bit integers.")
                }
                width to height
            } else {
                val square = normalized.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: throw formatError(field, "$name must use WIDTHxHEIGHT format or a positive square size.")
                square to square
            }
        }
        else -> throw formatError(field, "$name must be a positive integer or WIDTHxHEIGHT string.")
    }
    return dimensions
}

private fun JSONObject.firstPresentAlias(names: List<String>): String? =
    names.firstOrNull { name -> has(name) && !isNull(name) }

internal fun JSONObject.strictObject(name: String): JSONObject =
    optionalObject(name) ?: throw formatError(name, "$name is required and must be an object.")

internal fun JSONObject.optionalObject(name: String): JSONObject? {
    if (!has(name) || isNull(name)) return null
    return get(name) as? JSONObject ?: throw formatError(name, "$name must be an object.")
}

internal fun JSONObject.optionalArray(name: String): JSONArray? {
    if (!has(name) || isNull(name)) return null
    return get(name) as? JSONArray ?: throw formatError(name, "$name must be an array.")
}

internal fun JSONArray.strictStringList(field: String): List<String> = List(length()) { index ->
    val value = get(index)
    if (value !is String || value.isBlank()) throw formatError("$field[$index]", "Array item must be a non-blank string.")
    value.trim()
}

internal fun JSONArray.strictPositiveIntList(field: String): List<Int> = List(length()) { index ->
    val value = get(index)
    val number = value as? Number ?: throw formatError("$field[$index]", "Shape item must be an integer.")
    val integer = number.toInt()
    if (number.toDouble() != integer.toDouble() || integer <= 0) {
        throw formatError("$field[$index]", "Shape item must be a positive integer.")
    }
    integer
}

internal fun JSONArray.strictObjectList(field: String): List<JSONObject> = List(length()) { index ->
    get(index) as? JSONObject ?: throw formatError("$field[$index]", "Array item must be an object.")
}

internal fun JSONArray.strictArrayList(field: String): List<JSONArray> = List(length()) { index ->
    get(index) as? JSONArray ?: throw formatError("$field[$index]", "Array item must be an array.")
}
