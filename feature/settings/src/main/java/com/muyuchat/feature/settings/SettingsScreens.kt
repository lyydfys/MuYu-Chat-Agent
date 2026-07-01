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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.telemetry.RuntimeMetrics
import kotlinx.coroutines.launch

private const val MAX_VISIBLE_RUNTIME_LOGS = 10
private const val MAX_VISIBLE_AGENT_LOGS = 10

data class SettingsUiState(
    val params: GenerationParams = GenerationParams(),
    val stats: RuntimeStats = RuntimeStats(),
    val logs: List<RuntimeMetrics> = emptyList(),
    val agentLogs: List<String> = emptyList(),
    val apiEnabled: Boolean = false,
    val restEnabled: Boolean = false,
    val apiKey: String = "",
    val localApiAddress: String = "",
    val openApiAddress: String = "",
    val nativeStatsJson: String = "{}",
    val diagnosticReport: String = ""
)

private enum class SettingsSection(val title: String) {
    RUNTIME("运行"),
    LOGS("日志/诊断")
}

@Composable
fun SettingsHubScreen(
    state: SettingsUiState,
    onRefreshLogs: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.RUNTIME) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "返回聊天")
                    }
                    Column {
                        Text("系统设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("运行状态、日志和诊断", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsSection.entries.forEach { item ->
                    FilterChip(
                        selected = section == item,
                        onClick = { section = item },
                        label = { Text(item.title) }
                    )
                }
            }
        }
        when (section) {
            SettingsSection.RUNTIME -> RuntimeScreen(
                state = state,
                modifier = Modifier.weight(1f)
            )
            SettingsSection.LOGS -> LogsApiScreen(
                state = state,
                onRefreshLogs = onRefreshLogs,
                onRefreshDiagnostics = onRefreshDiagnostics,
                onExportDiagnostics = onExportDiagnostics,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun RuntimeScreen(
    state: SettingsUiState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("运行状态", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            InfoCard(
                "当前模型",
                state.stats.modelPath?.substringAfterLast('/') ?: "未加载",
                if (state.stats.loaded) "模型已就绪，使用本机 CPU 推理。" else "请先在模型页加载模型。"
            )
        }
        item {
            InfoCard(
                "性能",
                "首 token ${state.stats.ttftMs} ms · 速度 ${"%.2f".format(state.stats.decodeTps)} token/s",
                "已输出 ${state.stats.completionTokens} 个片段，输入约 ${state.stats.promptTokens} 个 token。"
            )
        }
        item {
            val rssKb = state.stats.processRssKb.takeIf { it > 0L }
            InfoCard(
                "内存",
                "RSS ${formatKb(rssKb ?: state.stats.nativePssKb)} · PSS ${formatKb(state.stats.nativePssKb)}",
                "Native 堆 ${formatKb(state.stats.nativeHeapKb)} · Java 堆 ${formatKb(state.stats.javaHeapKb)} · 系统可用 ${formatKb(state.stats.availMemKb)} · 运行预算 ${formatKb(state.stats.modelMemoryBudgetKb)}"
            )
        }
        item {
            val error = state.stats.lastError
            InfoCard(
                title = if (error.isNullOrBlank()) "诊断" else "最近问题",
                primary = if (error.isNullOrBlank()) "运行正常" else friendlyError(error),
                secondary = if (error.isNullOrBlank()) "需要排错时，可到“日志与接口”导出完整诊断。" else "完整信息已保留在诊断报告中。"
            )
        }
    }
}

@Composable
fun LogsApiScreen(
    state: SettingsUiState,
    onRefreshLogs: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onExportDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val visibleLogs = state.logs.takeLast(MAX_VISIBLE_RUNTIME_LOGS).asReversed()
    val visibleAgentLogs = state.agentLogs.take(MAX_VISIBLE_AGENT_LOGS)
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("日志/诊断", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onRefreshLogs, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Save, contentDescription = "刷新")
                    Text("刷新", modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = onRefreshDiagnostics, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Save, contentDescription = "生成诊断")
                    Text("生成诊断", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA 诊断报告", state.diagnosticReport))
                            )
                        }
                    },
                    enabled = state.diagnosticReport.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                    Text("复制", modifier = Modifier.padding(start = 6.dp))
                }
                Button(
                    onClick = onExportDiagnostics,
                    enabled = state.diagnosticReport.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = "导出")
                    Text("导出", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        if (state.diagnosticReport.isNotBlank()) {
            item {
                InfoCard(
                    title = "诊断报告",
                    primary = "已生成，可复制或导出",
                    secondary = "报告包含引擎、模型、内存、参数和最近错误，默认不直接展示。"
                )
            }
        }
        if (state.logs.isNotEmpty()) {
            item {
                Text(
                    "运行记录（最近 ${visibleLogs.size} 条 / 共 ${state.logs.size} 条）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(visibleLogs) { log ->
                val rssKb = log.processRssKb.takeIf { it > 0L }
                InfoCard(
                    title = shortName(log.model).ifBlank { "运行记录" },
                    primary = "速度 ${"%.2f".format(log.decodeTps)} token/s · 首 token ${log.ttftMs} ms",
                    secondary = "输出 ${log.genTokens} · RSS ${formatKb(rssKb ?: log.nativePssKb)} · PSS ${formatKb(log.nativePssKb)}"
                )
            }
        }
        if (state.agentLogs.isNotEmpty()) {
            item {
                Text(
                    "调参记录（最近 ${visibleAgentLogs.size} 条 / 共 ${state.agentLogs.size} 条）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(visibleAgentLogs) { log ->
                InfoCard(
                    title = "智能调参",
                    primary = log.take(120),
                    secondary = "完整记录只保存在本机，可按需导出。"
                )
            }
        }
    }
}

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
    val localAddress = state.localApiAddress.ifBlank { "http://127.0.0.1:11435/v1/chat/completions" }
    val openAddress = state.openApiAddress.ifBlank { "http://本机局域网IP:11435/v1/chat/completions" }
    val localBaseAddress = localAddress.substringBefore("/v1/chat/completions").ifBlank { "http://127.0.0.1:11435" } + "/v1"
    val openBaseAddress = openAddress.substringBefore("/v1/chat/completions").ifBlank { "http://本机局域网IP:11435" } + "/v1"
    val webChatAddress = openAddress.substringBefore("/v1/chat/completions").ifBlank { "http://本机局域网IP:11435" }
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
                InfoCard(
                    title = "同设备 Base URL",
                    primary = localBaseAddress,
                    secondary = "客户端和 MCA 在同一台设备上运行时优先填写这个地址。"
                )
            }
            item {
                InfoCard(
                    title = "同网段 Base URL",
                    primary = openBaseAddress,
                    secondary = "另一台设备访问 MCA 时填写这个地址；需要开启“开放端口”。"
                )
            }
            item {
                InfoCard(
                    title = "完整接口地址",
                    primary = openAddress,
                    secondary = "高级调用可直接 POST 到 /v1/chat/completions。"
                )
            }
            item {
                InfoCard(
                    title = "网页聊天",
                    primary = webChatAddress,
                    secondary = "浏览器打开这个地址，可以直接使用 MCA 的网页对话界面。"
                )
            }
            item {
                InfoCard(
                    title = "API Key",
                    primary = state.apiKey.ifBlank { "未生成" },
                    secondary = "Header: Authorization: Bearer ${state.apiKey.ifBlank { "<api-key>" }}"
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA API 地址", openBaseAddress)))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制地址")
                        Text("复制同网段", modifier = Modifier.padding(start = 6.dp))
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA 本机 API 地址", localBaseAddress)))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制地址")
                        Text("复制本机", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA API Key", state.apiKey)))
                            }
                        },
                        enabled = state.apiKey.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制 Key")
                        Text("复制 Key", modifier = Modifier.padding(start = 6.dp))
                    }
                }
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
                title = "完整聊天接口",
                primary = chatCompletionsAddress,
                secondary = "仅在客户端要求填写完整请求地址，或你手动发起 HTTP 请求时使用。"
            )
        }
        item {
            InfoCard(
                title = "网页对话",
                primary = webChatAddress,
                secondary = "浏览器打开后可直接使用网页界面；同网段访问同样需要开启“开放端口”。"
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

@Composable
private fun InfoCard(title: String, primary: String, secondary: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(primary)
            if (secondary.isNotBlank()) {
                Text(secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatKb(kb: Long): String {
    val gb = kb / 1024.0 / 1024.0
    val mb = kb / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.0f MB".format(mb)
}

private fun shortName(value: String): String =
    value.substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { value }
        .let { if (it.length > 32) it.take(29) + "..." else it }

private fun friendlyError(error: String): String = when {
    "no backends are loaded" in error -> "引擎后端没有初始化，请重启 APP 或重新加载模型。"
    "connection abort" in error.lowercase() -> "网络连接中断，可稍后继续下载。"
    "out of memory" in error.lowercase() || "oom" in error.lowercase() -> "内存不足，建议换小模型或降低上下文。"
    "not gguf" in error.lowercase() -> "文件不是可加载的 GGUF 模型。"
    else -> error.lineSequence().firstOrNull().orEmpty().let { if (it.length > 80) it.take(77) + "..." else it }
}
