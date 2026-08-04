package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImageGenerationImageEditingTest {
    @Test
    fun `fit transform centers landscape image and maps letterbox deterministically`() {
        val transform = ImageFitTransform.create(
            viewSize = ImageEditingSize(1_000.0, 1_000.0),
            imageWidth = 1_600,
            imageHeight = 900
        )

        assertRectEquals(
            ImageEditingViewRect(0.0, 218.75, 1_000.0, 781.25),
            transform.displayedImageBounds
        )
        assertPointEquals(
            NormalizedImagePoint(0.5, 0.5),
            requireNotNull(transform.viewToNormalized(ImageEditingViewPoint(500.0, 500.0)))
        )
        assertPointEquals(
            NormalizedImagePoint(0.5, 0.0),
            requireNotNull(transform.viewToNormalized(ImageEditingViewPoint(500.0, 0.0)))
        )
        assertNull(
            transform.viewToNormalized(
                ImageEditingViewPoint(500.0, 0.0),
                clampToImage = false
            )
        )
    }

    @Test
    fun `fit transform centers portrait image clamps outside view and round trips`() {
        val transform = ImageFitTransform.create(
            viewSize = ImageEditingSize(1_000.0, 1_000.0),
            imageWidth = 900,
            imageHeight = 1_600
        )

        assertRectEquals(
            ImageEditingViewRect(218.75, 0.0, 781.25, 1_000.0),
            transform.displayedImageBounds
        )
        assertPointEquals(
            NormalizedImagePoint(0.0, 1.0),
            requireNotNull(transform.viewToNormalized(ImageEditingViewPoint(-50.0, 1_050.0)))
        )
        val original = NormalizedImagePoint(0.23, 0.81)
        val viewPoint = transform.normalizedToView(original)
        assertPointEquals(original, requireNotNull(transform.viewToNormalized(viewPoint)))
    }

    @Test
    fun `viewport transform round trips normalized points and keeps base fit area covered`() {
        val state = ImageEditingViewportState(
            zoom = 3.25,
            panXFraction = 0.72,
            panYFraction = -0.63
        )
        val transform = ImageEditingViewportTransform.create(
            viewSize = ImageEditingSize(1_000.0, 800.0),
            imageWidth = 1_600,
            imageHeight = 900,
            state = state
        )

        assertTrue(transform.coversBaseFitBounds())
        listOf(
            NormalizedImagePoint(0.0, 0.0),
            NormalizedImagePoint(0.17, 0.83),
            NormalizedImagePoint(0.5, 0.5),
            NormalizedImagePoint(1.0, 1.0)
        ).forEach { point ->
            val view = transform.normalizedToView(point)
            assertPointEquals(
                point,
                requireNotNull(transform.viewToNormalized(view, clampToImage = false)),
                tolerance = 1e-12
            )
        }
        assertNull(
            transform.viewToNormalized(
                ImageEditingViewPoint(
                    transform.displayedImageBounds.left - 1.0,
                    transform.displayedImageBounds.top
                ),
                clampToImage = false
            )
        )
    }

    @Test
    fun `viewport zoom focal point pan clamp and canvas resize remain deterministic`() {
        val initial = ImageEditingViewportTransform.create(
            viewSize = ImageEditingSize(1_000.0, 800.0),
            imageWidth = 900,
            imageHeight = 1_600,
            state = ImageEditingViewportState(zoom = 2.0, panXFraction = 0.2, panYFraction = -0.3)
        )
        val focal = ImageEditingViewPoint(500.0, 400.0)
        val focalBefore = requireNotNull(initial.viewToNormalized(focal))
        val zoomedState = initial.stateForZoom(4.0, focal)
        val zoomed = ImageEditingViewportTransform.create(
            initial.viewSize,
            initial.imageWidth,
            initial.imageHeight,
            zoomedState
        )
        assertPointEquals(focalBefore, requireNotNull(zoomed.viewToNormalized(focal)), 1e-12)

        val clampedState = zoomed.stateAfterGesture(
            centroid = focal,
            panDelta = ImageEditingViewPoint(Double.MAX_VALUE, -Double.MAX_VALUE),
            zoomFactor = 100.0
        )
        assertEquals(ImageEditingViewportState.MAX_ZOOM, clampedState.zoom, 0.0)
        assertEquals(1.0, clampedState.panXFraction, 0.0)
        assertEquals(-1.0, clampedState.panYFraction, 0.0)
        val resized = ImageEditingViewportTransform.create(
            viewSize = ImageEditingSize(800.0, 1_000.0),
            imageWidth = initial.imageWidth,
            imageHeight = initial.imageHeight,
            state = clampedState
        )
        assertTrue(resized.coversBaseFitBounds())
        val point = NormalizedImagePoint(0.31, 0.69)
        assertPointEquals(
            point,
            requireNotNull(resized.viewToNormalized(resized.normalizedToView(point), false)),
            1e-12
        )
    }

    @Test
    fun `viewport rejects non finite zoom pan gesture and tolerance values`() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageEditingViewportState(zoom = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageEditingViewportState(zoom = 2.0, panXFraction = Double.POSITIVE_INFINITY)
        }
        val transform = ImageEditingViewportTransform.create(
            ImageEditingSize(100.0, 100.0),
            imageWidth = 100,
            imageHeight = 50
        )
        assertThrows(IllegalArgumentException::class.java) {
            transform.stateForZoom(Double.NaN, ImageEditingViewPoint(50.0, 50.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            transform.stateAfterGesture(
                centroid = ImageEditingViewPoint(50.0, 50.0),
                panDelta = ImageEditingViewPoint(0.0, 0.0),
                zoomFactor = Double.POSITIVE_INFINITY
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            transform.coversBaseFitBounds(Double.NaN)
        }
    }

    @Test
    fun `normalized crop orders corners clamps bounds and uses pixel coverage rounding`() {
        val crop = NormalizedImageRect.fromCorners(
            NormalizedImagePoint(0.9001, 0.8001),
            NormalizedImagePoint(0.1001, 0.2001)
        )

        assertEquals(
            ImagePixelRect(left = 10, top = 10, right = 91, bottom = 41),
            crop.toPixelRectOrNull(imageWidth = 100, imageHeight = 50)
        )
        assertEquals(
            NormalizedImageRect(0.0, 0.25, 1.0, 1.0),
            NormalizedImageRect.fromUnorderedClampedOrNull(
                left = 2.0,
                top = 2.0,
                right = -1.0,
                bottom = 0.25
            )
        )
        assertNull(
            NormalizedImageRect.fromUnorderedClampedOrNull(
                left = -2.0,
                top = 0.2,
                right = -1.0,
                bottom = 0.8
            )
        )
    }

    @Test
    fun `crop rejects undersized result and pixel bounds round trip exactly`() {
        val tiny = NormalizedImageRect(0.10, 0.10, 0.11, 0.11)
        assertNull(
            tiny.toPixelRectOrNull(
                imageWidth = 100,
                imageHeight = 100,
                minimumPixelWidth = 2,
                minimumPixelHeight = 2
            )
        )

        val pixels = ImagePixelRect(left = 17, top = 23, right = 319, bottom = 401)
        val normalized = pixels.toNormalizedRect(imageWidth = 640, imageHeight = 480)
        assertEquals(pixels, normalized.toPixelRectOrNull(640, 480))
        assertThrows(IllegalArgumentException::class.java) {
            pixels.toNormalizedRect(imageWidth = 300, imageHeight = 480)
        }

        val random = Random(0)
        repeat(2_000) {
            val width = random.nextInt(1, 4_097)
            val height = random.nextInt(1, 4_097)
            val left = random.nextInt(0, width)
            val top = random.nextInt(0, height)
            val sampled = ImagePixelRect(
                left = left,
                top = top,
                right = random.nextInt(left + 1, width + 1),
                bottom = random.nextInt(top + 1, height + 1)
            )
            assertEquals(sampled, sampled.toNormalizedRect(width, height).toPixelRectOrNull(width, height))
        }
    }

    @Test
    fun `centered crop and drag preserve target aspect in source pixels`() {
        val centered = centeredNormalizedCropForTargetAspect(
            imageWidth = 1_600,
            imageHeight = 900,
            targetWidth = 1,
            targetHeight = 1
        )
        assertEquals(NormalizedImageRect(0.21875, 0.0, 0.78125, 1.0), centered)
        assertEquals(1.0, pixelAspect(centered, 1_600, 900), 1e-9)

        val dragged = requireNotNull(
            fixedAspectNormalizedCropFromDragOrNull(
                anchor = NormalizedImagePoint(0.1, 0.2),
                current = NormalizedImagePoint(0.9, 0.8),
                imageWidth = 1_600,
                imageHeight = 900,
                targetWidth = 3,
                targetHeight = 2
            )
        )
        assertEquals(1.5, pixelAspect(dragged, 1_600, 900), 1e-9)
        val minimumDrag = requireNotNull(
            fixedAspectNormalizedCropFromDragOrNull(
                anchor = NormalizedImagePoint(0.5, 0.5),
                current = NormalizedImagePoint(0.5, 0.5),
                imageWidth = 1_600,
                imageHeight = 900,
                targetWidth = 3,
                targetHeight = 2
            )
        )
        val minimumPixels = requireNotNull(
            minimumDrag.toExactTargetAspectPixelRectOrNull(1_600, 900, 3, 2)
        )
        assertTrue(minimumPixels.width >= 32)
        assertTrue(minimumPixels.height >= 32)
        assertEquals(1.5, minimumPixels.width.toDouble() / minimumPixels.height, 0.0)
        assertNull(
            fixedAspectNormalizedCropFromDragOrNull(
                anchor = NormalizedImagePoint(0.99, 0.99),
                current = NormalizedImagePoint(1.0, 1.0),
                imageWidth = 100,
                imageHeight = 100,
                targetWidth = 1,
                targetHeight = 1
            )
        )

        val exactPixels = requireNotNull(
            dragged.toExactTargetAspectPixelRectOrNull(
                imageWidth = 1_600,
                imageHeight = 900,
                targetWidth = 768,
                targetHeight = 512
            )
        )
        assertEquals(3, exactPixels.width / greatestCommonTestDivisor(exactPixels.width, exactPixels.height))
        assertEquals(2, exactPixels.height / greatestCommonTestDivisor(exactPixels.width, exactPixels.height))
        assertNull(
            NormalizedImageRect(0.0, 0.0, 0.01, 0.01)
                .toExactTargetAspectPixelRectOrNull(1_600, 900, 1, 1)
        )

        val smaller = scaledFixedAspectNormalizedCrop(
            crop = centered,
            imageWidth = 1_600,
            imageHeight = 900,
            targetWidth = 1,
            targetHeight = 1,
            scaleFactor = 0.5
        )
        val smallerPixels = requireNotNull(
            smaller.toExactTargetAspectPixelRectOrNull(1_600, 900, 1, 1)
        )
        assertEquals(smallerPixels.width, smallerPixels.height)
        assertTrue(smallerPixels.width < 900)
        val moved = translatedFixedAspectNormalizedCrop(
            crop = smaller,
            imageWidth = 1_600,
            imageHeight = 900,
            targetWidth = 1,
            targetHeight = 1,
            horizontalDirection = -1,
            verticalDirection = -1
        )
        val movedPixels = requireNotNull(
            moved.toExactTargetAspectPixelRectOrNull(1_600, 900, 1, 1)
        )
        assertEquals(smallerPixels.width, movedPixels.width)
        assertTrue(movedPixels.left < smallerPixels.left)
        assertTrue(movedPixels.top < smallerPixels.top)
    }

    @Test
    fun `brush and eraser strokes plus history operations remain immutable`() {
        val mutablePoints = mutableListOf(NormalizedImagePoint(0.1, 0.2))
        val brush = ImageMaskStroke(ImageMaskStrokeMode.BRUSH, 0.05, mutablePoints)
        mutablePoints.clear()
        val extendedBrush = brush.appendPoint(NormalizedImagePoint(0.3, 0.4))
        val eraser = ImageMaskStroke(
            ImageMaskStrokeMode.ERASER,
            0.1,
            listOf(NormalizedImagePoint(0.8, 0.7))
        )

        assertEquals(1, brush.points.size)
        assertEquals(2, extendedBrush.points.size)
        assertEquals(45.0, brush.radiusInPixels(imageWidth = 1_600, imageHeight = 900), 0.0)
        assertNotSame(brush, extendedBrush)
        assertSame(brush, brush.appendPoint(brush.points.last()))
        assertEquals(255, ImageMaskStrokeMode.BRUSH.grayscaleValue)
        assertEquals(0, ImageMaskStrokeMode.ERASER.grayscaleValue)
        assertEquals(0, ImageMaskEditingState.INITIAL_GRAYSCALE_VALUE)
        val empty = ImageMaskEditingState()
        val painted = empty.addStroke(extendedBrush)
        val erased = painted.addStroke(eraser)
        assertEquals(listOf(extendedBrush, eraser), erased.strokes)
        assertEquals(3, erased.totalPointCount)
        assertEquals(painted, erased.undo())
        assertEquals(empty, erased.clear())
        assertEquals(1, painted.strokes.size)
        assertSame(empty, empty.undo())
        assertSame(empty, empty.clear())
        val fullRepaint = empty.fill(ImageMaskStrokeMode.BRUSH.grayscaleValue)
        assertEquals(255, fullRepaint.baseGrayscaleValue)
        assertTrue(fullRepaint.strokes.isEmpty())
        assertNotSame(empty, fullRepaint)
        assertSame(fullRepaint, fullRepaint.fill(255))
        assertEquals(empty, fullRepaint.clear())
        assertThrows(IllegalArgumentException::class.java) { empty.fill(128) }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (erased.strokes as MutableList<ImageMaskStroke>).clear()
        }
        assertThrows(IllegalArgumentException::class.java) {
            brush.radiusInPixels(imageWidth = 0, imageHeight = 900)
        }
    }

    @Test
    fun `stroke point stroke count and total point limits fail closed`() {
        val point = NormalizedImagePoint(0.5, 0.5)
        val maximumStroke = ImageMaskStroke(
            ImageMaskStrokeMode.BRUSH,
            ImageMaskStroke.MIN_NORMALIZED_RADIUS,
            List(ImageMaskStroke.MAX_POINTS_PER_STROKE) { point }
        )
        assertThrows(IllegalArgumentException::class.java) {
            maximumStroke.appendPoint(NormalizedImagePoint(0.6, 0.5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageMaskStroke(
                ImageMaskStrokeMode.BRUSH,
                0.1,
                List(ImageMaskStroke.MAX_POINTS_PER_STROKE + 1) { point }
            )
        }

        val onePointStroke = ImageMaskStroke(ImageMaskStrokeMode.BRUSH, 0.1, listOf(point))
        val strokeFull = ImageMaskEditingState(
            List(ImageMaskEditingState.MAX_STROKES) { onePointStroke }
        )
        assertThrows(IllegalStateException::class.java) { strokeFull.addStroke(onePointStroke) }

        val pointsPerStroke = ImageMaskStroke.MAX_POINTS_PER_STROKE
        val totalFull = ImageMaskEditingState(
            List(ImageMaskEditingState.MAX_TOTAL_POINTS / pointsPerStroke) { maximumStroke }
        )
        assertEquals(ImageMaskEditingState.MAX_TOTAL_POINTS, totalFull.totalPointCount)
        assertThrows(IllegalStateException::class.java) { totalFull.addStroke(onePointStroke) }
    }

    @Test
    fun `positioned mask cursor clamps moves and commits one point through normal history state`() {
        val start = NormalizedImagePoint(0.5, 0.5)
        assertEquals(
            NormalizedImagePoint(0.6, 0.5),
            movedImageMaskCursor(start, horizontalDirection = 1, verticalDirection = 0, stepFraction = 0.1)
        )
        assertEquals(
            NormalizedImagePoint(0.0, 1.0),
            movedImageMaskCursor(
                NormalizedImagePoint(0.02, 0.98),
                horizontalDirection = -1,
                verticalDirection = 1,
                stepFraction = 0.05
            )
        )

        val applied = ImageMaskEditingState().applyPointAtCursor(
            cursor = start,
            mode = ImageMaskStrokeMode.ERASER,
            radius = 0.05
        )
        assertEquals(1, applied.strokes.size)
        assertEquals(ImageMaskStrokeMode.ERASER, applied.strokes.single().mode)
        assertEquals(listOf(start), applied.strokes.single().points)
        assertEquals(ImageMaskEditingState(), applied.undo())
        assertThrows(IllegalArgumentException::class.java) {
            movedImageMaskCursor(start, 1, 0, 0.0)
        }
    }

    @Test
    fun `invalid geometry radius and points are rejected`() {
        val point = NormalizedImagePoint(0.5, 0.5)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                NormalizedImagePoint(invalid, 0.5)
            }
        }
        listOf(
            Double.NaN,
            Double.NEGATIVE_INFINITY,
            0.0,
            ImageMaskStroke.MIN_NORMALIZED_RADIUS / 2.0,
            ImageMaskStroke.MAX_NORMALIZED_RADIUS + 0.01
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ImageMaskStroke(ImageMaskStrokeMode.BRUSH, invalid, listOf(point))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageMaskStroke(ImageMaskStrokeMode.BRUSH, 0.1, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedImageRect(0.5, 0.0, 0.5, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedImageRect.fromUnorderedClampedOrNull(Double.NaN, 0.0, 1.0, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageEditingSize(Double.POSITIVE_INFINITY, 10.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageEditingViewRect(-Double.MAX_VALUE, 0.0, Double.MAX_VALUE, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageFitTransform.create(ImageEditingSize(10.0, 10.0), Int.MAX_VALUE, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageFitTransform.create(
                ImageEditingSize(Double.MAX_VALUE, java.lang.Double.MIN_NORMAL),
                imageWidth = 1,
                imageHeight = Int.MAX_VALUE
            )
        }
    }

    private fun assertPointEquals(
        expected: NormalizedImagePoint,
        actual: NormalizedImagePoint,
        tolerance: Double = 1e-9
    ) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }

    private fun assertRectEquals(
        expected: ImageEditingViewRect,
        actual: ImageEditingViewRect,
        tolerance: Double = 1e-9
    ) {
        assertEquals(expected.left, actual.left, tolerance)
        assertEquals(expected.top, actual.top, tolerance)
        assertEquals(expected.right, actual.right, tolerance)
        assertEquals(expected.bottom, actual.bottom, tolerance)
    }


    private fun pixelAspect(rect: NormalizedImageRect, width: Int, height: Int): Double =
        (rect.right - rect.left) * width / ((rect.bottom - rect.top) * height)

    private tailrec fun greatestCommonTestDivisor(first: Int, second: Int): Int =
        if (second == 0) kotlin.math.abs(first) else greatestCommonTestDivisor(second, first % second)
}
