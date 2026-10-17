package com.muyuchat.feature.chat

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S22：从 ImageWorkspaceScreens.kt 等值移出的结果渲染簇。
// 原样逐字迁移，未做行为改动；2 个跨区入口按协议提升 internal。
@Composable
// P9/S22: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageGalleryTitleBar(onBack: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
            shape = CircleShape,
            shadowElevation = 7.dp
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(27.dp))
            }
        }
        Text(
            "图片",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
            fontWeight = FontWeight.Bold,
            color = if (darkTheme) MaterialTheme.colorScheme.onBackground else Color(0xFF202124)
        )
        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
// P9/S22: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageGenerationCanvas(
    prompt: String,
    job: ImageGenerationUiJob?,
    image: ImageAssetUiItem?,
    images: List<ImageAssetUiItem>,
    imageModels: List<ChatModelChoice>,
    imageModelsLoading: Boolean,
    selectedImageModelId: String?,
    selectedImageModelName: String?,
    selectedImageModelIsCloud: Boolean,
    onBackToGallery: () -> Unit,
    onSelectImageModel: (String) -> Unit,
    onRetry: () -> Unit,
    onCancelGeneration: () -> Unit,
    onUseImageAsset: (String) -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 126.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onBackToGallery,
                    color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                    shape = CircleShape,
                    shadowElevation = 7.dp
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回图片", modifier = Modifier.size(24.dp))
                        Text("图片", fontSize = 17.sp, color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        item {
            ImageEngineSwitcher(
                models = imageModels,
                selectedModelId = selectedImageModelId,
                selectedModelName = selectedImageModelName,
                selectedModelIsCloud = selectedImageModelIsCloud,
                // B45/S2: the canvas stays on screen after a terminal job; only a working job may keep
                // the pill disabled, so completion/failure/cancel can switch models again.
                enabled = job?.isWorking != true,
                loading = imageModelsLoading,
                onSelectModel = onSelectImageModel
            )
        }
        item {
            UserImagePromptBubble(prompt = prompt)
        }
        item {
            ImageAssistantResultCard(
                job = job,
                image = image,
                images = images,
                onRetry = onRetry,
                onCancelGeneration = onCancelGeneration,
                onUseImageAsset = onUseImageAsset
            )
        }
    }
}

@Composable
private fun UserImagePromptBubble(prompt: String) {
    val darkTheme = isSystemInDarkTheme()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.widthIn(max = 310.dp),
            color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F1F1),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                prompt.ifBlank { "正在准备图片请求" },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 15.dp),
                color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124),
                lineHeight = 23.sp,
                fontSize = 17.sp
            )
        }
    }
}

