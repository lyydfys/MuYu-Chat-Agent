package com.muyuchat.feature.modelhub

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonSize
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaChip
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaHairline
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaIconPlate
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaPlateSize
import com.muyuchat.core.designsystem.McaShape
import com.muyuchat.core.modelstore.ModelManifest

/**
 * Standalone preset management page opened from the app menu.
 *
 * The page owns no persistence of its own: import, delete and binding writes stay with the
 * injected callbacks so this page and the chat generation panel read the same records.
 *
 * History: model management used to carry a second `GenerationPresetManagerSection` entry point
 * into this same data. It duplicated the app-menu page and was removed; the file name is kept
 * because `BatchImportContractTest` pins this path as the multi-select preset picker.
 */
@Composable
fun PresetManagerScreen(
    presets: List<GenerationPresetUiItem>,
    localModels: List<ModelManifest>,
    cloudChatModels: List<CloudModelUiItem>,
    onImportPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onSaveBindings: (String, String, Set<String>, Set<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Receives every selected file from one multi-select picker round-trip. */
    onImportPresets: (List<String>) -> Unit = { uris -> uris.forEach(onImportPreset) }
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PresetManagerHeader(onImportPresets = onImportPresets, onBack = onBack)
        PresetManagerList(
            presets = presets,
            localModels = localModels,
            cloudChatModels = cloudChatModels,
            onDeletePreset = onDeletePreset,
            onSaveBindings = onSaveBindings,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PresetManagerHeader(
    onImportPresets: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    // Multi-select: importing five presets used to mean five picker round-trips. The launcher now
    // returns every selected file and the caller imports them in one store write.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val targets = uris.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
        if (targets.isNotEmpty()) onImportPresets(targets)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (onBack != null) {
                McaIconButton(contentDescription = "返回聊天", onClick = onBack) {
                    Icon(McaIcons.Back, contentDescription = null)
                }
            }
            PresetHeaderTitle(modifier = Modifier.weight(1f))
            PresetImportButton(onClick = { picker.launch(arrayOf("*/*")) })
        }
        Text(
            text = "可一次选择多个预设文件；提示词、模板和宏请继续在角色卡中导入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PresetHeaderTitle(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "预设管理",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "预设只保存采样与输出参数",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PresetImportButton(onClick: () -> Unit) {
    McaButton(
        style = McaButtonStyle.Primary,
        onClick = onClick,
        leadingIcon = {
            Icon(
                McaIcons.UploadFile,
                contentDescription = null,
                modifier = Modifier.size(McaIconSize.Xs)
            )
        }
    ) {
        Text("批量导入")
    }
}

@Composable
private fun PresetManagerList(
    presets: List<GenerationPresetUiItem>,
    localModels: List<ModelManifest>,
    cloudChatModels: List<CloudModelUiItem>,
    onDeletePreset: (String) -> Unit,
    onSaveBindings: (String, String, Set<String>, Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingPreset by remember { mutableStateOf<GenerationPresetUiItem?>(null) }
    var pendingDelete by remember { mutableStateOf<GenerationPresetUiItem?>(null) }
    val localById = remember(localModels) { localModels.associateBy { it.id } }
    val cloudById = remember(cloudChatModels) { cloudChatModels.associateBy { it.id } }
    val sortedPresets = remember(presets) { presets.sortedByDescending { it.updatedAt } }

    PresetManagerListContent(
        presets = sortedPresets,
        localById = localById,
        cloudById = cloudById,
        onManageBindings = { editingPreset = it },
        onDeleteRequest = { pendingDelete = it },
        modifier = modifier
    )

    editingPreset?.let { preset ->
        GenerationPresetBindingDialog(
            preset = preset,
            localModels = localModels,
            cloudModels = cloudChatModels,
            onDismiss = { editingPreset = null },
            onSave = { name, localIds, cloudIds ->
                onSaveBindings(preset.id, name, localIds, cloudIds)
                editingPreset = null
            }
        )
    }

    pendingDelete?.let { preset ->
        PresetDeleteDialog(
            preset = preset,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                onDeletePreset(preset.id)
                pendingDelete = null
            }
        )
    }
}

@Composable
private fun PresetManagerListContent(
    presets: List<GenerationPresetUiItem>,
    localById: Map<String, ModelManifest>,
    cloudById: Map<String, CloudModelUiItem>,
    onManageBindings: (GenerationPresetUiItem) -> Unit,
    onDeleteRequest: (GenerationPresetUiItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (presets.isEmpty()) {
            item(key = "preset-empty") { PresetManagerEmptyState() }
        } else {
            item(key = "preset-count") { PresetListHeader(count = presets.size) }
            items(presets, key = { it.id }) { preset ->
                val boundNames = preset.boundLocalModelIds.mapNotNull { localById[it]?.displayName } +
                    preset.boundCloudModelIds.mapNotNull { cloudById[it]?.displayName }
                PresetManagerCard(
                    preset = preset,
                    boundNames = boundNames,
                    onManageBindings = { onManageBindings(preset) },
                    onDelete = { onDeleteRequest(preset) }
                )
            }
        }
    }
}

@Composable
private fun PresetListHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "已保存的预设",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$count 个",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PresetManagerEmptyState(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            McaIconPlate(size = McaPlateSize.Large, shape = McaShape.Badge) {
                Icon(McaIcons.UploadFile, contentDescription = null, modifier = Modifier.size(McaIconSize.M))
            }
            Text(
                text = "还没有预设",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "点击右上角「批量导入」，一次可以选择多个酒馆预设文件；导入后即可在这里管理绑定的本地 / 云端模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PresetManagerCard(
    preset: GenerationPresetUiItem,
    boundNames: List<String>,
    onManageBindings: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val updatedLabel = remember(preset.updatedAt) { presetUpdatedAtLabel(preset.updatedAt) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PresetCardIdentity(
                preset = preset,
                boundNames = boundNames,
                updatedLabel = updatedLabel
            )
            PresetParameterSummaryRow(summary = preset.summary)
            PresetCardActions(
                presetName = preset.name,
                onManageBindings = onManageBindings,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun PresetCardIdentity(
    preset: GenerationPresetUiItem,
    boundNames: List<String>,
    updatedLabel: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = preset.name.ifBlank { "未命名预设" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = presetBoundModelsLabel(boundNames),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (updatedLabel.isNotBlank()) {
            Text(
                text = updatedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun PresetCardActions(
    presetName: String,
    onManageBindings: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(color = McaHairline.subtle(), thickness = McaHairline.Width)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            McaButton(
                style = McaButtonStyle.Text,
                size = McaButtonSize.CardAction,
                onClick = onManageBindings,
                modifier = Modifier.semantics { contentDescription = "管理绑定 $presetName" }
            ) {
                Text("管理绑定")
            }
            McaButton(
                style = McaButtonStyle.Text,
                size = McaButtonSize.CardAction,
                onClick = onDelete,
                contentColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = "删除预设 $presetName" }
            ) {
                Text("删除")
            }
        }
    }
}

@Composable
private fun PresetDeleteDialog(
    preset: GenerationPresetUiItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    McaDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "删除预设",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text("确定删除「${preset.name}」？已应用到模型的参数不会自动回退。")
        },
        confirmButton = {
            McaButton(style = McaButtonStyle.Destructive, onClick = onConfirm) {
                Text("删除")
            }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text, onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun GenerationPresetBindingDialog(
    preset: GenerationPresetUiItem,
    localModels: List<ModelManifest>,
    cloudModels: List<CloudModelUiItem>,
    onDismiss: () -> Unit,
    onSave: (String, Set<String>, Set<String>) -> Unit
) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var localSelection by remember(preset.id) { mutableStateOf(preset.boundLocalModelIds) }
    var cloudSelection by remember(preset.id) { mutableStateOf(preset.boundCloudModelIds) }

    McaDialog(
        onDismissRequest = onDismiss,
        title = { PresetBindingDialogTitle(presetName = preset.name) },
        text = {
            PresetBindingDialogContent(
                name = name,
                onNameChange = { name = it.take(64) },
                localModels = localModels,
                cloudModels = cloudModels,
                localSelection = localSelection,
                cloudSelection = cloudSelection,
                onToggleLocal = { model -> localSelection = localSelection.toggle(model.id) },
                onToggleCloud = { model -> cloudSelection = cloudSelection.toggle(model.id) }
            )
        },
        confirmButton = {
            McaButton(
                style = McaButtonStyle.Primary,
                onClick = { onSave(name, localSelection, cloudSelection) }
            ) {
                Text("保存并应用")
            }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text, onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun PresetBindingDialogTitle(presetName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "绑定模型",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = presetName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PresetBindingDialogContent(
    name: String,
    onNameChange: (String) -> Unit,
    localModels: List<ModelManifest>,
    cloudModels: List<CloudModelUiItem>,
    localSelection: Set<String>,
    cloudSelection: Set<String>,
    onToggleLocal: (ModelManifest) -> Unit,
    onToggleCloud: (CloudModelUiItem) -> Unit
) {
    Column(
        modifier = Modifier
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("预设名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        PresetBindingChipSection(
            title = "本地模型（已下载）",
            models = localModels,
            emptyHint = "暂无已下载的本地模型。",
            label = { it.displayName },
            selected = { it.id in localSelection },
            onToggle = onToggleLocal
        )
        PresetBindingChipSection(
            title = "云端对话模型",
            models = cloudModels,
            emptyHint = "暂无已配置的云端对话模型。",
            label = { it.displayName.ifBlank { it.modelName } },
            selected = { it.id in cloudSelection },
            onToggle = onToggleCloud
        )
        Text(
            text = "保存后会把预设采样参数应用到选中的模型（角色卡覆盖仍然优先）。取消不会改动模型参数。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun <T> PresetBindingChipSection(
    title: String,
    models: List<T>,
    emptyHint: String,
    label: (T) -> String,
    selected: (T) -> Boolean,
    onToggle: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (models.isEmpty()) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                models.forEach { model ->
                    McaChip(
                        selected = selected(model),
                        onClick = { onToggle(model) },
                        label = { Text(label(model)) }
                    )
                }
            }
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id
