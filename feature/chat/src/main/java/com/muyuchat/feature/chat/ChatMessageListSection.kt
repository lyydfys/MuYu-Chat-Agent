package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.Role

@Composable
internal fun ChatMessageListSection(
    listState: LazyListState,
    state: ChatUiState,
    bottomChromeHeight: Dp,
    onOpenRecommendedModels: () -> Unit,
    onImportChatModel: () -> Unit,
    onOpenQuickStart: () -> Unit,
    onOpenBrowserTask: (BrowserTaskLaunch) -> Unit,
    onRetryChatImageRequest: (String) -> Unit,
    onCancelChatImageGeneration: (String) -> Unit,
    onApproveChatImageRequest: (String) -> Unit,
    onRejectChatImageRequest: (String) -> Unit,
    onContinueAssistantImageTurn: (String) -> Unit,
    onOpenModels: () -> Unit,
    onUseImageAsset: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDeleteMessage: (Int) -> Unit,
    onTogglePinMessage: (String) -> Unit,
    onDeleteLastTurn: () -> Unit,
    onBrowserTaskEvent: (BrowserTaskEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 26.dp, bottom = 118.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (state.messages.isEmpty()) {
                item {
                    EmptyChatPanel(
                        modelName = state.selectedModelName,
                        onChooseModel = onOpenRecommendedModels,
                        onImportModel = onImportChatModel,
                        showSetup = state.history.isEmpty() && state.localModels.isEmpty() && !state.isGenerating,
                        onOpenQuickStart = onOpenQuickStart
                    )
                }
            }
            val lastAssistantIndex = state.messages.indexOfLast { it.role == Role.ASSISTANT }
            itemsIndexed(
                items = state.messages,
                key = { index, message -> "${message.role.name}:${message.createdAt}:$index" }
            ) { index, message ->
                val generatedImageRequest = message.generatedImageRequest
                val generatedImageJob = generatedImageRequest?.let { request ->
                    chatGeneratedImageJob(
                        request = request,
                        jobs = state.imageJobs,
                        activeConversationId = state.activeConversationId
                    )
                }
                val generatedImages = generatedImageRequest?.let { request ->
                    chatGeneratedImageItems(
                        request = request,
                        job = generatedImageJob,
                        availableImages = state.images
                    )
                }.orEmpty()
                val generatedImageVisualState = generatedImageRequest?.let { request ->
                    chatGeneratedImageUiState(
                        request = request,
                        job = generatedImageJob,
                        imageCount = generatedImages.size
                    )
                }
                MessageBubble(
                    message = message,
                    readOnly = state.conversationReadOnly,
                    browserSessionId = state.activeConversationId,
                    characterCardHtml = state.assistants.any { assistant ->
                        assistant.id == state.selectedAssistantId && assistant.characterCardImported
                    },
                    onOpenBrowserTask = onOpenBrowserTask,
                    generatedImageRequest = generatedImageRequest,
                    generatedImageJob = generatedImageJob,
                    generatedImageVisualState = generatedImageVisualState,
                    generatedImages = generatedImages,
                    onRetryChatImage = {
                        generatedImageRequest?.let { onRetryChatImageRequest(it.id) }
                    },
                    onCancelChatImage = {
                        generatedImageRequest?.let { onCancelChatImageGeneration(it.id) }
                    },
                    onApproveChatImage = {
                        generatedImageRequest?.let { onApproveChatImageRequest(it.id) }
                    },
                    onRejectChatImage = {
                        generatedImageRequest?.let { onRejectChatImageRequest(it.id) }
                    },
                    onContinueAssistantImageTurn = {
                        generatedImageRequest?.let { onContinueAssistantImageTurn(it.id) }
                    },
                    onOpenImageModels = onOpenModels,
                    onUseImageAsset = onUseImageAsset,
                    onDeleteImageAsset = onDeleteImageAsset,
                    showAssistantActions = index == lastAssistantIndex && message.role == Role.ASSISTANT,
                    canRegenerate = !state.isGenerating && !state.conversationReadOnly,
                    isGenerating = state.isGenerating && index == lastAssistantIndex,
                    generationPhase = if (state.isGenerating && index == lastAssistantIndex) {
                        state.generationPhase
                    } else {
                        null
                    },
                    generationTokenProgress = if (state.isGenerating && index == lastAssistantIndex) {
                        state.generationTokenProgress
                    } else {
                        null
                    },
                    generationPersistProgress = if (state.isGenerating && index == lastAssistantIndex) {
                        state.generationPersistProgress
                    } else {
                        null
                    },
                    generationStats = if (state.isGenerating && index == lastAssistantIndex) {
                        state.generationStats
                    } else {
                        null
                    },
                    onRegenerate = onRegenerate,
                    onDelete = { onDeleteMessage(index) },
                    onTogglePinned = { onTogglePinMessage(message.id) },
                    canTogglePinned = !state.isGenerating && !state.conversationReadOnly,
                    onDeleteLastTurn = onDeleteLastTurn
                )
            }
            state.browserTask?.takeIf { task ->
                task.kind == BrowserTaskKind.SEARCH && !task.windowVisible && task.phase in setOf(
                    BrowserTaskPhase.SEARCHING,
                    BrowserTaskPhase.AWAITING_CONFIRMATION
                )
            }?.let { task ->
                item(key = task.taskId) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                task.query.orEmpty(),
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = {
                                onBrowserTaskEvent(
                                    BrowserTaskEvent(
                                        task.taskId, task.navigationId, BrowserTaskAction.CANCEL
                                    )
                                )
                            }) { Icon(Icons.Default.Close, contentDescription = "取消检索") }
                        }
                        Text(
                            if (task.phase == BrowserTaskPhase.SEARCHING) "正在检索来源"
                            else "选择来源以在浏览器中打开",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        task.candidateUrls.forEach { url ->
                            TextButton(onClick = {
                                onBrowserTaskEvent(
                                    BrowserTaskEvent(
                                        task.taskId, task.navigationId,
                                        BrowserTaskAction.SELECT_RESULT, url = url,
                                        userInitiated = true
                                    )
                                )
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    url, modifier = Modifier.fillMaxWidth(), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        ChatDeviceStatusOverlay(
            state = state,
            modifier = Modifier.matchParentSize().padding(bottom = bottomChromeHeight)
        )
    }
}
