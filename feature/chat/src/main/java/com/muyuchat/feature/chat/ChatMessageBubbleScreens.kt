package com.muyuchat.feature.chat

import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import android.content.ClipData
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.muyuchat.core.designsystem.McaIconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIconPlate
import com.muyuchat.core.designsystem.McaPlateSize
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.TokenProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.rememberMcaHaptics

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
    onTogglePinned: () -> Unit,
    canTogglePinned: Boolean,
    onDeleteLastTurn: () -> Unit,
    onEdit: () -> Unit,
    onQuote: () -> Unit,
    canEditMessage: Boolean,
    canCopyMessage: () -> Boolean,
    /**
     * Live world-book/knowledge-base names for the "上下文来源" panel. Defaulted: a caller that
     * cannot supply names still renders recorded names (or a readable placeholder), never a UUID.
     */
    contextTraceNames: ContextTraceLibraryNames = ContextTraceLibraryNames(),
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = rememberMcaHaptics()
    val isUser = message.role == Role.USER

    // `modifier` carries the LazyColumn item animation (appearance/move/removal springs) and is
    // applied to the item's root node. Static layout is unchanged: fillMaxWidth plus the same
    // horizontal arrangement as before.
    // Copy confirmation reflects a successful clipboard write: the action row swaps to a check
    // for ~1.4s and returns to the copy glyph without changing button geometry. User and assistant
    // messages now share this in-place confirmation instead of the user side raising a Toast.
    // `message.id` keeps the confirmation isolated per message: copying A never marks B as copied.
    var copiedRecently by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(copiedRecently) {
        if (copiedRecently) {
            delay(1_400)
            copiedRecently = false
        }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            UserMessageBubble(
                message = message,
                readOnly = readOnly,
                canEdit = canEditMessage,
                onTogglePinned = {
                    haptics.confirm()
                    onTogglePinned()
                },
                canTogglePinned = canTogglePinned,
                onEdit = onEdit,
                onQuote = onQuote,
                copied = copiedRecently,
                onCopy = {
                    if (canCopyMessage()) {
                        val copyText = displayInputWithoutAttachment(message.content)
                        if (copyText.isNotBlank()) {
                            haptics.confirm()
                            scope.launch {
                                if (!canCopyMessage()) return@launch
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("MCA", copyText))
                                )
                                // Feedback only after the real clipboard write succeeded.
                                copiedRecently = true
                            }
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
                onTogglePinned = {
                    haptics.confirm()
                    onTogglePinned()
                },
                canTogglePinned = canTogglePinned,
                onDeleteLastTurn = onDeleteLastTurn,
                onQuote = onQuote,
                onCopy = {
                    if (canCopyMessage()) {
                        haptics.confirm()
                        scope.launch {
                            if (!canCopyMessage()) return@launch
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA", message.content))
                            )
                            copiedRecently = true
                        }
                    }
                },
                copied = copiedRecently,
                contextTraceNames = contextTraceNames
            )
        }
    }
}

