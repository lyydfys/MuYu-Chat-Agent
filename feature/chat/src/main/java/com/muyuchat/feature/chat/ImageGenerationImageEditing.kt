package com.muyuchat.feature.chat

import java.util.Collections
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round

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

internal fun NormalizedImageRect.toExactTargetAspectPixelRectOrNull(
    imageWidth: Int,
    imageHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    minimumPixelEdge: Int = 32
): ImagePixelRect? {
    require(targetWidth > 0 && targetHeight > 0 && minimumPixelEdge > 0) {
        "Target dimensions and minimum edge must be positive."
    }
    val coverage = toPixelRectOrNull(imageWidth, imageHeight) ?: return null
    val divisor = greatestCommonDivisor(targetWidth, targetHeight)
    val widthUnit = targetWidth / divisor
    val heightUnit = targetHeight / divisor
    val unitCount = minOf(coverage.width / widthUnit, coverage.height / heightUnit)
    if (unitCount <= 0) return null
    val exactWidth = widthUnit * unitCount
    val exactHeight = heightUnit * unitCount
    if (exactWidth < minimumPixelEdge || exactHeight < minimumPixelEdge) return null
    val left = coverage.left + (coverage.width - exactWidth) / 2
    val top = coverage.top + (coverage.height - exactHeight) / 2
    return ImagePixelRect(left, top, left + exactWidth, top + exactHeight)
}

/** Largest centered crop that matches the target output aspect in source-image pixels. */
internal fun centeredNormalizedCropForTargetAspect(
    imageWidth: Int,
    imageHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): NormalizedImageRect {
    require(imageWidth > 0 && imageHeight > 0 && targetWidth > 0 && targetHeight > 0) {
        "Image and target dimensions must be positive."
    }
    val imageAspect = imageWidth.toDouble() / imageHeight.toDouble()
    val targetAspect = targetWidth.toDouble() / targetHeight.toDouble()
    return if (imageAspect > targetAspect) {
        val normalizedWidth = targetAspect / imageAspect
        NormalizedImageRect(
            left = (1.0 - normalizedWidth) / 2.0,
            top = 0.0,
            right = (1.0 + normalizedWidth) / 2.0,
            bottom = 1.0
        )
    } else {
        val normalizedHeight = imageAspect / targetAspect
        NormalizedImageRect(
            left = 0.0,
            top = (1.0 - normalizedHeight) / 2.0,
            right = 1.0,
            bottom = (1.0 + normalizedHeight) / 2.0
        )
    }
}

/**
 * Builds a corner-anchored crop while preserving the target aspect in source-image pixels. The
 * requested extent grows along the dominant drag axis and is then clamped inside the source.
 */
internal fun fixedAspectNormalizedCropFromDragOrNull(
    anchor: NormalizedImagePoint,
    current: NormalizedImagePoint,
    imageWidth: Int,
    imageHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    minimumPixelEdge: Int = 32
): NormalizedImageRect? {
    require(
        imageWidth > 0 && imageHeight > 0 && targetWidth > 0 && targetHeight > 0 &&
            minimumPixelEdge > 0
    ) {
        "Image, target, and minimum crop dimensions must be positive."
    }
    val directionX = when {
        current.x > anchor.x -> 1.0
        current.x < anchor.x -> -1.0
        anchor.x <= 0.5 -> 1.0
        else -> -1.0
    }
    val directionY = when {
        current.y > anchor.y -> 1.0
        current.y < anchor.y -> -1.0
        anchor.y <= 0.5 -> 1.0
        else -> -1.0
    }
    val requestedPixelWidth = abs(current.x - anchor.x) * imageWidth.toDouble()
    val requestedPixelHeight = abs(current.y - anchor.y) * imageHeight.toDouble()
    val targetAspect = targetWidth.toDouble() / targetHeight.toDouble()
    val divisor = greatestCommonDivisor(targetWidth, targetHeight)
    val widthUnit = targetWidth / divisor
    val heightUnit = targetHeight / divisor
    val minimumUnits = maxOf(
        ceil(minimumPixelEdge.toDouble() / widthUnit).toInt(),
        ceil(minimumPixelEdge.toDouble() / heightUnit).toInt()
    )
    val minimumPixelWidth = widthUnit * minimumUnits
    val availablePixelWidth = if (directionX > 0) {
        (1.0 - anchor.x) * imageWidth
    } else {
        anchor.x * imageWidth
    }
    val availablePixelHeight = if (directionY > 0) {
        (1.0 - anchor.y) * imageHeight
    } else {
        anchor.y * imageHeight
    }
    val maximumPixelWidth = minOf(availablePixelWidth, availablePixelHeight * targetAspect)
    if (maximumPixelWidth + Math.ulp(maximumPixelWidth) < minimumPixelWidth) return null
    val desiredPixelWidth = maxOf(
        requestedPixelWidth,
        requestedPixelHeight * targetAspect,
        minimumPixelWidth.toDouble()
    ).coerceAtMost(maximumPixelWidth)
    val desiredPixelHeight = desiredPixelWidth / targetAspect
    val second = NormalizedImagePoint.clamped(
        x = anchor.x + directionX * desiredPixelWidth / imageWidth,
        y = anchor.y + directionY * desiredPixelHeight / imageHeight
    )
    val candidate = NormalizedImageRect.fromCorners(anchor, second)
    return candidate.takeIf {
        it.toExactTargetAspectPixelRectOrNull(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            minimumPixelEdge = minimumPixelEdge
        ) != null
    }
}

