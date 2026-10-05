package com.muyuchat.feature.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S14：从 ChatChromeScreens.kt 等值移出的模型切换/推理模式簇。
// 原样逐字迁移，未做行为改动；双向跨区入口按协议提升 internal（随迁 2 项 + 保留区 2 项）。
@Composable
// P9/S14: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun ModelSwitcherDropdown(
    expanded: Boolean,
    models: List<ChatModelChoice>,
    selectedModelIsCloud: Boolean,
    isGenerating: Boolean,
    reasoningMode: ReasoningMode,
    generationParams: GenerationParams,
    onGenerationParamsChange: (GenerationParams) -> Unit,
    onDismiss: () -> Unit,
    onLoadModel: (String) -> Unit,
    onModelBackendChange: (String, String) -> Unit,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit,
    onOpenModels: () -> Unit
) {
    val context = LocalContext.current
    var reasoningExpanded by rememberSaveable { mutableStateOf(false) }
    var generationParamsDialogVisible by rememberSaveable { mutableStateOf(false) }
    var sourceExpanded by rememberSaveable { mutableStateOf<InferenceSource?>(null) }
    val localModels = models.filterNot { it.cloud }
    val cloudModels = models.filter { it.cloud }
    val activeReasoningMode = if (selectedModelIsCloud) ReasoningMode.STANDARD else reasoningMode
    LaunchedEffect(selectedModelIsCloud) {
        if (selectedModelIsCloud) reasoningExpanded = false
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(30.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        modifier = Modifier
            .widthIn(min = 238.dp, max = 320.dp)
            .clip(RoundedCornerShape(30.dp))
            .padding(vertical = 10.dp, horizontal = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 0.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "推理来源",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                InferenceSourceMenuRow(
                    source = InferenceSource.LOCAL,
                    selected = !selectedModelIsCloud,
                    expanded = sourceExpanded == InferenceSource.LOCAL,
                    count = localModels.size,
                    onClick = {
                        reasoningExpanded = false
                        sourceExpanded = if (sourceExpanded == InferenceSource.LOCAL) null else InferenceSource.LOCAL
                    }
                )
                InferenceSourceMenuRow(
                    source = InferenceSource.CLOUD,
                    selected = selectedModelIsCloud,
                    expanded = sourceExpanded == InferenceSource.CLOUD,
                    count = cloudModels.size,
                    onClick = {
                        reasoningExpanded = false
                        sourceExpanded = if (sourceExpanded == InferenceSource.CLOUD) null else InferenceSource.CLOUD
                    }
                )
                if (localModels.isEmpty() && cloudModels.isEmpty()) {
                    Text(
                        text = "暂无可用推理模型",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isGenerating) {
                    Text(
                        text = "生成中请先停止，再切换模型。",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                )
                CapsuleMenuRow(
                    leading = {
                        ThinkingModeMark(
                            mode = activeReasoningMode,
                            selected = activeReasoningMode != ReasoningMode.OFF,
                            modifier = Modifier.size(34.dp)
                        )
                    },
                    onClick = {
                        sourceExpanded = null
                        if (selectedModelIsCloud) {
                            reasoningExpanded = false
                            Toast.makeText(context, CLOUD_REASONING_LOCKED_TIP, Toast.LENGTH_SHORT).show()
                            onCloudReasoningModeLocked()
                        } else {
                            reasoningExpanded = !reasoningExpanded
                        }
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "思考模式",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 19.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (selectedModelIsCloud) "默认开启" else reasoningMode.shortLabel(),
                            color = if (activeReasoningMode == ReasoningMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.Bold
                        )
                        if (!selectedModelIsCloud) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .rotate(if (reasoningExpanded) -90f else 0f)
                                    .size(18.dp)
                            )
                        }
                    }
                }
                CapsuleMenuRow(
                    leading = {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
                    },
                    onClick = {
                        sourceExpanded = null
                        reasoningExpanded = false
                        generationParamsDialogVisible = true
                    }
                ) {
                    Column {
                        Text(
                            text = "生成参数",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "按当前模型保存采样和输出设置",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                CapsuleMenuRow(
                    leading = {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
                    },
                    onClick = onOpenModels
                ) {
                    Text(
                        text = "更多模型",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            AnimatedVisibility(
                visible = sourceExpanded != null,
                enter = fadeIn(tween(120)) + slideInHorizontally(tween(160), initialOffsetX = { -it / 4 }),
                exit = fadeOut(tween(100))
            ) {
                ModelSourceInlineCapsule(
                    source = sourceExpanded ?: InferenceSource.LOCAL,
                    models = if (sourceExpanded == InferenceSource.CLOUD) cloudModels else localModels,
                    isGenerating = isGenerating,
                    onLoadModel = { id ->
                        onLoadModel(id)
                        sourceExpanded = null
                    },
                    onModelBackendChange = onModelBackendChange,
                    onOpenModels = onOpenModels,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                )
            }
            AnimatedVisibility(
                visible = reasoningExpanded && !selectedModelIsCloud,
                enter = fadeIn(tween(120)) + slideInHorizontally(tween(160), initialOffsetX = { -it / 4 }),
                exit = fadeOut(tween(100))
            ) {
                ReasoningModeInlineCapsule(
                    selected = reasoningMode,
                    onSelect = { mode ->
                        onReasoningModeChange(mode)
                        reasoningExpanded = false
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
    if (generationParamsDialogVisible) {
        GenerationParamsQuickDialog(
            params = generationParams,
            onDismiss = { generationParamsDialogVisible = false },
            onApply = { updated ->
                onGenerationParamsChange(updated)
                generationParamsDialogVisible = false
                onDismiss()
            }
        )
    }
}

private enum class InferenceSource(
    val title: String,
    val subtitle: String
) {
    LOCAL("本地推理", "设备端模型"),
    CLOUD("云端推理", "API 推理引擎")
}

@Composable
private fun InferenceSourceMenuRow(
    source: InferenceSource,
    selected: Boolean,
    expanded: Boolean,
    count: Int,
    onClick: () -> Unit
) {
    val selectedColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    CapsuleMenuRow(
        leading = {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp))
            } else if (source == InferenceSource.CLOUD) {
                Icon(Icons.Default.NetworkWifi, contentDescription = null, tint = selectedColor, modifier = Modifier.size(21.dp))
            } else {
                McaLogoMark(size = 22.dp, cornerRadius = 7.dp)
            }
        },
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = source.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .rotate(if (expanded) -90f else 0f)
                    .size(18.dp)
            )
        }
    }
}

@Composable
private fun ModelSourceInlineCapsule(
    source: InferenceSource,
    models: List<ChatModelChoice>,
    isGenerating: Boolean,
    onLoadModel: (String) -> Unit,
    onModelBackendChange: (String, String) -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = source.title,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                fontWeight = FontWeight.Medium
            )
            if (models.isEmpty()) {
                Text(
                    text = if (source == InferenceSource.CLOUD) "暂无云端模型" else "暂无本地模型",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF9AA0A6)
                )
                ReasoningModePill(
                    label = "模型管理",
                    selected = false,
                    onClick = onOpenModels
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    models.forEach { model ->
                        ModelChoicePill(
                            model = model,
                            enabled = !isGenerating || model.loaded,
                            onClick = {
                                if (!model.loaded) onLoadModel(model.id)
                            },
                            onBackendChange = onModelBackendChange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelChoicePill(
    model: ChatModelChoice,
    enabled: Boolean,
    onClick: () -> Unit,
    onBackendChange: (String, String) -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    var customGpuDialogVisible by rememberSaveable(model.id) { mutableStateOf(false) }
    var customGpuLayersText by rememberSaveable(model.id, model.customGpuLayers) {
        mutableStateOf((model.customGpuLayers ?: 32).toString())
    }
    var customGpuLayerError by rememberSaveable(model.id) { mutableStateOf<String?>(null) }
    if (customGpuDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                customGpuDialogVisible = false
                customGpuLayerError = null
            },
            title = { Text("自定义 GPU 层数") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "仅对当前 GGUF 模型生效。数值越大，尝试放到 GPU 的层越多；0 表示 CPU。" +
                            "设备或显存不足时，加载会给出具体错误，不会静默假装使用 GPU。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = customGpuLayersText,
                        onValueChange = {
                            customGpuLayersText = it.filter(Char::isDigit).take(4)
                            customGpuLayerError = null
                        },
                        label = { Text("GPU 层数（0–4096）") },
                        singleLine = true,
                        isError = customGpuLayerError != null,
                        supportingText = customGpuLayerError?.let { error -> { Text(error) } }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val layers = customGpuLayersText.toIntOrNull()
                    if (layers == null || layers !in 0..4096) {
                        customGpuLayerError = "请输入 0 到 4096 之间的整数。"
                    } else {
                        onBackendChange(model.id, "custom:$layers")
                        customGpuDialogVisible = false
                        customGpuLayerError = null
                    }
                }) { Text("保存并加载") }
            },
            dismissButton = {
                TextButton(onClick = {
                    customGpuDialogVisible = false
                    customGpuLayerError = null
                }) { Text("取消") }
            }
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (model.loaded) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (model.loaded) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = selectedColor)
            } else {
                Spacer(modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = displayModelName(model.displayName) ?: model.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.loaded) selectedColor else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 16.sp),
                    fontWeight = if (model.loaded) FontWeight.SemiBold else FontWeight.Medium
                )
                Text(
                    text = buildList {
                        if (model.loaded) add("当前")
                        if (model.cloud) {
                            if (model.subtitle.isNotBlank()) add(model.subtitle)
                        } else {
                            if (model.subtitle.isNotBlank()) add(model.subtitle)
                            model.quant?.let(::add)
                            formatModelBytes(model.sizeBytes)?.let(::add)
                        }
                    }.joinToString(" · ").ifBlank { if (model.cloud) "云端模型" else "本地推理" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.loaded) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    fontWeight = if (model.loaded) FontWeight.Medium else FontWeight.Normal
                )
                if (model.backendOptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        model.backendOptions.forEach { option ->
                            FilterChip(
                                selected = model.selectedBackendId == option.id,
                                onClick = {
                                    if (option.id == "custom") {
                                        customGpuLayersText = (model.customGpuLayers ?: 32).toString()
                                        customGpuDialogVisible = true
                                    } else {
                                        onBackendChange(model.id, option.id)
                                    }
                                },
                                enabled = option.enabled && enabled,
                                label = {
                                    Text(
                                        option.label,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp)
                                    )
                                },
                                modifier = Modifier.heightIn(min = 28.dp)
                            )
                        }
                    }
                    model.backendOptions
                        .firstOrNull { it.id == model.selectedBackendId }
                        ?.availabilityNote
                        ?.takeIf(String::isNotBlank)
                        ?.let { note ->
                            Text(
                                text = note,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp)
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun ThinkingModeMark(
    mode: ReasoningMode,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    Surface(
        modifier = modifier,
        color = if (selected) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFFF1F3F4)
        },
        shape = CircleShape
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                tint = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            if (mode == ReasoningMode.ADVANCED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 5.dp)
                        .size(5.dp)
                        .background(selectedColor, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun ReasoningModeInlineCapsule(
    selected: ReasoningMode,
    onSelect: (ReasoningMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        color = if (darkTheme) MaterialTheme.colorScheme.surface else Color.White,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = "思考模式",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                color = if (darkTheme) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF80868B),
                fontWeight = FontWeight.Medium
            )
            ReasoningMode.entries.forEach { mode ->
                ReasoningModePill(
                    label = mode.shortLabel(),
                    selected = selected == mode,
                    onClick = { onSelect(mode) }
                )
            }
        }
    }
}

@Composable
private fun CapsuleMenuRow(
    leading: @Composable () -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(38.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            leading()
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}
@Composable
// P9/S13: private→internal（跨区入口：迁移的输入区复合区引用）
internal fun ReasoningModePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable(onClick = onClick),
        color = if (selected) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            Color.Transparent
        },
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp), tint = selectedColor)
            } else {
                Spacer(modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = if (selected) selectedColor else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

// P9/S14: private→internal（跨文件被 ChatChromeScreens 复合区引用）
internal fun ReasoningMode.shortLabel(): String = when (this) {
    ReasoningMode.OFF -> "关闭"
    ReasoningMode.STANDARD -> "标准"
    ReasoningMode.ADVANCED -> "进阶"
}
