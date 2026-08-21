package com.muyuchat.feature.chat

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private const val CLOUD_REASONING_LOCKED_TIP = "云端思考由模型服务商控制，MCA 已默认启用，暂不支持切换。"
private const val MAX_ASSISTANT_SYSTEM_PROMPT_CHARS = 12_000
private const val CHAT_BACKGROUND_MAX_DECODE_EDGE = 2048
private val McaPrimaryBlue = Color(0xFF3F7DE8)
private val McaInputShell = Color(0xFFFEFEFF)
private val McaInputField = Color(0xFFF3F7FF)
private val McaInputIconSurface = Color(0xFFEAF1FF)
private val McaInputText = Color(0xFF1F2937)
private val McaInputPlaceholder = Color(0xFF8A93A3)

data class ChatUiState(
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
    val input: String = "",
    val isGenerating: Boolean = false,
    val generationPhase: GenerationPhase? = null,
    val generationTokenProgress: TokenProgress? = null,
    val generationPersistProgress: PersistProgress? = null,
    val generationStats: RuntimeStats? = null,
    val promptContextUsage: PromptContextUsage? = null,
    val selectedModelId: String? = null,
    val selectedModelName: String? = null,
    val selectedModelIsCloud: Boolean = false,
    val selectedModelRuntimeLabel: String? = null,
    val selectedImageModelId: String? = null,
    val selectedImageModelName: String? = null,
    val selectedImageModelIsCloud: Boolean = false,
    val stats: RuntimeStats = RuntimeStats(),
    val apiEnabled: Boolean = false,
    val restEnabled: Boolean = false,
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
    val inUse: Boolean = false
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

internal val IMAGE_TEXTUAL_INVERSION_ALL_FORMATS: Set<String> = setOf(
    "safetensors",
    "pytorch",
    "checkpoint",
    "binary"
)

private fun ImageTextualInversionUiItem.isSupportedBy(formats: Set<String>): Boolean =
    format.trim().lowercase() in formats

data class ImageUpscalerUiItem(
    val id: String,
    val name: String,
    val sizeText: String,
    val sha256: String,
    val selected: Boolean = false,
    val inUse: Boolean = false,
    val deleting: Boolean = false
)

data class ImageUpscaleUiJob(
    val id: String,
    val sourceImageId: String,
    val upscalerId: String,
    val upscalerName: String,
    val targetScale: Int,
    val statusLabel: String,
    val running: Boolean,
    val failed: Boolean,
    val terminal: Boolean,
    val resultImageAssetId: String? = null,
    val message: String = ""
)

internal val IMAGE_UPSCALE_TARGET_SCALES: List<Int> = listOf(2, 3, 4)

internal fun upscaleOutputDimensionsOrNull(width: Int, height: Int, scale: Int): Pair<Int, Int>? {
    if (width !in 1..2_048 || height !in 1..2_048 ||
        scale !in IMAGE_UPSCALE_TARGET_SCALES
    ) return null
    return runCatching {
        val sourcePixels = Math.multiplyExact(width.toLong(), height.toLong())
        val outputWidth = Math.multiplyExact(width, scale)
        val outputHeight = Math.multiplyExact(height, scale)
        val outputPixels = Math.multiplyExact(outputWidth.toLong(), outputHeight.toLong())
        (outputWidth to outputHeight).takeIf {
            sourcePixels <= 4_000_000L &&
                outputWidth <= 4_096 && outputHeight <= 4_096 &&
                outputPixels <= 16_000_000L
        }
    }.getOrNull()
}

data class ImageGenerationUiLoraSelection(
    val id: String,
    val multiplier: Double
)

internal data class ImageGenerationUiLoraDraft(
    val id: String,
    val multiplierText: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("multiplierText", multiplierText)

    companion object {
        fun fromJson(json: JSONObject): ImageGenerationUiLoraDraft =
            ImageGenerationUiLoraDraft(
                id = json.getString("id").trim().also { require(it.isNotEmpty()) },
                multiplierText = json.getString("multiplierText").trim().take(32)
            )
    }
}

data class AssistantUiItem(
    val id: String,
    val name: String,
    val avatar: String,
    val tag: String,
    val systemPrompt: String,
    val modelSummary: String,
    val defaultModelMode: String,
    val defaultModelId: String?,
    val temperature: Float,
    val topP: Float,
    val nPredict: Int,
    val reasoningMode: ReasoningMode,
    val memoryEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val fileContextEnabled: Boolean,
    val selected: Boolean,
    val exportJson: String
)

data class AssistantEditorDraft(
    val id: String?,
    val name: String,
    val avatar: String,
    val tag: String,
    val systemPrompt: String,
    val defaultModelMode: String,
    val defaultModelId: String?,
    val temperature: Float,
    val topP: Float,
    val nPredict: Int,
    val reasoningMode: ReasoningMode,
    val memoryEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val fileContextEnabled: Boolean
)

data class ImageAssetUiItem(
    val id: String,
    val name: String,
    val uriString: String,
    val source: String,
    val prompt: String,
    val createdAtText: String,
    val sizeText: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long = 0L,
    val upscaleTargetScale: Int? = null,
    val generationDetails: String = "",
    val generationPrompt: String = "",
    val generationModelId: String = "",
    val generationModelName: String = "",
    val generationTaskMode: String = "",
    /** Product history operation; unlike task mode this can distinguish UltraFix from img2img. */
    val generationOperation: String = "",
    val generationSampler: String = "",
    val parameterShareJson: String = "",
    val generationPreset: ImageGenerationUiPreset? = null,
    val favorite: Boolean = false,
    val canRecreate: Boolean = false,
    val createdAtMillis: Long = 0L,
    val generationRuntime: String = "",
    val generationDevice: String = ""
)

data class WorldBookUiItem(
    val id: String,
    val name: String,
    val entryCount: Int,
    val scopeLabel: String
)

/** Scope selected before the host launches a world-book document picker. */
enum class WorldBookImportScope(val label: String) {
    GLOBAL("全局"),
    ASSISTANT("当前角色"),
    CHAT("当前对话")
}

data class KnowledgeBaseUiItem(
    val id: String,
    val name: String,
    val description: String,
    val selected: Boolean,
    val indexStateLabel: String,
    val documentCount: Int = 0,
    val importing: Boolean = false
)

internal fun imageAssetBadgeText(image: ImageAssetUiItem): String = when {
    image.source.startsWith("upscaled:", ignoreCase = true) ->
        image.upscaleTargetScale
            ?.takeIf(IMAGE_UPSCALE_TARGET_SCALES::contains)
            ?.let { scale -> "ESRGAN ${scale}×" }
            ?: "高清放大"
    image.width > 0 && image.height > 0 -> "${image.width}×${image.height}"
    else -> ""
}

data class ImageGenerationUiPreset(
    val prompt: String,
    val taskMode: ImageGenerationUiTaskMode? = null,
    val negativePrompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfgScale: Double? = null,
    val seed: Int? = null,
    val sampleMethod: String? = null,
    val clipSkip: Int? = null,
    val batchCount: Int? = null,
    val vaeTileSize: Int? = null,
    val vaeTileOverlap: Double? = null,
    val loras: List<ImageGenerationUiLoraSelection> = emptyList(),
    val textualInversionIds: List<String> = emptyList(),
    val ultraFix: ImageGenerationUiUltraFixOptions? = null,
    val strength: Double? = null,
    val controlStrength: Double? = null,
)

internal enum class ImageGenerationPresetField(val label: String) {
    PROMPT("提示词"),
    NEGATIVE_PROMPT("负向提示词"),
    SIZE("尺寸"),
    STEPS("步数"),
    CFG("CFG"),
    SEED("Seed"),
    SAMPLER("采样器"),
    CLIP_SKIP("CLIP skip"),
    LORA("LoRA"),
    TEXTUAL_INVERSION("Textual Inversion"),
    ULTRAFIX("UltraFix"),
    STRENGTH("重绘强度"),
    CONTROL_STRENGTH("控制强度"),
    BATCH("批次数量"),
    VAE_TILING("VAE 分块")
}

data class FileAssetUiItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val preview: String,
    val createdAtText: String,
    val sizeText: String,
    val truncated: Boolean
)

data class ImageGenerationUiJob(
    val id: String,
    val prompt: String,
    val statusLabel: String,
    val modelId: String? = null,
    val modelName: String = "",
    val modelIsCloud: Boolean = false,
    val imageAssetId: String? = null,
    val previewUriString: String? = null,
    val previewMode: String = "",
    val previewStep: Int = 0,
    val previewRevision: Long = 0L,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val failed: Boolean = false,
    val terminal: Boolean = false,
    val message: String = "",
    val startedAtMillis: Long = System.currentTimeMillis()
)

internal enum class ImageAssistantCardKind {
    FAILURE,
    RESULT,
    TERMINAL,
    CREATING
}

internal fun imageAssistantCardKind(
    job: ImageGenerationUiJob?,
    image: ImageAssetUiItem?
): ImageAssistantCardKind = when {
    job?.failed == true -> ImageAssistantCardKind.FAILURE
    image != null -> ImageAssistantCardKind.RESULT
    job?.terminal == true -> ImageAssistantCardKind.TERMINAL
    else -> ImageAssistantCardKind.CREATING
}

enum class ImageGenerationUiTaskMode(val wireName: String, val label: String) {
    TEXT_TO_IMAGE("text_to_image", "文生图"),
    IMG2IMG("img2img", "图生图"),
    INPAINT("inpaint", "局部重绘"),
    CONTROL("control", "Control"),
    EDIT("edit", "编辑")
}

enum class ImageGenerationUiPreviewMode(val wireName: String) {
    PROJECTION("projection"),
    VAE("vae");

    companion object {
        fun fromWireNameOrNull(value: String?): ImageGenerationUiPreviewMode? {
            val normalized = value.orEmpty().trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

data class ImageGenerationUiOptions(
    val taskMode: ImageGenerationUiTaskMode = ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
    val negativePrompt: String? = null,
    val inputImageUri: String? = null,
    val maskImageUri: String? = null,
    val controlImageUri: String? = null,
    val strength: Double? = null,
    val controlStrength: Double? = null,
    val clipSkip: Int? = null,
    val batchCount: Int = 1,
    val loras: List<ImageGenerationUiLoraSelection> = emptyList(),
    val vaeTileSize: Int? = null,
    val vaeTileOverlap: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfgScale: Double? = null,
    val seed: Int? = null,
    val sampleMethod: String? = null,
    val previewMode: ImageGenerationUiPreviewMode? = null,
    val previewInterval: Int? = null,
    val textualInversionIds: List<String> = emptyList(),
    val ultraFix: ImageGenerationUiUltraFixOptions? = null,
)

data class ImageGenerationUiUltraFixOptions(
    val targetWidth: Int,
    val targetHeight: Int,
    val strength: Double,
    val inversionSteps: Int,
    val refinementSteps: Int,
    val tileSize: Int,
    val overlap: Double,
)

/** Uses the same Float strength that crosses JNI into stable-diffusion.cpp. */
internal fun imageGenerationUltraFixDenoisingTailStepCount(
    refinementSteps: Int,
    strength: Double,
): Int {
    require(refinementSteps > 0)
    val wireStrength = strength.toFloat()
    require(wireStrength.isFinite() && wireStrength in 0.0f..1.0f)
    val beginIndex = (refinementSteps.toFloat() * (1.0f - wireStrength))
        .toInt()
        .coerceIn(0, refinementSteps - 1)
    return refinementSteps - beginIndex
}

/**
 * Local Dream exposes the effective denoising-step count instead of a raw strength. Choosing the
 * midpoint of the scheduler's integer interval keeps the Float value crossing JNI away from either
 * boundary, so the native begin index deterministically yields [denoisingSteps].
 */
internal fun imageGenerationUltraFixStrengthForDenoisingSteps(
    refinementSteps: Int,
    denoisingSteps: Int,
): Double {
    require(refinementSteps > 0)
    require(denoisingSteps in 1..refinementSteps)
    val strength = (denoisingSteps.toDouble() - 0.5) / refinementSteps.toDouble()
    check(imageGenerationUltraFixDenoisingTailStepCount(refinementSteps, strength) == denoisingSteps)
    return strength
}

internal const val IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS = 20
internal const val IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS = 10
private const val IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION = 9

internal data class ImageGenerationUiParameterSnapshot(
    val taskModeName: String,
    val strengthText: String,
    val controlStrengthText: String,
    val negativePrompt: String,
    val disableModelNegativePrompt: Boolean,
    val clipSkipText: String,
    val vaeTilingEnabled: Boolean,
    val batchCount: Int,
    val widthText: String,
    val heightText: String,
    val stepsText: String,
    val cfgScaleText: String,
    val seedText: String,
    val sampler: String,
    val loras: List<ImageGenerationUiLoraDraft> = emptyList(),
    val inputImageUri: String? = null,
    val maskImageUri: String? = null,
    val controlImageUri: String? = null,
    val livePreviewMode: ImageGenerationUiPreviewMode? = null,
    val livePreviewEnabled: Boolean = true,
    val livePreviewInterval: Int = 1,
    val livePreviewIntervalExplicit: Boolean = false,
    val textualInversionIds: List<String> = emptyList(),
    val ultraFixEnabled: Boolean = false,
    val ultraFixStrengthText: String = "0.35",
    val ultraFixInversionStepsText: String = "4",
    val ultraFixRefinementStepsText: String = "10",
    val ultraFixTileSizeText: String = "512",
    val ultraFixTileSizeExplicit: Boolean = false,
    val ultraFixOverlapText: String = "0.25",
    /** UltraFix target dimensions are transient execution dimensions, separate from normal size. */
    val ultraFixTargetWidthText: String = "",
    val ultraFixTargetHeightText: String = "",
    internal val sourceVersion: Int = IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION)
        .put("taskModeName", taskModeName)
        .put("strengthText", strengthText)
        .put("controlStrengthText", controlStrengthText)
        .put("negativePrompt", negativePrompt)
        .put("disableModelNegativePrompt", disableModelNegativePrompt)
        .put("clipSkipText", clipSkipText)
        .put("vaeTilingEnabled", vaeTilingEnabled)
        .put("batchCount", batchCount)
        .put("widthText", widthText)
        .put("heightText", heightText)
        .put("stepsText", stepsText)
        .put("cfgScaleText", cfgScaleText)
        .put("seedText", seedText)
        .put("sampler", sampler)
        .put("livePreviewEnabled", livePreviewEnabled)
        .put("livePreviewInterval", livePreviewInterval.coerceIn(1, 10))
        .put("livePreviewIntervalExplicit", livePreviewIntervalExplicit)
        .put("textualInversionIds", JSONArray(textualInversionIds))
        .put("ultraFixEnabled", ultraFixEnabled)
        .put("ultraFixStrengthText", ultraFixStrengthText)
        .put("ultraFixInversionStepsText", ultraFixInversionStepsText)
        .put("ultraFixRefinementStepsText", ultraFixRefinementStepsText)
        .put("ultraFixTileSizeText", ultraFixTileSizeText)
        .put("ultraFixTileSizeExplicit", ultraFixTileSizeExplicit)
        .put("ultraFixOverlapText", ultraFixOverlapText)
        .put("ultraFixTargetWidthText", ultraFixTargetWidthText)
        .put("ultraFixTargetHeightText", ultraFixTargetHeightText)
        .put("loras", JSONArray().apply { loras.forEach { put(it.toJson()) } })
        .apply {
            livePreviewMode?.let { put("livePreviewMode", it.wireName) }
            inputImageUri?.let { put("inputImageUri", it) }
            maskImageUri?.let { put("maskImageUri", it) }
            controlImageUri?.let { put("controlImageUri", it) }
        }

    companion object {
        fun fromJsonOrNull(raw: String?): ImageGenerationUiParameterSnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val version = json.optInt("version", -1)
                require(version in 1..IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION)
                val previewMode = if (version >= 5) {
                    json.optString("livePreviewMode").takeIf(String::isNotBlank)?.let { wireName ->
                        requireNotNull(ImageGenerationUiPreviewMode.fromWireNameOrNull(wireName)) {
                            "Unknown image live-preview mode."
                        }
                    }
                } else {
                    null
                }
                ImageGenerationUiParameterSnapshot(
                    taskModeName = json.getString("taskModeName"),
                    strengthText = json.getString("strengthText"),
                    controlStrengthText = json.getString("controlStrengthText"),
                    negativePrompt = json.getString("negativePrompt"),
                    disableModelNegativePrompt = json.getBoolean("disableModelNegativePrompt"),
                    clipSkipText = json.getString("clipSkipText"),
                    vaeTilingEnabled = json.getBoolean("vaeTilingEnabled"),
                    batchCount = json.getInt("batchCount").coerceIn(1, 8),
                    widthText = json.getString("widthText"),
                    heightText = json.getString("heightText"),
                    stepsText = json.getString("stepsText"),
                    cfgScaleText = json.getString("cfgScaleText"),
                    seedText = json.getString("seedText"),
                    sampler = json.getString("sampler"),
                    loras = if (version >= 3) {
                        val array = json.getJSONArray("loras")
                        require(array.length() <= 8)
                        buildList {
                            for (index in 0 until array.length()) {
                                add(ImageGenerationUiLoraDraft.fromJson(array.getJSONObject(index)))
                            }
                        }.also { drafts ->
                            require(drafts.map(ImageGenerationUiLoraDraft::id).distinct().size == drafts.size)
                        }
                    } else {
                        emptyList()
                    },
                    inputImageUri = if (version >= 2) {
                        json.optString("inputImageUri").takeIf(String::isNotBlank)
                    } else {
                        null
                    },
                    maskImageUri = if (version >= 2) {
                        json.optString("maskImageUri").takeIf(String::isNotBlank)
                    } else {
                        null
                    },
                    controlImageUri = if (version >= 2) {
                        json.optString("controlImageUri").takeIf(String::isNotBlank)
                    } else {
                        null
                    },
                    livePreviewMode = previewMode,
                    livePreviewEnabled = if (version >= 4) {
                        json.getBoolean("livePreviewEnabled")
                    } else {
                        true
                    },
                    livePreviewInterval = if (version >= 4) {
                        json.getInt("livePreviewInterval").coerceIn(1, 10)
                    } else {
                        1
                    },
                    livePreviewIntervalExplicit =
                        version >= 5 &&
                            json.optBoolean("livePreviewIntervalExplicit", false),
                    textualInversionIds = if (version >= 6) {
                        json.optJSONArray("textualInversionIds")?.let { values ->
                            require(values.length() <= 8)
                            buildList {
                                for (index in 0 until values.length()) {
                                    add(java.util.UUID.fromString(values.getString(index)).toString())
                                }
                            }.also { ids -> require(ids.distinct().size == ids.size) }
                        }.orEmpty()
                    } else {
                        emptyList()
                    },
                    ultraFixEnabled = version >= 6 && json.optBoolean("ultraFixEnabled", false),
                    ultraFixStrengthText = json.optString("ultraFixStrengthText", "0.35"),
                    ultraFixInversionStepsText = json.optString("ultraFixInversionStepsText", "4"),
                    ultraFixRefinementStepsText = json.optString("ultraFixRefinementStepsText", "10"),
                    ultraFixTileSizeText = json.optString("ultraFixTileSizeText", "512"),
                    ultraFixTileSizeExplicit = version >= 7 &&
                        json.optBoolean("ultraFixTileSizeExplicit", false),
                    ultraFixOverlapText = json.optString("ultraFixOverlapText", "0.25"),
                    ultraFixTargetWidthText = if (version >= 9) {
                        json.optString("ultraFixTargetWidthText", "")
                    } else {
                        // Versions 6-8 stored the active UltraFix target in widthText/heightText.
                        // Keep those values as the target for a one-time, loss-minimizing migration;
                        // normalizedForImageModel supplies clean ordinary dimensions.
                        ""
                    },
                    ultraFixTargetHeightText = if (version >= 9) {
                        json.optString("ultraFixTargetHeightText", "")
                    } else {
                        ""
                    },
                    sourceVersion = version
                )
            }.getOrNull()
        }
    }
}

internal fun normalizedImageSamplerForCapabilities(
    current: String,
    supported: List<String>,
    defaultSampler: String
): String {
    val available = supported.distinct()
    if (available.isEmpty() || current in available) return current
    return defaultSampler.takeIf(available::contains) ?: available.first()
}

private const val IMAGE_GENERATION_UI_PARAMETER_PREFS = "mca_image_generation_ui_parameters"
private const val IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX = "model:"
private const val IMAGE_GENERATION_OWNED_GRANTS_KEY = "owned:persistable_image_uris"
private val generationImageGrantOwnershipLock = Any()
private val pendingGenerationImageGrantUriEpochs = mutableMapOf<String, Long>()
private var nextPendingGenerationImageGrantEpoch = 0L

private fun imageLoraDraftsFromJson(raw: String): List<ImageGenerationUiLoraDraft> = runCatching {
    val array = JSONArray(raw)
    require(array.length() <= 8)
    buildList {
        for (index in 0 until array.length()) {
            add(ImageGenerationUiLoraDraft.fromJson(array.getJSONObject(index)))
        }
    }.also { drafts ->
        require(drafts.map(ImageGenerationUiLoraDraft::id).distinct().size == drafts.size)
    }
}.getOrDefault(emptyList())

private fun imageLoraDraftsToJson(drafts: List<ImageGenerationUiLoraDraft>): String {
    require(drafts.size <= 8) { "At most 8 LoRA adapters may be selected." }
    return JSONArray().apply { drafts.forEach { put(it.toJson()) } }.toString()
}

private fun imageTextualInversionIdsFromJson(raw: String): List<String> = runCatching {
    val array = JSONArray(raw)
    require(array.length() <= 8)
    buildList {
        for (index in 0 until array.length()) {
            add(java.util.UUID.fromString(array.getString(index)).toString())
        }
    }.also { ids -> require(ids.distinct().size == ids.size) }
}.getOrDefault(emptyList())

private fun imageTextualInversionIdsToJson(ids: List<String>): String {
    require(ids.size <= 8) { "At most 8 textual inversions may be selected." }
    val canonical = ids.map { java.util.UUID.fromString(it).toString() }
    require(canonical.distinct().size == canonical.size)
    return JSONArray(canonical).toString()
}

private val IMAGE_TEXTUAL_INVERSION_TRIGGER_PATTERN = Regex("[A-Za-z0-9_:#<>|.-]{1,64}")

internal fun imagePromptContainsTextualInversionTrigger(
    prompt: String,
    trigger: String
): Boolean = imageTextualInversionTriggerRegex(trigger).containsMatchIn(prompt)

internal fun imagePromptWithTextualInversionTrigger(
    prompt: String,
    trigger: String
): String {
    if (imagePromptContainsTextualInversionTrigger(prompt, trigger)) return prompt
    val base = prompt.trimEnd()
    return when {
        base.isEmpty() -> trigger
        base.endsWith(',') -> "$base $trigger"
        else -> "$base, $trigger"
    }
}

internal fun imagePromptWithoutTextualInversionTrigger(
    prompt: String,
    trigger: String
): String {
    val removed = imageTextualInversionTriggerRegex(trigger).replace(prompt, "")
    return removed
        .replace(Regex("(?:[\\t ]*,[\\t ]*){2,}"), ", ")
        .replace(Regex("[\\t ]+"), " ")
        .trim()
        .trim(',')
        .trim()
}

internal data class ImageTextualInversionSelectionReconciliation(
    val ids: List<String>,
    val prompt: String,
    val triggersById: Map<String, String>,
    val additionalPrompt: String = "",
)

internal fun reconcileImageTextualInversionSelection(
    supportsTextualInversion: Boolean,
    libraryBusy: Boolean,
    currentIds: List<String>,
    prompt: String,
    knownTriggersById: Map<String, String>,
    available: List<ImageTextualInversionUiItem>,
    supportedFormats: Set<String> = IMAGE_TEXTUAL_INVERSION_ALL_FORMATS,
    additionalPrompt: String = "",
): ImageTextualInversionSelectionReconciliation {
    val normalizedFormats = supportedFormats.mapTo(mutableSetOf()) { it.trim().lowercase() }
    if (libraryBusy) {
        val knownById = available.associateBy(ImageTextualInversionUiItem::id)
        val retained = if (supportsTextualInversion) {
            currentIds.filter { id ->
                knownById[id]?.isSupportedBy(normalizedFormats) != false
            }.take(8)
        } else {
            emptyList()
        }
        var normalizedPrompt = prompt
        var normalizedAdditionalPrompt = additionalPrompt
        (currentIds + knownTriggersById.keys)
            .distinct()
            .filterNot(retained::contains)
            .forEach { id ->
                val trigger = knownTriggersById[id] ?: knownById[id]?.trigger
                if (trigger != null) {
                    normalizedPrompt = imagePromptWithoutTextualInversionTrigger(
                        normalizedPrompt,
                        trigger
                    )
                    normalizedAdditionalPrompt = imagePromptWithoutTextualInversionTrigger(
                        normalizedAdditionalPrompt,
                        trigger
                    )
                }
            }
        return ImageTextualInversionSelectionReconciliation(
            ids = retained,
            prompt = normalizedPrompt,
            additionalPrompt = normalizedAdditionalPrompt,
            triggersById = knownTriggersById.filterKeys(retained::contains)
        )
    }
    val availableById = available
        .filter { artifact -> artifact.isSupportedBy(normalizedFormats) }
        .associateBy(ImageTextualInversionUiItem::id)
    val retained = if (supportsTextualInversion) {
        currentIds.filter(availableById::containsKey).take(8)
    } else {
        emptyList()
    }
    var normalizedPrompt = prompt
    var normalizedAdditionalPrompt = additionalPrompt
    (currentIds + knownTriggersById.keys)
        .distinct()
        .filterNot(retained::contains)
        .forEach { id ->
            val trigger = knownTriggersById[id] ?: availableById[id]?.trigger
            if (trigger != null) {
                normalizedPrompt = imagePromptWithoutTextualInversionTrigger(normalizedPrompt, trigger)
                normalizedAdditionalPrompt = imagePromptWithoutTextualInversionTrigger(
                    normalizedAdditionalPrompt,
                    trigger
                )
            }
        }
    val retainedTriggers = buildMap {
        retained.forEach { id ->
            val trigger = requireNotNull(availableById[id]).trigger
            put(id, trigger)
            if (!imagePromptContainsTextualInversionTrigger(normalizedAdditionalPrompt, trigger)) {
                normalizedPrompt = imagePromptWithTextualInversionTrigger(normalizedPrompt, trigger)
            }
        }
    }
    return ImageTextualInversionSelectionReconciliation(
        ids = retained,
        prompt = normalizedPrompt,
        additionalPrompt = normalizedAdditionalPrompt,
        triggersById = retainedTriggers
    )
}

private fun imageTextualInversionTriggerRegex(trigger: String): Regex {
    require(IMAGE_TEXTUAL_INVERSION_TRIGGER_PATTERN.matches(trigger)) {
        "Textual inversion trigger is invalid."
    }
    val startsWithWord = trigger.first().isAsciiImageTriggerWordCharacter()
    val endsWithWord = trigger.last().isAsciiImageTriggerWordCharacter()
    return Regex(
        buildString {
            if (startsWithWord) append("(?<![A-Za-z0-9_])")
            append(Regex.escape(trigger))
            if (endsWithWord) append("(?![A-Za-z0-9_])")
        },
        RegexOption.IGNORE_CASE
    )
}

private fun Char.isAsciiImageTriggerWordCharacter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

internal data class GenerationImageGrantReconciliationPlan(
    val retainedOwnedUris: Set<String>,
    val releaseOwnedUris: Set<String>,
    val forgetOwnedUris: Set<String>
)

internal fun generationImageSnapshotReferences(
    preferences: Map<String, *>,
    currentImageModelIds: Set<String>
): Set<String> = buildSet {
    preferences.forEach { (key, value) ->
        if (!key.startsWith(IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX)) return@forEach
        val modelId = key.removePrefix(IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX)
        if (modelId !in currentImageModelIds) return@forEach
        val snapshot = ImageGenerationUiParameterSnapshot.fromJsonOrNull(value as? String)
            ?: return@forEach
        listOf(snapshot.inputImageUri, snapshot.maskImageUri, snapshot.controlImageUri)
            .mapNotNull { raw -> raw?.trim()?.takeIf { it.startsWith("content://", true) } }
            .forEach(::add)
    }
}

internal fun normalizedGenerationImageHistoryReferences(
    references: Iterable<String>
): Set<String> = references.mapNotNullTo(mutableSetOf()) { raw ->
    raw.trim().takeIf { it.startsWith("content://", ignoreCase = true) }
}

internal fun combinedGenerationImageGrantReferences(
    snapshotReferencedUris: Set<String>,
    historyReferencedUris: Set<String>,
    transientReferencedUris: Set<String>,
    pendingUris: Set<String>
): Set<String> = snapshotReferencedUris +
    historyReferencedUris +
    transientReferencedUris +
    pendingUris

internal fun obsoleteGenerationImageSnapshotKeys(
    preferences: Map<String, *>,
    currentImageModelIds: Set<String>
): Set<String> = preferences.keys.filterTo(mutableSetOf()) { key ->
    key.startsWith(IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX) &&
        key.removePrefix(IMAGE_GENERATION_UI_PARAMETER_KEY_PREFIX) !in currentImageModelIds
}

internal fun planGenerationImageGrantReconciliation(
    ownedUris: Set<String>,
    persistedReadUris: Set<String>,
    referencedUris: Set<String>,
    deferRelease: Boolean
): GenerationImageGrantReconciliationPlan {
    val validOwned = ownedUris intersect persistedReadUris
    val forget = ownedUris - persistedReadUris
    val release = if (deferRelease) emptySet() else validOwned - referencedUris
    return GenerationImageGrantReconciliationPlan(
        retainedOwnedUris = validOwned - release,
        releaseOwnedUris = release,
        forgetOwnedUris = forget
    )
}

/**
 * Resolves only pending entries that existed when a reconciliation was scheduled. This prevents a
 * stale IO effect from pruning a grant taken by a newer picker callback before Compose has exposed
 * that URI as transient state. Any durable UI/history reference or a successful snapshot commit
 * resolves pending protection immediately; otherwise it remains only while a transient role uses it.
 */
internal fun pendingGenerationImageGrantUrisAfterReconciliation(
    pendingUriEpochs: Map<String, Long>,
    eligibleForPruneUriEpochs: Map<String, Long>,
    snapshotReferencedUris: Set<String>,
    transientReferencedUris: Set<String>,
    committedReferencedUris: Set<String> = emptySet()
): Map<String, Long> {
    val currentReferences = snapshotReferencedUris + transientReferencedUris
    return pendingUriEpochs.filterNot { (uri, epoch) ->
        eligibleForPruneUriEpochs[uri] == epoch &&
            (uri in committedReferencedUris ||
                uri in snapshotReferencedUris ||
                uri !in currentReferences)
    }
}

private fun pendingGenerationImageGrantUriEpochsSnapshot(): Map<String, Long> =
    synchronized(generationImageGrantOwnershipLock) {
        pendingGenerationImageGrantUriEpochs.toMap()
    }

private fun registerPendingGenerationImageGrantUri(raw: String) {
    check(nextPendingGenerationImageGrantEpoch < Long.MAX_VALUE) {
        "Generation image grant pending epoch exhausted."
    }
    pendingGenerationImageGrantUriEpochs[raw] = ++nextPendingGenerationImageGrantEpoch
}

private fun ownedGenerationImageUris(preferences: SharedPreferences): Set<String> =
    preferences.getStringSet(IMAGE_GENERATION_OWNED_GRANTS_KEY, emptySet())
        ?.mapNotNullTo(mutableSetOf()) { raw ->
            raw.trim().takeIf { it.startsWith("content://", ignoreCase = true) }
        }
        .orEmpty()

internal fun persistGenerationImageUri(context: Context, uri: Uri): String? = runCatching {
    synchronized(generationImageGrantOwnershipLock) {
        require(uri.scheme.equals("content", ignoreCase = true)) {
            "Generation image inputs must use a persistable content URI."
        }
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(
            IMAGE_GENERATION_UI_PARAMETER_PREFS,
            Context.MODE_PRIVATE
        )
        val raw = uri.toString()
        val ownedBefore = ownedGenerationImageUris(preferences)
        val alreadyPersisted = appContext.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == uri
        }
        // A pre-existing grant may belong to model import, the file library, or another feature.
        // Use it without claiming ownership so generation reconciliation can never release it.
        if (alreadyPersisted && raw !in ownedBefore) {
            return@synchronized raw
        }
        if (!alreadyPersisted) {
            check(
                preferences.edit()
                    .putStringSet(IMAGE_GENERATION_OWNED_GRANTS_KEY, ownedBefore + raw)
                    .commit()
            ) { "Unable to register generation image grant ownership." }
            try {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (error: Throwable) {
                preferences.edit()
                    .putStringSet(IMAGE_GENERATION_OWNED_GRANTS_KEY, ownedBefore)
                    .commit()
                throw error
            }
        }
        val retained = appContext.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == uri
        }
        if (!retained) {
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            preferences.edit()
                .putStringSet(IMAGE_GENERATION_OWNED_GRANTS_KEY, ownedBefore)
                .commit()
            pendingGenerationImageGrantUriEpochs.remove(raw)
            error("The document provider did not retain read access.")
        }
        registerPendingGenerationImageGrantUri(raw)
        raw
    }
}.getOrNull()

private fun reconcileGenerationImageUriGrants(
    context: Context,
    preferences: SharedPreferences,
    currentImageModelIds: Set<String>,
    deferRelease: Boolean,
    releaseOwnedUrisIfCoordinatorIdle: ((() -> Unit) -> Boolean),
    libraryHistoryReferencedUris: Set<String> = emptySet(),
    transientReferencedUris: Set<String> = emptySet(),
    committedReferencedUris: Set<String> = emptySet(),
    pendingUriEpochsEligibleForPrune: Map<String, Long> = emptyMap()
) = synchronized(generationImageGrantOwnershipLock) {
    val obsoleteKeys = obsoleteGenerationImageSnapshotKeys(
        preferences = preferences.all,
        currentImageModelIds = currentImageModelIds
    )
    if (obsoleteKeys.isNotEmpty()) {
        val editor = preferences.edit()
        obsoleteKeys.forEach(editor::remove)
        editor.commit()
    }
    val uiSnapshotReferencedUris = generationImageSnapshotReferences(
        preferences = preferences.all,
        currentImageModelIds = currentImageModelIds
    )
    val persistentReferencedUris = uiSnapshotReferencedUris + libraryHistoryReferencedUris
    val retainedPendingUris = pendingGenerationImageGrantUrisAfterReconciliation(
        pendingUriEpochs = pendingGenerationImageGrantUriEpochs,
        eligibleForPruneUriEpochs = pendingUriEpochsEligibleForPrune,
        snapshotReferencedUris = persistentReferencedUris,
        transientReferencedUris = transientReferencedUris,
        committedReferencedUris = committedReferencedUris
    )
    pendingGenerationImageGrantUriEpochs.clear()
    pendingGenerationImageGrantUriEpochs.putAll(retainedPendingUris)
    val referencedUris = combinedGenerationImageGrantReferences(
        snapshotReferencedUris = uiSnapshotReferencedUris,
        historyReferencedUris = libraryHistoryReferencedUris,
        transientReferencedUris = transientReferencedUris,
        pendingUris = retainedPendingUris.keys
    )
    val persistedReadUris = context.contentResolver.persistedUriPermissions
        .asSequence()
        .filter { it.isReadPermission }
        .map { it.uri.toString() }
        .toSet()
    val plan = planGenerationImageGrantReconciliation(
        ownedUris = ownedGenerationImageUris(preferences),
        persistedReadUris = persistedReadUris,
        referencedUris = referencedUris,
        deferRelease = deferRelease
    )
    val retained = plan.retainedOwnedUris.toMutableSet()
    val releaseWindowOpened = if (deferRelease) {
        plan.releaseOwnedUris.isEmpty()
    } else {
        releaseOwnedUrisIfCoordinatorIdle {
            plan.releaseOwnedUris.forEach { raw ->
                val uri = runCatching { Uri.parse(raw) }.getOrNull()
                if (uri == null) return@forEach
                val released = runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }.isSuccess
                val stillPersisted = context.contentResolver.persistedUriPermissions.any { permission ->
                    permission.isReadPermission && permission.uri == uri
                }
                if (!released || stillPersisted) retained += raw
            }
            GenerationImageOwnedInputStore(context).pruneUnreferenced(referencedUris)
        }
    }
    if (!releaseWindowOpened) {
        retained += plan.releaseOwnedUris
    }
    preferences.edit()
        .putStringSet(IMAGE_GENERATION_OWNED_GRANTS_KEY, retained)
        .commit()
}

internal fun persistedGenerationImageUriOrNull(context: Context, raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    if (!uri.scheme.equals("content", ignoreCase = true)) return null
    if (isGenerationOwnedInputUri(context, value)) {
        return GenerationImageOwnedInputStore(context).readableOwnedUriOrNull(value)
    }
    val permissionStillGranted = context.contentResolver.persistedUriPermissions.any { permission ->
        permission.isReadPermission && permission.uri == uri
    }
    if (!permissionStillGranted) return null
    val documentStillReadable = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true }
            ?: context.contentResolver.openInputStream(uri)?.use { true }
            ?: false
    }.getOrDefault(false)
    return value.takeIf { documentStillReadable }
}

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
    val supportsImageLivePreview: Boolean = imagePreviewMode != null
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

internal fun ChatModelChoice.resolvedImagePreviewMode(): ImageGenerationUiPreviewMode? =
    imagePreviewMode

internal fun ChatModelChoice.resolvedImageUltraFixDefaultTileSize(): Int {
    imageUltraFixRequiredTileSize.takeIf { it > 0 }?.let { return it }
    if (!supportsImageUltraFix) return 512

    val widthMultiple = imageUltraFixWidthMultiple.coerceAtLeast(1)
    val heightMultiple = imageUltraFixHeightMultiple.coerceAtLeast(1)
    val commonMultiple = leastCommonMultiple(widthMultiple, heightMultiple)
    val upperBound = minOf(
        1024,
        imageDefaultWidth,
        imageDefaultHeight,
        imageUltraFixMaxWidth,
        imageUltraFixMaxHeight,
        2048,
    ).toLong()
    val aligned = (upperBound / commonMultiple) * commonMultiple
    check(aligned in 128L..2048L) {
        "UltraFix capabilities do not expose a valid default tile."
    }
    return aligned.toInt()
}

internal fun ChatModelChoice.ultraFixTargetSizeForSourceOrNull(
    sourceWidth: Int,
    sourceHeight: Int,
): Pair<Int, Int>? {
    if (!supportsImageUltraFix || sourceWidth <= 0 || sourceHeight <= 0) return null
    val tile = resolvedImageUltraFixDefaultTileSize()

    fun alignedAxis(source: Int, minimum: Int, maximum: Int, multiple: Int): Int? {
        if (maximum <= 0 || multiple <= 0) return null
        val requested = maxOf(source, minimum, tile).toLong()
        val step = multiple.toLong()
        val aligned = ((requested + step - 1L) / step) * step
        return aligned.takeIf { it <= maximum.toLong() }?.toInt()
    }

    val width = alignedAxis(
        sourceWidth,
        imageUltraFixMinWidth,
        imageUltraFixMaxWidth,
        imageUltraFixWidthMultiple,
    ) ?: return null
    val height = alignedAxis(
        sourceHeight,
        imageUltraFixMinHeight,
        imageUltraFixMaxHeight,
        imageUltraFixHeightMultiple,
    ) ?: return null
    if (width.toLong() * height.toLong() > 64L * 1024L * 1024L) return null
    return width to height
}

internal data class ImageGenerationUiUltraFixTileSelection(
    val tileSize: Int,
    val explicit: Boolean,
)

internal fun normalizedImageGenerationUltraFixTileSelection(
    model: ChatModelChoice,
    rawValue: String,
    explicit: Boolean,
    sourceVersion: Int,
): ImageGenerationUiUltraFixTileSelection {
    model.imageUltraFixRequiredTileSize.takeIf { it > 0 }?.let { required ->
        return ImageGenerationUiUltraFixTileSelection(required, explicit = false)
    }

    val defaultTile = model.resolvedImageUltraFixDefaultTileSize()
    val parsed = rawValue.trim().toIntOrNull()
    val valid = parsed != null && parsed in 128..2048 &&
        parsed <= model.imageUltraFixMaxWidth &&
        parsed <= model.imageUltraFixMaxHeight &&
        parsed % model.imageUltraFixWidthMultiple.coerceAtLeast(1) == 0 &&
        parsed % model.imageUltraFixHeightMultiple.coerceAtLeast(1) == 0
    val inferredExplicit = when {
        sourceVersion >= 7 -> explicit
        rawValue.trim() == "512" -> false
        else -> valid
    }
    return if (inferredExplicit && valid) {
        ImageGenerationUiUltraFixTileSelection(requireNotNull(parsed), explicit = true)
    } else {
        ImageGenerationUiUltraFixTileSelection(defaultTile, explicit = false)
    }
}

private fun leastCommonMultiple(first: Int, second: Int): Long {
    fun greatestCommonDivisor(left: Long, right: Long): Long {
        var a = left
        var b = right
        while (b != 0L) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a
    }

    val left = first.coerceAtLeast(1).toLong()
    val right = second.coerceAtLeast(1).toLong()
    return (left / greatestCommonDivisor(left, right)) * right
}

internal fun ChatModelChoice.imageSupportedSamplersForTask(
    taskMode: ImageGenerationUiTaskMode
): List<String> = when (taskMode) {
    ImageGenerationUiTaskMode.IMG2IMG,
    ImageGenerationUiTaskMode.INPAINT -> imageImg2ImgSupportedSamplers
    else -> imageSupportedSamplers
}.distinct()

internal data class ImageGenerationUiPreviewRequest(
    val mode: ImageGenerationUiPreviewMode,
    val interval: Int
)

internal fun imageGenerationUiPreviewRequestOrNull(
    model: ChatModelChoice?,
    enabled: Boolean,
    interval: Int
): ImageGenerationUiPreviewRequest? = model
    ?.resolvedImagePreviewMode()
    ?.takeIf { enabled }
    ?.let { mode ->
        ImageGenerationUiPreviewRequest(
            mode = mode,
            interval = interval.coerceIn(1, 10)
        )
    }

internal fun shouldShowImageGenerationVaePreviewCostWarning(
    mode: ImageGenerationUiPreviewMode?,
    enabled: Boolean,
    interval: Int
): Boolean = mode == ImageGenerationUiPreviewMode.VAE && enabled && interval == 1

internal fun normalizedImageGenerationDimensionText(
    rawValue: String,
    defaultValue: Int,
    minValue: Int,
    maxValue: Int,
    multiple: Int
): String {
    val safeMin = minValue.coerceAtLeast(1)
    val safeMax = maxValue.coerceAtLeast(safeMin)
    if (safeMin == safeMax) return safeMin.toString()

    val step = multiple.coerceAtLeast(1).toLong()
    val min = safeMin.toLong()
    val max = safeMax.toLong()
    val firstAligned = ((min + step - 1L) / step) * step
    val lastAligned = (max / step) * step
    if (firstAligned > lastAligned) {
        return defaultValue.coerceIn(safeMin, safeMax).toString()
    }

    val requested = rawValue.trim().toLongOrNull() ?: defaultValue.toLong()
    val bounded = requested.coerceIn(firstAligned, lastAligned)
    val lower = ((bounded / step) * step).coerceAtLeast(firstAligned)
    val upper = (lower + step).coerceAtMost(lastAligned)
    val normalized = if (bounded - lower <= upper - bounded) lower else upper
    return normalized.toString()
}

internal fun normalizedImageGenerationStepsText(
    rawValue: String,
    defaultValue: Int,
    minValue: Int,
    maxValue: Int
): String {
    val safeMin = minValue.coerceAtLeast(1)
    val safeMax = maxValue.coerceAtLeast(safeMin)
    val safeDefault = defaultValue.coerceIn(safeMin, safeMax)
    return (rawValue.trim().toLongOrNull() ?: safeDefault.toLong())
        .coerceIn(safeMin.toLong(), safeMax.toLong())
        .toString()
}

internal fun ImageGenerationUiParameterSnapshot.normalizedForImageModel(
    model: ChatModelChoice
): ImageGenerationUiParameterSnapshot {
    if (model.cloud) return this
    val taskMode = ImageGenerationUiTaskMode.entries.firstOrNull { it.name == taskModeName }
        ?: ImageGenerationUiTaskMode.TEXT_TO_IMAGE
    val supportedSamplers = model.imageSupportedSamplersForTask(taskMode)
    val targetPreviewMode = model.resolvedImagePreviewMode()
    val targetPreviewInterval = model.imageDefaultPreviewInterval.coerceIn(1, 10)
    val currentPreviewInterval = livePreviewInterval.coerceIn(1, 10)
    val inferredLegacyExplicitInterval = sourceVersion <= 4 && when (targetPreviewMode) {
        ImageGenerationUiPreviewMode.VAE -> currentPreviewInterval != 1
        ImageGenerationUiPreviewMode.PROJECTION -> currentPreviewInterval != targetPreviewInterval
        null -> false
    }
    val normalizedPreviewIntervalExplicit =
        livePreviewIntervalExplicit || inferredLegacyExplicitInterval
    val normalizedPreviewInterval = when {
        targetPreviewMode == null -> currentPreviewInterval
        normalizedPreviewIntervalExplicit -> currentPreviewInterval
        sourceVersion <= 4 && targetPreviewMode == ImageGenerationUiPreviewMode.PROJECTION ->
            currentPreviewInterval
        else -> targetPreviewInterval
    }
    val normalizedUltraFixTotalSteps = ultraFixRefinementStepsText.toIntOrNull()
        ?.coerceIn(1, IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS)
        ?: 10
    val legacyDenoisingSteps = ultraFixStrengthText.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
        ?.coerceIn(0.0, 1.0)
        ?.let { strength ->
            imageGenerationUltraFixDenoisingTailStepCount(
                normalizedUltraFixTotalSteps,
                strength,
            )
        }
        ?: 4
    val normalizedUltraFixInversionSteps = ultraFixInversionStepsText.toIntOrNull()
        ?.coerceIn(
            1,
            minOf(
                IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                normalizedUltraFixTotalSteps,
            ),
        )
        ?: legacyDenoisingSteps.coerceIn(
            1,
            minOf(
                IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                normalizedUltraFixTotalSteps,
            ),
        )
    val normalizedUltraFixStrength = imageGenerationUltraFixStrengthForDenoisingSteps(
        normalizedUltraFixTotalSteps,
        normalizedUltraFixInversionSteps,
    )
    val normalizedUltraFixEnabled = ultraFixEnabled && model.supportsImageUltraFix &&
        taskMode == ImageGenerationUiTaskMode.IMG2IMG
    val normalizedUltraFixTile = normalizedImageGenerationUltraFixTileSelection(
        model = model,
        rawValue = ultraFixTileSizeText,
        explicit = ultraFixTileSizeExplicit,
        sourceVersion = sourceVersion,
    )
    val normalizedUltraFixOverlap = ultraFixOverlapText.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
        ?.coerceIn(0.0, 0.5)
        ?: 0.25
    val legacyUltraFixTargetWidth = if (ultraFixTargetWidthText.isBlank() && ultraFixEnabled) {
        widthText
    } else {
        ultraFixTargetWidthText
    }
    val legacyUltraFixTargetHeight = if (ultraFixTargetHeightText.isBlank() && ultraFixEnabled) {
        heightText
    } else {
        ultraFixTargetHeightText
    }
    val ordinaryWidthRaw = if (sourceVersion < 9 && ultraFixEnabled) {
        model.imageDefaultWidth.toString()
    } else {
        widthText
    }
    val ordinaryHeightRaw = if (sourceVersion < 9 && ultraFixEnabled) {
        model.imageDefaultHeight.toString()
    } else {
        heightText
    }
    val normalizedUltraFixTargetWidth = normalizedImageGenerationDimensionText(
        rawValue = legacyUltraFixTargetWidth,
        defaultValue = model.imageDefaultWidth,
        minValue = maxOf(model.imageUltraFixMinWidth, normalizedUltraFixTile.tileSize),
        maxValue = model.imageUltraFixMaxWidth,
        multiple = model.imageUltraFixWidthMultiple
    )
    val normalizedUltraFixTargetHeight = normalizedImageGenerationDimensionText(
        rawValue = legacyUltraFixTargetHeight,
        defaultValue = model.imageDefaultHeight,
        minValue = maxOf(model.imageUltraFixMinHeight, normalizedUltraFixTile.tileSize),
        maxValue = model.imageUltraFixMaxHeight,
        multiple = model.imageUltraFixHeightMultiple
    )
    return copy(
        livePreviewMode = targetPreviewMode,
        livePreviewInterval = normalizedPreviewInterval,
        livePreviewIntervalExplicit = normalizedPreviewIntervalExplicit,
        textualInversionIds = textualInversionIds
            .distinct()
            .take(8)
            .takeIf { model.supportsImageTextualInversion }
            .orEmpty(),
        ultraFixEnabled = normalizedUltraFixEnabled,
        ultraFixStrengthText = normalizedUltraFixStrength.toString(),
        ultraFixInversionStepsText = normalizedUltraFixInversionSteps.toString(),
        ultraFixRefinementStepsText = normalizedUltraFixTotalSteps.toString(),
        ultraFixTileSizeText = normalizedUltraFixTile.tileSize.toString(),
        ultraFixTileSizeExplicit = normalizedUltraFixTile.explicit,
        ultraFixOverlapText = normalizedUltraFixOverlap.toString(),
        ultraFixTargetWidthText = normalizedUltraFixTargetWidth,
        ultraFixTargetHeightText = normalizedUltraFixTargetHeight,
        sourceVersion = IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION,
        widthText = normalizedImageGenerationDimensionText(
            rawValue = ordinaryWidthRaw,
            defaultValue = model.imageDefaultWidth,
            minValue = model.imageMinWidth,
            maxValue = model.imageMaxWidth,
            multiple = model.imageWidthMultiple
        ),
        heightText = normalizedImageGenerationDimensionText(
            rawValue = ordinaryHeightRaw,
            defaultValue = model.imageDefaultHeight,
            minValue = model.imageMinHeight,
            maxValue = model.imageMaxHeight,
            multiple = model.imageHeightMultiple
        ),
        stepsText = normalizedImageGenerationStepsText(
            rawValue = stepsText,
            defaultValue = model.imageDefaultSteps,
            minValue = model.imageMinSteps,
            maxValue = model.imageMaxSteps
        ),
        sampler = normalizedImageSamplerForCapabilities(
            current = sampler,
            supported = supportedSamplers,
            defaultSampler = model.imageDefaultSampler
        )
    )
}

data class ChatHistoryItem(
    val id: String,
    val title: String,
    val updatedAtText: String,
    val updatedAtMillis: Long,
    val messageCount: Int,
    val pinned: Boolean,
    val selected: Boolean
)

@Composable
fun ChatScreen(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onDismissStatusMessage: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewConversation: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onTogglePinConversation: (String) -> Unit,
    onExportConversation: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDeleteMessage: (Int) -> Unit,
    onDeleteLastTurn: () -> Unit = {},
    onUploadFile: (String) -> Unit,
    onUseImageAsset: (String) -> Unit = {},
    onDeleteImageAsset: (String) -> Unit = {},
    onDeleteImageAssets: (List<String>) -> Unit = {},
    onSetImageAssetFavorite: (String, Boolean) -> Unit = { _, _ -> },
    onExportImageLibraryBackup: (String, Boolean) -> Unit = { _, _ -> },
    onImportImageLibraryBackup: (String) -> Unit = {},
    onCancelImageLibraryBackup: () -> Unit = {},
    onImportImageLora: (String) -> Unit = {},
    onDeleteImageLora: (String) -> Unit = {},
    onImportImageTextualInversion: (String, String) -> Unit = { _, _ -> },
    onDeleteImageTextualInversion: (String) -> Unit = {},
    onImportImageUpscaler: (String) -> Unit = {},
    onDeleteImageUpscaler: (String) -> Unit = {},
    onSelectImageUpscaler: (String) -> Unit = {},
    onUpscaleImageAsset: (String, Int) -> Unit = { _, _ -> },
    onCancelImageUpscale: () -> Unit = {},
    onUseFileAsset: (String) -> Unit = {},
    onDeleteFileAsset: (String) -> Unit = {},
    onGenerateImagePrompt: (String, ImageGenerationUiOptions) -> Boolean = { _, _ -> false },
    onRetryImageGeneration: (String) -> Unit = {},
    onRecreateImageAsset: (String) -> Unit = {},
    onCancelImageGeneration: () -> Unit = {},
    releaseGenerationImageGrantsIfCoordinatorIdle: ((() -> Unit) -> Boolean),
    onSelectImageModel: (String) -> Unit = {},
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit = {},
    onToggleWebSearchForTurn: () -> Unit = {},
    onSelectWebSearchResearchMode: (String) -> Unit = {},
    onOpenWebSearchSettings: () -> Unit = {},
    onLoadModel: (String) -> Unit = {},
    onOpenAgent: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onSaveAssistant: (AssistantEditorDraft) -> Unit = {},
    onSelectAssistant: (String) -> Unit = {},
    onDeleteAssistant: (String) -> Unit = {},
    onImportAssistantCard: (String) -> Unit = {},
    onImportAssistantCardFile: () -> Unit = {},
    onImportWorldBookFile: (WorldBookImportScope) -> Unit = {},
    onDeleteWorldBook: (String) -> Unit = {},
    onCreateKnowledgeBase: (String) -> Unit = {},
    onImportKnowledgeDocument: (String) -> Unit = {},
    onSetKnowledgeBaseSelected: (String, Boolean) -> Unit = { _, _ -> },
    onDeleteKnowledgeBase: (String) -> Unit = {},
    appMenuOpen: Boolean = false,
    onAppMenuOpenChange: (Boolean) -> Unit = {},
    onMeasureImagePromptTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)? = null,
    chatBackground: ChatBackgroundState = ChatBackgroundState(),
    globalChatBackground: ChatBackgroundState = ChatBackgroundState(),
    assistantChatBackground: ChatBackgroundState? = null,
    sessionChatBackground: ChatBackgroundState? = null,
    hasActiveChatSession: Boolean = false,
    chatBackgroundImporting: Boolean = false,
    onChatBackgroundChange: (ChatBackgroundScope, ChatBackgroundState?) -> Unit = { _, _ -> },
    onChatBackgroundImageSelected: (ChatBackgroundScope, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val imageParameterPreferences = remember(context) {
        context.applicationContext.getSharedPreferences(
            IMAGE_GENERATION_UI_PARAMETER_PREFS,
            Context.MODE_PRIVATE
        )
    }
    val generationImageGrantMutex = remember { Mutex() }
    var showImages by rememberSaveable { mutableStateOf(false) }
    var showAssistants by rememberSaveable { mutableStateOf(false) }
    var showFileLibrary by rememberSaveable { mutableStateOf(false) }
    var showBackgroundSettings by rememberSaveable { mutableStateOf(false) }
    var pendingBackgroundScope by rememberSaveable { mutableStateOf(ChatBackgroundScope.ASSISTANT) }
    var imagePrompt by rememberSaveable { mutableStateOf("") }
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
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return
        if (imageTaskMode !in selectedImageTaskModes) {
            Toast.makeText(context, "当前模型不支持此生成方式", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "请先选择当前模式需要的图片输入", Toast.LENGTH_SHORT).show()
            return
        }
        val strengthValue = imageStrengthText.toDoubleOrNull()
        if (imageTaskMode in setOf(
                ImageGenerationUiTaskMode.IMG2IMG,
                ImageGenerationUiTaskMode.INPAINT
            ) && (strengthValue == null || strengthValue < 0.0 || strengthValue > 1.0)
        ) {
            Toast.makeText(context, "重绘强度必须在 [0, 1]", Toast.LENGTH_SHORT).show()
            return
        }
        val controlStrengthValue = imageControlStrengthText.toDoubleOrNull()
        if (imageTaskMode == ImageGenerationUiTaskMode.CONTROL &&
            (controlStrengthValue == null || controlStrengthValue < 0.0 || controlStrengthValue > 2.0)
        ) {
            Toast.makeText(context, "控制强度必须在 [0, 2]", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "CLIP skip 必须为 -1 或 0-32", Toast.LENGTH_SHORT).show()
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
                    heightValue % localControls.imageHeightMultiple == 0
                )
        }
        if (!dimensionsValid) {
            val message = when {
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
            Toast.makeText(context, "CFG 必须为 0-30 的有限数值", Toast.LENGTH_SHORT).show()
            return
        }
        val seedValue = localControls?.let {
            imageSeedText.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
        }
        if (localControls != null && imageSeedText.isNotBlank() &&
            (seedValue == null || seedValue < 0)
        ) {
            Toast.makeText(context, "Seed 必须为非负 32 位整数", Toast.LENGTH_SHORT).show()
            return
        }
        val sampleMethodValue = localControls?.let { imageSampler.trim() }
        if (localControls != null && (
                sampleMethodValue.isNullOrBlank() ||
                    sampleMethodValue !in selectedImageSupportedSamplers
                )
        ) {
            Toast.makeText(context, "请选择当前模型支持的采样器", Toast.LENGTH_SHORT).show()
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
    LaunchedEffect(state.messages.size, state.isGenerating, streamingScrollBucket) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }
    val historyBackEnabled = drawerState.currentValue == DrawerValue.Open ||
        drawerState.targetValue == DrawerValue.Open
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
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                state = state,
                onClose = { scope.launch { drawerState.close() } },
                onNewConversation = {
                    onNewConversation()
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
        ) {
            ChatBackground(
                state = chatBackground,
                modifier = Modifier.fillMaxSize()
            )
            Column(modifier = Modifier.fillMaxSize()) {
                ChatStatusBar(
                    state = state,
                    onOpenHistory = { scope.launch { drawerState.open() } },
                    onNewConversation = onNewConversation,
                    onLoadModel = onLoadModel,
                    onOpenModels = onOpenModels,
                    onReasoningModeChange = onReasoningModeChange,
                    onCloudReasoningModeLocked = onCloudReasoningModeLocked,
                    onOpenBackgroundSettings = { showBackgroundSettings = true },
                    onOpenAppMenu = { onAppMenuOpenChange(true) }
                )

                state.promptContextUsage?.let { usage ->
                    PromptContextUsageLine(usage)
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    contentPadding = PaddingValues(top = 26.dp, bottom = 118.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    if (state.messages.isEmpty()) {
                        item { EmptyChatPanel(state.selectedModelName) }
                    }
                    val lastAssistantIndex = state.messages.indexOfLast { it.role == Role.ASSISTANT }
                    itemsIndexed(
                        items = state.messages,
                        key = { index, message -> "${message.role.name}:${message.createdAt}:$index" }
                    ) { index, message ->
                        MessageBubble(
                            message = message,
                            showAssistantActions = index == lastAssistantIndex && message.role == Role.ASSISTANT,
                            canRegenerate = !state.isGenerating,
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
                            onDeleteLastTurn = onDeleteLastTurn
                        )
                    }
                }
            }

            ChatInputBar(
                input = state.input,
                isGenerating = state.isGenerating,
                statusMessage = state.statusMessage,
                onInputChange = onInputChange,
                onDismissStatusMessage = onDismissStatusMessage,
                onSend = onSend,
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
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            if (showBackgroundSettings) {
                ChatBackgroundSettingsDialog(
                    effectiveState = chatBackground,
                    globalState = globalChatBackground,
                    assistantState = assistantChatBackground,
                    sessionState = sessionChatBackground,
                    hasActiveSession = hasActiveChatSession,
                    importing = chatBackgroundImporting,
                    onDismiss = { showBackgroundSettings = false },
                    onChooseImage = { scope ->
                        pendingBackgroundScope = scope
                        chatBackgroundPicker.launch(arrayOf("image/*"))
                    },
                    onChange = onChatBackgroundChange,
                    onReset = { scope -> onChatBackgroundChange(scope, null) }
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
                    selectedAssistantId = state.selectedAssistantId,
                    hasActiveConversation = state.activeConversationId != null,
                    selectedModelName = state.selectedModelName,
                    selectedModelId = state.selectedModelId,
                    selectedModelIsCloud = state.selectedModelIsCloud,
                    onSaveAssistant = onSaveAssistant,
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
                    onStepsTextChange = { imageStepsText = it },
                    onCfgScaleTextChange = { imageCfgScaleText = it },
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

            PredictiveAppMenuPage(
                visible = appMenuOpen,
                onDismiss = { onAppMenuOpenChange(false) }
            ) { menuModifier, closeMenu ->
                McaAppMenuPage(
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
                    onOpenImages = {
                        closeMenu()
                        showImages = true
                    },
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
}

@Composable
private fun AssistantRoleScreen(
    assistants: List<AssistantUiItem>,
    worldBooks: List<WorldBookUiItem>,
    knowledgeBases: List<KnowledgeBaseUiItem>,
    selectedAssistantId: String,
    hasActiveConversation: Boolean,
    selectedModelName: String?,
    selectedModelId: String?,
    selectedModelIsCloud: Boolean,
    onSaveAssistant: (AssistantEditorDraft) -> Unit,
    onSelectAssistant: (String) -> Unit,
    onDeleteAssistant: (String) -> Unit,
    onImportAssistantCard: (String) -> Unit,
    onImportAssistantCardFile: () -> Unit,
    onImportWorldBookFile: (WorldBookImportScope) -> Unit,
    onDeleteWorldBook: (String) -> Unit,
    onCreateKnowledgeBase: (String) -> Unit,
    onImportKnowledgeDocument: (String) -> Unit,
    onSetKnowledgeBaseSelected: (String, Boolean) -> Unit,
    onDeleteKnowledgeBase: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<AssistantUiItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var managingContext by remember { mutableStateOf(false) }
    val selected = assistants.firstOrNull { it.id == selectedAssistantId } ?: assistants.firstOrNull()
    val editingAssistant = editing
    fun closeAssistantSubPage() {
        creating = false
        editing = null
        importing = false
        managingContext = false
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .navigationBarsPadding()
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("助手与角色", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "当前助手、角色卡、提示词与能力",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { creating = true }) {
                    Text("新建")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            AssistantCurrentCard(
                assistant = selected,
                selectedModelName = selectedModelName,
                onEdit = { selected?.let { editing = it } },
                onExport = {
                    selected?.let { item ->
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA assistant", item.exportJson)))
                            Toast.makeText(context, "已复制角色卡 JSON", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { managingContext = true },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("世界书与知识库")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "助手列表",
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 14.dp)
            ) {
                items(assistants) { assistant ->
                    AssistantListCard(
                        assistant = assistant,
                        onSelect = { onSelectAssistant(assistant.id) },
                        onEdit = { editing = assistant },
                        onDelete = { onDeleteAssistant(assistant.id) }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { importing = true }, modifier = Modifier.weight(1f)) {
                    Text("导入角色卡")
                }
                Button(
                    onClick = {
                        selected?.let { item ->
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA assistant", item.exportJson)))
                                Toast.makeText(context, "已复制当前助手角色卡", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出当前助手")
                }
            }
        }

        SmoothRightToLeftPage(
            visible = creating || editingAssistant != null || importing || managingContext,
            onDismiss = ::closeAssistantSubPage
        ) { pageModifier, closePage ->
            when {
                managingContext -> ContextLibraryPage(
                    worldBooks = worldBooks,
                    knowledgeBases = knowledgeBases,
                    hasActiveConversation = hasActiveConversation,
                    onBack = closePage,
                    onImportWorldBook = onImportWorldBookFile,
                    onDeleteWorldBook = onDeleteWorldBook,
                    onCreateKnowledgeBase = onCreateKnowledgeBase,
                    onImportKnowledgeDocument = onImportKnowledgeDocument,
                    onSetKnowledgeBaseSelected = onSetKnowledgeBaseSelected,
                    onDeleteKnowledgeBase = onDeleteKnowledgeBase,
                    modifier = pageModifier
                )
                creating -> AssistantEditorPage(
                    assistant = null,
                    onBack = closePage,
                    selectedModelName = selectedModelName,
                    selectedModelId = selectedModelId,
                    selectedModelIsCloud = selectedModelIsCloud,
                    onDelete = onDeleteAssistant,
                    onSave = {
                        onSaveAssistant(it)
                        closePage()
                    },
                    modifier = pageModifier
                )
                editingAssistant != null -> AssistantEditorPage(
                    assistant = editingAssistant,
                    onBack = closePage,
                    selectedModelName = selectedModelName,
                    selectedModelId = selectedModelId,
                    selectedModelIsCloud = selectedModelIsCloud,
                    onDelete = { id ->
                        onDeleteAssistant(id)
                        closePage()
                    },
                    onSave = {
                        onSaveAssistant(it)
                        closePage()
                    },
                    modifier = pageModifier
                )
                importing -> AssistantImportPage(
                    onBack = closePage,
                    onImportFile = onImportAssistantCardFile,
                    onImport = {
                        onImportAssistantCard(it)
                        closePage()
                    },
                    modifier = pageModifier
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun AssistantCurrentCard(
    assistant: AssistantUiItem?,
    selectedModelName: String?,
    onEdit: () -> Unit,
    onExport: () -> Unit
) {
    val assistantTag = assistant?.tag.orEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("当前助手", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistantAvatar(assistant?.name ?: "MCA", assistant?.avatar.orEmpty(), selected = true)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        assistant?.name ?: "默认助手",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (assistantTag.isNotBlank()) {
                        Text(
                            assistantTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        assistant?.modelSummary?.takeIf { it.isNotBlank() } ?: selectedModelName ?: "跟随当前模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistantCapabilityChip("记忆", assistant?.memoryEnabled == true)
                AssistantCapabilityChip("联网检索", assistant?.webSearchEnabled == true)
                AssistantCapabilityChip("文件上下文", assistant?.fileContextEnabled != false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onExport) { Text("导出") }
            }
        }
    }
}

@Composable
private fun AssistantListCard(
    assistant: AssistantUiItem,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (assistant.selected) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFEAF1FF)
        } else {
            if (darkTheme) MaterialTheme.colorScheme.surface else Color(0xFFF8F9FA)
        },
        shape = RoundedCornerShape(20.dp),
        border = if (assistant.selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)) else null
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistantAvatar(assistant.name, assistant.avatar, selected = assistant.selected)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        assistant.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        assistant.tag.ifBlank { assistant.systemPrompt.ifBlank { "未设置提示词" } },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (assistant.selected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onSelect, enabled = !assistant.selected) { Text(if (assistant.selected) "使用中" else "切换") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete, enabled = assistant.id != "default") {
                    Text("删除", color = if (assistant.id == "default") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AssistantEditorPage(
    assistant: AssistantUiItem?,
    onBack: () -> Unit,
    selectedModelName: String?,
    selectedModelId: String?,
    selectedModelIsCloud: Boolean,
    onDelete: (String) -> Unit,
    onSave: (AssistantEditorDraft) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember(assistant?.id) { mutableStateOf(assistant?.name ?: "") }
    var avatar by remember(assistant?.id) { mutableStateOf(assistant?.avatar ?: "") }
    var tag by remember(assistant?.id) { mutableStateOf(assistant?.tag ?: "") }
    var prompt by remember(assistant?.id) {
        mutableStateOf((assistant?.systemPrompt ?: "").take(MAX_ASSISTANT_SYSTEM_PROMPT_CHARS))
    }
    var defaultModelMode by remember(assistant?.id) { mutableStateOf(assistant?.defaultModelMode ?: "follow_current") }
    var defaultModelId by remember(assistant?.id) { mutableStateOf(assistant?.defaultModelId) }
    var temperatureText by remember(assistant?.id) { mutableStateOf((assistant?.temperature ?: GenerationParams().temperature).cleanParamText()) }
    var topPText by remember(assistant?.id) { mutableStateOf((assistant?.topP ?: GenerationParams().topP).cleanParamText()) }
    var nPredictText by remember(assistant?.id) { mutableStateOf((assistant?.nPredict ?: GenerationParams().nPredict).toString()) }
    var reasoningMode by remember(assistant?.id) { mutableStateOf(assistant?.reasoningMode ?: GenerationParams().reasoningMode) }
    var memoryEnabled by remember(assistant?.id) { mutableStateOf(assistant?.memoryEnabled ?: false) }
    var webSearchEnabled by remember(assistant?.id) { mutableStateOf(assistant?.webSearchEnabled ?: false) }
    var fileContextEnabled by remember(assistant?.id) { mutableStateOf(assistant?.fileContextEnabled ?: true) }
    fun buildDraft(id: String?, draftName: String = name): AssistantEditorDraft =
        AssistantEditorDraft(
            id = id,
            name = draftName,
            avatar = avatar,
            tag = tag,
            systemPrompt = prompt,
            defaultModelMode = defaultModelMode,
            defaultModelId = defaultModelId,
            temperature = temperatureText.toAssistantFloat(assistant?.temperature ?: GenerationParams().temperature, 0f, 2f),
            topP = topPText.toAssistantFloat(assistant?.topP ?: GenerationParams().topP, 0f, 1f),
            nPredict = nPredictText.toAssistantInt(assistant?.nPredict ?: GenerationParams().nPredict, 128, 65_536),
            reasoningMode = reasoningMode,
            memoryEnabled = memoryEnabled,
            webSearchEnabled = webSearchEnabled,
            fileContextEnabled = fileContextEnabled
        )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回助手列表")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(if (assistant == null) "新建助手" else "编辑助手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("角色卡、提示词、默认模型与生成参数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
                item {
                    Text("基础信息", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = avatar,
                            onValueChange = { avatar = it.take(4) },
                            label = { Text("头像") },
                            singleLine = true,
                            modifier = Modifier.weight(0.72f)
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("助手名称") },
                            singleLine = true,
                            modifier = Modifier.weight(1.28f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tag,
                        onValueChange = { tag = it.take(24) },
                        label = { Text("标签") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it.take(MAX_ASSISTANT_SYSTEM_PROMPT_CHARS) },
                        label = { Text("系统提示词") },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { prompt = GenerationParams().systemPrompt }) {
                        Text("恢复默认提示词")
                    }
                }
                item {
                    Text("默认模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = defaultModelMode == "follow_current",
                            onClick = {
                                defaultModelMode = "follow_current"
                                defaultModelId = null
                            },
                            label = { Text("跟随当前") }
                        )
                        FilterChip(
                            selected = defaultModelMode != "follow_current",
                            enabled = selectedModelId != null,
                            onClick = {
                                defaultModelMode = if (selectedModelIsCloud) "cloud" else "local"
                                defaultModelId = selectedModelId
                            },
                            label = {
                                Text(
                                    when (defaultModelMode) {
                                        "cloud" -> "已绑定云端"
                                        "local" -> "已绑定本地"
                                        else -> if (selectedModelIsCloud) "绑定当前云端" else "绑定当前本地"
                                    }
                                )
                            }
                        )
                    }
                    Text(
                        when {
                            defaultModelMode == "follow_current" -> "切换到该助手时继续使用聊天页当前模型。"
                            defaultModelId.isNullOrBlank() -> "当前绑定缺少模型，请先在聊天页选择可用模型。"
                            else -> "切换到该助手时优先使用：${assistant?.modelSummary ?: selectedModelName ?: "已绑定模型"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Text("能力", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(selected = memoryEnabled, onClick = { memoryEnabled = !memoryEnabled }, label = { Text("记忆") })
                        FilterChip(selected = webSearchEnabled, onClick = { webSearchEnabled = !webSearchEnabled }, label = { Text("联网检索") })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(selected = fileContextEnabled, onClick = { fileContextEnabled = !fileContextEnabled }, label = { Text("文件上下文") })
                        FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("本地工具预留") })
                    }
                }
                item {
                    Text("参数", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = temperatureText,
                            onValueChange = { temperatureText = it },
                            label = { Text("温度") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = topPText,
                            onValueChange = { topPText = it },
                            label = { Text("top_p") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nPredictText,
                        onValueChange = { nPredictText = it },
                        label = { Text("输出长度") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ReasoningMode.entries.forEach { mode ->
                            FilterChip(
                                selected = reasoningMode == mode,
                                onClick = { reasoningMode = mode },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    Text(
                        "助手只保存生成参数；上下文、线程和 native 加载参数属于当前模型配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Text("角色卡", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                val copyName = name.trim().ifBlank { assistant?.name ?: "未命名助手" }.let { "$it 副本" }
                                onSave(buildDraft(null, copyName))
                            },
                            enabled = assistant != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("复制为新助手")
                        }
                        TextButton(
                            onClick = { assistant?.id?.let(onDelete) },
                            enabled = assistant != null && assistant.id != "default",
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "删除助手",
                                color = if (assistant != null && assistant.id != "default") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = { onSave(buildDraft(assistant?.id)) },
                    modifier = Modifier.weight(1.45f).height(48.dp),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text("保存")
                }
            }
        }
}

@Composable
private fun ContextLibraryPage(
    worldBooks: List<WorldBookUiItem>,
    knowledgeBases: List<KnowledgeBaseUiItem>,
    hasActiveConversation: Boolean,
    onBack: () -> Unit,
    onImportWorldBook: (WorldBookImportScope) -> Unit,
    onDeleteWorldBook: (String) -> Unit,
    onCreateKnowledgeBase: (String) -> Unit,
    onImportKnowledgeDocument: (String) -> Unit,
    onSetKnowledgeBaseSelected: (String, Boolean) -> Unit,
    onDeleteKnowledgeBase: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var knowledgeBaseName by rememberSaveable { mutableStateOf("") }
    var worldBookScopeMenuOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .navigationBarsPadding()
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回助手列表")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("上下文资料", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "世界书与本地知识库",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("世界书", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { worldBookScopeMenuOpen = true }) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("导入")
                        }
                        DropdownMenu(
                            expanded = worldBookScopeMenuOpen,
                            onDismissRequest = { worldBookScopeMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("导入到全局") },
                                onClick = {
                                    worldBookScopeMenuOpen = false
                                    onImportWorldBook(WorldBookImportScope.GLOBAL)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导入到当前角色") },
                                onClick = {
                                    worldBookScopeMenuOpen = false
                                    onImportWorldBook(WorldBookImportScope.ASSISTANT)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导入到当前对话") },
                                enabled = hasActiveConversation,
                                onClick = {
                                    worldBookScopeMenuOpen = false
                                    onImportWorldBook(WorldBookImportScope.CHAT)
                                }
                            )
                        }
                    }
                }
            }
            if (worldBooks.isEmpty()) {
                item {
                    Text(
                        "尚未导入世界书",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(worldBooks, key = { it.id }) { book ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(book.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${book.scopeLabel} · ${book.entryCount} 条",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onDeleteWorldBook(book.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除世界书")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                }
            }
            item {
                Spacer(modifier = Modifier.height(14.dp))
                Text("知识库", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = knowledgeBaseName,
                        onValueChange = { knowledgeBaseName = it.take(96) },
                        label = { Text("知识库名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val name = knowledgeBaseName.trim()
                            if (name.isNotEmpty()) {
                                onCreateKnowledgeBase(name)
                                knowledgeBaseName = ""
                            }
                        },
                        enabled = knowledgeBaseName.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "创建知识库")
                    }
                }
            }
            if (knowledgeBases.isEmpty()) {
                item {
                    Text(
                        "尚未创建知识库",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(knowledgeBases, key = { it.id }) { knowledgeBase ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = knowledgeBase.selected,
                            onCheckedChange = { selected ->
                                onSetKnowledgeBaseSelected(knowledgeBase.id, selected)
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                knowledgeBase.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                buildList {
                                    knowledgeBase.description.takeIf { it.isNotBlank() }?.let(::add)
                                    add("${knowledgeBase.documentCount} 个文件")
                                    add(if (knowledgeBase.importing) "导入中" else knowledgeBase.indexStateLabel)
                                }.joinToString(" · "),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onImportKnowledgeDocument(knowledgeBase.id) },
                            enabled = !knowledgeBase.importing
                        ) {
                            if (knowledgeBase.importing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Folder, contentDescription = "导入知识库文档")
                            }
                        }
                        IconButton(onClick = { onDeleteKnowledgeBase(knowledgeBase.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除知识库")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                }
            }
        }
    }
}

@Composable
private fun AssistantImportPage(
    onBack: () -> Unit,
    onImportFile: () -> Unit,
    onImport: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var rawJson by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回助手列表")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("导入角色卡", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("粘贴 MCA 角色卡 JSON", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp)
        ) {
            OutlinedTextField(
                value = rawJson,
                onValueChange = { rawJson = it },
                label = { Text("角色卡 JSON") },
                minLines = 8,
                modifier = Modifier.fillMaxSize().padding(12.dp)
            )
        }
        Button(
            onClick = onImportFile,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("选择 PNG / JSON 文件")
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("取消")
                }
                Button(
                    onClick = { onImport(rawJson) },
                    enabled = rawJson.isNotBlank(),
                    modifier = Modifier.weight(1.45f).height(48.dp),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text("导入")
                }
            }
        }
    }
}

@Composable
private fun AssistantAvatar(name: String, avatar: String, selected: Boolean) {
    val label = avatar.trim().ifBlank { name.trim() }.firstOrNull()?.toString() ?: "M"
    Surface(
        modifier = Modifier.size(42.dp),
        color = if (selected) McaPrimaryBlue else MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AssistantCapabilityChip(label: String, enabled: Boolean) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        color = when {
            enabled && darkTheme -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
            enabled -> Color(0xFFE6F4EA)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            "$label${if (enabled) "开" else "关"}",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = when {
                enabled && darkTheme -> MaterialTheme.colorScheme.onPrimaryContainer
                enabled -> Color(0xFF137333)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun Float.cleanParamText(): String =
    if (this % 1f == 0f) toInt().toString() else "%.2f".format(java.util.Locale.US, this).trimEnd('0').trimEnd('.')

private fun String.toAssistantFloat(default: Float, min: Float, max: Float): Float =
    trim().toFloatOrNull()?.coerceIn(min, max) ?: default.coerceIn(min, max)

private fun String.toAssistantInt(default: Int, min: Int, max: Int): Int =
    trim().toIntOrNull()?.coerceIn(min, max) ?: default.coerceIn(min, max)

@Composable
private fun ImagesWorkspaceScreen(
    images: List<ImageAssetUiItem>,
    generationStartSignal: Long,
    backupState: ImageLibraryBackupUiState,
    jobs: List<ImageGenerationUiJob>,
    imageModels: List<ChatModelChoice>,
    selectedImageModelId: String?,
    selectedImageModelName: String?,
    selectedImageModelIsCloud: Boolean,
    supportedTaskModes: Set<ImageGenerationUiTaskMode>,
    supportsNegativePrompt: Boolean,
    supportsClipSkip: Boolean,
    supportsVaeTiling: Boolean,
    supportsTextualInversion: Boolean,
    supportsUltraFix: Boolean,
    supportsLora: Boolean,
    supportsLivePreview: Boolean,
    loras: List<ImageLoraUiItem>,
    selectedLoras: List<ImageGenerationUiLoraDraft>,
    loraRestoreWarning: String?,
    loraImporting: Boolean,
    loraMessage: String,
    textualInversions: List<ImageTextualInversionUiItem>,
    selectedTextualInversionIds: List<String>,
    textualInversionImporting: Boolean,
    textualInversionMessage: String,
    upscalers: List<ImageUpscalerUiItem>,
    selectedUpscalerId: String?,
    upscalerImporting: Boolean,
    upscalerMessage: String,
    upscaleJob: ImageUpscaleUiJob?,
    batchCount: Int,
    maxBatchCount: Int,
    taskMode: ImageGenerationUiTaskMode,
    inputImageUri: String?,
    inputImageWidth: Int,
    inputImageHeight: Int,
    maskImageUri: String?,
    controlImageUri: String?,
    inputRestoreWarning: String?,
    strengthText: String,
    controlStrengthText: String,
    negativePrompt: String,
    disableModelNegativePrompt: Boolean,
    clipSkipText: String,
    vaeTilingEnabled: Boolean,
    ultraFixEnabled: Boolean,
    ultraFixStrengthText: String,
    ultraFixInversionStepsText: String,
    ultraFixRefinementStepsText: String,
    ultraFixTileSizeText: String,
    ultraFixOverlapText: String,
    livePreviewEnabled: Boolean,
    livePreviewInterval: Int,
    widthText: String,
    heightText: String,
    stepsText: String,
    cfgScaleText: String,
    seedText: String,
    sampler: String,
    prompt: String,
    onMeasureImagePromptTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)?,
    onPromptChange: (String) -> Unit,
    onSubmitPrompt: () -> Unit,
    onCancelGeneration: () -> Unit,
    onRetryJob: (ImageGenerationUiJob) -> Unit,
    onRecreateImageAsset: (String) -> Unit,
    onBack: () -> Unit,
    onTaskModeChange: (ImageGenerationUiTaskMode) -> Unit,
    onPickImageRole: (String) -> Unit,
    onCropImageRole: (String) -> Unit,
    onDrawImageMask: () -> Unit,
    onClearImageRole: (String) -> Unit,
    onStrengthTextChange: (String) -> Unit,
    onControlStrengthTextChange: (String) -> Unit,
    onNegativePromptChange: (String) -> Unit,
    onDisableModelNegativePromptChange: (Boolean) -> Unit,
    onClipSkipTextChange: (String) -> Unit,
    onVaeTilingEnabledChange: (Boolean) -> Unit,
    onLivePreviewEnabledChange: (Boolean) -> Unit,
    onLivePreviewIntervalChange: (Int) -> Unit,
    onToggleLora: (String) -> Unit,
    onLoraMultiplierChange: (String, String) -> Unit,
    onImportLora: () -> Unit,
    onDeleteLora: (String) -> Unit,
    onToggleTextualInversion: (String) -> Unit,
    onImportTextualInversion: () -> Unit,
    onDeleteTextualInversion: (String) -> Unit,
    onUltraFixEnabledChange: (Boolean) -> Boolean,
     onResetUltraFix: () -> Unit,
     onUltraFixDenoisingStepsTextChange: (String) -> Unit,
    onUltraFixRefinementStepsTextChange: (String) -> Unit,
    onUltraFixTileSizeTextChange: (String) -> Unit,
    onUltraFixOverlapTextChange: (String) -> Unit,
    onImportUpscaler: () -> Unit,
    onDeleteUpscaler: (String) -> Unit,
    onSelectUpscaler: (String) -> Unit,
    onUpscaleImage: (String, Int) -> Unit,
    onCancelUpscale: () -> Unit,
    onBatchCountChange: (Int) -> Unit,
    onWidthTextChange: (String) -> Unit,
    onHeightTextChange: (String) -> Unit,
    onStepsTextChange: (String) -> Unit,
    onCfgScaleTextChange: (String) -> Unit,
    onSeedTextChange: (String) -> Unit,
    onSamplerChange: (String) -> Unit,
    onSelectImageModel: (String) -> Unit,
    onUseImageAsGenerationInput: (String, Int, Int) -> Unit,
    onUseImageAsset: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit,
    onDeleteImageAssets: (List<String>) -> Unit,
    onSetImageAssetFavorite: (String, Boolean) -> Unit,
    onExportBackup: (String, Boolean) -> Unit,
    onImportBackup: (String) -> Unit,
    onCancelBackup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val darkTheme = isSystemInDarkTheme()
    var showGenerationCanvas by rememberSaveable { mutableStateOf(false) }
    var pendingConversationPrompt by rememberSaveable { mutableStateOf("") }
    var previewImageId by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var libraryModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryTaskMode by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryOperation by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryDateStartUtcMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var libraryDateEndUtcMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var libraryDimensionsKey by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryScheduler by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryRuntime by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedImageIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingDeleteIds by remember { mutableStateOf(emptySet<String>()) }
    var batchSaving by remember { mutableStateOf(false) }
    var showBackupDialog by rememberSaveable { mutableStateOf(false) }
    var backupFavoritesOnly by rememberSaveable { mutableStateOf(false) }
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { onExportBackup(it.toString(), backupFavoritesOnly) }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onImportBackup(it.toString()) }
    }
    val libraryModels = remember(images) {
        images.mapNotNull { image ->
            image.generationModelId.takeIf(String::isNotBlank)?.let { id ->
                id to image.generationModelName.ifBlank { id }
            }
        }.distinctBy { it.first }
    }
    val libraryTaskModes = remember(images) {
        images.map(ImageAssetUiItem::generationTaskMode)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }
    val libraryOperations = remember(images) {
        images.mapNotNull { imageLibraryOperationWireName(it) }
            .distinct()
            .sorted()
    }
    val libraryDimensions = remember(images) {
        images.mapNotNull(ImageLibraryDimensions::from)
            .distinct()
            .sortedWith(compareBy<ImageLibraryDimensions> { it.width * it.height }.thenBy { it.key })
    }
    val librarySchedulers = remember(images) {
        images.map(ImageAssetUiItem::generationSampler)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }
    val libraryRuntimes = remember(images) {
        images.map(ImageAssetUiItem::generationRuntime)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }
    val libraryDevices = remember(images) {
        images.map(ImageAssetUiItem::generationDevice)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }
    val libraryDateRange = remember(libraryDateStartUtcMillis, libraryDateEndUtcMillis) {
        imageLibraryDateRangeFromUtcPickerSelection(
            selectedStartDateMillis = libraryDateStartUtcMillis,
            selectedEndDateMillis = libraryDateEndUtcMillis
        )
    }
    val libraryDimensionsFilter = remember(libraryDimensionsKey) {
        ImageLibraryDimensions.fromKeyOrNull(libraryDimensionsKey)
    }
    val filteredImages = remember(
        images,
        libraryQuery,
        favoritesOnly,
        libraryModelId,
        libraryTaskMode,
        libraryOperation,
        libraryDateRange,
        libraryDimensionsFilter,
        libraryScheduler,
        libraryRuntime,
        libraryDevice,
        newestFirst
    ) {
        filterImageLibrary(
            images = images,
            filter = ImageLibraryFilter(
                query = libraryQuery,
                favoritesOnly = favoritesOnly,
                modelId = libraryModelId,
                taskMode = libraryTaskMode,
                operation = libraryOperation,
                dateRange = libraryDateRange,
                dimensions = libraryDimensionsFilter,
                scheduler = libraryScheduler,
                runtime = libraryRuntime,
                device = libraryDevice,
                newestFirst = newestFirst
            )
        )
    }
    val latestJob = jobs.firstOrNull()
    val activeJob = if (showGenerationCanvas) latestJob else null
    val activePrompt = activeJob?.prompt ?: pendingConversationPrompt
    val activeImage = activeJob?.imageAssetId?.let { imageId ->
        images.firstOrNull { it.id == imageId }
    } ?: activeJob
        ?.takeIf { it.statusLabel == "完成" }
        ?.let { doneJob -> images.firstOrNull { it.prompt == doneJob.prompt } }
    val isImageGenerating = activeJob?.isWorking == true
    val canvasModelId = activeJob?.modelId ?: selectedImageModelId
    val canvasModelName = activeJob?.modelName?.takeIf(String::isNotBlank) ?: selectedImageModelName
    val canvasModelIsCloud = activeJob?.modelIsCloud ?: selectedImageModelIsCloud

    LaunchedEffect(images.map(ImageAssetUiItem::id)) {
        val available = images.mapTo(mutableSetOf(), ImageAssetUiItem::id)
        selectedImageIds = selectedImageIds.intersect(available)
        pendingDeleteIds = pendingDeleteIds.intersect(available)
        if (selectedImageIds.isEmpty() && images.isEmpty()) selectionMode = false
    }

    BackHandler(enabled = showGenerationCanvas) {
        showGenerationCanvas = false
    }
    BackHandler(enabled = previewImageId != null) {
        previewImageId = null
    }
    LaunchedEffect(generationStartSignal) {
        if (generationStartSignal > 0L) showGenerationCanvas = true
    }

    fun submitFromImages() {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return
        if (taskMode !in supportedTaskModes) {
            Toast.makeText(context, "当前模型不支持此生成方式", Toast.LENGTH_SHORT).show()
            return
        }
        val ready = when (taskMode) {
            ImageGenerationUiTaskMode.TEXT_TO_IMAGE -> true
            ImageGenerationUiTaskMode.IMG2IMG,
            ImageGenerationUiTaskMode.EDIT -> inputImageUri != null
            ImageGenerationUiTaskMode.INPAINT -> inputImageUri != null && maskImageUri != null
            ImageGenerationUiTaskMode.CONTROL -> controlImageUri != null
        }
        if (!ready) {
            Toast.makeText(context, "请先选择当前模式需要的图片输入", Toast.LENGTH_SHORT).show()
            return
        }
        pendingConversationPrompt = cleanPrompt
        onSubmitPrompt()
    }

    val selectedTextualInversionIdsForAutocomplete = selectedTextualInversionIds.toSet()
    ImagePromptTagAutocompleteProvider(
        textualInversionCompletions = textualInversions
            .asSequence()
            .filter(ImageTextualInversionUiItem::compatibleWithSelectedModel)
            .map { artifact ->
                ImagePromptTextualInversionCompletion(
                    id = artifact.id,
                    name = artifact.name,
                    trigger = artifact.trigger,
                    selected = artifact.id in selectedTextualInversionIdsForAutocomplete,
                )
            }
            .toList(),
        onActivateTextualInversion = activate@ { id ->
            val artifact = textualInversions.firstOrNull { candidate ->
                candidate.id == id && candidate.compatibleWithSelectedModel
            } ?: return@activate false
            if (id in selectedTextualInversionIdsForAutocomplete) return@activate true
            if (selectedTextualInversionIdsForAutocomplete.size >= 8) {
                Toast.makeText(
                    context,
                    "单次最多选择 8 个 Textual Inversion",
                    Toast.LENGTH_SHORT,
                ).show()
                return@activate false
            }
            onToggleTextualInversion(artifact.id)
            true
        },
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(if (darkTheme) MaterialTheme.colorScheme.background else Color.White)
                .navigationBarsPadding()
                .imePadding()
        ) {
        if (showGenerationCanvas) {
            ImageGenerationCanvas(
                prompt = activePrompt,
                job = activeJob,
                image = activeImage,
                imageModels = imageModels,
                selectedImageModelId = canvasModelId,
                selectedImageModelName = canvasModelName,
                selectedImageModelIsCloud = canvasModelIsCloud,
                onBackToGallery = { showGenerationCanvas = false },
                onSelectImageModel = onSelectImageModel,
                onRetry = { activeJob?.let(onRetryJob) },
                onCancelGeneration = onCancelGeneration,
                onUseImageAsset = onUseImageAsset
            )
        } else {
            ImageGalleryHome(
                images = filteredImages,
                totalImageCount = images.size,
                libraryQuery = libraryQuery,
                favoritesOnly = favoritesOnly,
                selectedLibraryModelId = libraryModelId,
                selectedLibraryTaskMode = libraryTaskMode,
                selectedLibraryOperation = libraryOperation,
                selectedLibraryDateRange = libraryDateRange,
                selectedLibraryDateStartUtcMillis = libraryDateStartUtcMillis,
                selectedLibraryDateEndUtcMillis = libraryDateEndUtcMillis,
                selectedLibraryDimensionsKey = libraryDimensionsKey,
                selectedLibraryScheduler = libraryScheduler,
                selectedLibraryRuntime = libraryRuntime,
                selectedLibraryDevice = libraryDevice,
                newestFirst = newestFirst,
                libraryModels = libraryModels,
                libraryTaskModes = libraryTaskModes,
                libraryOperations = libraryOperations,
                libraryDimensions = libraryDimensions,
                librarySchedulers = librarySchedulers,
                libraryRuntimes = libraryRuntimes,
                libraryDevices = libraryDevices,
                selectionMode = selectionMode,
                selectedImageIds = selectedImageIds,
                batchSaving = batchSaving,
                imageModels = imageModels,
                imageModelSwitchEnabled = jobs.none { it.isWorking },
                selectedImageModelId = selectedImageModelId,
                selectedImageModelName = selectedImageModelName,
                selectedImageModelIsCloud = selectedImageModelIsCloud,
                supportedTaskModes = supportedTaskModes,
                supportsNegativePrompt = supportsNegativePrompt,
                supportsClipSkip = supportsClipSkip,
                supportsVaeTiling = supportsVaeTiling,
                supportsTextualInversion = supportsTextualInversion,
                supportsUltraFix = supportsUltraFix,
                supportsLora = supportsLora,
                supportsLivePreview = supportsLivePreview,
                loras = loras,
                selectedLoras = selectedLoras,
                loraRestoreWarning = loraRestoreWarning,
                loraImporting = loraImporting,
                loraMessage = loraMessage,
                textualInversions = textualInversions,
                selectedTextualInversionIds = selectedTextualInversionIds,
                textualInversionImporting = textualInversionImporting,
                textualInversionMessage = textualInversionMessage,
                batchCount = batchCount,
                maxBatchCount = maxBatchCount,
                taskMode = taskMode,
                inputImageUri = inputImageUri,
                inputImageWidth = inputImageWidth,
                inputImageHeight = inputImageHeight,
                maskImageUri = maskImageUri,
                controlImageUri = controlImageUri,
                inputRestoreWarning = inputRestoreWarning,
                strengthText = strengthText,
                controlStrengthText = controlStrengthText,
                negativePrompt = negativePrompt,
                disableModelNegativePrompt = disableModelNegativePrompt,
                clipSkipText = clipSkipText,
                vaeTilingEnabled = vaeTilingEnabled,
                ultraFixEnabled = ultraFixEnabled,
                ultraFixStrengthText = ultraFixStrengthText,
                ultraFixInversionStepsText = ultraFixInversionStepsText,
                ultraFixRefinementStepsText = ultraFixRefinementStepsText,
                ultraFixTileSizeText = ultraFixTileSizeText,
                ultraFixOverlapText = ultraFixOverlapText,
                livePreviewEnabled = livePreviewEnabled,
                livePreviewInterval = livePreviewInterval,
                widthText = widthText,
                heightText = heightText,
                stepsText = stepsText,
                cfgScaleText = cfgScaleText,
                seedText = seedText,
                sampler = sampler,
                onMeasureImagePromptTokens = onMeasureImagePromptTokens,
                onBack = onBack,
                onSelectImageModel = onSelectImageModel,
                onTaskModeChange = onTaskModeChange,
                onPickImageRole = onPickImageRole,
                onCropImageRole = onCropImageRole,
                onDrawImageMask = onDrawImageMask,
                onClearImageRole = onClearImageRole,
                onStrengthTextChange = onStrengthTextChange,
                onControlStrengthTextChange = onControlStrengthTextChange,
                onNegativePromptChange = onNegativePromptChange,
                onDisableModelNegativePromptChange = onDisableModelNegativePromptChange,
                onClipSkipTextChange = onClipSkipTextChange,
                onVaeTilingEnabledChange = onVaeTilingEnabledChange,
                onLivePreviewEnabledChange = onLivePreviewEnabledChange,
                onLivePreviewIntervalChange = onLivePreviewIntervalChange,
                onToggleLora = onToggleLora,
                onLoraMultiplierChange = onLoraMultiplierChange,
                onImportLora = onImportLora,
                onDeleteLora = onDeleteLora,
                onToggleTextualInversion = onToggleTextualInversion,
                onImportTextualInversion = onImportTextualInversion,
                onDeleteTextualInversion = onDeleteTextualInversion,
                 onUltraFixEnabledChange = onUltraFixEnabledChange,
                 onResetUltraFix = onResetUltraFix,
                 onUltraFixDenoisingStepsTextChange = onUltraFixDenoisingStepsTextChange,
                onUltraFixRefinementStepsTextChange = onUltraFixRefinementStepsTextChange,
                onUltraFixTileSizeTextChange = onUltraFixTileSizeTextChange,
                onUltraFixOverlapTextChange = onUltraFixOverlapTextChange,
                onBatchCountChange = onBatchCountChange,
                onWidthTextChange = onWidthTextChange,
                onHeightTextChange = onHeightTextChange,
                onStepsTextChange = onStepsTextChange,
                onCfgScaleTextChange = onCfgScaleTextChange,
                onSeedTextChange = onSeedTextChange,
                onSamplerChange = onSamplerChange,
                onPromptChange = onPromptChange,
                onOpenImagePreview = { previewImageId = it },
                onDeleteImageAsset = { pendingDeleteIds = setOf(it) },
                onSetImageAssetFavorite = onSetImageAssetFavorite,
                onLibraryQueryChange = { libraryQuery = it },
                onFavoritesOnlyChange = { favoritesOnly = it },
                onLibraryModelChange = { libraryModelId = it },
                onLibraryTaskModeChange = { libraryTaskMode = it },
                onLibraryOperationChange = { libraryOperation = it },
                onLibraryDateRangeChange = { start, end ->
                    libraryDateStartUtcMillis = start
                    libraryDateEndUtcMillis = end
                },
                onLibraryDimensionsChange = { libraryDimensionsKey = it },
                onLibrarySchedulerChange = { libraryScheduler = it },
                onLibraryRuntimeChange = { libraryRuntime = it },
                onLibraryDeviceChange = { libraryDevice = it },
                onNewestFirstChange = { newestFirst = it },
                onClearLibraryFilters = {
                    libraryQuery = ""
                    favoritesOnly = false
                    libraryModelId = null
                    libraryTaskMode = null
                    libraryOperation = null
                    libraryDateStartUtcMillis = null
                    libraryDateEndUtcMillis = null
                    libraryDimensionsKey = null
                    libraryScheduler = null
                    libraryRuntime = null
                    libraryDevice = null
                },
                onSelectionModeChange = { enabled ->
                    selectionMode = enabled
                    if (!enabled) selectedImageIds = emptySet()
                },
                onToggleImageSelection = { imageId ->
                    selectedImageIds = if (imageId in selectedImageIds) {
                        selectedImageIds - imageId
                    } else {
                        selectedImageIds + imageId
                    }
                },
                onSelectAllVisible = {
                    val visibleIds = filteredImages.mapTo(mutableSetOf(), ImageAssetUiItem::id)
                    selectedImageIds = if (
                        visibleIds.isNotEmpty() && visibleIds.all(selectedImageIds::contains)
                    ) {
                        selectedImageIds - visibleIds
                    } else {
                        selectedImageIds + visibleIds
                    }
                },
                onBatchSave = {
                    val selected = images.filter { it.id in selectedImageIds }
                    if (selected.isNotEmpty() && !batchSaving) {
                        scope.launch {
                            batchSaving = true
                            try {
                                val failures = withContext(Dispatchers.IO) {
                                    selected.count { image ->
                                        downloadImageAssetToGallery(context, image).isFailure
                                    }
                                }
                                Toast.makeText(
                                    context,
                                    if (failures == 0) {
                                        "已保存 ${selected.size} 张图片"
                                    } else {
                                        "已保存 ${selected.size - failures} 张，失败 $failures 张"
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            } finally {
                                batchSaving = false
                            }
                        }
                    }
                },
                onBatchDelete = {
                    if (selectedImageIds.isNotEmpty()) pendingDeleteIds = selectedImageIds
                },
                onOpenBackup = { showBackupDialog = true }
            )
        }

        ImagePromptBar(
            prompt = prompt,
            onPromptChange = onPromptChange,
            modelId = selectedImageModelId,
            onMeasureTokens = onMeasureImagePromptTokens,
            onOpenPhoto = {
                when (taskMode) {
                    ImageGenerationUiTaskMode.CONTROL -> onPickImageRole("control")
                    ImageGenerationUiTaskMode.TEXT_TO_IMAGE -> {
                        if (ImageGenerationUiTaskMode.IMG2IMG in supportedTaskModes) {
                            onTaskModeChange(ImageGenerationUiTaskMode.IMG2IMG)
                            onPickImageRole("input")
                        } else {
                            Toast.makeText(context, "当前模型不支持图片输入", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> onPickImageRole("input")
                }
            },
            onSubmit = ::submitFromImages,
            placeholder = if (showGenerationCanvas) "回复 MCA" else "描述图像",
            isGenerating = isImageGenerating,
            onStop = onCancelGeneration,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        previewImageId?.let { imageId ->
            images.firstOrNull { it.id == imageId }?.let { image ->
                val selectedModel = imageModels.firstOrNull { it.id == selectedImageModelId }
                val generationActionsEnabled = jobs.none(ImageGenerationUiJob::isWorking) &&
                    image.uriString.startsWith("content://", ignoreCase = true)
                val canUseAsImg2Img = generationActionsEnabled &&
                    ImageGenerationUiTaskMode.IMG2IMG in supportedTaskModes
                val ultraFixTargetSize = selectedModel
                    ?.takeIf { !selectedImageModelIsCloud && canUseAsImg2Img }
                    ?.ultraFixTargetSizeForSourceOrNull(image.width, image.height)
                val sourcePresetFields = image.generationPreset
                    ?.let(::availableImageGenerationPresetFields)
                    .orEmpty()
                val reusablePresetFields = image.generationPreset?.let { preset ->
                    compatibleImageGenerationPresetFields(
                        preset = preset,
                        selectedModel = selectedModel,
                        selectedModelIsCloud = selectedImageModelIsCloud,
                        supportsNegativePrompt = supportsNegativePrompt,
                        supportsClipSkip = supportsClipSkip,
                        supportsVaeTiling = supportsVaeTiling,
                        supportsLora = supportsLora,
                        availableLoraIds = loras.mapTo(mutableSetOf(), ImageLoraUiItem::id),
                        maxBatchCount = maxBatchCount,
                        currentTaskMode = taskMode,
                        supportsTextualInversion = supportsTextualInversion,
                        supportsUltraFix = supportsUltraFix,
                        availableTextualInversionIds = textualInversions
                            .filter { it.compatibleWithSelectedModel }
                            .mapTo(mutableSetOf(), ImageTextualInversionUiItem::id)
                    ).let { compatible ->
                        if (inputImageUri == null) {
                            compatible - ImageGenerationPresetField.ULTRAFIX
                        } else {
                            compatible
                        }
                    }
                }.orEmpty()
                ImageAssetPreviewOverlay(
                    image = image,
                    upscalers = upscalers,
                    selectedUpscalerId = selectedUpscalerId,
                    upscalerImporting = upscalerImporting,
                    upscalerMessage = upscalerMessage,
                    upscaleJob = upscaleJob,
                    onDismiss = { previewImageId = null },
                    onShare = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                createCachedImageShareIntent(
                                    context = context,
                                    image = image,
                                    includePrompt = false
                                )
                            }
                            result
                                .mapCatching { intent -> context.startActivity(intent) }
                                .onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        error.message?.takeIf(String::isNotBlank) ?: "图片分享失败",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        }
                    },
                    onDelete = {
                        pendingDeleteIds = setOf(image.id)
                    },
                    canUseAsImg2Img = canUseAsImg2Img,
                    canUseUltraFix = ultraFixTargetSize != null,
                    onUseAsImg2Img = {
                        onUseImageAsGenerationInput(image.uriString, image.width, image.height)
                        onTaskModeChange(ImageGenerationUiTaskMode.IMG2IMG)
                        onUltraFixEnabledChange(false)
                        image.generationPrompt.takeIf(String::isNotBlank)?.let(onPromptChange)
                        showGenerationCanvas = true
                        previewImageId = null
                    },
                    onUseUltraFix = onUseUltraFix@ {
                        val target = ultraFixTargetSize ?: return@onUseUltraFix
                        onUseImageAsGenerationInput(image.uriString, image.width, image.height)
                        onTaskModeChange(ImageGenerationUiTaskMode.IMG2IMG)
                        if (!onUltraFixEnabledChange(true)) return@onUseUltraFix
                        onWidthTextChange(target.first.toString())
                        onHeightTextChange(target.second.toString())
                        image.generationPrompt.takeIf(String::isNotBlank)?.let(onPromptChange)
                        showGenerationCanvas = true
                        previewImageId = null
                    },
                    onImportUpscaler = onImportUpscaler,
                    onDeleteUpscaler = onDeleteUpscaler,
                    onSelectUpscaler = onSelectUpscaler,
                    onUpscale = { scale -> onUpscaleImage(image.id, scale) },
                    onCancelUpscale = onCancelUpscale,
                    onSetFavorite = { favorite -> onSetImageAssetFavorite(image.id, favorite) },
                    reusablePresetFields = reusablePresetFields,
                    hiddenPresetFieldCount = sourcePresetFields.size - reusablePresetFields.size,
                    onUseParameters = useParameters@ { preset, fields ->
                        val hasStructuredUltraFixPayload = preset.ultraFix != null
                        val selectedUltraFix = ImageGenerationPresetField.ULTRAFIX in fields
                        val compositeUltraFix = preset.ultraFix?.takeIf {
                            selectedUltraFix
                        }
                        if (compositeUltraFix != null) {
                            val currentSourceTarget = selectedModel
                                ?.ultraFixTargetSizeForSourceOrNull(
                                    inputImageWidth,
                                    inputImageHeight
                                )
                            if (currentSourceTarget == null ||
                                compositeUltraFix.targetWidth < currentSourceTarget.first ||
                                compositeUltraFix.targetHeight < currentSourceTarget.second
                            ) {
                                Toast.makeText(
                                    context,
                                    "当前源图尺寸与这组 UltraFix 参数不兼容",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@useParameters
                            }
                            if (!onUltraFixEnabledChange(true)) return@useParameters
                        }
                        if (ImageGenerationPresetField.PROMPT in fields) {
                            onPromptChange(preset.prompt)
                        }
                        if (ImageGenerationPresetField.NEGATIVE_PROMPT in fields) {
                            onNegativePromptChange(preset.negativePrompt.orEmpty())
                            onDisableModelNegativePromptChange(preset.negativePrompt == "")
                        }
                        if (ImageGenerationPresetField.STRENGTH in fields &&
                            !hasStructuredUltraFixPayload
                        ) {
                            preset.strength?.let { onStrengthTextChange(it.toString()) }
                        }
                        if (ImageGenerationPresetField.CONTROL_STRENGTH in fields) {
                            preset.controlStrength?.let {
                                onControlStrengthTextChange(it.toString())
                            }
                        }
                        if (ImageGenerationPresetField.SIZE in fields &&
                            !hasStructuredUltraFixPayload
                        ) {
                            preset.width?.let { onWidthTextChange(it.toString()) }
                            preset.height?.let { onHeightTextChange(it.toString()) }
                        }
                        if (ImageGenerationPresetField.STEPS in fields &&
                            !hasStructuredUltraFixPayload
                        ) {
                            preset.steps?.let { onStepsTextChange(it.toString()) }
                        }
                        if (ImageGenerationPresetField.CFG in fields) {
                            preset.cfgScale?.let { onCfgScaleTextChange(it.toString()) }
                        }
                        if (ImageGenerationPresetField.SEED in fields) {
                            preset.seed?.let { onSeedTextChange(it.toString()) }
                        }
                        if (ImageGenerationPresetField.SAMPLER in fields) {
                            val selectedModel = imageModels.firstOrNull { it.id == selectedImageModelId }
                            preset.sampleMethod
                                ?.takeIf {
                                    selectedModel
                                        ?.imageSupportedSamplersForTask(taskMode)
                                        ?.contains(it) == true
                                }
                                ?.let(onSamplerChange)
                        }
                        if (ImageGenerationPresetField.CLIP_SKIP in fields && supportsClipSkip) {
                            preset.clipSkip?.let { onClipSkipTextChange(it.toString()) }
                        }
                        if (ImageGenerationPresetField.BATCH in fields &&
                            !hasStructuredUltraFixPayload
                        ) {
                            preset.batchCount?.let { onBatchCountChange(it.coerceIn(1, maxBatchCount)) }
                        }
                        if (ImageGenerationPresetField.VAE_TILING in fields &&
                            supportsVaeTiling && !hasStructuredUltraFixPayload
                        ) {
                            onVaeTilingEnabledChange(preset.vaeTileSize != null)
                        }
                        if (ImageGenerationPresetField.LORA in fields && supportsLora) {
                            val desiredIds = preset.loras.mapTo(mutableSetOf(), ImageGenerationUiLoraSelection::id)
                            selectedLoras
                                .filterNot { draft -> draft.id in desiredIds }
                                .forEach { draft -> onToggleLora(draft.id) }
                            preset.loras.forEach { selection ->
                                if (selectedLoras.none { draft -> draft.id == selection.id }) {
                                    onToggleLora(selection.id)
                                }
                                onLoraMultiplierChange(selection.id, selection.multiplier.toString())
                            }
                        }
                        if (ImageGenerationPresetField.TEXTUAL_INVERSION in fields &&
                            supportsTextualInversion
                        ) {
                            val desiredIds = preset.textualInversionIds.toSet()
                            selectedTextualInversionIds
                                .filterNot(desiredIds::contains)
                                .forEach(onToggleTextualInversion)
                            preset.textualInversionIds
                                .filterNot(selectedTextualInversionIds::contains)
                                .forEach(onToggleTextualInversion)
                        }
                        if (ImageGenerationPresetField.ULTRAFIX in fields && supportsUltraFix) {
                            preset.ultraFix?.let { ultraFix ->
                                onWidthTextChange(ultraFix.targetWidth.toString())
                                onHeightTextChange(ultraFix.targetHeight.toString())
                                onUltraFixRefinementStepsTextChange(
                                    ultraFix.refinementSteps.toString()
                                )
                                onUltraFixDenoisingStepsTextChange(
                                    ultraFix.inversionSteps.toString()
                                )
                                onUltraFixTileSizeTextChange(ultraFix.tileSize.toString())
                                onUltraFixOverlapTextChange(ultraFix.overlap.toString())
                            }
                        }
                        previewImageId = null
                        Toast.makeText(context, "已将所选参数应用到生成面板", Toast.LENGTH_SHORT).show()
                    },
                    onRecreate = {
                        pendingConversationPrompt = image.generationPrompt.ifBlank { image.prompt }
                        showGenerationCanvas = true
                        previewImageId = null
                        onRecreateImageAsset(image.id)
                    },
                    recreateEnabled = image.canRecreate && jobs.none { it.isWorking },
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        }
    }
    if (pendingDeleteIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDeleteIds = emptySet() },
            title = { Text(if (pendingDeleteIds.size == 1) "删除图片" else "批量删除") },
            text = {
                Text(
                    if (pendingDeleteIds.size == 1) {
                        "确定删除这张图片吗？此操作无法撤销。"
                    } else {
                        "确定删除选中的 ${pendingDeleteIds.size} 张图片吗？此操作无法撤销。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = pendingDeleteIds.toList()
                        if (ids.size == 1) onDeleteImageAsset(ids.single())
                        else onDeleteImageAssets(ids)
                        if (previewImageId?.let(pendingDeleteIds::contains) == true) {
                            previewImageId = null
                        }
                        selectedImageIds = selectedImageIds - pendingDeleteIds
                        pendingDeleteIds = emptySet()
                        if (selectedImageIds.isEmpty()) selectionMode = false
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteIds = emptySet() }) { Text("取消") }
            }
        )
    }
    if (showBackupDialog) {
        ImageLibraryBackupDialog(
            state = backupState,
            totalCount = images.size,
            totalBytes = images.sumOf(ImageAssetUiItem::sizeBytes),
            favoriteCount = images.count(ImageAssetUiItem::favorite),
            favoriteBytes = images.asSequence()
                .filter(ImageAssetUiItem::favorite)
                .sumOf(ImageAssetUiItem::sizeBytes),
            favoritesOnly = backupFavoritesOnly,
            onFavoritesOnlyChange = { backupFavoritesOnly = it },
            onExport = {
                val timestamp = java.text.SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    java.util.Locale.US
                ).format(java.util.Date())
                backupExportLauncher.launch("MCA_image_library_$timestamp.zip")
            },
            onImport = {
                backupImportLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            },
            onCancel = onCancelBackup,
            onDismiss = { if (!backupState.running) showBackupDialog = false }
        )
    }
}

@Composable
private fun ImageLibraryBackupDialog(
    state: ImageLibraryBackupUiState,
    totalCount: Int,
    totalBytes: Long,
    favoriteCount: Int,
    favoriteBytes: Long,
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text("图片库备份与恢复") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "导出会保存图片、生成参数和收藏状态；恢复会与当前图片库合并，不会覆盖未知设备或未安装模型的历史。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "全部：$totalCount 张 · ${formatImageLibraryBytes(totalBytes)}\n" +
                        "收藏：$favoriteCount 张 · ${formatImageLibraryBytes(favoriteBytes)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!state.running) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !favoritesOnly,
                            onClick = { onFavoritesOnlyChange(false) },
                            label = { Text("导出全部") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = { onFavoritesOnlyChange(true) },
                            label = { Text("仅导出收藏") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (state.running) {
                    if (state.total > 0) {
                        LinearProgressIndicator(
                            progress = { state.done.coerceIn(0, state.total).toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        if (state.importing) {
                            "正在恢复 ${state.done}/${state.total}"
                        } else {
                            "正在导出 ${state.done}/${state.total}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (state.message.isNotBlank()) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onExport,
                            enabled = if (favoritesOnly) favoriteCount > 0 else totalCount > 0,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("导出 ZIP")
                        }
                        Button(
                            onClick = onImport,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("合并恢复")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.running) {
                TextButton(onClick = onCancel) { Text("取消") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

private fun formatImageLibraryBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) {
        "${value.toLong()} ${units[index]}"
    } else {
        "${"%.1f".format(value)} ${units[index]}"
    }
}

private val ImageGenerationUiJob.isWorking: Boolean
    get() = !terminal

@Composable
private fun ImageGalleryHome(
    images: List<ImageAssetUiItem>,
    totalImageCount: Int,
    libraryQuery: String,
    favoritesOnly: Boolean,
    selectedLibraryModelId: String?,
    selectedLibraryTaskMode: String?,
    selectedLibraryOperation: String?,
    selectedLibraryDateRange: ImageLibraryDateRange?,
    selectedLibraryDateStartUtcMillis: Long?,
    selectedLibraryDateEndUtcMillis: Long?,
    selectedLibraryDimensionsKey: String?,
    selectedLibraryScheduler: String?,
    selectedLibraryRuntime: String?,
    selectedLibraryDevice: String?,
    newestFirst: Boolean,
    libraryModels: List<Pair<String, String>>,
    libraryTaskModes: List<String>,
    libraryOperations: List<String>,
    libraryDimensions: List<ImageLibraryDimensions>,
    librarySchedulers: List<String>,
    libraryRuntimes: List<String>,
    libraryDevices: List<String>,
    selectionMode: Boolean,
    selectedImageIds: Set<String>,
    batchSaving: Boolean,
    imageModels: List<ChatModelChoice>,
    imageModelSwitchEnabled: Boolean,
    selectedImageModelId: String?,
    selectedImageModelName: String?,
    selectedImageModelIsCloud: Boolean,
    supportedTaskModes: Set<ImageGenerationUiTaskMode>,
    supportsNegativePrompt: Boolean,
    supportsClipSkip: Boolean,
    supportsVaeTiling: Boolean,
    supportsTextualInversion: Boolean,
    supportsUltraFix: Boolean,
    supportsLora: Boolean,
    supportsLivePreview: Boolean,
    loras: List<ImageLoraUiItem>,
    selectedLoras: List<ImageGenerationUiLoraDraft>,
    loraRestoreWarning: String?,
    loraImporting: Boolean,
    loraMessage: String,
    textualInversions: List<ImageTextualInversionUiItem>,
    selectedTextualInversionIds: List<String>,
    textualInversionImporting: Boolean,
    textualInversionMessage: String,
    batchCount: Int,
    maxBatchCount: Int,
    taskMode: ImageGenerationUiTaskMode,
    inputImageUri: String?,
    inputImageWidth: Int,
    inputImageHeight: Int,
    maskImageUri: String?,
    controlImageUri: String?,
    inputRestoreWarning: String?,
    strengthText: String,
    controlStrengthText: String,
    negativePrompt: String,
    disableModelNegativePrompt: Boolean,
    clipSkipText: String,
    vaeTilingEnabled: Boolean,
    ultraFixEnabled: Boolean,
    ultraFixStrengthText: String,
    ultraFixInversionStepsText: String,
    ultraFixRefinementStepsText: String,
    ultraFixTileSizeText: String,
    ultraFixOverlapText: String,
    livePreviewEnabled: Boolean,
    livePreviewInterval: Int,
    widthText: String,
    heightText: String,
    stepsText: String,
    cfgScaleText: String,
    seedText: String,
    sampler: String,
    onMeasureImagePromptTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)?,
    onBack: () -> Unit,
    onSelectImageModel: (String) -> Unit,
    onTaskModeChange: (ImageGenerationUiTaskMode) -> Unit,
    onPickImageRole: (String) -> Unit,
    onCropImageRole: (String) -> Unit,
    onDrawImageMask: () -> Unit,
    onClearImageRole: (String) -> Unit,
    onStrengthTextChange: (String) -> Unit,
    onControlStrengthTextChange: (String) -> Unit,
    onNegativePromptChange: (String) -> Unit,
    onDisableModelNegativePromptChange: (Boolean) -> Unit,
    onClipSkipTextChange: (String) -> Unit,
    onVaeTilingEnabledChange: (Boolean) -> Unit,
    onLivePreviewEnabledChange: (Boolean) -> Unit,
    onLivePreviewIntervalChange: (Int) -> Unit,
    onToggleLora: (String) -> Unit,
    onLoraMultiplierChange: (String, String) -> Unit,
    onImportLora: () -> Unit,
    onDeleteLora: (String) -> Unit,
    onToggleTextualInversion: (String) -> Unit,
    onImportTextualInversion: () -> Unit,
    onDeleteTextualInversion: (String) -> Unit,
    onUltraFixEnabledChange: (Boolean) -> Boolean,
     onResetUltraFix: () -> Unit,
     onUltraFixDenoisingStepsTextChange: (String) -> Unit,
    onUltraFixRefinementStepsTextChange: (String) -> Unit,
    onUltraFixTileSizeTextChange: (String) -> Unit,
    onUltraFixOverlapTextChange: (String) -> Unit,
    onBatchCountChange: (Int) -> Unit,
    onWidthTextChange: (String) -> Unit,
    onHeightTextChange: (String) -> Unit,
    onStepsTextChange: (String) -> Unit,
    onCfgScaleTextChange: (String) -> Unit,
    onSeedTextChange: (String) -> Unit,
    onSamplerChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onOpenImagePreview: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit,
    onSetImageAssetFavorite: (String, Boolean) -> Unit,
    onLibraryQueryChange: (String) -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onLibraryModelChange: (String?) -> Unit,
    onLibraryTaskModeChange: (String?) -> Unit,
    onLibraryOperationChange: (String?) -> Unit,
    onLibraryDateRangeChange: (Long?, Long?) -> Unit,
    onLibraryDimensionsChange: (String?) -> Unit,
    onLibrarySchedulerChange: (String?) -> Unit,
    onLibraryRuntimeChange: (String?) -> Unit,
    onLibraryDeviceChange: (String?) -> Unit,
    onNewestFirstChange: (Boolean) -> Unit,
    onClearLibraryFilters: () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onToggleImageSelection: (String) -> Unit,
    onSelectAllVisible: () -> Unit,
    onBatchSave: () -> Unit,
    onBatchDelete: () -> Unit,
    onOpenBackup: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val titleColor = if (darkTheme) MaterialTheme.colorScheme.onBackground else Color(0xFF202124)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { ImageGalleryTitleBar(onBack = onBack) }
        item {
            ImageEngineSwitcher(
                models = imageModels,
                selectedModelId = selectedImageModelId,
                selectedModelName = selectedImageModelName,
                selectedModelIsCloud = selectedImageModelIsCloud,
                enabled = imageModelSwitchEnabled,
                onSelectModel = onSelectImageModel
            )
        }
        item {
            ImageInputOptionsPanel(
                taskMode = taskMode,
                selectedModelIsCloud = selectedImageModelIsCloud,
                executionModel = imageModels.firstOrNull { it.id == selectedImageModelId },
                supportedTaskModes = supportedTaskModes,
                supportsNegativePrompt = supportsNegativePrompt,
                supportsClipSkip = supportsClipSkip,
                supportsVaeTiling = supportsVaeTiling,
                supportsTextualInversion = supportsTextualInversion,
                supportsUltraFix = supportsUltraFix,
                supportsLora = supportsLora,
                supportsLivePreview = supportsLivePreview,
                loras = loras,
                selectedLoras = selectedLoras,
                loraRestoreWarning = loraRestoreWarning,
                loraImporting = loraImporting,
                loraMessage = loraMessage,
                textualInversions = textualInversions,
                selectedTextualInversionIds = selectedTextualInversionIds,
                textualInversionImporting = textualInversionImporting,
                textualInversionMessage = textualInversionMessage,
                batchCount = batchCount,
                maxBatchCount = maxBatchCount,
                inputImageUri = inputImageUri,
                inputImageWidth = inputImageWidth,
                inputImageHeight = inputImageHeight,
                maskImageUri = maskImageUri,
                controlImageUri = controlImageUri,
                inputRestoreWarning = inputRestoreWarning,
                strengthText = strengthText,
                controlStrengthText = controlStrengthText,
                negativePrompt = negativePrompt,
                disableModelNegativePrompt = disableModelNegativePrompt,
                clipSkipText = clipSkipText,
                vaeTilingEnabled = vaeTilingEnabled,
                ultraFixEnabled = ultraFixEnabled,
                ultraFixStrengthText = ultraFixStrengthText,
                ultraFixInversionStepsText = ultraFixInversionStepsText,
                ultraFixRefinementStepsText = ultraFixRefinementStepsText,
                ultraFixTileSizeText = ultraFixTileSizeText,
                ultraFixOverlapText = ultraFixOverlapText,
                livePreviewEnabled = livePreviewEnabled,
                livePreviewInterval = livePreviewInterval,
                widthText = widthText,
                heightText = heightText,
                stepsText = stepsText,
                cfgScaleText = cfgScaleText,
                seedText = seedText,
                sampler = sampler,
                modelId = selectedImageModelId,
                onMeasureTokens = onMeasureImagePromptTokens,
                onPromptChange = onPromptChange,
                onTaskModeChange = onTaskModeChange,
                onPickImageRole = onPickImageRole,
                onCropImageRole = onCropImageRole,
                onDrawImageMask = onDrawImageMask,
                onClearImageRole = onClearImageRole,
                onStrengthTextChange = onStrengthTextChange,
                onControlStrengthTextChange = onControlStrengthTextChange,
                onNegativePromptChange = onNegativePromptChange,
                onDisableModelNegativePromptChange = onDisableModelNegativePromptChange,
                onClipSkipTextChange = onClipSkipTextChange,
                onVaeTilingEnabledChange = onVaeTilingEnabledChange,
                onLivePreviewEnabledChange = onLivePreviewEnabledChange,
                onLivePreviewIntervalChange = onLivePreviewIntervalChange,
                onToggleLora = onToggleLora,
                onLoraMultiplierChange = onLoraMultiplierChange,
                onImportLora = onImportLora,
                onDeleteLora = onDeleteLora,
                onToggleTextualInversion = onToggleTextualInversion,
                onImportTextualInversion = onImportTextualInversion,
                onDeleteTextualInversion = onDeleteTextualInversion,
                 onUltraFixEnabledChange = onUltraFixEnabledChange,
                 onResetUltraFix = onResetUltraFix,
                 onUltraFixDenoisingStepsTextChange = onUltraFixDenoisingStepsTextChange,
                onUltraFixRefinementStepsTextChange = onUltraFixRefinementStepsTextChange,
                onUltraFixTileSizeTextChange = onUltraFixTileSizeTextChange,
                onUltraFixOverlapTextChange = onUltraFixOverlapTextChange,
                onBatchCountChange = onBatchCountChange,
                onWidthTextChange = onWidthTextChange,
                onHeightTextChange = onHeightTextChange,
                onStepsTextChange = onStepsTextChange,
                onCfgScaleTextChange = onCfgScaleTextChange,
                onSeedTextChange = onSeedTextChange,
                onSamplerChange = onSamplerChange
            )
        }
        item {
            Text(
                "生成图片",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
        }
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val cardWidth = ((maxWidth - 27.dp) / 4f).coerceIn(68.dp, 88.dp)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(end = 18.dp)
                ) {
                    items(imagePromptTemplates, key = { it.title }) { template ->
                        ImageTemplateCard(
                            template = template,
                            cardWidth = cardWidth,
                            onClick = {
                                onPromptChange(template.prompt)
                                onNegativePromptChange(template.negativePrompt)
                                onDisableModelNegativePromptChange(false)
                            }
                        )
                    }
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(58.dp))
        }
        item {
            Text(
                "我的图片",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
        }
        item {
            ImageLibraryToolbar(
                query = libraryQuery,
                resultCount = images.size,
                totalCount = totalImageCount,
                favoritesOnly = favoritesOnly,
                selectedModelId = selectedLibraryModelId,
                selectedTaskMode = selectedLibraryTaskMode,
                selectedOperation = selectedLibraryOperation,
                selectedDateRange = selectedLibraryDateRange,
                selectedDateStartUtcMillis = selectedLibraryDateStartUtcMillis,
                selectedDateEndUtcMillis = selectedLibraryDateEndUtcMillis,
                selectedDimensionsKey = selectedLibraryDimensionsKey,
                selectedScheduler = selectedLibraryScheduler,
                selectedRuntime = selectedLibraryRuntime,
                selectedDevice = selectedLibraryDevice,
                newestFirst = newestFirst,
                models = libraryModels,
                taskModes = libraryTaskModes,
                operations = libraryOperations,
                dimensions = libraryDimensions,
                schedulers = librarySchedulers,
                runtimes = libraryRuntimes,
                devices = libraryDevices,
                selectionMode = selectionMode,
                selectedCount = selectedImageIds.size,
                batchSaving = batchSaving,
                allVisibleSelected = images.isNotEmpty() && images.all { it.id in selectedImageIds },
                onQueryChange = onLibraryQueryChange,
                onFavoritesOnlyChange = onFavoritesOnlyChange,
                onModelChange = onLibraryModelChange,
                onTaskModeChange = onLibraryTaskModeChange,
                onOperationChange = onLibraryOperationChange,
                onDateRangeChange = onLibraryDateRangeChange,
                onDimensionsChange = onLibraryDimensionsChange,
                onSchedulerChange = onLibrarySchedulerChange,
                onRuntimeChange = onLibraryRuntimeChange,
                onDeviceChange = onLibraryDeviceChange,
                onNewestFirstChange = onNewestFirstChange,
                onClearFilters = onClearLibraryFilters,
                onSelectionModeChange = onSelectionModeChange,
                onSelectAllVisible = onSelectAllVisible,
                onBatchSave = onBatchSave,
                onBatchDelete = onBatchDelete,
                onOpenBackup = onOpenBackup
            )
        }
        if (images.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (favoritesOnly) Icons.Default.FavoriteBorder else Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(30.dp)
                    )
                    Text(
                        if (totalImageCount == 0) "生成完成的图片会自动保存在这里" else "没有符合当前筛选条件的图片",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(images.chunked(3), key = { row -> row.joinToString("-") { it.id } }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { image ->
                        ImageAssetTile(
                            image = image,
                            selected = image.id in selectedImageIds,
                            selectionMode = selectionMode,
                            onOpen = {
                                if (selectionMode) onToggleImageSelection(image.id)
                                else onOpenImagePreview(image.id)
                            },
                            onLongPress = {
                                if (!selectionMode) onSelectionModeChange(true)
                                onToggleImageSelection(image.id)
                            },
                            onToggleFavorite = {
                                onSetImageAssetFavorite(image.id, !image.favorite)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    }
                    repeat(3 - row.size) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageLibraryToolbar(
    query: String,
    resultCount: Int,
    totalCount: Int,
    favoritesOnly: Boolean,
    selectedModelId: String?,
    selectedTaskMode: String?,
    selectedOperation: String?,
    selectedDateRange: ImageLibraryDateRange?,
    selectedDateStartUtcMillis: Long?,
    selectedDateEndUtcMillis: Long?,
    selectedDimensionsKey: String?,
    selectedScheduler: String?,
    selectedRuntime: String?,
    selectedDevice: String?,
    newestFirst: Boolean,
    models: List<Pair<String, String>>,
    taskModes: List<String>,
    operations: List<String>,
    dimensions: List<ImageLibraryDimensions>,
    schedulers: List<String>,
    runtimes: List<String>,
    devices: List<String>,
    selectionMode: Boolean,
    selectedCount: Int,
    batchSaving: Boolean,
    allVisibleSelected: Boolean,
    onQueryChange: (String) -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onModelChange: (String?) -> Unit,
    onTaskModeChange: (String?) -> Unit,
    onOperationChange: (String?) -> Unit,
    onDateRangeChange: (Long?, Long?) -> Unit,
    onDimensionsChange: (String?) -> Unit,
    onSchedulerChange: (String?) -> Unit,
    onRuntimeChange: (String?) -> Unit,
    onDeviceChange: (String?) -> Unit,
    onNewestFirstChange: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onSelectAllVisible: () -> Unit,
    onBatchSave: () -> Unit,
    onBatchDelete: () -> Unit,
    onOpenBackup: () -> Unit
) {
    var showModelMenu by remember { mutableStateOf(false) }
    var showTaskMenu by remember { mutableStateOf(false) }
    var showOperationMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDimensionsMenu by remember { mutableStateOf(false) }
    var showSchedulerMenu by remember { mutableStateOf(false) }
    var showRuntimeMenu by remember { mutableStateOf(false) }
    var showDeviceMenu by remember { mutableStateOf(false) }
    val selectedModelLabel = models.firstOrNull { it.first == selectedModelId }?.second
    val selectedTaskLabel = when (selectedTaskMode) {
        "ultrafix" -> "UltraFix"
        else -> ImageGenerationUiTaskMode.entries
            .firstOrNull { it.wireName == selectedTaskMode }
            ?.label
            ?: selectedTaskMode
    }
    val selectedOperationLabel = ImageLibraryOperationFacet.labelFor(selectedOperation)
    val activeFilterCount = listOfNotNull(
        favoritesOnly.takeIf { it },
        selectedModelId,
        selectedTaskMode,
        selectedOperation,
        selectedDateRange,
        selectedDimensionsKey,
        selectedScheduler,
        selectedRuntime,
        selectedDevice,
        query.takeIf(String::isNotBlank)
    ).size
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("搜索图片、提示词或模型") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "清空搜索")
                    }
                }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = favoritesOnly,
                onClick = { onFavoritesOnlyChange(!favoritesOnly) },
                label = { Text("收藏") },
                leadingIcon = {
                    Icon(
                        if (favoritesOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
            if (models.isNotEmpty()) {
                Box {
                    FilterChip(
                        selected = selectedModelId != null,
                        onClick = { showModelMenu = true },
                        label = { Text(selectedModelLabel ?: "模型") },
                        leadingIcon = {
                            Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    DropdownMenu(
                        expanded = showModelMenu,
                        onDismissRequest = { showModelMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部模型") },
                            onClick = {
                                onModelChange(null)
                                showModelMenu = false
                            }
                        )
                        models.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onModelChange(id)
                                    showModelMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (taskModes.isNotEmpty()) {
                Box {
                    FilterChip(
                        selected = selectedTaskMode != null,
                        onClick = { showTaskMenu = true },
                        label = { Text(selectedTaskLabel ?: "模式") }
                    )
                    DropdownMenu(
                        expanded = showTaskMenu,
                        onDismissRequest = { showTaskMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部模式") },
                            onClick = {
                                onTaskModeChange(null)
                                showTaskMenu = false
                            }
                        )
                        taskModes.forEach { mode ->
                            val label = when (mode) {
                                "ultrafix" -> "UltraFix"
                                else -> ImageGenerationUiTaskMode.entries
                                    .firstOrNull { it.wireName == mode }
                                    ?.label
                                    ?: mode
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onTaskModeChange(mode)
                                    showTaskMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (operations.isNotEmpty() || selectedOperation != null) {
                Box {
                    FilterChip(
                        selected = selectedOperation != null,
                        onClick = { showOperationMenu = true },
                        label = { Text(selectedOperationLabel ?: "操作") }
                    )
                    DropdownMenu(
                        expanded = showOperationMenu,
                        onDismissRequest = { showOperationMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部操作") },
                            onClick = {
                                onOperationChange(null)
                                showOperationMenu = false
                            }
                        )
                        operations.forEach { operation ->
                            DropdownMenuItem(
                                text = { Text(ImageLibraryOperationFacet.labelFor(operation) ?: operation) },
                                onClick = {
                                    onOperationChange(operation)
                                    showOperationMenu = false
                                }
                            )
                        }
                    }
                }
            }
            FilterChip(
                selected = selectedDateRange != null,
                onClick = { showDatePicker = true },
                label = {
                    Text(selectedDateRange?.let(::formatImageLibraryDateRange) ?: "日期")
                }
            )
            if (dimensions.isNotEmpty() || selectedDimensionsKey != null) {
                Box {
                    FilterChip(
                        selected = selectedDimensionsKey != null,
                        onClick = { showDimensionsMenu = true },
                        label = {
                            Text(
                                dimensions.firstOrNull { it.key == selectedDimensionsKey }?.label
                                    ?: selectedDimensionsKey
                                    ?: "尺寸"
                            )
                        }
                    )
                    DropdownMenu(
                        expanded = showDimensionsMenu,
                        onDismissRequest = { showDimensionsMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部尺寸") },
                            onClick = {
                                onDimensionsChange(null)
                                showDimensionsMenu = false
                            }
                        )
                        dimensions.forEach { size ->
                            DropdownMenuItem(
                                text = { Text(size.label) },
                                onClick = {
                                    onDimensionsChange(size.key)
                                    showDimensionsMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (schedulers.isNotEmpty() || selectedScheduler != null) {
                Box {
                    FilterChip(
                        selected = selectedScheduler != null,
                        onClick = { showSchedulerMenu = true },
                        label = { Text(selectedScheduler ?: "Scheduler") }
                    )
                    DropdownMenu(
                        expanded = showSchedulerMenu,
                        onDismissRequest = { showSchedulerMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部 Scheduler") },
                            onClick = {
                                onSchedulerChange(null)
                                showSchedulerMenu = false
                            }
                        )
                        schedulers.forEach { scheduler ->
                            DropdownMenuItem(
                                text = { Text(scheduler) },
                                onClick = {
                                    onSchedulerChange(scheduler)
                                    showSchedulerMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (runtimes.isNotEmpty() || selectedRuntime != null) {
                Box {
                    FilterChip(
                        selected = selectedRuntime != null,
                        onClick = { showRuntimeMenu = true },
                        label = { Text(selectedRuntime ?: "Runtime") }
                    )
                    DropdownMenu(
                        expanded = showRuntimeMenu,
                        onDismissRequest = { showRuntimeMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部 Runtime") },
                            onClick = {
                                onRuntimeChange(null)
                                showRuntimeMenu = false
                            }
                        )
                        runtimes.forEach { runtime ->
                            DropdownMenuItem(
                                text = { Text(runtime) },
                                onClick = {
                                    onRuntimeChange(runtime)
                                    showRuntimeMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (devices.isNotEmpty() || selectedDevice != null) {
                Box {
                    FilterChip(
                        selected = selectedDevice != null,
                        onClick = { showDeviceMenu = true },
                        label = { Text(selectedDevice ?: "执行设备") }
                    )
                    DropdownMenu(
                        expanded = showDeviceMenu,
                        onDismissRequest = { showDeviceMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部执行设备") },
                            onClick = {
                                onDeviceChange(null)
                                showDeviceMenu = false
                            }
                        )
                        devices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device) },
                                onClick = {
                                    onDeviceChange(device)
                                    showDeviceMenu = false
                                }
                            )
                        }
                    }
                }
            }
            FilterChip(
                selected = !newestFirst,
                onClick = { onNewestFirstChange(!newestFirst) },
                label = { Text(if (newestFirst) "最新优先" else "最早优先") }
            )
            if (activeFilterCount > 0) {
                FilterChip(
                    selected = false,
                    onClick = onClearFilters,
                    label = { Text("清除筛选 ($activeFilterCount)") },
                    leadingIcon = {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }
            FilterChip(
                selected = selectionMode,
                onClick = { onSelectionModeChange(!selectionMode) },
                label = { Text(if (selectionMode) "完成选择" else "选择") }
            )
        }
        if (selectionMode) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 $selectedCount 张",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = onSelectAllVisible, enabled = resultCount > 0 && !batchSaving) {
                        Text(if (allVisibleSelected) "取消全选" else "全选结果")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onBatchSave, enabled = selectedCount > 0 && !batchSaving) {
                        Text(if (batchSaving) "保存中…" else "保存")
                    }
                    TextButton(onClick = onBatchDelete, enabled = selectedCount > 0 && !batchSaving) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (resultCount == totalCount) "$totalCount 张图片" else "$resultCount / $totalCount 张图片",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenBackup) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("备份与恢复")
            }
        }
    }
    if (showDatePicker) {
        ImageLibraryDateFilterDialog(
            selectedStartDateMillis = selectedDateStartUtcMillis,
            selectedEndDateMillis = selectedDateEndUtcMillis,
            onDismiss = { showDatePicker = false },
            onClear = {
                onDateRangeChange(null, null)
                showDatePicker = false
            },
            onConfirm = { start, end ->
                onDateRangeChange(start, end)
                showDatePicker = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageLibraryDateFilterDialog(
    selectedStartDateMillis: Long?,
    selectedEndDateMillis: Long?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onConfirm: (Long, Long) -> Unit
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = selectedStartDateMillis,
        initialSelectedEndDateMillis = selectedEndDateMillis
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis
            TextButton(
                onClick = { start?.let { onConfirm(it, state.selectedEndDateMillis ?: it) } },
                enabled = start != null
            ) {
                Text("应用")
            }
        },
        dismissButton = {
            if (selectedStartDateMillis != null) {
                TextButton(onClick = onClear) { Text("清除") }
            }
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            showModeToggle = false
        )
    }
}

private fun formatImageLibraryDateRange(range: ImageLibraryDateRange): String {
    val zoneId = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("MM/dd")
    val start = Instant.ofEpochMilli(range.startInclusiveMillis).atZone(zoneId).toLocalDate()
    val end = Instant.ofEpochMilli(range.endExclusiveMillis - 1L).atZone(zoneId).toLocalDate()
    return if (start == end) formatter.format(start) else "${formatter.format(start)}–${formatter.format(end)}"
}

@Composable
private fun ImageInputOptionsPanel(
    taskMode: ImageGenerationUiTaskMode,
    selectedModelIsCloud: Boolean,
    executionModel: ChatModelChoice?,
    supportedTaskModes: Set<ImageGenerationUiTaskMode>,
    supportsNegativePrompt: Boolean,
    supportsClipSkip: Boolean,
    supportsVaeTiling: Boolean,
    supportsTextualInversion: Boolean,
    supportsUltraFix: Boolean,
    supportsLora: Boolean,
    supportsLivePreview: Boolean,
    loras: List<ImageLoraUiItem>,
    selectedLoras: List<ImageGenerationUiLoraDraft>,
    loraRestoreWarning: String?,
    loraImporting: Boolean,
    loraMessage: String,
    textualInversions: List<ImageTextualInversionUiItem>,
    selectedTextualInversionIds: List<String>,
    textualInversionImporting: Boolean,
    textualInversionMessage: String,
    batchCount: Int,
    maxBatchCount: Int,
    inputImageUri: String?,
    inputImageWidth: Int,
    inputImageHeight: Int,
    maskImageUri: String?,
    controlImageUri: String?,
    inputRestoreWarning: String?,
    strengthText: String,
    controlStrengthText: String,
    negativePrompt: String,
    disableModelNegativePrompt: Boolean,
    clipSkipText: String,
    vaeTilingEnabled: Boolean,
    ultraFixEnabled: Boolean,
    ultraFixStrengthText: String,
    ultraFixInversionStepsText: String,
    ultraFixRefinementStepsText: String,
    ultraFixTileSizeText: String,
    ultraFixOverlapText: String,
    livePreviewEnabled: Boolean,
    livePreviewInterval: Int,
    widthText: String,
    heightText: String,
    stepsText: String,
    cfgScaleText: String,
    seedText: String,
    sampler: String,
    modelId: String?,
    onMeasureTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)?,
    onPromptChange: (String) -> Unit,
    onTaskModeChange: (ImageGenerationUiTaskMode) -> Unit,
    onPickImageRole: (String) -> Unit,
    onCropImageRole: (String) -> Unit,
    onDrawImageMask: () -> Unit,
    onClearImageRole: (String) -> Unit,
    onStrengthTextChange: (String) -> Unit,
    onControlStrengthTextChange: (String) -> Unit,
    onNegativePromptChange: (String) -> Unit,
    onDisableModelNegativePromptChange: (Boolean) -> Unit,
    onClipSkipTextChange: (String) -> Unit,
    onVaeTilingEnabledChange: (Boolean) -> Unit,
    onLivePreviewEnabledChange: (Boolean) -> Unit,
    onLivePreviewIntervalChange: (Int) -> Unit,
    onToggleLora: (String) -> Unit,
    onLoraMultiplierChange: (String, String) -> Unit,
    onImportLora: () -> Unit,
    onDeleteLora: (String) -> Unit,
    onToggleTextualInversion: (String) -> Unit,
    onImportTextualInversion: () -> Unit,
    onDeleteTextualInversion: (String) -> Unit,
    onUltraFixEnabledChange: (Boolean) -> Boolean,
     onResetUltraFix: () -> Unit,
     onUltraFixDenoisingStepsTextChange: (String) -> Unit,
    onUltraFixRefinementStepsTextChange: (String) -> Unit,
    onUltraFixTileSizeTextChange: (String) -> Unit,
    onUltraFixOverlapTextChange: (String) -> Unit,
    onBatchCountChange: (Int) -> Unit,
    onWidthTextChange: (String) -> Unit,
    onHeightTextChange: (String) -> Unit,
    onStepsTextChange: (String) -> Unit,
    onCfgScaleTextChange: (String) -> Unit,
    onSeedTextChange: (String) -> Unit,
    onSamplerChange: (String) -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val selectedLoraById = selectedLoras.associateBy(ImageGenerationUiLoraDraft::id)
    val loraSelectionLimitReached = selectedLoras.size >= 8
    val selectedTextualInversionIdSet = selectedTextualInversionIds.toSet()
    val textualInversionSelectionLimitReached = selectedTextualInversionIds.size >= 8
    val ultraFixTargetForInput = if (inputImageUri != null) {
        executionModel?.ultraFixTargetSizeForSourceOrNull(inputImageWidth, inputImageHeight)
    } else {
        null
    }
    val ultraFixInputReady = inputImageUri != null && ultraFixTargetForInput != null
    var pendingLoraDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTextualInversionDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingParameterImport by remember {
        mutableStateOf<ImageGenerationParameterImportSelection?>(null)
    }
    var advancedParametersExpanded by rememberSaveable(
        executionModel?.id,
        selectedModelIsCloud
    ) { mutableStateOf(false) }
    fun importParametersFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = clipboard
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        ImageGenerationParameterImportCodec.decode(raw)
            .onSuccess { imported ->
                val compatible = compatibleImageGenerationParameterImportFields(
                    imported = imported,
                    currentTaskMode = taskMode,
                    selectedModel = executionModel,
                    selectedModelIsCloud = selectedModelIsCloud,
                    supportsNegativePrompt = supportsNegativePrompt,
                    supportsClipSkip = supportsClipSkip,
                    supportsVaeTiling = supportsVaeTiling,
                    supportsLora = supportsLora,
                    availableLoraIds = loras.mapTo(mutableSetOf(), ImageLoraUiItem::id),
                    maxBatchCount = maxBatchCount,
                    supportsTextualInversion = supportsTextualInversion,
                    supportsUltraFix = supportsUltraFix,
                    ultraFixEnabled = ultraFixEnabled,
                    hasImageInput = inputImageUri != null,
                    imageInputWidth = inputImageWidth.takeIf { it > 0 },
                    imageInputHeight = inputImageHeight.takeIf { it > 0 },
                    availableTextualInversionIds = textualInversions
                        .filter { it.compatibleWithSelectedModel }
                        .mapTo(mutableSetOf(), ImageTextualInversionUiItem::id)
                )
                pendingParameterImport = ImageGenerationParameterImportSelection(
                    imported = imported,
                    compatibleFields = compatible
                )
            }
            .onFailure { error ->
                Toast.makeText(
                    context,
                    error.message?.takeIf(String::isNotBlank) ?: "无法识别剪贴板图片参数。",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }
    fun applyParameterImport(application: ImageGenerationParameterImportApplication) {
        val imported = application.imported
        val preset = imported.preset
        val fields = application.fields
        val hasStructuredUltraFixPayload = imported.hasStructuredUltraFixPayload()
        val selectedUltraFix = ImageGenerationParameterImportField.ULTRAFIX in fields
        val localDreamUltraFixPayload =
            imported.source == ImageGenerationParameterImportSource.LOCAL_DREAM &&
                imported.taskMode.equals("ULTRAFIX", ignoreCase = true) &&
                ImageGenerationParameterImportField.ULTRAFIX in imported.fields
        val ultraFixSourceTarget = if (selectedUltraFix) {
            executionModel?.ultraFixTargetSizeForSourceOrNull(inputImageWidth, inputImageHeight)
        } else {
            null
        }
        if (selectedUltraFix) {
            val structured = preset.ultraFix
            if (ultraFixSourceTarget == null ||
                (structured != null &&
                    (structured.targetWidth < ultraFixSourceTarget.first ||
                        structured.targetHeight < ultraFixSourceTarget.second))
            ) {
                Toast.makeText(
                    context,
                    "源图或目标尺寸已变化，无法安全应用 UltraFix 参数",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            // Cross the mode switch before mutating any imported field. A rejected switch leaves
            // both the ordinary preset and every other selected field untouched.
            if (!onUltraFixEnabledChange(true)) return
        }
        if (ImageGenerationParameterImportField.PROMPT in fields) onPromptChange(preset.prompt)
        if (ImageGenerationParameterImportField.NEGATIVE_PROMPT in fields) {
            onNegativePromptChange(preset.negativePrompt.orEmpty())
            onDisableModelNegativePromptChange(preset.negativePrompt == "")
        }
        if (ImageGenerationParameterImportField.SIZE in fields && !hasStructuredUltraFixPayload) {
            preset.width?.let { onWidthTextChange(it.toString()) }
            preset.height?.let { onHeightTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.STEPS in fields && !hasStructuredUltraFixPayload) {
            preset.steps?.let { onStepsTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.CFG in fields) {
            preset.cfgScale?.let { onCfgScaleTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.SEED in fields) {
            preset.seed?.let { onSeedTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.SAMPLER in fields) {
            preset.sampleMethod
                ?.takeIf {
                    executionModel
                        ?.imageSupportedSamplersForTask(taskMode)
                        ?.contains(it) == true
                }
                ?.let(onSamplerChange)
        }
        if (ImageGenerationParameterImportField.CLIP_SKIP in fields) {
            preset.clipSkip?.let { onClipSkipTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.BATCH in fields && !hasStructuredUltraFixPayload) {
            preset.batchCount?.let { onBatchCountChange(it.coerceIn(1, maxBatchCount)) }
        }
        if (ImageGenerationParameterImportField.VAE_TILING in fields &&
            !hasStructuredUltraFixPayload
        ) {
            onVaeTilingEnabledChange(preset.vaeTileSize != null)
        }
        if (ImageGenerationParameterImportField.LORA in fields) {
            val desiredIds = preset.loras.mapTo(mutableSetOf(), ImageGenerationUiLoraSelection::id)
            selectedLoras
                .filterNot { draft -> draft.id in desiredIds }
                .forEach { draft -> onToggleLora(draft.id) }
            preset.loras.forEach { selection ->
                if (selectedLoras.none { draft -> draft.id == selection.id }) {
                    onToggleLora(selection.id)
                }
                onLoraMultiplierChange(selection.id, selection.multiplier.toString())
            }
        }
        if (ImageGenerationParameterImportField.TEXTUAL_INVERSION in fields) {
            val desiredIds = preset.textualInversionIds.toSet()
            selectedTextualInversionIds
                .filterNot(desiredIds::contains)
                .forEach(onToggleTextualInversion)
            preset.textualInversionIds
                .filterNot(selectedTextualInversionIds::contains)
                .forEach(onToggleTextualInversion)
        }
        if (selectedUltraFix) {
            val sourceTarget = requireNotNull(ultraFixSourceTarget)
            val structured = preset.ultraFix
            if (structured != null) {
                val ultraFix = structured
                onWidthTextChange(ultraFix.targetWidth.toString())
                onHeightTextChange(ultraFix.targetHeight.toString())
                onUltraFixRefinementStepsTextChange(ultraFix.refinementSteps.toString())
                onUltraFixDenoisingStepsTextChange(ultraFix.inversionSteps.toString())
                onUltraFixTileSizeTextChange(ultraFix.tileSize.toString())
                onUltraFixOverlapTextChange(ultraFix.overlap.toString())
            } else if (localDreamUltraFixPayload) {
                onWidthTextChange(sourceTarget.first.toString())
                onHeightTextChange(sourceTarget.second.toString())
                val refinementSteps = preset.steps
                    ?.coerceIn(1, IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS)
                    ?: ultraFixRefinementStepsText.toIntOrNull()
                        ?.coerceIn(1, IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS)
                    ?: 10
                onUltraFixRefinementStepsTextChange(refinementSteps.toString())
                imported.strength?.let { strength ->
                    val denoisingSteps = imageGenerationUltraFixDenoisingTailStepCount(
                        refinementSteps,
                        strength
                    ).coerceAtMost(IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS)
                    onUltraFixDenoisingStepsTextChange(denoisingSteps.toString())
                }
            }
        }
        if (ImageGenerationParameterImportField.STRENGTH in fields && !hasStructuredUltraFixPayload) {
            imported.strength?.let { onStrengthTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.CONTROL_STRENGTH in fields) {
            imported.controlStrength?.let { onControlStrengthTextChange(it.toString()) }
        }
        val sourceLabel = when (imported.source) {
            ImageGenerationParameterImportSource.MCA -> "MCA"
            ImageGenerationParameterImportSource.LOCAL_DREAM -> "Local Dream"
        }
        Toast.makeText(
            context,
            "已从 $sourceLabel 应用 ${fields.size} 项参数。",
            Toast.LENGTH_SHORT
        ).show()
    }
    LaunchedEffect(pendingLoraDeleteId, loras) {
        val pendingId = pendingLoraDeleteId ?: return@LaunchedEffect
        if (loras.none { adapter -> adapter.id == pendingId && !adapter.inUse }) {
            pendingLoraDeleteId = null
        }
    }
    LaunchedEffect(pendingTextualInversionDeleteId, textualInversions) {
        val pendingId = pendingTextualInversionDeleteId ?: return@LaunchedEffect
        if (textualInversions.none { artifact -> artifact.id == pendingId && !artifact.inUse }) {
            pendingTextualInversionDeleteId = null
        }
    }
    Box {
        Surface(
            color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF6F8FC),
            shape = RoundedCornerShape(20.dp)
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("生成方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            inputRestoreWarning?.let { warning ->
                Text(
                    warning,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    ImageGenerationUiTaskMode.entries.filter(supportedTaskModes::contains),
                    key = { it.name }
                ) { mode ->
                    FilterChip(
                        selected = mode == taskMode,
                        onClick = { onTaskModeChange(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }
            if (selectedModelIsCloud && (
                    taskMode != ImageGenerationUiTaskMode.TEXT_TO_IMAGE ||
                        clipSkipText.isNotBlank() || vaeTilingEnabled
                    )
            ) {
                Text(
                    "当前云端连接器只声明了基础文生图；请选择本地引擎后再运行这些输入或高级控制。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            when (taskMode) {
                ImageGenerationUiTaskMode.TEXT_TO_IMAGE -> Unit
                ImageGenerationUiTaskMode.IMG2IMG -> {
                    ImageInputRoleRow(
                        "原图",
                        "input",
                        inputImageUri,
                        onPickImageRole,
                        onClearImageRole,
                        onEdit = onCropImageRole
                    )
                    ImageStrengthField("重绘强度 [0-1]", strengthText, onStrengthTextChange)
                }
                ImageGenerationUiTaskMode.EDIT -> {
                    ImageInputRoleRow(
                        "原图",
                        "input",
                        inputImageUri,
                        onPickImageRole,
                        onClearImageRole,
                        onEdit = onCropImageRole
                    )
                }
                ImageGenerationUiTaskMode.INPAINT -> {
                    ImageInputRoleRow(
                        "原图",
                        "input",
                        inputImageUri,
                        onPickImageRole,
                        onClearImageRole,
                        onEdit = onCropImageRole
                    )
                    ImageInputRoleRow("蒙版", "mask", maskImageUri, onPickImageRole, onClearImageRole)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = onDrawImageMask,
                            enabled = inputImageUri != null,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("绘制蒙版")
                        }
                    }
                    ImageStrengthField("重绘强度 [0-1]", strengthText, onStrengthTextChange)
                }
                ImageGenerationUiTaskMode.CONTROL -> {
                    ImageInputRoleRow(
                        "控制图",
                        "control",
                        controlImageUri,
                        onPickImageRole,
                        onClearImageRole,
                        onEdit = onCropImageRole
                    )
                    ImageStrengthField("控制强度 [0-2]", controlStrengthText, onControlStrengthTextChange)
                }
            }
            val parameterSummary = if (!selectedModelIsCloud && executionModel != null) {
                buildString {
                    append(widthText)
                    append('×')
                    append(heightText)
                    append(" · ")
                    append(stepsText)
                    append(" 步 · CFG ")
                    append(cfgScaleText)
                    append(" · ")
                    append(if (seedText.isBlank()) "随机 seed" else "seed $seedText")
                    if (batchCount > 1) append(" · ${batchCount} 张")
                }
            } else {
                "负面提示词、批量与高级控制"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (advancedParametersExpanded) "已展开" else "已收起"
                    }
                    .clickable(
                        role = SemanticsRole.Button,
                        onClickLabel = if (advancedParametersExpanded) {
                            "收起生成参数"
                        } else {
                            "展开生成参数"
                        }
                    ) {
                        advancedParametersExpanded = !advancedParametersExpanded
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "生成参数",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        parameterSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (advancedParametersExpanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = null
                )
            }
            if (advancedParametersExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = ::importParametersFromClipboard) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("从剪贴板导入")
                }
            }
            Text(
                "支持 MCA JSON / MCAPARAMS 与 Local Dream LDPARAMS；仅应用当前模型兼容字段。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            if (!selectedModelIsCloud && executionModel != null) {
                val displayedMinWidth = if (ultraFixEnabled) {
                    executionModel.imageUltraFixMinWidth
                } else {
                    executionModel.imageMinWidth
                }
                val displayedMaxWidth = if (ultraFixEnabled) {
                    executionModel.imageUltraFixMaxWidth
                } else {
                    executionModel.imageMaxWidth
                }
                val displayedMinHeight = if (ultraFixEnabled) {
                    executionModel.imageUltraFixMinHeight
                } else {
                    executionModel.imageMinHeight
                }
                val displayedMaxHeight = if (ultraFixEnabled) {
                    executionModel.imageUltraFixMaxHeight
                } else {
                    executionModel.imageMaxHeight
                }
                val displayedWidthMultiple = if (ultraFixEnabled) {
                    executionModel.imageUltraFixWidthMultiple
                } else {
                    executionModel.imageWidthMultiple
                }
                val displayedHeightMultiple = if (ultraFixEnabled) {
                    executionModel.imageUltraFixHeightMultiple
                } else {
                    executionModel.imageHeightMultiple
                }
                val fixedWidth = displayedMinWidth == displayedMaxWidth
                val fixedHeight = displayedMinHeight == displayedMaxHeight
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { value -> if (!fixedWidth) onWidthTextChange(value) },
                        label = { Text("宽度") },
                        supportingText = {
                            Text(
                                if (fixedWidth) {
                                    "模型固定尺寸"
                                } else {
                                    "$displayedMinWidth-$displayedMaxWidth / $displayedWidthMultiple"
                                }
                            )
                        },
                        singleLine = true,
                        readOnly = fixedWidth,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { value -> if (!fixedHeight) onHeightTextChange(value) },
                        label = { Text("高度") },
                        supportingText = {
                            Text(
                                if (fixedHeight) {
                                    "模型固定尺寸"
                                } else {
                                    "$displayedMinHeight-$displayedMaxHeight / $displayedHeightMultiple"
                                }
                            )
                        },
                        singleLine = true,
                        readOnly = fixedHeight,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = stepsText,
                        onValueChange = onStepsTextChange,
                        label = { Text("步数") },
                        supportingText = {
                            Text("${executionModel.imageMinSteps}-${executionModel.imageMaxSteps}")
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = cfgScaleText,
                        onValueChange = onCfgScaleTextChange,
                        label = { Text("CFG") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = seedText,
                        onValueChange = onSeedTextChange,
                        label = { Text("Seed") },
                        placeholder = { Text("随机") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("采样器", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(executionModel.imageSupportedSamplersForTask(taskMode)) { method ->
                        FilterChip(
                            selected = method == sampler,
                            onClick = { onSamplerChange(method) },
                            label = { Text(method) }
                        )
                    }
                }
            }
            if (supportsNegativePrompt) {
                ImageNegativePromptTagField(
                    value = negativePrompt,
                    onValueChange = onNegativePromptChange,
                    modelId = modelId,
                    onMeasureTokens = onMeasureTokens,
                    modifier = Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected = disableModelNegativePrompt,
                    onClick = { onDisableModelNegativePromptChange(!disableModelNegativePrompt) },
                    label = { Text("关闭模型默认负面词") }
                )
            }
            if (supportsLora) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "LoRA",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "最多选择 8 个；倍率可为负值，不能为 0。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = onImportLora, enabled = !loraImporting) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (loraImporting) "导入中…" else "导入")
                    }
                }
                loraRestoreWarning?.let { warning ->
                    Text(
                        warning,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (loraMessage.isNotBlank()) {
                    val isError = loraMessage.contains("失败") || loraMessage.contains("无效")
                    Text(
                        loraMessage,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (loras.isEmpty()) {
                    Text(
                        "尚未导入 LoRA。支持 .safetensors 与 .ckpt 文件。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    loras.forEachIndexed { index, adapter ->
                        val selection = selectedLoraById[adapter.id]
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = selection != null,
                                    onClick = { onToggleLora(adapter.id) },
                                    enabled = selection != null || !loraSelectionLimitReached,
                                    label = {
                                        Text(
                                            when {
                                                selection != null -> "已启用"
                                                loraSelectionLimitReached -> "已达上限"
                                                else -> "启用"
                                            }
                                        )
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        adapter.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "${adapter.sizeText} · ${adapter.sha256.take(10)}…",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(
                                    onClick = { pendingLoraDeleteId = adapter.id },
                                    enabled = !adapter.inUse && !loraImporting
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = if (adapter.inUse) {
                                            "当前任务使用中，无法删除 ${adapter.name}"
                                        } else {
                                            "删除 ${adapter.name}"
                                        },
                                        tint = if (adapter.inUse) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                }
                            }
                            if (selection != null) {
                                val multiplier = selection.multiplierText.toDoubleOrNull()
                                val multiplierValid = multiplier != null && multiplier.isFinite() &&
                                    multiplier in -4.0..4.0 && kotlin.math.abs(multiplier) >= 0.01
                                OutlinedTextField(
                                    value = selection.multiplierText,
                                    onValueChange = { onLoraMultiplierChange(adapter.id, it) },
                                    label = { Text("倍率") },
                                    supportingText = {
                                        Text(if (multiplierValid) "范围 -4 到 4" else "请输入 [-4, -0.01] 或 [0.01, 4]")
                                    },
                                    isError = !multiplierValid,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (adapter.inUse) {
                                Text(
                                    "当前图片任务正在使用此 LoRA，任务结束后可删除。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (index != loras.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
            if (supportsTextualInversion) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Textual Inversion",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${selectedTextualInversionIds.size.coerceAtMost(8)} / 8",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(
                        onClick = onImportTextualInversion,
                        enabled = !textualInversionImporting
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (textualInversionImporting) "导入中" else "导入")
                    }
                }
                if (textualInversionMessage.isNotBlank()) {
                    val isError = textualInversionMessage.contains("失败") ||
                        textualInversionMessage.contains("无效") ||
                        textualInversionMessage.contains("已删除")
                    Text(
                        textualInversionMessage,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (textualInversions.isEmpty()) {
                    Text(
                        "尚未导入 Textual Inversion",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    textualInversions.forEachIndexed { index, artifact ->
                        val selected = artifact.id in selectedTextualInversionIdSet
                        val compatible = artifact.compatibleWithSelectedModel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selected,
                                onClick = { onToggleTextualInversion(artifact.id) },
                                enabled = selected ||
                                    (compatible && !textualInversionSelectionLimitReached),
                                label = {
                                    Text(
                                        when {
                                            selected -> "已启用"
                                            compatible -> "启用"
                                            else -> "格式不兼容"
                                        }
                                    )
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    artifact.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "${artifact.trigger} · ${artifact.format} · ${artifact.sizeText} · ${artifact.sha256.take(10)}…",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(
                                onClick = { pendingTextualInversionDeleteId = artifact.id },
                                enabled = !artifact.inUse && !textualInversionImporting
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = if (artifact.inUse) {
                                        "当前任务使用中，无法删除 ${artifact.name}"
                                    } else {
                                        "删除 ${artifact.name}"
                                    },
                                    tint = if (artifact.inUse) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }
                        if (index != textualInversions.lastIndex) HorizontalDivider()
                    }
                }
            }
            if (supportsClipSkip || supportsVaeTiling) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (supportsClipSkip) {
                        OutlinedTextField(
                            value = clipSkipText,
                            onValueChange = onClipSkipTextChange,
                            label = { Text("CLIP skip") },
                            placeholder = { Text("模型默认") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (supportsVaeTiling) {
                        FilterChip(
                            selected = vaeTilingEnabled,
                            onClick = { onVaeTilingEnabledChange(!vaeTilingEnabled) },
                            enabled = !ultraFixEnabled,
                            label = { Text("VAE 分块") }
                        )
                    }
                }
            }
            if (supportsUltraFix && taskMode == ImageGenerationUiTaskMode.IMG2IMG) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = ultraFixEnabled,
                        onClick = { onUltraFixEnabledChange(!ultraFixEnabled) },
                        enabled = ultraFixEnabled || ultraFixInputReady,
                        label = { Text("UltraFix") }
                    )
                    if (!ultraFixEnabled && !ultraFixInputReady) {
                        Text(
                            when {
                                inputImageUri == null -> "选择源图后可启用 UltraFix"
                                inputImageWidth <= 0 || inputImageHeight <= 0 ->
                                    "源图尺寸检查完成后可启用 UltraFix"
                                else -> "源图超出当前模型的 UltraFix 目标范围，请先裁剪图片"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (ultraFixEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = ultraFixRefinementStepsText,
                                onValueChange = onUltraFixRefinementStepsTextChange,
                                label = { Text("总精修步数") },
                                supportingText = { Text("1-20") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = ultraFixInversionStepsText,
                                onValueChange = onUltraFixDenoisingStepsTextChange,
                                label = { Text("实际去噪步数") },
                                supportingText = {
                                    val maximum = ultraFixRefinementStepsText.toIntOrNull()
                                        ?.coerceIn(1, IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS)
                                        ?.let { steps ->
                                            minOf(
                                                IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                                                steps,
                                            )
                                        }
                                        ?: IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS
                                    Text("1-$maximum · strength $ultraFixStrengthText")
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = ultraFixTileSizeText,
                                onValueChange = onUltraFixTileSizeTextChange,
                                label = { Text("分块尺寸") },
                                supportingText = {
                                    Text(
                                        executionModel?.imageUltraFixRequiredTileSize
                                            ?.takeIf { it > 0 }
                                            ?.let { "图固定：$it" }
                                            ?: "128-2048 / 8"
                                    )
                                },
                                readOnly = executionModel?.imageUltraFixRequiredTileSize
                                    ?.let { it > 0 } == true,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = ultraFixOverlapText,
                                onValueChange = onUltraFixOverlapTextChange,
                                label = { Text("重叠比例") },
                                supportingText = { Text("0-0.5") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        TextButton(
                            onClick = onResetUltraFix
                        ) {
                            Icon(
                                Icons.Default.Replay,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("恢复默认")
                        }
                    }
                }
            }
            if (supportsLivePreview) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = livePreviewEnabled,
                        onClick = { onLivePreviewEnabledChange(!livePreviewEnabled) },
                        enabled = !ultraFixEnabled,
                        label = { Text("实时预览") }
                    )
                    Text(
                        "仅影响生成过程中的预览帧，不影响最终图片。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (livePreviewEnabled) {
                        Text("预览间隔（步）", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((1..10).toList()) { interval ->
                                FilterChip(
                                    selected = interval == livePreviewInterval.coerceIn(1, 10),
                                    onClick = { onLivePreviewIntervalChange(interval) },
                                    label = { Text(interval.toString()) }
                                )
                            }
                        }
                        val showVaePreviewCostWarning =
                            shouldShowImageGenerationVaePreviewCostWarning(
                                mode = executionModel?.resolvedImagePreviewMode(),
                                enabled = livePreviewEnabled,
                                interval = livePreviewInterval
                            )
                        if (showVaePreviewCostWarning) {
                            val vaePreviewPerformanceWarning =
                                "每一步预览都会额外执行一次完整 VAE 解码，生成会明显变慢。"
                            Text(
                                vaePreviewPerformanceWarning,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        stateDescription = vaePreviewPerformanceWarning
                                    },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            if (maxBatchCount > 1) {
                Text("输出张数", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Batch is a product-level sequential coordinator feature for QNN/MNN, so
                    // expose every valid count instead of skipping 3/5/6/7.
                    items((1..maxBatchCount.coerceAtLeast(1)).toList()) { count ->
                            FilterChip(
                                selected = count == batchCount,
                                onClick = { onBatchCountChange(count) },
                                enabled = !ultraFixEnabled,
                                label = { Text(count.toString()) }
                        )
                    }
                }
            }
            Text(
                buildString {
                    append("单次输出 ")
                    append(batchCount.coerceIn(1, maxBatchCount))
                    append(" 张")
                    if (supportsVaeTiling && vaeTilingEnabled) {
                        append(" · VAE 分块 ")
                        append(executionModel?.imageDefaultVaeTileSize ?: 512)
                        append(" / 重叠 ")
                        append(executionModel?.imageDefaultVaeTileOverlap ?: 0.5)
                    }
                    if (supportsLivePreview && livePreviewEnabled) {
                        append(" · 每 ")
                        append(livePreviewInterval.coerceIn(1, 10))
                        append(" 步预览")
                    }
                    if (supportsLora && selectedLoras.isNotEmpty()) {
                        append(" · LoRA ")
                        append(selectedLoras.size)
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            }
        }
        }

        pendingLoraDeleteId?.let { adapterId ->
            val adapter = loras.firstOrNull { it.id == adapterId }
            if (adapter != null && !adapter.inUse) {
                AlertDialog(
                    onDismissRequest = { pendingLoraDeleteId = null },
                    title = { Text("删除 LoRA") },
                    text = { Text("确定删除“${adapter.name}”吗？文件会从本机移除，此操作无法撤销。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingLoraDeleteId = null
                                onDeleteLora(adapter.id)
                            }
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingLoraDeleteId = null }) { Text("取消") }
                    }
                )
            }
        }
        pendingTextualInversionDeleteId?.let { artifactId ->
            val artifact = textualInversions.firstOrNull { it.id == artifactId }
            if (artifact != null && !artifact.inUse) {
                AlertDialog(
                    onDismissRequest = { pendingTextualInversionDeleteId = null },
                    title = { Text("删除 Textual Inversion") },
                    text = { Text("确定删除“${artifact.name}”吗？文件会从本机移除，此操作无法撤销。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingTextualInversionDeleteId = null
                                onDeleteTextualInversion(artifact.id)
                            }
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingTextualInversionDeleteId = null }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
        pendingParameterImport?.let { selection ->
            ImageGenerationParameterImportDialog(
                selection = selection,
                currentModelName = executionModel?.displayName.orEmpty(),
                currentTaskMode = taskMode,
                onSelectionChange = { pendingParameterImport = it },
                onDismiss = { pendingParameterImport = null },
                onConfirm = {
                    selection.applicationOrNull(confirm = true)?.let(::applyParameterImport)
                    pendingParameterImport = null
                }
            )
        }
    }

}

@Composable
private fun ImageGenerationParameterImportDialog(
    selection: ImageGenerationParameterImportSelection,
    currentModelName: String,
    currentTaskMode: ImageGenerationUiTaskMode,
    onSelectionChange: (ImageGenerationParameterImportSelection) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val imported = selection.imported
    val sourceLabel = when (imported.source) {
        ImageGenerationParameterImportSource.MCA -> "MCA"
        ImageGenerationParameterImportSource.LOCAL_DREAM -> "Local Dream"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要导入的参数") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    buildString {
                        append("来源：").append(sourceLabel)
                        imported.sourceModelId?.let { append(" · 模型 ").append(it) }
                        imported.taskMode?.let { append(" · 模式 ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    buildString {
                        append("仅填入")
                        append(currentModelName.ifBlank { "当前模型" })
                        append("的").append(currentTaskMode.label)
                        append("面板，不会切换模型或模式，也不会立即生成。")
                        if (selection.hiddenFieldCount > 0) {
                            append(" 不兼容的 ")
                            append(selection.hiddenFieldCount)
                            append(" 项字段已隐藏。")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (selection.compatibleFields.isEmpty()) {
                    Text(
                        "没有可用于当前模型与模式的字段。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    selection.compatibleFields.forEach { field ->
                        FilterChip(
                            selected = field in selection.selectedFields,
                            onClick = { onSelectionChange(selection.toggle(field)) },
                            label = { Text(field.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selection.selectedFields.isNotEmpty()
            ) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ImageInputRoleRow(
    label: String,
    role: String,
    uri: String?,
    onPick: (String) -> Unit,
    onClear: (String) -> Unit,
    onEdit: ((String) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = { onPick(role) }) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (uri == null) "选择$label" else "更换$label")
        }
        Text(
            uri?.let(::imageInputDisplayName) ?: "未选择",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        if (uri != null) {
            if (onEdit != null) {
                IconButton(
                    onClick = { onEdit(role) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "裁剪$label")
                }
            }
            IconButton(onClick = { onClear(role) }) {
                Icon(Icons.Default.Close, contentDescription = "清除$label")
            }
        }
    }
}

@Composable
private fun ImageStrengthField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun imageInputDisplayName(uriString: String): String = runCatching {
    Uri.parse(uriString).lastPathSegment?.substringAfterLast('/')
}.getOrNull()?.takeIf(String::isNotBlank)?.takeLast(64) ?: "已选择图片"

@Composable
private fun ImageGalleryTitleBar(onBack: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
            shape = CircleShape,
            shadowElevation = 7.dp
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(27.dp))
            }
        }
        Text(
            "图片",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
            fontWeight = FontWeight.Bold,
            color = if (darkTheme) MaterialTheme.colorScheme.onBackground else Color(0xFF202124)
        )
        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun ImageGenerationCanvas(
    prompt: String,
    job: ImageGenerationUiJob?,
    image: ImageAssetUiItem?,
    imageModels: List<ChatModelChoice>,
    selectedImageModelId: String?,
    selectedImageModelName: String?,
    selectedImageModelIsCloud: Boolean,
    onBackToGallery: () -> Unit,
    onSelectImageModel: (String) -> Unit,
    onRetry: () -> Unit,
    onCancelGeneration: () -> Unit,
    onUseImageAsset: (String) -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 126.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onBackToGallery,
                    color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                    shape = CircleShape,
                    shadowElevation = 7.dp
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回图片", modifier = Modifier.size(24.dp))
                        Text("图片", fontSize = 17.sp, color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        item {
            ImageEngineSwitcher(
                models = imageModels,
                selectedModelId = selectedImageModelId,
                selectedModelName = selectedImageModelName,
                selectedModelIsCloud = selectedImageModelIsCloud,
                enabled = false,
                onSelectModel = onSelectImageModel
            )
        }
        item {
            UserImagePromptBubble(prompt = prompt)
        }
        item {
            ImageAssistantResultCard(
                job = job,
                image = image,
                onRetry = onRetry,
                onCancelGeneration = onCancelGeneration,
                onUseImageAsset = onUseImageAsset
            )
        }
    }
}

@Composable
private fun UserImagePromptBubble(prompt: String) {
    val darkTheme = isSystemInDarkTheme()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.widthIn(max = 310.dp),
            color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F1F1),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                prompt.ifBlank { "正在准备图片请求" },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 15.dp),
                color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124),
                lineHeight = 23.sp,
                fontSize = 17.sp
            )
        }
    }
}

@Composable
private fun ImageAssistantResultCard(
    job: ImageGenerationUiJob?,
    image: ImageAssetUiItem?,
    onRetry: () -> Unit,
    onCancelGeneration: () -> Unit,
    onUseImageAsset: (String) -> Unit
) {
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (imageAssistantCardKind(job, image)) {
            ImageAssistantCardKind.FAILURE -> ImageGenerationFailureCard(
                job = requireNotNull(job),
                onRetry = onRetry
            )
            ImageAssistantCardKind.RESULT -> ImageGenerationResultImage(
                image = requireNotNull(image),
                onUseImageAsset = onUseImageAsset
            )
            ImageAssistantCardKind.TERMINAL -> ImageGenerationTerminalCard(
                job = requireNotNull(job),
                onRetry = onRetry
            )
            ImageAssistantCardKind.CREATING -> ImageCreatingPlaceholder(
                statusText = job?.statusLabel ?: "正在创建图片",
                statusMessage = job?.message.orEmpty(),
                startedAtMillis = job?.startedAtMillis ?: System.currentTimeMillis(),
                previewUriString = job?.previewUriString,
                previewStep = job?.previewStep ?: 0,
                previewRevision = job?.previewRevision ?: 0L,
                onCancel = onCancelGeneration
            )
        }
    }
}

@Composable
private fun ImageCreatingPlaceholder(
    statusText: String,
    statusMessage: String,
    startedAtMillis: Long,
    previewUriString: String?,
    previewStep: Int,
    previewRevision: Long,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val previewBitmap = remember(previewUriString, previewRevision) {
        previewUriString?.let { loadImageBitmap(context, it) }
    }
    val showingPreview = previewBitmap != null
    val cardColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF5F8FF)
    val titleColor = if (showingPreview) {
        Color.White
    } else if (darkTheme) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color(0xFF31415F)
    }
    val bodyColor = if (showingPreview) {
        Color.White.copy(alpha = 0.88f)
    } else if (darkTheme) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Color(0xFF66748A)
    }
    val progressTrackColor = if (darkTheme) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    } else {
        Color.White.copy(alpha = 0.92f)
    }
    val transition = rememberInfiniteTransition(label = "image-create")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1600, easing = LinearEasing)),
        label = "image-create-phase"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
        label = "image-create-pulse"
    )
    var elapsedSeconds by remember(startedAtMillis) {
        mutableStateOf(((System.currentTimeMillis() - startedAtMillis) / 1000L).coerceAtLeast(0L))
    }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - startedAtMillis) / 1000L).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val waitingText = when {
        showingPreview && previewStep > 0 -> "实时预览 · 第 $previewStep 步"
        elapsedSeconds >= 240L -> "已等待 ${elapsedSeconds.formatElapsed()}，本地生成仍在运行"
        elapsedSeconds >= 60L -> "已等待 ${elapsedSeconds.formatElapsed()}"
        else -> "已等待 ${elapsedSeconds} 秒"
    }
    Surface(
        modifier = Modifier
            .width(292.dp)
            .height(292.dp),
        color = cardColor,
        shape = RoundedCornerShape(26.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "生成中的实际预览，第 $previewStep 步",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.30f))
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .zIndex(1f)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    if (statusText == "完成") "正在整理图片" else "正在创建图片",
                    color = titleColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    statusMessage.ifBlank { "MCA 正在等待图像引擎返回结果" },
                    color = bodyColor,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                Text(
                    waitingText,
                    color = if (showingPreview) Color.White else McaPrimaryBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onCancel) {
                    Text("取消生成")
                }
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!showingPreview) {
                    val dot = 1.7.dp.toPx()
                    val gap = 12.dp.toPx()
                    val groups = listOf(
                        Offset(size.width * 0.25f, size.height * 0.45f),
                        Offset(size.width * 0.62f, size.height * 0.72f)
                    )
                    groups.forEachIndexed { groupIndex, origin ->
                        repeat(6) { x ->
                            repeat(5) { y ->
                                val wave = ((x + y + groupIndex * 2) / 12f + phase) % 1f
                                val alpha = (0.18f + (1f - kotlin.math.abs(wave - 0.5f) * 2f) * 0.50f) * pulse
                                drawCircle(
                                    color = McaPrimaryBlue.copy(alpha = alpha.coerceIn(0.12f, 0.62f)),
                                    radius = dot,
                                    center = Offset(origin.x + x * gap, origin.y + y * gap)
                                )
                            }
                        }
                    }
                }
                val trackWidth = size.width * 0.64f
                val trackHeight = 7.dp.toPx()
                val trackLeft = size.width * 0.18f
                val trackTop = size.height - 48.dp.toPx()
                drawRoundRect(
                    color = progressTrackColor,
                    topLeft = Offset(trackLeft, trackTop),
                    size = Size(trackWidth, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                )
                val highlightWidth = trackWidth * 0.34f
                val highlightLeft = trackLeft + (trackWidth + highlightWidth) * phase - highlightWidth
                drawRoundRect(
                    color = McaPrimaryBlue.copy(alpha = 0.55f),
                    topLeft = Offset(highlightLeft.coerceIn(trackLeft - highlightWidth, trackLeft + trackWidth), trackTop),
                    size = Size(highlightWidth, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                )
            }
        }
    }
}

@Composable
private fun ImageGenerationFailureCard(job: ImageGenerationUiJob, onRetry: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF4F6F9),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("失败", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Column(modifier = Modifier.weight(1f)) {
                Text(job.prompt, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124))
                if (job.modelName.isNotBlank()) {
                    Text(job.modelName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
                Text(job.message.ifBlank { "图片生成失败" }, maxLines = 2, color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF5F6368), fontSize = 13.sp)
            }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun ImageGenerationResultImage(image: ImageAssetUiItem, onUseImageAsset: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val darkTheme = isSystemInDarkTheme()
    val bitmap = remember(image.uriString) { loadImageBitmap(context, image.uriString) }
    val ratio = remember(image.width, image.height) {
        if (image.width > 0 && image.height > 0) {
            (image.width.toFloat() / image.height.toFloat()).coerceIn(0.72f, 1.78f)
        } else {
            1.22f
        }
    }
    Box(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth(0.92f)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(22.dp))
            .background(if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFEDEFF1))
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .clickable { onUseImageAsset(image.id) },
            color = Color.Black.copy(alpha = 0.54f),
            shape = CircleShape
        ) {
            Text(
                "编辑",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(13.dp)
                .size(48.dp)
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(42.dp),
                color = Color.Black.copy(alpha = 0.54f),
                shape = CircleShape
            ) {}
            IconButton(
                onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            createCachedImageShareIntent(
                                context = context,
                                image = image,
                                includePrompt = false
                            )
                        }
                        result
                            .mapCatching { intent -> context.startActivity(intent) }
                            .onFailure { error ->
                                Toast.makeText(
                                    context,
                                    error.message?.takeIf(String::isNotBlank) ?: "图片分享失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "分享图片",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

private enum class ImageEngineSource(val title: String, val subtitle: String) {
    LOCAL("本地生图", "设备端图像引擎"),
    CLOUD("云端生图", "API 图像引擎")
}

@Composable
private fun ImageEngineSwitcher(
    models: List<ChatModelChoice>,
    selectedModelId: String?,
    selectedModelName: String?,
    selectedModelIsCloud: Boolean,
    enabled: Boolean = true,
    onSelectModel: (String) -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val chipColor = if (darkTheme) MaterialTheme.colorScheme.surface else Color(0xFFF4F6F9)
    val modelChipColor = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White
    val chipBorder = if (darkTheme) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f) else Color(0xFFE0E3E7)
    val chipTextColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124)
    val chipMutedColor = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF5F6368)
    var sourceMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var modelMenuSource by rememberSaveable { mutableStateOf<ImageEngineSource?>(null) }
    val selectedSource = if (selectedModelIsCloud) ImageEngineSource.CLOUD else ImageEngineSource.LOCAL
    val localModels = models.filterNot { it.cloud }
    val cloudModels = models.filter { it.cloud }
    LaunchedEffect(enabled) {
        if (!enabled) {
            sourceMenuExpanded = false
            modelMenuSource = null
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Surface(
                onClick = { sourceMenuExpanded = true },
                enabled = enabled,
                color = chipColor,
                shape = CircleShape,
                border = BorderStroke(1.dp, chipBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp), tint = chipTextColor)
                    Text(selectedSource.title, fontWeight = FontWeight.Bold, color = chipTextColor)
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = chipMutedColor)
                }
            }
            DropdownMenu(
                expanded = enabled && sourceMenuExpanded,
                onDismissRequest = { sourceMenuExpanded = false }
            ) {
                ImageEngineSource.entries.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            Text(source.title, fontWeight = FontWeight.Bold)
                        },
                        onClick = {
                            sourceMenuExpanded = false
                            modelMenuSource = source
                        }
                    )
                }
            }
        }

        val source = modelMenuSource ?: selectedSource
        val sourceModels = if (source == ImageEngineSource.CLOUD) cloudModels else localModels
        Box(modifier = Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { modelMenuSource = source },
                    enabled = enabled,
                    color = modelChipColor,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, chipBorder),
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            if (source == selectedSource) selectedModelName ?: "选择模型" else source.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = chipTextColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = chipMutedColor)
                    }
                }
                DropdownMenu(
                    expanded = enabled && modelMenuSource == source,
                    onDismissRequest = { modelMenuSource = null },
                    modifier = Modifier
                        .widthIn(min = 220.dp, max = 320.dp)
                        .heightIn(max = 360.dp)
                ) {
                    if (sourceModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(if (source == ImageEngineSource.CLOUD) "暂无云端生图模型" else "暂无本地生图模型") },
                            onClick = { modelMenuSource = null }
                        )
                    } else {
                        sourceModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(model.quant, model.subtitle.takeIf { it.isNotBlank() }).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectModel(model.id)
                                    modelMenuSource = null
                                }
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun ImageGenerationTerminalCard(job: ImageGenerationUiJob, onRetry: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val cancelled = job.statusLabel == "已取消"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF4F6F9),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (cancelled) "已取消" else job.statusLabel,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    job.prompt,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124)
                )
                if (job.modelName.isNotBlank()) {
                    Text(
                        job.modelName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                }
                Text(
                    job.message.ifBlank { if (cancelled) "图片生成已取消" else job.statusLabel },
                    maxLines = 2,
                    color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF5F6368),
                    fontSize = 13.sp
                )
            }
            TextButton(onClick = onRetry) {
                Text(if (cancelled) "按原参数再生成" else "重试")
            }
        }
    }
}

private data class ImagePromptTemplate(
    val title: String,
    val prompt: String,
    val negativePrompt: String,
    val imageRes: Int,
    val accent: Color
)

private val imagePromptTemplates = listOf(
    ImagePromptTemplate(
        "液态金属花园",
        "luminous blue-white liquid metal garden, flowing chrome blossoms, polished silver petals, soft studio lighting, delicate cinematic 3D render, shallow depth of field, pristine reflective surfaces, elegant futuristic atmosphere",
        "blurry, low quality, distorted geometry, warped metal, misshapen flowers, oversaturated colors, harsh shadows, watermark, text, logo, jpeg artifacts",
        R.drawable.template_liquid_garden,
        Color(0xFF7EA9F5)
    ),
    ImagePromptTemplate(
        "晨光工作岛",
        "morning light island desk, blue-white minimalist workspace, clean futuristic office setup, soft sunrise glow through large windows, tidy surfaces, gentle reflections, professional 3D product render, inviting calm atmosphere",
        "clutter, messy cables, low quality, blurry, dim lighting, warped perspective, reflected glare, watermark, text, logo, oversaturated colors",
        R.drawable.template_work_island,
        Color(0xFF74A2E8)
    ),
    ImagePromptTemplate(
        "山海便签",
        "mountain and sea sticky-note collage, misty blue ink-wash painting style, embossed paper texture, layered memo cards, tranquil distant landscape, soft fog, delicate brushwork, harmonious balanced composition",
        "sharp harsh edges, overly bright, low contrast, smudged notes, illegible handwriting, cluttered layout, blurry, watermark, text, logo, jpeg artifacts",
        R.drawable.template_mountain_memo,
        Color(0xFF8BAFA9)
    ),
    ImagePromptTemplate(
        "纸雕分身",
        "paper-cut character portrait, layered pale blue background, glowing blue pencil accent, delicate cut-paper shadows, crisp clean silhouette, dreamy studio lighting, minimal elegant illustration, subtle paper grain",
        "low resolution, blurry edges, torn paper fragments, distorted face, harsh shadows, oversaturated colors, text, watermark, logo, messy composition",
        R.drawable.template_paper_avatar,
        Color(0xFF8BB7F0)
    ),
    ImagePromptTemplate(
        "旅行手帐",
        "travel journal collage, mountain and sea vista, warm analog film tones, vintage paper ticket and map layers, sunlit horizon, nostalgic handmade texture, rich layered composition, gentle photographic grain",
        "cold blue cast, washed-out colors, cluttered layout, unreadable text, low quality, blurry, watermark, logo, oversaturated highlights, harsh contrast",
        R.drawable.template_travel_journal,
        Color(0xFFD4A36D)
    ),
    ImagePromptTemplate(
        "黑白漫画感",
        "black and white comic illustration, small hand-drawn robot character, bold clean ink lines, white background, expressive pose, retro manga style, strong visual hierarchy, no text, crisp monochrome artwork",
        "color, text, speech bubbles, sound effects, watermark, noise, blurry, awkward anatomy, broken linework, low quality, gray gradient",
        R.drawable.template_mono_comic,
        Color(0xFF7F8795)
    ),
    ImagePromptTemplate(
        "未来城市",
        "glass-domed futuristic city, blue-white soft lighting, lush green plants along clean boulevards, soaring transparent architecture, morning mist, optimistic sci-fi atmosphere, detailed wide cinematic 3D render",
        "dark grungy dystopia, neon overload, smog, low quality, blurry, warped buildings, text, watermark, logo, overcrowded scene, harsh shadows",
        R.drawable.template_future_city,
        Color(0xFF79B6E8)
    ),
    ImagePromptTemplate(
        "陶瓷甜点",
        "delicate pale blue ceramic dessert, pastel glaze, soft morning window light, minimalist still life photography, gentle shadows, smooth porcelain texture, airy elegant styling, high-end food photography",
        "harsh shadows, overexposed, dull colors, cracked ceramic, messy background, text, watermark, logo, low quality, blurry, plasticky texture",
        R.drawable.template_ceramic_dessert,
        Color(0xFFB7D9A7)
    )
)

@Composable
private fun ImageTemplateCard(
    template: ImagePromptTemplate,
    cardWidth: Dp,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .width(cardWidth)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            modifier = Modifier
                .width(cardWidth)
                .height(cardWidth * 1.42f),
            color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, template.accent.copy(alpha = 0.18f))
        ) {
            Image(
                painter = painterResource(template.imageRes),
                contentDescription = template.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            template.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF858C98),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ImageJobRow(job: ImageGenerationUiJob, onRetry: () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF4F6F9),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                job.statusLabel,
                modifier = Modifier.width(58.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (job.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    job.prompt,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124)
                )
                if (job.modelName.isNotBlank()) {
                    Text(
                        job.modelName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (job.message.isNotBlank()) {
                    Text(
                        job.message,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF5F6368)
                    )
                }
            }
            if (job.failed) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageAssetTile(
    image: ImageAssetUiItem,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val bitmap = remember(image.uriString) { loadImageBitmap(context, image.uriString) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFEDEFF1))
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                } else {
                    Modifier
                }
            )
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
            )
        }
        if (image.favorite && !selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(48.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(28.dp),
                    color = if (darkTheme) {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                    } else {
                        Color.White.copy(alpha = 0.9f)
                    },
                    shape = CircleShape
                ) {}
                IconButton(onClick = onToggleFavorite, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = "取消收藏",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (selectionMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(28.dp),
                color = if (darkTheme) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                } else {
                    Color.White.copy(alpha = 0.9f)
                },
                shape = CircleShape
            ) {
                Icon(
                    if (selected) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = if (selected) "已选择" else "选择图片",
                    modifier = Modifier.padding(6.dp).size(16.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        val badgeText = imageAssetBadgeText(image)
        if (badgeText.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(7.dp),
                color = Color.Black.copy(alpha = 0.62f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    badgeText,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ImageAssetPreviewOverlay(
    image: ImageAssetUiItem,
    upscalers: List<ImageUpscalerUiItem>,
    selectedUpscalerId: String?,
    upscalerImporting: Boolean,
    upscalerMessage: String,
    upscaleJob: ImageUpscaleUiJob?,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    canUseAsImg2Img: Boolean,
    canUseUltraFix: Boolean,
    onUseAsImg2Img: () -> Unit,
    onUseUltraFix: () -> Unit,
    onImportUpscaler: () -> Unit,
    onDeleteUpscaler: (String) -> Unit,
    onSelectUpscaler: (String) -> Unit,
    onUpscale: (Int) -> Unit,
    onCancelUpscale: () -> Unit,
    onSetFavorite: (Boolean) -> Unit,
    reusablePresetFields: Set<ImageGenerationPresetField>,
    hiddenPresetFieldCount: Int,
    onUseParameters: (ImageGenerationUiPreset, Set<ImageGenerationPresetField>) -> Unit,
    onRecreate: () -> Unit,
    recreateEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(image.uriString) { loadImageBitmap(context, image.uriString) }
    var showGenerationDetails by rememberSaveable(image.id) { mutableStateOf(false) }
    var showParameterReuse by rememberSaveable(image.id) { mutableStateOf(false) }
    var showUpscaleDialog by rememberSaveable(image.id) { mutableStateOf(false) }
    var upscaleTargetScale by rememberSaveable(image.id) { mutableStateOf(2) }
    var pendingUpscalerDeleteId by rememberSaveable(image.id) { mutableStateOf<String?>(null) }
    var showActionsMenu by remember(image.id) { mutableStateOf(false) }
    var savingToGallery by remember(image.id) { mutableStateOf(false) }
    var selectedPresetFields by remember(image.id) {
        mutableStateOf(emptySet<ImageGenerationPresetField>())
    }
    val sourceUpscaleRunning = upscaleJob?.running == true && upscaleJob.sourceImageId == image.id
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = Color.White.copy(alpha = 0.13f), shape = CircleShape) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭预览", tint = Color.White)
                }
            }
            Text(
                image.name,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Surface(color = Color.White.copy(alpha = 0.13f), shape = CircleShape) {
                IconButton(onClick = { onSetFavorite(!image.favorite) }) {
                    Icon(
                        if (image.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (image.favorite) "取消收藏" else "收藏图片",
                        tint = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box {
                Surface(color = Color.White.copy(alpha = 0.13f), shape = CircleShape) {
                    IconButton(onClick = { showActionsMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多图片操作", tint = Color.White)
                    }
                }
                DropdownMenu(
                    expanded = showActionsMenu,
                    onDismissRequest = { showActionsMenu = false }
                ) {
                    if (image.generationDetails.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("查看生成参数") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = {
                                showActionsMenu = false
                                showGenerationDetails = true
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("作为图生图输入") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        enabled = canUseAsImg2Img,
                        onClick = {
                            showActionsMenu = false
                            onUseAsImg2Img()
                        }
                    )
                    if (canUseUltraFix) {
                        DropdownMenuItem(
                            text = { Text("使用 UltraFix 精修") },
                            leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null) },
                            onClick = {
                                showActionsMenu = false
                                onUseUltraFix()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("高清放大") },
                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                        onClick = {
                            showActionsMenu = false
                            showUpscaleDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("分享图片") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            showActionsMenu = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (savingToGallery) "保存中…" else "保存到系统图片库") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        enabled = !savingToGallery,
                        onClick = {
                            showActionsMenu = false
                            scope.launch {
                                savingToGallery = true
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        downloadImageAssetToGallery(context, image)
                                    }
                                    result
                                        .onSuccess { path ->
                                            Toast.makeText(context, "已保存到 $path", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { error ->
                                            Toast.makeText(
                                                context,
                                                error.message ?: "图片保存失败",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                } finally {
                                    savingToGallery = false
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (sourceUpscaleRunning) "放大中，暂不可删除" else "删除图片",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        enabled = !sourceUpscaleRunning,
                        onClick = {
                            showActionsMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 12.dp, vertical = 76.dp)
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(42.dp))
                Text("无法读取图片", color = Color.White.copy(alpha = 0.82f))
            }
        }
        if (image.prompt.isNotBlank() || image.createdAtText.isNotBlank() || image.generationDetails.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "${image.source.displayImageSource()} · ${image.createdAtText} · ${image.sizeText}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp
                    )
                    if (image.prompt.isNotBlank()) {
                        Text(
                            image.prompt,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White.copy(alpha = 0.86f),
                            fontSize = 13.sp
                        )
                    }
                    if (image.generationDetails.isNotBlank()) {
                        Text(
                            image.generationDetails,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp
                        )
                    }
                    if (image.canRecreate) {
                        TextButton(
                            onClick = onRecreate,
                            enabled = recreateEnabled
                        ) {
                            Text("按原参数再生成", color = Color.White)
                        }
                    }
                }
                }
            }
        }
    if (showUpscaleDialog && pendingUpscalerDeleteId == null) {
        val selectedUpscaler = upscalers.firstOrNull { it.id == selectedUpscalerId }
        val currentImageJob = upscaleJob?.takeIf { it.sourceImageId == image.id }
        val anotherImageRunning = upscaleJob?.running == true && currentImageJob == null
        val outputDimensions = upscaleOutputDimensionsOrNull(
            image.width,
            image.height,
            upscaleTargetScale
        )
        AlertDialog(
            onDismissRequest = { showUpscaleDialog = false },
            title = { Text("高清放大") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "选择本地 ESRGAN 模型和目标倍率。任务会保留当前源图与模型快照，结果完成后自动进入图片库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("放大模型", style = MaterialTheme.typography.titleSmall)
                    if (upscalers.isEmpty()) {
                        Text(
                            if (upscalerImporting) "正在导入放大模型…" else "尚未导入放大模型。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        upscalers.forEach { upscaler ->
                            FilterChip(
                                selected = upscaler.id == selectedUpscalerId,
                                onClick = { onSelectUpscaler(upscaler.id) },
                                enabled = upscaleJob?.running != true && !upscaler.deleting,
                                label = {
                                    Column {
                                        Text(upscaler.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${upscaler.sizeText} · ${upscaler.sha256.take(12)}…",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onImportUpscaler,
                            enabled = !upscalerImporting && upscaleJob?.running != true
                        ) {
                            Text(if (upscalerImporting) "导入中…" else "导入模型")
                        }
                        TextButton(
                            onClick = {
                                selectedUpscaler?.let { pendingUpscalerDeleteId = it.id }
                            },
                            enabled = selectedUpscaler != null &&
                                !selectedUpscaler.inUse &&
                                !selectedUpscaler.deleting &&
                                upscaleJob?.running != true
                        ) {
                            Text("删除所选", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text("目标倍率", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IMAGE_UPSCALE_TARGET_SCALES.forEach { scale ->
                            FilterChip(
                                selected = upscaleTargetScale == scale,
                                onClick = { upscaleTargetScale = scale },
                                enabled = upscaleJob?.running != true,
                                label = { Text("${scale}x") }
                            )
                        }
                    }
                    outputDimensions?.let { (width, height) ->
                        Text(
                            "${image.width}x${image.height} → ${width}x$height",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (image.width > 0 && image.height > 0 && outputDimensions == null) {
                        Text(
                            "当前尺寸与倍率超出本地放大上限（输入 2048/4MP，输出 4096/16MP）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    val status = when {
                        currentImageJob != null -> currentImageJob.message.ifBlank {
                            currentImageJob.statusLabel
                        }
                        anotherImageRunning -> "另一张图片正在放大，请等待完成或先停止该任务。"
                        else -> upscalerMessage
                    }
                    if (status.isNotBlank()) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (currentImageJob?.failed == true) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (currentImageJob?.running == true) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                if (currentImageJob?.running == true) {
                    Button(onClick = onCancelUpscale) { Text("停止") }
                } else {
                    Button(
                        onClick = { onUpscale(upscaleTargetScale) },
                        enabled = selectedUpscaler != null &&
                            !upscalerImporting &&
                            !anotherImageRunning &&
                            (image.width <= 0 || image.height <= 0 || outputDimensions != null)
                    ) {
                        Text(if (currentImageJob?.failed == true) "重试放大" else "开始放大")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpscaleDialog = false }) { Text("关闭") }
            }
        )
    }
    pendingUpscalerDeleteId?.let { upscalerId ->
        val upscaler = upscalers.firstOrNull { it.id == upscalerId }
        AlertDialog(
            onDismissRequest = { pendingUpscalerDeleteId = null },
            title = { Text("删除放大模型") },
            text = {
                Text("确定删除 ${upscaler?.name ?: "所选模型"}？模型文件会从本机移除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUpscalerDeleteId = null
                        onDeleteUpscaler(upscalerId)
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUpscalerDeleteId = null }) { Text("取消") }
            }
        )
    }
    if (showGenerationDetails) {
        AlertDialog(
            onDismissRequest = { showGenerationDetails = false },
            title = { Text("生成参数") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(image.generationDetails, style = MaterialTheme.typography.bodyMedium)
                    val reproduciblePrompt = image.generationPrompt.ifBlank { image.prompt }
                    if (reproduciblePrompt.isNotBlank()) {
                        Text("提示词", style = MaterialTheme.typography.titleSmall)
                        Text(reproduciblePrompt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Row {
                    if (image.parameterShareJson.isNotBlank()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "MCA image parameters",
                                                image.parameterShareJson
                                            )
                                        )
                                    )
                                    Toast.makeText(context, "生成参数已复制", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("复制参数")
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val encoded = runCatching {
                                        ImageGenerationParameterImportCodec.encodeMcaBase64(
                                            image.parameterShareJson
                                        )
                                    }.getOrElse { error ->
                                        Toast.makeText(
                                            context,
                                            error.message?.takeIf(String::isNotBlank) ?: "参数编码失败",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "MCA image parameters Base64",
                                                encoded
                                            )
                                        )
                                    )
                                    Toast.makeText(context, "Base64 参数已复制", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("复制 Base64")
                        }
                    }
                    if (image.generationPreset != null) {
                        TextButton(
                            onClick = {
                                selectedPresetFields = reusablePresetFields
                                showGenerationDetails = false
                                showParameterReuse = true
                            }
                        ) {
                            Text("复用参数")
                        }
                    }
                    if (image.canRecreate) {
                        TextButton(
                            onClick = {
                                showGenerationDetails = false
                                onRecreate()
                            },
                            enabled = recreateEnabled
                        ) {
                            Text("按原参数再生成")
                        }
                    }
                    TextButton(onClick = { showGenerationDetails = false }) {
                        Text("关闭")
                    }
                }
            }
        )
    }
    if (showParameterReuse) {
        val preset = image.generationPreset
        if (preset == null) {
            showParameterReuse = false
        } else {
            val availableFields = reusablePresetFields
            AlertDialog(
                onDismissRequest = { showParameterReuse = false },
                title = { Text("选择要复用的参数") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            buildString {
                                append("参数会填入当前模型的生成面板，不会立即开始生成。")
                                if (hiddenPresetFieldCount > 0) {
                                    append(" 当前模型不兼容的 ")
                                    append(hiddenPresetFieldCount)
                                    append(" 项参数已隐藏。")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        availableFields.forEach { field ->
                            FilterChip(
                                selected = field in selectedPresetFields,
                                onClick = {
                                    selectedPresetFields = if (field in selectedPresetFields) {
                                        selectedPresetFields - field
                                    } else {
                                        selectedPresetFields + field
                                    }
                                },
                                label = { Text(field.label) }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onUseParameters(preset, selectedPresetFields)
                            showParameterReuse = false
                        },
                        enabled = selectedPresetFields.isNotEmpty()
                    ) { Text("应用") }
                },
                dismissButton = {
                    TextButton(onClick = { showParameterReuse = false }) { Text("取消") }
                }
            )
        }
    }
}

private fun availableImageGenerationPresetFields(
    preset: ImageGenerationUiPreset
): Set<ImageGenerationPresetField> = buildSet {
    val hasStructuredUltraFixPayload = preset.ultraFix != null
    add(ImageGenerationPresetField.PROMPT)
    add(ImageGenerationPresetField.NEGATIVE_PROMPT)
    if (!hasStructuredUltraFixPayload && preset.width != null && preset.height != null) {
        add(ImageGenerationPresetField.SIZE)
    }
    if (!hasStructuredUltraFixPayload && preset.steps != null) {
        add(ImageGenerationPresetField.STEPS)
    }
    if (preset.cfgScale != null) add(ImageGenerationPresetField.CFG)
    if (preset.seed != null) add(ImageGenerationPresetField.SEED)
    if (!preset.sampleMethod.isNullOrBlank()) add(ImageGenerationPresetField.SAMPLER)
    if (preset.clipSkip != null) add(ImageGenerationPresetField.CLIP_SKIP)
    if (preset.loras.isNotEmpty()) add(ImageGenerationPresetField.LORA)
    if (preset.textualInversionIds.isNotEmpty()) {
        add(ImageGenerationPresetField.TEXTUAL_INVERSION)
    }
    if (preset.ultraFix != null) add(ImageGenerationPresetField.ULTRAFIX)
    if (!hasStructuredUltraFixPayload && preset.strength != null) {
        add(ImageGenerationPresetField.STRENGTH)
    }
    if (preset.controlStrength != null) add(ImageGenerationPresetField.CONTROL_STRENGTH)
    if (!hasStructuredUltraFixPayload && preset.batchCount != null) {
        add(ImageGenerationPresetField.BATCH)
    }
    if (!hasStructuredUltraFixPayload && preset.vaeTileSize != null) {
        add(ImageGenerationPresetField.VAE_TILING)
    }
}

internal fun compatibleImageGenerationPresetFields(
    preset: ImageGenerationUiPreset,
    selectedModel: ChatModelChoice?,
    selectedModelIsCloud: Boolean,
    supportsNegativePrompt: Boolean,
    supportsClipSkip: Boolean,
    supportsVaeTiling: Boolean,
    supportsLora: Boolean,
    availableLoraIds: Set<String>,
    maxBatchCount: Int,
    currentTaskMode: ImageGenerationUiTaskMode = ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
    supportsTextualInversion: Boolean = false,
    supportsUltraFix: Boolean = false,
    availableTextualInversionIds: Set<String> = emptySet(),
): Set<ImageGenerationPresetField> {
    val available = availableImageGenerationPresetFields(preset)
    return buildSet {
        if (ImageGenerationPresetField.PROMPT in available) add(ImageGenerationPresetField.PROMPT)
        if (supportsNegativePrompt && ImageGenerationPresetField.NEGATIVE_PROMPT in available) {
            add(ImageGenerationPresetField.NEGATIVE_PROMPT)
        }
        if (ImageGenerationPresetField.STRENGTH in available &&
            preset.taskMode in setOf(
                ImageGenerationUiTaskMode.IMG2IMG,
                ImageGenerationUiTaskMode.INPAINT,
            ) &&
            currentTaskMode in setOf(
                ImageGenerationUiTaskMode.IMG2IMG,
                ImageGenerationUiTaskMode.INPAINT,
            ) &&
            preset.strength?.let { it.isFinite() && it in 0.0..1.0 } == true
        ) {
            add(ImageGenerationPresetField.STRENGTH)
        }
        if (ImageGenerationPresetField.CONTROL_STRENGTH in available &&
            preset.taskMode == ImageGenerationUiTaskMode.CONTROL &&
            currentTaskMode == ImageGenerationUiTaskMode.CONTROL &&
            preset.controlStrength?.let { it.isFinite() && it in 0.0..2.0 } == true
        ) {
            add(ImageGenerationPresetField.CONTROL_STRENGTH)
        }
        if (!selectedModelIsCloud && selectedModel != null) {
            val width = preset.width
            val height = preset.height
            val ultraFixSizeContract = supportsUltraFix &&
                currentTaskMode == ImageGenerationUiTaskMode.IMG2IMG &&
                preset.ultraFix != null
            val minWidth = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixMinWidth
            } else {
                selectedModel.imageMinWidth
            }
            val maxWidth = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixMaxWidth
            } else {
                selectedModel.imageMaxWidth
            }
            val minHeight = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixMinHeight
            } else {
                selectedModel.imageMinHeight
            }
            val maxHeight = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixMaxHeight
            } else {
                selectedModel.imageMaxHeight
            }
            val widthMultiple = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixWidthMultiple
            } else {
                selectedModel.imageWidthMultiple
            }
            val heightMultiple = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixHeightMultiple
            } else {
                selectedModel.imageHeightMultiple
            }
            if (ImageGenerationPresetField.SIZE in available &&
                width != null && height != null &&
                width in minWidth..maxWidth && height in minHeight..maxHeight &&
                width % widthMultiple == 0 && height % heightMultiple == 0 &&
                (!ultraFixSizeContract ||
                    width.toLong() * height.toLong() <= 64L * 1024L * 1024L)
            ) {
                add(ImageGenerationPresetField.SIZE)
            }
            if (ImageGenerationPresetField.STEPS in available &&
                preset.steps?.let { it in selectedModel.imageMinSteps..selectedModel.imageMaxSteps } == true
            ) {
                add(ImageGenerationPresetField.STEPS)
            }
            if (ImageGenerationPresetField.CFG in available &&
                preset.cfgScale?.let { it.isFinite() && it in 0.0..30.0 } == true
            ) {
                add(ImageGenerationPresetField.CFG)
            }
            if (ImageGenerationPresetField.SEED in available && preset.seed?.let { it >= 0 } == true) {
                add(ImageGenerationPresetField.SEED)
            }
            if (ImageGenerationPresetField.SAMPLER in available &&
                preset.sampleMethod in selectedModel.imageSupportedSamplersForTask(currentTaskMode)
            ) {
                add(ImageGenerationPresetField.SAMPLER)
            }
            if (ImageGenerationPresetField.BATCH in available &&
                preset.batchCount?.let { it in 1..maxBatchCount.coerceAtLeast(1) } == true
            ) {
                add(ImageGenerationPresetField.BATCH)
            }
        }
        if (supportsClipSkip && ImageGenerationPresetField.CLIP_SKIP in available) {
            add(ImageGenerationPresetField.CLIP_SKIP)
        }
        if (supportsVaeTiling &&
            ImageGenerationPresetField.VAE_TILING in available &&
            selectedModel != null &&
            preset.vaeTileSize == selectedModel.imageDefaultVaeTileSize &&
            preset.vaeTileOverlap?.let {
                kotlin.math.abs(it - selectedModel.imageDefaultVaeTileOverlap) < 0.000_001
            } == true
        ) {
            add(ImageGenerationPresetField.VAE_TILING)
        }
        if (supportsLora &&
            ImageGenerationPresetField.LORA in available &&
            preset.loras.isNotEmpty() &&
            preset.loras.all { selection -> selection.id in availableLoraIds }
        ) {
            add(ImageGenerationPresetField.LORA)
        }
        if (supportsTextualInversion &&
            ImageGenerationPresetField.TEXTUAL_INVERSION in available &&
            preset.textualInversionIds.size <= 8 &&
            preset.textualInversionIds.distinct().size == preset.textualInversionIds.size &&
            preset.textualInversionIds.all(availableTextualInversionIds::contains)
        ) {
            add(ImageGenerationPresetField.TEXTUAL_INVERSION)
        }
        val ultraFix = preset.ultraFix
        if (!selectedModelIsCloud && selectedModel != null && supportsUltraFix &&
            currentTaskMode == ImageGenerationUiTaskMode.IMG2IMG &&
            ImageGenerationPresetField.ULTRAFIX in available && ultraFix != null &&
            ultraFix.targetWidth in selectedModel.imageUltraFixMinWidth..
                selectedModel.imageUltraFixMaxWidth &&
            ultraFix.targetHeight in selectedModel.imageUltraFixMinHeight..
                selectedModel.imageUltraFixMaxHeight &&
            ultraFix.targetWidth % selectedModel.imageUltraFixWidthMultiple == 0 &&
            ultraFix.targetHeight % selectedModel.imageUltraFixHeightMultiple == 0 &&
            ultraFix.targetWidth.toLong() * ultraFix.targetHeight.toLong() <=
                64L * 1024L * 1024L &&
            ultraFix.strength.isFinite() && ultraFix.strength > 0.0 && ultraFix.strength <= 1.0 &&
            ultraFix.refinementSteps in 1..IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS &&
            ultraFix.inversionSteps in 1..minOf(
                IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                ultraFix.refinementSteps,
            ) &&
            ultraFix.inversionSteps == imageGenerationUltraFixDenoisingTailStepCount(
                ultraFix.refinementSteps,
                ultraFix.strength,
            ) &&
            ultraFix.tileSize in 128..2048 && ultraFix.tileSize % 8 == 0 &&
            ultraFix.tileSize % selectedModel.imageUltraFixWidthMultiple == 0 &&
            ultraFix.tileSize % selectedModel.imageUltraFixHeightMultiple == 0 &&
            (selectedModel.imageUltraFixRequiredTileSize == 0 ||
                ultraFix.tileSize == selectedModel.imageUltraFixRequiredTileSize) &&
            ultraFix.tileSize <= minOf(ultraFix.targetWidth, ultraFix.targetHeight) &&
            ultraFix.overlap.isFinite() && ultraFix.overlap in 0.0..0.5
        ) {
            add(ImageGenerationPresetField.ULTRAFIX)
        }
    }
}

private const val IMAGE_PROMPT_TOKEN_MEASUREMENT_DEBOUNCE_MILLIS = 250L

private sealed interface ImagePromptTokenUiState {
    data object Unavailable : ImagePromptTokenUiState
    data object Measuring : ImagePromptTokenUiState
    data class Measured(val value: ImagePromptTokenMeasurement) : ImagePromptTokenUiState
}

private data class ImagePromptTokenPublication(
    val modelId: String,
    val prompt: String,
    val measurement: ImagePromptTokenMeasurement?,
)

@Composable
private fun rememberImagePromptTokenUiState(
    modelId: String?,
    prompt: String,
    onMeasureTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)?,
): ImagePromptTokenUiState {
    val normalizedModelId = modelId?.trim()?.takeIf(String::isNotEmpty)
    val latestMeasurer by rememberUpdatedState(onMeasureTokens)
    var publication by remember { mutableStateOf<ImagePromptTokenPublication?>(null) }

    LaunchedEffect(normalizedModelId, prompt, onMeasureTokens != null) {
        val selectedModelId = normalizedModelId
        val measurer = latestMeasurer
        if (selectedModelId == null || measurer == null) {
            publication = null
            return@LaunchedEffect
        }
        if (prompt.isBlank()) {
            publication = null
            return@LaunchedEffect
        }

        delay(IMAGE_PROMPT_TOKEN_MEASUREMENT_DEBOUNCE_MILLIS)
        val measured = try {
            measurer(selectedModelId, prompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        publication = ImagePromptTokenPublication(
            modelId = selectedModelId,
            prompt = prompt,
            measurement = measured?.copy(
                overflowOffset = measured.overflowOffset?.takeIf { offset ->
                    offset <= prompt.length
                },
            ),
        )
    }

    if (normalizedModelId == null || onMeasureTokens == null) {
        return ImagePromptTokenUiState.Unavailable
    }
    // Match Local Dream's empty-field behavior: do not surface BOS/EOS-only
    // counts or a transient spinner before the user has entered a prompt.
    if (prompt.isBlank()) {
        return ImagePromptTokenUiState.Unavailable
    }
    val current = publication
    if (current == null || current.modelId != normalizedModelId || current.prompt != prompt) {
        return ImagePromptTokenUiState.Measuring
    }
    return current.measurement
        ?.let { ImagePromptTokenUiState.Measured(it) }
        ?: ImagePromptTokenUiState.Unavailable
}

private val ImagePromptTokenUiState.measurementOrNull: ImagePromptTokenMeasurement?
    get() = (this as? ImagePromptTokenUiState.Measured)?.value

private class ImagePromptOverflowVisualTransformation(
    private val overflowOffset: Int,
    private val overflowColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val start = overflowOffset.coerceIn(0, text.length)
        if (start >= text.length) return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(
            text = buildAnnotatedString {
                append(text)
                addStyle(SpanStyle(color = overflowColor), start, text.length)
            },
            offsetMapping = OffsetMapping.Identity,
        )
    }
}

@Composable
private fun imagePromptVisualTransformation(
    tokenState: ImagePromptTokenUiState,
): VisualTransformation {
    val measurement = tokenState.measurementOrNull
    val overflowColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    return remember(measurement, overflowColor) {
        val overflowOffset = measurement
            ?.takeIf(ImagePromptTokenMeasurement::overflows)
            ?.overflowOffset
        if (overflowOffset == null) {
            VisualTransformation.None
        } else {
            ImagePromptOverflowVisualTransformation(overflowOffset, overflowColor)
        }
    }
}

@Composable
private fun ImagePromptTokenStatus(
    state: ImagePromptTokenUiState,
    showUnavailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurement = state.measurementOrNull
    val unavailable = state == ImagePromptTokenUiState.Unavailable && showUnavailable
    val overflowCount = measurement
        ?.takeIf(ImagePromptTokenMeasurement::overflows)
        ?.let { it.count - it.maxTokens }
    val label = when {
        state == ImagePromptTokenUiState.Measuring -> "正在精确计算 Token…"
        unavailable -> "Token 数量不可用"
        measurement == null -> null
        overflowCount != null ->
            "超出 $overflowCount 个 Token · ${measurement.count} / ${measurement.maxTokens}"
        else -> "${measurement.count} / ${measurement.maxTokens} Token"
    } ?: return
    val accessibilityLabel = when {
        state == ImagePromptTokenUiState.Unavailable ->
            "当前模型无法提供精确 Token 数量"
        overflowCount != null ->
            "提示词超出模型上限 $overflowCount 个 Token，当前 ${measurement?.count}，上限 ${measurement?.maxTokens}"
        measurement != null ->
            "提示词 Token 数 ${measurement.count}，上限 ${measurement.maxTokens}"
        else -> "正在使用模型 Tokenizer 精确计算提示词长度"
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (overflowCount != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.semantics {
            stateDescription = accessibilityLabel
            if (overflowCount != null || unavailable) liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
private fun ImageNegativePromptTagField(
    value: String,
    onValueChange: (String) -> Unit,
    modelId: String?,
    onMeasureTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)?,
    modifier: Modifier = Modifier
) {
    val tagSession = LocalImagePromptTagAutocompleteSession.current
    val editHistory = remember { ImagePromptEditHistory() }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value, TextRange(value.length))
            editHistory.replace()
        }
    }

    val tokenState = rememberImagePromptTokenUiState(modelId, fieldValue.text, onMeasureTokens)
    val tokenVisualTransformation = imagePromptVisualTransformation(tokenState)
    val tokenOverflow = tokenState.measurementOrNull?.overflows == true

    fun commitFieldValue(next: TextFieldValue) {
        fieldValue = next
        if (next.text != value) onValueChange(next.text)
    }

    fun applyFieldValue(next: TextFieldValue) {
        if (next.text != fieldValue.text) editHistory.recordContinuous(fieldValue)
        commitFieldValue(next)
    }

    fun applyDiscreteFieldValue(next: TextFieldValue) {
        if (next.text != fieldValue.text) editHistory.recordDiscrete(fieldValue)
        commitFieldValue(next)
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = ::applyFieldValue,
            label = { Text("负面提示词") },
            placeholder = { Text("留空使用模型默认") },
            minLines = 2,
            maxLines = 4,
            isError = tokenOverflow,
            visualTransformation = tokenVisualTransformation,
            trailingIcon = {
                IconButton(
                    onClick = tagSession::openManager,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "标签联想与词典设置")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )
        ImagePromptTokenStatus(
            state = tokenState,
            showUnavailable = fieldValue.text.isNotBlank(),
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 4.dp, end = 4.dp),
        )
        ImagePromptTagAssistPanel(
            value = fieldValue,
            focused = focused,
            onEdit = ::applyDiscreteFieldValue,
            onUndo = { editHistory.undo(fieldValue)?.let(::commitFieldValue) },
            onRedo = { editHistory.redo(fieldValue)?.let(::commitFieldValue) },
            undoEnabled = editHistory.undoEnabled,
            redoEnabled = editHistory.redoEnabled,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ImagePromptBar(
    prompt: String,
    onPromptChange: (String) -> Unit,
    modelId: String?,
    onMeasureTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)?,
    onOpenPhoto: () -> Unit,
    onSubmit: () -> Unit,
    placeholder: String = "描述图像",
    isGenerating: Boolean = false,
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    val inputShellColor = if (darkTheme) MaterialTheme.colorScheme.surface else McaInputShell
    val inputFieldColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputField
    val inputIconSurfaceColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputIconSurface
    val inputTextColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else McaInputText
    val inputPlaceholderColor = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else McaInputPlaceholder
    val tagSession = LocalImagePromptTagAutocompleteSession.current
    val editHistory = remember { ImagePromptEditHistory() }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(prompt, TextRange(prompt.length)))
    }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(prompt) {
        if (prompt != fieldValue.text) {
            fieldValue = TextFieldValue(prompt, TextRange(prompt.length))
            editHistory.replace()
        }
    }

    val tokenState = rememberImagePromptTokenUiState(modelId, fieldValue.text, onMeasureTokens)
    val tokenVisualTransformation = imagePromptVisualTransformation(tokenState)
    val tokenOverflow = tokenState.measurementOrNull?.overflows == true

    fun commitFieldValue(next: TextFieldValue) {
        fieldValue = next
        if (next.text != prompt) onPromptChange(next.text)
    }

    fun applyFieldValue(next: TextFieldValue) {
        if (next.text != fieldValue.text) editHistory.recordContinuous(fieldValue)
        commitFieldValue(next)
    }

    fun applyDiscreteFieldValue(next: TextFieldValue) {
        if (next.text != fieldValue.text) editHistory.recordDiscrete(fieldValue)
        commitFieldValue(next)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ImagePromptTagAssistPanel(
            value = fieldValue,
            focused = focused,
            onEdit = ::applyDiscreteFieldValue,
            onUndo = { editHistory.undo(fieldValue)?.let(::commitFieldValue) },
            onRedo = { editHistory.redo(fieldValue)?.let(::commitFieldValue) },
            undoEnabled = editHistory.undoEnabled,
            redoEnabled = editHistory.redoEnabled,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            color = inputShellColor,
            shape = RoundedCornerShape(36.dp),
            shadowElevation = 14.dp
        ) {
            Row(
                modifier = Modifier.padding(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(inputIconSurfaceColor)
                        .clickable(onClick = onOpenPhoto),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "添加图片",
                        modifier = Modifier.size(22.dp),
                        tint = McaPrimaryBlue
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    color = inputFieldColor,
                    shape = CircleShape,
                    border = if (tokenOverflow) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    } else {
                        null
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (fieldValue.text.isBlank()) {
                                    Text(
                                        placeholder,
                                        color = inputPlaceholderColor,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 15.sp,
                                            lineHeight = 20.sp
                                        )
                                    )
                                }
                                BasicTextField(
                                    value = fieldValue,
                                    onValueChange = ::applyFieldValue,
                                    maxLines = 3,
                                    visualTransformation = tokenVisualTransformation,
                                    textStyle = TextStyle(
                                        color = inputTextColor,
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp
                                    ),
                                    cursorBrush = SolidColor(McaPrimaryBlue),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { focused = it.isFocused }
                                )
                            }
                            ImagePromptTokenStatus(
                                state = tokenState,
                                showUnavailable = fieldValue.text.isNotBlank(),
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .padding(top = 2.dp),
                            )
                        }
                        IconButton(
                            onClick = tagSession::openManager,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "标签联想与词典设置",
                                tint = McaPrimaryBlue
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                FloatingActionButton(
                    onClick = if (isGenerating) onStop else onSubmit,
                    containerColor = when {
                        isGenerating -> McaPrimaryBlue
                        prompt.isBlank() -> if (darkTheme) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            Color(0xFFE5ECF8)
                        }
                        else -> McaPrimaryBlue
                    },
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    if (isGenerating) {
                        Icon(Icons.Default.Stop, contentDescription = "停止生成", tint = Color.White)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "生成图片",
                            tint = if (prompt.isBlank()) inputPlaceholderColor else Color.White
                        )
                    }
                }
            }
        }
    }
}

private fun loadImageBitmap(context: Context, uriString: String): Bitmap? =
    runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme.equals("file", ignoreCase = true)) {
            BitmapFactory.decodeFile(uri.path)
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

private fun downloadImageAssetToGallery(context: Context, image: ImageAssetUiItem): Result<String> =
    runCatching { copyImageAssetToGallery(context, image).displayPath }

private data class SavedImageCopy(
    val uri: Uri,
    val displayPath: String,
    val mimeType: String
)

private fun copyImageAssetToGallery(context: Context, image: ImageAssetUiItem): SavedImageCopy =
    runCatching {
        val fileName = image.downloadFileName()
        val mimeType = fileName.imageMimeType()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MCA")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建系统图片文件")
        try {
            openImageAssetInputStream(context, image.uriString).use { input ->
                resolver.openOutputStream(outputUri)?.use { output ->
                    input.copyTo(output)
                } ?: error("无法写入系统图片库")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(outputUri, values, null, null)
            }
        } catch (error: Throwable) {
            resolver.delete(outputUri, null, null)
            throw error
        }
        SavedImageCopy(
            uri = outputUri,
            displayPath = "${Environment.DIRECTORY_PICTURES}/MCA/$fileName",
            mimeType = mimeType
        )
    }.getOrThrow()

private fun openImageAssetInputStream(context: Context, uriString: String): InputStream {
    val uri = Uri.parse(uriString)
    return if (uri.scheme.equals("file", ignoreCase = true)) {
        File(requireNotNull(uri.path) { "图片路径无效" }).inputStream()
    } else {
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取图片文件" }
    }
}

private fun ImageAssetUiItem.downloadFileName(): String {
    val rawName = name.ifBlank { "MCA-${id.take(8)}" }
    val extension = rawName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in setOf("png", "jpg", "jpeg", "webp") }
        ?: uriString.substringAfterLast('.', "")
            .substringBefore('?')
            .lowercase()
            .takeIf { it in setOf("png", "jpg", "jpeg", "webp") }
        ?: "png"
    val base = rawName.substringBeforeLast('.', rawName)
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .trim('_')
        .take(64)
        .ifBlank { "MCA-${id.take(8)}" }
    return "$base.$extension"
}

private fun String.imageMimeType(): String =
    when (substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

private fun String.displayImageSource(): String =
    when {
        startsWith("upscaled:", ignoreCase = true) -> "高清放大"
        startsWith("generated:", ignoreCase = true) -> removePrefix("generated:")
            .replace('-', ' ')
            .replace('_', ' ')
            .ifBlank { "生成图片" }
        equals("generated", ignoreCase = true) -> "生成图片"
        equals("uploaded", ignoreCase = true) -> "上传图片"
        isBlank() -> "图片"
        else -> this
    }

private fun Long.formatElapsed(): String {
    val minutes = this / 60L
    val seconds = this % 60L
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun PredictiveAppMenuPage(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    SmoothRightToLeftPage(
        visible = visible,
        onDismiss = onDismiss,
        content = content
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SmoothRightToLeftPage(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = appMenuPageEnter(),
        exit = ExitTransition.None
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val scope = rememberCoroutineScope()
            val offsetX = remember { Animatable(0f) }
            // Let the IME own the first Back press. Some Android builds dispatch that event
            // through the page callback while a text field is acquiring focus.
            val imeVisible = WindowInsets.isImeVisible

            LaunchedEffect(visible) {
                if (visible) offsetX.snapTo(0f)
            }

            suspend fun closeAnimated() {
                offsetX.animateTo(
                    targetValue = widthPx,
                    animationSpec = tween(durationMillis = 180)
                )
                onDismiss()
            }

            fun closeWithDrawerMotion() {
                scope.launch {
                    closeAnimated()
                }
            }

            SystemBackMotionHandler(
                enabled = visible && !imeVisible,
                onProgress = { progress ->
                    scope.launch {
                        offsetX.snapTo(widthPx * progress.coerceIn(0f, 1f))
                    }
                },
                onCancel = {
                    scope.launch {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 160)
                        )
                    }
                },
                onBack = {
                    scope.launch {
                        if (offsetX.value < widthPx * 0.08f) {
                            offsetX.snapTo(widthPx * 0.08f)
                        }
                        closeAnimated()
                    }
                }
            )

            val pageModifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }

            content(pageModifier, ::closeWithDrawerMotion)
        }
    }
}

private fun appMenuPageEnter() = slideInHorizontally(
    animationSpec = tween(durationMillis = 240),
    initialOffsetX = { it }
) + fadeIn(animationSpec = tween(durationMillis = 140))

private fun appMenuPageExit() = slideOutHorizontally(
    animationSpec = tween(durationMillis = 240),
    targetOffsetX = { it }
) + fadeOut(animationSpec = tween(durationMillis = 140))

@Composable
private fun SystemBackMotionHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val currentOnBack by rememberUpdatedState(onBack)

    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                currentOnProgress(0f)
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                currentOnProgress(backEvent.progress)
            }

            override fun handleOnBackCancelled() {
                currentOnCancel()
            }

            override fun handleOnBackPressed() {
                currentOnBack()
            }
        }
    }

    LaunchedEffect(enabled) {
        callback.isEnabled = enabled
    }

    androidx.compose.runtime.DisposableEffect(dispatcher, lifecycleOwner, callback) {
        dispatcher?.addCallback(lifecycleOwner, callback)
        onDispose {
            callback.remove()
        }
    }
}

@Composable
private fun McaAppMenuPage(
    state: ChatUiState,
    onClose: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAssistants: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .navigationBarsPadding()
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(22.dp))
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            McaLogoMark(size = 66.dp, cornerRadius = 18.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "MuYu Chat Agent",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                fontWeight = FontWeight.Black
            )
        }
        Spacer(modifier = Modifier.height(28.dp))

        Text(
            "MCA",
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppMenuCard {
            AppMenuRow(
                icon = { Icon(Icons.Default.Psychology, null) },
                title = "助手与角色",
                subtitle = state.assistants.firstOrNull { it.selected }?.let { "${it.name} · 角色卡与能力" } ?: "当前助手、角色卡、提示词与能力",
                onClick = onOpenAssistants
            )
            AppMenuRow(icon = { Icon(Icons.Default.Folder, null) }, title = "模型管理", subtitle = "本地高速/兼容引擎与魔塔下载", onClick = onOpenModels)
            AppMenuRow(icon = { McaLogoMark(size = 22.dp, cornerRadius = 7.dp) }, title = "智能调参", subtitle = "测速、推荐与高级参数", onClick = onOpenAgent)
            AppMenuRow(icon = { Icon(Icons.Default.NetworkWifi, null) }, title = "本地 API", subtitle = "接口地址、Key、API 使用文档", onClick = onOpenApi)
            AppMenuRow(icon = { Icon(Icons.Default.Settings, null) }, title = "系统设置", subtitle = "运行、日志、诊断与实验功能", onClick = onOpenSettings)
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            "本机状态",
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppMenuCard {
            AppMenuRow(
                icon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (state.selectedModelName == null) Color(0xFF9AA0A6) else Color(0xFF34A853))
                    )
                },
                title = displayModelName(state.selectedModelName) ?: "未加载模型",
                subtitle = "${"%.1f".format(state.stats.decodeTps)} token/s · ${state.stats.backend.uppercase()} 后端",
                onClick = {}
            )
            AppMenuRow(
                icon = { Icon(Icons.Default.NetworkWifi, null) },
                title = "本地 API",
                subtitle = if (state.apiEnabled || state.restEnabled) "已启用" else "未启用",
                onClick = onOpenApi
            )
            AppMenuRow(
                icon = { Icon(Icons.Default.Image, null) },
                title = "图像任务",
                subtitle = state.imageTaskSummary(),
                onClick = onOpenImages
            )
            AppMenuRow(
                icon = { Icon(Icons.Default.Psychology, null) },
                title = state.assistants.firstOrNull { it.selected }?.name ?: "默认助手",
                subtitle = state.assistants.firstOrNull { it.selected }?.let {
                    "记忆${if (it.memoryEnabled) "开" else "关"} · 检索${if (it.webSearchEnabled) "开" else "关"}"
                } ?: "助手能力状态",
                onClick = onOpenAssistants
            )
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            "历史",
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppMenuCard {
            AppMenuRow(
                icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                title = "清空全部",
                subtitle = "删除本机保存的全部聊天记录",
                contentColor = MaterialTheme.colorScheme.error,
                onClick = { confirmClear = true }
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部历史") },
            text = { Text("确定清空所有聊天记录吗？当前模型和模型文件不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        confirmClear = false
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun AppMenuCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun AppMenuRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    contentColor: Color? = null,
    onClick: () -> Unit
) {
    val rowContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            title,
            color = rowContentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 18.sp),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun McaLogoMark(
    size: Dp,
    cornerRadius: Dp = 10.dp,
    modifier: Modifier = Modifier
) {
    val markBackground = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF8F9FA)
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension
        val scale = side / 108f
        fun s(value: Float) = value * scale

        drawRoundRect(
            color = markBackground,
            topLeft = Offset.Zero,
            size = Size(side, side),
            cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
        )

        val bubble = Path().apply {
            moveTo(s(54f), s(20f))
            cubicTo(s(35.22f), s(20f), s(20f), s(33.43f), s(20f), s(50f))
            cubicTo(s(20f), s(59.39f), s(24.87f), s(67.76f), s(32.22f), s(73.19f))
            lineTo(s(28f), s(88f))
            lineTo(s(43.55f), s(83.33f))
            cubicTo(s(46.86f), s(84.45f), s(50.36f), s(85f), s(54f), s(85f))
            cubicTo(s(72.78f), s(85f), s(88f), s(71.57f), s(88f), s(55f))
            cubicTo(s(88f), s(38.43f), s(72.78f), s(20f), s(54f), s(20f))
            close()
        }
        drawPath(bubble, Color(0xFF1A73E8))

        val star = Path().apply {
            moveTo(s(54f), s(34f))
            lineTo(s(57f), s(47f))
            lineTo(s(70f), s(50f))
            lineTo(s(57f), s(53f))
            lineTo(s(54f), s(66f))
            lineTo(s(51f), s(53f))
            lineTo(s(38f), s(50f))
            lineTo(s(51f), s(47f))
            close()
        }
        drawPath(star, Color.White)
        drawCircle(Color.White.copy(alpha = 0.82f), radius = s(3.5f), center = Offset(s(36f), s(38f)))
        drawCircle(Color.White.copy(alpha = 0.82f), radius = s(3.5f), center = Offset(s(72f), s(62f)))
        drawCircle(Color.White.copy(alpha = 0.50f), radius = s(2.5f), center = Offset(s(42f), s(68f)))
        drawCircle(Color.White.copy(alpha = 0.50f), radius = s(2f), center = Offset(s(66f), s(36f)))
    }
}

@Composable
private fun AnimatedMcaAssistantMark(
    isGenerating: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "mca-mark")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mca-ring"
    )
    Box(
        modifier = modifier.size(38.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
            val ringInset = 1.5.dp.toPx()
            val ringSize = Size(this.size.width - ringInset * 2f, this.size.height - ringInset * 2f)
            if (isGenerating) {
                drawArc(
                    color = Color(0xFF1A73E8).copy(alpha = 0.16f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
                drawArc(
                    color = Color(0xFF1A73E8).copy(alpha = 0.88f),
                    startAngle = angle,
                    sweepAngle = 132f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
                drawArc(
                    color = Color(0xFF34A853).copy(alpha = 0.74f),
                    startAngle = angle + 180f,
                    sweepAngle = 86f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
            } else {
                drawArc(
                    color = Color(0xFF1A73E8).copy(alpha = 0.18f),
                    startAngle = 215f,
                    sweepAngle = 94f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
                drawArc(
                    color = Color(0xFF34A853).copy(alpha = 0.16f),
                    startAngle = 34f,
                    sweepAngle = 70f,
                    useCenter = false,
                    style = stroke,
                    topLeft = Offset(ringInset, ringInset),
                    size = ringSize
                )
            }
        }
        McaLogoMark(size = 22.dp, cornerRadius = 8.dp)
    }
}

@Composable
private fun ReasoningPanel(
    content: String,
    durationMs: Long,
    awaitingFinalAnswer: Boolean,
    modifier: Modifier = Modifier
) {
    var visibleCharacters by rememberSaveable { mutableStateOf(MESSAGE_RENDER_PAGE_CHARS) }
    val boundedContent = remember(content, visibleCharacters) {
        content.safePrefix(visibleCharacters)
    }
    val cleaned = remember(boundedContent) { cleanReasoningForDisplay(boundedContent) }
    if (cleaned.isBlank()) return

    var expanded by rememberSaveable { mutableStateOf(true) }
    val seconds = durationMs.div(1000).coerceAtLeast(0)
    val title = if (awaitingFinalAnswer) {
        if (seconds > 0) "正在思考（用时 ${seconds} 秒）" else "正在思考"
    } else if (seconds > 0) {
        "已思考（用时 ${seconds} 秒）"
    } else {
        "已思考"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val displayText = if (expanded) cleaned else reasoningPreview(cleaned)
    val wrappedDisplayText = remember(displayText) { wrapForDisplay(displayText) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = muted,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起思考" else "展开思考",
                tint = muted,
                modifier = Modifier.size(18.dp)
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(muted.copy(alpha = 0.16f), RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = wrappedDisplayText,
                    color = muted.copy(alpha = 0.74f),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    ),
                    maxLines = if (expanded) Int.MAX_VALUE else REASONING_COLLAPSED_MAX_LINES,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    softWrap = true
                )
                if (expanded && boundedContent.length < content.length) {
                    TextButton(
                        onClick = {
                            visibleCharacters = (visibleCharacters + MESSAGE_RENDER_PAGE_CHARS)
                                .coerceAtMost(content.length)
                        }
                    ) {
                        Text("显示更多（${boundedContent.length}/${content.length}）")
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingReasoningPanel(
    startedAt: Long,
    phase: GenerationPhase?,
    tokenProgress: TokenProgress?,
    persistProgress: PersistProgress?,
    modifier: Modifier = Modifier
) {
    var elapsedMs by remember(startedAt) { mutableStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            delay(1000)
        }
    }
    val seconds = elapsedMs.div(1000).coerceAtLeast(0)
    val phaseTitle = generationPhaseLabel(phase)
    val progressText = tokenProgress?.let { progress ->
        "\u5df2\u5904\u7406 ${progress.completedTokens}/${progress.totalTokens} tokens"
    }
    val persist = persistProgress?.takeIf { it.isActive }
    val persistTitle = persist?.let {
        when (it.stage) {
            com.muyuchat.core.engine.PersistStage.ENCODING -> "\u7f13\u5b58\u7f16\u7801\u4e2d"
            com.muyuchat.core.engine.PersistStage.WRITING -> "\u7f13\u5b58\u5e8f\u5217\u5316"
            else -> phaseTitle
        }
    }
    val displayTitle = if (persistTitle != null) {
        if (seconds > 0) "$persistTitle (\u5df2\u7528\u65f6 ${seconds} \u79d2)" else persistTitle
    } else if (seconds > 0) {
        "$phaseTitle (\u5df2\u7528\u65f6 ${seconds} \u79d2)"
    } else {
        phaseTitle
    }
    val persistText = persist?.let { p ->
        val writtenMb = p.writtenBytes / 1048576.0
        val totalMb = p.totalBytes / 1048576.0
        "\u5df2\u5199\u5165 ${"%.1f".format(writtenMb)}/${"%.1f".format(totalMb)} MB"
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                color = muted,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(18.dp)
            )
        }
        if (phase != null) {
            if (persist != null && persist.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = {
                        (persist.writtenBytes.toFloat() / persist.totalBytes.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp))
                )
            } else if (tokenProgress != null) {
                LinearProgressIndicator(
                    progress = {
                        tokenProgress.completedTokens.toFloat() / tokenProgress.totalTokens.toFloat()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp))
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp))
                )
            }
            (persistText ?: progressText)?.let { text ->
                Text(
                    text = text,
                    modifier = Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted
                )
            }
        }
    }
}

@Composable
private fun PromptContextUsageLine(usage: PromptContextUsage) {
    val summary = buildString {
        append("\u4fdd\u7559 ").append(usage.retainedMessageCount).append(" \u6761")
        if (usage.trimmedMessageCount > 0) {
            append(" \u00b7 \u88c1\u526a ").append(usage.trimmedMessageCount).append(" \u6761")
        }
        append(" \u00b7 \u89d2\u8272 ").append(usage.roleTokens).append(" tokens")
        append(" \u00b7 \u4e16\u754c\u4e66 ").append(usage.worldBookTokens).append(" tokens")
        append(" \u00b7 \u77e5\u8bc6\u5e93 ").append(usage.knowledgeTokens).append(" tokens")
    }
    val retained = usage.messageRetention.filter { it.retained }
    val trimmed = usage.messageRetention.filterNot { it.retained }
    val historyDetails = buildList {
        if (retained.isNotEmpty()) appendPromptDecision("\u4fdd\u7559", retained)
        if (trimmed.isNotEmpty()) appendPromptDecision("\u88c1\u526a", trimmed)
    }.joinToString(" \u00b7 ")
    val sourceDetails = buildList {
        appendIdDecision("\u4e16\u754c\u4e66\u547d\u4e2d", usage.selectedWorldBookEntryIds)
        appendIdDecision("\u4e16\u754c\u4e66\u8df3\u8fc7", usage.skippedWorldBookEntryIds)
        appendIdDecision("\u77e5\u8bc6\u5e93\u547d\u4e2d", usage.selectedKnowledgeChunkIds)
        appendIdDecision("\u77e5\u8bc6\u5e93\u8df3\u8fc7", usage.skippedKnowledgeChunkIds)
    }.joinToString(" \u00b7 ")
    val details = listOf(
        "$summary \u00b7 \u603b ${usage.totalEstimatedTokens} tokens",
        historyDetails,
        sourceDetails
    ).filter { it.isNotBlank() }.joinToString("\n")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp)
            .semantics {
                stateDescription = "\u5f53\u524d\u4e0a\u4e0b\u6587\u4f7f\u7528\u60c5\u51b5"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = details,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
        )
    }
}

private fun MutableList<String>.appendPromptDecision(
    label: String,
    decisions: List<PromptMessageRetention>
) {
    val visible = decisions.take(4).joinToString(",") { decision ->
        val role = when (decision.role) {
            Role.SYSTEM -> "S"
            Role.USER -> "U"
            Role.ASSISTANT -> "A"
        }
        "#${decision.originalIndex + 1}$role"
    }
    val remainder = decisions.size - minOf(decisions.size, 4)
    add(buildString {
        append(label).append(' ').append(visible)
        if (remainder > 0) append(" +").append(remainder)
    })
}

private fun MutableList<String>.appendIdDecision(label: String, ids: List<String>) {
    if (ids.isEmpty()) return
    val visible = ids.take(3).joinToString(",") { it.take(8) }
    val remainder = ids.size - minOf(ids.size, 3)
    add(buildString {
        append(label).append(' ').append(visible)
        if (remainder > 0) append(" +").append(remainder)
    })
}

private fun generationPhaseLabel(phase: GenerationPhase?): String = when (phase) {
    GenerationPhase.LOAD -> "\u6b63\u5728\u52a0\u8f7d\u8fd0\u884c\u65f6"
    GenerationPhase.TOKENIZE -> "\u6b63\u5728\u5206\u8bcd"
    GenerationPhase.PREFILL -> "\u6b63\u5728\u9884\u5904\u7406\u4e0a\u4e0b\u6587"
    GenerationPhase.DECODE -> "\u6b63\u5728\u751f\u6210\u56de\u7b54"
    GenerationPhase.PERSIST -> "\u6b63\u5728\u5b8c\u6210\u63a8\u7406\u6536\u5c3e"
    null -> "\u6b63\u5728\u601d\u8003"
}

internal fun generationPerformanceSummary(stats: RuntimeStats?): String? {
    val activeStats = stats ?: return null
    return buildList {
        activeStats.prefillTps.takeIf { it.isFinite() && it > 0.0 }?.let { rate ->
            add("预填充 ${formatGenerationRate(rate)} token/s")
        }
        activeStats.effectivePromptTps
            .takeIf {
                it.isFinite() && it > 0.0 &&
                    activeStats.cacheReusedTokens > 0 &&
                    it > activeStats.prefillTps * 1.05
            }
            ?.let { rate ->
                add("缓存有效 ${formatGenerationRate(rate)} token/s")
            }
        activeStats.decodeTps.takeIf { it.isFinite() && it > 0.0 }?.let { rate ->
            add("输出 ${formatGenerationRate(rate)} token/s")
        }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun formatGenerationRate(rate: Double): String =
    "%.1f".format(java.util.Locale.US, rate)

@Composable
private fun GenerationPerformanceLine(stats: RuntimeStats?) {
    val summary = generationPerformanceSummary(stats) ?: return
    Text(
        text = summary,
        modifier = Modifier.padding(top = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    )
}

private fun cacheEvidenceLabel(stats: RuntimeStats): String? {
    val persistentReason = stats.persistentPrefixCacheReason
        ?.takeUnless { it in setOf("not_requested", "not_attempted", "model_unloaded") }
    val inMemoryReason = stats.cacheReuseReason
        ?.takeUnless { it in setOf("not_attempted", "model_unloaded") }
    return when {
        stats.persistentPrefixCacheHit ->
            "\u56fa\u5b9a\u524d\u7f00\u7f13\u5b58\u547d\u4e2d \u00b7 \u590d\u7528 ${stats.persistentPrefixCacheTokens} tokens"
        persistentReason != null -> "\u56fa\u5b9a\u524d\u7f00\u7f13\u5b58\u672a\u547d\u4e2d \u00b7 $persistentReason"
        stats.cacheReuseHit -> "\u4e0a\u4e0b\u6587 KV \u547d\u4e2d \u00b7 \u590d\u7528 ${stats.cacheReusedTokens} tokens"
        stats.cacheReusedTokens > 0 -> "\u4e0a\u4e0b\u6587 KV \u590d\u7528 ${stats.cacheReusedTokens} tokens"
        inMemoryReason != null -> "\u4e0a\u4e0b\u6587 KV \u672a\u547d\u4e2d \u00b7 $inMemoryReason"
        else -> null
    }
}

@Composable
private fun ChatStatusBar(
    state: ChatUiState,
    onOpenHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onLoadModel: (String) -> Unit,
    onOpenModels: () -> Unit,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    onOpenAppMenu: () -> Unit
) {
    val apiActive = state.selectedModelIsCloud || state.apiEnabled || state.restEnabled
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val modelRuntimeLabel = state.selectedModelRuntimeLabel?.takeIf { it.isNotBlank() }
    val cacheEvidence = cacheEvidenceLabel(state.stats)
    val performanceSummary = generationPerformanceSummary(state.stats)
    val modelSubtitle = if (state.selectedModelName == null) {
        modelRuntimeLabel ?: "未加载本地或云端推理引擎"
    } else {
        listOfNotNull(
            modelRuntimeLabel,
            performanceSummary
        ).joinToString(" · ")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 6.dp
        ) {
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Default.Menu, contentDescription = "打开历史", modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { modelMenuExpanded = true },
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                shadowElevation = 5.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McaLogoMark(size = 24.dp, cornerRadius = 8.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayModelName(state.selectedModelName) ?: "选择模型",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 15.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = modelSubtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        cacheEvidence?.let { evidence ->
                            Text(
                                text = evidence,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                            )
                        }
                    }
                    Surface(
                        color = if (apiActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ) {
                        Text(
                            text = "API",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (apiActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(15.dp)
                    )
                }
            }
            ModelSwitcherDropdown(
                expanded = modelMenuExpanded,
                models = state.localModels,
                selectedModelIsCloud = state.selectedModelIsCloud,
                isGenerating = state.isGenerating,
                reasoningMode = state.reasoningMode,
                onDismiss = { modelMenuExpanded = false },
                onLoadModel = { id ->
                    modelMenuExpanded = false
                    onLoadModel(id)
                },
                onReasoningModeChange = onReasoningModeChange,
                onCloudReasoningModeLocked = onCloudReasoningModeLocked,
                onOpenModels = {
                    modelMenuExpanded = false
                    onOpenModels()
                }
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNewConversation, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "新建聊天", modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onOpenBackgroundSettings, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Image, contentDescription = "聊天背景", modifier = Modifier.size(21.dp))
                }
                IconButton(onClick = onOpenAppMenu, modifier = Modifier.size(38.dp)) {
                    McaLogoMark(size = 28.dp, cornerRadius = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun ChatBackground(
    state: ChatBackgroundState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = state.imageUri) {
        value = withContext(Dispatchers.IO) {
            val raw = state.imageUri?.trim().orEmpty()
            if (raw.isBlank()) return@withContext null
            decodeChatBackgroundBitmap(context, raw)
        }
    }
    Box(modifier = modifier) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = when (state.scaleMode) {
                    ChatBackgroundScaleMode.CROP -> ContentScale.Crop
                    ChatBackgroundScaleMode.FIT -> ContentScale.Fit
                    ChatBackgroundScaleMode.CENTER -> ContentScale.None
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (state.blurRadius > 0f) Modifier.blur(state.blurRadius.dp) else Modifier)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = state.scrimAlpha.coerceIn(0f, 0.9f)))
            )
        }
    }
}

private fun decodeChatBackgroundBitmap(context: Context, rawUri: String): Bitmap? = runCatching {
    fun openStream(): InputStream? = when {
        rawUri.startsWith("content://") || rawUri.startsWith("android.resource://") || rawUri.startsWith("file://") ->
            context.contentResolver.openInputStream(Uri.parse(rawUri))
        else -> File(rawUri).inputStream()
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > CHAT_BACKGROUND_MAX_DECODE_EDGE ||
        bounds.outHeight / sampleSize > CHAT_BACKGROUND_MAX_DECODE_EDGE
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()

@Composable
private fun ChatBackgroundSettingsDialog(
    effectiveState: ChatBackgroundState,
    globalState: ChatBackgroundState,
    assistantState: ChatBackgroundState?,
    sessionState: ChatBackgroundState?,
    hasActiveSession: Boolean,
    importing: Boolean,
    onDismiss: () -> Unit,
    onChooseImage: (ChatBackgroundScope) -> Unit,
    onChange: (ChatBackgroundScope, ChatBackgroundState?) -> Unit,
    onReset: (ChatBackgroundScope) -> Unit
) {
    var scope by rememberSaveable(hasActiveSession) {
        mutableStateOf(if (hasActiveSession) ChatBackgroundScope.SESSION else ChatBackgroundScope.ASSISTANT)
    }
    val explicitState = when (scope) {
        ChatBackgroundScope.GLOBAL -> globalState
        ChatBackgroundScope.ASSISTANT -> assistantState
        ChatBackgroundScope.SESSION -> sessionState
    }
    val inheritedState = when (scope) {
        ChatBackgroundScope.GLOBAL -> ChatBackgroundState()
        ChatBackgroundScope.ASSISTANT -> globalState
        ChatBackgroundScope.SESSION -> assistantState ?: globalState
    }
    val state = explicitState ?: inheritedState
    val hasOverride = when (scope) {
        ChatBackgroundScope.GLOBAL -> globalState != ChatBackgroundState()
        ChatBackgroundScope.ASSISTANT -> assistantState != null
        ChatBackgroundScope.SESSION -> sessionState != null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("聊天背景") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("应用范围", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = scope == ChatBackgroundScope.GLOBAL,
                        onClick = { scope = ChatBackgroundScope.GLOBAL },
                        label = { Text("全局") }
                    )
                    FilterChip(
                        selected = scope == ChatBackgroundScope.ASSISTANT,
                        onClick = { scope = ChatBackgroundScope.ASSISTANT },
                        label = { Text("当前角色") }
                    )
                    FilterChip(
                        selected = scope == ChatBackgroundScope.SESSION,
                        onClick = { scope = ChatBackgroundScope.SESSION },
                        enabled = hasActiveSession,
                        label = { Text("当前会话") }
                    )
                }
                Text(
                    text = when {
                        importing -> "正在复制并优化背景图片…"
                        state.imageUri.isNullOrBlank() -> "使用默认主题背景"
                        explicitState == null -> "正在继承上一级背景"
                        else -> "已为此范围设置自定义背景"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    Button(onClick = { onChooseImage(scope) }, enabled = !importing) { Text("选择图片") }
                    TextButton(
                        onClick = { onReset(scope) },
                        enabled = hasOverride && !importing
                    ) { Text(if (scope == ChatBackgroundScope.GLOBAL) "恢复默认" else "改为继承") }
                }
                Text("遮罩 ${((state.scrimAlpha.coerceIn(0f, 1f)) * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.scrimAlpha.coerceIn(0f, 0.9f),
                    onValueChange = { onChange(scope, state.copy(scrimAlpha = it)) },
                    enabled = !importing,
                    valueRange = 0f..0.9f
                )
                Text("模糊 ${state.blurRadius.roundToInt()} dp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.blurRadius.coerceIn(0f, 24f),
                    onValueChange = { onChange(scope, state.copy(blurRadius = it)) },
                    enabled = !importing,
                    valueRange = 0f..24f
                )
                Text("缩放方式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.scaleMode == ChatBackgroundScaleMode.CROP,
                        onClick = { onChange(scope, state.copy(scaleMode = ChatBackgroundScaleMode.CROP)) },
                        label = { Text("裁剪填充") }
                    )
                    FilterChip(
                        selected = state.scaleMode == ChatBackgroundScaleMode.FIT,
                        onClick = { onChange(scope, state.copy(scaleMode = ChatBackgroundScaleMode.FIT)) },
                        label = { Text("完整显示") }
                    )
                    FilterChip(
                        selected = state.scaleMode == ChatBackgroundScaleMode.CENTER,
                        onClick = { onChange(scope, state.copy(scaleMode = ChatBackgroundScaleMode.CENTER)) },
                        label = { Text("居中") }
                    )
                }
                if (effectiveState.imageUri != state.imageUri) {
                    Text(
                        "当前画布由更高优先级的设置覆盖",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
private fun ModelSwitcherDropdown(
    expanded: Boolean,
    models: List<ChatModelChoice>,
    selectedModelIsCloud: Boolean,
    isGenerating: Boolean,
    reasoningMode: ReasoningMode,
    onDismiss: () -> Unit,
    onLoadModel: (String) -> Unit,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit,
    onOpenModels: () -> Unit
) {
    val context = LocalContext.current
    var reasoningExpanded by rememberSaveable { mutableStateOf(false) }
    var sourceExpanded by rememberSaveable { mutableStateOf<InferenceSource?>(null) }
    val localModels = models.filterNot { it.cloud }
    val cloudModels = models.filter { it.cloud }
    val activeReasoningMode = if (selectedModelIsCloud) ReasoningMode.STANDARD else reasoningMode
    LaunchedEffect(selectedModelIsCloud) {
        if (selectedModelIsCloud) reasoningExpanded = false
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(30.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        modifier = Modifier
            .widthIn(min = 238.dp, max = 320.dp)
            .clip(RoundedCornerShape(30.dp))
            .padding(vertical = 10.dp, horizontal = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 0.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "推理来源",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                InferenceSourceMenuRow(
                    source = InferenceSource.LOCAL,
                    selected = !selectedModelIsCloud,
                    expanded = sourceExpanded == InferenceSource.LOCAL,
                    count = localModels.size,
                    onClick = {
                        reasoningExpanded = false
                        sourceExpanded = if (sourceExpanded == InferenceSource.LOCAL) null else InferenceSource.LOCAL
                    }
                )
                InferenceSourceMenuRow(
                    source = InferenceSource.CLOUD,
                    selected = selectedModelIsCloud,
                    expanded = sourceExpanded == InferenceSource.CLOUD,
                    count = cloudModels.size,
                    onClick = {
                        reasoningExpanded = false
                        sourceExpanded = if (sourceExpanded == InferenceSource.CLOUD) null else InferenceSource.CLOUD
                    }
                )
                if (localModels.isEmpty() && cloudModels.isEmpty()) {
                    Text(
                        text = "暂无可用推理模型",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isGenerating) {
                    Text(
                        text = "生成中请先停止，再切换模型。",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                )
                CapsuleMenuRow(
                    leading = {
                        ThinkingModeMark(
                            mode = activeReasoningMode,
                            selected = activeReasoningMode != ReasoningMode.OFF,
                            modifier = Modifier.size(34.dp)
                        )
                    },
                    onClick = {
                        sourceExpanded = null
                        if (selectedModelIsCloud) {
                            reasoningExpanded = false
                            Toast.makeText(context, CLOUD_REASONING_LOCKED_TIP, Toast.LENGTH_SHORT).show()
                            onCloudReasoningModeLocked()
                        } else {
                            reasoningExpanded = !reasoningExpanded
                        }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "思考模式",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 19.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (selectedModelIsCloud) "默认开启" else reasoningMode.shortLabel(),
                            color = if (activeReasoningMode == ReasoningMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.Bold
                        )
                        if (!selectedModelIsCloud) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .rotate(if (reasoningExpanded) -90f else 0f)
                                    .size(18.dp)
                            )
                        }
                    }
                }
                CapsuleMenuRow(
                    leading = {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
                    },
                    onClick = onOpenModels
                ) {
                    Text(
                        text = "更多模型",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            AnimatedVisibility(
                visible = sourceExpanded != null,
                enter = fadeIn(tween(120)) + slideInHorizontally(tween(160), initialOffsetX = { -it / 4 }),
                exit = fadeOut(tween(100))
            ) {
                ModelSourceInlineCapsule(
                    source = sourceExpanded ?: InferenceSource.LOCAL,
                    models = if (sourceExpanded == InferenceSource.CLOUD) cloudModels else localModels,
                    isGenerating = isGenerating,
                    onLoadModel = { id ->
                        onLoadModel(id)
                        sourceExpanded = null
                    },
                    onOpenModels = onOpenModels,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                )
            }
            AnimatedVisibility(
                visible = reasoningExpanded && !selectedModelIsCloud,
                enter = fadeIn(tween(120)) + slideInHorizontally(tween(160), initialOffsetX = { -it / 4 }),
                exit = fadeOut(tween(100))
            ) {
                ReasoningModeInlineCapsule(
                    selected = reasoningMode,
                    onSelect = { mode ->
                        onReasoningModeChange(mode)
                        reasoningExpanded = false
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}

private enum class InferenceSource(
    val title: String,
    val subtitle: String
) {
    LOCAL("本地推理", "设备端模型"),
    CLOUD("云端推理", "API 推理引擎")
}

@Composable
private fun InferenceSourceMenuRow(
    source: InferenceSource,
    selected: Boolean,
    expanded: Boolean,
    count: Int,
    onClick: () -> Unit
) {
    val selectedColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    CapsuleMenuRow(
        leading = {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
            } else if (source == InferenceSource.CLOUD) {
                Icon(Icons.Default.NetworkWifi, contentDescription = null, tint = selectedColor, modifier = Modifier.size(21.dp))
            } else {
                McaLogoMark(size = 22.dp, cornerRadius = 7.dp)
            }
        },
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = source.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .rotate(if (expanded) -90f else 0f)
                    .size(18.dp)
            )
        }
    }
}

@Composable
private fun ModelSourceInlineCapsule(
    source: InferenceSource,
    models: List<ChatModelChoice>,
    isGenerating: Boolean,
    onLoadModel: (String) -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = source.title,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                fontWeight = FontWeight.Medium
            )
            if (models.isEmpty()) {
                Text(
                    text = if (source == InferenceSource.CLOUD) "暂无云端模型" else "暂无本地模型",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6)
                )
                ReasoningModePill(
                    label = "模型管理",
                    selected = false,
                    onClick = onOpenModels
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    models.forEach { model ->
                        ModelChoicePill(
                            model = model,
                            enabled = !isGenerating || model.loaded,
                            onClick = {
                                if (!model.loaded) onLoadModel(model.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelChoicePill(
    model: ChatModelChoice,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (model.loaded) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (model.loaded) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = selectedColor)
            } else {
                Spacer(modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = displayModelName(model.displayName) ?: model.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.loaded) selectedColor else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 16.sp),
                    fontWeight = if (model.loaded) FontWeight.SemiBold else FontWeight.Medium
                )
                Text(
                    text = buildList {
                        if (model.loaded) add("当前")
                        if (model.cloud) {
                            if (model.subtitle.isNotBlank()) add(model.subtitle)
                        } else {
                            if (model.subtitle.isNotBlank()) add(model.subtitle)
                            model.quant?.let(::add)
                            formatModelBytes(model.sizeBytes)?.let(::add)
                        }
                    }.joinToString(" · ").ifBlank { if (model.cloud) "云端模型" else "本地推理" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.loaded) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    fontWeight = if (model.loaded) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ThinkingModeMark(
    mode: ReasoningMode,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    Surface(
        modifier = modifier,
        color = if (selected) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F3F4)
        },
        shape = CircleShape
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                tint = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            if (mode == ReasoningMode.ADVANCED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 5.dp)
                        .size(5.dp)
                        .background(selectedColor, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun ReasoningModeInlineCapsule(
    selected: ReasoningMode,
    onSelect: (ReasoningMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = "思考模式",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                fontWeight = FontWeight.Medium
            )
            ReasoningMode.entries.forEach { mode ->
                ReasoningModePill(
                    label = mode.shortLabel(),
                    selected = selected == mode,
                    onClick = { onSelect(mode) }
                )
            }
        }
    }
}

@Composable
private fun CapsuleMenuRow(
    leading: @Composable () -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(38.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            leading()
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun ReasoningModeCapsuleDrawer(
    visible: Boolean,
    selected: ReasoningMode,
    onSelect: (ReasoningMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(animationSpec = tween(220), initialOffsetX = { it / 2 }) + fadeIn(tween(140)),
        exit = slideOutHorizontally(animationSpec = tween(180), targetOffsetX = { it / 2 }) + fadeOut(tween(120)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.TopEnd
        ) {
            Surface(
                modifier = Modifier
                    .padding(top = 82.dp, end = 18.dp)
                    .width(152.dp)
                    .clickable(enabled = false) {},
                color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "思考模式",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                        fontWeight = FontWeight.Medium
                    )
                    ReasoningMode.entries.forEach { mode ->
                        ReasoningModePill(
                            label = mode.shortLabel(),
                            selected = selected == mode,
                            onClick = { onSelect(mode) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningModePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable(onClick = onClick),
        color = if (selected) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            Color.Transparent
        },
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = selectedColor)
            } else {
                Spacer(modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

private fun ReasoningMode.shortLabel(): String = when (this) {
    ReasoningMode.OFF -> "关闭"
    ReasoningMode.STANDARD -> "标准"
    ReasoningMode.ADVANCED -> "进阶"
}

@Composable
private fun ChatHistoryDrawer(
    state: ChatUiState,
    onClose: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenAppMenu: () -> Unit,
    onOpenImages: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onTogglePinConversation: (String) -> Unit,
    onExportConversation: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatHistoryItem?>(null) }
    var renameTarget by remember { mutableStateOf<ChatHistoryItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var clearAllRequested by remember { mutableStateOf(false) }
    val filteredHistory = remember(state.history, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            state.history
        } else {
            state.history.filter { item ->
                item.title.contains(keyword, ignoreCase = true) ||
                    item.updatedAtText.contains(keyword, ignoreCase = true)
            }
        }
    }

    ModalDrawerSheet(
        modifier = Modifier.fillMaxSize(),
        drawerContainerColor = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .edgeSwipeBack(onClose)
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "MCA",
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 25.sp),
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Search, contentDescription = "搜索", modifier = Modifier.size(22.dp))
                            }
                            IconButton(onClick = onOpenAppMenu, modifier = Modifier.size(34.dp)) {
                                McaLogoMark(size = 25.dp, cornerRadius = 9.dp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
                AppFeatureButton(icon = { Icon(Icons.Default.Image, null) }, text = "图片", onClick = onOpenImages)
                Spacer(modifier = Modifier.height(34.dp))

                Text(
                    "最近",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 92.dp)
                ) {
                    if (state.history.isEmpty()) {
                        item { HistoryEmptyState("暂无历史记录。发送第一条消息后会自动保存在这里。") }
                    } else if (filteredHistory.isEmpty()) {
                        item { HistoryEmptyState("没有找到匹配的对话。") }
                    } else {
                        historySections(filteredHistory, query).forEach { section ->
                            item(key = "section-${section.title}") {
                                HistorySectionHeader(section.title)
                            }
                            items(section.items, key = { it.id }) { item ->
                                HistoryRow(
                                    item = item,
                                    onClick = { onSelectConversation(item.id) },
                                    onRename = {
                                        renameTarget = item
                                        renameText = item.title
                                    },
                                    onTogglePin = { onTogglePinConversation(item.id) },
                                    onDelete = { deleteTarget = item }
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 24.dp)
                    .height(44.dp)
                    .clickable(onClick = onNewConversation),
                color = MaterialTheme.colorScheme.onBackground,
                shape = CircleShape,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "聊天",
                        color = MaterialTheme.colorScheme.background,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除对话") },
            text = { Text("确定删除“${item.title}”吗？此操作不会删除模型文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteConversation(item.id)
                        deleteTarget = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    renameTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("对话标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameConversation(item.id, renameText)
                        renameTarget = null
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (clearAllRequested) {
        AlertDialog(
            onDismissRequest = { clearAllRequested = false },
            title = { Text("清空全部历史") },
            text = { Text("确定清空所有聊天记录吗？当前模型和模型文件不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        clearAllRequested = false
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearAllRequested = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun Modifier.edgeSwipeBack(onBack: () -> Unit): Modifier {
    val density = LocalDensity.current
    val triggerPx = with(density) { 42.dp.toPx() }
    return pointerInput(onBack, triggerPx) {
        var startedAtEdge = false
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                startedAtEdge = offset.x >= 0f
                totalDrag = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                if (startedAtEdge) {
                    totalDrag += dragAmount
                    if (dragAmount < 0f) change.consume()
                }
            },
            onDragEnd = {
                if (startedAtEdge && totalDrag < -triggerPx) onBack()
                startedAtEdge = false
                totalDrag = 0f
            },
            onDragCancel = {
                startedAtEdge = false
                totalDrag = 0f
            }
        )
    }
}

@Composable
private fun AppFeatureButton(icon: @Composable () -> Unit, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(29.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun HistorySectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 10.dp, bottom = 5.dp, start = 8.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, lineHeight = 14.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun HistoryEmptyState(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryRow(
    item: ChatHistoryItem,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        color = if (item.selected) {
            MaterialTheme.colorScheme.surface
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 0.dp,
        shadowElevation = if (item.selected) 5.dp else 0.dp,
        border = null,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.pinned) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "置顶",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    "${item.updatedAtText} · ${item.messageCount} 条消息",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, lineHeight = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.width(216.dp),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 18.dp
                ) {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        HistoryMenuItem(
                            text = "重命名",
                            icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        HistoryMenuItem(
                            text = if (item.pinned) "取消置顶" else "置顶",
                            icon = { Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                menuExpanded = false
                                onTogglePin()
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                        HistoryMenuItem(
                            text = "删除",
                            icon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            contentColor = MaterialTheme.colorScheme.error,
                            iconContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMenuItem(
    text: String,
    icon: @Composable () -> Unit,
    contentColor: Color? = null,
    iconContainerColor: Color? = null,
    onClick: () -> Unit
) {
    val itemContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface
    val itemIconContainerColor = iconContainerColor ?: MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(30.dp),
            color = itemIconContainerColor,
            shape = RoundedCornerShape(10.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text,
            color = itemContentColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

private data class HistorySection(
    val title: String,
    val items: List<ChatHistoryItem>
)

private fun historySections(items: List<ChatHistoryItem>, query: String): List<HistorySection> {
    if (query.trim().isNotEmpty()) {
        return listOf(HistorySection("搜索结果", items))
    }
    val order = listOf("置顶", "今天", "昨天", "7 天内", "更早")
    return items
        .groupBy { historySectionTitle(it) }
        .map { (title, sectionItems) -> HistorySection(title, sectionItems) }
        .sortedBy { section -> order.indexOf(section.title).let { if (it < 0) Int.MAX_VALUE else it } }
}

private fun historySectionTitle(item: ChatHistoryItem): String {
    if (item.pinned) return "置顶"
    val now = Calendar.getInstance()
    val updated = Calendar.getInstance().apply { timeInMillis = item.updatedAtMillis }
    if (sameDay(now, updated)) return "今天"

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(yesterday, updated)) return "昨天"

    val sevenDaysAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
    return if (updated.after(sevenDaysAgo)) "7 天内" else "更早"
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

@Composable
private fun EmptyChatPanel(modelName: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MCA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                displayModelName(modelName) ?: "请先在模型页加载一个模型。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun displayModelName(value: String?): String? =
    value?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.removeModelSuffix()

private fun formatModelBytes(bytes: Long): String? {
    if (bytes <= 0L) return null
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1.0) {
        "%.1f GB".format(gb)
    } else {
        "%.0f MB".format(bytes / 1024.0 / 1024.0)
    }
}

private fun String.removeModelSuffix(): String =
    removeSuffix(".gguf")
        .removeSuffix(".GGUF")
        .removeSuffix(".mnn")
        .removeSuffix(".MNN")
        .removeSuffix("-GGUF")
        .removeSuffix("_GGUF")
        .removeSuffix("-MNN")
        .removeSuffix("_MNN")

private fun extractAttachmentName(input: String): String? =
    ATTACHMENT_NAME_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(input)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

private fun displayInputWithoutAttachment(input: String): String {
    val marker = firstAttachmentMarker(input)
    if (marker < 0) return input
    return input.substring(0, marker).trimEnd()
}

private fun saveCameraPreview(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, "mca_camera").apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
    }
    return Uri.fromFile(file)
}

private fun removeAttachmentFromInput(input: String): String =
    displayInputWithoutAttachment(input).trimEnd()

private fun mergeInputWithExistingAttachment(currentInput: String, visibleText: String): String {
    val marker = firstAttachmentMarker(currentInput)
    if (marker < 0) return visibleText
    val attachment = currentInput.substring(marker).trimStart()
    return buildString {
        append(visibleText)
        if (visibleText.isNotBlank()) append("\n\n")
        append(attachment)
    }
}

private val ATTACHMENT_PREFIXES = listOf("【上传文件：", "【上传图片：")

private fun firstAttachmentMarker(input: String): Int =
    ATTACHMENT_PREFIXES
        .map { input.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: -1

private val ATTACHMENT_NAME_PATTERNS = listOf(
    Regex("""【上传文件：([^】]+)】"""),
    Regex("""【上传图片：([^】]+)】""")
)

private val IMAGE_ATTACHMENT_PATTERN = Regex("""【上传图片：([^】]+)】""")
private val IMAGE_ATTACHMENT_WITH_URI_PATTERN = Regex("""【上传图片：([^】]+)】(?:\s*\n描述：[^\n]+)?\s*\n(\S+)""")

private fun hasImageAttachmentMarker(input: String): Boolean =
    IMAGE_ATTACHMENT_PATTERN.containsMatchIn(input)

private fun extractAttachmentMeta(input: String): String? {
    val uriString = IMAGE_ATTACHMENT_WITH_URI_PATTERN.find(input)
        ?.groupValues
        ?.getOrNull(2)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val sourceLabel = when {
        uriString.startsWith("content://", ignoreCase = true) -> "系统图片"
        uriString.startsWith("file://", ignoreCase = true) -> "本地文件"
        uriString.startsWith("http://", ignoreCase = true) || uriString.startsWith("https://", ignoreCase = true) -> "网络图片"
        uriString.startsWith("data:image", ignoreCase = true) -> "内联图片"
        else -> "图片输入"
    }
    val extension = uriString
        .substringBefore('?')
        .substringAfterLast('.', "")
        .takeIf { it.length in 2..5 }
        ?.uppercase()
    return listOfNotNull(sourceLabel, extension, "发送前自动压缩").joinToString(" · ")
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatInputBar(
    input: String,
    isGenerating: Boolean,
    statusMessage: String?,
    onInputChange: (String) -> Unit,
    onDismissStatusMessage: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenPhoto: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenFileLibrary: () -> Unit,
    reasoningMode: ReasoningMode,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    webSearchEnabled: Boolean,
    webSearchConfigured: Boolean,
    webSearchEnabledForTurn: Boolean,
    webSearchStatusMessage: String?,
    webSearchTurnModeLabel: String,
    webSearchResearchMode: String,
    webSearchResearchModeLabel: String,
    webSearchResearchOverridden: Boolean,
    webSearchProviderLabel: String,
    visionCapabilityLabel: String,
    visionCapabilityDetail: String,
    visionCapabilityReady: Boolean,
    onToggleWebSearchForTurn: () -> Unit,
    onSelectWebSearchResearchMode: (String) -> Unit,
    onOpenWebSearchSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showActionSheet by rememberSaveable { mutableStateOf(false) }
    var researchModeExpanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val inputShellColor = if (darkTheme) MaterialTheme.colorScheme.surface else McaInputShell
    val inputFieldColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputField
    val inputIconSurfaceColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputIconSurface
    val inputTextColor = if (darkTheme) MaterialTheme.colorScheme.onSurface else McaInputText
    val inputPlaceholderColor = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else McaInputPlaceholder
    if (showActionSheet) {
        CompactInputActionMenu(
            onDismiss = {
                researchModeExpanded = false
                showActionSheet = false
            },
            onCamera = {
                showActionSheet = false
                onOpenCamera()
            },
            onPhoto = {
                showActionSheet = false
                onOpenPhoto()
            },
            onFile = {
                showActionSheet = false
                onOpenFile()
            },
            onLibrary = {
                showActionSheet = false
                onOpenFileLibrary()
            },
            onWebSearch = {
                researchModeExpanded = false
                showActionSheet = false
                onToggleWebSearchForTurn()
            },
            onResearchMode = {
                if (webSearchEnabled) {
                    researchModeExpanded = !researchModeExpanded
                } else {
                    researchModeExpanded = false
                    showActionSheet = false
                    onSelectWebSearchResearchMode(webSearchResearchMode)
                }
            },
            onOpenWebSearchSettings = {
                researchModeExpanded = false
                showActionSheet = false
                onOpenWebSearchSettings()
            },
            webSearchEnabled = webSearchEnabled,
            webSearchConfigured = webSearchConfigured,
            webSearchEnabledForTurn = webSearchEnabledForTurn,
            webSearchTurnModeLabel = webSearchTurnModeLabel,
            webSearchResearchMode = webSearchResearchMode,
            webSearchResearchModeLabel = webSearchResearchModeLabel,
            webSearchResearchOverridden = webSearchResearchOverridden,
            webSearchProviderLabel = webSearchProviderLabel,
            modifier = modifier
        )
    }
    if (showActionSheet && researchModeExpanded) {
        ResearchModeFloatingCapsule(
            selected = webSearchResearchMode,
            overridden = webSearchResearchOverridden,
            onDismiss = { researchModeExpanded = false },
            onSelect = { mode ->
                researchModeExpanded = false
                showActionSheet = false
                onSelectWebSearchResearchMode(mode)
            },
            modifier = modifier
        )
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = inputShellColor,
        shape = RoundedCornerShape(36.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier
                .padding(7.dp)
                .navigationBarsPadding()
        ) {
            statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
                ComposerStatusMessage(
                    message = message,
                    onDismiss = onDismissStatusMessage,
                    modifier = Modifier.padding(start = 4.dp, end = 2.dp, bottom = 6.dp)
                )
            }
            if (!webSearchStatusMessage.isNullOrBlank()) {
                WebSearchStatusChip(
                    message = webSearchStatusMessage,
                    active = webSearchEnabledForTurn,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)
                )
            }
            AttachmentPreview(
                input = input,
                visionCapabilityLabel = visionCapabilityLabel,
                visionCapabilityDetail = visionCapabilityDetail,
                visionCapabilityReady = visionCapabilityReady,
                onRemove = { onInputChange(removeAttachmentFromInput(input)) }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isGenerating) MaterialTheme.colorScheme.surfaceVariant else inputIconSurfaceColor)
                        .clickable(enabled = !isGenerating) { showActionSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "更多操作", modifier = Modifier.size(24.dp), tint = if (isGenerating) inputPlaceholderColor else McaPrimaryBlue)
                }
                Spacer(modifier = Modifier.width(10.dp))
                val visibleInput = displayInputWithoutAttachment(input)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    color = inputFieldColor,
                    shape = CircleShape
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (visibleInput.isBlank()) {
                            Text(
                                "问问 MCA",
                                color = inputPlaceholderColor,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp)
                            )
                        }
                        BasicTextField(
                            value = visibleInput,
                            onValueChange = { visibleText ->
                                onInputChange(mergeInputWithExistingAttachment(input, visibleText))
                            },
                            enabled = !isGenerating,
                            maxLines = 4,
                            textStyle = TextStyle(
                                color = inputTextColor,
                                fontSize = 15.sp,
                                lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(McaPrimaryBlue),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                FloatingActionButton(
                    onClick = {
                    if (isGenerating) onStop() else onSend()
                },
                    containerColor = McaPrimaryBlue,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isGenerating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isGenerating) "停止" else "发送",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

internal fun composerStatusIsError(message: String): Boolean =
    listOf("失败", "无法", "未发送", "未加载", "请先", "超过").any(message::contains)

@Composable
private fun ComposerStatusMessage(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isError = composerStatusIsError(message)
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = contentColor
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭提示",
                modifier = Modifier.size(16.dp),
                tint = contentColor
            )
        }
    }
}

@Composable
private fun WebSearchStatusChip(
    message: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (active && !darkTheme) Color(0xFFEAF1FF) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (active) McaPrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private enum class ResearchModeChoice(val key: String, val label: String) {
    OFF("OFF", "普通"),
    AUTO("AUTO", "自动"),
    DEEP("DEEP", "深度");

    companion object {
        fun selectedKey(value: String): String =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) }?.key ?: AUTO.key
    }
}

@Composable
private fun ResearchModeFloatingCapsule(
    selected: String,
    overridden: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 86.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomStart
    ) {
        val capsuleWidth = 128.dp
        val startPadding = minOf(252.dp, maxWidth - capsuleWidth - 12.dp).coerceAtLeast(12.dp)
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(120)) + slideInHorizontally(tween(160), initialOffsetX = { -it / 4 }),
            exit = fadeOut(tween(100)),
            modifier = Modifier
                .padding(start = startPadding)
                .width(capsuleWidth)
        ) {
            val darkTheme = isSystemInDarkTheme()
            Surface(
                modifier = Modifier.clickable(enabled = false) {},
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (overridden) "本轮研究" else "研究模式",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                            color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭研究模式选择",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(onClick = onDismiss)
                        )
                    }
                    val selectedKey = ResearchModeChoice.selectedKey(selected)
                    ResearchModeChoice.entries.forEach { mode ->
                        ReasoningModePill(
                            label = mode.label,
                            selected = selectedKey == mode.key,
                            onClick = { onSelect(mode.key) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactInputActionMenu(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    onLibrary: () -> Unit,
    onWebSearch: () -> Unit,
    onResearchMode: () -> Unit,
    onOpenWebSearchSettings: () -> Unit,
    webSearchEnabled: Boolean,
    webSearchConfigured: Boolean,
    webSearchEnabledForTurn: Boolean,
    webSearchTurnModeLabel: String,
    webSearchResearchMode: String,
    webSearchResearchModeLabel: String,
    webSearchResearchOverridden: Boolean,
    webSearchProviderLabel: String,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomStart
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onDismiss)
        )
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(120)) + slideInHorizontally(
                animationSpec = tween(180),
                initialOffsetX = { -it / 5 }
            ),
            exit = fadeOut(tween(100)),
            modifier = Modifier
                .padding(bottom = 86.dp)
                .width(238.dp)
        ) {
            Surface(
                modifier = Modifier.clickable(enabled = false) {},
                color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
                shape = RoundedCornerShape(30.dp),
                shadowElevation = 18.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "相机",
                        onClick = onCamera
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "照片",
                        onClick = onPhoto
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "文件",
                        onClick = onFile
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "从文件库添加",
                        onClick = onLibrary
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = when {
                            !webSearchEnabled -> "联网检索：去启用"
                            !webSearchConfigured && webSearchProviderLabel.contains("协议自检源") -> "联网检索：协议自检"
                            !webSearchConfigured -> "联网检索：网页直读"
                            webSearchTurnModeLabel.isNotBlank() -> "联网检索：$webSearchTurnModeLabel"
                            webSearchProviderLabel.contains("智能") -> "联网检索：智能判断"
                            webSearchProviderLabel.contains("始终") -> "联网检索：始终开启"
                            webSearchEnabledForTurn -> "联网检索：本轮开启"
                            else -> "联网检索：手动开启"
                        },
                        subtitle = when {
                            !webSearchEnabled -> "设置中开启"
                            !webSearchConfigured && webSearchProviderLabel.contains("协议自检源") -> "可直读链接 · 关键词搜索未接入"
                            !webSearchConfigured -> "可读取链接 · 搜索未配置"
                            else -> webSearchProviderLabel.takeIf { it.isNotBlank() }
                        },
                        onClick = onWebSearch
                    )
                    CompactInputActionRow(
                        icon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = "研究模式：${webSearchResearchModeLabel.ifBlank { "自动" }}",
                        subtitle = when {
                            !webSearchEnabled -> "先启用联网检索"
                            webSearchResearchOverridden -> "仅影响下一轮发送"
                            else -> "跟随联网检索默认设置"
                        },
                        onClick = onResearchMode
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactInputActionRow(
    icon: @Composable () -> Unit,
    label: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F3F4),
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            color = if (darkTheme) MaterialTheme.colorScheme.onSurface else Color(0xFF202124),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private enum class FileLibraryFilter(val label: String) {
    ALL("全部"),
    TEXT("文本"),
    CODE("代码"),
    DATA("数据")
}

@Composable
private fun FileLibraryPage(
    files: List<FileAssetUiItem>,
    onInsert: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FileLibraryFilter.ALL) }
    val darkTheme = isSystemInDarkTheme()
    val libraryInputFieldColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputField
    val filtered = remember(files, query, filter) {
        files.filter { file ->
            val queryMatched = query.isBlank() ||
                file.name.contains(query, ignoreCase = true) ||
                file.preview.contains(query, ignoreCase = true)
            queryMatched && file.matchesFilter(filter)
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("文件库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${files.size} 个本机文件索引",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = "关闭文件库")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索文件名或内容预览") },
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = libraryInputFieldColor,
                    unfocusedContainerColor = libraryInputFieldColor,
                    focusedBorderColor = McaPrimaryBlue.copy(alpha = 0.42f),
                    unfocusedBorderColor = Color.Transparent
                )
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                FileLibraryFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label) }
                    )
                }
            }
            if (filtered.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            if (files.isEmpty()) "文件库为空" else "没有匹配文件",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (files.isEmpty()) "从输入框上传文本、Markdown、JSON、代码文件后，会自动出现在这里。"
                            else "换个关键词或切换筛选类型试试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { file ->
                        FileLibraryRow(
                            file = file,
                            onInsert = { onInsert(file.id) },
                            onDelete = { onDelete(file.id) }
                        )
                    }
                }
            }
        }
    }
@Composable
private fun FileLibraryRow(
    file: FileAssetUiItem,
    onInsert: () -> Unit,
    onDelete: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputIconSurface,
                    shape = CircleShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = McaPrimaryBlue
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${file.sizeText} · ${file.createdAtText}${if (file.truncated) " · 已截取" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                file.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDelete) {
                    Text("删除")
                }
                Button(onClick = onInsert) {
                    Text("插入当前聊天")
                }
            }
        }
    }
}

private fun FileAssetUiItem.matchesFilter(filter: FileLibraryFilter): Boolean {
    if (filter == FileLibraryFilter.ALL) return true
    val lowerName = name.lowercase()
    val lowerMime = mimeType.lowercase()
    return when (filter) {
        FileLibraryFilter.ALL -> true
        FileLibraryFilter.TEXT -> lowerMime.startsWith("text/") &&
            !lowerName.endsWith(".kt") &&
            !lowerName.endsWith(".java") &&
            !lowerName.endsWith(".py") &&
            !lowerName.endsWith(".js") &&
            !lowerName.endsWith(".ts")
        FileLibraryFilter.CODE -> lowerMime.contains("code") ||
            lowerName.endsWith(".kt") ||
            lowerName.endsWith(".java") ||
            lowerName.endsWith(".py") ||
            lowerName.endsWith(".js") ||
            lowerName.endsWith(".ts")
        FileLibraryFilter.DATA -> lowerMime.contains("json") ||
            lowerMime.contains("xml") ||
            lowerName.endsWith(".json") ||
            lowerName.endsWith(".jsonl") ||
            lowerName.endsWith(".xml") ||
            lowerName.endsWith(".csv")
    }
}

private fun ChatUiState.imageTaskSummary(): String {
    val running = imageJobs.firstOrNull { job ->
        !job.failed && job.imageAssetId == null && (job.statusLabel == "排队" || job.statusLabel == "生成中")
    }
    if (running != null) {
        return "${running.statusLabel} · ${running.prompt.take(18)}"
    }
    val failedJob = imageJobs.firstOrNull { it.failed }
    if (failedJob != null) {
        return "失败，可进入图片页重试"
    }
    val completed = imageJobs.firstOrNull { it.imageAssetId != null || it.statusLabel == "完成" }
    return if (completed != null) "最近完成 · 可查看图片库" else "空闲 · 可进入图片页"
}

@Composable
private fun AttachmentPreview(
    input: String,
    visionCapabilityLabel: String,
    visionCapabilityDetail: String,
    visionCapabilityReady: Boolean,
    onRemove: () -> Unit
) {
    val name = remember(input) { extractAttachmentName(input) } ?: return
    val isImageAttachment = remember(input) { hasImageAttachmentMarker(input) }
    val statusColor = if (visionCapabilityReady) McaPrimaryBlue else MaterialTheme.colorScheme.error
    val containerColor = if (isImageAttachment) {
        if (visionCapabilityReady) McaInputIconSurface.copy(alpha = 0.92f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.44f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    }
    val contentColor = if (isImageAttachment) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        border = if (isImageAttachment) BorderStroke(1.dp, statusColor.copy(alpha = 0.22f)) else null
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .heightIn(min = 30.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isImageAttachment) Icons.Default.Image else Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (isImageAttachment) statusColor else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isImageAttachment) "识图" else "文件",
                        color = if (isImageAttachment) statusColor else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                if (isImageAttachment) {
                    Text(
                        text = "$visionCapabilityLabel · $visionCapabilityDetail",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    extractAttachmentMeta(input)?.let { meta ->
                        Text(
                            text = meta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "移除附件",
                    modifier = Modifier.size(16.dp),
                    tint = contentColor.copy(alpha = 0.74f)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    showAssistantActions: Boolean,
    canRegenerate: Boolean,
    isGenerating: Boolean,
    generationPhase: GenerationPhase?,
    generationTokenProgress: TokenProgress?,
    generationPersistProgress: PersistProgress?,
    generationStats: RuntimeStats?,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onDeleteLastTurn: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val isUser = message.role == Role.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            UserMessageBubble(message)
        } else {
            AssistantMessageBlock(
                modifier = Modifier.fillMaxWidth(),
                message = message,
                showActions = showAssistantActions,
                canRegenerate = canRegenerate,
                isGenerating = isGenerating,
                generationPhase = generationPhase,
                generationTokenProgress = generationTokenProgress,
                generationPersistProgress = generationPersistProgress,
                generationStats = generationStats,
                onRegenerate = onRegenerate,
                onDelete = onDelete,
                onDeleteLastTurn = onDeleteLastTurn,
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("MCA", message.content))
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun UserMessageBubble(message: ChatMessage) {
    val context = LocalContext.current
    Surface(
        color = Color(0xFFEEF4FF),
        shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 20.dp,
            bottomEnd = 6.dp
        ),
        modifier = Modifier.widthIn(max = 280.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (message.imageAttachments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    message.imageAttachments.forEach { attachment ->
                        val bitmap = remember(attachment.uriString) {
                            loadImageBitmap(context, attachment.uriString)
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.size(104.dp)
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = attachment.name.ifBlank { "上传图片" },
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = null,
                                        tint = McaPrimaryBlue.copy(alpha = 0.72f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (message.content.isNotBlank()) {
                PagedPlainMessageText(
                    content = message.content,
                    color = Color(0xFF202124),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp)
                )
            }
        }
    }
}

@Composable
private fun AssistantMessageBlock(
    modifier: Modifier = Modifier,
    message: ChatMessage,
    showActions: Boolean,
    canRegenerate: Boolean,
    isGenerating: Boolean,
    generationPhase: GenerationPhase?,
    generationTokenProgress: TokenProgress?,
    generationPersistProgress: PersistProgress?,
    generationStats: RuntimeStats?,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onDeleteLastTurn: () -> Unit,
    onCopy: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(0.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null,
        modifier = modifier
            .fillMaxWidth()
            .padding(end = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (message.reasoningContent.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    AnimatedMcaAssistantMark(
                        isGenerating = isGenerating,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    ReasoningPanel(
                        content = message.reasoningContent,
                        durationMs = message.reasoningDurationMs,
                        awaitingFinalAnswer = isGenerating && message.content.isBlank(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (message.content.isBlank() && message.reasoningContent.isBlank()) {
                if (isGenerating) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        AnimatedMcaAssistantMark(
                            isGenerating = true,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        PendingReasoningPanel(
                            startedAt = message.createdAt,
                            phase = generationPhase,
                            tokenProgress = generationTokenProgress,
                            persistProgress = generationPersistProgress,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(18.dp))
                }
            } else if (message.content.isNotBlank()) {
                SelectionContainer {
                    PagedAssistantRichText(message.content)
                }
            }
            if (isGenerating) {
                GenerationPerformanceLine(generationStats)
            }
            if (message.sourceReferences.isNotEmpty() || message.webSearchTrace?.hasContent == true) {
                WebSearchSourcesRow(
                    sources = message.sourceReferences,
                    trace = message.webSearchTrace
                )
            }
            if (showActions) {
                AssistantActionRow(
                    canRegenerate = canRegenerate,
                    onRegenerate = onRegenerate,
                    onDelete = onDelete,
                    onDeleteLastTurn = onDeleteLastTurn,
                    onCopy = onCopy
                )
            }
        }
    }
}

@Composable
private fun WebSearchSourcesRow(
    sources: List<ChatSourceReference>,
    trace: ChatWebSearchTrace?
) {
    val context = LocalContext.current
    var selectedUrl by remember(sources) { mutableStateOf<String?>(null) }
    val selectedSource = sources.firstOrNull { it.url == selectedUrl }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (trace?.hasContent == true) {
            WebSearchTraceCard(trace = trace)
        }
        if (sources.isEmpty()) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = McaPrimaryBlue
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                sources.webSearchSourceSummaryLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources.take(8)) { source ->
                val trustLabel = source.displayTrustLabel()
                val hostLabel = source.displayHostLabel()
                val selected = source.url == selectedUrl
                Surface(
                    modifier = Modifier
                        .widthIn(min = 188.dp, max = 256.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            selectedUrl = if (selected) null else source.url
                        },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(18.dp),
                    border = if (selected) BorderStroke(1.dp, McaPrimaryBlue.copy(alpha = 0.46f)) else null
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = source.webSearchTrustColor().copy(alpha = 0.14f),
                                shape = CircleShape
                            ) {
                                Text(
                                    trustLabel,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = source.webSearchTrustColor(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                hostLabel,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            source.title.ifBlank { source.url },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (source.snippet.isNotBlank()) {
                            Text(
                                source.snippet,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val providerLine = listOf(source.provider, source.displayTrustReason())
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString(" · ")
                        if (providerLine.isNotBlank()) {
                            Text(
                                providerLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = selectedSource != null,
            enter = fadeIn(tween(120)) + expandVertically(animationSpec = tween(180)),
            exit = fadeOut(tween(90)) + shrinkVertically(animationSpec = tween(140))
        ) {
            selectedSource?.let { source ->
                WebSearchSourceDetailCard(
                    source = source,
                    onOpen = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                        }
                    },
                    onClose = { selectedUrl = null }
                )
            }
        }
    }
}

@Composable
private fun WebSearchTraceCard(trace: ChatWebSearchTrace) {
    var expanded by rememberSaveable(trace.query, trace.message, trace.elapsedMs, trace.running) { mutableStateOf(false) }
    val statusColor = trace.webSearchTraceColor()
    val runningRotation = if (trace.running) {
        val transition = rememberInfiniteTransition(label = "web-search-running")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "web-search-running-angle"
        )
        angle
    } else {
        0f
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.16f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    color = statusColor.copy(alpha = 0.13f),
                    shape = CircleShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(runningRotation),
                            tint = statusColor
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (trace.running) trace.stageLabel.ifBlank { "正在检索" } else "检索过程",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        trace.summaryLine(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起检索过程" else "展开检索过程",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (trace.running) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = statusColor,
                    trackColor = statusColor.copy(alpha = 0.10f)
                )
            }
            if (trace.message.isNotBlank()) {
                Text(
                    trace.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(120)) + expandVertically(animationSpec = tween(180)),
                exit = fadeOut(tween(90)) + shrinkVertically(animationSpec = tween(140))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WebSearchTraceSection(
                        title = "检索目标",
                        values = (trace.searchedQueries + trace.directUrls)
                            .distinct()
                            .take(6)
                    )
                    WebSearchTraceSection(
                        title = "触发依据",
                        values = trace.triggerReasons.take(4)
                    )
                    WebSearchTraceSection(
                        title = "证据分组",
                        values = trace.evidenceGroups.take(5)
                    )
                    WebSearchTraceSection(
                        title = "不确定性",
                        values = (trace.conflictWarnings + trace.warnings)
                            .distinct()
                            .take(5),
                        error = true
                    )
                    WebSearchTraceSection(
                        title = "闭环检查",
                        values = trace.closedLoopChecks.take(6)
                    )
                }
            }
        }
    }
}

@Composable
private fun WebSearchTraceSection(
    title: String,
    values: List<String>,
    error: Boolean = false
) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        values.forEach { value ->
            Text(
                "• $value",
                style = MaterialTheme.typography.labelSmall.copy(lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChatWebSearchTrace.webSearchTraceColor(): Color =
    when {
        running -> McaPrimaryBlue
        success && qualityScore >= 72 -> McaPrimaryBlue
        success -> Color(0xFF0F9D58)
        warnings.isNotEmpty() || conflictWarnings.isNotEmpty() -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun ChatWebSearchTrace.summaryLine(): String {
    val targetCount = (searchedQueries.size + directUrls.size).coerceAtLeast(sourceCount)
    return buildList {
        if (providerLabel.isNotBlank()) add(providerLabel)
        if (targetCount > 0) add(if (running) "计划 ${targetCount} 个目标" else "${targetCount} 个目标")
        if (sourceCount > 0) add("${sourceCount} 个来源")
        if (elapsedMs > 0) add(formatWebSearchElapsed(elapsedMs))
        if (qualityLabel.isNotBlank()) add("质量 $qualityLabel ${qualityScore}/100")
        if (researchConfidenceLabel.isNotBlank()) add("置信度 $researchConfidenceLabel ${researchConfidenceScore}/100")
    }.ifEmpty {
        listOf(message.ifBlank { query.ifBlank { "已记录本轮联网检索" } })
    }.joinToString(" · ")
}

private fun formatWebSearchElapsed(elapsedMs: Long): String =
    if (elapsedMs >= 1000L) {
        "${"%.1f".format(elapsedMs / 1000.0)}s"
    } else {
        "${elapsedMs}ms"
    }

@Composable
private fun WebSearchSourceDetailCard(
    source: ChatSourceReference,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = source.webSearchTrustColor().copy(alpha = 0.14f),
                    shape = CircleShape
                ) {
                    Text(
                        source.displayTrustLabel(),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = source.webSearchTrustColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    source.displayHostLabel(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "收起来源详情",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                source.title.ifBlank { source.url },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            val reason = source.displayTrustReason()
            if (reason.isNotBlank() || source.provider.isNotBlank()) {
                Text(
                    listOf(source.provider, reason)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (source.snippet.isNotBlank()) {
                Text(
                    source.snippet,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                source.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA 来源链接", source.url))
                            )
                            Toast.makeText(context, "已复制来源链接", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("复制链接")
                }
                Button(onClick = onOpen) {
                    Text("打开网页")
                }
            }
        }
    }
}

private fun List<ChatSourceReference>.webSearchSourceSummaryLabel(): String {
    if (isEmpty()) return "联网来源"
    val summary = map { it.displayTrustLabel() }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(2)
        .joinToString(" · ") { "${it.key} ${it.value}" }
    return buildString {
        append("联网来源")
        append(" · ")
        append(size)
        append(" 个")
        if (summary.isNotBlank()) {
            append(" · ")
            append(summary)
        }
    }
}

private fun ChatSourceReference.displayHostLabel(): String =
    hostLabel.ifBlank {
        runCatching { Uri.parse(url).host?.removePrefix("www.").orEmpty() }
            .getOrDefault("")
            .ifBlank { url.removePrefix("https://").removePrefix("http://").substringBefore("/") }
    }

private fun ChatSourceReference.displayTrustLabel(): String =
    trustLabel.ifBlank {
        val host = displayHostLabel().lowercase()
        when {
            provider == "安全拦截" -> "安全拦截"
            host == "github.com" || host.endsWith(".github.com") -> "代码仓库"
            host == "huggingface.co" || host == "modelscope.cn" -> "模型社区"
            host == "arxiv.org" || host.endsWith(".arxiv.org") -> "学术论文"
            host.contains("docs") || url.contains("/docs", ignoreCase = true) || url.contains("/developer", ignoreCase = true) -> "开发者文档"
            host.contains("reddit") || host.contains("stackoverflow") || host.contains("zhihu") -> "社区讨论"
            else -> "普通网页"
        }
    }

private fun ChatSourceReference.displayTrustReason(): String =
    trustReason.ifBlank {
        when (displayTrustLabel()) {
            "安全拦截" -> "受限地址未读取"
            "代码仓库" -> "开发者仓库"
            "模型社区" -> "模型托管与下载站点"
            "学术论文" -> "论文或预印本来源"
            "开发者文档" -> "文档、指南或开发者资料"
            "社区讨论" -> "论坛、问答或社交讨论"
            else -> ""
        }
    }

@Composable
private fun ChatSourceReference.webSearchTrustColor(): Color =
    when (displayTrustLabel()) {
        "官方/一手", "开发者文档", "模型社区", "代码仓库" -> McaPrimaryBlue
        "学术论文" -> Color(0xFF5E6AD2)
        "社区讨论", "媒体报道" -> Color(0xFF0F9D58)
        "安全拦截" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun AssistantActionRow(
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onDeleteLastTurn: () -> Unit,
    onCopy: () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onRegenerate,
            enabled = canRegenerate,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Replay,
                contentDescription = "重新生成",
                modifier = Modifier.size(17.dp),
                tint = tint.copy(alpha = if (canRegenerate) 0.78f else 0.32f)
            )
        }
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "复制消息",
                modifier = Modifier.size(17.dp),
                tint = tint.copy(alpha = 0.78f)
            )
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "更多消息操作",
                    modifier = Modifier.size(18.dp),
                    tint = tint.copy(alpha = 0.78f)
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("复制回答") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onCopy()
                    }
                )
                DropdownMenuItem(
                    text = { Text("重新生成") },
                    leadingIcon = { Icon(Icons.Default.Replay, contentDescription = null) },
                    enabled = canRegenerate,
                    onClick = {
                        menuOpen = false
                        onRegenerate()
                    }
                )
                DropdownMenuItem(
                    text = { Text("\u5220\u9664\u672c\u8f6e\u5bf9\u8bdd", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDeleteLastTurn()
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除本条", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun PagedPlainMessageText(
    content: String,
    color: Color,
    style: TextStyle
) {
    var visibleCharacters by rememberSaveable { mutableStateOf(MESSAGE_RENDER_PAGE_CHARS) }
    val visibleContent = remember(content, visibleCharacters) {
        content.safePrefix(visibleCharacters)
    }
    val displayContent = remember(visibleContent) { wrapForDisplay(visibleContent) }
    Column {
        Text(
            text = displayContent,
            color = color,
            style = style,
            softWrap = true
        )
        if (visibleContent.length < content.length) {
            TextButton(
                onClick = {
                    visibleCharacters = (visibleCharacters + MESSAGE_RENDER_PAGE_CHARS)
                        .coerceAtMost(content.length)
                }
            ) {
                Text("显示更多（${visibleContent.length}/${content.length}）")
            }
        }
    }
}

@Composable
private fun PagedAssistantRichText(content: String) {
    var visibleCharacters by rememberSaveable { mutableStateOf(MESSAGE_RENDER_PAGE_CHARS) }
    val visibleContent = remember(content, visibleCharacters) {
        content.safePrefix(visibleCharacters)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistantRichText(visibleContent)
        if (visibleContent.length < content.length) {
            TextButton(
                onClick = {
                    visibleCharacters = (visibleCharacters + MESSAGE_RENDER_PAGE_CHARS)
                        .coerceAtMost(content.length)
                }
            ) {
                Text("显示更多（${visibleContent.length}/${content.length}）")
            }
        }
    }
}

@Composable
private fun AssistantRichText(content: String) {
    val displayContent = remember(content) { cleanAssistantContentForDisplay(content) }
    val blocks = remember(displayContent) { parseMarkdownBlocks(displayContent) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> CodeBlock(block.language, block.code)
                is MarkdownBlock.Heading -> HeadingBlock(block.text, block.level)
                is MarkdownBlock.Paragraph -> ParagraphBlock(block.text)
                is MarkdownBlock.BulletList -> ListBlock(block.items, ordered = false)
                is MarkdownBlock.NumberedList -> ListBlock(block.items, ordered = true)
                is MarkdownBlock.Quote -> QuoteBlock(block.text)
                is MarkdownBlock.Table -> TableBlock(block.rows)
                MarkdownBlock.Divider -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HeadingBlock(text: String, level: Int) {
    Text(
        text = inlineMarkdownText(text),
        color = MaterialTheme.colorScheme.onSurface,
        style = if (level <= 2) {
            MaterialTheme.typography.titleMedium.copy(lineHeight = 23.sp)
        } else {
            MaterialTheme.typography.titleSmall.copy(lineHeight = 21.sp)
        },
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        softWrap = true
    )
}

@Composable
private fun ParagraphBlock(text: String) {
    Text(
        text = inlineMarkdownText(text),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 15.sp,
            lineHeight = 23.sp
        ),
        modifier = Modifier.fillMaxWidth(),
        softWrap = true
    )
}

@Composable
private fun ListBlock(items: List<String>, ordered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    text = if (ordered) "${index + 1}." else "•",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = if (ordered) 22.dp else 12.dp)
                )
                Text(
                    text = inlineMarkdownText(item),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    modifier = Modifier.weight(1f),
                    softWrap = true
                )
            }
        }
    }
}

@Composable
private fun QuoteBlock(text: String) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
        )
        Text(
            text = inlineMarkdownText(text),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
            modifier = Modifier.weight(1f),
            softWrap = true
        )
    }
}

@Composable
private fun CodeBlock(language: String?, code: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    Surface(
        color = Color(0xFF1F2937),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    language?.ifBlank { null } ?: "代码",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA code", code.trimEnd()))
                            )
                            copied = true
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "已复制代码" else "复制代码",
                        modifier = Modifier.size(14.dp),
                        tint = Color.White.copy(alpha = 0.78f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (copied) "已复制" else "复制",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Text(
                text = code.trimEnd(),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.74f)),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.background(
                        if (rowIndex == 0) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)
                        } else {
                            Color.Transparent
                        }
                    )
                ) {
                    row.forEach { cell ->
                        Text(
                            text = inlineMarkdownText(cell),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .widthIn(min = 92.dp, max = 190.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            softWrap = true
                        )
                    }
                }
                if (rowIndex != rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f))
                }
            }
        }
    }
}

@Composable
private fun inlineMarkdownText(value: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(value, codeColor, codeBackground, linkColor) {
        buildInlineMarkdown(value, codeColor, codeBackground, linkColor)
    }
}

private sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val text: String, val level: Int) : MarkdownBlock()
    data class BulletList(val items: List<String>) : MarkdownBlock()
    data class NumberedList(val items: List<String>) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class Code(val language: String?, val code: String) : MarkdownBlock()
    data class Table(val rows: List<List<String>>) : MarkdownBlock()
    data object Divider : MarkdownBlock()
}

private fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val listItems = mutableListOf<String>()
    var listOrdered = false
    val codeBuffer = StringBuilder()
    val plainCodeLines = mutableListOf<String>()
    val tableLines = mutableListOf<String>()
    var inCode = false
    var codeLanguage: String? = null

    fun flushParagraph() {
        val value = paragraph.joinToString(" ").trim()
        if (value.isNotBlank()) {
            splitReadableParagraphs(value).forEach { blocks += MarkdownBlock.Paragraph(it) }
        }
        paragraph.clear()
    }

    fun flushList() {
        if (listItems.isNotEmpty()) {
            blocks += if (listOrdered) {
                MarkdownBlock.NumberedList(listItems.toList())
            } else {
                MarkdownBlock.BulletList(listItems.toList())
            }
        }
        listItems.clear()
    }

    fun flushPlainCode() {
        if (plainCodeLines.isEmpty()) return
        val nonBlank = plainCodeLines.filter { it.isNotBlank() }
        val shouldRenderAsCode = nonBlank.size >= 2 || nonBlank.any { it.isStrongCodeLine() }
        if (shouldRenderAsCode) {
            blocks += MarkdownBlock.Code(
                language = detectCodeLanguage(nonBlank),
                code = plainCodeLines.joinToString("\n")
            )
        } else {
            paragraph += nonBlank.joinToString(" ").trim()
        }
        plainCodeLines.clear()
    }

    fun flushTable() {
        if (tableLines.isEmpty()) return
        val rows = tableLines
            .filterNot { it.isTableSeparatorLine() }
            .map { it.toTableCells() }
            .filter { it.isNotEmpty() }
        if (rows.isNotEmpty()) {
            blocks += MarkdownBlock.Table(rows)
        }
        tableLines.clear()
    }

    fun flushCode() {
        blocks += MarkdownBlock.Code(codeLanguage, repairCodeText(codeBuffer.toString(), codeLanguage))
        codeBuffer.clear()
        codeLanguage = null
    }

    normalizeChatText(content).lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                flushCode()
                inCode = false
            } else {
                flushParagraph()
                flushList()
                inCode = true
                codeLanguage = line.trim().removePrefix("```").trim().ifBlank { null }
            }
        } else if (inCode) {
            codeBuffer.appendLine(line)
        } else if (line.isTableLine()) {
            flushPlainCode()
            flushParagraph()
            flushList()
            tableLines += line
        } else if (line.isBlank()) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
        } else if (line.isHorizontalRule()) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            blocks += MarkdownBlock.Divider
        } else if (line.isLikelyCodeLine(plainCodeLines.isNotEmpty())) {
            flushTable()
            flushParagraph()
            flushList()
            plainCodeLines += line
        } else if (line.trimStart().startsWith("#")) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            val trimmed = line.trimStart()
            val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
            blocks += MarkdownBlock.Heading(trimmed.drop(level).trim(), level)
        } else if (line.trimStart().startsWith(">")) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            blocks += MarkdownBlock.Quote(line.trimStart().removePrefix(">").trim())
        } else if (line.isBulletLine()) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            val ordered = line.isNumberedLine()
            if (listItems.isNotEmpty() && listOrdered != ordered) flushList()
            listOrdered = ordered
            listItems += stripListMarker(line)
        } else {
            flushTable()
            flushPlainCode()
            flushList()
            paragraph += line.trim()
        }
    }
    if (inCode) flushCode()
    flushTable()
    flushPlainCode()
    flushParagraph()
    flushList()
    return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(content.trim())) }
}

private fun normalizeChatText(content: String): String =
    content
        .repairInlineCodeFences()
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(Regex("""\s+(?=\d{1,2}[.)]\s+)"""), "\n")
        .replace(Regex("""\s+(?=[-*]\s+)"""), "\n")

private fun String.repairInlineCodeFences(): String {
    val withFenceBreaks = replace(Regex("""([^\n])\s*```"""), "$1\n```")
    return CODE_FENCE_LANGUAGE_WITH_CODE_PATTERN.replace(withFenceBreaks) { match ->
        "```${match.groupValues[1]}\n"
    }
}

private fun repairCodeText(value: String, language: String?): String {
    val normalizedLanguage = language?.lowercase().orEmpty()
    if (value.lineSequence().count() > 1) return value
    if (normalizedLanguage !in setOf("python", "py")) return value
    return value
        .replace(Regex("""(?<=[A-Za-z0-9_)\]])(?=(?:import|from|def|class|if|elif|else|for|while|try|except|finally|return|print)\b)"""), "\n")
        .replace(Regex("""(?<=[A-Za-z0-9_)\]])(?=#)"""), "\n")
        .replace(Regex("""(?<=[0-9)\]])(?=[A-Z_]{2,}\s*=)"""), "\n")
        .trimStart('\n')
}

private fun splitReadableParagraphs(value: String): List<String> {
    if (value.length <= 140) return listOf(value)
    val sentences = Regex("""[^。！？!?；;]+[。！？!?；;]?""")
        .findAll(value)
        .map { it.value.trim() }
        .filter { it.isNotBlank() }
        .toList()
    if (sentences.size <= 2) return listOf(value)
    val result = mutableListOf<String>()
    val current = StringBuilder()
    sentences.forEach { sentence ->
        if (current.isNotEmpty() && current.length + sentence.length > 110) {
            result += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append(' ')
        current.append(sentence)
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
}

private fun String.isBulletLine(): Boolean {
    val trimmed = trimStart()
    return trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        trimmed.matches(Regex("""\d+[.)]\s+.*"""))
}

private fun String.isNumberedLine(): Boolean =
    trimStart().matches(Regex("""\d+[.)]\s+.*"""))

private fun stripListMarker(value: String): String {
    val trimmed = value.trimStart()
    return trimmed
        .removePrefix("- ")
        .removePrefix("* ")
        .replaceFirst(Regex("""^\d+[.)]\s+"""), "")
}

private fun String.isHorizontalRule(): Boolean {
    val trimmed = trim()
    return trimmed.length >= 3 && trimmed.all { it == '-' || it == '_' || it == '*' || it.isWhitespace() }
}

private fun String.isTableLine(): Boolean {
    val trimmed = trim()
    if (!trimmed.contains("|")) return false
    if (trimmed.count { it == '|' } < 2) return false
    return trimmed.startsWith("|") || trimmed.endsWith("|")
}

private fun String.isTableSeparatorLine(): Boolean {
    val compact = trim().trim('|').replace(" ", "")
    return compact.isNotBlank() && compact.all { it == '-' || it == ':' || it == '|' }
}

private fun String.toTableCells(): List<String> =
    trim()
        .trim('|')
        .split('|')
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun String.isLikelyCodeLine(continuingCodeBlock: Boolean): Boolean {
    val trimmed = trim()
    if (trimmed.isBlank()) return false
    if (startsWith("    ") || startsWith("\t")) return true
    if (continuingCodeBlock && (
            trimmed == "}" ||
                trimmed == "};" ||
                trimmed.startsWith("}") ||
                trimmed.startsWith("else") ||
                trimmed.startsWith("elif") ||
                trimmed.startsWith("except") ||
                trimmed.startsWith("finally") ||
                trimmed.startsWith("catch") ||
                trimmed.startsWith("//") ||
                trimmed.startsWith("# ")
            )
    ) {
        return true
    }
    return trimmed.isStrongCodeLine() ||
        CODE_KEYWORD_PATTERN.containsMatchIn(trimmed) ||
        CODE_CALL_PATTERN.containsMatchIn(trimmed)
}

private fun String.isStrongCodeLine(): Boolean {
    val trimmed = trim()
    if (trimmed.length < 2) return false
    if (CODE_DECLARATION_PATTERN.containsMatchIn(trimmed)) return true
    if (CODE_KEYWORD_PATTERN.containsMatchIn(trimmed) && (trimmed.endsWith(":") || trimmed.endsWith("{"))) return true
    if (trimmed.startsWith("#include") || trimmed.startsWith("using namespace")) return true
    if (trimmed.startsWith("<") && trimmed.endsWith(">") && "/" in trimmed) return true
    if (trimmed.endsWith(";") && !trimmed.contains("；")) return true
    if (trimmed == "{" || trimmed == "}" || trimmed == "};") return true
    return CODE_ASSIGNMENT_PATTERN.containsMatchIn(trimmed) && !trimmed.any { it.isCjkLike() }
}

private fun detectCodeLanguage(lines: List<String>): String? {
    val sample = lines.joinToString("\n") { it.trim() }
    val lower = sample.lowercase()
    return when {
        Regex("""(?m)^(def|from|import|elif|except|print)\b""").containsMatchIn(sample) -> "python"
        Regex("""(?m)^(fun|val|var|package|override|private|suspend)\b""").containsMatchIn(sample) -> "kotlin"
        Regex("""(?m)^(const|let|function|export|interface|type)\b""").containsMatchIn(sample) -> "typescript"
        "#include" in sample || "std::" in sample -> "cpp"
        Regex("""(?m)^(public|private|protected)\s+(class|static|void|int|String)\b""").containsMatchIn(sample) -> "java"
        Regex("""(?m)^(select|insert|update|delete|create|alter)\b""", RegexOption.IGNORE_CASE).containsMatchIn(sample) -> "sql"
        "<html" in lower || "</" in sample -> "html"
        else -> null
    }
}

private val CODE_DECLARATION_PATTERN = Regex(
    pattern = """^(def|class|fun|function|const|let|var|val|public|private|protected|override|interface|type|struct|enum|async\s+def)\b.*"""
)

private val CODE_KEYWORD_PATTERN = Regex(
    pattern = """^(if|else|elif|for|while|when|switch|case|try|catch|except|finally|with|return|break|continue|await|import|from|package|using|namespace|select|insert|update|delete|create|alter)\b.*""",
    option = RegexOption.IGNORE_CASE
)

private val CODE_CALL_PATTERN = Regex(
    pattern = """^(print|console\.log|System\.out\.println|printf|Log\.[diew]|println)\s*\(.*"""
)

private val CODE_ASSIGNMENT_PATTERN = Regex(
    pattern = """^[A-Za-z_][A-Za-z0-9_.$\[\]]*\s*(=|\+=|-=|\*=|/=|:=)\s*.+"""
)

private fun cleanReasoningForDisplay(value: String): String {
    return value
        .replace("<think>", "", ignoreCase = true)
        .replace("</think>", "", ignoreCase = true)
        .replace("<|think|>", "", ignoreCase = true)
        .replace("<|channel>thought", "", ignoreCase = true)
        .replace("<|channel|>thought", "", ignoreCase = true)
        .replace("<|channel>analysis", "", ignoreCase = true)
        .replace("<|channel|>analysis", "", ignoreCase = true)
        .replace("<channel|>", "", ignoreCase = true)
        .replace("<channel>", "", ignoreCase = true)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex("""\n[ \t]+"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

private fun cleanAssistantContentForDisplay(value: String): String =
    value
        .replace(Regex("""(?is)<think>.*?</think>"""), "")
        .replace(Regex("""(?i)(?:\bnull\b\s*){3,}"""), "")
        .replace("\u0000", "")
        .trim()

private fun String.removeEnglishReasoningScaffold(): String {
    val parts = split(Regex("""(?=\s*\*\s+)"""))
    if (parts.size <= 1) return this
    val kept = parts.filterNot { part ->
        val compact = part.trim().lowercase()
        ENGLISH_REASONING_SCAFFOLD_PREFIXES.any { compact.startsWith(it) }
    }
    return kept.joinToString("").ifBlank { this }
}

private fun String.removePromptEchoScaffold(): String {
    val parts = split(REASONING_SCAFFOLD_SEGMENT_SPLIT)
    if (parts.size <= 1) {
        return this
    }
    return parts
        .filterNot { it.isEnglishPromptScaffold() }
        .joinToString("")
        .ifBlank { this }
}

private fun String.isEnglishPromptScaffold(): Boolean {
    val compact = trim()
    if (compact.isBlank()) return false
    val lower = compact.lowercase()
    val hasScaffoldKeyword = ENGLISH_REASONING_SCAFFOLD_KEYWORDS.any { it in lower }
    if (!hasScaffoldKeyword) return false
    val latin = compact.count { it in 'a'..'z' || it in 'A'..'Z' }
    val cjk = compact.count { it.isCjkLike() }
    return latin >= 24 && latin > cjk * 2
}

private fun String.preferChineseReasoningWhenEnglishDominates(): String {
    val compact = trim()
    if (compact.isBlank()) return compact
    val latin = compact.count { it in 'a'..'z' || it in 'A'..'Z' }
    val cjk = compact.count { it.isCjkLike() }
    if (cjk <= 0 || latin <= cjk * 2) return compact

    val chinese = compact
        .split(Regex("""(?<=[。！？.!?])\s+|\n+"""))
        .map { it.trim() }
        .filter { segment ->
            val segmentCjk = segment.count { it.isCjkLike() }
            val segmentLatin = segment.count { it in 'a'..'z' || it in 'A'..'Z' }
            segmentCjk >= 6 && segmentCjk >= segmentLatin
        }
        .joinToString("\n")
        .trim()

    return chinese.ifBlank { compact }
}

private fun reasoningPreview(value: String): String {
    val lines = value
        .lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .take(REASONING_COLLAPSED_MAX_LINES)
        .toList()
    return if (lines.isEmpty()) value.take(REASONING_PREVIEW_MAX_CHARS) else {
        val preview = lines.joinToString("\n")
        if (preview.length <= REASONING_PREVIEW_MAX_CHARS) {
            preview
        } else {
            preview.take(REASONING_PREVIEW_MAX_CHARS).trimEnd()
        }
    }
}

private fun cleanInlineMarkdown(value: String): String =
    value
        .replace("**", "")
        .replace("__", "")

private fun buildInlineMarkdown(
    value: String,
    codeColor: Color,
    codeBackground: Color,
    linkColor: Color
): AnnotatedString = buildAnnotatedString {
    var index = 0

    fun appendPlain(text: String) {
        append(wrapForDisplay(text))
    }

    while (index < value.length) {
        when {
            value.startsWith("[", index) -> {
                val match = MARKDOWN_LINK_PATTERN.find(value, index)
                if (match != null && match.range.first == index) {
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(wrapForDisplay(match.groupValues[1]))
                    }
                    index = match.range.last + 1
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            URL_PATTERN.find(value, index)?.range?.first == index -> {
                val match = URL_PATTERN.find(value, index)!!
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(wrapForDisplay(match.value))
                }
                index = match.range.last + 1
            }
            value.startsWith("`", index) -> {
                val end = value.indexOf('`', startIndex = index + 1)
                if (end > index + 1) {
                    withStyle(
                        SpanStyle(
                            color = codeColor,
                            background = codeBackground,
                            fontFamily = FontFamily.Monospace
                        )
                    ) {
                        append(wrapForDisplay(value.substring(index + 1, end)))
                    }
                    index = end + 1
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            value.startsWith("**", index) -> {
                val end = value.indexOf("**", startIndex = index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendPlain(value.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            value.startsWith("__", index) -> {
                val end = value.indexOf("__", startIndex = index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendPlain(value.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            else -> {
                val next = listOf(
                    value.indexOf('`', startIndex = index).takeIf { it >= 0 } ?: value.length,
                    value.indexOf("**", startIndex = index).takeIf { it >= 0 } ?: value.length,
                    value.indexOf("__", startIndex = index).takeIf { it >= 0 } ?: value.length,
                    value.indexOf("[", startIndex = index).takeIf { it >= 0 } ?: value.length,
                    URL_PATTERN.find(value, index)?.range?.first ?: value.length
                ).min()
                appendPlain(value.substring(index, next))
                index = next
            }
        }
    }
}

private val MARKDOWN_LINK_PATTERN = Regex("""\[(.+?)]\((https?://[^)\s]+)\)""")
private val URL_PATTERN = Regex("""https?://[^\s)）]+""")
private val CODE_FENCE_LANGUAGE_WITH_CODE_PATTERN = Regex(
    """```(python|py|kotlin|java|javascript|js|typescript|ts|cpp|c\+\+|c|html|css|sql|json|bash|sh)(?=(?:import|from|def|class|#|//|[A-Za-z_][A-Za-z0-9_]*\s*=))""",
    RegexOption.IGNORE_CASE
)
private const val REASONING_COLLAPSED_MAX_LINES = 4
private const val REASONING_PREVIEW_MAX_CHARS = 520
private val REASONING_LEADING_MARKERS = listOf(
    Regex("""(?is)^\s*here(?:'s| is)\s+a\s+thinking\s+process[^:：\n]*[:：]?\s*"""),
    Regex("""(?is)^\s*this\s+is\s+(?:my\s+)?(?:thinking|reasoning)\s+process[^:：\n]*[:：]?\s*"""),
    Regex("""(?im)^\s*thinking\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*thought\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*reasoning\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*analysis\s*\d*\s*[:：]?\s*"""),
    Regex("""(?m)^\s*(思考过程|推理过程|分析过程)\s*\d*\s*[:：.]?\s*""")
)
private val REASONING_NUMBERED_STEP_PATTERN = Regex(
    pattern = """(?im)^\s*\d+\.\s*\*\*(analyze|determine|check|identify|construct|decide|consider|reason)[^*]{0,96}\*\*\s*[:：]?\s*"""
)
private val REASONING_BOLD_STEP_PATTERN = Regex(
    pattern = """(?im)^\s*\*\*(analyze|determine|check|identify|construct|decide|consider|reason)[^*]{0,96}\*\*\s*[:：]?\s*"""
)
private val ENGLISH_REASONING_SCAFFOLD_PREFIXES = listOf(
    "* user asks",
    "* role:",
    "* system requirements",
    "* default response language",
    "* style:",
    "* specific thinking pattern",
    "* constraint checklist",
    "* output format",
    "* determine the role",
    "* analyze the request",
    "* context:",
    "* question:"
)

private val ENGLISH_REASONING_SCAFFOLD_KEYWORDS = listOf(
    "the user is asking",
    "user asks",
    "identify core",
    "identity constraints",
    "determine persona",
    "determine the role",
    "formulate the answer",
    "final review",
    "self-correction",
    "system prompt",
    "system instruction",
    "system requirements",
    "default response language",
    "output format",
    "constraint checklist",
    "role:",
    "question:",
    "context:",
    "language:",
    "style:",
    "developer:",
    "nature:",
    "must use",
    "use /think",
    "thinking mode is open"
)

private val REASONING_SCAFFOLD_SEGMENT_SPLIT = Regex(
    pattern = """(?=(?:^|\s)(?:\d+\.\s*)?(?:\*\*)?(?:User|Role|Question|Context|Language|Style|Constraint|Constraints|System|Output|Identify|Determine|Formulate|Final Review|Self-Correction|Developer|Nature|Type)\b)""",
    option = RegexOption.IGNORE_CASE
)

private fun wrapForDisplay(value: String): String {
    val out = StringBuilder(value.length + value.length / 24)
    var runLength = 0
    value.forEach { ch ->
        out.append(ch)
        runLength = if (ch.isWhitespace()) 0 else runLength + 1
        if (ch == '/' || ch == '\\' || ch == '_' || ch == '-' || ch == '.' || ch == '=' || ch == '&' || ch == '?') {
            out.append('\u200B')
            runLength = 0
        } else if (runLength >= 18 && !ch.isCjkLike()) {
            out.append('\u200B')
            runLength = 0
        }
    }
    return out.toString()
}

private fun String.safePrefix(maxCharacters: Int): String {
    if (length <= maxCharacters) return this
    var end = maxCharacters.coerceAtLeast(0)
    if (end in 1 until length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) {
        end--
    }
    return substring(0, end)
}

private fun Char.isCjkLike(): Boolean =
    this in '\u4E00'..'\u9FFF' ||
        this in '\u3400'..'\u4DBF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\uAC00'..'\uD7AF'

private const val MESSAGE_RENDER_PAGE_CHARS = 16_384
private const val STREAMING_SCROLL_CHAR_STEP = 512
