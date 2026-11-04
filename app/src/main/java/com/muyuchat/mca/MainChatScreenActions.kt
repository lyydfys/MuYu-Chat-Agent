package com.muyuchat.mca

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.muyuchat.mca.presentation.toAppearance
import com.muyuchat.mca.presentation.toAppearanceScope
import com.muyuchat.mca.presentation.toLocalImagePreviewOptions
import com.muyuchat.feature.chat.AssistantEditorDraft
import com.muyuchat.feature.chat.ChatContentActions
import com.muyuchat.feature.chat.ChatConversationActions
import com.muyuchat.feature.chat.ChatGenerationActions
import com.muyuchat.feature.chat.ChatImageActions
import com.muyuchat.feature.chat.ChatScreenActions
import com.muyuchat.feature.chat.ChatShellActions
import com.muyuchat.feature.chat.ImageGenerationUiLoraSelection
import com.muyuchat.feature.chat.ImageGenerationUiOptions
import com.muyuchat.feature.chat.WorldBookImportScope

/**
 * The chat page's actions, assembled once instead of inline in the composition root.
 *
 * This was 204 lines in the middle of `MainActivity`'s chat call site -- the single longest piece of
 * wiring there. It is a pure move: every lambda is the one that was there before, in the same order,
 * with the same receiver.
 *
 * Two things the old inline block captured are explicit parameters now. Five are callbacks the
 * enclosing composable owned; four are the shell bits the chat page drives but does not own -- three
 * `rememberSaveable` flags it only ever writes, plus the tab switch it calls. Passing the setters
 * keeps that state owned by `MainActivity`: the single-writer rule is intact and nothing is copied.
 *
 * ## P03: one stable bundle instead of 96 fresh lambdas per frame
 *
 * The chat page receives this bundle through `ChatRoute`/`ChatViewModel`. Rebuilding it every
 * composition also rebuilt `ChatViewModel.screenActions` (its identity check is `!==`), so a
 * streaming frame re-created ninety-six lambdas even though none of the covered intents changed.
 * [rememberMainChatScreenActions] now builds the bundle once per (viewModel, host) pair and keeps
 * only the **host callbacks** up to date:
 *
 * - Host callbacks (tab switch, import/export launchers, menu setters) are published to
 *   [MainChatScreenActionHost] in a `SideEffect`, i.e. only after a successful composition, and
 *   every wrapper reads the host field **at invocation time**. A lambda that ran during composition
 *   could see a half-published bundle; an event handler cannot run before the frame is applied.
 * - The only state-dependent intent, `onGenerateImagePrompt`, now resolves the current LoRA
 *   snapshot from the owner (`viewModel.uiState.value.localImageLoras`) when the user triggers it,
 *   exactly like the rest of the business validation that already lives behind the owner. No UI
 *   state is captured, frozen, or copied into the action bundle.
 * - Availability/nullability shape does not change: the three nullable translation/token-measure
 *   capabilities are always the same non-null owner methods, so a stable bundle cannot hide or
 *   invent an action.
 *
 * Nothing here writes business state, starts work, or renders. The side effect only publishes the
 * latest already-created callbacks; it executes no business action.
 */
@Composable
internal fun rememberMainChatScreenActions(
    viewModel: MainViewModel,
    onExportChatSession: (String) -> Unit,
    onImportAssistantCardFile: () -> Unit,
    onImportKnowledgeDocument: (String) -> Unit,
    onImportLocalImageModel: () -> Unit,
    onImportWorldBookFile: (WorldBookImportScope) -> Unit,
    requestModelImport: () -> Unit,
    onTab: (AppTab) -> Unit,
    setAppMenuOpen: (Boolean) -> Unit,
    setStartModelsInRecommended: (Boolean) -> Unit,
    setStartSettingsInWebSearch: (Boolean) -> Unit,
): ChatScreenActions {
    val host = remember { MainChatScreenActionHost() }
    SideEffect {
        host.onExportChatSession = onExportChatSession
        host.onImportAssistantCardFile = onImportAssistantCardFile
        host.onImportKnowledgeDocument = onImportKnowledgeDocument
        host.onImportLocalImageModel = onImportLocalImageModel
        host.onImportWorldBookFile = onImportWorldBookFile
        host.requestModelImport = requestModelImport
        host.onTab = onTab
        host.setAppMenuOpen = setAppMenuOpen
        host.setStartModelsInRecommended = setStartModelsInRecommended
        host.setStartSettingsInWebSearch = setStartSettingsInWebSearch
    }
    return remember(viewModel, host) { mainChatScreenActions(viewModel, host) }
}