@Composable
private fun ImageAssistantResultCard(
    job: ImageGenerationUiJob?,
    image: ImageAssetUiItem?,
    images: List<ImageAssetUiItem> = image?.let(::listOf).orEmpty(),
    onRetry: () -> Unit,
    onCancelGeneration: () -> Unit,
    onUseImageAsset: (String) -> Unit
) {
    val fallbackStartedAtElapsedMs = remember(job?.id) { SystemClock.elapsedRealtime() }
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (imageAssistantCardKind(job, image)) {
            ImageAssistantCardKind.FAILURE -> ImageGenerationFailureCard(
                job = requireNotNull(job),
                onRetry = onRetry
            )
            ImageAssistantCardKind.RESULT -> ImageGenerationResultImage(
                image = requireNotNull(image),
                images = images,
                onUseImageAsset = onUseImageAsset
            )
            ImageAssistantCardKind.TERMINAL -> ImageGenerationTerminalCard(
                job = requireNotNull(job),
                onRetry = onRetry
            )
            ImageAssistantCardKind.CREATING -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ImageCreatingPlaceholder(
                        statusText = job?.statusLabel ?: "正在创建图片",
                        statusMessage = job?.message.orEmpty(),
                        startedAtElapsedMs = job?.startedAtElapsedMs ?: fallbackStartedAtElapsedMs,
                        previewUriString = job?.previewUriString,
                        previewStep = job?.previewStep ?: 0,
                        previewRevision = job?.previewRevision ?: 0L,
                        onCancel = onCancelGeneration
                    )
                    job?.translationPreview?.takeIf(String::isNotBlank)?.let { translated ->
                        Column(
                            modifier = Modifier
                                .widthIn(max = 360.dp)
                                .testTag("image.generation.translation-preview"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "英文草稿（可编辑）",
                                style = MaterialTheme.typography.labelSmall,
                                color = McaPrimaryBlue
                            )
                            SelectionContainer {
                                Text(
                                    translated,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImageCreatingPlaceholder(
    statusText: String,
    statusMessage: String,
    startedAtElapsedMs: Long,
    previewUriString: String?,
    previewStep: Int,
    previewRevision: Long,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val previewBitmap by rememberChatImageBitmap(
        context = context,
        uriString = previewUriString.orEmpty(),
        maxDimensionPx = 1_024,
        revisionKey = previewRevision
    )
    val previewBitmapValue = previewBitmap
    val showingPreview = previewBitmapValue != null
    val cardColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF5F8FF)
    val titleColor = if (showingPreview) {
        Color.White
    } else if (darkTheme) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color(0xFF31415F)
    }
    val bodyColor = if (showingPreview) {
        Color.White.copy(alpha = 0.88f)
    } else if (darkTheme) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Color(0xFF66748A)
    }
    val progressTrackColor = if (darkTheme) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    } else {
        Color.White.copy(alpha = 0.92f)
    }
    val transition = rememberInfiniteTransition(label = "image-create")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1600, easing = LinearEasing)),
        label = "image-create-phase"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "image-create-pulse"
    )
    var elapsedSeconds by remember(startedAtElapsedMs) {
        mutableStateOf(((SystemClock.elapsedRealtime() - startedAtElapsedMs) / 1000L).coerceAtLeast(0L))
    }
    LaunchedEffect(startedAtElapsedMs) {
        while (true) {
            elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAtElapsedMs) / 1000L).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val waitingText = when {
        showingPreview && previewStep > 0 -> "实时预览 · 第 $previewStep 步"
        elapsedSeconds >= 240L -> "已等待 ${elapsedSeconds.formatElapsed()}，本地生成仍在运行"
        elapsedSeconds >= 60L -> "已等待 ${elapsedSeconds.formatElapsed()}"
        else -> "已等待 ${elapsedSeconds} 秒"
    }
    Surface(
        modifier = Modifier
            .width(292.dp)
            .height(292.dp),
        color = cardColor,
        shape = RoundedCornerShape(26.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (previewBitmapValue != null) {
                Image(
                    bitmap = previewBitmapValue.asImageBitmap(),
                    contentDescription = "生成中的实际预览，第 $previewStep 步",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.30f))
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .zIndex(1f)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    if (statusText == "完成") "正在整理图片" else "正在创建图片",
                    color = titleColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    statusMessage.ifBlank { "MCA 正在等待图像引擎返回结果" },
                    color = bodyColor,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                Text(
                    waitingText,
                    color = if (showingPreview) Color.White else McaPrimaryBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onCancel, modifier = Modifier.testTag("image.cancel")) {
                    Text("取消生成")
                }
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!showingPreview) {
                    val dot = 1.7.dp.toPx()
                    val gap = 12.dp.toPx()
                    val groups = listOf(
                        Offset(size.width * 0.25f, size.height * 0.45f),
                        Offset(size.width * 0.62f, size.height * 0.72f)
                    )
                    groups.forEachIndexed { groupIndex, origin ->
                        repeat(6) { x ->
                            repeat(5) { y ->
                                val wave = ((x + y + groupIndex * 2) / 12f + phase) % 1f
                                val alpha = (0.18f + (1f - kotlin.math.abs(wave - 0.5f) * 2f) * 0.50f) * pulse
                                drawCircle(
                                    color = McaPrimaryBlue.copy(alpha = alpha.coerceIn(0.12f, 0.62f)),
                                    radius = dot,
                                    center = Offset(origin.x + x * gap, origin.y + y * gap)
                                )
                            }
                        }
                    }
                }
                val trackWidth = size.width * 0.64f
                val trackHeight = 7.dp.toPx()
                val trackLeft = size.width * 0.18f
                val trackTop = size.height - 48.dp.toPx()
                drawRoundRect(
                    color = progressTrackColor,
                    topLeft = Offset(trackLeft, trackTop),
                    size = Size(trackWidth, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                )
                val highlightWidth = trackWidth * 0.34f
                val highlightLeft = trackLeft + (trackWidth + highlightWidth) * phase - highlightWidth
                drawRoundRect(
                    color = McaPrimaryBlue.copy(alpha = 0.55f),
                    topLeft = Offset(highlightLeft.coerceIn(trackLeft - highlightWidth, trackLeft + trackWidth), trackTop),
                    size = Size(highlightWidth, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                )
            }
        }
    }
}

@Composable
private fun ImageGenerationFailureCard(job: ImageGenerationUiJob, onRetry: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF4F6F9),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("失败", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Column(modifier = Modifier.weight(1f)) {
                Text(job.prompt, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124))
                if (job.modelName.isNotBlank()) {
                    Text(job.modelName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
                Text(job.message.ifBlank { "图片生成失败" }, maxLines = 2, color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF5F6368), fontSize = 13.sp)
            }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun ImageGenerationResultImage(
    image: ImageAssetUiItem,
    images: List<ImageAssetUiItem>,
    onUseImageAsset: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val darkTheme = isSystemInDarkTheme()
    // Chat bubbles can contain user-selected photos straight from the gallery.
    // Never decode their full source dimensions synchronously during Compose:
    // large camera images can exhaust the UI process while the message is sent.
    val bitmap by rememberChatImageBitmap(context, image.uriString, maxDimensionPx = 512)
    val ratio = remember(image.width, image.height) {
        if (image.width > 0 && image.height > 0) {
            (image.width.toFloat() / image.height.toFloat()).coerceIn(0.72f, 1.78f)
        } else {
            1.22f
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.size > 1) {
            Text("共 ${images.size} 张", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                images.forEach { item ->
                    val thumbnail by rememberChatImageBitmap(
                        context,
                        item.uriString,
                        maxDimensionPx = 192
                    )
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFEDEFF1))
                            .clickable { onUseImageAsset(item.id) }
                    ) {
                        thumbnail?.let {
                            Image(it.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(0.92f)
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(22.dp))
                .background(if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFEDEFF1))
        ) {
        bitmap?.let { loadedBitmap ->
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } ?: run {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .clickable { onUseImageAsset(image.id) },
            color = Color.Black.copy(alpha = 0.54f),
            shape = CircleShape
        ) {
            Text(
                "编辑",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(13.dp)
                .size(48.dp)
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(42.dp),
                color = Color.Black.copy(alpha = 0.54f),
                shape = CircleShape
            ) {}
            IconButton(
                onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            createCachedImageShareIntent(
                                context = context,
                                image = image,
                                includePrompt = false
                            )
                        }
                        result
                            .mapCatching { intent -> context.startActivity(intent) }
                            .onFailure { error ->
                                Toast.makeText(
                                    context,
                                    error.message?.takeIf(String::isNotBlank) ?: "图片分享失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "分享图片",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

}
