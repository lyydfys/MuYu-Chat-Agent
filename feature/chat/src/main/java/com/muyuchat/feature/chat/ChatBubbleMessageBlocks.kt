package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaIcons

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.muyuchat.core.designsystem.McaIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.TokenProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.muyuchat.core.designsystem.McaIconSize

private data class CharacterHtmlProjection(
    val messageId: String,
    val source: String,
    val preview: String?,
    val blocks: List<CharacterHtmlBlock>?
)

/**
 * Display-rate token pacer for active streaming turns.
 *
 * Network SSE and local JNI chunks arrive in uneven bursts. When [isGenerating] is true, this
 * reveals newly appended characters smoothly across display frames using surrogate-safe prefixes
 * ([safePrefix]), and snaps immediately to [content] as soon as generation completes or when
 * system animations are disabled.
 */
@Composable
private fun rememberSmoothedStreamingText(
    content: String,
    isGenerating: Boolean,
    contentIdentity: ChatMessageIdentity
): String {
    if (!isGenerating || content.isEmpty()) return content
    var revealedChars by remember(contentIdentity) { mutableIntStateOf(content.length) }
    val latestTarget by rememberUpdatedState(content)
    if (revealedChars > content.length) {
        revealedChars = content.length
    }
    LaunchedEffect(contentIdentity, isGenerating) {
        val durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        if (durationScale <= 0f) {
            revealedChars = latestTarget.length
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            val targetLen = latestTarget.length
            val current = revealedChars
            if (current < targetLen) {
                val backlog = targetLen - current
                val step = (backlog / 3).coerceAtLeast(2)
                revealedChars = (current + step).coerceAtMost(targetLen)
            } else if (current > targetLen) {
                revealedChars = targetLen
            }
        }
    }
    val effectiveChars = revealedChars.coerceIn(0, content.length)
    return if (effectiveChars >= content.length) {
        content
    } else {
        content.safePrefix(effectiveChars)
    }
}

@Composable
internal fun AssistantMessageBlock(
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
    onTogglePinned: () -> Unit,
    canTogglePinned: Boolean,
    onDeleteLastTurn: () -> Unit,
    onQuote: () -> Unit,
    onCopy: () -> Unit,
    copied: Boolean = false,
    /** Live library names for the trace panel; the trace's own recorded names win over these. */
    contextTraceNames: ContextTraceLibraryNames = ContextTraceLibraryNames(),
    /**
     * 安全富文本块开关：由 `ChatUiState.richBlocksEnabled`（设置层投影）提供。
     * 默认 false，既有 Markdown/角色卡预览路径完全不变。
     */
    richBlocksEnabled: Boolean = false
) {
    val richTextIdentity = ChatMessageIdentity(browserSessionId, message.id)
    val streamedContent = rememberSmoothedStreamingText(
        content = message.content,
        isGenerating = isGenerating,
        contentIdentity = richTextIdentity
    )
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
    // 富文本块只在生成结束后解析：流式半截标签不该反复切换布局；关闭时完全不进入该路径。
    val richBlocksActive = richBlocksEnabled && !isGenerating
    val richProjection by produceState<RichBlockResult?>(
        initialValue = null,
        key1 = message.id,
        key2 = message.content,
        key3 = richBlocksActive,
    ) {
        value = if (richBlocksActive && message.content.isNotBlank()) {
            withContext(Dispatchers.Default) { sanitizeRichBlocks(message.content) }
        } else {
            null
        }
    }
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
                val richResult = richProjection
                if (richBlocksActive && richResult != null && richResult.detected) {
                    var showRichSource by remember(message.id) { mutableStateOf(false) }
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            McaButton(style = McaButtonStyle.Text, onClick = { showRichSource = !showRichSource }) {
                                Text(if (showRichSource) "返回预览" else "查看原文")
                            }
                        }
                        SelectionContainer {
                            when {
                                showRichSource -> PagedAssistantRichText(
                                    message.content,
                                    contentIdentity = richTextIdentity
                                )

                                richResult.blocks.isEmpty() -> Text(
                                    "该消息只包含不可显示的 HTML 内容",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                else -> RichBlockView(
                                    blocks = richResult.blocks,
                                    truncated = richResult.truncated,
                                )
                            }
                        }
                    }
                } else if (visibleHtmlPreview != null) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            McaButton(style = McaButtonStyle.Text,onClick = { showHtmlSource = !showHtmlSource }) {
                                Text(if (showHtmlSource) "返回预览" else "查看原文")
                            }
                        }
                        SelectionContainer {
                            if (showHtmlSource) {
                                PagedAssistantRichText(message.content, contentIdentity = richTextIdentity)
                            } else if (visibleHtmlBlocks != null) {
                                CharacterHtmlBlocksContent(visibleHtmlBlocks)
                            } else {
                                PagedAssistantRichText(visibleHtmlPreview, contentIdentity = richTextIdentity)
                            }
                        }
                    }
                } else if (previewPending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    SelectionContainer {
                        PagedAssistantRichText(
                            streamedContent,
                            contentIdentity = richTextIdentity,
                            isStreaming = isGenerating
                        )
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
            // 回答尾部：正文与尾部之间仍由外层 12dp 分界；尾部内部（统计 → 来源 → 操作）用更紧
            // 的 4dp 节奏，让它们读作同一条回答，而不影响推理区、图片和正文的既有间距。
            Column(
                modifier = Modifier.animateContentSize(animationSpec = McaMotion.sizeChangePx),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isGenerating) {
                    GenerationPerformanceLine(generationStats)
                } else {
                    message.generationMetricsFields()?.let { fields ->
                        GenerationMetricsRow(fields)
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
                    McaButton(style = McaButtonStyle.Text,
                        onClick = { showContextTrace = true },
                        modifier = Modifier.testTag("context-trace-${message.id}"),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) {
                        Icon(McaIcons.Info, contentDescription = null, modifier = Modifier.size(McaIconSize.Xs))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("上下文来源", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (showActions) {
                    Spacer(modifier = Modifier.height(2.dp))
                    AssistantActionRow(
                        readOnly = readOnly,
                        canRegenerate = canRegenerate,
                        onRegenerate = onRegenerate,
                        onDeleteLastTurn = onDeleteLastTurn,
                        onQuote = onQuote,
                        onCopy = onCopy,
                        copied = copied,
                        pinned = message.pinned,
                        canTogglePinned = canTogglePinned,
                        onTogglePinned = onTogglePinned
                    )
                } else if (!readOnly) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        McaIconButton(
                            contentDescription = if (message.pinned) "取消固定上下文" else "固定到上下文",
                            onClick = onTogglePinned,
                            enabled = canTogglePinned,
                            hapticsEnabled = false,
                            size = 48.dp,
                            glyphSize = McaIconSize.Xs,
                            tint = if (message.pinned) McaPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Icon(
                                if (message.pinned) McaIcons.PushPin else McaIcons.PinUnselected,
                                contentDescription = null,
                            )
                        }
                        McaIconButton(
                            contentDescription = "引用回复",
                            onClick = onQuote,
                            hapticsEnabled = false,
                            size = 48.dp,
                            glyphSize = McaIconSize.Xs,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Icon(
                                McaIcons.ChatBubbleOutline,
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
        }
    }
    if (showContextTrace && contextTrace != null) {
        ContextAssemblyTraceDialog(
            contextTrace,
            onDismiss = { showContextTrace = false },
            names = contextTraceNames
        )
    }
}