internal fun scaledFixedAspectNormalizedCrop(
    crop: NormalizedImageRect,
    imageWidth: Int,
    imageHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    scaleFactor: Double,
    minimumPixelEdge: Int = 32
): NormalizedImageRect {
    require(scaleFactor.isFinite() && scaleFactor > 0.0) { "Crop scale must be positive." }
    val current = crop.toExactTargetAspectPixelRectOrNull(
        imageWidth,
        imageHeight,
        targetWidth,
        targetHeight,
        minimumPixelEdge
    ) ?: return crop
    val divisor = greatestCommonDivisor(targetWidth, targetHeight)
    val widthUnit = targetWidth / divisor
    val heightUnit = targetHeight / divisor
    val minimumUnits = maxOf(
        ceil(minimumPixelEdge.toDouble() / widthUnit).toInt(),
        ceil(minimumPixelEdge.toDouble() / heightUnit).toInt()
    )
    val maximumUnits = minOf(imageWidth / widthUnit, imageHeight / heightUnit)
    if (maximumUnits < minimumUnits) return crop
    val currentUnits = minOf(current.width / widthUnit, current.height / heightUnit)
    val roundedUnits = round(currentUnits * scaleFactor).toInt()
    val steppedUnits = when {
        scaleFactor > 1.0 && roundedUnits <= currentUnits && currentUnits < maximumUnits ->
            currentUnits + 1
        scaleFactor < 1.0 && roundedUnits >= currentUnits && currentUnits > minimumUnits ->
            currentUnits - 1
        else -> roundedUnits
    }
    val desiredUnits = steppedUnits.coerceIn(minimumUnits, maximumUnits)
    val desiredWidth = widthUnit * desiredUnits
    val desiredHeight = heightUnit * desiredUnits
    val centerX = (current.left + current.right) / 2.0
    val centerY = (current.top + current.bottom) / 2.0
    val left = round(centerX - desiredWidth / 2.0).toInt().coerceIn(0, imageWidth - desiredWidth)
    val top = round(centerY - desiredHeight / 2.0).toInt().coerceIn(0, imageHeight - desiredHeight)
    return ImagePixelRect(left, top, left + desiredWidth, top + desiredHeight)
        .toNormalizedRect(imageWidth, imageHeight)
}

internal fun translatedFixedAspectNormalizedCrop(
    crop: NormalizedImageRect,
    imageWidth: Int,
    imageHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    horizontalDirection: Int,
    verticalDirection: Int,
    stepFraction: Double = 0.08,
    minimumPixelEdge: Int = 32
): NormalizedImageRect {
    require(horizontalDirection in -1..1 && verticalDirection in -1..1) {
        "Crop translation directions must be -1, 0, or 1."
    }
    require(stepFraction.isFinite() && stepFraction > 0.0 && stepFraction <= 1.0) {
        "Crop translation step must be within (0, 1]."
    }
    val current = crop.toExactTargetAspectPixelRectOrNull(
        imageWidth,
        imageHeight,
        targetWidth,
        targetHeight,
        minimumPixelEdge
    ) ?: return crop
    val horizontalStep = maxOf(1, round(current.width * stepFraction).toInt())
    val verticalStep = maxOf(1, round(current.height * stepFraction).toInt())
    val left = (current.left + horizontalDirection * horizontalStep)
        .coerceIn(0, imageWidth - current.width)
    val top = (current.top + verticalDirection * verticalStep)
        .coerceIn(0, imageHeight - current.height)
    return ImagePixelRect(left, top, left + current.width, top + current.height)
        .toNormalizedRect(imageWidth, imageHeight)
}

