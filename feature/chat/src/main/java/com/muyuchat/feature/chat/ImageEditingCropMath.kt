package com.muyuchat.feature.chat

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

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
