package com.muyuchat.feature.chat

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
internal fun ChatImagesWorkspaceOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: ChatUiState,
    actions: ChatScreenActions,
    offlineTranslationStatus: String = "未安装",
    offlineTranslationInstalled: Boolean = false,
    offlineTranslationInstalling: Boolean = false,
    offlineTranslationDownloading: Boolean = false,
    offlineTranslationDownloadProgress: Float? = null,
    onDownloadOfflineTranslation: () -> Unit,
    onCancelOfflineTranslationDownload: () -> Unit,
    onOpenRecommendedModels: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageParameterPreferences = remember(context) {
        context.applicationContext.getSharedPreferences(
            IMAGE_GENERATION_UI_PARAMETER_PREFS,
            Context.MODE_PRIVATE
        )
    }
    val generationImageGrantMutex = remember { Mutex() }
    var showImages = visible
    val onDismissStatusMessage = actions.shell.onDismissStatusMessage
    val onTranslateImagePrompt = actions.image.onTranslateImagePrompt
    val onTranslateImageNegativePrompt = actions.image.onTranslateImageNegativePrompt
    val onImportImageModel = actions.image.onImportImageModel
    val releaseGenerationImageGrantsIfCoordinatorIdle = actions.generation.releaseGenerationImageGrantsIfCoordinatorIdle
    val onSelectImageModel = actions.image.onSelectImageModel
    val onGenerateImagePrompt = actions.image.onGenerateImagePrompt
    val onCancelImageGeneration = actions.image.onCancelImageGeneration
    val onRetryImageGeneration = actions.image.onRetryImageGeneration
    val onChatImageLoraSelectionChange = actions.image.onChatImageLoraSelectionChange
    val onImportImageLora = actions.image.onImportImageLora
    val onDeleteImageLora = actions.image.onDeleteImageLora
    val onImportImageTextualInversion = actions.image.onImportImageTextualInversion
    val onDeleteImageTextualInversion = actions.image.onDeleteImageTextualInversion
    val onSelectImageUpscaler = actions.image.onSelectImageUpscaler
    val onImportImageUpscaler = actions.image.onImportImageUpscaler
    val onDeleteImageUpscaler = actions.image.onDeleteImageUpscaler
    val onUpscaleImageAsset = actions.image.onUpscaleImageAsset
    val onCancelImageUpscale = actions.image.onCancelImageUpscale
    val onUseImageAsset = actions.image.onUseImageAsset
    val onDeleteImageAsset = actions.image.onDeleteImageAsset
    val onDeleteImageAssets = actions.image.onDeleteImageAssets
    val onSetImageAssetFavorite = actions.image.onSetImageAssetFavorite
    val onExportImageLibraryBackup = actions.image.onExportImageLibraryBackup
    val onImportImageLibraryBackup = actions.image.onImportImageLibraryBackup
    val onCancelImageLibraryBackup = actions.image.onCancelImageLibraryBackup
    val onRecreateImageAsset = actions.image.onRecreateImageAsset
    val onMeasureImagePromptTokens = actions.image.onMeasureImagePromptTokens
    val onDownloadOfficialViggleLora = actions.image.onDownloadOfficialViggleLora
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


            SmoothRightToLeftPage(
                visible = showImages,
                onDismiss = onDismiss
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
