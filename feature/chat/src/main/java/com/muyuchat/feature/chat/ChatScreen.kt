package com.muyuchat.feature.chat

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.visionDeduplicationKey
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.PromptMessageRetention
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import com.muyuchat.core.designsystem.SystemBackMotionHandler
import com.muyuchat.core.engine.isAsciiTriggerWordCharacter

internal const val CLOUD_REASONING_LOCKED_TIP = "云端思考由模型服务商控制，MCA 已默认启用，暂不支持切换。"
internal const val CHAT_BACKGROUND_MAX_DECODE_EDGE = 2048

data class ChatUiState(
    val modelLoadMessage: String? = null,
    val modelReadinessLabel: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val history: List<ChatHistoryItem> = emptyList(),
    val localModels: List<ChatModelChoice> = emptyList(),
    val imageModels: List<ChatModelChoice> = emptyList(),
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
data class ChatBackgroundState(
    val imageUri: String? = null,
    val scrimAlpha: Float = 0.22f,
    val blurRadius: Float = 0f,
    val scaleMode: ChatBackgroundScaleMode = ChatBackgroundScaleMode.CROP
)

enum class ChatBackgroundScaleMode { CROP, FIT, CENTER }

enum class ChatBackgroundScope { GLOBAL, ASSISTANT, SESSION }

data class ImageLibraryBackupUiState(
    val running: Boolean = false,
    val importing: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val failed: Boolean = false
)

data class ImageLoraUiItem(
    val id: String,
    val name: String,
    val sizeText: String,
    val sha256: String,
    val inUse: Boolean = false,
    val isQwenImage21ViggleV021: Boolean = false
)

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
    var imagePrompt by rememberSaveable { mutableStateOf("") }
    var imageValidationError by rememberSaveable { mutableStateOf<String?>(null) }
    var imageTaskModeName by rememberSaveable {
        mutableStateOf(ImageGenerationUiTaskMode.TEXT_TO_IMAGE.name)
    }
    var imageInputUri by rememberSaveable { mutableStateOf<String?>(null) }
    var imageMaskUri by rememberSaveable { mutableStateOf<String?>(null) }
    var imageControlUri by rememberSaveable { mutableStateOf<String?>(null) }
    var imageInputDimensionsUri by rememberSaveable { mutableStateOf<String?>(null) }
    var imageInputSourceWidth by rememberSaveable { mutableStateOf(0) }
    var imageInputSourceHeight by rememberSaveable { mutableStateOf(0) }
    var imageInputDimensionsProbing by remember { mutableStateOf(false) }
    var imageInputDimensionsProbeFailed by remember { mutableStateOf(false) }
    var imageStrengthText by rememberSaveable { mutableStateOf("0.75") }
    var imageControlStrengthText by rememberSaveable { mutableStateOf("1.0") }
    var imageNegativePrompt by rememberSaveable { mutableStateOf("") }
    var imageDisableModelNegativePrompt by rememberSaveable { mutableStateOf(false) }
    var imageClipSkipText by rememberSaveable { mutableStateOf("") }
    var imageVaeTilingEnabled by rememberSaveable { mutableStateOf(false) }
    var imageLivePreviewEnabled by rememberSaveable { mutableStateOf(true) }
    var imageLivePreviewInterval by rememberSaveable { mutableStateOf(1) }
    var imageLivePreviewIntervalExplicit by rememberSaveable { mutableStateOf(false) }
    var imageBatchCount by rememberSaveable { mutableStateOf(1) }
    var imageWidthText by rememberSaveable { mutableStateOf("512") }
    var imageHeightText by rememberSaveable { mutableStateOf("512") }
    // imageWidthText/imageHeightText are the active fields shown to the editor. Keep the
    // ordinary generation dimensions separately so UltraFix can temporarily target a larger
    // canvas without overwriting the user's normal preset.
    var imageNormalWidthText by rememberSaveable { mutableStateOf("512") }
    var imageNormalHeightText by rememberSaveable { mutableStateOf("512") }
    var imageUltraFixTargetWidthText by rememberSaveable { mutableStateOf("512") }
    var imageUltraFixTargetHeightText by rememberSaveable { mutableStateOf("512") }
    var imageStepsText by rememberSaveable { mutableStateOf("20") }
    var imageCfgScaleText by rememberSaveable { mutableStateOf("7") }
    var imageViggleAutoSteps by rememberSaveable { mutableStateOf(false) }
    var imageViggleAutoCfg by rememberSaveable { mutableStateOf(false) }
    var imageStepsEditedByUser by rememberSaveable { mutableStateOf(false) }
    var imageCfgEditedByUser by rememberSaveable { mutableStateOf(false) }
    var imageViggleSelected by rememberSaveable { mutableStateOf(false) }
    var imageSeedText by rememberSaveable { mutableStateOf("") }
    var imageSampler by rememberSaveable { mutableStateOf("euler") }
    var imageLoraDraftJson by rememberSaveable { mutableStateOf("[]") }
    var imageTextualInversionIdsJson by rememberSaveable { mutableStateOf("[]") }
    val imageTextualInversionTriggerById = remember { mutableStateMapOf<String, String>() }
    var imageUltraFixEnabled by rememberSaveable { mutableStateOf(false) }
    var imageUltraFixStrengthText by rememberSaveable { mutableStateOf("0.35") }
    var imageUltraFixInversionStepsText by rememberSaveable { mutableStateOf("4") }
    var imageUltraFixRefinementStepsText by rememberSaveable { mutableStateOf("10") }
    var imageUltraFixTileSizeText by rememberSaveable { mutableStateOf("512") }
    var imageUltraFixTileSizeExplicit by rememberSaveable { mutableStateOf(false) }
    var imageUltraFixOverlapText by rememberSaveable { mutableStateOf("0.25") }
    var restoredImageParameterModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var imageInputRestoreWarning by rememberSaveable { mutableStateOf<String?>(null) }
    var imageLoraRestoreWarning by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingGenerationImageRole by rememberSaveable { mutableStateOf("input") }
    var pendingUltraFixSubmission by remember {
        mutableStateOf<Pair<String, ImageGenerationUiOptions>?>(null)
    }
    var imageGenerationStartSignal by remember { mutableStateOf(0L) }
    var generationImageEditorRequest by remember {
        mutableStateOf<GenerationImageEditorRequest?>(null)
    }
    val imageTaskMode = ImageGenerationUiTaskMode.entries.firstOrNull {
        it.name == imageTaskModeName
    } ?: ImageGenerationUiTaskMode.TEXT_TO_IMAGE
    val selectedImageModelChoice = state.imageModels
        .firstOrNull { model -> model.id == state.selectedImageModelId }
    val selectedImageTaskModes = selectedImageModelChoice
        ?.supportedImageTaskModes
        ?.takeIf { modes -> modes.isNotEmpty() }
        ?: setOf(ImageGenerationUiTaskMode.TEXT_TO_IMAGE)
    val supportsImageNegativePrompt = selectedImageModelChoice?.supportsImageNegativePrompt == true
    val supportsImageClipSkip = selectedImageModelChoice?.supportsImageClipSkip == true
    val supportsImageVaeTiling = selectedImageModelChoice?.supportsImageVaeTiling == true
    val supportsImageTextualInversion =
        selectedImageModelChoice?.supportsImageTextualInversion == true
    val supportedImageTextualInversionFormats = selectedImageModelChoice
        ?.supportedImageTextualInversionFormats
        ?.mapTo(mutableSetOf()) { it.trim().lowercase() }
        .orEmpty()
    val imageTextualInversionsForSelectedModel = state.imageTextualInversions.map { artifact ->
        artifact.copy(
            compatibleWithSelectedModel = supportsImageTextualInversion &&
                artifact.isSupportedBy(supportedImageTextualInversionFormats)
        )
    }
    val supportsImageUltraFix = selectedImageModelChoice?.supportsImageUltraFix == true
    val supportsImageLora = selectedImageModelChoice?.supportsImageLora == true
    LaunchedEffect(
        state.selectedImageModelId,
        restoredImageParameterModelId,
        supportsImageLora,
        imageLoraDraftJson,
        imageLoraRestoreWarning,
        state.imageLoras.map(ImageLoraUiItem::id)
    ) {
        val modelId = state.selectedImageModelId
        if (modelId == null || restoredImageParameterModelId != modelId ||
            imageLoraRestoreWarning != null
        ) {
            onChatImageLoraSelectionChange(null)
            return@LaunchedEffect
        }
        if (!supportsImageLora) {
            onChatImageLoraSelectionChange(emptyList())
            return@LaunchedEffect
        }
        val drafts = imageLoraDraftsFromJson(imageLoraDraftJson)
        val availableIds = state.imageLoras.mapTo(hashSetOf(), ImageLoraUiItem::id)
        val valid = drafts.size <= 8 && drafts.map { it.id }.distinct().size == drafts.size &&
            drafts.all { draft ->
                val multiplier = draft.multiplierText.toDoubleOrNull()
                draft.id in availableIds && multiplier != null && multiplier.isFinite() &&
                    multiplier in -4.0..4.0 && kotlin.math.abs(multiplier) >= 0.01
            }
        onChatImageLoraSelectionChange(if (valid) drafts.map { draft ->
            ImageGenerationUiLoraSelection(draft.id, requireNotNull(draft.multiplierText.toDoubleOrNull()))
        } else null)
    }
    val selectedImagePreviewMode = selectedImageModelChoice?.resolvedImagePreviewMode()
    val supportsImageLivePreview = selectedImagePreviewMode != null
    val selectedImageSupportedSamplers = selectedImageModelChoice
        ?.imageSupportedSamplersForTask(imageTaskMode)
        .orEmpty()
    val selectedImageDefaultSampler = selectedImageModelChoice?.imageDefaultSampler.orEmpty()
    val maxImageBatchCount = selectedImageModelChoice?.maxImageBatchCount?.coerceIn(1, 8) ?: 1
    val currentImageModelIds = state.existingImageModelIds.ifEmpty {
        state.imageModels.mapTo(mutableSetOf(), ChatModelChoice::id)
    }
    val transientGenerationImageUris = setOfNotNull(
        imageInputUri,
        imageMaskUri,
        imageControlUri,
        generationImageEditorRequest?.sourceUri
    )
    val libraryHistoryGenerationImageUris = normalizedGenerationImageHistoryReferences(
        state.generationHistoryInputUris
    )
    LaunchedEffect(
        currentImageModelIds,
        state.deferGenerationImageGrantRelease,
        transientGenerationImageUris,
        libraryHistoryGenerationImageUris
    ) {
        val pendingUriEpochsEligibleForPrune = pendingGenerationImageGrantUriEpochsSnapshot()
        withContext(Dispatchers.IO) {
            generationImageGrantMutex.withLock {
                reconcileGenerationImageUriGrants(
                    context = context.applicationContext,
                    preferences = imageParameterPreferences,
                    currentImageModelIds = currentImageModelIds,
                    deferRelease = state.deferGenerationImageGrantRelease,
                    releaseOwnedUrisIfCoordinatorIdle =
                        releaseGenerationImageGrantsIfCoordinatorIdle,
                    libraryHistoryReferencedUris = libraryHistoryGenerationImageUris,
                    transientReferencedUris = transientGenerationImageUris,
                    pendingUriEpochsEligibleForPrune = pendingUriEpochsEligibleForPrune
                )
            }
        }
    }
    LaunchedEffect(
        state.selectedImageModelId,
        selectedImageModelChoice?.id,
        selectedImagePreviewMode,
        selectedImageModelChoice?.imageDefaultPreviewInterval,
        selectedImageModelChoice?.imageSupportedSamplers,
        selectedImageModelChoice?.imageImg2ImgSupportedSamplers,
        selectedImageDefaultSampler,
        selectedImageModelChoice?.imageDefaultSteps,
        selectedImageModelChoice?.imageMinSteps,
        selectedImageModelChoice?.imageMaxSteps,
        selectedImageModelChoice?.imageDefaultWidth,
        selectedImageModelChoice?.imageDefaultHeight,
        selectedImageModelChoice?.imageUltraFixMinWidth,
        selectedImageModelChoice?.imageUltraFixMaxWidth,
        selectedImageModelChoice?.imageUltraFixMinHeight,
        selectedImageModelChoice?.imageUltraFixMaxHeight,
        selectedImageModelChoice?.imageUltraFixWidthMultiple,
        selectedImageModelChoice?.imageUltraFixHeightMultiple,
        selectedImageModelChoice?.imageUltraFixRequiredTileSize,
    ) {
        val modelId = state.selectedImageModelId
        val model = selectedImageModelChoice
        if (modelId == null || model == null) {
            restoredImageParameterModelId = null
            imageInputRestoreWarning = null
            imageLoraRestoreWarning = null
            imageViggleSelected = false
            return@LaunchedEffect
        }
        val snapshot = ImageGenerationUiParameterSnapshot.fromJsonOrNull(
            imageParameterPreferences.getString(
                IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX + modelId,
                null
            )
        )?.normalizedForImageModel(model)
        val restoredTaskMode = snapshot?.taskModeName
            ?.takeIf { saved -> ImageGenerationUiTaskMode.entries.any { it.name == saved } }
            ?.let { ImageGenerationUiTaskMode.valueOf(it) }
            ?: ImageGenerationUiTaskMode.TEXT_TO_IMAGE
        imageTaskModeName = restoredTaskMode.name
        imageStrengthText = snapshot?.strengthText ?: "0.75"
        imageControlStrengthText = snapshot?.controlStrengthText ?: "1.0"
        imageNegativePrompt = snapshot?.negativePrompt.orEmpty()
        imageDisableModelNegativePrompt = snapshot?.disableModelNegativePrompt ?: false
        imageClipSkipText = snapshot?.clipSkipText.orEmpty()
        imageVaeTilingEnabled = snapshot?.vaeTilingEnabled ?: false
        imageTextualInversionIdsJson = imageTextualInversionIdsToJson(
            snapshot?.textualInversionIds.orEmpty()
        )
        imageUltraFixEnabled = snapshot?.ultraFixEnabled == true
        imageUltraFixStrengthText = snapshot?.ultraFixStrengthText ?: "0.35"
        imageUltraFixInversionStepsText = snapshot?.ultraFixInversionStepsText ?: "4"
        imageUltraFixRefinementStepsText = snapshot?.ultraFixRefinementStepsText ?: "10"
        imageUltraFixTileSizeText = snapshot?.ultraFixTileSizeText
            ?: model.resolvedImageUltraFixDefaultTileSize().toString()
        imageUltraFixTileSizeExplicit = snapshot?.ultraFixTileSizeExplicit ?: false
        imageUltraFixOverlapText = snapshot?.ultraFixOverlapText ?: "0.25"
        imageLivePreviewEnabled = model.resolvedImagePreviewMode() != null &&
            (snapshot?.livePreviewEnabled ?: true)
        imageLivePreviewInterval = (
            snapshot?.livePreviewInterval ?: model.imageDefaultPreviewInterval
        ).coerceIn(1, 10)
        imageLivePreviewIntervalExplicit = snapshot?.livePreviewIntervalExplicit ?: false
        imageBatchCount = (snapshot?.batchCount ?: 1).coerceIn(1, model.maxImageBatchCount.coerceIn(1, 8))
        imageNormalWidthText = snapshot?.widthText ?: normalizedImageGenerationDimensionText(
            rawValue = model.imageDefaultWidth.toString(),
            defaultValue = model.imageDefaultWidth,
            minValue = model.imageMinWidth,
            maxValue = model.imageMaxWidth,
            multiple = model.imageWidthMultiple
        )
        imageNormalHeightText = snapshot?.heightText ?: normalizedImageGenerationDimensionText(
            rawValue = model.imageDefaultHeight.toString(),
            defaultValue = model.imageDefaultHeight,
            minValue = model.imageMinHeight,
            maxValue = model.imageMaxHeight,
            multiple = model.imageHeightMultiple
        )
        imageUltraFixTargetWidthText = snapshot?.ultraFixTargetWidthText
            ?.takeIf(String::isNotBlank)
            ?: snapshot?.widthText
            ?: model.imageDefaultWidth.toString()
        imageUltraFixTargetHeightText = snapshot?.ultraFixTargetHeightText
            ?.takeIf(String::isNotBlank)
            ?: snapshot?.heightText
            ?: model.imageDefaultHeight.toString()
        imageWidthText = if (imageUltraFixEnabled) {
            imageUltraFixTargetWidthText
        } else {
            imageNormalWidthText
        }
        imageHeightText = if (imageUltraFixEnabled) {
            imageUltraFixTargetHeightText
        } else {
            imageNormalHeightText
        }
        imageStepsText = snapshot?.stepsText ?: normalizedImageGenerationStepsText(
            rawValue = model.imageDefaultSteps.toString(),
            defaultValue = model.imageDefaultSteps,
            minValue = model.imageMinSteps,
            maxValue = model.imageMaxSteps
        )
        imageCfgScaleText = snapshot?.cfgScaleText
            ?: model.imageDefaultCfgScale.toString().trimEnd('0').trimEnd('.')
        imageViggleAutoSteps = snapshot?.viggleAutoSteps ?: false
        imageViggleAutoCfg = snapshot?.viggleAutoCfg ?: false
        imageStepsEditedByUser = snapshot?.stepsEditedByUser ?: false
        imageCfgEditedByUser = snapshot?.cfgEditedByUser ?: false
        imageViggleSelected = false
        imageSeedText = snapshot?.seedText.orEmpty()
        imageSampler = normalizedImageSamplerForCapabilities(
            current = snapshot?.sampler ?: model.imageDefaultSampler,
            supported = model.imageSupportedSamplersForTask(restoredTaskMode),
            defaultSampler = model.imageDefaultSampler
        )
        val availableLoraIds = state.imageLoras.mapTo(mutableSetOf(), ImageLoraUiItem::id)
        val restoredLoras = snapshot?.loras.orEmpty().filter { draft -> draft.id in availableLoraIds }
        imageLoraDraftJson = imageLoraDraftsToJson(
            if (model.supportsImageLora) restoredLoras else emptyList()
        )
        val missingLoraCount = snapshot?.loras.orEmpty().size - restoredLoras.size
        imageLoraRestoreWarning = when {
            !model.supportsImageLora && snapshot?.loras?.isNotEmpty() == true ->
                "当前模型不支持 LoRA，已停用之前保存的选择。"
            missingLoraCount > 0 ->
                "之前选择的 $missingLoraCount 个 LoRA 已删除，请重新导入后选择。"
            else -> null
        }
        val restoredInputUri = persistedGenerationImageUriOrNull(context, snapshot?.inputImageUri)
        val restoredMaskUri = persistedGenerationImageUriOrNull(context, snapshot?.maskImageUri)
        val restoredControlUri = persistedGenerationImageUriOrNull(context, snapshot?.controlImageUri)
        imageInputUri = restoredInputUri
        imageMaskUri = restoredMaskUri
        imageControlUri = restoredControlUri
        val invalidatedRoles = buildList {
            if (!snapshot?.inputImageUri.isNullOrBlank() && restoredInputUri == null) add("原图")
            if (!snapshot?.maskImageUri.isNullOrBlank() && restoredMaskUri == null) add("蒙版")
            if (!snapshot?.controlImageUri.isNullOrBlank() && restoredControlUri == null) add("控制图")
        }
        imageInputRestoreWarning = invalidatedRoles.takeIf { it.isNotEmpty() }?.let { roles ->
            "之前选择的${roles.joinToString("、")}已被删除或读取权限已失效，请重新选择。"
        }
        restoredImageParameterModelId = modelId
    }
    LaunchedEffect(
        state.selectedImageModelId,
        restoredImageParameterModelId,
        selectedImageModelChoice?.isQwenImage21Gguf,
        selectedImageModelChoice?.imageDefaultSteps,
        selectedImageModelChoice?.imageDefaultCfgScale,
        imageLoraDraftJson,
        imageViggleSelected,
        imageViggleAutoSteps,
        imageViggleAutoCfg,
        state.imageLoras.map { it.id to it.isQwenImage21ViggleV021 }
    ) {
        val model = selectedImageModelChoice ?: return@LaunchedEffect
        if (restoredImageParameterModelId != model.id) return@LaunchedEffect
        val drafts = imageLoraDraftsFromJson(imageLoraDraftJson)
        val selectedViggle = model.isQwenImage21Gguf && drafts.size == 1 &&
            drafts.single().multiplierText.toDoubleOrNull() == 1.0 &&
            state.imageLoras.any { it.id == drafts.single().id && it.isQwenImage21ViggleV021 }
        if (selectedViggle == imageViggleSelected &&
            (selectedViggle || (!imageViggleAutoSteps && !imageViggleAutoCfg))
        ) return@LaunchedEffect
        val next = imageViggleUiControlsForSelection(
            current = ImageViggleUiControls(
                stepsText = imageStepsText,
                cfgScaleText = imageCfgScaleText,
                autoSteps = imageViggleAutoSteps,
                autoCfg = imageViggleAutoCfg,
                stepsEditedByUser = imageStepsEditedByUser,
                cfgEditedByUser = imageCfgEditedByUser
            ),
            model = model,
            selected = selectedViggle
        )
        imageStepsText = next.stepsText
        imageCfgScaleText = next.cfgScaleText
        imageViggleAutoSteps = next.autoSteps
        imageViggleAutoCfg = next.autoCfg
        imageViggleSelected = selectedViggle
    }
    LaunchedEffect(
        state.selectedImageModelId,
        selectedImageModelChoice,
        restoredImageParameterModelId,
        imageTaskModeName,
        imageStrengthText,
        imageControlStrengthText,
        imageNegativePrompt,
        imageDisableModelNegativePrompt,
        imageClipSkipText,
        imageVaeTilingEnabled,
        imageLivePreviewEnabled,
        imageLivePreviewInterval,
        imageLivePreviewIntervalExplicit,
        imageBatchCount,
        imageWidthText,
        imageHeightText,
        imageStepsText,
        imageCfgScaleText,
        imageViggleAutoSteps,
        imageViggleAutoCfg,
        imageStepsEditedByUser,
        imageCfgEditedByUser,
        imageSeedText,
        imageSampler,
        imageLoraDraftJson,
        imageTextualInversionIdsJson,
        imageUltraFixEnabled,
        imageUltraFixStrengthText,
        imageUltraFixInversionStepsText,
        imageUltraFixRefinementStepsText,
        imageUltraFixTileSizeText,
        imageUltraFixTileSizeExplicit,
        imageUltraFixOverlapText,
        imageNormalWidthText,
        imageNormalHeightText,
        imageUltraFixTargetWidthText,
        imageUltraFixTargetHeightText,
        imageInputUri,
        imageMaskUri,
        imageControlUri
    ) {
        val modelId = state.selectedImageModelId ?: return@LaunchedEffect
        val model = selectedImageModelChoice ?: return@LaunchedEffect
        if (restoredImageParameterModelId != modelId) return@LaunchedEffect
        val snapshot = ImageGenerationUiParameterSnapshot(
            taskModeName = imageTaskModeName,
            strengthText = imageStrengthText,
            controlStrengthText = imageControlStrengthText,
            negativePrompt = imageNegativePrompt,
            disableModelNegativePrompt = imageDisableModelNegativePrompt,
            clipSkipText = imageClipSkipText,
            vaeTilingEnabled = imageVaeTilingEnabled,
            livePreviewMode = model.resolvedImagePreviewMode(),
            livePreviewEnabled = imageLivePreviewEnabled,
            livePreviewInterval = imageLivePreviewInterval.coerceIn(1, 10),
            livePreviewIntervalExplicit = imageLivePreviewIntervalExplicit,
            batchCount = imageBatchCount,
            widthText = if (imageUltraFixEnabled) imageNormalWidthText else imageWidthText,
            heightText = if (imageUltraFixEnabled) imageNormalHeightText else imageHeightText,
            stepsText = imageStepsText,
            cfgScaleText = imageCfgScaleText,
            viggleAutoSteps = imageViggleAutoSteps,
            viggleAutoCfg = imageViggleAutoCfg,
            stepsEditedByUser = imageStepsEditedByUser,
            cfgEditedByUser = imageCfgEditedByUser,
            seedText = imageSeedText,
            sampler = imageSampler,
            loras = imageLoraDraftsFromJson(imageLoraDraftJson),
            textualInversionIds = imageTextualInversionIdsFromJson(imageTextualInversionIdsJson),
            ultraFixEnabled = imageUltraFixEnabled,
            ultraFixStrengthText = imageUltraFixStrengthText,
            ultraFixInversionStepsText = imageUltraFixInversionStepsText,
            ultraFixRefinementStepsText = imageUltraFixRefinementStepsText,
            ultraFixTileSizeText = imageUltraFixTileSizeText,
            ultraFixTileSizeExplicit = imageUltraFixTileSizeExplicit,
            ultraFixOverlapText = imageUltraFixOverlapText,
            ultraFixTargetWidthText = if (imageUltraFixEnabled) {
                imageWidthText
            } else {
                imageUltraFixTargetWidthText
            },
            ultraFixTargetHeightText = if (imageUltraFixEnabled) {
                imageHeightText
            } else {
                imageUltraFixTargetHeightText
            },
            inputImageUri = imageInputUri,
            maskImageUri = imageMaskUri,
            controlImageUri = imageControlUri
        ).normalizedForImageModel(model)
        if (imageUltraFixTileSizeText != snapshot.ultraFixTileSizeText) {
            imageUltraFixTileSizeText = snapshot.ultraFixTileSizeText
        }
        if (imageUltraFixTileSizeExplicit != snapshot.ultraFixTileSizeExplicit) {
            imageUltraFixTileSizeExplicit = snapshot.ultraFixTileSizeExplicit
        }
        if (!model.cloud && model.imageMinWidth == model.imageMaxWidth &&
            imageNormalWidthText != snapshot.widthText
        ) {
            imageNormalWidthText = snapshot.widthText
        }
        if (!model.cloud && model.imageMinHeight == model.imageMaxHeight &&
            imageNormalHeightText != snapshot.heightText
        ) {
            imageNormalHeightText = snapshot.heightText
        }
        val committedReferencedUris = setOfNotNull(
            snapshot.inputImageUri,
            snapshot.maskImageUri,
            snapshot.controlImageUri
        )
        val pendingUriEpochsEligibleForPrune = pendingGenerationImageGrantUriEpochsSnapshot()
        withContext(Dispatchers.IO) {
            generationImageGrantMutex.withLock {
                check(
                    imageParameterPreferences.edit()
                        .putString(
                            IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX + modelId,
                            snapshot.toJson().toString()
                        )
                        .commit()
                ) { "Unable to persist image generation UI parameters." }
                reconcileGenerationImageUriGrants(
                    context = context.applicationContext,
                    preferences = imageParameterPreferences,
                    currentImageModelIds = currentImageModelIds,
                    deferRelease = state.deferGenerationImageGrantRelease,
                    releaseOwnedUrisIfCoordinatorIdle =
                        releaseGenerationImageGrantsIfCoordinatorIdle,
                    libraryHistoryReferencedUris = libraryHistoryGenerationImageUris,
                    transientReferencedUris = transientGenerationImageUris,
                    committedReferencedUris = committedReferencedUris,
                    pendingUriEpochsEligibleForPrune = pendingUriEpochsEligibleForPrune
                )
            }
        }
    }
    LaunchedEffect(
        state.selectedImageModelId,
        selectedImageTaskModes,
        imageTaskMode,
        supportsImageNegativePrompt,
        supportsImageClipSkip,
        supportsImageVaeTiling,
        supportsImageUltraFix,
        supportsImageLora,
        supportsImageLivePreview,
        selectedImageSupportedSamplers,
        selectedImageDefaultSampler,
        selectedImageModelChoice?.imageDefaultSteps,
        selectedImageModelChoice?.imageMinSteps,
        selectedImageModelChoice?.imageMaxSteps,
        state.imageLoras.map(ImageLoraUiItem::id),
        maxImageBatchCount
    ) {
        if (imageTaskMode !in selectedImageTaskModes) {
            imageTaskModeName = ImageGenerationUiTaskMode.entries
                .firstOrNull(selectedImageTaskModes::contains)
                ?.name
                ?: ImageGenerationUiTaskMode.TEXT_TO_IMAGE.name
            imageInputUri = null
            imageMaskUri = null
            imageControlUri = null
        }
        if (!supportsImageNegativePrompt) {
            imageNegativePrompt = ""
            imageDisableModelNegativePrompt = false
        }
        if (!supportsImageClipSkip) imageClipSkipText = ""
        if (!supportsImageVaeTiling) imageVaeTilingEnabled = false
        if ((!supportsImageUltraFix || imageTaskMode != ImageGenerationUiTaskMode.IMG2IMG) &&
            imageUltraFixEnabled
        ) {
            imageUltraFixTargetWidthText = imageWidthText
            imageUltraFixTargetHeightText = imageHeightText
            imageWidthText = imageNormalWidthText
            imageHeightText = imageNormalHeightText
            imageUltraFixEnabled = false
        }
        if (!supportsImageLivePreview) imageLivePreviewEnabled = false
        imageSampler = normalizedImageSamplerForCapabilities(
            current = imageSampler,
            supported = selectedImageSupportedSamplers,
            defaultSampler = selectedImageDefaultSampler
        )
        selectedImageModelChoice?.takeUnless(ChatModelChoice::cloud)?.let { model ->
            imageStepsText = normalizedImageGenerationStepsText(
                rawValue = imageStepsText,
                defaultValue = model.imageDefaultSteps,
                minValue = model.imageMinSteps,
                maxValue = model.imageMaxSteps
            )
        }
        imageLivePreviewInterval = imageLivePreviewInterval.coerceIn(1, 10)
        val availableLoraIds = state.imageLoras.mapTo(mutableSetOf(), ImageLoraUiItem::id)
        val currentLoras = imageLoraDraftsFromJson(imageLoraDraftJson)
        val retainedLoras = if (supportsImageLora) {
            currentLoras.filter { draft -> draft.id in availableLoraIds }
        } else {
            emptyList()
        }
        if (retainedLoras != currentLoras) {
            imageLoraDraftJson = imageLoraDraftsToJson(retainedLoras)
            if (supportsImageLora && currentLoras.isNotEmpty()) {
                imageLoraRestoreWarning = "所选 LoRA 已删除，请重新导入后选择。"
            }
        }
        imageBatchCount = imageBatchCount.coerceIn(1, maxImageBatchCount)
    }
    LaunchedEffect(
        supportsImageTextualInversion,
        supportedImageTextualInversionFormats,
        state.imageTextualInversionImporting,
        state.imageTextualInversions.map { artifact ->
            Triple(artifact.id, artifact.trigger, artifact.format)
        },
        imageTextualInversionIdsJson
    ) {
        val current = imageTextualInversionIdsFromJson(imageTextualInversionIdsJson)
        val reconciled = reconcileImageTextualInversionSelection(
            supportsTextualInversion = supportsImageTextualInversion,
            libraryBusy = state.imageTextualInversionImporting,
            currentIds = current,
            prompt = imagePrompt,
            additionalPrompt = imageNegativePrompt,
            knownTriggersById = imageTextualInversionTriggerById,
            available = state.imageTextualInversions,
            supportedFormats = supportedImageTextualInversionFormats
        )
        imageTextualInversionTriggerById.clear()
        imageTextualInversionTriggerById.putAll(reconciled.triggersById)
        if (reconciled.ids != current) {
            imageTextualInversionIdsJson = imageTextualInversionIdsToJson(reconciled.ids)
        }
        if (reconciled.prompt != imagePrompt) imagePrompt = reconciled.prompt
        if (reconciled.additionalPrompt != imageNegativePrompt) {
            imageNegativePrompt = reconciled.additionalPrompt
        }
    }
    fun enqueueImagePrompt(prompt: String) {
        imageValidationError = null
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return
        if (imageTaskMode !in selectedImageTaskModes) {
            imageValidationError = "当前模型不支持此生成方式"
            return
        }
        val requiredInputMissing = when (imageTaskMode) {
            ImageGenerationUiTaskMode.TEXT_TO_IMAGE -> false
            ImageGenerationUiTaskMode.IMG2IMG,
            ImageGenerationUiTaskMode.EDIT -> imageInputUri == null
            ImageGenerationUiTaskMode.INPAINT -> imageInputUri == null || imageMaskUri == null
            ImageGenerationUiTaskMode.CONTROL -> imageControlUri == null
        }
        if (requiredInputMissing) {
            imageValidationError = "请先选择当前模式需要的图片输入"
            return
        }
        val strengthValue = imageStrengthText.toDoubleOrNull()
        if (imageTaskMode in setOf(
                ImageGenerationUiTaskMode.IMG2IMG,
                ImageGenerationUiTaskMode.INPAINT
            ) && (strengthValue == null || !strengthValue.isFinite() || strengthValue < 0.0 || strengthValue > 1.0)
        ) {
            imageValidationError = "重绘强度必须在 [0, 1]"
            return
        }
        val controlStrengthValue = imageControlStrengthText.toDoubleOrNull()
        if (imageTaskMode == ImageGenerationUiTaskMode.CONTROL &&
            (controlStrengthValue == null || !controlStrengthValue.isFinite() || controlStrengthValue < 0.0 || controlStrengthValue > 2.0)
        ) {
            imageValidationError = "控制强度必须在 [0, 2]"
            return
        }
        val clipSkipValue = if (supportsImageClipSkip) {
            imageClipSkipText.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        } else {
            null
        }
        if (supportsImageClipSkip &&
            imageClipSkipText.isNotBlank() &&
            (clipSkipValue == null || clipSkipValue !in -1..32)
        ) {
            imageValidationError = "CLIP skip 参数必须为 -1 或 0-32"
            return
        }
        val localControls = selectedImageModelChoice?.takeUnless(ChatModelChoice::cloud)
        val widthValue = localControls?.let { imageWidthText.toIntOrNull() }
        val heightValue = localControls?.let { imageHeightText.toIntOrNull() }
        val ultraFixSourceTarget = if (imageUltraFixEnabled &&
            !imageInputDimensionsProbing &&
            imageInputDimensionsUri == imageInputUri &&
            imageInputSourceWidth > 0 && imageInputSourceHeight > 0
        ) {
            localControls?.ultraFixTargetSizeForSourceOrNull(
                imageInputSourceWidth,
                imageInputSourceHeight
            )
        } else {
            null
        }
        val dimensionsValid = if (imageUltraFixEnabled) {
            localControls != null && widthValue != null && heightValue != null &&
                ultraFixSourceTarget != null &&
                widthValue in localControls.imageUltraFixMinWidth..
                    localControls.imageUltraFixMaxWidth &&
                heightValue in localControls.imageUltraFixMinHeight..
                    localControls.imageUltraFixMaxHeight &&
                widthValue >= ultraFixSourceTarget.first &&
                heightValue >= ultraFixSourceTarget.second &&
                widthValue % localControls.imageUltraFixWidthMultiple == 0 &&
                heightValue % localControls.imageUltraFixHeightMultiple == 0 &&
                widthValue.toLong() * heightValue.toLong() <= 64L * 1024L * 1024L
        } else {
            localControls == null || (
                widthValue != null && heightValue != null &&
                    widthValue in localControls.imageMinWidth..localControls.imageMaxWidth &&
                    heightValue in localControls.imageMinHeight..localControls.imageMaxHeight &&
                    widthValue % localControls.imageWidthMultiple == 0 &&
                    heightValue % localControls.imageHeightMultiple == 0 &&
                    (!localControls.isQwenImage21Model() ||
                        (widthValue to heightValue) in QWEN_IMAGE_21_VERIFIED_SIZE_PAIRS)
                )
        }
        if (!dimensionsValid) {
            val message = when {
                localControls?.isQwenImage21Model() == true ->
                    "Qwen-Image-2.1 当前 runtime 提供 21 个尺寸组合，请按比例/档位选择；本机结果以实际生成为准"
                imageUltraFixEnabled && imageInputDimensionsProbing ->
                    "正在检查 UltraFix 源图尺寸，请稍后重试"
                imageUltraFixEnabled && ultraFixSourceTarget == null ->
                    "无法为当前源图选择不缩小的 UltraFix 目标，请先裁剪图片"
                imageUltraFixEnabled && ultraFixSourceTarget != null &&
                    widthValue != null && heightValue != null &&
                    (widthValue < ultraFixSourceTarget.first || heightValue < ultraFixSourceTarget.second) ->
                    "UltraFix 目标尺寸不能小于源图"
                else -> "图片尺寸不符合当前模型的范围或步进要求"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            return
        }
        val stepsValue = localControls?.let { imageStepsText.toIntOrNull() }
        if (localControls != null && (
                stepsValue == null ||
                    stepsValue !in localControls.imageMinSteps..localControls.imageMaxSteps
                )
        ) {
            Toast.makeText(
                context,
                "采样步数必须为 ${localControls.imageMinSteps}-${localControls.imageMaxSteps}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val cfgScaleValue = localControls?.let { imageCfgScaleText.toDoubleOrNull() }
        if (localControls != null &&
            (cfgScaleValue == null || !cfgScaleValue.isFinite() || cfgScaleValue !in 0.0..30.0)
        ) {
            imageValidationError = "CFG 必须为 0-30 的有限数值"
            return
        }
        val seedValue = localControls?.let {
            imageSeedText.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        }
        if (localControls != null && imageSeedText.isNotBlank() &&
            (seedValue == null || seedValue < 0)
        ) {
            imageValidationError = "Seed 必须为非负 32 位整数"
            return
        }
        val sampleMethodValue = localControls?.let { imageSampler.trim() }
        if (localControls != null && (
                sampleMethodValue.isNullOrBlank() ||
                    sampleMethodValue !in selectedImageSupportedSamplers
                )
        ) {
            imageValidationError = "当前采样器不可用，请选择模型支持的采样器"
            return
        }
        val loraSelections = if (supportsImageLora) {
            val availableIds = state.imageLoras.mapTo(mutableSetOf(), ImageLoraUiItem::id)
            val drafts = imageLoraDraftsFromJson(imageLoraDraftJson)
            if (drafts.any { it.id !in availableIds }) {
                Toast.makeText(context, "所选 LoRA 已删除，请重新选择", Toast.LENGTH_SHORT).show()
                return
            }
            val invalid = drafts.firstOrNull { draft ->
                val value = draft.multiplierText.toDoubleOrNull()
                value == null || !value.isFinite() || value !in -4.0..4.0 ||
                    kotlin.math.abs(value) < 0.01
            }
            if (invalid != null) {
                Toast.makeText(context, "LoRA 倍率必须在 [-4, -0.01] 或 [0.01, 4]", Toast.LENGTH_SHORT).show()
                return
            }
            drafts.map { draft ->
                ImageGenerationUiLoraSelection(
                    id = draft.id,
                    multiplier = requireNotNull(draft.multiplierText.toDoubleOrNull())
                )
            }
        } else {
            emptyList()
        }
        val textualInversionIds = if (supportsImageTextualInversion) {
            val requestedIds = imageTextualInversionIdsFromJson(imageTextualInversionIdsJson)
            if (requestedIds.isNotEmpty() && state.imageTextualInversionImporting) {
                Toast.makeText(
                    context,
                    "Textual Inversion 库仍在加载或更新，请稍后重试",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            val availableById = state.imageTextualInversions
                .filter { artifact ->
                    artifact.isSupportedBy(supportedImageTextualInversionFormats)
                }
                .associateBy(ImageTextualInversionUiItem::id)
            requestedIds.also { ids ->
                if (ids.size > 8 || ids.any { it !in availableById }) {
                    Toast.makeText(context, "所选 Textual Inversion 已失效，请重新选择", Toast.LENGTH_SHORT).show()
                    return
                }
                val missingTriggers = ids.mapNotNull(availableById::get).map { it.trigger }
                    .filterNot { trigger ->
                        imagePromptContainsTextualInversionTrigger(cleanPrompt, trigger) ||
                            imagePromptContainsTextualInversionTrigger(imageNegativePrompt, trigger)
                    }
                if (missingTriggers.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        "请在提示词中保留触发词：${missingTriggers.take(3).joinToString()}",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
            }
        } else {
            emptyList()
        }
        val ultraFixOptions = if (imageUltraFixEnabled) {
            if (!supportsImageUltraFix || imageTaskMode != ImageGenerationUiTaskMode.IMG2IMG ||
                imageInputUri == null || widthValue == null || heightValue == null ||
                localControls == null
            ) {
                Toast.makeText(context, "当前输入或模型不能执行 UltraFix", Toast.LENGTH_SHORT).show()
                return
            }
            val ultraStrength = imageUltraFixStrengthText.toDoubleOrNull()
            val totalSteps = imageUltraFixRefinementStepsText.toIntOrNull()
            val requestedInversionSteps = imageUltraFixInversionStepsText.toIntOrNull()
            val tileSize = imageUltraFixTileSizeText.toIntOrNull()
            val overlap = imageUltraFixOverlapText.toDoubleOrNull()
            val expectedInversionSteps = if (ultraStrength != null && totalSteps != null &&
                ultraStrength.isFinite() && totalSteps > 0
            ) {
                imageGenerationUltraFixDenoisingTailStepCount(totalSteps, ultraStrength)
            } else {
                -1
            }
            if (ultraStrength == null || !ultraStrength.isFinite() || ultraStrength <= 0.0 || ultraStrength > 1.0 ||
                totalSteps == null || totalSteps !in 1..IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS ||
                requestedInversionSteps == null || requestedInversionSteps !in
                    1..minOf(IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS, totalSteps) ||
                requestedInversionSteps != expectedInversionSteps ||
                tileSize == null || tileSize !in 128..2048 ||
                tileSize % localControls.imageUltraFixWidthMultiple != 0 ||
                tileSize % localControls.imageUltraFixHeightMultiple != 0 ||
                (localControls.imageUltraFixRequiredTileSize > 0 &&
                    tileSize != localControls.imageUltraFixRequiredTileSize) ||
                tileSize > minOf(widthValue, heightValue) ||
                overlap == null || !overlap.isFinite() || overlap !in 0.0..0.5
            ) {
                Toast.makeText(context, "UltraFix 参数不符合执行合同", Toast.LENGTH_SHORT).show()
                return
            }
            ImageGenerationUiUltraFixOptions(
                targetWidth = widthValue,
                targetHeight = heightValue,
                strength = ultraStrength,
                inversionSteps = expectedInversionSteps,
                refinementSteps = totalSteps,
                tileSize = tileSize,
                overlap = overlap,
            )
        } else {
            null
        }
        val previewRequest = imageGenerationUiPreviewRequestOrNull(
            model = selectedImageModelChoice,
            enabled = imageLivePreviewEnabled && ultraFixOptions == null,
            interval = imageLivePreviewInterval
        )
        val generationOptions = ImageGenerationUiOptions(
                taskMode = imageTaskMode,
                negativePrompt = if (!supportsImageNegativePrompt) {
                    null
                } else {
                    imageNegativePrompt.takeIf(String::isNotBlank)
                        ?: if (imageDisableModelNegativePrompt) "" else null
                },
                inputImageUri = imageInputUri,
                maskImageUri = imageMaskUri,
                controlImageUri = imageControlUri,
                strength = if (imageTaskMode in setOf(
                        ImageGenerationUiTaskMode.IMG2IMG,
                        ImageGenerationUiTaskMode.INPAINT
                    )
                ) ultraFixOptions?.strength ?: strengthValue else null,
                controlStrength = if (imageTaskMode == ImageGenerationUiTaskMode.CONTROL) {
                    controlStrengthValue
                } else {
                    null
                },
                clipSkip = clipSkipValue,
                batchCount = if (ultraFixOptions != null) 1 else
                    imageBatchCount.coerceIn(1, maxImageBatchCount),
                loras = loraSelections,
                vaeTileSize = ultraFixOptions?.tileSize
                    ?: if (supportsImageVaeTiling && imageVaeTilingEnabled) {
                        selectedImageModelChoice?.imageDefaultVaeTileSize
                    } else {
                        null
                    },
                vaeTileOverlap = ultraFixOptions?.overlap
                    ?: if (supportsImageVaeTiling && imageVaeTilingEnabled) {
                        selectedImageModelChoice?.imageDefaultVaeTileOverlap
                    } else {
                        null
                    },
                width = widthValue,
                height = heightValue,
                steps = ultraFixOptions?.refinementSteps ?: stepsValue,
                cfgScale = cfgScaleValue,
                seed = seedValue,
                sampleMethod = sampleMethodValue,
                previewMode = previewRequest?.mode,
                previewInterval = previewRequest?.interval,
                textualInversionIds = textualInversionIds,
                ultraFix = ultraFixOptions,
            )
        if (ultraFixOptions != null) {
            pendingUltraFixSubmission = cleanPrompt to generationOptions
        } else {
            if (onGenerateImagePrompt(cleanPrompt, generationOptions)) {
                imageGenerationStartSignal++
            }
        }
        imagePrompt = cleanPrompt
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onUploadFile(it.toString()) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onUploadFile(it.toString()) }
    }
    val generationPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            val selected = persistGenerationImageUri(context, selectedUri)
            if (selected == null) {
                Toast.makeText(
                    context,
                    "无法保留该图片的读取权限，请重新选择。",
                    Toast.LENGTH_SHORT
                ).show()
                return@rememberLauncherForActivityResult
            }
            when (pendingGenerationImageRole) {
                "mask" -> imageMaskUri = selected
                "control" -> imageControlUri = selected
                else -> {
                    imageInputUri = selected
                    imageMaskUri = null
                }
            }
            imageInputRestoreWarning = null
        }
    }
    LaunchedEffect(imageInputUri) {
        val sourceUri = imageInputUri
        imageInputDimensionsProbeFailed = false
        if (sourceUri == null) {
            imageInputDimensionsUri = null
            imageInputSourceWidth = 0
            imageInputSourceHeight = 0
            imageInputDimensionsProbing = false
            return@LaunchedEffect
        }
        if (imageInputDimensionsUri != sourceUri) {
            imageInputSourceWidth = 0
            imageInputSourceHeight = 0
        }
        imageInputDimensionsProbing = true
        try {
            val dimensions = withContext(Dispatchers.IO) {
                val probeJob = currentCoroutineContext()[Job]
                ImageGenerationBitmapEditing.probeDimensionsBounded(
                    context = context,
                    rawUri = sourceUri,
                    checkCancelled = { probeJob?.ensureActive() }
                )
            }
            if (imageInputUri == sourceUri) {
                imageInputDimensionsUri = sourceUri
                imageInputSourceWidth = dimensions.width
                imageInputSourceHeight = dimensions.height
                imageInputDimensionsProbeFailed = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (imageInputUri == sourceUri) {
                imageInputDimensionsUri = sourceUri
                imageInputSourceWidth = 0
                imageInputSourceHeight = 0
                imageInputDimensionsProbeFailed = true
            }
        } finally {
            if (imageInputUri == sourceUri) imageInputDimensionsProbing = false
        }
    }
    LaunchedEffect(
        imageUltraFixEnabled,
        imageInputUri,
        imageInputDimensionsUri,
        imageInputSourceWidth,
        imageInputSourceHeight,
        imageInputDimensionsProbing,
        selectedImageModelChoice?.id
    ) {
        if (!imageUltraFixEnabled || imageInputUri == null ||
            imageInputDimensionsUri != imageInputUri ||
            imageInputDimensionsProbing
        ) return@LaunchedEffect
        if (imageInputSourceWidth <= 0 || imageInputSourceHeight <= 0) {
            imageUltraFixEnabled = false
            return@LaunchedEffect
        }
        val model = selectedImageModelChoice?.takeIf(ChatModelChoice::supportsImageUltraFix)
            ?: return@LaunchedEffect
        val target = model.ultraFixTargetSizeForSourceOrNull(
            imageInputSourceWidth,
            imageInputSourceHeight
        ) ?: run {
            imageUltraFixEnabled = false
            return@LaunchedEffect
        }
        val tile = normalizedImageGenerationUltraFixTileSelection(
            model = model,
            rawValue = imageUltraFixTileSizeText,
            explicit = imageUltraFixTileSizeExplicit,
            sourceVersion = IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION
        )
        imageWidthText = normalizedImageGenerationDimensionText(
            rawValue = imageWidthText,
            defaultValue = model.imageDefaultWidth,
            minValue = maxOf(target.first, tile.tileSize, model.imageUltraFixMinWidth),
            maxValue = model.imageUltraFixMaxWidth,
            multiple = model.imageUltraFixWidthMultiple
        )
        imageHeightText = normalizedImageGenerationDimensionText(
            rawValue = imageHeightText,
            defaultValue = model.imageDefaultHeight,
            minValue = maxOf(target.second, tile.tileSize, model.imageUltraFixMinHeight),
            maxValue = model.imageUltraFixMaxHeight,
            multiple = model.imageUltraFixHeightMultiple
        )
    }
    val generationLoraPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri -> onImportImageLora(selectedUri.toString()) }
    }
    val generationTextualInversionPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            val stem = selectedUri.lastPathSegment.orEmpty()
                .substringAfterLast('/')
                .substringAfterLast(':')
                .substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_')
                .take(48)
                .ifBlank { "embedding" }
            onImportImageTextualInversion(selectedUri.toString(), "<$stem>")
        }
    }
    val generationUpscalerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri -> onImportImageUpscaler(selectedUri.toString()) }
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
    val streamingScrollBucket = state.messages.lastOrNull()?.let { message ->
        (message.content.length + message.reasoningContent.length) / STREAMING_SCROLL_CHAR_STEP
    } ?: 0
    val browserSearchCardVisible = state.browserTask?.let { task ->
        task.kind == BrowserTaskKind.SEARCH && !task.windowVisible && task.phase in setOf(
            BrowserTaskPhase.SEARCHING, BrowserTaskPhase.AWAITING_CONFIRMATION
        )
    } == true
    LaunchedEffect(state.messages.size, state.isGenerating, streamingScrollBucket,
        state.browserTask?.taskId, state.browserTask?.navigationId, browserSearchCardVisible) {
        if (browserSearchCardVisible) {
            listState.scrollToItem(state.messages.size + if (state.messages.isEmpty()) 1 else 0)
        } else if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
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
    val bottomChromeHeight = with(LocalDensity.current) { bottomChromeHeightPx.toDp() }
        .coerceAtLeast(120.dp)
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

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
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
                                onOpenQuickStart = { showQuickStart = true }
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
                                        onBrowserTaskEvent(BrowserTaskEvent(
                                            task.taskId, task.navigationId, BrowserTaskAction.CANCEL
                                        ))
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
                                        onBrowserTaskEvent(BrowserTaskEvent(
                                            task.taskId, task.navigationId,
                                            BrowserTaskAction.SELECT_RESULT, url = url,
                                            userInitiated = true
                                        ))
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Text(url, modifier = Modifier.fillMaxWidth(), maxLines = 2,
                                            overflow = TextOverflow.Ellipsis)
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
                var positive by rememberSaveable(draft.id) { mutableStateOf(draft.proposedPositive) }
                var negative by rememberSaveable(draft.id) {
                    mutableStateOf(draft.proposedNegative.orEmpty())
                }
                AlertDialog(
                    onDismissRequest = { onCancelImageTranslationDraft(draft.id) },
                    title = { Text("确认生图提示词") },
                    text = {
                        Column(
                            modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(draft.sourceLabel, style = MaterialTheme.typography.bodySmall)
                            Text("原始正向", style = MaterialTheme.typography.labelMedium)
                            SelectionContainer {
                                Text(draft.originalPositive, style = MaterialTheme.typography.bodyMedium)
                            }
                            draft.originalNegative?.let { original ->
                                Text("原始负向", style = MaterialTheme.typography.labelMedium)
                                SelectionContainer {
                                    Text(original, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            OutlinedTextField(
                                value = positive,
                                onValueChange = { positive = it },
                                label = { Text("使用的正向提示词") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 7
                            )
                            OutlinedTextField(
                                value = negative,
                                onValueChange = { negative = it },
                                label = { Text("使用的负向提示词") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 5
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onConfirmImageTranslationDraft(
                                    draft.id,
                                    positive,
                                    negative.takeIf { it.isNotEmpty() ||
                                        draft.originalNegative != null || draft.proposedNegative != null }
                                )
                            },
                            enabled = positive.isNotBlank()
                        ) { Text("使用提示词") }
                    },
                    dismissButton = {
                        TextButton(onClick = { onCancelImageTranslationDraft(draft.id) }) {
                            Text("取消")
                        }
                    }
                )
            }

            state.pendingImageActionDraft?.let { draft ->
                var editedText by rememberSaveable(draft.id) { mutableStateOf(draft.sourceText) }
                AlertDialog(
                    onDismissRequest = { onResolveImageActionDraft(draft.id, false, editedText) },
                    title = { Text(if (draft.kind == ChatImageActionKind.AMBIGUOUS) "确认本轮操作" else "确认云端整理") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(draft.reason, style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = editedText,
                                onValueChange = { editedText = it },
                                label = { Text("原始描述") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 6
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { onResolveImageActionDraft(draft.id, true, editedText) },
                            enabled = editedText.isNotBlank()
                        ) { Text(if (draft.kind == ChatImageActionKind.AMBIGUOUS) "生成图片" else "使用云端整理") }
                    },
                    dismissButton = {
                        TextButton(onClick = { onResolveImageActionDraft(draft.id, false, editedText) }) {
                            Text(if (draft.kind == ChatImageActionKind.AMBIGUOUS) "继续聊天" else "取消")
                        }
                    }
                )
            }

            SmoothRightToLeftPage(
                visible = showAssistants,
                onDismiss = { showAssistants = false }
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
                    onCreateKnowledgeBase = onCreateKnowledgeBase,
                    onImportKnowledgeDocument = onImportKnowledgeDocument,
                    onSetKnowledgeBaseSelected = onSetKnowledgeBaseSelected,
                    onDeleteKnowledgeBase = onDeleteKnowledgeBase,
                    onBack = closePage,
                    modifier = pageModifier
                )
            }

            SmoothRightToLeftPage(
                visible = showImages,
                onDismiss = { showImages = false }
            ) { pageModifier, closePage ->
                ImagesWorkspaceScreen(
                    onOpenModels = onOpenRecommendedModels,
                    onImportModel = onImportImageModel,
                    offlineTranslationStatus = offlineTranslationStatus,
                    offlineTranslationInstalled = offlineTranslationInstalled,
                    offlineTranslationInstalling = offlineTranslationInstalling,
                    offlineTranslationDownloading = offlineTranslationDownloading,
                    offlineTranslationDownloadProgress = offlineTranslationDownloadProgress,
                    onDownloadOfflineTranslation = onDownloadOfflineTranslation,
                    onCancelOfflineTranslationDownload = onCancelOfflineTranslationDownload,
                    onTranslateImagePrompt = onTranslateImagePrompt,
                    onTranslateImageNegativePrompt = onTranslateImageNegativePrompt,
                    statusMessage = imageValidationError ?: state.statusMessage,
                    onDismissStatusMessage = { imageValidationError = null; onDismissStatusMessage() },
                    images = state.images,
                    generationStartSignal = imageGenerationStartSignal,
                    backupState = state.imageLibraryBackup,
                    jobs = state.imageJobs,
                    imageModels = state.imageModels,
                    selectedImageModelId = state.selectedImageModelId,
                    selectedImageModelName = state.selectedImageModelName,
                    selectedImageModelIsCloud = state.selectedImageModelIsCloud,
                    supportedTaskModes = selectedImageTaskModes,
                    supportsNegativePrompt = supportsImageNegativePrompt,
                    supportsClipSkip = supportsImageClipSkip,
                    supportsVaeTiling = supportsImageVaeTiling,
                    supportsTextualInversion = supportsImageTextualInversion,
                    supportsUltraFix = supportsImageUltraFix,
                    supportsLora = supportsImageLora,
                    supportsLivePreview = supportsImageLivePreview,
                    loras = state.imageLoras,
                    selectedLoras = imageLoraDraftsFromJson(imageLoraDraftJson),
                    loraRestoreWarning = imageLoraRestoreWarning,
                    onAcceptLoraRestore = { imageLoraRestoreWarning = null },
                    loraImporting = state.imageLoraImporting,
                    loraMessage = state.imageLoraMessage,
                    textualInversions = imageTextualInversionsForSelectedModel,
                    selectedTextualInversionIds = imageTextualInversionIdsFromJson(
                        imageTextualInversionIdsJson
                    ),
                    textualInversionImporting = state.imageTextualInversionImporting,
                    textualInversionMessage = state.imageTextualInversionMessage,
                    upscalers = state.imageUpscalers,
                    selectedUpscalerId = state.selectedImageUpscalerId,
                    upscalerImporting = state.imageUpscalerImporting,
                    upscalerMessage = state.imageUpscalerMessage,
                    upscaleJob = state.imageUpscaleJob,
                    batchCount = if (imageUltraFixEnabled) 1 else imageBatchCount,
                    maxBatchCount = maxImageBatchCount,
                    taskMode = imageTaskMode,
                    inputImageUri = imageInputUri,
                    inputImageWidth = imageInputSourceWidth.takeIf {
                        !imageInputDimensionsProbing && imageInputDimensionsUri == imageInputUri
                    } ?: 0,
                    inputImageHeight = imageInputSourceHeight.takeIf {
                        !imageInputDimensionsProbing && imageInputDimensionsUri == imageInputUri
                    } ?: 0,
                    maskImageUri = imageMaskUri,
                    controlImageUri = imageControlUri,
                    inputRestoreWarning = imageInputRestoreWarning,
                    strengthText = imageStrengthText,
                    controlStrengthText = imageControlStrengthText,
                    negativePrompt = imageNegativePrompt,
                    disableModelNegativePrompt = imageDisableModelNegativePrompt,
                    clipSkipText = imageClipSkipText,
                    // UltraFix forces these values only for its request. The ordinary controls
                    // remain untouched and are restored verbatim when UltraFix is disabled.
                    vaeTilingEnabled = imageVaeTilingEnabled || imageUltraFixEnabled,
                    ultraFixEnabled = imageUltraFixEnabled,
                    ultraFixStrengthText = imageUltraFixStrengthText,
                    ultraFixInversionStepsText = imageUltraFixInversionStepsText,
                    ultraFixRefinementStepsText = imageUltraFixRefinementStepsText,
                    ultraFixTileSizeText = imageUltraFixTileSizeText,
                    ultraFixOverlapText = imageUltraFixOverlapText,
                    livePreviewEnabled = imageLivePreviewEnabled && !imageUltraFixEnabled,
                    livePreviewInterval = imageLivePreviewInterval,
                    widthText = imageWidthText,
                    heightText = imageHeightText,
                    stepsText = imageStepsText,
                    cfgScaleText = imageCfgScaleText,
                    seedText = imageSeedText,
                     sampler = imageSampler,
                     prompt = imagePrompt,
                     onMeasureImagePromptTokens = onMeasureImagePromptTokens,
                     onPromptChange = { imagePrompt = it },
                    onSubmitPrompt = { enqueueImagePrompt(imagePrompt) },
                    onCancelGeneration = onCancelImageGeneration,
                    onRetryJob = { job -> onRetryImageGeneration(job.id) },
                    onRecreateImageAsset = onRecreateImageAsset,
                    onBack = closePage,
                    onTaskModeChange = { mode ->
                        imageInputRestoreWarning = null
                        imageTaskModeName = mode.name
                        when (mode) {
                            ImageGenerationUiTaskMode.TEXT_TO_IMAGE -> {
                                imageInputUri = null
                                imageMaskUri = null
                                imageControlUri = null
                            }
                            ImageGenerationUiTaskMode.IMG2IMG,
                            ImageGenerationUiTaskMode.EDIT -> {
                                imageMaskUri = null
                                imageControlUri = null
                            }
                            ImageGenerationUiTaskMode.INPAINT -> imageControlUri = null
                            ImageGenerationUiTaskMode.CONTROL -> {
                                imageInputUri = null
                                imageMaskUri = null
                            }
                        }
                    },
                    onPickImageRole = { role ->
                        pendingGenerationImageRole = role
                        generationPhotoPicker.launch(arrayOf("image/*"))
                    },
                    onCropImageRole = { role ->
                        val source = if (role == "control") imageControlUri else imageInputUri
                        if (source != null) {
                            val model = selectedImageModelChoice
                            val targetWidth = model?.let {
                                normalizedImageGenerationDimensionText(
                                    rawValue = imageWidthText,
                                    defaultValue = it.imageDefaultWidth,
                                    minValue = if (imageUltraFixEnabled) {
                                        it.imageUltraFixMinWidth
                                    } else {
                                        it.imageMinWidth
                                    },
                                    maxValue = if (imageUltraFixEnabled) {
                                        it.imageUltraFixMaxWidth
                                    } else {
                                        it.imageMaxWidth
                                    },
                                    multiple = if (imageUltraFixEnabled) {
                                        it.imageUltraFixWidthMultiple
                                    } else {
                                        it.imageWidthMultiple
                                    }
                                ).toInt()
                            } ?: 512
                            val targetHeight = model?.let {
                                normalizedImageGenerationDimensionText(
                                    rawValue = imageHeightText,
                                    defaultValue = it.imageDefaultHeight,
                                    minValue = if (imageUltraFixEnabled) {
                                        it.imageUltraFixMinHeight
                                    } else {
                                        it.imageMinHeight
                                    },
                                    maxValue = if (imageUltraFixEnabled) {
                                        it.imageUltraFixMaxHeight
                                    } else {
                                        it.imageMaxHeight
                                    },
                                    multiple = if (imageUltraFixEnabled) {
                                        it.imageUltraFixHeightMultiple
                                    } else {
                                        it.imageHeightMultiple
                                    }
                                ).toInt()
                            } ?: 512
                            generationImageEditorRequest = GenerationImageEditorRequest.Crop(
                                sourceUri = source,
                                role = role,
                                targetWidth = targetWidth,
                                targetHeight = targetHeight
                            )
                        }
                    },
                    onDrawImageMask = {
                        imageInputUri?.let { source ->
                            generationImageEditorRequest = GenerationImageEditorRequest.Mask(source)
                        }
                    },
                    onClearImageRole = { role ->
                        imageInputRestoreWarning = null
                        when (role) {
                            "mask" -> imageMaskUri = null
                            "control" -> imageControlUri = null
                            else -> {
                                imageInputUri = null
                                if (imageUltraFixEnabled) {
                                    imageUltraFixTargetWidthText = imageWidthText
                                    imageUltraFixTargetHeightText = imageHeightText
                                    imageWidthText = imageNormalWidthText
                                    imageHeightText = imageNormalHeightText
                                    imageUltraFixEnabled = false
                                }
                            }
                        }
                    },
                    onStrengthTextChange = { imageStrengthText = it },
                    onControlStrengthTextChange = { imageControlStrengthText = it },
                    onNegativePromptChange = { imageNegativePrompt = it },
                    onDisableModelNegativePromptChange = { imageDisableModelNegativePrompt = it },
                    onClipSkipTextChange = { imageClipSkipText = it },
                    onVaeTilingEnabledChange = { imageVaeTilingEnabled = it },
                    onLivePreviewEnabledChange = { imageLivePreviewEnabled = it },
                    onLivePreviewIntervalChange = {
                        imageLivePreviewInterval = it.coerceIn(1, 10)
                        imageLivePreviewIntervalExplicit = true
                    },
                    onToggleLora = { id ->
                        val current = imageLoraDraftsFromJson(imageLoraDraftJson)
                        val alreadySelected = current.any { it.id == id }
                        if (!alreadySelected && current.size >= 8) {
                            Toast.makeText(context, "单次最多选择 8 个 LoRA", Toast.LENGTH_SHORT).show()
                        } else {
                            val next = if (alreadySelected) {
                                current.filterNot { it.id == id }
                            } else {
                                current + ImageGenerationUiLoraDraft(id, "1.0")
                            }
                            imageLoraDraftJson = imageLoraDraftsToJson(next)
                            imageLoraRestoreWarning = null
                        }
                    },
                    onLoraMultiplierChange = { id, value ->
                        val current = imageLoraDraftsFromJson(imageLoraDraftJson)
                        imageLoraDraftJson = imageLoraDraftsToJson(
                            current.map { draft ->
                                if (draft.id == id) draft.copy(multiplierText = value.take(32)) else draft
                            }
                        )
                    },
                    onImportLora = { generationLoraPicker.launch(arrayOf("*/*")) },
                    onDeleteLora = onDeleteImageLora,
                    onToggleTextualInversion = onToggleTextualInversion@ { id ->
                        val artifact = state.imageTextualInversions.firstOrNull { it.id == id }
                        if (artifact == null) {
                            Toast.makeText(
                                context,
                                "该 Textual Inversion 已不存在",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@onToggleTextualInversion
                        }
                        val current = imageTextualInversionIdsFromJson(
                            imageTextualInversionIdsJson
                        )
                        if (id !in current &&
                            !artifact.isSupportedBy(supportedImageTextualInversionFormats)
                        ) {
                            Toast.makeText(
                                context,
                                "当前模型不支持 ${artifact.format} 格式的 Textual Inversion",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@onToggleTextualInversion
                        }
                        val next = if (id in current) {
                            imagePrompt = imagePromptWithoutTextualInversionTrigger(
                                imagePrompt,
                                artifact.trigger
                            )
                            imageNegativePrompt = imagePromptWithoutTextualInversionTrigger(
                                imageNegativePrompt,
                                artifact.trigger
                            )
                            imageTextualInversionTriggerById.remove(id)
                            current - id
                        } else if (current.size < 8) {
                            imagePrompt = imagePromptWithTextualInversionTrigger(
                                imagePrompt,
                                artifact.trigger
                            )
                            imageTextualInversionTriggerById[id] = artifact.trigger
                            current + id
                        } else {
                            Toast.makeText(
                                context,
                                "单次最多选择 8 个 Textual Inversion",
                                Toast.LENGTH_SHORT
                            ).show()
                            current
                        }
                        imageTextualInversionIdsJson = imageTextualInversionIdsToJson(next)
                    },
                    onImportTextualInversion = {
                        generationTextualInversionPicker.launch(
                            arrayOf("application/octet-stream", "application/zip", "*/*")
                        )
                    },
                    onDeleteTextualInversion = { id ->
                        val current = imageTextualInversionIdsFromJson(
                            imageTextualInversionIdsJson
                        )
                        if (id in current) {
                            val trigger = imageTextualInversionTriggerById[id]
                                ?: state.imageTextualInversions.firstOrNull { it.id == id }?.trigger
                            if (trigger != null) {
                                imagePrompt = imagePromptWithoutTextualInversionTrigger(
                                    imagePrompt,
                                    trigger
                                )
                                imageNegativePrompt = imagePromptWithoutTextualInversionTrigger(
                                    imageNegativePrompt,
                                    trigger
                                )
                            }
                            imageTextualInversionIdsJson = imageTextualInversionIdsToJson(current - id)
                            imageTextualInversionTriggerById.remove(id)
                        }
                        onDeleteImageTextualInversion(id)
                    },
                    onUltraFixEnabledChange = onUltraFixEnabledChange@ { enabled ->
                        if (!enabled) {
                            imageUltraFixTargetWidthText = imageWidthText
                            imageUltraFixTargetHeightText = imageHeightText
                            imageWidthText = imageNormalWidthText
                            imageHeightText = imageNormalHeightText
                            imageUltraFixEnabled = false
                            return@onUltraFixEnabledChange true
                        }
                        val model = selectedImageModelChoice
                            ?.takeIf(ChatModelChoice::supportsImageUltraFix)
                        if (imageInputUri == null) {
                            Toast.makeText(context, "请先选择 UltraFix 源图", Toast.LENGTH_SHORT).show()
                            return@onUltraFixEnabledChange false
                        }
                        if (imageInputDimensionsProbing) {
                            Toast.makeText(context, "正在检查源图尺寸，请稍后重试", Toast.LENGTH_SHORT).show()
                            return@onUltraFixEnabledChange false
                        }
                        if (imageInputDimensionsUri != imageInputUri ||
                            imageInputSourceWidth <= 0 || imageInputSourceHeight <= 0
                        ) {
                            Toast.makeText(
                                context,
                                if (imageInputDimensionsProbeFailed) {
                                    "无法安全读取源图尺寸，请重新选择或先裁剪图片"
                                } else {
                                    "请等待源图尺寸检查完成"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                            return@onUltraFixEnabledChange false
                        }
                        val sourceTarget = model?.ultraFixTargetSizeForSourceOrNull(
                            imageInputSourceWidth,
                            imageInputSourceHeight
                        )
                        if (model == null || sourceTarget == null) {
                            Toast.makeText(
                                context,
                                "源图尺寸超出当前模型的 UltraFix 目标范围，请先裁剪图片",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@onUltraFixEnabledChange false
                        }
                        if (!imageUltraFixEnabled) {
                            imageNormalWidthText = imageWidthText
                            imageNormalHeightText = imageHeightText
                        }
                        imageUltraFixEnabled = true
                        run {
                            imageTaskModeName = ImageGenerationUiTaskMode.IMG2IMG.name
                            val refinementSteps = imageUltraFixRefinementStepsText.toIntOrNull()
                                ?.coerceIn(1, IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS)
                                ?: 10
                            val denoisingSteps = imageUltraFixInversionStepsText.toIntOrNull()
                                ?.coerceIn(
                                    1,
                                    minOf(
                                        IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                                        refinementSteps,
                                    ),
                                )
                                ?: minOf(4, refinementSteps)
                            imageUltraFixRefinementStepsText = refinementSteps.toString()
                            imageUltraFixInversionStepsText = denoisingSteps.toString()
                            imageUltraFixStrengthText =
                                imageGenerationUltraFixStrengthForDenoisingSteps(
                                    refinementSteps,
                                    denoisingSteps,
                                ).toString()
                            model.let {
                                    val selection = normalizedImageGenerationUltraFixTileSelection(
                                        model = model,
                                        rawValue = imageUltraFixTileSizeText,
                                        explicit = imageUltraFixTileSizeExplicit,
                                        sourceVersion = IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION,
                                    )
                                    imageUltraFixTileSizeText = selection.tileSize.toString()
                                    imageUltraFixTileSizeExplicit = selection.explicit
                                    imageWidthText = normalizedImageGenerationDimensionText(
                                        rawValue = imageUltraFixTargetWidthText,
                                        defaultValue = model.imageDefaultWidth,
                                        minValue = maxOf(
                                            sourceTarget.first,
                                            model.imageUltraFixMinWidth,
                                            selection.tileSize,
                                        ),
                                        maxValue = model.imageUltraFixMaxWidth,
                                        multiple = model.imageUltraFixWidthMultiple,
                                    )
                                    imageHeightText = normalizedImageGenerationDimensionText(
                                        rawValue = imageUltraFixTargetHeightText,
                                        defaultValue = model.imageDefaultHeight,
                                        minValue = maxOf(
                                            sourceTarget.second,
                                            model.imageUltraFixMinHeight,
                                            selection.tileSize,
                                        ),
                                        maxValue = model.imageUltraFixMaxHeight,
                                        multiple = model.imageUltraFixHeightMultiple,
                                    )
                                    imageUltraFixTargetWidthText = imageWidthText
                                    imageUltraFixTargetHeightText = imageHeightText
                                }
                        }
                        true
                    },
                     onResetUltraFix = {
                         val model = selectedImageModelChoice
                         imageUltraFixRefinementStepsText = "10"
                         imageUltraFixInversionStepsText = "4"
                         imageUltraFixStrengthText =
                             imageGenerationUltraFixStrengthForDenoisingSteps(10, 4).toString()
                         imageUltraFixTileSizeText = model
                             ?.resolvedImageUltraFixDefaultTileSize()
                             ?.toString()
                             ?: "512"
                         imageUltraFixTileSizeExplicit = false
                         imageUltraFixOverlapText = "0.25"
                         model?.let { selected ->
                             val tile = selected.resolvedImageUltraFixDefaultTileSize()
                             val sourceTarget = selected.ultraFixTargetSizeForSourceOrNull(
                                 imageInputSourceWidth,
                                 imageInputSourceHeight
                             )
                             imageWidthText = normalizedImageGenerationDimensionText(
                                 rawValue = imageUltraFixTargetWidthText,
                                 defaultValue = selected.imageDefaultWidth,
                                 minValue = maxOf(
                                     sourceTarget?.first ?: 0,
                                     selected.imageUltraFixMinWidth,
                                     tile
                                 ),
                                 maxValue = selected.imageUltraFixMaxWidth,
                                 multiple = selected.imageUltraFixWidthMultiple,
                             )
                             imageHeightText = normalizedImageGenerationDimensionText(
                                 rawValue = imageUltraFixTargetHeightText,
                                 defaultValue = selected.imageDefaultHeight,
                                 minValue = maxOf(
                                     sourceTarget?.second ?: 0,
                                     selected.imageUltraFixMinHeight,
                                     tile
                                 ),
                                 maxValue = selected.imageUltraFixMaxHeight,
                                 multiple = selected.imageUltraFixHeightMultiple,
                             )
                             imageUltraFixTargetWidthText = imageWidthText
                             imageUltraFixTargetHeightText = imageHeightText
                         }
                     },
                     onUltraFixDenoisingStepsTextChange = { value ->
                        imageUltraFixInversionStepsText = value.take(3)
                        val denoisingSteps = value.toIntOrNull()
                        val refinementSteps = imageUltraFixRefinementStepsText.toIntOrNull()
                        if (denoisingSteps != null && refinementSteps != null &&
                            refinementSteps in 1..IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS &&
                            denoisingSteps in 1..minOf(
                                IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                                refinementSteps,
                            )
                        ) {
                            imageUltraFixStrengthText =
                                imageGenerationUltraFixStrengthForDenoisingSteps(
                                    refinementSteps,
                                    denoisingSteps,
                                ).toString()
                        }
                    },
                    onUltraFixRefinementStepsTextChange = { value ->
                        imageUltraFixRefinementStepsText = value.take(2)
                        val refinementSteps = value.toIntOrNull()
                        if (refinementSteps != null &&
                            refinementSteps in 1..IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS
                        ) {
                            val denoisingSteps = imageUltraFixInversionStepsText.toIntOrNull()
                                ?.coerceIn(
                                    1,
                                    minOf(
                                        IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                                        refinementSteps,
                                    ),
                                )
                                ?: minOf(4, refinementSteps)
                            imageUltraFixInversionStepsText = denoisingSteps.toString()
                            imageUltraFixStrengthText =
                                imageGenerationUltraFixStrengthForDenoisingSteps(
                                    refinementSteps,
                                    denoisingSteps,
                                ).toString()
                        }
                    },
                    onUltraFixTileSizeTextChange = { value ->
                        val required = selectedImageModelChoice
                            ?.imageUltraFixRequiredTileSize
                            ?.takeIf { it > 0 }
                        imageUltraFixTileSizeText = required?.toString() ?: value.take(4)
                        imageUltraFixTileSizeExplicit = required == null
                    },
                    onUltraFixOverlapTextChange = { imageUltraFixOverlapText = it.take(16) },
                    onImportUpscaler = { generationUpscalerPicker.launch(arrayOf("*/*")) },
                    onDeleteUpscaler = onDeleteImageUpscaler,
                    onSelectUpscaler = onSelectImageUpscaler,
                    onUpscaleImage = onUpscaleImageAsset,
                    onCancelUpscale = onCancelImageUpscale,
                    onBatchCountChange = { imageBatchCount = it.coerceIn(1, maxImageBatchCount) },
                    onWidthTextChange = { value ->
                        imageWidthText = value
                        if (imageUltraFixEnabled) {
                            imageUltraFixTargetWidthText = value
                        } else {
                            imageNormalWidthText = value
                        }
                    },
                    onHeightTextChange = { value ->
                        imageHeightText = value
                        if (imageUltraFixEnabled) {
                            imageUltraFixTargetHeightText = value
                        } else {
                            imageNormalHeightText = value
                        }
                    },
                    onStepsTextChange = {
                        imageStepsText = it
                        imageViggleAutoSteps = false
                        imageStepsEditedByUser = true
                    },
                    onCfgScaleTextChange = {
                        imageCfgScaleText = it
                        imageViggleAutoCfg = false
                        imageCfgEditedByUser = true
                    },
                    onSeedTextChange = { imageSeedText = it },
                    onSamplerChange = { imageSampler = it },
                    onSelectImageModel = onSelectImageModel,
                    onUseImageAsGenerationInput = { uri, width, height ->
                        imageInputDimensionsUri = uri
                        imageInputSourceWidth = width
                        imageInputSourceHeight = height
                        // Library assets already carry trusted decoded dimensions. Clear any
                        // probe state from the previous source before a same-frame UltraFix
                        // action evaluates readiness; the URI-keyed probe will still revalidate.
                        imageInputDimensionsProbing = false
                        imageInputDimensionsProbeFailed = false
                        imageInputRestoreWarning = null
                        imageInputUri = uri
                        imageMaskUri = null
                        imageControlUri = null
                    },
                    onUseImageAsset = {
                        onUseImageAsset(it)
                        closePage()
                    },
                    onDeleteImageAsset = onDeleteImageAsset,
                    onDeleteImageAssets = onDeleteImageAssets,
                    onSetImageAssetFavorite = onSetImageAssetFavorite,
                    onExportBackup = onExportImageLibraryBackup,
                    onImportBackup = onImportImageLibraryBackup,
                    onCancelBackup = onCancelImageLibraryBackup,
                    modifier = pageModifier
                )
            }

            generationImageEditorRequest?.let { editorRequest ->
                SmoothRightToLeftPage(
                    visible = true,
                    onDismiss = { generationImageEditorRequest = null }
                ) { pageModifier, closePage ->
                    GenerationImageEditorScreen(
                        request = editorRequest,
                        onBack = closePage,
                        onCropConfirmed = { role, ownedUri ->
                            imageInputRestoreWarning = null
                            if (role == "control") {
                                imageControlUri = ownedUri
                            } else {
                                imageInputUri = ownedUri
                                imageMaskUri = null
                            }
                            closePage()
                        },
                        onMaskConfirmed = { ownedUris ->
                            imageInputRestoreWarning = null
                            imageInputUri = ownedUris.inputUri
                            imageMaskUri = ownedUris.maskUri
                            closePage()
                        },
                        modifier = pageModifier
                    )
                }
            }

            SmoothRightToLeftPage(
                visible = showFileLibrary,
                onDismiss = { showFileLibrary = false }
            ) { pageModifier, closePage ->
                FileLibraryPage(
                    files = state.files,
                    onInsert = { id ->
                        onUseFileAsset(id)
                        closePage()
                    },
                    onDelete = onDeleteFileAsset,
                    onBack = closePage,
                    modifier = pageModifier
                )
            }

            SmoothRightToLeftPage(
                visible = showBackgroundSettings,
                onDismiss = { showBackgroundSettings = false; onAppMenuOpenChange(true) }
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
                    onChooseImage = { scope ->
                        pendingBackgroundScope = scope
                        chatBackgroundPicker.launch(arrayOf("image/*"))
                    },
                    onChange = onChatBackgroundChange,
                    onReset = { scope -> onChatBackgroundChange(scope, null) }
                )
            }

            PredictiveAppMenuPage(
                visible = appMenuOpen,
                onDismiss = { onAppMenuOpenChange(false) }
            ) { menuModifier, closeMenu ->
                McaAppMenuPage(
                    onOpenQuickStart = { closeMenu(); showQuickStart = true },
                    state = state,
                    onClose = closeMenu,
                    onOpenModels = {
                        onOpenModels()
                    },
                    onOpenAgent = {
                        onOpenAgent()
                    },
                    onOpenApi = {
                        onOpenApi()
                    },
                    onOpenSettings = {
                        onOpenSettings()
                    },
                    onRequestContextCompression = onRequestContextCompression,
                    onUndoContextSummary = onUndoContextSummary,
                    onSearchContextSummarySources = onSearchContextSummarySources,
                    onSetContextCompressionThreshold = onSetContextCompressionThreshold,
                    onSetContextSummaryModel = onSetContextSummaryModel,
                    onOpenImages = {
                        closeMenu()
                        showImages = true
                    },
                    onOpenBackgroundSettings = { onAppMenuOpenChange(false); showBackgroundSettings = true },
                    onOpenAssistants = {
                        closeMenu()
                        showAssistants = true
                    },
                    onClearHistory = onClearHistory,
                    modifier = menuModifier
                )
            }

            pendingUltraFixSubmission?.let { (prompt, options) ->
                val ultraFix = requireNotNull(options.ultraFix)
                AlertDialog(
                    onDismissRequest = { pendingUltraFixSubmission = null },
                    title = { Text("开始 UltraFix 精修？") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "将以 ${ultraFix.targetWidth}×${ultraFix.targetHeight} 分块处理整张图片，" +
                                    "通常明显慢于普通生成并会增加设备发热。"
                            )
                            Text(
                                "当前去噪步数 ${ultraFix.inversionSteps} / 总步数 " +
                                    "${ultraFix.refinementSteps}。建议该比例不超过 0.5。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingUltraFixSubmission = null
                                if (onGenerateImagePrompt(prompt, options)) {
                                    imageGenerationStartSignal++
                                }
                            }
                        ) { Text("开始精修") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingUltraFixSubmission = null }) {
                            Text("取消")
                        }
                    }
                )
            }

        }
    }
    if (showNewConversationPicker) {
        NewConversationAssistantDialog(
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
}
