package com.muyuchat.mca

import org.json.JSONObject

/**
 * The image-input data contract: field defaults, clamping bounds and JSON codec.
 *
 * Split out of LocalImageInputContract.kt; declarations moved verbatim. The JSON field
 * readers stay `private` here, so they remain file-scoped and cannot collide with another
 * file's helpers.
 */

data class LocalImagePreparedLora(
    val id: String,
    val name: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val multiplier: Double
) {
    init {
        require(ID_PATTERN.matches(id)) { "LoRA id must be a UUID." }
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS) { "LoRA name is invalid." }
        require(path.isNotBlank() && path.length <= MAX_PATH_CHARS) { "LoRA path is invalid." }
        require(SHA256_PATTERN.matches(sha256)) { "LoRA sha256 is invalid." }
        require(sizeBytes in 16..MAX_LORA_BYTES) { "LoRA size is invalid." }
        require(multiplier.isFinite() &&
            multiplier in MIN_MULTIPLIER..MAX_MULTIPLIER &&
            kotlin.math.abs(multiplier) >= MIN_ABSOLUTE_MULTIPLIER
        ) {
            "LoRA multiplier must be in [-4, -0.01] or [0.01, 4]."
        }
    }

    fun toJson(includePath: Boolean = true): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("sha256", sha256)
        .put("sizeBytes", sizeBytes)
        .put("multiplier", multiplier)
        .apply { if (includePath) put("path", path) }

    companion object {
        const val MAX_COUNT = 8
        const val MIN_MULTIPLIER = -4.0
        const val MAX_MULTIPLIER = 4.0
        const val MIN_ABSOLUTE_MULTIPLIER = 0.01
        private const val MAX_NAME_CHARS = 128
        private const val MAX_PATH_CHARS = 4_096
        private const val MAX_LORA_BYTES = 2L * 1024L * 1024L * 1024L
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val ID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun fromJson(json: JSONObject): LocalImagePreparedLora = LocalImagePreparedLora(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            path = json.requiredString("path"),
            sha256 = json.requiredString("sha256").lowercase(),
            sizeBytes = json.requiredExactLong("sizeBytes"),
            multiplier = json.requiredFiniteDouble("multiplier")
        )
    }
}

