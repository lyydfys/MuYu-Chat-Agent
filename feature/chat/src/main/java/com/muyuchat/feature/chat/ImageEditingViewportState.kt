package com.muyuchat.feature.chat

import kotlin.math.min

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
