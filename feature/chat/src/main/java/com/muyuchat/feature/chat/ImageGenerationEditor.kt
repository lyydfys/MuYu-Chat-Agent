@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.chat

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    BackHandler(enabled = !WindowInsets.isImeVisible, onBack = ::requestBack)

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

internal fun boundedImageSubtitle(
    sourceWidth: Int,
    sourceHeight: Int,
    bitmap: Bitmap,
    sampleSize: Int
): String = if (sampleSize > 1) {
    "${sourceWidth}×${sourceHeight} 已安全缩放为 ${bitmap.width}×${bitmap.height} 编辑"
} else {
    "${bitmap.width}×${bitmap.height}"
}

internal fun Exception.userFacingEditingMessage(fallback: String): String =
    message
        ?.takeIf(String::isNotBlank)
        ?.takeIf { raw -> raw.any { character -> character in '\u4E00'..'\u9FFF' } }
        ?.takeIf { raw -> '/' !in raw && '\\' !in raw }
        ?.take(160)
        ?: fallback
