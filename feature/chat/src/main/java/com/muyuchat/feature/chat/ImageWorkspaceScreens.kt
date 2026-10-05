package com.muyuchat.feature.chat

// The image workspace, gallery, prompt editing and image-asset plumbing, moved verbatim out of
// ChatScreen.kt.
//
// ChatScreen.kt had grown to 19,175 lines holding four unrelated page domains. This file is the
// largest of them: the workspace shell, the gallery home and toolbar, the image input and parameter
// panels, the generation canvas and result cards, the asset tile and preview overlay, the prompt bar
// with its token measurement, and the bitmap and MediaStore helpers that domain uses. Nothing here
// is new code: one contiguous block was cut from ChatScreen.kt and pasted unchanged, plus
// isQwenImage21Model, which sat immediately before the assistant domain and could not travel with
// the block without dragging AssistantImportPage along.
//
// Three declarations widened from private to internal because ChatScreen.kt still calls them:
// ImagesWorkspaceScreen (invoked from inside ChatScreen), rememberChatImageBitmap and
// ImageCreatingPlaceholder (invoked from ChatGeneratedImageContent in the chat chrome). Those three
// words are the only visibility changes in this move. The colour tokens this file shares with
// ChatScreen.kt went to ChatColorTokens.kt instead, for the same reason.
//
// The import list is the one from ChatScreen.kt, copied whole rather than trimmed: unused imports
// are warnings in Kotlin, and copying them keeps the move mechanical instead of making it a
// judgement call about which of roughly 260 imports each declaration still needs.

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

