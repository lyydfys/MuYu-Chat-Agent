@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest

private enum class LocalModelPendingAction {
    UNLOAD,
    DELETE
}

private data class PendingLocalModelAction(
    val modelId: String,
    val action: LocalModelPendingAction,
    val statusAtStart: String?
)

@Composable
internal fun LocalModelsSection(
    state: ModelHubUiState,
    onImportClick: () -> Unit,
    onPauseImport: (String) -> Unit,
    onResumeImport: (String) -> Unit,
    onLoad: (ModelManifest) -> Unit,
    onUnload: (ModelManifest) -> Unit,
    onVerify: (ModelManifest) -> Unit,
    onDelete: (ModelManifest) -> Unit,
    onAttachVisionProjector: (ModelManifest) -> Unit,
    onImportLocalImageModel: () -> Unit,
    onSelectLocalImageModel: (String) -> Unit,
    onVerifyLocalImageModel: (String) -> Unit,
    onDeleteLocalImageModel: (String) -> Unit,
    onRemoveLocalImageModelRecord: (String) -> Unit,
    modifier: Modifier
) {
    var imageOnly by rememberSaveable { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingLocalModelAction?>(null) }
    var pendingObservedBusy by remember { mutableStateOf(false) }
    var localActionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(
        state.isBusy,
        state.loadedModelId,
        state.localModels,
        state.statusMessage,
        pendingAction
    ) {
        val pending = pendingAction ?: return@LaunchedEffect
        if (state.isBusy) pendingObservedBusy = true
        val actionCompleted = when (pending.action) {
            LocalModelPendingAction.UNLOAD -> state.loadedModelId != pending.modelId
            LocalModelPendingAction.DELETE -> state.localModels.none { it.id == pending.modelId }
        }
        val statusCompleted = state.statusMessage != pending.statusAtStart && !state.isBusy
        val busyCompleted = pendingObservedBusy && !state.isBusy
        if (actionCompleted || statusCompleted || busyCompleted) {
            pendingAction = null
            pendingObservedBusy = false
        }
    }

    fun submitLocalModelAction(
        model: ModelManifest,
        action: LocalModelPendingAction,
        callback: () -> Unit
    ) {
        if (state.isBusy || pendingAction != null) return
        localActionError = null
        pendingObservedBusy = false
        pendingAction = PendingLocalModelAction(
            modelId = model.id,
            action = action,
            statusAtStart = state.statusMessage
        )
        runCatching(callback).onFailure { error ->
            pendingAction = null
            localActionError = "操作失败：${error.message ?: error::class.java.simpleName}"
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !imageOnly, onClick = { imageOnly = false }, label = { Text("聊天 (${state.localModels.size})") })
            FilterChip(selected = imageOnly, onClick = { imageOnly = true }, label = { Text("生图 (${state.localImageModels.size})") })
        }
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.importTasks, key = { "import-${it.id}" }) { task ->
            ModelImportProgressCard(task, onPauseImport, onResumeImport)
        }
        if (!imageOnly) {
            item {
                CardBox {
                Text("本地推理引擎", fontWeight = FontWeight.Bold)
                Text("高速引擎优先使用 MNN；兼容引擎继续支持 GGUF / llama.cpp 生态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                localActionError?.let { StatusMessageCard(message = it, isError = true) }
                Button(
                    onClick = onImportClick,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("+ 导入 GGUF / LiteRT-LM / MNN 本地模型", fontWeight = FontWeight.Bold)
                }
                    if (state.localModels.isEmpty()) Text("还没有本地推理引擎，可从推荐页下载或导入完整模型。")
                }
            }
            items(state.localModels, key = { "chat-${it.id}-${it.path}" }) { model ->
                        LocalModelCard(
                            model = model,
                            isLoaded = localChatModelCardIsLoaded(state, model.id),
                            mnnRuntimeAvailable = state.mnnRuntimeAvailable,
                            enabled = !state.isBusy && pendingAction == null,
                            pendingAction = pendingAction
                                ?.takeIf { it.modelId == model.id }
                                ?.action,
                            onLoad = { onLoad(model) },
                            onUnload = {
                                submitLocalModelAction(model, LocalModelPendingAction.UNLOAD) {
                                    onUnload(model)
                                }
                            },
                            onVerify = { onVerify(model) },
                            onDelete = {
                                submitLocalModelAction(model, LocalModelPendingAction.DELETE) {
                                    onDelete(model)
                                }
                            },
                            onAttachVisionProjector = { onAttachVisionProjector(model) }
                        )
            }
        } else {
            item {
                CardBox {
                    Text("图像生成引擎", fontWeight = FontWeight.Bold)
                    Text("本地文生图模型独立管理，图片页会使用选中的引擎")
                    if (state.localImageModels.isEmpty()) Text("还没有本地图像生成引擎，可下载推荐包或导入完整引擎包。")
                OutlinedButton(
                    onClick = onImportLocalImageModel,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("+ 导入本地生图引擎包", fontWeight = FontWeight.Bold)
                }
                }
            }
            items(state.localImageModels, key = { "image-${it.id}-${it.storagePath}" }) { model ->
                        LocalImageModelCard(
                            model = model,
                            enabled = !state.isBusy,
                            onSelect = { onSelectLocalImageModel(model.id) },
                            onVerify = { onVerifyLocalImageModel(model.id) },
                            onDelete = { onDeleteLocalImageModel(model.id) },
                            onRemoveRecord = { onRemoveLocalImageModelRecord(model.id) }
                        )
            }
        }
    }
    }
}

