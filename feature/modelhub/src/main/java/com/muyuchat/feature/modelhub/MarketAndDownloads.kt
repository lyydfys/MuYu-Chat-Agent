@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.ModelScopeHubModel
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.RemoteModelFileKind
import com.muyuchat.core.download.fileKind
import com.muyuchat.core.download.isChatModelCandidate
import com.muyuchat.core.download.isImageModelCandidate
import com.muyuchat.core.download.isLiteRtLmModelCandidate
import com.muyuchat.core.download.isVisionModelCandidate
import com.muyuchat.core.download.kindLabel
import com.muyuchat.core.designsystem.McaMotion

@Composable
internal fun MarketSection(
    state: ModelHubUiState,
    onHubQueryChange: (String) -> Unit,
    onSearchHubModels: (Boolean) -> Unit,
    onShowFiles: (ModelScopeHubModel) -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier
) {
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("搜索魔塔公开模型。下载后仍保存到 MCA 的受管模型目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.hubQuery,
                    onValueChange = onHubQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("搜索模型") }
                )
                IconButton(onClick = { onSearchHubModels(true) }, enabled = !state.isBusy) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索")
                }
            }
        }
        if (state.hubModels.isNotEmpty()) {
            item { Text("已显示 ${state.hubModels.size}/${state.hubTotalCount} · 第 ${state.hubPage} 页", style = MaterialTheme.typography.bodySmall) }
            items(state.hubModels, key = { it.id }) { model ->
                Box(
                    modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
                ) {
                    HubModelCard(
                        model = model,
                        enabled = !state.isBusy,
                        onShowFiles = { onShowFiles(model) },
                        onOpenPage = { onOpenPage(model.modelPageUrl) }
                    )
                }
            }
            item {
                Button(
                    onClick = { onSearchHubModels(false) },
                    enabled = !state.isBusy && state.hubModels.size < state.hubTotalCount,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text("下一页")
                }
            }
        }
    }
}

@Composable
internal fun RemoteFilesSection(
    state: ModelHubUiState,
    onImportClick: () -> Unit,
    onPauseImport: (String) -> Unit,
    onResumeImport: (String) -> Unit,
    onRepoInputChange: (String) -> Unit,
    onFetchRemoteFiles: () -> Unit,
    onDownload: (RemoteModelFile) -> Unit,
    modifier: Modifier
) {
    var fileFilter by rememberSaveable { mutableStateOf("") }
    // W9: filtering the remote file list is a per-item string scan. It only depends on the file
    // list and the filter, so a download-progress recomposition must not redo it.
    val visibleFiles = remember(state.remoteFiles, fileFilter) {
        filterRemoteModelFiles(state.remoteFiles, fileFilter)
    }
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.importTasks, key = { "import-${it.id}" }) { task ->
            Box(
                modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
            ) {
                ModelImportProgressCard(task, onPauseImport, onResumeImport)
            }
        }
        item {
            Button(onClick = onImportClick, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(999.dp)) {
                Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入本地推理引擎")
            }
        }
        item {
            Text("支持 GGUF / LiteRT-LM 文件、MNN 完整目录或 ZIP。多选 MNN 文件时请包含配置引用的全部组件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.repoInput,
                    onValueChange = onRepoInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("模型 ID 或链接") }
                )
                IconButton(onClick = onFetchRemoteFiles, enabled = !state.isBusy) {
                    Icon(Icons.Rounded.Search, contentDescription = "查找远程推理文件")
                }
            }
        }
        if (state.remoteFiles.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = fileFilter,
                    onValueChange = { fileFilter = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("筛选当前文件") },
                    placeholder = { Text("文件名、路径、类型或来源") },
                    trailingIcon = {
                        if (fileFilter.isNotEmpty()) {
                            IconButton(onClick = { fileFilter = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清除筛选")
                            }
                        }
                    }
                )
            }
        }
        if (state.remoteFiles.isEmpty()) {
            item { EmptyCard("暂无文件", "可从推荐或广场读取文件列表。") }
        } else if (visibleFiles.isEmpty()) {
            item { EmptyCard("没有匹配文件", "清除筛选或换一个文件名、路径、类型关键词。") }
        } else {
            items(visibleFiles, key = { it.path }) { file ->
                Box(
                    modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
                ) {
                    RemoteFileCard(file = file, enabled = !state.isBusy, onDownload = { onDownload(file) })
                }
            }
        }
    }
}

