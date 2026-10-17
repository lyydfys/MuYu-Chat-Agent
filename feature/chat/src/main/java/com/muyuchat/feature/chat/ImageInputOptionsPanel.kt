package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight

@Composable
// P9/S27: private→internal（跨文件被 ImageGalleryHome 引用）
internal fun ImageInputOptionsPanel(
    revealParameters: Int,
    taskMode: ImageGenerationUiTaskMode,
    selectedModelIsCloud: Boolean,
    executionModel: ChatModelChoice?,
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
    modelId: String?,
    onMeasureTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)?,
    onPromptChange: (String) -> Unit,
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
    onSamplerChange: (String) -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val selectedLoraById = selectedLoras.associateBy(ImageGenerationUiLoraDraft::id)
    val loraSelectionLimitReached = selectedLoras.size >= 8
    val selectedTextualInversionIdSet = selectedTextualInversionIds.toSet()
    val textualInversionSelectionLimitReached = selectedTextualInversionIds.size >= 8
    val ultraFixTargetForInput = if (inputImageUri != null) {
        executionModel?.ultraFixTargetSizeForSourceOrNull(inputImageWidth, inputImageHeight)
    } else {
        null
    }
    val ultraFixInputReady = inputImageUri != null && ultraFixTargetForInput != null
    var pendingLoraDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTextualInversionDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingParameterImport by remember {
        mutableStateOf<ImageGenerationParameterImportSelection?>(null)
    }
    var advancedParametersExpanded by rememberSaveable(
        executionModel?.id,
        selectedModelIsCloud
    ) { mutableStateOf(false) }
    LaunchedEffect(revealParameters) {
        if (revealParameters > 0) advancedParametersExpanded = true
    }
    fun importParametersFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = clipboard
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        ImageGenerationParameterImportCodec.decode(raw)
            .onSuccess { imported ->
                val compatible = compatibleImageGenerationParameterImportFields(
                    imported = imported,
                    currentTaskMode = taskMode,
                    selectedModel = executionModel,
                    selectedModelIsCloud = selectedModelIsCloud,
                    supportsNegativePrompt = supportsNegativePrompt,
                    supportsClipSkip = supportsClipSkip,
                    supportsVaeTiling = supportsVaeTiling,
                    supportsLora = supportsLora,
                    availableLoraIds = loras.mapTo(mutableSetOf(), ImageLoraUiItem::id),
                    maxBatchCount = maxBatchCount,
                    supportsTextualInversion = supportsTextualInversion,
                    supportsUltraFix = supportsUltraFix,
                    ultraFixEnabled = ultraFixEnabled,
                    hasImageInput = inputImageUri != null,
                    imageInputWidth = inputImageWidth.takeIf { it > 0 },
                    imageInputHeight = inputImageHeight.takeIf { it > 0 },
                    availableTextualInversionIds = textualInversions
                        .filter { it.compatibleWithSelectedModel }
                        .mapTo(mutableSetOf(), ImageTextualInversionUiItem::id)
                )
                pendingParameterImport = ImageGenerationParameterImportSelection(
                    imported = imported,
                    compatibleFields = compatible
                )
            }
            .onFailure { error ->
                Toast.makeText(
                    context,
                    error.message?.takeIf(String::isNotBlank) ?: "无法识别剪贴板图片参数。",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }
    fun applyParameterImport(application: ImageGenerationParameterImportApplication) {
        val imported = application.imported
        val preset = imported.preset
        val fields = application.fields
        val hasStructuredUltraFixPayload = imported.hasStructuredUltraFixPayload()
        val selectedUltraFix = ImageGenerationParameterImportField.ULTRAFIX in fields
        val localDreamUltraFixPayload =
            imported.source == ImageGenerationParameterImportSource.LOCAL_DREAM &&
                imported.taskMode.equals("ULTRAFIX", ignoreCase = true) &&
                ImageGenerationParameterImportField.ULTRAFIX in imported.fields
        val ultraFixSourceTarget = if (selectedUltraFix) {
            executionModel?.ultraFixTargetSizeForSourceOrNull(inputImageWidth, inputImageHeight)
        } else {
            null
        }
        if (selectedUltraFix) {
            val structured = preset.ultraFix
            if (ultraFixSourceTarget == null ||
                (structured != null &&
                    (structured.targetWidth < ultraFixSourceTarget.first ||
                        structured.targetHeight < ultraFixSourceTarget.second))
            ) {
                Toast.makeText(
                    context,
                    "源图或目标尺寸已变化，无法安全应用 UltraFix 参数",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            // Cross the mode switch before mutating any imported field. A rejected switch leaves
            // both the ordinary preset and every other selected field untouched.
            if (!onUltraFixEnabledChange(true)) return
        }
        if (ImageGenerationParameterImportField.PROMPT in fields) onPromptChange(preset.prompt)
        if (ImageGenerationParameterImportField.NEGATIVE_PROMPT in fields) {
            onNegativePromptChange(preset.negativePrompt.orEmpty())
            onDisableModelNegativePromptChange(preset.negativePrompt == "")
        }
        if (ImageGenerationParameterImportField.SIZE in fields && !hasStructuredUltraFixPayload) {
            preset.width?.let { onWidthTextChange(it.toString()) }
            preset.height?.let { onHeightTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.STEPS in fields && !hasStructuredUltraFixPayload) {
            preset.steps?.let { onStepsTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.CFG in fields) {
            preset.cfgScale?.let { onCfgScaleTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.SEED in fields) {
            preset.seed?.let { onSeedTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.SAMPLER in fields) {
            preset.sampleMethod
                ?.takeIf {
                    executionModel
                        ?.imageSupportedSamplersForTask(taskMode)
                        ?.contains(it) == true
                }
                ?.let(onSamplerChange)
        }
        if (ImageGenerationParameterImportField.CLIP_SKIP in fields) {
            preset.clipSkip?.let { onClipSkipTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.BATCH in fields && !hasStructuredUltraFixPayload) {
            preset.batchCount?.let { onBatchCountChange(it.coerceIn(1, maxBatchCount)) }
        }
        if (ImageGenerationParameterImportField.VAE_TILING in fields &&
            !hasStructuredUltraFixPayload
        ) {
            onVaeTilingEnabledChange(preset.vaeTileSize != null)
        }
        if (ImageGenerationParameterImportField.LORA in fields) {
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
        if (ImageGenerationParameterImportField.TEXTUAL_INVERSION in fields) {
            val desiredIds = preset.textualInversionIds.toSet()
            selectedTextualInversionIds
                .filterNot(desiredIds::contains)
                .forEach(onToggleTextualInversion)
            preset.textualInversionIds
                .filterNot(selectedTextualInversionIds::contains)
                .forEach(onToggleTextualInversion)
        }
        if (selectedUltraFix) {
            val sourceTarget = requireNotNull(ultraFixSourceTarget)
            val structured = preset.ultraFix
            if (structured != null) {
                val ultraFix = structured
                onWidthTextChange(ultraFix.targetWidth.toString())
                onHeightTextChange(ultraFix.targetHeight.toString())
                onUltraFixRefinementStepsTextChange(ultraFix.refinementSteps.toString())
                onUltraFixDenoisingStepsTextChange(ultraFix.inversionSteps.toString())
                onUltraFixTileSizeTextChange(ultraFix.tileSize.toString())
                onUltraFixOverlapTextChange(ultraFix.overlap.toString())
            } else if (localDreamUltraFixPayload) {
                onWidthTextChange(sourceTarget.first.toString())
                onHeightTextChange(sourceTarget.second.toString())
                val refinementSteps = preset.steps
                    ?.coerceIn(1, IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS)
                    ?: ultraFixRefinementStepsText.toIntOrNull()
                        ?.coerceIn(1, IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS)
                    ?: 10
                onUltraFixRefinementStepsTextChange(refinementSteps.toString())
                imported.strength?.let { strength ->
                    val denoisingSteps = imageGenerationUltraFixDenoisingTailStepCount(
                        refinementSteps,
                        strength
                    ).coerceAtMost(IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS)
                    onUltraFixDenoisingStepsTextChange(denoisingSteps.toString())
                }
            }
        }
        if (ImageGenerationParameterImportField.STRENGTH in fields && !hasStructuredUltraFixPayload) {
            imported.strength?.let { onStrengthTextChange(it.toString()) }
        }
        if (ImageGenerationParameterImportField.CONTROL_STRENGTH in fields) {
            imported.controlStrength?.let { onControlStrengthTextChange(it.toString()) }
        }
        val sourceLabel = when (imported.source) {
            ImageGenerationParameterImportSource.MCA -> "MCA"
            ImageGenerationParameterImportSource.LOCAL_DREAM -> "Local Dream"
        }
        Toast.makeText(
            context,
            "已从 $sourceLabel 应用 ${fields.size} 项参数。",
            Toast.LENGTH_SHORT
        ).show()
    }
    LaunchedEffect(pendingLoraDeleteId, loras) {
        val pendingId = pendingLoraDeleteId ?: return@LaunchedEffect
        if (loras.none { adapter -> adapter.id == pendingId && !adapter.inUse }) {
            pendingLoraDeleteId = null
        }
    }
    LaunchedEffect(pendingTextualInversionDeleteId, textualInversions) {
        val pendingId = pendingTextualInversionDeleteId ?: return@LaunchedEffect
        if (textualInversions.none { artifact -> artifact.id == pendingId && !artifact.inUse }) {
            pendingTextualInversionDeleteId = null
        }
    }
    val deleteState = ImageInputOptionsPanelDeleteState(
        readPendingLoraDeleteId = { pendingLoraDeleteId },
        writePendingLoraDeleteId = { pendingLoraDeleteId = it },
        readPendingTextualInversionDeleteId = { pendingTextualInversionDeleteId },
        writePendingTextualInversionDeleteId = { pendingTextualInversionDeleteId = it },
        readPendingParameterImport = { pendingParameterImport },
        writePendingParameterImport = { pendingParameterImport = it },
        applyParameterImportImpl = ::applyParameterImport,
    )
    Box {
        Surface(
            color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF6F8FC),
            shape = RoundedCornerShape(20.dp)
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ImageInputTaskModePanel(
                taskMode = taskMode,
                selectedModelIsCloud = selectedModelIsCloud,
                supportedTaskModes = supportedTaskModes,
                supportsLora = supportsLora,
                loraRestoreWarning = loraRestoreWarning,
                onAcceptLoraRestore = onAcceptLoraRestore,
                inputImageUri = inputImageUri,
                maskImageUri = maskImageUri,
                controlImageUri = controlImageUri,
                inputRestoreWarning = inputRestoreWarning,
                strengthText = strengthText,
                controlStrengthText = controlStrengthText,
                clipSkipText = clipSkipText,
                vaeTilingEnabled = vaeTilingEnabled,
                onTaskModeChange = onTaskModeChange,
                onPickImageRole = onPickImageRole,
                onCropImageRole = onCropImageRole,
                onDrawImageMask = onDrawImageMask,
                onClearImageRole = onClearImageRole,
                onStrengthTextChange = onStrengthTextChange,
                onControlStrengthTextChange = onControlStrengthTextChange,
            )
            val parameterSummary = if (!selectedModelIsCloud && executionModel != null) {
                buildString {
                    append(widthText)
                    append('×')
                    append(heightText)
                    if (executionModel.imageMinWidth == executionModel.imageMaxWidth &&
                        executionModel.imageMinHeight == executionModel.imageMaxHeight && !ultraFixEnabled) {
                        append(" · 模型固定尺寸")
                    }
                    if (selectedLoras.isNotEmpty()) append(" · LoRA ${selectedLoras.size}")
                    if (selectedTextualInversionIds.isNotEmpty()) append(" · 嵌入 ${selectedTextualInversionIds.size}")
                    if (ultraFixEnabled) append(" · UltraFix")
                    if (batchCount > 1) append(" · ${batchCount} 张")
                }
            } else {
                "负面提示词、批量与高级控制"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (advancedParametersExpanded) "已展开" else "已收起"
                    }
                    .clickable(
                        role = SemanticsRole.Button,
                        onClickLabel = if (advancedParametersExpanded) {
                            "收起生成参数"
                        } else {
                            "展开生成参数"
                        }
                    ) {
                        advancedParametersExpanded = !advancedParametersExpanded
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "更多参数",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        parameterSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (advancedParametersExpanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = null
                )
            }
            if (advancedParametersExpanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = ::importParametersFromClipboard) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("从剪贴板导入")
                }
            }
            Text(
                "支持 MCA JSON / MCAPARAMS 与 Local Dream LDPARAMS；仅应用当前模型兼容字段。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            ImageInputGenerationParametersPanel(
                taskMode = taskMode,
                selectedModelIsCloud = selectedModelIsCloud,
                executionModel = executionModel,
                loras = loras,
                selectedLoras = selectedLoras,
                ultraFixEnabled = ultraFixEnabled,
                widthText = widthText,
                heightText = heightText,
                stepsText = stepsText,
                cfgScaleText = cfgScaleText,
                seedText = seedText,
                sampler = sampler,
                onWidthTextChange = onWidthTextChange,
                onHeightTextChange = onHeightTextChange,
                onStepsTextChange = onStepsTextChange,
                onCfgScaleTextChange = onCfgScaleTextChange,
                onSeedTextChange = onSeedTextChange,
                onSamplerChange = onSamplerChange,
            )
            ImageInputAdapterOptionsPanel(
                deleteState = deleteState,
                executionModel = executionModel,
                supportsNegativePrompt = supportsNegativePrompt,
                supportsTextualInversion = supportsTextualInversion,
                supportsLora = supportsLora,
                loras = loras,
                loraImporting = loraImporting,
                loraMessage = loraMessage,
                textualInversions = textualInversions,
                selectedTextualInversionIds = selectedTextualInversionIds,
                textualInversionImporting = textualInversionImporting,
                textualInversionMessage = textualInversionMessage,
                negativePrompt = negativePrompt,
                disableModelNegativePrompt = disableModelNegativePrompt,
                modelId = modelId,
                onMeasureTokens = onMeasureTokens,
                onNegativePromptChange = onNegativePromptChange,
                onDisableModelNegativePromptChange = onDisableModelNegativePromptChange,
                onToggleLora = onToggleLora,
                onLoraMultiplierChange = onLoraMultiplierChange,
                onImportLora = onImportLora,
                onDownloadOfficialViggleLora = onDownloadOfficialViggleLora,
                onToggleTextualInversion = onToggleTextualInversion,
                onImportTextualInversion = onImportTextualInversion,
                selectedLoraById = selectedLoraById,
                loraSelectionLimitReached = loraSelectionLimitReached,
                selectedTextualInversionIdSet = selectedTextualInversionIdSet,
                textualInversionSelectionLimitReached = textualInversionSelectionLimitReached,
            )
            ImageInputUltraFixPanel(
                taskMode = taskMode,
                executionModel = executionModel,
                supportsClipSkip = supportsClipSkip,
                supportsVaeTiling = supportsVaeTiling,
                supportsUltraFix = supportsUltraFix,
                inputImageUri = inputImageUri,
                inputImageWidth = inputImageWidth,
                inputImageHeight = inputImageHeight,
                clipSkipText = clipSkipText,
                vaeTilingEnabled = vaeTilingEnabled,
                ultraFixEnabled = ultraFixEnabled,
                ultraFixStrengthText = ultraFixStrengthText,
                ultraFixInversionStepsText = ultraFixInversionStepsText,
                ultraFixRefinementStepsText = ultraFixRefinementStepsText,
                ultraFixTileSizeText = ultraFixTileSizeText,
                ultraFixOverlapText = ultraFixOverlapText,
                onClipSkipTextChange = onClipSkipTextChange,
                onVaeTilingEnabledChange = onVaeTilingEnabledChange,
                onUltraFixEnabledChange = onUltraFixEnabledChange,
                onResetUltraFix = onResetUltraFix,
                onUltraFixDenoisingStepsTextChange = onUltraFixDenoisingStepsTextChange,
                onUltraFixRefinementStepsTextChange = onUltraFixRefinementStepsTextChange,
                onUltraFixTileSizeTextChange = onUltraFixTileSizeTextChange,
                onUltraFixOverlapTextChange = onUltraFixOverlapTextChange,
                ultraFixInputReady = ultraFixInputReady,
            )
            ImageInputLivePreviewBatchPanel(
                executionModel = executionModel,
                supportsVaeTiling = supportsVaeTiling,
                supportsLora = supportsLora,
                supportsLivePreview = supportsLivePreview,
                selectedLoras = selectedLoras,
                batchCount = batchCount,
                maxBatchCount = maxBatchCount,
                vaeTilingEnabled = vaeTilingEnabled,
                ultraFixEnabled = ultraFixEnabled,
                livePreviewEnabled = livePreviewEnabled,
                livePreviewInterval = livePreviewInterval,
                onLivePreviewEnabledChange = onLivePreviewEnabledChange,
                onLivePreviewIntervalChange = onLivePreviewIntervalChange,
                onBatchCountChange = onBatchCountChange,
            )
            }
        }
        }

        ImageInputDeleteDialogsPanel(
            deleteState = deleteState,
            taskMode = taskMode,
            executionModel = executionModel,
            loras = loras,
            textualInversions = textualInversions,
            onDeleteLora = onDeleteLora,
            onDeleteTextualInversion = onDeleteTextualInversion,
        )
    }

}

