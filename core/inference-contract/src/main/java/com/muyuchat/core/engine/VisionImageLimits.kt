package com.muyuchat.core.engine

/**
 * Keep image payloads bounded before Base64 decoding or a native decoder can
 * allocate a second full copy. The same byte budget is used for inline,
 * downloaded, and local-file inputs so the API and UI paths cannot drift.
 */
const val MAX_LOCAL_VISION_IMAGE_BYTES: Long = 20L * 1024L * 1024L

/** Maximum decoded width or height accepted by the shared vision preflight. */
const val MAX_LOCAL_VISION_IMAGE_SIDE: Int = 16_384

/** Maximum decoded pixel count accepted before handing an image to native code. */
const val MAX_LOCAL_VISION_IMAGE_PIXELS: Long = 32L * 1024L * 1024L

/** Backwards-compatible name used by the inline Base64 estimator. */
const val MAX_LOCAL_VISION_INLINE_IMAGE_BYTES: Long = MAX_LOCAL_VISION_IMAGE_BYTES

/** Estimate decoded Base64 bytes without allocating a normalized copy of the payload. */
fun estimatedInlineVisionImageBytes(encoded: CharSequence): Long {
    var symbols = 0L
    var last = '\u0000'
    var previous = '\u0000'
    for (character in encoded) {
        if (character.isWhitespace()) continue
        symbols += 1L
        previous = last
        last = character
    }
    val padding = when {
        symbols >= 2L && last == '=' && previous == '=' -> 2L
        symbols >= 1L && last == '=' -> 1L
        else -> 0L
    }
    return ((symbols * 3L) / 4L - padding).coerceAtLeast(0L)
}

fun inlineVisionImageWithinLimit(
    encoded: CharSequence,
    maxBytes: Long = MAX_LOCAL_VISION_INLINE_IMAGE_BYTES
): Boolean = maxBytes >= 0L && estimatedInlineVisionImageBytes(encoded) <= maxBytes