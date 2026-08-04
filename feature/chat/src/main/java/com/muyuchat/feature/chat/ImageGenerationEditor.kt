package com.muyuchat.feature.chat

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal sealed interface GenerationImageEditorRequest {
    val sourceUri: String

    data class Crop(
        override val sourceUri: String,
        val role: String,
        val targetWidth: Int,
        val targetHeight: Int
    ) : GenerationImageEditorRequest

    data class Mask(override val sourceUri: String) : GenerationImageEditorRequest
}

private enum class MaskEditorInteractionMode {
    DRAW,
    PAN
}

@Composable
internal fun GenerationImageEditorScreen(
    request: GenerationImageEditorRequest,
    onBack: () -> Unit,
    onCropConfirmed: (role: String, ownedUri: String) -> Unit,
    onMaskConfirmed: (GenerationInpaintOwnedUris) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var decoded by remember(request) { mutableStateOf<DecodedGenerationImage?>(null) }
    var loadError by remember(request) { mutableStateOf<String?>(null) }
    LaunchedEffect(request) {
        decoded = null
        loadError = null
        var candidate: DecodedGenerationImage? = null
        try {
            withContext(Dispatchers.IO) {
                val decodingContext = currentCoroutineContext()
                ImageGenerationBitmapEditing.decodeBounded(
                    context = context,
                    rawUri = request.sourceUri,
                    checkCancelled = { decodingContext.ensureActive() }
                )
                    .also { candidate = it }
            }
            currentCoroutineContext().ensureActive()
            decoded = candidate
            candidate = null
        } catch (cancelled: CancellationException) {
            candidate?.bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            throw cancelled
        } catch (error: Exception) {
            candidate?.bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            loadError = error.userFacingEditingMessage("无法打开图片。")
        }
    }
    DisposableEffect(decoded) {
        val bitmap = decoded?.bitmap
        onDispose { if (bitmap?.isRecycled == false) bitmap.recycle() }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            loadError != null -> ImageEditorLoadFailure(
                message = requireNotNull(loadError),
                onBack = onBack
            )
            decoded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.semantics {
                    contentDescription = "正在载入图片编辑器"
                })
            }
            request is GenerationImageEditorRequest.Crop -> CropEditor(
                bitmap = requireNotNull(decoded).bitmap,
                sourceWidth = requireNotNull(decoded).sourceWidth,
                sourceHeight = requireNotNull(decoded).sourceHeight,
                sampleSize = requireNotNull(decoded).sampleSize,
                role = request.role,
                targetWidth = request.targetWidth,
                targetHeight = request.targetHeight,
                onBack = onBack,
                onConfirmed = onCropConfirmed
            )
            request is GenerationImageEditorRequest.Mask -> MaskEditor(
                bitmap = requireNotNull(decoded).bitmap,
                sourceWidth = requireNotNull(decoded).sourceWidth,
                sourceHeight = requireNotNull(decoded).sourceHeight,
                sampleSize = requireNotNull(decoded).sampleSize,
                onBack = onBack,
                onConfirmed = onMaskConfirmed
            )
        }
    }
}

