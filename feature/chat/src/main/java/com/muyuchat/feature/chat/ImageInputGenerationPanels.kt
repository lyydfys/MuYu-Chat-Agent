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

@Composable
internal fun ImageInputTaskModePanel(
    taskMode: ImageGenerationUiTaskMode,
    selectedModelIsCloud: Boolean,
    supportedTaskModes: Set<ImageGenerationUiTaskMode>,
    supportsLora: Boolean,
    loraRestoreWarning: String?,
    onAcceptLoraRestore: () -> Unit,
    inputImageUri: String?,
    maskImageUri: String?,
    controlImageUri: String?,
    inputRestoreWarning: String?,
    strengthText: String,
    controlStrengthText: String,
    clipSkipText: String,
    vaeTilingEnabled: Boolean,
    onTaskModeChange: (ImageGenerationUiTaskMode) -> Unit,
    onPickImageRole: (String) -> Unit,
    onCropImageRole: (String) -> Unit,
    onDrawImageMask: () -> Unit,
    onClearImageRole: (String) -> Unit,
    onStrengthTextChange: (String) -> Unit,
    onControlStrengthTextChange: (String) -> Unit,
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
}

@Composable
internal fun ImageInputGenerationParametersPanel(
    taskMode: ImageGenerationUiTaskMode,
    selectedModelIsCloud: Boolean,
    executionModel: ChatModelChoice?,
    loras: List<ImageLoraUiItem>,
    selectedLoras: List<ImageGenerationUiLoraDraft>,
    ultraFixEnabled: Boolean,
    widthText: String,
    heightText: String,
    stepsText: String,
    cfgScaleText: String,
    seedText: String,
    sampler: String,
    onWidthTextChange: (String) -> Unit,
    onHeightTextChange: (String) -> Unit,
    onStepsTextChange: (String) -> Unit,
    onCfgScaleTextChange: (String) -> Unit,
    onSeedTextChange: (String) -> Unit,
    onSamplerChange: (String) -> Unit,
) {
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
}

@Composable
internal fun ImageInputUltraFixPanel(
    taskMode: ImageGenerationUiTaskMode,
    executionModel: ChatModelChoice?,
    supportsClipSkip: Boolean,
    supportsVaeTiling: Boolean,
    supportsUltraFix: Boolean,
    inputImageUri: String?,
    inputImageWidth: Int,
    inputImageHeight: Int,
    clipSkipText: String,
    vaeTilingEnabled: Boolean,
    ultraFixEnabled: Boolean,
    ultraFixStrengthText: String,
    ultraFixInversionStepsText: String,
    ultraFixRefinementStepsText: String,
    ultraFixTileSizeText: String,
    ultraFixOverlapText: String,
    onClipSkipTextChange: (String) -> Unit,
    onVaeTilingEnabledChange: (Boolean) -> Unit,
    onUltraFixEnabledChange: (Boolean) -> Boolean,
    onResetUltraFix: () -> Unit,
    onUltraFixDenoisingStepsTextChange: (String) -> Unit,
    onUltraFixRefinementStepsTextChange: (String) -> Unit,
    onUltraFixTileSizeTextChange: (String) -> Unit,
    onUltraFixOverlapTextChange: (String) -> Unit,
    ultraFixInputReady: Boolean,
) {
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
}
