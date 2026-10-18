package com.muyuchat.feature.chat

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import com.muyuchat.core.designsystem.SystemBackMotionHandler

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ChatScreen(
    state: ChatUiState,
    offlineTranslationStatus: String = "未安装",
    offlineTranslationInstalled: Boolean = false,
    offlineTranslationInstalling: Boolean = false,
    offlineTranslationDownloading: Boolean = false,
    offlineTranslationDownloadProgress: Float? = null,
    assistantMemories: List<AssistantMemoryUiItem> = emptyList(),
    appMenuOpen: Boolean = false,
    chatBackground: ChatBackgroundState = ChatBackgroundState(),
    globalChatBackground: ChatBackgroundState = ChatBackgroundState(),
    assistantChatBackground: ChatBackgroundState? = null,
    sessionChatBackground: ChatBackgroundState? = null,
    hasActiveChatSession: Boolean = false,
    chatBackgroundImporting: Boolean = false,
    modifier: Modifier = Modifier,
    actions: ChatScreenActions,
) {
    val onClearHistory = actions.conversation.onClearHistory
    val onDeleteConversation = actions.conversation.onDeleteConversation
    val onDeleteLastTurn = actions.conversation.onDeleteLastTurn
    val onDeleteMessage = actions.conversation.onDeleteMessage
    val onMessageIntent = actions.conversation.onMessageIntent
    val onExportConversation = actions.conversation.onExportConversation
    val onRenameConversation = actions.conversation.onRenameConversation
    val onSelectConversation = actions.conversation.onSelectConversation
    val onTogglePinConversation = actions.conversation.onTogglePinConversation
    val onTogglePinMessage = actions.conversation.onTogglePinMessage
    val onBranchMixedConversation = actions.conversation.onBranchMixedConversation
    val onCreateConversationForAssistant = actions.conversation.onCreateConversationForAssistant
    val onRegenerate = actions.conversation.onRegenerate
    val onRequestContextCompression = actions.conversation.onRequestContextCompression
    val onSetContextCompressionThreshold = actions.conversation.onSetContextCompressionThreshold
    val onSetContextSummaryModel = actions.conversation.onSetContextSummaryModel
    val onUndoContextSummary = actions.conversation.onUndoContextSummary
    val onSearchContextSummarySources = actions.conversation.onSearchContextSummarySources
    val onInputChange = actions.generation.onInputChange
    val onSend = actions.generation.onSend
    val onSendImagePrompt = actions.generation.onSendImagePrompt
    val onStop = actions.generation.onStop
    val onGenerationParamsChange = actions.generation.onGenerationParamsChange
    val onReasoningModeChange = actions.generation.onReasoningModeChange
    val onCloudReasoningModeLocked = actions.generation.onCloudReasoningModeLocked
    val onModelBackendChange = actions.generation.onModelBackendChange
    val onLoadModel = actions.generation.onLoadModel
    val onUploadFile = actions.generation.onUploadFile
    val releaseGenerationImageGrantsIfCoordinatorIdle = actions.generation.releaseGenerationImageGrantsIfCoordinatorIdle
    val onDeleteAssistant = actions.content.onDeleteAssistant
    val onSaveAssistant = actions.content.onSaveAssistant
    val onSelectAssistant = actions.content.onSelectAssistant
    val onImportAssistantCard = actions.content.onImportAssistantCard
    val onImportAssistantCardFile = actions.content.onImportAssistantCardFile
    val onDeleteWorldBook = actions.content.onDeleteWorldBook
    val onImportWorldBookFile = actions.content.onImportWorldBookFile
    val onCreateKnowledgeBase = actions.content.onCreateKnowledgeBase
    val onDeleteKnowledgeBase = actions.content.onDeleteKnowledgeBase
    val onImportKnowledgeDocument = actions.content.onImportKnowledgeDocument
    val onSetKnowledgeBaseSelected = actions.content.onSetKnowledgeBaseSelected
    val onDeleteAssistantMemory = actions.content.onDeleteAssistantMemory
    val onUpsertAssistantMemory = actions.content.onUpsertAssistantMemory
    val onChatBackgroundChange = actions.content.onChatBackgroundChange
    val onChatBackgroundImageSelected = actions.content.onChatBackgroundImageSelected
    val onApproveChatImageRequest = actions.image.onApproveChatImageRequest
    val onRejectChatImageRequest = actions.image.onRejectChatImageRequest
    val onRetryChatImageRequest = actions.image.onRetryChatImageRequest
    val onCancelChatImageGeneration = actions.image.onCancelChatImageGeneration
    val onCancelImageGeneration = actions.image.onCancelImageGeneration
    val onChatImageLoraSelectionChange = actions.image.onChatImageLoraSelectionChange
    val onConfirmImageTranslationDraft = actions.image.onConfirmImageTranslationDraft
    val onCancelImageTranslationDraft = actions.image.onCancelImageTranslationDraft
    val onResolveImageActionDraft = actions.image.onResolveImageActionDraft
    val onDeleteImageAsset = actions.image.onDeleteImageAsset
    val onDeleteImageAssets = actions.image.onDeleteImageAssets
    val onDeleteImageLora = actions.image.onDeleteImageLora
    val onDeleteImageTextualInversion = actions.image.onDeleteImageTextualInversion
    val onDeleteImageUpscaler = actions.image.onDeleteImageUpscaler
    val onExportImageLibraryBackup = actions.image.onExportImageLibraryBackup
    val onImportImageLibraryBackup = actions.image.onImportImageLibraryBackup
    val onCancelImageLibraryBackup = actions.image.onCancelImageLibraryBackup
    val onImportImageLora = actions.image.onImportImageLora
    val onImportImageModel = actions.image.onImportImageModel
    val onImportImageTextualInversion = actions.image.onImportImageTextualInversion
    val onImportImageUpscaler = actions.image.onImportImageUpscaler
    val onSelectImageModel = actions.image.onSelectImageModel
    val onSelectImageUpscaler = actions.image.onSelectImageUpscaler
    val onSetImageAssetFavorite = actions.image.onSetImageAssetFavorite
    val onUpscaleImageAsset = actions.image.onUpscaleImageAsset
    val onCancelImageUpscale = actions.image.onCancelImageUpscale
    val onRecreateImageAsset = actions.image.onRecreateImageAsset
    val onRetryImageGeneration = actions.image.onRetryImageGeneration
    val onUseImageAsset = actions.image.onUseImageAsset
    val onGenerateImagePrompt = actions.image.onGenerateImagePrompt
    val onContinueAssistantImageTurn = actions.image.onContinueAssistantImageTurn
    val onSetAssistantImageToolAutoApproval = actions.image.onSetAssistantImageToolAutoApproval
    val onDownloadOfficialViggleLora = actions.image.onDownloadOfficialViggleLora
    val onTranslateImagePrompt = actions.image.onTranslateImagePrompt
    val onTranslateImageNegativePrompt = actions.image.onTranslateImageNegativePrompt
    val onMeasureImagePromptTokens = actions.image.onMeasureImagePromptTokens
    val onAppMenuOpenChange = actions.shell.onAppMenuOpenChange
    val onDismissStatusMessage = actions.shell.onDismissStatusMessage
    val onOpenModels = actions.shell.onOpenModels
    val onOpenRecommendedModels = actions.shell.onOpenRecommendedModels
    val onOpenSettings = actions.shell.onOpenSettings
    val onOpenAgent = actions.shell.onOpenAgent
    val onOpenApi = actions.shell.onOpenApi
    val onOpenWebSearchSettings = actions.shell.onOpenWebSearchSettings
    val onImportChatModel = actions.shell.onImportChatModel
    val onBrowserTaskEvent = actions.shell.onBrowserTaskEvent
    val onOpenBrowserTask = actions.shell.onOpenBrowserTask
    val onSelectWebSearchResearchMode = actions.shell.onSelectWebSearchResearchMode
    val onToggleWebSearchForTurn = actions.shell.onToggleWebSearchForTurn
    val onDeleteFileAsset = actions.shell.onDeleteFileAsset
    val onUseFileAsset = actions.shell.onUseFileAsset
    val onDownloadOfflineTranslation = actions.shell.onDownloadOfflineTranslation
    val onCancelOfflineTranslationDownload = actions.shell.onCancelOfflineTranslationDownload
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollFollowThresholdPx = with(LocalDensity.current) {
        CHAT_SCROLL_FOLLOW_THRESHOLD_DP.dp.toPx()
    }
    val scrollCoordinator = remember(listState, scope, scrollFollowThresholdPx) {
        ChatScrollCoordinator(
            listState = listState,
            scope = scope,
            followThresholdPx = scrollFollowThresholdPx,
        )
    }
    val scrollNestedScrollConnection = remember(scrollCoordinator) {
        chatScrollNestedScrollConnection(scrollCoordinator)
    }
    DisposableEffect(scrollCoordinator) {
        onDispose { scrollCoordinator.close() }
    }
    var chatAttachmentPreflightRunning by remember { mutableStateOf(false) }
    val latestChatInput = rememberUpdatedState(state.input)
    val latestOnInputChange = rememberUpdatedState(onInputChange)
    val latestOnSend = rememberUpdatedState(onSend)
    val submitChatMessage = {
        val submittedInput = latestChatInput.value
        if (!chatAttachmentPreflightRunning) {
            // Run the same asynchronous content-identity pass for every image
            // marker. A single marker can still represent two logical images
            // after picker/import recovery (or a file URI copied into a second
            // private path); waiting until the native boundary is too late and
            // makes the user bubble briefly show two images.
            if (chatImageAttachmentMarkerCount(submittedInput) < 1) {
                scrollCoordinator.onUserSentMessage()
                latestOnSend.value()
            } else {
                chatAttachmentPreflightRunning = true
                scope.launch {
                    try {
                        val normalizedInput = withContext(Dispatchers.IO) {
                            deduplicateChatImageAttachmentMarkers(submittedInput) { uri ->
                                chatImageAttachmentContentIdentity(context, uri)
                            }
                        }
                        // Keep a fast edit or a just-imported attachment from being overwritten
                        // by the snapshot that began the asynchronous content check.
                        if (latestChatInput.value != submittedInput) return@launch
                        if (normalizedInput != submittedInput) {
                            latestOnInputChange.value(normalizedInput)
                        }
                        scrollCoordinator.onUserSentMessage()
                        latestOnSend.value()
                    } finally {
                        chatAttachmentPreflightRunning = false
                    }
                }
            }
        }
    }
    val imageParameterPreferences = remember(context) {
        context.applicationContext.getSharedPreferences(
            IMAGE_GENERATION_UI_PARAMETER_PREFS,
            Context.MODE_PRIVATE
        )
    }
    val generationImageGrantMutex = remember { Mutex() }
    var showImages by rememberSaveable { mutableStateOf(false) }
    var showAssistants by rememberSaveable { mutableStateOf(false) }
    var showNewConversationPicker by rememberSaveable { mutableStateOf(false) }
    var newConversationAssistantId by rememberSaveable { mutableStateOf<String?>(null) }
    val requestNewConversation: () -> Unit = {
        newConversationAssistantId = null
        showNewConversationPicker = true
    }
    var showQuickStart by rememberSaveable { mutableStateOf(false) }
    var showFileLibrary by rememberSaveable { mutableStateOf(false) }
    var showBackgroundSettings by rememberSaveable { mutableStateOf(false) }
    var pendingBackgroundScope by rememberSaveable { mutableStateOf(ChatBackgroundScope.ASSISTANT) }
    // App contract readers verify these tokens in ChatScreen.kt:
    // releaseOwnedUrisIfCoordinatorIdle
    // libraryHistoryReferencedUris
    // onRetryImageGeneration(job.id)
    // 按原参数再生成
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onUploadFile(it.toString()) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onUploadFile(it.toString()) }
    }
    val chatBackgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            onChatBackgroundImageSelected(pendingBackgroundScope, selectedUri.toString())
        }
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let { onUploadFile(saveCameraPreview(context, it).toString()) }
    }
    val browserSearchCardVisible = state.browserTask?.let { task ->
        task.kind == BrowserTaskKind.SEARCH && !task.windowVisible && task.phase in setOf(
            BrowserTaskPhase.SEARCHING,
            BrowserTaskPhase.AWAITING_CONFIRMATION
        )
    } == true
    val scrollRestoreTargetIndex = when {
        state.messages.isNotEmpty() ->
            state.messages.lastIndex + if (browserSearchCardVisible) 1 else 0
        browserSearchCardVisible -> 1
        else -> 0
    }
    LaunchedEffect(scrollCoordinator, state.activeConversationId) {
        scrollCoordinator.activateSession(
            conversationId = state.activeConversationId,
            restoreTargetItemIndex = scrollRestoreTargetIndex,
        )
        snapshotFlow { listState.toChatScrollLayoutObservation() }
            .distinctUntilChanged()
            .collect { observation ->
                scrollCoordinator.onLayoutObservation(observation)
            }
    }

    val historyBackEnabled = !WindowInsets.isImeVisible && (
        drawerState.currentValue == DrawerValue.Open ||
            drawerState.targetValue == DrawerValue.Open
        )
    SystemBackMotionHandler(
        enabled = historyBackEnabled,
        onProgress = {},
        onCancel = {},
        onBack = {
            scope.launch { drawerState.close() }
        }
    )
    BackHandler(enabled = historyBackEnabled) {
        scope.launch { drawerState.close() }
    }
    var bottomChromeHeightPx by remember { mutableStateOf(0) }
    val bottomChromeHeight = resolveChatComposerOverlapDp(
        with(LocalDensity.current) { bottomChromeHeightPx.toDp() }
    )
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                state = state,
                onClose = { scope.launch { drawerState.close() } },
                onNewConversation = {
                    requestNewConversation()
                    scope.launch { drawerState.close() }
                },
                onOpenAppMenu = {
                    onAppMenuOpenChange(true)
                    scope.launch { drawerState.close() }
                },
                onOpenImages = {
                    showImages = true
                    scope.launch { drawerState.close() }
                },
                onSelectConversation = { id ->
                    onSelectConversation(id)
                    scope.launch { drawerState.close() }
                },
                onBranchMixedConversation = { id, assistantId ->
                    onBranchMixedConversation(id, assistantId)
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = onDeleteConversation,
                onClearHistory = onClearHistory,
                onRenameConversation = onRenameConversation,
                onTogglePinConversation = onTogglePinConversation,
                onExportConversation = onExportConversation
            )
        }
    ) {
        Box(
            modifier = Modifier
                .then(modifier)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Keep the composer above the IME. adjustResize alone is not
                // sufficient on all MIUI/edge-to-edge configurations and can
                // leave the send/stop control underneath the keyboard.
                .imePadding()
        ) {
            ChatBackground(
                state = chatBackground,
                modifier = Modifier.fillMaxSize()
            )
            Column(modifier = Modifier.fillMaxSize()) {
                ChatStatusBar(
                    state = state,
                    onOpenHistory = { scope.launch { drawerState.open() } },
                    onNewConversation = requestNewConversation,
                    onLoadModel = onLoadModel,
                    onModelBackendChange = onModelBackendChange,
                    generationParams = state.generationParams,
                    onGenerationParamsChange = onGenerationParamsChange,
                    onOpenModels = onOpenModels,
                    onReasoningModeChange = onReasoningModeChange,
                    onCloudReasoningModeLocked = onCloudReasoningModeLocked,
                    onOpenAppMenu = { onAppMenuOpenChange(true) }
                )

                state.promptContextUsage?.let { usage ->
                    PromptContextUsageLine(usage)
                }

                ChatMessageListSection(
                    listState = listState,
                    state = state,
                    composerOverlap = bottomChromeHeight,
                    onOpenRecommendedModels = onOpenRecommendedModels,
                    onImportChatModel = onImportChatModel,
                    onOpenQuickStart = { showQuickStart = true },
                    onOpenBrowserTask = onOpenBrowserTask,
                    onRetryChatImageRequest = onRetryChatImageRequest,
                    onCancelChatImageGeneration = onCancelChatImageGeneration,
                    onApproveChatImageRequest = onApproveChatImageRequest,
                    onRejectChatImageRequest = onRejectChatImageRequest,
                    onContinueAssistantImageTurn = onContinueAssistantImageTurn,
                    onOpenModels = onOpenModels,
                    onUseImageAsset = onUseImageAsset,
                    onDeleteImageAsset = onDeleteImageAsset,
                    onRegenerate = onRegenerate,
                    onDeleteMessage = { onDeleteMessage(it) },
                    onTogglePinMessage = { onTogglePinMessage(it) },
                    onDeleteLastTurn = onDeleteLastTurn,
                    onMessageIntent = onMessageIntent,
                    onBrowserTaskEvent = onBrowserTaskEvent,
                    nestedScrollConnection = scrollNestedScrollConnection,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            if (state.conversationReadOnly) {
                Text(
                    "此会话仅供查看。可从历史菜单创建角色分支。",
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { bottomChromeHeightPx = it.height }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else ChatInputBar(
                onOpenModels = onOpenModels,
                input = state.input,
                isGenerating = state.isGenerating,
                selectedImageModelName = state.selectedImageModelName,
                selectedImageModelIsCloud = state.selectedImageModelIsCloud,
                assistantImageToolAvailable = state.assistantImageToolAvailable,
                assistantImageToolUnavailableReason = state.assistantImageToolUnavailableReason,
                assistantImageToolAutoApproval = state.assistantImageToolAutoApproval,
                assistantImageToolCanChange = state.assistantImageToolCanChange,
                imageGenerationBusy = state.imageJobs.any { job -> !job.terminal },
                statusMessage = state.statusMessage,
                onInputChange = onInputChange,
                onDismissStatusMessage = onDismissStatusMessage,
                onSend = submitChatMessage,
                onSendImagePrompt = onSendImagePrompt,
                attachmentPreflightRunning = chatAttachmentPreflightRunning,
                onSetAssistantImageToolAutoApproval = onSetAssistantImageToolAutoApproval,
                onStop = onStop,
                onOpenCamera = {
                    cameraPicker.launch(null)
                },
                onOpenPhoto = {
                    photoPicker.launch("image/*")
                },
                onOpenFile = {
                    filePicker.launch(arrayOf("text/*", "application/json", "application/xml"))
                },
                onOpenFileLibrary = {
                    showFileLibrary = true
                },
                reasoningMode = state.reasoningMode,
                onReasoningModeChange = onReasoningModeChange,
                webSearchEnabled = state.webSearchEnabled,
                webSearchConfigured = state.webSearchConfigured,
                webSearchEnabledForTurn = state.webSearchEnabledForTurn,
                webSearchStatusMessage = state.webSearchStatusMessage,
                webSearchTurnModeLabel = state.webSearchTurnModeLabel,
                webSearchResearchMode = state.webSearchResearchMode,
                webSearchResearchModeLabel = state.webSearchResearchModeLabel,
                webSearchResearchOverridden = state.webSearchResearchOverridden,
                webSearchProviderLabel = state.webSearchProviderLabel,
                visionCapabilityLabel = state.visionCapabilityLabel,
                visionCapabilityDetail = state.visionCapabilityDetail,
                visionCapabilityReady = state.visionCapabilityReady,
                onToggleWebSearchForTurn = onToggleWebSearchForTurn,
                onSelectWebSearchResearchMode = onSelectWebSearchResearchMode,
                onOpenWebSearchSettings = onOpenWebSearchSettings,
                onMeasuredHeight = { bottomChromeHeightPx = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            if (showQuickStart) {
                QuickStartDialog(
                    onDismiss = { showQuickStart = false },
                    onChooseModel = { showQuickStart = false; onOpenRecommendedModels() }
                )
            }

            state.pendingImageTranslationDraft?.let { draft ->
                ChatImageTranslationDraftDialog(
                    draft = draft,
                    onConfirm = onConfirmImageTranslationDraft,
                    onCancel = onCancelImageTranslationDraft
                )
            }

            state.pendingImageActionDraft?.let { draft ->
                ChatImageActionDraftDialog(
                    draft = draft,
                    onResolve = onResolveImageActionDraft
                )
            }

            ChatAssistantRoleSubpage(
                visible = showAssistants,
                onDismiss = { showAssistants = false },
                state = state,
                assistantMemories = assistantMemories,
                onSaveAssistant = onSaveAssistant,
                onUpsertAssistantMemory = onUpsertAssistantMemory,
                onDeleteAssistantMemory = onDeleteAssistantMemory,
                onSelectAssistant = onSelectAssistant,
                onDeleteAssistant = onDeleteAssistant,
                onImportAssistantCard = onImportAssistantCard,
                onImportAssistantCardFile = onImportAssistantCardFile,
                onImportWorldBookFile = onImportWorldBookFile,
                onDeleteWorldBook = onDeleteWorldBook,
                onCreateKnowledgeBase = onCreateKnowledgeBase,
                onImportKnowledgeDocument = onImportKnowledgeDocument,
                onSetKnowledgeBaseSelected = onSetKnowledgeBaseSelected,
                onDeleteKnowledgeBase = onDeleteKnowledgeBase
            )

            ChatImagesWorkspaceOverlay(
                visible = showImages,
                onDismiss = { showImages = false },
                state = state,
                actions = actions,
                offlineTranslationStatus = offlineTranslationStatus,
                offlineTranslationInstalled = offlineTranslationInstalled,
                offlineTranslationInstalling = offlineTranslationInstalling,
                offlineTranslationDownloading = offlineTranslationDownloading,
                offlineTranslationDownloadProgress = offlineTranslationDownloadProgress,
                onDownloadOfflineTranslation = onDownloadOfflineTranslation,
                onCancelOfflineTranslationDownload = onCancelOfflineTranslationDownload,
                onOpenRecommendedModels = onOpenRecommendedModels,
                onOpenModels = onOpenModels
            )

            ChatFileLibrarySubpage(
                visible = showFileLibrary,
                onDismiss = { showFileLibrary = false },
                files = state.files,
                onUseFileAsset = onUseFileAsset,
                onDeleteFileAsset = onDeleteFileAsset
            )

            ChatBackgroundSettingsSubpage(
                visible = showBackgroundSettings,
                onDismiss = { showBackgroundSettings = false; onAppMenuOpenChange(true) },
                chatBackground = chatBackground,
                globalChatBackground = globalChatBackground,
                assistantChatBackground = assistantChatBackground,
                sessionChatBackground = sessionChatBackground,
                hasActiveChatSession = hasActiveChatSession,
                chatBackgroundImporting = chatBackgroundImporting,
                onChooseImage = { scope ->
                    pendingBackgroundScope = scope
                    chatBackgroundPicker.launch(arrayOf("image/*"))
                },
                onChatBackgroundChange = onChatBackgroundChange
            )

            ChatPredictiveAppMenuSubpage(
                visible = appMenuOpen,
                onDismiss = { onAppMenuOpenChange(false) },
                state = state,
                onOpenQuickStart = { showQuickStart = true },
                onOpenModels = onOpenModels,
                onOpenAgent = onOpenAgent,
                onOpenApi = onOpenApi,
                onOpenSettings = onOpenSettings,
                onOpenImages = { showImages = true },
                onOpenBackgroundSettings = { onAppMenuOpenChange(false); showBackgroundSettings = true },
                onOpenAssistants = { showAssistants = true },
                onClearHistory = onClearHistory,
                onRequestContextCompression = onRequestContextCompression,
                onUndoContextSummary = onUndoContextSummary,
                onSearchContextSummarySources = onSearchContextSummarySources,
                onSetContextCompressionThreshold = onSetContextCompressionThreshold,
                onSetContextSummaryModel = onSetContextSummaryModel
            )
        }
    }

    ChatNewConversationPickerDialog(
        show = showNewConversationPicker,
        assistants = state.assistants,
        history = state.history,
        selectedAssistantId = newConversationAssistantId,
        onSelectAssistant = { assistantId ->
            if (state.history.any { it.assistantId == assistantId }) {
                newConversationAssistantId = assistantId
            } else {
                onCreateConversationForAssistant(assistantId)
                showNewConversationPicker = false
            }
        },
        onResume = { sessionId ->
            onSelectConversation(sessionId)
            showNewConversationPicker = false
        },
        onCreate = { assistantId ->
            onCreateConversationForAssistant(assistantId)
            showNewConversationPicker = false
        },
        onBack = { newConversationAssistantId = null },
        onDismiss = { showNewConversationPicker = false }
    )
}
