package com.muyuchat.feature.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag

/**
 * 全屏图片预览的缩放手势层。
 *
 * - 双指捏合缩放、单指拖动平移，缩放范围 1x–6x；
 * - 双击在「适应屏幕」与 [IMAGE_PREVIEW_DOUBLE_TAP_ZOOM] 之间切换；
 * - 单击关闭（与返回键、右上角关闭一致，由宿主提供退出动画）；
 * - 平移/缩放边界由 [ImageEditingViewportTransform] 纯几何夹紧，图片始终覆盖适应区域，
 *   不会拖出可视区或留下非法留白；父容器 `clipToBounds` 保证不会画到顶部栏/信息卡上。
 *
 * 只负责手势和绘制，不持有 Bitmap 生命周期；解码仍由宿主的 `rememberChatImageLoadState`
 * （带 `inSampleSize` 采样）负责。
 */
@Composable
internal fun ZoomablePreviewImage(
    imageId: String,
    bitmap: Bitmap,
    contentDescription: String,
    onDismissTap: () -> Unit,
    modifier: Modifier = Modifier,
    bitmapTestTag: String? = null,
) {
    var viewport by remember(imageId) { mutableStateOf(ImageEditingViewportState()) }
    var viewSize by remember(imageId) { mutableStateOf<ImageEditingSize?>(null) }
    val transform = remember(viewSize, viewport, bitmap.width, bitmap.height) {
        viewSize?.let { size ->
            ImageEditingViewportTransform.create(
                viewSize = size,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                state = viewport,
            )
        }
    }
    Box(
        modifier = modifier
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    viewSize = ImageEditingSize(
                        width = size.width.toDouble(),
                        height = size.height.toDouble(),
                    )
                }
            }
            .clipToBounds()
            .pointerInput(imageId, bitmap.width, bitmap.height, viewSize) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val currentViewSize = viewSize ?: return@detectTransformGestures
                    viewport = ImageEditingViewportTransform
                        .create(currentViewSize, bitmap.width, bitmap.height, viewport)
                        .stateAfterGesture(
                            centroid = ImageEditingViewPoint(
                                centroid.x.toDouble(),
                                centroid.y.toDouble(),
                            ),
                            panDelta = ImageEditingViewPoint(
                                pan.x.toDouble(),
                                pan.y.toDouble(),
                            ),
                            zoomFactor = gestureZoom.toDouble(),
                        )
                }
            }
            .pointerInput(imageId, bitmap.width, bitmap.height, viewSize) {
                detectTapGestures(
                    onTap = { onDismissTap() },
                    onDoubleTap = { focal ->
                        val currentViewSize = viewSize ?: return@detectTapGestures
                        viewport = imagePreviewDoubleTapZoomState(
                            current = viewport,
                            viewSize = currentViewSize,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                            focalPoint = ImageEditingViewPoint(
                                focal.x.toDouble(),
                                focal.y.toDouble(),
                            ),
                        )
                    },
                )
            }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .then(if (bitmapTestTag != null) Modifier.testTag(bitmapTestTag) else Modifier)
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = viewport.zoom.toFloat()
                    scaleY = viewport.zoom.toFloat()
                    translationX = transform?.panTranslation?.x?.toFloat() ?: 0f
                    translationY = transform?.panTranslation?.y?.toFloat() ?: 0f
                },
        )
    }
}
