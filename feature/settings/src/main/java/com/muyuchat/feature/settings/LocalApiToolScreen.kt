package com.muyuchat.feature.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun LocalApiToolScreen(
    state: SettingsUiState,
    onApiToggle: (Boolean) -> Unit,
    onRestToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showApiDocs by rememberSaveable { mutableStateOf(false) }
    var modelsSelfTest by rememberSaveable { mutableStateOf("尚未测试 /v1/models。") }
    var chatSelfTest by rememberSaveable { mutableStateOf("尚未测试 /v1/chat/completions。") }
    var visionSelfTest by rememberSaveable { mutableStateOf("尚未测试图片输入接口。") }
    var selfTestBusy by remember { mutableStateOf(false) }
    val localBaseAddress = state.localApiAddress
        .ifBlank { "http://127.0.0.1:11435/v1" }
        .normalizedApiBase()
    val openBaseAddress = state.openApiAddress
        .ifBlank { "http://本机局域网IP:11435/v1" }
        .normalizedApiBase()
    val openAddress = "$openBaseAddress/chat/completions"
    val webChatAddress = openBaseAddress.removeSuffix("/v1")
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (showApiDocs) showApiDocs = false else onBack() }) {
                    Icon(Icons.Default.Close, contentDescription = "返回")
                }
                Column {
                    Text(if (showApiDocs) "API使用文档" else "本地 API", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (showApiDocs) "连接参数、接口路径、流式输出与排错" else "OpenAI 兼容接口与网页对话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = { showApiDocs = !showApiDocs }) {
                Text(if (showApiDocs) "接口信息" else "API使用文档")
            }
        }
        if (showApiDocs) {
            ApiUsageDocumentContent(
                localBaseAddress = localBaseAddress,
                openBaseAddress = openBaseAddress,
                chatCompletionsAddress = openAddress,
                webChatAddress = webChatAddress,
                apiKey = state.apiKey,
                modifier = Modifier.fillMaxSize()
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ApiStatusCard(
                    apiEnabled = state.apiEnabled,
                    restEnabled = state.restEnabled,
                    localBaseAddress = localBaseAddress,
                    openBaseAddress = openBaseAddress,
                    apiKey = state.apiKey,
                    onCopyLocalBase = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA 本机 Base URL", localBaseAddress)))
                        }
                    },
                    onCopyOpenBase = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA 局域网 Base URL", openBaseAddress)))
                        }
                    },
                    onCopyKey = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA API Key", state.apiKey)))
                        }
                    }
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("服务开关", fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("本机调用", fontWeight = FontWeight.Medium)
                                Text("供同一台设备上的客户端、浏览器或本机组件访问。", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = state.apiEnabled, onCheckedChange = onApiToggle)
                        }
                        HorizontalDivider()
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("开放端口", fontWeight = FontWeight.Medium)
                                Text("开启后同网段设备可通过 HTTP 访问本机模型服务。", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = state.restEnabled, onCheckedChange = onRestToggle)
                        }
                        Text(
                            if (state.restEnabled) "开放端口已开启，请只在可信网络中使用。"
                            else if (state.apiEnabled) "本机调用已开启；同设备客户端优先使用 127.0.0.1 地址。"
                            else "本地 API 未开启。先开启“本机调用”；需要电脑访问时再开启“开放端口”。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                ApiSelfTestCard(
                    modelsResult = modelsSelfTest,
                    chatResult = chatSelfTest,
                    visionResult = visionSelfTest,
                    busy = selfTestBusy,
                    enabled = state.apiEnabled && state.apiKey.isNotBlank(),
                    onTestModels = {
                        scope.launch {
                            modelsSelfTest = withLocalApiSelfTestBusy(
                                updateBusy = { selfTestBusy = it }
                            ) {
                                runLocalApiModelsSelfTest(localBaseAddress, state.apiKey)
                            }
                        }
                    },
                    onTestChat = {
                        scope.launch {
                            chatSelfTest = withLocalApiSelfTestBusy(
                                updateBusy = { selfTestBusy = it }
                            ) {
                                runLocalApiChatSelfTest(
                                    baseUrl = localBaseAddress,
                                    apiKey = state.apiKey,
                                    loadedModelId = state.loadedModelId
                                )
                            }
                        }
                    },
                    onTestVision = {
                        scope.launch {
                            visionSelfTest = withLocalApiSelfTestBusy(
                                updateBusy = { selfTestBusy = it }
                            ) {
                                runLocalApiVisionSelfTest(
                                    baseUrl = localBaseAddress,
                                    apiKey = state.apiKey,
                                    loadedModelId = state.loadedModelId
                                )
                            }
                        }
                    }
                )
            }
            item {
                InfoCard(
                    title = "请求示例",
                    primary = "POST $openAddress\nAuthorization: Bearer ${state.apiKey.ifBlank { "<api-key>" }}",
                    secondary = "JSON 请求体使用 OpenAI Chat Completions 格式；stream=true 时返回 SSE 流式输出。"
                )
            }
        }
    }
}

