package com.muyuchat.feature.chat

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaPressScale

/**
 * T2B22-T1（b 工位）拆片：ImageAssetPreviewOverlay（@Composable internal）自
 * ImageAssetScreens.kt 逐字节平移；零改写。closure 读面 union 由 T1-A 登记执行
 * （ImageUpscaleProductClosureTest chat 拼接追加本文件）。
 */

@Composable
// P9/S23: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageAssetPreviewOverlay(
    image: ImageAssetUiItem,
    upscalers: List<ImageUpscalerUiItem>,
    selectedUpscalerId: String?,
    upscalerImporting: Boolean,
    upscalerMessage: String,
    upscaleJob: ImageUpscaleUiJob?,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    canUseAsImg2Img: Boolean,
    canUseUltraFix: Boolean,
    onUseAsImg2Img: () -> Unit,
    onUseUltraFix: () -> Unit,
    onImportUpscaler: () -> Unit,
    onDeleteUpscaler: (String) -> Unit,
    onSelectUpscaler: (String) -> Unit,
    onUpscale: (Int) -> Unit,
    onCancelUpscale: () -> Unit,
    onSetFavorite: (Boolean) -> Unit,
    reusablePresetFields: Set<ImageGenerationPresetField>,
    hiddenPresetFieldCount: Int,
    onUseParameters: (ImageGenerationUiPreset, Set<ImageGenerationPresetField>) -> Unit,
    onRecreate: () -> Unit,
    recreateEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val bitmap by rememberChatImageBitmap(context, image.uriString, maxDimensionPx = 1280)
    var showGenerationDetails by rememberSaveable(image.id) { mutableStateOf(false) }
    var showParameterReuse by rememberSaveable(image.id) { mutableStateOf(false) }
    var showUpscaleDialog by rememberSaveable(image.id) { mutableStateOf(false) }
    var upscaleTargetScale by rememberSaveable(image.id) { mutableStateOf(2) }
    var pendingUpscalerDeleteId by rememberSaveable(image.id) { mutableStateOf<String?>(null) }
    var showActionsMenu by remember(image.id) { mutableStateOf(false) }
    var savingToGallery by remember(image.id) { mutableStateOf(false) }
    var selectedPresetFields by remember(image.id) {
        mutableStateOf(emptySet<ImageGenerationPresetField>())
    }
    val sourceUpscaleRunning = upscaleJob?.running == true && upscaleJob.sourceImageId == image.id
    // Full-screen preview entrance/exit: one progress value drives alpha and the 0.96 -> 1 scale
    // inside a graphicsLayer, so no animated value is read during composition. Closing plays to the
    // end before the host is told to remove the preview.
    val entryProgress = remember { Animatable(0f) }
    var previewClosing by remember { mutableStateOf(false) }
    val closeInteraction = remember { MutableInteractionSource() }
    val favoriteInteraction = remember { MutableInteractionSource() }
    val moreInteraction = remember { MutableInteractionSource() }
    val recreateInteraction = remember { MutableInteractionSource() }
    LaunchedEffect(Unit) {
        entryProgress.animateTo(1f, McaMotion.contentEnter)
    }
    val dismissWithMotion: () -> Unit = {
        if (!previewClosing) {
            previewClosing = true
            scope.launch {
                entryProgress.animateTo(0f, McaMotion.fade(millis = 150))
                onDismiss()
            }
        }
    }
    // The workspace registers its own back handler; this later, preview-only callback wins while
    // the overlay is composed and gives Back the same played-out close as the X button.
    BackHandler(enabled = !previewClosing) { dismissWithMotion() }
    Box(
        modifier = modifier
            .testTag("image.preview.${image.id}")
            .fillMaxSize()
            .graphicsLayer {
                val progress = entryProgress.value
                alpha = progress
                val scale = 0.96f + 0.04f * progress
                scaleX = scale
                scaleY = scale
            }
            .background(Color.Black.copy(alpha = 0.92f))
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color.White.copy(alpha = 0.13f),
                shape = CircleShape,
                modifier = Modifier.mcaPressScale(closeInteraction)
            ) {
                IconButton(onClick = dismissWithMotion, interactionSource = closeInteraction) {
                    Icon(Icons.Default.Close, contentDescription = "关闭预览", tint = Color.White)
                }
            }
            Text(
                image.name,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Surface(
                color = Color.White.copy(alpha = 0.13f),
                shape = CircleShape,
                modifier = Modifier.mcaPressScale(favoriteInteraction)
            ) {
                IconButton(
                    onClick = { onSetFavorite(!image.favorite) },
                    interactionSource = favoriteInteraction
                ) {
                    Icon(
                        if (image.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (image.favorite) "取消收藏" else "收藏图片",
                        tint = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box {
                Surface(
                    color = Color.White.copy(alpha = 0.13f),
                    shape = CircleShape,
                    modifier = Modifier.mcaPressScale(moreInteraction)
                ) {
                    IconButton(
                        onClick = { showActionsMenu = true },
                        interactionSource = moreInteraction
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多图片操作", tint = Color.White)
                    }
                }
                DropdownMenu(
                    expanded = showActionsMenu,
                    onDismissRequest = { showActionsMenu = false }
                ) {
                    if (image.generationDetails.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("查看生成参数") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = {
                                showActionsMenu = false
                                showGenerationDetails = true
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("作为图生图输入") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        enabled = canUseAsImg2Img,
                        onClick = {
                            showActionsMenu = false
                            onUseAsImg2Img()
                        }
                    )
                    if (canUseUltraFix) {
                        DropdownMenuItem(
                            text = { Text("使用 UltraFix 精修") },
                            leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null) },
                            onClick = {
                                showActionsMenu = false
                                onUseUltraFix()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("高清放大") },
                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                        onClick = {
                            showActionsMenu = false
                            showUpscaleDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("分享图片") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            showActionsMenu = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (savingToGallery) "保存中…" else "保存到系统图片库") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        enabled = !savingToGallery,
                        onClick = {
                            showActionsMenu = false
                            scope.launch {
                                savingToGallery = true
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        downloadImageAssetToGallery(context, image)
                                    }
                                    result
                                        .onSuccess { path ->
                                            Toast.makeText(context, "已保存到 $path", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { error ->
                                            Toast.makeText(
                                                context,
                                                error.message ?: "图片保存失败",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                } finally {
                                    savingToGallery = false
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (sourceUpscaleRunning) "放大中，暂不可删除" else "删除图片",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        enabled = !sourceUpscaleRunning,
                        onClick = {
                            showActionsMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
        bitmap?.let { loadedBitmap ->
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .testTag("image.preview.bitmap.${image.id}")
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 12.dp, vertical = 76.dp)
            )
        } ?: run {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(42.dp))
                Text("无法读取图片", color = Color.White.copy(alpha = 0.82f))
            }
        }
        if (image.prompt.isNotBlank() || image.createdAtText.isNotBlank() || image.generationDetails.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "${image.source.displayImageSource()} · ${image.createdAtText} · ${image.sizeText}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp
                    )
                    if (image.prompt.isNotBlank()) {
                        Text(
                            image.prompt,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White.copy(alpha = 0.86f),
                            fontSize = 13.sp
                        )
                    }
                    if (image.generationDetails.isNotBlank()) {
                        Text(
                            image.generationDetails,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp
                        )
                    }
                    if (image.canRecreate) {
                        TextButton(
                            onClick = onRecreate,
                            enabled = recreateEnabled,
                            modifier = Modifier.mcaPressScale(
                                recreateInteraction,
                                enabled = recreateEnabled
                            ),
                            interactionSource = recreateInteraction
                        ) {
                            Text("按原参数再生成", color = Color.White)
                        }
                    }
                }
                }
            }
        }
    if (showUpscaleDialog && pendingUpscalerDeleteId == null) {
        val selectedUpscaler = upscalers.firstOrNull { it.id == selectedUpscalerId }
        val currentImageJob = upscaleJob?.takeIf { it.sourceImageId == image.id }
        val anotherImageRunning = upscaleJob?.running == true && currentImageJob == null
        val outputDimensions = upscaleOutputDimensionsOrNull(
            image.width,
            image.height,
            upscaleTargetScale
        )
        AlertDialog(
            onDismissRequest = { showUpscaleDialog = false },
            title = { Text("高清放大") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "选择本地 ESRGAN 模型和目标倍率。任务会保留当前源图与模型快照，结果完成后自动进入图片库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("放大模型", style = MaterialTheme.typography.titleSmall)
                    if (upscalers.isEmpty()) {
                        Text(
                            if (upscalerImporting) "正在导入放大模型…" else "尚未导入放大模型。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        upscalers.forEach { upscaler ->
                            FilterChip(
                                selected = upscaler.id == selectedUpscalerId,
                                onClick = { onSelectUpscaler(upscaler.id) },
                                enabled = upscaleJob?.running != true && !upscaler.deleting,
                                label = {
                                    Column {
                                        Text(upscaler.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${upscaler.sizeText} · ${upscaler.sha256.take(12)}…",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onImportUpscaler,
                            enabled = !upscalerImporting && upscaleJob?.running != true
                        ) {
                            Text(if (upscalerImporting) "导入中…" else "导入模型")
                        }
                        TextButton(
                            onClick = {
                                selectedUpscaler?.let { pendingUpscalerDeleteId = it.id }
                            },
                            enabled = selectedUpscaler != null &&
                                !selectedUpscaler.inUse &&
                                !selectedUpscaler.deleting &&
                                upscaleJob?.running != true
                        ) {
                            Text("删除所选", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text("目标倍率", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IMAGE_UPSCALE_TARGET_SCALES.forEach { scale ->
                            FilterChip(
                                selected = upscaleTargetScale == scale,
                                onClick = { upscaleTargetScale = scale },
                                enabled = upscaleJob?.running != true,
                                label = { Text("${scale}x") }
                            )
                        }
                    }
                    outputDimensions?.let { (width, height) ->
                        Text(
                            "${image.width}x${image.height} → ${width}x$height",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (image.width > 0 && image.height > 0 && outputDimensions == null) {
                        Text(
                            "当前尺寸与倍率超出本地放大上限（输入 2048/4MP，输出 4096/16MP）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    val status = when {
                        currentImageJob != null -> currentImageJob.message.ifBlank {
                            currentImageJob.statusLabel
                        }
                        anotherImageRunning -> "另一张图片正在放大，请等待完成或先停止该任务。"
                        else -> upscalerMessage
                    }
                    if (status.isNotBlank()) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (currentImageJob?.failed == true) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (currentImageJob?.running == true) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                if (currentImageJob?.running == true) {
                    Button(onClick = onCancelUpscale) { Text("停止") }
                } else {
                    Button(
                        onClick = { onUpscale(upscaleTargetScale) },
                        enabled = selectedUpscaler != null &&
                            !upscalerImporting &&
                            !anotherImageRunning &&
                            (image.width <= 0 || image.height <= 0 || outputDimensions != null)
                    ) {
                        Text(if (currentImageJob?.failed == true) "重试放大" else "开始放大")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpscaleDialog = false }) { Text("关闭") }
            }
        )
    }
    pendingUpscalerDeleteId?.let { upscalerId ->
        val upscaler = upscalers.firstOrNull { it.id == upscalerId }
        AlertDialog(
            onDismissRequest = { pendingUpscalerDeleteId = null },
            title = { Text("删除放大模型") },
            text = {
                Text("确定删除 ${upscaler?.name ?: "所选模型"}？模型文件会从本机移除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUpscalerDeleteId = null
                        onDeleteUpscaler(upscalerId)
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUpscalerDeleteId = null }) { Text("取消") }
            }
        )
    }
    if (showGenerationDetails) {
        AlertDialog(
            onDismissRequest = { showGenerationDetails = false },
            title = { Text("生成参数") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(image.generationDetails, style = MaterialTheme.typography.bodyMedium)
                    val reproduciblePrompt = image.generationPrompt.ifBlank { image.prompt }
                    if (reproduciblePrompt.isNotBlank()) {
                        Text("提示词", style = MaterialTheme.typography.titleSmall)
                        Text(reproduciblePrompt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Row {
                    if (image.parameterShareJson.isNotBlank()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "MCA image parameters",
                                                image.parameterShareJson
                                            )
                                        )
                                    )
                                    Toast.makeText(context, "生成参数已复制", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("复制参数")
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val encoded = runCatching {
                                        ImageGenerationParameterImportCodec.encodeMcaBase64(
                                            image.parameterShareJson
                                        )
                                    }.getOrElse { error ->
                                        Toast.makeText(
                                            context,
                                            error.message?.takeIf(String::isNotBlank) ?: "参数编码失败",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "MCA image parameters Base64",
                                                encoded
                                            )
                                        )
                                    )
                                    Toast.makeText(context, "Base64 参数已复制", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("复制 Base64")
                        }
                    }
                    if (image.generationPreset != null) {
                        TextButton(
                            onClick = {
                                selectedPresetFields = reusablePresetFields
                                showGenerationDetails = false
                                showParameterReuse = true
                            }
                        ) {
                            Text("复用参数")
                        }
                    }
                    if (image.canRecreate) {
                        TextButton(
                            onClick = {
                                showGenerationDetails = false
                                onRecreate()
                            },
                            enabled = recreateEnabled
                        ) {
                            Text("按原参数再生成")
                        }
                    }
                    TextButton(onClick = { showGenerationDetails = false }) {
                        Text("关闭")
                    }
                }
            }
        )
    }
    if (showParameterReuse) {
        val preset = image.generationPreset
        if (preset == null) {
            showParameterReuse = false
        } else {
            val availableFields = reusablePresetFields
            AlertDialog(
                onDismissRequest = { showParameterReuse = false },
                title = { Text("选择要复用的参数") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            buildString {
                                append("参数会填入当前模型的生成面板，不会立即开始生成。")
                                if (hiddenPresetFieldCount > 0) {
                                    append(" 当前模型不兼容的 ")
                                    append(hiddenPresetFieldCount)
                                    append(" 项参数已隐藏。")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        availableFields.forEach { field ->
                            FilterChip(
                                selected = field in selectedPresetFields,
                                onClick = {
                                    selectedPresetFields = if (field in selectedPresetFields) {
                                        selectedPresetFields - field
                                    } else {
                                        selectedPresetFields + field
                                    }
                                },
                                label = { Text(field.label) }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onUseParameters(preset, selectedPresetFields)
                            showParameterReuse = false
                        },
                        enabled = selectedPresetFields.isNotEmpty()
                    ) { Text("应用") }
                },
                dismissButton = {
                    TextButton(onClick = { showParameterReuse = false }) { Text("取消") }
                }
            )
        }
    }
}