@Composable
private fun CropEditor(
    bitmap: Bitmap,
    sourceWidth: Int,
    sourceHeight: Int,
    sampleSize: Int,
    role: String,
    targetWidth: Int,
    targetHeight: Int,
    onBack: () -> Unit,
    onConfirmed: (String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { GenerationImageOwnedInputStore(context) }
    val defaultCrop = remember(bitmap, targetWidth, targetHeight) {
        centeredNormalizedCropForTargetAspect(
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
    }
    var crop by remember(bitmap, targetWidth, targetHeight) { mutableStateOf(defaultCrop) }
    var dragStart by remember { mutableStateOf<NormalizedImagePoint?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = crop != defaultCrop
    val validCrop = crop.toExactTargetAspectPixelRectOrNull(
        imageWidth = bitmap.width,
        imageHeight = bitmap.height,
        targetWidth = targetWidth,
        targetHeight = targetHeight
    ) != null
    val smallerCrop = scaledFixedAspectNormalizedCrop(
        crop, bitmap.width, bitmap.height, targetWidth, targetHeight, scaleFactor = 0.9
    )
    val largerCrop = scaledFixedAspectNormalizedCrop(
        crop, bitmap.width, bitmap.height, targetWidth, targetHeight, scaleFactor = 1.1
    )

    fun moveCrop(horizontalDirection: Int, verticalDirection: Int) {
        crop = translatedFixedAspectNormalizedCrop(
            crop = crop,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            horizontalDirection = horizontalDirection,
            verticalDirection = verticalDirection
        )
    }

    fun requestBack() {
        if (saving) return
        if (dirty) confirmDiscard = true else onBack()
    }
    BackHandler(enabled = true, onBack = ::requestBack)

    ImageEditorScaffold(
        title = "裁剪图片",
        subtitle = "固定 ${targetWidth}:${targetHeight} 构图 · " +
            boundedImageSubtitle(sourceWidth, sourceHeight, bitmap, sampleSize),
        saving = saving,
        confirmEnabled = validCrop && !saving,
        confirmLabel = "使用裁剪",
        onBack = ::requestBack,
        onConfirm = {
            saving = true
            error = null
            scope.launch {
                try {
                    val ownedUri = withContext(Dispatchers.IO) {
                        val cropped = ImageGenerationBitmapEditing.crop(
                            bitmap = bitmap,
                            crop = crop,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight
                        )
                        try {
                            store.writeBitmap(cropped)
                        } finally {
                            if (cropped !== bitmap) cropped.recycle()
                        }
                    }
                    onConfirmed(role, ownedUri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (saveError: Exception) {
                    error = saveError.userFacingEditingMessage("无法保存裁剪图片。")
                    saving = false
                }
            }
        },
        controls = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "裁剪框大小",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { crop = defaultCrop },
                    enabled = dirty && !saving,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("重置") }
                IconButton(
                    onClick = { crop = smallerCrop },
                    enabled = smallerCrop != crop && !saving,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "缩小裁剪框，放大保留内容")
                }
                IconButton(
                    onClick = { crop = largerCrop },
                    enabled = largerCrop != crop && !saving,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "放大裁剪框，缩小保留内容")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "移动裁剪框",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = { moveCrop(-1, 0) },
                    enabled = !saving,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "向左移动裁剪框")
                }
                IconButton(
                    onClick = { moveCrop(0, -1) },
                    enabled = !saving,
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "向上移动裁剪框") }
                IconButton(
                    onClick = { moveCrop(0, 1) },
                    enabled = !saving,
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "向下移动裁剪框") }
                IconButton(
                    onClick = { moveCrop(1, 0) },
                    enabled = !saving,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "向右移动裁剪框")
                }
            }
        },
        error = error ?: if (!validCrop) "裁剪区域短边至少需要 32 像素。" else null
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { canvasSize = it }
                .semantics {
                    contentDescription = "裁剪画布。拖动手指选择要保留的图片区域。"
                    stateDescription = if (dirty) "已调整固定比例裁剪区域" else "当前为默认固定比例裁剪区域"
                }
                .pointerInput(bitmap, canvasSize, saving) {
                    if (saving || canvasSize == IntSize.Zero) return@pointerInput
                    val transform = ImageFitTransform.create(
                        ImageEditingSize(canvasSize.width.toDouble(), canvasSize.height.toDouble()),
                        bitmap.width,
                        bitmap.height
                    )
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = transform.viewToNormalized(
                                ImageEditingViewPoint(offset.x.toDouble(), offset.y.toDouble()),
                                clampToImage = false
                            )
                        },
                        onDragCancel = { dragStart = null },
                        onDragEnd = { dragStart = null }
                    ) { change, _ ->
                        change.consume()
                        val start = dragStart ?: return@detectDragGestures
                        val current = transform.viewToNormalized(
                            ImageEditingViewPoint(change.position.x.toDouble(), change.position.y.toDouble())
                        ) ?: return@detectDragGestures
                        fixedAspectNormalizedCropFromDragOrNull(
                            anchor = start,
                            current = current,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight
                        )?.let { crop = it }
                    }
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            CropOverlay(bitmap = bitmap, canvasSize = canvasSize, crop = crop)
        }
    }

    if (confirmDiscard) {
        DiscardImageEditsDialog(
            onDiscard = onBack,
            onKeepEditing = { confirmDiscard = false }
        )
    }
}

