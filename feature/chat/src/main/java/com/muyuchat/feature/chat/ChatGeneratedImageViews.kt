package com.muyuchat.feature.chat

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
// P9/S17: private→internal（跨区入口：迁移的消息气泡复合区引用）
internal fun ChatGeneratedImageContent(
    request: ChatGeneratedImageRequest,
    job: ImageGenerationUiJob?,
    visualState: ChatGeneratedImageUiState,
    images: List<ImageAssetUiItem>,
    readOnly: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onContinueAssistantImageTurn: () -> Unit,
    onOpenImageModels: () -> Unit,
    onUseImageAsset: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedImageId by remember(request.id) { mutableStateOf(images.firstOrNull()?.id) }
    var previewImageId by remember(request.id) { mutableStateOf<String?>(null) }
    val selectedImage = images.firstOrNull { it.id == selectedImageId } ?: images.firstOrNull()
    val darkTheme = isSystemInDarkTheme()

    when (visualState) {
        ChatGeneratedImageUiState.AWAITING_APPROVAL -> {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "角色请求生成图片",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        request.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                    chatGeneratedImageModelLabel(request)?.let { modelLabel ->
                        Text(
                            "将使用：$modelLabel",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (request.backendId.equals("CLOUD", ignoreCase = true)) {
                        Text(
                            "确认后会将这段提示词发送给所选云端生图服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        request.message.ifBlank { "是否允许使用当前图像模型生成这张图片？" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!readOnly) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onApprove) { Text("同意生成") }
                            TextButton(onClick = onReject) { Text("拒绝") }
                        }
                    }
                }
            }
        }
        ChatGeneratedImageUiState.RUNNING -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    request.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                chatGeneratedImageModelLabel(request)?.let { modelLabel ->
                    Text(
                        modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (readOnly) {
                    Text(request.message, style = MaterialTheme.typography.bodySmall)
                } else if (job != null) {
                    ImageCreatingPlaceholder(
                        statusText = job.statusLabel.ifBlank { "生成中" },
                        statusMessage = job.message.ifBlank { request.message },
                        startedAtElapsedMs = job.startedAtElapsedMs
                            ?: remember(job.id) { SystemClock.elapsedRealtime() },
                        previewUriString = job.previewUriString,
                        previewStep = job.previewStep,
                        previewRevision = job.previewRevision,
                        onCancel = onCancel
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            request.message.ifBlank { "正在准备图片生成。" },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                }
            }
        }
        ChatGeneratedImageUiState.RESULT -> {
            if (selectedImage != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        request.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    chatGeneratedImageModelLabel(request)?.let { modelLabel ->
                        Text(
                            modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL) {
                        val continuationMessage = when (request.toolContinuationStatus) {
                            ChatImageToolContinuationStatus.RUNNING -> "正在继续角色回复…"
                            ChatImageToolContinuationStatus.FAILED -> "图片已完成，角色后续回复未完成。可以重试继续。"
                            ChatImageToolContinuationStatus.NOT_STARTED -> "图片已完成，正在准备角色回复。"
                            ChatImageToolContinuationStatus.COMPLETED -> null
                        }
                        continuationMessage?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (images.size > 1) {
                        Text(
                            "共 ${images.size} 张 · 点击图片查看大图",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ChatGeneratedImageGrid(
                            images = images,
                            selectedImageId = selectedImage.id,
                            onSelect = { imageId ->
                                selectedImageId = imageId
                                previewImageId = imageId
                            }
                        )
                    } else {
                        // Never decode an original camera/PNG at full resolution in the
                        // composition path.  A single 12K image can otherwise allocate tens of
                        // megabytes and take the UI process down when a chat image is selected.
                        val bitmap by rememberChatImageBitmap(
                            context = context,
                            uriString = selectedImage.uriString,
                            maxDimensionPx = 1536
                        )
                        val bitmapValue = bitmap
                        val ratio = remember(selectedImage.width, selectedImage.height) {
                            if (selectedImage.width > 0 && selectedImage.height > 0) {
                                (selectedImage.width.toFloat() / selectedImage.height.toFloat())
                                    .coerceIn(0.5f, 2.0f)
                            } else {
                                1f
                            }
                        }
                        Box(
                            modifier = Modifier
                                .widthIn(max = 480.dp)
                                .fillMaxWidth(0.94f)
                                .aspectRatio(ratio)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { previewImageId = selectedImage.id },
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmapValue != null) {
                                Image(
                                    bitmap = bitmapValue.asImageBitmap(),
                                    contentDescription = "生成图片 ${selectedImage.width}×${selectedImage.height}，点击查看大图",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    "图片文件暂时无法读取",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (images.size > 1) {
                        val selectedIndex = images.indexOfFirst { it.id == selectedImage.id } + 1
                        Text(
                            "当前选择：第 ${selectedIndex.coerceAtLeast(1)} 张",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { previewImageId = selectedImage.id }) { Text("查看") }
                        if (!readOnly) {
                            TextButton(onClick = { onUseImageAsset(selectedImage.id) }) { Text("编辑") }
                            TextButton(onClick = onRetry) { Text("再生成") }
                            TextButton(onClick = { onDeleteImageAsset(selectedImage.id) }) { Text("删除") }
                        }
                        if (!readOnly && request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
                            request.toolContinuationStatus in setOf(
                                ChatImageToolContinuationStatus.NOT_STARTED,
                                ChatImageToolContinuationStatus.FAILED
                            )
                        ) {
                            TextButton(onClick = onContinueAssistantImageTurn) { Text("继续角色回复") }
                        }
                    }
                }
            }
        }
        ChatGeneratedImageUiState.FAILED,
        ChatGeneratedImageUiState.CANCELLED,
        ChatGeneratedImageUiState.INTERRUPTED,
        ChatGeneratedImageUiState.MISSING_RESULT -> {
            val headline = when (visualState) {
                ChatGeneratedImageUiState.FAILED -> "图片生成失败"
                ChatGeneratedImageUiState.CANCELLED -> "图片生成已取消"
                ChatGeneratedImageUiState.INTERRUPTED -> "图片生成中断"
                ChatGeneratedImageUiState.MISSING_RESULT -> "图片文件不可用"
                else -> "图片生成未完成"
            }
            Surface(
                color = if (visualState == ChatGeneratedImageUiState.FAILED) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.34f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(headline, style = MaterialTheme.typography.labelLarge)
                    Text(
                        request.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    chatGeneratedImageModelLabel(request)?.let { modelLabel ->
                        Text(
                            modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        job?.message?.takeIf(String::isNotBlank)
                            ?: request.message.ifBlank { "图片没有保存到本地图库。" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!readOnly) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRetry) { Text("重试") }
                        TextButton(onClick = onOpenImageModels) { Text("生图模型") }
                        if (request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
                            request.toolContinuationStatus in setOf(
                                ChatImageToolContinuationStatus.NOT_STARTED,
                                ChatImageToolContinuationStatus.FAILED
                            )
                        ) {
                            TextButton(onClick = onContinueAssistantImageTurn) { Text("继续角色回复") }
                        }
                    }
                }
            }
        }
    }

    val previewImage = images.firstOrNull { it.id == previewImageId }
    if (previewImage != null) {
        // The full-screen preview still uses a bounded bitmap; the original file remains
        // available for save/share and is never decoded into the Compose heap.
        val previewBitmap by rememberChatImageBitmap(
            context = context,
            uriString = previewImage.uriString,
            maxDimensionPx = 2048
        )
        val previewBitmapValue = previewBitmap
        Dialog(
            onDismissRequest = { previewImageId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .heightIn(max = 760.dp),
                shape = RoundedCornerShape(18.dp),
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${previewImage.width}×${previewImage.height}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { previewImageId = null }) { Text("关闭") }
                    }
                    if (previewBitmapValue != null) {
                        Image(
                            bitmap = previewBitmapValue.asImageBitmap(),
                            contentDescription = previewImage.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 680.dp)
                        )
                    } else {
                        Text("图片文件暂时无法读取。")
                    }
                }
            }
        }
    }
}

