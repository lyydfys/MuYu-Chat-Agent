package com.muyuchat.feature.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

// P3-b：把 ChatScreen 的 96 个回调参数按职责分成 5 个动作组（方案 §五.2）。
// 逐项沿用 ChatScreen 既有参数的原类型与默认值；未做任何行为改动。
// 可见性与 ChatUiState 一致（public），因为 ChatScreen 是 public 函数。

@Immutable
data class ChatConversationActions(
    val onClearHistory: () -> Unit,
    val onDeleteConversation: (String) -> Unit,
    val onDeleteLastTurn: () -> Unit = {},
    val onDeleteMessage: (Int) -> Unit,
    val onExportConversation: (String) -> Unit,
    val onRenameConversation: (String, String) -> Unit,
    val onSelectConversation: (String) -> Unit,
    val onTogglePinConversation: (String) -> Unit,
    val onTogglePinMessage: (String) -> Unit = {},
    val onBranchMixedConversation: (String, String) -> Unit = { _, _ -> },
    val onCreateConversationForAssistant: (String) -> Unit,
    val onRegenerate: () -> Unit,
    val onRequestContextCompression: () -> Unit = {},
    val onSetContextCompressionThreshold: (Int) -> Unit = {},
    val onSetContextSummaryModel: (String) -> Unit = {},
    val onUndoContextSummary: () -> Unit = {},
    val onSearchContextSummarySources: (String) -> List<ContextSummaryEvidenceUiItem> = { emptyList() },
    /** Identity-aware replacement for delayed message actions; legacy callbacks remain above. */
    val onMessageIntent: ((ChatMessageIntent) -> Unit)? = null,
)

/** 生成与输入：发送、停止、重试、参数与推理模式、模型后端、附件。 */
@Immutable
data class ChatGenerationActions(
    val onInputChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onSendImagePrompt: () -> Unit,
    val onStop: () -> Unit,
    val onGenerationParamsChange: (GenerationParams) -> Unit = {},
    val onReasoningModeChange: (ReasoningMode) -> Unit,
    val onCloudReasoningModeLocked: () -> Unit = {},
    val onModelBackendChange: (String, String) -> Unit = { _, _ -> },
    val onLoadModel: (String) -> Unit = {},
    val onUploadFile: (String) -> Unit,
    val releaseGenerationImageGrantsIfCoordinatorIdle: ((() -> Unit) -> Boolean),
)

/** 内容与背景：助手、世界书、知识库、助手记忆、聊天背景。 */
@Immutable
data class ChatContentActions(
    val onDeleteAssistant: (String) -> Unit = {},
    val onSaveAssistant: (AssistantEditorDraft) -> Boolean = { false },
    val onSelectAssistant: (String) -> Unit = {},
    val onImportAssistantCard: (String) -> Unit = {},
    val onImportAssistantCardFile: () -> Unit = {},
    val onDeleteWorldBook: (String) -> Unit = {},
    val onSetWorldBookEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val onImportWorldBookFile: (WorldBookImportScope) -> Unit = {},
    val onCreateKnowledgeBase: (String) -> Unit = {},
    val onDeleteKnowledgeBase: (String) -> Unit = {},
    val onImportKnowledgeDocument: (String) -> Unit = {},
    val onSetKnowledgeBaseSelected: (String, Boolean) -> Unit = { _, _ -> },
    val onDeleteAssistantMemory: (assistantId: String, id: String) -> Unit = { _, _ -> },
    val onUpsertAssistantMemory: (assistantId: String, id: String?, scope: String, content: String) -> Deferred<Boolean> =
        { _, _, _, _ -> CompletableDeferred(false) },
    val onChatBackgroundChange: (ChatBackgroundScope, ChatBackgroundState?) -> Unit = { _, _ -> },
    val onChatBackgroundImageSelected: (ChatBackgroundScope, String) -> Unit = { _, _ -> },
)

