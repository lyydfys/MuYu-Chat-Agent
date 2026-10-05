package com.muyuchat.feature.chat

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight

// P9/S25：从 ImageWorkspaceScreens.kt 等值移出的输入支持簇（参数导入对话框 + 角色/强度输入行）。
// 原样逐字迁移，未做行为改动；3 个跨区入口按协议提升 internal。
@Composable
// P9/S25: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageGenerationParameterImportDialog(
    selection: ImageGenerationParameterImportSelection,
    currentModelName: String,
    currentTaskMode: ImageGenerationUiTaskMode,
    onSelectionChange: (ImageGenerationParameterImportSelection) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val imported = selection.imported
    val sourceLabel = when (imported.source) {
        ImageGenerationParameterImportSource.MCA -> "MCA"
        ImageGenerationParameterImportSource.LOCAL_DREAM -> "Local Dream"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要导入的参数") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    buildString {
                        append("来源：").append(sourceLabel)
                        imported.sourceModelId?.let { append(" · 模型 ").append(it) }
                        imported.taskMode?.let { append(" · 模式 ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    buildString {
                        append("仅填入")
                        append(currentModelName.ifBlank { "当前模型" })
                        append("的").append(currentTaskMode.label)
                        append("面板，不会切换模型或模式，也不会立即生成。")
                        if (selection.hiddenFieldCount > 0) {
                            append(" 不兼容的 ")
                            append(selection.hiddenFieldCount)
                            append(" 项字段已隐藏。")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (selection.compatibleFields.isEmpty()) {
                    Text(
                        "没有可用于当前模型与模式的字段。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    selection.compatibleFields.forEach { field ->
                        FilterChip(
                            selected = field in selection.selectedFields,
                            onClick = { onSelectionChange(selection.toggle(field)) },
                            label = { Text(field.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selection.selectedFields.isNotEmpty()
            ) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
// P9/S25: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageInputRoleRow(
    label: String,
    role: String,
    uri: String?,
    onPick: (String) -> Unit,
    onClear: (String) -> Unit,
    onEdit: ((String) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = { onPick(role) }) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (uri == null) "选择$label" else "更换$label")
        }
        Text(
            uri?.let(::imageInputDisplayName) ?: "未选择",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        if (uri != null) {
            if (onEdit != null) {
                IconButton(
                    onClick = { onEdit(role) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "裁剪$label")
                }
            }
            IconButton(onClick = { onClear(role) }) {
                Icon(Icons.Default.Close, contentDescription = "清除$label")
            }
        }
    }
}

@Composable
// P9/S25: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageStrengthField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun imageInputDisplayName(uriString: String): String = runCatching {
    Uri.parse(uriString).lastPathSegment?.substringAfterLast('/')
}.getOrNull()?.takeIf(String::isNotBlank)?.takeLast(64) ?: "已选择图片"

// P9/S27：从 ImageWorkspaceScreens.kt 等值移出的输入选项面板（图片生成输入配置主体）。
// 原样逐字迁移，未做行为改动；跨区入口按协议提升 internal。
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
            Text("生成方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            inputRestoreWarning?.let { warning ->
                Text(
                    warning,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            loraRestoreWarning?.let { warning ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        warning,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onAcceptLoraRestore) {
                        Text(if (supportsLora) "按当前选择继续" else "继续不带 LoRA")
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    ImageGenerationUiTaskMode.entries.filter(supportedTaskModes::contains),
                    key = { it.name }
                ) { mode ->
                    FilterChip(
                        selected = mode == taskMode,
                        onClick = { onTaskModeChange(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }
            if (selectedModelIsCloud && (
                    taskMode != ImageGenerationUiTaskMode.TEXT_TO_IMAGE ||
                        clipSkipText.isNotBlank() || vaeTilingEnabled
                    )
            ) {
                Text(
                    "当前云端连接器只声明了基础文生图；请选择本地引擎后再运行这些输入或高级控制。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            when (taskMode) {
                ImageGenerationUiTaskMode.TEXT_TO_IMAGE -> Unit
                ImageGenerationUiTaskMode.IMG2IMG -> {
                    ImageInputRoleRow(
                        "原图",
                        "input",
                        inputImageUri,
                        onPickImageRole,
                        onClearImageRole,
                        onEdit = onCropImageRole
                    )
                    ImageStrengthField("重绘强度 [0-1]", strengthText, onStrengthTextChange)
                }
                ImageGenerationUiTaskMode.EDIT -> {
                    ImageInputRoleRow(
                        "原图",
                        "input",
                        inputImageUri,
                        onPickImageRole,
                        onClearImageRole,
                        onEdit = onCropImageRole
                    )
                }
                ImageGenerationUiTaskMode.INPAINT -> {
                    ImageInputRoleRow(
                        "原图",
                        "input",
                        inputImageUri,
                        onPickImageRole,
                        onClearImageRole,
                        onEdit = onCropImageRole
                    )
                    ImageInputRoleRow("蒙版", "mask", maskImageUri, onPickImageRole, onClearImageRole)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = onDrawImageMask,
                            enabled = inputImageUri != null,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("绘制蒙版")
                        }
                    }
                    ImageStrengthField("重绘强度 [0-1]", strengthText, onStrengthTextChange)
                }
                ImageGenerationUiTaskMode.CONTROL -> {
                    ImageInputRoleRow(
                        "控制图",
                        "control",
                        controlImageUri,
                        onPickImageRole,
                        onClearImageRole,
                        onEdit = onCropImageRole
                    )
                    ImageStrengthField("控制强度 [0-2]", controlStrengthText, onControlStrengthTextChange)
                }
            }
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
            if (!selectedModelIsCloud && executionModel != null) {
                val isQwenImage21Model = executionModel.isQwenImage21Model()
                val displayedMinWidth = if (ultraFixEnabled) {
                    executionModel.imageUltraFixMinWidth
                } else {
                    executionModel.imageMinWidth
                }
                val displayedMaxWidth = if (ultraFixEnabled) {
                    executionModel.imageUltraFixMaxWidth
                } else {
                    executionModel.imageMaxWidth
                }
                val displayedMinHeight = if (ultraFixEnabled) {
                    executionModel.imageUltraFixMinHeight
                } else {
                    executionModel.imageMinHeight
                }
                val displayedMaxHeight = if (ultraFixEnabled) {
                    executionModel.imageUltraFixMaxHeight
                } else {
                    executionModel.imageMaxHeight
                }
                val displayedWidthMultiple = if (ultraFixEnabled) {
                    executionModel.imageUltraFixWidthMultiple
                } else {
                    executionModel.imageWidthMultiple
                }
                val displayedHeightMultiple = if (ultraFixEnabled) {
                    executionModel.imageUltraFixHeightMultiple
                } else {
                    executionModel.imageHeightMultiple
                }
                val fixedWidth = displayedMinWidth == displayedMaxWidth
                val fixedHeight = displayedMinHeight == displayedMaxHeight
                if (isQwenImage21Model) {
                    Text(
                        "Qwen-Image-2.1 当前 runtime 的 21 个预设尺寸组合：Standard（7 种比例）512×512、576×448、448×576、640×416、416×640、672×384、384×672；Fast 384×384、448×320、320×448、480×320、320×480、512×288、288×512；Tiny 320×320、384×288、288×384、384×256、256×384、416×256、256×416。任意 32 对齐尺寸不代表可运行。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { value -> if (!fixedWidth) onWidthTextChange(value) },
                        label = { Text("宽度") },
                        supportingText = {
                            Text(
                                if (isQwenImage21Model) {
                                    "请选择当前 runtime 提供的宽高组合"
                                } else if (fixedWidth) {
                                    "模型固定尺寸"
                                } else {
                                    "$displayedMinWidth-$displayedMaxWidth / $displayedWidthMultiple"
                                }
                            )
                        },
                        singleLine = true,
                        readOnly = fixedWidth,
                        modifier = Modifier.weight(1f).testTag("image.width")
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { value -> if (!fixedHeight) onHeightTextChange(value) },
                        label = { Text("高度") },
                        supportingText = {
                            Text(
                                if (isQwenImage21Model) {
                                    "请选择当前 runtime 提供的宽高组合"
                                } else if (fixedHeight) {
                                    "模型固定尺寸"
                                } else {
                                    "$displayedMinHeight-$displayedMaxHeight / $displayedHeightMultiple"
                                }
                            )
                        },
                        singleLine = true,
                        readOnly = fixedHeight,
                        modifier = Modifier.weight(1f).testTag("image.height")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = stepsText,
                        onValueChange = onStepsTextChange,
                        label = { Text("步数") },
                        supportingText = {
                            Text("${executionModel.imageMinSteps}-${executionModel.imageMaxSteps}")
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("image.steps")
                    )
                    OutlinedTextField(
                        value = cfgScaleText,
                        onValueChange = onCfgScaleTextChange,
                        label = { Text("CFG") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = seedText,
                        onValueChange = onSeedTextChange,
                        label = { Text("Seed") },
                        placeholder = { Text("随机") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("image.seed")
                    )
                }
                if (executionModel.isQwenImage21Gguf) {
                    val viggleIncluded = selectedLoras.any { selection ->
                        loras.any { adapter ->
                            adapter.id == selection.id && adapter.isQwenImage21ViggleV021
                        }
                    }
                    val viggleSelected = viggleIncluded && selectedLoras.size == 1 &&
                        selectedLoras.single().multiplierText.toDoubleOrNull() == 1.0
                    Text(
                        when {
                            !viggleIncluded -> "未启用 Viggle LoRA：使用基座原生步数与调度。"
                            !viggleSelected ->
                                "Viggle LoRA 已启用；仅单独选用且倍率为 1 时使用其蒸馏调度，当前使用原生调度。"
                            cfgScaleText.toDoubleOrNull() != 1.0 ->
                                "Viggle LoRA 已启用；蒸馏调度要求 CFG 1，当前使用原生调度。"
                            stepsText.toIntOrNull() == 4 ->
                                "Viggle v0.2.1 四步调度已选；四步可能减少细节与多样性，建议先用六步比较。"
                            stepsText.toIntOrNull()?.let { it in 5..8 } == true ->
                                "Viggle v0.2.1 蒸馏调度已选；六步为官方推荐，可自行调整为四至八步。"
                            else ->
                                "Viggle LoRA 已启用；当前步数使用基座原生调度。四至八步才启用蒸馏调度。"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text("采样器", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(executionModel.imageSupportedSamplersForTask(taskMode)) { method ->
                        FilterChip(
                            selected = method == sampler,
                            onClick = { onSamplerChange(method) },
                            label = { Text(method) }
                        )
                    }
                }
            }
            if (supportsNegativePrompt) {
                ImageNegativePromptTagField(
                    value = negativePrompt,
                    onValueChange = onNegativePromptChange,
                    modelId = modelId,
                    onMeasureTokens = onMeasureTokens,
                    modifier = Modifier.fillMaxWidth()
                )
                FilterChip(
                    selected = disableModelNegativePrompt,
                    onClick = { onDisableModelNegativePromptChange(!disableModelNegativePrompt) },
                    label = { Text("关闭模型默认负面词") }
                )
            }
            if (supportsLora) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "LoRA",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "图像页与聊天生图共用选择；最多 8 个，倍率不能为 0。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = onImportLora, enabled = !loraImporting) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (loraImporting) "导入中…" else "导入")
                    }
                    if (executionModel?.isQwenImage21Gguf == true) {
                        TextButton(
                            onClick = onDownloadOfficialViggleLora,
                            enabled = !loraImporting
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下载 Viggle v0.2.1")
                        }
                    }
                }
                if (loraMessage.isNotBlank()) {
                    val isError = loraMessage.contains("失败") || loraMessage.contains("无效")
                    Text(
                        loraMessage,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (loras.isEmpty()) {
                    Text(
                        "尚未导入 LoRA。支持 .safetensors 与 .ckpt 文件。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    loras.forEachIndexed { index, adapter ->
                        val selection = selectedLoraById[adapter.id]
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = selection != null,
                                    onClick = { onToggleLora(adapter.id) },
                                    enabled = selection != null || !loraSelectionLimitReached,
                                    label = {
                                        Text(
                                            when {
                                                selection != null -> "已启用"
                                                loraSelectionLimitReached -> "已达上限"
                                                else -> "启用"
                                            }
                                        )
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        adapter.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (executionModel?.isQwenImage21Gguf == true &&
                                        adapter.isQwenImage21ViggleV021
                                    ) {
                                        Text(
                                            "Viggle v0.2.1 · 推荐六步 / CFG 1",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        "${adapter.sizeText} · ${adapter.sha256.take(10)}…",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(
                                    onClick = { pendingLoraDeleteId = adapter.id },
                                    enabled = !adapter.inUse && !loraImporting
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = if (adapter.inUse) {
                                            "当前任务使用中，无法删除 ${adapter.name}"
                                        } else {
                                            "删除 ${adapter.name}"
                                        },
                                        tint = if (adapter.inUse) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                }
                            }
                            if (selection != null) {
                                val multiplier = selection.multiplierText.toDoubleOrNull()
                                val multiplierValid = multiplier != null && multiplier.isFinite() &&
                                    multiplier in -4.0..4.0 && kotlin.math.abs(multiplier) >= 0.01
                                OutlinedTextField(
                                    value = selection.multiplierText,
                                    onValueChange = { onLoraMultiplierChange(adapter.id, it) },
                                    label = { Text("倍率") },
                                    supportingText = {
                                        Text(if (multiplierValid) "范围 -4 到 4" else "请输入 [-4, -0.01] 或 [0.01, 4]")
                                    },
                                    isError = !multiplierValid,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (adapter.inUse) {
                                Text(
                                    "当前图片任务正在使用此 LoRA，任务结束后可删除。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (index != loras.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
            if (supportsTextualInversion) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Textual Inversion",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "${selectedTextualInversionIds.size.coerceAtMost(8)} / 8",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(
                        onClick = onImportTextualInversion,
                        enabled = !textualInversionImporting
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (textualInversionImporting) "导入中" else "导入")
                    }
                }
                if (textualInversionMessage.isNotBlank()) {
                    val isError = textualInversionMessage.contains("失败") ||
                        textualInversionMessage.contains("无效") ||
                        textualInversionMessage.contains("已删除")
                    Text(
                        textualInversionMessage,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (textualInversions.isEmpty()) {
                    Text(
                        "尚未导入 Textual Inversion",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    textualInversions.forEachIndexed { index, artifact ->
                        val selected = artifact.id in selectedTextualInversionIdSet
                        val compatible = artifact.compatibleWithSelectedModel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selected,
                                onClick = { onToggleTextualInversion(artifact.id) },
                                enabled = selected ||
                                    (compatible && !textualInversionSelectionLimitReached),
                                label = {
                                    Text(
                                        when {
                                            selected -> "已启用"
                                            compatible -> "启用"
                                            else -> "格式不兼容"
                                        }
                                    )
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    artifact.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "${artifact.trigger} · ${artifact.format} · ${artifact.sizeText} · ${artifact.sha256.take(10)}…",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(
                                onClick = { pendingTextualInversionDeleteId = artifact.id },
                                enabled = !artifact.inUse && !textualInversionImporting
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = if (artifact.inUse) {
                                        "当前任务使用中，无法删除 ${artifact.name}"
                                    } else {
                                        "删除 ${artifact.name}"
                                    },
                                    tint = if (artifact.inUse) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }
                        if (index != textualInversions.lastIndex) HorizontalDivider()
                    }
                }
            }
            if (supportsClipSkip || supportsVaeTiling) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (supportsClipSkip) {
                        OutlinedTextField(
                            value = clipSkipText,
                            onValueChange = onClipSkipTextChange,
                            label = { Text("CLIP skip") },
                            placeholder = { Text("模型默认") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (supportsVaeTiling) {
                        FilterChip(
                            selected = vaeTilingEnabled,
                            onClick = { onVaeTilingEnabledChange(!vaeTilingEnabled) },
                            enabled = !ultraFixEnabled,
                            label = { Text("VAE 分块") }
                        )
                    }
                }
            }
            if (supportsUltraFix && taskMode == ImageGenerationUiTaskMode.IMG2IMG) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = ultraFixEnabled,
                        onClick = { onUltraFixEnabledChange(!ultraFixEnabled) },
                        enabled = ultraFixEnabled || ultraFixInputReady,
                        label = { Text("UltraFix") }
                    )
                    if (!ultraFixEnabled && !ultraFixInputReady) {
                        Text(
                            when {
                                inputImageUri == null -> "选择源图后可启用 UltraFix"
                                inputImageWidth <= 0 || inputImageHeight <= 0 ->
                                    "源图尺寸检查完成后可启用 UltraFix"
                                else -> "源图超出当前模型的 UltraFix 目标范围，请先裁剪图片"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (ultraFixEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = ultraFixRefinementStepsText,
                                onValueChange = onUltraFixRefinementStepsTextChange,
                                label = { Text("总精修步数") },
                                supportingText = { Text("1-20") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = ultraFixInversionStepsText,
                                onValueChange = onUltraFixDenoisingStepsTextChange,
                                label = { Text("实际去噪步数") },
                                supportingText = {
                                    val maximum = ultraFixRefinementStepsText.toIntOrNull()
                                        ?.coerceIn(1, IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS)
                                        ?.let { steps ->
                                            minOf(
                                                IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                                                steps,
                                            )
                                        }
                                        ?: IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS
                                    Text("1-$maximum · strength $ultraFixStrengthText")
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = ultraFixTileSizeText,
                                onValueChange = onUltraFixTileSizeTextChange,
                                label = { Text("分块尺寸") },
                                supportingText = {
                                    Text(
                                        executionModel?.imageUltraFixRequiredTileSize
                                            ?.takeIf { it > 0 }
                                            ?.let { "图固定：$it" }
                                            ?: "128-2048 / 8"
                                    )
                                },
                                readOnly = executionModel?.imageUltraFixRequiredTileSize
                                    ?.let { it > 0 } == true,
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = ultraFixOverlapText,
                                onValueChange = onUltraFixOverlapTextChange,
                                label = { Text("重叠比例") },
                                supportingText = { Text("0-0.5") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        TextButton(
                            onClick = onResetUltraFix
                        ) {
                            Icon(
                                Icons.Default.Replay,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("恢复默认")
                        }
                    }
                }
            }
            if (supportsLivePreview) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = livePreviewEnabled,
                        onClick = { onLivePreviewEnabledChange(!livePreviewEnabled) },
                        enabled = !ultraFixEnabled,
                        label = { Text("实时预览") }
                    )
                    Text(
                        "仅影响生成过程中的预览帧，不影响最终图片。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (livePreviewEnabled) {
                        Text("预览间隔（步）", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((1..10).toList()) { interval ->
                                FilterChip(
                                    selected = interval == livePreviewInterval.coerceIn(1, 10),
                                    onClick = { onLivePreviewIntervalChange(interval) },
                                    label = { Text(interval.toString()) }
                                )
                            }
                        }
                        val showVaePreviewCostWarning =
                            shouldShowImageGenerationVaePreviewCostWarning(
                                mode = executionModel?.resolvedImagePreviewMode(),
                                enabled = livePreviewEnabled,
                                interval = livePreviewInterval
                            )
                        if (showVaePreviewCostWarning) {
                            val vaePreviewPerformanceWarning =
                                "每一步预览都会额外执行一次完整 VAE 解码，生成会明显变慢。"
                            Text(
                                vaePreviewPerformanceWarning,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        stateDescription = vaePreviewPerformanceWarning
                                    },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            if (maxBatchCount > 1) {
                Text("输出张数", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Batch is a product-level sequential coordinator feature for QNN/MNN, so
                    // expose every valid count instead of skipping 3/5/6/7.
                    items((1..maxBatchCount.coerceAtLeast(1)).toList()) { count ->
                            FilterChip(
                                selected = count == batchCount,
                                onClick = { onBatchCountChange(count) },
                                enabled = !ultraFixEnabled,
                                label = { Text(count.toString()) }
                        )
                    }
                }
            }
            Text(
                buildString {
                    append("单次输出 ")
                    append(batchCount.coerceIn(1, maxBatchCount))
                    append(" 张")
                    if (supportsVaeTiling && vaeTilingEnabled) {
                        append(" · VAE 分块 ")
                        append(executionModel?.imageDefaultVaeTileSize ?: 512)
                        append(" / 重叠 ")
                        append(executionModel?.imageDefaultVaeTileOverlap ?: 0.5)
                    }
                    if (supportsLivePreview && livePreviewEnabled) {
                        append(" · 每 ")
                        append(livePreviewInterval.coerceIn(1, 10))
                        append(" 步预览")
                    }
                    if (supportsLora && selectedLoras.isNotEmpty()) {
                        append(" · LoRA ")
                        append(selectedLoras.size)
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            }
        }
        }

        pendingLoraDeleteId?.let { adapterId ->
            val adapter = loras.firstOrNull { it.id == adapterId }
            if (adapter != null && !adapter.inUse) {
                AlertDialog(
                    onDismissRequest = { pendingLoraDeleteId = null },
                    title = { Text("删除 LoRA") },
                    text = { Text("确定删除“${adapter.name}”吗？文件会从本机移除，此操作无法撤销。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingLoraDeleteId = null
                                onDeleteLora(adapter.id)
                            }
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingLoraDeleteId = null }) { Text("取消") }
                    }
                )
            }
        }
        pendingTextualInversionDeleteId?.let { artifactId ->
            val artifact = textualInversions.firstOrNull { it.id == artifactId }
            if (artifact != null && !artifact.inUse) {
                AlertDialog(
                    onDismissRequest = { pendingTextualInversionDeleteId = null },
                    title = { Text("删除 Textual Inversion") },
                    text = { Text("确定删除“${artifact.name}”吗？文件会从本机移除，此操作无法撤销。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingTextualInversionDeleteId = null
                                onDeleteTextualInversion(artifact.id)
                            }
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingTextualInversionDeleteId = null }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
        pendingParameterImport?.let { selection ->
            ImageGenerationParameterImportDialog(
                selection = selection,
                currentModelName = executionModel?.displayName.orEmpty(),
                currentTaskMode = taskMode,
                onSelectionChange = { pendingParameterImport = it },
                onDismiss = { pendingParameterImport = null },
                onConfirm = {
                    selection.applicationOrNull(confirm = true)?.let(::applyParameterImport)
                    pendingParameterImport = null
                }
            )
        }
    }

}

// P9/S29：从 ImageWorkspaceScreens.kt 等值移出的预设字段兼容簇（available + compatible 两个字段集函数）。
// 原样逐字迁移，未做行为改动；跨区入口按协议提升 internal。
// P9/S29: private→internal（跨文件被 ImagesWorkspaceScreen 复合区引用）
internal fun availableImageGenerationPresetFields(
    preset: ImageGenerationUiPreset
): Set<ImageGenerationPresetField> = buildSet {
    val hasStructuredUltraFixPayload = preset.ultraFix != null
    add(ImageGenerationPresetField.PROMPT)
    add(ImageGenerationPresetField.NEGATIVE_PROMPT)
    if (!hasStructuredUltraFixPayload && preset.width != null && preset.height != null) {
        add(ImageGenerationPresetField.SIZE)
    }
    if (!hasStructuredUltraFixPayload && preset.steps != null) {
        add(ImageGenerationPresetField.STEPS)
    }
    if (preset.cfgScale != null) add(ImageGenerationPresetField.CFG)
    if (preset.seed != null) add(ImageGenerationPresetField.SEED)
    if (!preset.sampleMethod.isNullOrBlank()) add(ImageGenerationPresetField.SAMPLER)
    if (preset.clipSkip != null) add(ImageGenerationPresetField.CLIP_SKIP)
    if (preset.loras.isNotEmpty()) add(ImageGenerationPresetField.LORA)
    if (preset.textualInversionIds.isNotEmpty()) {
        add(ImageGenerationPresetField.TEXTUAL_INVERSION)
    }
    if (preset.ultraFix != null) add(ImageGenerationPresetField.ULTRAFIX)
    if (!hasStructuredUltraFixPayload && preset.strength != null) {
        add(ImageGenerationPresetField.STRENGTH)
    }
    if (preset.controlStrength != null) add(ImageGenerationPresetField.CONTROL_STRENGTH)
    if (!hasStructuredUltraFixPayload && preset.batchCount != null) {
        add(ImageGenerationPresetField.BATCH)
    }
    if (!hasStructuredUltraFixPayload && preset.vaeTileSize != null) {
        add(ImageGenerationPresetField.VAE_TILING)
    }
}

internal fun compatibleImageGenerationPresetFields(
    preset: ImageGenerationUiPreset,
    selectedModel: ChatModelChoice?,
    selectedModelIsCloud: Boolean,
    supportsNegativePrompt: Boolean,
    supportsClipSkip: Boolean,
    supportsVaeTiling: Boolean,
    supportsLora: Boolean,
    availableLoraIds: Set<String>,
    maxBatchCount: Int,
    currentTaskMode: ImageGenerationUiTaskMode = ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
    supportsTextualInversion: Boolean = false,
    supportsUltraFix: Boolean = false,
    availableTextualInversionIds: Set<String> = emptySet(),
): Set<ImageGenerationPresetField> {
    val available = availableImageGenerationPresetFields(preset)
    return buildSet {
        if (ImageGenerationPresetField.PROMPT in available) add(ImageGenerationPresetField.PROMPT)
        if (supportsNegativePrompt && ImageGenerationPresetField.NEGATIVE_PROMPT in available) {
            add(ImageGenerationPresetField.NEGATIVE_PROMPT)
        }
        if (ImageGenerationPresetField.STRENGTH in available &&
            preset.taskMode in setOf(
                ImageGenerationUiTaskMode.IMG2IMG,
                ImageGenerationUiTaskMode.INPAINT,
            ) &&
            currentTaskMode in setOf(
                ImageGenerationUiTaskMode.IMG2IMG,
                ImageGenerationUiTaskMode.INPAINT,
            ) &&
            preset.strength?.let { it.isFinite() && it in 0.0..1.0 } == true
        ) {
            add(ImageGenerationPresetField.STRENGTH)
        }
        if (ImageGenerationPresetField.CONTROL_STRENGTH in available &&
            preset.taskMode == ImageGenerationUiTaskMode.CONTROL &&
            currentTaskMode == ImageGenerationUiTaskMode.CONTROL &&
            preset.controlStrength?.let { it.isFinite() && it in 0.0..2.0 } == true
        ) {
            add(ImageGenerationPresetField.CONTROL_STRENGTH)
        }
        if (!selectedModelIsCloud && selectedModel != null) {
            val width = preset.width
            val height = preset.height
            val ultraFixSizeContract = supportsUltraFix &&
                currentTaskMode == ImageGenerationUiTaskMode.IMG2IMG &&
                preset.ultraFix != null
            val minWidth = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixMinWidth
            } else {
                selectedModel.imageMinWidth
            }
            val maxWidth = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixMaxWidth
            } else {
                selectedModel.imageMaxWidth
            }
            val minHeight = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixMinHeight
            } else {
                selectedModel.imageMinHeight
            }
            val maxHeight = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixMaxHeight
            } else {
                selectedModel.imageMaxHeight
            }
            val widthMultiple = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixWidthMultiple
            } else {
                selectedModel.imageWidthMultiple
            }
            val heightMultiple = if (ultraFixSizeContract) {
                selectedModel.imageUltraFixHeightMultiple
            } else {
                selectedModel.imageHeightMultiple
            }
            if (ImageGenerationPresetField.SIZE in available &&
                width != null && height != null &&
                width in minWidth..maxWidth && height in minHeight..maxHeight &&
                width % widthMultiple == 0 && height % heightMultiple == 0 &&
                (!ultraFixSizeContract ||
                    width.toLong() * height.toLong() <= 64L * 1024L * 1024L)
            ) {
                add(ImageGenerationPresetField.SIZE)
            }
            if (ImageGenerationPresetField.STEPS in available &&
                preset.steps?.let { it in selectedModel.imageMinSteps..selectedModel.imageMaxSteps } == true
            ) {
                add(ImageGenerationPresetField.STEPS)
            }
            if (ImageGenerationPresetField.CFG in available &&
                preset.cfgScale?.let { it.isFinite() && it in 0.0..30.0 } == true
            ) {
                add(ImageGenerationPresetField.CFG)
            }
            if (ImageGenerationPresetField.SEED in available && preset.seed?.let { it >= 0 } == true) {
                add(ImageGenerationPresetField.SEED)
            }
            if (ImageGenerationPresetField.SAMPLER in available &&
                preset.sampleMethod in selectedModel.imageSupportedSamplersForTask(currentTaskMode)
            ) {
                add(ImageGenerationPresetField.SAMPLER)
            }
            if (ImageGenerationPresetField.BATCH in available &&
                preset.batchCount?.let { it in 1..maxBatchCount.coerceAtLeast(1) } == true
            ) {
                add(ImageGenerationPresetField.BATCH)
            }
        }
        if (supportsClipSkip && ImageGenerationPresetField.CLIP_SKIP in available) {
            add(ImageGenerationPresetField.CLIP_SKIP)
        }
        if (supportsVaeTiling &&
            ImageGenerationPresetField.VAE_TILING in available &&
            selectedModel != null &&
            preset.vaeTileSize == selectedModel.imageDefaultVaeTileSize &&
            preset.vaeTileOverlap?.let {
                kotlin.math.abs(it - selectedModel.imageDefaultVaeTileOverlap) < 0.000_001
            } == true
        ) {
            add(ImageGenerationPresetField.VAE_TILING)
        }
        if (supportsLora &&
            ImageGenerationPresetField.LORA in available &&
            preset.loras.isNotEmpty() &&
            preset.loras.all { selection -> selection.id in availableLoraIds }
        ) {
            add(ImageGenerationPresetField.LORA)
        }
        if (supportsTextualInversion &&
            ImageGenerationPresetField.TEXTUAL_INVERSION in available &&
            preset.textualInversionIds.size <= 8 &&
            preset.textualInversionIds.distinct().size == preset.textualInversionIds.size &&
            preset.textualInversionIds.all(availableTextualInversionIds::contains)
        ) {
            add(ImageGenerationPresetField.TEXTUAL_INVERSION)
        }
        val ultraFix = preset.ultraFix
        if (!selectedModelIsCloud && selectedModel != null && supportsUltraFix &&
            currentTaskMode == ImageGenerationUiTaskMode.IMG2IMG &&
            ImageGenerationPresetField.ULTRAFIX in available && ultraFix != null &&
            ultraFix.targetWidth in selectedModel.imageUltraFixMinWidth..
                selectedModel.imageUltraFixMaxWidth &&
            ultraFix.targetHeight in selectedModel.imageUltraFixMinHeight..
                selectedModel.imageUltraFixMaxHeight &&
            ultraFix.targetWidth % selectedModel.imageUltraFixWidthMultiple == 0 &&
            ultraFix.targetHeight % selectedModel.imageUltraFixHeightMultiple == 0 &&
            ultraFix.targetWidth.toLong() * ultraFix.targetHeight.toLong() <=
                64L * 1024L * 1024L &&
            ultraFix.strength.isFinite() && ultraFix.strength > 0.0 && ultraFix.strength <= 1.0 &&
            ultraFix.refinementSteps in 1..IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS &&
            ultraFix.inversionSteps in 1..minOf(
                IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                ultraFix.refinementSteps,
            ) &&
            ultraFix.inversionSteps == imageGenerationUltraFixDenoisingTailStepCount(
                ultraFix.refinementSteps,
                ultraFix.strength,
            ) &&
            ultraFix.tileSize in 128..2048 && ultraFix.tileSize % 8 == 0 &&
            ultraFix.tileSize % selectedModel.imageUltraFixWidthMultiple == 0 &&
            ultraFix.tileSize % selectedModel.imageUltraFixHeightMultiple == 0 &&
            (selectedModel.imageUltraFixRequiredTileSize == 0 ||
                ultraFix.tileSize == selectedModel.imageUltraFixRequiredTileSize) &&
            ultraFix.tileSize <= minOf(ultraFix.targetWidth, ultraFix.targetHeight) &&
            ultraFix.overlap.isFinite() && ultraFix.overlap in 0.0..0.5
        ) {
            add(ImageGenerationPresetField.ULTRAFIX)
        }
    }
}