internal fun localModelDeleteActionLabel(displayName: String): String =
    "删除本地模型：${displayName.trim().ifBlank { "未命名模型" }}"

internal fun localImageModelDeleteActionLabel(displayName: String): String =
    "删除本地图像生成引擎：${displayName.trim().ifBlank { "未命名模型" }}"

@Composable
private fun LocalModelCard(
    model: ModelManifest,
    isLoaded: Boolean,
    mnnRuntimeAvailable: Boolean,
    enabled: Boolean,
    pendingAction: LocalModelPendingAction?,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
    onAttachVisionProjector: () -> Unit
) {
    var confirmDelete by rememberSaveable(model.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val isMnnRuntime = model.runtime == ChatModelRuntime.MNN
            val isQairtRuntime = model.runtime == ChatModelRuntime.GENIEX_QAIRT
            val canLoadRuntime = !isMnnRuntime || mnnRuntimeAvailable
            val canNormalLoad = canLoadRuntime
            ModelStoragePath(model.path)
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shortName(model.displayName),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isLoaded) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(999.dp)) {
                        Text(
                            "已加载",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Text(
                "${model.runtime.label} · ${model.architecture ?: "未知架构"} · ${model.quant ?: "未知量化"} · ${formatBytes(model.sizeBytes)} · ${sourceLabel(model.source.name)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "已保存到 MCA 的本地模型目录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                when {
                    isMnnRuntime -> "MNN · CPU / OpenCL GPU"
                    isQairtRuntime -> "GenieX QAIRT · Qualcomm NPU"
                    model.runtime == ChatModelRuntime.LITERT_LM -> "LiteRT-LM · CPU / GPU / Qualcomm NPU，需对应后端的模型文件"
                    else -> "GGUF · llama.cpp"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                localVisionStatusText(model, isLoaded),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (model.hasVisionProjector) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (isMnnRuntime && !mnnRuntimeAvailable) {
                Text(
                    "当前 APK 未启用 MNN-LLM executor。请先使用 GGUF 兼容模型，或打包官方 MNN runtime 后再加载 MNN 模型。",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (isMnnRuntime) {
                Text(
                    "推荐模型优先走 MNN 高速引擎；GGUF / llama.cpp 继续作为兼容生态补充。",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (pendingAction != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = when (pendingAction) {
                        LocalModelPendingAction.UNLOAD -> "正在卸载 ${shortName(model.displayName)}…"
                        LocalModelPendingAction.DELETE -> if (isLoaded) {
                            "正在卸载并删除 ${shortName(model.displayName)}…"
                        } else {
                            "正在删除 ${shortName(model.displayName)}…"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (isLoaded) onUnload() else onLoad() },
                    enabled = enabled && (isLoaded || canNormalLoad),
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = if (isLoaded) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    if (isLoaded) {
                        Text("卸载", maxLines = 1, softWrap = false)
                    } else if (!canLoadRuntime) {
                        Text("引擎未启用", maxLines = 1, softWrap = false)
                    } else {
                        Text("加载", maxLines = 1, softWrap = false)
                    }
                }
                OutlinedButton(
                    onClick = onVerify,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isQairtRuntime) "重新诊断" else "校验", maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = onAttachVisionProjector,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (model.hasVisionProjector) "更换" else "绑定", maxLines = 1, softWrap = false)
                }
                IconButton(onClick = { confirmDelete = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = localModelDeleteActionLabel(model.displayName))
                }
            }
        }
    }
    if (confirmDelete) {
        LocalModelDeleteDialog(
            model = model,
            isLoaded = isLoaded,
            enabled = enabled,
            onDismiss = { confirmDelete = false },
            onUnload = {
                confirmDelete = false
                onUnload()
            },
            onDelete = {
                confirmDelete = false
                onDelete()
            }
        )
    }
}

@Composable
private fun LocalModelDeleteDialog(
    model: ModelManifest,
    isLoaded: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = {
            Text(
                text = if (isLoaded) "卸载或删除模型" else "删除本地模型",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                if (isLoaded) {
                    "「${shortName(model.displayName)}」正在运行。可以只释放当前运行时，也可以在安全卸载后一并删除本地模型文件。"
                } else {
                    "确定删除「${shortName(model.displayName)}」吗？这会移除 MCA 中的模型记录和本地模型文件，且无法撤销。"
                }
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isLoaded) {
                    TextButton(onClick = onUnload, enabled = enabled) {
                        Text("仅卸载")
                    }
                }
                TextButton(
                    onClick = onDelete,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isLoaded) "卸载并删除" else "删除")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun LocalImageModelCard(
    model: LocalImageModelUiItem,
    enabled: Boolean,
    onSelect: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
    onRemoveRecord: () -> Unit
) {
    var confirmRemoval by remember { mutableStateOf(false) }
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("移除本地图像模型") },
            text = { Text(model.displayName) },
            confirmButton = {
                TextButton(onClick = { confirmRemoval = false; onRemoveRecord() }) {
                    Text("仅移除记录")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmRemoval = false; onDelete() }) {
                        Text("删除文件")
                    }
                    TextButton(onClick = { confirmRemoval = false }) { Text("取消") }
                }
            }
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.displayName,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (model.selected) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(999.dp)) {
                        Text("当前", modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(
                "${model.familyLabel} · ${model.runtimeLabel} · ${model.imageSize} · ${formatBytes(model.sizeBytes)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                buildString {
                    append(model.fileName)
                    if (model.componentCount > 1) append(" · ").append(model.componentCount).append(" 个组件")
                    if (!model.readyForGeneration) append(" · ").append(model.readinessLabel.ifBlank { "不可用" })
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ModelStoragePath(model.storagePath)
            if (!model.readyForGeneration) {
                Text(
                    model.readinessMessage ?: "缺少本地生图组件包。",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                localImageExecutionLabel(model.runtimeLabel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSelect,
                    enabled = enabled && !model.selected && model.readyForGeneration,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            model.selected -> "当前"
                            !model.readyForGeneration -> model.readinessLabel.ifBlank { "不可用" }
                            else -> "设为生图"
                        },
                        maxLines = 1
                    )
                }
                OutlinedButton(
                    onClick = onVerify,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("校验", maxLines = 1)
                }
                IconButton(onClick = { confirmRemoval = true }, enabled = enabled) {
                    Icon(Icons.Default.Delete, contentDescription = localImageModelDeleteActionLabel(model.displayName))
                }
            }
        }
    }
}

private fun localImageExecutionLabel(runtimeLabel: String): String {
    val lower = runtimeLabel.lowercase()
    return when {
        "qnn" in lower || "npu" in lower || "htp" in lower -> "骁龙 NPU 生图"
        "mnn" in lower -> "MNN 生图"
        "onnx" in lower -> "ONNX 生图"
        else -> "CPU 生图"
    }
}
