package com.muyuchat.feature.chat

import android.widget.Toast

internal fun imagesWorkspaceEnqueueImagePrompt(bindings: ImagesWorkspaceOverlayBindings, prompt: String) {
    with(bindings) {
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
}
