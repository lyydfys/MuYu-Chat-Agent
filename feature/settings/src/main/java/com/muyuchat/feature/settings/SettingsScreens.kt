package com.muyuchat.feature.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.telemetry.RuntimeMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private const val MAX_VISIBLE_RUNTIME_LOGS = 10
private const val MAX_VISIBLE_AGENT_LOGS = 10
private const val WEB_SEARCH_PUBLIC_CHECK_ENDPOINT = "https://hn.algolia.com/api/v1/search"
private const val WEB_SEARCH_PUBLIC_CHECK_QUERY = "Android AI"
private const val WEB_SEARCH_RESEARCH_CHECK_QUERY = "手机端本地 AI 生图方案优缺点 生态 可行性 最新评测"

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
    val loadedModelId: String? = null,
    val nativeStatsJson: String = "{}",
    val diagnosticReport: String = "",
    val chatSessionCount: Int = 0,
    val imageAssetCount: Int = 0,
    val imageAssetBytes: Long = 0L,
    val fileAssetCount: Int = 0,
    val fileAssetBytes: Long = 0L,
    val persistentPrefixCacheEnabled: Boolean = true,
    val persistentPrefixCacheEntryCount: Int = 0,
    val persistentPrefixCacheBytes: Long = 0L,
    val statusMessage: String? = null,
    val webSearch: WebSearchSettingsUiState = WebSearchSettingsUiState()
)

data class WebSearchSettingsUiState(
    val enabled: Boolean = false,
    val provider: String = "SEARXNG",
    val providerLabel: String = "SearxNG",
    val endpoint: String = "",
    val apiKey: String = "",
    val maxResults: Int = 5,
    val fetchPageContent: Boolean = true,
    val triggerMode: String = "SMART",
    val triggerModeLabel: String = "智能",
    val researchMode: String = "AUTO",
    val researchModeLabel: String = "自动",
    val configured: Boolean = false,
    val realSearchConfigured: Boolean = false,
    val realSearchProviderLabel: String = "",
    val backupProviders: List<WebSearchBackupProviderUiState> = emptyList(),
    val statusMessage: String? = null,
    val diagnostics: List<WebSearchDiagnosticUiItem> = emptyList()
)

data class WebSearchBackupProviderUiState(
    val enabled: Boolean = false,
    val provider: String = "SEARXNG",
    val providerLabel: String = "SearxNG",
    val endpoint: String = "",
    val apiKey: String = "",
    val configured: Boolean = false
)

data class WebSearchDiagnosticUiItem(
    val createdAtText: String,
    val providerLabel: String,
    val triggerModeLabel: String,
    val query: String,
    val success: Boolean,
    val message: String,
    val sourceCount: Int,
    val elapsedMs: Long,
    val searchedQueries: List<String>,
    val directUrls: List<String>,
    val healthScore: Int = 0,
    val healthLabel: String = "",
    val healthReasons: List<String> = emptyList(),
    val qualityScore: Int,
    val qualityLabel: String,
    val qualityReasons: List<String>,
    val sourceTrustSummary: List<String> = emptyList(),
    val researchConfidenceScore: Int = 0,
    val researchConfidenceLabel: String = "",
    val researchEvidenceGroups: List<String> = emptyList(),
    val researchConflictWarnings: List<String> = emptyList(),
    val researchSynthesisGuidance: List<String> = emptyList(),
    val triggerReasons: List<String>,
    val warnings: List<String>,
    val cacheStatus: String = "",
    val closedLoopChecks: List<String> = emptyList(),
    val topSources: List<WebSearchDiagnosticSourceUiItem>
)

data class WebSearchDiagnosticSourceUiItem(
    val title: String,
    val url: String,
    val snippet: String = "",
    val provider: String = "",
    val trustLabel: String = "",
    val hostLabel: String = ""
)

data class WebSearchSettingsDraft(
    val enabled: Boolean,
    val provider: String,
    val endpoint: String,
    val apiKey: String,
    val maxResults: Int,
    val fetchPageContent: Boolean,
    val triggerMode: String,
    val researchMode: String = "AUTO",
    val backupProviders: List<WebSearchBackupProviderDraft> = emptyList()
)

data class WebSearchBackupProviderDraft(
    val enabled: Boolean,
    val provider: String,
    val endpoint: String,
    val apiKey: String
)

private enum class SettingsSection(val title: String) {
    RUNTIME("运行"),
    LOGS("日志/诊断"),
    PRIVACY("隐私与数据"),
    SEARCH("联网检索"),
    EXPERIMENTS("实验功能")
}