@Composable
internal fun ImagesWorkspaceScreen(
    onOpenModels: () -> Unit,
    onImportModel: () -> Unit,
    offlineTranslationStatus: String,
    offlineTranslationInstalled: Boolean,
    offlineTranslationInstalling: Boolean,
    offlineTranslationDownloading: Boolean,
    offlineTranslationDownloadProgress: Float?,
    onDownloadOfflineTranslation: () -> Unit,
    onCancelOfflineTranslationDownload: () -> Unit,
    onTranslateImagePrompt: (suspend (String) -> String)?,
    onTranslateImageNegativePrompt: (suspend (String) -> String)?,
    statusMessage: String?,
    onDismissStatusMessage: () -> Unit,
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
    onAcceptLoraRestore: () -> Unit,
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
    onDownloadOfficialViggleLora: () -> Unit = {},
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
    var revealParameters by rememberSaveable { mutableStateOf(0) }
    var inputError by rememberSaveable { mutableStateOf<String?>(null) }
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
    val activeImageIds = activeJob?.imageAssetIds.orEmpty().ifEmpty {
        activeJob?.imageAssetId?.let(::listOf).orEmpty()
    }
    // A prompt is not an image identity. Falling back to a prompt match can
    // attach images from an older task (or duplicate the same image) when two
    // generations use the same text. Completed jobs must publish explicit
    // asset ids; legacy single-image jobs may still use imageAssetId above.
    val activeImages = activeImageIds.mapNotNull { imageId ->
        images.firstOrNull { it.id == imageId }
    }
    val activeImage = activeImages.firstOrNull()
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
        inputError = null
        onDismissStatusMessage()
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return
        if (taskMode !in supportedTaskModes) {
            inputError = "当前模型不支持此生成方式"
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
            inputError = "请先选择当前模式需要的图片输入"
            return
        }
        if (selectedImageModelId == null) {
            onOpenModels()
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
                images = activeImages,
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
        } else if (imageModels.isEmpty() && images.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ImageGalleryTitleBar(onBack = onBack)
                Text("还没有可用的生图模型", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onOpenModels) { Text("选择推荐模型") }
                OutlinedButton(onClick = onImportModel) { Text("导入已有模型") }
                ImageTranslationDownloadRow(
                    status = offlineTranslationStatus,
                    installed = offlineTranslationInstalled,
                    installing = offlineTranslationInstalling,
                    downloading = offlineTranslationDownloading,
                    progress = offlineTranslationDownloadProgress,
                    onDownload = onDownloadOfflineTranslation,
                    onCancel = onCancelOfflineTranslationDownload
                )
            }
        } else {
            ImageGalleryHome(
                offlineTranslationStatus = offlineTranslationStatus,
                offlineTranslationInstalled = offlineTranslationInstalled,
                offlineTranslationInstalling = offlineTranslationInstalling,
                offlineTranslationDownloading = offlineTranslationDownloading,
                offlineTranslationDownloadProgress = offlineTranslationDownloadProgress,
                onDownloadOfflineTranslation = onDownloadOfflineTranslation,
                onCancelOfflineTranslationDownload = onCancelOfflineTranslationDownload,
                revealParameters = revealParameters,
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
                onAcceptLoraRestore = onAcceptLoraRestore,
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
                onDownloadOfficialViggleLora = onDownloadOfficialViggleLora,
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

        Column(Modifier.align(Alignment.BottomCenter)) {
        (inputError ?: statusMessage)?.let { message ->
            GuidanceNotice(
                message = message,
                onModels = onOpenModels,
                onEdit = { showGenerationCanvas = false; revealParameters += 1 },
                onDismiss = { inputError = null; onDismissStatusMessage() }
            )
        }
        ImagePromptBar(
            prompt = prompt,
            onPromptChange = onPromptChange,
            modelId = selectedImageModelId,
            onMeasureTokens = onMeasureImagePromptTokens,
            onTranslatePrompt = onTranslateImagePrompt,
            negativePrompt = if (supportsNegativePrompt) negativePrompt else "",
            onNegativePromptChange = onNegativePromptChange,
            onTranslateNegativePrompt = onTranslateImageNegativePrompt,
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
            placeholder = "描述图像",
            isGenerating = isImageGenerating,
            onStop = onCancelGeneration,
            modifier = Modifier.fillMaxWidth()
        )
        }

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
private fun ImageTranslationDownloadRow(
    status: String,
    installed: Boolean,
    installing: Boolean,
    downloading: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("image.translation.status"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("图片提示词翻译 · Hy-MT2", style = MaterialTheme.typography.titleSmall)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (installed && !downloading) {
                Text(
                    "已安装",
                    modifier = Modifier.testTag("image.translation.installed"),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            } else {
                OutlinedButton(
                    onClick = if (downloading) onCancel else onDownload,
                    enabled = downloading || (!installing && !installed),
                    modifier = Modifier.testTag(if (downloading) "image.translation.download.cancel" else "image.translation.download")
                ) {
                    Icon(
                        if (downloading) Icons.Default.Stop else Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (downloading) "取消" else "下载")
                }
            }
        }
        if (downloading) {
            val boundedProgress = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            if (boundedProgress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { boundedProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ImageGalleryHome(
    offlineTranslationStatus: String,
    offlineTranslationInstalled: Boolean,
    offlineTranslationInstalling: Boolean,
    offlineTranslationDownloading: Boolean,
    offlineTranslationDownloadProgress: Float?,
    onDownloadOfflineTranslation: () -> Unit,
    onCancelOfflineTranslationDownload: () -> Unit,
    revealParameters: Int,
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
    onAcceptLoraRestore: () -> Unit,
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
    onDownloadOfficialViggleLora: () -> Unit = {},
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
            .testTag("image.gallery")
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
            ImageTranslationDownloadRow(
                status = offlineTranslationStatus,
                installed = offlineTranslationInstalled,
                installing = offlineTranslationInstalling,
                downloading = offlineTranslationDownloading,
                progress = offlineTranslationDownloadProgress,
                onDownload = onDownloadOfflineTranslation,
                onCancel = onCancelOfflineTranslationDownload
            )
        }
        item {
            ImageInputOptionsPanel(
                revealParameters = revealParameters,
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
                onAcceptLoraRestore = onAcceptLoraRestore,
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
                onDownloadOfficialViggleLora = onDownloadOfficialViggleLora,
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
    revealParameters: Int,
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
    onAcceptLoraRestore: () -> Unit,
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
    onDownloadOfficialViggleLora: () -> Unit = {},
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
    LaunchedEffect(revealParameters) {
        if (revealParameters > 0) advancedParametersExpanded = true
    }
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
            loraRestoreWarning?.let { warning ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        warning,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onAcceptLoraRestore) {
                        Text(if (supportsLora) "按当前选择继续" else "继续不带 LoRA")
                    }
                }
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
                    if (executionModel.imageMinWidth == executionModel.imageMaxWidth &&
                        executionModel.imageMinHeight == executionModel.imageMaxHeight && !ultraFixEnabled) {
                        append(" · 模型固定尺寸")
                    }
                    if (selectedLoras.isNotEmpty()) append(" · LoRA ${selectedLoras.size}")
                    if (selectedTextualInversionIds.isNotEmpty()) append(" · 嵌入 ${selectedTextualInversionIds.size}")
                    if (ultraFixEnabled) append(" · UltraFix")
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
                        "更多参数",
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
                val isQwenImage21Model = executionModel.isQwenImage21Model()
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
                if (isQwenImage21Model) {
                    Text(
                        "Qwen-Image-2.1 当前 runtime 的 21 个预设尺寸组合：Standard（7 种比例）512×512、576×448、448×576、640×416、416×640、672×384、384×672；Fast 384×384、448×320、320×448、480×320、320×480、512×288、288×512；Tiny 320×320、384×288、288×384、384×256、256×384、416×256、256×416。任意 32 对齐尺寸不代表可运行。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                                if (isQwenImage21Model) {
                                    "请选择当前 runtime 提供的宽高组合"
                                } else if (fixedWidth) {
                                    "模型固定尺寸"
                                } else {
                                    "$displayedMinWidth-$displayedMaxWidth / $displayedWidthMultiple"
                                }
                            )
                        },
                        singleLine = true,
                        readOnly = fixedWidth,
                        modifier = Modifier.weight(1f).testTag("image.width")
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { value -> if (!fixedHeight) onHeightTextChange(value) },
                        label = { Text("高度") },
                        supportingText = {
                            Text(
                                if (isQwenImage21Model) {
                                    "请选择当前 runtime 提供的宽高组合"
                                } else if (fixedHeight) {
                                    "模型固定尺寸"
                                } else {
                                    "$displayedMinHeight-$displayedMaxHeight / $displayedHeightMultiple"
                                }
                            )
                        },
                        singleLine = true,
                        readOnly = fixedHeight,
                        modifier = Modifier.weight(1f).testTag("image.height")
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
                        modifier = Modifier.weight(1f).testTag("image.steps")
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
                        modifier = Modifier.weight(1f).testTag("image.seed")
                    )
                }
                if (executionModel.isQwenImage21Gguf) {
                    val viggleIncluded = selectedLoras.any { selection ->
                        loras.any { adapter ->
                            adapter.id == selection.id && adapter.isQwenImage21ViggleV021
                        }
                    }
                    val viggleSelected = viggleIncluded && selectedLoras.size == 1 &&
                        selectedLoras.single().multiplierText.toDoubleOrNull() == 1.0
                    Text(
                        when {
                            !viggleIncluded -> "未启用 Viggle LoRA：使用基座原生步数与调度。"
                            !viggleSelected ->
                                "Viggle LoRA 已启用；仅单独选用且倍率为 1 时使用其蒸馏调度，当前使用原生调度。"
                            cfgScaleText.toDoubleOrNull() != 1.0 ->
                                "Viggle LoRA 已启用；蒸馏调度要求 CFG 1，当前使用原生调度。"
                            stepsText.toIntOrNull() == 4 ->
                                "Viggle v0.2.1 四步调度已选；四步可能减少细节与多样性，建议先用六步比较。"
                            stepsText.toIntOrNull()?.let { it in 5..8 } == true ->
                                "Viggle v0.2.1 蒸馏调度已选；六步为官方推荐，可自行调整为四至八步。"
                            else ->
                                "Viggle LoRA 已启用；当前步数使用基座原生调度。四至八步才启用蒸馏调度。"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
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
                            "图像页与聊天生图共用选择；最多 8 个，倍率不能为 0。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = onImportLora, enabled = !loraImporting) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (loraImporting) "导入中…" else "导入")
                    }
                    if (executionModel?.isQwenImage21Gguf == true) {
                        TextButton(
                            onClick = onDownloadOfficialViggleLora,
                            enabled = !loraImporting
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下载 Viggle v0.2.1")
                        }
                    }
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
                                    if (executionModel?.isQwenImage21Gguf == true &&
                                        adapter.isQwenImage21ViggleV021
                                    ) {
                                        Text(
                                            "Viggle v0.2.1 · 推荐六步 / CFG 1",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
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
    images: List<ImageAssetUiItem>,
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
                images = images,
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
    images: List<ImageAssetUiItem> = image?.let(::listOf).orEmpty(),
    onRetry: () -> Unit,
    onCancelGeneration: () -> Unit,
    onUseImageAsset: (String) -> Unit
) {
    val fallbackStartedAtElapsedMs = remember(job?.id) { SystemClock.elapsedRealtime() }
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (imageAssistantCardKind(job, image)) {
            ImageAssistantCardKind.FAILURE -> ImageGenerationFailureCard(
                job = requireNotNull(job),
                onRetry = onRetry
            )
            ImageAssistantCardKind.RESULT -> ImageGenerationResultImage(
                image = requireNotNull(image),
                images = images,
                onUseImageAsset = onUseImageAsset
            )
            ImageAssistantCardKind.TERMINAL -> ImageGenerationTerminalCard(
                job = requireNotNull(job),
                onRetry = onRetry
            )
            ImageAssistantCardKind.CREATING -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ImageCreatingPlaceholder(
                        statusText = job?.statusLabel ?: "正在创建图片",
                        statusMessage = job?.message.orEmpty(),
                        startedAtElapsedMs = job?.startedAtElapsedMs ?: fallbackStartedAtElapsedMs,
                        previewUriString = job?.previewUriString,
                        previewStep = job?.previewStep ?: 0,
                        previewRevision = job?.previewRevision ?: 0L,
                        onCancel = onCancelGeneration
                    )
                    job?.translationPreview?.takeIf(String::isNotBlank)?.let { translated ->
                        Column(
                            modifier = Modifier
                                .widthIn(max = 360.dp)
                                .testTag("image.generation.translation-preview"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "英文草稿（可编辑）",
                                style = MaterialTheme.typography.labelSmall,
                                color = McaPrimaryBlue
                            )
                            SelectionContainer {
                                Text(
                                    translated,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImageCreatingPlaceholder(
    statusText: String,
    statusMessage: String,
    startedAtElapsedMs: Long,
    previewUriString: String?,
    previewStep: Int,
    previewRevision: Long,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val previewBitmap by rememberChatImageBitmap(
        context = context,
        uriString = previewUriString.orEmpty(),
        maxDimensionPx = 1_024,
        revisionKey = previewRevision
    )
    val previewBitmapValue = previewBitmap
    val showingPreview = previewBitmapValue != null
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
    var elapsedSeconds by remember(startedAtElapsedMs) {
        mutableStateOf(((SystemClock.elapsedRealtime() - startedAtElapsedMs) / 1000L).coerceAtLeast(0L))
    }
    LaunchedEffect(startedAtElapsedMs) {
        while (true) {
            elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAtElapsedMs) / 1000L).coerceAtLeast(0L)
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
            if (previewBitmapValue != null) {
                Image(
                    bitmap = previewBitmapValue.asImageBitmap(),
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
                TextButton(onClick = onCancel, modifier = Modifier.testTag("image.cancel")) {
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
private fun ImageGenerationResultImage(
    image: ImageAssetUiItem,
    images: List<ImageAssetUiItem>,
    onUseImageAsset: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val darkTheme = isSystemInDarkTheme()
    // Chat bubbles can contain user-selected photos straight from the gallery.
    // Never decode their full source dimensions synchronously during Compose:
    // large camera images can exhaust the UI process while the message is sent.
    val bitmap by rememberChatImageBitmap(context, image.uriString, maxDimensionPx = 512)
    val ratio = remember(image.width, image.height) {
        if (image.width > 0 && image.height > 0) {
            (image.width.toFloat() / image.height.toFloat()).coerceIn(0.72f, 1.78f)
        } else {
            1.22f
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (images.size > 1) {
            Text("共 ${images.size} 张", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                images.forEach { item ->
                    val thumbnail by rememberChatImageBitmap(
                        context,
                        item.uriString,
                        maxDimensionPx = 192
                    )
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFEDEFF1))
                            .clickable { onUseImageAsset(item.id) }
                    ) {
                        thumbnail?.let {
                            Image(it.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
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
        bitmap?.let { loadedBitmap ->
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } ?: run {
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
                    modifier = Modifier.fillMaxWidth().testTag("image.model.selector"),
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
    val bitmap by rememberChatImageBitmap(context, image.uriString, maxDimensionPx = 384)
    Box(
        modifier = modifier
            .testTag("image.asset.${image.id}")
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
        bitmap?.let { loadedBitmap ->
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } ?: run {
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
    val bitmap by rememberChatImageBitmap(context, image.uriString, maxDimensionPx = 1280)
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
            .testTag("image.preview.${image.id}")
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
        bitmap?.let { loadedBitmap ->
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = image.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .testTag("image.preview.bitmap.${image.id}")
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 12.dp, vertical = 76.dp)
            )
        } ?: run {
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
