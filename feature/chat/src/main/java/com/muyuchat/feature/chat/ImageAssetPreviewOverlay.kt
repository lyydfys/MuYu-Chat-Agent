package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaChip
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaFeedbackPopup
import com.muyuchat.core.designsystem.rememberMcaFeedbackState

import android.content.ClipData
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
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.muyuchat.core.designsystem.McaChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.muyuchat.core.designsystem.rememberMcaNotify
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.McaIconSize

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
    val notify = rememberMcaNotify()
    // The old nullable bitmap conflated "still decoding" with "decode failed": the failure copy
    // appeared during loading and there was no retry. Reuse the existing ChatImageLoadState so the
    // preview renders Loading / Ready / Failed honestly; bumping the revision restarts the same
    // producer (no new state type). The asset id is the display owner identity, so another asset
    // shown at the same position cannot keep the previous one's frame.
    var imageLoadRevision by remember(image.uriString) { mutableStateOf(0) }
    val imageLoadState = rememberChatImageLoadState(
        context = context,
        uriString = image.uriString,
        maxDimensionPx = 1280,
        revisionKey = imageLoadRevision,
        taskIdentity = image.id
    )
    var showGenerationDetails by rememberSaveable(image.id) { mutableStateOf(false) }
    var showParameterReuse by rememberSaveable(image.id) { mutableStateOf(false) }
    var showUpscaleDialog by rememberSaveable(image.id) { mutableStateOf(false) }
    var upscaleTargetScale by rememberSaveable(image.id) { mutableStateOf(2) }
    var pendingUpscalerDeleteId by rememberSaveable(image.id) { mutableStateOf<String?>(null) }
    var selectedPresetFields by remember(image.id) {
        mutableStateOf(emptySet<ImageGenerationPresetField>())
    }
    // Closing is a one-way latch shared by Back, the close button and every business action. The
    // overflow submenu and the in-flight gallery save live in the same owner, so the exit collapses
    // the submenu atomically and can never submit the save twice.
    val actionGate = remember(image.id) { ImagePreviewActionGate() }
    // Single decision point: while the exit plays, nothing new may start. The submenu is collapsed
    // even when its action is refused, so a late tap cannot leave a popup behind the fading overlay.
    val runPreviewAction: (ImagePreviewAction, () -> Unit) -> Unit = { action, block ->
        actionGate.closeMenu()
        if (actionGate.allows(action)) block()
    }
    val sourceUpscaleRunning = upscaleJob?.running == true && upscaleJob.sourceImageId == image.id
    // Full-screen preview entrance/exit: one progress value drives alpha and the 0.96 -> 1 scale
    // inside a graphicsLayer, so no animated value is read during composition. Closing plays to the
    // end before the host is told to remove the preview.
    val entryProgress = remember { Animatable(0f) }
    val closeInteraction = remember { MutableInteractionSource() }
    val favoriteInteraction = remember { MutableInteractionSource() }
    val moreInteraction = remember { MutableInteractionSource() }
    val recreateInteraction = remember { MutableInteractionSource() }
    LaunchedEffect(Unit) {
        entryProgress.animateTo(1f, McaMotion.contentEnter)
    }
    val dismissWithMotion: () -> Unit = {
        // Idempotent: the first Back/close tap owns the played-out exit, later ones are consumed
        // without restarting it.
        if (actionGate.requestClose()) {
            scope.launch {
                entryProgress.animateTo(0f, McaMotion.fade(millis = 150))
                onDismiss()
            }
        }
    }
    // Hold Back from composition until removal, not only until the exit starts. The workspace
    // registers its own Back handler; this later, preview-only callback wins while the overlay is
    // composed, so once Closing begins a second Back is consumed by requestClose instead of reaching
    // the workspace, which used to clear the preview id and truncate the ~150 ms exit.
    BackHandler(enabled = true) { dismissWithMotion() }
    // Keep the preview above sibling workspace content for the whole exit animation; otherwise a
    // later-ordered sibling could draw above the fading overlay.
    Box(
        modifier = modifier
            .testTag("image.preview.${image.id}")
            .zIndex(1f)
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
        // Disable the secondary top-bar actions while the exit animation runs; the close handler
        // already guards re-entry, so closing stays available as the escape hatch.
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
                McaIconButton(
                    contentDescription = "关闭预览",
                    onClick = dismissWithMotion,
                    interactionSource = closeInteraction,
                    pressScaleEnabled = false,
                    glyphSize = McaIconSize.L,
                    tint = Color.White,
                    disabledTint = Color.White.copy(alpha = 0.55f),
                ) {
                    Icon(McaIcons.Close, contentDescription = null)
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
                McaIconButton(
                    contentDescription = if (image.favorite) "取消收藏" else "收藏图片",
                    onClick = {
                        runPreviewAction(ImagePreviewAction.FAVORITE) { onSetFavorite(!image.favorite) }
                    },
                    interactionSource = favoriteInteraction,
                    enabled = actionGate.allows(ImagePreviewAction.FAVORITE),
                    pressScaleEnabled = false,
                    glyphSize = McaIconSize.L,
                    tint = Color.White,
                    disabledTint = Color.White.copy(alpha = 0.55f),
                ) {
                    Icon(
                        if (image.favorite) McaIcons.Favorite else McaIcons.FavoriteBorder,
                        contentDescription = null,
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
                    McaIconButton(
                        contentDescription = "更多图片操作",
                        onClick = { actionGate.openMenu() },
                        interactionSource = moreInteraction,
                        enabled = actionGate.allows(ImagePreviewAction.MORE_MENU),
                        pressScaleEnabled = false,
                        glyphSize = McaIconSize.L,
                        tint = Color.White,
                        disabledTint = Color.White.copy(alpha = 0.55f),
                    ) {
                        Icon(
                            McaIcons.MoreVert,
                            contentDescription = null,
                        )
                    }
                }
                DropdownMenu(
                    expanded = actionGate.isMenuOpen,
                    // Closing collapses the submenu; while closing, openMenu() refuses to reopen it.
                    onDismissRequest = { actionGate.closeMenu() }
                ) {
                    if (image.generationDetails.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("查看生成参数") },
                            leadingIcon = { Icon(McaIcons.Info, contentDescription = null) },
                            onClick = {
                                runPreviewAction(ImagePreviewAction.SHOW_GENERATION_DETAILS) {
                                    showGenerationDetails = true
                                }
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("作为图生图输入") },
                        leadingIcon = { Icon(McaIcons.Edit, contentDescription = null) },
                        enabled = canUseAsImg2Img &&
                            actionGate.allows(ImagePreviewAction.USE_AS_IMAGE_INPUT),
                        onClick = {
                            runPreviewAction(ImagePreviewAction.USE_AS_IMAGE_INPUT) { onUseAsImg2Img() }
                        }
                    )
                    if (canUseUltraFix) {
                        DropdownMenuItem(
                            text = { Text("使用 UltraFix 精修") },
                            leadingIcon = { Icon(McaIcons.Replay, contentDescription = null) },
                            // UltraFix feeds the same generation-input path as img2img.
                            enabled = actionGate.allows(ImagePreviewAction.USE_AS_IMAGE_INPUT),
                            onClick = {
                                runPreviewAction(ImagePreviewAction.USE_AS_IMAGE_INPUT) { onUseUltraFix() }
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("高清放大") },
                        leadingIcon = { Icon(McaIcons.Image, contentDescription = null) },
                        enabled = actionGate.allows(ImagePreviewAction.UPSCALE),
                        onClick = {
                            runPreviewAction(ImagePreviewAction.UPSCALE) { showUpscaleDialog = true }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("分享图片") },
                        leadingIcon = { Icon(McaIcons.Share, contentDescription = null) },
                        enabled = actionGate.allows(ImagePreviewAction.SHARE),
                        onClick = {
                            runPreviewAction(ImagePreviewAction.SHARE) { onShare() }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (actionGate.isSaving) "保存中…" else "保存到系统图片库") },
                        leadingIcon = { Icon(McaIcons.Download, contentDescription = null) },
                        enabled = actionGate.allows(ImagePreviewAction.SAVE),
                        onClick = {
                            actionGate.closeMenu()
                            if (actionGate.beginSave()) {
                                scope.launch {
                                    try {
                                        // A started gallery write settles here even if the visual exit
                                        // removes the overlay (a half-finished copy would leave a
                                        // pending MediaStore row with no feedback); beginSave() already
                                        // guarantees it is submitted once.
                                        withContext(NonCancellable + Dispatchers.IO) {
                                            val result = downloadImageAssetToGallery(context, image)
                                            withContext(Dispatchers.Main) {
                                                result.onSuccess { path ->
                                                    notify("已保存到 $path")
                                                }.onFailure { error ->
                                                    notify(error.message ?: "图片保存失败")
                                                }
                                            }
                                        }
                                    } finally {
                                        actionGate.finishSave()
                                    }
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
                                McaIcons.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        enabled = !sourceUpscaleRunning &&
                            actionGate.allows(ImagePreviewAction.DELETE),
                        onClick = {
                            runPreviewAction(ImagePreviewAction.DELETE) { onDelete() }
                        }
                    )
                }
            }
        }
        when (imageLoadState) {
            is ChatImageLoadState.Ready -> Image(
                bitmap = imageLoadState.bitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .testTag("image.preview.bitmap.${image.id}")
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 12.dp, vertical = 76.dp)
            )
            ChatImageLoadState.Loading -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = Color.White)
                Text("正在读取图片", color = Color.White.copy(alpha = 0.82f))
            }
            ChatImageLoadState.Failed -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(McaIcons.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(42.dp))
                Text("无法读取图片", color = Color.White.copy(alpha = 0.82f))
                // Retry restarts the existing producer through its revision key; a failed decode
                // must not be a dead end. While the exit plays it is refused, and the explicit white
                // label is dimmed with the disabled state so it cannot look actionable mid-exit.
                val retryAllowed = actionGate.allows(ImagePreviewAction.RETRY_IMAGE)
                McaButton(style = McaButtonStyle.Text,
                    onClick = { runPreviewAction(ImagePreviewAction.RETRY_IMAGE) { imageLoadRevision++ } },
                    enabled = retryAllowed
                ) {
                    Text("重试", color = if (retryAllowed) Color.White else Color.White.copy(alpha = 0.55f))
                }
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
                        val recreateAllowed = recreateEnabled &&
                            actionGate.allows(ImagePreviewAction.RECREATE)
                        McaButton(style = McaButtonStyle.Text,
                            onClick = {
                                runPreviewAction(ImagePreviewAction.RECREATE) { onRecreate() }
                            },
                            enabled = recreateAllowed,
                            interactionSource = recreateInteraction
                        ) {
                            // The explicit white label used to stay bright on a disabled button, so a
                            // refused action still read as executable. Keep the colour role and dim
                            // only its alpha with the disabled state.
                            Text(
                                "按原参数再生成",
                                color = if (recreateAllowed) Color.White else Color.White.copy(alpha = 0.55f)
                            )
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
                            McaChip(
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
                        McaButton(style = McaButtonStyle.Text,
                            onClick = onImportUpscaler,
                            enabled = !upscalerImporting && upscaleJob?.running != true
                        ) {
                            Text(if (upscalerImporting) "导入中…" else "导入模型")
                        }
                        McaButton(style = McaButtonStyle.Text,
                            onClick = {
                                selectedUpscaler?.let { pendingUpscalerDeleteId = it.id }
                            },
                            enabled = selectedUpscaler != null &&
                                !selectedUpscaler.inUse &&
                                !selectedUpscaler.deleting &&
                                upscaleJob?.running != true,
                            contentColor = MaterialTheme.colorScheme.error
                        ) {
                            Text("删除所选")
                        }
                    }
                    Text("目标倍率", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IMAGE_UPSCALE_TARGET_SCALES.forEach { scale ->
                            McaChip(
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
                    McaButton(style = McaButtonStyle.Primary,onClick = onCancelUpscale) { Text("停止") }
                } else {
                    McaButton(style = McaButtonStyle.Primary,
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
                McaButton(style = McaButtonStyle.Text,onClick = { showUpscaleDialog = false }) { Text("关闭") }
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
                McaButton(style = McaButtonStyle.Text,
                    onClick = {
                        pendingUpscalerDeleteId = null
                        onDeleteUpscaler(upscalerId)
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                McaButton(style = McaButtonStyle.Text,onClick = { pendingUpscalerDeleteId = null }) { Text("取消") }
            }
        )
    }
    if (showGenerationDetails) {
        val detailsFeedback = rememberMcaFeedbackState()
        val detailsScope = rememberCoroutineScope()
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
                        McaButton(style = McaButtonStyle.Text,
                            onClick = {
                                detailsScope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "MCA image parameters",
                                                image.parameterShareJson
                                            )
                                        )
                                    )
                                    detailsFeedback.post("生成参数已复制")
                                }
                            }
                        ) {
                            Text("复制参数")
                        }
                        McaButton(style = McaButtonStyle.Text,
                            onClick = {
                                detailsScope.launch {
                                    val encoded = runCatching {
                                        ImageGenerationParameterImportCodec.encodeMcaBase64(
                                            image.parameterShareJson
                                        )
                                    }.getOrElse { error ->
                                        detailsFeedback.post(error.message?.takeIf(String::isNotBlank) ?: "参数编码失败")
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
                                    detailsFeedback.post("Base64 参数已复制")
                                }
                            }
                        ) {
                            Text("复制 Base64")
                        }
                    }
                    if (image.generationPreset != null) {
                        McaButton(style = McaButtonStyle.Text,
                            onClick = {
                                runPreviewAction(ImagePreviewAction.USE_PARAMETERS) {
                                    selectedPresetFields = reusablePresetFields
                                    showGenerationDetails = false
                                    showParameterReuse = true
                                }
                            },
                            enabled = actionGate.allows(ImagePreviewAction.USE_PARAMETERS)
                        ) {
                            Text("复用参数")
                        }
                    }
                    if (image.canRecreate) {
                        McaButton(style = McaButtonStyle.Text,
                            onClick = {
                                runPreviewAction(ImagePreviewAction.RECREATE) {
                                    showGenerationDetails = false
                                    onRecreate()
                                }
                            },
                            enabled = recreateEnabled &&
                                actionGate.allows(ImagePreviewAction.RECREATE)
                        ) {
                            Text("按原参数再生成")
                        }
                    }
                    McaButton(style = McaButtonStyle.Text,onClick = { showGenerationDetails = false }) {
                        Text("关闭")
                    }
                    McaFeedbackPopup(detailsFeedback)
                }
            }
        )
    }
    if (showParameterReuse) {
        val preset = image.generationPreset
        // Do not write Compose state during composition. If the preset disappeared, simply skip
        // rendering; the caller only opens this dialog while the preset exists.
        if (preset != null) {
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
                            McaChip(
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
                    McaButton(style = McaButtonStyle.Text,
                        onClick = {
                            runPreviewAction(ImagePreviewAction.USE_PARAMETERS) {
                                onUseParameters(preset, selectedPresetFields)
                                showParameterReuse = false
                            }
                        },
                        enabled = selectedPresetFields.isNotEmpty() &&
                            actionGate.allows(ImagePreviewAction.USE_PARAMETERS)
                    ) { Text("应用") }
                },
                dismissButton = {
                    McaButton(style = McaButtonStyle.Text,onClick = { showParameterReuse = false }) { Text("取消") }
                }
            )
        }
    }
}