/** Coordinate transform matching a centered ContentScale.Fit image. */
internal class ImageFitTransform private constructor(
    val viewSize: ImageEditingSize,
    val imageWidth: Int,
    val imageHeight: Int,
    val displayedImageBounds: ImageEditingViewRect
) {
    fun viewToNormalized(
        point: ImageEditingViewPoint,
        clampToImage: Boolean = true
    ): NormalizedImagePoint? {
        if (!clampToImage && point !in displayedImageBounds) return null
        val normalizedX = (point.x - displayedImageBounds.left) / displayedImageBounds.width
        val normalizedY = (point.y - displayedImageBounds.top) / displayedImageBounds.height
        return NormalizedImagePoint.clamped(normalizedX, normalizedY)
    }

    fun normalizedToView(point: NormalizedImagePoint): ImageEditingViewPoint =
        ImageEditingViewPoint(
            x = displayedImageBounds.left + point.x * displayedImageBounds.width,
            y = displayedImageBounds.top + point.y * displayedImageBounds.height
        )

    companion object {
        fun create(
            viewSize: ImageEditingSize,
            imageWidth: Int,
            imageHeight: Int
        ): ImageFitTransform {
            require(imageWidth > 0 && imageHeight > 0) {
                "Image pixel dimensions must be positive."
            }
            val scale = min(
                viewSize.width / imageWidth.toDouble(),
                viewSize.height / imageHeight.toDouble()
            )
            require(scale.isFinite() && scale > 0.0) {
                "Image fit scale must be finite and positive."
            }
            val displayedWidth = imageWidth * scale
            val displayedHeight = imageHeight * scale
            val left = (viewSize.width - displayedWidth) / 2.0
            val top = (viewSize.height - displayedHeight) / 2.0
            return ImageFitTransform(
                viewSize = viewSize,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                displayedImageBounds = ImageEditingViewRect(
                    left = left,
                    top = top,
                    right = left + displayedWidth,
                    bottom = top + displayedHeight
                )
            )
        }
    }
}

internal data class ImageEditingViewportState(
    val zoom: Double = MIN_ZOOM,
    /** Fraction of the legal horizontal pan range, within [-1, 1]. */
    val panXFraction: Double = 0.0,
    /** Fraction of the legal vertical pan range, within [-1, 1]. */
    val panYFraction: Double = 0.0
) {
    init {
        require(zoom.isFinite() && zoom in MIN_ZOOM..MAX_ZOOM) {
            "Viewport zoom must be finite and within [$MIN_ZOOM, $MAX_ZOOM]."
        }
        require(panXFraction.isFinite() && panXFraction in -1.0..1.0) {
            "Viewport horizontal pan fraction must be finite and within [-1, 1]."
        }
        require(panYFraction.isFinite() && panYFraction in -1.0..1.0) {
            "Viewport vertical pan fraction must be finite and within [-1, 1]."
        }
    }

    companion object {
        const val MIN_ZOOM: Double = 1.0
        const val MAX_ZOOM: Double = 6.0
    }
}

/**
 * ContentScale.Fit viewport shared by the source image, mask overlay, and positioned cursor.
 * Pan fractions are normalized so rotation/recomposition and canvas resizing preserve the view.
 */
