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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaChip
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.modelstore.ModelManifest

/**
 * Standalone preset management page opened from the app menu.
 *
 * The page owns no persistence of its own: import, delete and binding writes stay with the
 * injected callbacks so the chat/model-hub presets tab and this page read the same records.
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PresetManagerHeader(onImportPreset = onImportPreset, onBack = onBack)
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
internal fun GenerationPresetManagerSection(
    state: ModelHubUiState,
    onImportPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onSaveBindings: (String, String, Set<String>, Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PresetManagerHeader(onImportPreset = onImportPreset)
        PresetManagerList(
            presets = state.generationPresets,
            localModels = state.localModels,
            cloudChatModels = state.cloudApi.connectedModels.filter { it.kind == "CHAT" },
            onDeletePreset = onDeletePreset,
            onSaveBindings = onSaveBindings,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PresetManagerHeader(
    onImportPreset: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onImportPreset(it.toString()) }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onBack != null) {
                McaIconButton(contentDescription = "返回聊天", onClick = onBack) {
                    Icon(McaIcons.Back, contentDescription = null)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("预设管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "导入酒馆预设，选择绑定的本地/云端对话模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            McaButton(style = McaButtonStyle.Outlined, onClick = { picker.launch(arrayOf("*/*")) }) {
                Text("导入预设")
            }
        }
        Text(
            "预设只保存采样与输出参数；提示词、模板和宏请继续在角色卡中导入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

    Column(modifier = modifier) {
        if (sortedPresets.isEmpty()) {
            Text(
                "暂无模型预设。导入一个酒馆预设后，可在这里选择它绑定的模型。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sortedPresets, key = { it.id }) { preset ->
                    val boundNames = preset.boundLocalModelIds.mapNotNull { localById[it]?.displayName } +
                        preset.boundCloudModelIds.mapNotNull { cloudById[it]?.displayName }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(preset.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    preset.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            McaButton(style = McaButtonStyle.Text, onClick = { editingPreset = preset }) {
                                Text("管理绑定")
                            }
                            McaIconButton(
                                contentDescription = "删除预设 ${preset.name}",
                                onClick = { pendingDelete = preset }
                            ) {
                                Icon(McaIcons.Delete, contentDescription = null)
                            }
                        }
                        Text(
                            if (boundNames.isEmpty()) {
                                "尚未绑定模型"
                            } else {
                                "已绑定：${boundNames.joinToString("、")}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

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
        McaDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除预设", fontWeight = FontWeight.SemiBold) },
            text = {
                Text("确定删除「${preset.name}」？已应用到模型的参数不会自动回退。")
            },
            confirmButton = {
                McaButton(
                    style = McaButtonStyle.Destructive,
                    onClick = {
                        onDeletePreset(preset.id)
                        pendingDelete = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                McaButton(style = McaButtonStyle.Text, onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
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
        title = { Text("绑定模型 · ${preset.name}", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = { Text("预设名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("本地模型（已下载）", style = MaterialTheme.typography.labelLarge)
                if (localModels.isEmpty()) {
                    Text("暂无已下载的本地模型。", style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        localModels.forEach { model ->
                            McaChip(
                                selected = model.id in localSelection,
                                onClick = {
                                    localSelection = if (model.id in localSelection) {
                                        localSelection - model.id
                                    } else {
                                        localSelection + model.id
                                    }
                                },
                                label = { Text(model.displayName) }
                            )
                        }
                    }
                }
                Text("云端对话模型", style = MaterialTheme.typography.labelLarge)
                if (cloudModels.isEmpty()) {
                    Text("暂无已配置的云端对话模型。", style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        cloudModels.forEach { model ->
                            McaChip(
                                selected = model.id in cloudSelection,
                                onClick = {
                                    cloudSelection = if (model.id in cloudSelection) {
                                        cloudSelection - model.id
                                    } else {
                                        cloudSelection + model.id
                                    }
                                },
                                label = { Text(model.displayName.ifBlank { model.modelName }) }
                            )
                        }
                    }
                }
                Text(
                    "保存后会把预设采样参数应用到选中的模型（角色卡覆盖仍然优先）。取消不会改动模型参数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            McaButton(style = McaButtonStyle.Primary, onClick = { onSave(name, localSelection, cloudSelection) }) {
                Text("保存并应用")
            }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text, onClick = onDismiss) { Text("取消") }
        }
    )
}
