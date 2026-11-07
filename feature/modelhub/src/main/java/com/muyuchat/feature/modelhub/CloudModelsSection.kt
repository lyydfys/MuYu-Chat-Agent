@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import com.muyuchat.core.designsystem.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaTextField
import kotlinx.coroutines.launch

@Composable
internal fun CloudModelsSection(
    state: ModelHubUiState,
    onBeginAddCloudModel: (String) -> Unit,
    onEditCloudModel: (String) -> Unit,
    onSelectCloudChat: (String) -> Unit,
    onSelectCloudImage: (String) -> Unit,
    onDeleteCloudModel: (String) -> Unit,
    modifier: Modifier
) {
    val chatModels = state.cloudApi.connectedModels.filter { it.kind == "CHAT" }
    val imageModels = state.cloudApi.connectedModels.filter { it.kind == "IMAGE" }
    // 与本地/推荐一致：busy 时禁用云端写操作，避免和保存/测试并发
    val enabled = !state.isBusy
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CloudModelGroupCard(
                title = "云端推理引擎",
                subtitle = "已接入 ${chatModels.size} 个云端推理引擎",
                emptyTitle = "还没有云端推理引擎",
                emptyBody = "点击下方按钮选择 OpenAI 或 Anthropic 协议，再填写自定义 Base URL、模型名和 API Key。",
                models = chatModels,
                enabled = enabled,
                primaryAction = "加载",
                addAction = "+ 接入更多推理引擎",
                onAdd = { onBeginAddCloudModel("CHAT") },
                onPrimaryAction = onSelectCloudChat,
                onEdit = onEditCloudModel,
                onDelete = onDeleteCloudModel
            )
        }
        item {
            CloudModelGroupCard(
                title = "图像生成引擎",
                subtitle = "图片页会使用选中的图像生成引擎",
                emptyTitle = "还没有图像生成引擎",
                emptyBody = "图像生成引擎和云端推理引擎分开保存，支持 OpenAI Images、DashScope Image 和后续自定义路径。",
                models = imageModels,
                enabled = enabled,
                primaryAction = "设为当前",
                addAction = "+ 接入更多图像生成引擎",
                onAdd = { onBeginAddCloudModel("IMAGE") },
                onPrimaryAction = onSelectCloudImage,
                onEdit = onEditCloudModel,
                onDelete = onDeleteCloudModel
            )
        }
    }
}