/** 生图与图库：生图请求审批/重试、模型与 LoRA/反演/放大器管理、资产、放大、翻译草稿、token 计量。 */
@Immutable
data class ChatImageActions(
    val onApproveChatImageRequest: (String) -> Unit = {},
    val onRejectChatImageRequest: (String) -> Unit = {},
    val onRetryChatImageRequest: (String) -> Unit = {},
    val onCancelChatImageGeneration: (String) -> Unit = {},
    val onCancelImageGeneration: () -> Unit = {},
    val onChatImageLoraSelectionChange: (List<ImageGenerationUiLoraSelection>?) -> Unit = {},
    val onConfirmImageTranslationDraft: (String, String, String?) -> Unit = { _, _, _ -> },
    val onCancelImageTranslationDraft: (String) -> Unit = {},
    val onResolveImageActionDraft: (String, Boolean, String) -> Unit = { _, _, _ -> },
    val onDeleteImageAsset: (String) -> Unit = {},
    val onDeleteImageAssets: (List<String>) -> Unit = {},
    val onDeleteImageLora: (String) -> Unit = {},
    val onDeleteImageTextualInversion: (String) -> Unit = {},
    val onDeleteImageUpscaler: (String) -> Unit = {},
    val onExportImageLibraryBackup: (String, Boolean) -> Unit = { _, _ -> },
    val onImportImageLibraryBackup: (String) -> Unit = {},
    val onCancelImageLibraryBackup: () -> Unit = {},
    val onImportImageLora: (String) -> Unit = {},
    val onImportImageModel: () -> Unit,
    val onImportImageTextualInversion: (String, String) -> Unit = { _, _ -> },
    val onImportImageUpscaler: (String) -> Unit = {},
    val onSelectImageModel: (String) -> Unit = {},
    val onSelectImageUpscaler: (String) -> Unit = {},
    val onSetImageAssetFavorite: (String, Boolean) -> Unit = { _, _ -> },
    val onUpscaleImageAsset: (String, Int) -> Unit = { _, _ -> },
    val onCancelImageUpscale: () -> Unit = {},
    val onRecreateImageAsset: (String) -> Unit = {},
    val onRetryImageGeneration: (String) -> Unit = {},
    val onUseImageAsset: (String) -> Unit = {},
    val onGenerateImagePrompt: (String, ImageGenerationUiOptions) -> Boolean = { _, _ -> false },
    val onContinueAssistantImageTurn: (String) -> Unit = {},
    val onSetAssistantImageToolAutoApproval: (Boolean) -> Unit = {},
    val onDownloadOfficialViggleLora: () -> Unit = {},
    val onTranslateImagePrompt: (suspend (String) -> String)? = null,
    val onTranslateImageNegativePrompt: (suspend (String) -> String)? = null,
    val onMeasureImagePromptTokens: (suspend ( modelId: String, prompt: String, ) -> ImagePromptTokenMeasurement?)? = null,
)

/** 应用外壳与导航：菜单、状态提示、页面跳转、浏览器任务、网页搜索、文件资产、离线翻译。 */
@Immutable
data class ChatShellActions(
    val onAppMenuOpenChange: (Boolean) -> Unit = {},
    val onDismissStatusMessage: () -> Unit = {},
    val onOpenModels: () -> Unit,
    val onOpenRecommendedModels: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenAgent: () -> Unit,
    /** Opens the independent Agent workspace; [onOpenAgent] keeps opening smart tuning. */
    val onOpenAgentWorkspace: () -> Unit = {},
    /** Opens the independent official-DSH workspace; it is a separate mode, not smart tuning. */
    val onOpenDshWorkspace: () -> Unit = {},
    /** Opens the native video workspace below the DSH entry; video runs outlive the page. */
    val onOpenVideoWorkspace: () -> Unit = {},
    val onOpenApi: () -> Unit,
    val onOpenWebSearchSettings: () -> Unit = {},
    val onImportChatModel: () -> Unit,
    val onBrowserTaskEvent: (BrowserTaskEvent) -> Unit = {},
    val onOpenBrowserTask: (BrowserTaskLaunch) -> Unit = {},
    val onSelectWebSearchResearchMode: (String) -> Unit = {},
    val onToggleWebSearchForTurn: () -> Unit = {},
    val onDeleteFileAsset: (String) -> Unit = {},
    val onUseFileAsset: (String) -> Unit = {},
    val onDownloadOfflineTranslation: () -> Unit = {},
    val onCancelOfflineTranslationDownload: () -> Unit = {},
)

/** ChatScreen 的全部用户意图，按职责分组（方案 §五.2 的动作组）。 */
@Immutable
data class ChatScreenActions(
    val conversation: ChatConversationActions,
    val generation: ChatGenerationActions,
    val content: ChatContentActions,
    val image: ChatImageActions,
    val shell: ChatShellActions,
)
