package com.muyuchat.api.local

import com.muyuchat.core.engine.compareUtf8Unsigned
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Request/response evidence validation for the image-generation contract.
 *
 * Moved verbatim out of [ImageGenerationApiContract], which had grown past 3.9k lines. This
 * file owns one responsibility: deciding whether a parsed image-generation request or a native
 * provider response is acceptable, and which error code and message a rejection must carry.
 * Field names, limits, check order, error codes and messages are unchanged -- this is the
 * outward HTTP/SSE contract, so any edit here is a protocol change.
 *
 * The prefixed top-level helpers below are the JSON field accessors and numeric comparisons
 * shared with [ImageGenerationApiContract]; their bodies are unchanged and they only gained a
 * responsibility prefix, because a new top-level name in this package must not collide.
 */
internal fun JSONObject.imageGenerationApiOptionalString(name: String, allowEmpty: Boolean): String? {
    if (!has(name)) return null
    if (isNull(name)) reject("invalid_$name", "$name must be a string when specified.")
    val raw = get(name)
    if (raw !is String) reject("invalid_$name", "$name must be a string when specified.")
    val value = raw.trim()
    if (!allowEmpty && value.isEmpty()) {
        reject("invalid_$name", "$name must not be empty when specified.")
    }
    return value
}

internal fun JSONObject.imageGenerationApiRequiredNonBlankString(name: String): String =
    imageGenerationApiOptionalString(name, allowEmpty = false)
        ?: reject(
            "invalid_image_execution_evidence",
            "Image execution field $name is required and must be a non-empty string."
        )

internal fun JSONObject.imageGenerationApiRejectUnknownFields(allowed: Set<String>, objectName: String) {
    val unknown = keys().asSequence().filterNot(allowed::contains).toList().sorted()
    if (unknown.isNotEmpty()) {
        reject(
            "unknown_image_request_field",
            "$objectName contains unsupported field(s): ${unknown.joinToString(", ")}"
        )
    }
}

internal fun JSONObject.imageGenerationApiRequiredBoolean(name: String): Boolean {
    if (!has(name) || isNull(name) || get(name) !is Boolean) {
        reject(
            "invalid_image_execution_evidence",
            "Image execution field $name is required and must be a boolean."
        )
    }
    return getBoolean(name)
}

internal fun JSONObject.imageGenerationApiRequiredInteger(name: String, minimum: Long): Long {
    if (!has(name) || isNull(name)) {
        reject("invalid_image_execution_evidence", "Image execution field $name is required.")
    }
    val raw = get(name)
    if (raw !is Number) {
        reject("invalid_image_execution_evidence", "Image execution field $name must be an integer.")
    }
    val longValue = raw.imageGenerationApiToExactLongOrNull()
    if (longValue == null || longValue < minimum) {
        reject(
            "invalid_image_execution_evidence",
            "Image execution field $name must be an integer no smaller than $minimum."
        )
    }
    return longValue
}

internal fun Number.imageGenerationApiToExactLongOrNull(): Long? = runCatching {
    // BigInteger#longValueExact() requires API 31 while this module ships minSdk 26, so on an
    // API 26-30 device the old call would have thrown NoSuchMethodError instead of returning null.
    // bitLength()/toLong() are available since API 1 and keep the contract identical:
    // a non-integral value still throws ArithmeticException from toBigIntegerExact(), and a value
    // that does not fit a signed 64-bit range has bitLength() >= 64 and therefore yields null,
    // exactly as longValueExact() would have thrown ArithmeticException.
    val big = BigDecimal(toString()).toBigIntegerExact()
    if (big.bitLength() < 64) big.toLong() else null
}.getOrNull()

internal fun JSONObject.imageGenerationApiRequiredPositiveLong(name: String): Long =
    imageGenerationApiRequiredInteger(name, minimum = 1L)

internal fun JSONObject.imageGenerationApiRequiredFiniteNumber(name: String): Double {
    if (!has(name) || isNull(name) || get(name) !is Number) {
        reject("invalid_image_execution_evidence", "Image execution field $name must be numeric.")
    }
    val value = getDouble(name)
    if (!value.isFinite()) {
        reject("invalid_image_execution_evidence", "Image execution field $name must be finite.")
    }
    return value
}

internal fun JSONObject.imageGenerationApiRequiredPositiveNumber(name: String): Double =
    imageGenerationApiRequiredFiniteNumber(name).also { value ->
        if (value <= 0.0) {
            reject("invalid_image_execution_evidence", "Image execution field $name must be positive.")
        }
    }

internal fun imageGenerationApiNumbersMatch(first: Double, second: Double): Boolean {
    if (!first.isFinite() || !second.isFinite()) return false
    val scale = maxOf(1.0, kotlin.math.abs(first), kotlin.math.abs(second))
    // Native bridges commonly round request doubles through C float fields.
    return kotlin.math.abs(first - second) <= 1e-6 * scale
}

internal fun imageGenerationApiCanonicalSampler(raw: String): String {
    val normalized = raw.trim().lowercase()
        .replace(" ", "")
        .replace("-", "_")
    return when (normalized) {
        "euler" -> "euler"
        "euler_a", "euler_ancestral", "euler_ancestral_discrete" -> "euler_a"
        "ddim" -> "ddim"
        "pndm", "plms", "pndm_plms" -> "pndm"
        "dpm++2m", "dpmpp2m", "dpmpp_2m", "dpm_plus_plus_2m" -> "dpmpp_2m"
        "lcm" -> "lcm"
        "flow", "flowmatch", "flow_match" -> "flow_match"
        else -> normalized
    }
}
