package com.muyuchat.feature.chat

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.TokenProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S17：从 ChatChromeScreens.kt 等值移出的消息气泡/参数弹窗/助手块簇。
// 原样逐字迁移，未做行为改动；入口原即 internal，双向扫描按协议处理。
@Composable
// P9/S13: private→internal（跨区入口：迁移的输入区复合区引用）
internal fun AttachmentPreview(
    input: String,
    visionCapabilityLabel: String,
    visionCapabilityDetail: String,
    visionCapabilityReady: Boolean,
    onRemove: () -> Unit
) {
    val name = remember(input) { extractAttachmentName(input) } ?: return
    val isImageAttachment = remember(input) { hasImageAttachmentMarker(input) }
    val imageAttachmentCount = remember(input) { chatImageAttachmentMarkerCount(input) }
    val statusColor = if (visionCapabilityReady) McaPrimaryBlue else MaterialTheme.colorScheme.error
    val containerColor = if (isImageAttachment) {
        if (visionCapabilityReady) McaInputIconSurface.copy(alpha = 0.92f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.44f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    }
    val contentColor = if (isImageAttachment) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        border = if (isImageAttachment) BorderStroke(1.dp, statusColor.copy(alpha = 0.22f)) else null
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .heightIn(min = 30.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isImageAttachment) Icons.Default.Image else Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (isImageAttachment) statusColor else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isImageAttachment) "识图" else "文件",
                        color = if (isImageAttachment) statusColor else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isImageAttachment && imageAttachmentCount > 1) {
                            "$name · 共 $imageAttachmentCount 张图片"
                        } else {
                            name
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                if (isImageAttachment) {
                    Text(
                        text = "$visionCapabilityLabel · $visionCapabilityDetail",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    extractAttachmentMeta(input)?.let { meta ->
                        Text(
                            text = meta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "移除附件",
                    modifier = Modifier.size(16.dp),
                    tint = contentColor.copy(alpha = 0.74f)
                )
            }
        }
    }
}

