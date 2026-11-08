package com.muyuchat.mca.agent.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Turns one screenshot into a `data:` URL a vision model can read.
 *
 * This is the *conversion* half of the screen tool's visual fallback: when the accessibility tree
 * carries no readable control (self-drawn UI, games, Flutter/Unity surfaces) the agent still needs
 * to see the pixels. Nothing here calls a model, writes state or touches the accessibility lane — it
 * only decodes, downsamples and re-encodes, so it stays a pure, testable boundary.
 *
 * Failure is always `null`, never an exception: a screenshot the codec cannot read is a missing
 * observation, and the caller decides what to tell the model. Three inputs are rejected up front —
 * empty bytes, a payload over [MAX_SOURCE_BYTES], and anything the platform decoder refuses.
 */
internal fun encodeScreenshotForModel(
    bytes: ByteArray,
    maxSidePx: Int = DEFAULT_SCREENSHOT_MAX_SIDE_PX,
    quality: Int = DEFAULT_SCREENSHOT_JPEG_QUALITY
): String? {
    if (bytes.isEmpty() || bytes.size > MAX_SOURCE_BYTES) return null
    val boundedQuality = quality.coerceIn(1, 100)
    val boundedSide = maxSidePx.coerceAtLeast(1)
    return try {
        encodeDecodedScreenshot(bytes, boundedSide, boundedQuality)
    } catch (error: Exception) {
        null
    } catch (error: OutOfMemoryError) {
        // A hostile or enormous bitmap must degrade to "no observation", never crash the agent run.
        null
    }
}

private fun encodeDecodedScreenshot(bytes: ByteArray, maxSidePx: Int, quality: Int): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sourceWidth = bounds.outWidth
    val sourceHeight = bounds.outHeight
    if (sourceWidth <= 0 || sourceHeight <= 0) return null

    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    ) ?: return null

    var scaled: Bitmap? = null
    try {
        val (targetWidth, targetHeight) = targetSize(sourceWidth, sourceHeight, maxSidePx)
        if (targetWidth <= 0 || targetHeight <= 0) return null
        // createScaledBitmap only when the aspect-preserving size actually differs, so a screenshot
        // already within budget keeps its exact pixels (no resample blur).
        scaled = if (targetWidth == decoded.width && targetHeight == decoded.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        }
        val compressed = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, compressed)) return null
        return jpegDataUrl(compressed.toByteArray())
    } finally {
        if (scaled != null && scaled !== decoded && !scaled.isRecycled) scaled.recycle()
        if (!decoded.isRecycled) decoded.recycle()
    }
}

/**
 * Aspect-preserving target size for a screenshot, never enlarging.
 *
 * Returns `(0, 0)` for a non-positive dimension or cap, which the caller treats as "not encodable".
 * Every positive result keeps at least one pixel per axis, so a 20 000 × 8 panorama scaled to 1 024
 * still yields a 1-pixel-tall image instead of collapsing to zero and throwing in the platform
 * resampler.
 */
internal fun targetSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0 || maxSide <= 0) return 0 to 0
    val longestSide = maxOf(width, height)
    if (longestSide <= maxSide) return width to height
    val scale = maxSide.toDouble() / longestSide.toDouble()
    val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return scaledWidth to scaledHeight
}

private fun jpegDataUrl(jpegBytes: ByteArray): String? {
    if (jpegBytes.isEmpty()) return null
    return "data:image/jpeg;base64," + Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
}

/** Longest edge kept for a screenshot handed to a vision model. */
internal const val DEFAULT_SCREENSHOT_MAX_SIDE_PX: Int = 1024

/** JPEG quality: enough for text legibility at [DEFAULT_SCREENSHOT_MAX_SIDE_PX], small on the wire. */
internal const val DEFAULT_SCREENSHOT_JPEG_QUALITY: Int = 70

/** A screenshot larger than this is refused before any decode is attempted. */
internal const val MAX_SOURCE_BYTES: Int = 8 * 1024 * 1024