data class LocalImageVaeTilingOptions(
    val tileSize: Int,
    val overlap: Double
) {
    init {
        require(tileSize in MIN_TILE_SIZE..MAX_TILE_SIZE && tileSize % 8 == 0) {
            "vaeTiling.tileSize must be an 8-pixel multiple between $MIN_TILE_SIZE and $MAX_TILE_SIZE."
        }
        require(overlap.isFinite() && overlap >= 0.0 && overlap <= 0.5) {
            "vaeTiling.overlap must be a finite ratio in [0, 0.5]."
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("tileSize", tileSize)
        .put("overlap", overlap)

    companion object {
        private const val MIN_TILE_SIZE = 64
        private const val MAX_TILE_SIZE = 4_096

        fun fromJson(json: JSONObject): LocalImageVaeTilingOptions = LocalImageVaeTilingOptions(
            tileSize = json.requiredExactInt("tileSize"),
            overlap = json.requiredFiniteDouble("overlap")
        )
    }
}

data class LocalImagePreviewOptions(
    val interval: Int,
    val mode: LocalImagePreviewMode
) {
    init {
        require(interval in 1..100) { "preview.interval must be between 1 and 100 steps." }
        require(mode != LocalImagePreviewMode.NONE) {
            "Omit preview instead of specifying preview.mode=none."
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("interval", interval)
        .put("mode", mode.wireName)

    companion object {
        fun fromJson(json: JSONObject): LocalImagePreviewOptions = LocalImagePreviewOptions(
            interval = json.requiredExactInt("interval"),
            mode = LocalImagePreviewMode.fromWireName(json.requiredString("mode"))
        )
    }
}

/**
 * A bounded app-private copy ready to cross Binder. No data URL, content URI, or arbitrary path is
 * permitted in this type. The worker copies it once more into its own request directory before
 * native execution.
 */
data class LocalImagePreparedInput(
    val path: String,
    val mimeType: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Encoded pixel dimensions before EXIF orientation is applied. */
    val width: Int,
    val height: Int,
    /** Logical dimensions seen by native decoders after EXIF orientation is applied. */
    val orientedWidth: Int = width,
    val orientedHeight: Int = height,
    val exifOrientation: Int = 1
) {
    init {
        require(path.isNotBlank()) { "Prepared image input path must not be blank." }
        require(mimeType.startsWith("image/") && mimeType.length <= 128) {
            "Prepared image input MIME type must use image/*."
        }
        require(SHA256_PATTERN.matches(sha256)) {
            "Prepared image input sha256 must be a lowercase 64-character digest."
        }
        require(sizeBytes in 1..MAX_INPUT_BYTES) {
            "Prepared image input size must be between 1 byte and $MAX_INPUT_BYTES bytes."
        }
        require(width in 1..MAX_IMAGE_SIDE && height in 1..MAX_IMAGE_SIDE) {
            "Prepared image input dimensions must be between 1 and $MAX_IMAGE_SIDE pixels per side."
        }
        require(width.toLong() * height.toLong() <= MAX_IMAGE_PIXELS) {
            "Prepared image input exceeds the $MAX_IMAGE_PIXELS pixel limit."
        }
        require(orientedWidth in 1..MAX_IMAGE_SIDE && orientedHeight in 1..MAX_IMAGE_SIDE &&
            orientedWidth.toLong() * orientedHeight.toLong() <= MAX_IMAGE_PIXELS
        ) { "Prepared image input oriented dimensions are invalid." }
        require(exifOrientation in 1..8) {
            "Prepared image input EXIF orientation must be between 1 and 8."
        }
        require(
            (orientedWidth to orientedHeight) ==
                localImageOrientedDimensions(width, height, exifOrientation)
        ) { "Prepared image input dimensions do not match its EXIF orientation." }
    }

    fun toJson(includePath: Boolean = true): JSONObject = JSONObject()
        .apply { if (includePath) put("path", path) }
        .put("mimeType", mimeType)
        .put("sha256", sha256)
        .put("sizeBytes", sizeBytes)
        .put("width", width)
        .put("height", height)
        .put("orientedWidth", orientedWidth)
        .put("orientedHeight", orientedHeight)
        .put("exifOrientation", exifOrientation)

    companion object {
        const val MAX_INPUT_BYTES: Long = 32L * 1024L * 1024L
        const val MAX_IMAGE_SIDE: Int = 8_192
        const val MAX_IMAGE_PIXELS: Long = 64L * 1024L * 1024L
        private val SHA256_PATTERN = Regex("[a-f0-9]{64}")

        fun fromJson(json: JSONObject): LocalImagePreparedInput {
            val width = json.requiredExactInt("width")
            val height = json.requiredExactInt("height")
            val exifOrientation = if (json.has("exifOrientation")) {
                json.requiredExactInt("exifOrientation")
            } else {
                1
            }
            val defaultOriented = localImageOrientedDimensions(width, height, exifOrientation)
            return LocalImagePreparedInput(
                path = json.requiredString("path"),
                mimeType = json.requiredString("mimeType"),
                sha256 = json.requiredString("sha256").lowercase(),
                sizeBytes = json.requiredExactLong("sizeBytes"),
                width = width,
                height = height,
                orientedWidth = if (json.has("orientedWidth")) {
                    json.requiredExactInt("orientedWidth")
                } else defaultOriented.first,
                orientedHeight = if (json.has("orientedHeight")) {
                    json.requiredExactInt("orientedHeight")
                } else defaultOriented.second,
                exifOrientation = exifOrientation
            )
        }
    }
}

private fun JSONObject.requiredString(name: String): String {
    require(has(name) && !isNull(name)) { "$name is required." }
    val raw = get(name)
    require(raw is String) { "$name must be a string." }
    return raw.trim().also { require(it.isNotEmpty()) { "$name must not be blank." } }
}

private fun JSONObject.requiredExactInt(name: String): Int {
    val value = requiredExactLong(name)
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "$name must fit a 32-bit integer."
    }
    return value.toInt()
}

private fun JSONObject.requiredExactLong(name: String): Long {
    require(has(name) && !isNull(name)) { "$name is required." }
    val raw = get(name)
    require(raw is Number) { "$name must be an integer." }
    val doubleValue = raw.toDouble()
    require(doubleValue.isFinite() && doubleValue % 1.0 == 0.0) { "$name must be an integer." }
    val longValue = raw.toLong()
    require(longValue.toDouble() == doubleValue) { "$name must be an exact integer." }
    return longValue
}

private fun JSONObject.requiredFiniteDouble(name: String): Double {
    require(has(name) && !isNull(name)) { "$name is required." }
    val raw = get(name)
    require(raw is Number) { "$name must be numeric." }
    return raw.toDouble().also { value -> require(value.isFinite()) { "$name must be finite." } }
}
