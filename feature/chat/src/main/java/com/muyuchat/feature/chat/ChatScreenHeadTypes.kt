package com.muyuchat.feature.chat

import androidx.compose.runtime.Immutable
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress

// P9/B1：从 ChatScreen.kt 等值移出的头部类型与常量簇（状态/背景/生图条目模型与 prefs 常量）。
// 原样逐字迁移，未做行为改动；同包解析，调用点无需变更。
internal const val CLOUD_REASONING_LOCKED_TIP = "云端思考由模型服务商控制，MCA 已默认启用，暂不支持切换。"
internal const val CHAT_BACKGROUND_MAX_DECODE_EDGE = 2048

@Immutable
data class ChatUiState(
    val modelLoadMessage: String? = null,
    val modelReadinessLabel: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val history: List<ChatHistoryItem> = emptyList(),
    val localModels: List<ChatModelChoice> = emptyList(),
    val imageModels: List<ChatModelChoice> = emptyList(),
    /** 本地生图模型行仍在准备（capabilities 未就绪）；选择器应显示加载态而非“暂无”。 */
    val imageModelsLoading: Boolean = false,
    val existingImageModelIds: Set<String> = emptySet(),
    val assistants: List<AssistantUiItem> = emptyList(),
    val selectedAssistantId: String = "default",
    val worldBooks: List<WorldBookUiItem> = emptyList(),
    val knowledgeBases: List<KnowledgeBaseUiItem> = emptyList(),
    val statusMessage: String? = null,
    val images: List<ImageAssetUiItem> = emptyList(),
    val generationHistoryInputUris: Set<String> = emptySet(),
    val imageLibraryBackup: ImageLibraryBackupUiState = ImageLibraryBackupUiState(),
    val imageLoras: List<ImageLoraUiItem> = emptyList(),
    val imageLoraImporting: Boolean = false,
    val imageLoraMessage: String = "",
    val imageTextualInversions: List<ImageTextualInversionUiItem> = emptyList(),
    val imageTextualInversionImporting: Boolean = false,
    val imageTextualInversionMessage: String = "",
    val imageUpscalers: List<ImageUpscalerUiItem> = emptyList(),
    val selectedImageUpscalerId: String? = null,
    val imageUpscalerImporting: Boolean = false,
    val imageUpscalerMessage: String = "",
    val imageUpscaleJob: ImageUpscaleUiJob? = null,
    val deferGenerationImageGrantRelease: Boolean = false,
    val files: List<FileAssetUiItem> = emptyList(),
    val imageJobs: List<ImageGenerationUiJob> = emptyList(),
    val activeConversationId: String? = null,
    val conversationReadOnly: Boolean = false,
    val input: String = "",
    /** Request-scoped quote shown above the composer; cleared when the send is accepted. */
    val quotedMessage: ChatQuotedMessage? = null,
    val isGenerating: Boolean = false,
    val generationPhase: GenerationPhase? = null,
    val generationTokenProgress: TokenProgress? = null,
    val generationPersistProgress: PersistProgress? = null,
    val generationStats: RuntimeStats? = null,
    val promptContextUsage: PromptContextUsage? = null,
    val contextCompressionThresholdPercent: Int = 80,
    val contextCompressionPending: Boolean = false,
    val contextSummaryModelSelection: String = "current",
    val contextSummaryModels: List<ContextSummaryModelOption> = emptyList(),
    val contextSummaryHistory: List<ContextSummaryUiItem> = emptyList(),
    val canUndoContextSummary: Boolean = false,
    val pendingImageTranslationDraft: ChatImageTranslationDraftUi? = null,
    val pendingImageActionDraft: ChatImageActionDraftUi? = null,
    val browserTask: BrowserTask? = null,
    val selectedModelId: String? = null,
    val selectedModelName: String? = null,
    val selectedModelIsCloud: Boolean = false,
    val selectedModelRuntimeLabel: String? = null,
    val selectedImageModelId: String? = null,
    val selectedImageModelName: String? = null,
    val selectedImageModelIsCloud: Boolean = false,
    val assistantImageToolAvailable: Boolean = false,
    val assistantImageToolUnavailableReason: String = "角色生图工具仅支持已配置的 OpenAI Responses 云端聊天。",
    val assistantImageToolAutoApproval: Boolean = false,
    val assistantImageToolCanChange: Boolean = false,
    val stats: RuntimeStats = RuntimeStats(),
    val apiEnabled: Boolean = false,
    val restEnabled: Boolean = false,
    /** Sampling/reasoning values for the currently selected model. */
    val generationParams: GenerationParams = GenerationParams(),
    /** The selected model's stored preset before any role-card overrides. */
    val modelGenerationPreset: GenerationParams = GenerationParams(),
    val reasoningMode: ReasoningMode = ReasoningMode.OFF,
    val webSearchEnabled: Boolean = false,
    val webSearchConfigured: Boolean = false,
    val webSearchEnabledForTurn: Boolean = false,
    val webSearchStatusMessage: String? = null,
    val webSearchTurnModeLabel: String = "",
    val webSearchResearchMode: String = "AUTO",
    val webSearchResearchModeLabel: String = "",
    val webSearchResearchOverridden: Boolean = false,
    val webSearchProviderLabel: String = "",
    val visionCapabilityLabel: String = "识图未就绪",
    val visionCapabilityDetail: String = "请加载云端多模态模型，或绑定本地 mmproj 视觉投影器。",
    val visionCapabilityReady: Boolean = false
)

