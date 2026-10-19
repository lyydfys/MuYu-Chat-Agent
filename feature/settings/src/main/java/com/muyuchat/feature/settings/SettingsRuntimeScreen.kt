package com.muyuchat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
    val lifecycleOwner = LocalLifecycleOwner.current
    // P08/W9-1: the native stats JSON is parsed once per distinct full value. The previous
    // composition-time JSONObject(...) ran on every system-load tick (1 Hz) and on every
    // download-progress recomposition, which is exactly the parse-on-recompose cost this slice
    // removes. The key is the complete JSON value, never the loaded model id.
    val nativeStats = remember(state.nativeStatsJson) {
        runtimeNativeStatsPresentation(state.nativeStatsJson)
    }
    // W9-1: the translation status is a small string, but its keyword scan belongs to a mapping
    // step keyed by the status value, not to every recomposition of the scrollable runtime list.
    val offlineStatus = remember(offlineTranslationStatus) {
        RuntimeOfflineTranslationStatus(
            needsAttention = offlineTranslationStatus.contains("失败") ||
                offlineTranslationStatus.contains("错误"),
            downloadFailed = offlineTranslationStatus.contains("下载失败")
        )
    }
    var systemLoad by remember { mutableStateOf(SystemLoadSnapshot()) }
    LaunchedEffect(lifecycleOwner) {
        val reader = SystemLoadReader(context.applicationContext)
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                systemLoad = withContext(Dispatchers.IO) { reader.read() }
                delay(1000L)
            }
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
                    color = if (offlineStatus.needsAttention) {
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
                            Icon(if (offlineTranslationDownloading) Icons.Rounded.Close else Icons.Rounded.Download, contentDescription = null)
                            Text(
                                if (offlineTranslationDownloading) "取消下载" else if (offlineStatus.downloadFailed) "重试下载" else "下载",
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
                        Icon(if (offlineTranslationInstalling) Icons.Rounded.Close else Icons.Rounded.FileDownload, contentDescription = null)
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
            val modelText = remember(state.stats.modelPath, state.stats.loaded, nativeStats) {
                RuntimeModelText(
                    name = state.stats.modelPath?.substringAfterLast('/') ?: "未加载",
                    details = if (state.stats.loaded) {
                        buildString {
                            append("模型已加载。")
                            append(nativeStats.componentSummary)
                        }
                    } else {
                        "请先在模型页加载模型。"
                    }
                )
            }
            InfoCard(
                "当前模型",
                modelText.name,
                modelText.details
            )
        }
        item {
            val performanceText = remember(state.stats) {
                RuntimePerformanceText(
                    primary = "首 token ${state.stats.ttftMs} ms · 速度 ${"%.2f".format(state.stats.decodeTps)} token/s",
                    secondary = "已输出 ${state.stats.completionTokens} 个片段，输入约 ${state.stats.promptTokens} 个 token。"
                )
            }
            InfoCard("性能", performanceText.primary, performanceText.secondary)
        }
        item {
            val memoryText = remember(state.stats) {
                val rssKb = state.stats.processRssKb.takeIf { it > 0L }
                RuntimeMemoryText(
                    primary = "RSS ${formatKb(rssKb ?: state.stats.nativePssKb)} · PSS ${formatKb(state.stats.nativePssKb)}",
                    secondary = "Native 堆 ${formatKb(state.stats.nativeHeapKb)} · Java 堆 ${formatKb(state.stats.javaHeapKb)} · 系统可用 ${formatKb(state.stats.availMemKb)} · 运行预算 ${formatKb(state.stats.modelMemoryBudgetKb)}"
                )
            }
            InfoCard("内存", memoryText.primary, memoryText.secondary)
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
            val npu = systemLoad.npuPercent?.let { "$it%" } ?:
                if (nativeStats.npuExecutionProven) "已执行（无公开占用率）" else "不可用"
            InfoCard(
                "实时负载",
                "CPU $cpu · 进程 $processCpu · 内存 $memory",
                "GPU $gpu${systemLoad.gpuSource?.let { " ($it)" }.orEmpty()} · NPU $npu"
            )
        }
        item {
            val error = state.stats.lastError
            val diagnosticText = remember(error) {
                RuntimeDiagnosticText(
                    title = if (error.isNullOrBlank()) "诊断" else "最近问题",
                    primary = if (error.isNullOrBlank()) "运行正常" else friendlyError(error),
                    secondary = if (error.isNullOrBlank()) {
                        "需要排错时，可到“日志与接口”导出完整诊断。"
                    } else {
                        "完整信息已保留在诊断报告中。"
                    }
                )
            }
            InfoCard(
                title = diagnosticText.title,
                primary = diagnosticText.primary,
                secondary = diagnosticText.secondary
            )
        }
    }
}

private class RuntimeOfflineTranslationStatus(
    val needsAttention: Boolean,
    val downloadFailed: Boolean
)

