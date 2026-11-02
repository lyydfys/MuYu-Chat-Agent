@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaChip
import com.muyuchat.core.designsystem.McaSlider
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaIconButton

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import com.muyuchat.core.designsystem.McaChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.muyuchat.core.designsystem.McaIconSize

private enum class MaskEditorInteractionMode {
    DRAW,
    PAN
}

@Composable
internal fun MaskEditor(
    bitmap: Bitmap,
    sourceWidth: Int,
    sourceHeight: Int,
    sampleSize: Int,
    onBack: () -> Unit,
    onConfirmed: (GenerationInpaintOwnedUris) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { GenerationImageOwnedInputStore(context) }
    var maskState by remember(bitmap) { mutableStateOf(ImageMaskEditingState()) }
    var undoStates by remember(bitmap) { mutableStateOf(emptyList<ImageMaskEditingState>()) }
    var redoStates by remember(bitmap) { mutableStateOf(emptyList<ImageMaskEditingState>()) }
    var activeStroke by remember { mutableStateOf<ImageMaskStroke?>(null) }
    var interactionMode by remember { mutableStateOf(MaskEditorInteractionMode.DRAW) }
    var viewportState by remember(bitmap) { mutableStateOf(ImageEditingViewportState()) }
    val latestViewportState by rememberUpdatedState(viewportState)
    var strokeMode by remember { mutableStateOf(ImageMaskStrokeMode.BRUSH) }
    var radius by remember { mutableStateOf(0.045) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showPositionedDrawing by remember { mutableStateOf(false) }
    var positionedCursor by remember(bitmap) { mutableStateOf(NormalizedImagePoint(0.5, 0.5)) }
    var positionedStep by remember { mutableStateOf(0.05) }
    var positionedDrawingError by remember { mutableStateOf<String?>(null) }
    val emptyMaskState = remember { ImageMaskEditingState() }
    val dirty = maskState != emptyMaskState || activeStroke != null
    val fullRepaintState = maskState.fill(ImageMaskStrokeMode.BRUSH.grayscaleValue)
    val viewportTransform = remember(canvasSize, bitmap, viewportState) {
        if (canvasSize == IntSize.Zero) {
            null
        } else {
            ImageEditingViewportTransform.create(
                viewSize = ImageEditingSize(
                    canvasSize.width.toDouble(),
                    canvasSize.height.toDouble()
                ),
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                state = viewportState
            )
        }
    }

    fun setViewportZoom(requestedZoom: Double) {
        val transform = viewportTransform ?: return
        viewportState = transform.stateForZoom(
            requestedZoom = requestedZoom,
            focalPoint = ImageEditingViewPoint(
                canvasSize.width / 2.0,
                canvasSize.height / 2.0
            )
        )
    }

    fun commitMaskState(next: ImageMaskEditingState) {
        if (next == maskState) return
        undoStates = (undoStates + maskState).takeLast(ImageMaskEditingState.MAX_STROKES + 1)
        redoStates = emptyList()
        maskState = next
    }

    fun undoMaskState() {
        val previous = undoStates.lastOrNull() ?: return
        redoStates = (redoStates + maskState).takeLast(ImageMaskEditingState.MAX_STROKES + 1)
        undoStates = undoStates.dropLast(1)
        maskState = previous
    }

    fun redoMaskState() {
        val next = redoStates.lastOrNull() ?: return
        undoStates = (undoStates + maskState).takeLast(ImageMaskEditingState.MAX_STROKES + 1)
        redoStates = redoStates.dropLast(1)
        maskState = next
    }

    fun requestBack() {
        if (saving) return
        if (dirty) confirmDiscard = true else onBack()
    }
    BackHandler(enabled = !WindowInsets.isImeVisible, onBack = ::requestBack)

    ImageEditorScaffold(
        title = "绘制蒙版",
        subtitle = "白色重绘，黑色保留 · " +
            boundedImageSubtitle(sourceWidth, sourceHeight, bitmap, sampleSize),
        saving = saving,
        confirmEnabled = maskState != emptyMaskState && !saving,
        confirmLabel = "使用蒙版",
        onBack = ::requestBack,
        onConfirm = {
            saving = true
            error = null
            scope.launch {
                try {
                    val ownedUris = withContext(Dispatchers.IO) {
                        val mask = ImageGenerationBitmapEditing.renderMask(
                            bitmap.width,
                            bitmap.height,
                            maskState
                        )
                        try {
                            store.writeInpaintPair(bitmap, mask)
                        } finally {
                            mask.recycle()
                        }
                    }
                    onConfirmed(ownedUris)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (saveError: Exception) {
                    error = saveError.userFacingEditingMessage("无法保存原图和蒙版。")
                    saving = false
                }
            }
        },
        controls = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                McaChip(
                    selected = interactionMode == MaskEditorInteractionMode.DRAW,
                    onClick = { interactionMode = MaskEditorInteractionMode.DRAW },
                    enabled = !saving,
                    label = { Text("绘制") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                McaChip(
                    selected = interactionMode == MaskEditorInteractionMode.PAN,
                    onClick = { interactionMode = MaskEditorInteractionMode.PAN },
                    enabled = !saving,
                    label = { Text("移动画布") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${(viewportState.zoom * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "画布缩放",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                McaIconButton(
                    contentDescription = "缩小画布",
                    onClick = { setViewportZoom(viewportState.zoom / 1.25) },
                    enabled = viewportState.zoom > ImageEditingViewportState.MIN_ZOOM && !saving,
                    modifier = Modifier.size(48.dp),
                    glyphSize = McaIconSize.L
                ) { Icon(McaIcons.ZoomOut, contentDescription = null) }
                McaIconButton(
                    contentDescription = "放大画布",
                    onClick = { setViewportZoom(viewportState.zoom * 1.25) },
                    enabled = viewportState.zoom < ImageEditingViewportState.MAX_ZOOM && !saving,
                    modifier = Modifier.size(48.dp),
                    glyphSize = McaIconSize.L
                ) { Icon(McaIcons.ZoomIn, contentDescription = null) }
                McaButton(style = McaButtonStyle.Text,
                    onClick = { viewportState = ImageEditingViewportState() },
                    enabled = viewportState != ImageEditingViewportState() && !saving,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("重置") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                McaChip(
                    selected = strokeMode == ImageMaskStrokeMode.BRUSH,
                    onClick = { strokeMode = ImageMaskStrokeMode.BRUSH },
                    enabled = !saving,
                    label = { Text("画笔") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                McaChip(
                    selected = strokeMode == ImageMaskStrokeMode.ERASER,
                    onClick = { strokeMode = ImageMaskStrokeMode.ERASER },
                    enabled = !saving,
                    label = { Text("橡皮") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                Spacer(Modifier.weight(1f))
                McaButton(style = McaButtonStyle.Text,
                    onClick = {
                        positionedDrawingError = null
                        showPositionedDrawing = true
                    },
                    enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("定位绘制") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                McaButton(style = McaButtonStyle.Text,
                    onClick = { commitMaskState(fullRepaintState) },
                    enabled = fullRepaintState != maskState && !saving,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("全图重绘") }
                Spacer(Modifier.weight(1f))
                McaIconButton(
                    contentDescription = "撤销上一笔",
                    onClick = ::undoMaskState,
                    enabled = undoStates.isNotEmpty() && !saving,
                    modifier = Modifier.size(48.dp),
                    glyphSize = McaIconSize.L
                ) { Icon(McaIcons.Undo, contentDescription = null) }
                McaIconButton(
                    contentDescription = "重做下一笔",
                    onClick = ::redoMaskState,
                    enabled = redoStates.isNotEmpty() && !saving,
                    modifier = Modifier.size(48.dp),
                    glyphSize = McaIconSize.L
                ) { Icon(McaIcons.Redo, contentDescription = null) }
                McaIconButton(
                    contentDescription = "清空蒙版",
                    onClick = { commitMaskState(maskState.clear()) },
                    enabled = maskState != emptyMaskState && !saving,
                    modifier = Modifier.size(48.dp),
                    glyphSize = McaIconSize.L
                ) { Icon(McaIcons.Clear, contentDescription = null) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("笔刷", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                McaSlider(
                    value = radius.toFloat(),
                    onValueChange = { radius = it.toDouble() },
                    valueRange = 0.01f..0.16f,
                    enabled = !saving,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "蒙版笔刷大小" }
                )
            }
        },
        error = error
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .background(Color.Black)
                .onSizeChanged { canvasSize = it }
                .semantics {
                    contentDescription = if (interactionMode == MaskEditorInteractionMode.PAN) {
                        "蒙版画布，移动画布模式。使用双指缩放和平移，或使用下方缩放按钮。"
                    } else {
                        "蒙版画布，绘制模式。自由手绘需要触摸坐标；读屏或键盘可使用下方定位绘制。"
                    }
                    stateDescription = if (maskState.baseGrayscaleValue == 255) {
                        "${(viewportState.zoom * 100).roundToInt()}% 缩放，全图重绘基底，已绘制 ${maskState.strokes.size} 笔"
                    } else {
                        "${(viewportState.zoom * 100).roundToInt()}% 缩放，黑色保留基底，已绘制 ${maskState.strokes.size} 笔"
                    }
                }
                .pointerInput(bitmap, canvasSize, strokeMode, radius, interactionMode, saving) {
                    if (saving || canvasSize == IntSize.Zero) return@pointerInput
                    if (interactionMode == MaskEditorInteractionMode.PAN) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            val transform = ImageEditingViewportTransform.create(
                                viewSize = ImageEditingSize(
                                    canvasSize.width.toDouble(),
                                    canvasSize.height.toDouble()
                                ),
                                imageWidth = bitmap.width,
                                imageHeight = bitmap.height,
                                state = latestViewportState
                            )
                            viewportState = transform.stateAfterGesture(
                                centroid = ImageEditingViewPoint(
                                    centroid.x.toDouble(),
                                    centroid.y.toDouble()
                                ),
                                panDelta = ImageEditingViewPoint(
                                    pan.x.toDouble(),
                                    pan.y.toDouble()
                                ),
                                zoomFactor = gestureZoom.toDouble()
                            )
                        }
                        return@pointerInput
                    }
                    fun currentDrawingTransform(): ImageEditingViewportTransform =
                        ImageEditingViewportTransform.create(
                            viewSize = ImageEditingSize(
                                canvasSize.width.toDouble(),
                                canvasSize.height.toDouble()
                            ),
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                            state = latestViewportState
                        )
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentDrawingTransform().viewToNormalized(
                                ImageEditingViewPoint(offset.x.toDouble(), offset.y.toDouble()),
                                clampToImage = false
                            )?.let { point ->
                                activeStroke = ImageMaskStroke(strokeMode, radius, listOf(point))
                            }
                        },
                        onDragCancel = { activeStroke = null },
                        onDragEnd = {
                            activeStroke?.let { stroke ->
                                try {
                                    commitMaskState(maskState.addStroke(stroke))
                                } catch (_: Exception) {
                                    error = "蒙版笔画过多，请撤销或清空后重试。"
                                }
                            }
                            activeStroke = null
                        }
                    ) { change, _ ->
                        val stroke = activeStroke ?: return@detectDragGestures
                        change.consume()
                        currentDrawingTransform().viewToNormalized(
                            ImageEditingViewPoint(change.position.x.toDouble(), change.position.y.toDouble())
                        )?.let { point ->
                            try {
                                activeStroke = stroke.appendPoint(point)
                            } catch (_: Exception) {
                                activeStroke = null
                                error = "单笔过长，请抬手后继续绘制。"
                            }
                        }
                    }
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = viewportState.zoom.toFloat()
                        scaleY = viewportState.zoom.toFloat()
                        translationX = viewportTransform?.panTranslation?.x?.toFloat() ?: 0f
                        translationY = viewportTransform?.panTranslation?.y?.toFloat() ?: 0f
                        transformOrigin = TransformOrigin.Center
                    }
            )
            MaskStrokeOverlay(
                bitmap = bitmap,
                canvasSize = canvasSize,
                state = maskState,
                activeStroke = activeStroke,
                viewportState = viewportState,
                positionedCursor = positionedCursor.takeIf { showPositionedDrawing },
                positionedCursorRadius = radius,
                positionedCursorColor = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showPositionedDrawing) {
        PositionedMaskDrawingDialog(
            cursor = positionedCursor,
            mode = strokeMode,
            radius = radius,
            stepFraction = positionedStep,
            error = positionedDrawingError,
            onStepFractionChange = { positionedStep = it },
            onMove = { horizontal, vertical ->
                positionedCursor = movedImageMaskCursor(
                    cursor = positionedCursor,
                    horizontalDirection = horizontal,
                    verticalDirection = vertical,
                    stepFraction = positionedStep
                )
            },
            onApply = {
                try {
                    commitMaskState(
                        maskState.applyPointAtCursor(
                            cursor = positionedCursor,
                            mode = strokeMode,
                            radius = radius
                        )
                    )
                    positionedDrawingError = null
                } catch (_: Exception) {
                    positionedDrawingError = "蒙版笔画过多，请关闭后撤销或清空。"
                }
            },
            onDismiss = {
                positionedDrawingError = null
                showPositionedDrawing = false
            }
        )
    }

    if (confirmDiscard) {
        DiscardImageEditsDialog(
            onDiscard = onBack,
            onKeepEditing = { confirmDiscard = false }
        )
    }
}

@Composable
private fun MaskStrokeOverlay(
    bitmap: Bitmap,
    canvasSize: IntSize,
    state: ImageMaskEditingState,
    activeStroke: ImageMaskStroke?,
    viewportState: ImageEditingViewportState,
    positionedCursor: NormalizedImagePoint?,
    positionedCursorRadius: Double,
    positionedCursorColor: Color
) {
    Canvas(Modifier.fillMaxSize()) {
        if (canvasSize == IntSize.Zero) return@Canvas
        val transform = ImageEditingViewportTransform.create(
            ImageEditingSize(size.width.toDouble(), size.height.toDouble()),
            bitmap.width,
            bitmap.height,
            viewportState
        )
        val imageBounds = transform.displayedImageBounds
        val scale = imageBounds.width / bitmap.width.toDouble()
        clipRect(
            left = imageBounds.left.toFloat(),
            top = imageBounds.top.toFloat(),
            right = imageBounds.right.toFloat(),
            bottom = imageBounds.bottom.toFloat()
        ) {
            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = IntOffset(
                    imageBounds.left.roundToInt(),
                    imageBounds.top.roundToInt()
                ),
                dstSize = IntSize(
                    imageBounds.width.roundToInt(),
                    imageBounds.height.roundToInt()
                )
            )
            if (state.baseGrayscaleValue == ImageMaskStrokeMode.BRUSH.grayscaleValue) {
                drawRect(
                    color = Color.White.copy(alpha = 0.46f),
                    topLeft = Offset(imageBounds.left.toFloat(), imageBounds.top.toFloat()),
                    size = Size(imageBounds.width.toFloat(), imageBounds.height.toFloat())
                )
            }
            (state.strokes + listOfNotNull(activeStroke)).forEach { stroke ->
                val color = if (stroke.mode == ImageMaskStrokeMode.BRUSH) {
                    Color.White.copy(alpha = 0.68f)
                } else {
                    Color.Black.copy(alpha = 0.72f)
                }
                val width = (stroke.radiusInPixels(bitmap.width, bitmap.height) * 2.0 * scale).toFloat()
                val points = stroke.points.map(transform::normalizedToView)
                if (points.size == 1) {
                    drawCircle(
                        color = color,
                        radius = width / 2f,
                        center = Offset(points.first().x.toFloat(), points.first().y.toFloat())
                    )
                } else {
                    points.zipWithNext().forEach { (from, to) ->
                        drawLine(
                            color = color,
                            start = Offset(from.x.toFloat(), from.y.toFloat()),
                            end = Offset(to.x.toFloat(), to.y.toFloat()),
                            strokeWidth = width,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
            positionedCursor?.let { cursor ->
                val center = transform.normalizedToView(cursor)
                val radiusPx = maxOf(
                    8.dp.toPx(),
                    (positionedCursorRadius * minOf(bitmap.width, bitmap.height) * scale).toFloat()
                )
                val centerOffset = Offset(center.x.toFloat(), center.y.toFloat())
                drawCircle(
                    color = Color.Black.copy(alpha = 0.88f),
                    radius = radiusPx,
                    center = centerOffset,
                    style = Stroke(width = 5.dp.toPx())
                )
                drawCircle(
                    color = positionedCursorColor,
                    radius = radiusPx,
                    center = centerOffset,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }
    }
}
