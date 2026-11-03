package com.muyuchat.feature.chat

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Deferred

@Composable
internal fun ChatAssistantRoleSubpage(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: ChatUiState,
    assistantMemories: List<AssistantMemoryUiItem>,
    onSaveAssistant: (AssistantEditorDraft) -> Boolean,
    onUpsertAssistantMemory: (assistantId: String, id: String?, scope: String, content: String) -> Deferred<Boolean>,
    onDeleteAssistantMemory: (assistantId: String, id: String) -> Unit,
    onSelectAssistant: (String) -> Unit,
    onDeleteAssistant: (String) -> Unit,
    onImportAssistantCard: (String) -> Unit,
    onImportAssistantCardFile: () -> Unit,
    onImportWorldBookFile: (WorldBookImportScope) -> Unit,
    onDeleteWorldBook: (String) -> Unit,
    onSetWorldBookEnabled: (String, Boolean) -> Unit,
    onCreateKnowledgeBase: (String) -> Unit,
    onImportKnowledgeDocument: (String) -> Unit,
    onSetKnowledgeBaseSelected: (String, Boolean) -> Unit,
    onDeleteKnowledgeBase: (String) -> Unit
) {
    SmoothRightToLeftPage(
        visible = visible,
        onDismiss = onDismiss
    ) { pageModifier, closePage ->
        AssistantRoleScreen(
            assistants = state.assistants,
            worldBooks = state.worldBooks,
            knowledgeBases = state.knowledgeBases,
            statusMessage = state.statusMessage,
            selectedAssistantId = state.selectedAssistantId,
            hasActiveConversation = state.activeConversationId != null,
            selectedModelName = state.selectedModelName,
            selectedModelId = state.selectedModelId,
            selectedModelIsCloud = state.selectedModelIsCloud,
            modelGenerationPreset = state.modelGenerationPreset,
            modelChoices = state.localModels,
            onSaveAssistant = onSaveAssistant,
            assistantMemories = assistantMemories,
            onUpsertAssistantMemory = onUpsertAssistantMemory,
            onDeleteAssistantMemory = onDeleteAssistantMemory,
            onSelectAssistant = onSelectAssistant,
            onDeleteAssistant = onDeleteAssistant,
            onImportAssistantCard = onImportAssistantCard,
            onImportAssistantCardFile = onImportAssistantCardFile,
            onImportWorldBookFile = onImportWorldBookFile,
            onDeleteWorldBook = onDeleteWorldBook,
            onSetWorldBookEnabled = onSetWorldBookEnabled,
            onCreateKnowledgeBase = onCreateKnowledgeBase,
            onImportKnowledgeDocument = onImportKnowledgeDocument,
            onSetKnowledgeBaseSelected = onSetKnowledgeBaseSelected,
            onDeleteKnowledgeBase = onDeleteKnowledgeBase,
            onBack = closePage,
            modifier = pageModifier
        )
    }
}

@Composable
internal fun ChatGenerationImageEditorSubpage(
    request: GenerationImageEditorRequest?,
    onDismiss: () -> Unit,
    onCropConfirmed: (role: String, ownedUri: String) -> Unit,
    onMaskConfirmed: (GenerationInpaintOwnedUris) -> Unit
) {
    request?.let { editorRequest ->
        SmoothRightToLeftPage(
            visible = true,
            onDismiss = onDismiss
        ) { pageModifier, closePage ->
            GenerationImageEditorScreen(
                request = editorRequest,
                onBack = closePage,
                onCropConfirmed = { role, ownedUri ->
                    onCropConfirmed(role, ownedUri)
                    closePage()
                },
                onMaskConfirmed = { ownedUris ->
                    onMaskConfirmed(ownedUris)
                    closePage()
                },
                modifier = pageModifier
            )
        }
    }
}

@Composable
internal fun ChatFileLibrarySubpage(
    visible: Boolean,
    onDismiss: () -> Unit,
    files: List<FileAssetUiItem>,
    onUseFileAsset: (String) -> Unit,
    onDeleteFileAsset: (String) -> Unit
) {
    SmoothRightToLeftPage(
        visible = visible,
        onDismiss = onDismiss
    ) { pageModifier, closePage ->
        FileLibraryPage(
            files = files,
            onInsert = { id ->
                onUseFileAsset(id)
                closePage()
            },
            onDelete = onDeleteFileAsset,
            onBack = closePage,
            modifier = pageModifier
        )
    }
}

@Composable
internal fun ChatBackgroundSettingsSubpage(
    visible: Boolean,
    onDismiss: () -> Unit,
    chatBackground: ChatBackgroundState,
    globalChatBackground: ChatBackgroundState,
    assistantChatBackground: ChatBackgroundState?,
    sessionChatBackground: ChatBackgroundState?,
    hasActiveChatSession: Boolean,
    chatBackgroundImporting: Boolean,
    onChooseImage: (ChatBackgroundScope) -> Unit,
    onChatBackgroundChange: (ChatBackgroundScope, ChatBackgroundState?) -> Unit
) {
    SmoothRightToLeftPage(
        visible = visible,
        onDismiss = onDismiss
    ) { pageModifier, closePage ->
        ChatBackgroundSettingsPage(
            modifier = pageModifier,
            effectiveState = chatBackground,
            globalState = globalChatBackground,
            assistantState = assistantChatBackground,
            sessionState = sessionChatBackground,
            hasActiveSession = hasActiveChatSession,
            importing = chatBackgroundImporting,
            onDismiss = closePage,
            onChooseImage = onChooseImage,
            onChange = onChatBackgroundChange,
            onReset = { scope -> onChatBackgroundChange(scope, null) }
        )
    }
}

@Composable
internal fun ChatPredictiveAppMenuSubpage(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: ChatUiState,
    onOpenQuickStart: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    onOpenAssistants: () -> Unit,
    onClearHistory: () -> Unit,
    onRequestContextCompression: () -> Unit,
    onUndoContextSummary: () -> Unit,
    onSearchContextSummarySources: (String) -> List<ContextSummaryEvidenceUiItem>,
    onSetContextCompressionThreshold: (Int) -> Unit,
    onSetContextSummaryModel: (String) -> Unit
) {
    PredictiveAppMenuPage(
        visible = visible,
        onDismiss = onDismiss
    ) { menuModifier, closeMenu ->
        McaAppMenuPage(
            onOpenQuickStart = { closeMenu(); onOpenQuickStart() },
            state = state,
            onClose = closeMenu,
            onOpenModels = onOpenModels,
            onOpenPresets = onOpenPresets,
            onOpenAgent = onOpenAgent,
            onOpenApi = onOpenApi,
            onOpenSettings = onOpenSettings,
            onRequestContextCompression = onRequestContextCompression,
            onUndoContextSummary = onUndoContextSummary,
            onSearchContextSummarySources = onSearchContextSummarySources,
            onSetContextCompressionThreshold = onSetContextCompressionThreshold,
            onSetContextSummaryModel = onSetContextSummaryModel,
            onOpenImages = {
                closeMenu()
                onOpenImages()
            },
            onOpenBackgroundSettings = onOpenBackgroundSettings,
            onOpenAssistants = {
                closeMenu()
                onOpenAssistants()
            },
            onClearHistory = onClearHistory,
            modifier = menuModifier
        )
    }
}