/** Presentation-only projection of the runtime stats cards; computed once per stats value. */
private class RuntimeModelText(val name: String, val details: String)

private class RuntimePerformanceText(val primary: String, val secondary: String)

private class RuntimeMemoryText(val primary: String, val secondary: String)

private class RuntimeDiagnosticText(val title: String, val primary: String, val secondary: String)

/**
 * W9-1/P08: parse the native runtime stats JSON once per distinct full value.
 *
 * This deliberately keys off the whole JSON string (not the loaded model id): the renderer can
 * publish new component backend selections without the model id changing. Runs outside composition
 * in a `remember`, so the 1 Hz system-load sampler and download-progress updates no longer trigger
 * a JSON parse.
 */
private class RuntimeNativeStatsPresentation(
    val componentSummary: String,
    val npuExecutionProven: Boolean
)

private fun runtimeNativeStatsPresentation(json: String): RuntimeNativeStatsPresentation {
    val root = runCatching { JSONObject(json) }.getOrNull()
    val components = root?.optJSONObject("componentBackends")
    val componentSummary = buildString {
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
    }
    return RuntimeNativeStatsPresentation(
        componentSummary = componentSummary,
        npuExecutionProven = root?.optBoolean("npuExecutionProven", false) ?: false
    )
}

private class AppUpdateStatusPresentation(
    val checking: Boolean,
    val downloading: Boolean,
    val verifying: Boolean,
    val readyToInstall: Boolean,
    val hasUpdate: Boolean,
    val statusLabel: String,
    val statusIsError: Boolean
)

/**
 * W9-1: the update card used to re-scan the status/notes strings on every recomposition
 * (download progress can update several times per second). Map it once per status value instead.
 */