internal class ImageEditingViewportTransform private constructor(
    val viewSize: ImageEditingSize,
    val imageWidth: Int,
    val imageHeight: Int,
    val state: ImageEditingViewportState,
    val baseFitBounds: ImageEditingViewRect,
    val displayedImageBounds: ImageEditingViewRect,
    val panTranslation: ImageEditingViewPoint,
    private val maximumPanX: Double,
    private val maximumPanY: Double
) {
    fun viewToNormalized(
        point: ImageEditingViewPoint,
        clampToImage: Boolean = true
    ): NormalizedImagePoint? {
        if (!clampToImage && point !in displayedImageBounds) return null
        return NormalizedImagePoint.clamped(
            x = (point.x - displayedImageBounds.left) / displayedImageBounds.width,
            y = (point.y - displayedImageBounds.top) / displayedImageBounds.height
        )
    }

    fun normalizedToView(point: NormalizedImagePoint): ImageEditingViewPoint =
        ImageEditingViewPoint(
            x = displayedImageBounds.left + point.x * displayedImageBounds.width,
            y = displayedImageBounds.top + point.y * displayedImageBounds.height
        )

    fun stateForZoom(
        requestedZoom: Double,
        focalPoint: ImageEditingViewPoint
    ): ImageEditingViewportState {
        require(requestedZoom.isFinite()) { "Requested viewport zoom must be finite." }
        val targetZoom = requestedZoom.coerceIn(
            ImageEditingViewportState.MIN_ZOOM,
            ImageEditingViewportState.MAX_ZOOM
        )
        val focalNormalized = requireNotNull(viewToNormalized(focalPoint))
        val targetWidth = baseFitBounds.width * targetZoom
        val targetHeight = baseFitBounds.height * targetZoom
        val targetMaximumPanX = (targetWidth - baseFitBounds.width) / 2.0
        val targetMaximumPanY = (targetHeight - baseFitBounds.height) / 2.0
        val baseCenterX = (baseFitBounds.left + baseFitBounds.right) / 2.0
        val baseCenterY = (baseFitBounds.top + baseFitBounds.bottom) / 2.0
        val desiredPanX = focalPoint.x - focalNormalized.x * targetWidth -
            (baseCenterX - targetWidth / 2.0)
        val desiredPanY = focalPoint.y - focalNormalized.y * targetHeight -
            (baseCenterY - targetHeight / 2.0)
        return stateFromPanPixels(
            zoom = targetZoom,
            panX = desiredPanX,
            panY = desiredPanY,
            maximumPanX = targetMaximumPanX,
            maximumPanY = targetMaximumPanY
        )
    }

    fun stateAfterPan(delta: ImageEditingViewPoint): ImageEditingViewportState =
        stateFromPanPixels(
            zoom = state.zoom,
            panX = panTranslation.x + delta.x,
            panY = panTranslation.y + delta.y,
            maximumPanX = maximumPanX,
            maximumPanY = maximumPanY
        )

    fun stateAfterGesture(
        centroid: ImageEditingViewPoint,
        panDelta: ImageEditingViewPoint,
        zoomFactor: Double
    ): ImageEditingViewportState {
        require(zoomFactor.isFinite() && zoomFactor > 0.0) {
            "Viewport gesture zoom factor must be finite and positive."
        }
        val zoomedState = stateForZoom(state.zoom * zoomFactor, centroid)
        return create(viewSize, imageWidth, imageHeight, zoomedState)
            .stateAfterPan(panDelta)
    }

    fun coversBaseFitBounds(tolerance: Double = 1e-9): Boolean {
        require(tolerance.isFinite() && tolerance >= 0.0) {
            "Viewport coverage tolerance must be finite and non-negative."
        }
        return displayedImageBounds.left <= baseFitBounds.left + tolerance &&
            displayedImageBounds.top <= baseFitBounds.top + tolerance &&
            displayedImageBounds.right >= baseFitBounds.right - tolerance &&
            displayedImageBounds.bottom >= baseFitBounds.bottom - tolerance
    }

    companion object {
        fun create(
            viewSize: ImageEditingSize,
            imageWidth: Int,
            imageHeight: Int,
            state: ImageEditingViewportState = ImageEditingViewportState()
        ): ImageEditingViewportTransform {
            val base = ImageFitTransform.create(viewSize, imageWidth, imageHeight)
            val baseBounds = base.displayedImageBounds
            val displayedWidth = baseBounds.width * state.zoom
            val displayedHeight = baseBounds.height * state.zoom
            val maximumPanX = (displayedWidth - baseBounds.width) / 2.0
            val maximumPanY = (displayedHeight - baseBounds.height) / 2.0
            val panX = state.panXFraction * maximumPanX
            val panY = state.panYFraction * maximumPanY
            val centerX = (baseBounds.left + baseBounds.right) / 2.0 + panX
            val centerY = (baseBounds.top + baseBounds.bottom) / 2.0 + panY
            val displayedBounds = ImageEditingViewRect(
                left = centerX - displayedWidth / 2.0,
                top = centerY - displayedHeight / 2.0,
                right = centerX + displayedWidth / 2.0,
                bottom = centerY + displayedHeight / 2.0
            )
            return ImageEditingViewportTransform(
                viewSize = viewSize,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                state = state,
                baseFitBounds = baseBounds,
                displayedImageBounds = displayedBounds,
                panTranslation = ImageEditingViewPoint(panX, panY),
                maximumPanX = maximumPanX,
                maximumPanY = maximumPanY
            ).also { transform ->
                check(transform.coversBaseFitBounds()) {
                    "Viewport pan must keep the base fit area covered."
                }
            }
        }

        private fun stateFromPanPixels(
            zoom: Double,
            panX: Double,
            panY: Double,
            maximumPanX: Double,
            maximumPanY: Double
        ): ImageEditingViewportState {
            require(
                zoom.isFinite() && panX.isFinite() && panY.isFinite() &&
                    maximumPanX.isFinite() && maximumPanY.isFinite()
            ) { "Viewport zoom and pan values must be finite." }
            val clampedPanX = panX.coerceIn(-maximumPanX, maximumPanX)
            val clampedPanY = panY.coerceIn(-maximumPanY, maximumPanY)
            return ImageEditingViewportState(
                zoom = zoom,
                panXFraction = if (maximumPanX == 0.0) 0.0 else clampedPanX / maximumPanX,
                panYFraction = if (maximumPanY == 0.0) 0.0 else clampedPanY / maximumPanY
            )
        }
    }
}

