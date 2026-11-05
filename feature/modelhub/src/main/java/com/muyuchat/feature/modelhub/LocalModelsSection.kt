@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import com.muyuchat.core.designsystem.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.designsystem.McaIconSize

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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            McaChip(selected = !imageOnly, onClick = { imageOnly = false }, label = { Text("聊天 (${state.localModels.size})") })
            McaChip(selected = imageOnly, onClick = { imageOnly = true }, label = { Text("生图 (${state.localImageModels.size})") })
        }
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.importTasks, key = { "import-${it.id}" }) { task ->
            ModelImportProgressCard(
                task = task,
                onPause = onPauseImport,
                onResume = onResumeImport,
                modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
            )
        }
        if (!imageOnly) {
            item {
                CardBox {
                val compactForLargeText = LocalDensity.current.fontScale >= 1.5f
                Text("本地推理引擎", fontWeight = FontWeight.Bold)
                Text("高速引擎优先使用 MNN；兼容引擎继续支持 GGUF / llama.cpp 生态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                localActionError?.let { StatusMessageCard(message = it, isError = true) }
                McaButton(style = McaButtonStyle.Primary,
                    onClick = onImportClick,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(McaIcons.UploadFile, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
                    Spacer(Modifier.width(6.dp))
                    // The long engine list does not fit on one line at 2.0x; keep the action and
                    // its icon readable and leave the engine list to the description above.
                    Text(
                        text = if (compactForLargeText) {
                            "+ 导入本地模型"
                        } else {
                            "+ 导入 GGUF / LiteRT-LM / MNN 本地模型"
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                            onAttachVisionProjector = { onAttachVisionProjector(model) },
                            modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
                        )
            }
        } else {
            item {
                CardBox {
                    Text("图像生成引擎", fontWeight = FontWeight.Bold)
                    Text("本地文生图模型独立管理，图片页会使用选中的引擎")
                    if (state.localImageModels.isEmpty()) Text("还没有本地图像生成引擎，可下载推荐包或导入完整引擎包。")
                McaButton(style = McaButtonStyle.Outlined,
                    onClick = onImportLocalImageModel,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(McaIcons.Image, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
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
                            onRemoveRecord = { onRemoveLocalImageModelRecord(model.id) },
                            modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
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
    onAttachVisionProjector: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmDelete by rememberSaveable(model.id) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = if (isLoaded) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else McaHairline.stroke()
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
                    // primary + onPrimary clears 4.5:1 in both themes (primary text on a light container did not).
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(999.dp)) {
                        Text(
                            "已加载",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
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
            // 2.0x 下三按钮+删除同排会裁标签：大字号隐藏装饰图标、允许两行并改用最小高度
            val largeTextActions = LocalDensity.current.fontScale >= 1.5f
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                McaButton(style = McaButtonStyle.Primary,
                    onClick = { if (isLoaded) onUnload() else onLoad() },
                    enabled = enabled && (isLoaded || canNormalLoad),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(
                        horizontal = if (largeTextActions) 4.dp else 8.dp,
                        vertical = 0.dp
                    )
                ) {
                    if (!largeTextActions) {
                        Icon(
                            imageVector = if (isLoaded) McaIcons.Close else McaIcons.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(McaIconSize.S)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    if (isLoaded) {
                        Text("卸载", maxLines = if (largeTextActions) 2 else 1, overflow = TextOverflow.Ellipsis)
                    } else if (!canLoadRuntime) {
                        Text("引擎未启用", maxLines = if (largeTextActions) 2 else 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text("加载", maxLines = if (largeTextActions) 2 else 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                McaButton(style = McaButtonStyle.Outlined,
                    onClick = onVerify,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(
                        horizontal = if (largeTextActions) 4.dp else 8.dp,
                        vertical = 0.dp
                    )
                ) {
                    if (!largeTextActions) {
                        Icon(McaIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        if (isQairtRuntime) "重新诊断" else "校验",
                        maxLines = if (largeTextActions) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                McaButton(style = McaButtonStyle.Outlined,
                    onClick = onAttachVisionProjector,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(
                        horizontal = if (largeTextActions) 4.dp else 8.dp,
                        vertical = 0.dp
                    )
                ) {
                    if (!largeTextActions) {
                        Icon(McaIcons.Visibility, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        if (model.hasVisionProjector) "更换" else "绑定",
                        maxLines = if (largeTextActions) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                McaIconButton(
                    contentDescription = localModelDeleteActionLabel(model.displayName),
                    onClick = { confirmDelete = true },
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                    glyphSize = McaIconSize.S,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    // Same 18dp glyph box and muted tint as the sibling action icons; the trash was
                    // the only 24dp, full-contrast mark in the row.
                    Icon(
                        McaIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(McaIconSize.S)
                    )
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
    McaDialog(
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
                    McaButton(style = McaButtonStyle.Text, onClick = onUnload, enabled = enabled) {
                        Text("仅卸载")
                    }
                }
                McaButton(style = McaButtonStyle.Text,
                    onClick = onDelete,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isLoaded) "卸载并删除" else "删除")
                }
            }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text, onClick = onDismiss, enabled = enabled) {
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
    onRemoveRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmRemoval by remember { mutableStateOf(false) }
    if (confirmRemoval) {
        McaDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("移除本地图像模型") },
            text = { Text(model.displayName) },
            confirmButton = {
                McaButton(style = McaButtonStyle.Text, onClick = { confirmRemoval = false; onRemoveRecord() }) {
                    Text("仅移除记录")
                }
            },
            dismissButton = {
                Row {
                    McaButton(style = McaButtonStyle.Text, onClick = { confirmRemoval = false; onDelete() }) {
                        Text("删除文件")
                    }
                    McaButton(style = McaButtonStyle.Text, onClick = { confirmRemoval = false }) { Text("取消") }
                }
            }
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = if (model.selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else McaHairline.stroke()
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
                    // “当前”与“已加载”统一用 primary/onPrimary 配对（≥4.5:1）；原 primaryContainer 底在暗色仅约 2.1:1
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(999.dp)) {
                        Text("当前", modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
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
                McaButton(style = McaButtonStyle.Primary,
                    onClick = onSelect,
                    enabled = enabled && !model.selected && model.readyForGeneration,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(McaIcons.Image, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
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
                McaButton(style = McaButtonStyle.Outlined,
                    onClick = onVerify,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(McaIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
                    Spacer(Modifier.width(6.dp))
                    Text("校验", maxLines = 1)
                }
                // 与本地聊天卡删除按钮统一：48dp 触达 + 18dp muted 字形
                McaIconButton(
                    contentDescription = localImageModelDeleteActionLabel(model.displayName),
                    onClick = { confirmRemoval = true },
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                    glyphSize = McaIconSize.S,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(
                        McaIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(McaIconSize.S)
                    )
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
