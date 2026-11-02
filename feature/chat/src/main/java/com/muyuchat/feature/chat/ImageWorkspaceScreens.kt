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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.muyuchat.core.designsystem.rememberMcaNotify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The existing status surfaces the image page may render a message through. */
internal enum class ImageStatusSurface { GUIDANCE, COMPOSER }

/**
 * Picks the visible surface for an image-page status message.
 *
 * The image page used to render every message through [GuidanceNotice], which returns without
 * drawing anything when [userNotice] does not recognise the text. Several real admission
 * rejections from the image owner (an active job, the shared image lease, a structural readiness
 * failure, a busy local chat turn, a loading Textual Inversion library) are not guidance text, so
 * a genuine fail-closed rejection looked like a dead generate button. Recognised guidance keeps its
 * guidance card; everything else uses the same status row the chat composer already renders.
 */
internal fun imageStatusSurfaceFor(message: String): ImageStatusSurface =
    if (userNotice(message) != null) ImageStatusSurface.GUIDANCE else ImageStatusSurface.COMPOSER

@Composable
private fun ImageStatusFallbackNotice(
    message: String,
    onDismiss: () -> Unit
) {
    // Same row as the chat composer, so the two surfaces cannot drift. That row clamps to two lines;
    // the full text stays reachable through accessibility instead of being dropped silently.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = message }
    ) {
        ComposerStatusMessage(message = message, onDismiss = onDismiss)
    }
}

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
    imageModelsLoading: Boolean,
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
    val notify = rememberMcaNotify()
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
    val filters = rememberImageWorkspaceLibraryFilters(
        images = images,
        libraryQuery = libraryQuery,
        favoritesOnly = favoritesOnly,
        libraryModelId = libraryModelId,
        libraryTaskMode = libraryTaskMode,
        libraryOperation = libraryOperation,
        libraryDateStartUtcMillis = libraryDateStartUtcMillis,
        libraryDateEndUtcMillis = libraryDateEndUtcMillis,
        libraryDimensionsKey = libraryDimensionsKey,
        libraryScheduler = libraryScheduler,
        libraryRuntime = libraryRuntime,
        libraryDevice = libraryDevice,
        newestFirst = newestFirst
    )
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
    // B45/S2: while the displayed job is still working the pill reports that job's model; once the
    // job is terminal the canvas switcher is enabled again and must report the current selection.
    val canvasModelId = activeJob?.takeIf { it.isWorking }?.modelId ?: selectedImageModelId
    val canvasModelName = activeJob?.takeIf { it.isWorking }?.modelName?.takeIf(String::isNotBlank) ?: selectedImageModelName
    val canvasModelIsCloud = activeJob?.takeIf { it.isWorking }?.modelIsCloud ?: selectedImageModelIsCloud

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
                notify("单次最多选择 8 个 Textual Inversion")
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
                imageModelsLoading = imageModelsLoading,
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
            ImageWorkspaceEmptyState(
                onBack = onBack,
                onOpenModels = onOpenModels,
                onImportModel = onImportModel,
                offlineTranslationStatus = offlineTranslationStatus,
                offlineTranslationInstalled = offlineTranslationInstalled,
                offlineTranslationInstalling = offlineTranslationInstalling,
                offlineTranslationDownloading = offlineTranslationDownloading,
                offlineTranslationDownloadProgress = offlineTranslationDownloadProgress,
                onDownloadOfflineTranslation = onDownloadOfflineTranslation,
                onCancelOfflineTranslationDownload = onCancelOfflineTranslationDownload
            )
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
                images = filters.filteredImages,
                totalImageCount = images.size,
                libraryQuery = libraryQuery,
                favoritesOnly = favoritesOnly,
                selectedLibraryModelId = libraryModelId,
                selectedLibraryTaskMode = libraryTaskMode,
                selectedLibraryOperation = libraryOperation,
                selectedLibraryDateRange = filters.dateRange,
                selectedLibraryDateStartUtcMillis = libraryDateStartUtcMillis,
                selectedLibraryDateEndUtcMillis = libraryDateEndUtcMillis,
                selectedLibraryDimensionsKey = libraryDimensionsKey,
                selectedLibraryScheduler = libraryScheduler,
                selectedLibraryRuntime = libraryRuntime,
                selectedLibraryDevice = libraryDevice,
                newestFirst = newestFirst,
                libraryModels = filters.models,
                libraryTaskModes = filters.taskModes,
                libraryOperations = filters.operations,
                libraryDimensions = filters.dimensions,
                librarySchedulers = filters.schedulers,
                libraryRuntimes = filters.runtimes,
                libraryDevices = filters.devices,
                selectionMode = selectionMode,
                selectedImageIds = selectedImageIds,
                batchSaving = batchSaving,
                imageModels = imageModels,
                imageModelsLoading = imageModelsLoading,
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
                    val visibleIds = filters.filteredImages.mapTo(mutableSetOf(), ImageAssetUiItem::id)
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
                                notify(
                                    if (failures == 0) {
                                        "已保存 ${selected.size} 张图片"
                                    } else {
                                        "已保存 ${selected.size - failures} 张，失败 $failures 张"
                                    }
                                )
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
            when (imageStatusSurfaceFor(message)) {
                ImageStatusSurface.GUIDANCE -> GuidanceNotice(
                    message = message,
                    onModels = onOpenModels,
                    onEdit = { showGenerationCanvas = false; revealParameters += 1 },
                    onDismiss = { inputError = null; onDismissStatusMessage() }
                )
                ImageStatusSurface.COMPOSER -> ImageStatusFallbackNotice(
                    message = message,
                    onDismiss = { inputError = null; onDismissStatusMessage() }
                )
            }
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
                            notify("当前模型不支持图片输入")
                        }
                    }
                    else -> onPickImageRole("input")
                }
            },
            // Local function references compare equal even when captured prompt/model values
            // differ. Use a lambda so Compose refreshes the send action with the current input.
            onSubmit = { submitFromImages() },
            placeholder = "描述图像",
            isGenerating = isImageGenerating,
            onStop = onCancelGeneration,
            modifier = Modifier.fillMaxWidth()
        )
        }

        ImageWorkspacePreviewSection(
            previewImageId = previewImageId,
            images = images,
            imageModels = imageModels,
            selectedImageModelId = selectedImageModelId,
            selectedImageModelIsCloud = selectedImageModelIsCloud,
            jobs = jobs,
            supportedTaskModes = supportedTaskModes,
            supportsNegativePrompt = supportsNegativePrompt,
            supportsClipSkip = supportsClipSkip,
            supportsVaeTiling = supportsVaeTiling,
            supportsLora = supportsLora,
            loras = loras,
            selectedLoras = selectedLoras,
            maxBatchCount = maxBatchCount,
            taskMode = taskMode,
            supportsTextualInversion = supportsTextualInversion,
            supportsUltraFix = supportsUltraFix,
            textualInversions = textualInversions,
            selectedTextualInversionIds = selectedTextualInversionIds,
            inputImageUri = inputImageUri,
            inputImageWidth = inputImageWidth,
            inputImageHeight = inputImageHeight,
            upscalers = upscalers,
            selectedUpscalerId = selectedUpscalerId,
            upscalerImporting = upscalerImporting,
            upscalerMessage = upscalerMessage,
            upscaleJob = upscaleJob,
            onDismissPreview = { previewImageId = null },
            onDeleteImage = { id -> pendingDeleteIds = setOf(id) },
            onUseImageAsGenerationInput = onUseImageAsGenerationInput,
            onTaskModeChange = onTaskModeChange,
            onUltraFixEnabledChange = onUltraFixEnabledChange,
            onPromptChange = onPromptChange,
            onNegativePromptChange = onNegativePromptChange,
            onDisableModelNegativePromptChange = onDisableModelNegativePromptChange,
            onStrengthTextChange = onStrengthTextChange,
            onControlStrengthTextChange = onControlStrengthTextChange,
            onWidthTextChange = onWidthTextChange,
            onHeightTextChange = onHeightTextChange,
            onStepsTextChange = onStepsTextChange,
            onCfgScaleTextChange = onCfgScaleTextChange,
            onSeedTextChange = onSeedTextChange,
            onSamplerChange = onSamplerChange,
            onClipSkipTextChange = onClipSkipTextChange,
            onBatchCountChange = onBatchCountChange,
            onVaeTilingEnabledChange = onVaeTilingEnabledChange,
            onToggleLora = onToggleLora,
            onLoraMultiplierChange = onLoraMultiplierChange,
            onToggleTextualInversion = onToggleTextualInversion,
            onUltraFixRefinementStepsTextChange = onUltraFixRefinementStepsTextChange,
            onUltraFixDenoisingStepsTextChange = onUltraFixDenoisingStepsTextChange,
            onUltraFixTileSizeTextChange = onUltraFixTileSizeTextChange,
            onUltraFixOverlapTextChange = onUltraFixOverlapTextChange,
            onImportUpscaler = onImportUpscaler,
            onDeleteUpscaler = onDeleteUpscaler,
            onSelectUpscaler = onSelectUpscaler,
            onUpscaleImage = { id, scale -> onUpscaleImage(id, scale) },
            onCancelUpscale = onCancelUpscale,
            onSetImageAssetFavorite = { id, fav -> onSetImageAssetFavorite(id, fav) },
            onRecreateImageAsset = onRecreateImageAsset,
            onOpenGenerationCanvas = { showGenerationCanvas = true },
            onSetPendingConversationPrompt = { pendingConversationPrompt = it }
        )
    }
    ImageWorkspaceDeleteDialog(
        pendingDeleteIds = pendingDeleteIds,
        onConfirmDelete = { ids ->
            if (ids.size == 1) onDeleteImageAsset(ids.single())
            else onDeleteImageAssets(ids)
            if (previewImageId?.let(ids::contains) == true) {
                previewImageId = null
            }
            selectedImageIds = selectedImageIds - ids.toSet()
            pendingDeleteIds = emptySet()
            if (selectedImageIds.isEmpty()) selectionMode = false
        },
        onDismiss = { pendingDeleteIds = emptySet() }
    )

    ImageWorkspaceBackupDialogHost(
        show = showBackupDialog,
        backupState = backupState,
        images = images,
        onExportBackup = onExportBackup,
        onImportBackup = onImportBackup,
        onCancelBackup = onCancelBackup,
        onDismiss = { showBackupDialog = false }
    )
}
}