@Composable
internal fun CloudModelEditorPage(
    kind: String,
    cloud: CloudApiUiState,
    enabled: Boolean,
    statusMessage: String?,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onFormatChange: (String) -> Unit,
    onImageFormatChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onChatModelChange: (String) -> Unit,
    onSupportsVisionChange: (Boolean) -> Unit,
    onSupportsToolsChange: (Boolean) -> Unit,
    onResponsesReasoningChange: (Boolean) -> Unit = {},
    onStreamingChange: (Boolean) -> Unit = {},
    onImageModelChange: (String) -> Unit,
    onImageSizeChange: (String) -> Unit,
    onImageEndpointPathChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onSaveChat: () -> Unit,
    onSaveImage: () -> Unit,
    onTest: () -> Unit,
    onFetchModels: suspend () -> Result<List<String>> = {
        Result.failure(UnsupportedOperationException("获取模型列表未接入"))
    },
    onFetchImageModels: suspend () -> Result<List<String>> = {
        Result.failure(UnsupportedOperationException("获取图像模型列表未接入"))
    },
    modifier: Modifier = Modifier
) {
    val isImage = kind == "IMAGE"
    val title = if (isImage) "接入图像生成引擎" else "接入云端推理引擎"
    val subtitle = if (isImage) {
        "图像生成引擎独立保存，用于图片页。"
    } else {
        "云端推理引擎用于普通聊天页。"
    }
    val fetchScope = rememberCoroutineScope()
    var fetchingModels by rememberSaveable { mutableStateOf(false) }
    var fetchModelsError by rememberSaveable { mutableStateOf<String?>(null) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsDialogOpen by rememberSaveable { mutableStateOf(false) }
    // Chat and image engines keep separate protocol fields, so the editor asks each one with its
    // own callback but shares one in-progress flag, one error slot and one result dialog.
    val fetchModelsFor: suspend () -> Result<List<String>> =
        if (isImage) onFetchImageModels else onFetchModels

    fun fetchModels() {
        if (fetchingModels) return
        fetchingModels = true
        fetchModelsError = null
        fetchScope.launch {
            fetchModelsFor()
                .onSuccess { models ->
                    fetchedModels = models
                    modelsDialogOpen = true
                }
                .onFailure { error ->
                    fetchModelsError = error.message ?: "请求失败"
                }
            fetchingModels = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 2.0x 大字号下给标题列限宽，长副标题省略而不是越界裁切
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                McaIconButton(
                    contentDescription = "返回云端模型",
                    onClick = onBack,
                    glyphSize = McaIconSize.L,
                ) {
                    Icon(McaIcons.Back, contentDescription = null)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (!enabled) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                CardBox {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("基本信息", fontWeight = FontWeight.Bold)
                            Text(
                                if (isImage) "填写图像模型名和可选显示名。" else "填写聊天模型名和可选显示名。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        McaSwitch(checked = cloud.enabled, onCheckedChange = onEnabledChange, enabled = enabled, contentDescription = "启用云端模型")
                    }
                    McaTextField(
                        value = cloud.displayName,
                        onValueChange = onDisplayNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        label = { Text("显示名称") },
                        placeholder = { Text("可选，不填则使用模型名") }
                    )
                    McaTextField(
                        value = if (isImage) cloud.imageModel else cloud.chatModel,
                        onValueChange = if (isImage) onImageModelChange else onChatModelChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled && (!isImage || cloud.imageSupported),
                        label = { Text(if (isImage) "图像模型名" else "推理模型名") },
                        placeholder = {
                            Text(
                                if (isImage) {
                                    imageModelPlaceholder(cloud.imageApiFormat)
                                } else {
                                    chatModelPlaceholder(cloud.apiFormat)
                                }
                            )
                        }
                    )
                    if (!isImage) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            McaButton(
                                style = McaButtonStyle.Outlined,
                                onClick = ::fetchModels,
                                enabled = enabled && !fetchingModels,
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                if (fetchingModels) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (fetchingModels) "获取中" else "获取模型列表")
                            }
                            Text(
                                "用当前 Base URL 和 API Key 拉取可用模型。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        fetchModelsError?.let { error ->
                            Text(
                                "获取模型列表失败：$error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        // Image engines have their own protocol fields, so they ask the provider
                        // through a separate callback; the button, spinner, error slot and picker
                        // dialog are shared with the chat editor above.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            McaButton(
                                style = McaButtonStyle.Outlined,
                                onClick = ::fetchModels,
                                enabled = enabled && !fetchingModels && cloud.imageSupported,
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                if (fetchingModels) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (fetchingModels) "获取中" else "获取图像模型列表")
                            }
                            Text(
                                "用当前 Base URL 和 API Key 拉取可用图像模型。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        fetchModelsError?.let { error ->
                            Text(
                                "获取图像模型列表失败：$error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (!isImage) {
                        if (cloud.apiFormat == "OPENAI_RESPONSES") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("支持工具调用", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "仅在此模型和服务端实际支持 Responses 工具调用时开启。开启后角色可请求生图；每次调用前仍会向你确认。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                McaSwitch(
                                    checked = cloud.supportsTools,
                                    onCheckedChange = onSupportsToolsChange,
                                    contentDescription = "支持工具调用",
                                    enabled = enabled
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("推理模型", fontWeight = FontWeight.SemiBold)
                                    Text("仅对支持 reasoning 的模型开启。开启后请求推理摘要，并停用温度与 Top P 参数。",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                McaSwitch(checked = cloud.responsesReasoningEnabled,
                                    onCheckedChange = onResponsesReasoningChange, enabled = enabled, contentDescription = "推理模型")
                            }
                        }
Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("流式输出", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "关闭后每次请求只返回一整段结果，用于 SSE 流不会正常结束的网关；普通网关请保持开启。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                McaSwitch(
                                    checked = cloud.streaming,
                                    onCheckedChange = onStreamingChange,
                                    contentDescription = "流式输出",
                                    enabled = enabled
                                )
                            }
                                                    Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("支持图片输入", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "仅在当前云端模型确实支持多模态识图时开启。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                McaSwitch(
                                    checked = cloud.supportsVision,
                                    onCheckedChange = onSupportsVisionChange,
                                    contentDescription = "支持图片输入",
                                    enabled = enabled
                                )
                            }
                        }
                    }
                }
            }

            item {
                CardBox {
                    Text("协议", fontWeight = FontWeight.Bold)
                    Text(
                        if (isImage) "选择图像生成接口协议。" else "选择聊天推理接口协议。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isImage) {
                            items(cloud.availableImageFormats, key = { it.first }) { format ->
                                McaChip(
                                    selected = cloud.imageApiFormat == format.first,
                                    onClick = { onImageFormatChange(format.first) },
                                    label = { Text(format.second) },
                                    enabled = enabled
                                )
                            }
                        } else {
                            items(cloud.availableFormats, key = { it.first }) { format ->
                                McaChip(
                                    selected = cloud.apiFormat == format.first,
                                    onClick = { onFormatChange(format.first) },
                                    label = { Text(format.second) },
                                    enabled = enabled
                                )
                            }
                        }
                    }
                }
            }

            item {
                CardBox {
                    Text("接口信息", fontWeight = FontWeight.Bold)
                    Text(
                        "Base URL、API Key 和路径信息会保存在本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    McaTextField(
                        value = cloud.baseUrl,
                        onValueChange = onBaseUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        label = { Text("Base URL") },
                        placeholder = {
                            Text(
                                if (isImage) imageBaseUrlPlaceholder(cloud.imageApiFormat) else chatBaseUrlPlaceholder(cloud.apiFormat)
                            )
                        }
                    )
                    if (isImage) {
                        McaTextField(
                            value = cloud.imageEndpointPath,
                            onValueChange = onImageEndpointPathChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled && cloud.imageSupported,
                            label = { Text("图像路径") },
                            placeholder = { Text(imageEndpointPlaceholder(cloud.imageApiFormat)) },
                            supportingText = { Text("可以留空，MCA 会按当前协议补齐默认路径。") }
                        )
                        McaTextField(
                            value = cloud.imageSize,
                            onValueChange = onImageSizeChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled && cloud.imageSupported,
                            label = { Text("图片尺寸") },
                            placeholder = { Text(imageSizePlaceholder(cloud.imageApiFormat)) },
                            supportingText = { Text("可以留空，默认使用 1024x1024。") }
                        )
                    }
                    McaTextField(
                        value = cloud.apiKey,
                        onValueChange = onApiKeyChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("API Key") },
                        placeholder = { Text("不需要密钥的本地转发服务可以留空") }
                    )
                }
            }

            item {
                val status = cloudDialogStatusMessage(statusMessage)
                if (status != null) {
                    // 保存/测试失败需要 error 语义，复用模块内已有的状态判定
                    StatusMessageCard(message = status, isError = modelHubStatusMessageIsError(status))
                } else {
                    CardBox {
                        Text("状态反馈", fontWeight = FontWeight.Bold)
                        Text(
                            if (isImage) {
                                "保存后可在图片页用短提示词验证真实生图。图像生成会产生实际请求和费用，当前不做静默测试。"
                            } else {
                                "保存前建议先测试连接。测试结果会显示在这里，不再被弹窗遮挡。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(78.dp))
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            // 2.0x 大字号下固定 48dp 会裁切按钮文字，改为最小高度并让保存按钮省略
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isImage) {
                    McaButton(style = McaButtonStyle.Outlined,
                        onClick = onBack,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("取消")
                    }
                    McaButton(style = McaButtonStyle.Primary,
                        onClick = onSaveImage,
                        enabled = enabled && cloud.imageConfigured,
                        modifier = Modifier.weight(1.45f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("保存并设为当前", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    McaButton(style = McaButtonStyle.Outlined,
                        onClick = onTest,
                        enabled = enabled && cloud.configured,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("测试")
                    }
                    McaButton(style = McaButtonStyle.Primary,
                        onClick = onSaveChat,
                        enabled = enabled && cloud.configured,
                        modifier = Modifier.weight(1.45f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("保存并加载", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (modelsDialogOpen && fetchedModels.isNotEmpty()) {
            McaDialog(
                onDismissRequest = { modelsDialogOpen = false },
                title = {
                    Text(if (isImage) "选择图像模型" else "选择推理模型", fontWeight = FontWeight.Bold)
                },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(fetchedModels) { modelId ->
                            val currentModel = if (isImage) cloud.imageModel else cloud.chatModel
                            Text(
                                modelId,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (modelId == currentModel) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isImage) onImageModelChange(modelId) else onChatModelChange(modelId)
                                        modelsDialogOpen = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    McaButton(style = McaButtonStyle.Text, onClick = { modelsDialogOpen = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
private fun CloudModelGroupCard(
    title: String,
    subtitle: String,
    emptyTitle: String,
    emptyBody: String,
    models: List<CloudModelUiItem>,
    enabled: Boolean,
    primaryAction: String,
    addAction: String,
    onAdd: () -> Unit,
    onPrimaryAction: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    CardBox {
        Text(title, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (models.isEmpty()) {
            Text(emptyTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(emptyBody, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            models.forEach { model ->
                CloudModelRow(
                    model = model,
                    enabled = enabled,
                    primaryAction = primaryAction,
                    onPrimaryAction = onPrimaryAction,
                    onEdit = onEdit,
                    onDelete = onDelete
                )
            }
        }
        McaButton(style = McaButtonStyle.Outlined,
            onClick = onAdd,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(addAction, fontWeight = FontWeight.Bold)
        }
    }
}

internal fun cloudModelDeleteActionLabel(model: CloudModelUiItem): String {
    val target = model.displayName.trim().ifBlank {
        model.modelName.trim().ifBlank { "未命名模型" }
    }
    return "删除云端模型：$target"
}

@Composable
private fun CloudModelRow(
    model: CloudModelUiItem,
    enabled: Boolean,
    primaryAction: String,
    onPrimaryAction: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var confirmDelete by rememberSaveable(model.id) { mutableStateOf(false) }
    // 2.0x 下动作按钮只有一半宽：隐藏装饰图标并允许两行，避免“设为当前”被硬裁
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "模型：${model.modelName}",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (model.selected) {
                        // 与本地“已加载/当前”一致：primary/onPrimary 配对在明暗主题都 ≥4.5:1
                        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(999.dp)) {
                            Text("当前", modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                val meta = buildString {
                    append("协议：").append(model.protocolLabel)
                    if (model.imageSize.isNotBlank()) append(" · 尺寸：").append(model.imageSize)
                    if (model.kind == "CHAT" && model.supportsVision) append(" · 图片输入")
                    if (model.kind == "CHAT" && model.protocolLabel == "OpenAI Responses" && model.supportsTools) {
                        append(" · 工具调用")
                    }
                }
                Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(model.baseUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    McaButton(style = McaButtonStyle.Primary,
                        onClick = { onPrimaryAction(model.id) },
                        enabled = enabled && !model.selected,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        if (!largeText) {
                            Icon(
                                imageVector = if (model.kind == "IMAGE") McaIcons.Image else McaIcons.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(McaIconSize.S)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (model.selected) "当前" else primaryAction,
                            maxLines = if (largeText) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    McaButton(style = McaButtonStyle.Outlined,
                        onClick = { onEdit(model.id) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        if (!largeText) {
                            Icon(McaIcons.Edit, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("编辑", maxLines = if (largeText) 2 else 1, overflow = TextOverflow.Ellipsis)
                    }
                    // 与本地模型卡删除按钮统一：48dp 触达 + 18dp muted 字形
                    McaIconButton(
                        contentDescription = cloudModelDeleteActionLabel(model),
                        onClick = { confirmDelete = true },
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
    if (confirmDelete) {
        McaDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除云端模型", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定删除「${model.modelName}」吗？这只会移除 MCA 中保存的接入配置，不会影响云端服务商账号。")
            },
            confirmButton = {
                McaButton(style = McaButtonStyle.Text,
                    onClick = {
                        confirmDelete = false
                        onDelete(model.id)
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                McaButton(style = McaButtonStyle.Text, onClick = { confirmDelete = false }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun cloudDialogStatusMessage(message: String?): String? {
    val clean = message?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return clean.takeIf {
        it.contains("云端 API") ||
            it.contains("快速测试") ||
            it.contains("请先") ||
            it.contains("失败") ||
            it.contains("错误") ||
            it.contains("成功") ||
            it.contains("Base URL", ignoreCase = true) ||
            it.contains("API Key", ignoreCase = true)
    }
}