/** Grayscale mask contract: white is regenerated; black preserves the source image. */
internal enum class ImageMaskStrokeMode(
    val grayscaleValue: Int
) {
    BRUSH(grayscaleValue = 255),
    ERASER(grayscaleValue = 0)
}

/**
 * An immutable ordered polyline. Adapters render round joins/caps between consecutive points;
 * a one-point stroke is a filled circle. This keeps sparse touch sampling visually continuous.
 */
internal class ImageMaskStroke(
    val mode: ImageMaskStrokeMode,
    /** Radius relative to the shorter source-image edge. */
    val radius: Double,
    points: List<NormalizedImagePoint>
) {
    private val pointsSnapshot = immutableSnapshot(points)

    val points: List<NormalizedImagePoint>
        get() = pointsSnapshot

    init {
        require(radius.isFinite() && radius in MIN_NORMALIZED_RADIUS..MAX_NORMALIZED_RADIUS) {
            "Mask radius must be finite and within the supported normalized range."
        }
        require(pointsSnapshot.isNotEmpty()) { "A mask stroke must contain at least one point." }
        require(pointsSnapshot.size <= MAX_POINTS_PER_STROKE) {
            "A mask stroke exceeds the point limit."
        }
    }

    fun appendPoint(point: NormalizedImagePoint): ImageMaskStroke {
        if (point == pointsSnapshot.last()) return this
        require(pointsSnapshot.size < MAX_POINTS_PER_STROKE) {
            "A mask stroke exceeds the point limit."
        }
        return ImageMaskStroke(mode, radius, pointsSnapshot + point)
    }

    fun radiusInPixels(imageWidth: Int, imageHeight: Int): Double {
        require(imageWidth > 0 && imageHeight > 0) {
            "Image pixel dimensions must be positive."
        }
        return radius * min(imageWidth, imageHeight).toDouble()
    }

    override fun equals(other: Any?): Boolean =
        other is ImageMaskStroke &&
            mode == other.mode && radius == other.radius && pointsSnapshot == other.pointsSnapshot

    override fun hashCode(): Int {
        var result = mode.hashCode()
        result = 31 * result + radius.hashCode()
        result = 31 * result + pointsSnapshot.hashCode()
        return result
    }

    override fun toString(): String =
        "ImageMaskStroke(mode=$mode, radius=$radius, points=$pointsSnapshot)"

    companion object {
        const val MIN_NORMALIZED_RADIUS: Double = 0.001
        const val MAX_NORMALIZED_RADIUS: Double = 0.5
        const val MAX_POINTS_PER_STROKE: Int = 2_048
    }
}

