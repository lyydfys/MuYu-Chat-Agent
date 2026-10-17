package com.muyuchat.feature.settings

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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.muyuchat.core.telemetry.SystemLoadReader
import com.muyuchat.core.telemetry.SystemLoadSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun RuntimeScreen(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    offlineTranslationStatus: String = "未安装",
    offlineTranslationInstalled: Boolean = false,
    offlineTranslationInstalling: Boolean = false,
    offlineTranslationDownloading: Boolean = false,
    offlineTranslationDownloadProgress: Float? = null,
    onImportOfflineTranslation: () -> Unit = {},
    onCancelOfflineTranslationImport: () -> Unit = {},
    onDownloadOfflineTranslation: () -> Unit = {},
    onCancelOfflineTranslationDownload: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    onOpenRelease: () -> Unit = {},
    onAutoCheckChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var systemLoad by remember { mutableStateOf(SystemLoadSnapshot()) }
    LaunchedEffect(Unit) {
        val reader = SystemLoadReader(context.applicationContext)
        while (true) {
            systemLoad = withContext(Dispatchers.IO) { reader.read() }
            delay(1000L)
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("运行状态", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("离线图片提示词翻译 · Hy-MT2", fontWeight = FontWeight.SemiBold)
                Text(
                    offlineTranslationStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (offlineTranslationStatus.contains("失败") || offlineTranslationStatus.contains("错误")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (offlineTranslationDownloading) {
                    val progress = offlineTranslationDownloadProgress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("下载进度 ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (offlineTranslationDownloading || !offlineTranslationInstalled) {
                        Button(
                            onClick = if (offlineTranslationDownloading) onCancelOfflineTranslationDownload else onDownloadOfflineTranslation,
                            enabled = offlineTranslationDownloading || !offlineTranslationInstalling,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(if (offlineTranslationDownloading) Icons.Default.Close else Icons.Default.Download, contentDescription = null)
                            Text(
                                if (offlineTranslationDownloading) "取消下载" else if (offlineTranslationStatus.contains("下载失败")) "重试下载" else "下载",
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    } else {
                        Text(
                            "已安装",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedButton(
                        onClick = if (offlineTranslationInstalling) onCancelOfflineTranslationImport else onImportOfflineTranslation,
                        enabled = !offlineTranslationDownloading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(if (offlineTranslationInstalling) Icons.Default.Close else Icons.Default.FileDownload, contentDescription = null)
                        Text(if (offlineTranslationInstalling) "取消导入" else "本地导入", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        item {
            AppUpdateCard(
                state = state.appUpdate,
                onCheckUpdate = onCheckUpdate,
                onDownloadUpdate = onDownloadUpdate,
                onInstallUpdate = onInstallUpdate,
                onOpenRelease = onOpenRelease,
                onAutoCheckChanged = onAutoCheckChanged
            )
        }
        item {
            InfoCard(
                "当前模型",
                state.stats.modelPath?.substringAfterLast('/') ?: "未加载",
                if (state.stats.loaded) buildString {
                    append("模型已加载。")
                    val components = runCatching {
                        JSONObject(state.nativeStatsJson).optJSONObject("componentBackends")
                    }.getOrNull()
                    for ((componentId, label) in listOf("llm" to "LLM", "vision" to "视觉", "audio" to "音频")) {
                        val component = components?.optJSONObject(componentId) ?: continue
                        val selected = (component.opt("selected") as? String)
                            ?.takeIf { it.isNotBlank() } ?: when (component.optString("selectionSource")) {
                                "sdk_model_default" -> "SDK/模型默认"
                                "not_submitted" -> "未提交"
                                else -> "未知"
                            }
                        val actual = (component.opt("actual") as? String)
                            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) } ?: "未知"
                        append('\n').append(label).append("：选择 ").append(selected)
                            .append("；实际 ").append(actual)
                    }
                } else "请先在模型页加载模型。"
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
            val cpu = systemLoad.systemCpuPercent?.let { "$it%" } ?: "不可用"
            val processCpu = systemLoad.processCpuPercent?.let { "$it%" } ?: "不可用"
            val memory = if (systemLoad.memoryTotalBytes > 0L) {
                "${formatBytes(systemLoad.memoryUsedBytes)} / ${formatBytes(systemLoad.memoryTotalBytes)}"
            } else {
                "不可用"
            }
            val gpu = systemLoad.gpuPercent?.let { "$it%" } ?: "不可用"
            val npuExecutionProven = runCatching {
                JSONObject(state.nativeStatsJson).optBoolean("npuExecutionProven", false)
            }.getOrDefault(false)
            val npu = systemLoad.npuPercent?.let { "$it%" } ?:
                if (npuExecutionProven) "已执行（无公开占用率）" else "不可用"
            InfoCard(
                "实时负载",
                "CPU $cpu · 进程 $processCpu · 内存 $memory",
                "GPU $gpu${systemLoad.gpuSource?.let { " ($it)" }.orEmpty()} · NPU $npu"
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
private fun AppUpdateCard(
    state: AppUpdateSettingsUiState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenRelease: () -> Unit,
    onAutoCheckChanged: (Boolean) -> Unit
) {
    val normalizedStatus = state.status.trim().uppercase()
    val checking = normalizedStatus == "CHECKING" || normalizedStatus.contains("检查")
    val downloading = normalizedStatus == "DOWNLOADING" || normalizedStatus.contains("下载")
    val verifying = normalizedStatus == "VERIFYING" || normalizedStatus.contains("校验")
    val readyToInstall = normalizedStatus == "READY_TO_INSTALL" ||
        normalizedStatus == "READY" ||
        normalizedStatus.contains("可安装")
    val updateAvailable = normalizedStatus == "AVAILABLE" || normalizedStatus.contains("可更新")
    val retryableDownload = state.canDownload && normalizedStatus == "ERROR"
    val hasUpdate = (updateAvailable && state.canDownload) || retryableDownload || downloading || verifying || readyToInstall
    val statusLabel = when (normalizedStatus) {
        "IDLE" -> "未检查"
        "CHECKING" -> "检查中"
        "UP_TO_DATE" -> "已是最新稳定版"
        "AVAILABLE" -> "有可用更新"
        "DOWNLOADING" -> "下载中"
        "VERIFYING" -> "校验中"
        "READY_TO_INSTALL" -> "可安装"
        "ERROR" -> "失败"
        else -> state.status.ifBlank { "未检查" }
    }
    val notes = state.releaseNotes.trim().let { value ->
        if (value.length > 1200) value.take(1197).trimEnd() + "..." else value
    }

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
                    Text("应用更新", fontWeight = FontWeight.Bold)
                    Text(
                        "从官方 GitHub Releases 检查并安装稳定版更新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.autoCheckEnabled,
                    onCheckedChange = onAutoCheckChanged
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoLine(
                    label = "当前版本",
                    value = state.currentVersionName.ifBlank { "未知" },
                )
                InfoLine(
                    label = "最新版本",
                    value = state.latestVersionName ?: "尚未检查",
                )
            }
            if (!state.latestTitle.isNullOrBlank()) {
                Text(state.latestTitle.orEmpty(), fontWeight = FontWeight.SemiBold)
            }
            Text(
                "状态：$statusLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.lastCheckedText.isNotBlank()) {
                Text(
                    "上次检查：${state.lastCheckedText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.apkSizeText.isNotBlank()) {
                Text(
                    if (state.downloadTotalBytes > 0L && state.downloadedBytes > 0L) {
                        "安装包：${state.apkSizeText} · 已下载 ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.downloadTotalBytes)}"
                    } else {
                        "安装包：${state.apkSizeText}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (normalizedStatus == "ERROR" || normalizedStatus.contains("失败")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (notes.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("更新说明", fontWeight = FontWeight.SemiBold)
                    Text(notes, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCheckUpdate,
                    enabled = !checking && !downloading && !verifying && !readyToInstall,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("检查", modifier = Modifier.padding(start = 6.dp))
                }
                Button(
                    onClick = onDownloadUpdate,
                    enabled = hasUpdate && !checking && !downloading && !verifying && !readyToInstall,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text("下载", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onInstallUpdate,
                    enabled = readyToInstall && !verifying,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.InstallMobile, contentDescription = null)
                    Text("安装", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = onOpenRelease,
                    enabled = state.releaseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Text("打开 Release", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
