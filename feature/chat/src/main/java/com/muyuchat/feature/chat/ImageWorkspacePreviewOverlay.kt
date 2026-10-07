package com.muyuchat.feature.chat

import android.widget.Toast
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BoxScope.ImageWorkspacePreviewSection(
    previewImageId: String?,
    images: List<ImageAssetUiItem>,
    imageModels: List<ChatModelChoice>,
    selectedImageModelId: String?,
    selectedImageModelIsCloud: Boolean,
    jobs: List<ImageGenerationUiJob>,
    supportedTaskModes: Set<ImageGenerationUiTaskMode>,
    supportsNegativePrompt: Boolean,
    supportsClipSkip: Boolean,
    supportsVaeTiling: Boolean,
    supportsLora: Boolean,
    loras: List<ImageLoraUiItem>,
    selectedLoras: List<ImageGenerationUiLoraDraft>,
    maxBatchCount: Int,
    taskMode: ImageGenerationUiTaskMode,
    supportsTextualInversion: Boolean,
    supportsUltraFix: Boolean,
    textualInversions: List<ImageTextualInversionUiItem>,
    selectedTextualInversionIds: List<String>,
    inputImageUri: String?,
    inputImageWidth: Int,
    inputImageHeight: Int,
    upscalers: List<ImageUpscalerUiItem>,
    selectedUpscalerId: String?,
    upscalerImporting: Boolean,
    upscalerMessage: String,
    upscaleJob: ImageUpscaleUiJob?,
    onDismissPreview: () -> Unit,
    onDeleteImage: (String) -> Unit,
    onUseImageAsGenerationInput: (String, Int, Int) -> Unit,
    onTaskModeChange: (ImageGenerationUiTaskMode) -> Unit,
    onUltraFixEnabledChange: (Boolean) -> Boolean,
    onPromptChange: (String) -> Unit,
    onNegativePromptChange: (String) -> Unit,
    onDisableModelNegativePromptChange: (Boolean) -> Unit,
    onStrengthTextChange: (String) -> Unit,
    onControlStrengthTextChange: (String) -> Unit,
    onWidthTextChange: (String) -> Unit,
    onHeightTextChange: (String) -> Unit,
    onStepsTextChange: (String) -> Unit,
    onCfgScaleTextChange: (String) -> Unit,
    onSeedTextChange: (String) -> Unit,
    onSamplerChange: (String) -> Unit,
    onClipSkipTextChange: (String) -> Unit,
    onBatchCountChange: (Int) -> Unit,
    onVaeTilingEnabledChange: (Boolean) -> Unit,
    onToggleLora: (String) -> Unit,
    onLoraMultiplierChange: (String, String) -> Unit,
    onToggleTextualInversion: (String) -> Unit,
    onUltraFixRefinementStepsTextChange: (String) -> Unit,
    onUltraFixDenoisingStepsTextChange: (String) -> Unit,
    onUltraFixTileSizeTextChange: (String) -> Unit,
    onUltraFixOverlapTextChange: (String) -> Unit,
    onImportUpscaler: () -> Unit,
    onDeleteUpscaler: (String) -> Unit,
    onSelectUpscaler: (String) -> Unit,
    onUpscaleImage: (String, Int) -> Unit,
    onCancelUpscale: () -> Unit,
    onSetImageAssetFavorite: (String, Boolean) -> Unit,
    onRecreateImageAsset: (String) -> Unit,
    onOpenGenerationCanvas: () -> Unit,
    onSetPendingConversationPrompt: (String) -> Unit
) {
    if (previewImageId == null) return
    val image = images.firstOrNull { it.id == previewImageId } ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
        onDismiss = onDismissPreview,
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
        onDelete = { onDeleteImage(image.id) },
        canUseAsImg2Img = canUseAsImg2Img,
        canUseUltraFix = ultraFixTargetSize != null,
        onUseAsImg2Img = {
            onUseImageAsGenerationInput(image.uriString, image.width, image.height)
            onTaskModeChange(ImageGenerationUiTaskMode.IMG2IMG)
            onUltraFixEnabledChange(false)
            image.generationPrompt.takeIf(String::isNotBlank)?.let(onPromptChange)
            onOpenGenerationCanvas()
            onDismissPreview()
        },
        onUseUltraFix = onUseUltraFix@ {
            val target = ultraFixTargetSize ?: return@onUseUltraFix
            onUseImageAsGenerationInput(image.uriString, image.width, image.height)
            onTaskModeChange(ImageGenerationUiTaskMode.IMG2IMG)
            if (!onUltraFixEnabledChange(true)) return@onUseUltraFix
            onWidthTextChange(target.first.toString())
            onHeightTextChange(target.second.toString())
            image.generationPrompt.takeIf(String::isNotBlank)?.let(onPromptChange)
            onOpenGenerationCanvas()
            onDismissPreview()
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
            val compositeUltraFix = preset.ultraFix?.takeIf { selectedUltraFix }
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
            if (ImageGenerationPresetField.STRENGTH in fields && !hasStructuredUltraFixPayload) {
                preset.strength?.let { onStrengthTextChange(it.toString()) }
            }
            if (ImageGenerationPresetField.CONTROL_STRENGTH in fields) {
                preset.controlStrength?.let { onControlStrengthTextChange(it.toString()) }
            }
            if (ImageGenerationPresetField.SIZE in fields && !hasStructuredUltraFixPayload) {
                preset.width?.let { onWidthTextChange(it.toString()) }
                preset.height?.let { onHeightTextChange(it.toString()) }
            }
            if (ImageGenerationPresetField.STEPS in fields && !hasStructuredUltraFixPayload) {
                preset.steps?.let { onStepsTextChange(it.toString()) }
            }
            if (ImageGenerationPresetField.CFG in fields) {
                preset.cfgScale?.let { onCfgScaleTextChange(it.toString()) }
            }
            if (ImageGenerationPresetField.SEED in fields) {
                preset.seed?.let { onSeedTextChange(it.toString()) }
            }
            if (ImageGenerationPresetField.SAMPLER in fields) {
                val model = imageModels.firstOrNull { it.id == selectedImageModelId }
                preset.sampleMethod
                    ?.takeIf {
                        model?.imageSupportedSamplersForTask(taskMode)?.contains(it) == true
                    }
                    ?.let(onSamplerChange)
            }
            if (ImageGenerationPresetField.CLIP_SKIP in fields && supportsClipSkip) {
                preset.clipSkip?.let { onClipSkipTextChange(it.toString()) }
            }
            if (ImageGenerationPresetField.BATCH in fields && !hasStructuredUltraFixPayload) {
                preset.batchCount?.let { onBatchCountChange(it.coerceIn(1, maxBatchCount)) }
            }
            if (ImageGenerationPresetField.VAE_TILING in fields && supportsVaeTiling && !hasStructuredUltraFixPayload) {
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
            if (ImageGenerationPresetField.TEXTUAL_INVERSION in fields && supportsTextualInversion) {
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
                    onUltraFixRefinementStepsTextChange(ultraFix.refinementSteps.toString())
                    onUltraFixDenoisingStepsTextChange(ultraFix.inversionSteps.toString())
                    onUltraFixTileSizeTextChange(ultraFix.tileSize.toString())
                    onUltraFixOverlapTextChange(ultraFix.overlap.toString())
                }
            }
            onDismissPreview()
            Toast.makeText(context, "已将所选参数应用到生成面板", Toast.LENGTH_SHORT).show()
        },
        onRecreate = {
            onSetPendingConversationPrompt(image.generationPrompt.ifBlank { image.prompt })
            onOpenGenerationCanvas()
            onDismissPreview()
            onRecreateImageAsset(image.id)
        },
        recreateEnabled = image.canRecreate && jobs.none { it.isWorking },
        modifier = Modifier.align(Alignment.Center)
    )
}
