package com.muyuchat.feature.chat

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.sync.Mutex

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
    val bindings = ImagesWorkspaceOverlayBindings(
        state = state,
        context = context,
        imageParameterPreferences = imageParameterPreferences,
        generationImageGrantMutex = generationImageGrantMutex,
        showImages = showImages,
        onDismiss = onDismiss,
        onDismissStatusMessage = onDismissStatusMessage,
        onTranslateImagePrompt = onTranslateImagePrompt,
        onTranslateImageNegativePrompt = onTranslateImageNegativePrompt,
        onImportImageModel = onImportImageModel,
        releaseGenerationImageGrantsIfCoordinatorIdle = releaseGenerationImageGrantsIfCoordinatorIdle,
        onSelectImageModel = onSelectImageModel,
        onGenerateImagePrompt = onGenerateImagePrompt,
        onCancelImageGeneration = onCancelImageGeneration,
        onRetryImageGeneration = onRetryImageGeneration,
        onChatImageLoraSelectionChange = onChatImageLoraSelectionChange,
        onDeleteImageLora = onDeleteImageLora,
        onDeleteImageTextualInversion = onDeleteImageTextualInversion,
        onSelectImageUpscaler = onSelectImageUpscaler,
        onDeleteImageUpscaler = onDeleteImageUpscaler,
        onUpscaleImageAsset = onUpscaleImageAsset,
        onCancelImageUpscale = onCancelImageUpscale,
        onUseImageAsset = onUseImageAsset,
        onDeleteImageAsset = onDeleteImageAsset,
        onDeleteImageAssets = onDeleteImageAssets,
        onSetImageAssetFavorite = onSetImageAssetFavorite,
        onExportImageLibraryBackup = onExportImageLibraryBackup,
        onImportImageLibraryBackup = onImportImageLibraryBackup,
        onCancelImageLibraryBackup = onCancelImageLibraryBackup,
        onRecreateImageAsset = onRecreateImageAsset,
        onMeasureImagePromptTokens = onMeasureImagePromptTokens,
        onDownloadOfflineTranslation = onDownloadOfflineTranslation,
        onCancelOfflineTranslationDownload = onCancelOfflineTranslationDownload,
        onOpenRecommendedModels = onOpenRecommendedModels,
        offlineTranslationStatus = offlineTranslationStatus,
        offlineTranslationInstalled = offlineTranslationInstalled,
        offlineTranslationInstalling = offlineTranslationInstalling,
        offlineTranslationDownloading = offlineTranslationDownloading,
        offlineTranslationDownloadProgress = offlineTranslationDownloadProgress,
        imageTextualInversionTriggerById = imageTextualInversionTriggerById,
        imageTaskMode = imageTaskMode,
        selectedImageModelChoice = selectedImageModelChoice,
        selectedImageTaskModes = selectedImageTaskModes,
        supportsImageNegativePrompt = supportsImageNegativePrompt,
        supportsImageClipSkip = supportsImageClipSkip,
        supportsImageVaeTiling = supportsImageVaeTiling,
        supportsImageTextualInversion = supportsImageTextualInversion,
        supportedImageTextualInversionFormats = supportedImageTextualInversionFormats,
        imageTextualInversionsForSelectedModel = imageTextualInversionsForSelectedModel,
        supportsImageUltraFix = supportsImageUltraFix,
        supportsImageLora = supportsImageLora,
        selectedImagePreviewMode = selectedImagePreviewMode,
        supportsImageLivePreview = supportsImageLivePreview,
        selectedImageSupportedSamplers = selectedImageSupportedSamplers,
        selectedImageDefaultSampler = selectedImageDefaultSampler,
        maxImageBatchCount = maxImageBatchCount,
        currentImageModelIds = currentImageModelIds,
        transientGenerationImageUris = transientGenerationImageUris,
        libraryHistoryGenerationImageUris = libraryHistoryGenerationImageUris,
        generationPhotoPicker = generationPhotoPicker,
        generationLoraPicker = generationLoraPicker,
        generationTextualInversionPicker = generationTextualInversionPicker,
        generationUpscalerPicker = generationUpscalerPicker,
        imagePromptVar = ImagesWorkspaceBoundVar({ imagePrompt }, { imagePrompt = it }),
        imageValidationErrorVar = ImagesWorkspaceBoundVar({ imageValidationError }, { imageValidationError = it }),
        imageTaskModeNameVar = ImagesWorkspaceBoundVar({ imageTaskModeName }, { imageTaskModeName = it }),
        imageInputUriVar = ImagesWorkspaceBoundVar({ imageInputUri }, { imageInputUri = it }),
        imageMaskUriVar = ImagesWorkspaceBoundVar({ imageMaskUri }, { imageMaskUri = it }),
        imageControlUriVar = ImagesWorkspaceBoundVar({ imageControlUri }, { imageControlUri = it }),
        imageInputDimensionsUriVar = ImagesWorkspaceBoundVar({ imageInputDimensionsUri }, { imageInputDimensionsUri = it }),
        imageInputSourceWidthVar = ImagesWorkspaceBoundVar({ imageInputSourceWidth }, { imageInputSourceWidth = it }),
        imageInputSourceHeightVar = ImagesWorkspaceBoundVar({ imageInputSourceHeight }, { imageInputSourceHeight = it }),
        imageInputDimensionsProbingVar = ImagesWorkspaceBoundVar({ imageInputDimensionsProbing }, { imageInputDimensionsProbing = it }),
        imageInputDimensionsProbeFailedVar = ImagesWorkspaceBoundVar({ imageInputDimensionsProbeFailed }, { imageInputDimensionsProbeFailed = it }),
        imageStrengthTextVar = ImagesWorkspaceBoundVar({ imageStrengthText }, { imageStrengthText = it }),
        imageControlStrengthTextVar = ImagesWorkspaceBoundVar({ imageControlStrengthText }, { imageControlStrengthText = it }),
        imageNegativePromptVar = ImagesWorkspaceBoundVar({ imageNegativePrompt }, { imageNegativePrompt = it }),
        imageDisableModelNegativePromptVar = ImagesWorkspaceBoundVar({ imageDisableModelNegativePrompt }, { imageDisableModelNegativePrompt = it }),
        imageClipSkipTextVar = ImagesWorkspaceBoundVar({ imageClipSkipText }, { imageClipSkipText = it }),
        imageVaeTilingEnabledVar = ImagesWorkspaceBoundVar({ imageVaeTilingEnabled }, { imageVaeTilingEnabled = it }),
        imageLivePreviewEnabledVar = ImagesWorkspaceBoundVar({ imageLivePreviewEnabled }, { imageLivePreviewEnabled = it }),
        imageLivePreviewIntervalVar = ImagesWorkspaceBoundVar({ imageLivePreviewInterval }, { imageLivePreviewInterval = it }),
        imageLivePreviewIntervalExplicitVar = ImagesWorkspaceBoundVar({ imageLivePreviewIntervalExplicit }, { imageLivePreviewIntervalExplicit = it }),
        imageBatchCountVar = ImagesWorkspaceBoundVar({ imageBatchCount }, { imageBatchCount = it }),
        imageWidthTextVar = ImagesWorkspaceBoundVar({ imageWidthText }, { imageWidthText = it }),
        imageHeightTextVar = ImagesWorkspaceBoundVar({ imageHeightText }, { imageHeightText = it }),
        imageNormalWidthTextVar = ImagesWorkspaceBoundVar({ imageNormalWidthText }, { imageNormalWidthText = it }),
        imageNormalHeightTextVar = ImagesWorkspaceBoundVar({ imageNormalHeightText }, { imageNormalHeightText = it }),
        imageUltraFixTargetWidthTextVar = ImagesWorkspaceBoundVar({ imageUltraFixTargetWidthText }, { imageUltraFixTargetWidthText = it }),
        imageUltraFixTargetHeightTextVar = ImagesWorkspaceBoundVar({ imageUltraFixTargetHeightText }, { imageUltraFixTargetHeightText = it }),
        imageStepsTextVar = ImagesWorkspaceBoundVar({ imageStepsText }, { imageStepsText = it }),
        imageCfgScaleTextVar = ImagesWorkspaceBoundVar({ imageCfgScaleText }, { imageCfgScaleText = it }),
        imageViggleAutoStepsVar = ImagesWorkspaceBoundVar({ imageViggleAutoSteps }, { imageViggleAutoSteps = it }),
        imageViggleAutoCfgVar = ImagesWorkspaceBoundVar({ imageViggleAutoCfg }, { imageViggleAutoCfg = it }),
        imageStepsEditedByUserVar = ImagesWorkspaceBoundVar({ imageStepsEditedByUser }, { imageStepsEditedByUser = it }),
        imageCfgEditedByUserVar = ImagesWorkspaceBoundVar({ imageCfgEditedByUser }, { imageCfgEditedByUser = it }),
        imageViggleSelectedVar = ImagesWorkspaceBoundVar({ imageViggleSelected }, { imageViggleSelected = it }),
        imageSeedTextVar = ImagesWorkspaceBoundVar({ imageSeedText }, { imageSeedText = it }),
        imageSamplerVar = ImagesWorkspaceBoundVar({ imageSampler }, { imageSampler = it }),
        imageLoraDraftJsonVar = ImagesWorkspaceBoundVar({ imageLoraDraftJson }, { imageLoraDraftJson = it }),
        imageTextualInversionIdsJsonVar = ImagesWorkspaceBoundVar({ imageTextualInversionIdsJson }, { imageTextualInversionIdsJson = it }),
        imageUltraFixEnabledVar = ImagesWorkspaceBoundVar({ imageUltraFixEnabled }, { imageUltraFixEnabled = it }),
        imageUltraFixStrengthTextVar = ImagesWorkspaceBoundVar({ imageUltraFixStrengthText }, { imageUltraFixStrengthText = it }),
        imageUltraFixInversionStepsTextVar = ImagesWorkspaceBoundVar({ imageUltraFixInversionStepsText }, { imageUltraFixInversionStepsText = it }),
        imageUltraFixRefinementStepsTextVar = ImagesWorkspaceBoundVar({ imageUltraFixRefinementStepsText }, { imageUltraFixRefinementStepsText = it }),
        imageUltraFixTileSizeTextVar = ImagesWorkspaceBoundVar({ imageUltraFixTileSizeText }, { imageUltraFixTileSizeText = it }),
        imageUltraFixTileSizeExplicitVar = ImagesWorkspaceBoundVar({ imageUltraFixTileSizeExplicit }, { imageUltraFixTileSizeExplicit = it }),
        imageUltraFixOverlapTextVar = ImagesWorkspaceBoundVar({ imageUltraFixOverlapText }, { imageUltraFixOverlapText = it }),
        restoredImageParameterModelIdVar = ImagesWorkspaceBoundVar({ restoredImageParameterModelId }, { restoredImageParameterModelId = it }),
        imageInputRestoreWarningVar = ImagesWorkspaceBoundVar({ imageInputRestoreWarning }, { imageInputRestoreWarning = it }),
        imageLoraRestoreWarningVar = ImagesWorkspaceBoundVar({ imageLoraRestoreWarning }, { imageLoraRestoreWarning = it }),
        pendingGenerationImageRoleVar = ImagesWorkspaceBoundVar({ pendingGenerationImageRole }, { pendingGenerationImageRole = it }),
        pendingUltraFixSubmissionVar = ImagesWorkspaceBoundVar({ pendingUltraFixSubmission }, { pendingUltraFixSubmission = it }),
        imageGenerationStartSignalVar = ImagesWorkspaceBoundVar({ imageGenerationStartSignal }, { imageGenerationStartSignal = it }),
        generationImageEditorRequestVar = ImagesWorkspaceBoundVar({ generationImageEditorRequest }, { generationImageEditorRequest = it }),
    )
    ImagesWorkspaceEffectsSection(bindings)
    ImagesWorkspaceProbeEffectsSection(bindings)
    ImagesWorkspaceMainPageSection(bindings)
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
                        // At 2.0x the two warning paragraphs can exceed the dialog height; keep the
                        // warning scrollable so the confirm/dismiss buttons stay reachable. No
                        // typography, color or default-size change when the content already fits.
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
