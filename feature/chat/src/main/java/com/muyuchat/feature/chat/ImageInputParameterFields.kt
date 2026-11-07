package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaChip
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaTextField
import com.muyuchat.core.designsystem.McaIconButton

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
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import com.muyuchat.core.designsystem.McaChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape







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
import com.muyuchat.core.designsystem.McaIconSize

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
                        McaChip(
                            selected = field in selection.selectedFields,
                            onClick = { onSelectionChange(selection.toggle(field)) },
                            label = { Text(field.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            McaButton(style = McaButtonStyle.Text,
                onClick = onConfirm,
                enabled = selection.selectedFields.isNotEmpty()
            ) { Text("应用") }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text,onClick = onDismiss) { Text("取消") }
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
        McaButton(style = McaButtonStyle.Primary,onClick = { onPick(role) }) {
            Icon(McaIcons.PhotoLibrary, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
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
                McaIconButton(
                    contentDescription = "裁剪$label",
                    onClick = { onEdit(role) },
                    modifier = Modifier.size(48.dp),
                    glyphSize = McaIconSize.L
                ) {
                    Icon(McaIcons.Edit, contentDescription = null)
                }
            }
            McaIconButton(contentDescription = "清除$label", onClick = { onClear(role) }, glyphSize = McaIconSize.L) {
                Icon(McaIcons.Close, contentDescription = null)
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
    McaTextField(
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
