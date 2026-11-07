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

internal class ImageInputOptionsPanelDeleteState(
    private val readPendingLoraDeleteId: () -> String?,
    private val writePendingLoraDeleteId: (String?) -> Unit,
    private val readPendingTextualInversionDeleteId: () -> String?,
    private val writePendingTextualInversionDeleteId: (String?) -> Unit,
    private val readPendingParameterImport: () -> ImageGenerationParameterImportSelection?,
    private val writePendingParameterImport: (ImageGenerationParameterImportSelection?) -> Unit,
    private val applyParameterImportImpl: (ImageGenerationParameterImportApplication) -> Unit,
) {
    var pendingLoraDeleteId: String?
        get() = readPendingLoraDeleteId()
        set(value) = writePendingLoraDeleteId(value)

    var pendingTextualInversionDeleteId: String?
        get() = readPendingTextualInversionDeleteId()
        set(value) = writePendingTextualInversionDeleteId(value)

    var pendingParameterImport: ImageGenerationParameterImportSelection?
        get() = readPendingParameterImport()
        set(value) = writePendingParameterImport(value)

    fun applyParameterImport(application: ImageGenerationParameterImportApplication) {
        applyParameterImportImpl(application)
    }
}

@Composable
internal fun ImageInputAdapterOptionsPanel(
    deleteState: ImageInputOptionsPanelDeleteState,
    executionModel: ChatModelChoice?,
    supportsNegativePrompt: Boolean,
    supportsTextualInversion: Boolean,
    supportsLora: Boolean,
    loras: List<ImageLoraUiItem>,
    loraImporting: Boolean,
    loraMessage: String,
    textualInversions: List<ImageTextualInversionUiItem>,
    selectedTextualInversionIds: List<String>,
    textualInversionImporting: Boolean,
    textualInversionMessage: String,
    negativePrompt: String,
    disableModelNegativePrompt: Boolean,
    modelId: String?,
    onMeasureTokens: (suspend ( modelId: String, prompt: String, ) -> ImagePromptTokenMeasurement?)?,
    onNegativePromptChange: (String) -> Unit,
    onDisableModelNegativePromptChange: (Boolean) -> Unit,
    onToggleLora: (String) -> Unit,
    onLoraMultiplierChange: (String, String) -> Unit,
    onImportLora: () -> Unit,
    onDownloadOfficialViggleLora: () -> Unit,
    onToggleTextualInversion: (String) -> Unit,
    onImportTextualInversion: () -> Unit,
    selectedLoraById: Map<String, ImageGenerationUiLoraDraft>,
    loraSelectionLimitReached: Boolean,
    selectedTextualInversionIdSet: Set<String>,
    textualInversionSelectionLimitReached: Boolean,
) {
    with(deleteState) {
            if (supportsNegativePrompt) {
                ImageNegativePromptTagField(
                    value = negativePrompt,
                    onValueChange = onNegativePromptChange,
                    modelId = modelId,
                    onMeasureTokens = onMeasureTokens,
                    modifier = Modifier.fillMaxWidth()
                )
                McaChip(
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
                    McaButton(style = McaButtonStyle.Text,onClick = onImportLora, enabled = !loraImporting) {
                        Icon(McaIcons.Add, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (loraImporting) "导入中…" else "导入")
                    }
                    if (executionModel?.isQwenImage21Gguf == true) {
                        McaButton(style = McaButtonStyle.Text,
                            onClick = onDownloadOfficialViggleLora,
                            enabled = !loraImporting
                        ) {
                            Icon(McaIcons.Download, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
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
                                McaChip(
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
                                McaIconButton(
                                    contentDescription = if (adapter.inUse) {
                                        "当前任务使用中，无法删除 ${adapter.name}"
                                    } else {
                                        "删除 ${adapter.name}"
                                    },
                                    onClick = { pendingLoraDeleteId = adapter.id },
                                    enabled = !adapter.inUse && !loraImporting,
                                    glyphSize = McaIconSize.L,
                                    tint = MaterialTheme.colorScheme.error,
                                ) {
                                    Icon(
                                        McaIcons.Delete,
                                        contentDescription = null,
                                    )
                                }
                            }
                            if (selection != null) {
                                val multiplier = selection.multiplierText.toDoubleOrNull()
                                val multiplierValid = multiplier != null && multiplier.isFinite() &&
                                    multiplier in -4.0..4.0 && kotlin.math.abs(multiplier) >= 0.01
                                McaTextField(
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
                    McaButton(style = McaButtonStyle.Text,
                        onClick = onImportTextualInversion,
                        enabled = !textualInversionImporting
                    ) {
                        Icon(McaIcons.Add, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
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
                            McaChip(
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
                            McaIconButton(
                                contentDescription = if (artifact.inUse) {
                                    "当前任务使用中，无法删除 ${artifact.name}"
                                } else {
                                    "删除 ${artifact.name}"
                                },
                                onClick = { pendingTextualInversionDeleteId = artifact.id },
                                enabled = !artifact.inUse && !textualInversionImporting,
                                glyphSize = McaIconSize.L,
                                tint = MaterialTheme.colorScheme.error,
                            ) {
                                Icon(
                                    McaIcons.Delete,
                                    contentDescription = null,
                                )
                            }
                        }
                        if (index != textualInversions.lastIndex) HorizontalDivider()
                    }
                }
            }
    }
}

@Composable
internal fun ImageInputLivePreviewBatchPanel(
    executionModel: ChatModelChoice?,
    supportsVaeTiling: Boolean,
    supportsLora: Boolean,
    supportsLivePreview: Boolean,
    selectedLoras: List<ImageGenerationUiLoraDraft>,
    batchCount: Int,
    maxBatchCount: Int,
    vaeTilingEnabled: Boolean,
    ultraFixEnabled: Boolean,
    livePreviewEnabled: Boolean,
    livePreviewInterval: Int,
    onLivePreviewEnabledChange: (Boolean) -> Unit,
    onLivePreviewIntervalChange: (Int) -> Unit,
    onBatchCountChange: (Int) -> Unit,
) {
            if (supportsLivePreview) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    McaChip(
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
                                McaChip(
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
                            McaChip(
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

@Composable
internal fun ImageInputDeleteDialogsPanel(
    deleteState: ImageInputOptionsPanelDeleteState,
    taskMode: ImageGenerationUiTaskMode,
    executionModel: ChatModelChoice?,
    loras: List<ImageLoraUiItem>,
    textualInversions: List<ImageTextualInversionUiItem>,
    onDeleteLora: (String) -> Unit,
    onDeleteTextualInversion: (String) -> Unit,
) {
    with(deleteState) {
        pendingLoraDeleteId?.let { adapterId ->
            val adapter = loras.firstOrNull { it.id == adapterId }
            if (adapter != null && !adapter.inUse) {
                AlertDialog(
                    onDismissRequest = { pendingLoraDeleteId = null },
                    title = { Text("删除 LoRA") },
                    text = { Text("确定删除“${adapter.name}”吗？文件会从本机移除，此操作无法撤销。") },
                    confirmButton = {
                        McaButton(style = McaButtonStyle.Text,
                            onClick = {
                                pendingLoraDeleteId = null
                                onDeleteLora(adapter.id)
                            }
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        McaButton(style = McaButtonStyle.Text,onClick = { pendingLoraDeleteId = null }) { Text("取消") }
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
                        McaButton(style = McaButtonStyle.Text,
                            onClick = {
                                pendingTextualInversionDeleteId = null
                                onDeleteTextualInversion(artifact.id)
                            }
                        ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        McaButton(style = McaButtonStyle.Text,onClick = { pendingTextualInversionDeleteId = null }) {
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
