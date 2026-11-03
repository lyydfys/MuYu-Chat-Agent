package com.muyuchat.feature.chat

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The chat page's own state holder and its named user-intent surface.
 *
 * ## What this owns
 *
 * [state] is the page's state. [ChatRoute] publishes the app's projection into this holder and the
 * chat page renders from it, so the chat presentation boundary owns the state it draws instead of
 * reading an anonymous parameter that the composition root recomputes inline. [inputs] is the same
 * deal for the thirteen appearance/session values that used to be forwarded one by one.
 *
 * ## Why the intents exist
 *
 * `ChatScreen` does not talk about "callbacks" -- it talks about clearing history, sending a turn,
 * approving a generated image. [screenActions] therefore rebuilds the [ChatScreenActions] bundle the
 * screen consumes *out of* the named intents below, which makes this class the single place where
 * "what the chat page can be asked to do" is written down, and puts the intent layer on the real
 * call path rather than beside it: `ChatScreen` -> [ChatScreenActions] -> named intent -> the
 * app-injected capability. The capabilities themselves stay injected from the app; the feature does
 * not reach for them (see the note on direction below).
 *
 * ## What this deliberately is not
 *
 * It is not an `androidx.lifecycle.ViewModel`: `:feature:chat` has no lifecycle-viewmodel dependency
 * and adding one is out of this change's scope, so this is the "equivalent chat-owned state holder"
 * the boundary calls for. It is held by `ChatRoute`'s composition (`remember`).
 *
 * It is also not a second writer. The app still owns every business decision and remains the single
 * writer of the projection, exactly as before: [update] is how its decision reaches the page. That
 * direction -- app injects capabilities and state, feature composes them -- is what keeps
 * `:feature:chat` free of any `app` import.
 */
