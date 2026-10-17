package com.muyuchat.feature.chat

import kotlin.math.min
import kotlin.math.round

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
