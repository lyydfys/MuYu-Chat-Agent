package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineMinDeviceTier
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/**
 * T2B22-T5（b 工位）拆片：manifest 严格读取与路径安全（strictOptional* / strictRequired* /
 * requireSafeImageRuntimePath / optImageEngineMinDeviceTier / manifestImageSize）自
 * LocalImageProviderBundleManifest.kt 逐字节平移；声明行登记为 internal。
 */

internal fun JSONObject.strictOptionalObject(field: String): JSONObject? {
    if (!has(field) || isNull(field)) return null
    return opt(field) as? JSONObject
        ?: throw IllegalArgumentException("Image bundle manifest $field must be an object.")
}

internal fun JSONObject.strictOptionalArray(field: String): JSONArray? {
    if (!has(field) || isNull(field)) return null
    return opt(field) as? JSONArray
        ?: throw IllegalArgumentException("Image bundle manifest $field must be an array.")
}

internal fun JSONObject.strictRequiredString(field: String): String {
    val value = opt(field)
    return (value as? String)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("Image bundle manifest $field must be a non-empty string.")
}

internal fun JSONObject.strictOptionalNonBlankString(field: String): String? {
    if (!has(field) || isNull(field)) return null
    val value = opt(field)
    require(value is String && value.isNotBlank()) {
        "Image bundle manifest $field must be a non-empty string when present."
    }
    return value.trim()
}

internal fun JSONObject.strictRequiredPositiveInt(field: String): Int {
    val value = opt(field)
    return (value as? Number)
        ?.let { number ->
            val longValue = runCatching { BigDecimal(number.toString()).longValueExact() }
                .getOrNull()
            longValue?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        }
        ?: throw IllegalArgumentException(
            "Image bundle manifest $field must be a positive integer."
        )
}

internal fun JSONObject.strictOptionalBoolean(field: String, default: Boolean): Boolean {
    if (!has(field) || isNull(field)) return default
    return opt(field) as? Boolean
        ?: throw IllegalArgumentException("Image bundle manifest $field must be a boolean.")
}

internal fun requireSafeImageRuntimePath(path: String, field: String) {
    val normalized = path.trim().replace('\\', '/')
    val unsafe = normalized.isBlank() ||
        normalized.startsWith('/') ||
        Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
        normalized.split('/').any { segment ->
            segment.isBlank() || segment == "." || segment == ".."
        }
    require(!unsafe) { "Image bundle manifest $field escapes the bundle root: $path" }
}
internal fun JSONObject.optImageEngineMinDeviceTier(): ImageEngineMinDeviceTier {
    val value = optString("minDeviceTier").takeIf { it.isNotBlank() } ?: return ImageEngineMinDeviceTier.ANY
    return runCatching { ImageEngineMinDeviceTier.valueOf(value) }.getOrDefault(ImageEngineMinDeviceTier.ANY)
}

internal fun JSONObject.manifestImageSize(): String? {
    val direct = optString("imageSize").takeIf { it.isNotBlank() }
        ?: optString("size").takeIf { it.isNotBlank() }
    if (direct != null) return direct
    val smoke = optJSONObject("smoke") ?: optJSONObject("smokeSpec")
    val width = smoke?.optInt("width", 0)?.takeIf { it > 0 }
        ?: optInt("width", 0).takeIf { it > 0 }
    val height = smoke?.optInt("height", 0)?.takeIf { it > 0 }
        ?: optInt("height", 0).takeIf { it > 0 }
    return if (width != null && height != null) "${width}x${height}" else null
}
