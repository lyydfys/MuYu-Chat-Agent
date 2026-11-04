package com.muyuchat.feature.chat

import kotlin.math.max

/**
 * 全屏图片预览的缩放规则（纯函数，供 Composable 与 JVM 测试共用）。
 *
 * 变换复用编辑器的 [ImageEditingViewportTransform]：它按 `ContentScale.Fit` 的中心基准计算
 * 缩放后的显示边界，并把平移夹在合法范围内，因此预览不会把图片拖出可视区，也不会出现比
 * 「适应屏幕」更小的留白。双击在「适应」与 [IMAGE_PREVIEW_DOUBLE_TAP_ZOOM] 之间切换。
 */
internal const val IMAGE_PREVIEW_DOUBLE_TAP_ZOOM: Double = 2.5

/** 双击后的视口状态：已放大时还原，未放大时以双击点为中心放大到固定倍率。 */
internal fun imagePreviewDoubleTapZoomState(
    current: ImageEditingViewportState,
    viewSize: ImageEditingSize,
    imageWidth: Int,
    imageHeight: Int,
    focalPoint: ImageEditingViewPoint,
): ImageEditingViewportState {
    require(viewSize.width > 0.0 && viewSize.height > 0.0) {
        "Preview view size must be positive."
    }
    require(imageWidth > 0 && imageHeight > 0) {
        "Preview image dimensions must be positive."
    }
    if (current.zoom > 1.01) return ImageEditingViewportState()
    return ImageEditingViewportTransform
        .create(viewSize, imageWidth, imageHeight, current)
        .stateForZoom(IMAGE_PREVIEW_DOUBLE_TAP_ZOOM, focalPoint)
}

/**
 * `BitmapFactory` 的采样倍数：只按边界尺寸降采样，绝不整图解码。
 *
 * [maxDimensionPx] 为空或非正时返回 1（调用方明确要求原图）；边界非法时也返回 1，由上层的
 * 解码结果判定失败，而不是在这里抛异常。
 */
internal fun imageSampleSizeFor(
    width: Int,
    height: Int,
    maxDimensionPx: Int?,
): Int {
    val bound = maxDimensionPx?.takeIf { it > 0 } ?: return 1
    if (width <= 0 || height <= 0) return 1
    var sampleSize = 1
    while (max(width, height) / sampleSize > bound && sampleSize <= MAX_SAMPLE_SIZE) {
        sampleSize *= 2
    }
    return sampleSize
}

/** `inSampleSize` is an Int; keep doubling far enough for a huge image but stop before overflow. */
private const val MAX_SAMPLE_SIZE = 1 shl 29