@Composable
fun SettingsHubScreen(
    state: SettingsUiState,
    onRefreshLogs: () -> Unit,
    onRefreshDiagnostics: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onClearChatHistory: () -> Unit,
    onClearImageLibrary: () -> Unit,
    onClearFileLibrary: () -> Unit,
    onPersistentPrefixCacheEnabledChanged: (Boolean) -> Unit,
    onClearPersistentPrefixCache: () -> Unit,
    onSaveWebSearchSettings: (WebSearchSettingsDraft) -> Unit,
    onPreflightWebSearch: (WebSearchSettingsDraft) -> Unit,
    onTestWebSearch: (String, WebSearchSettingsDraft) -> Unit,
    onTestWebSearchTurn: (String, WebSearchSettingsDraft, Boolean) -> Unit,
    onClearWebSearchDiagnostics: () -> Unit,
    onBack: () -> Unit,
    startInWebSearch: Boolean = false,
    modifier: Modifier = Modifier
) {
    val startSection = if (startInWebSearch) SettingsSection.SEARCH else SettingsSection.RUNTIME
    var section by rememberSaveable(startInWebSearch) { mutableStateOf(startSection) }
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
                        Text("运行、日志、隐私与实验功能", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
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
            SettingsSection.PRIVACY -> PrivacyDataScreen(
                state = state,
                onClearChatHistory = onClearChatHistory,
                onClearImageLibrary = onClearImageLibrary,
                onClearFileLibrary = onClearFileLibrary,
                onPersistentPrefixCacheEnabledChanged = onPersistentPrefixCacheEnabledChanged,
                onClearPersistentPrefixCache = onClearPersistentPrefixCache,
                modifier = Modifier.weight(1f)
            )
            SettingsSection.SEARCH -> SearchSettingsScreen(
                state = state.webSearch,
                onSave = onSaveWebSearchSettings,
                onPreflight = onPreflightWebSearch,
                onTest = onTestWebSearch,
                onTestTurn = onTestWebSearchTurn,
                onClearDiagnostics = onClearWebSearchDiagnostics,
                modifier = Modifier.weight(1f)
            )
            SettingsSection.EXPERIMENTS -> ExperimentsScreen(
                state = state,
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
fun PrivacyDataScreen(
    state: SettingsUiState,
    onClearChatHistory: () -> Unit,
    onClearImageLibrary: () -> Unit,
    onClearFileLibrary: () -> Unit,
    onPersistentPrefixCacheEnabledChanged: (Boolean) -> Unit,
    onClearPersistentPrefixCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClearChats by rememberSaveable { mutableStateOf(false) }
    var confirmClearImages by rememberSaveable { mutableStateOf(false) }
    var confirmClearFiles by rememberSaveable { mutableStateOf(false) }
    var confirmClearPersistentPrefixCache by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("隐私与数据", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            InfoCard(
                title = "记忆管理",
                primary = "已预留助手记忆入口",
                secondary = "当前版本先提供助手侧的记忆开关；后续会在这里集中查看、编辑和删除本机记忆。"
            )
        }
        item {
            InfoCard(
                title = "本地数据",
                primary = "聊天 ${state.chatSessionCount} 条 · 图片 ${state.imageAssetCount} 张 · 文件 ${state.fileAssetCount} 个",
                secondary = "图片占用 ${formatBytes(state.imageAssetBytes)} · 文件索引 ${formatBytes(state.fileAssetBytes)}。使用云端模型时，发送的消息、图片和启用的上下文会按所选服务商接口规则传输。"
            )
        }
        item {
            InfoCard(
                title = "文件库索引",
                primary = if (state.fileAssetCount > 0) "已索引 ${state.fileAssetCount} 个文件" else "暂无文件索引",
                secondary = "上传的文本、Markdown、JSON、代码文件会保存为本机文本索引，便于从输入框重新添加到当前聊天。"
            )
        }
        item {
            PersistentPrefixCacheCard(
                enabled = state.persistentPrefixCacheEnabled,
                entryCount = state.persistentPrefixCacheEntryCount,
                bytes = state.persistentPrefixCacheBytes,
                confirmClear = confirmClearPersistentPrefixCache,
                onEnabledChanged = onPersistentPrefixCacheEnabledChanged,
                onRequestClear = {
                    if (confirmClearPersistentPrefixCache) {
                        confirmClearPersistentPrefixCache = false
                        onClearPersistentPrefixCache()
                    } else {
                        confirmClearPersistentPrefixCache = true
                    }
                }
            )
        }
        item {
            PrivacyCleanupCard(
                chatSessionCount = state.chatSessionCount,
                imageAssetCount = state.imageAssetCount,
                fileAssetCount = state.fileAssetCount,
                confirmClearChats = confirmClearChats,
                confirmClearImages = confirmClearImages,
                confirmClearFiles = confirmClearFiles,
                onRequestClearChats = {
                    if (confirmClearChats) {
                        confirmClearChats = false
                        onClearChatHistory()
                    } else {
                        confirmClearChats = true
                        confirmClearImages = false
                        confirmClearFiles = false
                    }
                },
                onRequestClearImages = {
                    if (confirmClearImages) {
                        confirmClearImages = false
                        onClearImageLibrary()
                    } else {
                        confirmClearImages = true
                        confirmClearChats = false
                        confirmClearFiles = false
                    }
                },
                onRequestClearFiles = {
                    if (confirmClearFiles) {
                        confirmClearFiles = false
                        onClearFileLibrary()
                    } else {
                        confirmClearFiles = true
                        confirmClearChats = false
                        confirmClearImages = false
                    }
                }
            )
        }
    }
}

@Composable
private fun PersistentPrefixCacheCard(
    enabled: Boolean,
    entryCount: Int,
    bytes: Long,
    confirmClear: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onRequestClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("持久化前缀缓存", fontWeight = FontWeight.Bold)
                    Text(
                        "在本机保存 llama.cpp 的前缀 KV 缓存，用于减少重复预填充。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChanged)
            }
            Text(
                "缓存项 $entryCount 个 · 占用 ${formatBytes(bytes)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "仅缓存固定的系统提示词和角色设定前缀，且只保存在本机；每次请求中的消息、附件和其他请求范围内容不会写入缓存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onRequestClear,
                enabled = entryCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (confirmClear) "确认清空前缀缓存" else "清空前缀缓存")
            }
        }
    }
}