/**
 * The latest host callbacks, published after each successful composition.
 *
 * Holds callbacks only -- no screen state, no business owner, no ability to write state. The
 * wrappers in [mainChatScreenActions] read these fields when an intent fires, so a rebuilt caller
 * lambda never leaves a stale sink behind an action.
 */
internal class MainChatScreenActionHost {
    var onExportChatSession: (String) -> Unit = {}
    var onImportAssistantCardFile: () -> Unit = {}
    var onImportKnowledgeDocument: (String) -> Unit = {}
    var onImportLocalImageModel: () -> Unit = {}
    var onImportWorldBookFile: (WorldBookImportScope) -> Unit = {}
    var requestModelImport: () -> Unit = {}
    var onTab: (AppTab) -> Unit = {}
    var setAppMenuOpen: (Boolean) -> Unit = {}
    var setStartModelsInRecommended: (Boolean) -> Unit = {}
    var setStartSettingsInWebSearch: (Boolean) -> Unit = {}
}

/**
 * Builds the bundle once; every host-callback wrapper reads [host] at invocation time and every
 * owner intent is a direct `viewModel` reference. The one state-dependent intent resolves the
 * current owner snapshot when it runs (see the class comment above).
 */
internal fun mainChatScreenActions(
    viewModel: MainViewModel,
    host: MainChatScreenActionHost,
): ChatScreenActions = ChatScreenActions(
        conversation = ChatConversationActions(
            onClearHistory = viewModel::clearChatHistory,
            onDeleteConversation = viewModel::deleteChatSession,
            onDeleteLastTurn = viewModel::deleteLastConversationTurn,
            onDeleteMessage = viewModel::deleteMessageAt,
            onExportConversation = { sessionId -> host.onExportChatSession(sessionId) },
            onRenameConversation = viewModel::renameChatSession,
            onSelectConversation = viewModel::selectChatSession,
            onTogglePinConversation = viewModel::toggleChatSessionPinned,
            onTogglePinMessage = viewModel::toggleMessagePinned,
            onBranchMixedConversation = viewModel::branchMixedChatSession,
            onCreateConversationForAssistant = viewModel::createChatForAssistant,
            onRegenerate = viewModel::regenerateLastResponse,
            onRequestContextCompression = viewModel::requestContextCompression,
            onSetContextCompressionThreshold = viewModel::setContextCompressionThreshold,
            onSetContextSummaryModel = viewModel::setContextSummaryModel,
            onUndoContextSummary = viewModel::undoContextSummary,
            onSearchContextSummarySources = viewModel::contextSummaryEvidenceForQuery,
            onMessageIntent = viewModel::handleChatMessageIntent,
            onEditAndResend = viewModel::editAndResendMessage,
        ),
        generation = ChatGenerationActions(
            onInputChange = viewModel::onInputChange,
            onSend = viewModel::sendMessage,
            onSendImagePrompt = viewModel::sendChatImagePrompt,
            onStop = viewModel::stopGeneration,
            onGenerationParamsChange = viewModel::updateParams,
            onReasoningModeChange = viewModel::updateReasoningMode,
            onCloudReasoningModeLocked = viewModel::showCloudReasoningModeLocked,
            onModelBackendChange = { modelId, backendId ->
                    viewModel.updateModelBackendPreference(modelId, backendId)
                },
            onLoadModel = viewModel::selectChatModel,
            onUploadFile = viewModel::attachFile,
            onQuoteMessage = viewModel::setQuotedMessage,
            releaseGenerationImageGrantsIfCoordinatorIdle =
                    viewModel::releaseGenerationImageGrantsIfCoordinatorIdle,
        ),
        content = ChatContentActions(
            onDeleteAssistant = viewModel::deleteAssistant,
            onSaveAssistant = { draft: AssistantEditorDraft ->
                    viewModel.saveAssistantProfile(
                        id = draft.id,
                        name = draft.name,
                        avatar = draft.avatar,
                        tag = draft.tag,
                        systemPrompt = draft.systemPrompt,
                        defaultModelMode = draft.defaultModelMode,
                        defaultModelId = draft.defaultModelId?.removePrefix(MainViewModel.CLOUD_MODEL_CHOICE_PREFIX),
                        temperature = draft.temperature,
                        topP = draft.topP,
                        nPredict = draft.nPredict,
                        topK = draft.topK,
                        minP = draft.minP,
                        repeatPenalty = draft.repeatPenalty,
                        presencePenalty = draft.presencePenalty,
                        frequencyPenalty = draft.frequencyPenalty,
                        stopWords = draft.stopWords,
                        reasoningMode = draft.reasoningMode,
                        memoryEnabled = draft.memoryEnabled,
                        memorySummaryInterval = draft.memorySummaryInterval,
                        webSearchEnabled = draft.webSearchEnabled,
                        fileContextEnabled = draft.fileContextEnabled,
                        generationOverrideFields = draft.generationOverrideFields
                    )
                },
            onSelectAssistant = viewModel::selectAssistant,
            onImportAssistantCard = viewModel::importAssistantCard,
            onImportAssistantCardFile = { host.onImportAssistantCardFile() },
            onDeleteWorldBook = viewModel::deleteWorldBook,
            onSetWorldBookEnabled = viewModel::setWorldBookEnabled,
            onImportWorldBookFile = { scope -> host.onImportWorldBookFile(scope) },
            onCreateKnowledgeBase = { name -> viewModel.createKnowledgeBase(name) },
            onDeleteKnowledgeBase = viewModel::deleteKnowledgeBase,
            onImportKnowledgeDocument = { knowledgeBaseId -> host.onImportKnowledgeDocument(knowledgeBaseId) },
            onSetKnowledgeBaseSelected = viewModel::setKnowledgeBaseSelected,
            onDeleteAssistantMemory = viewModel::deleteAssistantMemory,
            onUpsertAssistantMemory = viewModel::upsertAssistantMemory,
            onChatBackgroundChange = { scope, appearance ->
                    viewModel.setChatAppearance(scope.toAppearanceScope(), appearance?.toAppearance())
                },
            onChatBackgroundImageSelected = { scope, uri ->
                    viewModel.importChatBackground(scope.toAppearanceScope(), uri)
                },
        ),
        image = ChatImageActions(
            onApproveChatImageRequest = viewModel::approveChatImageRequest,
            onRejectChatImageRequest = viewModel::rejectChatImageRequest,
            onRetryChatImageRequest = viewModel::retryChatImageGeneration,
            onCancelChatImageGeneration = viewModel::cancelChatImageGeneration,
            onCancelImageGeneration = viewModel::cancelImageGeneration,
            onChatImageLoraSelectionChange = { selections ->
                    viewModel.updateChatImageLoraSelection(
                        selections?.map { selection -> selection.id to selection.multiplier }
                    )
                },
            onConfirmImageTranslationDraft = viewModel::confirmChatImageTranslationDraft,
            onCancelImageTranslationDraft = viewModel::cancelChatImageTranslationDraft,
            onResolveImageActionDraft = viewModel::resolveChatImageActionDraft,
            onDeleteImageAsset = viewModel::deleteImageAsset,
            onDeleteImageAssets = viewModel::deleteImageAssets,
            onDeleteImageLora = viewModel::deleteLocalImageLora,
            onDeleteImageTextualInversion = viewModel::deleteLocalImageTextualInversion,
            onDeleteImageUpscaler = viewModel::deleteLocalImageUpscaler,
            onExportImageLibraryBackup = viewModel::exportImageLibraryBackup,
            onImportImageLibraryBackup = viewModel::importImageLibraryBackup,
            onCancelImageLibraryBackup = viewModel::cancelImageLibraryBackup,
            onImportImageLora = viewModel::importLocalImageLora,
            onImportImageModel = { host.onImportLocalImageModel() },
            onImportImageTextualInversion = viewModel::importLocalImageTextualInversion,
            onImportImageUpscaler = viewModel::importLocalImageUpscaler,
            onSelectImageModel = viewModel::selectImageGenerationModel,
            onSelectImageUpscaler = viewModel::selectLocalImageUpscaler,
            onSetImageAssetFavorite = viewModel::setImageAssetFavorite,
            onUpscaleImageAsset = viewModel::upscaleImageAsset,
            onCancelImageUpscale = viewModel::cancelImageUpscale,
            onRecreateImageAsset = viewModel::recreateImageAsset,
            onRetryImageGeneration = viewModel::retryImageGeneration,
            onUseImageAsset = viewModel::useImageAsset,
            onGenerateImagePrompt = { prompt, uiOptions ->
                    submitChatImageFromUi(
                        prompt = prompt,
                        uiOptions = uiOptions,
                        currentLocalLoras = { viewModel.uiState.value.localImageLoras },
                        onMissingLocalImageLora = viewModel::reportMissingLocalImageLoraSelection,
                        submit = viewModel::generateImageAsset
                    )
                },
            onContinueAssistantImageTurn = viewModel::continueAssistantImageTurn,
            onSetAssistantImageToolAutoApproval = viewModel::setAssistantImageToolAutoApproval,
            onDownloadOfficialViggleLora = viewModel::downloadOfficialViggleLora,
            onTranslateImagePrompt = viewModel::translateImagePromptPreview,
            onTranslateImageNegativePrompt = viewModel::translateImageNegativePromptPreview,
            onMeasureImagePromptTokens = viewModel::measureImagePromptTokens,
        ),
        shell = ChatShellActions(
            onAppMenuOpenChange = { open -> host.setAppMenuOpen(open) },
            onDismissStatusMessage = viewModel::clearStatusMessage,
            onOpenModels = { host.setStartModelsInRecommended(false); host.onTab(AppTab.MODELS) },
            onOpenPresets = { host.onTab(AppTab.PRESETS) },
            onOpenRecommendedModels = {
                    host.setStartModelsInRecommended(true)
                    host.onTab(AppTab.MODELS)
                },
            onOpenSettings = {
                    host.setStartSettingsInWebSearch(false)
                    host.onTab(AppTab.SETTINGS)
                },
            onOpenAgent = { host.onTab(AppTab.AGENT) },
            onOpenAgentWorkspace = { host.onTab(AppTab.AGENT_WORKSPACE) },
            onOpenDshWorkspace = { host.onTab(AppTab.DSH_WORKSPACE) },
            onOpenVideoWorkspace = { host.onTab(AppTab.VIDEO_WORKSPACE) },
            onOpenApi = { host.onTab(AppTab.API) },
            onOpenWebSearchSettings = {
                    host.setStartSettingsInWebSearch(true)
                    host.onTab(AppTab.SETTINGS)
                },
            onImportChatModel = { host.requestModelImport() },
            onBrowserTaskEvent = viewModel::onBrowserTaskEvent,
            onOpenBrowserTask = viewModel::startBrowserTask,
            onSelectWebSearchResearchMode = viewModel::selectWebSearchResearchModeForNextTurn,
            onToggleWebSearchForTurn = viewModel::toggleWebSearchForNextTurn,
            onDeleteFileAsset = viewModel::deleteFileAsset,
            onUseFileAsset = viewModel::useFileAsset,
            onDownloadOfflineTranslation = viewModel::downloadOfflinePromptTranslationBundle,
            onCancelOfflineTranslationDownload = viewModel::cancelOfflinePromptTranslationDownload,
        ),
    )

