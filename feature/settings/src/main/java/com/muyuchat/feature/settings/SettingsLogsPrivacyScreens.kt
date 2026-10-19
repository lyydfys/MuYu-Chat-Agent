package com.muyuchat.feature.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.telemetry.RuntimeMetrics
import kotlinx.coroutines.launch

private const val MAX_VISIBLE_RUNTIME_LOGS = 10
private const val MAX_VISIBLE_AGENT_LOGS = 10

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
    // W9-1: derive the bounded visible windows once per list value. `asReversed()` allocates and
    // `takeLast` walks the list; doing it in composition rebuilt both on every unrelated update.
    val visibleLogs = remember(state.logs) {
        state.logs.takeLast(MAX_VISIBLE_RUNTIME_LOGS).asReversed()
    }
    val visibleAgentLogs = remember(state.agentLogs) {
        state.agentLogs.take(MAX_VISIBLE_AGENT_LOGS)
    }
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
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text("刷新", modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = onRefreshDiagnostics, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Description, contentDescription = null)
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
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Text("复制", modifier = Modifier.padding(start = 6.dp))
                }
                Button(
                    onClick = onExportDiagnostics,
                    enabled = state.diagnosticReport.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.FileDownload, contentDescription = null)
                    Text("导出", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        if (state.logs.isEmpty() && state.agentLogs.isEmpty() && state.diagnosticReport.isBlank()) {
            item { LogsEmptyState() }
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
                // W9-1: number formatting and KB conversion run once per metrics row, not on every
                // recomposition of this page (the page also observes live runtime state).
                val row = remember(log) { runtimeLogRowPresentation(log) }
                Box(
                    modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
                ) {
                    InfoCard(
                        title = row.title,
                        primary = row.primary,
                        secondary = row.secondary
                    )
                }
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
                val primary = remember(log) { log.take(120) }
                Box(
                    modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
                ) {
                    InfoCard(
                        title = "智能调参",
                        primary = primary,
                        secondary = "完整记录只保存在本机，可按需导出。"
                    )
                }
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
            val localDataText = remember(state.imageAssetBytes, state.fileAssetBytes) {
                "图片占用 ${formatBytes(state.imageAssetBytes)} · 文件索引 ${formatBytes(state.fileAssetBytes)}。使用云端模型时，发送的消息、图片和启用的上下文会按所选服务商接口规则传输。"
            }
            InfoCard(
                title = "本地数据",
                primary = "聊天 ${state.chatSessionCount} 条 · 图片 ${state.imageAssetCount} 张 · 文件 ${state.fileAssetCount} 个",
                secondary = localDataText
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
                busy = state.cleanupBusy.persistentPrefixCache,
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
                chatHistoryBusy = state.cleanupBusy.chatHistory,
                imageLibraryBusy = state.cleanupBusy.imageLibrary,
                fileLibraryBusy = state.cleanupBusy.fileLibrary,
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
    busy: Boolean,
    confirmClear: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onRequestClear: () -> Unit
) {
    val usageText = remember(entryCount, bytes) {
        "缓存项 $entryCount 个 · 占用 ${formatBytes(bytes)}"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Switch stays on the title row; the description below keeps the full card width.
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("持久化前缀缓存", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = if (busy) null else onEnabledChanged
                    )
                }
                Text(
                    "在本机保存 llama.cpp 的前缀 KV 缓存，用于减少重复预填充。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                usageText,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "仅缓存固定的系统提示词和角色设定前缀，且只保存在本机；每次请求中的消息、附件和其他请求范围内容不会写入缓存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onRequestClear,
                enabled = entryCount > 0 && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(6.dp))
                }
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
    chatHistoryBusy: Boolean,
    imageLibraryBusy: Boolean,
    fileLibraryBusy: Boolean,
    confirmClearChats: Boolean,
    confirmClearImages: Boolean,
    confirmClearFiles: Boolean,
    onRequestClearChats: () -> Unit,
    onRequestClearImages: () -> Unit,
    onRequestClearFiles: () -> Unit
) {
    val anyBusy = chatHistoryBusy || imageLibraryBusy || fileLibraryBusy
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
                    enabled = chatSessionCount > 0 && !anyBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    if (chatHistoryBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(6.dp))
                    }
                    Text(if (confirmClearChats) "确认清空聊天" else "清空聊天")
                }
                TextButton(
                    onClick = onRequestClearImages,
                    enabled = imageAssetCount > 0 && !anyBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    if (imageLibraryBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(6.dp))
                    }
                    Text(if (confirmClearImages) "确认清空图片" else "清空图片")
                }
            }
            TextButton(
                onClick = onRequestClearFiles,
                enabled = fileAssetCount > 0 && !anyBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (fileLibraryBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(6.dp))
                }
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
fun ExperimentsScreen(
    state: SettingsUiState,
    modifier: Modifier = Modifier
) {
    // W9-1: the vision-readiness flag is a scan over the whole native stats JSON. Cache it by the
    // full JSON value so recompositions that only change unrelated settings do not rescan it.
    val visionReady = remember(state.nativeStatsJson) {
        state.nativeStatsJson.contains("\"visionReady\":true")
    }
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
                primary = if (visionReady) "视觉 runner 已就绪" else "需要 MNN 多模态包或 GGUF+mmproj",
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

/**
 * W9-1: presentation for one runtime metrics row. Formatting and KB/MB conversion are evaluated
 * once per row value inside `remember`, not on every recomposition of the logs page.
 */
private class RuntimeLogRowPresentation(
    val title: String,
    val primary: String,
    val secondary: String
)

private fun runtimeLogRowPresentation(log: RuntimeMetrics): RuntimeLogRowPresentation {
    val rssKb = log.processRssKb.takeIf { it > 0L }
    return RuntimeLogRowPresentation(
        title = shortName(log.model).ifBlank { "运行记录" },
        primary = "速度 ${"%.2f".format(log.decodeTps)} token/s · 首 token ${log.ttftMs} ms",
        secondary = "输出 ${log.genTokens} · RSS ${formatKb(rssKb ?: log.nativePssKb)} · PSS ${formatKb(log.nativePssKb)}"
    )
}

/**
 * Empty state for the logs/diagnostics tab. Without it the tab was a wall of blank background under
 * four buttons, which reads as a broken screen rather than "nothing captured yet".
 */
@Composable
private fun LogsEmptyState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Rounded.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(32.dp)
            )
            Text(
                "暂无运行日志",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "点击刷新读取本机运行记录；生成诊断可收集引擎与设备信息，便于排查问题。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
