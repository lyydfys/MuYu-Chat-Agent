package com.muyuchat.feature.settings

import com.muyuchat.core.designsystem.*

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    modifier: Modifier = Modifier,
    /**
     * Backup manifest export through the existing SAF create-document channel. Defaulted so the
     * page (and its previews) still compile where no launcher is wired.
     */
    onExportBackup: () -> Unit = {},
    /** Verify a picked backup file. Read-only: it never replaces the live library. */
    onPreviewBackupRestore: () -> Unit = {}
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
                McaButton(style = McaButtonStyle.Primary, onClick = onRefreshLogs, modifier = Modifier.weight(1f)) {
                    Icon(McaIcons.Refresh, contentDescription = null)
                    Text("刷新", modifier = Modifier.padding(start = 6.dp))
                }
                McaButton(style = McaButtonStyle.Primary, onClick = onRefreshDiagnostics, modifier = Modifier.weight(1f)) {
                    Icon(McaIcons.Description, contentDescription = null)
                    Text("生成诊断", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                McaButton(style = McaButtonStyle.Primary,
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
                    Icon(McaIcons.ContentCopy, contentDescription = null)
                    Text("复制", modifier = Modifier.padding(start = 6.dp))
                }
                McaButton(style = McaButtonStyle.Primary,
                    onClick = onExportDiagnostics,
                    enabled = state.diagnosticReport.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(McaIcons.FileDownload, contentDescription = null)
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
        item {
            BackupRestoreCard(
                statusMessage = state.statusMessage,
                onExportBackup = onExportBackup,
                onPreviewBackupRestore = onPreviewBackupRestore
            )
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

/**
 * Backup entry points of the logs/privacy page.
 *
 * The copy states exactly what the file contains and, equally important, what this build does not
 * do yet: a picked backup is verified against the format but never written into the live library.
 */
@Composable
private fun BackupRestoreCard(
    statusMessage: String?,
    onExportBackup: () -> Unit,
    onPreviewBackupRestore: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmingRestore by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("备份与恢复", fontWeight = FontWeight.Bold)
            Text(
                "导出备份包：包含格式版本、导出时间、应用版本、五张数据库表的行数据（凭据列会被剔除并记录）与非敏感设置。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "世界书与助手卡是文件存储，不在本备份范围；备份里也不会有任何 API Key 或 token。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                McaButton(
                    style = McaButtonStyle.Primary,
                    onClick = onExportBackup,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(McaIcons.FileDownload, contentDescription = null)
                    Text("导出备份包（JSON）", modifier = Modifier.padding(start = 6.dp))
                }
                McaButton(
                    style = McaButtonStyle.Outlined,
                    onClick = { confirmingRestore = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(McaIcons.UploadFile, contentDescription = null)
                    Text("恢复（覆盖当前数据）", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Text(
                "恢复会覆盖当前对话、消息、助手、知识库与知识库文档的数据库记录；失败时整批回滚，原数据不变。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            backupStatusLine(statusMessage)?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    if (confirmingRestore) {
        // Destructive action: the confirmation names the tables it replaces and the rollback rule, so
        // a one-tap overwrite is impossible.
        McaDialog(
            onDismissRequest = { confirmingRestore = false },
            title = { Text("恢复备份会覆盖当前数据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "将用备份里的行数据替换当前数据库中的对话、消息、助手、知识库与知识库文档。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "恢复前会先把当前数据导出到应用缓存备份；恢复失败会整批回滚，现有数据保持不变。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "世界书与助手卡是文件存储，不会被这次恢复改动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                McaButton(
                    style = McaButtonStyle.Destructive,
                    onClick = {
                        confirmingRestore = false
                        onPreviewBackupRestore()
                    }
                ) {
                    Text("覆盖并恢复")
                }
            },
            dismissButton = {
                McaButton(style = McaButtonStyle.Text, onClick = { confirmingRestore = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * The status line belongs to this card only when the backup action produced it; the app's global
 * status surface stays the authority for everything else.
 */
internal fun backupStatusLine(statusMessage: String?): String? {
    val text = statusMessage?.trim().orEmpty()
    if (text.isEmpty()) return null
    return text.takeIf { candidate -> BACKUP_STATUS_PREFIXES.any { prefix -> candidate.startsWith(prefix) } }
}

private val BACKUP_STATUS_PREFIXES = listOf("备份", "可恢复", "无法恢复")

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
    // A two-step confirmation is armed against the counts the user just read. The owner runs one
    // cleanup at a time and drops any concurrent request, so once any cleanup takes the slot every
    // destructive control here is disabled. An armed label that survived that window would turn the
    // next single tap into an immediate wipe.
    LaunchedEffect(state.cleanupBusy.any) {
        if (state.cleanupBusy.any) {
            confirmClearChats = false
            confirmClearImages = false
            confirmClearFiles = false
            confirmClearPersistentPrefixCache = false
        }
    }
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
                hitRatePercent = state.prefixCacheHitRatePercent,
                attempts = state.prefixCacheAttempts,
                hits = state.prefixCacheHits,
                reusedTokens = state.prefixCacheReusedTokens,
                topMissReasons = state.prefixCacheTopMissReasons,
                busy = state.cleanupBusy.persistentPrefixCache,
                // Clearing the prefix cache is itself a cleanup: it holds the shared single-flight
                // slot, so the switch must not offer an opt-out it cannot honor right now.
                blockedByCleanup = state.cleanupBusy.any && !state.cleanupBusy.persistentPrefixCache,
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
            // DSH 设备访问（传感器/定位/手电筒 + 短信只读）：默认全关，逐项授权。
            DeviceAccessCard()
        }
        item {
            PrivacyCleanupCard(
                chatSessionCount = state.chatSessionCount,
                imageAssetCount = state.imageAssetCount,
                fileAssetCount = state.fileAssetCount,
                chatHistoryBusy = state.cleanupBusy.chatHistory,
                imageLibraryBusy = state.cleanupBusy.imageLibrary,
                fileLibraryBusy = state.cleanupBusy.fileLibrary,
                anyCleanupBusy = state.cleanupBusy.any,
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
    hitRatePercent: Int?,
    attempts: Long,
    hits: Long,
    reusedTokens: Long,
    topMissReasons: List<String>,
    busy: Boolean,
    blockedByCleanup: Boolean,
    confirmClear: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onRequestClear: () -> Unit
) {
    // A switch that flips and snaps straight back reads as a broken control; disable it instead.
    val interactive = !busy && !blockedByCleanup
    val usageText = remember(entryCount, bytes) {
        "缓存项 $entryCount 个 · 占用 ${formatBytes(bytes)}"
    }
    // The engine already reported these numbers per turn; showing the aggregate is what makes the
    // "is this cache worth its disk space" question answerable at all.
    val reuseText = remember(hitRatePercent, attempts, hits, reusedTokens) {
        when {
            attempts <= 0L -> "还没有可统计的缓存复用记录；先进行几次本地对话即可看到命中率。"
            hitRatePercent == null -> "已记录 $attempts 次缓存尝试。"
            else -> "命中率 $hitRatePercent% · 命中 $hits / 尝试 $attempts 次 · 复用 ${formatTokenCount(reusedTokens)} tokens"
        }
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
                    McaSwitch(
                        checked = enabled,
                        onCheckedChange = if (interactive) onEnabledChanged else null,
                        enabled = interactive,
                        contentDescription = "持久化前缀缓存"
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
                reuseText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (topMissReasons.isNotEmpty()) {
                Text(
                    "未命中主要因：${topMissReasons.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "仅缓存固定的系统提示词和角色设定前缀，且只保存在本机；每次请求中的消息、附件和其他请求范围内容不会写入缓存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            McaButton(style = McaButtonStyle.Text,
                onClick = onRequestClear,
                loading = busy,
                loadingIndicatorColor = MaterialTheme.colorScheme.primary,
                enabled = entryCount > 0 && interactive,
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
    chatHistoryBusy: Boolean,
    imageLibraryBusy: Boolean,
    fileLibraryBusy: Boolean,
    anyCleanupBusy: Boolean,
    confirmClearChats: Boolean,
    confirmClearImages: Boolean,
    confirmClearFiles: Boolean,
    onRequestClearChats: () -> Unit,
    onRequestClearImages: () -> Unit,
    onRequestClearFiles: () -> Unit
) {
    // The owner serializes cleanups and drops any concurrent request, so all three buttons follow
    // the aggregate: a running prefix-cache clear blocks them too.
    val anyBusy = anyCleanupBusy || chatHistoryBusy || imageLibraryBusy || fileLibraryBusy
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
                McaButton(style = McaButtonStyle.Text,
                    onClick = onRequestClearChats,
                    loading = chatHistoryBusy,
                    loadingIndicatorColor = MaterialTheme.colorScheme.primary,
                    enabled = chatSessionCount > 0 && !anyBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (confirmClearChats) "确认清空聊天" else "清空聊天")
                }
                McaButton(style = McaButtonStyle.Text,
                    onClick = onRequestClearImages,
                    loading = imageLibraryBusy,
                    loadingIndicatorColor = MaterialTheme.colorScheme.primary,
                    enabled = imageAssetCount > 0 && !anyBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (confirmClearImages) "确认清空图片" else "清空图片")
                }
            }
            McaButton(style = McaButtonStyle.Text,
                onClick = onRequestClearFiles,
                loading = fileLibraryBusy,
                loadingIndicatorColor = MaterialTheme.colorScheme.primary,
                enabled = fileAssetCount > 0 && !anyBusy,
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
fun ExperimentsScreen(
    state: SettingsUiState,
    onChatLocalToolsEnabledChanged: (Boolean) -> Unit = {},
    onRichBlocksEnabledChanged: (Boolean) -> Unit = {},
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
            ChatLocalToolsCard(
                enabled = state.chatLocalToolsEnabled,
                onEnabledChanged = onChatLocalToolsEnabledChanged
            )
        }
        item {
            RichBlocksCard(
                enabled = state.richBlocksEnabled,
                onEnabledChanged = onRichBlocksEnabledChanged
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
 * The chat local tool switch. All three tools are local, read-only and side-effect free, so the
 * only real tradeoff is a small amount of extra system text per turn plus the chance that a small
 * model prints a protocol marker it cannot use.
 */
@Composable
private fun ChatLocalToolsCard(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("聊天本地工具", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    McaSwitch(
                        checked = enabled,
                        onCheckedChange = onEnabledChanged,
                        enabled = true,
                        contentDescription = "聊天本地工具"
                    )
                }
                Text(
                    "角色可以读取设备当前时间、做算术计算、掷骰，回答时不再靠猜。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "时间、算式和骰子全部在设备本地执行，不联网、不读写文件、不改动聊天记录；只把结果回填给当轮回复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "本地模型和云端模型都可用。模型不支持时不会调用，会像平常一样回复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Rich-block display switch. Same card/switch/typography as the local-tools card.
 *
 * Display-only: scripts and styles never execute, and every assistant message keeps a
 * "查看原文" escape hatch back to the raw Markdown.
 */
@Composable
private fun RichBlocksCard(
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("富文本块显示", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    McaSwitch(
                        checked = enabled,
                        onCheckedChange = onEnabledChanged,
                        enabled = true,
                        contentDescription = "富文本块显示"
                    )
                }
                Text(
                    "识别 <details>/<status>/<StatusBlock> 这类块并结构化显示，不再是满屏原始标签。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "脚本与样式永不执行：script/style 整段丢弃，事件属性与危险链接不会进入界面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "只影响显示；每条消息仍可点「查看原文」回到 Markdown。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                McaIcons.Description,
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

// ---------------------------------------------------------------------------------------------
// DSH 设备访问授权卡：传感器/定位/手电筒（逐项）+ 短信只读（单独开关 + 每次确认）
//
// 这一块与 app 侧 `AgentDeviceAccessStore` 共用同一个 SharedPreferences 文件与键名；两个模块
// 之间没有编译期依赖（feature:settings 不能依赖 app），键名一致性由 app 侧
// `AgentDeviceAccessSettingsKeyContractTest` 做源码契约校验。全部默认关闭。
// ---------------------------------------------------------------------------------------------

private const val DEVICE_ACCESS_PREFERENCES = "mca_agent_device_access"
private const val DEVICE_ACCESS_SENSOR_KEY_PREFIX = "sensor."
private const val DEVICE_ACCESS_SMS_READ_KEY = "sms.read_enabled"
private const val DEVICE_ACCESS_SMS_SKIP_KEY = "sms.skip_confirmation_session"

private const val PERMISSION_ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"
private const val PERMISSION_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
private const val PERMISSION_READ_SMS = "android.permission.READ_SMS"

/** One row of the sensor authorisation list (ids match `AgentSensorId` on the app side). */
private data class DeviceSensorSetting(
    val id: String,
    val name: String,
    val permission: String?,
    val permissionLabel: String?,
    val note: String
)

private val DEVICE_SENSOR_SETTINGS = listOf(
    DeviceSensorSetting(
        id = "light", name = "光线", permission = null, permissionLabel = null,
        note = "读取一次环境光强度（lux）"
    ),
    DeviceSensorSetting(
        id = "accelerometer", name = "加速度", permission = null, permissionLabel = null,
        note = "读取一次三轴加速度合矢量（m/s²）"
    ),
    DeviceSensorSetting(
        id = "gyroscope", name = "陀螺仪", permission = null, permissionLabel = null,
        note = "读取一次三轴角速度合矢量（rad/s）"
    ),
    DeviceSensorSetting(
        id = "magnetometer", name = "磁力计", permission = null, permissionLabel = null,
        note = "读取一次三轴磁场合矢量（µT）"
    ),
    DeviceSensorSetting(
        id = "barometer", name = "气压", permission = null, permissionLabel = null,
        note = "读取一次大气压（hPa）"
    ),
    DeviceSensorSetting(
        id = "step_counter", name = "步数", permission = PERMISSION_ACTIVITY_RECOGNITION,
        permissionLabel = "身体活动",
        note = "读取今日累计步数；需要「身体活动」权限"
    ),
    DeviceSensorSetting(
        id = "location", name = "定位", permission = PERMISSION_FINE_LOCATION,
        permissionLabel = "精确位置",
        note = "只取最近已知位置，不主动发起新定位；需要「位置」权限"
    ),
    DeviceSensorSetting(
        id = "torch", name = "手电筒", permission = null, permissionLabel = null,
        note = "允许 DSH 开关后置闪光灯（不读取内容）"
    )
)

private fun deviceAccessPermissionGranted(context: Context, permission: String?): Boolean =
    permission == null ||
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openMcaAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun DeviceAccessCard() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(DEVICE_ACCESS_PREFERENCES, Context.MODE_PRIVATE)
    }
    // Re-read permission grants when the user comes back from the system settings page.
    var permissionRevision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionGrants = remember(permissionRevision, context) {
        DEVICE_SENSOR_SETTINGS.associate { it.id to deviceAccessPermissionGranted(context, it.permission) }
    }
    var sensorToggles by remember {
        mutableStateOf(
            DEVICE_SENSOR_SETTINGS.associate { setting ->
                setting.id to preferences.getBoolean(DEVICE_ACCESS_SENSOR_KEY_PREFIX + setting.id, false)
            }
        )
    }
    var pendingSensorId by remember { mutableStateOf<String?>(null) }
    var smsPermissionDenied by remember { mutableStateOf(false) }
    var smsEnabled by remember {
        mutableStateOf(preferences.getBoolean(DEVICE_ACCESS_SMS_READ_KEY, false))
    }
    var smsSkipConfirmation by remember {
        mutableStateOf(preferences.getBoolean(DEVICE_ACCESS_SMS_SKIP_KEY, false))
    }

    fun writeSensor(setting: DeviceSensorSetting, enabled: Boolean) {
        preferences.edit().putBoolean(DEVICE_ACCESS_SENSOR_KEY_PREFIX + setting.id, enabled).apply()
        sensorToggles = sensorToggles + (setting.id to enabled)
    }

    val activityRecognitionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { grantedNow ->
        val sensorId = pendingSensorId
        pendingSensorId = null
        val setting = DEVICE_SENSOR_SETTINGS.firstOrNull { it.id == sensorId }
        if (grantedNow && setting != null) writeSensor(setting, true)
        permissionRevision += 1
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { grantedNow ->
        val sensorId = pendingSensorId
        pendingSensorId = null
        val setting = DEVICE_SENSOR_SETTINGS.firstOrNull { it.id == sensorId }
        if (grantedNow && setting != null) writeSensor(setting, true)
        permissionRevision += 1
    }
    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { grantedNow ->
        smsPermissionDenied = !grantedNow
        if (grantedNow) {
            preferences.edit().putBoolean(DEVICE_ACCESS_SMS_READ_KEY, true).apply()
            smsEnabled = true
        }
        permissionRevision += 1
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("设备传感器与位置（DSH 能力）", fontWeight = FontWeight.Bold)
            Text(
                "默认全部关闭，由你逐项打开。每次请求只读取一次数据，不会持续采集；" +
                    "定位只取最近已知位置，不主动发起新定位以免耗电。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            DEVICE_SENSOR_SETTINGS.forEach { setting ->
                val enabled = sensorToggles[setting.id] == true
                val granted = permissionGrants[setting.id] != false
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(setting.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            setting.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    McaSwitch(
                        checked = enabled,
                        onCheckedChange = { wanted ->
                            if (!wanted) {
                                writeSensor(setting, false)
                            } else if (granted) {
                                writeSensor(setting, true)
                            } else {
                                pendingSensorId = setting.id
                                when (setting.permission) {
                                    PERMISSION_ACTIVITY_RECOGNITION -> activityRecognitionLauncher.launch(
                                        PERMISSION_ACTIVITY_RECOGNITION
                                    )
                                    PERMISSION_FINE_LOCATION -> locationLauncher.launch(PERMISSION_FINE_LOCATION)
                                }
                            }
                        }
                    )
                }
                if (enabled && !granted) {
                    Text(
                        "缺少「${setting.permissionLabel}」权限，DSH 读取时会返回未授权。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        McaButton(style = McaButtonStyle.Text, onClick = {
                            pendingSensorId = setting.id
                            when (setting.permission) {
                                PERMISSION_ACTIVITY_RECOGNITION ->
                                    activityRecognitionLauncher.launch(PERMISSION_ACTIVITY_RECOGNITION)
                                PERMISSION_FINE_LOCATION ->
                                    locationLauncher.launch(PERMISSION_FINE_LOCATION)
                            }
                        }) { Text("授予权限") }
                        McaButton(style = McaButtonStyle.Text, onClick = { openMcaAppSettings(context) }) {
                            Text("去系统设置")
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text("短信只读查询（DSH 能力）", fontWeight = FontWeight.Bold)
            Text(
                "只读查询最近短信（最多 20 条），返回号码后 4 位、接收时间和 120 字预览；" +
                    "不发送、不修改、不写日志、不进备份。需要系统「短信」权限，且每次查询仍需确认。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val smsPermissionGranted = deviceAccessPermissionGranted(context, PERMISSION_READ_SMS)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("允许 DSH 读取短信", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (smsPermissionGranted) "系统权限已授予" else "尚未授予系统「短信」权限",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (smsPermissionGranted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                McaSwitch(
                    checked = smsEnabled && smsPermissionGranted,
                    onCheckedChange = { wanted ->
                        if (!wanted) {
                            preferences.edit().putBoolean(DEVICE_ACCESS_SMS_READ_KEY, false).apply()
                            smsEnabled = false
                        } else {
                            smsPermissionDenied = false
                            smsLauncher.launch(PERMISSION_READ_SMS)
                        }
                    }
                )
            }
            if (!smsPermissionGranted || smsPermissionDenied) {
                Text(
                    "短信权限被拒绝或系统未授予：DSH 查询会返回未授权。可重新授权，或到系统设置中打开。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McaButton(style = McaButtonStyle.Text, onClick = {
                        smsPermissionDenied = false
                        smsLauncher.launch(PERMISSION_READ_SMS)
                    }) { Text("重新授权") }
                    McaButton(style = McaButtonStyle.Text, onClick = { openMcaAppSettings(context) }) {
                        Text("去系统设置")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("本次运行内不再询问短信确认", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关闭时每次查询都会弹一次确认；重启应用后恢复询问。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                McaSwitch(
                    checked = smsSkipConfirmation,
                    enabled = smsEnabled && smsPermissionGranted,
                    onCheckedChange = { wanted ->
                        preferences.edit().putBoolean(DEVICE_ACCESS_SMS_SKIP_KEY, wanted).apply()
                        smsSkipConfirmation = wanted
                    }
                )
            }
            Text(
                "以上能力只服务于 DSH 官方的设备与短信工具；MCA 自身不会在后台读取它们，也不会上传到任何服务器。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