internal suspend fun <T> withLocalApiSelfTestBusy(
    updateBusy: (Boolean) -> Unit,
    block: suspend () -> T
): T {
    updateBusy(true)
    try {
        return block()
    } finally {
        updateBusy(false)
    }
}

@Composable
private fun ApiStatusCard(
    apiEnabled: Boolean,
    restEnabled: Boolean,
    localBaseAddress: String,
    openBaseAddress: String,
    apiKey: String,
    onCopyLocalBase: () -> Unit,
    onCopyOpenBase: () -> Unit,
    onCopyKey: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("接口状态", fontWeight = FontWeight.Bold)
            InfoLine(
                label = "服务状态",
                value = if (apiEnabled) "运行中" else "未开启"
            )
            InfoLine(
                label = "本机 Base URL",
                value = localBaseAddress
            )
            InfoLine(
                label = "局域网 Base URL",
                value = if (restEnabled) openBaseAddress else "$openBaseAddress（需开启开放端口）"
            )
            InfoLine(
                label = "API Key",
                value = if (apiKey.isBlank()) "未生成" else "已生成，复制后填入客户端 Key/Token"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onCopyLocalBase, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制本机地址")
                    Text("本机", modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = onCopyOpenBase, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制局域网地址")
                    Text("局域网", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Button(onClick = onCopyKey, enabled = apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制 Key")
                Text("复制 API Key", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun ApiSelfTestCard(
    modelsResult: String,
    chatResult: String,
    visionResult: String,
    busy: Boolean,
    enabled: Boolean,
    onTestModels: () -> Unit,
    onTestChat: () -> Unit,
    onTestVision: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("连接自检", fontWeight = FontWeight.Bold)
            Text(
                if (enabled) "测试结果会显示在这里，不再依赖顶部通知。" else "先开启“本机调用”并确认 API Key 已生成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onTestModels,
                    enabled = enabled && !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("测试模型列表")
                }
                Button(
                    onClick = onTestChat,
                    enabled = enabled && !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("测试聊天接口")
                }
            }
            Button(
                onClick = onTestVision,
                enabled = enabled && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("测试识图接口")
            }
            ApiSelfTestResultBlock(
                title = "/v1/models",
                primary = if (busy) "正在测试..." else modelsResult,
                secondary = "用于确认 Base URL、Key 和模型列表路径是否正确。"
            )
            ApiSelfTestResultBlock(
                title = "/v1/chat/completions",
                primary = if (busy) "正在测试..." else chatResult,
                secondary = "会发起一次 SSE 流式短输出，并检查 [DONE] 终止帧；未加载本地模型时会返回 503。"
            )
            ApiSelfTestResultBlock(
                title = "image_url",
                primary = if (busy) "正在测试..." else visionResult,
                secondary = "会发送一张极小的 base64 测试图；本地识图需 vision_ready=true。"
            )
        }
    }
}

@Composable
private fun ApiSelfTestResultBlock(title: String, primary: String, secondary: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, fontWeight = FontWeight.Medium)
        Text(primary)
        Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ApiUsageDocumentContent(
    localBaseAddress: String,
    openBaseAddress: String,
    chatCompletionsAddress: String,
    webChatAddress: String,
    apiKey: String,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoCard(
                title = "用途",
                primary = "让通用第三方 OpenAI-compatible 客户端调用 MCA 已加载的本地聊天模型。",
                secondary = "适用于同一设备、本地浏览器、同网段电脑或其他受信任设备；模型仍在手机端本地运行。"
            )
        }
        item {
            InfoCard(
                title = "连接前准备",
                primary = "1. 先在 MCA 顶部选择并加载一个本地聊天模型。\n2. 开启“本机调用”。\n3. 需要另一台设备访问时，再开启“开放端口”。",
                secondary = "未加载模型时，聊天接口会返回 503；开放端口只建议在可信 Wi-Fi 或热点环境中临时开启。"
            )
        }
        item {
            InfoCard(
                title = "推荐配置",
                primary = "协议：OpenAI-compatible\nBase URL：填到 /v1\nAPI Key：填写下方 Key\n模型：优先从 /v1/models 自动选择",
                secondary = "不要把 Base URL 填成完整的 /v1/chat/completions，除非客户端明确要求填写完整请求地址。"
            )
        }
        item {
            InfoCard(
                title = "同设备连接",
                primary = localBaseAddress,
                secondary = "客户端和 MCA 在同一台 Android 设备上运行时优先使用。若客户端无法访问 127.0.0.1，可开启“开放端口”后改用同网段地址。"
            )
        }
        item {
            InfoCard(
                title = "同网段连接",
                primary = openBaseAddress,
                secondary = "另一台设备访问 MCA 时使用；需要开启“开放端口”，并确保两台设备在同一可信网络。"
            )
        }
        item {
            InfoCard(
                title = "API Key",
                primary = apiKey.ifBlank { "未生成" },
                secondary = "客户端的 Key、Token 或 Authorization 字段填写这个值。请求头格式：Authorization: Bearer <api-key>。"
            )
        }
        item {
            InfoCard(
                title = "兼容接口",
                primary = "GET /v1/models\nPOST /v1/chat/completions\nGET /health\nGET /",
                secondary = "/v1/models 可用于模型列表；/health 可用于连接检测；/ 是内置网页对话入口。"
            )
        }
        item {
            InfoCard(
                title = "流式输出",
                primary = "聊天接口支持普通 JSON 和 stream=true 的 SSE 流式输出。\n响应格式：data: {...}\ndata: [DONE]",
                secondary = "MCA 会兼容常见连接测试请求，包括多条 system 消息、无 user 的探测消息，以及 Accept: text/event-stream。"
            )
        }
        item {
            InfoCard(
                title = "角色设定",
                primary = "客户端未发送 system 消息时，MCA 会继承当前“助手与角色”的系统提示词。\n客户端发送 system 消息时，优先使用客户端自己的角色设定。",
                secondary = "这可以兼容通用第三方 OpenAI-compatible 客户端的角色卡，同时避免模型重载后丢失 MCA 当前助手。"
            )
        }
        item {
            InfoCard(
                title = "图片输入",
                primary = "兼容 messages 里的 image_url，也兼容 Responses 风格 input_image。支持 data:image base64、本地可读路径、file: URL、可访问的 http(s) 图片。",
                secondary = "Android content:// 不能稳定跨应用传给本机 HTTP API，建议转成 base64 或文件路径。本地识图需要先加载 MNN 多模态包，或加载多模态 GGUF 并绑定匹配 mmproj；/v1/models 会返回 vision_ready，也可以在本地 API 页面直接运行“测试识图接口”。云端识图需要在对应云端推理引擎中开启图片输入能力，实际效果由所选模型和服务商决定。"
            )
        }
        item {
            InfoCard(
                title = "完整聊天接口",
                primary = chatCompletionsAddress,
                secondary = "仅在客户端要求填写完整请求地址，或你手动发起 HTTP 请求时使用。"
            )
        }
        item {
            InfoCard(
                title = "网页对话",
                primary = webChatAddress,
                secondary = "浏览器打开后可直接使用网页界面，支持文本聊天和图片附件；同网段访问同样需要开启“开放端口”。"
            )
        }
        item {
            InfoCard(
                title = "配置步骤",
                primary = "1. 客户端选择 OpenAI-compatible 或自定义 OpenAI 接口。\n2. 填写 Base URL 和 API Key。\n3. 刷新模型列表，选择 MCA 返回的模型。\n4. 发送一条短消息验证聊天。",
                secondary = "如果客户端必须手动填写模型名，可复制 /v1/models 返回的 id。"
            )
        }
        item {
            InfoCard(
                title = "常见错误",
                primary = "无法获取模型列表：检查 Base URL 是否填到 /v1。\n401：API Key 缺失或不正确。\n404：客户端拼接了错误路径，重新检查 Base URL。\n503：MCA 尚未加载本地模型。\n连接超时：检查开放端口、同网段、VPN 或网络隔离。\nAndroid 客户端无请求：确认客户端允许 HTTP 明文访问，或通过可信 HTTPS 反代连接。",
                secondary = "如果同设备 127.0.0.1 不通，通常改用同网段地址即可定位问题。开放端口用完后建议关闭。"
            )
        }
    }
}