@Composable
internal fun DownloadProgressPanel(
    state: ModelHubUiState,
    onShowDetails: () -> Unit,
    onPauseDownloads: () -> Unit,
    onResumeDownloads: () -> Unit
) {
    val total = state.downloadTotalBytes
    val downloaded = state.downloadedBytes
    val progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    // The animation is read only inside the draw-phase progress lambda below, so the 350 ms tween
    // never drives layout or composition; only the target float changes per download tick.
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 350),
        label = "downloadProgress"
    )
    // W9: status/percentage/size formatting is mapped from its real inputs, not rebuilt on every
    // recomposition caused by unrelated model-hub state (busy, selection, status text).
    val percentText = remember(state.downloadStatus, state.downloadPhase, progress, total) {
        when (state.downloadStatus) {
            DownloadStatus.FAILED -> "失败"
            DownloadStatus.PAUSED -> "已暂停"
            DownloadStatus.DONE -> "完成"
            else -> when (state.downloadPhase) {
                ModelHubDownloadPhase.INSTALLING,
                ModelHubDownloadPhase.INTEGRITY_CHECK,
                ModelHubDownloadPhase.EXECUTION_CHECK -> state.downloadPhase?.label ?: "处理中"
                else -> if (total > 0L) "%.1f%%".format(progress * 100f) else "下载中"
            }
        }
    }
    val totalText = remember(total) { if (total > 0L) formatBytes(total) else "未知大小" }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 3.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    state.downloadFileName.orEmpty(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(percentText, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                if (state.downloadStatus == DownloadStatus.RUNNING || state.downloadStatus == DownloadStatus.QUEUED) {
                    TextButton(
                        onClick = onPauseDownloads,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        // W9 accessibility: keep the dense look while guaranteeing a >= 44dp target.
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) { Text("暂停", style = MaterialTheme.typography.labelMedium) }
                }
                if (state.downloadStatus == DownloadStatus.PAUSED || state.downloadStatus == DownloadStatus.FAILED) {
                    TextButton(
                        onClick = onResumeDownloads,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) { Text(if (state.downloadStatus == DownloadStatus.FAILED) "重试" else "继续",
                        style = MaterialTheme.typography.labelMedium) }
                }
                IconButton(
                    onClick = onShowDetails,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = "查看下载详情",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (total > 0L || state.downloadStatus == DownloadStatus.FAILED ||
                state.downloadStatus == DownloadStatus.PAUSED || state.downloadStatus == DownloadStatus.DONE
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            val transferSummary = remember(
                state.downloadSpeedBytesPerSecond,
                state.downloadRemainingSeconds
            ) {
                buildString {
                    if (state.downloadSpeedBytesPerSecond > 0L) {
                        append(formatDownloadRate(state.downloadSpeedBytesPerSecond))
                    }
                    state.downloadRemainingSeconds?.let { seconds ->
                        if (isNotEmpty()) append(" · ")
                        append("剩余约 ").append(formatDuration(seconds))
                    }
                }
            }
            AnimatedVisibility(
                visible = transferSummary.isNotEmpty() && state.downloadStatus == DownloadStatus.RUNNING,
                enter = fadeIn(animationSpec = McaMotion.fade()) +
                    expandVertically(animationSpec = McaMotion.sizeChangePx),
                exit = fadeOut(animationSpec = McaMotion.fade()) +
                    shrinkVertically(animationSpec = McaMotion.sizeChangePx)
            ) {
                Text(
                    transferSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AnimatedVisibility(
                visible = state.downloadStatus == DownloadStatus.FAILED,
                enter = fadeIn(animationSpec = McaMotion.fade()) +
                    expandVertically(animationSpec = McaMotion.sizeChangePx),
                exit = fadeOut(animationSpec = McaMotion.fade()) +
                    shrinkVertically(animationSpec = McaMotion.sizeChangePx)
            ) {
                val failureText = remember(state.downloadFailureSource, state.statusMessage) {
                    listOfNotNull(state.downloadFailureSource?.label, state.statusMessage)
                        .joinToString("：").ifBlank { "下载失败，请查看详情后重试。" }
                }
                Text(
                    failureText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun DownloadDetailsDialog(
    state: ModelHubUiState,
    onDismiss: () -> Unit,
    onOpenLocalModels: () -> Unit,
    onResumeDownloads: () -> Unit
) {
    val context = LocalContext.current
    val total = state.downloadTotalBytes
    val downloaded = state.downloadedBytes
    val totalText = remember(total) { if (total > 0L) formatBytes(total) else "未知大小" }
    val stageLabel = if (state.downloadStatus == DownloadStatus.RUNNING || state.downloadStatus == DownloadStatus.QUEUED) {
        state.downloadPhase?.label ?: state.downloadStatus.downloadStatusLabel()
    } else {
        state.downloadStatus.downloadStatusLabel()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                state.downloadFileName.orEmpty(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(min = 164.dp, max = 280.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "$stageLabel · 已下载 ${formatBytes(downloaded)} / $totalText",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.downloadStatus == DownloadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (state.downloadStatus == DownloadStatus.RUNNING &&
                    (state.downloadPhase == null || state.downloadPhase == ModelHubDownloadPhase.DOWNLOADING)) {
                    Text(
                        "当前速率：" + if (state.downloadSpeedBytesPerSecond > 0L) {
                            formatDownloadRate(state.downloadSpeedBytesPerSecond)
                        } else "正在估算",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "预计剩余下载时间：" + when {
                            total <= 0L -> "总大小未知"
                            state.downloadRemainingSeconds != null -> "约 " + formatDuration(state.downloadRemainingSeconds)
                            downloaded >= total -> "下载完成，正在处理文件"
                            else -> "正在估算"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DownloadCheckLine("文件完整性", state.downloadIntegrityStatus, state.downloadIntegrityMessage)
                DownloadCheckLine("本机执行兼容性", state.downloadExecutionStatus, state.downloadExecutionMessage)
                if (state.downloadStatus == DownloadStatus.FAILED) {
                    state.statusMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (state.downloadStatus == DownloadStatus.FAILED || state.downloadStatus == DownloadStatus.PAUSED) {
                TextButton(onClick = onResumeDownloads) {
                    Text(if (state.downloadStatus == DownloadStatus.FAILED) "重试" else "继续")
                }
            } else if (state.downloadStatus == DownloadStatus.DONE && !state.isBusy) {
                TextButton(onClick = onOpenLocalModels) { Text("查看本地模型") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("模型下载诊断", downloadDiagnostics(state)))
            }) {
                // 行内图标统一 18dp，16dp 是全模块孤例
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("复制诊断")
            }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun downloadDiagnostics(state: ModelHubUiState): String = buildString {
    appendLine("任务: ${state.downloadTaskId ?: "未知"}")
    appendLine("文件: ${state.downloadFileName ?: "未知"}")
    appendLine("状态: ${state.downloadStatus ?: "UNKNOWN"}")
    appendLine("阶段: ${state.downloadPhase ?: "UNKNOWN"}")
    state.downloadFailureSource?.let { appendLine("失败来源: $it") }
    appendLine("进度: ${state.downloadedBytes}/${state.downloadTotalBytes} bytes")
    appendLine("当前速率: ${state.downloadSpeedBytesPerSecond} bytes/s")
    appendLine("预计剩余下载时间: ${state.downloadRemainingSeconds?.let { "$it 秒" } ?: "未知"}")
    appendLine("文件完整性: ${state.downloadIntegrityStatus}: ${state.downloadIntegrityMessage.orEmpty()}")
    appendLine("本机执行: ${state.downloadExecutionStatus}: ${state.downloadExecutionMessage.orEmpty()}")
    state.statusMessage?.let { appendLine("说明: $it") }
}

@Composable
private fun DownloadCheckLine(title: String, status: String, message: String?) {
    val normalized = status.uppercase()
    // Missing acceptance/metadata alone is internal state, not a user warning.
    if (normalized == "EXPERIMENTAL" && message.isNullOrBlank()) return
    val label = when (normalized) {
        "PASSED" -> "通过"
        "PREFLIGHT_PASSED" -> "预检通过"
        "FAILED" -> "失败"
        "PENDING" -> "检查中"
        "RUNNING" -> "校验中"
        "EXPERIMENTAL" -> "运行提示"
        "NOT_APPLICABLE" -> "不适用"
        else -> "待检查"
    }
    val color = when (normalized) {
        "FAILED" -> MaterialTheme.colorScheme.error
        "PASSED", "PREFLIGHT_PASSED" -> MaterialTheme.colorScheme.primary
        "EXPERIMENTAL" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        buildString {
            append(title).append("：").append(label)
            message?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        },
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun HubModelCard(
    model: ModelScopeHubModel,
    enabled: Boolean,
    onShowFiles: () -> Unit,
    onOpenPage: () -> Unit
) {
    CardBox {
        Text(shortName(model.displayName), fontWeight = FontWeight.Bold)
        Text("${formatBytes(model.fileSizeBytes)} · 下载 ${model.downloads} · 收藏 ${model.likes} · ${model.license ?: "未知许可"}", style = MaterialTheme.typography.bodySmall)
        val tags = remember(model.tags) {
            model.tags.filter {
                it.contains("gguf", ignoreCase = true) ||
                    it.contains("mnn", ignoreCase = true) ||
                    it.startsWith("task:")
            }.take(3)
        }
        if (tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag -> StaticStatusTag(tag.substringAfter(':')) }
            }
        }
        if (model.private || model.gated) {
            Text("该模型可能需要登录或访问授权。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShowFiles, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) {
                Text("读取文件")
            }
            OutlinedButton(onClick = onOpenPage, modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) {
                Text("打开页面")
            }
        }
    }
}

// Preserve chip-equivalent density without exposing click semantics.
@Composable
private fun StaticStatusTag(text: String) {
    Surface(
        modifier = Modifier.heightIn(min = 32.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RemoteFileCard(file: RemoteModelFile, enabled: Boolean, onDownload: () -> Unit) {
    val kind = file.fileKind()
    val isProjector = kind == RemoteModelFileKind.PROJECTOR
    val isLiteRtLm = file.isLiteRtLmModelCandidate()
    val isDownloadableModel =
        file.isChatModelCandidate() || file.isVisionModelCandidate() || file.isImageModelCandidate() || isProjector
    CardBox {
        Text(shortName(file.name), fontWeight = FontWeight.Bold)
        Text("${file.kindLabel()} · ${file.sizeBytes?.let(::formatBytes) ?: "未知大小"}", style = MaterialTheme.typography.bodySmall)
        Text("来源：${file.provider.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!isDownloadableModel) {
            Text("这是辅助文件，不适合作为推理引擎加载。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else if (isProjector) {
            Text("下载后会绑定到当前已加载的本地多模态主模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (isLiteRtLm) {
            Text("这是独立 LiteRT-LM 容器，不是 GGUF；下载后会按 LiteRT-LM 运行时注册。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onDownload, enabled = enabled && isDownloadableModel, shape = RoundedCornerShape(999.dp)) {
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    isProjector -> "下载并绑定"
                    isDownloadableModel -> "下载到本机"
                    else -> "辅助文件"
                }
            )
        }
    }
}

private fun formatDownloadRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024L * 1024L -> "%.2f GB/s".format(bytesPerSecond / (1024.0 * 1024.0 * 1024.0))
    bytesPerSecond >= 1024L * 1024L -> "%.2f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024L -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
    else -> "${bytesPerSecond.coerceAtLeast(0L)} B/s"
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val minutes = safe / 60
    val remainSeconds = safe % 60
    val hours = minutes / 60
    val remainMinutes = minutes % 60
    return when {
        hours > 0 -> "${hours}小时${remainMinutes}分"
        minutes > 0 -> "${minutes}分${remainSeconds}秒"
        else -> "${remainSeconds}秒"
    }
}

private fun DownloadStatus?.downloadStatusLabel(): String = when (this) {
    DownloadStatus.QUEUED -> "排队中"
    DownloadStatus.RUNNING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.FAILED -> "失败，请查看原因并重试"
    DownloadStatus.DONE -> "已导入本地模型"
    null -> "准备中"
}
