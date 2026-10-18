package com.muyuchat.mca

import androidx.compose.runtime.Composable
import com.muyuchat.mca.presentation.toAppearance
import com.muyuchat.mca.presentation.toAppearanceScope
import com.muyuchat.mca.presentation.toLocalImagePreviewOptions
import androidx.compose.runtime.saveable.rememberSaveable
import com.muyuchat.feature.chat.AssistantEditorDraft
import com.muyuchat.feature.chat.ChatContentActions
import com.muyuchat.feature.chat.ChatConversationActions
import com.muyuchat.feature.chat.ChatGenerationActions
import com.muyuchat.feature.chat.ChatImageActions
import com.muyuchat.feature.chat.ChatScreenActions
import com.muyuchat.feature.chat.ChatShellActions
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
 * It exists so the chat page has one named place to be wired from, which is the seam the chat
 * feature's own view model will attach to; until then the app remains the single writer.
 */
@Composable
internal fun mainChatScreenActions(
    viewModel: MainViewModel,
    state: MainUiState,
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
): ChatScreenActions = ChatScreenActions(
        conversation = ChatConversationActions(
            onClearHistory = viewModel::clearChatHistory,
            onDeleteConversation = viewModel::deleteChatSession,
            onDeleteLastTurn = viewModel::deleteLastConversationTurn,
            onDeleteMessage = viewModel::deleteMessageAt,
            onExportConversation = onExportChatSession,
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
                        fileContextEnabled = draft.fileContextEnabled
                    )
                },
            onSelectAssistant = viewModel::selectAssistant,
            onImportAssistantCard = viewModel::importAssistantCard,
            onImportAssistantCardFile = onImportAssistantCardFile,
            onDeleteWorldBook = viewModel::deleteWorldBook,
            onImportWorldBookFile = onImportWorldBookFile,
            onCreateKnowledgeBase = { name -> viewModel.createKnowledgeBase(name) },
            onDeleteKnowledgeBase = viewModel::deleteKnowledgeBase,
            onImportKnowledgeDocument = onImportKnowledgeDocument,
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
            onImportImageModel = onImportLocalImageModel,
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
            onGenerateImagePrompt = generateImage@{ prompt, uiOptions ->
                    val loras = uiOptions.loras.mapNotNull { selection ->
                        state.localImageLoras
                            .firstOrNull { adapter -> adapter.id == selection.id }
                            ?.toPrepared(selection.multiplier)
                    }
                    if (loras.size != uiOptions.loras.size) {
                        viewModel.reportMissingLocalImageLoraSelection()
                        return@generateImage false
                    }
                    viewModel.generateImageAsset(
                        prompt = prompt,
                        inputDraft = LocalImageInputDraft(
                            taskMode = LocalImageTaskMode.fromWireName(uiOptions.taskMode.wireName),
                            inputImageReference = uiOptions.inputImageUri,
                            maskImageReference = uiOptions.maskImageUri,
                            controlImageReference = uiOptions.controlImageUri,
                            strength = uiOptions.strength,
                            controlStrength = uiOptions.controlStrength
                        ),
                        options = LocalImageGenerationOptions(
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
                },
            onContinueAssistantImageTurn = viewModel::continueAssistantImageTurn,
            onSetAssistantImageToolAutoApproval = viewModel::setAssistantImageToolAutoApproval,
            onDownloadOfficialViggleLora = viewModel::downloadOfficialViggleLora,
            onTranslateImagePrompt = viewModel::translateImagePromptPreview,
            onTranslateImageNegativePrompt = viewModel::translateImageNegativePromptPreview,
            onMeasureImagePromptTokens = viewModel::measureImagePromptTokens,
        ),
        shell = ChatShellActions(
            onAppMenuOpenChange = setAppMenuOpen,
            onDismissStatusMessage = viewModel::clearStatusMessage,
            onOpenModels = { setStartModelsInRecommended(false); onTab(AppTab.MODELS) },
            onOpenRecommendedModels = {
                    setStartModelsInRecommended(true)
                    onTab(AppTab.MODELS)
                },
            onOpenSettings = {
                    setStartSettingsInWebSearch(false)
                    onTab(AppTab.SETTINGS)
                },
            onOpenAgent = { onTab(AppTab.AGENT) },
            onOpenApi = { onTab(AppTab.API) },
            onOpenWebSearchSettings = {
                    setStartSettingsInWebSearch(true)
                    onTab(AppTab.SETTINGS)
                },
            onImportChatModel = requestModelImport,
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