@Composable
internal fun MessageBubble(
    message: ChatMessage,
    readOnly: Boolean,
    browserSessionId: String?,
    characterCardHtml: Boolean,
    onOpenBrowserTask: (BrowserTaskLaunch) -> Unit,
    generatedImageRequest: ChatGeneratedImageRequest?,
    generatedImageJob: ImageGenerationUiJob?,
    generatedImageVisualState: ChatGeneratedImageUiState?,
    generatedImages: List<ImageAssetUiItem>,
    onRetryChatImage: () -> Unit,
    onCancelChatImage: () -> Unit,
    onApproveChatImage: () -> Unit,
    onRejectChatImage: () -> Unit,
    onContinueAssistantImageTurn: () -> Unit,
    onOpenImageModels: () -> Unit,
    onUseImageAsset: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit,
    showAssistantActions: Boolean,
    canRegenerate: Boolean,
    isGenerating: Boolean,
    generationPhase: GenerationPhase?,
    generationTokenProgress: TokenProgress?,
    generationPersistProgress: PersistProgress?,
    generationStats: RuntimeStats?,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
    canTogglePinned: Boolean,
    onDeleteLastTurn: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isUser = message.role == Role.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            UserMessageBubble(
                message = message,
                readOnly = readOnly,
                onTogglePinned = onTogglePinned,
                canTogglePinned = canTogglePinned,
                onCopy = {
                    val copyText = displayInputWithoutAttachment(message.content)
                    if (copyText.isNotBlank()) {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA", copyText))
                            )
                            Toast.makeText(context, "已复制提示词", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        } else {
            AssistantMessageBlock(
                modifier = Modifier.fillMaxWidth(),
                message = message,
                readOnly = readOnly,
                browserSessionId = browserSessionId,
                characterCardHtml = characterCardHtml,
                onOpenBrowserTask = onOpenBrowserTask,
                generatedImageRequest = generatedImageRequest,
                generatedImageJob = generatedImageJob,
                generatedImageVisualState = generatedImageVisualState,
                generatedImages = generatedImages,
                onRetryChatImage = onRetryChatImage,
                onCancelChatImage = onCancelChatImage,
                onApproveChatImage = onApproveChatImage,
                onRejectChatImage = onRejectChatImage,
                onContinueAssistantImageTurn = onContinueAssistantImageTurn,
                onOpenImageModels = onOpenImageModels,
                onUseImageAsset = onUseImageAsset,
                onDeleteImageAsset = onDeleteImageAsset,
                showActions = showAssistantActions,
                canRegenerate = canRegenerate,
                isGenerating = isGenerating,
                generationPhase = generationPhase,
                generationTokenProgress = generationTokenProgress,
                generationPersistProgress = generationPersistProgress,
                generationStats = generationStats,
                onRegenerate = onRegenerate,
                onDelete = onDelete,
                onTogglePinned = onTogglePinned,
                canTogglePinned = canTogglePinned,
                onDeleteLastTurn = onDeleteLastTurn,
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("MCA", message.content))
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun UserMessageBubble(
    message: ChatMessage,
    readOnly: Boolean,
    onTogglePinned: () -> Unit,
    canTogglePinned: Boolean,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    // The bubble colour was hardcoded to a light value, so on a dark theme the user's own
    // messages rendered as solid white blocks. It now follows the same
    // "if (darkTheme) colorScheme.<role> else <light value>" shape the rest of this file uses --
    // primaryContainer at 0.42 is the brand-tinted surface already used for blue surfaces at
    // lines 2287, 2389 and 2490, and surfaceVariant is the neutral one used at 2391 and 4080.
    // Only the two colour expressions change: size, radius, padding, spacing and text are untouched.
    val darkTheme = isSystemInDarkTheme()
    val bubbleColor = if (darkTheme) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
    } else {
        Color(0xFFEEF4FF)
    }
    // The colour above is an alpha-modified derived value, so Surface cannot resolve a matching
    // content colour through contentColorFor and falls back to the ambient one, which left the text
    // dim against the blue. On dark the pair is explicit: onPrimaryContainer is the role that
    // belongs with primaryContainer. On light LocalContentColor.current is exactly what Surface
    // would have fallen back to, so the light rendering is unchanged down to the colour value.
    val bubbleContentColor = if (darkTheme) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        LocalContentColor.current
    }
    val attachmentPlaceholderColor = if (darkTheme) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color.White.copy(alpha = 0.72f)
    }
    val copyableContent = displayInputWithoutAttachment(message.content)
    val visibleImageAttachments by produceState(
        initialValue = message.imageAttachments.takeIf { it.size <= 1 }.orEmpty(),
        key1 = message.imageAttachments
    ) {
        // Legacy histories may keep distinct content:// URIs for the same bytes.
        // Resolve their identities off the UI thread before showing thumbnails.
        value = withContext(Dispatchers.IO) {
            deduplicateChatImageAttachments(message.imageAttachments) { uri ->
                chatImageAttachmentContentIdentity(context, uri)
            }
        }
    }
    Column(
        modifier = Modifier.widthIn(max = 280.dp),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            color = bubbleColor,
            contentColor = bubbleContentColor,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 6.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 17.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (visibleImageAttachments.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        visibleImageAttachments.forEach { attachment ->
                            val bitmap by rememberChatImageBitmap(
                                context,
                                attachment.uriString,
                                maxDimensionPx = 384
                            )
                            val displayBitmap = bitmap
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = attachmentPlaceholderColor,
                                modifier = Modifier.size(104.dp)
                            ) {
                                if (displayBitmap != null) {
                                    Image(
                                        bitmap = displayBitmap.asImageBitmap(),
                                        contentDescription = attachment.name.ifBlank { "上传图片" },
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Image,
                                            contentDescription = null,
                                            tint = McaPrimaryBlue.copy(alpha = 0.72f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (message.content.isNotBlank()) {
                    PagedPlainMessageText(
                        content = message.content,
                        // Third hardcoded light value in this function. The bubble background became
                        // theme-aware first, which left this near-black text on a blue surface:
                        // readable enough to look deliberate, dim enough to be wrong. An explicit
                        // colour here also wins over the Surface contentColor above, which is why
                        // that pair on its own did not fix it. Light keeps 0xFF202124 exactly.
                        color = if (darkTheme) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFF202124),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp)
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!readOnly) {
                IconButton(onClick = onTogglePinned, enabled = canTogglePinned, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.PushPin,
                        contentDescription = if (message.pinned) "取消固定上下文" else "固定到上下文",
                        modifier = Modifier.size(17.dp),
                        tint = if (message.pinned) McaPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (copyableContent.isNotBlank()) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制提示词",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                )
            }
            }
        }
    }
}

@Composable
// P9/S14: private→internal（跨区入口：迁移的模型切换复合区引用）
internal fun GenerationParamsQuickDialog(
    params: GenerationParams,
    onDismiss: () -> Unit,
    onApply: (GenerationParams) -> Unit
) {
    var temperatureText by remember(params) { mutableStateOf(params.temperature.cleanParamText()) }
    var topPText by remember(params) { mutableStateOf(params.topP.cleanParamText()) }
    var topKText by remember(params) { mutableStateOf(params.topK.toString()) }
    var minPText by remember(params) { mutableStateOf(params.minP.cleanParamText()) }
    var repeatPenaltyText by remember(params) { mutableStateOf(params.repeatPenalty.cleanParamText()) }
    var nPredictText by remember(params) { mutableStateOf(params.nPredict.toString()) }
    var reasoningMode by remember(params) { mutableStateOf(params.reasoningMode) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun parseFloat(label: String, value: String, min: Float, max: Float): Float? {
        val parsed = value.trim().toFloatOrNull()
        if (parsed == null || !parsed.isFinite() || parsed !in min..max) {
            errorText = "$label 请输入 ${min.cleanParamText()} 到 ${max.cleanParamText()} 之间的数字"
            return null
        }
        return parsed
    }

    fun parseInt(label: String, value: String, min: Int, max: Int): Int? {
        val parsed = value.trim().toIntOrNull()
        if (parsed == null || parsed !in min..max) {
            errorText = "$label 请输入 $min 到 $max 之间的整数"
            return null
        }
        return parsed
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当前模型参数") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "只影响当前模型，切换模型后会自动恢复该模型的设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(value = temperatureText, onValueChange = { temperatureText = it; errorText = null }, label = { Text("temperature") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = topPText, onValueChange = { topPText = it; errorText = null }, label = { Text("top_p") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = topKText, onValueChange = { topKText = it; errorText = null }, label = { Text("top_k") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = minPText, onValueChange = { minPText = it; errorText = null }, label = { Text("min_p") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = repeatPenaltyText, onValueChange = { repeatPenaltyText = it; errorText = null }, label = { Text("重复惩罚") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = nPredictText, onValueChange = { nPredictText = it; errorText = null }, label = { Text("最大输出 tokens") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("思考模式", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReasoningMode.entries.forEach { mode ->
                        FilterChip(
                            selected = reasoningMode == mode,
                            onClick = { reasoningMode = mode },
                            label = { Text(mode.shortLabel()) }
                        )
                    }
                }
                errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val temperature = parseFloat("temperature", temperatureText, 0f, 2f) ?: return@TextButton
                val topP = parseFloat("top_p", topPText, 0f, 1f) ?: return@TextButton
                val topK = parseInt("top_k", topKText, 0, 1000) ?: return@TextButton
                val minP = parseFloat("min_p", minPText, 0f, 1f) ?: return@TextButton
                val repeatPenalty = parseFloat("重复惩罚", repeatPenaltyText, 0.5f, 2f) ?: return@TextButton
                val nPredict = parseInt("最大输出 tokens", nPredictText, 16, 65_536) ?: return@TextButton
                onApply(params.copy(temperature = temperature, topP = topP, topK = topK, minP = minP, repeatPenalty = repeatPenalty, nPredict = nPredict, reasoningMode = reasoningMode, hideReasoning = reasoningMode == ReasoningMode.OFF))
            }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private data class CharacterHtmlProjection(
    val messageId: String,
    val source: String,
    val preview: String?,
    val blocks: List<CharacterHtmlBlock>?
)

@Composable
private fun AssistantMessageBlock(
    modifier: Modifier = Modifier,
    message: ChatMessage,
    readOnly: Boolean,
    browserSessionId: String?,
    characterCardHtml: Boolean,
    onOpenBrowserTask: (BrowserTaskLaunch) -> Unit,
    generatedImageRequest: ChatGeneratedImageRequest?,
    generatedImageJob: ImageGenerationUiJob?,
    generatedImageVisualState: ChatGeneratedImageUiState?,
    generatedImages: List<ImageAssetUiItem>,
    onRetryChatImage: () -> Unit,
    onCancelChatImage: () -> Unit,
    onApproveChatImage: () -> Unit,
    onRejectChatImage: () -> Unit,
    onContinueAssistantImageTurn: () -> Unit,
    onOpenImageModels: () -> Unit,
    onUseImageAsset: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit,
    showActions: Boolean,
    canRegenerate: Boolean,
    isGenerating: Boolean,
    generationPhase: GenerationPhase?,
    generationTokenProgress: TokenProgress?,
    generationPersistProgress: PersistProgress?,
    generationStats: RuntimeStats?,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
    canTogglePinned: Boolean,
    onDeleteLastTurn: () -> Unit,
    onCopy: () -> Unit
) {
    val contextTrace = remember(message.id, message.contextAssemblyTraceJson) {
        parseContextAssemblyTrace(message.contextAssemblyTraceJson)
    }
    var showContextTrace by remember(message.id) { mutableStateOf(false) }
    val htmlCandidate = remember(message.content, characterCardHtml) {
        characterHtmlMayNeedPreview(message.content) &&
            (characterCardHtml || characterHtmlHasStatus(message.content))
    }
    val htmlProjection by produceState<CharacterHtmlProjection?>(
        initialValue = null,
        key1 = message.id,
        key2 = message.content,
        key3 = htmlCandidate
    ) {
        val blocks = if (htmlCandidate) {
            withContext(Dispatchers.Default) { characterHtmlBlocks(message.content) }
        } else null
        val preview = blocks?.joinToString("\n\n") { block ->
            when (block) {
                is CharacterHtmlBlock.Text -> block.content
                is CharacterHtmlBlock.Details -> listOf(block.title, block.content)
                    .filter(String::isNotBlank)
                    .joinToString("\n")
            }
        }
        value = CharacterHtmlProjection(message.id, message.content, preview, blocks)
    }
    val currentProjection = htmlProjection?.takeIf { it.messageId == message.id }
    val visibleHtmlPreview = currentProjection?.preview?.takeIf { htmlCandidate }
    val visibleHtmlBlocks = currentProjection?.blocks?.takeIf { htmlCandidate }
    val previewPending = htmlCandidate && currentProjection?.source != message.content && visibleHtmlPreview == null
    var showHtmlSource by remember(message.id) { mutableStateOf(false) }
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(0.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null,
        modifier = modifier
            .fillMaxWidth()
            .padding(end = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (message.reasoningContent.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    AnimatedMcaAssistantMark(
                        isGenerating = isGenerating,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    ReasoningPanel(
                        content = message.reasoningContent,
                        durationMs = message.reasoningDurationMs,
                        awaitingFinalAnswer = isGenerating && message.content.isBlank(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (message.content.isBlank() && message.reasoningContent.isBlank()) {
                if (isGenerating) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        AnimatedMcaAssistantMark(
                            isGenerating = true,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        PendingReasoningPanel(
                            ownerCreatedAt = message.createdAt,
                            phase = generationPhase,
                            tokenProgress = generationTokenProgress,
                            persistProgress = generationPersistProgress,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(18.dp))
                }
            } else if (message.content.isNotBlank() && generatedImageRequest == null) {
                if (visibleHtmlPreview != null) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showHtmlSource = !showHtmlSource }) {
                                Text(if (showHtmlSource) "返回预览" else "查看原文")
                            }
                        }
                        SelectionContainer {
                            if (showHtmlSource) {
                                PagedAssistantRichText(message.content)
                            } else if (visibleHtmlBlocks != null) {
                                CharacterHtmlBlocksContent(visibleHtmlBlocks)
                            } else {
                                PagedAssistantRichText(visibleHtmlPreview)
                            }
                        }
                    }
                } else if (previewPending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    SelectionContainer {
                        PagedAssistantRichText(message.content)
                    }
                }
            }
            if (generatedImageRequest != null && generatedImageVisualState != null) {
                ChatGeneratedImageContent(
                    request = generatedImageRequest,
                    job = generatedImageJob,
                    visualState = generatedImageVisualState,
                    images = generatedImages,
                    readOnly = readOnly,
                    onRetry = onRetryChatImage,
                    onCancel = onCancelChatImage,
                    onApprove = onApproveChatImage,
                    onReject = onRejectChatImage,
                    onContinueAssistantImageTurn = onContinueAssistantImageTurn,
                    onOpenImageModels = onOpenImageModels,
                    onUseImageAsset = onUseImageAsset,
                    onDeleteImageAsset = onDeleteImageAsset
                )
            }
            if (isGenerating) {
                GenerationPerformanceLine(generationStats)
            } else {
                message.generationMetricsSummary()?.let { summary ->
                    // 与生成中态保持一致：labelSmall(11sp) 在手机上偏小，提到 labelMedium(12sp)。
                    // 颜色沿用 onSurfaceVariant（Material3 对次级文本的正确 token），不做额外 alpha 折减。
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (message.sourceReferences.isNotEmpty() || message.webSearchTrace?.hasContent == true) {
                WebSearchSourcesRow(
                    sources = message.sourceReferences,
                    trace = message.webSearchTrace,
                    sessionId = browserSessionId,
                    messageId = message.id,
                    onOpenBrowserTask = onOpenBrowserTask
                )
            }
            if (contextTrace != null) {
                TextButton(
                    onClick = { showContextTrace = true },
                    modifier = Modifier.testTag("context-trace-${message.id}"),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("上下文来源", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (showActions) {
                AssistantActionRow(
                    readOnly = readOnly,
                    canRegenerate = canRegenerate,
                    onRegenerate = onRegenerate,
                    onDelete = onDelete,
                    onDeleteLastTurn = onDeleteLastTurn,
                    onCopy = onCopy,
                    pinned = message.pinned,
                    canTogglePinned = canTogglePinned,
                    onTogglePinned = onTogglePinned
                )
            } else if (!readOnly) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onTogglePinned, enabled = canTogglePinned, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = if (message.pinned) "取消固定上下文" else "固定到上下文",
                            modifier = Modifier.size(16.dp),
                            tint = if (message.pinned) McaPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    if (showContextTrace && contextTrace != null) {
        ContextAssemblyTraceDialog(contextTrace, onDismiss = { showContextTrace = false })
    }
}
