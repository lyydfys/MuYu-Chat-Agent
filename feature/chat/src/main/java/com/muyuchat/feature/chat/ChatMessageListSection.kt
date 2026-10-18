package com.muyuchat.feature.chat

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.Role

/**
 * Message-list item motion: spring fade for appearing/removed rows, damped spring for placement.
 *
 * The placement spring never overshoots. Compose's LazyLayoutItemAnimator treats the scroll the
 * list consumed as a delta it must not animate (LazyLayoutItemAnimation.applyScrollOffset) and only
 * animates genuine data-change offsets, so the ChatScrollCoordinator's follow scroll keeps owning
 * scroll geometry. In follow mode item motion therefore contributes alpha only, never a second
 * displacement, and the first layout of a restored conversation does not fade in either.
 */
private val chatMessageAppearSpec: FiniteAnimationSpec<Float> =
    spring(
        dampingRatio = ChatMotion.CONTROL_DAMPING_RATIO,
        stiffness = ChatMotion.CONTROL_STIFFNESS
    )
private val chatMessagePlacementSpec: FiniteAnimationSpec<IntOffset> =
    spring(
        dampingRatio = ChatMotion.ITEM_DAMPING_RATIO,
        stiffness = ChatMotion.ITEM_STIFFNESS,
        visibilityThreshold = IntOffset.VisibilityThreshold
    )
private val chatMessageRemoveSpec: FiniteAnimationSpec<Float> = tween(durationMillis = 140)

@Composable
internal fun ChatMessageListSection(
    listState: LazyListState,
    state: ChatUiState,
    composerOverlap: Dp,
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
    onMessageIntent: ((ChatMessageIntent) -> Unit)?,
    onBrowserTaskEvent: (BrowserTaskEvent) -> Unit,
    nestedScrollConnection: NestedScrollConnection,
    modifier: Modifier = Modifier
) {
    val latestState = rememberUpdatedState(state)
    val latestOnDeleteMessage = rememberUpdatedState(onDeleteMessage)
    val latestOnRegenerate = rememberUpdatedState(onRegenerate)
    val latestOnDeleteLastTurn = rememberUpdatedState(onDeleteLastTurn)
    val latestOnMessageIntent = rememberUpdatedState(onMessageIntent)
    val actionDispatcher = remember {
        ChatMessageIntentDispatcher(
            currentMessages = { latestState.value.messages },
            activeConversationId = { latestState.value.activeConversationId },
            onIntent = { latestOnMessageIntent.value },
            legacyOnDeleteMessage = { latestOnDeleteMessage.value(it) },
            legacyOnRegenerate = { latestOnRegenerate.value() },
            legacyOnDeleteLastTurn = { latestOnDeleteLastTurn.value() }
        )
    }
    val messageRows = remember(state.messages, state.activeConversationId) {
        chatMessageRows(state.messages, state.activeConversationId)
    }
    val lastAssistantIndex = messageRows.indexOfLast { row -> row.message.role == Role.ASSISTANT }
    val characterCardHtml = remember(state.assistants, state.selectedAssistantId) {
        state.assistants.any { assistant ->
            assistant.id == state.selectedAssistantId && assistant.characterCardImported
        }
    }

    Box(modifier = modifier.nestedScroll(nestedScrollConnection)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(
                top = 26.dp,
                bottom = resolveChatListBottomPaddingDp(composerOverlap),
            ),
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
            itemsIndexed(
                items = messageRows,
                key = { _, row -> row.identity }
            ) { index, row ->
                val message = row.message
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
                    characterCardHtml = characterCardHtml,
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
                    onRegenerate = { actionDispatcher.regenerate(row.identity) },
                    onDelete = { actionDispatcher.delete(row.identity) },
                    onTogglePinned = {
                        if (actionDispatcher.isCurrent(row.identity)) onTogglePinMessage(message.id)
                    },
                    canTogglePinned = !state.isGenerating && !state.conversationReadOnly,
                    onDeleteLastTurn = { actionDispatcher.deleteLastTurn(row.identity) },
                    canCopyMessage = { actionDispatcher.isCurrent(row.identity) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = chatMessageAppearSpec,
                        placementSpec = chatMessagePlacementSpec,
                        fadeOutSpec = chatMessageRemoveSpec
                    )
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
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = chatMessageAppearSpec,
                                placementSpec = chatMessagePlacementSpec,
                                fadeOutSpec = chatMessageRemoveSpec
                            )
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            modifier = Modifier.matchParentSize().padding(bottom = composerOverlap)
        )
    }
}
