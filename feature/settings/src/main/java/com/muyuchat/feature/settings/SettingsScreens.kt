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
    val localAddress = state.localApiAddress.ifBlank { "http://127.0.0.1:11435/v1/chat/completions" }
    val openAddress = state.openApiAddress.ifBlank { "http://本机局域网IP:11435/v1/chat/completions" }
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
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = "返回")
                }
                Column {
                    Text("本地 API", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("OpenAI 兼容接口与网页对话", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
                                Text("供 MCA 应用内和本机组件调用，不开放网络端口。", style = MaterialTheme.typography.bodySmall)
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
                            else "开放端口默认关闭，开启后下方同网段地址才可访问。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                InfoCard(
                    title = "接口地址",
                    primary = openAddress,
                    secondary = "电脑客户端、curl、Chatbox 等工具使用这个地址。"
                )
            }
            item {
                InfoCard(
                    title = "网页聊天",
                    primary = webChatAddress,
                    secondary = "浏览器打开这个地址，可以像 llama-server 页面一样直接对话。"
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
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA API 地址", openAddress)))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制地址")
                        Text("复制地址", modifier = Modifier.padding(start = 6.dp))
                    }
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
                    primary = "curl \"$openAddress\" -H \"Authorization: Bearer ${state.apiKey.ifBlank { "<api-key>" }}\"",
                    secondary = "POST JSON 使用 /v1/chat/completions；stream=true 时返回 SSE。"
                )
            }
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
