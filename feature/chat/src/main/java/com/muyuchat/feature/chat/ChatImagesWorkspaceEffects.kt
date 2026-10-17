package com.muyuchat.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

@Composable
internal fun ImagesWorkspaceEffectsSection(bindings: ImagesWorkspaceOverlayBindings) {
    with(bindings) {
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
    }
}