/**
 * Validates the currently selected LoRA ids against the owner's current catalog.
 *
 * Returns the prepared adapters in the exact order of [selections], or `null` when any selection is
 * no longer present. The old inline block did the same `mapNotNull` + size comparison; keeping it a
 * pure function is what lets the action test prove the latest-snapshot behavior without a
 * `MainViewModel`.
 */
internal fun resolvePreparedChatImageLoras(
    availableLoras: List<LocalImageLoraRecord>,
    selections: List<ImageGenerationUiLoraSelection>,
): List<LocalImagePreparedLora>? {
    val prepared = selections.mapNotNull { selection ->
        availableLoras
            .firstOrNull { adapter -> adapter.id == selection.id }
            ?.toPrepared(selection.multiplier)
    }
    return prepared.takeIf { it.size == selections.size }
}

/**
 * The body of the `onGenerateImagePrompt` intent.
 *
 * The old lambda captured the state of the frame that built it. Here the LoRA snapshot is read
 * through [currentLocalLoras] when the intent fires, i.e. from the real owner's latest committed
 * state; validation, failure reporting, argument mapping and the returned submission result are the
 * ones the inline lambda had.
 */
internal fun submitChatImageFromUi(
    prompt: String,
    uiOptions: ImageGenerationUiOptions,
    currentLocalLoras: () -> List<LocalImageLoraRecord>,
    onMissingLocalImageLora: () -> Unit,
    submit: (String, LocalImageInputDraft, LocalImageGenerationOptions) -> Boolean,
): Boolean {
    // The old inline block only read the catalog inside `mapNotNull`, i.e. never for an empty
    // selection list. Keep that: no selection means no owner-state read at all.
    val loras = if (uiOptions.loras.isEmpty()) {
        emptyList<LocalImagePreparedLora>()
    } else {
        resolvePreparedChatImageLoras(currentLocalLoras(), uiOptions.loras)
    }
    if (loras == null) {
        onMissingLocalImageLora()
        return false
    }
    return submit(
        prompt,
        LocalImageInputDraft(
            taskMode = LocalImageTaskMode.fromWireName(uiOptions.taskMode.wireName),
            inputImageReference = uiOptions.inputImageUri,
            maskImageReference = uiOptions.maskImageUri,
            controlImageReference = uiOptions.controlImageUri,
            strength = uiOptions.strength,
            controlStrength = uiOptions.controlStrength
        ),
        LocalImageGenerationOptions(
            negativePrompt = uiOptions.negativePrompt,
            width = uiOptions.width,
            height = uiOptions.height,
            steps = uiOptions.steps,
            seed = uiOptions.seed,
            cfgScale = uiOptions.cfgScale,
            sampleMethod = uiOptions.sampleMethod,
            clipSkip = uiOptions.clipSkip,
            batchCount = uiOptions.batchCount,
            loras = loras,
            vaeTiling = uiOptions.vaeTileSize?.let { tileSize ->
                LocalImageVaeTilingOptions(
                    tileSize = tileSize,
                    overlap = uiOptions.vaeTileOverlap ?: 0.5
                )
            },
            textualInversionIds = uiOptions.textualInversionIds,
            ultraFix = uiOptions.ultraFix?.let { request ->
                LocalImageUltraFixOptions(
                    targetWidth = request.targetWidth,
                    targetHeight = request.targetHeight,
                    strength = request.strength,
                    inversionSteps = request.inversionSteps,
                    refinementSteps = request.refinementSteps,
                    tileSize = request.tileSize,
                    overlap = request.overlap,
                )
            },
            preview = uiOptions.toLocalImagePreviewOptions()
        )
    )
}