@Composable
private fun UserMessageBubble(
    message: ChatMessage,
    readOnly: Boolean,
    canEdit: Boolean,
    onTogglePinned: () -> Unit,
    canTogglePinned: Boolean,
    onEdit: () -> Unit,
    onQuote: () -> Unit,
    copied: Boolean,
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
    // Press feedback for the message action buttons; drawn in graphicsLayer, no layout change and
    // the 48dp touch target stays exactly as before.
    val pinInteraction = remember { MutableInteractionSource() }
    val copyInteraction = remember { MutableInteractionSource() }
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
            border = BorderStroke(
                0.5.dp,
                if (darkTheme) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                } else {
                    McaPrimaryBlue.copy(alpha = 0.16f)
                }
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
                        // Each attachment keeps its own retry epoch and request identity (URI plus the
                        // attachment identity as task identity), so an insert or reorder never borrows a
                        // neighbour's decoded state and a stale frame is never shown for another item.
                        visibleImageAttachments.forEach { attachment ->
                            key(attachment.uriString) {
                                var attachmentRetry by remember(attachment.uriString) { mutableStateOf(0) }
                                val attachmentDisplay = rememberChatImageBitmap(
                                    context = context,
                                    uriString = attachment.uriString,
                                    maxDimensionPx = 384,
                                    revisionKey = attachmentRetry,
                                    taskIdentity = attachment.uriString
                                )
                                val displayBitmap = attachmentDisplay.readyValue
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = attachmentPlaceholderColor,
                                    modifier = Modifier.size(104.dp)
                                ) {
                                    when {
                                        displayBitmap != null -> Image(
                                            bitmap = displayBitmap.asImageBitmap(),
                                            contentDescription = attachment.name.ifBlank { "上传图片" },
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        // A failed read is its own state with a retry, not the old
                                        // "still decoding" placeholder that could never recover.
                                        attachmentDisplay.phase == ImageDisplayPhase.FAILED -> Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                McaIcons.Image,
                                                contentDescription = null,
                                                tint = McaPrimaryBlue.copy(alpha = 0.72f)
                                            )
                                            McaButton(style = McaButtonStyle.Text,onClick = { attachmentRetry += 1 }) {
                                                Text("重试", fontSize = 12.sp)
                                            }
                                        }
                                        attachmentDisplay.phase == ImageDisplayPhase.LOADING -> Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp
                                            )
                                        }
                                        else -> Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                McaIcons.Image,
                                                contentDescription = null,
                                                tint = McaPrimaryBlue.copy(alpha = 0.72f)
                                            )
                                        }
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
        val pinIconScale by animateFloatAsState(
            targetValue = if (message.pinned) 1.12f else 1.0f,
            animationSpec = McaMotion.sizeChange,
            label = "userPinIconScale"
        )
        val copyIconScale by animateFloatAsState(
            targetValue = if (copied) 1.15f else 1.0f,
            animationSpec = McaMotion.sizeChange,
            label = "userCopyIconScale"
        )
        val pinPillBg by animateColorAsState(
            targetValue = if (message.pinned && canTogglePinned) {
                McaPrimaryBlue.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (canTogglePinned) 0.45f else 0.22f)
            },
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
            label = "userPinPillBg"
        )
        val pinPillBorder by animateColorAsState(
            targetValue = if (message.pinned && canTogglePinned) {
                McaPrimaryBlue.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (canTogglePinned) 0.42f else 0.20f)
            },
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
            label = "userPinPillBorder"
        )
        val copyPillBg by animateColorAsState(
            targetValue = if (copied) {
                McaPrimaryBlue.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
            label = "userCopyPillBg"
        )
        val copyPillBorder by animateColorAsState(
            targetValue = if (copied) {
                McaPrimaryBlue.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
            },
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
            label = "userCopyPillBorder"
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!readOnly) {
                McaIconButton(
                    contentDescription = if (message.pinned) {
                        "取消固定上下文"
                    } else {
                        "固定到上下文"
                    },
                    onClick = onTogglePinned,
                    enabled = canTogglePinned,
                    hapticsEnabled = false,
                    interactionSource = pinInteraction,
                    glyphSize = McaPlateSize.Medium,
                    tint = if (message.pinned) McaPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                ) {
                  McaIconPlate(
                      size = 32.dp,
                      containerColor = pinPillBg,
                      borderColor = pinPillBorder,
                      animateColors = false,
                  ) {
                        Icon(
                            if (message.pinned) McaIcons.PushPin else McaIcons.PinUnselected,
                            contentDescription = null,
                            modifier = Modifier
                                .size(McaIconSize.Optical.Inline)
                                .graphicsLayer {
                                    scaleX = pinIconScale
                                    scaleY = pinIconScale
                                },
                        )
                    }
                }
            }
            if (copyableContent.isNotBlank()) {
            McaIconButton(
                contentDescription = if (copied) "已复制" else "复制提示词",
                onClick = onCopy,
                hapticsEnabled = false,
                interactionSource = copyInteraction,
                glyphSize = McaPlateSize.Medium,
            ) {
              McaIconPlate(
                  size = 32.dp,
                  containerColor = copyPillBg,
                  borderColor = copyPillBorder,
                  animateColors = false,
              ) {
                    Icon(
                        if (copied) McaIcons.Check else McaIcons.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier
                            .size(McaIconSize.Optical.Inline)
                            .graphicsLayer {
                                scaleX = copyIconScale
                                scaleY = copyIconScale
                            },
                        tint = if (copied) {
                            McaPrimaryBlue
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        }
                    )
                }
            }
            }
            if (!readOnly) {
                UserMessageActionMenu(
                    readOnly = readOnly,
                    canEdit = canEdit,
                    onEdit = onEdit,
                    onQuote = onQuote
                )
            }
        }
    }
}
