package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.muyuchat.core.designsystem.McaMotion

// P9/S28：从 ImageWorkspaceScreens.kt 等值移出的图库首页（图片网格与空态）。
// 原样逐字迁移，未做行为改动；跨区入口按协议提升 internal。
@Composable
// P9/S28: private→internal（跨文件被 ImagesWorkspaceScreen 引用）
internal fun ImageGalleryHome(
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
    imageModelsLoading: Boolean,
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
                loading = imageModelsLoading,
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
                // Row keys are stable per chunk, so animateItem covers grid add/remove/reorder
                // without touching the 3-column geometry or the 3dp gutter.
                Row(
                    modifier = Modifier.animateItem(
                        fadeInSpec = McaMotion.contentEnter,
                        placementSpec = McaMotion.pageSlide,
                        fadeOutSpec = McaMotion.fade(millis = 140)
                    ),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
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