@Composable
private fun MaskEditor(
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
    BackHandler(enabled = true, onBack = ::requestBack)

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
                FilterChip(
                    selected = interactionMode == MaskEditorInteractionMode.DRAW,
                    onClick = { interactionMode = MaskEditorInteractionMode.DRAW },
                    enabled = !saving,
                    label = { Text("绘制") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
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
                IconButton(
                    onClick = { setViewportZoom(viewportState.zoom / 1.25) },
                    enabled = viewportState.zoom > ImageEditingViewportState.MIN_ZOOM && !saving,
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Default.ZoomOut, contentDescription = "缩小画布") }
                IconButton(
                    onClick = { setViewportZoom(viewportState.zoom * 1.25) },
                    enabled = viewportState.zoom < ImageEditingViewportState.MAX_ZOOM && !saving,
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Default.ZoomIn, contentDescription = "放大画布") }
                TextButton(
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
                FilterChip(
                    selected = strokeMode == ImageMaskStrokeMode.BRUSH,
                    onClick = { strokeMode = ImageMaskStrokeMode.BRUSH },
                    enabled = !saving,
                    label = { Text("画笔") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
                    selected = strokeMode == ImageMaskStrokeMode.ERASER,
                    onClick = { strokeMode = ImageMaskStrokeMode.ERASER },
                    enabled = !saving,
                    label = { Text("橡皮") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                Spacer(Modifier.weight(1f))
                TextButton(
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
                TextButton(
                    onClick = { commitMaskState(fullRepaintState) },
                    enabled = fullRepaintState != maskState && !saving,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("全图重绘") }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = ::undoMaskState,
                    enabled = undoStates.isNotEmpty() && !saving,
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销上一笔") }
                IconButton(
                    onClick = ::redoMaskState,
                    enabled = redoStates.isNotEmpty() && !saving,
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "重做下一笔") }
                IconButton(
                    onClick = { commitMaskState(maskState.clear()) },
                    enabled = maskState != emptyMaskState && !saving,
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Default.Clear, contentDescription = "清空蒙版") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("笔刷", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                Slider(
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
private fun ImageEditorScaffold(
    title: String,
    subtitle: String,
    saving: Boolean,
    confirmEnabled: Boolean,
    confirmLabel: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    controls: @Composable () -> Unit,
    error: String?,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        enabled = !saving,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    TextButton(
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("保存中")
                        } else {
                            Text(confirmLabel)
                        }
                    }
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) { content() }
        Surface(tonalElevation = 2.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                controls()
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun CropOverlay(bitmap: Bitmap, canvasSize: IntSize, crop: NormalizedImageRect) {
    Canvas(Modifier.fillMaxSize()) {
        if (canvasSize == IntSize.Zero) return@Canvas
        val transform = ImageFitTransform.create(
            ImageEditingSize(size.width.toDouble(), size.height.toDouble()),
            bitmap.width,
            bitmap.height
        )
        val first = transform.normalizedToView(NormalizedImagePoint(crop.left, crop.top))
        val second = transform.normalizedToView(NormalizedImagePoint(crop.right, crop.bottom))
        val left = first.x.toFloat()
        val top = first.y.toFloat()
        val right = second.x.toFloat()
        val bottom = second.y.toFloat()
        val shade = Color.Black.copy(alpha = 0.58f)
        drawRect(shade, Offset(0f, 0f), Size(size.width, top.coerceAtLeast(0f)))
        drawRect(shade, Offset(0f, bottom), Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
        drawRect(shade, Offset(0f, top), Size(left.coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)))
        drawRect(shade, Offset(right, top), Size((size.width - right).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)))
        drawRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 2.dp.toPx())
        )
        val gridColor = Color.White.copy(alpha = 0.55f)
        repeat(2) { index ->
            val fraction = (index + 1) / 3f
            val x = left + (right - left) * fraction
            val y = top + (bottom - top) * fraction
            drawLine(gridColor, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            drawLine(gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
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

@Composable
private fun PositionedMaskDrawingDialog(
    cursor: NormalizedImagePoint,
    mode: ImageMaskStrokeMode,
    radius: Double,
    stepFraction: Double,
    error: String?,
    onStepFractionChange: (Double) -> Unit,
    onMove: (horizontalDirection: Int, verticalDirection: Int) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定位绘制") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "位置 X ${(cursor.x * 100).roundToInt()}% · Y ${(cursor.y * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${if (mode == ImageMaskStrokeMode.BRUSH) "画笔（白色重绘）" else "橡皮（黑色保留）"}" +
                        " · 大小 ${(radius * 100).roundToInt()}% 短边",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.05 to "步长 5%", 0.10 to "步长 10%").forEach { (step, label) ->
                        FilterChip(
                            selected = stepFraction == step,
                            onClick = { onStepFractionChange(step) },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = { onMove(0, -1) },
                        modifier = Modifier.size(48.dp)
                    ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "光标向上移动") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onMove(-1, 0) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "光标向左移动")
                    }
                    Spacer(Modifier.size(48.dp))
                    IconButton(
                        onClick = { onMove(1, 0) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "光标向右移动")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = { onMove(0, 1) },
                        modifier = Modifier.size(48.dp)
                    ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "光标向下移动") }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApply,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("在当前位置应用") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("关闭") }
        }
    )
}

@Composable
private fun ImageEditorLoadFailure(message: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("无法编辑这张图片", style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onBack,
            modifier = Modifier
                .padding(top = 16.dp)
                .heightIn(min = 48.dp)
        ) { Text("返回") }
    }
}

@Composable
private fun DiscardImageEditsDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("放弃本次编辑？") },
        text = { Text("未保存的裁剪或蒙版笔画将丢失。") },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text("放弃") }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text("继续编辑") }
        }
    )
}

private fun boundedImageSubtitle(
    sourceWidth: Int,
    sourceHeight: Int,
    bitmap: Bitmap,
    sampleSize: Int
): String = if (sampleSize > 1) {
    "${sourceWidth}×${sourceHeight} 已安全缩放为 ${bitmap.width}×${bitmap.height} 编辑"
} else {
    "${bitmap.width}×${bitmap.height}"
}

private fun Exception.userFacingEditingMessage(fallback: String): String =
    message
        ?.takeIf(String::isNotBlank)
        ?.takeIf { raw -> raw.any { character -> character in '\u4E00'..'\u9FFF' } }
        ?.takeIf { raw -> '/' !in raw && '\\' !in raw }
        ?.take(160)
        ?: fallback
