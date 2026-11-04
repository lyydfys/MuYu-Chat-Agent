package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全屏图片预览的缩放/采样合同。
 *
 * 触摸手势本身只能在真机验证，但“双击在适应与放大之间切换、缩放后平移被夹在合法范围、边界始终
 * 覆盖适应屏幕区域”都是纯几何；采样倍数则是大图不进内存的硬条件。
 */
class ImagePreviewZoomTest {

    private val viewSize = ImageEditingSize(width = 1_080.0, height = 1_920.0)
    private val imageWidth = 4_000
    private val imageHeight = 3_000

    @Test
    fun `double tap from fit zooms to the preview factor around the tapped point`() {
        val state = imagePreviewDoubleTapZoomState(
            current = ImageEditingViewportState(),
            viewSize = viewSize,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            focalPoint = ImageEditingViewPoint(540.0, 960.0),
        )
        assertEquals(IMAGE_PREVIEW_DOUBLE_TAP_ZOOM, state.zoom, 1e-9)
        assertTrue(
            ImageEditingViewportTransform
                .create(viewSize, imageWidth, imageHeight, state)
                .coversBaseFitBounds(),
        )
    }

    @Test
    fun `double tap at a corner keeps the image inside its legal pan range`() {
        val state = imagePreviewDoubleTapZoomState(
            current = ImageEditingViewportState(),
            viewSize = viewSize,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            focalPoint = ImageEditingViewPoint(-10.0, 5_000.0),
        )
        assertEquals(IMAGE_PREVIEW_DOUBLE_TAP_ZOOM, state.zoom, 1e-9)
        assertTrue(state.panXFraction in -1.0..1.0)
        assertTrue(state.panYFraction in -1.0..1.0)
        assertTrue(
            ImageEditingViewportTransform
                .create(viewSize, imageWidth, imageHeight, state)
                .coversBaseFitBounds(),
        )
    }

    @Test
    fun `double tap while zoomed restores fit and clears pan`() {
        val zoomed = imagePreviewDoubleTapZoomState(
            current = ImageEditingViewportState(),
            viewSize = viewSize,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            focalPoint = ImageEditingViewPoint(800.0, 400.0),
        )
        val restored = imagePreviewDoubleTapZoomState(
            current = zoomed,
            viewSize = viewSize,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            focalPoint = ImageEditingViewPoint(800.0, 400.0),
        )
        assertEquals(1.0, restored.zoom, 1e-9)
        assertEquals(0.0, restored.panXFraction, 1e-9)
        assertEquals(0.0, restored.panYFraction, 1e-9)
    }

    @Test
    fun `gesture zoom and pan stay clamped even when dragged far outside`() {
        val base = ImageEditingViewportTransform.create(viewSize, imageWidth, imageHeight)
        val zoomed = base.stateAfterGesture(
            centroid = ImageEditingViewPoint(540.0, 960.0),
            panDelta = ImageEditingViewPoint(3_000.0, -4_000.0),
            zoomFactor = 2.0,
        )
        assertEquals(2.0, zoomed.zoom, 1e-9)
        val dragged = ImageEditingViewportTransform
            .create(viewSize, imageWidth, imageHeight, zoomed)
            .stateAfterPan(ImageEditingViewPoint(1_000_000.0, -1_000_000.0))
        assertTrue(
            ImageEditingViewportTransform
                .create(viewSize, imageWidth, imageHeight, dragged)
                .coversBaseFitBounds(),
        )
        assertTrue(dragged.panXFraction in -1.0..1.0)
        assertTrue(dragged.panYFraction in -1.0..1.0)
    }

    @Test
    fun `sample size halves large bitmaps only as far as needed`() {
        assertEquals(4, imageSampleSizeFor(4_000, 3_000, 1_280))
        assertEquals(16, imageSampleSizeFor(5_000, 5_000, 512))
        assertEquals(1, imageSampleSizeFor(1_000, 800, 1_280))
        assertEquals(1, imageSampleSizeFor(4_000, 3_000, null))
        assertEquals(1, imageSampleSizeFor(0, 0, 1_280))
    }

    @Test
    fun `preview decode stays sampled for a 4096 image`() {
        val sample = imageSampleSizeFor(4_096, 4_096, 2_048)
        assertTrue("sample size must skip pixels", sample >= 2)
        assertTrue(4_096 / sample <= 2_048)
    }
}