private fun appUpdateStatusPresentation(status: String, canDownload: Boolean): AppUpdateStatusPresentation {
    val normalizedStatus = status.trim().uppercase()
    val checking = normalizedStatus == "CHECKING" || normalizedStatus.contains("检查")
    val downloading = normalizedStatus == "DOWNLOADING" || normalizedStatus.contains("下载")
    val verifying = normalizedStatus == "VERIFYING" || normalizedStatus.contains("校验")
    val readyToInstall = normalizedStatus == "READY_TO_INSTALL" ||
        normalizedStatus == "READY" ||
        normalizedStatus.contains("可安装")
    val updateAvailable = normalizedStatus == "AVAILABLE" || normalizedStatus.contains("可更新")
    val retryableDownload = canDownload && normalizedStatus == "ERROR"
    return AppUpdateStatusPresentation(
        checking = checking,
        downloading = downloading,
        verifying = verifying,
        readyToInstall = readyToInstall,
        hasUpdate = (updateAvailable && canDownload) || retryableDownload || downloading || verifying || readyToInstall,
        statusLabel = when (normalizedStatus) {
            "IDLE" -> "未检查"
            "CHECKING" -> "检查中"
            "UP_TO_DATE" -> "已是最新稳定版"
            "AVAILABLE" -> "有可用更新"
            "DOWNLOADING" -> "下载中"
            "VERIFYING" -> "校验中"
            "READY_TO_INSTALL" -> "可安装"
            "ERROR" -> "失败"
            else -> status.ifBlank { "未检查" }
        },
        statusIsError = normalizedStatus == "ERROR" || normalizedStatus.contains("失败")
    )
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
    val presentation = remember(state.status, state.canDownload) {
        appUpdateStatusPresentation(state.status, state.canDownload)
    }
    val statusLabel = presentation.statusLabel
    val checking = presentation.checking
    val downloading = presentation.downloading
    val verifying = presentation.verifying
    val readyToInstall = presentation.readyToInstall
    val hasUpdate = presentation.hasUpdate
    val notes = remember(state.releaseNotes) {
        state.releaseNotes.trim().let { value ->
            if (value.length > 1200) value.take(1197).trimEnd() + "..." else value
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Switch on the title row; at 2.0x font scale a vertically centred switch used to sit
            // on top of the two-line description.
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("应用更新", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.autoCheckEnabled,
                        onCheckedChange = onAutoCheckChanged
                    )
                }
                Text(
                    "从官方 GitHub Releases 检查并安装稳定版更新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = if (presentation.statusIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (notes.isNotBlank()) {
                ReleaseNotes(notes)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCheckUpdate,
                    enabled = !checking && !downloading && !verifying && !readyToInstall,
                    modifier = Modifier.weight(1f)
                ) {
                    // Same-size spinner while checking keeps button geometry and adds busy feedback.
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                    Text("检查", modifier = Modifier.padding(start = 6.dp))
                }
                Button(
                    onClick = onDownloadUpdate,
                    enabled = hasUpdate && !checking && !downloading && !verifying && !readyToInstall,
                    modifier = Modifier.weight(1f)
                ) {
                    // Same-size spinner while downloading; the label and button size are unchanged.
                    if (downloading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                    }
                    Text("下载", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onInstallUpdate,
                    enabled = readyToInstall && !verifying,
                    modifier = Modifier.weight(1f)
                ) {
                    // Same-size spinner while verifying the downloaded update.
                    if (verifying) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.InstallMobile, contentDescription = null)
                    }
                    Text("安装", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = onOpenRelease,
                    enabled = state.releaseUrl.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                    Text("打开 Release", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

private enum class ReleaseNoteKind { HEADING, SECTION, BULLET, BODY }

private data class ReleaseNoteLine(val kind: ReleaseNoteKind, val text: String)

private val releaseNoteTableRule = Regex("^[|\\s:-]*-[|\\s:-]*$")

/**
 * GitHub release bodies arrive as Markdown. Rendering the raw text (`#`, `##`, `-`, `| a | b |`)
 * in the update card reads as broken copy, so flatten the subset the project actually writes:
 * headings become section labels, bullets stay bullets, hard-wrapped body lines merge into one
 * paragraph and pipe tables become label/value lines. The version heading is dropped because the
 * card already shows the version above.
 */
private fun releaseNoteLines(raw: String): List<ReleaseNoteLine> {
    val lines = mutableListOf<ReleaseNoteLine>()
    val rawLines = raw.lineSequence().toList()
    for ((index, rawLine) in rawLines.withIndex()) {
        val line = rawLine.trimEnd()
        val indent = line.length - line.trimStart().length
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        if (releaseNoteTableRule.matches(trimmed)) continue
        if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
            // A row followed by a separator is the table header; the card does not need it.
            val nextIsRule = rawLines.getOrNull(index + 1)?.trim()?.let(releaseNoteTableRule::matches) == true
            if (nextIsRule) continue
            val cells = trimmed.trim('|').split('|').map { releaseNoteText(it) }.filter { it.isNotBlank() }
            if (cells.isNotEmpty()) lines += ReleaseNoteLine(ReleaseNoteKind.BODY, cells.joinToString("："))
            continue
        }
        val section = when {
            trimmed.startsWith("#### ") -> trimmed.removePrefix("#### ")
            trimmed.startsWith("### ") -> trimmed.removePrefix("### ")
            trimmed.startsWith("## ") -> trimmed.removePrefix("## ")
            else -> null
        }
        if (section != null) {
            lines += ReleaseNoteLine(ReleaseNoteKind.SECTION, releaseNoteText(section))
            continue
        }
        if (trimmed.startsWith("# ")) continue
        val bullet = when {
            trimmed.startsWith("- ") -> trimmed.drop(2)
            trimmed.startsWith("* ") -> trimmed.drop(2)
            trimmed.startsWith("• ") -> trimmed.drop(2)
            else -> null
        }
        if (bullet != null) {
            lines += ReleaseNoteLine(ReleaseNoteKind.BULLET, releaseNoteText(bullet))
            continue
        }
        val continuation = indent >= 2 && lines.isNotEmpty() &&
            (lines.last().kind == ReleaseNoteKind.BULLET || lines.last().kind == ReleaseNoteKind.BODY)
        if (continuation) {
            val previous = lines.last()
            lines[lines.size - 1] = previous.copy(text = previous.text + " " + releaseNoteText(trimmed))
            continue
        }
        if (lines.isNotEmpty() && lines.last().kind == ReleaseNoteKind.BODY) {
            val previous = lines.last()
            lines[lines.size - 1] = previous.copy(text = previous.text + " " + releaseNoteText(trimmed))
        } else {
            lines += ReleaseNoteLine(ReleaseNoteKind.BODY, releaseNoteText(trimmed))
        }
    }
    return lines.take(60)
}

private fun releaseNoteText(value: String): String = value
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("__(.+?)__"), "$1")
    .replace(Regex("`(.+?)`"), "$1")
    .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")
    .trim()

private const val RELEASE_NOTES_COLLAPSED_LINES = 6

@Composable
private fun ReleaseNotes(notes: String) {
    val lines = remember(notes) { releaseNoteLines(notes) }
    if (lines.isEmpty()) return
    // A full changelog is long; rendering every line on the first frame is both a scan problem and
    // the main composition cost when settings opens. Show a digest and expand on demand.
    var expanded by rememberSaveable(notes) { mutableStateOf(false) }
    val visibleLines = if (expanded) lines else lines.take(RELEASE_NOTES_COLLAPSED_LINES)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("更新说明", fontWeight = FontWeight.SemiBold)
        visibleLines.forEach { line ->
            when (line.kind) {
                ReleaseNoteKind.SECTION -> Text(
                    line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
                ReleaseNoteKind.BULLET -> Row(verticalAlignment = Alignment.Top) {
                    Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(line.text, style = MaterialTheme.typography.bodySmall)
                }
                else -> Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (lines.size > RELEASE_NOTES_COLLAPSED_LINES) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开全部")
            }
        }
    }
}