@Composable
private fun PrivacyCleanupCard(
    chatSessionCount: Int,
    imageAssetCount: Int,
    fileAssetCount: Int,
    confirmClearChats: Boolean,
    confirmClearImages: Boolean,
    confirmClearFiles: Boolean,
    onRequestClearChats: () -> Unit,
    onRequestClearImages: () -> Unit,
    onRequestClearFiles: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("清理能力", fontWeight = FontWeight.Bold)
            Text("清理只影响 MCA 本机数据；模型文件和云端 API 配置不会被删除。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onRequestClearChats,
                    enabled = chatSessionCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (confirmClearChats) "确认清空聊天" else "清空聊天")
                }
                TextButton(
                    onClick = onRequestClearImages,
                    enabled = imageAssetCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (confirmClearImages) "确认清空图片" else "清空图片")
                }
            }
            TextButton(
                onClick = onRequestClearFiles,
                enabled = fileAssetCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (confirmClearFiles) "确认清空文件库" else "清空文件库")
            }
            Text(
                "图片清理会移除 MCA 管理的本地图片副本；文件库清理只移除 MCA 保存的文本索引，不会删除外部原文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SearchSettingsScreen(
    state: WebSearchSettingsUiState,
    onSave: (WebSearchSettingsDraft) -> Unit,
    onPreflight: (WebSearchSettingsDraft) -> Unit,
    onTest: (String, WebSearchSettingsDraft) -> Unit,
    onTestTurn: (String, WebSearchSettingsDraft, Boolean) -> Unit,
    onClearDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var enabled by rememberSaveable(state.enabled) { mutableStateOf(state.enabled) }
    var provider by rememberSaveable(state.provider) { mutableStateOf(state.provider) }
    var endpoint by rememberSaveable(state.endpoint) { mutableStateOf(state.endpoint) }
    var apiKey by rememberSaveable(state.apiKey) { mutableStateOf(state.apiKey) }
    var maxResultsText by rememberSaveable(state.maxResults) { mutableStateOf(state.maxResults.toString()) }
    var fetchPageContent by rememberSaveable(state.fetchPageContent) { mutableStateOf(state.fetchPageContent) }
    var triggerMode by rememberSaveable(state.triggerMode) { mutableStateOf(state.triggerMode) }
    var researchMode by rememberSaveable(state.researchMode) { mutableStateOf(state.researchMode) }
    var testQuery by rememberSaveable { mutableStateOf("MCA 本地 AI") }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var backupExpanded by rememberSaveable { mutableStateOf(false) }
    var testExpanded by rememberSaveable { mutableStateOf(false) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    var helpExpanded by rememberSaveable { mutableStateOf(false) }
    val backup0 = state.backupProviders.getOrNull(0)
    val backup1 = state.backupProviders.getOrNull(1)
    val backup2 = state.backupProviders.getOrNull(2)
    var backup0Enabled by rememberSaveable(backup0?.enabled) { mutableStateOf(backup0?.enabled ?: false) }
    var backup0Provider by rememberSaveable(backup0?.provider) { mutableStateOf(backup0?.provider ?: "SEARXNG") }
    var backup0Endpoint by rememberSaveable(backup0?.endpoint) { mutableStateOf(backup0?.endpoint.orEmpty()) }
    var backup0ApiKey by rememberSaveable(backup0?.apiKey) { mutableStateOf(backup0?.apiKey.orEmpty()) }
    var backup1Enabled by rememberSaveable(backup1?.enabled) { mutableStateOf(backup1?.enabled ?: false) }
    var backup1Provider by rememberSaveable(backup1?.provider) { mutableStateOf(backup1?.provider ?: "SEARXNG") }
    var backup1Endpoint by rememberSaveable(backup1?.endpoint) { mutableStateOf(backup1?.endpoint.orEmpty()) }
    var backup1ApiKey by rememberSaveable(backup1?.apiKey) { mutableStateOf(backup1?.apiKey.orEmpty()) }
    var backup2Enabled by rememberSaveable(backup2?.enabled) { mutableStateOf(backup2?.enabled ?: false) }
    var backup2Provider by rememberSaveable(backup2?.provider) { mutableStateOf(backup2?.provider ?: "SEARXNG") }
    var backup2Endpoint by rememberSaveable(backup2?.endpoint) { mutableStateOf(backup2?.endpoint.orEmpty()) }
    var backup2ApiKey by rememberSaveable(backup2?.apiKey) { mutableStateOf(backup2?.apiKey.orEmpty()) }
    val providerItems = listOf(
        "SEARXNG" to "SearxNG",
        "BRAVE" to "Brave",
        "TAVILY" to "Tavily",
        "JINA" to "Jina",
        "CUSTOM_JSON" to "自定义"
    )
    val providerLabel = providerItems.firstOrNull { it.first == provider }?.second ?: state.providerLabel
    val isPublicCheckDraft = isPublicWebSearchCheckSource(provider, endpoint)
    val providerSetupGuidance = webSearchProviderSetupGuidance(
        provider = provider,
        endpoint = endpoint,
        apiKey = apiKey,
        publicCheck = isPublicCheckDraft
    )
    val endpointPlaceholder = when (provider) {
        "SEARXNG" -> "https://your-searxng.example"
        "BRAVE" -> "https://api.search.brave.com/res/v1/web/search"
        "TAVILY" -> "https://api.tavily.com/search"
        "JINA" -> "https://s.jina.ai"
        else -> "https://your-search-api.example/search"
    }
    val needsApiKey = provider == "BRAVE" || provider == "TAVILY" || provider == "JINA"
    val triggerModes = listOf(
        Triple("SMART", "智能", "按问题判断是否需要联网"),
        Triple("MANUAL", "手动", "只在本轮开关或助手默认开启时联网"),
        Triple("ALWAYS", "始终", "每轮非空问题都会先检索")
    )
    val researchModes = listOf(
        Triple("AUTO", "自动", "命中调研、评测、方案、对比等问题时启用多源研究"),
        Triple("OFF", "普通", "只做轻量检索，不强制扩展为研究查询"),
        Triple("DEEP", "深度", "把当前问题扩展为官方、评测、限制和社区等多角度查询")
    )
    fun currentDraft(): WebSearchSettingsDraft =
        WebSearchSettingsDraft(
            enabled = enabled,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            maxResults = maxResultsText.toIntOrNull()?.coerceIn(1, 8) ?: 5,
            fetchPageContent = fetchPageContent,
            triggerMode = triggerMode,
            researchMode = researchMode,
            backupProviders = listOf(
                WebSearchBackupProviderDraft(backup0Enabled, backup0Provider, backup0Endpoint, backup0ApiKey),
                WebSearchBackupProviderDraft(backup1Enabled, backup1Provider, backup1Endpoint, backup1ApiKey),
                WebSearchBackupProviderDraft(backup2Enabled, backup2Provider, backup2Endpoint, backup2ApiKey)
            )
        )
    fun publicCheckDraft(deepResearch: Boolean): WebSearchSettingsDraft =
        WebSearchSettingsDraft(
            enabled = true,
            provider = "CUSTOM_JSON",
            endpoint = WEB_SEARCH_PUBLIC_CHECK_ENDPOINT,
            apiKey = "",
            maxResults = if (deepResearch) 5 else 3,
            fetchPageContent = false,
            triggerMode = "SMART",
            researchMode = if (deepResearch) "DEEP" else "AUTO",
            backupProviders = emptyList()
        )
    fun applyPublicCheckDraft(query: String, deepResearch: Boolean) {
        enabled = true
        provider = "CUSTOM_JSON"
        endpoint = WEB_SEARCH_PUBLIC_CHECK_ENDPOINT
        apiKey = ""
        maxResultsText = if (deepResearch) "5" else "3"
        fetchPageContent = false
        triggerMode = "SMART"
        researchMode = if (deepResearch) "DEEP" else "AUTO"
        testQuery = query
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("联网检索", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            InfoCard(
                title = "工作方式",
                primary = when {
                    state.realSearchConfigured -> "已配置真实搜索：${state.realSearchProviderLabel.ifBlank { state.providerLabel }}"
                    state.isPublicWebSearchCheckSource() -> "已配置：公开 JSON 自检源（仅用于自检）"
                    state.configured -> "已配置：${state.providerLabel}"
                    state.enabled -> "已启用：可读取网页链接"
                    else -> "需要启用或配置搜索服务"
                },
                secondary = "触发：${state.triggerModeLabel} · 研究：${state.researchModeLabel}"
            )
        }
        item {
            ExpandableSettingsCard(
                title = "使用说明",
                summary = "搜索源配置、测试步骤、隐私边界",
                expanded = helpExpanded,
                onExpandedChange = { helpExpanded = it }
            ) {
                if (state.isPublicWebSearchCheckSource()) {
                    HelpTextBlock(
                        title = "覆盖范围提示",
                        primary = "当前使用的是公开 JSON 协议自检源。",
                        secondary = "它只用于验证联网链路、上下文注入和来源卡片，不是通用搜索服务。正式使用请配置自己的 SearxNG、Brave、Tavily、Jina 或可信自建搜索源。"
                    )
                }
                HelpTextBlock(
                    title = "推荐校验流程",
                    primary = "先测试完整 URL 直读，再测试关键词搜索，最后回到聊天页确认来源卡片。",
                    secondary = "公共 SearxNG 可能限流，稳定使用建议自建或使用带 Key 的搜索服务。Jina 配置 Key 后可在普通网页抓取内容不足时用 Reader 增强正文读取。"
                )
                HelpTextBlock(
                    title = "如何获取搜索源",
                    primary = "快速可用选 Tavily 或 Brave：到服务商官网注册并创建 Search API Key。",
                    secondary = "隐私可控选自建 SearxNG；网页正文增强选 Jina；团队或高级用户可接自建 JSON 网关。公开 JSON 自检源只验证链路，不能当正式搜索源。"
                )
                HelpTextBlock(
                    title = providerSetupGuidance.title,
                    primary = providerSetupGuidance.primary,
                    secondary = providerSetupGuidance.secondary
                )
                HelpTextBlock(
                    title = "安全边界",
                    primary = "本地模型不会直接联网，MCA 只把搜索摘要注入当前轮对话。",
                    secondary = "网页内容中的指令不会被当作系统指令执行；本机、局域网、链路本地和保留地址默认会被拦截。API Key 只保存在本机。"
                )
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用联网检索", fontWeight = FontWeight.Bold)
                            Text(
                                "开启后可读取用户提供的网页链接；关键词搜索还需要配置搜索服务。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        providerItems.forEach { item ->
                            FilterChip(
                                selected = provider == item.first,
                                onClick = { provider = item.first },
                                label = { Text(item.second) }
                            )
                        }
                    }
                    ExpandableSettingsInlineSection(
                        title = "高级设置",
                        summary = "触发：${triggerModes.firstOrNull { it.first == triggerMode }?.second.orEmpty()} · 研究：${researchModes.firstOrNull { it.first == researchMode }?.second.orEmpty()}",
                        expanded = advancedExpanded,
                        onExpandedChange = { advancedExpanded = it }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("触发方式", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                triggerModes.forEach { item ->
                                    FilterChip(
                                        selected = triggerMode == item.first,
                                        onClick = { triggerMode = item.first },
                                        label = { Text(item.second) }
                                    )
                                }
                            }
                            Text(
                                triggerModes.firstOrNull { it.first == triggerMode }?.third.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("研究模式", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                researchModes.forEach { item ->
                                    FilterChip(
                                        selected = researchMode == item.first,
                                        onClick = { researchMode = item.first },
                                        label = { Text(item.second) }
                                    )
                                }
                            }
                            Text(
                                researchModes.firstOrNull { it.first == researchMode }?.third.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text(if (provider == "SEARXNG") "SearxNG 地址" else "搜索接口地址") },
                        placeholder = { Text(endpointPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(if (needsApiKey) "API Key" else "API Key（可选）") },
                        placeholder = { Text(if (needsApiKey) "粘贴服务商 Key" else "自定义接口需要鉴权时填写") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (provider == "JINA") {
                        Text(
                            "Jina Search 需要 Key；开启正文抓取后，MCA 会先尝试本机直连读取公开网页，内容不足时再用 Jina Reader 获取 Markdown 摘要。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isPublicCheckDraft) {
                        Text(
                            "当前填写的是公开协议自检源，只适合测试链路，不建议保存为日常搜索服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    ExpandableSettingsInlineSection(
                        title = "备用搜索源",
                        summary = "已启用 ${listOf(backup0Enabled, backup1Enabled, backup2Enabled).count { it }} 个",
                        expanded = backupExpanded,
                        onExpandedChange = { backupExpanded = it }
                    ) {
                        Text(
                            "主搜索源限流、鉴权失败或没有可用结果时，MCA 只会按这里显式配置过的服务接力，不会调用未授权服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        BackupProviderEditor(
                            title = "备用 1",
                            enabled = backup0Enabled,
                            provider = backup0Provider,
                            endpoint = backup0Endpoint,
                            apiKey = backup0ApiKey,
                            providerItems = providerItems,
                            onEnabledChange = { backup0Enabled = it },
                            onProviderChange = { backup0Provider = it },
                            onEndpointChange = { backup0Endpoint = it },
                            onApiKeyChange = { backup0ApiKey = it }
                        )
                        BackupProviderEditor(
                            title = "备用 2",
                            enabled = backup1Enabled,
                            provider = backup1Provider,
                            endpoint = backup1Endpoint,
                            apiKey = backup1ApiKey,
                            providerItems = providerItems,
                            onEnabledChange = { backup1Enabled = it },
                            onProviderChange = { backup1Provider = it },
                            onEndpointChange = { backup1Endpoint = it },
                            onApiKeyChange = { backup1ApiKey = it }
                        )
                        BackupProviderEditor(
                            title = "备用 3",
                            enabled = backup2Enabled,
                            provider = backup2Provider,
                            endpoint = backup2Endpoint,
                            apiKey = backup2ApiKey,
                            providerItems = providerItems,
                            onEnabledChange = { backup2Enabled = it },
                            onProviderChange = { backup2Provider = it },
                            onEndpointChange = { backup2Endpoint = it },
                            onApiKeyChange = { backup2ApiKey = it }
                        )
                    }
                    OutlinedTextField(
                        value = maxResultsText,
                        onValueChange = { maxResultsText = it.filter { ch -> ch.isDigit() }.take(1).ifBlank { "" } },
                        label = { Text("结果数量") },
                        placeholder = { Text("1-8") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("抓取网页正文", fontWeight = FontWeight.Bold)
                            Text(
                                "会额外读取前几个公开网页正文，提高回答质量，但速度会慢一点；本机、局域网、链路本地和保留地址会被安全拦截。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = fetchPageContent, onCheckedChange = { fetchPageContent = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onSave(currentDraft())
                            }
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Text("保存")
                        }
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("测试检索", fontWeight = FontWeight.Bold)
                    Text(
                        "测试会使用当前表单内容，不需要先保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    WebSearchTestStatusPanel(
                        statusMessage = state.statusMessage,
                        latestDiagnostic = state.diagnostics.firstOrNull(),
                        configured = state.configured
                    )
                    OutlinedTextField(
                        value = testQuery,
                        onValueChange = { testQuery = it },
                        label = { Text("测试问题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onPreflight(currentDraft()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("网络预检")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { onTest(testQuery, currentDraft()) }, modifier = Modifier.weight(1f)) {
                            Text("测试当前填写")
                        }
                        Button(onClick = { onTestTurn(testQuery, currentDraft(), false) }, modifier = Modifier.weight(1f)) {
                            Text("闭环自检")
                        }
                    }
                    ExpandableSettingsInlineSection(
                        title = "更多测试",
                        summary = "协议自检、研究闭环、清空记录",
                        expanded = testExpanded,
                        onExpandedChange = { testExpanded = it }
                    ) {
                        Button(
                            onClick = {
                                testQuery = WEB_SEARCH_RESEARCH_CHECK_QUERY
                                onTestTurn(WEB_SEARCH_RESEARCH_CHECK_QUERY, currentDraft().copy(researchMode = "DEEP"), false)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("研究闭环自检")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    applyPublicCheckDraft(WEB_SEARCH_PUBLIC_CHECK_QUERY, deepResearch = false)
                                    onTestTurn(WEB_SEARCH_PUBLIC_CHECK_QUERY, publicCheckDraft(deepResearch = false), true)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("协议自检")
                            }
                            Button(
                                onClick = {
                                    applyPublicCheckDraft(WEB_SEARCH_RESEARCH_CHECK_QUERY, deepResearch = true)
                                    onTestTurn(WEB_SEARCH_RESEARCH_CHECK_QUERY, publicCheckDraft(deepResearch = true), true)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("研究协议自检")
                            }
                        }
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                applyPublicCheckDraft(WEB_SEARCH_PUBLIC_CHECK_QUERY, deepResearch = false)
                            }
                        ) {
                            Text("填入公开 JSON 协议自检源")
                        }
                        Text(
                            "公开协议自检无需 Key，用于验证 JSON 接入、上下文注入、来源卡片和诊断链路；它不是通用搜索服务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = onClearDiagnostics,
                            enabled = state.diagnostics.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("清空记录")
                        }
                    }
                }
            }
        }
        item {
            ExpandableSettingsCard(
                title = "最近检索",
                summary = if (state.diagnostics.isEmpty()) "暂无记录" else "${state.diagnostics.size} 条本机诊断记录",
                expanded = diagnosticsExpanded,
                onExpandedChange = { diagnosticsExpanded = it }
            ) {
                if (state.diagnostics.isEmpty()) {
                    HelpTextBlock(
                        title = "暂无记录",
                        primary = "完成一次测试或聊天联网后会显示在这里。",
                        secondary = "记录只保存在本机，包含检索词、来源数、耗时和失败摘要，不保存 API Key。"
                    )
                } else {
                    state.diagnostics.take(8).forEach { diagnostic ->
                        WebSearchDiagnosticCard(diagnostic)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableSettingsCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ExpandableSettingsHeader(
                title = title,
                summary = summary,
                expanded = expanded,
                onClick = { onExpandedChange(!expanded) }
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider()
                    content()
                }
            }
        }
    }
}

@Composable
private fun ExpandableSettingsInlineSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExpandableSettingsHeader(
            title = title,
            summary = summary,
            expanded = expanded,
            onClick = { onExpandedChange(!expanded) }
        )
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun ExpandableSettingsHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
            modifier = Modifier.rotate(if (expanded) 180f else 0f)
        )
    }
}

@Composable
private fun HelpTextBlock(
    title: String,
    primary: String,
    secondary: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(primary, style = MaterialTheme.typography.bodySmall)
        Text(
            secondary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WebSearchTestStatusPanel(
    statusMessage: String?,
    latestDiagnostic: WebSearchDiagnosticUiItem?,
    configured: Boolean
) {
    val context = LocalContext.current
    val message = statusMessage
        ?: latestDiagnostic?.message
        ?: if (configured) {
            "当前配置可用于聊天页和助手默认联网检索。"
        } else {
            "先做网络预检；如需关键词搜索，请配置真实搜索源。"
        }
    val needsAttention = message.contains("失败") ||
        message.contains("未通过") ||
        message.contains("需检查") ||
        message.contains("请先") ||
        latestDiagnostic?.success == false
    val accent = if (needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("当前测试结果", fontWeight = FontWeight.Bold, color = accent)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (latestDiagnostic != null) {
            Text(
                "${latestDiagnostic.providerLabel} · ${if (latestDiagnostic.success) "完成" else "未完成"} · ${latestDiagnostic.sourceCount} 源 · ${latestDiagnostic.elapsedMs}ms · ${latestDiagnostic.createdAtText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (latestDiagnostic.query.isNotBlank()) {
                Text(
                    "问题：${latestDiagnostic.query}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (message.shouldShowWebSearchNetworkTroubleshootingActions()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { context.openMcaNetworkSettings() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("打开网络设置")
                }
                TextButton(
                    onClick = { context.openMcaAppSettings() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("应用联网权限")
                }
            }
        }
    }
}

@Composable
private fun BackupProviderEditor(
    title: String,
    enabled: Boolean,
    provider: String,
    endpoint: String,
    apiKey: String,
    providerItems: List<Pair<String, String>>,
    onEnabledChange: (Boolean) -> Unit,
    onProviderChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit
) {
    val endpointPlaceholder = when (provider) {
        "SEARXNG" -> "https://your-searxng.example"
        "BRAVE" -> "https://api.search.brave.com/res/v1/web/search"
        "TAVILY" -> "https://api.tavily.com/search"
        "JINA" -> "https://s.jina.ai"
        else -> "https://your-search-api.example/search"
    }
    val needsApiKey = provider == "BRAVE" || provider == "TAVILY" || provider == "JINA"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    if (enabled) "已加入故障接力链路" else "关闭时不会发起请求",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                providerItems.forEach { item ->
                    FilterChip(
                        selected = provider == item.first,
                        onClick = { onProviderChange(item.first) },
                        label = { Text(item.second) }
                    )
                }
            }
            OutlinedTextField(
                value = endpoint,
                onValueChange = onEndpointChange,
                label = { Text("接口地址") },
                placeholder = { Text(endpointPlaceholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(if (needsApiKey) "API Key" else "API Key（可选）") },
                placeholder = { Text(if (needsApiKey) "粘贴服务商 Key" else "自定义接口需要鉴权时填写") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WebSearchDiagnosticCard(item: WebSearchDiagnosticUiItem) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val citationChecks = item.closedLoopChecks.filter { it.startsWith("引用审计") }
    val closedLoopChecks = item.closedLoopChecks.filterNot { it.startsWith("引用审计") }
    val troubleshootingAdvice = item.webSearchTroubleshootingAdvice()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (item.success) "检索成功" else "检索未完成", fontWeight = FontWeight.Bold)
                    Text(
                        "${item.providerLabel} · ${item.triggerModeLabel} · ${item.createdAtText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${item.sourceCount} 源",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            DiagnosticMetricRow(
                values = buildList {
                    add("耗时 ${item.elapsedMs}ms")
                    if (item.healthLabel.isNotBlank()) add("健康 ${item.healthLabel} ${item.healthScore}/100")
                    if (item.qualityLabel.isNotBlank()) add("质量 ${item.qualityLabel} ${item.qualityScore}/100")
                    if (item.cacheStatus.isNotBlank()) add(item.cacheStatus)
                }
            )
            DiagnosticEvidenceSection(
                title = "问题",
                values = listOf(item.query.ifBlank { "空问题" }, item.message)
            )
            DiagnosticEvidenceSection(
                title = "触发",
                values = item.triggerReasons.take(5),
                emptyText = "没有命中自动联网线索"
            )
            DiagnosticEvidenceSection(
                title = "检索",
                values = buildList {
                    if (item.searchedQueries.isNotEmpty()) {
                        add("检索词：${item.searchedQueries.joinToString(" / ")}")
                    }
                    if (item.directUrls.isNotEmpty()) {
                        add("网页：${item.directUrls.joinToString(" / ")}")
                    }
                    if (item.warnings.isNotEmpty()) {
                        addAll(item.warnings.take(4).map { "警告：$it" })
                    }
                },
                error = item.warnings.isNotEmpty(),
                emptyText = "未执行关键词检索"
            )
            DiagnosticEvidenceSection(
                title = "检索健康",
                values = buildList {
                    if (item.healthLabel.isNotBlank()) add("${item.healthLabel} · ${item.healthScore}/100")
                    addAll(item.healthReasons.take(6))
                },
                error = item.healthLabel == "需检查" || item.healthLabel == "失败",
                emptyText = "暂无健康评估"
            )
            if (troubleshootingAdvice.isNotEmpty()) {
                DiagnosticEvidenceSection(
                    title = "处理建议",
                    values = troubleshootingAdvice,
                    error = item.healthLabel == "需检查" || item.healthLabel == "失败" || item.warnings.isNotEmpty()
                )
            }
            DiagnosticEvidenceSection(
                title = "来源质量",
                values = buildList {
                    if (item.qualityLabel.isNotBlank()) add("${item.qualityLabel} · ${item.qualityScore}/100")
                    addAll(item.sourceTrustSummary.take(4))
                    addAll(item.qualityReasons.take(5))
                },
                emptyText = "没有可评估来源"
            )
            DiagnosticEvidenceSection(
                title = "研究综合",
                values = buildList {
                    if (item.researchConfidenceLabel.isNotBlank()) {
                        add("置信度 ${item.researchConfidenceLabel} · ${item.researchConfidenceScore}/100")
                    }
                    addAll(item.researchEvidenceGroups.take(5))
                    addAll(item.researchConflictWarnings.take(4).map { "不确定性：$it" })
                    addAll(item.researchSynthesisGuidance.take(3).map { "建议：$it" })
                },
                error = item.researchConflictWarnings.isNotEmpty(),
                emptyText = "暂无研究综合评估"
            )
            if (closedLoopChecks.isNotEmpty()) {
                DiagnosticEvidenceSection(
                    title = "闭环",
                    values = closedLoopChecks.take(5)
                )
            }
            if (citationChecks.isNotEmpty()) {
                DiagnosticEvidenceSection(
                    title = "引用审计",
                    values = citationChecks.take(4),
                    error = item.warnings.any { it.contains("引用") }
                )
            }
            if (item.topSources.isNotEmpty()) {
                HorizontalDivider()
                item.topSources.forEach { source ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), RoundedCornerShape(8.dp))
                            .clickable {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                                }
                            }
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            listOf(source.trustLabel, source.hostLabel)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            source.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            source.url.removePrefix("https://").removePrefix("http://"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (source.provider.isNotBlank()) {
                            Text(
                                source.provider,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (source.snippet.isNotBlank()) {
                            Text(
                                source.snippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                ClipData.newPlainText(
                                    "MCA 联网检索诊断",
                                    item.toEvidenceText()
                                )
                            )
                        )
                    }
                }
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制诊断")
                Text("复制诊断", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun DiagnosticMetricRow(values: List<String>) {
    if (values.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        values.forEach { value ->
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun DiagnosticEvidenceSection(
    title: String,
    values: List<String>,
    emptyText: String = "",
    error: Boolean = false
) {
    val visibleValues = values.filter { it.isNotBlank() }
    if (visibleValues.isEmpty() && emptyText.isBlank()) return
    val textColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (visibleValues.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            visibleValues.forEach { value ->
                Text(value, style = MaterialTheme.typography.bodySmall, color = textColor)
            }
        }
    }
}

internal fun String.shouldShowWebSearchNetworkTroubleshootingActions(): Boolean {
    val text = trim()
    if (text.isBlank()) return false
    if (!text.startsWith("网络预检需检查")) return false
    val networkHints = listOf(
        "没有活动网络",
        "WLAN",
        "移动数据",
        "Wi-Fi",
        "公网",
        "DNS",
        "VPN",
        "私人 DNS",
        "代理",
        "网络受限",
        "未登录 Wi-Fi",
        "系统尚未验证",
        "安全中心",
        "省电策略"
    )
    return networkHints.any { text.contains(it, ignoreCase = true) }
}

internal fun WebSearchDiagnosticUiItem.webSearchTroubleshootingAdvice(): List<String> {
    val text = buildString {
        appendLine(providerLabel)
        appendLine(message)
        warnings.forEach { appendLine(it) }
        healthReasons.forEach { appendLine(it) }
        qualityReasons.forEach { appendLine(it) }
    }
    val lower = text.lowercase()
    return buildList {
        if (providerLabel.contains("公开 JSON 自检源") || text.contains("不是通用搜索服务") || text.contains("公开 JSON")) {
            add("当前是协议自检源，只能验证请求、JSON 解析、上下文注入和来源卡片；正式搜索请切换到自建 SearxNG、Brave、Tavily、Jina 或可信自定义 JSON。")
        }
        if ("401" in lower || "403" in lower || "鉴权失败" in text || "鉴权或权限" in text) {
            add("重新复制 API Key，确认账号已开通对应搜索接口和额度；Brave 使用 X-Subscription-Token，Tavily/Jina 使用 Bearer Key。")
        }
        if ("404" in lower || "路径不存在" in text || "接口路径" in text) {
            add(providerLabel.webSearchEndpointAdvice())
        }
        if ("429" in lower || "限流" in text) {
            add("当前搜索服务被限流，建议稍后重试、降低结果数量、配置备用搜索源，或改用自建 SearxNG / 付费 Key。")
        }
        if ("400" in lower || "请求参数" in text) {
            add("检查协议类型和 Base URL 是否匹配；自建网关可使用 /search?q={query}&limit={max_results} 这类 URL 模板，返回 results/items/data/hits/organic_results 等常见 JSON 结构。")
        }
        if ("500" in lower || "502" in lower || "503" in lower || "504" in lower || "服务端异常" in text) {
            add("搜索服务端暂时异常，先切换备用源或稍后重试；自建服务请检查服务日志和反代状态。")
        }
        if (
            "unknown host" in lower || "unable to resolve host" in lower || "no address associated" in lower ||
            "无法解析" in text || "dns" in lower || "vpn" in lower || "代理" in text
        ) {
            add("先用手机浏览器打开任意公网网页；仍失败时检查应用联网权限、Wi-Fi 登录页、私人 DNS、VPN、代理和系统安全中心。")
        }
        if ("timeout" in lower || "timed out" in lower || "超时" in text) {
            add("请求超时通常来自移动网络波动、公共实例拥堵或正文抓取过慢；可关闭抓取网页正文、降低结果数量或换更稳定的搜索源。")
        }
        if ("空结果" in text || "无结果" in text || "没有可用来源" in text) {
            add("换更明确的关键词，加入官网/文档/评测等限定词；如果公共 SearxNG 返回空结果，建议自建或切换 Brave/Tavily/Jina。")
        }
        if ("低相关" in text || "相关性较弱" in text || "相关性过滤" in text) {
            add("来源相关性不足时，尝试把问题改成具体产品名、版本号、接口名或错误码，必要时开启研究模式。")
        }
        if ("安全拦截" in text || "受限地址" in text) {
            add("MCA 默认不读取 localhost、局域网、链路本地和保留地址，这是防止网页读取误扫内网的安全策略。")
        }
    }.distinct().take(5)
}

private fun String.webSearchEndpointAdvice(): String =
    when {
        contains("Brave", ignoreCase = true) ->
            "Brave 常规搜索推荐完整 Web Search API 路径 https://api.search.brave.com/res/v1/web/search；填写官方根地址 https://api.search.brave.com 时 MCA 会自动补全到 Web Search，AI grounding/RAG 可手动填写 /res/v1/llm/context。不要填控制台或聊天模型接口。"
        contains("Tavily", ignoreCase = true) ->
            "Tavily 推荐 https://api.tavily.com/search；填写官方根地址 https://api.tavily.com 时 MCA 会自动补全 /search，并使用 POST JSON 调用。"
        contains("Jina", ignoreCase = true) ->
            "Jina Search 推荐填写 https://s.jina.ai；Jina Reader 只用于网页正文增强，不要把 Reader 地址填到搜索服务里。"
        contains("SearxNG", ignoreCase = true) ->
            "SearxNG 通常填写实例根地址即可，MCA 会自动访问 /search?format=json；如果实例禁用 JSON，请换可信实例或自建。"
        else ->
            "检查 Base URL 和协议路径是否是搜索接口，不要把聊天模型接口、控制台页面或网页地址填到联网检索服务里。"
    }

private fun WebSearchSettingsUiState.isPublicWebSearchCheckSource(): Boolean =
    isPublicWebSearchCheckSource(provider, endpoint)

private fun isPublicWebSearchCheckSource(provider: String, endpoint: String): Boolean =
    provider == "CUSTOM_JSON" && endpoint.trim().trimEnd('/') == WEB_SEARCH_PUBLIC_CHECK_ENDPOINT

internal data class WebSearchProviderSetupGuidance(
    val title: String,
    val primary: String,
    val secondary: String
)

internal fun webSearchProviderSetupGuidance(
    provider: String,
    endpoint: String,
    apiKey: String,
    publicCheck: Boolean
): WebSearchProviderSetupGuidance {
    if (publicCheck) {
        return WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = "公开 JSON 自检源只适合验证链路",
            secondary = "它能证明 App 可以发起请求、解析 JSON、生成上下文和来源卡片，但覆盖范围有限，不是全网搜索服务。正式搜索请切换到自建 SearxNG、Brave、Tavily、Jina 或可信自定义 JSON。"
        )
    }
    val endpointText = endpoint.trim()
    val path = runCatching { URI(endpointText).path.orEmpty().trimEnd('/') }.getOrDefault("")
    val host = runCatching { URI(endpointText).host.orEmpty() }.getOrDefault("")
    val keyHint = if (apiKey.isBlank()) "当前未填写 API Key。" else "API Key 已填写。"
    return when (provider) {
        "SEARXNG" -> WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = "SearxNG 适合自建或可信实例",
            secondary = "可填写实例根地址，MCA 会自动请求 /search?format=json。公共实例经常限流或关闭 JSON，产品化使用建议自建，必要时再配置带 Key 的备用搜索源。"
        )
        "BRAVE" -> WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = when {
                path == "/res/v1/web/search" -> "Brave Web Search 路径正确"
                path == "/res/v1/llm/context" -> "Brave LLM Context 路径正确"
                host.equals("api.search.brave.com", ignoreCase = true) && path.isBlank() ->
                    "Brave 官方根地址会自动补全为完整 Web Search API 路径"
                else -> "Brave 需要 Search API 路径"
            },
            secondary = "常规搜索推荐：https://api.search.brave.com/res/v1/web/search；直接填写 https://api.search.brave.com 时 MCA 会自动补全到 Web Search。AI grounding/RAG 可填写：https://api.search.brave.com/res/v1/llm/context。MCA 会使用 X-Subscription-Token 发送 Key。$keyHint 如果你使用自建代理并返回兼容 JSON，也可以改用“自定义”。"
        )
        "TAVILY" -> WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = when {
                path.endsWith("/search") -> "Tavily Search 路径正确"
                host.equals("api.tavily.com", ignoreCase = true) && path.isBlank() ->
                    "Tavily 官方根地址会自动补全 /search"
                else -> "Tavily 应填写 Search API 地址"
            },
            secondary = "推荐地址：https://api.tavily.com/search；直接填写 https://api.tavily.com 时 MCA 会自动补全 /search。MCA 会用 POST JSON + Authorization: Bearer Key 调用，并可通过“抓取网页正文”切换 basic/advanced 深度。$keyHint"
        )
        "JINA" -> WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = if (host.equals("s.jina.ai", ignoreCase = true)) {
                "Jina Search 地址正确"
            } else {
                "Jina Search 推荐使用 s.jina.ai"
            },
            secondary = "推荐地址：https://s.jina.ai。MCA 会用 Bearer Key 请求搜索；开启正文抓取时，普通网页正文不足会尝试 Jina Reader 增强摘要。$keyHint"
        )
        else -> WebSearchProviderSetupGuidance(
            title = "当前服务建议",
            primary = "自定义 JSON 适合自建搜索网关",
            secondary = "MCA 支持 {query}/{max_results} URL 模板，也会尝试 q/query/max_results 参数，并解析 results、items、data、hits、organic_results 等常见结构。请确保返回项包含 url/link/href 和 title/snippet，API Key 只会以 Bearer 形式发送。"
        )
    }
}

private fun Context.openMcaNetworkSettings() {
    startFirstAvailable(
        Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS),
        Intent(AndroidSettings.ACTION_WIFI_SETTINGS),
        Intent(AndroidSettings.ACTION_SETTINGS)
    )
}

private fun Context.openMcaAppSettings() {
    startFirstAvailable(
        Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ),
        Intent(AndroidSettings.ACTION_APPLICATION_SETTINGS),
        Intent(AndroidSettings.ACTION_SETTINGS)
    )
}

private fun Context.startFirstAvailable(vararg intents: Intent) {
    val target = intents.firstOrNull { intent ->
        intent.resolveActivity(packageManager) != null
    } ?: intents.lastOrNull() ?: return
    startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun WebSearchDiagnosticUiItem.toEvidenceText(): String =
    buildString {
        appendLine("MCA 联网检索诊断")
        appendLine("状态: ${if (success) "成功" else "未完成"}")
        appendLine("服务: $providerLabel")
        appendLine("触发: $triggerModeLabel")
        appendLine("时间: $createdAtText")
        appendLine("耗时: ${elapsedMs}ms")
        appendLine("来源数: $sourceCount")
        if (cacheStatus.isNotBlank()) {
            appendLine("缓存: $cacheStatus")
        }
        if (closedLoopChecks.isNotEmpty()) {
            appendLine("闭环证据:")
            closedLoopChecks.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (triggerReasons.isNotEmpty()) {
            appendLine("触发依据:")
            triggerReasons.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (warnings.isNotEmpty()) {
            appendLine("检索警告:")
            warnings.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (healthLabel.isNotBlank()) {
            appendLine("检索健康: $healthLabel ($healthScore/100)")
            if (healthReasons.isNotEmpty()) {
                appendLine("健康依据: ${healthReasons.joinToString("；")}")
            }
        }
        val troubleshootingAdvice = webSearchTroubleshootingAdvice()
        if (troubleshootingAdvice.isNotEmpty()) {
            appendLine("处理建议:")
            troubleshootingAdvice.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (qualityLabel.isNotBlank()) {
            appendLine("资料质量: $qualityLabel ($qualityScore/100)")
            if (sourceTrustSummary.isNotEmpty()) {
                appendLine("来源类型: ${sourceTrustSummary.joinToString("；")}")
            }
            if (qualityReasons.isNotEmpty()) {
                appendLine("质量依据: ${qualityReasons.joinToString("；")}")
            }
        }
        if (researchConfidenceLabel.isNotBlank()) {
            appendLine("研究置信度: $researchConfidenceLabel ($researchConfidenceScore/100)")
            if (researchEvidenceGroups.isNotEmpty()) {
                appendLine("证据分组: ${researchEvidenceGroups.joinToString("；")}")
            }
            if (researchConflictWarnings.isNotEmpty()) {
                appendLine("冲突/不确定性: ${researchConflictWarnings.joinToString("；")}")
            }
            if (researchSynthesisGuidance.isNotEmpty()) {
                appendLine("综合建议: ${researchSynthesisGuidance.joinToString("；")}")
            }
        }
        appendLine("问题: ${query.ifBlank { "空问题" }}")
        appendLine("结果: $message")
        if (searchedQueries.isNotEmpty()) {
            appendLine("检索词:")
            searchedQueries.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (directUrls.isNotEmpty()) {
            appendLine("网页:")
            directUrls.forEachIndexed { index, value ->
                appendLine("${index + 1}. $value")
            }
        }
        if (topSources.isNotEmpty()) {
            appendLine("来源:")
            topSources.forEachIndexed { index, source ->
                appendLine("${index + 1}. ${source.title.ifBlank { source.url }}")
                appendLine("   URL: ${source.url}")
                if (source.trustLabel.isNotBlank() || source.hostLabel.isNotBlank()) {
                    appendLine("   类型: ${listOf(source.trustLabel, source.hostLabel).filter { it.isNotBlank() }.joinToString(" · ")}")
                }
                if (source.provider.isNotBlank()) appendLine("   Provider: ${source.provider}")
                if (source.snippet.isNotBlank()) appendLine("   摘要: ${source.snippet}")
            }
        }
    }.trim()

@Composable
fun ExperimentsScreen(
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
        item { Text("实验功能", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            InfoCard(
                title = "本地生图",
                primary = "实验功能",
                secondary = "本地生图速度取决于设备、模型包和分辨率；生成任务应在图片页显示状态、耗时、取消和失败重试。"
            )
        }
        item {
            InfoCard(
                title = "本地多模态",
                primary = if (state.nativeStatsJson.contains("\"visionReady\":true")) "视觉 runner 已就绪" else "需要 MNN 多模态包或 GGUF+mmproj",
                secondary = "不是所有文本模型都能识图；MNN 需要完整多模态包，GGUF 需要匹配的视觉投影器。"
            )
        }
        item {
            InfoCard(
                title = "联网检索",
                primary = if (state.webSearch.configured) "已配置：${state.webSearch.providerLabel}" else "需要在联网检索页配置服务",
                secondary = "可由助手默认开关或输入框本轮开关触发。MCA 会显示来源卡片，并只把搜索摘要注入当前轮。"
            )
        }
        item {
            InfoCard(
                title = "工具 / MCP",
                primary = "后续扩展",
                secondary = "当前优先保持 MCA 本地模型、API 服务和助手角色卡主线稳定。"
            )
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
    var modelsSelfTest by rememberSaveable { mutableStateOf("尚未测试 /v1/models。") }
    var chatSelfTest by rememberSaveable { mutableStateOf("尚未测试 /v1/chat/completions。") }
    var visionSelfTest by rememberSaveable { mutableStateOf("尚未测试图片输入接口。") }
    var selfTestBusy by rememberSaveable { mutableStateOf(false) }
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
                            selfTestBusy = true
                            modelsSelfTest = runLocalApiModelsSelfTest(localBaseAddress, state.apiKey)
                            selfTestBusy = false
                        }
                    },
                    onTestChat = {
                        scope.launch {
                            selfTestBusy = true
                            chatSelfTest = runLocalApiChatSelfTest(
                                baseUrl = localBaseAddress,
                                apiKey = state.apiKey,
                                loadedModelId = state.loadedModelId
                            )
                            selfTestBusy = false
                        }
                    },
                    onTestVision = {
                        scope.launch {
                            selfTestBusy = true
                            visionSelfTest = runLocalApiVisionSelfTest(
                                baseUrl = localBaseAddress,
                                apiKey = state.apiKey,
                                loadedModelId = state.loadedModelId
                            )
                            selfTestBusy = false
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

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        "%.1f %s".format(value, units[unitIndex])
    }
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

private suspend fun runLocalApiModelsSelfTest(baseUrl: String, apiKey: String): String =
    withContext(Dispatchers.IO) {
        val url = "${baseUrl.normalizedApiBase()}/models"
        runCatching {
            val response = httpRequest(
                method = "GET",
                url = url,
                apiKey = apiKey
            )
            response.toSelfTestMessage(
                successPrefix = "模型列表请求成功",
                knownFailureHint = "请确认本机调用已开启，Base URL 填到 /v1，API Key 正确。"
            )
        }.getOrElse { error ->
            "请求失败：${error.message ?: error.javaClass.simpleName}\n$url"
        }
    }

private suspend fun runLocalApiChatSelfTest(
    baseUrl: String,
    apiKey: String,
    loadedModelId: String?
): String =
    withContext(Dispatchers.IO) {
        val url = "${baseUrl.normalizedApiBase()}/chat/completions"
        val modelField = localApiSelfTestModelField(loadedModelId)
        val body = """
            {$modelField"messages":[{"role":"user","content":"ping"}],"max_tokens":8,"temperature":0,"stream":true}
        """.trimIndent()
        runCatching {
            val response = httpRequest(
                method = "POST",
                url = url,
                apiKey = apiKey,
                contentType = "application/json; charset=utf-8",
                body = body,
                accept = "text/event-stream"
            )
            if (response.code in 200..299 && !response.body.contains("data: [DONE]")) {
                return@runCatching "聊天接口已连接，但流式响应缺少 [DONE] 终止帧，请检查本地 API 服务状态。"
            }
            response.toSelfTestMessage(
                successPrefix = "流式聊天接口请求成功",
                knownFailureHint = "如果返回 503，说明接口已连通但本地聊天模型尚未加载。"
            )
        }.getOrElse { error ->
            "请求失败：${error.message ?: error.javaClass.simpleName}\n$url"
        }
    }

private suspend fun runLocalApiVisionSelfTest(
    baseUrl: String,
    apiKey: String,
    loadedModelId: String?
): String =
    withContext(Dispatchers.IO) {
        val url = "${baseUrl.normalizedApiBase()}/chat/completions"
        val modelField = localApiSelfTestModelField(loadedModelId)
        val body = """
            {
              $modelField
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": "请用一句中文说明这张测试图。"},
                    {
                      "type": "image_url",
                      "image_url": {
                        "url": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=",
                        "detail": "auto"
                      }
                    }
                  ]
                }
              ],
              "max_tokens": 48,
              "temperature": 0,
              "stream": false
            }
        """.trimIndent()
        runCatching {
            val response = httpRequest(
                method = "POST",
                url = url,
                apiKey = apiKey,
                contentType = "application/json; charset=utf-8",
                body = body,
                readTimeoutMillis = 120_000
            )
            response.toSelfTestMessage(
                successPrefix = "识图接口请求成功",
                knownFailureHint = "若提示未启用识图，请先加载 MNN 多模态包，或加载多模态 GGUF 并绑定匹配 mmproj。"
            )
        }.getOrElse { error ->
            "请求失败：${error.message ?: error.javaClass.simpleName}\n$url"
        }
    }

internal fun localApiSelfTestModelField(loadedModelId: String?): String = loadedModelId
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { "\"model\":${it.asJsonString()}," }
    .orEmpty()

private fun String.asJsonString(): String = buildString(length + 2) {
    append('\"')
    this@asJsonString.forEach { character ->
        when (character) {
            '\"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('\"')
}

private data class ApiHttpResponse(
    val code: Int,
    val body: String
)

private fun httpRequest(
    method: String,
    url: String,
    apiKey: String,
    contentType: String? = null,
    body: String? = null,
    accept: String = "application/json",
    readTimeoutMillis: Int = 20_000
): ApiHttpResponse {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method.uppercase()
        connectTimeout = 5_000
        readTimeout = readTimeoutMillis
        setRequestProperty("Accept", accept)
        if (apiKey.isNotBlank()) {
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        if (contentType != null) {
            setRequestProperty("Content-Type", contentType)
        }
        if (body != null) {
            doOutput = true
        }
    }
    return connection.useConnection {
        if (body != null) {
            outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }
        }
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        ApiHttpResponse(code, text)
    }
}

private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T =
    try {
        block()
    } finally {
        disconnect()
    }

private fun ApiHttpResponse.toSelfTestMessage(successPrefix: String, knownFailureHint: String): String {
    val shortBody = body
        .lineSequence()
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .let { if (it.length > 220) it.take(217) + "..." else it }
        .ifBlank { "无响应正文" }
    return if (code in 200..299) {
        "$successPrefix：HTTP $code\n$shortBody"
    } else {
        "请求返回 HTTP $code\n$shortBody\n$knownFailureHint"
    }
}

internal fun String.normalizedApiBase(): String {
    var base = trim().trimEnd('/')
    listOf("/chat/completions", "/completions", "/models").forEach { suffix ->
        if (base.endsWith(suffix)) {
            base = base.removeSuffix(suffix).trimEnd('/')
        }
    }
    base = base.removeSuffix("/v1").trimEnd('/')
    return "$base/v1"
}
