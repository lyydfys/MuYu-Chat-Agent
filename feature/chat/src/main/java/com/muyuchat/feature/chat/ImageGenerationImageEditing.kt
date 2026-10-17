package com.muyuchat.feature.chat

import kotlin.math.ceil
import kotlin.math.floor

/** Pure Kotlin geometry and immutable mask state shared by image-editing UI adapters. */
internal data class ImageEditingSize(
    val width: Double,
    val height: Double
) {
    init {
        require(width.isFinite() && height.isFinite() && width > 0.0 && height > 0.0) {
            "Image-editing dimensions must be finite and positive."
        }
    }
}

internal data class ImageEditingViewPoint(
    val x: Double,
    val y: Double
) {
    init {
        require(x.isFinite() && y.isFinite()) {
            "Image-editing view coordinates must be finite."
        }
    }
}

internal data class ImageEditingViewRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    init {
        require(
            left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
                left < right && top < bottom &&
                (right - left).isFinite() && (bottom - top).isFinite()
        ) {
            "Image-editing view bounds must be finite, ordered, and non-empty."
        }
    }

    val width: Double = right - left
    val height: Double = bottom - top

    operator fun contains(point: ImageEditingViewPoint): Boolean =
        point.x in left..right && point.y in top..bottom
}

internal data class NormalizedImagePoint(
    val x: Double,
    val y: Double
) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) {
            "Normalized image coordinates must be finite and within [0, 1]."
        }
    }

    companion object {
        fun clamped(x: Double, y: Double): NormalizedImagePoint {
            require(x.isFinite() && y.isFinite()) {
                "Normalized image coordinates must be finite."
            }
            return NormalizedImagePoint(
                x = x.coerceIn(0.0, 1.0),
                y = y.coerceIn(0.0, 1.0)
            )
        }
    }
}

internal data class NormalizedImageRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    init {
        require(
            left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
                left in 0.0..1.0 && top in 0.0..1.0 &&
                right in 0.0..1.0 && bottom in 0.0..1.0 &&
                left < right && top < bottom
        ) {
            "Normalized crop bounds must be finite, non-empty, and within [0, 1]."
        }
    }

    fun toPixelRectOrNull(
        imageWidth: Int,
        imageHeight: Int,
        minimumPixelWidth: Int = 1,
        minimumPixelHeight: Int = 1
    ): ImagePixelRect? {
        require(imageWidth > 0 && imageHeight > 0) {
            "Image pixel dimensions must be positive."
        }
        require(minimumPixelWidth > 0 && minimumPixelHeight > 0) {
            "Minimum crop dimensions must be positive."
        }

        val pixelLeft = floor(stablePixelBoundary(left, imageWidth)).toInt().coerceIn(0, imageWidth)
        val pixelTop = floor(stablePixelBoundary(top, imageHeight)).toInt().coerceIn(0, imageHeight)
        val pixelRight = ceil(stablePixelBoundary(right, imageWidth)).toInt().coerceIn(0, imageWidth)
        val pixelBottom = ceil(stablePixelBoundary(bottom, imageHeight)).toInt().coerceIn(0, imageHeight)
        if (
            pixelRight - pixelLeft < minimumPixelWidth ||
            pixelBottom - pixelTop < minimumPixelHeight
        ) {
            return null
        }
        return ImagePixelRect(pixelLeft, pixelTop, pixelRight, pixelBottom)
    }

    companion object {
        fun fromCorners(
            first: NormalizedImagePoint,
            second: NormalizedImagePoint
        ): NormalizedImageRect = NormalizedImageRect(
            left = minOf(first.x, second.x),
            top = minOf(first.y, second.y),
            right = maxOf(first.x, second.x),
            bottom = maxOf(first.y, second.y)
        )

        fun fromUnorderedClampedOrNull(
            left: Double,
            top: Double,
            right: Double,
            bottom: Double
        ): NormalizedImageRect? {
            require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
                "Normalized crop bounds must be finite."
            }
            val first = NormalizedImagePoint.clamped(left, top)
            val second = NormalizedImagePoint.clamped(right, bottom)
            return if (first.x == second.x || first.y == second.y) {
                null
            } else {
                fromCorners(first, second)
            }
        }
    }
}

internal data class ImagePixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top) {
            "Pixel crop bounds must be non-negative, ordered, and non-empty."
        }
    }

    val width: Int = right - left
    val height: Int = bottom - top

    fun toNormalizedRect(imageWidth: Int, imageHeight: Int): NormalizedImageRect {
        require(imageWidth > 0 && imageHeight > 0) {
            "Image pixel dimensions must be positive."
        }
        require(right <= imageWidth && bottom <= imageHeight) {
            "Pixel crop bounds must fit inside the image."
        }
        return NormalizedImageRect(
            left = left.toDouble() / imageWidth,
            top = top.toDouble() / imageHeight,
            right = right.toDouble() / imageWidth,
            bottom = bottom.toDouble() / imageHeight
        )
    }
}