internal class ImageMaskEditingState(
    strokes: List<ImageMaskStroke> = emptyList(),
    val baseGrayscaleValue: Int = INITIAL_GRAYSCALE_VALUE
) {
    private val strokesSnapshot = immutableSnapshot(strokes)

    val strokes: List<ImageMaskStroke>
        get() = strokesSnapshot

    val totalPointCount: Int = strokesSnapshot.sumOf { it.points.size }

    init {
        require(baseGrayscaleValue in setOf(0, 255)) {
            "Mask base grayscale must be black or white."
        }
        require(strokesSnapshot.size <= MAX_STROKES) {
            "Mask state exceeds the stroke limit."
        }
        require(totalPointCount <= MAX_TOTAL_POINTS) {
            "Mask state exceeds the total point limit."
        }
    }

    fun addStroke(stroke: ImageMaskStroke): ImageMaskEditingState {
        check(strokesSnapshot.size < MAX_STROKES) { "Mask state exceeds the stroke limit." }
        check(totalPointCount <= MAX_TOTAL_POINTS - stroke.points.size) {
            "Mask state exceeds the total point limit."
        }
        return ImageMaskEditingState(strokesSnapshot + stroke, baseGrayscaleValue)
    }

    fun undo(): ImageMaskEditingState =
        if (strokesSnapshot.isEmpty()) this else ImageMaskEditingState(
            strokesSnapshot.dropLast(1),
            baseGrayscaleValue
        )

    fun clear(): ImageMaskEditingState =
        if (strokesSnapshot.isEmpty() && baseGrayscaleValue == INITIAL_GRAYSCALE_VALUE) {
            this
        } else {
            ImageMaskEditingState()
        }

    fun fill(grayscaleValue: Int): ImageMaskEditingState {
        require(grayscaleValue in setOf(0, 255)) { "Mask fill must be black or white." }
        return if (strokesSnapshot.isEmpty() && baseGrayscaleValue == grayscaleValue) {
            this
        } else {
            ImageMaskEditingState(baseGrayscaleValue = grayscaleValue)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ImageMaskEditingState &&
            baseGrayscaleValue == other.baseGrayscaleValue &&
            strokesSnapshot == other.strokesSnapshot

    override fun hashCode(): Int = 31 * strokesSnapshot.hashCode() + baseGrayscaleValue

    override fun toString(): String =
        "ImageMaskEditingState(baseGrayscaleValue=$baseGrayscaleValue, strokes=$strokesSnapshot)"

    companion object {
        const val INITIAL_GRAYSCALE_VALUE: Int = 0
        const val MAX_STROKES: Int = 128
        const val MAX_TOTAL_POINTS: Int = 32_768
    }
}

internal fun movedImageMaskCursor(
    cursor: NormalizedImagePoint,
    horizontalDirection: Int,
    verticalDirection: Int,
    stepFraction: Double
): NormalizedImagePoint {
    require(horizontalDirection in -1..1 && verticalDirection in -1..1) {
        "Mask cursor directions must be -1, 0, or 1."
    }
    require(stepFraction.isFinite() && stepFraction > 0.0 && stepFraction <= 1.0) {
        "Mask cursor step must be within (0, 1]."
    }
    return NormalizedImagePoint.clamped(
        x = cursor.x + horizontalDirection * stepFraction,
        y = cursor.y + verticalDirection * stepFraction
    )
}

internal fun ImageMaskEditingState.applyPointAtCursor(
    cursor: NormalizedImagePoint,
    mode: ImageMaskStrokeMode,
    radius: Double
): ImageMaskEditingState = addStroke(
    ImageMaskStroke(
        mode = mode,
        radius = radius,
        points = listOf(cursor)
    )
)

private fun <T> immutableSnapshot(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/** Removes only floating-point noise around an exact pixel boundary. */
private fun stablePixelBoundary(normalized: Double, extent: Int): Double {
    val scaled = normalized * extent.toDouble()
    val nearestInteger = round(scaled)
    val boundaryTolerance = Math.ulp(scaled) * PIXEL_BOUNDARY_ULPS
    return if (abs(scaled - nearestInteger) <= boundaryTolerance) nearestInteger else scaled
}

private tailrec fun greatestCommonDivisor(first: Int, second: Int): Int =
    if (second == 0) abs(first) else greatestCommonDivisor(second, first % second)

private const val PIXEL_BOUNDARY_ULPS: Double = 4.0