/** Visual preferences for the conversation canvas. URI is copied to app-private storage by the host. */
@Immutable
data class ChatBackgroundState(
    val imageUri: String? = null,
    val scrimAlpha: Float = 0.22f,
    val blurRadius: Float = 0f,
    val scaleMode: ChatBackgroundScaleMode = ChatBackgroundScaleMode.CROP
)

enum class ChatBackgroundScaleMode { CROP, FIT, CENTER }

enum class ChatBackgroundScope { GLOBAL, ASSISTANT, SESSION }

@Immutable
data class ImageLibraryBackupUiState(
    val running: Boolean = false,
    val importing: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val failed: Boolean = false
)

@Immutable
data class ImageLoraUiItem(
    val id: String,
    val name: String,
    val sizeText: String,
    val sha256: String,
    val inUse: Boolean = false,
    val isQwenImage21ViggleV021: Boolean = false
)

@Immutable
data class ImageTextualInversionUiItem(
    val id: String,
    val name: String,
    val trigger: String,
    val format: String = "safetensors",
    val sizeText: String,
    val sha256: String,
    val inUse: Boolean = false,
    val compatibleWithSelectedModel: Boolean = true,
)

// P9/S30: private→internal（prefs 名/键前缀保留在本文件以维持 SharedPreferencesSurfaceGoldenTest 同文件解析，跨文件被 ChatImageGenerationSupport 引用）
internal const val IMAGE_GENERATION_UI_PARAMETER_PREFS = "mca_image_generation_ui_parameters"
// P9/S30: private→internal（prefs 名/键前缀保留在本文件以维持 SharedPreferencesSurfaceGoldenTest 同文件解析，跨文件被 ChatImageGenerationSupport 引用）
internal const val IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX = "model:"
@Immutable
data class ChatModelChoice(
    val id: String,
    val displayName: String,
    val quant: String? = null,
    val sizeBytes: Long = 0L,
    val loaded: Boolean = false,
    val subtitle: String = "",
    val cloud: Boolean = false,
    val supportedImageTaskModes: Set<ImageGenerationUiTaskMode> =
        setOf(ImageGenerationUiTaskMode.TEXT_TO_IMAGE),
    val supportsImageNegativePrompt: Boolean = false,
    val supportsImageClipSkip: Boolean = false,
    val supportsImageVaeTiling: Boolean = false,
    val supportsImageTextualInversion: Boolean = false,
    val supportedImageTextualInversionFormats: Set<String> =
        IMAGE_TEXTUAL_INVERSION_ALL_FORMATS,
    val supportsImageUltraFix: Boolean = false,
    val supportsImageLora: Boolean = false,
    val isQwenImage21Gguf: Boolean = false,
    val maxImageBatchCount: Int = 1,
    val imageDefaultWidth: Int = 512,
    val imageDefaultHeight: Int = 512,
    val imageDefaultVaeTileSize: Int =
        if (imageDefaultWidth >= 1024 && imageDefaultHeight >= 1024) 1024 else 512,
    val imageDefaultVaeTileOverlap: Double = 0.5,
    val imageDefaultSteps: Int = 20,
    val imageMinSteps: Int = 1,
    val imageMaxSteps: Int = 1_000,
    val imageDefaultCfgScale: Double = 7.0,
    val imageDefaultSeed: Int = 42,
    val imageDefaultSampler: String = "euler",
    val imageMinWidth: Int = 512,
    val imageMaxWidth: Int = 512,
    val imageMinHeight: Int = 512,
    val imageMaxHeight: Int = 512,
    val imageWidthMultiple: Int = 8,
    val imageHeightMultiple: Int = 8,
    val imageUltraFixMinWidth: Int = if (supportsImageUltraFix) imageMinWidth else 0,
    val imageUltraFixMaxWidth: Int = if (supportsImageUltraFix) imageMaxWidth else 0,
    val imageUltraFixMinHeight: Int = if (supportsImageUltraFix) imageMinHeight else 0,
    val imageUltraFixMaxHeight: Int = if (supportsImageUltraFix) imageMaxHeight else 0,
    val imageUltraFixWidthMultiple: Int = if (supportsImageUltraFix) imageWidthMultiple else 0,
    val imageUltraFixHeightMultiple: Int = if (supportsImageUltraFix) imageHeightMultiple else 0,
    /** Zero means the selected runtime accepts a user-selectable topology-aligned tile. */
    val imageUltraFixRequiredTileSize: Int = 0,
    val imageSupportedSamplers: List<String> = listOf("euler"),
    val imageImg2ImgSupportedSamplers: List<String> = imageSupportedSamplers,
    val imagePreviewMode: ImageGenerationUiPreviewMode? = null,
    val imageDefaultPreviewInterval: Int = 1,
    val supportsImageLivePreview: Boolean = imagePreviewMode != null,
    /** Per-model local execution transports exposed by the chat selector. */
    val backendOptions: List<ChatBackendOption> = emptyList(),
    val selectedBackendId: String? = null,
    /** Last persisted positive llama.cpp GPU layer count for custom mode. */
    val customGpuLayers: Int? = null
) {
    init {
        require(supportsImageLivePreview == (imagePreviewMode != null)) {
            "Image live-preview support must match its concrete preview mode."
        }
        require(imageMinSteps > 0 && imageMaxSteps >= imageMinSteps) {
            "Image step bounds must form a positive range."
        }
        require(imageDefaultSteps in imageMinSteps..imageMaxSteps) {
            "Default image steps must be inside the supported range."
        }
        require(imageDefaultVaeTileSize in 64..4_096 && imageDefaultVaeTileSize % 8 == 0 &&
            imageDefaultVaeTileOverlap.isFinite() && imageDefaultVaeTileOverlap in 0.0..0.5
        ) { "Default VAE tiling controls must satisfy the native product contract." }
        if (supportsImageUltraFix) {
            require(imageUltraFixMinWidth in 64..imageUltraFixMaxWidth &&
                imageUltraFixMinHeight in 64..imageUltraFixMaxHeight &&
                imageUltraFixMaxWidth <= 8_192 && imageUltraFixMaxHeight <= 8_192 &&
                imageUltraFixWidthMultiple > 0 && imageUltraFixHeightMultiple > 0 &&
                (imageUltraFixRequiredTileSize == 0 ||
                    (imageUltraFixRequiredTileSize in imageUltraFixMinWidth..imageUltraFixMaxWidth &&
                        imageUltraFixRequiredTileSize in imageUltraFixMinHeight..imageUltraFixMaxHeight &&
                        imageUltraFixRequiredTileSize % imageUltraFixWidthMultiple == 0 &&
                        imageUltraFixRequiredTileSize % imageUltraFixHeightMultiple == 0))
            ) { "UltraFix UI dimensions must expose a bounded executable range." }
        } else {
            require(imageUltraFixRequiredTileSize == 0) {
                "A model without UltraFix support cannot publish a fixed UltraFix graph tile."
            }
        }
    }
}

@Immutable
data class ChatHistoryItem(
    val id: String,
    val title: String,
    val displayName: String = title,
    val summary: String = "",
    val assistantId: String? = null,
    val updatedAtText: String,
    val updatedAtMillis: Long,
    val messageCount: Int,
    val pinned: Boolean,
    val selected: Boolean,
    val mixedAssistantHistory: Boolean = false
)
