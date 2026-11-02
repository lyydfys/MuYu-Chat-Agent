package com.muyuchat.feature.chat

import androidx.compose.runtime.Composable
import com.muyuchat.core.designsystem.rememberMcaNotify
import kotlinx.coroutines.launch

@Composable
internal fun ImagesWorkspaceMainPageSection(bindings: ImagesWorkspaceOverlayBindings) {
    val notify = rememberMcaNotify()
    with(bindings) {
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
                    imageModelsLoading = state.imageModelsLoading,
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
                    onSubmitPrompt = { enqueueImagePrompt(imagePrompt, notify) },
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
                            notify("单次最多选择 8 个 LoRA")
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
                            notify("该 Textual Inversion 已不存在")
                            return@onToggleTextualInversion
                        }
                        val current = imageTextualInversionIdsFromJson(
                            imageTextualInversionIdsJson
                        )
                        if (id !in current &&
                            !artifact.isSupportedBy(supportedImageTextualInversionFormats)
                        ) {
                            notify("当前模型不支持 ${artifact.format} 格式的 Textual Inversion")
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
                            notify("单次最多选择 8 个 Textual Inversion")
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
                            notify("请先选择 UltraFix 源图")
                            return@onUltraFixEnabledChange false
                        }
                        if (imageInputDimensionsProbing) {
                            notify("正在检查源图尺寸，请稍后重试")
                            return@onUltraFixEnabledChange false
                        }
                        if (imageInputDimensionsUri != imageInputUri ||
                            imageInputSourceWidth <= 0 || imageInputSourceHeight <= 0
                        ) {
                            notify(
                                if (imageInputDimensionsProbeFailed) {
                                    "无法安全读取源图尺寸，请重新选择或先裁剪图片"
                                } else {
                                    "请等待源图尺寸检查完成"
                                },
                            )
                            return@onUltraFixEnabledChange false
                        }
                        val sourceTarget = model?.ultraFixTargetSizeForSourceOrNull(
                            imageInputSourceWidth,
                            imageInputSourceHeight
                        )
                        if (model == null || sourceTarget == null) {
                            notify("源图尺寸超出当前模型的 UltraFix 目标范围，请先裁剪图片")
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
    }
}
