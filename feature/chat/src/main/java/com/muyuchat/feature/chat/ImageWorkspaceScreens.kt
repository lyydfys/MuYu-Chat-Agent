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
