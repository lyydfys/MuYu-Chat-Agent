package com.muyuchat.feature.chat

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.TokenProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
    onDeleteLastTurn: () -> Unit,
    canCopyMessage: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isUser = message.role == Role.USER

    // `modifier` carries the LazyColumn item animation (appearance/move/removal springs) and is
    // applied to the item's root node. Static layout is unchanged: fillMaxWidth plus the same
    // horizontal arrangement as before.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            UserMessageBubble(
                message = message,
                readOnly = readOnly,
                onTogglePinned = onTogglePinned,
                canTogglePinned = canTogglePinned,
                onCopy = {
                    if (canCopyMessage()) {
                        val copyText = displayInputWithoutAttachment(message.content)
                        if (copyText.isNotBlank()) {
                            scope.launch {
                                if (!canCopyMessage()) return@launch
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("MCA", copyText))
                                )
                                Toast.makeText(context, "已复制提示词", Toast.LENGTH_SHORT).show()
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
                onDelete = onDelete,
                onTogglePinned = onTogglePinned,
                canTogglePinned = canTogglePinned,
                onDeleteLastTurn = onDeleteLastTurn,
                onCopy = {
                    if (canCopyMessage()) {
                        scope.launch {
                            if (!canCopyMessage()) return@launch
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA", message.content))
                            )
                        }
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
                IconButton(onClick = onTogglePinned, enabled = canTogglePinned, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.PushPin,
                        contentDescription = if (message.pinned) "取消固定上下文" else "固定到上下文",
                        modifier = Modifier.size(18.dp),
                        tint = if (message.pinned) McaPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (copyableContent.isNotBlank()) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制提示词",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                )
            }
            }
        }
    }
}