class ChatViewModel(
    state: ChatUiState,
    inputs: ChatRouteInputs,
    capabilities: ChatScreenActions
) {
    private val _state = MutableStateFlow(state)

    /** The chat page's state. Written only by [update], from the app's projection. */
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** The thirteen appearance/session values the page needs besides its state. */
    var inputs: ChatRouteInputs = inputs
        private set

    private var capabilities: ChatScreenActions = capabilities

    private var _screenActions: ChatScreenActions = buildScreenActions()

    /**
     * The action bundle handed to `ChatScreen`.
     *
     * Prefer calling the named intents directly; this exists because `ChatScreen`'s parameter type is
     * the pre-existing [ChatScreenActions] contract, which this change must not alter.
     */
    val screenActions: ChatScreenActions get() = _screenActions

    /**
     * Publishes one frame of the app's projection into the holder.
     *
     * The value is written before the caller reads [state], so a frame renders exactly the state it
     * was composed with -- no stale frame and no extra recomposition. Identity is checked first
     * because the app hands over a freshly built (but usually equal) instance on every
     * recomposition, and `StateFlow` conflates equal values anyway.
     */
    fun update(state: ChatUiState, inputs: ChatRouteInputs, capabilities: ChatScreenActions) {
        this.inputs = inputs
        if (this.capabilities !== capabilities) {
            this.capabilities = capabilities
            _screenActions = buildScreenActions()
        }
        if (_state.value !== state) _state.value = state
    }

    // -- conversation ------------------------------------------------------------------------

    fun clearHistory() = capabilities.conversation.onClearHistory()

    fun deleteConversation(conversationId: String) =
        capabilities.conversation.onDeleteConversation(conversationId)

    fun deleteLastTurn() = capabilities.conversation.onDeleteLastTurn()

    fun deleteMessage(messageIndex: Int) = capabilities.conversation.onDeleteMessage(messageIndex)

    fun exportConversation(format: String) = capabilities.conversation.onExportConversation(format)

    fun renameConversation(conversationId: String, title: String) =
        capabilities.conversation.onRenameConversation(conversationId, title)

    fun selectConversation(conversationId: String) =
        capabilities.conversation.onSelectConversation(conversationId)

    fun togglePinConversation(conversationId: String) =
        capabilities.conversation.onTogglePinConversation(conversationId)

    fun togglePinMessage(messageId: String) = capabilities.conversation.onTogglePinMessage(messageId)

    fun branchMixedConversation(conversationId: String, assistantId: String) =
        capabilities.conversation.onBranchMixedConversation(conversationId, assistantId)

    fun createConversationForAssistant(assistantId: String) =
        capabilities.conversation.onCreateConversationForAssistant(assistantId)

    fun regenerate() = capabilities.conversation.onRegenerate()

    fun requestContextCompression() = capabilities.conversation.onRequestContextCompression()

    fun setContextCompressionThreshold(percent: Int) =
        capabilities.conversation.onSetContextCompressionThreshold(percent)

    fun setContextSummaryModel(selection: String) =
        capabilities.conversation.onSetContextSummaryModel(selection)

    fun undoContextSummary() = capabilities.conversation.onUndoContextSummary()

    fun searchContextSummarySources(query: String): List<ContextSummaryEvidenceUiItem> =
        capabilities.conversation.onSearchContextSummarySources(query)

    // -- generation and input -----------------------------------------------------------------

    fun changeInput(text: String) = capabilities.generation.onInputChange(text)

    fun send() = capabilities.generation.onSend()

    fun sendImagePrompt() = capabilities.generation.onSendImagePrompt()

    fun stop() = capabilities.generation.onStop()

    fun changeGenerationParams(params: GenerationParams) =
        capabilities.generation.onGenerationParamsChange(params)

    fun changeReasoningMode(mode: ReasoningMode) =
        capabilities.generation.onReasoningModeChange(mode)

    fun reportCloudReasoningLocked() = capabilities.generation.onCloudReasoningModeLocked()

    fun changeModelBackend(backendId: String, modelId: String) =
        capabilities.generation.onModelBackendChange(backendId, modelId)

    fun loadModel(modelId: String) = capabilities.generation.onLoadModel(modelId)

    fun uploadFile(kind: String) = capabilities.generation.onUploadFile(kind)

    fun releaseGenerationImageGrantsIfCoordinatorIdle(release: () -> Unit): Boolean =
        capabilities.generation.releaseGenerationImageGrantsIfCoordinatorIdle(release)

    // -- content: assistants, world books, knowledge, memory, background -----------------------

    fun deleteAssistant(assistantId: String) = capabilities.content.onDeleteAssistant(assistantId)

    fun saveAssistant(draft: AssistantEditorDraft): Boolean =
        capabilities.content.onSaveAssistant(draft)

    fun selectAssistant(assistantId: String) = capabilities.content.onSelectAssistant(assistantId)

    fun importAssistantCard(rawCard: String) = capabilities.content.onImportAssistantCard(rawCard)

    fun importAssistantCardFile() = capabilities.content.onImportAssistantCardFile()

    fun deleteWorldBook(worldBookId: String) = capabilities.content.onDeleteWorldBook(worldBookId)

    fun setWorldBookEnabled(worldBookId: String, enabled: Boolean) =
        capabilities.content.onSetWorldBookEnabled(worldBookId, enabled)

    fun importWorldBookFile(scope: WorldBookImportScope) =
        capabilities.content.onImportWorldBookFile(scope)

    fun createKnowledgeBase(name: String) = capabilities.content.onCreateKnowledgeBase(name)

    fun deleteKnowledgeBase(knowledgeBaseId: String) =
        capabilities.content.onDeleteKnowledgeBase(knowledgeBaseId)

    fun importKnowledgeDocument(knowledgeBaseId: String) =
        capabilities.content.onImportKnowledgeDocument(knowledgeBaseId)

    fun setKnowledgeBaseSelected(knowledgeBaseId: String, selected: Boolean) =
        capabilities.content.onSetKnowledgeBaseSelected(knowledgeBaseId, selected)

    fun deleteAssistantMemory(assistantId: String, memoryId: String) =
        capabilities.content.onDeleteAssistantMemory(assistantId, memoryId)

    fun upsertAssistantMemory(
        assistantId: String,
        memoryId: String?,
        scope: String,
        content: String
    ): Deferred<Boolean> =
        capabilities.content.onUpsertAssistantMemory(assistantId, memoryId, scope, content)

    fun changeChatBackground(scope: ChatBackgroundScope, background: ChatBackgroundState?) =
        capabilities.content.onChatBackgroundChange(scope, background)

    fun selectChatBackgroundImage(scope: ChatBackgroundScope, reference: String) =
        capabilities.content.onChatBackgroundImageSelected(scope, reference)

    // -- image generation and library ---------------------------------------------------------

    fun approveChatImageRequest(requestId: String) =
        capabilities.image.onApproveChatImageRequest(requestId)

    fun rejectChatImageRequest(requestId: String) =
        capabilities.image.onRejectChatImageRequest(requestId)

    fun retryChatImageRequest(requestId: String) =
        capabilities.image.onRetryChatImageRequest(requestId)

    fun cancelChatImageGeneration(messageId: String) =
        capabilities.image.onCancelChatImageGeneration(messageId)

    fun cancelImageGeneration() = capabilities.image.onCancelImageGeneration()

    fun changeChatImageLoraSelection(selections: List<ImageGenerationUiLoraSelection>?) =
        capabilities.image.onChatImageLoraSelectionChange(selections)

    fun confirmImageTranslationDraft(draftId: String, translated: String, target: String?) =
        capabilities.image.onConfirmImageTranslationDraft(draftId, translated, target)

    fun cancelImageTranslationDraft(draftId: String) =
        capabilities.image.onCancelImageTranslationDraft(draftId)

    fun resolveImageActionDraft(draftId: String, accepted: Boolean, detail: String) =
        capabilities.image.onResolveImageActionDraft(draftId, accepted, detail)

    fun deleteImageAsset(assetId: String) = capabilities.image.onDeleteImageAsset(assetId)

    fun deleteImageAssets(assetIds: List<String>) = capabilities.image.onDeleteImageAssets(assetIds)

    fun deleteImageLora(loraId: String) = capabilities.image.onDeleteImageLora(loraId)

    fun deleteImageTextualInversion(inversionId: String) =
        capabilities.image.onDeleteImageTextualInversion(inversionId)

    fun deleteImageUpscaler(upscalerId: String) =
        capabilities.image.onDeleteImageUpscaler(upscalerId)

    fun exportImageLibraryBackup(target: String, includeImages: Boolean) =
        capabilities.image.onExportImageLibraryBackup(target, includeImages)

    fun importImageLibraryBackup(source: String) =
        capabilities.image.onImportImageLibraryBackup(source)

    fun cancelImageLibraryBackup() = capabilities.image.onCancelImageLibraryBackup()

    fun importImageLora(source: String) = capabilities.image.onImportImageLora(source)

    fun importImageModel() = capabilities.image.onImportImageModel()

    fun importImageTextualInversion(source: String, name: String) =
        capabilities.image.onImportImageTextualInversion(source, name)

    fun importImageUpscaler(source: String) = capabilities.image.onImportImageUpscaler(source)

    fun selectImageModel(modelId: String) = capabilities.image.onSelectImageModel(modelId)

    fun selectImageUpscaler(upscalerId: String) = capabilities.image.onSelectImageUpscaler(upscalerId)

    fun setImageAssetFavorite(assetId: String, favorite: Boolean) =
        capabilities.image.onSetImageAssetFavorite(assetId, favorite)

    fun upscaleImageAsset(assetId: String, targetScale: Int) =
        capabilities.image.onUpscaleImageAsset(assetId, targetScale)

    fun cancelImageUpscale() = capabilities.image.onCancelImageUpscale()

    fun recreateImageAsset(assetId: String) = capabilities.image.onRecreateImageAsset(assetId)

    fun retryImageGeneration(messageId: String) =
        capabilities.image.onRetryImageGeneration(messageId)

    fun useImageAsset(assetId: String) = capabilities.image.onUseImageAsset(assetId)

    fun generateImagePrompt(prompt: String, options: ImageGenerationUiOptions): Boolean =
        capabilities.image.onGenerateImagePrompt(prompt, options)

    fun continueAssistantImageTurn(messageId: String) =
        capabilities.image.onContinueAssistantImageTurn(messageId)

    fun setAssistantImageToolAutoApproval(enabled: Boolean) =
        capabilities.image.onSetAssistantImageToolAutoApproval(enabled)

    fun downloadOfficialViggleLora() = capabilities.image.onDownloadOfficialViggleLora()

    /**
     * Null when the app injected no translator at all.
     *
     * The nullability is part of the contract, not an accident: `ChatScreen` decides whether to offer
     * the action by testing the lambda for null, so the intent must stay nullable and the rebuilt
     * bundle must pass null through unchanged.
     */
    val translateImagePrompt: (suspend (String) -> String)?
        get() = capabilities.image.onTranslateImagePrompt

    /** Null when the app injected no translator at all; same contract as [translateImagePrompt]. */
    val translateImageNegativePrompt: (suspend (String) -> String)?
        get() = capabilities.image.onTranslateImageNegativePrompt

    /** Null when the app injected no token meter at all; same contract as [translateImagePrompt]. */
    val measureImagePromptTokens: (suspend (modelId: String, prompt: String) -> ImagePromptTokenMeasurement?)?
        get() = capabilities.image.onMeasureImagePromptTokens

    // -- shell and navigation -----------------------------------------------------------------

    fun changeAppMenuOpen(open: Boolean) = capabilities.shell.onAppMenuOpenChange(open)

    fun dismissStatusMessage() = capabilities.shell.onDismissStatusMessage()

    fun openModels() = capabilities.shell.onOpenModels()

    fun openPresets() = capabilities.shell.onOpenPresets()

    fun openRecommendedModels() = capabilities.shell.onOpenRecommendedModels()

    fun openSettings() = capabilities.shell.onOpenSettings()

    fun openAgent() = capabilities.shell.onOpenAgent()

    fun openAgentWorkspace() = capabilities.shell.onOpenAgentWorkspace()

    fun openDshWorkspace() = capabilities.shell.onOpenDshWorkspace()

    fun openVideoWorkspace() = capabilities.shell.onOpenVideoWorkspace()

    fun openApi() = capabilities.shell.onOpenApi()

    fun openWebSearchSettings() = capabilities.shell.onOpenWebSearchSettings()

    fun importChatModel() = capabilities.shell.onImportChatModel()

    fun handleBrowserTaskEvent(event: BrowserTaskEvent) = capabilities.shell.onBrowserTaskEvent(event)

    fun openBrowserTask(launch: BrowserTaskLaunch) = capabilities.shell.onOpenBrowserTask(launch)

    fun selectWebSearchResearchMode(mode: String) =
        capabilities.shell.onSelectWebSearchResearchMode(mode)

    fun toggleWebSearchForTurn() = capabilities.shell.onToggleWebSearchForTurn()

    fun deleteFileAsset(assetId: String) = capabilities.shell.onDeleteFileAsset(assetId)

    fun useFileAsset(assetId: String) = capabilities.shell.onUseFileAsset(assetId)

    fun downloadOfflineTranslation() = capabilities.shell.onDownloadOfflineTranslation()

    fun cancelOfflineTranslationDownload() = capabilities.shell.onCancelOfflineTranslationDownload()

    /**
     * Rebuilds the bundle `ChatScreen` consumes out of the named intents above.
     *
     * Every one of the ninety-six fields is spelled out. Leaving one to its default would silently
     * replace a real capability with a no-op, so the defaults are never relied on here.
     */
    private fun buildScreenActions(): ChatScreenActions = ChatScreenActions(
        conversation = ChatConversationActions(
            onClearHistory = ::clearHistory,
            onDeleteConversation = ::deleteConversation,
            onDeleteLastTurn = ::deleteLastTurn,
            onDeleteMessage = ::deleteMessage,
            onExportConversation = ::exportConversation,
            onRenameConversation = ::renameConversation,
            onSelectConversation = ::selectConversation,
            onTogglePinConversation = ::togglePinConversation,
            onTogglePinMessage = ::togglePinMessage,
            onBranchMixedConversation = ::branchMixedConversation,
            onCreateConversationForAssistant = ::createConversationForAssistant,
            onRegenerate = ::regenerate,
            onRequestContextCompression = ::requestContextCompression,
            onSetContextCompressionThreshold = ::setContextCompressionThreshold,
            onSetContextSummaryModel = ::setContextSummaryModel,
            onUndoContextSummary = ::undoContextSummary,
            onSearchContextSummarySources = ::searchContextSummarySources,
            onMessageIntent = capabilities.conversation.onMessageIntent,
        ),
        generation = ChatGenerationActions(
            onInputChange = ::changeInput,
            onSend = ::send,
            onSendImagePrompt = ::sendImagePrompt,
            onStop = ::stop,
            onGenerationParamsChange = ::changeGenerationParams,
            onReasoningModeChange = ::changeReasoningMode,
            onCloudReasoningModeLocked = ::reportCloudReasoningLocked,
            onModelBackendChange = ::changeModelBackend,
            onLoadModel = ::loadModel,
            onUploadFile = ::uploadFile,
            releaseGenerationImageGrantsIfCoordinatorIdle = ::releaseGenerationImageGrantsIfCoordinatorIdle,
        ),
        content = ChatContentActions(
            onDeleteAssistant = ::deleteAssistant,
            onSaveAssistant = ::saveAssistant,
            onSelectAssistant = ::selectAssistant,
            onImportAssistantCard = ::importAssistantCard,
            onImportAssistantCardFile = ::importAssistantCardFile,
            onDeleteWorldBook = ::deleteWorldBook,
            onSetWorldBookEnabled = ::setWorldBookEnabled,
            onImportWorldBookFile = ::importWorldBookFile,
            onCreateKnowledgeBase = ::createKnowledgeBase,
            onDeleteKnowledgeBase = ::deleteKnowledgeBase,
            onImportKnowledgeDocument = ::importKnowledgeDocument,
            onSetKnowledgeBaseSelected = ::setKnowledgeBaseSelected,
            onDeleteAssistantMemory = ::deleteAssistantMemory,
            onUpsertAssistantMemory = ::upsertAssistantMemory,
            onChatBackgroundChange = ::changeChatBackground,
            onChatBackgroundImageSelected = ::selectChatBackgroundImage,
        ),
        image = ChatImageActions(
            onApproveChatImageRequest = ::approveChatImageRequest,
            onRejectChatImageRequest = ::rejectChatImageRequest,
            onRetryChatImageRequest = ::retryChatImageRequest,
            onCancelChatImageGeneration = ::cancelChatImageGeneration,
            onCancelImageGeneration = ::cancelImageGeneration,
            onChatImageLoraSelectionChange = ::changeChatImageLoraSelection,
            onConfirmImageTranslationDraft = ::confirmImageTranslationDraft,
            onCancelImageTranslationDraft = ::cancelImageTranslationDraft,
            onResolveImageActionDraft = ::resolveImageActionDraft,
            onDeleteImageAsset = ::deleteImageAsset,
            onDeleteImageAssets = ::deleteImageAssets,
            onDeleteImageLora = ::deleteImageLora,
            onDeleteImageTextualInversion = ::deleteImageTextualInversion,
            onDeleteImageUpscaler = ::deleteImageUpscaler,
            onExportImageLibraryBackup = ::exportImageLibraryBackup,
            onImportImageLibraryBackup = ::importImageLibraryBackup,
            onCancelImageLibraryBackup = ::cancelImageLibraryBackup,
            onImportImageLora = ::importImageLora,
            onImportImageModel = ::importImageModel,
            onImportImageTextualInversion = ::importImageTextualInversion,
            onImportImageUpscaler = ::importImageUpscaler,
            onSelectImageModel = ::selectImageModel,
            onSelectImageUpscaler = ::selectImageUpscaler,
            onSetImageAssetFavorite = ::setImageAssetFavorite,
            onUpscaleImageAsset = ::upscaleImageAsset,
            onCancelImageUpscale = ::cancelImageUpscale,
            onRecreateImageAsset = ::recreateImageAsset,
            onRetryImageGeneration = ::retryImageGeneration,
            onUseImageAsset = ::useImageAsset,
            onGenerateImagePrompt = ::generateImagePrompt,
            onContinueAssistantImageTurn = ::continueAssistantImageTurn,
            onSetAssistantImageToolAutoApproval = ::setAssistantImageToolAutoApproval,
            onDownloadOfficialViggleLora = ::downloadOfficialViggleLora,
            // Passed through by value, not wrapped: a wrapper would make an absent capability look
            // present, and ChatScreen offers these actions only when the lambda is non-null.
            onTranslateImagePrompt = capabilities.image.onTranslateImagePrompt,
            onTranslateImageNegativePrompt = capabilities.image.onTranslateImageNegativePrompt,
            onMeasureImagePromptTokens = capabilities.image.onMeasureImagePromptTokens,
        ),
        shell = ChatShellActions(
            onAppMenuOpenChange = ::changeAppMenuOpen,
            onDismissStatusMessage = ::dismissStatusMessage,
            onOpenModels = ::openModels,
            onOpenPresets = ::openPresets,
            onOpenRecommendedModels = ::openRecommendedModels,
            onOpenSettings = ::openSettings,
            onOpenAgent = ::openAgent,
            onOpenAgentWorkspace = ::openAgentWorkspace,
            onOpenDshWorkspace = ::openDshWorkspace,
            onOpenVideoWorkspace = ::openVideoWorkspace,
            onOpenApi = ::openApi,
            onOpenWebSearchSettings = ::openWebSearchSettings,
            onImportChatModel = ::importChatModel,
            onBrowserTaskEvent = ::handleBrowserTaskEvent,
            onOpenBrowserTask = ::openBrowserTask,
            onSelectWebSearchResearchMode = ::selectWebSearchResearchMode,
            onToggleWebSearchForTurn = ::toggleWebSearchForTurn,
            onDeleteFileAsset = ::deleteFileAsset,
            onUseFileAsset = ::useFileAsset,
            onDownloadOfflineTranslation = ::downloadOfflineTranslation,
            onCancelOfflineTranslationDownload = ::cancelOfflineTranslationDownload,
        ),
    )
}
