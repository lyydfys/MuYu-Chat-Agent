package com.muyuchat.feature.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.snapshots.SnapshotStateMap
import android.content.SharedPreferences
import kotlin.reflect.KProperty

internal class ImagesWorkspaceBoundVar<T>(val read: () -> T, val write: (T) -> Unit)

internal operator fun <T> ImagesWorkspaceBoundVar<T>.getValue(thisRef: Any?, property: KProperty<*>): T = read()

internal operator fun <T> ImagesWorkspaceBoundVar<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) { write(value) }

internal class ImagesWorkspaceOverlayBindings(
    val state: ChatUiState,
    val context: Context,
    val imageParameterPreferences: SharedPreferences,
    val generationImageGrantMutex: Mutex,
    val showImages: Boolean,
    val onDismiss: () -> Unit,
    val onDismissStatusMessage: () -> Unit,
    val onTranslateImagePrompt: (suspend (String) -> String)?,
    val onTranslateImageNegativePrompt: (suspend (String) -> String)?,
    val onImportImageModel: () -> Unit,
    val releaseGenerationImageGrantsIfCoordinatorIdle: ((() -> Unit) -> Boolean),
    val onSelectImageModel: (String) -> Unit,
    val onGenerateImagePrompt: (String, ImageGenerationUiOptions) -> Boolean,
    val onCancelImageGeneration: () -> Unit,
    val onRetryImageGeneration: (String) -> Unit,
    val onChatImageLoraSelectionChange: (List<ImageGenerationUiLoraSelection>?) -> Unit,
    val onDeleteImageLora: (String) -> Unit,
    val onDeleteImageTextualInversion: (String) -> Unit,
    val onSelectImageUpscaler: (String) -> Unit,
    val onDeleteImageUpscaler: (String) -> Unit,
    val onUpscaleImageAsset: (String, Int) -> Unit,
    val onCancelImageUpscale: () -> Unit,
    val onUseImageAsset: (String) -> Unit,
    val onDeleteImageAsset: (String) -> Unit,
    val onDeleteImageAssets: (List<String>) -> Unit,
    val onSetImageAssetFavorite: (String, Boolean) -> Unit,
    val onExportImageLibraryBackup: (String, Boolean) -> Unit,
    val onImportImageLibraryBackup: (String) -> Unit,
    val onCancelImageLibraryBackup: () -> Unit,
    val onRecreateImageAsset: (String) -> Unit,
    val onMeasureImagePromptTokens: (suspend (String, String) -> ImagePromptTokenMeasurement?)?,
    val onDownloadOfflineTranslation: () -> Unit,
    val onCancelOfflineTranslationDownload: () -> Unit,
    val onOpenRecommendedModels: () -> Unit,
    val offlineTranslationStatus: String,
    val offlineTranslationInstalled: Boolean,
    val offlineTranslationInstalling: Boolean,
    val offlineTranslationDownloading: Boolean,
    val offlineTranslationDownloadProgress: Float?,
    val imageTextualInversionTriggerById: SnapshotStateMap<String, String>,
    val imageTaskMode: ImageGenerationUiTaskMode,
    val selectedImageModelChoice: ChatModelChoice?,
    val selectedImageTaskModes: Set<ImageGenerationUiTaskMode>,
    val supportsImageNegativePrompt: Boolean,
    val supportsImageClipSkip: Boolean,
    val supportsImageVaeTiling: Boolean,
    val supportsImageTextualInversion: Boolean,
    val supportedImageTextualInversionFormats: Set<String>,
    val imageTextualInversionsForSelectedModel: List<ImageTextualInversionUiItem>,
    val supportsImageUltraFix: Boolean,
    val supportsImageLora: Boolean,
    val selectedImagePreviewMode: ImageGenerationUiPreviewMode?,
    val supportsImageLivePreview: Boolean,
    val selectedImageSupportedSamplers: List<String>,
    val selectedImageDefaultSampler: String,
    val maxImageBatchCount: Int,
    val currentImageModelIds: Set<String>,
    val transientGenerationImageUris: Set<String>,
    val libraryHistoryGenerationImageUris: Set<String>,
    val generationPhotoPicker: ManagedActivityResultLauncher<Array<String>, Uri?>,
    val generationLoraPicker: ManagedActivityResultLauncher<Array<String>, Uri?>,
    val generationTextualInversionPicker: ManagedActivityResultLauncher<Array<String>, Uri?>,
    val generationUpscalerPicker: ManagedActivityResultLauncher<Array<String>, Uri?>,
    imagePromptVar: ImagesWorkspaceBoundVar<String>,
    imageValidationErrorVar: ImagesWorkspaceBoundVar<String?>,
    imageTaskModeNameVar: ImagesWorkspaceBoundVar<String>,
    imageInputUriVar: ImagesWorkspaceBoundVar<String?>,
    imageMaskUriVar: ImagesWorkspaceBoundVar<String?>,
    imageControlUriVar: ImagesWorkspaceBoundVar<String?>,
    imageInputDimensionsUriVar: ImagesWorkspaceBoundVar<String?>,
    imageInputSourceWidthVar: ImagesWorkspaceBoundVar<Int>,
    imageInputSourceHeightVar: ImagesWorkspaceBoundVar<Int>,
    imageInputDimensionsProbingVar: ImagesWorkspaceBoundVar<Boolean>,
    imageInputDimensionsProbeFailedVar: ImagesWorkspaceBoundVar<Boolean>,
    imageStrengthTextVar: ImagesWorkspaceBoundVar<String>,
    imageControlStrengthTextVar: ImagesWorkspaceBoundVar<String>,
    imageNegativePromptVar: ImagesWorkspaceBoundVar<String>,
    imageDisableModelNegativePromptVar: ImagesWorkspaceBoundVar<Boolean>,
    imageClipSkipTextVar: ImagesWorkspaceBoundVar<String>,
    imageVaeTilingEnabledVar: ImagesWorkspaceBoundVar<Boolean>,
    imageLivePreviewEnabledVar: ImagesWorkspaceBoundVar<Boolean>,
    imageLivePreviewIntervalVar: ImagesWorkspaceBoundVar<Int>,
    imageLivePreviewIntervalExplicitVar: ImagesWorkspaceBoundVar<Boolean>,
    imageBatchCountVar: ImagesWorkspaceBoundVar<Int>,
    imageWidthTextVar: ImagesWorkspaceBoundVar<String>,
    imageHeightTextVar: ImagesWorkspaceBoundVar<String>,
    imageNormalWidthTextVar: ImagesWorkspaceBoundVar<String>,
    imageNormalHeightTextVar: ImagesWorkspaceBoundVar<String>,
    imageUltraFixTargetWidthTextVar: ImagesWorkspaceBoundVar<String>,
    imageUltraFixTargetHeightTextVar: ImagesWorkspaceBoundVar<String>,
    imageStepsTextVar: ImagesWorkspaceBoundVar<String>,
    imageCfgScaleTextVar: ImagesWorkspaceBoundVar<String>,
    imageViggleAutoStepsVar: ImagesWorkspaceBoundVar<Boolean>,
    imageViggleAutoCfgVar: ImagesWorkspaceBoundVar<Boolean>,
    imageStepsEditedByUserVar: ImagesWorkspaceBoundVar<Boolean>,
    imageCfgEditedByUserVar: ImagesWorkspaceBoundVar<Boolean>,
    imageViggleSelectedVar: ImagesWorkspaceBoundVar<Boolean>,
    imageSeedTextVar: ImagesWorkspaceBoundVar<String>,
    imageSamplerVar: ImagesWorkspaceBoundVar<String>,
    imageLoraDraftJsonVar: ImagesWorkspaceBoundVar<String>,
    imageTextualInversionIdsJsonVar: ImagesWorkspaceBoundVar<String>,
    imageUltraFixEnabledVar: ImagesWorkspaceBoundVar<Boolean>,
    imageUltraFixStrengthTextVar: ImagesWorkspaceBoundVar<String>,
    imageUltraFixInversionStepsTextVar: ImagesWorkspaceBoundVar<String>,
    imageUltraFixRefinementStepsTextVar: ImagesWorkspaceBoundVar<String>,
    imageUltraFixTileSizeTextVar: ImagesWorkspaceBoundVar<String>,
    imageUltraFixTileSizeExplicitVar: ImagesWorkspaceBoundVar<Boolean>,
    imageUltraFixOverlapTextVar: ImagesWorkspaceBoundVar<String>,
    restoredImageParameterModelIdVar: ImagesWorkspaceBoundVar<String?>,
    imageInputRestoreWarningVar: ImagesWorkspaceBoundVar<String?>,
    imageLoraRestoreWarningVar: ImagesWorkspaceBoundVar<String?>,
    pendingGenerationImageRoleVar: ImagesWorkspaceBoundVar<String>,
    pendingUltraFixSubmissionVar: ImagesWorkspaceBoundVar<Pair<String, ImageGenerationUiOptions>?>,
    imageGenerationStartSignalVar: ImagesWorkspaceBoundVar<Long>,
    generationImageEditorRequestVar: ImagesWorkspaceBoundVar<GenerationImageEditorRequest?>,
) {

    val enqueueImagePrompt: (String) -> Unit = { prompt -> imagesWorkspaceEnqueueImagePrompt(this, prompt) }

    var imagePrompt by imagePromptVar
    var imageValidationError by imageValidationErrorVar
    var imageTaskModeName by imageTaskModeNameVar
    var imageInputUri by imageInputUriVar
    var imageMaskUri by imageMaskUriVar
    var imageControlUri by imageControlUriVar
    var imageInputDimensionsUri by imageInputDimensionsUriVar
    var imageInputSourceWidth by imageInputSourceWidthVar
    var imageInputSourceHeight by imageInputSourceHeightVar
    var imageInputDimensionsProbing by imageInputDimensionsProbingVar
    var imageInputDimensionsProbeFailed by imageInputDimensionsProbeFailedVar
    var imageStrengthText by imageStrengthTextVar
    var imageControlStrengthText by imageControlStrengthTextVar
    var imageNegativePrompt by imageNegativePromptVar
    var imageDisableModelNegativePrompt by imageDisableModelNegativePromptVar
    var imageClipSkipText by imageClipSkipTextVar
    var imageVaeTilingEnabled by imageVaeTilingEnabledVar
    var imageLivePreviewEnabled by imageLivePreviewEnabledVar
    var imageLivePreviewInterval by imageLivePreviewIntervalVar
    var imageLivePreviewIntervalExplicit by imageLivePreviewIntervalExplicitVar
    var imageBatchCount by imageBatchCountVar
    var imageWidthText by imageWidthTextVar
    var imageHeightText by imageHeightTextVar
    var imageNormalWidthText by imageNormalWidthTextVar
    var imageNormalHeightText by imageNormalHeightTextVar
    var imageUltraFixTargetWidthText by imageUltraFixTargetWidthTextVar
    var imageUltraFixTargetHeightText by imageUltraFixTargetHeightTextVar
    var imageStepsText by imageStepsTextVar
    var imageCfgScaleText by imageCfgScaleTextVar
    var imageViggleAutoSteps by imageViggleAutoStepsVar
    var imageViggleAutoCfg by imageViggleAutoCfgVar
    var imageStepsEditedByUser by imageStepsEditedByUserVar
    var imageCfgEditedByUser by imageCfgEditedByUserVar
    var imageViggleSelected by imageViggleSelectedVar
    var imageSeedText by imageSeedTextVar
    var imageSampler by imageSamplerVar
    var imageLoraDraftJson by imageLoraDraftJsonVar
    var imageTextualInversionIdsJson by imageTextualInversionIdsJsonVar
    var imageUltraFixEnabled by imageUltraFixEnabledVar
    var imageUltraFixStrengthText by imageUltraFixStrengthTextVar
    var imageUltraFixInversionStepsText by imageUltraFixInversionStepsTextVar
    var imageUltraFixRefinementStepsText by imageUltraFixRefinementStepsTextVar
    var imageUltraFixTileSizeText by imageUltraFixTileSizeTextVar
    var imageUltraFixTileSizeExplicit by imageUltraFixTileSizeExplicitVar
    var imageUltraFixOverlapText by imageUltraFixOverlapTextVar
    var restoredImageParameterModelId by restoredImageParameterModelIdVar
    var imageInputRestoreWarning by imageInputRestoreWarningVar
    var imageLoraRestoreWarning by imageLoraRestoreWarningVar
    var pendingGenerationImageRole by pendingGenerationImageRoleVar
    var pendingUltraFixSubmission by pendingUltraFixSubmissionVar
    var imageGenerationStartSignal by imageGenerationStartSignalVar
    var generationImageEditorRequest by generationImageEditorRequestVar
}
